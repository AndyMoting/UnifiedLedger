"""Static inventory guard for the D-303 migration test split.

D-195 registered the single long migration test class as the shard wall-clock
floor and required a test-count guard before it could be split. The split moves
the same 48 @Test methods into three classes behind a shared support base; this
guard fails if any of them is renamed, deleted or duplicated, and it also keeps
the three classes registered in the shard manifest.
"""
from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = ROOT / "ledger-data" / "src" / "jvmTest" / "kotlin" / "com" / "unifiedledger" / "data"
MANIFEST = ROOT / "tools" / "ci" / "ledger-data-shards.txt"
ORIGINAL = "LedgerDatabaseMigrationTest.kt"
SUPPORT = "MigrationTestSupport.kt"
CLASSES = {
    "LedgerMigrationV1ToV11Test": 12,
    "LedgerMigrationV11ToV19Test": 21,
    "LedgerMigrationV20ToV36Test": 15,
}
EXPECTED = [
    "confirmationOnMigratedV23AdvancesSeededReconciliationRow",
    "freshSchemaAndMigratedVersionOneHaveEquivalentSchemaMetadata",
    "freshSchemaCreatesEveryLedgerDataTableAtVersionTwenty",
    "freshV23EqualsMigratedV23ForP408SharedObjects",
    "freshV25EqualsMigratedV25ForP406CreditAndP407Owners",
    "lateV23ToV24FailureRollsBackSourceRebuildAndP408Rows",
    "lateV24ToV25FailureRollsBackCreditStructuresAndSpineRows",
    "lateV25ToV26FailureRollsBackProjectionObjectsAndKeepsLegacyRows",
    "lateV35ToV36FailureRollsBackCorrectionRebuildAndKeepsLegacyRows",
    "migrationFromVersionOnePreservesFormalRowsAndAddsTheCommitBoundary",
    "migrationFromVersionThreePreservesExpenseAndNoteCommitOwnersWithCurrentChain",
    "populatedV23ToV24RebuildPreservesFormalAndP408GraphForeignKeysAndGuards",
    "populatedV23ToV24RebuildPreservesImportChainAndMigrationSentinelsAcrossReopen",
    "populatedV24ToV25RebuildPreservesV1V2RowsAndAddsCreditStructuresAcrossReopen",
    "populatedV25ToV26ProjectionBackfillEqualsFreshEquivalentRows",
    "populatedV35ToV36CorrectionRebuildPreservesCommittedRowsValueForValue",
    "populatedVersionFivePreservesRg03AndFormalOwnersAtVersionSix",
    "populatedVersionFourPreservesIncomeOwnersAndCurrentChainAtVersionFive",
    "populatedVersionNinePreservesFormalRowsAndAddsRg07OwnersAtVersionTen",
    "populatedVersionSixPreservesRg04ManualOwnersAtVersionSeven",
    "populatedVersionThirteenRemovesOnlyDerivedAdjustmentColumnsAtVersionFourteen",
    "testMigrateFromV19ToV20_preservesDataAndEnforcesGuards",
    "testMigrateFromV19ToV20_rollsBackOnConflict",
    "versionEighteenToNineteenAddsRg12MatchGuardsAndPreservesRowsAcrossReopen",
    "versionEighteenToNineteenDataGuardRejectsTamperedRg12StateAndRollsBackAtomically",
    "versionEighteenToNineteenDdlFailureRollsBackEveryGuardChange",
    "versionElevenToTwelveDdlFailureRollsBackEveryRg09Owner",
    "versionElevenToTwelvePreservesFormalRowsAndCreatesEmptyRg09OwnersAcrossReopen",
    "versionFifteenToSixteenDdlFailureRollsBackEveryRg11Owner",
    "versionFifteenToSixteenPreservesFormalRowsAndCreatesEmptyRg11OwnersAcrossReopen",
    "versionFifteenWiresLendCollectCanonicalKindsAndProtectsRg08Guards",
    "versionFiveToSixDdlFailureRollsBackEveryStageAndRg04Owner",
    "versionFourToFiveDdlFailureRollsBackEveryRg03Table",
    "versionFourteenToFifteenDdlFailureRollsBackEveryRg08Owner",
    "versionFourteenToFifteenPreservesFormalRowsAndCreatesEmptyRg08OwnersAcrossReopen",
    "versionSeventeenToEighteenDdlFailureRollsBackEveryCategoryNameOwner",
    "versionSixToSevenDdlFailureRollsBackEveryNewOwner",
    "versionSixteenToSeventeenDdlFailureRollsBackEveryRg12Owner",
    "versionSixteenToSeventeenPreservesFormalRowsAndCreatesEmptyRg12OwnersAcrossReopen",
    "versionSixteenWiresPrepaidCanonicalKindsAndProtectsRg11Guards",
    "versionTenToElevenAddsConfirmedAtAndPreservesRowsAcrossReopen",
    "versionThirteenLatestHistoryMismatchRejectsMigrationAndRollsBackAtomically",
    "versionThirteenMissingHistoryRejectsMigrationAndRollsBackAtomically",
    "versionThirteenProjectionMismatchRejectsMigrationAndRollsBackAtomically",
    "versionThreeToFourDdlFailureRollsBackEveryStageAndCoreTable",
    "versionTwelveToThirteenPreservesFormalRowsAndCreatesEmptyRg10OwnersAcrossReopen",
    "versionTwentyTwoToTwentyThreeDdlFailureRollsBackEverySharedP408Owner",
    "versionTwentyTwoToTwentyThreeSeedsOnlyCurrentEligiblePostings",
]

def test_names(path: Path) -> list[str]:
    return re.findall(r"@Test\s*\n\s*fun ([A-Za-z0-9_]+)\(", path.read_text(encoding="utf-8"))

class SplitInventory(unittest.TestCase):
    def test_original_class_is_gone_and_the_support_base_exists(self):
        self.assertFalse((PACKAGE / ORIGINAL).exists(), "the monolithic test class must stay deleted")
        self.assertTrue((PACKAGE / SUPPORT).exists(), "the shared support base is missing")

    def test_each_class_keeps_its_share(self):
        for name, count in CLASSES.items():
            found = test_names(PACKAGE / (name + ".kt"))
            self.assertEqual(len(found), count, name)
            self.assertEqual(len(set(found)), count, name)

    def test_the_48_tests_are_present_exactly_once(self):
        found = []
        for name in CLASSES:
            found.extend(test_names(PACKAGE / (name + ".kt")))
        self.assertEqual(sorted(found), sorted(EXPECTED))
        self.assertEqual(len(found), len(set(found)))

    def test_manifest_lists_every_split_class_once(self):
        rows = [line.split("\t") for line in MANIFEST.read_text(encoding="utf-8").splitlines() if line.strip()]
        names = [row[1].strip() for row in rows]
        for name in CLASSES:
            qualified = "com.unifiedledger.data." + name
            listed = [row[0] for row in rows if row[1].strip() == qualified]
            self.assertEqual(len(listed), 1, "manifest must list " + qualified + " exactly once")
            self.assertIn(listed[0], {"1", "2", "3", "4", "5"}, name)
        self.assertNotIn("com.unifiedledger.data.LedgerDatabaseMigrationTest", names)
        self.assertEqual(len(names), len(set(names)), "the manifest must not list any class twice")

if __name__ == "__main__":
    unittest.main()
