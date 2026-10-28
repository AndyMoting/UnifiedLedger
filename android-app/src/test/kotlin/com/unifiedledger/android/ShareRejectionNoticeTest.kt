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
 * probe that makes the refusal observable: one typed code per reachable vector, no id minted, nothing
 * consumed.
 *
 * [ShareIntakeRejection.NOT_A_SHARE] is deliberately absent: it has no producer in the validator
 * today (a registered residual — it stays as the reserved member and is mapped, so the mapping stays
 * total, but no Intent reaches it).
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
    fun everyReachableRejectionVectorIsReportedWithItsOwnTypedCode() {
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
    fun anEntryThatCarriesNoFileIsNotRefusedToTheUser() {
        // Device finding: a component-only start (`am start -n package/.Activity`, i.e. a null action)
        // raised UNSUPPORTED_ACTION and showed "无法接收这次分享" on an ordinary entry. Only an entry
        // that carries a file may be refused to the user's face.
        assertNull(
            intake().rejectionFor(
                snapshot(action = null, streamUri = null, streamScheme = null, readableUris = emptyList()),
            ),
        )
        // A foreign action that DOES carry a readable stream stays a genuine inbound-file attempt.
        assertEquals(
            ShareIntakeRejection.UNSUPPORTED_ACTION,
            intake().rejectionFor(snapshot(action = "android.intent.action.VIEW")),
        )
    }

    @Test
    fun probingARefusalMintsNothingAndConsumesNothing() {
        val subject = intake()
        val refused = snapshot(streamUri = "file:///sdcard/bill.csv", streamScheme = "file")
        assertEquals(ShareIntakeRejection.NON_CONTENT_URI, subject.rejectionFor(refused))
        assertEquals(0, minted, "the pure probe must never mint a delivery id")
        // A probe that consumed an id (a `requestFor`-derived implementation) would report the refusal
        // only once; the probe must be repeatable and side-effect free.
        assertEquals(ShareIntakeRejection.NON_CONTENT_URI, subject.rejectionFor(refused))
        // The same share is still acceptable afterwards: the probe did not consume the identity.
        val accepted = assertIs<ExternalNavigationRequest.OpenImport>(subject.hotReceiptRequest(snapshot()))
        assertEquals("delivery-1", accepted.deliveryId)
    }

    @Test
    fun probingAnAcceptedShareNeverConsumesIt() {
        val subject = intake()
        assertNull(subject.rejectionFor(snapshot()))
        assertNull(subject.rejectionFor(snapshot()))
        assertEquals(0, minted, "probing an accepted snapshot must not mint either")
        val accepted = assertIs<ExternalNavigationRequest.OpenImport>(subject.hotReceiptRequest(snapshot()))
        assertEquals("delivery-1", accepted.deliveryId)
    }

    @Test
    fun theMappingIsTotalAndOneToOne() {
        val mapped = ShareIntakeRejection.values().map { it.toShareRejectionReason() }
        assertEquals(ShareIntakeRejection.values().size, mapped.toSet().size, "two platform codes must never collapse into one")
        assertEquals(ShareRejectionReason.values().toSet(), mapped.toSet(), "every display vector needs one platform code")
        assertTrue(ShareIntakeRejection.NOT_A_SHARE.toShareRejectionReason() == ShareRejectionReason.NOT_A_SHARE)
    }
}
