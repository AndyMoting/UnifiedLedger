"""Offline negatives for the strict maximum-scale evidence reducer.

These tests never touch a device: they build synthetic evidence directories and
assert that `validate_evidence` refuses anything short of a complete, clean,
correctly identified run. The reducer is the only thing standing between a
partial or crashed long run and a green workflow, so the rejection paths that can
be reproduced offline are exercised here; the ones that need a real
`am instrument` transcript are registered as residuals in D-198.
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools" / "python"))

from android_scale.fixture import generate_fixture  # noqa: E402
import android_scale.runner as runner_module  # noqa: E402
from android_scale.result import (  # noqa: E402
    PHASES,
    STAGES,
    TEST_CLASS,
    TEST_METHOD,
    cross_check_manifest,
    validate_evidence,
)
from android_scale.runner import (  # noqa: E402
    ScaleDeadlineError,
    ScaleRunner,
    owned_serial,
    remaining_seconds,
)

SHA = "a" * 40
CONFIG = {
    "api": "36",
    "abi": "x86_64",
    "size": "1080x2400",
    "density": "420",
    "font_scale": "1.0",
    "locale": "zh-CN",
    "timezone": "Asia/Shanghai",
    "window_animation_scale": "1.0",
    "transition_animation_scale": "1.0",
    "animator_duration_scale": "1.0",
}
COUNTERS = {
    "preparedCandidates": 51000,
    "preparedRelations": 100000,
    "preparedDispositions": 100,
    "finalCandidates": 61000,
    "finalRelations": 150000,
    "observedCandidates": 61000,
    "mainGroupRelations": 50000,
    "groupDispositions": 50000,
    "formalTransactions": 1,
    "balancedPostings": 2,
}


def status_log(*, numtests: str = "1", code: int = -1, junit: str = "OK (1 test)",
               records: int = 2, test_name: str = TEST_METHOD) -> str:
    lines = []
    for index in range(records):
        lines += [
            f"INSTRUMENTATION_STATUS: class={TEST_CLASS}",
            f"INSTRUMENTATION_STATUS: test={test_name}",
            f"INSTRUMENTATION_STATUS: numtests={numtests}",
            f"INSTRUMENTATION_STATUS_CODE: {1 if index == 0 else 0}",
        ]
    if code is not None:
        lines.append(f"INSTRUMENTATION_CODE: {code}")
    if junit:
        lines.append(junit)
    return "\n".join(lines) + "\n"


def build_valid(directory: Path, *, sha: str = SHA) -> None:
    """Write a complete, clean evidence set that validate_evidence accepts."""
    directory.mkdir(parents=True, exist_ok=True)
    host = {
        "sha": sha,
        "status": "PASS",
        "timed_out": False,
        "crash_detected": False,
        "phases": list(PHASES),
        "apk_sha256": {"app": "b" * 64, "test": "c" * 64},
        "config": dict(CONFIG),
        "elapsed_seconds": 120.0,
    }
    (directory / "host.json").write_text(json.dumps(host), encoding="utf-8")
    stages = {}
    for index, stage in enumerate(STAGES):
        stages[stage] = {"status": "PASS", "startedMs": index * 1000, "elapsedMs": 10}
    device = {"schema": 1, "sha": sha, "stages": stages, "firstLastObserved": True,
              "uiIdentityScope": "projected-sequence-multiplicity-order"}
    device.update(COUNTERS)
    (directory / "device.json").write_text(json.dumps(device), encoding="utf-8")
    for phase in PHASES:
        (directory / f"instrumentation-{phase}.txt").write_text(status_log(), encoding="utf-8")
    root = ET.Element("testsuite", name="AndroidMaximumScale", tests="5", failures="0",
                      errors="0", skipped="0")
    for phase in PHASES:
        ET.SubElement(root, "testcase", name=phase, classname=TEST_CLASS, time="1.0")
    ET.ElementTree(root).write(directory / "junit.xml", encoding="utf-8", xml_declaration=True)
    (directory / "logcat.txt").write_text("I/ActivityManager: start\n", encoding="utf-8")
    (directory / "memory.txt").write_text("phase=prepare total=1000\n", encoding="utf-8")
    (directory / "configuration.txt").write_text(json.dumps(CONFIG, sort_keys=True) + "\n", encoding="utf-8")


def load(directory: Path, name: str) -> dict:
    return json.loads((directory / name).read_text(encoding="utf-8"))


def save(directory: Path, name: str, data: dict) -> None:
    (directory / name).write_text(json.dumps(data), encoding="utf-8")


def junit(directory: Path, **attributes: str) -> None:
    root = ET.parse(directory / "junit.xml").getroot()
    for key, value in attributes.items():
        root.set(key, value)
    ET.ElementTree(root).write(directory / "junit.xml", encoding="utf-8", xml_declaration=True)


class ReducerRejectsPartialEvidence(unittest.TestCase):
    """Missing or short evidence must never be reported as success."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.directory = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def assert_rejected(self, *, sha: str = SHA):
        with self.assertRaises((ValueError, OSError, KeyError, ET.ParseError)):
            validate_evidence(self.directory, sha)

    def test_accepts_the_complete_clean_set(self):
        build_valid(self.directory)
        self.assertEqual(validate_evidence(self.directory, SHA)["status"], "PASS")

    def test_fewer_junit_cases_is_rejected(self):
        build_valid(self.directory)
        junit(self.directory, tests="4")
        self.assert_rejected()

    def test_extra_or_missing_junit_case_is_rejected(self):
        build_valid(self.directory)
        root = ET.parse(self.directory / "junit.xml").getroot()
        root.remove(root.findall("testcase")[0])
        ET.ElementTree(root).write(self.directory / "junit.xml", encoding="utf-8")
        self.assert_rejected()

    def test_junit_error_child_is_rejected(self):
        build_valid(self.directory)
        root = ET.parse(self.directory / "junit.xml").getroot()
        ET.SubElement(root.findall("testcase")[0], "error", message="boom")
        ET.ElementTree(root).write(self.directory / "junit.xml", encoding="utf-8")
        self.assert_rejected()

    def test_unparseable_junit_is_rejected(self):
        build_valid(self.directory)
        (self.directory / "junit.xml").write_text("<testsuite", encoding="utf-8")
        self.assert_rejected()

    def test_missing_phase_log_is_rejected(self):
        build_valid(self.directory)
        (self.directory / f"instrumentation-{PHASES[0]}.txt").unlink()
        self.assert_rejected()

    def test_extra_phase_log_is_rejected(self):
        build_valid(self.directory)
        (self.directory / "instrumentation-stale.txt").write_text(status_log(), encoding="utf-8")
        self.assert_rejected()

    def test_single_instrumentation_record_is_rejected(self):
        build_valid(self.directory)
        (self.directory / f"instrumentation-{PHASES[0]}.txt").write_text(
            status_log(records=1), encoding="utf-8")
        self.assert_rejected()

    def test_wrong_numtests_is_rejected(self):
        build_valid(self.directory)
        (self.directory / f"instrumentation-{PHASES[0]}.txt").write_text(
            status_log(numtests="2"), encoding="utf-8")
        self.assert_rejected()

    def test_unexpected_test_identity_is_rejected(self):
        build_valid(self.directory)
        (self.directory / f"instrumentation-{PHASES[0]}.txt").write_text(
            status_log(test_name="someOtherMethod"), encoding="utf-8")
        self.assert_rejected()

    def test_missing_junit_completion_is_rejected(self):
        build_valid(self.directory)
        (self.directory / f"instrumentation-{PHASES[0]}.txt").write_text(
            status_log(junit=""), encoding="utf-8")
        self.assert_rejected()

    def test_missing_instrumentation_completion_is_rejected(self):
        build_valid(self.directory)
        (self.directory / f"instrumentation-{PHASES[0]}.txt").write_text(
            status_log(code=None), encoding="utf-8")
        self.assert_rejected()

    def test_instrumentation_failure_marker_is_rejected(self):
        build_valid(self.directory)
        (self.directory / f"instrumentation-{PHASES[0]}.txt").write_text(
            status_log() + "INSTRUMENTATION_FAILED: com.unifiedledger.android\n", encoding="utf-8")
        self.assert_rejected()

    def test_missing_host_report_is_rejected(self):
        build_valid(self.directory)
        (self.directory / "host.json").unlink()
        self.assert_rejected()

    def test_missing_device_report_is_rejected(self):
        build_valid(self.directory)
        (self.directory / "device.json").unlink()
        self.assert_rejected()

    def test_unsupported_device_schema_is_rejected(self):
        build_valid(self.directory)
        device = load(self.directory, "device.json")
        device["schema"] = 2
        save(self.directory, "device.json", device)
        self.assert_rejected()

    def test_missing_memory_sample_is_rejected(self):
        build_valid(self.directory)
        (self.directory / "memory.txt").unlink()
        self.assert_rejected()

    def test_empty_configuration_evidence_is_rejected(self):
        build_valid(self.directory)
        (self.directory / "configuration.txt").write_text("", encoding="utf-8")
        self.assert_rejected()

    def test_missing_stage_is_rejected(self):
        build_valid(self.directory)
        device = load(self.directory, "device.json")
        del device["stages"][STAGES[0]]
        save(self.directory, "device.json", device)
        self.assert_rejected()

    def test_extra_stage_is_rejected(self):
        build_valid(self.directory)
        device = load(self.directory, "device.json")
        device["stages"]["invented_stage"] = {"status": "PASS", "startedMs": 0, "elapsedMs": 1}
        save(self.directory, "device.json", device)
        self.assert_rejected()

    def test_stage_not_pass_is_rejected(self):
        build_valid(self.directory)
        device = load(self.directory, "device.json")
        device["stages"][STAGES[3]]["status"] = "FAIL"
        save(self.directory, "device.json", device)
        self.assert_rejected()

    def test_stage_ordering_violation_is_rejected(self):
        build_valid(self.directory)
        device = load(self.directory, "device.json")
        device["stages"][STAGES[2]]["startedMs"] = 0
        save(self.directory, "device.json", device)
        self.assert_rejected()

    def test_stage_timer_above_its_limit_is_rejected(self):
        build_valid(self.directory)
        device = load(self.directory, "device.json")
        device["stages"]["saf_import"]["elapsedMs"] = 1800001
        save(self.directory, "device.json", device)
        self.assert_rejected()


class ReducerRejectsWrongIdentityAndCrashes(unittest.TestCase):
    """Identity, configuration, oracle and crash evidence are all load-bearing."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.directory = Path(self._tmp.name)
        build_valid(self.directory)

    def tearDown(self):
        self._tmp.cleanup()

    def assert_rejected(self, *, sha: str = SHA):
        with self.assertRaises((ValueError, OSError, KeyError, ET.ParseError)):
            validate_evidence(self.directory, sha)

    def test_host_sha_mismatch_is_rejected(self):
        host = load(self.directory, "host.json")
        host["sha"] = "d" * 40
        save(self.directory, "host.json", host)
        self.assert_rejected()

    def test_device_sha_mismatch_is_rejected(self):
        device = load(self.directory, "device.json")
        device["sha"] = "d" * 40
        save(self.directory, "device.json", device)
        self.assert_rejected()

    def test_short_expected_sha_is_rejected(self):
        self.assert_rejected(sha="a" * 39)

    def test_host_not_pass_is_rejected(self):
        host = load(self.directory, "host.json")
        host["status"] = "ERROR"
        save(self.directory, "host.json", host)
        self.assert_rejected()

    def test_incomplete_phase_list_is_rejected(self):
        host = load(self.directory, "host.json")
        host["phases"] = list(PHASES)[:-1]
        save(self.directory, "host.json", host)
        self.assert_rejected()

    def test_host_timeout_flag_is_rejected(self):
        host = load(self.directory, "host.json")
        host["timed_out"] = True
        save(self.directory, "host.json", host)
        self.assert_rejected()

    def test_host_crash_flag_is_rejected(self):
        host = load(self.directory, "host.json")
        host["crash_detected"] = True
        save(self.directory, "host.json", host)
        self.assert_rejected()

    def test_device_configuration_mismatch_is_rejected(self):
        host = load(self.directory, "host.json")
        host["config"]["density"] = "480"
        save(self.directory, "host.json", host)
        self.assert_rejected()

    def test_device_font_scale_mismatch_is_rejected(self):
        host = load(self.directory, "host.json")
        host["config"]["font_scale"] = "1.3"
        save(self.directory, "host.json", host)
        self.assert_rejected()

    def test_disabled_animation_is_rejected(self):
        host = load(self.directory, "host.json")
        host["config"]["animator_duration_scale"] = "0.0"
        save(self.directory, "host.json", host)
        self.assert_rejected()

    def test_missing_apk_hashes_is_rejected(self):
        host = load(self.directory, "host.json")
        host["apk_sha256"] = {}
        save(self.directory, "host.json", host)
        self.assert_rejected()

    def test_malformed_apk_hash_is_rejected(self):
        host = load(self.directory, "host.json")
        host["apk_sha256"]["app"] = "not-a-hash"
        save(self.directory, "host.json", host)
        self.assert_rejected()

    def test_oracle_counter_drift_is_rejected(self):
        device = load(self.directory, "device.json")
        device["finalCandidates"] = 60000
        save(self.directory, "device.json", device)
        self.assert_rejected()

    def test_string_counter_is_rejected(self):
        device = load(self.directory, "device.json")
        device["preparedRelations"] = "100000"
        save(self.directory, "device.json", device)
        self.assert_rejected()

    def test_missing_ui_scope_evidence_is_rejected(self):
        device = load(self.directory, "device.json")
        device["uiIdentityScope"] = "row-count"
        save(self.directory, "device.json", device)
        self.assert_rejected()

    def test_missing_first_last_evidence_is_rejected(self):
        device = load(self.directory, "device.json")
        device["firstLastObserved"] = False
        save(self.directory, "device.json", device)
        self.assert_rejected()

    def test_driver_elapsed_time_bound_is_rejected(self):
        host = load(self.directory, "host.json")
        host["elapsed_seconds"] = 14401
        save(self.directory, "host.json", host)
        self.assert_rejected()

    def test_duplicate_json_key_is_rejected(self):
        host = (self.directory / "host.json").read_text(encoding="utf-8")
        (self.directory / "host.json").write_text(
            host.replace('{"sha"', '{"sha": "duplicate", "sha"', 1), encoding="utf-8")
        self.assert_rejected()

    def test_crash_in_logcat_is_rejected(self):
        (self.directory / "logcat.txt").write_text(
            "E/AndroidRuntime: FATAL EXCEPTION: main\n", encoding="utf-8")
        self.assert_rejected()

    def test_oom_in_logcat_is_rejected(self):
        (self.directory / "logcat.txt").write_text(
            "E/art: OutOfMemoryError: Failed to allocate\n", encoding="utf-8")
        self.assert_rejected()

    def test_anr_in_logcat_is_rejected(self):
        (self.directory / "logcat.txt").write_text(
            "W/ActivityManager: ANR in com.unifiedledger.android\n", encoding="utf-8")
        self.assert_rejected()


class RunnerGuardsAreOfflineTestable(unittest.TestCase):
    """The host driver's pure logic is exercised here, not first in the cloud."""

    def test_owned_serial_accepts_only_the_ci_emulator(self):
        devices = "List of devices attached\nemulator-5554\tdevice\n"
        self.assertEqual(owned_serial(devices, "ul-scale\nOK"), "emulator-5554")

    def test_owned_serial_rejects_second_device(self):
        devices = "List of devices attached\nemulator-5554\tdevice\nemulator-5556\tdevice\n"
        with self.assertRaises(ValueError):
            owned_serial(devices, "ul-scale\nOK")

    def test_owned_serial_rejects_foreign_avd_name(self):
        devices = "List of devices attached\nemulator-5554\tdevice\n"
        with self.assertRaises(ValueError):
            owned_serial(devices, "test\nOK")

    def test_owned_serial_rejects_non_device_state(self):
        devices = "List of devices attached\nemulator-5554\toffline\n"
        with self.assertRaises(ValueError):
            owned_serial(devices, "ul-scale\nOK")

    def test_remaining_seconds_caps_and_expires(self):
        self.assertEqual(remaining_seconds(deadline=100.0, now=0.0, cap=30), 30)
        self.assertEqual(remaining_seconds(deadline=100.0, now=90.0, cap=30), 10)
        with self.assertRaises(ScaleDeadlineError):
            remaining_seconds(deadline=100.0, now=100.0)

    def test_runner_refuses_outside_the_hosted_runner(self):
        with tempfile.TemporaryDirectory() as directory:
            saved = {name: os.environ.pop(name, None) for name in
                     ("GITHUB_ACTIONS", "RUNNER_ENVIRONMENT", "RUNNER_OS")}
            try:
                with self.assertRaises(ValueError):
                    ScaleRunner(Path(directory), Path(directory) / "evidence",
                                Path(directory) / "app.apk", Path(directory) / "test.apk", SHA)
            finally:
                for name, value in saved.items():
                    if value is not None:
                        os.environ[name] = value

    def test_runner_refuses_a_short_sha(self):
        with tempfile.TemporaryDirectory() as directory:
            os.environ.update(GITHUB_ACTIONS="true", RUNNER_ENVIRONMENT="github-hosted", RUNNER_OS="Linux")
            try:
                with self.assertRaises(ValueError):
                    ScaleRunner(Path(directory), Path(directory) / "evidence",
                                Path(directory) / "app.apk", Path(directory) / "test.apk", "abc")
            finally:
                for name in ("GITHUB_ACTIONS", "RUNNER_ENVIRONMENT", "RUNNER_OS"):
                    os.environ.pop(name, None)

    def test_reports_mark_unreached_phases_as_not_run(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            os.environ.update(GITHUB_ACTIONS="true", RUNNER_ENVIRONMENT="github-hosted", RUNNER_OS="Linux")
            try:
                runner = ScaleRunner(root, root / "evidence", root / "app.apk", root / "test.apk", SHA)
                runner.deadline = runner.started + 13800
                runner.cases.append({"name": PHASES[0], "status": "PASS", "seconds": 1.0})
                runner.report["status"] = "ERROR"
                runner.write_reports()
            finally:
                for name in ("GITHUB_ACTIONS", "RUNNER_ENVIRONMENT", "RUNNER_OS"):
                    os.environ.pop(name, None)
            host = json.loads((root / "evidence" / "host.json").read_text(encoding="utf-8"))
            self.assertEqual(host["sha"], SHA)
            self.assertLessEqual(host["elapsed_seconds"], 13800)
            suite = ET.parse(root / "evidence" / "junit.xml").getroot()
            self.assertEqual(suite.get("tests"), "5")
            self.assertEqual(suite.get("failures"), "0")
            self.assertEqual(suite.get("errors"), "0")
            self.assertEqual(suite.get("skipped"), "4")
            names = [case.get("name") for case in suite.findall("testcase")]
            self.assertEqual(names, list(PHASES))
            cases = suite.findall("testcase")
            self.assertIsNone(cases[0].find("skipped"))
            self.assertIsNotNone(cases[1].find("skipped"))

    def test_reports_are_refused_when_evidence_is_incomplete(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            os.environ.update(GITHUB_ACTIONS="true", RUNNER_ENVIRONMENT="github-hosted", RUNNER_OS="Linux")
            try:
                runner = ScaleRunner(root, root / "evidence", root / "app.apk", root / "test.apk", SHA)
                runner.write_reports()
                self.assertEqual(runner.report["status"], "ERROR")
            finally:
                for name in ("GITHUB_ACTIONS", "RUNNER_ENVIRONMENT", "RUNNER_OS"):
                    os.environ.pop(name, None)
            with self.assertRaises((ValueError, OSError, KeyError, ET.ParseError)):
                validate_evidence(root / "evidence", SHA)


class ReducerHardeningRejectsSubtleBypasses(unittest.TestCase):
    """Wrong types, extra status codes and malformed JSON must all be rejected."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.directory = Path(self._tmp.name)
        build_valid(self.directory)

    def tearDown(self):
        self._tmp.cleanup()

    def assert_rejected(self, *, sha: str = SHA):
        with self.assertRaises((ValueError, OSError, KeyError, ET.ParseError)):
            validate_evidence(self.directory, sha)

    def test_nonfinite_json_is_rejected(self):
        (self.directory / "host.json").write_text('{"sha": NaN}', encoding="utf-8")
        self.assert_rejected()

    def test_non_object_host_json_is_rejected(self):
        (self.directory / "host.json").write_text("[]", encoding="utf-8")
        self.assert_rejected()

    def test_boolean_schema_is_rejected(self):
        device = load(self.directory, "device.json")
        device["schema"] = True
        save(self.directory, "device.json", device)
        self.assert_rejected()

    def test_stage_timer_wrong_type_is_rejected(self):
        device = load(self.directory, "device.json")
        device["stages"][STAGES[0]]["elapsedMs"] = "10"
        save(self.directory, "device.json", device)
        self.assert_rejected()

    def test_zero_stage_timer_is_rejected(self):
        device = load(self.directory, "device.json")
        device["stages"][STAGES[0]]["elapsedMs"] = 0
        save(self.directory, "device.json", device)
        self.assert_rejected()

    def test_boolean_counter_is_rejected(self):
        # `true` compares equal to 1 in Python, so only the int type guard rejects it.
        device = load(self.directory, "device.json")
        device["formalTransactions"] = True
        save(self.directory, "device.json", device)
        self.assert_rejected()

    def test_extra_non_identity_status_code_is_rejected(self):
        path = self.directory / f"instrumentation-{PHASES[0]}.txt"
        path.write_text(status_log() + "INSTRUMENTATION_STATUS_CODE: -2\n", encoding="utf-8")
        self.assert_rejected()

    def test_wrong_instrumentation_code_is_rejected(self):
        (self.directory / f"instrumentation-{PHASES[0]}.txt").write_text(
            status_log(code=-2), encoding="utf-8")
        self.assert_rejected()

    def test_missing_logcat_is_rejected(self):
        (self.directory / "logcat.txt").unlink()
        self.assert_rejected()

    def test_junit_classname_mismatch_is_rejected(self):
        root = ET.parse(self.directory / "junit.xml").getroot()
        root.findall("testcase")[0].set("classname", "com.example.Other")
        ET.ElementTree(root).write(self.directory / "junit.xml", encoding="utf-8")
        self.assert_rejected()

    def test_junit_zero_time_is_rejected(self):
        root = ET.parse(self.directory / "junit.xml").getroot()
        root.findall("testcase")[0].set("time", "0")
        ET.ElementTree(root).write(self.directory / "junit.xml", encoding="utf-8")
        self.assert_rejected()

    def test_other_crash_signals_are_rejected(self):
        for signal_text in ("Fatal signal 11 (SIGSEGV)", "am_anr: com.unifiedledger.android",
                            "am_crash: com.unifiedledger.android", "INSTRUMENTATION_ABORTED"):
            with self.subTest(signal=signal_text):
                (self.directory / "logcat.txt").write_text(signal_text + "\n", encoding="utf-8")
                self.assert_rejected()


class ManifestCrossCheckBindsDeviceCounters(unittest.TestCase):
    """The independent gate must bind device counters to the generated manifest."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.directory = Path(self._tmp.name)
        build_valid(self.directory)
        self.device = load(self.directory, "device.json")
        self.manifest = {
            "profile": "maximum",
            "initial_candidates": 51000,
            "initial_duplicate_relations": 100000,
            "initially_confirmed_relations": 100,
            "final_candidates": 61000,
            "final_duplicate_relations": 150000,
            "new_session_duplicate_relations": 50000,
            "expected_formal_transactions_after_confirmation": 1,
        }

    def tearDown(self):
        self._tmp.cleanup()

    def test_matching_counters_are_accepted(self):
        cross_check_manifest(self.device, self.manifest)

    def test_counter_drift_is_rejected(self):
        self.manifest["final_duplicate_relations"] = 149999
        with self.assertRaises(ValueError):
            cross_check_manifest(self.device, self.manifest)

    def test_non_maximum_profile_is_rejected(self):
        self.manifest["profile"] = "parser-small"
        with self.assertRaises(ValueError):
            cross_check_manifest(self.device, self.manifest)

    def test_missing_manifest_field_is_rejected(self):
        del self.manifest["initial_candidates"]
        with self.assertRaises(ValueError):
            cross_check_manifest(self.device, self.manifest)


class RunnerRetriesAndDiagnosticsAreOfflineTestable(unittest.TestCase):
    """The host driver's first cloud run failed on a transient adb hiccup; the
    retry and the diagnostics that make such a failure readable are exercised
    here, offline."""

    def make_runner(self, root: Path) -> ScaleRunner:
        os.environ.update(GITHUB_ACTIONS="true", RUNNER_ENVIRONMENT="github-hosted", RUNNER_OS="Linux")
        self.addCleanup(
            lambda: [os.environ.pop(name, None)
                     for name in ("GITHUB_ACTIONS", "RUNNER_ENVIRONMENT", "RUNNER_OS")]
        )
        return ScaleRunner(root, root / "evidence", root / "app.apk", root / "test.apk", SHA)

    def test_command_error_carries_the_command_diagnostics(self):
        # A bare "command failed: adb (1)" is not diagnosable from the cloud log.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = self.make_runner(root)
            with self.assertRaises(RuntimeError) as caught:
                runner.command([sys.executable, "-c",
                                "import sys; sys.stderr.write('boom-marker'); sys.exit(3)"])
            message = str(caught.exception)
            self.assertIn("boom-marker", message)
            self.assertIn("(3)", message)

    def test_root_and_settle_retries_transient_failures(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = self.make_runner(root)
            calls = {"count": 0}

            def flaky(*_args, **_kwargs):
                calls["count"] += 1
                if calls["count"] <= 2:
                    raise RuntimeError("adb: device 'emulator-5554' not found")
                return ""

            runner.adb = flaky
            runner.root_and_settle(attempts=5)
            # Two failed attempts cost one call each; the successful attempt makes
            # three (wait-for-device, root, wait-for-device).
            self.assertEqual(calls["count"], 5)

    def test_root_and_settle_gives_up_loudly(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = self.make_runner(root)

            def always_failing(*_args, **_kwargs):
                raise RuntimeError("adb: device not found")

            runner.adb = always_failing
            with self.assertRaises(RuntimeError) as caught:
                runner.root_and_settle(attempts=2)
            self.assertIn("did not settle after 2 attempts", str(caught.exception))

    def test_run_records_the_error_message_in_host_json(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = self.make_runner(root)

            def boom():
                raise RuntimeError("adb root did not settle after 5 attempts: adb: device not found")

            runner.configure = boom
            self.assertEqual(runner.run(), 1)
            host = json.loads((root / "evidence" / "host.json").read_text(encoding="utf-8"))
            self.assertEqual(host["status"], "FAIL")
            self.assertEqual(host["errorType"], "RuntimeError")
            self.assertIn("did not settle", host["errorMessage"])
            self.assertEqual(host["phases"], [])

    def test_await_framework_waits_out_a_restarting_framework(self):
        # `sys.boot_completed` still reads 1 from the previous boot across a
        # framework restart, so the driver must probe a real framework command.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = self.make_runner(root)
            calls = {"count": 0}

            def restarting(*_args, **_kwargs):
                calls["count"] += 1
                if calls["count"] <= 2:
                    raise RuntimeError("command failed: adb (224): cmd: Failure calling service window: Broken pipe (32)")
                return "Physical size: 1080x2400"

            runner.adb = restarting
            runner.await_framework(timeout=60)
            self.assertEqual(calls["count"], 3)

    def test_await_framework_gives_up_at_its_deadline(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = self.make_runner(root)

            def never_ready(*_args, **_kwargs):
                raise RuntimeError("cmd: Failure calling service window: Broken pipe (32)")

            runner.adb = never_ready
            with self.assertRaises(ScaleDeadlineError):
                runner.await_framework(timeout=1)

    def test_pin_display_retries_until_the_override_sticks(self):
        # The framework can revert a single `wm size` write while it finishes its
        # boot-time display configuration.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = self.make_runner(root)
            reads = {"count": 0}

            def fake(*args, **_kwargs):
                if args[:3] == ("shell", "wm", "size") and len(args) == 3:
                    reads["count"] += 1
                    if reads["count"] == 1:
                        return "Physical size: 1080x1920\n"
                    return "Physical size: 1080x1920\nOverride size: 1080x2400\n"
                return ""

            runner.adb = fake
            runner.pin_display(attempts=3)
            self.assertEqual(reads["count"], 2)

    def test_pin_display_fails_loudly_when_the_override_never_sticks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = self.make_runner(root)
            runner.adb = lambda *_args, **_kwargs: "Physical size: 1080x1920\n"
            with self.assertRaises(RuntimeError) as caught:
                runner.pin_display(attempts=2)
            message = str(caught.exception)
            self.assertIn("1080x2400", message)
            self.assertIn("observed 1080x1920", message)

    def test_ensure_app_data_dir_resolves_launches_and_stops_the_app(self):
        # `pm install` does not create /data/user/0/<pkg> on this image, and
        # `run-as` needs it; the app must be launched once first, using the
        # component the platform itself resolves.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = self.make_runner(root)
            calls = []

            def record(*args, **_kwargs):
                calls.append(args)
                if args[:3] == ("shell", "cmd", "package"):
                    return "priority=0\ncom.unifiedledger.android/.MainActivity\n"
                return ""

            runner.adb = record
            runner.ensure_app_data_dir()
            self.assertEqual(calls[0], ("shell", "cmd", "package", "resolve-activity",
                                        "--brief", "com.unifiedledger.android"))
            self.assertEqual(calls[1], ("shell", "am", "start", "-W", "-n",
                                        "com.unifiedledger.android/.MainActivity"))
            self.assertEqual(calls[2], ("shell", "am", "force-stop", "com.unifiedledger.android"))

    def test_ensure_app_data_dir_retries_the_post_install_launcher_race(self):
        # ATMS answers START_CLASS_NOT_FOUND (result code=-92) for a fifth of a
        # second after a completed install; a single `am start` is not enough.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = self.make_runner(root)
            starts = {"count": 0}

            def fake(*args, **_kwargs):
                if args[:3] == ("shell", "cmd", "package"):
                    return "priority=0\ncom.unifiedledger.android/.MainActivity\n"
                if args[:3] == ("shell", "am", "start"):
                    starts["count"] += 1
                    if starts["count"] == 1:
                        raise RuntimeError("command failed: adb (1): Error type 3\n"
                                           "Error: Activity class does not exist.")
                    return ""
                return ""

            runner.adb = fake
            runner.ensure_app_data_dir(attempts=3)
            self.assertEqual(starts["count"], 2)

    def test_ensure_app_data_dir_fails_loudly_when_the_launcher_is_unresolvable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = self.make_runner(root)
            runner.adb = lambda *_args, **_kwargs: ""
            with self.assertRaises(RuntimeError) as caught:
                runner.ensure_app_data_dir(attempts=1)
            self.assertIn("launcher component not resolvable", str(caught.exception))

    def test_install_pushes_the_fixture_with_the_host_command(self):
        # `adb push` is a host command; `adb shell push` fails with exit 127
        # ("/system/bin/sh: push: inaccessible or not found").
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "fixture"
            generate_fixture(fixture)
            app = root / "app.apk"
            app.write_bytes(b"app")
            test = root / "test.apk"
            test.write_bytes(b"test")
            runner = self.make_runner(root)
            runner.fixture, runner.app, runner.test = fixture, app, test
            calls = []

            def record(*args, **_kwargs):
                calls.append(args)
                if args and args[0] == "install":
                    return "Success"
                if args[:3] == ("shell", "cmd", "package"):
                    return "priority=0\ncom.unifiedledger.android/.MainActivity\n"
                return ""

            runner.adb = record
            runner.install()
            pushed = ("push", str(fixture) + "/.", "/data/local/tmp/ul-scale/")
            self.assertIn(pushed, calls)
            self.assertNotIn(("shell",) + pushed, calls)

    def test_install_rejects_a_silent_install_failure(self):
        # `adb install` exits 0 even when the install fails; the verdict is in
        # the output text, and a silent failure surfaces much later as a
        # confusing "activity does not exist".
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "fixture"
            generate_fixture(fixture)
            app = root / "app.apk"
            app.write_bytes(b"app")
            test = root / "test.apk"
            test.write_bytes(b"test")
            runner = self.make_runner(root)
            runner.fixture, runner.app, runner.test = fixture, app, test
            runner.adb = lambda *_args, **_kwargs: "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]\n"
            with self.assertRaises(RuntimeError) as caught:
                runner.install()
            self.assertIn("INSTALL_FAILED_INSUFFICIENT_STORAGE", str(caught.exception))

    def test_no_host_command_is_invoked_through_the_shell(self):
        # Structural guard for the whole class of mistake: a host adb command
        # routed through `adb shell` fails on the device with exit 127.
        source = Path(runner_module.__file__).read_text(encoding="utf-8")
        host_commands = ("push", "pull", "install", "uninstall", "devices", "wait-for-device",
                         "root", "unroot", "logcat", "exec-out", "emu", "forward", "reverse",
                         "reboot", "bugreport", "sideload")
        offenders = [
            line.strip()
            for line in source.splitlines()
            if 'self.adb("shell"' in line and any(f'"{name}"' in line for name in host_commands)
        ]
        self.assertEqual([], offenders)


if __name__ == "__main__":
    unittest.main()
