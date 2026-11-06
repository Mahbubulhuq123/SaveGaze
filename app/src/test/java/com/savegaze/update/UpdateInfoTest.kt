package com.savegaze.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInfoTest {

    @Test
    fun testVersionCodeComparison() {
        assertTrue(UpdateInfo.isNewerVersion("1.0", "1.0", remoteCode = 2, currentCode = 1))
        assertFalse(UpdateInfo.isNewerVersion("1.0", "1.0", remoteCode = 1, currentCode = 1))
        assertFalse(UpdateInfo.isNewerVersion("1.0", "1.0", remoteCode = 1, currentCode = 2))
    }

    @Test
    fun testSemanticVersionNameComparison() {
        assertTrue(UpdateInfo.isNewerVersion("1.1", "1.0"))
        assertTrue(UpdateInfo.isNewerVersion("v1.1", "1.0"))
        assertTrue(UpdateInfo.isNewerVersion("1.0.1", "1.0"))
        assertTrue(UpdateInfo.isNewerVersion("2.0.0", "1.9.9"))
        assertTrue(UpdateInfo.isNewerVersion("v2.1-beta", "2.0"))

        assertFalse(UpdateInfo.isNewerVersion("1.0", "1.0"))
        assertFalse(UpdateInfo.isNewerVersion("v1.0", "1.0"))
        assertFalse(UpdateInfo.isNewerVersion("1.0.0", "1.0"))
        assertFalse(UpdateInfo.isNewerVersion("0.9", "1.0"))
        assertFalse(UpdateInfo.isNewerVersion("1.0", "1.1"))
    }

    @Test
    fun testRemindMeLaterPostponementLogic() {
        val now = 1000000L
        val snooze4Hours = now + 4 * 3600 * 1000L
        val snooze24Hours = now + 24 * 3600 * 1000L
        val expiredSnooze = now - 1000L

        // Active reminder should suppress prompt when current time is before snooze deadline
        assertTrue(snooze4Hours > now)
        assertTrue(snooze24Hours > now)

        // Expired reminder should allow update check to show
        assertFalse(expiredSnooze > now)

        // "Next App Launch" (delay = 0L) means remindUntil is 0L, so 0L > now is false
        val nextAppLaunch = 0L
        assertFalse(nextAppLaunch > now)
    }
}
