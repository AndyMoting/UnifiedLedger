"""P8-07 Q2 flow 2 device evidence: drive ONE case on a cloud AVD, then re-derive the verdict.

WHAT ONE GREEN RUN PROVES
-------------------------
The design gate's second cross-slice flow (`docs/P807_DESIGN_GATE.local.md`, 「已批准的跨切片流程」
row 2; the behavioural criteria are `docs/specs/2026-11-11-p8-01-account-reconciliation-design.md`
sections 2.2-2.7 and section 4 vectors V-8 / V-10 / V-11): on the pinned emulator profile, the
single instrumented case `P807Q2Flow2ReconciliationInstrumentedTest#...` runs end to end and
passes. The case itself carries the flow's assertions; this module only proves that exactly that
one case executed, on a device this runner owns, at exactly one commit — it adds no product claim.

WHY A DEDICATED RIG
-------------------
The flow seeds the app's OWN stable storage before any activity launch (the
`p807Q2Flow1RequireNoOpenLedgerHandle` guard), so its process must be the FIRST case of its
instrumentation run. The ordinary two-shard regression gives each shard exactly one `am instrument`
call, and both shard-heads are already taken (shard 1 by `P807Q2ImportSearchExportInstrumentedTest`,
shard 2 by `ImportReviewPagingInstrumentedTest`), so this flow gets its own dispatch-only workflow
and its own AVD. Nothing here changes the ordinary manifest or its shards.

WHY THE HOST IS A CLOUD RUNNER
------------------------------
The development host's adb surface belongs to the user's MuMu/ALas automation, so no device chain
boots a local emulator here. `run` therefore refuses to start outside a GitHub-hosted Linux
runner, and the workflow binds the run to one exact SHA before it builds.

HOW THE TWO SUBCOMMANDS SPLIT
-----------------------------
`run` owns every device interaction (single-owner AVD assertion, force-stop, both APK installs with
their hashes, the one `am instrument -w -r -e class` call, the raw transcript, logcat and the
pinned configuration) and writes `host.json`. `gate` never trusts the driver's verdict: it re-reads
the transcript from disk and re-applies every bound. It also refuses a session whose OWN status is
not PASS — one-way trust: a self-reported failure is never re-judged green, while a self-reported
success still has to be proven by the transcript.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

PACKAGE = "com.unifiedledger.android"
TEST_RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"

# The one flow this rig drives, and the ONE case it runs in ONE `am instrument` call. Keyed by the
# `--flow` value the workflow passes, so an unknown flow is refused before any device is touched.
FLOWS: dict[int, str] = {
    2: (
        "com.unifiedledger.android.P807Q2Flow2ReconciliationInstrumentedTest"
        "#theImportedEvidenceBindsToTheExistingPostingAndSurvivesAuditReentry"
    ),
}

SCHEMA = 1
DEFAULT_AVD = "ul-p807-q2"
# The pinned emulator profile the workflow declares; the gate re-checks the recorded API level so
# evidence from a re-imaged runner can never be read under the wrong label.
EXPECTED_API = "36"
HOSTED_RUNNER = ("true", "github-hosted", "Linux")

DEFAULT_TIMEOUT = 1800.0
INSTALL_TIMEOUT = 300.0
CONFIG_TIMEOUT = 60.0

PASS_MARKER = "OK (1 test)"
# Raw `am instrument` markers that mean the run did not complete as a clean single-case pass. They
# are checked verbatim in addition to the parsed records.
FAILURE_MARKERS = ("FAILURES!!!", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED",
                   "Process crashed", "shortMsg=")

STATUS_FIELD = "INSTRUMENTATION_STATUS: "
STATUS_CODE = "INSTRUMENTATION_STATUS_CODE: "
COMPLETION_CODE = "INSTRUMENTATION_CODE: "
TERMINAL_STATUS = (-1, -2, -3, -4)

CASE = re.compile(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*#[A-Za-z_$][\w$]*")
SHA = re.compile(r"[0-9a-f]{40}")

HOST_FILE = "host.json"
TRANSCRIPT_FILE = "instrumentation.txt"
STDERR_FILE = "instrumentation-stderr.txt"
LOGCAT_FILE = "logcat.txt"
CONFIG_FILE = "configuration.txt"
GATE_FILE = "gate.json"


class Q2Error(Exception):
    """A refusal or an unreadable observation; never a verdict."""


class Deadline:
    """One outer budget for the whole device session, recomputed from an absolute epoch."""

    def __init__(self, epoch: float | None):
        self.epoch = epoch

    def remaining(self) -> float:
        return float("inf") if self.epoch is None else self.epoch - time.time()

    def bound(self, timeout: float) -> float:
        left = self.remaining()
        if left <= 0:
            raise Q2Error("the outer deadline is exhausted")
        return min(timeout, left)


def require_hosted_runner(environ: dict | None = None) -> None:
    """Cloud-only: this driver installs into and stops an emulator it does not own."""
    env = os.environ if environ is None else environ
    triple = (env.get("GITHUB_ACTIONS"), env.get("RUNNER_ENVIRONMENT"), env.get("RUNNER_OS"))
    if triple != HOSTED_RUNNER:
        raise Q2Error("this driver is cloud-only; a local or user device is never operated by it")


def flow_case(flow: int) -> str:
    """The ONE `class#method` of [flow]; an unknown flow is a refusal, never a default."""
    case = FLOWS.get(flow)
    if case is None:
        raise Q2Error(f"unknown flow {flow!r}; this rig drives exactly {sorted(FLOWS)}")
    return case


def require_sha(sha: str) -> str:
    """The evidence is bound to a full lowercase 40-hex commit, nothing shorter."""
    if not SHA.fullmatch(sha):
        raise Q2Error(f"a full lowercase 40-hex commit sha is required, got {sha!r}")
    return sha


def owned_serial(devices: str) -> str:
    """Exactly one attached emulator: a runner is never shared between AVDs."""
    serials = [
        line.split()[0]
        for line in devices.replace("\r\n", "\n").splitlines()
        if len(line.split()) >= 2 and line.split()[0].startswith("emulator-") and line.split()[1] == "device"
    ]
    if len(serials) != 1:
        raise Q2Error(f"exactly one owned emulator must be attached, saw {serials or 'none'}")
    return serials[0]


def parse_avd_name(output: str) -> str:
    """The AVD name in an `adb emu avd name` answer, which ends with the console's own `OK`."""
    lines = [line.strip() for line in output.replace("\r", "").splitlines() if line.strip()]
    if len(lines) == 2 and lines[1] == "OK":
        return "" if lines[0] == "OK" else lines[0]
    if len(lines) == 1 and lines[0] != "OK":
        return lines[0]
    return ""


def _identity(fields: dict[str, str]) -> str | None:
    classname, test = fields.get("class", ""), fields.get("test", "")
    if not classname or not test:
        return None
    return classname + "#" + test


def parse_instrumentation(text: str) -> dict:
    """The parsed `am instrument -w -r` transcript: started/passed/failed cases, code, count, markers."""
    started: list[str] = []
    passed: list[str] = []
    failed: list[tuple[str, int]] = []
    markers: list[str] = []
    fields: dict[str, str] = {}
    code: int | None = None
    reported: int | None = None
    for line in text.splitlines():
        if line.startswith(STATUS_FIELD):
            key, _, value = line[len(STATUS_FIELD):].partition("=")
            if key:
                fields[key] = value
            continue
        if line.startswith(STATUS_CODE):
            try:
                value = int(line[len(STATUS_CODE):].strip())
            except ValueError:
                raise Q2Error("invalid instrumentation status code") from None
            identity, fields = _identity(fields), {}
            if identity is None:
                if value in TERMINAL_STATUS:
                    raise Q2Error("instrumentation failure without a test identity")
                continue
            if value == 1:
                started.append(identity)
            elif value == 0:
                passed.append(identity)
            elif value in TERMINAL_STATUS:
                failed.append((identity, value))
            else:
                raise Q2Error(f"unexpected instrumentation status code: {value}")
            continue
        if line.startswith(COMPLETION_CODE):
            try:
                code = int(line[len(COMPLETION_CODE):].strip())
            except ValueError:
                raise Q2Error("invalid instrumentation completion code") from None
            continue
        count = re.fullmatch(r"OK \((\d+) tests?\)", line.strip())
        if count:
            reported = int(count[1])
        total = re.match(r"^Tests run: (\d+),", line)
        if total:
            reported = int(total[1])
        for marker in FAILURE_MARKERS:
            if marker in line and marker not in markers:
                markers.append(marker)
    return {
        "started": started,
        "passed": passed,
        "failed": failed,
        "code": code,
        "reported": reported,
        "markers": markers,
    }


def judge(text: str, case: str) -> list[str]:
    """Every reason this transcript is NOT a clean single-case pass; an empty list is PASS.

    The judgement is complete from the transcript alone (plus the expected case), so a driver that
    mis-read its own session cannot make a red run green. One-way additions live in
    [gate_evidence]: the session's self-reported status may only make a run redder.
    """
    reasons: list[str] = []
    if not CASE.fullmatch(case):
        reasons.append(f"{case!r} is not a class#method case")
        return reasons
    try:
        parsed = parse_instrumentation(text)
    except Q2Error as error:
        return [str(error)]
    if not text.strip():
        return ["the transcript is empty"]
    if PASS_MARKER not in text:
        reasons.append(f"the transcript does not report {PASS_MARKER!r}")
    if parsed["markers"]:
        reasons.append("the transcript carries failure markers: " + ", ".join(parsed["markers"]))
    if parsed["failed"]:
        failed_case, status = parsed["failed"][0]
        reasons.append(f"the case did not pass: {failed_case} (status {status})")
    if parsed["code"] != -1:
        reasons.append(f"the instrumentation completion code is {parsed['code']!r}, not -1")
    for label, values in (("started", parsed["started"]), ("passed", parsed["passed"])):
        if len(values) != len(set(values)):
            reasons.append(f"the transcript repeats a {label} case: {sorted(values)}")
    expected = {case}
    if set(parsed["started"]) != expected:
        reasons.append(f"the started case set is {sorted(set(parsed['started']))}, not exactly {[case]}")
    missing = sorted(expected - set(parsed["passed"]))
    extra = sorted(set(parsed["passed"]) - expected)
    if missing or extra:
        reasons.append(f"the executed case set differs from the request: missing={missing} extra={extra}")
    if parsed["reported"] != 1:
        reasons.append(f"the runner reported {parsed['reported']!r} tests, not exactly 1")
    return reasons


class Device:
    """The only place this driver talks to adb; every call is bounded by the deadline."""

    def __init__(self, serial: str, deadline: Deadline, package: str = PACKAGE):
        self.serial = serial
        self.deadline = deadline
        self.package = package

    def run(self, args: list[str], *, timeout: float = 120.0) -> tuple[int, str, str]:
        bound = self.deadline.bound(timeout)
        try:
            result = subprocess.run(
                ["adb", "-s", self.serial, *args],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=bound,
            )
        except subprocess.TimeoutExpired:
            raise Q2Error(f"adb timed out after {bound:.0f}s: {' '.join(args)}") from None
        return (
            result.returncode,
            result.stdout.decode("utf-8", errors="replace"),
            result.stderr.decode("utf-8", errors="replace"),
        )

    def text(self, *args: str, timeout: float = 120.0) -> str:
        code, out, err = self.run(list(args), timeout=timeout)
        if code:
            raise Q2Error(f"adb failed ({code}): {' '.join(args)}: {(err or out).strip()[:300]}")
        return out

    def instrument(self, case: str, timeout: float) -> tuple[int, str, str]:
        """Run ONE case: one `-e class` value, the raw `-w -r` transcript returned verbatim."""
        code, out, err = self.run(
            ["shell", "am", "instrument", "-w", "-r", "-e", "class", case, TEST_RUNNER],
            timeout=timeout,
        )
        return code, out, err


def install_apks(device: Device, app: Path, test: Path) -> dict:
    """Hash both APKs, stop the app, install them, and prove the runner targets the app."""
    hashes: dict[str, str] = {}
    stopped = False
    for role, apk in (("app", app), ("test", test)):
        if not apk.is_file() or apk.stat().st_size == 0:
            raise Q2Error(f"the {role} APK is missing or empty: {apk}")
        hashes[role] = hashlib.sha256(apk.read_bytes()).hexdigest()
        if not stopped:
            # The flow seeds the app's own storage BEFORE any activity launch, so no process of the
            # app may be alive when the case starts.
            device.text("shell", "am", "force-stop", PACKAGE)
            stopped = True
        output = device.text("install", "-t", str(apk), timeout=INSTALL_TIMEOUT)
        if "Success" not in output or "Failure" in output:
            raise Q2Error(f"install of the {role} APK failed: {output.strip()[:300]}")
    listed = device.text("shell", "pm", "list", "instrumentation")
    if f"instrumentation:{TEST_RUNNER} (target={PACKAGE})" not in listed.splitlines():
        raise Q2Error("the installed instrumentation does not target the app under test")
    return hashes


def collect_configuration(device: Device, avd: str) -> dict:
    """The pinned facts the gate re-checks, read from the device that actually runs the case."""
    api = device.text("shell", "getprop", "ro.build.version.sdk", timeout=CONFIG_TIMEOUT).strip()
    size = _last_match(r"(?:Physical|Override) size: ([0-9]+x[0-9]+)",
                       device.text("shell", "wm", "size", timeout=CONFIG_TIMEOUT), "display size")
    density = _last_match(r"(?:Physical|Override) density: ([0-9]+)",
                          device.text("shell", "wm", "density", timeout=CONFIG_TIMEOUT), "display density")
    locale = device.text("shell", "getprop", "persist.sys.locale", timeout=CONFIG_TIMEOUT).strip()
    font_scale = device.text("shell", "settings", "get", "system", "font_scale", timeout=CONFIG_TIMEOUT).strip()
    return {
        "api_level": api,
        "avd": avd,
        "font_scale": font_scale,
        "locale": locale,
        "serial": device.serial,
        "wm_density": density,
        "wm_size": size,
    }


def _last_match(pattern: str, text: str, label: str) -> str:
    matches = re.findall(pattern, text)
    if not matches:
        raise Q2Error(f"cannot read {label} from the device: {text.strip()[:200]!r}")
    return matches[-1]


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _write(path: Path, text: str) -> None:
    path.write_bytes(text.encode("utf-8"))


def run_session(
    app: Path,
    test: Path,
    evidence: Path,
    sha: str,
    flow: int = 2,
    avd_name: str = DEFAULT_AVD,
    deadline: Deadline | None = None,
    device_factory=None,
    timeout: float = DEFAULT_TIMEOUT,
) -> dict:
    """One device session for the flow's single case; returns (and writes) `host.json`."""
    require_sha(sha)
    case = flow_case(flow)
    budget = Deadline(None) if deadline is None else deadline
    evidence.mkdir(parents=True, exist_ok=True)
    started_at = _now()
    if device_factory is None:
        serial = owned_serial(_adb_devices(budget))
        device = Device(serial, budget)
    else:
        device = device_factory(budget)
        serial = device.serial
    name = parse_avd_name(device.text("emu", "avd", "name"))
    if name != avd_name:
        raise Q2Error(f"this runner owns {name or 'an unnamed emulator'}, not {avd_name}")
    hashes = install_apks(device, app, test)
    configuration = collect_configuration(device, name)
    _write(evidence / CONFIG_FILE, json.dumps(configuration, indent=2, sort_keys=True))
    _write(evidence / LOGCAT_FILE, device.text("logcat", "-d", "-v", "threadtime", timeout=CONFIG_TIMEOUT))
    exit_code, stdout, stderr = device.instrument(case, budget.bound(timeout))
    _write(evidence / TRANSCRIPT_FILE, stdout)
    if stderr.strip():
        _write(evidence / STDERR_FILE, stderr)
    payload = {
        "schema": SCHEMA,
        "sha": sha,
        "flow": flow,
        "case": case,
        "avd": name,
        "serial": serial,
        "api_level": configuration["api_level"],
        "started_at": started_at,
        "finished_at": _now(),
        "instrumentation_exit": exit_code,
        "apk_sha256": hashes,
        "files": {
            "configuration": CONFIG_FILE,
            "instrumentation": TRANSCRIPT_FILE,
            "logcat": LOGCAT_FILE,
        },
        "status": "FAIL",
    }
    reasons = judge(stdout, case)
    if exit_code:
        reasons.insert(0, f"the instrumentation exit code is {exit_code}, not 0")
    if reasons:
        payload["reasons"] = reasons
        _write(evidence / HOST_FILE, json.dumps(payload, indent=2, sort_keys=True))
        raise Q2Error("the case did not pass: " + "; ".join(reasons))
    payload["status"] = "PASS"
    _write(evidence / HOST_FILE, json.dumps(payload, indent=2, sort_keys=True))
    return payload


def _adb_devices(deadline: Deadline) -> str:
    result = subprocess.run(["adb", "devices"], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=deadline.bound(120.0))
    return result.stdout.decode("utf-8", errors="replace")


def gate_evidence(evidence: Path, sha: str, flow: int = 2) -> dict:
    """Re-derive the verdict from the evidence on disk; `host.json` only identifies the run.

    The driver's own status is trusted in ONE direction only: anything but `PASS` makes the run red
    (a session that reported a failure is never re-judged green), while a `PASS` claim still has to
    be proven by the transcript, the recorded configuration and the exit code.
    """
    require_sha(sha)
    case = flow_case(flow)
    host_path = evidence / HOST_FILE
    if not host_path.is_file():
        raise Q2Error("the session wrote no host.json; nothing to bind this verdict to")
    host = json.loads(host_path.read_text())
    if host.get("sha") != sha:
        raise Q2Error(f"the evidence belongs to {host.get('sha')}, not {sha}")
    if host.get("flow") != flow:
        raise Q2Error(f"the evidence belongs to flow {host.get('flow')!r}, not {flow}")
    if host.get("case") != case:
        raise Q2Error(f"the evidence was produced for {host.get('case')!r}, not {case}")
    if host.get("status") != "PASS":
        raise Q2Error(
            f"the session's own status is {host.get('status')!r}; a self-reported failure is never "
            "re-judged green"
        )
    transcript_path = evidence / TRANSCRIPT_FILE
    if not transcript_path.is_file():
        raise Q2Error("the session wrote no instrumentation transcript; nothing to judge")
    reasons = judge(transcript_path.read_text(), case)
    if host.get("instrumentation_exit") != 0:
        reasons.insert(0, f"the recorded instrumentation exit code is {host.get('instrumentation_exit')!r}, not 0")
    recorded_api = str(host.get("api_level") or "")
    if recorded_api != EXPECTED_API:
        reasons.append(f"the recorded API level is {recorded_api!r}, not {EXPECTED_API}")
    config_path = evidence / CONFIG_FILE
    if not config_path.is_file():
        reasons.append("the session wrote no configuration.txt")
        configuration: dict = {}
    else:
        configuration = json.loads(config_path.read_text())
        if str(configuration.get("api_level") or "") != EXPECTED_API:
            reasons.append(f"the device's API level is {configuration.get('api_level')!r}, not {EXPECTED_API}")
    execution = {
        "api_level": recorded_api,
        "avd": host.get("avd"),
        "case": case,
        "flow": flow,
        "serial": host.get("serial"),
        "sha": sha,
    }
    verdict = {
        "verdict": "FAIL" if reasons else "PASS",
        "reasons": reasons,
        "execution": execution,
        "configuration": configuration,
        "apk_sha256": host.get("apk_sha256"),
        "started_at": host.get("started_at"),
        "finished_at": host.get("finished_at"),
    }
    _write(evidence / GATE_FILE, json.dumps(verdict, indent=2, sort_keys=True))
    return verdict


def format_verdict(verdict: dict) -> str:
    execution = verdict["execution"]
    lines = [
        f"P8-07 Q2 flow {execution['flow']} device gate: {verdict['verdict']}",
        f"  sha={execution['sha']} avd={execution['avd']} serial={execution['serial']} api={execution['api_level']}",
        f"  case={execution['case']}",
    ]
    for reason in verdict["reasons"]:
        lines.append(f"  FAIL: {reason}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    import argparse
    import sys

    parser = argparse.ArgumentParser(description="P8-07 Q2 flow-2 device evidence on a cloud AVD")
    sub = parser.add_subparsers(dest="action", required=True)

    run = sub.add_parser("run", help="drive the one case inside the emulator session")
    run.add_argument("--app", type=Path, required=True)
    run.add_argument("--test", type=Path, required=True)
    run.add_argument("--evidence", type=Path, required=True)
    run.add_argument("--sha", required=True)
    run.add_argument("--flow", type=int, required=True)
    run.add_argument("--avd-name", default=DEFAULT_AVD)
    run.add_argument("--outer-deadline-epoch", type=float, required=True)

    check = sub.add_parser("gate", help="re-derive the verdict from the evidence on disk")
    check.add_argument("--evidence", type=Path, required=True)
    check.add_argument("--sha", required=True)
    check.add_argument("--flow", type=int, required=True)

    args = parser.parse_args(argv)
    try:
        if args.action == "run":
            require_hosted_runner()
            payload = run_session(
                args.app,
                args.test,
                args.evidence,
                args.sha,
                flow=args.flow,
                avd_name=args.avd_name,
                deadline=Deadline(args.outer_deadline_epoch),
            )
            print(f"flow {payload['flow']} case {payload['case']} status={payload['status']}")
            return 0
        verdict = gate_evidence(args.evidence, args.sha, args.flow)
        print(format_verdict(verdict))
        return 0 if verdict["verdict"] == "PASS" else 1
    except Q2Error as error:
        print(f"P8-07 Q2 gate: {error}", file=sys.stderr)
        return 2
