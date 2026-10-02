package com.unifiedledger.android

import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Exercises the same private staging path used by the maximum-scale driver. */
@RunWith(AndroidJUnit4::class)
class AndroidScalePreflightInstrumentedTest {
    @Test
    fun privateFixtureRoundTrip() {
        val arguments = InstrumentationRegistry.getArguments()
        val sha = checkNotNull(arguments.getString("expectedSha"))
        check(Regex("[0-9a-f]{40}").matches(sha))
        val deadline = checkNotNull(arguments.getString("deadlineElapsedMs")).toLong()
        check(SystemClock.elapsedRealtime() < deadline)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = "unifiedledger-ci-preflight:$sha\n".toByteArray(Charsets.US_ASCII)
        val staged = File(context.filesDir, "scale-fixture/probe.txt").readBytes()
        check(staged.contentEquals(expected))
        val roundTrip = File(context.filesDir, "scale-fixture/probe-roundtrip.txt")
        roundTrip.writeBytes(staged)
        check(roundTrip.readBytes().contentEquals(expected))
        check(SystemClock.elapsedRealtime() < deadline)
        val digest = MessageDigest.getInstance("SHA-256").digest(staged).joinToString("") { "%02x".format(it) }
        val evidence =
            JSONObject()
                .put("schema", 1)
                .put("mode", "preflight")
                .put("sha", sha)
                .put("probeSha256", digest)
                .put("roundTrip", true)
        val target = AtomicFile(File(context.filesDir, "android-preflight-evidence.json"))
        val output = target.startWrite()
        try {
            output.write(evidence.toString().toByteArray())
            target.finishWrite(output)
        } catch (failure: Throwable) {
            target.failWrite(output)
            throw failure
        }
    }
}
