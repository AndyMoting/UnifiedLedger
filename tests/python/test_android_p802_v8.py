"""Offline proofs for the P8-02 V-8 render rig (cloud AVD chain). No device is touched."""
from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from android_p802_v8 import (
    CASE,
    CONFIG_FILE,
    DEFAULT_AVD,
    DEFAULT_TIMEOUT,
    EXPECTED_API,
    EXPECTED_CONDITION,
    EXPECTED_FIXTURE_TRANSACTIONS,
    EXPECTED_PAGE_ROWS,
    EXPECTED_TOTAL_COUNT,
    GATE_FILE,
    HOST_FILE,
    LOGCAT_FILE,
    READING_PREFIX,
    REQUIRED_READING_KEYS,
    SETTLE_SAMPLES,
    TRANSCRIPT_FILE,
    V8Error,
    gate_evidence,
    judge,
    judge_reading,
    owned_serial,
    parse_avd_name,
    parse_instrumentation,
    parse_reading,
    require_hosted_runner,
    require_sha,
    run_session,
)

SHA = "0123456789abcdef0123456789abcdef01234567"
APP_APK_SHA = "a" * 64
TEST_APK_SHA = "b" * 64
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
INSTRUMENTATION_STATUS: stack=java.lang.IllegalStateException: the result band never settled
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS_CODE: -2
There was 1 failure:
1) {METHOD}
java.lang.IllegalStateException: the result band never settled
FAILURES!!!
Tests run: 1,  Failures: 1
INSTRUMENTATION_CODE: -1
"""

# The canonical reading line. Its field set IS [REQUIRED_READING_KEYS]; the case's own source is
# checked against that set below, so a case that stops emitting one field turns this file red.
READING_FIELDS = {
    "fixture": str(EXPECTED_FIXTURE_TRANSACTIONS),
    "condition": EXPECTED_CONDITION,
    "windowStart": "2026-11-26T02:00:00Z",
    "windowEnd": "2026-11-26T02:00:01Z",
    "sampleIntervalMs": "50",
    "samples": "37",
    "firstSampleAfterSubmitMs": "213.5",
    "settleSamples": str(SETTLE_SAMPLES),
    "renderFirstPageMs": "812.4",
    "pageRows": str(EXPECTED_PAGE_ROWS),
    "totalCount": str(EXPECTED_TOTAL_COUNT),
    "schema": "38",
    "apk": APP_APK_SHA,
}
READING_MESSAGE = READING_PREFIX + " " + " ".join(f"{key}={value}" for key, value in READING_FIELDS.items())
# The reading line arrives inside a logcat frame, as the case's one `Log.i` line.
LOGCAT_READING = (
    "11-26 10:05:58.000 I/UnifiedLedger( 1234): " + READING_MESSAGE + "\n"
)
# A diagnostic line of the case. Its prefix must NOT contain the reading prefix, or the gate's
# "exactly one reading line" bound would count it.
LOGCAT_DIAGNOSTIC = "11-26 10:05:57.000 I/UnifiedLedger( 1234): P802V8PREP seed transactions=61000\n"
LOGCAT_CLEAR = ("logcat", "-c")
LOGCAT_DUMP = ("logcat", "-d", "-v", "threadtime")


def reading(**overrides: str) -> str:
    """The logcat text of one reading line with the given fields replaced."""
    fields = dict(READING_FIELDS)
    fields.update(overrides)
    return "11-26 10:05:58.000 I/UnifiedLedger( 1234): " + READING_PREFIX + " " + " ".join(
        f"{key}={value}" for key, value in fields.items()
    ) + "\n"


def without(key: str) -> str:
    fields = {k: v for k, v in READING_FIELDS.items() if k != key}
    return "11-26 10:05:58.000 I/UnifiedLedger( 1234): " + READING_PREFIX + " " + " ".join(
        f"{key}={value}" for key, value in fields.items()
    ) + "\n"


class ReadingLine(unittest.TestCase):
    def test_the_canonical_line_parses_to_the_frozen_field_set(self) -> None:
        parsed = parse_reading(LOGCAT_READING)
        for key, value in READING_FIELDS.items():
            self.assertEqual(value, parsed[key], key)
        for key in REQUIRED_READING_KEYS:
            self.assertIn(key, parsed)
        self.assertEqual([], judge_reading(parsed, APP_APK_SHA))

    def test_the_window_bounds_are_carried_but_not_required(self) -> None:
        parsed = parse_reading(reading())
        self.assertIn("windowStart", parsed)
        self.assertNotIn("windowStart", REQUIRED_READING_KEYS)

    def test_no_reading_line_is_refused(self) -> None:
        with self.assertRaises(V8Error) as caught:
            parse_reading(LOGCAT_DIAGNOSTIC)
        self.assertIn("no P802-V8R reading line", str(caught.exception))
        # An empty field set is a list of named misses, never a silent default.
        self.assertTrue(judge_reading({}, APP_APK_SHA))

    def test_two_reading_lines_are_refused(self) -> None:
        # The reading must be unique: a second line would let a red run pick whichever it liked.
        with self.assertRaises(V8Error) as caught:
            parse_reading(LOGCAT_READING + reading(renderFirstPageMs="1.0"))
        self.assertIn("exactly one is required", str(caught.exception))

    def test_a_diagnostic_prefix_is_not_a_reading_line(self) -> None:
        self.assertEqual([], [line for line in LOGCAT_DIAGNOSTIC.splitlines() if READING_PREFIX in line])

    def test_a_token_without_a_key_is_refused(self) -> None:
        text = "11-26 10:05:58.000 I/UnifiedLedger(1234): " + READING_PREFIX + " fixture=61000 loose\n"
        with self.assertRaises(V8Error):
            parse_reading(text)

    def test_every_missing_field_is_named(self) -> None:
        for key in REQUIRED_READING_KEYS:
            parsed = parse_reading(without(key))
            reasons = judge_reading(parsed, APP_APK_SHA)
            self.assertTrue(reasons, key)
            self.assertIn(f"missing {key}=", " ".join(reasons))


class ReadingBounds(unittest.TestCase):
    """One mutation per bound, so a case that stops emitting or fudges a field is red for it."""

    def reasons(self, text: str, app_sha: str = APP_APK_SHA) -> list[str]:
        return judge_reading(parse_reading(text), app_sha)

    def test_the_frozen_fixture_size_is_bound(self) -> None:
        self.assertIn("not 61000", " ".join(self.reasons(reading(fixture="61013"))))
        self.assertIn("not 61000", " ".join(self.reasons(reading(fixture="20000"))))

    def test_the_frozen_page_size_is_bound(self) -> None:
        self.assertIn("not the frozen 50", " ".join(self.reasons(reading(pageRows="49"))))

    def test_the_fixed_group_manifest_is_bound(self) -> None:
        # 436 is the fixed group's construction manifest; 8715 (the note arm alone) and 872 (the
        # annotated set) are what an un-filtered or half-filtered condition would answer.
        self.assertIn("not the manifest's 436", " ".join(self.reasons(reading(totalCount="8715"))))
        self.assertIn("not the manifest's 436", " ".join(self.reasons(reading(totalCount="872"))))

    def test_the_condition_label_is_bound(self) -> None:
        self.assertIn(EXPECTED_CONDITION, " ".join(self.reasons(reading(condition="note-only"))))

    def test_a_reading_shorter_than_the_settle_window_is_red(self) -> None:
        self.assertIn("fewer than the settle window", " ".join(self.reasons(reading(samples="2"))))

    def test_the_settle_window_itself_is_frozen(self) -> None:
        self.assertIn("not the frozen 3", " ".join(self.reasons(reading(settleSamples="2"))))

    def test_a_non_positive_or_unreadable_render_reading_is_red(self) -> None:
        for value in ("0", "0.0", "-1", "-0.5", "nan", "inf", "-inf", "unmeasurable", ""):
            reasons = " ".join(self.reasons(reading(renderFirstPageMs=value)))
            self.assertTrue(reasons, value)
            self.assertIn("renderFirstPageMs", reasons, value)

    def test_a_finite_positive_reading_passes(self) -> None:
        self.assertEqual([], self.reasons(reading(renderFirstPageMs="0.1")))
        self.assertEqual([], self.reasons(reading(renderFirstPageMs="9999.9")))

    def test_the_sampling_interval_must_be_a_positive_integer(self) -> None:
        for value in ("0", "-50", "fifty", "50.5"):
            self.assertIn("sampleIntervalMs", " ".join(self.reasons(reading(sampleIntervalMs=value))), value)

    def test_the_submit_to_first_sample_overhead_is_a_finite_non_negative_number(self) -> None:
        # The reading's resolution is the interval PLUS this overhead, so an unusable value is red.
        for value in ("-1", "-0.4", "nan", "inf", "-inf", "uneven", ""):
            reasons = " ".join(self.reasons(reading(firstSampleAfterSubmitMs=value)))
            self.assertTrue(reasons, value)
            self.assertIn("firstSampleAfterSubmitMs", reasons, value)
        self.assertEqual([], self.reasons(reading(firstSampleAfterSubmitMs="0")))
        self.assertEqual([], self.reasons(reading(firstSampleAfterSubmitMs="912.5")))

    def test_the_reading_is_bound_to_the_installed_app_apk(self) -> None:
        self.assertIn("not the installed app APK", " ".join(self.reasons(reading(apk="c" * 64))))
        self.assertIn("not a lowercase sha256", " ".join(self.reasons(reading(apk="A" * 64))))
        self.assertIn("not a lowercase sha256", " ".join(self.reasons(reading(apk="a" * 16))))

    def test_a_non_positive_schema_or_count_is_red(self) -> None:
        for key in ("schema", "fixture", "pageRows", "totalCount", "samples"):
            self.assertTrue(self.reasons(reading(**{key: "0"})), key)
            self.assertTrue(self.reasons(reading(**{key: "abc"})), key)


class Transcript(unittest.TestCase):
    def test_a_passing_transcript_reads_one_started_and_one_passed_case(self) -> None:
        parsed = parse_instrumentation(PASSING)
        self.assertEqual([CASE], parsed["started"])
        self.assertEqual([CASE], parsed["passed"])
        self.assertEqual([], parsed["failed"])
        self.assertEqual(-1, parsed["code"])
        self.assertEqual(1, parsed["reported"])
        self.assertEqual([], judge(PASSING, CASE))

    def test_a_failed_case_is_red_for_its_marker(self) -> None:
        joined = " ".join(judge(FAILING, CASE))
        self.assertIn("did not pass", joined)
        self.assertIn("FAILURES!!!", joined)

    def test_a_missing_pass_marker_is_red(self) -> None:
        self.assertTrue(any("does not report" in reason for reason in judge(PASSING.replace("OK (1 test)\n", ""), CASE)))

    def test_an_extra_case_is_red(self) -> None:
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
        self.assertIn("started case set", " ".join(judge(text, CASE)))

    def test_an_empty_transcript_is_red(self) -> None:
        self.assertTrue(judge("", CASE))


class Ownership(unittest.TestCase):
    def test_exactly_one_emulator(self) -> None:
        self.assertEqual("emulator-5556", owned_serial("List of devices attached\nemulator-5556\tdevice\n"))

    def test_two_or_no_emulators_are_refused(self) -> None:
        with self.assertRaises(V8Error):
            owned_serial("emulator-5554\tdevice\nemulator-5556\tdevice\n")
        with self.assertRaises(V8Error):
            owned_serial("List of devices attached\n")

    def test_the_console_ok_line_is_stripped(self) -> None:
        self.assertEqual(DEFAULT_AVD, parse_avd_name(f"{DEFAULT_AVD}\nOK\n"))
        self.assertEqual("", parse_avd_name("OK\nOK\n"))

    def test_the_full_lowercase_sha_is_required(self) -> None:
        with self.assertRaises(V8Error):
            require_sha(SHA.upper())
        with self.assertRaises(V8Error):
            require_sha(SHA[:12])
        self.assertEqual(SHA, require_sha(SHA))

    def test_a_local_environment_is_refused(self) -> None:
        for triple in (
            {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "self-hosted", "RUNNER_OS": "Linux"},
            {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted", "RUNNER_OS": "Windows"},
            {},
        ):
            with self.assertRaises(V8Error):
                require_hosted_runner(triple)
        require_hosted_runner({"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted", "RUNNER_OS": "Linux"})


class FakeDevice:
    """A device that answers from a script instead of an emulator."""

    def __init__(
        self,
        avd: str = DEFAULT_AVD,
        transcript: str = PASSING,
        exit_code: int = 0,
        app_sha: str = APP_APK_SHA,
        logcat_dump: str | None = None,
        instrument_error: Exception | None = None,
        logcat_clear_error: Exception | None = None,
        logcat_error: Exception | None = None,
    ):
        self.serial = "emulator-9999"
        self.avd = avd
        self.transcript = transcript
        self.exit_code = exit_code
        # The reading must name the app APK this session actually installs: the rig compares the two.
        self.logcat_dump = reading(apk=app_sha) if logcat_dump is None else logcat_dump
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
            return f"{EXPECTED_API}\n"
        if args == ("shell", "getprop", "persist.sys.locale"):
            return "zh-CN\n"
        if args[2:] == ("size",):
            return "Physical size: 1080x2400\n"
        if args[2:] == ("density",):
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
        self.app_sha = hashlib.sha256(self.app.read_bytes()).hexdigest()
        self.evidence = self.root / "evidence"

    def test_the_session_writes_the_contract_files_and_runs_the_one_case(self) -> None:
        device = FakeDevice(app_sha=self.app_sha)
        payload = run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertEqual("PASS", payload["status"])
        self.assertEqual(CASE, payload["case"])
        self.assertEqual(DEFAULT_AVD, payload["avd"])
        self.assertEqual(EXPECTED_API, payload["api_level"])
        self.assertEqual(self.app_sha, payload["apk_sha256"]["app"])
        for name in (HOST_FILE, TRANSCRIPT_FILE, CONFIG_FILE, LOGCAT_FILE):
            self.assertTrue((self.evidence / name).is_file(), name)
        self.assertEqual([("instrument", CASE)], [call for call in device.calls if call[0] == "instrument"])
        self.assertEqual(reading(apk=self.app_sha), (self.evidence / LOGCAT_FILE).read_text())

    def test_the_app_is_stopped_before_the_installs(self) -> None:
        device = FakeDevice(app_sha=self.app_sha)
        run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        calls = list(device.calls)
        force_stop = calls.index(("shell", "am", "force-stop", "com.unifiedledger.android"))
        first_install = next(index for index, call in enumerate(calls) if call[:1] == ("install",))
        self.assertLess(force_stop, first_install)

    def test_the_logcat_is_cleared_after_the_install_and_exported_after_the_case(self) -> None:
        device = FakeDevice(app_sha=self.app_sha)
        run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        calls = list(device.calls)
        first_install = next(index for index, call in enumerate(calls) if call[:1] == ("install",))
        self.assertLess(first_install, calls.index(LOGCAT_CLEAR))
        self.assertLess(calls.index(LOGCAT_CLEAR), calls.index(("instrument", CASE)))
        self.assertLess(calls.index(("instrument", CASE)), calls.index(LOGCAT_DUMP))

    def test_a_foreign_avd_is_refused_before_installing(self) -> None:
        device = FakeDevice(avd="ul-scale", app_sha=self.app_sha)
        with self.assertRaises(V8Error):
            run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertFalse(any(call[:1] == ("install",) for call in device.calls))

    def test_a_run_without_a_reading_line_is_red_and_writes_no_pass_status(self) -> None:
        # The whole point of the rig: a green case that recorded nothing must not pass.
        device = FakeDevice(app_sha=self.app_sha, logcat_dump=LOGCAT_DIAGNOSTIC)
        with self.assertRaises(V8Error) as caught:
            run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertIn("P802-V8R", str(caught.exception))
        host = json.loads((self.evidence / HOST_FILE).read_text())
        self.assertEqual("FAIL", host["status"])
        self.assertTrue(host["reasons"])

    def test_a_run_whose_render_reading_is_not_a_bound_is_red(self) -> None:
        device = FakeDevice(
            app_sha=self.app_sha,
            logcat_dump=reading(apk=self.app_sha, renderFirstPageMs="nan"),
        )
        with self.assertRaises(V8Error) as caught:
            run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertIn("renderFirstPageMs", str(caught.exception))

    def test_a_run_with_a_second_reading_line_is_red(self) -> None:
        device = FakeDevice(
            app_sha=self.app_sha,
            logcat_dump=reading(apk=self.app_sha) + reading(apk=self.app_sha, renderFirstPageMs="1.0"),
        )
        with self.assertRaises(V8Error) as caught:
            run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertIn("exactly one is required", str(caught.exception))

    def test_a_failing_transcript_stops_the_session_with_reasons(self) -> None:
        device = FakeDevice(app_sha=self.app_sha, transcript=FAILING)
        with self.assertRaises(V8Error) as caught:
            run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertIn("FAILURES!!!", str(caught.exception))
        self.assertEqual("FAIL", json.loads((self.evidence / HOST_FILE).read_text())["status"])

    def test_a_nonzero_exit_code_is_red_even_with_a_reading(self) -> None:
        device = FakeDevice(app_sha=self.app_sha, exit_code=137)
        with self.assertRaises(V8Error) as caught:
            run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertIn("exit code is 137", str(caught.exception))

    def test_a_logcat_export_failure_fails_the_session_closed(self) -> None:
        device = FakeDevice(app_sha=self.app_sha, logcat_error=V8Error("logcat refused"))
        with self.assertRaises(V8Error) as caught:
            run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertIn("logcat export failed", str(caught.exception))

    def test_a_refused_logcat_clear_does_not_cost_the_run_and_is_recorded(self) -> None:
        device = FakeDevice(app_sha=self.app_sha, logcat_clear_error=V8Error("clear refused"))
        payload = run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertEqual("PASS", payload["status"])
        self.assertEqual("V8Error: clear refused", payload["logcat_clear_error"])

    def test_a_reused_evidence_directory_is_refused_as_stale_evidence(self) -> None:
        # A leftover logcat from an earlier run of the SAME commit could be read as this run's, so
        # the session refuses the directory before it touches the device or overwrites anything.
        device = FakeDevice(app_sha=self.app_sha)
        self.evidence.mkdir(parents=True)
        stale = self.evidence / LOGCAT_FILE
        stale.write_text("11-26 10:05:58.000 I/UnifiedLedger( 1234): P802-V8R fixture=61000\n")
        with self.assertRaises(V8Error) as caught:
            run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertIn("stale evidence", str(caught.exception))
        self.assertEqual([], device.calls)
        self.assertIn("P802-V8R", stale.read_text())

    def test_the_workflows_own_apk_hash_file_is_not_stale_evidence(self) -> None:
        # The workflow records the APK hashes into the evidence directory BEFORE the session runs
        # (its "Record the APK hashes this run installs" step). The gate never reads that file, so it
        # is the one entry a session may find already there.
        device = FakeDevice(app_sha=self.app_sha)
        self.evidence.mkdir(parents=True)
        (self.evidence / "host-apks.txt").write_text(f"{self.app_sha}  app.apk\n")
        payload = run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertEqual("PASS", payload["status"])


class Gating(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.evidence = self.root / "evidence"
        self.app = self.root / "app.apk"
        self.test = self.root / "test.apk"
        self.app.write_bytes(b"app")
        self.test.write_bytes(b"test")
        self.app_sha = hashlib.sha256(self.app.read_bytes()).hexdigest()

    def session(self, **kwargs) -> dict:
        kwargs.setdefault("app_sha", self.app_sha)
        device = FakeDevice(**kwargs)
        return run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)

    def test_a_clean_session_passes_and_writes_the_gate_with_the_reading(self) -> None:
        payload = self.session()
        verdict = gate_evidence(self.evidence, SHA, self.app, self.test)
        self.assertEqual("PASS", verdict["verdict"], verdict["reasons"])
        self.assertEqual(payload["apk_sha256"]["app"], verdict["reading"]["apk"])
        self.assertEqual(str(EXPECTED_TOTAL_COUNT), verdict["reading"]["totalCount"])
        self.assertTrue((self.evidence / GATE_FILE).is_file())

    def test_a_reading_that_disappears_from_disk_fails_the_gate(self) -> None:
        # The gate re-reads the logcat from disk: deleting the line after a PASS session is red.
        self.session()
        (self.evidence / LOGCAT_FILE).write_text(f"11-26 10:05:57.000 I/UnifiedLedger( 1234): P802V8PREP seed\n")
        verdict = gate_evidence(self.evidence, SHA, self.app, self.test)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("P802-V8R", " ".join(verdict["reasons"]))

    def test_a_reading_edited_on_disk_fails_the_gate(self) -> None:
        self.session()
        (self.evidence / LOGCAT_FILE).write_text(reading(apk=self.app_sha, totalCount="8715"))
        verdict = gate_evidence(self.evidence, SHA, self.app, self.test)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("not the manifest's 436", " ".join(verdict["reasons"]))

    def test_an_apk_swapped_on_disk_fails_the_gate(self) -> None:
        self.session()
        self.app.write_bytes(b"another build")
        verdict = gate_evidence(self.evidence, SHA, self.app, self.test)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("hashes to", " ".join(verdict["reasons"]))

    def test_a_test_apk_swapped_on_disk_fails_the_gate(self) -> None:
        # The second APK has its own recompute branch: swapping only the test APK must be red too.
        self.session()
        self.test.write_bytes(b"another test build")
        verdict = gate_evidence(self.evidence, SHA, self.app, self.test)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("the test APK on disk hashes to", " ".join(verdict["reasons"]))

    def test_a_missing_logcat_fails_the_gate(self) -> None:
        # The reading line is re-derived from logcat.txt: without the file there is nothing to read.
        self.session()
        (self.evidence / LOGCAT_FILE).unlink()
        verdict = gate_evidence(self.evidence, SHA, self.app, self.test)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("wrote no logcat.txt", " ".join(verdict["reasons"]))

    def test_a_missing_configuration_fails_the_gate(self) -> None:
        # The pinned API/AVD come from configuration.txt; its absence is a named reason, not a skip.
        self.session()
        (self.evidence / CONFIG_FILE).unlink()
        verdict = gate_evidence(self.evidence, SHA, self.app, self.test)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("wrote no configuration.txt", " ".join(verdict["reasons"]))

    def test_a_self_reported_failure_is_never_re_judged_green(self) -> None:
        self.session()
        host = json.loads((self.evidence / HOST_FILE).read_text())
        host["status"] = "FAIL"
        (self.evidence / HOST_FILE).write_text(json.dumps(host))
        with self.assertRaises(V8Error) as caught:
            gate_evidence(self.evidence, SHA, self.app, self.test)
        self.assertIn("self-reported failure", str(caught.exception))

    def test_evidence_for_another_commit_or_case_is_refused(self) -> None:
        self.session()
        with self.assertRaises(V8Error):
            gate_evidence(self.evidence, "f" * 40, self.app, self.test)
        host = json.loads((self.evidence / HOST_FILE).read_text())
        host["case"] = "com.unifiedledger.android.OtherTest#otherCase"
        (self.evidence / HOST_FILE).write_text(json.dumps(host))
        with self.assertRaises(V8Error):
            gate_evidence(self.evidence, SHA, self.app, self.test)

    def test_a_missing_host_json_or_transcript_is_refused(self) -> None:
        with self.assertRaises(V8Error):
            gate_evidence(self.evidence, SHA, self.app, self.test)
        self.session()
        (self.evidence / TRANSCRIPT_FILE).unlink()
        with self.assertRaises(V8Error):
            gate_evidence(self.evidence, SHA, self.app, self.test)

    def test_a_failed_transcript_fails_the_gate(self) -> None:
        # A session whose driver recorded PASS but whose transcript on disk carries a failure: the
        # gate re-reads the transcript and never inherits the driver's claim.
        self.session()
        (self.evidence / TRANSCRIPT_FILE).write_text(FAILING)
        verdict = gate_evidence(self.evidence, SHA, self.app, self.test)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("FAILURES!!!", " ".join(verdict["reasons"]))

    def test_a_recorded_api_level_other_than_the_pinned_one_fails_the_gate(self) -> None:
        self.session()
        host = json.loads((self.evidence / HOST_FILE).read_text())
        host["api_level"] = "35"
        (self.evidence / HOST_FILE).write_text(json.dumps(host))
        verdict = gate_evidence(self.evidence, SHA, self.app, self.test)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn(f"not {EXPECTED_API}", " ".join(verdict["reasons"]))

    def test_a_device_avd_other_than_the_owned_one_fails_the_gate(self) -> None:
        self.session()
        configuration = json.loads((self.evidence / CONFIG_FILE).read_text())
        configuration["avd"] = "ul-scale"
        (self.evidence / CONFIG_FILE).write_text(json.dumps(configuration))
        verdict = gate_evidence(self.evidence, SHA, self.app, self.test)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("the device's AVD", " ".join(verdict["reasons"]))

    def test_a_recorded_nonzero_exit_code_fails_the_gate(self) -> None:
        self.session()
        host = json.loads((self.evidence / HOST_FILE).read_text())
        host["instrumentation_exit"] = 1
        (self.evidence / HOST_FILE).write_text(json.dumps(host))
        verdict = gate_evidence(self.evidence, SHA, self.app, self.test)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("exit code", " ".join(verdict["reasons"]))


class Contract(unittest.TestCase):
    """The case, the rig and the workflow must agree; a drift turns one of these red."""

    def root(self) -> Path:
        return Path(__file__).resolve().parents[2]

    def case_source(self) -> str:
        return (
            self.root()
            / "android-app"
            / "src"
            / "androidTest"
            / "kotlin"
            / "com"
            / "unifiedledger"
            / "android"
            / "P802V8RenderDeviceSupport.kt"
        ).read_text()

    def emit_function_body(self) -> str:
        """The body of `p802V8EmitReadingLine` alone: the ONE place the reading line is built."""
        source = self.case_source()
        start = source.index("internal fun p802V8EmitReadingLine(")
        opening = source.index("{", start)
        depth = 0
        for index in range(opening, len(source)):
            if source[index] == "{":
                depth += 1
            elif source[index] == "}":
                depth -= 1
                if depth == 0:
                    return source[opening : index + 1]
        raise AssertionError("the reading-line builder has unbalanced braces")

    def function_body(self, name: str) -> str:
        """The body of one Kotlin function in the case: a block body brace-balanced from its
        opening `{`, or the expression after `=` when the function is an expression body."""
        source = self.case_source()
        start = source.index(f"fun {name}(")
        # Skip the parameter list, then the return type, to the signature's `{` or `=`.
        cursor = start + len(f"fun {name}(") - 1
        depth = 0
        while cursor < len(source):
            if source[cursor] == "(":
                depth += 1
            elif source[cursor] == ")":
                depth -= 1
                if depth == 0:
                    cursor += 1
                    break
            cursor += 1
        while cursor < len(source) and source[cursor] not in "{=":
            cursor += 1
        if cursor >= len(source):
            raise AssertionError(f"{name} has no body")
        if source[cursor] == "{":
            opening = cursor
            depth = 0
            for index in range(opening, len(source)):
                if source[index] == "{":
                    depth += 1
                elif source[index] == "}":
                    depth -= 1
                    if depth == 0:
                        return source[opening : index + 1]
            raise AssertionError(f"{name} has unbalanced braces")
        # Expression body: skip the line break after `=`, then stop at the first newline that is
        # not inside the expression's own brackets.
        opening = cursor + 1
        while opening < len(source) and source[opening].isspace():
            opening += 1
        depth = 0
        for index in range(opening, len(source)):
            character = source[index]
            if character in "([{":
                depth += 1
            elif character in ")]}":
                depth -= 1
            elif character == "\n" and depth == 0:
                return source[opening:index].strip()
        raise AssertionError(f"{name} has an unterminated expression body")

    def instrumented_test_source(self) -> str:
        return (
            self.root()
            / "android-app"
            / "src"
            / "androidTest"
            / "kotlin"
            / "com"
            / "unifiedledger"
            / "android"
            / "P802V8RenderInstrumentedTest.kt"
        ).read_text()

    def test_the_case_carries_the_rig_timeout_and_reds_on_its_own_budget(self) -> None:
        # The driver kills one `am instrument` at DEFAULT_TIMEOUT; the case carries the SAME budget and
        # asserts on what is left before the terminal starts, so a drift between the two reds here
        # instead of turning into a killed process with no cause on the transcript.
        self.assertIn(f"RIG_TIMEOUT_MILLIS = {int(DEFAULT_TIMEOUT * 1000):_}L", self.case_source())
        test_source = self.instrumented_test_source()
        self.assertIn("p802V8BudgetLine(", self.case_source())
        self.assertIn("p802V8BudgetLine(", test_source)
        self.assertIn("remainingBudget >= P802V8RenderFacts.REMAINING_WORK_MILLIS", test_source)

    def test_both_control_placements_share_one_verified_channel(self) -> None:
        # The tag and the merchant are the same defect class: one implementation proves both.
        for name in ("toggleTag", "selectMerchant"):
            self.assertIn("checkVerified(", self.function_body(name), name)

    def test_the_toggle_predicate_sees_selected_state_and_radio_rows(self) -> None:
        # QJ-2: the merchant row is a Material3 RadioButton whose state is `selected`, not `checked`
        # (`P503SearchScreen.kt:247`). Reverting `isToggle` to an `isCheckable`-only predicate drops
        # both tokens below, so the row becomes invisible to `toggleNear`; this contract reds instead
        # of waiting for a device round to explain a red.
        body = self.function_body("isToggle")
        self.assertIn("isSelected", body)
        self.assertIn("RadioButton", body)

    def test_the_merchant_effect_judges_the_selected_state(self) -> None:
        # QJ-2: the same row reports `selected`, so an effect reverted to `node.isChecked` would wait
        # out its whole poll and red on the device after a landed click. The effect is owned by
        # `selectMerchant`, which hands it to `checkVerified`; dropping `isSelected` from that
        # argument reds here.
        body = self.function_body("selectMerchant")
        self.assertIn("effect =", body)
        self.assertIn("isSelected", body[body.index("effect =") :])

    def test_a_control_placement_judges_state_from_a_forced_current_tree(self) -> None:
        body = self.function_body("checkVerified")
        self.assertIn("refreshTreeForRead()", body)
        self.assertIn("toggleNear(", body)
        self.assertIn("clickSemantic(", body)
        self.assertIn("tapAt(", body)
        self.assertIn("awaitEffect(", body)

    def test_a_refused_placement_names_expected_read_and_clicked_bounds_in_one_line(self) -> None:
        body = self.function_body("checkVerified")
        self.assertIn("check-failed", body)
        for token in ("expected=", "read=", "clickedBounds="):
            self.assertIn(token, body)

    def test_the_state_poll_is_bounded_and_forces_the_tree_current(self) -> None:
        body = self.function_body("awaitEffect")
        self.assertIn("CHECK_TIMEOUT_MILLIS", body)
        self.assertIn("CHECK_POLL_MILLIS", body)
        self.assertIn("CHECK_REFRESH_EVERY", body)
        self.assertIn("refreshTreeForRead()", body)

    def test_the_accept_time_tree_read_is_forced_current(self) -> None:
        self.assertIn("refreshTreeForRead()", self.function_body("confirmResultView"))

    def test_the_case_emits_every_field_the_rig_requires(self) -> None:
        # The check reads the reading-line builder ONLY: a field that survives in a diagnostic line
        # elsewhere in the file must not keep this green.
        body = self.emit_function_body()
        for key in REQUIRED_READING_KEYS:
            self.assertIn(f"{key}=", body, key)

    def test_the_field_check_reads_the_reading_line_builder_only(self) -> None:
        body = self.emit_function_body()
        self.assertIn("${P802V8RenderFacts.READING_PREFIX} fixture=", body)
        self.assertNotIn("DIAGNOSTIC_PREFIX", body)
        self.assertIn("return line", body)

    def test_the_case_carries_the_frozen_constants(self) -> None:
        source = self.case_source()
        self.assertIn(f'READING_PREFIX = "{READING_PREFIX}"', source)
        self.assertIn("SAMPLE_INTERVAL_MILLIS = 50", source)
        self.assertIn(f"SETTLE_SAMPLES = {SETTLE_SAMPLES}", source)
        self.assertIn(f'CONDITION_LABEL = "{EXPECTED_CONDITION}"', source)
        self.assertIn(f"EXPECTED_TOTAL_COUNT = {EXPECTED_TOTAL_COUNT}", source)
        self.assertIn(f"EXPECTED_PAGE_ROWS = {EXPECTED_PAGE_ROWS}", source)
        self.assertIn(f"EXPECTED_TRANSACTIONS = {EXPECTED_FIXTURE_TRANSACTIONS}", source)

    def test_the_case_never_reuses_the_reading_prefix_for_a_diagnostic_line(self) -> None:
        source = self.case_source()
        # Every diagnostic line goes through DIAGNOSTIC_PREFIX, whose value must not contain the
        # reading prefix: otherwise one informational line would make the reading count 2.
        self.assertIn('DIAGNOSTIC_PREFIX = "P802V8', source)
        self.assertNotIn(READING_PREFIX, "P802V8PREP" + "P802V8UI")
        # READING_PREFIX appears exactly twice: its frozen declaration and the ONE line builder.
        # A diagnostic that reused it would make this 3 and the gate's "exactly once" bound red.
        self.assertEqual(2, source.count("READING_PREFIX"))

    def test_the_workflow_drives_this_module_with_its_own_avd(self) -> None:
        text = (self.root() / ".github" / "workflows" / "android-p802-v8.yml").read_text()
        self.assertIn("tools/ci/android-p802-v8.py run", text)
        self.assertIn("tools/ci/android-p802-v8.py gate", text)
        self.assertIn(f"--avd-name {DEFAULT_AVD}", text)
        self.assertIn(f"avd-name: {DEFAULT_AVD}", text)
        self.assertIn("--sha", text)
        self.assertIn("workflow_dispatch:", text)
        self.assertNotIn("pull_request:", text)
        gate_index = text.index("Re-derive the gate from the evidence on disk")
        self.assertIn("if: always()", text[gate_index : gate_index + 400])

    def test_the_workflow_pins_the_device_profile_and_the_apk_paths(self) -> None:
        text = (self.root() / ".github" / "workflows" / "android-p802-v8.yml").read_text()
        self.assertIn("profile: pixel_6", text)
        self.assertIn(f"api-level: {EXPECTED_API}", text)
        self.assertIn("target: google_apis", text)
        self.assertIn("arch: x86_64", text)
        # The gate recomputes the APK hashes from disk, so both paths must reach it.
        self.assertIn("--app ", text)
        self.assertIn("--test ", text)

    def test_the_class_is_excluded_from_the_device_inventory_with_its_reason(self) -> None:
        import sys

        sys.path.insert(0, str(self.root() / "tools" / "python"))
        from android_scale.inventory import EXCLUDED

        self.assertIn("P802V8RenderInstrumentedTest", EXCLUDED)

    def test_the_ordinary_device_regression_does_not_list_this_case(self) -> None:
        root = self.root()
        inventory = (root / "tools" / "ci" / "android-instrumented-inventory.txt").read_text()
        shards = (root / "tools" / "ci" / "android-instrumented-shards.txt").read_text()
        self.assertNotIn("P802V8RenderInstrumentedTest", inventory)
        self.assertNotIn("P802V8RenderInstrumentedTest", shards)

    def test_the_python_module_is_registered_in_a_shard(self) -> None:
        lines = (self.root() / "tools" / "ci" / "python-shards.txt").read_text().splitlines()
        self.assertIn("2\ttests.python.test_android_p802_v8", lines)


if __name__ == "__main__":
    unittest.main()
