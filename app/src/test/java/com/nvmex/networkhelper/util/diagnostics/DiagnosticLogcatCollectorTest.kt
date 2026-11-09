package com.nvmex.networkhelper.util.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticLogcatCollectorTest {
    @Test fun throwableLinesProduceOneReportRatherThanOnePerFrame() {
        val collector = DiagnosticLogcatCollector()
        collector.accept("MyFeature", "12", "E", "read failed", 1000)
        collector.accept("MyFeature", "12", "E", "java.io.IOException: denied", 1001)
        repeat(50) { collector.accept("MyFeature", "12", "E", "\tat app.Reader.read(Reader.kt:$it)", 1002L + it) }
        val result = collector.flush(2000)
        assertEquals(1, result.size); assertEquals(DiagnosticLevel.ERROR, result.single().level)
        assertTrue(result.single().detail.contains("IOException")); assertTrue(result.single().detail.contains("Reader.kt:49"))
    }
    @Test fun vendorReflectionNoiseAndOrphanFramesNeverBecomeAutomaticErrors() {
        val collector = DiagnosticLogcatCollector()
        collector.accept("SettingTrigger", "1", "E", "getStaticObjectField error", 1000)
        collector.accept("SettingTrigger", "1", "E", "java.lang.NoSuchFieldException: mContentExtensionEnabled", 1001)
        collector.accept("SettingTrigger", "1", "E", "\tat miui.contentcatcher.Trigger.run(Trigger.java:3)", 1002)
        collector.accept("Other", "2", "E", "\tat orphan.Frame.run(Frame.java:4)", 1003)
        val result = collector.flush(2000)
        assertEquals(2, result.size)
        assertEquals(DiagnosticLevel.INFO, result.first { it.tag == "SettingTrigger" }.level)
        assertEquals(DiagnosticLevel.WARNING, result.first { it.tag == "Other" }.level)
    }
    @Test fun differentThreadsDoNotMixExceptionStacks() {
        val collector = DiagnosticLogcatCollector()
        collector.accept("Feature", "1", "E", "first", 1000); collector.accept("Feature", "2", "E", "second", 1000)
        collector.accept("Feature", "2", "E", "\tat second.Frame.run(Source.kt:2)", 1001)
        val result = collector.flush(2000)
        assertEquals("", result.first { it.message == "first" }.detail)
        assertTrue(result.first { it.message == "second" }.detail.contains("second.Frame"))
    }
    @Test fun limiterSurvivesRestartAndReleasesExpiredAdmissions() {
        val first = DiagnosticRateLimiter()
        repeat(20) { assertTrue(first.admit(it.toString(16).padStart(64, '0'), 1000000L + it)) }
        val restarted = DiagnosticRateLimiter(); restarted.restore(first.snapshot(), 1001000)
        assertFalse(restarted.admit("f".repeat(64), 1001000))
        assertTrue(restarted.admit("f".repeat(64), 5000000))
        assertFalse(restarted.admit("f".repeat(64), 5000001))
    }
}
