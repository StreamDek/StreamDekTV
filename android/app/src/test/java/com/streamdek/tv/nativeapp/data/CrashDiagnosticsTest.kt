package com.streamdek.tv.nativeapp.data

import org.junit.Assert.*
import org.junit.Test

class CrashDiagnosticsTest {
    @Test fun inputContextOnlyFollowsItsOwnFailure() {
        val error = IllegalStateException()
        CrashInputDiagnostics.record(error, "down", 1, 3)
        assertEquals("down", crashDiagnostics(RuntimeException(error))["inputKey"])
        assertFalse(crashDiagnostics(IllegalStateException()).containsKey("inputKey"))
    }
    @Test fun keepsObfuscatedAndFrameworkFramesWithoutMessagesOrPaths() {
        val error = IllegalStateException("https://private.invalid/?token=DO_NOT_RECORD")
        error.stackTrace = arrayOf(StackTraceElement("a.b", "c", "/private/path", 38), StackTraceElement("androidx.compose.Focus", "dispatch", "Focus.kt", 10))
        val report = crashDiagnostics(error).toString()
        assertTrue(report.contains("a.b.c:38"))
        assertTrue(report.contains("androidx.compose.Focus.dispatch:10"))
        assertFalse(report.contains("DO_NOT_RECORD"))
        assertFalse(report.contains("/private/path"))
    }

    @Test fun boundsCausesAndFramesAndRetainsRoot() {
        var error: Throwable = ClassNotFoundException("private class message")
        repeat(8) { error = IllegalStateException("wrapper", error) }
        error.stackTrace = Array(100) { StackTraceElement("a", "b", "c", it) }
        val causes = crashDiagnostics(error)["causes"] as List<*>
        assertEquals(4, causes.size)
        assertEquals(24, ((causes.first() as Map<*, *>)["frames"] as List<*>).size)
        assertEquals("java.lang.ClassNotFoundException", (causes.last() as Map<*, *>)["exceptionClass"])
    }
}
