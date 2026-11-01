"""Offline proofs for the two-shard ordinary Android device regression.

Everything here is static: manifest partitioning, transcript parsing, JUnit
generation, evidence validation, and the workflow's job wiring. No emulator,
no Gradle, and no network are involved.
"""
from __future__ import annotations

import ast
import copy
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from xml.etree import ElementTree as ET

from android_scale import apks, instrumented

ROOT = Path(__file__).resolve().parents[2]
INVENTORY = ROOT / "tools" / "ci" / "android-instrumented-inventory.txt"
SHARDS = ROOT / "tools" / "ci" / "android-instrumented-shards.txt"
WORKFLOW = ROOT / ".github" / "workflows" / "android-instrumented.yml"
SHA = "a" * 40


def status(case: str, code: int, *, extra: str = "") -> str:
    classname, _, name = case.partition("#")
    return (f"INSTRUMENTATION_STATUS: class={classname}\n"
            "INSTRUMENTATION_STATUS: current=1\n"
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n"
            "INSTRUMENTATION_STATUS: numtests=2\n"
            "INSTRUMENTATION_STATUS: stream=\n"
            f"INSTRUMENTATION_STATUS: test={name}\n"
            f"{extra}"
            f"INSTRUMENTATION_STATUS_CODE: {code}\n")


def ok_log(cases: tuple[str, ...] | list[str]) -> str:
    body = "".join(status(case, 1) + status(case, 0) for case in cases)
    return body + ("INSTRUMENTATION_RESULT: stream=\nTime: 0.4\n\n"
                   f"OK ({len(cases)} tests)\n\nINSTRUMENTATION_CODE: -1\n")


def failed_log(cases: list[str], failing: str, code: int = -2) -> str:
    body = ""
    for case in cases:
        body += status(case, 1)
        if case == failing:
            body += status(case, code, extra="INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: boom\n")
        else:
            body += status(case, 0)
    return body + ("INSTRUMENTATION_RESULT: stream=\nTime: 1.0\n\n"
                   f"FAILURES!!!\nTests run: {len(cases)},  Failures: 1\n\nINSTRUMENTATION_CODE: -1\n")


class ShardManifest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.inventory = self.root / "inventory.txt"
        self.inventory.write_text("# comment\ncom.example.A#one\ncom.example.A#two\ncom.example.B#three\n")
        self.shards = self.root / "shards.txt"
        self.shards.write_text("1\tcom.example.A\n2\tcom.example.B\n")

    def test_repository_split_partitions_the_ordinary_inventory(self):
        cases = instrumented.read_cases(INVENTORY)
        plan = instrumented.check_partition(INVENTORY, SHARDS)
        self.assertEqual(len(cases), 41)
        self.assertEqual(sorted(plan), [1, 2])
        self.assertEqual(sum(len(entries) for entries in plan.values()), 41)
        self.assertEqual(len({case for entries in plan.values() for case in entries}), 41)
        self.assertEqual(sorted(case for entries in plan.values() for case in entries), sorted(cases))
        self.assertEqual(len({case.split("#")[0] for case in cases}), 9)
        self.assertTrue(all(plan[shard] for shard in plan))

    def test_fixture_split_resolves_each_shard_subset(self):
        plan = instrumented.check_partition(self.inventory, self.shards)
        self.assertEqual(plan[1], ("com.example.A#one", "com.example.A#two"))
        self.assertEqual(plan[2], ("com.example.B#three",))

    def test_manifest_faults_are_rejected(self):
        faults = {
            "duplicate-class": "1\tcom.example.A\n2\tcom.example.A\n",
            "missing-class": "1\tcom.example.A\n",
            "unknown-class": "1\tcom.example.A\n2\tcom.example.B\n1\tcom.example.C\n",
            "unknown-shard": "1\tcom.example.A\n3\tcom.example.B\n",
            "space-separated": "1 com.example.A\n2\tcom.example.B\n",
            "empty": "# only comments\n",
        }
        for name, content in faults.items():
            with self.subTest(fault=name):
                self.shards.write_text(content)
                with self.assertRaises(ValueError):
                    instrumented.check_partition(self.inventory, self.shards)
        self.shards.write_text("1\tcom.example.A\n2\tcom.example.B\n")
        self.inventory.write_text("com.example.A#one\ncom.example.A#one\ncom.example.B#two\n")
        with self.assertRaises(ValueError):
            instrumented.check_partition(self.inventory, self.shards)
        self.inventory.write_text("com.example.A\ncom.example.B#two\n")
        with self.assertRaises(ValueError):
            instrumented.check_partition(self.inventory, self.shards)

    def test_class_assigned_twice_inside_one_shard_is_rejected(self):
        self.shards.write_text("1\tcom.example.A\n1\tcom.example.A\n2\tcom.example.B\n")
        with self.assertRaises(ValueError):
            instrumented.check_partition(self.inventory, self.shards)


class InstrumentationTranscript(unittest.TestCase):
    CASES = ("com.example.A#one", "com.example.A#two")

    def test_clean_run_parses_and_passes(self):
        log = ok_log(self.CASES)
        run = instrumented.parse_instrumentation(log)
        self.assertEqual(run.started, self.CASES)
        self.assertEqual(run.passed, self.CASES)
        self.assertEqual(run.failed, ())
        self.assertEqual(run.code, -1)
        self.assertEqual(run.reported, 2)
        self.assertEqual(run.markers, ())
        instrumented.check_run(run, self.CASES)

    def test_single_case_summary_is_accepted(self):
        run = instrumented.parse_instrumentation(ok_log(("com.example.A#one",)))
        self.assertEqual(run.reported, 1)
        instrumented.check_run(run, ("com.example.A#one",))

    def test_failure_error_and_skip_are_terminal(self):
        for code in (-2, -1, -3, -4):
            with self.subTest(code=code):
                run = instrumented.parse_instrumentation(failed_log(list(self.CASES), self.CASES[0], code))
                self.assertEqual(run.failed, ((self.CASES[0], code),))
                with self.assertRaises(ValueError):
                    instrumented.check_run(run, self.CASES)

    def test_missing_extra_duplicate_or_malformed_records_fail(self):
        cases = list(self.CASES)
        partial = instrumented.parse_instrumentation(ok_log((self.CASES[0],)))
        with self.assertRaises(ValueError):
            instrumented.check_run(partial, self.CASES)
        extra = instrumented.parse_instrumentation(ok_log(("com.example.A#one", "com.example.A#two", "com.example.C#three")))
        with self.assertRaises(ValueError):
            instrumented.check_run(extra, self.CASES)
        duplicated = instrumented.parse_instrumentation(
            status(self.CASES[0], 1) + status(self.CASES[0], 1)
            + status(self.CASES[0], 0) + status(self.CASES[1], 1) + status(self.CASES[1], 0)
            + "OK (2 tests)\nINSTRUMENTATION_CODE: -1\n")
        with self.assertRaises(ValueError):
            instrumented.check_run(duplicated, self.CASES)
        incomplete = instrumented.parse_instrumentation(ok_log(self.CASES).replace("INSTRUMENTATION_CODE: -1\n", ""))
        with self.assertRaises(ValueError):
            instrumented.check_run(incomplete, self.CASES)
        crashed = instrumented.parse_instrumentation(
            ok_log(self.CASES).replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: -1\nINSTRUMENTATION_RESULT: shortMsg=Process crashed."))
        with self.assertRaises(ValueError):
            instrumented.check_run(crashed, self.CASES)

    def test_transcript_without_test_identity_is_rejected_when_terminal(self):
        with self.assertRaises(ValueError):
            instrumented.parse_instrumentation(
                "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\nINSTRUMENTATION_STATUS_CODE: -1\n")
        with self.assertRaises(ValueError):
            instrumented.parse_instrumentation("INSTRUMENTATION_STATUS_CODE: nonsense\n")

    def test_junit_records_each_category_and_missing_cases(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            failed = instrumented.parse_instrumentation(failed_log(list(self.CASES), self.CASES[1], -2))
            instrumented.write_junit(root / "failed.xml", "AndroidInstrumentedShard1", self.CASES, failed)
            root_node = ET.parse(root / "failed.xml").getroot()
            self.assertEqual(root_node.get("tests"), "2")
            self.assertEqual(root_node.get("failures"), "1")
            self.assertEqual(root_node.get("errors"), "0")
            self.assertEqual(root_node.get("skipped"), "0")
            case = [node for node in root_node.findall("testcase") if node.get("name") == "two"][0]
            self.assertIsNotNone(case.find("failure"))

            skipped = instrumented.parse_instrumentation(failed_log(list(self.CASES), self.CASES[0], -3))
            instrumented.write_junit(root / "skipped.xml", "AndroidInstrumentedShard1", self.CASES, skipped)
            root_node = ET.parse(root / "skipped.xml").getroot()
            self.assertEqual(root_node.get("skipped"), "1")

            missing = instrumented.parse_instrumentation(ok_log((self.CASES[0],)))
            instrumented.write_junit(root / "missing.xml", "AndroidInstrumentedShard1", self.CASES, missing)
            root_node = ET.parse(root / "missing.xml").getroot()
            self.assertEqual(root_node.get("errors"), "1")
            case = [node for node in root_node.findall("testcase") if node.get("name") == "two"][0]
            self.assertEqual(case.find("error").get("message"), "expected case did not run")

            started_only = instrumented.InstrumentationRun((self.CASES[0],), (), (), None, None, ())
            instrumented.write_junit(root / "started.xml", "AndroidInstrumentedShard1", self.CASES, started_only)
            root_node = ET.parse(root / "started.xml").getroot()
            case = [node for node in root_node.findall("testcase") if node.get("name") == "one"][0]
            self.assertEqual(case.find("error").get("message"), "case started but never completed")


class ShardEvidence(unittest.TestCase):
    CASES = ("com.example.A#one", "com.example.A#two")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.inventory = self.root / "inventory.txt"
        self.inventory.write_text("com.example.A#one\ncom.example.A#two\ncom.example.B#three\n")
        self.shards = self.root / "shards.txt"
        self.shards.write_text("1\tcom.example.A\n2\tcom.example.B\n")
        self.evidence = self.root / "evidence"
        self.evidence.mkdir()
        self.report = {
            "schema": 2, "mode": "shard", "shard": 1, "sha": SHA, "avd": instrumented.avd_name(1),
            "runner": instrumented.RUNNER, "package": instrumented.PACKAGE,
            "classes": ["com.example.A"], "expected": sorted(self.CASES),
            "device": {"serial": "emulator-5554", "sdk": "36", "abi": "x86_64",
                       "size": "1080x2400", "density": "420", "cores": 2, "mem_total_kb": 2_000_000},
            "apk_sha256": {"app": "a" * 64, "test": "b" * 64},
            "instrumentation_exit": 0, "instrumentation": {"started": 2, "passed": 2, "failed": 0},
            "status": "PASS", "error": None,
        }
        self.write()

    def write(self):
        (self.evidence / "shard.json").write_text(json.dumps(self.report), encoding="utf-8")
        (self.evidence / "instrumentation-raw.txt").write_text(ok_log(self.CASES), encoding="utf-8")
        instrumented.write_junit(self.evidence / "junit-shard-1.xml", "AndroidInstrumentedShard1", self.CASES,
                                 instrumented.parse_instrumentation(ok_log(self.CASES)))
        (self.evidence / "logcat.txt").write_text("01-01 00:00:00.000 I/Test: ok\n", encoding="utf-8")

    def validate(self):
        return instrumented.validate(shard=1, evidence=self.evidence, inventory=self.inventory,
                                     shards=self.shards, sha=SHA)

    def test_complete_evidence_passes(self):
        self.assertEqual(self.validate(), 2)

    def test_each_evidence_fault_is_rejected(self):
        original = copy.deepcopy(self.report)
        mutations = {
            "avd": lambda: self.report.update(avd=instrumented.avd_name(2)),
            "device": lambda: self.report["device"].update(sdk="35"),
            "expected": lambda: self.report.update(expected=["com.example.B#three"]),
            "status": lambda: self.report.update(status="ERROR", error="boom"),
            "digest": lambda: self.report["apk_sha256"].update(app="short"),
            "shard": lambda: self.report.update(shard=2),
            "sha": lambda: self.report.update(sha="c" * 40),
        }
        for name, mutate in mutations.items():
            with self.subTest(fault=name):
                self.report = copy.deepcopy(original)
                mutate()
                self.write()
                with self.assertRaises(ValueError):
                    self.validate()
        self.report = copy.deepcopy(original)
        self.write()
        (self.evidence / "logcat.txt").unlink()
        with self.assertRaises(ValueError):
            self.validate()

    def test_transcript_and_junit_must_agree_with_the_manifest(self):
        (self.evidence / "instrumentation-raw.txt").write_text(ok_log((self.CASES[0],)), encoding="utf-8")
        with self.assertRaises(ValueError):
            self.validate()
        (self.evidence / "instrumentation-raw.txt").write_text(ok_log(self.CASES), encoding="utf-8")
        instrumented.write_junit(self.evidence / "junit-shard-1.xml", "AndroidInstrumentedShard1", (self.CASES[0],),
                                 instrumented.parse_instrumentation(ok_log((self.CASES[0],))))
        with self.assertRaises(ValueError):
            self.validate()

    def test_junit_with_a_nonzero_count_is_rejected(self):
        (self.evidence / "junit-shard-1.xml").write_text(
            '<testsuite tests="2" failures="1" errors="0" skipped="0">'
            '<testcase classname="com.example.A" name="one"/><testcase classname="com.example.A" name="two"/>'
            '</testsuite>', encoding="utf-8")
        with self.assertRaises(ValueError):
            self.validate()

    def test_shard_evidence_cannot_satisfy_the_fork_gate(self):
        with self.assertRaises(ValueError):
            instrumented.validate_fork(evidence=self.evidence, inventory=self.inventory, sha=SHA)


class ForkEvidence(unittest.TestCase):
    CASES = ("com.example.A#one", "com.example.B#two", "com.example.C#three")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.inventory = self.root / "inventory.txt"
        self.inventory.write_text("\n".join(self.CASES) + "\n", encoding="utf-8")
        self.shards = self.root / "shards.txt"
        self.shards.write_text("1\tcom.example.A\n2\tcom.example.B\n2\tcom.example.C\n", encoding="utf-8")
        self.evidence = self.root / "evidence"
        self.evidence.mkdir()
        self.report = {
            "schema": 2, "mode": "fork", "shard": None, "sha": SHA, "avd": instrumented.FORK_AVD,
            "runner": instrumented.RUNNER, "package": instrumented.PACKAGE,
            "classes": sorted({case.split("#")[0] for case in self.CASES}), "expected": sorted(self.CASES),
            "device": {"serial": "emulator-5554", "sdk": "36", "abi": "x86_64",
                       "size": "1080x2400", "density": "420", "cores": 2, "mem_total_kb": 2_000_000},
            "apk_sha256": {"app": "a" * 64, "test": "b" * 64},
            "instrumentation_exit": 0, "instrumentation": {"started": 3, "passed": 3, "failed": 0},
            "status": "PASS", "error": None,
        }
        self.write()

    def write(self):
        (self.evidence / "shard.json").write_text(json.dumps(self.report), encoding="utf-8")
        (self.evidence / "instrumentation-raw.txt").write_text(ok_log(self.CASES), encoding="utf-8")
        instrumented.write_junit(self.evidence / "junit-fork.xml", "AndroidInstrumentedFork", self.CASES,
                                 instrumented.parse_instrumentation(ok_log(self.CASES)))
        (self.evidence / "logcat.txt").write_text("01-01 00:00:00.000 I/Test: ok\n", encoding="utf-8")

    def test_whole_inventory_evidence_passes(self):
        self.assertEqual(instrumented.validate_fork(evidence=self.evidence, inventory=self.inventory, sha=SHA), 3)

    def test_fork_evidence_cannot_satisfy_a_shard_gate(self):
        with self.assertRaises(ValueError):
            instrumented.validate(shard=1, evidence=self.evidence, inventory=self.inventory,
                                  shards=self.shards, sha=SHA)

    def test_missing_or_extra_case_fails_the_fork_gate(self):
        (self.evidence / "instrumentation-raw.txt").write_text(ok_log(self.CASES[:2]), encoding="utf-8")
        with self.assertRaises(ValueError):
            instrumented.validate_fork(evidence=self.evidence, inventory=self.inventory, sha=SHA)


class CloudCommand(unittest.TestCase):
    def run_cli(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, "tools/ci/android-instrumented-cloud.py", *args],
                              cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

    def test_check_command_accepts_the_repository_split(self):
        result = self.run_cli("check", "--inventory", str(INVENTORY), "--shards", str(SHARDS))
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("PASS shard manifest", result.stdout)

    def test_check_command_rejects_a_tampered_split(self):
        with tempfile.TemporaryDirectory() as temp:
            shards = Path(temp) / "shards.txt"
            shards.write_text("1\tcom.unifiedledger.android.CcbBillParserAndroidInstrumentedTest\n", encoding="utf-8")
            result = self.run_cli("check", "--inventory", str(INVENTORY), "--shards", str(shards))
            self.assertEqual(result.returncode, 1)
            self.assertIn("FAIL check", result.stdout)

    def test_cli_exposes_the_fork_subcommands(self):
        result = self.run_cli("--help")
        self.assertEqual(result.returncode, 0, result.stdout)
        for action in ("check", "run", "validate", "run-fork", "validate-fork"):
            self.assertIn(action, result.stdout)


class ShardExecutionFailure(unittest.TestCase):
    def test_device_failure_still_writes_diagnosable_evidence(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            inventory = root / "inventory.txt"
            inventory.write_text("com.example.A#one\ncom.example.B#two\n", encoding="utf-8")
            shards = root / "shards.txt"
            shards.write_text("1\tcom.example.A\n2\tcom.example.B\n", encoding="utf-8")
            evidence = root / "evidence"
            with patch.object(instrumented, "_claim_device", side_effect=ValueError("no owned emulator")):
                self.assertEqual(instrumented.execute(shard=1, evidence=evidence, inventory=inventory,
                                                      shards=shards, app=root / "app.apk",
                                                      test=root / "test.apk", sha=SHA,
                                                      avd=instrumented.avd_name(1)), 1)
            report = json.loads((evidence / "shard.json").read_text(encoding="utf-8"))
            self.assertEqual(report["schema"], 2)
            self.assertEqual(report["mode"], "shard")
            self.assertEqual(report["status"], "ERROR")
            self.assertIn("no owned emulator", report["error"])
            junit = ET.parse(evidence / "junit-shard-1.xml").getroot()
            self.assertEqual(junit.get("tests"), "2")  # the expected case plus the setup error
            self.assertEqual(junit.get("errors"), "2")
            self.assertFalse((evidence / "logcat.txt").exists())


class WorkflowTopology(unittest.TestCase):
    def setUp(self):
        self.text = WORKFLOW.read_text(encoding="utf-8")

    def section(self, header: str) -> str:
        return self.text.split(header, 1)[1]

    def condition(self, header: str) -> str:
        match = re.search(r"(?m)^    if: (.+)$", self.section(header))
        self.assertIsNotNone(match)
        return match[1]

    def assert_condition(self, expression: str, *, event: str, head: str, expected: bool) -> None:
        expression = expression.replace("always() && ", "")
        text = (expression.replace("github.event.pull_request.head.repo.full_name", repr(head))
                .replace("github.repository", repr("example/ledger"))
                .replace("github.event_name", repr(event)).replace("&&", " and ").replace("||", " or "))
        parsed = ast.parse(text, mode="eval")
        self.assertIs(eval(compile(parsed, "workflow-condition", "eval"), {"__builtins__": {}}), expected)

    def test_producer_is_a_trusted_pack_and_upload_job(self):
        producer = self.section("  apks:\n")
        self.assertIn("    name: Android instrumented APKs\n", producer)
        self.assertIn("python3 tools/ci/android-apks.py reuse --directory reused-apks", producer)
        self.assertIn("if: steps.reuse.outputs.hit != 'true'", producer)
        self.assertIn("python3 tools/ci/android-apks.py pack --directory instrumented-apks", producer)
        self.assertIn("name: ${{ steps.bundle.outputs.artifact }}", producer)
        self.assertEqual(apks.PRODUCERS[".github/workflows/android-instrumented.yml"], "Android instrumented APKs")
        condition = self.condition("  apks:\n")
        for event, head, expected in (("workflow_dispatch", "", True),
                                      ("pull_request", "example/ledger", True),
                                      ("pull_request", "fork/ledger", False)):
            with self.subTest(event=event, head=head):
                self.assert_condition(condition, event=event, head=head, expected=expected)

    def test_two_shards_verify_the_bundle_and_run_the_cloud_runner(self):
        shard = self.section("  shard:\n")
        self.assertIn("        shard: [1, 2]", shard)
        self.assertIn("python3 tools/ci/android-apks.py verify --directory instrumented-apks", shard)
        self.assertIn("python3 tools/ci/android-instrumented-cloud.py run", shard)
        self.assertIn("--shard ${{ matrix.shard }}", shard)
        self.assertIn("python3 tools/ci/android-instrumented-cloud.py validate --shard ${{ matrix.shard }}", shard)
        self.assertIn("          avd-name: ul-instrumented-${{ matrix.shard }}", shard)
        for pinned in ("          profile: pixel_6", "          api-level: 36", "          target: google_apis",
                       "          arch: x86_64", "          cores: 2", "          ram-size: 2048M"):
            self.assertIn(pinned, shard)

    def test_emulator_script_never_runs_gradle(self):
        script = self.text.split("          script: >-\n", 1)[1].split("      - name: Require exactly", 1)[0]
        self.assertNotIn("gradlew", script)
        self.assertNotIn("connectedDebugAndroidTest", script)
        self.assertNotIn("connectedDebugAndroidTest", self.text)
        self.assertIn("--inventory tools/ci/android-instrumented-inventory.txt", script)
        self.assertIn("--shards tools/ci/android-instrumented-shards.txt", script)

    def test_fork_fallback_keeps_the_device_signal_without_a_trusted_bundle(self):
        fork = self.section("  fork-fallback:\n")
        self.assertIn("    name: Android instrumented fork fallback\n", fork)
        self.assertIn("python3 tools/ci/android-instrumented-cloud.py run-fork", fork)
        self.assertIn("python3 tools/ci/android-instrumented-cloud.py validate-fork", fork)
        self.assertIn("./gradlew --no-daemon --max-workers=1 :android-app:assembleDebug "
                      ":android-app:assembleDebugAndroidTest", fork)
        self.assertIn("          avd-name: ul-instrumented-fork", fork)
        self.assertNotIn("python3 tools/ci/android-apks.py", fork)
        self.assertNotIn("instrumented-apks", fork)
        self.assertEqual(instrumented.FORK_AVD, "ul-instrumented-fork")
        condition = self.condition("  fork-fallback:\n")
        for event, head, expected in (("pull_request", "fork/ledger", True),
                                      ("pull_request", "example/ledger", False),
                                      ("workflow_dispatch", "", False)):
            with self.subTest(event=event, head=head):
                self.assert_condition(condition, event=event, head=head, expected=expected)

    def test_gate_covers_both_paths_and_keeps_the_signal_name(self):
        gate = self.section("  instrumented:\n")
        self.assertIn("    name: Android instrumented tests\n", gate)
        self.assertIn("    needs: [apks, shard, fork-fallback]\n", gate)
        self.assertIn("    if: always()\n", gate)
        self.assertIn('if [ "${EVENT}" = "pull_request" ] && [ "${HEAD_REPO}" != "${BASE_REPO}" ]; then', gate)
        self.assertIn('if [ "${FORK}" != "success" ]; then', gate)
        self.assertIn('if [ "${APKS}" != "success" ] || [ "${SHARDS}" != "success" ]; then', gate)


if __name__ == "__main__":
    unittest.main()
