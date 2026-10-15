package com.unifiedledger.android

import com.unifiedledger.ui.ExternalNavigationRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P7-09 09.B (D-226; spec section 5 / J1/J3): the launcher shortcut face's PURE properties, asserted
 * on a plain JVM without Robolectric. `ShortcutInfo`/`ShortcutManager`/`Intent` construction needs
 * the Android framework (or a non-inlined stub), so the platform-free [LEDGER_SHORTCUT_SPECS]
 * projection carries everything that must be true of the published shortcuts: the stable ID set, the
 * generic (leak-free) labels, the route mapping and the platform-limit cap. The real launcher and
 * task-stack behaviour is 09.D's device leg and is NOT claimed here.
 */
class AppShortcutsTest {
    @Test
    fun thePublishedSetIsExactlyTheThreeGenericRoutes() {
        // Stable generic IDs: a re-publish updates by ID rather than duplicating a launcher entry.
        assertEquals(
            listOf(
                "com.unifiedledger.android.shortcut.NEW_EXPENSE",
                "com.unifiedledger.android.shortcut.NEW_INCOME",
                "com.unifiedledger.android.shortcut.OPEN_IMPORT",
            ),
            LEDGER_SHORTCUT_SPECS.map { it.id },
        )
        assertEquals(3, LEDGER_SHORTCUT_SPECS.map { it.id }.toSet().size, "IDs must be unique")
        // The order is the plan section 9.2 order, and the ranks agree with it.
        assertEquals(listOf(0, 1, 2), LEDGER_SHORTCUT_SPECS.map { it.rank })
    }

    @Test
    fun everyShortcutLabelIsGenericAndLeakFree() {
        // J1: the launcher can read shortcut metadata, so NO account name, category name or amount
        // may appear. The labels are exactly the three generic route names.
        assertEquals(listOf("记支出", "记收入", "导入"), LEDGER_SHORTCUT_SPECS.map { it.shortLabel })
        for (spec in LEDGER_SHORTCUT_SPECS) {
            assertTrue(spec.shortLabel.isNotBlank())
            assertTrue(spec.longLabel.isNotBlank())
            assertFalse(spec.shortLabel.any { it.isDigit() }, "a label must carry no amount literal: ${spec.shortLabel}")
            assertFalse(spec.longLabel.any { it.isDigit() }, "a label must carry no amount literal: ${spec.longLabel}")
        }
    }

    @Test
    fun everyShortcutRouteIsARecognizedShortcutActionAndCarriesNoPayload() {
        // J2: each shortcut delivers one explicit routing action and nothing else. The action must be
        // a member of the recognized family, and each maps to the neutral request of its own surface.
        assertEquals(ShortcutNavigationAction.ALL.toSet(), LEDGER_SHORTCUT_SPECS.map { it.route }.toSet())
        assertEquals(
            listOf(
                ExternalNavigationRequest.OpenNewEntry(com.unifiedledger.application.EntryType.EXPENSE),
                ExternalNavigationRequest.OpenNewEntry(com.unifiedledger.application.EntryType.INCOME),
                ExternalNavigationRequest.OpenImportTab,
            ),
            LEDGER_SHORTCUT_SPECS.map { ShortcutNavigationAction.requestFor(it.route) },
        )
    }

    @Test
    fun theSpecSetIsCappedByTheReportedPlatformLimitWithoutAssumingItsValue() {
        // Spec section 8 item 6: the concrete getMaxShortcutCountPerActivity() value is device
        // evidence (OPEN) and is NOT assumed. The cap is applied to whatever the platform reports,
        // and an unavailable/zero limit publishes nothing rather than risking the platform throw.
        val specs = LEDGER_SHORTCUT_SPECS
        assertEquals(specs, ledgerShortcutSpecsWithinLimit(specs, 5))
        assertEquals(specs, ledgerShortcutSpecsWithinLimit(specs, 3))
        assertEquals(specs.take(1), ledgerShortcutSpecsWithinLimit(specs, 1))
        assertEquals(emptyList(), ledgerShortcutSpecsWithinLimit(specs, 0))
        assertEquals(emptyList(), ledgerShortcutSpecsWithinLimit(specs, -1))
    }

    @Test
    fun thePublishSeamPublishesOncePerProcessAndNeverRisksAnEmptyPlatformCapacity() {
        // The call-site gate the product's `publishOnce` uses: the first caller schedules the publish
        // and every later re-composition / Activity recreation is refused, so ordinary use never
        // approaches the platform's publish rate limit.
        val gate = AtomicBoolean(false)
        val published = mutableListOf<List<LedgerShortcutSpec>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val record: (List<LedgerShortcutSpec>) -> Unit = { published += it }
        publishShortcutsOnce(gate, scope, { LEDGER_SHORTCUT_SPECS }, record)
        assertEquals(listOf(LEDGER_SHORTCUT_SPECS), published)
        publishShortcutsOnce(gate, scope, { LEDGER_SHORTCUT_SPECS }, record)
        assertEquals(1, published.size, "a second call must not re-publish")
        // An absent platform surface (a null plan) publishes nothing...
        val nullGate = AtomicBoolean(false)
        publishShortcutsOnce(nullGate, scope, { null }, record)
        assertEquals(1, published.size)
        // ...as does a platform reporting no usable capacity (an empty plan would make the platform
        // `addDynamicShortcuts` throw IllegalArgumentException, so it must never be published)...
        val emptyGate = AtomicBoolean(false)
        publishShortcutsOnce(emptyGate, scope, { emptyList() }, record)
        assertEquals(1, published.size)
        // ...and the empty-plan gate is consumed too: the decision was made, so it is not retried.
        publishShortcutsOnce(emptyGate, scope, { LEDGER_SHORTCUT_SPECS }, record)
        assertEquals(1, published.size)
        scope.cancel()
    }

    @Test
    fun theActivityShortcutWiringGoesThroughTheSameChannelAndNeverWidensMain() {
        // The wiring itself (MainActivity's receipt branch, the publisher call) needs a real Activity
        // to execute, so it is pinned STRUCTURALLY on the source, the `LedgerWidgetSnapshotReaderTest`
        // / `AndroidRestoreWiringTest` precedent. The regression red line (T4): the shortcut branch is
        // recognized by the explicit shortcut actions and delivered into the SAME process-scoped
        // channel as a share — `ACTION_MAIN` is never accepted by it, and the share validator's
        // `ACTION_MAIN` refusal is untouched.
        val activity =
            repositoryFile("android-app/src/main/kotlin/com/unifiedledger/android/MainActivity.kt")
                .toFile()
                .readText()
        assertTrue(activity.contains("ShortcutNavigationAction.requestFor("), "the shortcut branch must recognize only the explicit shortcut actions")
        assertTrue(activity.contains("holder.externalNavigationChannel.deliver("), "a shortcut must land in the same navigation channel/gate as a share")
        assertTrue(activity.contains("coldLaunchShortcutRequest("), "a cold shortcut launch must reuse the process-scoped receipt identity")
        assertTrue(activity.contains("AppShortcuts.publishOnce("), "the composition must publish the dynamic shortcuts")
        val intake =
            repositoryFile("android-app/src/main/kotlin/com/unifiedledger/android/ExternalNavigationIntake.kt")
                .toFile()
                .readText()
        assertTrue(
            intake.contains("if (snapshot.action == Intent.ACTION_MAIN) return null"),
            "the share receipt's ACTION_MAIN refusal must stay (an ordinary launch is never an entry)",
        )
    }

    @Test
    fun theFailureLegPreservationWiringRespectsTheExplicitClearDecision() {
        // REVIEW REGRESSION PIN (the P2 found in review): the failure-leg preservation in P503App's
        // dispatch tail must be told when the transition was an EXPLICIT user clear (保留当前 / 放弃后
        // 切换). Without that signal it would re-hold the startup-wait request right after the host's
        // `clearsExternalNavigation` branch nulled it, undoing the user's decision and pinning the Busy
        // strip on screen on every non-slot surface. The behaviour is asserted at the pure seam
        // (ExternalNavigationReducerTest.anExplicitUserClearIsNeverUndoneByTheFailureLegPreservation);
        // this pins the CALL SITE so the two cannot drift apart. Pinned structurally because the fold
        // runs inside the P503App composable, which has no JVM harness.
        val app =
            repositoryFile("app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt")
                .toFile()
                .readText()
        assertTrue(
            app.contains("startupHeldFallbackAfterLanding(landed, previousHeld, holdExplicitlyCleared = clearsExternalNavigation(event))"),
            "the preservation must pass the explicit-clear decision, not a hard-coded false",
        )
        assertTrue(
            app.contains("clearsExternalNavigation(event) -> hostHeldNavigation = null"),
            "the explicit-clear branch that nulls the fallback must remain",
        )
    }

    /**
     * Resolves a path relative to the repository root by walking up from the test working directory
     * to the settings file (the `AndroidRestoreWiringTest` precedent).
     */
    private fun repositoryFile(relative: String): Path {
        var candidate = Path.of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }
}
