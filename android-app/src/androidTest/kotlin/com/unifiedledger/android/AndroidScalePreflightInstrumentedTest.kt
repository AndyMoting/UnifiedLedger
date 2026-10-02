package com.unifiedledger.android

import android.net.Uri
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.ui.ImportFilePickResultChannel
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Proves existing startup storage, production reopen, and private staging. */
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
        val oracle = AndroidScaleOracle(context) { check(SystemClock.elapsedRealtime() < deadline) }
        // This read-only observation MUST precede any production opener. Otherwise
        // FreshInstall could create a missing ledger and conceal interrupted startup.
        val existing =
            oracle.read { database, generation ->
                database.rawQuery("SELECT DISTINCT ledger_id FROM catalog_version", null).use { cursor ->
                    check(cursor.moveToFirst()) { "existing ledger identity missing" }
                    val ledger = cursor.getString(0)
                    check(!cursor.moveToNext()) { "multiple existing ledger identities" }
                    ledger to generation
                }
            }

        fun observe(ledger: String): JSONObject {
            check(ledger == existing.first) { "ledger changed on open" }
            val snapshot = oracle.snapshot(ledger, compareReadPath = true)
            check(snapshot.generation == existing.second) { "generation changed on open" }
            check(snapshot.rows.isEmpty() && snapshot.relations == 0)
            oracle.zeroEconomics(snapshot)
            return JSONObject()
                .put("ledger", ledger)
                .put("generation", snapshot.generation)
                .put("transactions", snapshot.counts.getValue("ledger_transaction"))
                .put("postings", snapshot.counts.getValue("posting"))
        }

        fun openAndObserve(): JSONObject {
            val graph =
                openAndroidStableStorageLedger(
                    context,
                    AndroidImportFilePickPort<Uri>(
                        launchOpenDocument = { error("preflight must not launch UI") },
                        resolveMetadata = { error("no pick in preflight") },
                        openInputStream = { error("no pick in preflight") },
                        onResult = { error("no pick in preflight") },
                    ),
                    ImportFilePickResultChannel(),
                )
            try {
                return observe(graph.facade.ledgerId.value)
            } finally {
                graph.close()
            }
        }

        val beforeOpen = observe(existing.first)
        val firstOpen = openAndObserve()
        val reopened = openAndObserve()
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
                .put("schema", 2)
                .put("mode", "preflight")
                .put("sha", sha)
                .put("probeSha256", digest)
                .put("roundTrip", true)
                .put("pointerObservedBeforeOpen", true)
                .put("beforeOpen", beforeOpen)
                .put("firstOpen", firstOpen)
                .put("reopen", reopened)
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
