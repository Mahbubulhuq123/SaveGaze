package com.savegaze.guard

import java.nio.ByteBuffer

/**
 * Pure Kotlin IPv4 / UDP / TCP-RST / DNS codec for SaveGaze's zero-overhead local VPN domain firewall.
 */
object DnsPacketCodec {

    data class ParsedDnsPacket(
        val ipHeaderLen: Int,
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val dnsPayload: ByteArray,
        val queryDomain: String?,
        val queryType: Int = 1
    )

    data class ParsedTcpSynPacket(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val seqNum: Long
    )

    /**
     * Parses an IPv4 UDP packet destined to port 53. Returns null for non-IPv4 / non-UDP / non-DNS packets.
     */
    fun parseIpv4UdpDns(packet: ByteArray, length: Int): ParsedDnsPacket? {
        if (length < 28) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return null // UDP only

        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return null

        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val totalIpLen = minOf(length, ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF))
        val dnsOffset = ihl + 8
        if (totalIpLen <= dnsOffset + 12) return null

        val dnsPayload = packet.copyOfRange(dnsOffset, totalIpLen)
        val (domain, qtype) = extractQueryDomainAndType(dnsPayload)
        return ParsedDnsPacket(
            ipHeaderLen = ihl,
            srcIp = packet.copyOfRange(12, 16),
            dstIp = packet.copyOfRange(16, 20),
            srcPort = srcPort,
            dstPort = dstPort,
            dnsPayload = dnsPayload,
            queryDomain = domain,
            queryType = qtype
        )
    }

    /**
     * Parses an IPv4 TCP SYN packet (used to fast-reject Private DNS DoT :853 / TCP :53 with TCP RST
     * so Android's DnsResolver immediately uses UDP :53 instead of timing out).
     */
    fun parseIpv4TcpSyn(packet: ByteArray, length: Int): ParsedTcpSynPacket? {
        if (length < 40) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 20) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 6) return null // TCP only

        val flags = packet[ihl + 13].toInt() and 0xFF
        val isSyn = (flags and 0x02) != 0 && (flags and 0x04) == 0 && (flags and 0x10) == 0
        if (!isSyn) return null

        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        val seqNum = ((packet[ihl + 4].toLong() and 0xFFL) shl 24) or
            ((packet[ihl + 5].toLong() and 0xFFL) shl 16) or
            ((packet[ihl + 6].toLong() and 0xFFL) shl 8) or
            (packet[ihl + 7].toLong() and 0xFFL)

        return ParsedTcpSynPacket(
            srcIp = packet.copyOfRange(12, 16),
            dstIp = packet.copyOfRange(16, 20),
            srcPort = srcPort,
            dstPort = dstPort,
            seqNum = seqNum
        )
    }

    /** Builds a valid IPv4 TCP RST+ACK reply for [syn] to fail fast on DoT :853 / TCP :53 probes. */
    fun buildTcpRstResponseIpv4(syn: ParsedTcpSynPacket): ByteArray {
        val totalLen = 40 // 20B IPv4 + 20B TCP
        val out = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(out)

        // IPv4 Header
        out[0] = 0x45.toByte()
        out[1] = 0x00
        buf.putShort(2, totalLen.toShort())
        buf.putShort(4, (System.nanoTime() and 0xFFFF).toShort())
        out[6] = 0x40.toByte()
        out[7] = 0x00
        out[8] = 64.toByte()
        out[9] = 6.toByte() // TCP
        buf.putShort(10, 0)
        System.arraycopy(syn.dstIp, 0, out, 12, 4)
        System.arraycopy(syn.srcIp, 0, out, 16, 4)
        buf.putShort(10, computeIpv4Checksum(out, 0, 20).toShort())

        // TCP Header
        buf.putShort(20, syn.dstPort.toShort())
        buf.putShort(22, syn.srcPort.toShort())
        buf.putInt(24, 0) // SEQ = 0
        buf.putInt(28, ((syn.seqNum + 1L) and 0xFFFFFFFFL).toInt()) // ACK = syn.seq + 1
        out[32] = 0x50.toByte() // Data offset = 5 (20 bytes)
        out[33] = 0x14.toByte() // RST + ACK
        buf.putShort(34, 0)
        buf.putShort(36, 0) // Checksum placeholder
        buf.putShort(38, 0)

        val tcpChecksum = computeTransportChecksum(out, 12, 16, 20, 20, proto = 6)
        buf.putShort(36, tcpChecksum.toShort())
        return out
    }

    fun extractQueryDomain(dns: ByteArray): String? = extractQueryDomainAndType(dns).first

    fun extractQueryDomainAndType(dns: ByteArray): Pair<String?, Int> {
        if (dns.size < 14) return null to 1
        val qdCount = ((dns[4].toInt() and 0xFF) shl 8) or (dns[5].toInt() and 0xFF)
        if (qdCount < 1) return null to 1

        var pos = 12
        val sb = StringBuilder()
        var safety = 0
        while (pos < dns.size && safety++ < 128) {
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) {
                pos += 1
                val qtype = if (pos + 2 <= dns.size) {
                    ((dns[pos].toInt() and 0xFF) shl 8) or (dns[pos + 1].toInt() and 0xFF)
                } else {
                    1
                }
                return (if (sb.isNotEmpty()) sb.toString().lowercase() else null) to qtype
            }
            if ((len and 0xC0) != 0 || pos + 1 + len > dns.size) return null to 1
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 1..len) {
                sb.append((dns[pos + i].toInt() and 0xFF).toChar())
            }
            pos += 1 + len
        }
        return null to 1
    }

    /** Builds a compact RFC-1035 NXDOMAIN (`RCODE=3`) response preserving ID and question section. */
    fun buildNxDomainResponse(dnsRequest: ByteArray): ByteArray = buildErrorResponse(dnsRequest, rcode = 3)

    /** Builds an RFC-1035 SERVFAIL (`RCODE=2`) response so browsers never stall if offline. */
    fun buildServFailResponse(dnsRequest: ByteArray): ByteArray = buildErrorResponse(dnsRequest, rcode = 2)

    private fun buildErrorResponse(dnsRequest: ByteArray, rcode: Int): ByteArray {
        if (dnsRequest.size < 12) return dnsRequest
        var pos = 12
        while (pos < dnsRequest.size) {
            val len = dnsRequest[pos].toInt() and 0xFF
            if (len == 0) {
                pos += 1
                break
            }
            if ((len and 0xC0) != 0) {
                pos += 2
                break
            }
            pos += 1 + len
        }
        val questionEnd = (pos + 4).coerceAtMost(dnsRequest.size)
        val resp = ByteArray(questionEnd)
        resp[0] = dnsRequest[0]
        resp[1] = dnsRequest[1]
        val rdBit = dnsRequest[2].toInt() and 0x01
        resp[2] = (0x80 or rdBit).toByte()
        resp[3] = (0x80 or (rcode and 0x0F)).toByte()
        resp[4] = 0
        resp[5] = 1
        if (questionEnd > 12) {
            System.arraycopy(dnsRequest, 12, resp, 12, questionEnd - 12)
        }
        return resp
    }

    /** Clones a cached DNS wire response while patching its 2-byte Transaction ID to match [dnsRequest]. */
    fun copyWithTransactionId(cachedResponse: ByteArray, dnsRequest: ByteArray): ByteArray {
        if (cachedResponse.size < 2 || dnsRequest.size < 2) return cachedResponse
        val copy = cachedResponse.copyOf()
        copy[0] = dnsRequest[0]
        copy[1] = dnsRequest[1]
        return copy
    }

    /** Frames a valid IPv4 + UDP reply swapping source and destination from [requestPacket]. */
    fun wrapUdpResponseIpv4(requestPacket: ParsedDnsPacket, dnsResponse: ByteArray): ByteArray {
        val udpLen = 8 + dnsResponse.size
        val totalLen = 20 + udpLen
        val out = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(out)

        // IPv4 Header
        out[0] = 0x45.toByte()
        out[1] = 0x00
        buf.putShort(2, totalLen.toShort())
        buf.putShort(4, (System.nanoTime() and 0xFFFF).toShort())
        out[6] = 0x40.toByte() // Don't fragment
        out[7] = 0x00
        out[8] = 64.toByte()   // TTL
        out[9] = 17.toByte()   // UDP
        buf.putShort(10, 0)    // Checksum placeholder
        System.arraycopy(requestPacket.dstIp, 0, out, 12, 4)
        System.arraycopy(requestPacket.srcIp, 0, out, 16, 4)

        val ipChecksum = computeIpv4Checksum(out, 0, 20)
        buf.putShort(10, ipChecksum.toShort())

        // UDP Header
        buf.putShort(20, requestPacket.dstPort.toShort())
        buf.putShort(22, requestPacket.srcPort.toShort())
        buf.putShort(24, udpLen.toShort())
        buf.putShort(26, 0)
        System.arraycopy(dnsResponse, 0, out, 28, dnsResponse.size)

        val udpChecksum = computeUdpChecksum(out, 12, 16, 20, udpLen)
        buf.putShort(26, udpChecksum.toShort())

        return out
    }

    fun computeIpv4Checksum(data: ByteArray, offset: Int, len: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + len) {
            val high = data[i].toInt() and 0xFF
            val low = if (i + 1 < offset + len) data[i + 1].toInt() and 0xFF else 0
            sum += (high shl 8) or low
            i += 2
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    fun computeUdpChecksum(
        packet: ByteArray,
        srcIpOffset: Int,
        dstIpOffset: Int,
        udpOffset: Int,
        udpLen: Int
    ): Int {
        val res = computeTransportChecksum(packet, srcIpOffset, dstIpOffset, udpOffset, udpLen, proto = 17)
        return if (res == 0) 0xFFFF else res
    }

    fun computeTransportChecksum(
        packet: ByteArray,
        srcIpOffset: Int,
        dstIpOffset: Int,
        transportOffset: Int,
        transportLen: Int,
        proto: Int
    ): Int {
        var sum = 0
        for (i in 0 until 4 step 2) {
            sum += ((packet[srcIpOffset + i].toInt() and 0xFF) shl 8) or (packet[srcIpOffset + i + 1].toInt() and 0xFF)
            sum += ((packet[dstIpOffset + i].toInt() and 0xFF) shl 8) or (packet[dstIpOffset + i + 1].toInt() and 0xFF)
        }
        sum += proto
        sum += transportLen

        var i = transportOffset
        val end = transportOffset + transportLen
        while (i < end) {
            val high = packet[i].toInt() and 0xFF
            val low = if (i + 1 < end) packet[i + 1].toInt() and 0xFF else 0
            sum += (high shl 8) or low
            i += 2
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }
}
