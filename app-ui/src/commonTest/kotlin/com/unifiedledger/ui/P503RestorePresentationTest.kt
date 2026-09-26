package com.unifiedledger.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-06 06.D (D-182; spec sections 3/5.3/5.5/6): the pure presentation decisions of the restore
 * confirm & switch surface and the startup recovery face. Every load-bearing gate — the overview
 * entry, the two confirm gates, the 7-field preview, the typed outcome wording and the recovery
 * face's affordance visibility — is pinned here without a Compose harness.
 */
class P503RestorePresentationTest {
    private fun summary(
        accounts: Int = 3,
        categories: Int = 12,
        transactions: Int = 45,
        epochMillis: Long = 1_700_000_000_000L,
    ): RestorePreflightSummary =
        RestorePreflightSummary(
            containerFormatVersion = 1,
            containerSize = 2048L,
            sourceSchemaVersion = 1L,
            migratedSchemaVersion = 31L,
            sourceLedgerId = "ledger-a",
            targetLedgerId = "ledger-local-test",
            authenticatedArtifactSha256Hex = "ab".repeat(32),
            integrityOk = true,
            foreignKeyOk = true,
            domainOk = true,
            ledgerIdentityCount = 1,
            formalTableCount = 8,
            postingImbalanceCount = 0,
            accountsCount = accounts,
            categoriesCount = categories,
            transactionsCount = transactions,
            preflightEpochMillis = epochMillis,
        )

    private fun previewToken(): RestorePreflightToken = RestorePreflightToken("tok", 1, "ledger-local-test", ByteArray(32), null)

    private fun state(
        password: String = "",
        runningPreflight: Boolean = false,
        runningConfirm: Boolean = false,
        preview: RestorePreflightSummary? = null,
        token: RestorePreflightToken? = null,
    ): P503AppState.BackupRestore {
        val tokenValue =
            token
                ?: preview?.let {
                    // The token constructor is internal to the module; the tests are in it.
                    RestorePreflightToken("tok", 1, "ledger-local-test", ByteArray(32), null)
                }
        return P503AppState.BackupRestore(
            overview = P503AppState.OverviewEmpty(com.unifiedledger.application.LedgerCurrentState(com.unifiedledger.domain.LedgerId("ledger-local-test"), emptyList(), emptyList())),
            password = password,
            runningPreflight = runningPreflight,
            runningConfirm = runningConfirm,
            preview = preview,
            token = tokenValue,
        )
    }

    // ---------------------------------------------------------------- gates

    @Test
    fun theEntryIsVisibleExactlyWhenTheRestoreSurfaceIsWired() {
        assertTrue(backupRestoreEntryVisible(true))
        assertFalse(backupRestoreEntryVisible(false))
    }

    @Test
    fun thePreflightConfirmNeedsThePasswordRuleAndNoRunningOperation() {
        assertTrue(restorePreflightConfirmEnabled("password123", runningPreflight = false, runningConfirm = false))
        // The same 8-code-point minimum the export gate applies.
        assertFalse(restorePreflightConfirmEnabled("short", runningPreflight = false, runningConfirm = false))
        assertFalse(restorePreflightConfirmEnabled("password123", runningPreflight = true, runningConfirm = false))
        assertFalse(restorePreflightConfirmEnabled("password123", runningPreflight = false, runningConfirm = true))
    }

    @Test
    fun theReplaceConfirmationNeedsTheBoundPreviewAndTokenAndNoRunningOperation() {
        val preview = summary()
        assertTrue(restoreSwitchConfirmEnabled(state(preview = preview)))
        assertFalse(restoreSwitchConfirmEnabled(state()), "no preview, no confirmation")
        assertFalse(restoreSwitchConfirmEnabled(state(preview = preview, runningConfirm = true)))
        assertFalse(restoreSwitchConfirmEnabled(state(preview = preview, runningPreflight = true)))
    }

    // ---------------------------------------------------------------- the 7-field preview (spec 5.5)

    @Test
    fun thePreviewRendersExactlyTheSevenRuledFieldsInTheSpecOrder() {
        val lines = restorePreviewLines(summary())
        assertEquals(7, lines.size)
        assertTrue(lines[0].contains("版本 1") && lines[0].contains("2048"))
        assertTrue(lines[1].contains("1 → 31"))
        assertTrue(lines[2].contains("ledger-a") && lines[2].contains("ledger-local-test"))
        assertTrue(lines[3].contains("ab".repeat(32)))
        assertTrue(lines[4].contains("通过"))
        assertTrue(lines[5].contains("3 个账户") && lines[5].contains("12 个分类") && lines[5].contains("45 笔交易"))
        assertTrue(lines[6].contains("2023-11-14"), "the timestamp renders in ISO form: ${lines[6]}")
    }

    @Test
    fun thePreviewNeverContainsAPathOrAPassword() {
        val lines = restorePreviewLines(summary()).joinToString("\n")
        assertFalse(lines.contains("/"))
        assertFalse(lines.contains("password"))
    }

    // ---------------------------------------------------------------- typed banners

    @Test
    fun thePreflightBannerMapsEachTypedOutcome() {
        assertNull(restorePreflightOutcomeText(null))
        assertTrue(restorePreflightOutcomeText(RestorePreflightResult.PreviewReady(summary(), previewToken()))!!.contains("预检通过"))
        assertTrue(restorePreflightOutcomeText(RestorePreflightResult.Cancelled)!!.contains("已取消"))
        val rejected = restorePreflightOutcomeText(RestorePreflightResult.Rejected(com.unifiedledger.application.backup.BackupPreflightRejection.P706_CONTAINER_AUTHENTICATION_FAILED))
        assertTrue(rejected!!.contains("预检失败"))
        assertTrue(restorePreflightOutcomeIsError(RestorePreflightResult.Rejected(com.unifiedledger.application.backup.BackupPreflightRejection.P706_SOURCE_READ_FAILED)))
        assertFalse(restorePreflightOutcomeIsError(RestorePreflightResult.PreviewReady(summary(), previewToken())))
    }

    @Test
    fun theSwitchBannerMapsEveryTypedOutcomeIncludingTheRuntimeSuffix() {
        val committed = restoreSwitchOutcomeText(BackupRestoreSwitchResult.Committed(runtimeGeneration = 2))
        assertTrue(committed!!.contains("恢复已完成"))
        val rolledBack = restoreSwitchOutcomeText(BackupRestoreSwitchResult.RolledBack(runtimeGeneration = 1))
        assertTrue(rolledBack!!.contains("回退"))
        val stale = restoreSwitchOutcomeText(BackupRestoreSwitchResult.Stale(BackupRestoreStaleReason.GenerationSuperseded, BackupRestoreRuntimeOutcome.RestoredReady))
        assertTrue(stale!!.contains("重新执行预检"))
        assertTrue(!stale.contains("请重启应用"), "a restored runtime adds no restart suffix")
        val notRestored =
            restoreSwitchOutcomeText(BackupRestoreSwitchResult.Postponed(BackupRestorePostponeReason.QuiesceBlocked, BackupRestoreRuntimeOutcome.NotRestored))
        assertTrue(notRestored!!.contains("请重启应用"), "an unrestored runtime says so honestly")
        val aborted = restoreSwitchOutcomeText(BackupRestoreSwitchResult.AbortedBeforePublish(BackupRestoreAbortReason.InsufficientDiskSpace, BackupRestoreRuntimeOutcome.RestoredReady))
        assertTrue(aborted!!.contains("存储空间不足"))
        val postponed = restoreSwitchOutcomeText(BackupRestoreSwitchResult.Postponed(BackupRestorePostponeReason.ConfirmAlreadyRunning, BackupRestoreRuntimeOutcome.RestoredReady))
        assertTrue(postponed!!.contains("已有一个恢复流程"))
    }

    // ---------------------------------------------------------------- the startup recovery face (spec 5.3)

    @Test
    fun theRecoveryFaceShowsTheCandidatesAndOffersExactlyTheAvailableActions() {
        val adoptable =
            PointerMissingRecoveryState(
                candidates =
                    listOf(
                        PointerRecoveryCandidate(1, PointerRecoveryCandidateVerdict.Adoptable),
                        PointerRecoveryCandidate(2, PointerRecoveryCandidateVerdict.WrongVersion),
                    ),
                adoptableGeneration = 1,
                legacyUpgradeAvailable = true,
            )
        val lines = restoreRecoveryCandidateLines(adoptable)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("gen-1") && lines[0].contains("可采纳"))
        assertTrue(lines[1].contains("gen-2") && lines[1].contains("版本过旧"))
        assertTrue(restoreRecoveryAdoptVisible(adoptable))
        assertFalse(restoreRecoveryDiscardVisible(adoptable), "an adoptable candidate blocks the discard affordance")

        val discardOnly =
            PointerMissingRecoveryState(
                candidates = listOf(PointerRecoveryCandidate(1, PointerRecoveryCandidateVerdict.Unusable)),
                adoptableGeneration = null,
                legacyUpgradeAvailable = true,
            )
        assertFalse(restoreRecoveryAdoptVisible(discardOnly))
        assertTrue(restoreRecoveryDiscardVisible(discardOnly))

        val noBranch =
            PointerMissingRecoveryState(candidates = emptyList(), adoptableGeneration = null, legacyUpgradeAvailable = false)
        assertFalse(restoreRecoveryAdoptVisible(noBranch))
        assertFalse(restoreRecoveryDiscardVisible(noBranch), "the desktop shape renders no discard branch")
    }

    @Test
    fun theSessionTerminalTextNamesTheCauseAndTheProtection() {
        val text = restoreRecoveryText(BackupRestoreRecoveryCause.RollbackReopenFailed)
        assertTrue(text.contains("重新打开原账本失败"))
        assertTrue(text.contains("请退出并重启"))
        assertFalse(text.contains("/"), "no path ever reaches the face")
    }
}
