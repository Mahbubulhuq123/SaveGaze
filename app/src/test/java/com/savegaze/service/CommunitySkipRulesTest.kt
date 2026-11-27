package com.savegaze.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunitySkipRulesTest {

    @Test
    fun normalizesMovieAndSeriesFileOrStreamTitlesCleanly() {
        val oppenheimer = CommunitySkipRules.normalizeTitle("Oppenheimer.2023.1080p.BluRay.x264-GROUP.mkv")
        assertEquals("Oppenheimer", oppenheimer.baseTitle)
        assertEquals(2023, oppenheimer.year)
        assertNull(oppenheimer.episodeTag)

        val wolverine = CommunitySkipRules.normalizeTitle("The Wolverine (2013) [Dual Audio]")
        assertEquals("The Wolverine", wolverine.baseTitle)
        assertEquals(2013, wolverine.year)

        val bodyEp = CommunitySkipRules.normalizeTitle("The.Body.S01E06.720p.WEB-DL")
        assertEquals("The Body", bodyEp.baseTitle)
        assertEquals("S01E06", bodyEp.episodeTag)
        assertEquals("the body|S01E06", CommunitySkipRules.normalizeKey(bodyEp.baseTitle, bodyEp.episodeTag))
    }

    @Test
    fun parsesCleanStreamSkipsJsonFilteringIntimateCategoriesOnly() {
        val json = """
            {
              "version": "1.0.0",
              "imdbId": "tt15398776",
              "totalSkips": 4,
              "skips": [
                {"id": "1", "startMs": 1392670, "endMs": 1487800, "category": "nudity", "severity": "high"},
                {"id": "2", "startMs": 2000000, "endMs": 2010000, "category": "violence", "severity": "high"},
                {"id": "3", "startMs": 4488570, "endMs": 4513630, "category": "sex", "severity": "medium"},
                {"id": "4", "startMs": 4514200, "endMs": 4620290, "category": "nudity", "severity": "high"}
              ]
            }
        """.trimIndent()

        val parsed = CommunitySkipRules.parseCleanStreamSkipsJson(json)
        // Violence excluded; 4488570..4513630 and 4514200..4620290 (gap 570ms <= 1500ms) merged cleanly
        assertEquals(2, parsed.size)
        assertEquals(1392670L, parsed[0].startMs)
        assertEquals(1487800L, parsed[0].endMs)
        assertEquals(4488570L, parsed[1].startMs)
        assertEquals(4620290L, parsed[1].endMs)
    }

    @Test
    fun matchesCatalogCandidatesWithExactAndYearPriority() {
        val candidates = listOf(
            CatalogCandidate("tt0090329", "The Body", 1985),
            CatalogCandidate("tt8014982", "The Body", 2026),
            CatalogCandidate("tt1430132", "The Wolverine", 2013),
            CatalogCandidate("tt3315342", "Logan", 2017)
        )
        assertEquals("tt1430132", CommunitySkipRules.matchCatalogCandidate("The Wolverine", 2013, candidates))
        assertEquals("tt8014982", CommunitySkipRules.matchCatalogCandidate("The Body", 2026, candidates))
        assertNull(CommunitySkipRules.matchCatalogCandidate("Unknown Movie", null, candidates))
    }

    @Test
    fun parsesHumanRangesJsonAndSerializesCustomStoreRoundTrip() {
        val human = CommunitySkipRules.parseCustomTimestampsInput("35:30-37:10, 01:14:48 -> 01:15:13")
        assertEquals(2, human.size)
        assertEquals(2130000L, human[0].startMs)
        assertEquals(2230000L, human[0].endMs)
        assertEquals(4488000L, human[1].startMs)
        assertEquals(4513000L, human[1].endMs)

        val entries = listOf(CustomSkipEntry("the body|S01E06", "The Body (S01E06)", human))
        val json = CommunitySkipRules.serializeCustomStore(entries)
        val roundTrip = CommunitySkipRules.deserializeCustomStore(json)
        assertEquals(1, roundTrip.size)
        assertEquals("the body|S01E06", roundTrip[0].key)
        assertEquals(2, roundTrip[0].segments.size)
        assertEquals(2130000L, roundTrip[0].segments[0].startMs)
    }

    @Test
    fun findsPreemptiveSkipWindowAndComputesTargetPastEnd() {
        val segs = listOf(SkipSegment(startMs = 60_000L, endMs = 95_000L, category = "nudity"))
        assertNull(CommunitySkipRules.findActiveSkipWindow(58_000L, segs))

        val preHit = CommunitySkipRules.findActiveSkipWindow(59_650L, segs)
        assertNotNull(preHit)
        assertEquals(95_400L, preHit!!.targetEndMs)

        val midHit = CommunitySkipRules.findActiveSkipWindow(75_000L, segs)
        assertNotNull(midHit)
        assertTrue(midHit!!.targetEndMs > segs[0].endMs)

        assertNull(CommunitySkipRules.findActiveSkipWindow(95_200L, segs))
    }
}
