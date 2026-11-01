"""Offline regression proofs for real Android CI orchestration boundaries."""
from __future__ import annotations

import ast
import copy
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path
from unittest.mock import Mock, patch
from xml.etree import ElementTree as ET

from android_scale import apks
from android_scale.inventory import check_inventory
from android_scale.preflight import prepare_probe, probe_bytes, validate_preflight
from android_scale.result import PREFLIGHT_CLASS, PREFLIGHT_METHOD, TEST_CLASS, TEST_METHOD, validate_evidence
from android_scale.result import validate_app_ready, validate_log_collection
from android_scale.runner import PACKAGE, RUNNER, ScaleDeadlineError, ScaleRunner
from tests.python.test_android_scale_result import CONFIG, READY_XML, SHA, ready_adb_reply, ready_proof, status_log


class HostOrchestration(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        env = patch.dict(os.environ, GITHUB_ACTIONS="true", RUNNER_ENVIRONMENT="github-hosted", RUNNER_OS="Linux")
        env.start()
        self.addCleanup(env.stop)
        self.runner = ScaleRunner(self.root / "fixture", self.root / "evidence", self.root / "app.apk", self.root / "test.apk", SHA, "preflight")
        self.runner.app.write_bytes(b"app")
        self.runner.test.write_bytes(b"test")
        self.calls = []
        self.private = {}

    def adb(self, *args, **kwargs):
        self.calls.append(args)
        if args[:3] == ("shell", "am", "get-started-user-state"):
            return "RUNNING_UNLOCKED"
        if args[:4] == ("shell", "cmd", "package", "list"):
            return "package:android\n"
        if args[:3] == ("shell", "wm", "size"):
            return "Physical size: 1080x2400\n"
        if args[0] == "install":
            return "Success\n"
        if args[:3] == ("shell", "pm", "list"):
            return f"instrumentation:{RUNNER} (target={PACKAGE})\n"
        if args[:3] == ("shell", "pm", "path"):
            return "package:/data/app/base.apk\n"
        if args[:4] == ("shell", "cmd", "package", "resolve-activity"):
            return f"{PACKAGE}/.MainActivity\n"
        if args[:3] == ("shell", "am", "start"):
            return "Status: ok\n"
        if args[:4] == ("shell", "run-as", PACKAGE, "cp"):
            self.private[args[-1]] = (self.runner.fixture / args[-2].split("/")[-1]).read_bytes()
        if args[:4] == ("exec-out", "run-as", PACKAGE, "cat"):
            return self.private[args[-1]]
        return ready_adb_reply(args)

    def test_install_exercises_both_packages_launch_private_copy_and_readback(self):
        self.runner.adb = self.adb
        self.runner.install()
        self.assertTrue(self.runner.report["staging_verified"])
        self.assertEqual(self.private["files/scale-fixture/probe.txt"], probe_bytes(SHA))
        self.assertEqual(len([call for call in self.calls if call[0] == "install"]), 2)
        self.assertFalse(any("chown" in call or "restorecon" in call for call in self.calls))

    def test_starting_then_ready_precedes_normal_stop(self):
        observations = iter((READY_XML.replace("账本：synthetic", "正在打开本地账本…"), READY_XML))
        def controlled(*args, **kwargs):
            if args[:2] == ("exec-out", "cat"):
                self.calls.append(args)
                self.assertFalse(any("force-stop" in call for call in self.calls))
                return next(observations)
            return self.adb(*args, **kwargs)
        self.runner.adb = controlled
        with patch("android_scale.runner.time.sleep"):
            self.runner.ensure_app_data_dir()
        dumps = [call for call in self.calls if "dump" in call]
        self.assertEqual(len(dumps), 2)
        self.assertNotEqual(dumps[0][-1], dumps[1][-1])
        self.assertEqual(self.calls[-1], ("shell", "am", "force-stop", PACKAGE))
        validate_app_ready(self.runner.evidence, self.runner.report)

    def test_startup_error_and_pointer_recovery_take_priority_over_ready(self):
        for error in ("无法打开本地账本（本地数据库不可用）", "无法打开本地账本：缺少活动代指针，但磁盘上存在候选代目录。"):
            self.calls.clear()
            xml = READY_XML.replace("</hierarchy>", f'<node package="{PACKAGE}" text="{error}" bounds="[0,80][500,160]" /></hierarchy>')
            def controlled(*args, **kwargs):
                return xml if args[:2] == ("exec-out", "cat") else self.adb(*args, **kwargs)
            self.runner.adb = controlled
            with self.subTest(error=error), self.assertRaisesRegex(RuntimeError, "startup error/recovery"):
                self.runner.ensure_app_data_dir()
            self.assertEqual(len([call for call in self.calls if call[:3] == ("shell", "am", "start")]), 1)
            self.assertFalse(any("force-stop" in call for call in self.calls))
            self.assertFalse(any(call[:2] == ("shell", "rm") for call in self.calls))
            self.assertNotIn("app_ready", self.runner.report)

    def test_unknown_missing_malformed_foreign_or_invisible_xml_never_passes(self):
        samples = ("", "<hierarchy", "<unknown />", "<hierarchy><unknown /></hierarchy>",
                   READY_XML.replace(PACKAGE, "other.package"), READY_XML.replace("账本：synthetic", "unknown"),
                   READY_XML.replace('bounds=', 'visible-to-user="false" bounds='),
                   READY_XML.replace("[0,0][500,80]", "[0,0][0,0]"),
                   READY_XML.replace('bounds="[0,0][500,80]"', ""))
        for xml in samples:
            self.calls.clear()
            now = [100.0]
            self.runner.deadline = self.runner.active_deadline = 105
            def controlled(*args, **kwargs):
                return xml if args[:2] == ("exec-out", "cat") else self.adb(*args, **kwargs)
            self.runner.adb = controlled
            with self.subTest(xml=xml), patch("android_scale.runner.time.monotonic", side_effect=lambda: now[0]), \
                    patch("android_scale.runner.time.sleep", side_effect=lambda seconds: now.__setitem__(0, now[0] + seconds)), \
                    self.assertRaises(ScaleDeadlineError):
                self.runner.ensure_app_data_dir()
            self.assertEqual(now[0], 105)
            self.assertFalse(any("force-stop" in call for call in self.calls))
            self.assertNotIn("app_ready", self.runner.report)

    def test_failed_dump_cannot_reuse_a_previous_ready_file_or_output(self):
        for failure in (RuntimeError("dump failed after old file"), "UI hierchary dumped to: /data/local/tmp/old.xml", "ERROR: no idle state"):
            self.calls.clear()
            self.runner.report["app_ready"] = ready_proof(self.runner.evidence)
            now = [100.0]
            self.runner.deadline = self.runner.active_deadline = 104
            def controlled(*args, **kwargs):
                self.calls.append(args)
                if args[:3] == ("shell", "uiautomator", "dump"):
                    if isinstance(failure, Exception):
                        raise failure
                    return failure
                if args[:2] == ("exec-out", "cat"):
                    self.fail("must not read XML after an unsuccessful dump")
                return self.adb(*args, **kwargs)
            self.runner.adb = controlled
            with self.subTest(failure=failure), patch("android_scale.runner.time.monotonic", side_effect=lambda: now[0]), \
                    patch("android_scale.runner.time.sleep", side_effect=lambda seconds: now.__setitem__(0, now[0] + seconds)), \
                    self.assertRaises(ScaleDeadlineError):
                self.runner.ensure_app_data_dir()
            self.assertNotIn("app_ready", self.runner.report)
            self.assertFalse(any("force-stop" in call for call in self.calls))

    def test_transient_ui_read_failure_retries_with_a_new_dump(self):
        reads = [0]
        def controlled(*args, **kwargs):
            if args[:2] == ("exec-out", "cat"):
                reads[0] += 1
                if reads[0] == 1:
                    raise RuntimeError("read temporarily unavailable")
            return self.adb(*args, **kwargs)
        self.runner.adb = controlled
        with patch("android_scale.runner.time.sleep"):
            self.runner.ensure_app_data_dir()
        self.assertEqual(reads[0], 2)
        self.assertEqual(len([call for call in self.calls if "dump" in call]), 2)
        validate_app_ready(self.runner.evidence, self.runner.report)

    def test_ready_timeout_is_180_seconds_or_remaining_deadline(self):
        for remaining, expected in ((500, 180), (3, 3)):
            now = [100.0]
            self.runner.deadline = self.runner.active_deadline = 100 + remaining
            self.runner.adb = Mock(return_value="")
            with self.subTest(remaining=remaining), patch("android_scale.runner.time.monotonic", side_effect=lambda: now[0]), \
                    patch("android_scale.runner.time.sleep", side_effect=lambda seconds: now.__setitem__(0, now[0] + seconds)), \
                    self.assertRaises(ScaleDeadlineError):
                self.runner.await_app_ready()
            self.assertEqual(now[0], 100 + expected)
            self.assertEqual(self.runner.active_deadline, 100 + remaining)

    def test_ready_failure_is_setup_error_and_never_enters_instrumentation(self):
        self.runner.configure = Mock()
        self.runner.phase = Mock()
        def controlled(*args, **kwargs):
            if args[:2] == ("exec-out", "cat"):
                return READY_XML.replace("账本：synthetic", "无法打开本地账本")
            return self.adb(*args, **kwargs)
        self.runner.adb = controlled
        self.assertEqual(self.runner.run(), 1)
        self.runner.phase.assert_not_called()
        self.assertFalse(self.runner.setup_complete)
        host = json.loads((self.runner.evidence / "host.json").read_text())
        self.assertEqual(host["status"], "ERROR")
        self.assertEqual(host["phases"], [])
        self.assertIn("startup error/recovery", host["errorMessage"])
        suite = ET.parse(self.runner.evidence / "junit.xml").getroot()
        self.assertEqual(suite.find("testcase").get("name"), "setup")
        self.assertEqual(suite.get("errors"), "1")
        self.assertFalse(any("force-stop" in call for call in self.calls))

    def test_phase_cannot_start_without_ready_proof(self):
        self.runner.adb = Mock()
        with patch("android_scale.runner.subprocess.Popen") as process, self.assertRaises((OSError, ValueError)):
            self.runner.phase("preflight")
        self.runner.adb.assert_not_called()
        process.assert_not_called()

    def test_install_refuses_wrong_runner_and_private_staging_failures(self):
        for fault in ("runner", "copy", "readback", "launch", "package"):
            with self.subTest(fault=fault):
                fixture = self.root / fault
                self.runner.fixture = fixture
                def faulty(*args, **kwargs):
                    if fault == "runner" and args[:3] == ("shell", "pm", "list"):
                        return "instrumentation:wrong (target=other)"
                    if fault == "package" and args[:3] == ("shell", "pm", "path"):
                        return "Error: package not found"
                    if fault == "launch" and args[:3] == ("shell", "am", "start"):
                        return "Error type 3\nError: Activity does not exist"
                    if fault == "copy" and args[:4] == ("shell", "run-as", PACKAGE, "cp"):
                        raise RuntimeError("run-as denied")
                    if fault == "readback" and args[:4] == ("exec-out", "run-as", PACKAGE, "cat"):
                        return b"wrong"
                    return self.adb(*args, **kwargs)
                self.runner.adb = faulty
                with patch("android_scale.runner.time.sleep"), self.assertRaises(RuntimeError):
                    self.runner.install()

    def test_readiness_does_not_accept_window_before_user_unlock(self):
        states = iter(["RUNNING_LOCKED", "RUNNING_UNLOCKED"])
        def controlled(*args, **kwargs):
            if args[:3] == ("shell", "am", "get-started-user-state"):
                self.calls.append(args)
                return next(states)
            return self.adb(*args, **kwargs)
        self.runner.adb = controlled
        with patch("android_scale.runner.time.sleep"):
            self.runner.await_framework()
        self.assertEqual(len([call for call in self.calls if "get-started-user-state" in call]), 2)
        self.assertEqual(self.runner.report["readiness"]["user"], "RUNNING_UNLOCKED")

    def test_readiness_rejects_dead_package_service(self):
        def controlled(*args, **kwargs):
            if args[:4] == ("shell", "cmd", "package", "list"):
                return "Error: Can't find service: package"
            return self.adb(*args, **kwargs)
        self.runner.adb = controlled
        with self.assertRaises(ScaleDeadlineError):
            self.runner.await_framework(timeout=0.01)

    def test_even_best_effort_probe_is_clamped_to_active_deadline(self):
        self.runner.active_deadline = 105
        with patch("android_scale.runner.time.monotonic", return_value=100), patch("android_scale.runner.subprocess.run") as run:
            run.return_value = subprocess.CompletedProcess([], 0, b"ok", b"")
            self.runner.command(["unused"], timeout=60, best_effort=True)
            self.assertEqual(run.call_args.kwargs["timeout"], 5)
        with patch("android_scale.runner.time.monotonic", return_value=106), self.assertRaises(ScaleDeadlineError):
            self.runner.command(["unused"], best_effort=True)

    def test_setup_error_preserves_first_cause_and_junit_error(self):
        self.runner.configure = Mock(side_effect=RuntimeError("user did not unlock"))
        self.assertEqual(self.runner.run(), 1)
        host = json.loads((self.runner.evidence / "host.json").read_text())
        self.assertEqual(host["status"], "ERROR")
        self.assertEqual(host["errorMessage"], "user did not unlock")
        self.assertIn("validationError", host)
        root = ET.parse(self.runner.evidence / "junit.xml").getroot()
        self.assertEqual(root.get("errors"), "1")
        self.assertEqual(root.find("testcase").get("name"), "setup")
        self.assertEqual(root.get("skipped"), "1")

    def run_valid_preflight(self):
        self.runner.adb = self.adb
        self.runner.install()
        self.runner.setup_complete = True
        self.runner.report.update(readiness={"user": "RUNNING_UNLOCKED", "package_service": True, "window_service": True}, config=CONFIG)
        identity = {"ledger": "synthetic", "generation": "gen-1", "transactions": 0, "postings": 0}
        data = {"schema": 2, "mode": "preflight", "sha": SHA, "probeSha256": hashlib.sha256(probe_bytes(SHA)).hexdigest(),
                "roundTrip": True, "pointerObservedBeforeOpen": True,
                "beforeOpen": dict(identity), "firstOpen": dict(identity), "reopen": dict(identity)}
        def output_process(command, stdout, **kwargs):
            self.assertIn(PREFLIGHT_CLASS + "#" + PREFLIGHT_METHOD, command)
            stdout.write(status_log(test_name=PREFLIGHT_METHOD).replace(TEST_CLASS, PREFLIGHT_CLASS).encode())
            stdout.flush()
            self.private["files/android-preflight-evidence.json"] = json.dumps(data)
            process = Mock(returncode=0)
            process.poll.return_value = 0
            return process
        with patch("android_scale.runner.subprocess.Popen", side_effect=output_process), patch("android_scale.runner.time.monotonic", side_effect=[100, 101, 102]):
            self.runner.phase("preflight")
        self.runner.report["status"] = "PASS"
        self.runner.started -= 1  # The fake process completes within one Windows clock tick.
        self.runner.write_reports()
        for name in ("logcat.txt", "configuration.txt", "pm.txt", "logcat-collection.json"):
            (self.runner.evidence / name).write_text("evidence\n")
        (self.runner.evidence / "logcat-collection.json").write_text(json.dumps({"started": True, "stopped": True, "stream_bytes": 100, "gaps": []}))
        validate_preflight(self.runner.evidence, SHA)
        return data

    def test_real_preflight_phase_orchestration_requires_named_test_and_device_evidence(self):
        self.run_valid_preflight()
        with self.assertRaisesRegex(ValueError, "maximum evidence mode"):
            validate_evidence(self.runner.evidence, SHA)
        (self.runner.evidence / "instrumentation-preflight.txt").write_text(status_log())
        with self.assertRaises(ValueError):
            validate_preflight(self.runner.evidence, SHA)

    def test_preflight_rejects_missing_pointer_changed_identity_and_economic_effects(self):
        original = self.run_valid_preflight()
        variants = []
        for key in original:
            missing = copy.deepcopy(original)
            missing.pop(key)
            variants.append(missing)
        for value in (False, 1, None):
            variants.append(dict(original, pointerObservedBeforeOpen=value))
        for stage in ("beforeOpen", "firstOpen", "reopen"):
            for key, values in (("ledger", ("changed", "", None)), ("generation", ("gen-2", "", "gen-0", None)),
                                ("transactions", (1, False, "0")), ("postings", (1, False, "0"))):
                for value in values:
                    data = copy.deepcopy(original)
                    data[stage][key] = value
                    variants.append(data)
        for index, data in enumerate(variants):
            with self.subTest(index=index):
                (self.runner.evidence / "device.json").write_text(json.dumps(data))
                with self.assertRaises(ValueError):
                    validate_preflight(self.runner.evidence, SHA)

    def test_preflight_rejects_missing_ready_even_when_device_roundtrip_passes(self):
        self.run_valid_preflight()
        path = self.runner.evidence / "host.json"
        host = json.loads(path.read_text())
        host.pop("app_ready")
        path.write_text(json.dumps(host))
        with self.assertRaisesRegex(ValueError, "Ready proof"):
            validate_preflight(self.runner.evidence, SHA)

    def test_rejected_avd_never_enters_diagnostics_or_force_stop(self):
        def command(args, **kwargs):
            self.calls.append(args)
            if args == ["adb", "devices"]:
                return "List of devices attached\nemulator-5554\tdevice\n"
            if args == ["adb", "-s", "emulator-5554", "emu", "avd", "name"]:
                return "unowned\nOK\n"
            self.fail("unexpected device command after ownership rejection: " + repr(args))
        self.runner.command = command
        self.assertEqual(self.runner.run(), 1)
        self.assertIsNone(self.runner.serial)
        self.assertEqual(len(self.calls), 2)

    def test_tail_only_logs_cannot_substitute_for_continuous_capture(self):
        path = self.runner.evidence / "logcat-collection.json"
        for record in ({"started": False, "stopped": True, "stream_bytes": 100, "gaps": []},
                       {"started": True, "stopped": True, "stream_bytes": 0, "gaps": []},
                       {"started": True, "stopped": False, "stream_bytes": 100, "gaps": []}):
            path.write_text(json.dumps(record))
            with self.subTest(record=record), self.assertRaises(ValueError):
                validate_log_collection(self.runner.evidence)

    def test_stream_collector_reconnects_and_records_gap_without_losing_first_output(self):
        self.runner.serial = "emulator-5560"
        ready = threading.Event()
        count = 0
        def process(command, stdout, stderr):
            nonlocal count
            count += 1
            stdout.write(f"log chunk {count}\n".encode())
            stdout.flush()
            child = Mock()
            child.poll.return_value = 0 if count == 1 else None
            if count == 2:
                ready.set()
            return child
        with patch("android_scale.runner.subprocess.Popen", side_effect=process):
            self.runner.start_logcat()
            try:
                self.assertTrue(ready.wait(3))
            finally:
                self.runner.stop_logcat()
        validate_log_collection(self.runner.evidence)
        collection = json.loads((self.runner.evidence / "logcat-collection.json").read_text())
        self.assertEqual(len(collection["gaps"]), 1)
        self.assertIn("log chunk 1", (self.runner.evidence / "logcat.txt").read_text())
        self.assertIn("log chunk 2", (self.runner.evidence / "logcat.txt").read_text())

    def test_outer_budget_includes_slow_boot_and_preserves_mode_caps(self):
        # Epoch deadlines originate before the action; the driver starts only
        # after boot. Monotonic time is calibrated once and remains authoritative.
        scenarios = (("preflight", 1900, 1000, 600),
                     ("preflight", 1900, 1300, 450),
                     ("maximum", 15400, 1300, 13800),
                     ("maximum", 15400, 1700, 13550))
        for index, (mode, outer, wall, expected) in enumerate(scenarios):
            with self.subTest(mode=mode, wall=wall), patch("android_scale.runner.time.time", return_value=wall), patch("android_scale.runner.time.monotonic", return_value=100):
                runner = ScaleRunner(self.root / "fixture", self.root / f"budget-{index}", self.runner.app, self.runner.test,
                                     SHA, mode, outer_deadline_epoch=outer)
                self.assertEqual(runner.deadline - runner.started, expected)
                self.assertLessEqual(runner.deadline + 120 + 30, runner.outer_deadline)
                self.assertEqual(runner.report["execution_budget_seconds"], expected)

    def test_no_remaining_execution_budget_writes_reports_without_device_commands(self):
        for index, outer in enumerate((1140, 1020, 990)):
            with self.subTest(outer=outer), patch("android_scale.runner.time.time", return_value=1000), patch("android_scale.runner.time.monotonic", return_value=100):
                runner = ScaleRunner(self.root / "fixture", self.root / f"expired-{index}", self.runner.app, self.runner.test,
                                     SHA, "preflight", outer_deadline_epoch=outer)
                runner.configure = Mock(side_effect=AssertionError("configuration must not start"))
                runner.command = Mock(side_effect=AssertionError("no device commands allowed"))
                self.assertEqual(runner.run(), 1)
                runner.configure.assert_not_called()
                runner.command.assert_not_called()
                self.assertLessEqual(runner.active_deadline, min(220, runner.outer_deadline - 30))
                host = json.loads((runner.evidence / "host.json").read_text())
                self.assertTrue(host["timed_out"])
                self.assertEqual(host["status"], "ERROR")
                self.assertEqual(host["execution_budget_seconds"], 0)
                self.assertEqual(host["errorType"], "ScaleDeadlineError")
                suite = ET.parse(runner.evidence / "junit.xml").getroot()
                self.assertEqual(suite.get("errors"), "1")
                self.assertEqual(suite.get("skipped"), "1")

    def test_late_diagnostic_probe_is_clamped_to_outer_remaining_time(self):
        with patch("android_scale.runner.time.time", return_value=1000), patch("android_scale.runner.time.monotonic", return_value=100):
            runner = ScaleRunner(self.root / "fixture", self.root / "late-diagnostics", self.runner.app, self.runner.test,
                                 SHA, "preflight", outer_deadline_epoch=1900)
        runner.configure = Mock(side_effect=RuntimeError("late setup failure"))
        observed = []
        def diagnostics(*, failure):
            observed.append(runner.active_deadline)
            runner.command(["synthetic-probe"], timeout=60, best_effort=True)
        runner.diagnostics = diagnostics
        with patch("android_scale.runner.time.monotonic", return_value=950), patch("android_scale.runner.subprocess.run") as command:
            command.return_value = subprocess.CompletedProcess([], 0, b"ok", b"")
            self.assertEqual(runner.run(), 1)
        self.assertEqual(observed, [970])
        self.assertEqual(command.call_args.kwargs["timeout"], 20)

    def forensics_adb(self, responses):
        def controlled(*args, **kwargs):
            if args[:4] == ("exec-out", "run-as", PACKAGE, "cat"):
                return responses.get(args[-1])
            # Mirror command(): binary callers get bytes, text callers get str.
            return b"" if kwargs.get("binary") else ""
        return controlled

    def test_diagnostics_collects_present_coldstart_forensics_with_hashes(self):
        self.runner.serial = "emulator-5554"
        payload = json.dumps({"schema": 1, "kind": "coldstart-forensics", "sha": SHA}).encode("utf-8")
        png = b"\x89PNG\r\n\x1a\n synthetic coldstart frame"
        self.runner.adb = self.forensics_adb({
            "files/android-scale-coldstart-forensics.json": payload,
            "files/android-scale-coldstart-forensics.png": png,
        })
        self.runner.diagnostics(failure=True)
        records = self.runner.report["coldstart_forensics"]
        self.assertEqual(records["coldstart-forensics.json"], {
            "sha256": hashlib.sha256(payload).hexdigest(), "bytes": len(payload),
            "json_valid": True, "sha_match": True})
        self.assertEqual(records["coldstart-forensics.png"],
                         {"sha256": hashlib.sha256(png).hexdigest(), "bytes": len(png)})
        self.assertEqual((self.runner.evidence / "coldstart-forensics.json").read_bytes(), payload)
        self.assertEqual((self.runner.evidence / "coldstart-forensics.png").read_bytes(), png)

    def test_missing_coldstart_forensics_is_recorded_absent_and_changes_nothing(self):
        self.runner.serial = "emulator-5554"
        self.runner.adb = self.forensics_adb({})
        self.runner.diagnostics(failure=True)
        self.assertEqual(self.runner.report["coldstart_forensics"],
                         {"coldstart-forensics.json": "absent", "coldstart-forensics.png": "absent"})
        self.assertFalse((self.runner.evidence / "coldstart-forensics.json").exists())
        self.assertFalse((self.runner.evidence / "coldstart-forensics.png").exists())

    def test_coldstart_adb_not_found_text_is_recorded_absent_and_never_kept_as_content(self):
        # D-207: real `adb exec-out` behavior — a missing device file still
        # exits 0 and adb's `cat: ...: No such file or directory` error text
        # arrives inside the stdout stream instead of a device-side failure.
        self.runner.serial = "emulator-5554"
        self.runner.adb = self.forensics_adb({
            "files/android-scale-coldstart-forensics.json":
                b"cat: files/android-scale-coldstart-forensics.json: No such file or directory\n",
            "files/android-scale-coldstart-forensics.png":
                b"cat: files/android-scale-coldstart-forensics.png: No such file or directory\n",
        })
        self.runner.diagnostics(failure=True)
        self.assertEqual(self.runner.report["coldstart_forensics"],
                         {"coldstart-forensics.json": "absent", "coldstart-forensics.png": "absent"})
        self.assertFalse((self.runner.evidence / "coldstart-forensics.json").exists())
        self.assertFalse((self.runner.evidence / "coldstart-forensics.png").exists())

    def test_malformed_or_sha_mismatched_forensics_is_kept_but_flagged(self):
        self.runner.serial = "emulator-5554"
        responses = {"files/android-scale-coldstart-forensics.json": b"{not json"}
        self.runner.adb = self.forensics_adb(responses)
        self.runner.diagnostics(failure=True)
        record = self.runner.report["coldstart_forensics"]["coldstart-forensics.json"]
        self.assertFalse(record["json_valid"])
        self.assertNotIn("sha_match", record)
        self.assertEqual((self.runner.evidence / "coldstart-forensics.json").read_bytes(), b"{not json")
        responses["files/android-scale-coldstart-forensics.json"] = json.dumps({"schema": 1, "sha": "b" * 40}).encode("utf-8")
        self.runner.diagnostics(failure=True)
        record = self.runner.report["coldstart_forensics"]["coldstart-forensics.json"]
        self.assertTrue(record["json_valid"])
        self.assertFalse(record["sha_match"])


class ColdstartHostProbe(unittest.TestCase):
    """D-205 host-side discriminating probe: dumpsys window, diagnostics only.

    D-204 used `uiautomator dump`, which needs its own UiAutomation connection
    and therefore fails for the whole duration of the instrumentation run (the
    AndroidJUnitRunner holds the single connection; the same dump succeeded
    during install, before instrumentation). The probe no longer touches a11y:
    `dumpsys window windows` reports the window list and focus, and a second
    `dumpsys activity activities` reports the resumed activity.
    """

    WINDOW_READY = (
        "WINDOW MANAGER WINDOWS (dumpsys window windows)\n"
        "  Window #0 Window{11 u0 com.unifiedledger.android/com.unifiedledger.android.MainActivity}:\n"
        "    mOwnerUid=10123 package=com.unifiedledger.android\n"
        "    mHasSurface=true isOnScreen=true\n"
        "  Window #1 Window{22 u0 com.android.systemui/com.android.systemui.statusbar.phone.StatusBar}:\n"
        "    mOwnerUid=10021 package=com.android.systemui\n"
        "  mCurrentFocus=Window{11 u0 com.unifiedledger.android/com.unifiedledger.android.MainActivity}\n"
        "  mFocusedApp=ActivityRecord{aa u0 com.unifiedledger.android/.MainActivity t42}\n"
        "  mFocusedWindow=Window{11 u0 com.unifiedledger.android/com.unifiedledger.android.MainActivity}\n"
    )
    WINDOW_LAUNCHER = (
        "WINDOW MANAGER WINDOWS (dumpsys window windows)\n"
        "  Window #0 Window{33 u0 com.android.launcher3/com.android.launcher3.Launcher}:\n"
        "    mOwnerUid=10007 package=com.android.launcher3\n"
        "  mCurrentFocus=Window{33 u0 com.android.launcher3/com.android.launcher3.Launcher}\n"
        "  mFocusedApp=ActivityRecord{bb u0 com.android.launcher3/.Launcher t42}\n"
    )
    ACTIVITY_READY = (
        "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
        "  ResumedActivity: ActivityRecord{aa u0 com.unifiedledger.android/.MainActivity t42}\n"
    )
    ACTIVITY_LAUNCHER = (
        "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
        "  ResumedActivity: ActivityRecord{bb u0 com.android.launcher3/.Launcher t42}\n"
    )

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        env = patch.dict(os.environ, GITHUB_ACTIONS="true", RUNNER_ENVIRONMENT="github-hosted", RUNNER_OS="Linux")
        env.start()
        self.addCleanup(env.stop)
        self.runner = ScaleRunner(self.root / "fixture", self.root / "evidence", self.root / "app.apk", self.root / "test.apk", SHA, "maximum")
        self.calls = []
        self.started_ms = 1_000_000
        self.uptime_s = None
        self.window_text = self.WINDOW_READY
        self.activity_text = self.ACTIVITY_READY
        self.window_fails = False

    def adb(self, *args, **kwargs):
        self.calls.append(args)
        if args[:3] == ("exec-out", "run-as", PACKAGE):
            return json.dumps({"stages": {"coldstart": {"status": "NOT_RUN", "startedMs": self.started_ms}}})
        if args[:3] == ("shell", "cat", "/proc/uptime"):
            return f"{self.uptime_s:.3f} 0.00\n"
        if args[:3] == ("shell", "dumpsys", "meminfo"):
            return "meminfo sample\n"
        if args[:4] == ("shell", "dumpsys", "window", "windows"):
            if self.window_fails:
                raise RuntimeError("dumpsys window unavailable")
            return self.window_text
        if args[:4] == ("shell", "dumpsys", "activity", "activities"):
            return self.activity_text
        return ""

    def probe(self, adb=None):
        self.runner.adb = adb or self.adb
        self.runner.poll_once("chain")
        return self.runner.report.get("coldstart_host_probe")

    def test_target_window_and_resumed_activity_are_recorded(self):
        self.uptime_s = (self.started_ms + 40_000) / 1000
        state = self.probe()
        self.assertEqual(state["attempts"], 1)
        record = state["probes"][0]
        self.assertEqual(record["elapsed"], 40_000)
        self.assertTrue(record["targetWindowPresent"])
        self.assertTrue(record["targetActivityPresent"])
        self.assertEqual(record["currentFocusPackage"], PACKAGE)
        self.assertEqual(record["focusedAppPackage"], PACKAGE)
        self.assertEqual(record["resumedActivity"], PACKAGE + "/.MainActivity")
        self.assertEqual(record["focusedWindowPackage"], PACKAGE)
        self.assertTrue(record["currentFocusPresent"])
        self.assertTrue(record["focusedAppPresent"])
        self.assertTrue(record["focusedWindowPresent"])
        self.assertEqual(record["windowSha256"], hashlib.sha256(self.WINDOW_READY.encode("utf-8")).hexdigest())
        self.assertEqual(record["windowBytes"], len(self.WINDOW_READY.encode("utf-8")))
        self.assertEqual(record["activitySha256"], hashlib.sha256(self.ACTIVITY_READY.encode("utf-8")).hexdigest())
        self.assertEqual(record["activityBytes"], len(self.ACTIVITY_READY.encode("utf-8")))
        self.assertFalse(state["probeFailed"])
        self.assertIn("coldstart-host-probe", (self.runner.evidence / "memory.txt").read_text(encoding="utf-8"))
        self.assertEqual((self.runner.evidence / "coldstart-host-window.txt").read_text(encoding="utf-8"), self.WINDOW_READY)
        self.assertEqual((self.runner.evidence / "coldstart-host-activity.txt").read_text(encoding="utf-8"), self.ACTIVITY_READY)

    def test_launcher_window_records_absence_without_failing(self):
        self.uptime_s = (self.started_ms + 40_000) / 1000
        self.window_text = self.WINDOW_LAUNCHER
        self.activity_text = self.ACTIVITY_LAUNCHER
        state = self.probe()
        record = state["probes"][0]
        self.assertFalse(record["targetWindowPresent"])
        self.assertFalse(record["targetActivityPresent"])
        self.assertEqual(record["currentFocusPackage"], "com.android.launcher3")
        self.assertEqual(record["focusedAppPackage"], "com.android.launcher3")
        self.assertIsNone(record["focusedWindowPackage"])
        self.assertFalse(record["focusedWindowPresent"])
        self.assertEqual(record["resumedActivity"], "com.android.launcher3/.Launcher")
        self.assertFalse(state["probeFailed"])

    def test_unrecognizable_window_output_records_raw_prefix_and_fails(self):
        # An adb/dumpsys contention window can return an error line instead of a
        # window list; that must be recorded honestly (raw prefix) instead of
        # being read as "no target window".
        self.uptime_s = (self.started_ms + 40_000) / 1000
        self.window_text = "Can't find service: window\n"
        state = self.probe()
        record = state["probes"][0]
        self.assertTrue(record["probeFailed"])
        self.assertTrue(record["error"].startswith("window dump not recognizable: "))
        self.assertIn("Can't find service: window", record["error"])
        self.assertLessEqual(len(record["error"]), 200)
        self.assertNotIn("targetWindowPresent", record)
        self.assertFalse((self.runner.evidence / "coldstart-host-window.txt").exists())

    def test_activity_dump_failure_keeps_the_window_sample(self):
        # The window dump answers the primary question (is the target window
        # there, and who holds focus); the resumed-activity read is
        # supplementary and its failure must not discard a valid window sample.
        self.uptime_s = (self.started_ms + 40_000) / 1000

        def activity_fails(*args, **kwargs):
            if args[:4] == ("shell", "dumpsys", "activity", "activities"):
                raise subprocess.TimeoutExpired("adb", 30)
            return self.adb(*args, **kwargs)

        state = self.probe(activity_fails)
        record = state["probes"][0]
        self.assertIn("activityError", record)
        self.assertNotIn("probeFailed", record)
        self.assertEqual(record["currentFocusPackage"], PACKAGE)
        self.assertTrue(record["targetWindowPresent"])
        self.assertFalse((self.runner.evidence / "coldstart-host-activity.txt").exists())
        self.assertEqual((self.runner.evidence / "coldstart-host-window.txt").read_text(encoding="utf-8"), self.WINDOW_READY)

    def test_focus_naming_the_target_does_not_override_a_missing_window_entry(self):
        # Window presence is answered by the window list alone. If focus names
        # the target while the list does not (a contradictory snapshot), the
        # record must keep both facts visible instead of resolving the
        # contradiction in either direction.
        self.uptime_s = (self.started_ms + 40_000) / 1000
        self.window_text = (
            "WINDOW MANAGER WINDOWS (dumpsys window windows)\n"
            "  Window #0 Window{33 u0 com.android.launcher3/com.android.launcher3.Launcher}:\n"
            "    mOwnerUid=10007 package=com.android.launcher3\n"
            "  mCurrentFocus=Window{11 u0 com.unifiedledger.android/com.unifiedledger.android.MainActivity}\n"
            "  mFocusedApp=ActivityRecord{aa u0 com.unifiedledger.android/.MainActivity t42}\n"
        )
        record = self.probe()["probes"][0]
        self.assertFalse(record["targetWindowPresent"])
        self.assertEqual(record["windowCount"], 1)
        self.assertEqual(record["currentFocusPackage"], PACKAGE)
        self.assertEqual(record["focusedAppPackage"], PACKAGE)
        self.assertNotIn("probeFailed", record)

    def test_window_count_reports_every_window_entry(self):
        self.uptime_s = (self.started_ms + 40_000) / 1000
        record = self.probe()["probes"][0]
        self.assertEqual(record["windowCount"], 2)
        self.assertTrue(record["targetWindowPresent"])

    def test_focused_window_line_is_captured_and_kept_out_of_the_window_count(self):
        # mFocusedWindow carries the same Window{...} token as a list entry: it
        # must be recorded as a focus fact and must not be counted as a window.
        self.uptime_s = (self.started_ms + 40_000) / 1000
        self.window_text = (
            "WINDOW MANAGER WINDOWS (dumpsys window windows)\n"
            "  Window #0 Window{33 u0 com.android.launcher3/com.android.launcher3.Launcher}:\n"
            "    mOwnerUid=10007 package=com.android.launcher3\n"
            "  mCurrentFocus=null\n"
            "  mFocusedWindow=Window{11 u0 com.unifiedledger.android/com.unifiedledger.android.MainActivity}\n"
        )
        record = self.probe()["probes"][0]
        self.assertEqual(record["windowCount"], 1)
        self.assertFalse(record["targetWindowPresent"])
        self.assertEqual(record["focusedWindowPackage"], PACKAGE)
        self.assertTrue(record["focusedWindowPresent"])
        # An explicit null is a present line with no value, not a missing line.
        self.assertTrue(record["currentFocusPresent"])
        self.assertIsNone(record["currentFocusPackage"])
        self.assertFalse(record["focusedAppPresent"])
        self.assertIsNone(record["focusedAppPackage"])
        self.assertNotIn("probeFailed", record)

    def test_background_target_window_is_not_reported_as_the_resumed_activity(self):
        # The decisive cross-check for the D-205 question: the target window can
        # exist (and even hold surface) while another app stays resumed. The
        # window and activity reads are reported independently.
        self.uptime_s = (self.started_ms + 40_000) / 1000
        self.window_text = self.WINDOW_READY
        self.activity_text = self.ACTIVITY_LAUNCHER
        record = self.probe()["probes"][0]
        self.assertTrue(record["targetWindowPresent"])
        self.assertFalse(record["targetActivityPresent"])
        self.assertEqual(record["resumedActivity"], "com.android.launcher3/.Launcher")
        self.assertNotIn("probeFailed", record)

    def test_lookalike_package_is_not_taken_for_the_target_activity(self):
        # `com.unifiedledger.android.other/...` must not satisfy the target test;
        # only an exact package prefix followed by `/` does.
        self.uptime_s = (self.started_ms + 40_000) / 1000
        self.activity_text = (
            "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
            "  ResumedActivity: ActivityRecord{cc u0 com.unifiedledger.android.other/.Other t42}\n"
        )
        record = self.probe()["probes"][0]
        self.assertFalse(record["targetActivityPresent"])
        self.assertEqual(record["resumedActivity"], "com.unifiedledger.android.other/.Other")

    def test_failed_dumpsys_records_probe_failed_and_changes_nothing(self):
        self.uptime_s = (self.started_ms + 40_000) / 1000
        self.window_fails = True
        state = self.probe()
        self.assertEqual(state["attempts"], 1)
        record = state["probes"][0]
        self.assertTrue(record["probeFailed"])
        self.assertIn("error", record)
        self.assertTrue(state["probeFailed"])
        self.assertNotIn("windowSha256", record)
        self.assertFalse((self.runner.evidence / "coldstart-host-window.txt").exists())

    def test_probe_does_not_trigger_before_thirty_seconds_of_running_coldstart(self):
        self.uptime_s = (self.started_ms + 10_000) / 1000
        state = self.probe()
        self.assertEqual(state, {"attempts": 0, "probes": [], "probeFailed": False})
        self.assertFalse(any(args[:4] == ("shell", "dumpsys", "window", "windows") for args in self.calls))
        # A finished coldstart stage is equally out of scope.
        self.uptime_s = (self.started_ms + 40_000) / 1000

        def finished(*args, **kwargs):
            if args[:3] == ("exec-out", "run-as", PACKAGE):
                return json.dumps({"stages": {"coldstart": {"status": "PASS", "startedMs": self.started_ms}}})
            return self.adb(*args, **kwargs)

        self.runner.adb = finished
        self.runner.poll_once("chain")
        self.assertEqual(self.runner.report["coldstart_host_probe"]["attempts"], 0)

    def test_empty_dumpsys_readback_is_probe_failed_not_a_zero_byte_sample(self):
        self.uptime_s = (self.started_ms + 40_000) / 1000

        def empty_window(*args, **kwargs):
            if args[:4] == ("shell", "dumpsys", "window", "windows"):
                return "   \n"
            return self.adb(*args, **kwargs)

        state = self.probe(empty_window)
        record = state["probes"][0]
        self.assertTrue(record["probeFailed"])
        self.assertEqual(record["error"], "window dump read back empty")
        self.assertNotIn("windowBytes", record)
        self.assertFalse((self.runner.evidence / "coldstart-host-window.txt").exists())

    def test_probe_gate_hiccup_is_contained_and_changes_nothing(self):
        # The ≥30s gate reads device uptime before any dump exists. A stuck adb
        # there must degrade into a recorded diagnostic, never into a run error.
        self.uptime_s = (self.started_ms + 40_000) / 1000

        def uptime_times_out(*args, **kwargs):
            if args[:3] == ("shell", "cat", "/proc/uptime"):
                raise subprocess.TimeoutExpired("adb", 30)
            return self.adb(*args, **kwargs)

        self.runner.adb = uptime_times_out
        self.runner.poll_once("chain")
        state = self.runner.report["coldstart_host_probe"]
        self.assertEqual(state, {"attempts": 0, "probes": [], "probeFailed": False})
        self.assertIn("gate-failed", (self.runner.evidence / "memory.txt").read_text(encoding="utf-8"))

    def test_probe_is_chain_only(self):
        # Only the chain phase can hold a coldstart; other phases must not dump.
        self.uptime_s = (self.started_ms + 40_000) / 1000
        self.runner.adb = self.adb
        self.runner.poll_once("prepare")
        self.assertEqual(self.runner.report["coldstart_host_probe"], {"attempts": 0, "probes": [], "probeFailed": False})
        self.assertFalse(any(args[:4] == ("shell", "dumpsys", "window", "windows") for args in self.calls))

    def test_probe_does_not_trigger_without_device_evidence(self):
        # Evidence not yet written (common early-chain) and a failing probe both
        # leave `device` None; neither may attempt a dump or escape poll_once.
        def no_evidence(*args, **kwargs):
            if args[:3] == ("exec-out", "run-as", PACKAGE):
                return ""
            return self.adb(*args, **kwargs)

        self.uptime_s = (self.started_ms + 40_000) / 1000
        self.runner.adb = no_evidence
        self.runner.poll_once("chain")
        self.assertEqual(self.runner.report["coldstart_host_probe"], {"attempts": 0, "probes": [], "probeFailed": False})

        def evidence_breaks(*args, **kwargs):
            if args[:3] == ("exec-out", "run-as", PACKAGE):
                raise RuntimeError("run-as interrupted")
            return self.adb(*args, **kwargs)

        self.runner.adb = evidence_breaks
        self.runner.poll_once("chain")
        self.assertEqual(self.runner.report["coldstart_host_probe"], {"attempts": 0, "probes": [], "probeFailed": False})
        self.assertFalse(any(args[:4] == ("shell", "dumpsys", "window", "windows") for args in self.calls))

    def test_late_probe_failure_after_a_clean_sample_records_both_attempts(self):
        # A first clean sample must not stop the second probe from trying: the
        # budget is attempts, not successes. A failure then flags probeFailed
        # and keeps the first sample's hashes intact.
        self.uptime_s = (self.started_ms + 40_000) / 1000
        state = self.probe()
        self.assertEqual(state["attempts"], 1)
        self.assertFalse(state["probeFailed"])
        self.assertTrue(state["probes"][0]["targetWindowPresent"])
        self.uptime_s = (self.started_ms + 110_000) / 1000
        self.window_fails = True
        state = self.probe()
        self.assertEqual(state["attempts"], 2)
        self.assertTrue(state["probes"][1]["probeFailed"])
        self.assertTrue(state["probeFailed"])
        self.assertIn("windowSha256", state["probes"][0])

    def test_deadline_inside_the_probe_propagates_to_the_phase_loop(self):
        # An exhausted deadline is the one failure the probe must NOT contain:
        # the phase loop re-checks the budget on its next tick. The attempt
        # record still happens (it runs in the finally block).
        self.uptime_s = (self.started_ms + 40_000) / 1000

        def dump_exhausts_deadline(*args, **kwargs):
            if args[:4] == ("shell", "dumpsys", "window", "windows"):
                raise ScaleDeadlineError("global deadline exceeded")
            return self.adb(*args, **kwargs)

        self.runner.adb = dump_exhausts_deadline
        with self.assertRaises(ScaleDeadlineError):
            self.runner.poll_once("chain")
        state = self.runner.report["coldstart_host_probe"]
        self.assertEqual(state["attempts"], 1)
        self.assertEqual(len(state["probes"]), 1)
        self.assertIn("coldstart-host-probe", (self.runner.evidence / "memory.txt").read_text(encoding="utf-8"))

    def test_tail_failure_is_contained_and_keeps_the_attempt(self):
        # probes.append + record_memory sit after every capture guard and outside
        # the capture try. An OSError while recording the probe must not escape
        # poll_once or drop the attempt the probe already made. poll_once's own
        # memory writes (meminfo) are left working, so this targets the tail.
        self.uptime_s = (self.started_ms + 40_000) / 1000
        real_memory = self.runner.record_memory

        def memory_fails_for_the_probe(phase, text):
            if "coldstart-host-probe" in text:
                raise OSError("evidence directory vanished")
            return real_memory(phase, text)

        self.runner.record_memory = memory_fails_for_the_probe
        state = self.probe()
        self.assertEqual(state["attempts"], 1)
        self.assertEqual(len(state["probes"]), 1)
        self.assertTrue(state["probes"][0]["targetWindowPresent"])

    def test_gate_phase_deadline_propagates_instead_of_being_contained(self):
        # M18: without activeDeadlineElapsedMs the poll_once deadline check is
        # skipped, so a deadline surfaces in the probe's own uptime read. That
        # one is the phase loop's signal, not a diagnostic hiccup: it must
        # propagate, and no dump may be attempted.
        self.uptime_s = (self.started_ms + 40_000) / 1000

        def dead_uptime():
            raise ScaleDeadlineError("global deadline exceeded")

        self.runner.uptime_ms = dead_uptime
        with self.assertRaises(ScaleDeadlineError):
            self.probe()
        self.assertEqual(self.runner.report["coldstart_host_probe"], {"attempts": 0, "probes": [], "probeFailed": False})
        self.assertFalse(any(args[:4] == ("shell", "dumpsys", "window", "windows") for args in self.calls))

    def test_at_most_two_probes_at_least_sixty_seconds_apart(self):
        self.uptime_s = (self.started_ms + 40_000) / 1000
        state = self.probe()
        self.assertEqual(state["attempts"], 1)
        self.assertTrue(state["probes"][0]["targetWindowPresent"])
        self.uptime_s = (self.started_ms + 50_000) / 1000
        self.probe()
        self.assertEqual(state["attempts"], 1)
        self.uptime_s = (self.started_ms + 110_000) / 1000
        state = self.probe()
        self.assertEqual(state["attempts"], 2)
        self.assertEqual(state["probes"][1]["elapsed"], 110_000)
        self.uptime_s = (self.started_ms + 200_000) / 1000
        self.probe()
        self.assertEqual(state["attempts"], 2)


class ObserverDiagRecycle(unittest.TestCase):
    """D-205 device-side observer diagnostic recycled by the host, three states."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        env = patch.dict(os.environ, GITHUB_ACTIONS="true", RUNNER_ENVIRONMENT="github-hosted", RUNNER_OS="Linux")
        env.start()
        self.addCleanup(env.stop)
        self.runner = ScaleRunner(self.root / "fixture", self.root / "evidence", self.root / "app.apk", self.root / "test.apk", SHA, "maximum")
        self.runner.serial = "emulator-5554"

    def diag_adb(self, payload):
        def controlled(*args, **kwargs):
            if args[:4] == ("exec-out", "run-as", PACKAGE, "cat"):
                return payload
            return b"" if kwargs.get("binary") else ""
        return controlled

    def test_present_observer_diag_is_recycled_with_hash_and_json_validity(self):
        payload = json.dumps({"schema": 1, "kind": "observer-diag", "sha": SHA,
                              "phase": "chain", "stage": "coldstart", "records": [], "truncated": False}).encode("utf-8")
        self.runner.adb = self.diag_adb(payload)
        self.runner.diagnostics(failure=True)
        record = self.runner.report["observer_diag"]
        self.assertEqual(record, {"sha256": hashlib.sha256(payload).hexdigest(), "bytes": len(payload),
                                  "json_valid": True, "sha_match": True})
        self.assertEqual((self.runner.evidence / "observer-diag.json").read_bytes(), payload)

    def test_absent_observer_diag_is_recorded_absent_and_creates_no_file(self):
        self.runner.adb = self.diag_adb(b"")
        self.runner.diagnostics(failure=True)
        self.assertEqual(self.runner.report["observer_diag"], "absent")
        self.assertFalse((self.runner.evidence / "observer-diag.json").exists())

    def test_malformed_or_sha_mismatched_observer_diag_is_kept_but_flagged(self):
        self.runner.adb = self.diag_adb(b"{not json")
        self.runner.diagnostics(failure=True)
        record = self.runner.report["observer_diag"]
        self.assertEqual(record, {"sha256": hashlib.sha256(b"{not json").hexdigest(), "bytes": 9, "json_valid": False})
        self.assertEqual((self.runner.evidence / "observer-diag.json").read_bytes(), b"{not json")
        self.runner.adb = self.diag_adb(json.dumps({"schema": 1, "sha": "b" * 40}).encode("utf-8"))
        self.runner.diagnostics(failure=True)
        record = self.runner.report["observer_diag"]
        self.assertTrue(record["json_valid"])
        self.assertFalse(record["sha_match"])

    def test_observer_diag_absence_does_not_affect_the_coldstart_forensics_record(self):
        payload = json.dumps({"schema": 1, "kind": "coldstart-forensics", "sha": SHA}).encode("utf-8")

        def by_path(*args, **kwargs):
            if args[:4] == ("exec-out", "run-as", PACKAGE, "cat") and args[-1].endswith("coldstart-forensics.json"):
                return payload
            return b"" if kwargs.get("binary") else ""

        self.runner.adb = by_path
        self.runner.diagnostics(failure=True)
        self.assertIn("coldstart-forensics.json", self.runner.report["coldstart_forensics"])
        self.assertEqual(self.runner.report["observer_diag"], "absent")


class StageForensicsRecycle(unittest.TestCase):
    """D-206 device-side stage forensics recycled by the host, three states.

    The instrumentation writes a fixed-name JSON + PNG pair at the failure
    instant of any non-coldstart business stage (D-206); the host pulls both
    best-effort with the same honesty rules as the coldstart forensics: present
    records sha256/bytes (plus json_valid/sha_match for the JSON), absent is
    recorded as "absent", and a pull failure degrades to an error record
    without touching validation or any verdict.
    """

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        env = patch.dict(os.environ, GITHUB_ACTIONS="true", RUNNER_ENVIRONMENT="github-hosted", RUNNER_OS="Linux")
        env.start()
        self.addCleanup(env.stop)
        self.runner = ScaleRunner(self.root / "fixture", self.root / "evidence", self.root / "app.apk", self.root / "test.apk", SHA, "maximum")
        self.runner.serial = "emulator-5554"

    def forensics_adb(self, responses):
        def controlled(*args, **kwargs):
            if args[:4] == ("exec-out", "run-as", PACKAGE, "cat"):
                return responses.get(args[-1])
            # Mirror command(): binary callers get bytes, text callers get str.
            return b"" if kwargs.get("binary") else ""
        return controlled

    def test_present_stage_forensics_is_recycled_with_hashes(self):
        payload = json.dumps({"schema": 1, "kind": "stage-forensics", "sha": SHA,
                              "phase": "chain", "stage": "saf_import"}).encode("utf-8")
        png = b"\x89PNG\r\n\x1a\n synthetic stage failure frame"
        self.runner.adb = self.forensics_adb({
            "files/android-scale-stage-forensics.json": payload,
            "files/android-scale-stage-forensics.png": png,
        })
        self.runner.diagnostics(failure=True)
        records = self.runner.report["stage_forensics"]
        self.assertEqual(records["stage-forensics.json"], {
            "sha256": hashlib.sha256(payload).hexdigest(), "bytes": len(payload),
            "json_valid": True, "sha_match": True})
        self.assertEqual(records["stage-forensics.png"],
                         {"sha256": hashlib.sha256(png).hexdigest(), "bytes": len(png)})
        self.assertEqual((self.runner.evidence / "stage-forensics.json").read_bytes(), payload)
        self.assertEqual((self.runner.evidence / "stage-forensics.png").read_bytes(), png)

    def test_absent_stage_forensics_is_recorded_absent_and_creates_no_file(self):
        self.runner.adb = self.forensics_adb({})
        self.runner.diagnostics(failure=True)
        self.assertEqual(self.runner.report["stage_forensics"],
                         {"stage-forensics.json": "absent", "stage-forensics.png": "absent"})
        self.assertFalse((self.runner.evidence / "stage-forensics.json").exists())
        self.assertFalse((self.runner.evidence / "stage-forensics.png").exists())

    def test_stage_adb_not_found_text_is_recorded_absent_and_never_kept_as_content(self):
        # D-207: same adb not-found signature as the coldstart pull — the 73
        # bytes of `cat: ...: No such file or directory` from run 37123941430
        # must be recorded as absent, with no fabricated sha256 or bytes.
        self.runner.adb = self.forensics_adb({
            "files/android-scale-stage-forensics.json":
                b"cat: files/android-scale-stage-forensics.json: No such file or directory\n",
            "files/android-scale-stage-forensics.png":
                b"cat: files/android-scale-stage-forensics.png: No such file or directory\n",
        })
        self.runner.diagnostics(failure=True)
        self.assertEqual(self.runner.report["stage_forensics"],
                         {"stage-forensics.json": "absent", "stage-forensics.png": "absent"})
        self.assertFalse((self.runner.evidence / "stage-forensics.json").exists())
        self.assertFalse((self.runner.evidence / "stage-forensics.png").exists())

    def test_present_but_malformed_stage_forensics_is_kept_and_flagged_not_absent(self):
        # A present-but-corrupt file does not match the adb not-found signature
        # and must survive exactly as before: kept on disk, json_valid=false,
        # no sha_match — never misclassified as absent.
        self.runner.adb = self.forensics_adb({"files/android-scale-stage-forensics.json": b"{not json"})
        self.runner.diagnostics(failure=True)
        record = self.runner.report["stage_forensics"]["stage-forensics.json"]
        self.assertEqual(record,
                         {"sha256": hashlib.sha256(b"{not json").hexdigest(), "bytes": 9, "json_valid": False})
        self.assertEqual((self.runner.evidence / "stage-forensics.json").read_bytes(), b"{not json")

    def test_pull_failure_is_recorded_as_degraded_and_keeps_coldstart_record_independent(self):
        payload = json.dumps({"schema": 1, "kind": "coldstart-forensics", "sha": SHA}).encode("utf-8")

        def failing_pull(*args, **kwargs):
            if args[:4] == ("exec-out", "run-as", PACKAGE, "cat") and args[-1].startswith("files/android-scale-stage-forensics"):
                raise RuntimeError("run-as cat failed")
            if args[:4] == ("exec-out", "run-as", PACKAGE, "cat") and args[-1].endswith("coldstart-forensics.json"):
                return payload
            return b"" if kwargs.get("binary") else ""

        self.runner.adb = failing_pull
        self.runner.diagnostics(failure=True)
        self.assertEqual(self.runner.report["stage_forensics"], {"error": True})
        self.assertEqual(self.runner.report["coldstart_forensics"]["coldstart-forensics.json"],
                         {"sha256": hashlib.sha256(payload).hexdigest(), "bytes": len(payload),
                          "json_valid": True, "sha_match": True})

    def test_malformed_or_sha_mismatched_stage_forensics_is_kept_but_flagged(self):
        self.runner.adb = self.forensics_adb({"files/android-scale-stage-forensics.json": b"{not json"})
        self.runner.diagnostics(failure=True)
        record = self.runner.report["stage_forensics"]["stage-forensics.json"]
        self.assertFalse(record["json_valid"])
        self.assertNotIn("sha_match", record)
        self.assertEqual((self.runner.evidence / "stage-forensics.json").read_bytes(), b"{not json")
        self.runner.adb = self.forensics_adb(
            {"files/android-scale-stage-forensics.json": json.dumps({"schema": 1, "sha": "b" * 40}).encode("utf-8")})
        self.runner.diagnostics(failure=True)
        record = self.runner.report["stage_forensics"]["stage-forensics.json"]
        self.assertTrue(record["json_valid"])
        self.assertFalse(record["sha_match"])


class DbJournalProbe(unittest.TestCase):
    """D-210 host-side db-journal discriminator probe, diagnostics only.

    Two consecutive maximum rounds died in `saf_import` with SQLITE_BUSY on the
    oracle's read-only observation while the live app held the write lock, yet
    the failure-instant thread stacks showed the whole app process IDLE. In
    rollback-journal mode an in-flight or abandoned write transaction leaves a
    non-empty `ledger.db-journal` beside `ledger.db`; after a clean commit it is
    deleted or zero-length. One `run-as ... ls -laR databases` command per
    failed run, parsed on the host only: `journalNonEmpty=true` leans
    hypothesis B (abandoned transaction), false leans hypothesis A (long
    writes had completed by capture time). The probe never feeds validation or
    any PASS/FAIL judgment.
    """

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        env = patch.dict(os.environ, GITHUB_ACTIONS="true", RUNNER_ENVIRONMENT="github-hosted", RUNNER_OS="Linux")
        env.start()
        self.addCleanup(env.stop)
        self.runner = ScaleRunner(self.root / "fixture", self.root / "evidence", self.root / "app.apk", self.root / "test.apk", SHA, "maximum")
        self.runner.serial = "emulator-5554"
        self.ls_calls = []

    def journal_adb(self, response=None, error=None):
        def controlled(*args, **kwargs):
            if args[:6] == ("shell", "run-as", PACKAGE, "ls", "-laR", "databases"):
                self.ls_calls.append(args)
                if error is not None:
                    raise error
                return response
            # Mirror command(): binary callers get bytes, text callers get str.
            return b"" if kwargs.get("binary") else ""
        return controlled

    DIRTY_LISTING = (
        "databases:\n"
        "total 12\n"
        "drwxrwx--x 3 u0_a123 u0_a123 4096 2026-10-02 02:20 .\n"
        "drwxrwx--x 4 u0_a123 u0_a123 4096 2026-10-02 02:20 ..\n"
        "\n"
        "databases/ledger-generations:\n"
        "total 8\n"
        "drwxrwx--x 3 u0_a123 u0_a123 4096 2026-10-02 02:20 .\n"
        "\n"
        "databases/ledger-generations/gen-1:\n"
        "total 40\n"
        "-rw-rw---- 1 u0_a123 u0_a123 24576 2026-10-02 02:19 ledger.db\n"
        "-rw------- 1 u0_a123 u0_a123 12345 2026-10-02 02:20 ledger.db-journal\n"
    )

    def test_dirty_journal_is_parsed_with_journal_non_empty_true(self):
        self.runner.adb = self.journal_adb(self.DIRTY_LISTING)
        self.runner.diagnostics(failure=True)
        record = self.runner.report["db_journal_probe"]
        data = self.DIRTY_LISTING.encode("utf-8")
        self.assertEqual(record["bytes"], len(data))
        self.assertEqual(record["sha256"], hashlib.sha256(data).hexdigest())
        self.assertTrue(record["journalNonEmpty"])
        self.assertEqual(record["generations"], ["gen-1"])
        self.assertEqual(record["files"]["gen-1/ledger.db"],
                         {"present": True, "size": 24576, "mtime": "2026-10-02 02:19"})
        self.assertEqual(record["files"]["gen-1/ledger.db-journal"],
                         {"present": True, "size": 12345, "mtime": "2026-10-02 02:20"})
        self.assertEqual(record["files"]["gen-1/ledger.db-wal"],
                         {"present": False, "size": None, "mtime": None})
        self.assertEqual(record["files"]["gen-1/ledger.db-shm"],
                         {"present": False, "size": None, "mtime": None})
        self.assertEqual((self.runner.evidence / "db-journal-ls.txt").read_bytes(), data)
        self.assertEqual(len(self.ls_calls), 1)

    def test_clean_listing_records_journal_non_empty_false(self):
        clean = self.DIRTY_LISTING.replace(
            "-rw------- 1 u0_a123 u0_a123 12345 2026-10-02 02:20 ledger.db-journal\n", "")
        zeroed = self.DIRTY_LISTING.replace("12345 2026-10-02 02:20 ledger.db-journal",
                                            "0 2026-10-02 02:20 ledger.db-journal")
        kinds = ("no-journal", "zero-length-journal")
        for kind, listing in zip(kinds, (clean, zeroed)):
            with self.subTest(kind=kind):
                self.runner = ScaleRunner(self.root / "fixture", self.root / ("evidence-" + kind),
                                          self.root / "app.apk", self.root / "test.apk", SHA, "maximum")
                self.runner.serial = "emulator-5554"
                self.runner.adb = self.journal_adb(listing)
                self.runner.diagnostics(failure=True)
                record = self.runner.report["db_journal_probe"]
                self.assertFalse(record["journalNonEmpty"])
                self.assertEqual(record["generations"], ["gen-1"])
                self.assertEqual(record["files"]["gen-1/ledger.db-journal"]["present"], kind == "zero-length-journal")

    def test_run_as_not_found_signature_is_recorded_absent(self):
        # Same adb not-found/run-as error signature rule as the file pulls: the
        # error text is never kept as probe content and no sha256 is fabricated.
        self.runner.adb = self.journal_adb("run-as: com.unifiedledger.android: No such file or directory\n")
        self.runner.diagnostics(failure=True)
        self.assertEqual(self.runner.report["db_journal_probe"], "absent")
        self.assertFalse((self.runner.evidence / "db-journal-ls.txt").exists())

    def test_pull_failure_is_recorded_as_degraded(self):
        self.runner.adb = self.journal_adb(error=RuntimeError("run-as ls failed"))
        self.runner.diagnostics(failure=True)
        self.assertEqual(self.runner.report["db_journal_probe"], {"error": True})
        self.assertFalse((self.runner.evidence / "db-journal-ls.txt").exists())

    def test_unexpected_listing_shape_records_raw_without_parsed_facts(self):
        # A listing with a ledger entry but no gen-N directory line is not
        # parseable into generation facts: the raw output is still recorded
        # with its sha256, but no journalNonEmpty verdict is fabricated and no
        # exception escapes into the run.
        odd = "-rw-rw---- 1 u0_a123 u0_a123 24576 2026-10-02 02:19 ledger.db\n"
        self.runner.adb = self.journal_adb(odd)
        self.runner.diagnostics(failure=True)
        record = self.runner.report["db_journal_probe"]
        data = odd.encode("utf-8")
        self.assertEqual(record["bytes"], len(data))
        self.assertEqual(record["sha256"], hashlib.sha256(data).hexdigest())
        self.assertNotIn("journalNonEmpty", record)
        self.assertNotIn("generations", record)
        self.assertNotIn("files", record)
        self.assertEqual((self.runner.evidence / "db-journal-ls.txt").read_bytes(), data)

    def test_probe_runs_only_on_the_failure_path(self):
        self.runner.adb = self.journal_adb(error=AssertionError("probe must not run on success"))
        self.runner.diagnostics(failure=False)
        self.assertEqual(self.ls_calls, [])
        self.assertNotIn("db_journal_probe", self.runner.report)


class WorkflowConcurrency(unittest.TestCase):
    """Static proof of the D-204 parallel-acceptance concurrency contract."""

    def test_each_run_owns_its_concurrency_group_and_never_cancels(self):
        workflow = Path(__file__).resolve().parents[2] / ".github" / "workflows" / "android-scale.yml"
        text = workflow.read_text(encoding="utf-8")
        concurrency = text.split("concurrency:", 1)[1].split("jobs:", 1)[0]
        self.assertIn("group: android-scale-${{ github.run_id }}", concurrency)
        self.assertIn("cancel-in-progress: false", concurrency)
        self.assertNotIn("github.ref", concurrency)

    def test_evidence_artifact_name_carries_the_run_identity(self):
        # Parallel acceptance means two runs of the same SHA produce two
        # evidence sets; their artifact names must stay tellable apart. Both the
        # run id (parallel rounds) and the run attempt (an in-place whole-run
        # retry) belong in the name; the bare sha-only name must be gone.
        workflow = Path(__file__).resolve().parents[2] / ".github" / "workflows" / "android-scale.yml"
        text = workflow.read_text(encoding="utf-8")
        name = "android-scale-${{ inputs.expected_sha }}-${{ github.run_id }}-${{ github.run_attempt }}"
        self.assertIn("name: " + name, text)
        self.assertNotIn("name: android-scale-${{ inputs.expected_sha }}\n", text)
        self.assertNotIn("name: android-scale-${{ inputs.expected_sha }}-${{ github.run_id }}\n", text)
        self.assertIn("path: scale-evidence/**", text.split("name: " + name, 1)[1])


class ApkProvenance(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.tree = "b" * 40
        self.repo = "example/ledger"
        self.manifest = {"schema": 1, "tree": self.tree, "source_sha": SHA, "repository": self.repo,
                         "run_id": 123, "run_attempt": 1, "workflow": ".github/workflows/ci.yml",
                         "pull_request": {"number": 42, "base_sha": "d" * 40, "head_sha": "c" * 40},
                         "sha256": {"app": hashlib.sha256(b"app").hexdigest(), "test": hashlib.sha256(b"test").hexdigest()}}
        self.run = {"id": 123, "run_attempt": 1, "path": ".github/workflows/ci.yml", "event": "pull_request",
                    "head_sha": "c" * 40, "repository": {"full_name": self.repo, "id": 10},
                    "head_repository": {"full_name": self.repo},
                    "pull_requests": [{"base": {"sha": "d" * 40}, "head": {"sha": "c" * 40, "repo": {"id": 10}}}]}
        self.commit = {"sha": SHA, "tree": {"sha": self.tree}, "parents": [{"sha": "d" * 40}, {"sha": "c" * 40}]}
        self.pr = {"number": 42, "state": "open", "merged_at": None,
                   "base": {"ref": "main", "sha": "d" * 40, "repo": {"full_name": self.repo}},
                   "head": {"sha": "c" * 40, "repo": {"full_name": self.repo}}}
        self.artifact = {"id": 9, "name": f"android-apks-{self.tree}-123-1", "expired": False, "workflow_run": {"id": 123}}
        self.job = {"name": "Android compile", "conclusion": "success"}

    def payload(self, **changes):
        output = io.BytesIO()
        files = {apks.FILES["app"]: b"app", apks.FILES["test"]: b"test", "provenance.json": json.dumps(self.manifest).encode()}
        files.update(changes)
        with zipfile.ZipFile(output, "w") as archive:
            for name, value in files.items():
                if value is not None:
                    archive.writestr(name, value)
        return output.getvalue()

    def api(self, path, *, binary=False):
        if path.endswith("/zip"):
            return self.payload()
        if "/artifacts?" in path:
            return {"artifacts": [self.artifact]}
        if "/jobs?" in path:
            return {"jobs": [self.job]}
        if "/git/commits/" in path:
            return self.commit
        if "/pulls?" in path:
            return [self.pr]
        return self.run

    def test_complete_reuse_uses_explicit_artifact_run_and_both_hashes(self):
        with patch.object(apks, "api", side_effect=self.api) as api:
            self.assertTrue(apks.reuse(self.repo, self.tree, self.root / "download"))
        paths = [call.args[0] for call in api.call_args_list]
        self.assertIn(f"repos/{self.repo}/actions/artifacts/9/zip", paths)
        self.assertIn(f"repos/{self.repo}/actions/runs/123/attempts/1/jobs?per_page=100&page=1", paths)
        self.assertEqual((self.root / "download" / apks.FILES["app"]).read_bytes(), b"app")

    def test_changed_tree_or_expired_is_a_miss(self):
        for tree, expired in (("e" * 40, False), (self.tree, True)):
            self.artifact["expired"] = expired
            with patch.object(apks, "api", side_effect=self.api):
                self.assertFalse(apks.reuse(self.repo, tree, self.root / "download"))
        self.assertFalse((self.root / "download").exists())

    def test_bundle_missing_test_corruption_extra_member_and_wrong_tree_fail(self):
        variants = [{apks.FILES["test"]: None}, {apks.FILES["app"]: b"corrupt"}, {"../escape": b"bad"}]
        for index, changes in enumerate(variants):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                path = self.root / str(index)
                apks.unpack(self.payload(**changes), path)
                apks.check_bundle(path, self.tree)
        path = self.root / "valid"
        apks.unpack(self.payload(), path)
        with self.assertRaises(ValueError):
            apks.check_bundle(path, "f" * 40)

    def test_untrusted_or_unbound_source_rejected(self):
        originals = copy.deepcopy((self.run, self.commit, self.job))
        for fault in ("fork", "workflow", "parent", "tree", "job", "attempt", "source"):
            self.run, self.commit, self.job = copy.deepcopy(originals)
            if fault == "fork": self.run["head_repository"]["full_name"] = "foreign/ledger"
            if fault == "workflow": self.run["path"] = ".github/workflows/other.yml"
            if fault == "parent": self.commit["parents"][0]["sha"] = "f" * 40
            if fault == "tree": self.commit["tree"]["sha"] = "f" * 40
            if fault == "job": self.job["conclusion"] = "failure"
            if fault == "attempt": self.run["run_attempt"] = 2
            if fault == "source": self.commit["sha"] = "f" * 40
            with self.subTest(fault=fault), patch.object(apks, "api", side_effect=self.api), self.assertRaises(ValueError):
                apks.check_source(self.manifest, self.repo, 123)

    def test_failed_device_job_does_not_invalidate_successful_producer(self):
        self.run.update(event="workflow_dispatch", head_sha=SHA, conclusion="failure", path=".github/workflows/android-scale.yml")
        self.manifest["workflow"] = self.run["path"]
        self.job["name"] = "Android scale APKs"
        with patch.object(apks, "api", side_effect=self.api):
            apks.check_source(self.manifest, self.repo, 123)

    def test_corrupt_discovered_bundle_is_not_a_miss(self):
        api = self.api
        def corrupt(path, *, binary=False):
            return self.payload(**{apks.FILES["app"]: b"corrupt"}) if binary else api(path)
        with patch.object(apks, "api", side_effect=corrupt), self.assertRaises(ValueError):
            apks.reuse(self.repo, self.tree, self.root / "download")

    def test_expiry_between_discovery_and_download_is_a_miss(self):
        api = self.api
        def expires(path, *, binary=False):
            if binary:
                raise apks.ArtifactUnavailable("gone")
            return api(path)
        with patch.object(apks, "api", side_effect=expires):
            self.assertFalse(apks.reuse(self.repo, self.tree, self.root / "download"))
        self.assertFalse((self.root / "download").exists())

    def test_latest_attempt_cannot_borrow_an_earlier_attempt_success(self):
        self.artifact["name"] = f"android-apks-{self.tree}-123-2"
        api = self.api
        def attempts(path, *, binary=False):
            if "/attempts/2/jobs" in path:
                return {"jobs": [{"name": "Android compile", "conclusion": None}]}
            return api(path, binary=binary)
        with patch.object(apks, "api", side_effect=attempts) as called:
            self.assertFalse(apks.reuse(self.repo, self.tree, self.root / "download"))
        self.assertFalse(any(call.kwargs.get("binary") for call in called.call_args_list))

    def test_pack_keeps_origin_and_direct_reuse_source(self):
        previous = self.root / "prior"
        apks.unpack(self.payload(), previous)
        env = {"GITHUB_REPOSITORY": self.repo, "GITHUB_WORKFLOW_REF": self.repo + "/.github/workflows/android-scale.yml@refs/heads/main",
               "GITHUB_RUN_ID": "456", "GITHUB_RUN_ATTEMPT": "2", "GITHUB_EVENT_NAME": "workflow_dispatch"}
        with patch.dict(os.environ, env), patch.object(apks, "git", side_effect=[SHA, self.tree]):
            result = apks.pack(self.root / "repack", previous / apks.FILES["app"], previous / apks.FILES["test"], previous)
        self.assertEqual(result["run_id"], 456)
        self.assertEqual(result["run_attempt"], 2)
        self.assertEqual(result["reused_from"]["run_id"], 123)
        self.assertEqual(result["build_origin"]["run_id"], 123)
        self.assertEqual(result["sha256"], self.manifest["sha256"])

    def test_pack_records_historical_pr_event_without_copying_event_payload(self):
        app, test = self.root / "app.apk", self.root / "test.apk"
        app.write_bytes(b"app")
        test.write_bytes(b"test")
        event = self.root / "event.json"
        event.write_text(json.dumps({"number": 42, "pull_request": self.pr, "unrelated": "must not be retained"}))
        env = {"GITHUB_REPOSITORY": self.repo, "GITHUB_WORKFLOW_REF": self.repo + "/.github/workflows/ci.yml@refs/pull/42/merge",
               "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1", "GITHUB_EVENT_NAME": "pull_request", "GITHUB_EVENT_PATH": str(event)}
        with patch.dict(os.environ, env), patch.object(apks, "git", side_effect=[SHA, self.tree]):
            manifest = apks.pack(self.root / "pack", app, test)
        self.assertEqual(manifest["pull_request"], {"number": 42, "base_sha": "d" * 40, "head_sha": "c" * 40})
        self.assertNotIn("must not be retained", json.dumps(manifest))

    def test_merged_pr_with_empty_run_array_still_has_verifiable_artifact(self):
        self.run["pull_requests"] = []
        self.pr.update(state="closed", merged_at="2026-10-02T00:00:00Z", merge_commit_sha="e" * 40)
        original = self.api
        def merged(path, *, binary=False):
            if path.endswith("/git/commits/" + "e" * 40):
                return {"sha": "e" * 40, "parents": [{"sha": "f" * 40}, {"sha": "c" * 40}]}
            return original(path, binary=binary)
        with patch.object(apks, "api", side_effect=merged):
            self.assertTrue(apks.reuse(self.repo, self.tree, self.root / "merged-download"))

    def test_pr_advanced_after_the_producer_does_not_rewrite_historical_parents(self):
        self.run["pull_requests"] = []
        self.pr["head"]["sha"] = "e" * 40
        self.pr["base"]["sha"] = "f" * 40
        with patch.object(apks, "api", side_effect=self.api):
            apks.check_source(self.manifest, self.repo, 123)

    def test_closed_unmerged_pr_remains_a_valid_historical_producer(self):
        self.run["pull_requests"] = []
        self.pr["state"] = "closed"
        with patch.object(apks, "api", side_effect=self.api):
            apks.check_source(self.manifest, self.repo, 123)

    def test_current_pr_association_cannot_replace_wrong_historical_identity(self):
        original = copy.deepcopy((self.manifest, self.pr, self.run))
        for fault in ("number", "head", "base", "head-repo", "base-repo", "base-ref", "missing-event"):
            self.manifest, self.pr, self.run = copy.deepcopy(original)
            if fault == "number": self.pr["number"] = 43
            if fault == "head": self.manifest["pull_request"]["head_sha"] = "f" * 40
            if fault == "base": self.manifest["pull_request"]["base_sha"] = "f" * 40
            if fault == "head-repo": self.pr["head"]["repo"]["full_name"] = "fork/ledger"
            if fault == "base-repo": self.pr["base"]["repo"]["full_name"] = "foreign/ledger"
            if fault == "base-ref": self.pr["base"]["ref"] = "untrusted"
            if fault == "missing-event": del self.manifest["pull_request"]
            with self.subTest(fault=fault), patch.object(apks, "api", side_effect=self.api), self.assertRaises(ValueError):
                apks.check_source(self.manifest, self.repo, 123)

    def test_merged_same_tip_additional_parent_check_and_squash_compatibility(self):
        self.pr.update(state="closed", merged_at="2026-10-02T00:00:00Z", merge_commit_sha="e" * 40)
        original = self.api
        for parents, accepted in (([{"sha": "d" * 40}, {"sha": "f" * 40}], False), ([{"sha": "f" * 40}], True)):
            def merged(path, *, binary=False):
                if path.endswith("/git/commits/" + "e" * 40):
                    return {"sha": "e" * 40, "parents": parents}
                return original(path, binary=binary)
            with self.subTest(parents=parents), patch.object(apks, "api", side_effect=merged):
                if accepted:
                    apks.check_source(self.manifest, self.repo, 123)
                else:
                    with self.assertRaises(ValueError): apks.check_source(self.manifest, self.repo, 123)

    def test_commit_association_follows_paginated_array_responses(self):
        original = self.api
        def paginated(path, *, binary=False):
            if "/pulls?" in path:
                return [{"number": index + 100} for index in range(100)] if path.endswith("&page=1") else [self.pr]
            return original(path, binary=binary)
        with patch.object(apks, "api", side_effect=paginated) as called:
            apks.check_source(self.manifest, self.repo, 123)
        self.assertTrue(any("/pulls?per_page=100&page=2" in call.args[0] for call in called.call_args_list))

    def test_only_server_missing_expired_download_errors_allow_fallback(self):
        for code in (404, 410, 403, 500):
            error = subprocess.CalledProcessError(1, ["gh"], stderr=f"gh: message (HTTP {code})".encode())
            exception = apks.ArtifactUnavailable if code in (404, 410) else subprocess.CalledProcessError
            with self.subTest(code=code), patch.object(apks.subprocess, "check_output", side_effect=error), self.assertRaises(exception):
                apks.api("repos/example/ledger/actions/artifacts/1/zip", binary=True)

    def test_source_sha_filter_requires_the_exact_producer_head(self):
        # A dispatch producer's `head_sha` is the tested commit itself; that is
        # the identity a pinned caller must see. (A pull-request producer
        # records its preview merge commit instead, so the pin is only exact
        # for dispatch runs -- which is what the A06 matrix uses.)
        self.run.update(event="workflow_dispatch", head_sha=SHA)
        self.manifest.pop("pull_request", None)
        with patch.object(apks, "api", side_effect=self.api):
            self.assertTrue(apks.reuse(self.repo, self.tree, self.root / "pinned", SHA))
        # The same tree from another commit must not satisfy a pinned request:
        # its verdict would describe a different commit. No download may happen.
        self.run["head_sha"] = "e" * 40
        with patch.object(apks, "api", side_effect=self.api) as api:
            self.assertFalse(apks.reuse(self.repo, self.tree, self.root / "miss", SHA))
        self.assertFalse((self.root / "miss").exists())
        self.assertFalse(any(call.kwargs.get("binary") for call in api.call_args_list))

    def test_source_sha_filter_rejects_a_noncanonical_value(self):
        for value in ("A" * 40, "a" * 39, ""):
            with self.subTest(value=value), self.assertRaises(ValueError):
                apks.reuse(self.repo, self.tree, self.root / "invalid", value)

    def test_a06_reuse_step_pins_the_requested_commit(self):
        workflow = (Path(__file__).resolve().parents[2] /
                    ".github/workflows/android-p706-a06.yml").read_text(encoding="utf-8")
        step = workflow.split("- name: Discover and verify an earlier whole-tree APK bundle", 1)[1].split("      - name:", 1)[0]
        self.assertIn("EXPECTED_SHA: ${{ inputs.expected_sha }}", step)
        self.assertIn('--source-sha "$EXPECTED_SHA"', step)

    def test_source_sha_flag_is_rejected_for_other_actions(self):
        result = subprocess.run([sys.executable, "tools/ci/android-apks.py", "verify",
                                 "--directory", "unused", "--source-sha", SHA],
                                cwd=Path(__file__).resolve().parents[2],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--source-sha is only valid with the reuse action", result.stdout)


class TrustedWorkflowBoundary(unittest.TestCase):
    def test_actual_conditions_allow_same_repo_and_manual_but_skip_fork(self):
        root = Path(__file__).resolve().parents[2]
        ci = (root / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        android = ci.split("  android:\n", 1)[1].split("  python-shards:\n", 1)[0]
        blocks = re.split(r"(?m)^      - ", android)
        conditions = []
        for name in ("Package both APKs with whole-tree provenance", "Upload verified APK bundle"):
            block = next(block for block in blocks if block.startswith("name: " + name + "\n"))
            conditions.append(re.search(r"(?m)^        if: (.+)$", block)[1])
        # Required compilation and the existing human-install APK stay available
        # for fork PRs; only the two trusted-bundle steps may gain conditions.
        for name in ("Compile Android", "Android app unit tests", "Android app instrumented test sources compile",
                     "Android app assemble debug", "Upload debug APK"):
            block = next(block for block in blocks if block.startswith("name: " + name + "\n"))
            self.assertIsNone(re.search(r"(?m)^        if:", block), name)
        preflight = (root / ".github/workflows/android-preflight.yml").read_text(encoding="utf-8")
        producer = preflight.split("  apks:\n", 1)[1].split("    steps:\n", 1)[0]
        conditions.append(re.search(r"(?m)^    if: (.+)$", producer)[1])
        consumer = preflight.split("  preflight:\n", 1)[1].split("    steps:\n", 1)[0]
        self.assertIn("    needs: apks\n", consumer)
        self.assertIsNone(re.search(r"(?m)^    if:", consumer))
        for condition in conditions:
            for event, head, expected in (("pull_request", "example/ledger", True),
                                          ("pull_request", "fork/ledger", False),
                                          ("workflow_dispatch", "", True)):
                expression = condition.replace("github.event.pull_request.head.repo.full_name", repr(head))
                expression = expression.replace("github.repository", repr("example/ledger"))
                expression = expression.replace("github.event_name", repr(event)).replace("&&", " and ").replace("||", " or ")
                parsed = ast.parse(expression, mode="eval")
                self.assertTrue(all(isinstance(node, (ast.Expression, ast.BoolOp, ast.And, ast.Or, ast.Compare, ast.Eq, ast.Constant))
                                    for node in ast.walk(parsed)))
                with self.subTest(event=event, head=head, condition=condition):
                    self.assertIs(eval(compile(parsed, "workflow-condition", "eval"), {"__builtins__": {}}), expected)


class OrdinaryInventory(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "T.kt").write_text("package example\nclass T { @Test fun first() {} @Test fun second() {} }")
        self.manifest = self.root / "inventory.txt"
        self.manifest.write_text("example.T#first\nexample.T#second\n")
        self.results = self.root / "results"
        self.results.mkdir()
        self.suite = ET.Element("testsuite", tests="2", errors="0", failures="0", skipped="0")
        for name in ("first", "second"):
            ET.SubElement(self.suite, "testcase", classname="example.T", name=name)

    def write(self):
        ET.ElementTree(self.suite).write(self.results / "TEST-example.T.xml")

    def test_exact_source_and_executed_case_set(self):
        self.write()
        self.assertEqual(check_inventory(self.root, self.manifest, self.results), 2)

    def test_missing_zero_skipped_extra_or_duplicate_case_fails(self):
        for fault in ("missing", "zero", "skipped", "extra", "duplicate"):
            with self.subTest(fault=fault):
                original = copy.deepcopy(self.suite)
                if fault == "missing":
                    with self.assertRaises(ValueError):
                        check_inventory(self.root, self.manifest, self.root / "absent")
                    continue
                if fault == "zero":
                    self.suite.clear()
                    self.suite.set("tests", "0")
                if fault == "skipped": ET.SubElement(self.suite[0], "skipped")
                if fault == "extra": self.suite[0].set("name", "third")
                if fault == "duplicate": self.suite[1].set("name", "first")
                self.write()
                with self.assertRaises(ValueError): check_inventory(self.root, self.manifest, self.results)
                self.suite = original

    def test_source_addition_requires_manifest_update(self):
        (self.root / "T.kt").write_text("package example\nclass T { @Test fun third() {} }")
        with self.assertRaises(ValueError): check_inventory(self.root, self.manifest)


_A06_MATRIX = None


def a06_matrix():
    """The P706-A06 matrix driver, loaded by path because its file name is hyphenated."""
    global _A06_MATRIX
    if _A06_MATRIX is None:
        import importlib.util

        path = Path(__file__).resolve().parents[2] / "tools" / "ci" / "android-a06-cloud.py"
        spec = importlib.util.spec_from_file_location("android_a06_cloud", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        _A06_MATRIX = module
    return _A06_MATRIX


A06_SHA = "2fd1a67b" + "0" * 32


def a06_status_block(test_class, test_method, code, *, numtests="1"):
    return "\n".join([
        f"INSTRUMENTATION_STATUS: class={test_class}",
        "INSTRUMENTATION_STATUS: current=1",
        "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
        f"INSTRUMENTATION_STATUS: numtests={numtests}",
        "INSTRUMENTATION_STATUS: stream=",
        f"INSTRUMENTATION_STATUS: test={test_method}",
        f"INSTRUMENTATION_STATUS_CODE: {code}",
    ]) + "\n"


def a06_kill_transcript(row, *, codes=(1,), test_class=None, test_method=None, numtests="1"):
    matrix = a06_matrix()
    return "".join(
        a06_status_block(test_class or matrix.KILL_CLASS, test_method or row.kill_method, code, numtests=numtests)
        for code in codes
    )


def a06_parked_call():
    """The decorated call the park line names; the verdict file must repeat it verbatim."""
    return f"delete /data/user/0/{a06_matrix().PACKAGE}/cache/root/gen-2/ledger.db"


def a06_kill_logcat(row, *, extra=""):
    return ("11-02 01:00:00.000  1234  1250 I P706A06Boundary: parked at "
            f"{row.boundary} on {a06_parked_call()}\n"
            f"11-02 01:00:00.100  1234  1250 I P706A06Boundary: "
            f"run A marker written for {row.boundary}; killing the process\n" + extra)


def a06_marker(row, *, boundary=None, convergence="OPENS_OLD_GENERATION"):
    return "\n".join([
        "p706a06-boundary-marker-v1",
        f"boundary={boundary or row.boundary}",
        f"convergence={convergence}",
        "authoritativePayload=p706a06-confirm-old-generation",
        "legacyPayload=-",
        "legacySha256=-",
        "authoritativeSha256=" + "a" * 64,
    ]) + "\n"


def a06_run_a_log(row):
    return (f"boundary={row.boundary}\nphase=CONFIRM_SWITCH\nconvergence=OPENS_OLD_GENERATION\n"
            f"parkedOn={a06_parked_call()}\n")


def a06_verify_transcript(row, *, codes=(1, 0), test_method=None, numtests="1", tail=None):
    matrix = a06_matrix()
    body = "".join(
        a06_status_block(matrix.VERIFY_CLASS, test_method or row.verify_method, code, numtests=numtests)
        for code in codes
    )
    closing = tail if tail is not None else ("INSTRUMENTATION_RESULT: stream=\n", "Time: 1.2\n",
                                             "OK (1 test)\n", "INSTRUMENTATION_CODE: -1\n")
    return body + "".join(closing)


def a06_shell_blocks(text):
    """Every `run:`/`script:` shell body in a workflow file, one string each."""
    blocks = []
    lines = text.splitlines()
    index = 0
    while index < len(lines):
        line = lines[index]
        stripped = line.strip()
        key = ("run:" if stripped.startswith("run:")
               else "script:" if stripped.startswith("script:") else None)
        if key is None:
            index += 1
            continue
        indent = len(line) - len(line.lstrip())
        body = [stripped[len(key):].strip()]
        index += 1
        while index < len(lines):
            child = lines[index]
            if child.strip() and (len(child) - len(child.lstrip())) <= indent:
                break
            body.append(child)
            index += 1
        blocks.append("\n".join(body))
    return blocks


class FakeA06Device:
    """A scripted device: only what one boundary pair needs, with its call log kept."""

    def __init__(self, matrix, row, *, surviving_marker=False, verify_transcript=None):
        self.matrix, self.row = matrix, row
        self.surviving_marker = surviving_marker
        self.verify_transcript = verify_transcript
        self.calls: list[tuple[str, ...]] = []
        self.instruments: list[str] = []
        self.marker_reads = 0

    def text(self, *args, timeout=120, best_effort=False):
        self.calls.append(args)
        return a06_kill_logcat(self.row) if args[0] == "logcat" else ""

    def instrument(self, target, timeout):
        self.instruments.append(target)
        if target == self.row.kill_target:
            return 137, a06_kill_transcript(self.row)
        return 0, self.verify_transcript or a06_verify_transcript(self.row)

    def read_private(self, relative):
        if relative.endswith("p706a06-boundary-marker"):
            self.marker_reads += 1
            if self.marker_reads == 1:
                return a06_marker(self.row)
            return a06_marker(self.row) if self.surviving_marker else None
        if relative.endswith("p706a06-run-a.log"):
            return a06_run_a_log(self.row)
        return None


class P706A06Matrix(unittest.TestCase):
    """Offline proofs for the nineteen-boundary process-kill matrix driver.

    The driver's verdict is what turns a deliberate process death into evidence, so
    every rejection path is exercised here without a device, an APK or Gradle: the
    boundary plan against the real Kotlin sources, the three kill-leg signals, the
    strict verify leg, the paired driver, and the gate over four shards.
    """

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.matrix = a06_matrix()
        self.row = self.matrix.BY_BOUNDARY["B1"]

    def kill(self, row=None, **kwargs):
        row = row or self.row
        return self.matrix.validate_kill_leg(
            row=row,
            transcript=kwargs.get("transcript", a06_kill_transcript(row)),
            logcat=kwargs.get("logcat", a06_kill_logcat(row)),
            marker=kwargs.get("marker", a06_marker(row)),
            run_a_log=kwargs.get("run_a_log", a06_run_a_log(row)),
        )

    # ------------------------------------------------------------------ plan and file

    def test_plan_covers_nineteen_boundaries_once_across_four_disjoint_shards(self):
        names = [row.boundary for row in self.matrix.BOUNDARIES]
        self.assertEqual(names, [f"B{index}" for index in range(1, 20)])
        shards = {shard: [row.boundary for row in self.matrix.shard_boundaries(shard)] for shard in "1234"}
        self.assertEqual(shards, {"1": ["B1", "B2", "B3", "B4", "B5"],
                                  "2": ["B6", "B7", "B8", "B9", "B10"],
                                  "3": ["B11", "B12", "B13", "B14", "B15"],
                                  "4": ["B16", "B17", "B18", "B19"]})
        flattened = [name for chunk in shards.values() for name in chunk]
        self.assertEqual(len(flattened), len(set(flattened)))
        self.assertEqual(set(flattened), set(names))
        self.assertEqual([entry["boundary"] for entry in self.matrix.plan("4")], shards["4"])
        self.assertEqual(self.matrix.plan("1")[0]["kill"],
                         self.matrix.KILL_CLASS + "#b01PreStageDeleteKillsMidDeletion")

    def test_plan_matches_the_instrumented_sources(self):
        android_test = (Path(__file__).resolve().parents[2] /
                        "android-app/src/androidTest/kotlin/com/unifiedledger/android")
        kill_source = (android_test / "P706A06BoundaryKillInstrumentedTest.kt").read_text(encoding="utf-8")
        verify_source = (android_test / "P706A06BoundaryVerifyInstrumentedTest.kt").read_text(encoding="utf-8")
        harness = (android_test / "P706A06BoundaryHarness.kt").read_text(encoding="utf-8")
        self.assertIn("package com.unifiedledger.android\n", kill_source)
        self.assertIn("class P706A06BoundaryKillInstrumentedTest {", kill_source)
        self.assertIn("class P706A06BoundaryVerifyInstrumentedTest {", verify_source)
        self.assertEqual(re.findall(r"fun (b\d\d[A-Za-z0-9]+)\(\)", kill_source),
                         [row.kill_method for row in self.matrix.BOUNDARIES])
        self.assertEqual(re.findall(r"fun (v\d\d[A-Za-z0-9]+)\(\)", verify_source),
                         [row.verify_method for row in self.matrix.BOUNDARIES])
        self.assertEqual(self.matrix.KILL_CLASS, "com.unifiedledger.android.P706A06BoundaryKillInstrumentedTest")
        self.assertEqual(self.matrix.VERIFY_CLASS,
                         "com.unifiedledger.android.P706A06BoundaryVerifyInstrumentedTest")
        # The device constants the driver reads back by path must stay in sync too.
        self.assertIn('STABLE_ROOT_DIRECTORY_NAME = "p706a06-boundary-root"', harness)
        self.assertIn('FILE_NAME = "p706a06-boundary-marker"', harness)
        self.assertIn('RUN_A_LOG_NAME = "p706a06-run-a.log"', kill_source)

    def test_plan_rejects_an_unknown_shard(self):
        with self.assertRaises(self.matrix.A06Error):
            self.matrix.shard_boundaries("5")
        with self.assertRaises(self.matrix.A06Error):
            self.matrix.check_shard(self.root, "0", A06_SHA)

    def test_workflow_uses_one_avd_per_shard_and_never_builds_apks(self):
        text = (Path(__file__).resolve().parents[2] /
                ".github/workflows/android-p706-a06.yml").read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", text)
        self.assertIn("\n        required: true\n", text)
        self.assertIn("  actions: read\n", text)
        self.assertIn("shard: ['1', '2', '3', '4']\n", text)
        self.assertEqual(text.count("avd-name:"), 1)
        self.assertIn("avd-name: ul-a06-s${{ matrix.shard }}", text)
        self.assertIn("python3 tools/ci/android-apks.py reuse --directory reused-apks", text)
        self.assertNotIn("gradlew", text)
        self.assertIn("if: steps.reuse.outputs.hit != 'true'", text)
        self.assertIn("python3 tools/ci/android-a06-cloud.py check-shard", text)
        self.assertIn("python3 tools/ci/android-a06-cloud.py gate", text)
        self.assertIn("merge-multiple: true", text)
        # The scope statement must survive editing: process kill only, never an A06 pass.
        self.assertIn("process kill only", text)
        self.assertIn("not an A06 pass", text)
        # The bundle/commit binding is settled in the APK job, before any emulator exists.
        self.assertIn('check-bundle --directory reused-apks --sha "${EXPECTED_SHA}"', text)
        self.assertLess(text.index("check-bundle --directory reused-apks"),
                        text.index("name: P706-A06 shard"))

    # ------------------------------------------------------------------------ kill leg

    def test_kill_leg_accepts_the_expected_process_death(self):
        record = self.kill()
        self.assertEqual(record["status"], "PASS")
        self.assertEqual(record["method"], self.matrix.KILL_CLASS + "#b01PreStageDeleteKillsMidDeletion")
        self.assertIn("delete", record["parkedOn"])

    def test_kill_leg_rejects_a_missing_marker(self):
        with self.assertRaises(self.matrix.A06Error):
            self.kill(marker=None)
        with self.assertRaises(self.matrix.A06Error):
            self.kill(run_a_log=None)

    def test_kill_leg_rejects_a_foreign_or_duplicated_marker(self):
        with self.assertRaises(self.matrix.A06Error):
            self.kill(marker=a06_marker(self.row, boundary="B7"))
        with self.assertRaises(self.matrix.A06Error):
            self.kill(marker=a06_marker(self.row) + "boundary=B9\n")
        with self.assertRaises(self.matrix.A06Error):
            self.kill(marker=a06_marker(self.row).replace("p706a06-boundary-marker-v1", "wrong-header"))
        with self.assertRaises(self.matrix.A06Error):
            self.kill(marker=a06_marker(self.row, convergence="SOMETHING_ELSE"))

    def test_kill_leg_rejects_an_assertion_failure_before_the_kill(self):
        with self.assertRaises(self.matrix.A06Error):
            self.kill(transcript=a06_kill_transcript(self.row, codes=(1, -2)))
        failed = a06_kill_logcat(self.row) + "junit.framework.AssertionFailedError: boundary never parked\n"
        with self.assertRaises(self.matrix.A06Error):
            self.kill(logcat=failed)

    def test_kill_leg_rejects_a_crash_instead_of_the_deliberate_kill(self):
        for signature in ("E AndroidRuntime: FATAL EXCEPTION: p706a06-flow-B1\n",
                          "F Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)\n",
                          "E AndroidRuntime: java.lang.OutOfMemoryError: failed to allocate\n"):
            with self.subTest(signature=signature):
                with self.assertRaises(self.matrix.A06Error):
                    self.kill(logcat=a06_kill_logcat(self.row, extra=signature))

    def test_kill_leg_rejects_a_missing_kill_or_park_logcat_line(self):
        with self.assertRaises(self.matrix.A06Error):
            self.kill(logcat=a06_kill_logcat(self.row).replace("; killing the process", ""))
        only_kill = ("11-02 01:00:00.100  1234  1250 I P706A06Boundary: "
                     f"run A marker written for {self.row.boundary}; killing the process\n")
        with self.assertRaises(self.matrix.A06Error):
            self.kill(logcat=only_kill)

    def test_kill_leg_rejects_the_wrong_case_or_more_than_one_test(self):
        with self.assertRaises(self.matrix.A06Error):
            self.kill(transcript=a06_kill_transcript(self.row, test_method="b02MainFileCopyKillsAfterTheCopy"))
        with self.assertRaises(self.matrix.A06Error):
            self.kill(transcript=a06_kill_transcript(self.row, codes=(1, 1)))
        with self.assertRaises(self.matrix.A06Error):
            self.kill(transcript=a06_kill_transcript(self.row, numtests="2"))
        with self.assertRaises(self.matrix.A06Error):
            self.kill(transcript="")

    # ---------------------------------------------------------------------- verify leg

    def test_verify_leg_accepts_a_clean_single_test(self):
        record = self.matrix.validate_verify_leg(row=self.row, transcript=a06_verify_transcript(self.row),
                                                 marker=None)
        self.assertEqual(record["status"], "PASS")
        self.assertEqual(record["method"],
                         self.matrix.VERIFY_CLASS + "#v01PreStageDeleteMidKillConvergesToTheOldGeneration")

    def test_verify_leg_rejects_a_surviving_marker(self):
        with self.assertRaises(self.matrix.A06Error):
            self.matrix.validate_verify_leg(row=self.row, transcript=a06_verify_transcript(self.row),
                                            marker=a06_marker(self.row))

    def test_verify_leg_rejects_a_missing_or_extra_case(self):
        for transcript in (a06_verify_transcript(self.row, codes=(1, 0, 1, 0)),
                           a06_verify_transcript(self.row, tail=("INSTRUMENTATION_CODE: -1\n",)),
                           a06_verify_transcript(
                               self.row, test_method="v02MainFileCopyMidKillLeavesTheStagedGenerationInert"),
                           a06_verify_transcript(self.row, numtests="2"),
                           a06_verify_transcript(self.row, codes=(1, -2))):
            with self.subTest(tail=transcript[-60:]):
                with self.assertRaises(ValueError):
                    self.matrix.validate_verify_leg(row=self.row, transcript=transcript, marker=None)

    # --------------------------------------------------------------------------- driver

    def test_run_boundary_starts_one_instrumentation_per_leg_and_cleans_first(self):
        device = FakeA06Device(self.matrix, self.row)
        record = self.matrix.run_boundary(device, self.row, self.root / "B1")
        self.assertEqual(device.instruments, [self.row.kill_target, self.row.verify_target])
        self.assertEqual([call[0] for call in device.calls][:3], ["shell", "shell", "logcat"])
        self.assertIn(("shell", "am", "force-stop", self.matrix.PACKAGE), device.calls)
        self.assertIn(("shell", "run-as", self.matrix.PACKAGE, "rm", "-rf", self.matrix.STABLE_ROOT), device.calls)
        self.assertEqual(record["kill"]["status"], "PASS")
        self.assertEqual(record["verify"]["status"], "PASS")
        for name in ("kill-transcript.txt", "kill-logcat.txt", "marker.txt", "run-a.log",
                     "verify-transcript.txt", "verify-logcat.txt", "result.json"):
            self.assertTrue((self.root / "B1" / name).is_file(), name)

    def test_run_boundary_fails_when_the_verify_leg_leaves_the_marker(self):
        device = FakeA06Device(self.matrix, self.row, surviving_marker=True)
        with self.assertRaises(self.matrix.A06Error):
            self.matrix.run_boundary(device, self.row, self.root / "B1")
        self.assertFalse((self.root / "B1" / "result.json").exists())

    def test_run_boundary_rejects_a_verify_leg_that_reports_a_shell_failure(self):
        class FailingVerify(FakeA06Device):
            def instrument(inner, target, timeout):
                if target == inner.row.kill_target:
                    return super().instrument(target, timeout)
                inner.instruments.append(target)
                return 1, a06_verify_transcript(inner.row)

        with self.assertRaises(self.matrix.A06Error):
            self.matrix.run_boundary(FailingVerify(self.matrix, self.row), self.row, self.root / "B1")

    def test_driver_refuses_to_run_outside_a_hosted_runner(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(self.matrix.A06Error):
                self.matrix.drive_shard("1", self.root / "app.apk", self.root / "test.apk", self.root, A06_SHA,
                                        "ul-a06-s1", self.matrix.Deadline(60))
        self.assertFalse((self.root / "shard-1").exists())
        self.matrix.require_hosted_runner({"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted",
                                           "RUNNER_OS": "Linux"})

    def test_avd_name_probe_accepts_the_console_terminator(self):
        # `emu avd name` answers with the name and its own OK line; a doubled CR from
        # text-mode translation must not turn into a phantom empty line.
        for raw in ("ul-a06-s2\nOK\n", "ul-a06-s2\r\nOK\r\n", "ul-a06-s2\r\r\nOK\r\r\n", "ul-a06-s2\n"):
            with self.subTest(raw=raw):
                self.assertEqual(self.matrix.parse_avd_name(raw), "ul-a06-s2")
        # Anything else is refused rather than guessed, so the caller reports the raw text.
        for raw in ("", "OK\n", "ul-a06-s2\nOK\nextra\n", "OK\nul-a06-s2\n", "ul-a06-s2\nul-a06-s3\n"):
            with self.subTest(raw=raw):
                self.assertEqual(self.matrix.parse_avd_name(raw), "")

    def test_one_runner_never_hosts_two_emulators(self):
        self.assertEqual(self.matrix.owned_serial("List of devices attached\nemulator-5680\tdevice\n"),
                         "emulator-5680")
        for devices in ("List of devices attached\nemulator-5680\tdevice\nemulator-5682\tdevice\n",
                        "List of devices attached\nemulator-5680\toffline\n",
                        "List of devices attached\n"):
            with self.subTest(devices=devices):
                with self.assertRaises(self.matrix.A06Error):
                    self.matrix.owned_serial(devices)

    def test_install_rejects_a_runner_for_another_package(self):
        app = self.root / "app.apk"
        app.write_bytes(b"app")
        test = self.root / "test.apk"
        test.write_bytes(b"test")

        class InstallDevice:
            def __init__(self, listing):
                self.listing = listing

            def text(self, *args, timeout=120, best_effort=False):
                if args[0] == "install":
                    return "Success\n"
                if args[:3] == ("shell", "pm", "list"):
                    return self.listing
                return "package:/data/app/base.apk\n"

        good = f"instrumentation:{self.matrix.TEST_RUNNER} (target={self.matrix.PACKAGE})\n"
        hashes = self.matrix.install_apks(InstallDevice(good), app, test)
        self.assertEqual(set(hashes), {"app", "test"})
        with self.assertRaises(self.matrix.A06Error):
            self.matrix.install_apks(
                InstallDevice(f"instrumentation:{self.matrix.TEST_RUNNER} (target=other)\n"), app, test)

    def test_deadline_bounds_each_step_and_refuses_an_exhausted_budget(self):
        deadline = self.matrix.Deadline(600, clock=lambda: 1000.0, epoch_clock=lambda: 500.0)
        self.assertEqual(deadline.remaining(), 100)
        self.assertEqual(deadline.bound(30), 30)
        expired = self.matrix.Deadline(-5, clock=lambda: 1000.0, epoch_clock=lambda: 500.0)
        with self.assertRaises(self.matrix.A06DeadlineError):
            expired.bound(30)

    # ----------------------------------------------------------------------------- gate

    def new_root(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        return Path(temp.name)

    def write_shard(self, shard, root=None, *, boundaries=None, failed=None, verify_failed=None):
        matrix = self.matrix
        root = self.root if root is None else root
        rows = matrix.shard_boundaries(shard)
        listed = [row.boundary for row in rows] if boundaries is None else boundaries
        directory = root / f"shard-{shard}"
        directory.mkdir(parents=True, exist_ok=True)
        for row in rows:
            if row.boundary == failed:
                continue
            folder = directory / row.boundary
            folder.mkdir()
            status = "FAIL" if row.boundary == verify_failed else "PASS"
            (folder / "kill-transcript.txt").write_text(a06_kill_transcript(row), encoding="utf-8")
            (folder / "kill-logcat.txt").write_text(a06_kill_logcat(row), encoding="utf-8")
            (folder / "marker.txt").write_text(a06_marker(row), encoding="utf-8")
            (folder / "run-a.log").write_text(a06_run_a_log(row), encoding="utf-8")
            (folder / "verify-transcript.txt").write_text(a06_verify_transcript(row), encoding="utf-8")
            (folder / "verify-logcat.txt").write_text(
                "11-02 01:00:02.000  1234  1250 I ActivityManager: Start proc for instrumentation\n",
                encoding="utf-8")
            (folder / "result.json").write_text(json.dumps({
                "schema": 1,
                "boundary": row.boundary,
                "kill": {"status": status, "method": row.kill_target,
                         "convergence": "OPENS_OLD_GENERATION", "parkedOn": a06_parked_call(),
                         "instrumentationStatus": 137},
                "verify": {"status": status, "method": row.verify_target},
            }), encoding="utf-8")
        (directory / "shard.json").write_text(json.dumps({
            "schema": 1,
            "shard": shard,
            "sha": A06_SHA,
            "avd": f"ul-a06-s{shard}",
            "serial": "emulator-5680",
            "boundaries": listed,
            "completed": listed,
            "apk_sha256": {"app": "a" * 64, "test": "b" * 64},
            "elapsed_seconds": 12.5,
            "scope": matrix.PROCESS_KILL_SCOPE,
        }), encoding="utf-8")

    def write_all_shards(self, root=None, **kwargs):
        root = self.root if root is None else root
        for shard in "1234":
            self.write_shard(shard, root, **kwargs)
        return root

    def write_apks(self, *, source_sha=A06_SHA, root=None):
        root = self.root if root is None else root
        directory = root / "apks"
        directory.mkdir()
        (directory / "provenance.json").write_text(json.dumps({
            "schema": 1, "repository": "example/ledger", "source_sha": source_sha, "tree": "b" * 40,
            "run_id": 123, "run_attempt": 1, "workflow": ".github/workflows/ci.yml",
            "sha256": {"app": "a" * 64, "test": "b" * 64},
        }), encoding="utf-8")
        return directory

    def rebuild(self):
        """A fresh, complete four-shard evidence tree in its own directory."""
        return self.write_all_shards(root=self.new_root())

    def corrupt(self, root, shard, boundary, name, content):
        (root / f"shard-{shard}" / boundary / name).write_text(content, encoding="utf-8")
        return root

    def remove(self, root, shard, boundary, name):
        (root / f"shard-{shard}" / boundary / name).unlink()
        return root

    def test_gate_accepts_all_nineteen_pairs(self):
        report = self.matrix.validate_gate(self.write_all_shards(), A06_SHA)
        self.assertEqual(report["boundary_count"], 19)
        self.assertEqual(report["boundaries_executed"], [row.boundary for row in self.matrix.BOUNDARIES])
        self.assertEqual([entry["shard"] for entry in report["shards"]], ["1", "2", "3", "4"])
        self.assertIsNone(report["apk"])

    def test_gate_rejects_a_missing_shard(self):
        for shard in "124":
            self.write_shard(shard)
        with self.assertRaises(self.matrix.A06Error):
            self.matrix.validate_gate(self.root, A06_SHA)

    def test_gate_rejects_an_incomplete_or_foreign_boundary_list(self):
        for shard in "1234":
            self.write_shard(shard, boundaries=(["B1", "B2", "B3", "B4"] if shard == "1" else
                                                [row.boundary for row in self.matrix.shard_boundaries(shard)]))
        with self.assertRaises(self.matrix.A06Error):
            self.matrix.validate_gate(self.root, A06_SHA)

    def test_gate_rejects_a_failed_leg(self):
        for shard in "1234":
            self.write_shard(shard, verify_failed="B7")
        with self.assertRaises(self.matrix.A06Error):
            self.matrix.validate_gate(self.root, A06_SHA)

    def test_gate_rejects_absent_boundary_evidence(self):
        for shard in "1234":
            self.write_shard(shard, failed="B12")
        with self.assertRaises(OSError):
            self.matrix.validate_gate(self.root, A06_SHA)

    def test_gate_rejects_an_apk_bundle_from_another_commit(self):
        self.write_all_shards()
        apks_dir = self.write_apks(source_sha="c" * 40)
        with self.assertRaises(self.matrix.A06Error):
            self.matrix.validate_gate(self.root, A06_SHA, apks_dir)
        (apks_dir / "provenance.json").unlink()
        with self.assertRaises(self.matrix.A06Error):
            self.matrix.validate_gate(self.root, A06_SHA, apks_dir)

    def test_gate_states_the_process_kill_scope_and_never_an_a06_pass(self):
        report = self.matrix.validate_gate(self.write_all_shards(), A06_SHA, self.write_apks())
        self.assertEqual(report["scope"]["covers"], "process-kill-only")
        self.assertEqual(report["scope"]["power_loss"], "not-covered")
        self.assertEqual(report["scope"]["a06_verdict"], "not-passed")
        self.assertIn("non-PASS", report["scope"]["statement"])
        self.assertEqual(report["apk"]["producer"], ".github/workflows/ci.yml")
        self.assertTrue(report["apk"]["attested"])
        self.assertEqual(report["apk"]["source_sha"], A06_SHA)

    def test_gate_rejects_a_wrong_sha(self):
        self.write_all_shards()
        with self.assertRaises(self.matrix.A06Error):
            self.matrix.validate_gate(self.root, "c" * 40)
        with self.assertRaises(self.matrix.A06Error):
            self.matrix.validate_gate(self.root, "2FD1A67B" + "0" * 32)

    # ------------------------------------------------ raw evidence, not the verdict file

    def test_gate_rereads_the_marker_instead_of_trusting_the_verdict(self):
        root = self.rebuild()
        # result.json still says PASS; only the marker on disk now names another row.
        self.corrupt(root, "2", "B7", "marker.txt", a06_marker(self.matrix.BY_BOUNDARY["B9"]))
        with self.assertRaisesRegex(self.matrix.A06Error, "foreign or stale marker"):
            self.matrix.validate_gate(root, A06_SHA)

    def test_check_shard_rejects_a_lost_marker(self):
        root = self.remove(self.rebuild(), "2", "B7", "marker.txt")
        with self.assertRaisesRegex(self.matrix.A06Error, "no marker"):
            self.matrix.check_shard(root, "2", A06_SHA)

    def test_check_shard_rejects_a_lost_run_a_log(self):
        root = self.remove(self.rebuild(), "3", "B13", "run-a.log")
        with self.assertRaisesRegex(self.matrix.A06Error, "no run A log"):
            self.matrix.check_shard(root, "3", A06_SHA)

    def test_check_shard_rejects_a_kill_transcript_that_failed(self):
        row = self.matrix.BY_BOUNDARY["B4"]
        root = self.corrupt(self.rebuild(), "1", "B4", "kill-transcript.txt",
                            a06_kill_transcript(row, codes=(1, -2)))
        with self.assertRaisesRegex(self.matrix.A06Error, "non-start status"):
            self.matrix.check_shard(root, "1", A06_SHA)

    def test_check_shard_rejects_a_kill_logcat_without_the_kill_line(self):
        row = self.matrix.BY_BOUNDARY["B9"]
        stripped = a06_kill_logcat(row).replace("; killing the process", "")
        root = self.corrupt(self.rebuild(), "2", "B9", "kill-logcat.txt", stripped)
        with self.assertRaisesRegex(self.matrix.A06Error, "no kill line"):
            self.matrix.check_shard(root, "2", A06_SHA)

    def test_check_shard_rejects_a_verify_transcript_without_the_clean_completion(self):
        row = self.matrix.BY_BOUNDARY["B17"]
        root = self.corrupt(self.rebuild(), "4", "B17", "verify-transcript.txt",
                            a06_verify_transcript(row, tail=("INSTRUMENTATION_CODE: -1\n",)))
        with self.assertRaisesRegex(ValueError, "JUnit completion"):
            self.matrix.check_shard(root, "4", A06_SHA)

    def test_check_shard_rejects_a_verdict_that_disagrees_with_the_marker(self):
        root = self.rebuild()
        record_path = root / "shard-1" / "B2" / "result.json"
        record = json.loads(record_path.read_text(encoding="utf-8"))
        record["kill"]["convergence"] = "OPENS_NEW_GENERATION"
        record_path.write_text(json.dumps(record), encoding="utf-8")
        with self.assertRaisesRegex(self.matrix.A06Error, "convergence"):
            self.matrix.check_shard(root, "1", A06_SHA)

    def test_check_shard_rejects_an_avd_from_another_shard(self):
        root = self.rebuild()
        record_path = root / "shard-3" / "shard.json"
        record = json.loads(record_path.read_text(encoding="utf-8"))
        record["avd"] = "ul-a06-s4"
        record_path.write_text(json.dumps(record), encoding="utf-8")
        with self.assertRaisesRegex(self.matrix.A06Error, "owns exactly one AVD"):
            self.matrix.check_shard(root, "3", A06_SHA)

    def test_driver_refuses_a_shard_on_another_shard_avd(self):
        with patch.dict(os.environ, GITHUB_ACTIONS="true", RUNNER_ENVIRONMENT="github-hosted",
                        RUNNER_OS="Linux"):
            with self.assertRaisesRegex(self.matrix.A06Error, "own AVD"):
                self.matrix.drive_shard("2", self.root / "app.apk", self.root / "test.apk", self.root,
                                        A06_SHA, "ul-a06-s3", self.matrix.Deadline(60))

    # ------------------------------------------------ the bundle must attest this commit

    def test_bundle_check_requires_the_exact_source_commit(self):
        root = self.new_root()
        bundle = self.write_apks(root=root)
        manifest = self.matrix.require_producer_bundle(bundle, A06_SHA)
        self.assertEqual(manifest["source_sha"], A06_SHA)
        self.assertTrue(manifest["attested"])
        # Same tree, different commit: `reuse` accepts it, the matrix must refuse it.
        (bundle / "provenance.json").write_text(json.dumps({
            "schema": 1, "repository": "example/ledger", "source_sha": "c" * 40, "tree": "b" * 40,
            "run_id": 123, "run_attempt": 1, "workflow": ".github/workflows/ci.yml",
            "sha256": {"app": "a" * 64, "test": "b" * 64},
        }), encoding="utf-8")
        with self.assertRaisesRegex(self.matrix.A06Error, "same tree"):
            self.matrix.require_producer_bundle(bundle, A06_SHA)
        # A manifest without a usable tree/source binding is not provenance either.
        (bundle / "provenance.json").write_text(json.dumps({
            "schema": 1, "source_sha": A06_SHA, "tree": "not-a-tree",
            "sha256": {"app": "a" * 64, "test": "b" * 64},
        }), encoding="utf-8")
        with self.assertRaisesRegex(self.matrix.A06Error, "invalid tree"):
            self.matrix.require_producer_bundle(bundle, A06_SHA)
        (bundle / "provenance.json").unlink()
        with self.assertRaises(self.matrix.A06Error):
            self.matrix.require_producer_bundle(bundle, A06_SHA)

    def test_shell_blocks_take_values_from_the_environment(self):
        text = (Path(__file__).resolve().parents[2] /
                ".github/workflows/android-p706-a06.yml").read_text(encoding="utf-8")
        blocks = a06_shell_blocks(text)
        self.assertGreaterEqual(len(blocks), 6)
        self.assertEqual([block for block in blocks if "${{" in block], [])
        joined = "\n".join(blocks)
        for expected in ('"${EXPECTED_SHA}"', '"${SHA}"', '"$SHARD"', '"$EXPECTED_SHA"'):
            self.assertIn(expected, joined)


class TrustedProducerSet(unittest.TestCase):
    """The trust boundary in apks.py is an exact set, not an open registry."""

    def test_only_bundle_packing_workflows_are_trusted(self):
        # Each entry must be a workflow whose producer job calls
        # `android-apks.py pack`; a consumer-only workflow (for example the
        # P706-A06 kill/verify chain) must never be added: it would widen what
        # `reuse` and `check_source` accept without ever producing a bundle.
        self.assertEqual(apks.PRODUCERS, {
            ".github/workflows/ci.yml": "Android compile",
            ".github/workflows/android-scale.yml": "Android scale APKs",
            ".github/workflows/android-preflight.yml": "Android preflight APKs",
            ".github/workflows/android-instrumented.yml": "Android instrumented APKs",
        })
        root = Path(__file__).resolve().parents[2]
        for workflow, job in apks.PRODUCERS.items():
            text = (root / workflow).read_text(encoding="utf-8")
            self.assertIn(f"    name: {job}\n", text, workflow)


if __name__ == "__main__":
    unittest.main()
