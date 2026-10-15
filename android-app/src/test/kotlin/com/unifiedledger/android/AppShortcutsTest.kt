package com.unifiedledger.android

import com.unifiedledger.ui.ExternalNavigationRequest
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
    fun thePublishPlanPublishesOncePerProcessAndNeverRisksAnEmptyPlatformCapacity() {
        // The call-site gate the product's `publishOnce` uses: the first caller plans the publish and
        // every later re-composition / Activity recreation is refused (null), so ordinary use never
        // approaches the platform's publish rate limit.
        val gate = AtomicBoolean(false)
        assertEquals(LEDGER_SHORTCUT_SPECS, shortcutPublishPlan(gate, 5))
        assertEquals(null, shortcutPublishPlan(gate, 5), "a second call must not re-publish")
        // A platform reporting no capacity yields an EMPTY plan (publishing would throw platform-side).
        val freshGate = AtomicBoolean(false)
        assertEquals(emptyList(), shortcutPublishPlan(freshGate, 0))
        // The gate is consumed even by the empty plan: the decision was made, so it is not retried.
        assertEquals(null, shortcutPublishPlan(freshGate, 5))
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
