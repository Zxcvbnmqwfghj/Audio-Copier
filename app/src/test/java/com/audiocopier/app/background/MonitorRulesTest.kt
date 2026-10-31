package com.audiocopier.app.background

import com.audiocopier.app.data.ObservedAudio
import org.junit.Assert.*
import org.junit.Test

class MonitorRulesTest {
    private val fresh = ObservedAudio("session", "file", 100, 20, 1000)
    @Test fun baselineAndCompletedFilesNeverBecomeReadyJustBecauseTimePasses() {
        assertFalse(MonitorRules.ready(fresh.copy(baseline = true), 100, 20, 999999))
        assertFalse(MonitorRules.ready(fresh.copy(done = true), 100, 20, 999999))
    }
    @Test fun aNewFileRequiresAStableObservationWindow() {
        assertFalse(MonitorRules.ready(fresh, 100, 20, 2000))
        assertTrue(MonitorRules.ready(fresh, 100, 20, 21000))
        assertFalse(MonitorRules.ready(fresh, 101, 20, 21000))
        assertFalse(MonitorRules.ready(fresh, 100, 21, 21000))
        assertFalse(MonitorRules.ready(fresh.copy(size = 0), 0, 20, 21000))
    }
    @Test fun failedFilesHonorRetryTimeAndBackoffIsBounded() {
        assertFalse(MonitorRules.ready(fresh.copy(retryAfter = 50000), 100, 20, 21000))
        assertTrue(MonitorRules.ready(fresh.copy(retryAfter = 50000), 100, 20, 50000))
        assertEquals(30000L, MonitorRules.retryDelay(0))
        assertEquals(900000L, MonitorRules.retryDelay(100))
    }
    @Test fun aacDiscoveryDoesNotAcceptOtherKnownFormatsWithAMisleadingExtension() {
        assertTrue(MonitorRules.isAac("recording", "audio/aac"))
        assertTrue(MonitorRules.isAac("CALL.AAC", "application/octet-stream"))
        assertFalse(MonitorRules.isAac("call.aac", "audio/mpeg"))
        assertFalse(MonitorRules.isAac("call.mp3", "audio/mpeg"))
        assertFalse(MonitorRules.isAac("call.aac", "application/pdf"))
    }
}
