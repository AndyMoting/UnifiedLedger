package com.unifiedledger.application

import kotlinx.datetime.YearMonth
import java.io.File
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-07 A07 "device time zone changes" leg (plan section 7.3 line 252; D-237 item 4; budget
 * design spec section 6.4 item 1): the reporting zone is the frozen constant
 * `Asia/Shanghai`, never the host default, so an instant's Shanghai month does not move when
 * the device zone changes.
 *
 * Two independent legs, because `MonthlyBuckets.REPORT_TIME_ZONE` is a `val` of a Kotlin
 * `object` and therefore initializes exactly once per JVM:
 *  1. this class pins the constant identity `Asia/Shanghai` and then re-asserts the same
 *     LITERAL expected values under three different `java.util.TimeZone` defaults (UTC,
 *     Pacific/Kiritimati, America/Los_Angeles). `java.time.ZoneId.systemDefault()` follows
 *     `TimeZone.getDefault()`, so a per-call system-default lookup would move the months and
 *     turn these assertions red.
 *  2. [aChildJvmWithANonShanghaiDefaultInitializesTheFrozenZoneAndKeepsLiteralMonths] starts a
 *     fresh JVM with `-Duser.timezone=UTC`, so the object is initialized for the FIRST time
 *     under a non-Shanghai default there, and the child asserts the same literal values.
 *
 * The previous default zone is restored in a `finally` block so the rest of the JVM task is
 * unaffected. The child JVM is killed on timeout and a start failure is an assertion failure,
 * never a skipped (green) test.
 */
class MonthlyBucketsTimeZoneJvmTest {
    @Test
    fun theFrozenReportingZoneKeepsShanghaiMonthsWhenTheDeviceZoneChanges() {
        assertEquals("Asia/Shanghai", MonthlyBuckets.REPORT_TIME_ZONE.id)
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("UTC", "Pacific/Kiritimati", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertLiteralShanghaiMonths()
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun aChildJvmWithANonShanghaiDefaultInitializesTheFrozenZoneAndKeepsLiteralMonths() {
        // Cross-platform launcher: the Windows binary carries the .exe suffix, the POSIX one does not.
        val javaHome = File(System.getProperty("java.home"))
        val javaExecutable =
            listOf(File(javaHome, "bin/java"), File(javaHome, "bin/java.exe")).firstOrNull { it.isFile }
                ?: throw AssertionError("the child JVM executable was not found under $javaHome/bin")
        val process =
            try {
                ProcessBuilder(
                    javaExecutable.path,
                    "-Duser.timezone=UTC",
                    "-cp",
                    System.getProperty("java.class.path"),
                    CHILD_ENTRY_POINT,
                ).redirectErrorStream(true).start()
            } catch (failure: java.io.IOException) {
                throw AssertionError("the child JVM could not be started", failure)
            }
        val finished = process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(
            finished,
            "the child JVM did not finish within $CHILD_TIMEOUT_SECONDS s; output:\n$output",
        )
        assertEquals(0, process.exitValue(), "the child JVM must exit 0; output:\n$output")
        assertTrue(output.contains("OK"), "the child JVM must report OK; output:\n$output")
    }

    private companion object {
        /**
         * The facade class of this file: the top-level [main] compiles to
         * `com.unifiedledger.application.MonthlyBucketsTimeZoneJvmTestKt`, because the file's
         * name is `MonthlyBucketsTimeZoneJvmTest.kt`.
         */
        const val CHILD_ENTRY_POINT: String = "com.unifiedledger.application.MonthlyBucketsTimeZoneJvmTestKt"

        /** Generous for a cold JVM start; a hang is a loud failure, not a skip. */
        const val CHILD_TIMEOUT_SECONDS: Long = 120L
    }
}

/**
 * Child-JVM entry point for the first-initialization leg. The parent starts this class with
 * `-Duser.timezone=UTC`, so `MonthlyBuckets` is touched for the first time here under a
 * non-Shanghai default. It asserts the same literal YearMonth/Instant values as the parent and
 * prints `OK`; any mismatch propagates as a non-zero exit.
 */
fun main() {
    val deviceZone = TimeZone.getDefault()
    check(deviceZone.id != "Asia/Shanghai") {
        "the child JVM must start on a non-Shanghai default zone, was ${deviceZone.id}"
    }
    check(deviceZone.rawOffset == 0) {
        "the child JVM must start on the requested zero-offset default zone, was ${deviceZone.id}"
    }
    assertLiteralShanghaiMonths()
    println("OK")
}

/** The frozen Shanghai month boundary, spelled as literal expectations shared by both legs. */
private fun assertLiteralShanghaiMonths() {
    assertEquals(
        YearMonth(2026, 3),
        MonthlyBuckets.bucketKey(Instant.parse("2026-03-31T15:59:59.999999999Z")),
        "the last nanosecond of March must stay in March",
    )
    assertEquals(
        YearMonth(2026, 4),
        MonthlyBuckets.bucketKey(Instant.parse("2026-03-31T16:00:00Z")),
        "the Shanghai month boundary must not follow the device zone",
    )
    assertEquals(
        Instant.parse("2026-02-28T16:00:00Z"),
        MonthlyBuckets.monthStart(YearMonth(2026, 3)),
        "the Shanghai month start must not follow the device zone",
    )
    assertEquals(
        YearMonth(2028, 2),
        MonthlyBuckets.bucketKey(Instant.parse("2028-02-29T15:59:59.999999999Z")),
        "the last nanosecond of leap February must stay in February",
    )
    assertEquals(
        YearMonth(2028, 3),
        MonthlyBuckets.bucketKey(Instant.parse("2028-02-29T16:00:00Z")),
        "the leap-February boundary must not follow the device zone",
    )
}
