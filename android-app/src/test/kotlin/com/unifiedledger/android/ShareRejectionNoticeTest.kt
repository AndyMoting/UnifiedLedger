package com.unifiedledger.android

import android.content.Intent
import com.unifiedledger.ui.ExternalNavigationRequest
import com.unifiedledger.ui.IMPORT_FILE_PICK_MAX_READ_BYTES
import com.unifiedledger.ui.ShareRejectionReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-09 09.A refused-share evidence (spec section 2.3; ACC-D-SHARE-02). The validator already returned
 * a typed [ShareIntakeRejection], but the request path only exposed `null`, so the Activity could not
 * tell a refusal from an ordinary launch and the screen stayed untouched. These assertions pin the
 * probe that makes the refusal observable: one typed code per vector, no id minted, nothing consumed.
 */
class ShareRejectionNoticeTest {
    private var minted = 0

    private fun intake() = ProcessInboundNavigationIntake { "delivery-${++minted}" }

    private fun snapshot(
        action: String? = Intent.ACTION_SEND,
        mimeType: String? = "text/csv",
        streamUri: String? = "content://synthetic-provider/bill.csv",
        streamScheme: String? = "content",
        clipDataUris: List<String> = emptyList(),
        clipDataScheme: String? = if (clipDataUris.size == 1) "content" else null,
        readableUris: List<String> = listOf("content://synthetic-provider/bill.csv"),
        declaredSizeBytes: Long? = null,
        hasUnexpectedStreamExtra: Boolean = false,
        streamExtraIsList: Boolean = false,
    ) = ShareIntentSnapshot(
        action = action,
        mimeType = mimeType,
        streamUri = streamUri,
        streamScheme = streamScheme,
        clipDataUris = clipDataUris,
        clipDataScheme = clipDataScheme,
        readableUris = readableUris,
        declaredSizeBytes = declaredSizeBytes,
        hasUnexpectedStreamExtra = hasUnexpectedStreamExtra,
        streamExtraIsList = streamExtraIsList,
    )

    @Test
    fun everyRejectionVectorIsReportedWithItsOwnTypedCode() {
        val cases =
            listOf(
                Triple("a non-SEND action", snapshot(action = "android.intent.action.VIEW"), ShareIntakeRejection.UNSUPPORTED_ACTION),
                Triple("SEND_MULTIPLE", snapshot(action = Intent.ACTION_SEND_MULTIPLE), ShareIntakeRejection.MULTIPLE_STREAMS),
                Triple("an unusable stream extra", snapshot(hasUnexpectedStreamExtra = true), ShareIntakeRejection.MALFORMED_EXTRAS),
                Triple("a stream list", snapshot(streamExtraIsList = true), ShareIntakeRejection.MULTIPLE_STREAMS),
                Triple(
                    "no stream at all",
                    snapshot(streamUri = null, streamScheme = null, readableUris = emptyList()),
                    ShareIntakeRejection.MISSING_STREAM,
                ),
                Triple(
                    "two ClipData items",
                    snapshot(
                        clipDataUris = listOf("content://synthetic-provider/a.csv", "content://synthetic-provider/b.csv"),
                        readableUris = listOf("content://synthetic-provider/a.csv"),
                    ),
                    ShareIntakeRejection.MULTIPLE_STREAMS,
                ),
                Triple(
                    "a conflicting ClipData stream",
                    snapshot(clipDataUris = listOf("content://synthetic-provider/other.csv")),
                    ShareIntakeRejection.CONFLICTING_STREAM,
                ),
                Triple(
                    "a file:// stream",
                    snapshot(streamUri = "file:///sdcard/bill.csv", streamScheme = "file"),
                    ShareIntakeRejection.NON_CONTENT_URI,
                ),
                Triple(
                    "an unreadable stream",
                    snapshot(readableUris = emptyList()),
                    ShareIntakeRejection.STREAM_NOT_READABLE,
                ),
                Triple(
                    "a declared size over the bound",
                    snapshot(declaredSizeBytes = IMPORT_FILE_PICK_MAX_READ_BYTES + 1),
                    ShareIntakeRejection.DECLARED_SIZE_EXCEEDS_LIMIT,
                ),
            )
        cases.forEach { (label, received, expected) ->
            assertEquals(expected, intake().rejectionFor(received), "$label must be reported as $expected")
        }
    }

    @Test
    fun anAcceptedShareHasNoRejection() {
        assertNull(intake().rejectionFor(snapshot()))
    }

    @Test
    fun anOrdinaryMainLaunchIsNotARejection() {
        // A launcher tap is not an inbound entry at all: it must not raise a refusal strip.
        assertNull(intake().rejectionFor(snapshot(action = Intent.ACTION_MAIN)))
    }

    @Test
    fun probingARefusalMintsNothingAndConsumesNothing() {
        val subject = intake()
        assertEquals(
            ShareIntakeRejection.NON_CONTENT_URI,
            subject.rejectionFor(snapshot(streamUri = "file:///sdcard/bill.csv", streamScheme = "file")),
        )
        assertEquals(0, minted, "the pure probe must never mint a delivery id")
        // The same share is still acceptable afterwards: the probe did not consume the identity.
        val accepted = assertIs<ExternalNavigationRequest.OpenImport>(subject.hotReceiptRequest(snapshot()))
        assertEquals("delivery-1", accepted.deliveryId)
    }

    @Test
    fun theMappingIsTotalAndOneToOne() {
        val mapped = ShareIntakeRejection.values().map { it.toShareRejectionReason() }
        assertEquals(ShareIntakeRejection.values().size, mapped.toSet().size, "two platform codes must never collapse into one")
        assertEquals(ShareRejectionReason.values().toSet(), mapped.toSet(), "every display vector needs one platform code")
    }

    @Test
    fun aReportedRefusalCarriesNoInboundPayloadInItsName() {
        // The trace/copy identities are enum NAMES: neither a Uri nor a file name can travel through them.
        ShareIntakeRejection.values().forEach { rejection ->
            val reason = rejection.toShareRejectionReason()
            assertTrue(reason.name.none { it == '/' || it == '.' } && !reason.name.contains("content"))
        }
    }
}
