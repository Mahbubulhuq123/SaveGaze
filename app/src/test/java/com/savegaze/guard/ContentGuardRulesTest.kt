package com.savegaze.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class ContentGuardRulesTest {

    @Test
    fun domainMatchingNormalizesAndMatchesSubdomainsSafely() {
        val active = ContentGuardRules.buildActiveDomains(setOf("https://www.CustomSite.org/path"))
        assertEquals("pornhub.com", ContentGuardRules.isDomainBlocked("m.pornhub.com", active))
        assertEquals("customsite.org", ContentGuardRules.isDomainBlocked("sub.customsite.org", active))
        assertNull(ContentGuardRules.isDomainBlocked("notpornhub.com", active))
        assertNull(ContentGuardRules.isDomainBlocked("google.com", active))
    }

    @Test
    fun keywordMatchingEnforcesStrictWholeWordBoundaries() {
        assertFalse(ContentGuardRules.matchesWholeWordKeyword("data analyst report", "anal"))
        assertTrue(ContentGuardRules.matchesWholeWordKeyword("watch free porn online", "porn"))

        val domains = ContentGuardRules.buildActiveDomains(emptySet())
        val urlHit = ContentGuardRules.checkUrl(
            rawUrl = "https://www.google.com/search?q=free%20hentai%20art",
            blockedDomains = domains,
            customKeywords = emptySet()
        )
        assertTrue(urlHit.blocked)

        val safeScreen = ContentGuardRules.checkVisibleScreenText(
            screenText = "Statistical analysis of urban development in Copenhagen",
            customKeywords = emptySet(),
            blockedDomains = domains
        )
        assertFalse(safeScreen.blocked)

        val customScreen = ContentGuardRules.checkVisibleScreenText(
            screenText = "Streaming Ullu originals now",
            customKeywords = setOf("ullu"),
            blockedDomains = domains
        )
        assertTrue(customScreen.blocked)
    }

    @Test
    fun crossBlockTrackerTriggersFiveMinuteLockoutAtFiveAttemptsInTenMinutes() {
        val base = 1_700_000_000_000L
        var timestamps = emptyList<Long>()
        // Expired event 15 minutes earlier should not count
        timestamps = ContentGuardRules.recordCrossBlockEvent(timestamps, base - 15 * 60_000L).timestamps

        for (i in 0 until 4) {
            val res = ContentGuardRules.recordCrossBlockEvent(timestamps, base + i * 10_000L)
            assertFalse(res.triggeredLockout)
            timestamps = res.timestamps
        }

        val fifth = ContentGuardRules.recordCrossBlockEvent(timestamps, base + 60_000L)
        assertTrue(fifth.triggeredLockout)
        assertEquals(base + 60_000L + ContentGuardRules.LOCKOUT_DURATION_MS, fifth.lockoutUntilMs)
    }

    @Test
    fun masterPasswordAndTamperScreenDetectionWorkAccurately() {
        val hash = ContentGuardRules.hashPassword("Secret123")
        assertTrue(ContentGuardRules.verifyPassword("Secret123", hash))
        assertFalse(ContentGuardRules.verifyPassword("wrong", hash))

        assertTrue(
            ContentGuardRules.isTamperSettingsScreen(
                "com.android.settings",
                "App info SaveGaze Uninstall Force stop"
            )
        )
        assertFalse(
            ContentGuardRules.isTamperSettingsScreen(
                "com.android.settings",
                "Display Brightness Adaptive brightness"
            )
        )
        // Must allow in-app APK update installer prompt while continuing to block uninstall
        assertFalse(
            ContentGuardRules.isTamperSettingsScreen(
                "com.google.android.packageinstaller",
                "SaveGaze Do you want to update this app?"
            )
        )
        assertTrue(
            ContentGuardRules.isTamperSettingsScreen(
                "com.google.android.packageinstaller",
                "SaveGaze Do you want to uninstall this app?"
            )
        )
    }

    @Test
    fun dnsPacketCodecParsesDomainAndBuildsValidNxDomainReply() {
        // Minimal DNS query for "pornhub.com" (QDCOUNT=1)
        val qname = byteArrayOf(
            7, 'p'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'n'.code.toByte(),
            'h'.code.toByte(), 'u'.code.toByte(), 'b'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0, 0, 1, 0, 1 // null + QTYPE A + QCLASS IN
        )
        val dnsQuery = ByteArray(12 + qname.size)
        dnsQuery[0] = 0x12
        dnsQuery[1] = 0x34
        dnsQuery[2] = 0x01 // RD=1
        dnsQuery[5] = 0x01 // QDCOUNT=1
        System.arraycopy(qname, 0, dnsQuery, 12, qname.size)

        val dummyReq = DnsPacketCodec.ParsedDnsPacket(
            ipHeaderLen = 20,
            srcIp = byteArrayOf(10, 10, 10, 2),
            dstIp = byteArrayOf(10, 10, 10, 1),
            srcPort = 51234,
            dstPort = 53,
            dnsPayload = dnsQuery,
            queryDomain = DnsPacketCodec.extractQueryDomain(dnsQuery)
        )
        assertEquals("pornhub.com", dummyReq.queryDomain)

        val nx = DnsPacketCodec.buildNxDomainResponse(dnsQuery)
        assertEquals(0x12.toByte(), nx[0])
        assertEquals(0x34.toByte(), nx[1])
        assertEquals(3, nx[3].toInt() and 0x0F) // RCODE = 3 (NXDOMAIN)

        val ipv4Reply = DnsPacketCodec.wrapUdpResponseIpv4(dummyReq, nx)
        val parsedBack = ByteBuffer.wrap(ipv4Reply)
        assertEquals(ipv4Reply.size, parsedBack.getShort(2).toInt() and 0xFFFF)
        assertEquals(0, DnsPacketCodec.computeIpv4Checksum(ipv4Reply, 0, 20))
        assertNotNull(ipv4Reply)
    }
}
