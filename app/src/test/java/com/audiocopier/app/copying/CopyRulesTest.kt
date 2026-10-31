package com.audiocopier.app.copying

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.FileNotFoundException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class CopyRulesTest {
    @Test fun failedOpenWithFullStorageIsNotMisreportedAsAMissingFile() {
        assertEquals(Problem.SPACE, problemOf(FileNotFoundException("open failed: ENOSPC")))
        assertEquals(Problem.PERMISSION, problemOf(FileNotFoundException("open failed: EACCES")))
        assertEquals(Problem.MISSING, problemOf(FileNotFoundException("missing")))
    }

    @Test fun audioMimeWinsButMisleadingPdfExtensionDoesNot() {
        assertTrue(CopyRules.isAudio("recording", "audio/aac"))
        assertTrue(CopyRules.isAudio("CALL.AAC", "application/octet-stream"))
        assertTrue(CopyRules.isAudio("recording.3gp", "video/3gpp"))
        assertFalse(CopyRules.isAudio("document.aac", "application/pdf"))
        assertFalse(CopyRules.isAudio("unknown.bin", "application/octet-stream"))
    }

    @Test fun numberedNamesPreserveTheAudioExtensionAndAvoidCaseCollisions() {
        assertEquals("call (2).aac", CopyRules.uniqueName("call.aac", setOf("CALL.AAC", "call (1).aac")))
        assertEquals("تسجيل (1).aac", CopyRules.uniqueName("تسجيل.aac", setOf("تسجيل.aac")))
        assertEquals(".hidden (1)", CopyRules.uniqueName(".hidden", setOf(".hidden")))
    }

    @Test fun folderAncestryDoesNotConfuseSimilarNamesOrOtherVolumes() {
        assertTrue(CopyRules.overlapping("primary:Music/PhoneRecord", "primary:Music/PhoneRecord/Copies"))
        assertTrue(CopyRules.overlapping("primary:Music", "primary:Music/PhoneRecord"))
        assertTrue(CopyRules.overlapping("primary:", "primary:Music"))
        assertFalse(CopyRules.overlapping("primary:Music/PhoneRecord", "primary:Music/PhoneRecordBackup"))
        assertFalse(CopyRules.overlapping("primary:Music", "ABCD:Music"))
    }

    @Test fun streamCopyMatchesKnownSha256AndPreservesBytes() {
        val source = "abc".toByteArray()
        val output = ByteArrayOutputStream()
        val result = transferVerifiedLength(ByteArrayInputStream(source), output, 3)
        assertArrayEquals(source, output.toByteArray())
        assertEquals(3L, result.bytes)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.sha256)
    }

    @Test fun shrinkingAndGrowingInputsAreRejected() {
        for (expected in listOf(2L, 4L)) {
            try {
                transferVerifiedLength(ByteArrayInputStream(byteArrayOf(1, 2, 3)), ByteArrayOutputStream(), expected)
                fail("Changing length must not be marked successful")
            } catch (error: CopyProblem) {
                assertEquals(Problem.SOURCE_CHANGED, error.problem)
            }
        }
    }

    @Test fun zeroLengthIsWaitingRatherThanSuccessful() {
        try {
            transferVerifiedLength(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), 0)
            fail("Empty recording is not ready")
        } catch (error: CopyProblem) {
            assertEquals(Problem.NOT_READY, error.problem)
        }
    }

    @Test fun writeFailurePropagatesForTheRepositoryToCleanUp() {
        val failing = object : OutputStream() {
            override fun write(value: Int) { throw IOException("simulated full destination") }
        }
        try {
            transferVerifiedLength(ByteArrayInputStream(byteArrayOf(1)), failing, 1)
            fail("Failed writes must not count as success")
        } catch (error: IOException) {
            assertEquals("simulated full destination", error.message)
        }
    }

    @Test fun largeCopyReportsProgressAndAllowsCancellation() {
        var checks = 0
        try {
            transferVerifiedLength(ByteArrayInputStream(ByteArray(200000)), ByteArrayOutputStream(), 200000) {
                if (++checks == 2) throw InterruptedException("cancel")
            }
            fail("Cancellation checkpoint must stop the copy")
        } catch (_: InterruptedException) {
            assertEquals(2, checks)
        }
    }
}
