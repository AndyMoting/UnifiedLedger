package com.unifiedledger.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.ui.BackupSourceOpenResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** The synthetic bytes the test provider serves (fixed, anonymous, no product data). */
internal val RESTORE_SOURCE_FIXTURE_PAYLOAD: ByteArray =
    "ulbk-restore-source-fixture-0123456789abcdef".encodeToByteArray()

/**
 * P7-06 06.C (D-179; spec `2026-09-25-p7-06-restore-preflight-design.md` section 8.1): on-device
 * verification for the SAF source-port path of `AndroidRestorePreflightPorts.kt`
 * ([AndroidBackupSourcePort]) — until now its branches existed only as app-ui common-test fakes.
 * Driven here through the REAL `ContentResolver`.
 *
 * The test provider ([FixedPayloadRestoreSourceProvider], registered for this androidTest APK in
 * `src/androidTest/AndroidManifest.xml`) serves a FIXED synthetic payload from `openFile` as a real
 * `ParcelFileDescriptor`, so the port's reader is proven to deliver exactly those bytes through the
 * production-shaped closure (`context.contentResolver.openInputStream(uri)`), with the SAF launch
 * posted to the main thread via `Instrumentation.runOnMainSync` — the same threading shape the
 * production poster uses (the `AndroidBackupTargetPort` precedent).
 *
 * Failure branches, covered here with real components (P2-8's distinction, previously only faked in
 * the app-ui common tests): a dismissed SAF choice (a null handle) is `Cancelled`, and a stream open
 * that fails against a REAL ContentResolver (an authority no provider serves) is `LaunchFailed`,
 * not a cancel. No Robolectric: this file only runs as `connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class AndroidRestoreSourcePortInstrumentedTest {
    @Test
    fun theSourcePortDeliversTheProviderPayloadThroughTheRealContentResolver() {
        val port = openablePort(FIXTURE_URI)
        val opened = port.openSource()

        assertTrue("the picked SAF document must open: $opened", opened is BackupSourceOpenResult.Opened)
        val reader = (opened as BackupSourceOpenResult.Opened).reader
        val sink = ByteArrayOutputStream()
        // A buffer smaller than the payload on purpose: several reads must be stitched together.
        val buffer = ByteArray(16)
        reader.use {
            // The test provider serves a pipe, which reports no size metadata; the port passes the
            // platform's (absent) size through untouched.
            assertNull("the reader must expose the provider's absent size metadata", it.reportedSize)
            while (true) {
                val read = it.read(buffer)
                if (read <= 0) break
                sink.write(buffer, 0, read)
            }
        }
        assertEquals("the port must deliver exactly the provider's bytes", RESTORE_SOURCE_FIXTURE_PAYLOAD.size, sink.size())
        assertTrue("the delivered bytes must equal the provider payload", RESTORE_SOURCE_FIXTURE_PAYLOAD.contentEquals(sink.toByteArray()))
    }

    @Test
    fun aDismissedSafChoiceIsCancelledNotALaunchFailure() {
        // The production OpenDocument callback hands a null handle on dismissal; the port must map
        // that to Cancelled (P2-8), distinct from a failed launch.
        val port = openablePort(null)

        assertEquals(BackupSourceOpenResult.Cancelled, port.openSource())
    }

    @Test
    fun aStreamOpenFailureOnTheRealContentResolverIsALaunchFailure() {
        // An authority NO provider serves: the real ContentResolver throws, and the port must map
        // that to LaunchFailed (P2-8), never to a user cancel.
        val port = openablePort(MISSING_PROVIDER_URI)

        val opened = port.openSource()
        assertTrue("a failed stream open must be LaunchFailed: $opened", opened is BackupSourceOpenResult.LaunchFailed)
    }

    /**
     * Builds the port in the production shape: the SAF launch is posted to the main thread (real
     * main-thread hop via `Instrumentation.runOnMainSync`), the launch closure "delivers" the SAF
     * result by calling the port's callback (what the `OpenDocument` launcher's callback does), and
     * the stream is opened through the instrumentation target's real `ContentResolver` — the same
     * closure shape as the production composition root.
     */
    private fun openablePort(deliveredUri: Uri?): AndroidBackupSourcePort<Uri> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var portRef: AndroidBackupSourcePort<Uri>? = null
        val port =
            AndroidBackupSourcePort<Uri>(
                postToMainThread = { action -> instrumentation.runOnMainSync { action() } },
                launchOpenDocument = { portRef?.onOpenDocumentResult(deliveredUri) },
                openInputStream = { uri -> instrumentation.targetContext.contentResolver.openInputStream(uri) },
            )
        portRef = port
        return port
    }

    private companion object {
        /** The test provider's authority; declared in `src/androidTest/AndroidManifest.xml`. */
        const val FIXTURE_AUTHORITY: String = "com.unifiedledger.android.p706c.restoresource"

        const val MISSING_PROVIDER_AUTHORITY: String = "com.unifiedledger.android.p706c.missing"

        val FIXTURE_URI: Uri = Uri.parse("content://$FIXTURE_AUTHORITY/source-container")

        val MISSING_PROVIDER_URI: Uri = Uri.parse("content://$MISSING_PROVIDER_AUTHORITY/none")
    }
}

/**
 * A test-only ContentProvider: serves a FIXED synthetic payload
 * ([RESTORE_SOURCE_FIXTURE_PAYLOAD]) from `openFile` as a real `ParcelFileDescriptor` (a pipe
 * prefilled with those bytes, write end closed), so the restore source port exercises the real
 * ContentResolver/SAF-shaped file-access path on device. It holds no product data and exists only
 * inside the androidTest APK.
 */
internal class FixedPayloadRestoreSourceProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { stream -> stream.write(RESTORE_SOURCE_FIXTURE_PAYLOAD) }
        return pipe[0]
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = ANDROID_BACKUP_CONTAINER_MIME

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0
}
