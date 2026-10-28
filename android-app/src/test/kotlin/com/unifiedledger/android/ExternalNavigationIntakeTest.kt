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

    private fun rejection(result: ExternalNavigationIntakeResult): ShareIntakeRejection = assertIs<ExternalNavigationIntakeResult.Rejected>(result).code

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

    // ---- P7-09 P2-4 fix: EXTRA_STREAM shape classification (adapter layer, pure) ----

    @Test
    fun theStreamExtraShapeClassifierDistinguishesSingleListMalformedAndAbsent() {
        // P2-4: a LIST extra must be recognized (never coerced to its first element nor silently
        // nulled by a blind `as? Uri`). The classifier is pure, so it is driven with plain values.
        assertEquals(ShareStreamExtraShape.LIST, classifyShareStreamExtra(present = true, rawValue = listOf("content://p/a.csv", "content://p/b.csv")))
        assertEquals(ShareStreamExtraShape.LIST, classifyShareStreamExtra(present = true, rawValue = arrayOf("content://p/a.csv")))
        assertEquals(ShareStreamExtraShape.ABSENT, classifyShareStreamExtra(present = false, rawValue = null))
        // Present but neither a Uri nor a list (a String path, an opaque value or an explicit null).
        assertEquals(ShareStreamExtraShape.MALFORMED, classifyShareStreamExtra(present = true, rawValue = "content://p/a.csv"))
        assertEquals(ShareStreamExtraShape.MALFORMED, classifyShareStreamExtra(present = true, rawValue = null))
    }

    @Test
    fun aListStreamExtraIsRejectedAsMultipleStreamsAndIsNeverAccepted() {
        // P2-4 end to end through the validator: the list flag yields a typed MULTIPLE_STREAMS
        // rejection even though a single ClipData fallback would otherwise look acceptable.
        val listSnapshot =
            snapshot(
                clipDataUris = listOf("content://synthetic-provider/bill.csv"),
                streamExtraIsList = true,
            )
        assertEquals(ShareIntakeRejection.MULTIPLE_STREAMS, rejection(validateShareIntake(listSnapshot, "d")))
    }

    // ---- P7-09 P2-3 fix: declared size + unexpected-extras populated by the adapter ----

    @Test
    fun theDeclaredSizeHintIsParsedFromLongNumberOrNumericString() {
        // P2-3: the adapter genuinely reads the sender's declared-size hint (Long / Number / String),
        // degrading a malformed/absent value to null (unknown size, which is accepted).
        assertEquals(1234L, parseDeclaredShareSize(1234L))
        assertEquals(1234L, parseDeclaredShareSize(1234))
        assertEquals(1234L, parseDeclaredShareSize("1234"))
        assertEquals(null, parseDeclaredShareSize(null))
        assertEquals(null, parseDeclaredShareSize("not-a-number"))
        assertEquals(null, parseDeclaredShareSize(true))
        // The parsed value feeds the frozen bound check: over-limit is a typed rejection.
        assertEquals(
            ShareIntakeRejection.DECLARED_SIZE_EXCEEDS_LIMIT,
            rejection(validateShareIntake(snapshot(declaredSizeBytes = parseDeclaredShareSize("${IMPORT_FILE_PICK_MAX_READ_BYTES + 1}")), "d")),
        )
    }

    @Test
    fun aMalformedStreamExtraIsRejectedAsUnexpectedExtras() {
        // P2-3: an EXTRA_STREAM that is present but unusable is the spec's 异常 extras vector; the
        // adapter maps the MALFORMED shape to hasUnexpectedStreamExtra, which the validator rejects.
        val malformed = classifyShareStreamExtra(present = true, rawValue = 42) == ShareStreamExtraShape.MALFORMED
        assertEquals(
            ShareIntakeRejection.MALFORMED_EXTRAS,
            rejection(validateShareIntake(snapshot(hasUnexpectedStreamExtra = malformed), "d")),
        )
    }

    // ---- P7-09 P2-5 fix: the non-opening read-grant probe ----

    @Test
    fun theReadGrantProbeIsANonOpeningPermissionCheck() {
        // P2-5: readability is decided by a NON-opening permission probe (never openInputStream). The
        // probe performs no read; the seam simply compares the permission result.
        var probed = 0
        assertTrue(
            shareUriReadGranted("content://synthetic-provider/bill.csv", {
                probed += 1
                0
            }, permissionGranted = 0),
        )
        assertEquals(1, probed)
        assertEquals(false, shareUriReadGranted("content://synthetic-provider/bill.csv", { -1 }, permissionGranted = 0))
    }

    // ---- P7-09 P1-B fix: the process-scoped receipt identity across Activity recreation ----

    @Test
    fun aColdLaunchReceiptKeepsOneDeliveryIdAcrossAnActivityRecreationAndIsNotReConsumed() {
        // P1-B regression: the SAME launch Intent is re-read on every onCreate (a rotation /
        // configuration recreation). The process-scoped intake must deliver it ONCE; the recreation
        // re-read yields nothing and mints NO second deliveryId.
        var minted = 0
        val intake = ProcessInboundNavigationIntake { "delivery-${++minted}" }
        val first = assertIs<ExternalNavigationRequest.OpenImport>(intake.coldLaunchRequest(snapshot(), reuseHeldReceipt = false))
        assertEquals("delivery-1", first.deliveryId)
        // The recreation re-reads the same launch Intent: refused, and no new id minted.
        assertEquals(null, intake.coldLaunchRequest(snapshot(), reuseHeldReceipt = true))
        assertEquals(1, minted)
    }

    @Test
    fun aSecondShareInALiveProcessMintsANewDeliveryIdInsteadOfBeingRefusedAsARepeat() {
        // Spec section 4: a NEW user share mints a new deliveryId even for an identical URI, whichever
        // path the platform takes (top-instance reuse or a fresh instance - P709-A04 pins which).
        var minted = 0
        val intake = ProcessInboundNavigationIntake { "delivery-" + (++minted) }
        val first = assertIs<ExternalNavigationRequest.OpenImport>(intake.coldLaunchRequest(snapshot(), reuseHeldReceipt = false))
        val second = assertIs<ExternalNavigationRequest.OpenImport>(intake.coldLaunchRequest(snapshot(), reuseHeldReceipt = false))
        assertEquals("delivery-1", first.deliveryId)
        assertEquals("delivery-2", second.deliveryId)
        assertEquals(2, minted)
        // The launch receipt follows the NEWEST accepted share, so a recreation of it reuses that id
        // and stays refused (the re-hold path must never point at a superseded share).
        assertEquals("delivery-2", intake.coldLaunchReceipt()?.deliveryId)
        assertEquals(null, intake.coldLaunchRequest(snapshot(), reuseHeldReceipt = true))
    }

    @Test
    fun aRejectedColdLaunchDoesNotOccupyTheLaunchReceipt() {
        // A typed rejection must not consume the launch identity: a later recreation of the SAME
        // Intent would otherwise re-hold a request that was never delivered.
        val intake = ProcessInboundNavigationIntake { "delivery-1" }
        assertEquals(null, intake.coldLaunchRequest(snapshot(action = Intent.ACTION_SEND_MULTIPLE), reuseHeldReceipt = false))
        assertEquals(null, intake.coldLaunchReceipt())
    }

    @Test
    fun aRejectedRecreationWithNoReceiptDoesNotMintOne() {
        // The recreation branch with no launch id (process death + task restore): a rejected share
        // must leave no receipt behind, or a later read would re-hold a request never delivered.
        val intake = ProcessInboundNavigationIntake { "delivery-1" }
        assertEquals(null, intake.coldLaunchRequest(snapshot(action = Intent.ACTION_SEND_MULTIPLE), reuseHeldReceipt = true))
        assertEquals(null, intake.coldLaunchReceipt())
    }

    @Test
    fun aHotOnNewIntentReceiptMintsAFreshDeliveryIdForANewShare() {
        // Spec section 4: a hot receipt is a genuinely NEW user share, so it mints a new deliveryId
        // even for an identical URI (a new import, subject to the human duplicate review).
        var minted = 0
        val intake = ProcessInboundNavigationIntake { "delivery-${++minted}" }
        assertIs<ExternalNavigationRequest.OpenImport>(intake.coldLaunchRequest(snapshot(), reuseHeldReceipt = false))
        val hot = assertIs<ExternalNavigationRequest.OpenImport>(intake.hotReceiptRequest(snapshot()))
        assertEquals("delivery-2", hot.deliveryId)
        assertEquals(2, minted)
    }

    @Test
    fun anOrdinaryMainLaunchIsNotAnInboundEntryForEitherReceiptKind() {
        val intake = ProcessInboundNavigationIntake { "delivery-1" }
        assertEquals(null, intake.coldLaunchRequest(snapshot(action = Intent.ACTION_MAIN), reuseHeldReceipt = false))
        assertEquals(null, intake.hotReceiptRequest(snapshot(action = Intent.ACTION_MAIN)))
    }

    // ---- P7-09 09.B (D-226; spec sections 2.1/5): the launcher shortcut receipt ----

    @Test
    fun everyShortcutActionMapsToItsNeutralRequest() {
        assertEquals(
            ExternalNavigationRequest.OpenNewEntry(com.unifiedledger.application.EntryType.EXPENSE),
            ShortcutNavigationAction.requestFor(ShortcutNavigationAction.NEW_EXPENSE),
        )
        assertEquals(
            ExternalNavigationRequest.OpenNewEntry(com.unifiedledger.application.EntryType.INCOME),
            ShortcutNavigationAction.requestFor(ShortcutNavigationAction.NEW_INCOME),
        )
        assertEquals(ExternalNavigationRequest.OpenImportTab, ShortcutNavigationAction.requestFor(ShortcutNavigationAction.OPEN_IMPORT))
    }

    @Test
    fun anUnknownActionMapsToNoRequest() {
        // The typed-rejection discipline: an unrecognized or foreign action is never coerced.
        assertEquals(null, ShortcutNavigationAction.requestFor(null))
        assertEquals(null, ShortcutNavigationAction.requestFor(Intent.ACTION_MAIN))
        assertEquals(null, ShortcutNavigationAction.requestFor(Intent.ACTION_SEND))
        assertEquals(null, ShortcutNavigationAction.requestFor("com.example.action.NOPE"))
    }

    @Test
    fun shortcutActionsNeverCollideWithMainOrSend() {
        // REGRESSION RED LINE: the shortcut family stays an explicit, disjoint action set — it must
        // never be ACTION_MAIN (an ordinary launch) nor an accepted share action.
        assertTrue(ShortcutNavigationAction.ALL.none { it == Intent.ACTION_MAIN || it == Intent.ACTION_SEND || it == Intent.ACTION_SEND_MULTIPLE })
        assertEquals(3, ShortcutNavigationAction.ALL.size, "exactly the three 09.B shortcuts are published")
    }

    @Test
    fun aRecognizedShortcutActionIsDeliveredOnceAndARepeatIsRefused() {
        val intake = InboundNavigationIntake { "shortcut-id" }
        val first = assertIs<ExternalNavigationRequest.OpenNewEntry>(intake.shortcutRequestFor(ShortcutNavigationAction.NEW_EXPENSE, "shortcut:expense"))
        assertEquals(com.unifiedledger.application.EntryType.EXPENSE, first.type)
        // The same delivery key is consumed exactly once (idempotent re-consumption).
        assertEquals(null, intake.shortcutRequestFor(ShortcutNavigationAction.NEW_EXPENSE, "shortcut:expense"))
        // An unknown action consumes nothing and yields nothing.
        assertEquals(null, intake.shortcutRequestFor("com.example.action.NOPE", "shortcut:nope"))
    }

    @Test
    fun aColdLaunchShortcutKeepsOneKeyAcrossARecreationWhileAHotTapIsFresh() {
        var minted = 0
        val intake = ProcessInboundNavigationIntake { "delivery-${++minted}" }
        val cold = assertIs<ExternalNavigationRequest.OpenImportTab>(intake.coldLaunchShortcutRequest(ShortcutNavigationAction.OPEN_IMPORT))
        assertEquals(ExternalNavigationRequest.OpenImportTab, cold)
        // The recreation re-reads the SAME launch Intent: refused, no new id, nothing delivered.
        assertEquals(null, intake.coldLaunchShortcutRequest(ShortcutNavigationAction.OPEN_IMPORT))
        assertEquals(0, minted, "the cold receipt keys on the action, not on a minted id")
        // A HOT tap is a new user action: it mints a fresh id and always routes.
        val hot = assertIs<ExternalNavigationRequest.OpenNewEntry>(intake.hotShortcutRequest(ShortcutNavigationAction.NEW_INCOME))
        assertEquals(1, minted)
        assertEquals(com.unifiedledger.application.EntryType.INCOME, hot.type)
    }

    @Test
    fun ordinaryMainAndShareActionsRemainNonShortcutReceipts() {
        val intake = ProcessInboundNavigationIntake { "d" }
        assertEquals(null, intake.coldLaunchShortcutRequest(Intent.ACTION_MAIN))
        assertEquals(null, intake.hotShortcutRequest(Intent.ACTION_SEND))
        // The share face still returns null for ACTION_MAIN (the red line: MAIN is not an entry).
        assertEquals(null, intake.coldLaunchRequest(snapshot(action = Intent.ACTION_MAIN), reuseHeldReceipt = false))
    }
}
