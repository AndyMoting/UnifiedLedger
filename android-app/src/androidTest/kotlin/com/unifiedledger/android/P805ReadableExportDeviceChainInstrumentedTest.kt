package com.unifiedledger.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.application.CATALOG_MANAGED_CURRENCY
import com.unifiedledger.application.CreationEntry
import com.unifiedledger.application.DEFAULT_EXPENSE_LEAF_NAME
import com.unifiedledger.application.HOME_FLOW_PAGE_SIZE
import com.unifiedledger.application.export.readable.ExportOperationLease
import com.unifiedledger.application.export.readable.LedgerRuntimeLeasePort
import com.unifiedledger.application.export.readable.LedgerRuntimeLeasePortResult
import com.unifiedledger.application.export.readable.READABLE_EXPORT_HEADERS
import com.unifiedledger.application.export.readable.READABLE_EXPORT_UNNAMED_ACCOUNT
import com.unifiedledger.application.export.readable.READABLE_EXPORT_UNNAMED_CATEGORY
import com.unifiedledger.application.export.readable.ReadableExportCancellation
import com.unifiedledger.application.export.readable.ReadableExportSheetWriter
import com.unifiedledger.application.export.readable.ReadableExportSink
import com.unifiedledger.application.export.readable.ReadableLedgerExport
import com.unifiedledger.application.export.readable.ReadableLedgerExportFailure
import com.unifiedledger.application.export.readable.ReadableLedgerExportProgressListener
import com.unifiedledger.application.export.readable.ReadableLedgerExportResult
import com.unifiedledger.application.export.readable.ReadableLedgerXlsxStreamWriter
import com.unifiedledger.application.export.readable.parseReadableExportWallClockTextOrNull
import com.unifiedledger.application.export.readable.readableExportAmountText
import com.unifiedledger.application.export.readable.readableExportKindLabel
import com.unifiedledger.application.export.readable.readableExportSourceLabel
import com.unifiedledger.application.import.wechat.BoundedXlsxReader
import com.unifiedledger.domain.TransactionKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * P8-05 批 c (D-331 spec sections 4 批 c / 5 V-3/V-4/V-8/V-9): the readable export's DEVICE legs —
 * the SAF full chain (成功 / 取消 / 写出失败), the two large-sample readings and the TalkBack
 * structural leg — over the PRODUCTION composition root (`openAndroidStableStorageLedger` + the
 * real Android SQLite driver, the P8-01a V-10 chain-fixture shape).
 *
 * THE SIX CASES
 * -------------
 * 1. [theSafFullChainExportsCommitsAndStreamsTheExactRows] — SAF 全链 SUCCESS leg: the production
 *    `AndroidReadableExportTargetPort` latch flow opens the target (only the SAF picker UI is
 *    replaced — a synchronous poster plus an immediate synthetic target, the
 *    `BackupExportSafMidWrite` / `SelfEntryAndroid` rig shape; the port's latch, the D-E7
 *    suggested-name launch and the real `safTargetWriter` stream lifecycle are the product's), the
 *    production graph's own [CloseableLedgerGraph.readableExportFactory] builds the real batch-a
 *    use case, the D-E9 progress landings ride a recording listener, and the committed bytes
 *    round-trip through the production `BoundedXlsxReader` ON DEVICE: row/column counts and every
 *    cell equal the seeded manifest and the frozen column contract. This is also V-9's CI 钉腿
 *    (spec §6.3: 产物结构可被内容解析打开) — the real Excel/WPS editing leg is a registered
 *    follow-up device-evidence item in
 *    `docs/specs/2026-11-15-p8-05-readable-export-device-evidence-registry.md` (写出器扩 part、
 *    契约不变).
 * 2. [aDismissedSafChoiceIsCancelled] — the CANCELLED leg: the production port's latch flow
 *    delivers a null CreateDocument result (the user dismissed the picker), the open is the honest
 *    null and the use case answers [ReadableLedgerExportResult.Cancelled] — never a failure,
 *    never a success.
 * 3. [aTargetThatStopsAcceptingBytesMidWalkIsTypedWriteFailed] — the WRITE-FAILED leg (V-4 device
 *    half): the REAL `ContentResolver.openOutputStream` resolves the androidTest-only
 *    [FailAfterBytesBackupTargetProvider] (the D-288 evidence infrastructure, reused unchanged —
 *    zero new fixture providers in this batch), which drains exactly 1 MiB and then closes its
 *    pipe's read end so a later write raises EPIPE from the transport. The walk carries a
 *    high-entropy note per row (the fixture's `noteOf`) so the streamed bytes cross the failing
 *    window MID-WALK, not at the first chunk. Typed
 *    [ReadableLedgerExportFailure.WRITE_FAILED], and the provider's drained-byte counter (read
 *    back over `ContentProvider.call`, so it survives the process boundary) proves the failure
 *    happened AFTER bytes were accepted.
 * 4. [theTwoThousandRowReadingIsRecordedOnTheDevice] — the 2k-tier reading: one RECORDED READING
 *    (elapsed, the committed file's byte size, the observed Java-heap used delta and the total-PSS
 *    delta) of a full 2k-chain export into a byte-counting discard sink.
 * 5. [theFiftyThousandRowReadingIsRecordedOnTheDevice] — the 50k-tier reading: the same recorded
 *    reading over the 50k sample (spec section 4 批 c: 2k 与 50k 级合成库导出设备读数). The JVM
 *    leg's bounded-memory assertion (假页源「在途页 ≤ 1」) already holds the bounded-memory
 *    contract; the device tier observes the wall-clock + memory facts only.
 * 6. [theReadableExportSurfaceMarkersArePresentInTheAccessibilityTree] — the TalkBack structural
 *    leg (V-8): the REAL [MainActivity] renders the REAL composition, the drawer row 导出账目 is
 *    reached through the platform accessibility tree with `ACTION_CLICK` (never a synthetic
 *    gesture — the `ImportScaleTraversal` a11y-only discipline), and the surface's frozen markers
 *    (title, U4 purpose note, D-E11 consistency note, 开始导出 / 取消导出 / 返回, the honest
 *    outcome absence before any run) are asserted as accessibility-tree STRUCTURE. TalkBack's own
 *    utterance log (the liveRegion 宣告 + the progress row's spoken reading) is the registered
 *    follow-up device-evidence item in the registry file (the `A11yFocusProbeService` evidence
 *    face; spec §6.3 discipline).
 *
 * NO DURATION IS ASSERTED anywhere (the P8-02 V-8 device-timing discipline D2=A / D7-13): every
 * reading is a structural fact plus ONE grep-able logcat line; a green run can never be read as
 * "fast enough".
 *
 * INJECTION SHAPE (the task-book discipline): the device legs use the REAL SAF provider (case 3)
 * and the REAL port latch/stream lifecycle (cases 1-3); injections exist only at the use case's
 * documented seams — the target UI (the picker), the sheet-writer provider and the operation-lease
 * port. The lease bridge's own machinery (`LedgerRuntimeOwnerLeasePort` over the owner) is batch
 * b's JVM-covered surface and is not what a device leg tests, so the legs inject an
 * already-acquired-shaped port there.
 *
 * DATA SAFETY: the user's production ledger is never touched by cases 1-5 — the fixture resolves
 * its whole storage inside a fresh redirected root under the app cache dir (`p801RedirectedContext`)
 * and deletes it in `finally`. No filesystem is filled (the fail leg reuses D-288's pipe rig), so
 * the whole class is safe inside the ordinary device regression. Case 6 launches the real app over
 * the production path, which on a CI emulator is a fresh install (its own disposable state).
 *
 * Frozen app-ui copy: the failure-banner texts are mirrored here AS LITERALS only in
 * [OUTCOME_BANNER_PREFIXES] (the presentation functions are internal to the app-ui module — the
 * `FAIL_AFTER_BYTES` mirror discipline: a divergent copy fails the assertion instead of passing
 * silently). The ledger-application contract symbols are the SINGLE SOURCE and are imported
 * directly.
 */
@RunWith(AndroidJUnit4::class)
class P805ReadableExportDeviceChainInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private var fixture: P805ReadableExportDeviceFixture? = null

    @After
    fun closeTheFixture() {
        fixture?.close()
        fixture = null
    }

    // ------------------------------------------------------------------ 1: the SAF full chain, success leg

    @Test
    fun theSafFullChainExportsCommitsAndStreamsTheExactRows() {
        val tier = P805ReadableExportDeviceFacts.TIER_2K
        val seeded = buildFixture(tier, "saf-success")
        val sink = CapturingSink()
        var port: AndroidReadableExportTargetPort<Uri>? = null
        var launchedName: String? = null
        val targetPort =
            AndroidReadableExportTargetPort<Uri>(
                postToMainThread = { block -> block() },
                launchCreateDocument = { name ->
                    launchedName = name
                    // The SAF CreateDocument UI is replaced by an immediate delivery of a synthetic
                    // target (the rig shape); the port's latch flow and the real stream writer below
                    // are the product's.
                    port?.onCreateDocumentResult(SYNTHETIC_TARGET_URI)
                },
                openWriter = { _ -> safTargetWriter(sink.stream) },
            )
        port = targetPort
        val landings = mutableListOf<Pair<Long, Int>>()
        val progressListener =
            object : ReadableLedgerExportProgressListener {
                override fun onProgress(
                    exportedRows: Long,
                    totalRows: Int,
                ) {
                    landings.add(exportedRows to totalRows)
                }
            }
        val export =
            readableExport(
                graph = seeded.graph,
                writerProvider = { readableExportSinkFromTargetPort(targetPort)?.let(::ReadableLedgerXlsxStreamWriter) },
                progressListener = progressListener,
            )
        val result = export.export(P805ReadableExportDeviceFacts.ledgerId)

        // The typed outcome (D-E10): the full chain lands SUCCESS with the seeded manifest counts.
        assertTrue("the full chain must land Succeeded, got $result", result is ReadableLedgerExportResult.Succeeded)
        val succeeded = result as ReadableLedgerExportResult.Succeeded
        assertEquals("the export must stream every seeded chain", tier.toLong(), succeeded.exportedRows)
        assertEquals("the progress denominator is the exact effective count", tier, succeeded.totalRows)
        assertTrue(
            "the D-E7 suggested name must carry the frozen display shape, got $launchedName",
            SUGGESTED_NAME_SHAPE.matches(launchedName.orEmpty()),
        )
        assertTrue(
            "the committed suggested name must carry the same frozen shape, got ${succeeded.suggestedFileName}",
            SUGGESTED_NAME_SHAPE.matches(succeeded.suggestedFileName),
        )

        // The D-E9 progress face (the 进度 leg): the pre-open zero landing, per-page landings, and
        // the final landing equal to the exact total, monotonically non-decreasing.
        assertEquals("the first landing is the zero/total pair taken before the target opened", 0L to tier, landings.first())
        assertEquals("the last landing carries the exact total", tier.toLong() to tier, landings.last())
        assertTrue("the exported counter must never move backwards", landings.zipWithNext().all { (a, b) -> b.first >= a.first })
        assertTrue("a $tier-row walk must land more than the pre-open pair, got ${landings.size}", landings.size >= 2)

        // The committed bytes round-trip through the production reader ON DEVICE (V-9 钉腿).
        assertTrue("the committed stream must carry bytes", sink.stream.size() > 0)
        val workbook = BoundedXlsxReader.read(sink.stream.toByteArray())
        assertEquals("the committed file resolves exactly one sheet", 1, workbook.numberOfSheets)
        val sheet = workbook.firstSheet
        assertEquals("the committed file carries the header row plus every seeded chain", tier + 1, sheet.lastRowNum + 1)
        assertEquals("the header row carries the frozen ten columns", READABLE_EXPORT_HEADERS.size, sheet.getRow(0)!!.lastCellNum)
        assertEquals("the header row is the frozen column contract", READABLE_EXPORT_HEADERS, rowTexts(sheet, 0))
        val dataRows = (1..tier).map { index -> rowTexts(sheet, index) }
        val expectedAmounts =
            (0 until tier).map { index ->
                readableExportAmountText(
                    P805ReadableExportDeviceFacts.AMOUNT_BASE + (index % P805ReadableExportDeviceFacts.AMOUNT_STRIDE) * P805ReadableExportDeviceFacts.AMOUNT_STEP,
                    CATALOG_MANAGED_CURRENCY.precision,
                )
            }
        val expectedKind = readableExportKindLabel(TransactionKind.EXPENSE)
        val expectedSource = readableExportSourceLabel(CreationEntry.UNMARKED)
        assertEquals(
            "every row's 类型 cell is the frozen kind label (single constant source)",
            (0 until tier).map { _ -> expectedKind },
            dataRows.map { it[2] },
        )
        assertEquals(
            "every row's 金额 cell is the seeded amount spread through the frozen numeric text " +
                "(MULTISET equality: the use case writes the PAGE order, `(statistics_at DESC, txid DESC)` per D-E1, " +
                "while the seed formula runs over the insertion index — the two orders never coincide at the 2k or 50k tier " +
                "(LCM(30,97)=2910 exceeds both tiers), so the position-by-position comparison would always misalign; " +
                "the sorted lists must still hold EXACTLY the seeded amounts with no row swapped, dropped or invented)",
            expectedAmounts.sorted(),
            dataRows.map { it[3] }.sorted(),
        )
        assertEquals("the first data row's 金额 cell is a NUMERIC cell", BoundedXlsxReader.CellType.NUMERIC, sheet.getRow(1)!!.getCell(3)!!.type)
        assertEquals(
            "every row's 币种 cell is the managed currency code",
            (0 until tier).map { _ -> CATALOG_MANAGED_CURRENCY.code },
            dataRows.map { it[4] },
        )
        dataRows.forEachIndexed { index, texts ->
            assertTrue("row ${index + 1}'s 账户1 must name the category-leg account, got ${texts[5]}", texts[5].isNotEmpty() && texts[5] != READABLE_EXPORT_UNNAMED_ACCOUNT)
            assertTrue("row ${index + 1}'s 账户2 must name the funding-leg account, got ${texts[6]}", texts[6].isNotEmpty() && texts[6] != READABLE_EXPORT_UNNAMED_ACCOUNT)
            assertTrue(
                "row ${index + 1}'s 分类 must carry the leaf category's path, got ${texts[7]}",
                texts[7].isNotEmpty() && texts[7] != READABLE_EXPORT_UNNAMED_CATEGORY && texts[7].endsWith(DEFAULT_EXPENSE_LEAF_NAME),
            )
            assertNotNull("row ${index + 1}'s 凭证时间 must parse back through the frozen shape", parseReadableExportWallClockTextOrNull(texts[0]))
            assertNotNull("row ${index + 1}'s 统计时间 must parse back through the frozen shape", parseReadableExportWallClockTextOrNull(texts[1]))
            assertTrue("row ${index + 1} streams no note when none was seeded", texts[8].isEmpty())
        }
        assertEquals(
            "every row's 来源 cell is the honest unmarked label (no lineage rows are seeded)",
            (0 until tier).map { _ -> expectedSource },
            dataRows.map { it[9] },
        )
    }

    // ------------------------------------------------------------------ 2: the SAF full chain, cancelled leg

    @Test
    fun aDismissedSafChoiceIsCancelled() {
        val seeded = buildFixture(P805ReadableExportDeviceFacts.TIER_2K, "saf-cancelled")
        var port: AndroidReadableExportTargetPort<Uri>? = null
        val targetPort =
            AndroidReadableExportTargetPort<Uri>(
                postToMainThread = { block -> block() },
                launchCreateDocument = { _ ->
                    // The picker UI is replaced: the user's dismissal is a null CreateDocument
                    // result (the AndroidRestoreSourcePort device precedent's cancelled-choice
                    // vector). The port's own latch flow answers the honest null open.
                    port?.onCreateDocumentResult(null)
                },
                openWriter = { _ -> error("a dismissed choice must never open a writer") },
            )
        port = targetPort
        val export =
            readableExport(
                graph = seeded.graph,
                writerProvider = { readableExportSinkFromTargetPort(targetPort)?.let(::ReadableLedgerXlsxStreamWriter) },
            )
        val result = export.export(P805ReadableExportDeviceFacts.ledgerId)

        assertTrue(
            "a dismissed picker must answer the typed Cancelled, never a failure or a success: $result",
            result is ReadableLedgerExportResult.Cancelled,
        )
    }

    // ------------------------------------------------------------------ 3: the SAF full chain, write-failure leg

    @Test
    fun aTargetThatStopsAcceptingBytesMidWalkIsTypedWriteFailed() {
        val tier = P805ReadableExportDeviceFacts.TIER_2K
        val seeded =
            buildFixture(
                tier,
                "saf-midwrite",
                noteOf = { index ->
                    // Genuinely per-row, weakly-compressible payload: the walk streams through a
                    // DEFLATE-backed ZipOutputStream, and a row-identical repeated note collapses
                    // under LZ77 (the 2k tier then totals far under the 1 MiB window, the drainer
                    // never trips, and the leg reads Succeeded — the CI failure mode). A row-index
                    // seeded hex run defeats long back-references while staying deterministic.
                    P805ReadableExportDeviceFacts.NOTE_PREFIX +
                        incompressibleRun(index, NOTE_REPEAT)
                },
            )
        var port: AndroidReadableExportTargetPort<Uri>? = null
        val targetPort =
            AndroidReadableExportTargetPort<Uri>(
                postToMainThread = { block -> block() },
                launchCreateDocument = { _ -> port?.onCreateDocumentResult(FAILING_TARGET_URI) },
                openWriter = { picked ->
                    // The real platform call: it resolves the androidTest-only provider above.
                    targetContext.contentResolver.openOutputStream(picked, "w")?.let(::safTargetWriter)
                },
            )
        port = targetPort
        val export =
            readableExport(
                graph = seeded.graph,
                writerProvider = { readableExportSinkFromTargetPort(targetPort)?.let(::ReadableLedgerXlsxStreamWriter) },
            )
        val result = export.export(P805ReadableExportDeviceFacts.ledgerId)

        // The typed outcome: WRITE_FAILED, never a crash, never a success (V-4 device half).
        assertTrue(
            "a target that stops accepting bytes must produce a typed Failed, never a crash or Succeeded: $result",
            result is ReadableLedgerExportResult.Failed,
        )
        assertEquals(
            "the refused target write must surface as WRITE_FAILED",
            ReadableLedgerExportFailure.WRITE_FAILED,
            (result as ReadableLedgerExportResult.Failed).reason,
        )

        // MID-WALK proof: the provider accepted its whole failing window before the transport broke,
        // so the failure happened with bytes already delivered — not at open (the D-288 rig's own
        // evidence face, mirrored window).
        val drained = drainedBytes()
        assertEquals(
            "the provider must have accepted its full window before the write failed " +
                "(drained=$drained, window=$FAIL_AFTER_BYTES); a smaller count means the walk " +
                "never crossed the vector under test",
            FAIL_AFTER_BYTES,
            drained,
        )
    }

    // ------------------------------------------------------------------ 4/5: the recorded readings

    @Test
    fun theTwoThousandRowReadingIsRecordedOnTheDevice() {
        recordReading(P805ReadableExportDeviceFacts.TIER_2K, "reading-2k")
    }

    @Test
    fun theFiftyThousandRowReadingIsRecordedOnTheDevice() {
        recordReading(P805ReadableExportDeviceFacts.TIER_50K, "reading-50k")
    }

    // ------------------------------------------------------------------ 6: the TalkBack structural leg

    @Test
    fun theReadableExportSurfaceMarkersArePresentInTheAccessibilityTree() {
        val automation = uiAutomation()
        val serviceInfo = runCatching { automation.serviceInfo }.getOrNull()
        if (serviceInfo != null) {
            automation.serviceInfo =
                serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        }
        // The REAL app over the production path (a fresh install on a CI emulator). The explicit
        // Intent launch from the target context starts the same activity the launcher would.
        val launched =
            runCatching {
                val intent = Intent().setClassName(TARGET_PACKAGE, MAIN_ACTIVITY_CLASS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                targetContext.startActivity(intent)
            }
        Log.i(TAG, "$V8_PREFIX bringUp=$launched")

        // The home must be ready (the fresh install's empty-ledger copy) before the drawer exists.
        val homeReady =
            awaitNode("$V8_PREFIX home-ready", HOME_READY_TIMEOUT_MILLIS) {
                collectNodes(appRoot()).firstOrNull { node -> LedgerReadyCopy.matches(node.text?.toString().orEmpty()) }
            }
        assertNotNull("$V8_PREFIX the home must render its ready copy before the drawer walk", homeReady)

        // 入口 leg: the top bar's menu button (contentDescription 打开菜单), then the drawer's
        // 导出账目 row — the D-E8 wired row (the production root wires the readable-export factory).
        val menuButton =
            awaitNode("$V8_PREFIX menu-button", NODE_TIMEOUT_MILLIS) { findByContentDescription(appRoot(), DRAWER_OPEN_BUTTON_DESCRIPTION) }
        assertNotNull("$V8_PREFIX the drawer menu button must be reachable", menuButton)
        assertTrue("$V8_PREFIX the menu button must accept the accessibility click", clickNode(menuButton!!))
        val exportRow =
            awaitNode("$V8_PREFIX drawer-row", NODE_TIMEOUT_MILLIS) { findByText(appRoot(), DRAWER_EXPORT_ROW_TEXT) }
        assertNotNull("$V8_PREFIX the drawer's 导出账目 row must be reachable", exportRow)
        assertTrue("$V8_PREFIX the wired 导出账目 row must be enabled", exportRow!!.isEnabled)
        assertTrue("$V8_PREFIX the wired 导出账目 row must not render the 暂未开放 suffix", findByText(appRoot(), UNWIRED_SUFFIX_TEXT) == null)
        assertTrue("$V8_PREFIX the 导出账目 row must accept the accessibility click", clickNode(exportRow))

        // The surface markers: the frozen title, the U4 purpose note, the D-E11 consistency note,
        // the two action buttons and the exit — every one visible and reachable in the tree.
        val markerTexts = listOf(SURFACE_TITLE, PURPOSE_NOTE_MIRROR, CONSISTENCY_NOTE_MIRROR, START_BUTTON_TEXT, CANCEL_BUTTON_TEXT, EXIT_BUTTON_TEXT)
        val markers =
            markerTexts.associateWith { text -> awaitNode("$V8_PREFIX marker[$text]", NODE_TIMEOUT_MILLIS) { findByText(appRoot(), text) } }
        markers.forEach { (text, node) ->
            assertNotNull("$V8_PREFIX the surface marker [$text] must be present in the tree", node)
            assertTrue("$V8_PREFIX the surface marker [$text] must be visible to the user", node!!.isVisibleToUser)
        }
        // 开始导出 / 返回 are always live affordances; 取消导出 is NOT: the registered discipline
        // (P503ReadableExportPresentation.cancelEnabled = running && !cancelRequested) keeps the
        // fresh-surface cancel inert (no run in flight yet), so its tree node renders but stays
        // disabled — a structural clickability claim on it would contradict the reducer matrix
        // (cancelIsReachableExactlyWhileARunIsInFlightAndUnrequested).
        assertTrue("$V8_PREFIX the [$START_BUTTON_TEXT] affordance must be clickable", clickableAround(markers.getValue(START_BUTTON_TEXT)!!))
        assertTrue("$V8_PREFIX the [$EXIT_BUTTON_TEXT] affordance must be clickable", clickableAround(markers.getValue(EXIT_BUTTON_TEXT)!!))
        val cancelButton = markers.getValue(CANCEL_BUTTON_TEXT)!!
        assertTrue(
            "$V8_PREFIX the [$CANCEL_BUTTON_TEXT] affordance must be inert before any run (the registered discipline)",
            !cancelButton.isEnabled || !clickableAround(cancelButton),
        )

        // The honest pre-run face: no typed outcome banner may exist before any run landed (D-E10).
        OUTCOME_BANNER_PREFIXES.forEach { prefix ->
            assertNull(
                "$V8_PREFIX no outcome banner [$prefix] may render before any export ran",
                collectNodes(appRoot()).firstOrNull { it.text?.toString()?.startsWith(prefix) == true },
            )
        }

        // ONE grep-able evidence line: the TalkBack utterance leg (liveRegion 宣告 + the progress
        // row's spoken reading) is the registered follow-up device-evidence item (registry file).
        val flags =
            markerTexts.joinToString(separator = ",") { text ->
                val node = markers.getValue(text)
                "$text:visible=${node!!.isVisibleToUser},clickable=${node.isClickable},enabled=${node.isEnabled}"
            }
        Log.i(TAG, "$V8_PREFIX markers[$flags] outcomeAbsent=true")
    }

    // ------------------------------------------------------------------ the shared device rig

    /**
     * Builds this case's synthetic sample through the fixture and keeps it for `@After` cleanup.
     * [noteOf] seeds per-row notes (only the mid-write-failure leg needs payload weight).
     */
    private fun buildFixture(
        transactionCount: Int,
        label: String,
        noteOf: ((Int) -> String?)? = null,
    ): P805ReadableExportDeviceFixture {
        val seeded = P805ReadableExportDeviceFixture(label)
        fixture = seeded
        seeded.build(transactionCount, noteOf)
        assertEquals("the frozen schema version must hold on the device", SCHEMA_VERSION, seeded.schemaVersion)
        assertEquals("every version row must carry its statistics projection", 0L, seeded.missingProjectionRows)
        return seeded
    }

    /**
     * Assembles the REAL batch-a use case over the graph's own production factory (the R-222-4
     * one-named shape). The operation-lease port is the documented injectable seam: the bridge's
     * own machinery is batch b's JVM-covered surface, so the device leg injects an
     * already-acquired-shaped port and the walk under test stays the export itself.
     */
    private fun readableExport(
        graph: CloseableLedgerGraph,
        writerProvider: () -> ReadableExportSheetWriter?,
        progressListener: ReadableLedgerExportProgressListener? = null,
    ): ReadableLedgerExport {
        val factory = checkNotNull(graph.readableExportFactory) { "the production graph must wire the readable-export factory" }
        val leasePort =
            object : LedgerRuntimeLeasePort {
                override fun acquireLease(): LedgerRuntimeLeasePortResult = LedgerRuntimeLeasePortResult.Acquired(ExportOperationLease { })
            }
        return factory(writerProvider, leasePort, ReadableExportCancellation { false }, progressListener)
    }

    /**
     * One RECORDED READING of a full [transactionCount]-chain export into a byte-counting discard
     * sink. Structural facts are asserted (the typed outcome, the manifest counts, the D-E9
     * landing pair shape); NO DURATION IS ASSERTED — the numbers go into ONE grep-able logcat line
     * and the first reading becomes the baseline (the P8-02 device-timing discipline).
     */
    private fun recordReading(
        transactionCount: Int,
        label: String,
    ) {
        val seeded = buildFixture(transactionCount, label)
        val sink = CountingDiscardSink()
        val landings = mutableListOf<Pair<Long, Int>>()
        val progressListener =
            object : ReadableLedgerExportProgressListener {
                override fun onProgress(
                    exportedRows: Long,
                    totalRows: Int,
                ) {
                    landings.add(exportedRows to totalRows)
                }
            }
        val export =
            readableExport(
                graph = seeded.graph,
                writerProvider = { ReadableLedgerXlsxStreamWriter(sink) },
                progressListener = progressListener,
            )
        val heapBefore = usedHeapKb()
        val pssBefore = totalPssKb()
        val startNanos = System.nanoTime()
        val result = export.export(P805ReadableExportDeviceFacts.ledgerId)
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0
        val heapAfter = usedHeapKb()
        val pssAfter = totalPssKb()

        assertTrue("the reading's export must land Succeeded, got $result", result is ReadableLedgerExportResult.Succeeded)
        val succeeded = result as ReadableLedgerExportResult.Succeeded
        assertEquals("the reading must stream every seeded chain", transactionCount.toLong(), succeeded.exportedRows)
        assertEquals("the reading's denominator is the exact effective count", transactionCount, succeeded.totalRows)
        assertEquals("the reading's first landing is the zero/total pair", 0L to transactionCount, landings.first())
        assertEquals("the reading's last landing carries the exact total", transactionCount.toLong() to transactionCount, landings.last())
        assertTrue("the reading's exported counter must never move backwards", landings.zipWithNext().all { (a, b) -> b.first >= a.first })
        assertTrue("the reading's discard sink must have taken the whole stream", sink.bytesWritten > 0L)

        val line =
            "$READING_PREFIX tier=$transactionCount label=$label elapsedMs=${String.format(Locale.ROOT, "%.1f", elapsedMs)} " +
                "fileBytes=${sink.bytesWritten} heapUsedBeforeKb=$heapBefore heapUsedAfterKb=$heapAfter " +
                "totalPssBeforeKb=$pssBefore totalPssAfterKb=$pssAfter progressLandings=${landings.size} " +
                "pageSize=$HOME_FLOW_PAGE_SIZE schema=${seeded.schemaVersion}"
        // One logical line, chunked only because logcat truncates a single long message (the P8-02
        // precedent: every chunk starts mid-line, so the grep-able prefix is the only anchor).
        for (chunk in line.chunked(LOGCAT_CHUNK)) {
            Log.i(TAG, chunk)
        }
    }

    /** The provider's own view of how many bytes it accepted, read across the process boundary. */
    private fun drainedBytes(): Long {
        val answer = targetContext.contentResolver.call(FAILING_TARGET_URI, DRAINED_BYTES_METHOD, null, null)
        return answer?.getLong(DRAINED_BYTES_KEY) ?: -1L
    }

    /**
     * One row's pseudo-random hex run, seeded by [index] (an xorshift so every row differs from
     * its neighbours): hex digits keep the SQL literal and the XML text legal, the per-row seed
     * keeps consecutive rows dissimilar enough that the DEFLATE window cannot collapse the run
     * into one back-reference — [P805ReadableExportDeviceFacts.NOTE_REPEAT] copies of the run
     * give each row its ~1 KiB of weakly-compressible payload the mid-walk crossing needs.
     */
    private fun incompressibleRun(
        index: Int,
        copies: Int,
    ): String {
        var state = (index + 1) * 0x9E3779B9.toInt()
        val run = StringBuilder()
        repeat(copies * 12) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            run.append("0123456789abcdef"[state and 0xF])
            if (run.length % 4 == 0) run.append('-')
        }
        return run.toString()
    }

    /** The process's Java-heap used size, rounded to KiB (the reading's first memory face). */
    private fun usedHeapKb(): Long = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024L

    /** The process's total PSS, in KiB (the reading's second, whole-process memory face). */
    private fun totalPssKb(): Int {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss
    }

    // ------------------------------------------------------------------ a11y helpers (the ImportScaleTraversal a11y-only shape, minimal copy)

    /**
     * The instrumentation's UiAutomation connection, created with
     * [UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES] so a co-resident service (a live
     * TalkBack session) is never disconnected by this leg's own connection.
     */
    private fun uiAutomation(): UiAutomation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    /** The app window's root: the one automation window whose package is the target's. */
    private fun appRoot(): AccessibilityNodeInfo? {
        // The ImportScaleTraversal shape (:324-328): this UiAutomation's windows list can lag or
        // stay empty on a fresh activity, so the ACTIVE root is preferred when it already belongs
        // to the app and the windows scan is only the fallback.
        uiAutomation().rootInActiveWindow?.let { root ->
            if (root.packageName?.toString() == TARGET_PACKAGE) return root
        }
        return uiAutomation().windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == TARGET_PACKAGE }
    }

    /** Bounded breadth-first collection of one root's tree (the probe's own dump shape). */
    private fun collectNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val result = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && result.size < MAX_TREE_NODES) {
            val node = queue.removeFirst()
            result.add(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return result
    }

    /** The first visible node whose text equals [text]. */
    private fun findByText(
        root: AccessibilityNodeInfo?,
        text: String,
    ): AccessibilityNodeInfo? {
        val matches = collectNodes(root).filter { it.text?.toString() == text && it.isVisibleToUser }
        // The clickable node is preferred: a Compose Button's inner Text leaf carries the text but
        // not the click action (the ImportScaleTraversal discipline).
        return matches.firstOrNull { it.isClickable } ?: matches.firstOrNull()
    }

    /** The first visible node whose contentDescription equals [description]. */
    private fun findByContentDescription(
        root: AccessibilityNodeInfo?,
        description: String,
    ): AccessibilityNodeInfo? = collectNodes(root).firstOrNull { it.contentDescription?.toString() == description && it.isVisibleToUser }

    /**
     * One row's cell texts, positionally resolved. A NUMERIC cell carries its exact text in
     * `rawValue` (`string` stays null there), so both faces are taken — the reader's
     * absent-cell semantics: an absent cell stays null and reads as empty.
     */
    private fun rowTexts(
        sheet: BoundedXlsxReader.Sheet,
        rowIndex: Int,
    ): List<String> =
        (0 until READABLE_EXPORT_HEADERS.size).map { column ->
            sheet
                .getRow(rowIndex)!!
                .getCell(column)
                ?.let { cell -> cell.string ?: cell.rawValue }
                .orEmpty()
        }

    /** Polls [finder] on a fresh tree read until it answers, or null once [timeoutMillis] elapsed. */
    private fun awaitNode(
        label: String,
        timeoutMillis: Long,
        finder: () -> AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            finder()?.let { return it }
            SystemClock.sleep(POLL_MILLIS)
        }
        Log.i(TAG, "$label not-found within $timeoutMillis ms")
        return null
    }

    /**
     * The a11y-only click: the node's own [AccessibilityNodeInfo.ACTION_CLICK], or the nearest
     * clickable ancestor when the label node answers false (the probe service's walk shape). No
     * gesture fallback: a run whose every clickable level refuses the accessibility click is a
     * failed assertion, not a coordinate tap.
     */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops <= CLICK_ANCESTOR_HOPS) {
            if (current.isEnabled && current.isVisibleToUser && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            hops++
        }
        return false
    }

    /**
     * Whether a clickable node exists AT or AROUND [node] (the node itself, any ancestor within
     * [CLICK_ANCESTOR_HOPS] hops, or any visible clickable descendant): the structural clickability
     * face the affordance assertions judge, mirroring how TalkBack itself resolves a tap target.
     */
    private fun clickableAround(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops <= CLICK_ANCESTOR_HOPS) {
            if (current.isClickable && current.isEnabled) return true
            current = current.parent
            hops++
        }
        return collectNodes(node).any { it.isClickable && it.isEnabled && it.isVisibleToUser }
    }

    /** A capture sink for the success leg: the whole committed package, held in memory. */
    private class CapturingSink : ReadableExportSink {
        val stream = ByteArrayOutputStream()
        var committed = false
            private set

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            stream.write(bytes, offset, length)
        }

        override fun commit() {
            committed = true
        }

        override fun close() {}
    }

    /** A discard sink for the readings: counts every delivered byte, materializes none. */
    private class CountingDiscardSink : ReadableExportSink {
        var bytesWritten = 0L
            private set
        var committed = false
            private set

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            bytesWritten += length
        }

        override fun commit() {
            committed = true
        }

        override fun close() {}
    }

    private companion object {
        /** The host-visible log tag (the UnifiedLedger device-signal tag family). */
        const val TAG = "UnifiedLedger"

        /** The readings' grep-able line prefix (the P802-V8DEV precedent's shape). */
        const val READING_PREFIX = "P805-V3DEV"

        /** The TalkBack leg's grep-able line prefix. */
        const val V8_PREFIX = "P805-V8DEV"

        /** The frozen schema version the device samples must carry (the P802 fixture's assertion). */
        const val SCHEMA_VERSION = 38L

        /** The instrumentation target package (the ordinary shard's own package). */
        const val TARGET_PACKAGE = "com.unifiedledger.android"

        /** The real activity the a11y leg launches. */
        const val MAIN_ACTIVITY_CLASS = "com.unifiedledger.android.MainActivity"

        /** The synthetic target the success leg's replaced picker delivers (the rig shape). */
        val SYNTHETIC_TARGET_URI: Uri = Uri.parse("content://synthetic/p805/success.xlsx")

        /**
         * The failing provider's manifest authority, mirrored from
         * [BackupExportSafMidWriteInstrumentedTest.FAILING_TARGET_AUTHORITY] as a literal so this
         * class keeps ZERO reference to that rig (the mirror discipline).
         */
        const val FAILING_TARGET_AUTHORITY = "com.unifiedledger.android.p706a10.failtarget"

        /** The document URI inside the failing provider; the path is irrelevant to the fixture. */
        val FAILING_TARGET_URI: Uri = Uri.parse("content://$FAILING_TARGET_AUTHORITY/target.xlsx")

        /**
         * The provider's failing window, mirrored from [FailAfterBytesBackupTargetProvider.FAIL_AFTER_BYTES]
         * as a literal so a divergent value fails the drained-byte assertion instead of passing
         * silently. The mid-write leg's per-row hex payload ([incompressibleRun]: 110 copies × 13
         * chars ≈ 1.4 KiB raw/row ≈ 2.8 MB of sheet bytes at the 2k tier, ~1.5 MB after DEFLATE —
         * every row re-seeds so the run defeats long LZ77 back-references) crosses the 1 MiB
         * window well before the sheet closes — a row-identical repeated note would collapse
         * under DEFLATE and never reach the window (the CI failure this shape replaced).
         */
        const val FAIL_AFTER_BYTES = 1024L * 1024L

        /** The per-row hex-run copy count (the payload weight the mid-walk crossing needs). */
        const val NOTE_REPEAT = 110

        /** The provider's `call` method/key pair (the D-288 cross-process evidence face). */
        const val DRAINED_BYTES_METHOD = "drainedBytes"
        const val DRAINED_BYTES_KEY = "drainedBytes"

        /** The D-E7 suggested-name shape over the graph clock's Shanghai date. */
        val SUGGESTED_NAME_SHAPE = Regex("unifiedledger-export-[0-9]{8}\\.xlsx")

        /** The frozen app-ui surface copy, mirrored AS LITERALS (internal to the app-ui module). */
        const val PURPOSE_NOTE_MIRROR = "导出的账目文件用于浏览与对账，不能用于恢复数据；整机备份请用导出备份"
        const val CONSISTENCY_NOTE_MIRROR = "导出过程中账本发生变动时，文件可能出现重复或缺失行；导出不承诺时点快照。"

        /** The surface and drawer markers the a11y leg walks. */
        const val SURFACE_TITLE = "导出账目"
        const val DRAWER_EXPORT_ROW_TEXT = "导出账目"
        const val DRAWER_OPEN_BUTTON_DESCRIPTION = "打开菜单"
        const val UNWIRED_SUFFIX_TEXT = "暂未开放"
        const val START_BUTTON_TEXT = "开始导出"
        const val CANCEL_BUTTON_TEXT = "取消导出"
        const val EXIT_BUTTON_TEXT = "返回"

        /** The D-E10 banner prefixes that must be absent before any run landed. */
        val OUTCOME_BANNER_PREFIXES =
            listOf("已导出", "已取消", "账本未就绪", "读取账目失败", "写入导出文件失败", "备注超长", "存在无法确定的账目呈现")

        /** A11y walk pacing (the ImportScaleTraversal poll/pacing family). */
        const val POLL_MILLIS = 500L
        const val NODE_TIMEOUT_MILLIS = 30_000L
        const val HOME_READY_TIMEOUT_MILLIS = 120_000L
        const val MAX_TREE_NODES = 2_000
        const val CLICK_ANCESTOR_HOPS = 6

        /** logcat truncates a single long message; the grep-able prefix is the only anchor. */
        const val LOGCAT_CHUNK = 1_000
    }
}
