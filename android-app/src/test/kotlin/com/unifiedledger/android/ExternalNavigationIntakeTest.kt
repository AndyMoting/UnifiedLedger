package com.unifiedledger.android

import android.content.Intent
import com.unifiedledger.ui.ExternalNavigationRequest
import com.unifiedledger.ui.IMPORT_FILE_PICK_MAX_READ_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-09 09.A share-intake evidence (spec sections 2.3/4; D-225 Q19): the validation face is a PURE
 * function over a [ShareIntentSnapshot], so every rejection vector runs on a plain JVM without
 * Robolectric (the `AndroidImportFilePickPortTest` injection pattern). The snapshot's fields are
 * plain values, so the test drives the decision table directly.
 *
 * The [Intent] constants (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`, `ACTION_MAIN`) are compile-time
 * string constants on the android.jar stub, so referencing them needs no device.
 */
class ExternalNavigationIntakeTest {
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
    )

    private fun rejection(result: ExternalNavigationIntakeResult): ShareIntakeRejection =
        assertIs<ExternalNavigationIntakeResult.Rejected>(result).code

    @Test
    fun aSingleReadableContentStreamIsAcceptedWithTheGivenDeliveryId() {
        val accepted = assertIs<ExternalNavigationIntakeResult.Accepted>(validateShareIntake(snapshot(), deliveryId = "delivery-1"))
        val request = assertIs<ExternalNavigationRequest.OpenImport>(accepted.request)
        assertEquals("delivery-1", request.deliveryId)
    }

    @Test
    fun aClipDataOnlyStreamIsAcceptedWhenItIsReadable() {
        val accepted =
            assertIs<ExternalNavigationIntakeResult.Accepted>(
                validateShareIntake(
                    snapshot(streamUri = null, streamScheme = null, clipDataUris = listOf("content://synthetic-provider/bill.csv")),
                    deliveryId = "delivery-clip",
                ),
            )
        assertEquals("delivery-clip", assertIs<ExternalNavigationRequest.OpenImport>(accepted.request).deliveryId)
    }

    @Test
    fun actionSendMultipleIsRejected() {
        assertEquals(ShareIntakeRejection.MULTIPLE_STREAMS, rejection(validateShareIntake(snapshot(action = Intent.ACTION_SEND_MULTIPLE), "d")))
    }

    @Test
    fun aNonSendActionIsRejected() {
        assertEquals(ShareIntakeRejection.UNSUPPORTED_ACTION, rejection(validateShareIntake(snapshot(action = "android.intent.action.VIEW"), "d")))
        assertEquals(ShareIntakeRejection.UNSUPPORTED_ACTION, rejection(validateShareIntake(snapshot(action = null), "d")))
    }

    @Test
    fun missingStreamIsRejected() {
        assertEquals(
            ShareIntakeRejection.MISSING_STREAM,
            rejection(validateShareIntake(snapshot(streamUri = null, streamScheme = null, clipDataUris = emptyList()), "d")),
        )
    }

    @Test
    fun conflictingStreamBetweenExtraAndClipDataIsRejected() {
        assertEquals(
            ShareIntakeRejection.CONFLICTING_STREAM,
            rejection(
                validateShareIntake(
                    snapshot(clipDataUris = listOf("content://synthetic-provider/other.csv")),
                    "d",
                ),
            ),
        )
    }

    @Test
    fun multipleClipDataItemsAreRejected() {
        assertEquals(
            ShareIntakeRejection.MULTIPLE_STREAMS,
            rejection(
                validateShareIntake(
                    snapshot(
                        streamUri = null,
                        streamScheme = null,
                        clipDataUris = listOf("content://p/a.csv", "content://p/b.csv"),
                    ),
                    "d",
                ),
            ),
        )
    }

    @Test
    fun nonContentSchemesAreRejected() {
        assertEquals(
            ShareIntakeRejection.NON_CONTENT_URI,
            rejection(validateShareIntake(snapshot(streamUri = "file:///synthetic/bill.csv", streamScheme = "file"), "d")),
        )
        assertEquals(
            ShareIntakeRejection.NON_CONTENT_URI,
            rejection(validateShareIntake(snapshot(streamUri = "https://synthetic.example/bill.csv", streamScheme = "https"), "d")),
        )
        // A ClipData-only share with a non-content scheme is rejected the same way (the single
        // ClipData item is the effective stream, so its scheme is checked).
        assertEquals(
            ShareIntakeRejection.NON_CONTENT_URI,
            rejection(
                validateShareIntake(
                    snapshot(
                        streamUri = null,
                        streamScheme = null,
                        clipDataUris = listOf("file:///synthetic/bill.csv"),
                        clipDataScheme = "file",
                    ),
                    "d",
                ),
            ),
        )
    }

    @Test
    fun anUnreadableStreamIsRejected() {
        assertEquals(
            ShareIntakeRejection.STREAM_NOT_READABLE,
            rejection(validateShareIntake(snapshot(readableUris = emptyList()), "d")),
        )
    }

    @Test
    fun anUnexpectedStreamExtraIsRejectedAsMalformed() {
        assertEquals(
            ShareIntakeRejection.MALFORMED_EXTRAS,
            rejection(validateShareIntake(snapshot(hasUnexpectedStreamExtra = true), "d")),
        )
    }

    @Test
    fun aDeclaredSizeOverTheFrozenBoundIsRejectedButAnUnknownSizeIsNot() {
        assertEquals(
            ShareIntakeRejection.DECLARED_SIZE_EXCEEDS_LIMIT,
            rejection(validateShareIntake(snapshot(declaredSizeBytes = IMPORT_FILE_PICK_MAX_READ_BYTES + 1), "d")),
        )
        assertIs<ExternalNavigationIntakeResult.Accepted>(
            validateShareIntake(snapshot(declaredSizeBytes = IMPORT_FILE_PICK_MAX_READ_BYTES), "d"),
        )
        assertIs<ExternalNavigationIntakeResult.Accepted>(
            validateShareIntake(snapshot(declaredSizeBytes = null), "d"),
        )
    }

    @Test
    fun aFakeMimeOrExtensionNeverDecidesAcceptance() {
        // A non-CSV MIME with a .csv URI is still accepted on the stream facts alone: MIME is a
        // hint, never a判据 (spec section 2.3).
        assertIs<ExternalNavigationIntakeResult.Accepted>(
            validateShareIntake(snapshot(mimeType = "application/octet-stream"), "d"),
        )
    }

    @Test
    fun theDeliveryIdLedgerConsumesEachIdExactlyOnce() {
        val ledger = DeliveryIdLedger()
        assertTrue(ledger.consumeOnce("delivery-1"))
        assertEquals(false, ledger.consumeOnce("delivery-1"))
        assertTrue(ledger.consumeOnce("delivery-2"))
    }

    // ---- P7-09 09.0 receipt semantics (spec section 4): the InboundNavigationIntake decision ----

    @Test
    fun anOrdinaryLaunchIsNotAnInboundEntry() {
        val intake = InboundNavigationIntake { "delivery-1" }
        assertEquals(null, intake.requestFor(snapshot(action = Intent.ACTION_MAIN)))
    }

    @Test
    fun aValidShareMintsAFreshDeliveryIdPerReceipt() {
        var minted = 0
        val intake = InboundNavigationIntake { "delivery-${++minted}" }
        val first = assertIs<ExternalNavigationRequest.OpenImport>(intake.requestFor(snapshot()))
        val second = assertIs<ExternalNavigationRequest.OpenImport>(intake.requestFor(snapshot()))
        assertEquals("delivery-1", first.deliveryId)
        // A NEW receipt mints a NEW id even for the identical URI (D-146: a new import).
        assertEquals("delivery-2", second.deliveryId)
    }

    @Test
    fun aRejectedShareYieldsNoRequestAndNothingIsConsumed() {
        val intake = InboundNavigationIntake { "delivery-1" }
        assertEquals(null, intake.requestFor(snapshot(action = "android.intent.action.VIEW")))
    }

    @Test
    fun aRepeatedDeliveryOfTheSameIdIsConsumedAtMostOnce() {
        // The id is fixed (simulating the same opaque deliveryId arriving twice internally): the
        // first receipt delivers, the second is consumed-and-dropped (idempotent consumption).
        val intake = InboundNavigationIntake { "fixed-delivery" }
        assertIs<ExternalNavigationRequest.OpenImport>(intake.requestFor(snapshot()))
        assertEquals(null, intake.requestFor(snapshot()))
    }
}
