package com.savegaze.guard

import java.nio.ByteBuffer

/**
 * Pure Kotlin IPv4 / UDP / DNS codec for SaveGaze's zero-overhead local VPN domain firewall.
 * Avoids heavyweight native TCP/IP tunnel libraries while maintaining exact IPv4 & UDP checksums.
 */
object DnsPacketCodec {

    data class ParsedDnsPacket(
        val ipHeaderLen: Int,
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val dnsPayload: ByteArray,
        val queryDomain: String?
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
        val domain = extractQueryDomain(dnsPayload)
        return ParsedDnsPacket(
            ipHeaderLen = ihl,
            srcIp = packet.copyOfRange(12, 16),
            dstIp = packet.copyOfRange(16, 20),
            srcPort = srcPort,
            dstPort = dstPort,
            dnsPayload = dnsPayload,
            queryDomain = domain
        )
    }

    /** Extracts the primary DNS QNAME from a DNS request message (`offset = 12`). */
    fun extractQueryDomain(dns: ByteArray): String? {
        if (dns.size < 14) return null
        val qdCount = ((dns[4].toInt() and 0xFF) shl 8) or (dns[5].toInt() and 0xFF)
        if (qdCount < 1) return null

        var pos = 12
        val sb = StringBuilder()
        var safety = 0
        while (pos < dns.size && safety++ < 128) {
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) {
                return if (sb.isNotEmpty()) sb.toString().lowercase() else null
            }
            // Reject compressed pointer in question QNAME or out-of-bounds label
            if ((len and 0xC0) != 0 || pos + 1 + len > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 1..len) {
                sb.append((dns[pos + i].toInt() and 0xFF).toChar())
            }
            pos += 1 + len
        }
        return null
    }

    /** Builds a compact RFC-1035 NXDOMAIN (`RCODE=3`) response preserving ID and question section. */
    fun buildNxDomainResponse(dnsRequest: ByteArray): ByteArray {
        if (dnsRequest.size < 12) return dnsRequest
        // Find end of Question section so response stays strictly RFC compliant
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
        // Copy transaction ID
        resp[0] = dnsRequest[0]
        resp[1] = dnsRequest[1]
        // QR=1, Opcode=0, AA=0, TC=0, RD=(copy), RA=1, Z=0, RCODE=3 (NXDOMAIN)
        val rdBit = dnsRequest[2].toInt() and 0x01
        resp[2] = (0x80 or rdBit).toByte()
        resp[3] = 0x83.toByte()
        // QDCOUNT = 1, ANCOUNT = 0, NSCOUNT = 0, ARCOUNT = 0
        resp[4] = 0
        resp[5] = 1
        // Copy Question
        if (questionEnd > 12) {
            System.arraycopy(dnsRequest, 12, resp, 12, questionEnd - 12)
        }
        return resp
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
        var sum = 0
        // Pseudo header: srcIP + dstIP + zero + proto(17) + udpLen
        for (i in 0 until 4 step 2) {
            sum += ((packet[srcIpOffset + i].toInt() and 0xFF) shl 8) or (packet[srcIpOffset + i + 1].toInt() and 0xFF)
            sum += ((packet[dstIpOffset + i].toInt() and 0xFF) shl 8) or (packet[dstIpOffset + i + 1].toInt() and 0xFF)
        }
        sum += 17
        sum += udpLen

        var i = udpOffset
        val end = udpOffset + udpLen
        while (i < end) {
            val high = packet[i].toInt() and 0xFF
            val low = if (i + 1 < end) packet[i + 1].toInt() and 0xFF else 0
            sum += (high shl 8) or low
            i += 2
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val res = sum.inv() and 0xFFFF
        return if (res == 0) 0xFFFF else res
    }
}
