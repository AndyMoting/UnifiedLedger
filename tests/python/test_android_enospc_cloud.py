"""Offline regression proofs for the export half of the ENOSPC device rig (P706-A10, D-239 item 4).

The rig is a manual workflow (one AVD, one opted-in case) plus its driver
``tools/ci/android-enospc-cloud.py``. Everything here is static or pure: no
device, no network, no Gradle, no build. The device-side claim itself belongs to
a dispatched run; these tests pin the boundaries that make such a claim mean
something:

- the import case (the default) keeps the invocation it always had, so adding
  ``--case export`` cannot silently move the existing evidence chain;
- the export case names the class and method that really exist in the Kotlin rig,
  bound to the source rather than to a second hand-typed literal;
- the driver refuses an unknown case instead of falling back to the default;
- a report's stated case decides the transcript identity, so import evidence can
  never be relabelled as export evidence (or the reverse);
- the scope sentence differs per case, so a PASS from one rig is never presented
  as evidence for the other.

Every test below names, in its own body, the single edit that turns it red.
"""
from __future__ import annotations

import importlib.util
import json
import re
import tempfile
import unittest
from pathlib import Path

MODULE_NAME = "android_enospc_cloud"
DRIVER_PATH = ("tools", "ci", "android-enospc-cloud.py")
KOTLIN_DIR = ("android-app", "src", "androidTest", "kotlin", "com", "unifiedledger", "android")

IMPORT_CLASS = "com.unifiedledger.android.ImportEnospcInstrumentedTest"
IMPORT_METHOD = "aRealEnospcOnTheIntakeCommitIsTypedAndLeavesTheLedgerIntact"
EXPORT_CLASS = "com.unifiedledger.android.BackupExportEnospcInstrumentedTest"
EXPORT_METHOD = "aRealEnospcOnTheBackupTargetDeliveryIsTypedAndLeavesTheLedgerIntact"

ROOT = Path(__file__).resolve().parents[2]
SHA = "a" * 40


def driver():
    """Load tools/ci/android-enospc-cloud.py by path: its file name carries a hyphen."""
    path = ROOT.joinpath(*DRIVER_PATH)
    spec = importlib.util.spec_from_file_location(MODULE_NAME, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def kotlin_source(class_file: str) -> str:
    return ROOT.joinpath(*KOTLIN_DIR, class_file).read_text(encoding="utf-8")


def transcript(test_class: str, test_method: str) -> str:
    """One raw `am instrument -w -r` transcript in the runner's own shape."""
    return "\n".join([
        "INSTRUMENTATION_STATUS: class=" + test_class,
        "INSTRUMENTATION_STATUS: test=" + test_method,
        "INSTRUMENTATION_STATUS: numtests=1",
        "INSTRUMENTATION_STATUS_CODE: 1",
        "INSTRUMENTATION_STATUS: class=" + test_class,
        "INSTRUMENTATION_STATUS: test=" + test_method,
        "INSTRUMENTATION_STATUS: numtests=1",
        "INSTRUMENTATION_STATUS_CODE: 0",
        "INSTRUMENTATION_CODE: -1",
        "OK (1 test)",
    ]) + "\n"


def evidence(directory: Path, *, case: str, test_class: str, test_method: str) -> Path:
    """A minimal evidence directory carrying only what the identity/scope checks read."""
    directory.mkdir(parents=True, exist_ok=True)
    module = driver()
    (directory / "instrumentation-enospc.txt").write_text(
        transcript(test_class, test_method), encoding="utf-8")
    (directory / "result.json").write_text(json.dumps({
        "schema": 1,
        "mode": "enospc",
        "case": case,
        "rig": module.case_spec(case).rig,
        "scope": module.case_spec(case).scope,
        "sha": SHA,
        "status": "PASS",
        "timed_out": False,
        "crashed": False,
        "setup_complete": True,
        "instrumented_test": {
            "class": test_class, "method": test_method,
            "opt_in": module.OPT_IN_ARGUMENT + "=1",
            "numtests": 1, "skipped": False,
        },
        "apk_sha256": {"app": "b" * 64, "test": "c" * 64},
        "apk_producer": {},
        "disk": {},
        "cleanup": {},
        "config": {},
    }), encoding="utf-8")
    return directory


class CaseSelection(unittest.TestCase):
    """`--case` selects one case and never silently redefines the default."""

    def test_the_default_is_the_import_case_with_its_original_invocation(self):
        # Turns red if DEFAULT_CASE stops being "import", or if the import
        # invocation gains/loses/reorders any argument.
        module = driver()
        self.assertEqual("import", module.DEFAULT_CASE)
        self.assertEqual(module.TEST_CLASS, module.case_spec().test_class)
        self.assertEqual(module.TEST_METHOD, module.case_spec().test_method)
        self.assertEqual(
            ["adb", "-s", "emulator-5680", "shell", "am", "instrument", "-w", "-r",
             "-e", module.OPT_IN_ARGUMENT, "1", "-e", "class", IMPORT_CLASS,
             "com.unifiedledger.android.test/androidx.test.runner.AndroidJUnitRunner"],
            module.instrument_argv("emulator-5680"),
        )

    def test_case_import_matches_the_pre_change_chain_as_literals(self):
        # The import case is a recorded D-276 property: adding --case must not move
        # it. Everything here is a LITERAL of the pre-change chain (class, method,
        # opt-in argument, and the full argv), never a value read back from the
        # module, so a driver edit that relabels the import case cannot satisfy this
        # test by agreeing with itself.
        module = driver()
        pre_change_argv = [
            "adb", "-s", "emulator-5680", "shell", "am", "instrument", "-w", "-r",
            "-e", "ul.enospc", "1",
            "-e", "class", "com.unifiedledger.android.ImportEnospcInstrumentedTest",
            "com.unifiedledger.android.test/androidx.test.runner.AndroidJUnitRunner",
        ]
        # The argument-absent form (today's workflow before the case input) and the
        # explicit `--case import` form must both produce that exact chain.
        self.assertEqual(pre_change_argv, module.instrument_argv("emulator-5680"))
        self.assertEqual(pre_change_argv, module.instrument_argv("emulator-5680", "import"))
        spec = module.case_spec("import")
        self.assertEqual("com.unifiedledger.android.ImportEnospcInstrumentedTest", spec.test_class)
        self.assertEqual("aRealEnospcOnTheIntakeCommitIsTypedAndLeavesTheLedgerIntact", spec.test_method)
        self.assertEqual("import-intake-enospc", spec.rig)
        self.assertEqual("ul.enospc", module.OPT_IN_ARGUMENT)

    def test_the_export_case_changes_only_the_class_name(self):
        # Turns red if the export invocation stops being the import argv with a
        # different `class` value (e.g. a second opt-in argument, or a method
        # selector that would break the one-case numtests=1 contract).
        module = driver()
        expected = module.instrument_argv("emulator-5680")
        produced = module.instrument_argv("emulator-5680", "export")
        self.assertEqual(len(expected), len(produced))
        differing = [(a, b) for a, b in zip(expected, produced) if a != b]
        self.assertEqual([(IMPORT_CLASS, EXPORT_CLASS)], differing)

    def test_an_unknown_case_is_refused_rather_than_defaulted(self):
        # Turns red if case_spec falls back to DEFAULT_CASE on a KeyError, which
        # would run the wrong rig for a typo and still report a verdict.
        module = driver()
        for unknown in ("Export", "imprt", "", "both"):
            with self.subTest(case=unknown), self.assertRaises(ValueError):
                module.case_spec(unknown)
        with self.assertRaises(ValueError):
            module.instrument_argv("emulator-5680", "nope")

    def test_the_cli_exposes_the_case_on_run_and_validate(self):
        # Turns red if a subcommand loses --case, or if the validate default stops
        # being None (which would stop it following the report's own case field).
        source = ROOT.joinpath(*DRIVER_PATH).read_text(encoding="utf-8")
        self.assertIn('runner.add_argument("--case", default=DEFAULT_CASE', source)
        self.assertIn('validator.add_argument("--case", default=None', source)


class CaseIdentityMatchesTheKotlinRig(unittest.TestCase):
    """The driver's case identity is bound to the Kotlin source, not restated by hand."""

    def test_the_export_class_and_method_exist_in_the_rig_source(self):
        # Turns red if the class is renamed, if the @Test method is renamed, or if
        # the driver's literal drifts from the source.
        module = driver()
        source = kotlin_source("BackupExportEnospcInstrumentedTest.kt")
        self.assertIn("class BackupExportEnospcInstrumentedTest", source)
        self.assertIn("fun " + EXPORT_METHOD + "(", source)
        spec = module.case_spec("export")
        self.assertEqual(EXPORT_CLASS, spec.test_class)
        self.assertEqual(EXPORT_METHOD, spec.test_method)

    def test_the_opt_in_argument_and_fill_cap_match_the_export_rig(self):
        # Turns red if the export rig stops honouring the shared opt-in argument
        # (its @Before would then skip the driven run) or drops the 16 GiB cap the
        # driver's preflight mirrors.
        module = driver()
        source = kotlin_source("BackupExportEnospcInstrumentedTest.kt")
        self.assertIn('const val OPT_IN_ARGUMENT = "ul.enospc"', source)
        self.assertEqual("ul.enospc", module.OPT_IN_ARGUMENT)
        bound = re.search(r"const val MAX_FILL_BYTES = (\d+)L \* 1024 \* 1024 \* 1024", source)
        self.assertIsNotNone(bound, "the export rig's fill cap declaration moved; update the driver's mirror explicitly")
        self.assertEqual(int(bound[1]) * 1024 ** 3, module.MAX_FILL_BYTES)

    def test_the_export_rig_proves_a_real_enospc_not_an_injected_exception(self):
        # Turns red if the rig is weakened into an injected-throw fake: the D-239
        # gap is specifically that only synthetic writer exceptions existed, so the
        # real filler and the real target stream must both be present.
        source = kotlin_source("BackupExportEnospcInstrumentedTest.kt")
        self.assertIn("fillUntilEnospc", source)
        self.assertIn("FileOutputStream", source)
        self.assertIn("ENOSPC", source)
        self.assertIn("TARGET_WRITE_FAILED", source)
        # No hand-thrown IOException may stand in for the OS refusal.
        self.assertNotIn("throw IOException", source)
        self.assertNotIn("error(\"ENOSPC\")", source)

    def test_the_export_rig_asserts_the_staging_cleanup_and_the_ledger(self):
        # Turns red if an assertion the D-239 residual asks for is deleted: the
        # typed reason, the no-publish claim, the staging cleanup, and the
        # quick_check/pointer/generation integrity reads.
        source = kotlin_source("BackupExportEnospcInstrumentedTest.kt")
        for required in (
            "BackupExportFailure.TARGET_WRITE_FAILED",
            "snapshotFile.exists()",
            "containerFile.exists()",
            "PRAGMA quick_check",
            "PRAGMA foreign_key_check",
            "readActivePointer",
            "generationNames",
        ):
            with self.subTest(required=required):
                self.assertIn(required, source)


class CaseScopedEvidence(unittest.TestCase):
    """A PASS belongs to exactly one case, and the validator says which."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.module = driver()

    def test_the_report_case_decides_the_transcript_identity(self):
        # Turns red if validate_evidence stops consulting the report's `case` field
        # and hardcodes one identity. The discriminating signal: with the CORRECT
        # identity the validator proceeds past the identity gate and only then
        # trips over the deliberately absent collector log (a non-ValueError),
        # while a WRONG identity stops it AT the gate with a ValueError.
        directory = evidence(self.root, case="export", test_class=EXPORT_CLASS, test_method=EXPORT_METHOD)
        with self.assertRaises(OSError):
            self.module.validate_evidence(directory, SHA)
        self.module.instrumentation_pass(
            (directory / "instrumentation-enospc.txt").read_text(encoding="utf-8"),
            EXPORT_CLASS,
            EXPORT_METHOD,
        )

    def test_a_relabelled_report_cannot_borrow_the_other_cases_verdict(self):
        # Turns red if the identity check is dropped or widened: an import
        # transcript presented as export evidence (or the reverse) must fail AT the
        # gate with ValueError, not fall through to the missing-file error.
        directory = evidence(self.root, case="export", test_class=IMPORT_CLASS, test_method=IMPORT_METHOD)
        with self.assertRaises(ValueError):
            self.module.validate_evidence(directory, SHA)

    def test_an_explicit_case_overrides_the_report_and_can_disagree(self):
        # Turns red if the --case value is ignored by the validator, which would
        # let an operator believe they validated the case they asked for. As in the
        # identity test, a ValueError here is the GATE firing; the correct explicit
        # case instead falls through to the missing collector log.
        directory = evidence(self.root, case="export", test_class=EXPORT_CLASS, test_method=EXPORT_METHOD)
        with self.assertRaises(ValueError):
            self.module.validate_evidence(directory, SHA, "import")
        with self.assertRaises(OSError):
            self.module.validate_evidence(directory, SHA, "export")

    def test_a_well_formed_report_seeds_the_expected_identity(self):
        # Turns red if the report dictionary stops carrying the case identity, in
        # which case the summary and the validator would disagree about the rig.
        module = self.module
        self.assertEqual("import-intake-enospc", module.case_spec("import").rig)
        self.assertEqual("backup-export-enospc", module.case_spec("export").rig)
        self.assertNotEqual(module.case_spec("import").scope, module.case_spec("export").scope)


class ScopeSentence(unittest.TestCase):
    """Each rig states its own scope, so a PASS is never read as the other's."""

    def test_the_summary_carries_the_selected_cases_scope(self):
        # Turns red if build_summary hardcodes ENOSPC_SCOPE for every case, which
        # would present an export PASS as the DEF-IMPORT-ENOSPC-CRASH-01 evidence.
        module = driver()
        export_summary = module.build_summary({"status": "PASS", "sha": SHA, "case": "export"})
        self.assertIn(module.EXPORT_SCOPE, export_summary)
        self.assertNotIn(module.ENOSPC_SCOPE, export_summary)
        self.assertIn(EXPORT_CLASS, export_summary)
        import_summary = module.build_summary({"status": "PASS", "sha": SHA, "case": "import"})
        self.assertIn(module.ENOSPC_SCOPE, import_summary)
        self.assertIn(IMPORT_CLASS, import_summary)

    def test_a_report_without_a_case_still_summarises_as_the_import_rig(self):
        # Turns red if a report written before this batch (no `case` field) breaks
        # the summary, or starts being presented as export evidence.
        module = driver()
        summary = module.build_summary({"status": "PASS", "sha": SHA})
        self.assertIn(module.ENOSPC_SCOPE, summary)
        self.assertIn(IMPORT_CLASS, summary)

    def test_each_scope_sentence_refuses_the_regression_reading(self):
        # Turns red if either sentence loses the "not a required check / no P7
        # vector / does not change the PASS table" boundary.
        module = driver()
        for text in (module.ENOSPC_SCOPE, module.EXPORT_SCOPE):
            with self.subTest(scope=text[:40]):
                self.assertIn("not the ordinary device regression", text)
                self.assertIn("not a required check", text)
                self.assertIn("no P7 vector", text)
                self.assertIn("does not change the PASS table", text)


class OrdinaryRegressionUntouched(unittest.TestCase):
    """The export rig must stay opt-in and outside the 41-case inventory."""

    def test_the_export_class_is_excluded_from_the_ordinary_inventory(self):
        # Turns red if the exclusion is dropped from inventory.py: the CI step
        # "Check ordinary device source inventory" would then count 42 cases and
        # the 41-case manifest would mismatch.
        inventory = ROOT.joinpath("tools", "python", "android_scale", "inventory.py").read_text(encoding="utf-8")
        self.assertIn('"BackupExportEnospcInstrumentedTest"', inventory)
        manifest = ROOT.joinpath("tools", "ci", "android-instrumented-inventory.txt").read_text(encoding="utf-8")
        self.assertNotIn("Enospc", manifest)
        self.assertEqual(41, len([line for line in manifest.splitlines() if line.strip()]))

    def test_the_export_case_is_not_in_the_shard_plan(self):
        # Turns red if the class is added to the ordinary shard split, which would
        # run a disk-filling case beside 20 other cases on a shared AVD.
        shards = ROOT.joinpath("tools", "ci", "android-instrumented-shards.txt").read_text(encoding="utf-8")
        self.assertNotIn("BackupExportEnospcInstrumentedTest", shards)


if __name__ == "__main__":
    unittest.main()
