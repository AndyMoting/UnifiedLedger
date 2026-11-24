"""Offline proofs for the P8-07 Q2 flow-2 device rig (cloud AVD chain). No device is touched."""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from android_p807_q2 import (
    CONFIG_FILE,
    DEFAULT_AVD,
    EXPECTED_API,
    FLOWS,
    HOST_FILE,
    PASS_MARKER,
    TRANSCRIPT_FILE,
    Q2Error,
    flow_case,
    gate_evidence,
    judge,
    owned_serial,
    parse_avd_name,
    parse_instrumentation,
    require_hosted_runner,
    require_sha,
    run_session,
)

SHA = "0123456789abcdef0123456789abcdef01234567"
CASE = flow_case(2)
CLASS, _, METHOD = CASE.partition("#")

PASSING = f"""\
INSTRUMENTATION_STATUS: class={CLASS}
INSTRUMENTATION_STATUS: test={METHOD}
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: stream=
{CLASS}:
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class={CLASS}
INSTRUMENTATION_STATUS: test={METHOD}
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: stream=.
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_STATUS: class={CLASS}
INSTRUMENTATION_STATUS: test={METHOD}
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: stream=
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_CODE: -1
OK (1 test)
"""

FAILING = f"""\
INSTRUMENTATION_STATUS: class={CLASS}
INSTRUMENTATION_STATUS: test={METHOD}
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class={CLASS}
INSTRUMENTATION_STATUS: test={METHOD}
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: the audit line was not verbatim
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS_CODE: -2
There was 1 failure:
1) {METHOD}
java.lang.AssertionError: the audit line was not verbatim
FAILURES!!!
Tests run: 1,  Failures: 1
INSTRUMENTATION_CODE: -1
"""

SHORT_MSG = f"""\
INSTRUMENTATION_STATUS: class={CLASS}
INSTRUMENTATION_STATUS: test={METHOD}
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_FAILED: {CLASS}
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: Error=Unable to find instrumentation info for: ComponentInfo{{{CLASS}}}
android.util.AndroidException: INSTRUMENTATION_FAILED: {CLASS}
        at com.android.commands.am.Instrument.run(Instrument.java:519)
INSTRUMENTATION_STATUS: shortMsg=Process crashed.
"""

CRASHED = f"""\
INSTRUMENTATION_STATUS: class={CLASS}
INSTRUMENTATION_STATUS: test={METHOD}
INSTRUMENTATION_STATUS_CODE: 1
Process crashed.
INSTRUMENTATION_CODE: 0
"""

# The device-log protocol the driver must follow (W-01): the clear runs after the installs and
# before the case, and the dump runs AFTER the case, so `logcat.txt` carries this run's lines.
LOGCAT_CLEAR = ("logcat", "-c")
LOGCAT_DUMP = ("logcat", "-d", "-v", "threadtime")
# A stand-in for a line the CASE itself logs (`Log.i(TAG, "P807-Q2FLOW2 …")`): a driver that
# exports the log before the run cannot get this text into `logcat.txt`.
LOGCAT_RUN_LINE = (
    "01-01 00:00:00.000 I/UnifiedLedger( 1234): P807-Q2FLOW2 PASS evidence=p807-seed-import-evidence "
    "activeLinks=1 transactions=2 postings=4\n"
)


class Parsing(unittest.TestCase):
    def test_a_passing_transcript_reads_one_started_and_one_passed_case(self) -> None:
        parsed = parse_instrumentation(PASSING)
        self.assertEqual([CASE], parsed["started"])
        self.assertEqual([CASE], parsed["passed"])
        self.assertEqual([], parsed["failed"])
        self.assertEqual(-1, parsed["code"])
        self.assertEqual(1, parsed["reported"])
        self.assertEqual([], parsed["markers"])

    def test_a_failing_transcript_carries_its_marker_and_failed_case(self) -> None:
        parsed = parse_instrumentation(FAILING)
        self.assertEqual([(CASE, -2)], parsed["failed"])
        self.assertIn("FAILURES!!!", parsed["markers"])
        self.assertEqual(1, parsed["reported"])

    def test_a_short_message_transcript_carries_the_marker(self) -> None:
        parsed = parse_instrumentation(SHORT_MSG)
        self.assertIn("INSTRUMENTATION_FAILED", parsed["markers"])
        self.assertIn("shortMsg=", parsed["markers"])

    def test_a_process_crash_transcript_is_a_failure_not_a_pass(self) -> None:
        parsed = parse_instrumentation(CRASHED)
        self.assertIn("Process crashed", parsed["markers"])
        self.assertNotIn(PASS_MARKER, CRASHED)


class Judging(unittest.TestCase):
    def test_the_clean_single_case_transcript_passes(self) -> None:
        self.assertEqual([], judge(PASSING, CASE))

    def test_a_failed_case_is_named(self) -> None:
        joined = " ".join(judge(FAILING, CASE))
        self.assertIn("did not pass", joined)
        self.assertIn("FAILURES!!!", joined)

    def test_a_missing_pass_marker_is_red(self) -> None:
        text = PASSING.replace("OK (1 test)\n", "")
        self.assertTrue(any("does not report" in reason for reason in judge(text, CASE)))

    def test_an_extra_executed_case_is_red(self) -> None:
        extra = "com.unifiedledger.android.SomeOtherTest#anotherCase"
        injected = (
            f"INSTRUMENTATION_STATUS: class={extra.partition('#')[0]}\n"
            f"INSTRUMENTATION_STATUS: test={extra.partition('#')[2]}\n"
            "INSTRUMENTATION_STATUS_CODE: 1\n"
            f"INSTRUMENTATION_STATUS: class={extra.partition('#')[0]}\n"
            f"INSTRUMENTATION_STATUS: test={extra.partition('#')[2]}\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n"
        )
        text = PASSING.replace("INSTRUMENTATION_CODE: -1", injected + "INSTRUMENTATION_CODE: -1")
        joined = " ".join(judge(text, CASE))
        self.assertIn("started case set", joined)

    def test_a_missing_execution_is_red(self) -> None:
        # Started but never passed: the runner reported OK for it anyway.
        text = PASSING.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: 1")
        joined = " ".join(judge(text, CASE))
        self.assertIn("executed case set differs", joined)

    def test_zero_cases_is_red(self) -> None:
        text = "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\nINSTRUMENTATION_CODE: -1\nOK (1 test)\n"
        joined = " ".join(judge(text, CASE))
        self.assertIn("started case set", joined)

    def test_a_duplicated_case_record_is_red(self) -> None:
        text = PASSING.replace(
            f"class={CLASS}\nINSTRUMENTATION_STATUS: test={METHOD}\nINSTRUMENTATION_STATUS: numtests=1\n"
            "INSTRUMENTATION_STATUS: stream=.\nINSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n"
            "INSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS_CODE: 0",
            f"class={CLASS}\nINSTRUMENTATION_STATUS: test={METHOD}\nINSTRUMENTATION_STATUS: numtests=1\n"
            "INSTRUMENTATION_STATUS: stream=.\nINSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n"
            "INSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS_CODE: 0\n"
            f"INSTRUMENTATION_STATUS: class={CLASS}\nINSTRUMENTATION_STATUS: test={METHOD}\n"
            "INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: stream=.\n"
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\nINSTRUMENTATION_STATUS: current=1\n"
            "INSTRUMENTATION_STATUS_CODE: 0",
        )
        joined = " ".join(judge(text, CASE))
        self.assertIn("repeats a", joined)

    def test_an_empty_transcript_is_red(self) -> None:
        self.assertTrue(judge("", CASE))


class FlowTable(unittest.TestCase):
    def test_flow_two_maps_to_the_one_case_and_nothing_else(self) -> None:
        self.assertEqual([2], list(FLOWS))
        self.assertIn("#", CASE)
        self.assertTrue(CASE.startswith("com.unifiedledger.android.P807Q2Flow2ReconciliationInstrumentedTest#"))

    def test_an_unknown_flow_is_refused(self) -> None:
        for value in (0, 1, 3, 99):
            with self.assertRaises(Q2Error):
                flow_case(value)

    def test_the_full_lowercase_sha_is_required(self) -> None:
        with self.assertRaises(Q2Error):
            require_sha(SHA.upper())
        with self.assertRaises(Q2Error):
            require_sha(SHA[:12])
        self.assertEqual(SHA, require_sha(SHA))


class Ownership(unittest.TestCase):
    def test_exactly_one_emulator(self) -> None:
        self.assertEqual("emulator-5556", owned_serial("List of devices attached\nemulator-5556\tdevice\n"))

    def test_two_emulators_are_refused(self) -> None:
        with self.assertRaises(Q2Error):
            owned_serial("emulator-5554\tdevice\nemulator-5556\tdevice\n")

    def test_no_emulator_is_refused(self) -> None:
        with self.assertRaises(Q2Error):
            owned_serial("List of devices attached\n")

    def test_the_console_ok_line_is_stripped(self) -> None:
        self.assertEqual(DEFAULT_AVD, parse_avd_name(f"{DEFAULT_AVD}\nOK\n"))

    def test_an_unexpected_transcript_yields_no_name(self) -> None:
        self.assertEqual("", parse_avd_name("OK\nOK\n"))

    def test_a_local_environment_is_refused(self) -> None:
        with self.assertRaises(Q2Error):
            require_hosted_runner({"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "self-hosted", "RUNNER_OS": "Linux"})
        with self.assertRaises(Q2Error):
            require_hosted_runner({"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted", "RUNNER_OS": "Windows"})

    def test_a_hosted_runner_is_accepted(self) -> None:
        require_hosted_runner({"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted", "RUNNER_OS": "Linux"})


class FakeDevice:
    """A device that answers from a script instead of an emulator."""

    def __init__(
        self,
        avd: str = DEFAULT_AVD,
        transcript: str = PASSING,
        exit_code: int = 0,
        logcat_dump: str = LOGCAT_RUN_LINE,
        instrument_error: Exception | None = None,
        logcat_clear_error: Exception | None = None,
        logcat_error: Exception | None = None,
    ):
        self.serial = "emulator-9999"
        self.avd = avd
        self.transcript = transcript
        self.exit_code = exit_code
        self.logcat_dump = logcat_dump
        self.instrument_error = instrument_error
        self.logcat_clear_error = logcat_clear_error
        self.logcat_error = logcat_error
        self.calls: list[tuple[str, ...]] = []

    def text(self, *args: str, timeout: float = 120.0) -> str:
        self.calls.append(args)
        if args == ("emu", "avd", "name"):
            return f"{self.avd}\nOK\n"
        if args[:1] == ("install",):
            return "Performing Streamed Install\nSuccess\n"
        if args[:3] == ("shell", "pm", "list"):
            return (
                "instrumentation:com.unifiedledger.android.test/androidx.test.runner.AndroidJUnitRunner "
                "(target=com.unifiedledger.android)\n"
            )
        if args[:3] == ("shell", "am", "force-stop"):
            return ""
        if args == ("shell", "getprop", "ro.build.version.sdk"):
            return "36\n"
        if args == ("shell", "getprop", "persist.sys.locale"):
            return "zh-CN\n"
        if args[:2] == ("shell", "wm") and args[2:] == ("size",):
            return "Physical size: 1080x2400\n"
        if args[:2] == ("shell", "wm") and args[2:] == ("density",):
            return "Physical density: 420\n"
        if args[:3] == ("shell", "settings", "get"):
            return "1.0\n"
        if args == LOGCAT_CLEAR:
            if self.logcat_clear_error is not None:
                raise self.logcat_clear_error
            return ""
        if args == LOGCAT_DUMP:
            if self.logcat_error is not None:
                raise self.logcat_error
            return self.logcat_dump
        return ""

    def instrument(self, case: str, timeout: float) -> tuple[int, str, str]:
        self.calls.append(("instrument", case))
        if self.instrument_error is not None:
            raise self.instrument_error
        return self.exit_code, self.transcript, ""


class Session(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.app = self.root / "app.apk"
        self.test = self.root / "test.apk"
        self.app.write_bytes(b"app")
        self.test.write_bytes(b"test")
        self.evidence = self.root / "evidence"

    def test_the_session_writes_the_contract_files_and_runs_the_one_case(self) -> None:
        device = FakeDevice()
        payload = run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertEqual("PASS", payload["status"])
        self.assertEqual(SHA, payload["sha"])
        self.assertEqual(2, payload["flow"])
        self.assertEqual(CASE, payload["case"])
        self.assertEqual(DEFAULT_AVD, payload["avd"])
        self.assertEqual("emulator-9999", payload["serial"])
        self.assertEqual("36", payload["api_level"])
        self.assertEqual(0, payload["instrumentation_exit"])
        for name in (HOST_FILE, TRANSCRIPT_FILE, CONFIG_FILE, "logcat.txt"):
            self.assertTrue((self.evidence / name).is_file(), name)
        self.assertEqual(PASSING, (self.evidence / TRANSCRIPT_FILE).read_text())
        configuration = json.loads((self.evidence / CONFIG_FILE).read_text())
        for key in ("api_level", "avd", "font_scale", "locale", "serial", "wm_density", "wm_size"):
            self.assertIn(key, configuration)
        self.assertEqual("1080x2400", configuration["wm_size"])
        self.assertEqual("420", configuration["wm_density"])
        instrumented = [call for call in device.calls if call[0] == "instrument"]
        self.assertEqual([("instrument", CASE)], instrumented)
        # The force-stop precedes the first install and the case starts from a stopped app.
        kinds = list(device.calls)
        force_stop = kinds.index(("shell", "am", "force-stop", "com.unifiedledger.android"))
        first_install = next(index for index, call in enumerate(kinds) if call[:1] == ("install",))
        self.assertLess(force_stop, first_install)

    def test_a_foreign_avd_is_refused_before_installing(self) -> None:
        device = FakeDevice(avd="ul-scale")
        with self.assertRaises(Q2Error):
            run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertFalse(any(call[:1] == ("install",) for call in device.calls))

    def test_a_failing_transcript_stops_the_session_with_reasons(self) -> None:
        device = FakeDevice(transcript=FAILING)
        with self.assertRaises(Q2Error) as caught:
            run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertIn("FAILURES!!!", str(caught.exception))
        self.assertTrue((self.evidence / TRANSCRIPT_FILE).is_file())
        host = json.loads((self.evidence / HOST_FILE).read_text())
        self.assertEqual("FAIL", host["status"])
        self.assertTrue(host["reasons"])
        # W-01: a failing case still exports the run's tail, so the artifact carries the failure
        # context instead of install noise.
        self.assertEqual(LOGCAT_RUN_LINE, (self.evidence / "logcat.txt").read_text())

    def test_a_nonzero_exit_code_is_red_even_with_a_passing_transcript(self) -> None:
        device = FakeDevice(exit_code=137)
        with self.assertRaises(Q2Error) as caught:
            run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertIn("exit code is 137", str(caught.exception))

    def test_the_logcat_is_cleared_after_the_install_and_exported_after_the_case(self) -> None:
        # W-01: the clear precedes the case and the dump follows it, so `logcat.txt` carries THIS
        # run's lines. Moving either call across `am instrument` makes this test red.
        device = FakeDevice()
        payload = run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        calls = list(device.calls)
        first_install = next(index for index, call in enumerate(calls) if call[:1] == ("install",))
        clear = calls.index(LOGCAT_CLEAR)
        case = calls.index(("instrument", CASE))
        dump = calls.index(LOGCAT_DUMP)
        self.assertLess(first_install, clear, "the clear must follow the installs")
        self.assertLess(clear, case, "the clear must precede the case")
        self.assertLess(case, dump, "the logcat export must follow the case")
        self.assertEqual(LOGCAT_RUN_LINE, (self.evidence / "logcat.txt").read_text())
        self.assertEqual("PASS", payload["status"])

    def test_the_logcat_is_exported_even_when_the_instrumentation_raises(self) -> None:
        device = FakeDevice(instrument_error=Q2Error("the instrumentation exceeded its time budget"))
        with self.assertRaises(Q2Error) as caught:
            run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertIn("time budget", str(caught.exception))
        calls = list(device.calls)
        self.assertLess(calls.index(("instrument", CASE)), calls.index(LOGCAT_DUMP))
        self.assertEqual(LOGCAT_RUN_LINE, (self.evidence / "logcat.txt").read_text())

    def test_a_logcat_export_failure_fails_the_session_closed(self) -> None:
        device = FakeDevice(logcat_error=Q2Error("logcat refused"))
        with self.assertRaises(Q2Error) as caught:
            run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertIn("logcat export failed", str(caught.exception))
        host = json.loads((self.evidence / HOST_FILE).read_text())
        self.assertEqual("FAIL", host["status"])
        self.assertIn("logcat_error", host)

    def test_a_logcat_export_failure_does_not_mask_the_instrumentation_failure(self) -> None:
        device = FakeDevice(
            instrument_error=Q2Error("first cause: the instrumentation timed out"),
            logcat_error=Q2Error("logcat refused"),
        )
        with self.assertRaises(Q2Error) as caught:
            run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertIn("first cause", str(caught.exception))
        self.assertNotIn("logcat refused", str(caught.exception))

    def test_a_refused_logcat_clear_does_not_cost_the_run_and_is_recorded(self) -> None:
        device = FakeDevice(logcat_clear_error=Q2Error("clear refused"))
        payload = run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertEqual("PASS", payload["status"])
        self.assertEqual("Q2Error: clear refused", payload["logcat_clear_error"])
        self.assertEqual(LOGCAT_RUN_LINE, (self.evidence / "logcat.txt").read_text())

    def test_an_unknown_flow_is_refused_before_any_adb_call(self) -> None:
        device = FakeDevice()
        with self.assertRaises(Q2Error):
            run_session(self.app, self.test, self.evidence, SHA, flow=7, device_factory=lambda _budget: device)
        self.assertEqual([], device.calls)


class Gating(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.evidence = Path(self.temp.name)

    def write(
        self,
        *,
        transcript: str = PASSING,
        sha: str = SHA,
        flow: int = 2,
        case: str = CASE,
        status: str = "PASS",
        api: str = EXPECTED_API,
        device_api: str = EXPECTED_API,
        exit_code: int = 0,
        host: bool = True,
    ) -> None:
        payload = {
            "schema": 1,
            "sha": sha,
            "flow": flow,
            "case": case,
            "avd": DEFAULT_AVD,
            "serial": "emulator-9999",
            "api_level": api,
            "started_at": "2026-11-25T00:00:00+00:00",
            "finished_at": "2026-11-25T00:01:00+00:00",
            "instrumentation_exit": exit_code,
            "apk_sha256": {"app": "a" * 64, "test": "b" * 64},
            "files": {"configuration": CONFIG_FILE, "instrumentation": TRANSCRIPT_FILE, "logcat": "logcat.txt"},
            "status": status,
        }
        if host:
            (self.evidence / HOST_FILE).write_text(json.dumps(payload))
        (self.evidence / TRANSCRIPT_FILE).write_text(transcript)
        (self.evidence / CONFIG_FILE).write_text(
            json.dumps(
                {
                    "api_level": device_api,
                    "avd": DEFAULT_AVD,
                    "font_scale": "1.0",
                    "locale": "zh-CN",
                    "serial": "emulator-9999",
                    "wm_density": "420",
                    "wm_size": "1080x2400",
                }
            )
        )

    def test_a_clean_session_passes_and_writes_the_gate(self) -> None:
        self.write()
        verdict = gate_evidence(self.evidence, SHA, 2)
        self.assertEqual("PASS", verdict["verdict"])
        self.assertEqual([], verdict["reasons"])
        self.assertTrue((self.evidence / "gate.json").is_file())

    def test_evidence_for_another_commit_is_refused(self) -> None:
        self.write(sha="f" * 40)
        with self.assertRaises(Q2Error):
            gate_evidence(self.evidence, SHA, 2)

    def test_evidence_for_another_flow_is_refused(self) -> None:
        self.write(flow=3)
        with self.assertRaises(Q2Error):
            gate_evidence(self.evidence, SHA, 2)

    def test_evidence_for_another_case_is_refused(self) -> None:
        self.write(case="com.unifiedledger.android.OtherInstrumentedTest#otherCase")
        with self.assertRaises(Q2Error):
            gate_evidence(self.evidence, SHA, 2)

    def test_a_missing_host_json_is_refused(self) -> None:
        self.write(host=False)
        with self.assertRaises(Q2Error):
            gate_evidence(self.evidence, SHA, 2)

    def test_a_missing_transcript_is_refused(self) -> None:
        self.write()
        (self.evidence / TRANSCRIPT_FILE).unlink()
        with self.assertRaises(Q2Error):
            gate_evidence(self.evidence, SHA, 2)

    def test_a_self_reported_failure_is_never_re_judged_green(self) -> None:
        # One-way trust: the session's own FAIL stands even though the transcript on disk passes.
        self.write(status="FAIL")
        with self.assertRaises(Q2Error) as caught:
            gate_evidence(self.evidence, SHA, 2)
        self.assertIn("self-reported failure", str(caught.exception))

    def test_a_failed_transcript_fails_the_gate(self) -> None:
        self.write(transcript=FAILING)
        verdict = gate_evidence(self.evidence, SHA, 2)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertTrue(verdict["reasons"])

    def test_a_missing_pass_marker_fails_the_gate(self) -> None:
        self.write(transcript=PASSING.replace("OK (1 test)\n", ""))
        verdict = gate_evidence(self.evidence, SHA, 2)
        self.assertEqual("FAIL", verdict["verdict"])

    def test_an_extra_case_fails_the_gate(self) -> None:
        injected = (
            "INSTRUMENTATION_STATUS: class=com.unifiedledger.android.SomeOtherTest\n"
            "INSTRUMENTATION_STATUS: test=anotherCase\n"
            "INSTRUMENTATION_STATUS_CODE: 1\n"
            "INSTRUMENTATION_STATUS: class=com.unifiedledger.android.SomeOtherTest\n"
            "INSTRUMENTATION_STATUS: test=anotherCase\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n"
        )
        self.write(transcript=PASSING.replace("INSTRUMENTATION_CODE: -1", injected + "INSTRUMENTATION_CODE: -1"))
        verdict = gate_evidence(self.evidence, SHA, 2)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("started case set", " ".join(verdict["reasons"]))

    def test_a_missing_execution_fails_the_gate(self) -> None:
        self.write(transcript=PASSING.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: 1"))
        verdict = gate_evidence(self.evidence, SHA, 2)
        self.assertEqual("FAIL", verdict["verdict"])

    def test_zero_cases_fails_the_gate(self) -> None:
        self.write(
            transcript="INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\nINSTRUMENTATION_CODE: -1\nOK (1 test)\n",
        )
        verdict = gate_evidence(self.evidence, SHA, 2)
        self.assertEqual("FAIL", verdict["verdict"])

    def test_a_recorded_api_level_other_than_36_fails_the_gate(self) -> None:
        self.write(api="35")
        verdict = gate_evidence(self.evidence, SHA, 2)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("not 36", " ".join(verdict["reasons"]))

    def test_a_device_api_level_other_than_36_fails_the_gate(self) -> None:
        self.write(device_api="34")
        verdict = gate_evidence(self.evidence, SHA, 2)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("not 36", " ".join(verdict["reasons"]))

    def test_a_recorded_nonzero_exit_code_fails_the_gate(self) -> None:
        self.write(exit_code=1)
        verdict = gate_evidence(self.evidence, SHA, 2)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("exit code", " ".join(verdict["reasons"]))

    def test_an_unknown_flow_is_refused_by_the_gate(self) -> None:
        self.write()
        with self.assertRaises(Q2Error):
            gate_evidence(self.evidence, SHA, 9)


class Workflow(unittest.TestCase):
    """The workflow and this module must agree on the AVD, the profile and the gate."""

    def workflow_text(self) -> str:
        root = Path(__file__).resolve().parents[2]
        return (root / ".github" / "workflows" / "android-p807-q2.yml").read_text()

    def test_the_workflow_drives_this_module_with_its_own_avd(self) -> None:
        text = self.workflow_text()
        self.assertIn("tools/ci/android-p807-q2.py run", text)
        self.assertIn("tools/ci/android-p807-q2.py gate", text)
        self.assertIn(f"--avd-name {DEFAULT_AVD}", text)
        self.assertIn(f"avd-name: {DEFAULT_AVD}", text)
        self.assertIn("--flow", text)
        self.assertIn("--sha", text)

    def test_the_workflow_pins_the_device_profile(self) -> None:
        text = self.workflow_text()
        self.assertIn("profile: pixel_6", text)
        self.assertIn(f"api-level: {EXPECTED_API}", text)
        self.assertIn("disable-animations: false", text)
        self.assertIn("target: google_apis", text)
        self.assertIn("arch: x86_64", text)

    def test_the_workflow_gates_with_always(self) -> None:
        text = self.workflow_text()
        gate_index = text.index("Re-derive the gate from the evidence on disk")
        window = text[gate_index : gate_index + 400]
        self.assertIn("if: always()", window)
        upload_index = text.index("Upload the device evidence")
        self.assertIn("if: always()", text[upload_index : upload_index + 300])
        self.assertIn("android-p807-q2-${{ inputs.expected_sha }}-${{ github.run_id }}-${{ github.run_attempt }}", text)

    def test_the_workflow_never_runs_on_a_pull_request(self) -> None:
        text = self.workflow_text()
        self.assertNotIn("pull_request:", text)
        self.assertIn("workflow_dispatch:", text)

    def test_the_ordinary_device_regression_does_not_list_this_case(self) -> None:
        # The flow must never enter the ordinary two-shard regression: it seeds the app's storage
        # before any launch and needs a process of its own.
        root = Path(__file__).resolve().parents[2]
        inventory = (root / "tools" / "ci" / "android-instrumented-inventory.txt").read_text()
        shards = (root / "tools" / "ci" / "android-instrumented-shards.txt").read_text()
        self.assertNotIn("P807Q2Flow2ReconciliationInstrumentedTest", inventory)
        self.assertNotIn("P807Q2Flow2ReconciliationInstrumentedTest", shards)

    def test_the_class_is_excluded_from_the_device_inventory_with_its_reason(self) -> None:
        from android_scale.inventory import EXCLUDED

        self.assertIn("P807Q2Flow2ReconciliationInstrumentedTest", EXCLUDED)

    def test_the_python_module_is_registered_in_shard_two(self) -> None:
        root = Path(__file__).resolve().parents[2]
        lines = (root / "tools" / "ci" / "python-shards.txt").read_text().splitlines()
        self.assertIn("2\ttests.python.test_android_p807_q2", lines)


if __name__ == "__main__":
    unittest.main()
