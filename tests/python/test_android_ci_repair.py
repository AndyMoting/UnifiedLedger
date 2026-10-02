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


if __name__ == "__main__":
    unittest.main()
