package com.unifiedledger.android

import com.unifiedledger.application.CommitOnceInvocationTracker
import com.unifiedledger.application.ConfirmedExpenseTransactionFactory
import com.unifiedledger.application.ConfirmedManualExpenseCommitPort
import com.unifiedledger.application.ConfirmedManualExpenseIdSource
import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.CurrentVersionRow
import com.unifiedledger.application.ExecuteConfirmedManualExpense
import com.unifiedledger.application.ExecuteManualExpenseSave
import com.unifiedledger.application.ExecuteManualExpenseSubmission
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.ManualExpenseCommitRecord
import com.unifiedledger.application.ManualExpenseRequestIdSource
import com.unifiedledger.application.MonthlyContributionReadPort
import com.unifiedledger.application.MonthlyContributionReadResult
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.QueryLedgerCurrentState
import com.unifiedledger.application.QueryManualExpenseOptions
import com.unifiedledger.application.QueryWidgetMonthlySnapshot
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.ResolveManualExpenseCommitStatus
import com.unifiedledger.application.SummarizeLedgerActivity
import com.unifiedledger.application.WidgetMonthlyAmounts
import com.unifiedledger.application.WidgetMonthlySnapshot
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.ui.LedgerLeaseScope
import com.unifiedledger.ui.LedgerRuntimeOwner
import com.unifiedledger.ui.P503LedgerFacade
import com.unifiedledger.ui.WidgetSnapshotOutcome
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-09 09.C widget render-decision evidence (D-225 Q20; spec sections 6.1/6.4/6.5).
 *
 * Every display and privacy decision of the monthly widget is asserted here on the PURE functions
 * the provider calls, so no Android framework (no Robolectric, no real `RemoteViews`) is needed —
 * the `LedgerProcessHolder`/`ExternalNavigationIntake` JVM-seam discipline. The tests pin the three
 * spec section 6.5 payloads the reader can own: the visible lines, the `contentDescription`, and the
 * `PendingIntent` route payload; the config state the hidden-state set depends on; and the section
 * 6.4 generation/month/revision landing discard.
 *
 * The `P709-Axx` tags in comments are intent labels only; no P709 vector is claimed PASS here (the
 * device/TalkBack legs belong to the acceptance batch, spec section 9).
 */
class LedgerWidgetSnapshotReaderTest {
    private val ledgerId = LedgerId("ledger-widget-reader")
    private val cny = CurrencyUnit("CNY", 2)
    private val march = YearMonth(2026, 3)

    @Test
    fun aHiddenWidgetCarriesNoPlaintextAmountInAnyPayload() {
        // P709-A07 intent (spec section 6.5(a)-(c)): with the setting hidden (the default), NO
        // payload the reader owns may contain the amount digit run "1234.56" or any of the three
        // formatted amounts. The amounts ARE present in the snapshot, so a leak would be detected.
        val snapshot = snapshot(ordinaryIncome = 123_456L, netExpense = -78_900L, balance = 202_356L)
        val state = widgetViewState(march, WidgetSnapshotOutcome.Read(snapshot), LedgerWidgetConfig.hidden(), generation = 5, widgetId = 7)
        assertTrue(state.amountLines.isEmpty(), "a hidden widget must render no amount line")
        for (payload in listOf(state.statusText, state.updateText.orEmpty(), state.contentDescription, state.clickRoute.name)) {
            assertFalse(payload.contains("1234.56"), "hidden payload must not carry the income amount: $payload")
            assertFalse(payload.contains("789.00"), "hidden payload must not carry the net expense: $payload")
            assertFalse(payload.contains("2023.56"), "hidden payload must not carry the balance: $payload")
            assertFalse(payload.contains("123456"), "hidden payload must not carry a raw minor-unit run: $payload")
        }
        // The month label is not an amount; it is the only data-bearing line a hidden widget shows.
        assertTrue(state.contentDescription.contains("2026-03"))
    }

    @Test
    fun aVisibleWidgetShowsExactlyTheThreeExactMinorUnitAmounts() {
        // Spec section 6.2: the three amounts are exact integer minor units, formatted once.
        val snapshot = snapshot(ordinaryIncome = 123_456L, netExpense = -78_900L, balance = 202_356L)
        val state = widgetViewState(march, WidgetSnapshotOutcome.Read(snapshot), LedgerWidgetConfig.hidden().withAmounts(true), 5, 7)
        assertEquals(3, state.amountLines.size)
        assertTrue(state.amountLines[0].contains("1234.56"), state.amountLines[0])
        assertTrue(state.amountLines[1].contains("-789.00"), state.amountLines[1])
        assertTrue(state.amountLines[2].contains("2023.56"), state.amountLines[2])
        // The visible body and the contentDescription are the SAME string set (同源朗读).
        for (line in state.amountLines) assertTrue(state.contentDescription.contains(line), "contentDescription must mirror the visible line: $line")
    }

    @Test
    fun everyNonAvailableOutcomeRendersExplicitCopyAndNeverAZero() {
        // P709-A05/A06 intent (spec sections 6.4): a failure must never be rendered as a value. Each
        // non-available status has explicit copy, and the amount lines stay empty whatever the
        // visibility setting — a failure has no amount to format.
        val invalid = WidgetSnapshotOutcome.Read(WidgetMonthlySnapshot.invalid(ledgerId, march, 3))
        val unavailable = WidgetSnapshotOutcome.Read(WidgetMonthlySnapshot.unavailable(ledgerId, march, 3))
        val visible = LedgerWidgetConfig.hidden().withAmounts(true)
        for (outcome in listOf(invalid, unavailable, WidgetSnapshotOutcome.SurfaceAbsent, WidgetSnapshotOutcome.RuntimeNotReady)) {
            val state = widgetViewState(march, outcome, visible, generation = 3, widgetId = 1)
            assertTrue(state.amountLines.isEmpty(), "a non-available outcome must never render an amount line")
            assertTrue(state.statusText.isNotBlank())
            assertFalse(state.statusText.contains("0.00"), "a failure must never masquerade as zero: ${state.statusText}")
            assertFalse(state.contentDescription.contains("0.00"), "a failure's contentDescription must not carry a zero")
        }
        // A runtime that is not Ready guides the user to the app instead of showing anything.
        assertEquals(WIDGET_STATUS_OPEN_APP, widgetStatusText(WidgetSnapshotOutcome.RuntimeNotReady))
        assertEquals(WIDGET_STATUS_UNAVAILABLE, widgetStatusText(WidgetSnapshotOutcome.SurfaceAbsent))
        // An available EMPTY month is a genuine zero, and it is rendered as a real 0.00 when visible.
        val emptyAvailable = WidgetSnapshotOutcome.Read(emptySnapshot())
        assertEquals(3, widgetViewState(march, emptyAvailable, visible, 3, 1).amountLines.size)
        assertTrue(widgetViewState(march, emptyAvailable, visible, 3, 1).amountLines[0].contains("0.00"))
    }

    @Test
    fun theClickRouteIsTheExistingOpenCurrentMonthRequestNotANewOne() {
        // Spec sections 6.1/6.5 + J5: the widget click reuses the EXISTING neutral request family.
        // The route is the generic OpenCurrentMonth surface (ExternalNavigation.kt:34-35), it carries
        // no amount and no widget data, and the widget must NOT add a fourth neutral request type.
        val snapshot = snapshot(ordinaryIncome = 123_456L, netExpense = -78_900L, balance = 202_356L)
        val state = widgetViewState(march, WidgetSnapshotOutcome.Read(snapshot), LedgerWidgetConfig.hidden(), 5, 7)
        assertEquals(WidgetClickRoute.OPEN_CURRENT_MONTH, state.clickRoute)
        assertFalse(state.clickRoute.name.contains("1234"))
        assertEquals(7, state.widgetId)
        // The click path in the provider maps the click to the existing request, never a new type.
        val provider = repositoryFile("android-app/src/main/kotlin/com/unifiedledger/android/LedgerWidgetProvider.kt").toFile().readText()
        assertTrue(
            provider.contains("ExternalNavigationRequest.OpenCurrentMonth"),
            "the widget click must reuse the existing OpenCurrentMonth neutral request (J5)",
        )
        val existing = repositoryFile("app-ui/src/commonMain/kotlin/com/unifiedledger/ui/ExternalNavigation.kt").toFile().readText()
        assertEquals(
            3,
            Regex("(data class|data object) Open\\w+").findAll(existing).count(),
            "the neutral request family must still have exactly three members (A6 frozen face)",
        )
    }

    @Test
    fun theHostBoundaryNoticeIsAlwaysPresentAndAmountFree() {
        // Spec section 6.5: the host-control / no-screenshot-recall disclosure must be present, and
        // it is amount-free, so it appears in the HIDDEN state as well.
        val snapshot = snapshot(ordinaryIncome = 123_456L, netExpense = -78_900L, balance = 202_356L)
        val hidden = widgetViewState(march, WidgetSnapshotOutcome.Read(snapshot), LedgerWidgetConfig.hidden(), 5, 7)
        assertEquals(WIDGET_HOST_NOTICE, hidden.hostNoticeText)
        assertTrue(hidden.contentDescription.contains(WIDGET_HOST_NOTICE), "the host notice must be read aloud too")
        for (payload in listOf(hidden.hostNoticeText, hidden.contentDescription)) {
            assertFalse(payload.contains("1234.56"), "the host notice must be amount-free: $payload")
        }
    }

    @Test
    fun theConfigDefaultsToHiddenAndItsRevisionAdvancesOnEveryToggle() {
        // Spec sections 6.1/6.4: the default is HIDDEN, and the config revision advances on every
        // change, so a late result computed under the old setting is discarded (P709-A07: hiding must
        // not let a stale visible result reappear).
        val hidden = LedgerWidgetConfig.hidden()
        assertFalse(hidden.showAmounts)
        assertEquals(0L, hidden.revision)
        val shown = hidden.withAmounts(true)
        assertTrue(shown.showAmounts)
        assertEquals(1L, shown.revision)
        val hiddenAgain = shown.withAmounts(false)
        assertFalse(hiddenAgain.showAmounts)
        assertEquals(2L, hiddenAgain.revision)
    }

    @Test
    fun aResultIsDiscardedOnAnyGenerationMonthOrRevisionMismatch() {
        // Spec section 6.4: the landing must match generation, month AND config revision.
        val current = WidgetRenderRequest(widgetId = 7, generation = 5, month = march, revision = 2)
        assertFalse(shouldDiscardWidgetResult(current, current.copy()))
        assertTrue(shouldDiscardWidgetResult(current.copy(generation = 4), current), "a superseded generation must discard")
        assertTrue(shouldDiscardWidgetResult(current.copy(month = YearMonth(2026, 2)), current), "a previous month must not land as this month")
        assertTrue(shouldDiscardWidgetResult(current.copy(revision = 1), current), "a stale config revision must discard")
        assertTrue(shouldDiscardWidgetResult(current.copy(widgetId = 8), current), "another widget's result must not land here")
    }

    @Test
    fun theViewStateTakesTheCapturedGenerationNotTheAmbientOne() {
        // The state records the generation the READ ran under, so the provider's landing hop has the
        // captured value to compare (spec section 6.4).
        val snapshot = snapshot(ordinaryIncome = 1L, netExpense = 0L, balance = 1L)
        val state = widgetViewState(march, WidgetSnapshotOutcome.Read(snapshot), LedgerWidgetConfig.hidden(), generation = 11, widgetId = 2)
        assertEquals(11, state.generation)
        assertEquals(0L, state.revision)
        assertEquals(march, state.month)
    }

    // ------------------------------------------------------------------ wiring seam

    @Test
    fun theLeaseScopedAccessorReadsTheSnapshotUnderTheOwnersLeaseAndCarriesItsGeneration() {
        // The delivery seam: `LedgerLeaseScope.widgetMonthlySnapshot` must run the read inside the
        // owner's operation lease (the app-ui owner choke point), resolve the expected catalog
        // version from the SAME facade, and hand back the snapshot carrying the acquire-time
        // generation. A read that bypassed the lease would return RuntimeNotReady here.
        val port = CountingPort()
        val owner =
            ownerFor(
                widgetFacade(port),
            )
        val scope = LedgerLeaseScope(owner)
        val outcome = scope.widgetMonthlySnapshot(march)
        val read = assertIs<WidgetSnapshotOutcome.Read>(outcome)
        assertEquals(1, port.reads, "the accessor must run the read exactly once inside the lease")
        assertEquals(march, read.snapshot.month)
        assertEquals(1, read.snapshot.generation, "the read must carry the acquire-time generation")
        assertEquals(9L, read.snapshot.catalogVersion)
        assertEquals(0, owner.inFlightLeaseCount, "the accessor must release its operation lease")
    }

    @Test
    fun theLeaseScopedAccessorIsUnavailableWhenTheWidgetSurfaceIsUnwired() {
        val scope = LedgerLeaseScope(ownerFor(ownerFacade(widgetWired = false)))
        assertEquals(WidgetSnapshotOutcome.SurfaceAbsent, scope.widgetMonthlySnapshot(march))
    }

    @Test
    fun theLeaseScopedAccessorIsNotReadyBeforeTheOwnerReachesReady() {
        // An owner that never started has no facade, so the accessor reports the typed not-Ready
        // result instead of running any read (spec section 6.3: the widget never opens a graph).
        val owner =
            LedgerRuntimeOwner(
                openGeneration = { Any() },
                closeGraph = {},
                facadeOf = { ownerFacade(widgetWired = true) },
            )
        assertEquals(WidgetSnapshotOutcome.RuntimeNotReady, LedgerLeaseScope(owner).widgetMonthlySnapshot(march))
    }

    @Test
    fun theWidgetMonthlySnapshotSurfaceProbeReflectsTheWiring() {
        assertTrue(LedgerLeaseScope(ownerFor(ownerFacade(widgetWired = true))).surfaces.widgetMonthlySnapshot)
        assertFalse(LedgerLeaseScope(ownerFor(ownerFacade(widgetWired = false))).surfaces.widgetMonthlySnapshot)
    }

    @Test
    fun theProviderTranslatesThePureViewStateInsteadOfFormattingAmountsItself() {
        // THE WIRING GUARD (the `AndroidRestoreWiringTest` source-pin precedent). The privacy
        // guarantee lives in the pure `widgetViewState`/`widgetAmountLines` functions; this test
        // asserts the production provider ACTUALLY calls them and writes only the strings they
        // return, so a rewrite that inlined its own amount formatting (bypassing the hidden-state
        // payload set) turns this test RED. It proves the call sites EXIST; the behavioural
        // assertions above prove the payload content on the real seam.
        val source = repositoryFile("android-app/src/main/kotlin/com/unifiedledger/android/LedgerWidgetProvider.kt").toFile().readText()
        assertTrue(
            source.contains("widgetViewState("),
            "the provider must build its display payload through the pure widgetViewState decision",
        )
        assertTrue(
            source.contains("shouldDiscardWidgetResult("),
            "the provider must apply the generation/month/revision landing discard before rendering",
        )
        assertTrue(
            source.contains("PendingIntent.FLAG_IMMUTABLE"),
            "the widget PendingIntents must declare FLAG_IMMUTABLE explicitly (spec section 6.1)",
        )
        assertTrue(
            source.contains("ledgerProcessHolderFor(context)"),
            "the provider must reach the runtime through the process-scoped holder, never a composition",
        )
        assertFalse(
            source.contains("buildLedgerGraph"),
            "the provider must never call the bootstrap/migrate graph builder (spec section 6.3)",
        )
        assertTrue(
            source.contains("ACTION_REFRESH"),
            "the user-initiated refresh leg (spec section 6.4) must be wired",
        )
        assertTrue(
            source.contains("state.hostNoticeText"),
            "the provider must render the host-boundary notice (spec section 6.5)",
        )
        assertTrue(
            source.contains("override fun onDeleted("),
            "the provider must clear the per-widget preference when a widget is removed (spec section 6.5)",
        )
        // QUAL-002: a per-widget failure must stay inside its own failure domain (renderOneSafely)
        // and the scope must carry an uncaught handler, so one widget cannot skip the rest or tear
        // the process down. QUAL-005: the invalidation enumeration runs inside the coroutine.
        assertTrue(
            source.contains("renderOneSafely("),
            "each widget must render inside its own failure domain (QUAL-002)",
        )
        assertTrue(
            source.contains("CoroutineExceptionHandler"),
            "the render scope must carry an uncaught-exception handler (QUAL-002)",
        )
        assertTrue(
            source.contains("provideIds: () -> IntArray"),
            "the invalidation id enumeration must run inside the render coroutine, off the main thread (QUAL-005)",
        )
    }

    @Test
    fun theProductionRootWiresTheWidgetSnapshotSurfaceOnTheAuthoritativeSessionVersion() {
        // ASSEMBLY GUARD (the `AndroidRestoreWiringTest` source-pin precedent): the Android root must
        // wire BOTH the widget snapshot use case and its expected-catalog-version resolver, and the
        // version must come from the SHARED authoritative session (the same source the budget read
        // uses), so a widget read cannot be built against a different generation than the session.
        val source = repositoryFile("android-app/src/main/kotlin/com/unifiedledger/android/App.kt").toFile().readText()
        assertTrue(source.contains("QueryWidgetMonthlySnapshot("), "the root must construct the widget snapshot use case")
        assertTrue(source.contains("queryWidgetMonthlySnapshot = queryWidgetMonthlySnapshot"), "the root must hand the use case to the facade")
        assertTrue(
            source.contains("widgetExpectedCatalogVersion = { session.authority.catalogVersion }"),
            "the widget's expected catalog version must resolve from the shared authoritative session",
        )
    }

    @Test
    fun theManifestDeclaresTheWidgetReceiverWithItsProviderInfo() {
        // The platform face the widget cannot work without: a receiver declared with the appwidget
        // provider metadata. Asserted on the manifest source (a device leg belongs to the acceptance
        // batch). The receiver is NOT exported in this batch (spec section 8 open item 15 leaves the
        // exported/permission RULING open; an unexported receiver is the conservative choice, and the
        // platform evidence that the system-driven update still reaches it is recorded in the
        // manifest comment).
        val manifest = repositoryFile("android-app/src/main/AndroidManifest.xml").toFile().readText()
        assertTrue(manifest.contains("android:name=\".LedgerWidgetProvider\""), "the widget receiver must be declared")
        assertTrue(manifest.contains("android.appwidget.provider"), "the receiver must declare its appwidget provider metadata")
        assertTrue(manifest.contains("android:resource=\"@xml/ledger_widget_info\""), "the provider metadata must point at the widget info resource")
        assertTrue(manifest.contains("android.appwidget.action.APPWIDGET_UPDATE"), "the receiver must handle the system update action")
        assertTrue(
            manifest.contains("com.unifiedledger.android.widget.ACTION_REFRESH"),
            "the user-initiated refresh action must be declared (spec section 6.4)",
        )
        assertTrue(
            manifest.contains("com.unifiedledger.android.widget.ACTION_OPEN_CURRENT_MONTH"),
            "the click route action must be declared (J5)",
        )
        assertTrue(manifest.contains("android:exported=\"false\""), "this batch declares the widget receiver non-exported")
    }

    @Test
    fun theProductionRootWiresTheWidgetInvalidationLegToTheProcessHolder() {
        // ASSEMBLY GUARD (the `AndroidRestoreWiringTest` source-pin precedent). This is a SOURCE-PIN:
        // it asserts the call-site LITERALS exist, not that the wiring behaves — replacing the
        // observer body with a no-op while keeping the literals (or leaving them in a comment) would
        // still pass. The BEHAVIOURAL coverage of the leg lives in the JVM coordinator tests
        // (`P503HostCoordinatorTest`), which count the observer invocations on the real success
        // branches; the two together are the evidence. The pin's job is only to catch a deletion or
        // rename of the production wiring in the Android root/holder/provider.
        val root = repositoryFile("android-app/src/main/kotlin/com/unifiedledger/android/App.kt").toFile().readText()
        assertTrue(
            root.contains("widgetInvalidation ="),
            "the Android root must pass the widget-invalidation observer to P503App (spec section 6.4)",
        )
        assertTrue(
            root.contains("invalidateAllWidgets()"),
            "the observer must invoke the process holder's widget fan-out",
        )
        val holder = repositoryFile("android-app/src/main/kotlin/com/unifiedledger/android/LedgerProcessHolder.kt").toFile().readText()
        assertTrue(
            holder.contains("LedgerWidgetProvider.reRenderAllWidgets(applicationContext)"),
            "the process-scoped holder must fan out to the widget provider's re-render (spec section 6.3)",
        )
        val provider = repositoryFile("android-app/src/main/kotlin/com/unifiedledger/android/LedgerWidgetProvider.kt").toFile().readText()
        assertTrue(
            provider.contains("fun reRenderAllWidgets(context: Context)"),
            "the provider must expose the re-render fan-out the holder binds",
        )
        assertTrue(
            provider.contains("getAppWidgetIds("),
            "the fan-out must enumerate the host-assigned widget ids (no full-ledger scan)",
        )
    }

    @Test
    fun theRestoreSwitchCommittedLandingAlsoInvalidatesTheWidgets() {
        // SOURCE-PIN (QUAL-003; spec section 6.4): 换库 (the backup-restore switch) is one of the
        // frozen merge-invalidation causes, so its Committed closeout in `P503App` must invalidate
        // the widgets beside its authoritative refresh. Source-pin only (it asserts the literals
        // exist); the restore-switch path itself is not JVM-exercisable here.
        val source = repositoryFile("app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt").toFile().readText()
        assertTrue(
            source.contains("BackupRestoreSwitchResult.Committed"),
            "the restore-switch Committed closeout must exist in the composition root",
        )
        assertTrue(
            source.contains("invalidateWidgetsBestEffort(currentWidgetInvalidation)"),
            "the restore-switch (换库) closeout must invalidate the widgets (spec section 6.4)",
        )
    }

    @Test
    fun theWidgetFanOutEnumeratesOnlyTheLiveIdsAndToleratesAnAbsentArray() {
        // The JVM-assertable seam of the fan-out: a real array is copied (so the render loop cannot
        // mutate the platform's array) and a defensive null/empty read is the honest "no widget
        // placed" case — zero work, never a crash. The Android framework itself is not exercised
        // here (a device leg belongs to the acceptance batch).
        val ids = intArrayOf(3, 7, 11)
        val copied = LedgerWidgetProvider.activeWidgetIds(ids)
        assertContentEquals(ids, copied)
        copied[0] = 99
        assertEquals(3, ids[0], "the fan-out must copy the platform array, not alias it")
        assertEquals(0, LedgerWidgetProvider.activeWidgetIds(IntArray(0)).size)
        assertEquals(0, LedgerWidgetProvider.activeWidgetIds(null).size)
    }

    /** Resolves a repository-relative path by walking up to the settings file (the precedent helper). */
    private fun repositoryFile(relative: String): java.nio.file.Path {
        var candidate =
            java.nio.file.Path
                .of(System.getProperty("user.dir"))
        repeat(8) {
            if (java.nio.file.Files
                    .isRegularFile(candidate.resolve("settings.gradle.kts"))
            ) {
                return candidate.resolve(relative)
            }
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }

    // ------------------------------------------------------------------ helpers

    private fun ownerFor(facade: P503LedgerFacade): LedgerRuntimeOwner<Any> {
        val owner =
            LedgerRuntimeOwner(
                openGeneration = { Any() },
                closeGraph = {},
                facadeOf = { facade },
            )
        owner.startup()
        return owner
    }

    /**
     * A facade whose widget snapshot surface is wired to read through [port], so the test can assert
     * the accessor actually issued the read under the owner's lease.
     */
    private fun widgetFacade(port: MonthlyContributionReadPort): P503LedgerFacade = ownerFacade(widgetWired = true, widgetPort = port)

    /** A minimal valid facade with the widget surface optionally wired. */
    private fun ownerFacade(
        widgetWired: Boolean,
        widgetPort: MonthlyContributionReadPort = EmptyPort(),
    ): P503LedgerFacade {
        val catalog = emptyCatalog()
        val readPort = emptyReadPort()
        val resolver = ResolveManualExpenseCommitStatus(readPort)
        val tracker =
            CommitOnceInvocationTracker(
                ConfirmedManualExpenseCommitPort { _, _, _, _ ->
                    ConfirmedManualExpenseResult.Rejected(DomainViolation.InvalidOrdinaryExpense)
                },
            )
        val submission =
            ExecuteManualExpenseSubmission(
                ExecuteManualExpenseSave(
                    ExecuteConfirmedManualExpense(
                        tracker,
                        ConfirmedManualExpenseIdSource { error("unused in widget tests") },
                        ConfirmedExpenseTransactionFactory { _, _ -> error("unused in widget tests") },
                    ),
                ),
                tracker,
                resolver,
            )
        return P503LedgerFacade(
            ledgerId = ledgerId,
            currency = cny,
            catalog = catalog,
            parseAmount = ParseManualExpenseAmount(),
            baseOptionsProvider = QueryManualExpenseOptions(ledgerId, catalog),
            baseQueryCurrentState = QueryLedgerCurrentState(readPort, ledgerId, catalog),
            resolveCommitStatus = resolver,
            submitExpense = submission,
            requestIdSource = ManualExpenseRequestIdSource { RequestId("request-widget-test") },
            ledgerClock = LedgerClock { Instant.parse("2026-03-20T05:00:00Z") },
            baseSummarizeActivity = SummarizeLedgerActivity(catalog),
            queryWidgetMonthlySnapshot =
                if (widgetWired) {
                    QueryWidgetMonthlySnapshot(widgetPort, LedgerClock { Instant.parse("2026-03-20T05:00:00Z") })
                } else {
                    null
                },
            widgetExpectedCatalogVersion = if (widgetWired) ({ 9L }) else ({ null }),
        )
    }

    private fun emptyCatalog(): LedgerCatalog = (LedgerCatalog.create(accounts = emptyList(), categories = emptyList()) as DomainResult.Success).value

    private fun emptyReadPort(): LedgerCurrentStateReadPort =
        object : LedgerCurrentStateReadPort {
            override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

            override fun findManualExpenseByRequest(
                ledgerId: LedgerId,
                requestId: RequestId,
            ): ManualExpenseCommitRecord? = null

            override fun findManualExpenseByReceipt(
                ledgerId: LedgerId,
                receipt: com.unifiedledger.application.ConfirmedExpenseReceipt,
            ): ManualExpenseCommitRecord? = null

            override fun findManualIncomeByRequest(
                ledgerId: LedgerId,
                requestId: RequestId,
            ): com.unifiedledger.application.ManualIncomeCommitRecord? = null

            override fun findManualIncomeByReceipt(
                ledgerId: LedgerId,
                receipt: com.unifiedledger.application.ConfirmedIncomeReceipt,
            ): com.unifiedledger.application.ManualIncomeCommitRecord? = null

            override fun findManualTransferByRequest(
                ledgerId: LedgerId,
                requestId: RequestId,
            ): com.unifiedledger.application.ManualTransferCommitRecord? = null

            override fun findManualTransferByReceipt(
                ledgerId: LedgerId,
                receipt: com.unifiedledger.application.ConfirmedTransferReceipt,
            ): com.unifiedledger.application.ManualTransferCommitRecord? = null
        }

    private inner class CountingPort : MonthlyContributionReadPort {
        var reads = 0

        override fun readContributions(
            ledgerId: LedgerId,
            startInclusive: Instant,
            endExclusive: Instant,
            expectedCatalogVersion: Long,
        ): MonthlyContributionReadResult {
            reads += 1
            return MonthlyContributionReadResult.Success(catalogVersion = expectedCatalogVersion, catalog = emptyCatalog(), rows = emptyList())
        }
    }

    private class EmptyPort : MonthlyContributionReadPort {
        override fun readContributions(
            ledgerId: LedgerId,
            startInclusive: Instant,
            endExclusive: Instant,
            expectedCatalogVersion: Long,
        ): MonthlyContributionReadResult = MonthlyContributionReadResult.Success(catalogVersion = expectedCatalogVersion, catalog = (LedgerCatalog.create(accounts = emptyList(), categories = emptyList()) as DomainResult.Success).value, rows = emptyList())
    }

    private fun snapshot(
        ordinaryIncome: Long,
        netExpense: Long,
        balance: Long,
    ): WidgetMonthlySnapshot =
        WidgetMonthlySnapshot.available(
            ledgerId = ledgerId,
            month = march,
            generation = 4,
            catalogVersion = 9L,
            amounts = WidgetMonthlyAmounts(cny, ordinaryIncome, netExpense, balance),
            asOf = Instant.parse("2026-03-20T05:00:00Z"),
        )

    private fun emptySnapshot(): WidgetMonthlySnapshot = snapshot(ordinaryIncome = 0L, netExpense = 0L, balance = 0L)
}
