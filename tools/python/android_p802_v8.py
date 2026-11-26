"""P8-02 V-8 re-take: the 61k first-result-page RENDER terminal, driven on a cloud AVD.

WHAT ONE GREEN RUN PROVES
-------------------------
On the pinned emulator profile, the single instrumented case
`P802V8RenderInstrumentedTest#theFirstResultPageRendersAndIsMeasuredInProcessPixels` seeds the
app's OWN stable storage with the 61,000-transaction search sample, launches the app, drives the
frozen four-item condition group through the real search surface, and measures 首屏渲染完成 the way
the stage-acceptance design requires (`docs/specs/2026-11-22-p8-07-stage-acceptance-design.md`
section 3.3, Q5; the vector is P8-02 spec section 5 V-8 and its 「第二终点未取得」 registration in
section 9): IN PROCESS, from `UiAutomation.takeScreenshot()` samples of the result region, never
from a port return, a semantic tree or `gfxinfo`.

The case carries its own structural assertions (the sample's construction manifests, the frozen
page size, the rendered count). This module only proves that exactly that one case executed, on a
device this runner owns, at exactly one commit, and that the ONE reading line it emitted satisfies
every bound the design fixes. It adds no product claim, no threshold, and no stage acceptance.

WHY A DEDICATED RIG
-------------------
The sample is seeded into the app's real stable storage BEFORE any activity launch (the
`/proc/self/fd` guard, the P8-07 flow-1 shape), so the case must be the FIRST case of its
instrumentation process. The ordinary two-shard regression gives each shard exactly one
`am instrument` call and both shard heads are taken, so this rig gets its own dispatch-only
workflow, its own AVD and its own gate. No ordinary manifest, shard file or required check changes.

WHY THE HOST IS A CLOUD RUNNER
------------------------------
The development host's adb surface belongs to the user's MuMu/ALas automation, so no device chain
boots a local emulator here. `run` therefore refuses to start outside a GitHub-hosted Linux
runner, and the workflow binds the run to one exact SHA before it builds.

HOW THE TWO SUBCOMMANDS SPLIT
-----------------------------
`run` owns every device interaction (single-owner AVD assertion, force-stop, both APK installs with
their hashes, the log clear AFTER the installs, the one `am instrument -w -r -e class` call, the
post-run log export, the raw transcript and the pinned configuration) and writes `host.json`.
`gate` never trusts the driver's verdict: it re-reads the transcript, the logcat and the installed
APK files from disk and re-applies every bound. It also refuses a session whose OWN status is not
PASS (one-way trust).

THE READING LINE IS THE WHOLE EVIDENCE
--------------------------------------
The case emits exactly ONE line whose message starts with [READING_PREFIX]; every diagnostic line
it logs carries a different prefix, so a second reading-shaped line is itself a red. The gate
requires that line exactly once and re-derives each field's bound from the text, never from a
number the driver remembered.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import re
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

PACKAGE = "com.unifiedledger.android"
TEST_RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"

# The ONE case this rig drives, in ONE `am instrument` call.
CASE = (
    "com.unifiedledger.android.P802V8RenderInstrumentedTest"
    "#theFirstResultPageRendersAndIsMeasuredInProcessPixels"
)

SCHEMA = 1
DEFAULT_AVD = "ul-p802-v8"
# The pinned emulator profile the workflow declares; the gate re-checks the recorded API level so
# evidence from a re-imaged runner can never be read under the wrong label.
EXPECTED_API = "36"
HOSTED_RUNNER = ("true", "github-hosted", "Linux")

DEFAULT_TIMEOUT = 2400.0
INSTALL_TIMEOUT = 300.0
CONFIG_TIMEOUT = 60.0

PASS_MARKER = "OK (1 test)"
FAILURE_MARKERS = ("FAILURES!!!", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED",
                   "Process crashed", "shortMsg=")
STATUS_FAILURE_KEYS = ("shortMsg", "Error")

STATUS_FIELD = "INSTRUMENTATION_STATUS: "
STATUS_CODE = "INSTRUMENTATION_STATUS_CODE: "
COMPLETION_CODE = "INSTRUMENTATION_CODE: "
TERMINAL_STATUS = (-1, -2, -3, -4)

CASE_PATTERN = re.compile(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*#[A-Za-z_$][\w$]*")
SHA_PATTERN = re.compile(r"[0-9a-f]{40}")
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")

# ---- the reading-line contract (the single authority both sides are checked against) -----------
#
# The case emits it as one logical message; logcat may split a long message, and the case chunks its
# own long lines at 1,000 characters, so the prefix stays the only anchor. Every diagnostic line the
# case logs uses `P802V8PREP` or `P802V8UI`, which do not contain this prefix.
READING_PREFIX = "P802-V8R"

# The frozen fixture/condition/reading bounds. Each is a fact of the V-8 vector, not a threshold:
# 61,000 transactions (spec section 5 V-8 — the 61k CANDIDATE library cannot stand in for it),
# page 50 (the frozen page size), and 436 (the fixed group's construction manifest: every 70th
# transaction carries an annotation, every second one names merchant-0, so 61,000 / 70 / 2 = 436).
EXPECTED_FIXTURE_TRANSACTIONS = 61000
EXPECTED_PAGE_ROWS = 50
EXPECTED_TOTAL_COUNT = 436
EXPECTED_CONDITION = "fixed-note+tag+merchant0"

# The case's settle window. The design fixes the terminal as "the first sample pixel-identical to
# the final settled state"; the case freezes its own settle window and this module re-checks it.
SETTLE_SAMPLES = 3

REQUIRED_READING_KEYS = (
    "fixture",
    "condition",
    "sampleIntervalMs",
    "samples",
    "settleSamples",
    "renderFirstPageMs",
    "pageRows",
    "totalCount",
    "schema",
    "apk",
)

HOST_FILE = "host.json"
TRANSCRIPT_FILE = "instrumentation.txt"
STDERR_FILE = "instrumentation-stderr.txt"
LOGCAT_FILE = "logcat.txt"
CONFIG_FILE = "configuration.txt"
GATE_FILE = "gate.json"


class V8Error(Exception):
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
            raise V8Error("the outer deadline is exhausted")
        return min(timeout, left)


def require_hosted_runner(environ: dict | None = None) -> None:
    """Cloud-only: this driver installs into and stops an emulator it does not own."""
    env = os.environ if environ is None else environ
    triple = (env.get("GITHUB_ACTIONS"), env.get("RUNNER_ENVIRONMENT"), env.get("RUNNER_OS"))
    if triple != HOSTED_RUNNER:
        raise V8Error("this driver is cloud-only; a local or user device is never operated by it")


def require_sha(sha: str) -> str:
    """The evidence is bound to a full lowercase 40-hex commit, nothing shorter."""
    if not SHA_PATTERN.fullmatch(sha):
        raise V8Error(f"a full lowercase 40-hex commit sha is required, got {sha!r}")
    return sha


def owned_serial(devices: str) -> str:
    """Exactly one attached emulator: a runner is never shared between AVDs."""
    serials = [
        line.split()[0]
        for line in devices.replace("\r\n", "\n").splitlines()
        if len(line.split()) >= 2 and line.split()[0].startswith("emulator-") and line.split()[1] == "device"
    ]
    if len(serials) != 1:
        raise V8Error(f"exactly one owned emulator must be attached, saw {serials or 'none'}")
    return serials[0]


def parse_avd_name(output: str) -> str:
    """The AVD name in an `adb emu avd name` answer, which ends with the console's own `OK`."""
    lines = [line.strip() for line in output.replace("\r", "").splitlines() if line.strip()]
    if len(lines) == 2 and lines[1] == "OK":
        return "" if lines[0] == "OK" else lines[0]
    if len(lines) == 1 and lines[0] != "OK":
        return lines[0]
    return ""


# ------------------------------------------------------------------ the reading line


def reading_lines(text: str) -> list[str]:
    """Every logcat line whose message carries [READING_PREFIX] (the count must be exactly one)."""
    return [line for line in text.replace("\r\n", "\n").splitlines() if READING_PREFIX in line]


def parse_reading(text: str) -> dict[str, str]:
    """The ONE reading line's `key=value` fields; anything but exactly one line is a refusal."""
    lines = reading_lines(text)
    if not lines:
        raise V8Error(f"the logcat carries no {READING_PREFIX} reading line")
    if len(lines) > 1:
        raise V8Error(
            f"the logcat carries {len(lines)} {READING_PREFIX} reading lines; exactly one is required"
        )
    message = lines[0][lines[0].index(READING_PREFIX) + len(READING_PREFIX):].strip()
    fields: dict[str, str] = {}
    for token in message.split():
        key, separator, value = token.partition("=")
        if not separator or not key:
            raise V8Error(f"the reading line carries a token without `key=value`: {token!r}")
        fields[key] = value
    return fields


def apk_sha256(path: Path) -> str:
    """The full lowercase sha256 of one APK, read from disk (the artifact this run installs)."""
    if not path.is_file() or path.stat().st_size == 0:
        raise V8Error(f"the APK is missing or empty: {path}")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _positive_int(fields: dict[str, str], key: str, reasons: list[str]) -> int | None:
    raw = fields.get(key)
    if raw is None:
        return None
    try:
        value = int(raw)
    except ValueError:
        reasons.append(f"{key} is {raw!r}, not an integer")
        return None
    if value <= 0:
        reasons.append(f"{key} is {value}, not positive")
        return None
    return value


def judge_reading(reading: dict[str, str], app_apk_sha256: str) -> list[str]:
    """Every reason this reading is NOT the design's V-8 terminal; an empty list is PASS.

    Each bound comes from the vector itself: the sample size, the frozen page size, the fixed
    group's construction manifest, the case's own settle window, a positive finite upper bound, and
    the app APK the reading was produced from. A missing field is never read as a default.
    """
    reasons: list[str] = []
    for key in REQUIRED_READING_KEYS:
        if key not in reading:
            reasons.append(f"the reading line is missing {key}=")
    if reasons:
        return reasons

    if reading["condition"] != EXPECTED_CONDITION:
        reasons.append(f"the condition is {reading['condition']!r}, not {EXPECTED_CONDITION}")
    fixture = _positive_int(reading, "fixture", reasons)
    if fixture is not None and fixture != EXPECTED_FIXTURE_TRANSACTIONS:
        reasons.append(f"the fixture is {fixture} transactions, not {EXPECTED_FIXTURE_TRANSACTIONS}")
    page_rows = _positive_int(reading, "pageRows", reasons)
    if page_rows is not None and page_rows != EXPECTED_PAGE_ROWS:
        reasons.append(f"the first page carries {page_rows} rows, not the frozen {EXPECTED_PAGE_ROWS}")
    total = _positive_int(reading, "totalCount", reasons)
    if total is not None and total != EXPECTED_TOTAL_COUNT:
        reasons.append(f"the fixed group's total is {total}, not the manifest's {EXPECTED_TOTAL_COUNT}")
    _positive_int(reading, "schema", reasons)
    _positive_int(reading, "sampleIntervalMs", reasons)

    settle = _positive_int(reading, "settleSamples", reasons)
    samples = _positive_int(reading, "samples", reasons)
    if settle is not None and settle != SETTLE_SAMPLES:
        reasons.append(f"the settle window is {settle} samples, not the frozen {SETTLE_SAMPLES}")
    if samples is not None and samples < SETTLE_SAMPLES:
        reasons.append(f"the run took {samples} samples, fewer than the settle window's {SETTLE_SAMPLES}")

    raw_ms = reading["renderFirstPageMs"]
    try:
        render_ms = float(raw_ms)
    except ValueError:
        reasons.append(f"renderFirstPageMs is {raw_ms!r}, not a number")
    else:
        if not math.isfinite(render_ms):
            reasons.append(f"renderFirstPageMs is {raw_ms!r}, not a finite number")
        elif render_ms <= 0:
            reasons.append(f"renderFirstPageMs is {render_ms}, not a positive upper bound")

    raw_apk = reading["apk"]
    if not SHA256_PATTERN.fullmatch(raw_apk):
        reasons.append(f"the reading's apk is {raw_apk!r}, not a lowercase sha256")
    elif raw_apk != app_apk_sha256.lower():
        reasons.append(
            f"the reading was produced by APK {raw_apk}, not the installed app APK {app_apk_sha256.lower()}"
        )
    return reasons


# ------------------------------------------------------------------ the am instrument transcript


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
                # This branch consumes the whole STATUS line, so the raw-marker scan below never
                # sees it: a failure token that only rides a STATUS field is recorded here.
                if key in STATUS_FAILURE_KEYS and key + "=" not in markers:
                    markers.append(key + "=")
            continue
        if line.startswith(STATUS_CODE):
            try:
                value = int(line[len(STATUS_CODE):].strip())
            except ValueError:
                raise V8Error("invalid instrumentation status code") from None
            identity, fields = _identity(fields), {}
            if identity is None:
                if value in TERMINAL_STATUS:
                    raise V8Error("instrumentation failure without a test identity")
                continue
            if value == 1:
                started.append(identity)
            elif value == 0:
                passed.append(identity)
            elif value in TERMINAL_STATUS:
                failed.append((identity, value))
            else:
                raise V8Error(f"unexpected instrumentation status code: {value}")
            continue
        if line.startswith(COMPLETION_CODE):
            try:
                code = int(line[len(COMPLETION_CODE):].strip())
            except ValueError:
                raise V8Error("invalid instrumentation completion code") from None
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
    """Every reason this transcript is NOT a clean single-case pass; an empty list is PASS."""
    reasons: list[str] = []
    if not CASE_PATTERN.fullmatch(case):
        reasons.append(f"{case!r} is not a class#method case")
        return reasons
    try:
        parsed = parse_instrumentation(text)
    except V8Error as error:
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


# ------------------------------------------------------------------ the device session


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
            raise V8Error(f"adb timed out after {bound:.0f}s: {' '.join(args)}") from None
        return (
            result.returncode,
            result.stdout.decode("utf-8", errors="replace"),
            result.stderr.decode("utf-8", errors="replace"),
        )

    def text(self, *args: str, timeout: float = 120.0) -> str:
        code, out, err = self.run(list(args), timeout=timeout)
        if code:
            raise V8Error(f"adb failed ({code}): {' '.join(args)}: {(err or out).strip()[:300]}")
        return out

    def instrument(self, case: str, timeout: float) -> tuple[int, str, str]:
        """Run ONE case: one `-e class` value, the raw `-w -r` transcript returned verbatim."""
        return self.run(
            ["shell", "am", "instrument", "-w", "-r", "-e", "class", case, TEST_RUNNER],
            timeout=timeout,
        )


def install_apks(device: Device, app: Path, test: Path) -> dict:
    """Hash both APKs, stop the app, install them, and prove the runner targets the app."""
    hashes: dict[str, str] = {}
    stopped = False
    for role, apk in (("app", app), ("test", test)):
        hashes[role] = apk_sha256(apk)
        if not stopped:
            # The case seeds the app's own storage BEFORE any activity launch, so no process of the
            # app may be alive when it starts.
            device.text("shell", "am", "force-stop", PACKAGE)
            stopped = True
        output = device.text("install", "-t", str(apk), timeout=INSTALL_TIMEOUT)
        if "Success" not in output or "Failure" in output:
            raise V8Error(f"install of the {role} APK failed: {output.strip()[:300]}")
    listed = device.text("shell", "pm", "list", "instrumentation")
    if f"instrumentation:{TEST_RUNNER} (target={PACKAGE})" not in listed.splitlines():
        raise V8Error("the installed instrumentation does not target the app under test")
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
        raise V8Error(f"cannot read {label} from the device: {text.strip()[:200]!r}")
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
    avd_name: str = DEFAULT_AVD,
    deadline: Deadline | None = None,
    device_factory=None,
    timeout: float = DEFAULT_TIMEOUT,
) -> dict:
    """One device session for the single case; returns (and writes) `host.json`."""
    require_sha(sha)
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
        raise V8Error(f"this runner owns {name or 'an unnamed emulator'}, not {avd_name}")
    hashes = install_apks(device, app, test)
    configuration = collect_configuration(device, name)
    _write(evidence / CONFIG_FILE, json.dumps(configuration, indent=2, sort_keys=True))
    # LOG ORDER IS PART OF THE EVIDENCE: the clear runs AFTER the installs and BEFORE the case, and
    # the export runs AFTER the case, so `logcat.txt` carries THIS run's reading line. A refused
    # clear is best-effort and recorded; a refused export fails the session closed.
    logcat_clear_error: str | None = None
    try:
        device.text("logcat", "-c", timeout=CONFIG_TIMEOUT)
    except Exception as clear_failure:  # noqa: BLE001 - a log-clear hiccup must not abort the run
        logcat_clear_error = f"{type(clear_failure).__name__}: {clear_failure}"
    logcat_error: str | None = None
    logcat_text = ""
    try:
        exit_code, stdout, stderr = device.instrument(CASE, budget.bound(timeout))
    finally:
        try:
            logcat_text = device.text("logcat", "-d", "-v", "threadtime", timeout=CONFIG_TIMEOUT)
            _write(evidence / LOGCAT_FILE, logcat_text)
        except Exception as log_failure:  # noqa: BLE001 - logcat must not mask the first cause
            logcat_error = f"{type(log_failure).__name__}: {log_failure}"
    _write(evidence / TRANSCRIPT_FILE, stdout)
    if stderr.strip():
        _write(evidence / STDERR_FILE, stderr)
    payload = {
        "schema": SCHEMA,
        "sha": sha,
        "case": CASE,
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
    if logcat_clear_error is not None:
        payload["logcat_clear_error"] = logcat_clear_error
    reasons = judge(stdout, CASE)
    if exit_code:
        reasons.insert(0, f"the instrumentation exit code is {exit_code}, not 0")
    if logcat_error is not None:
        payload["logcat_error"] = logcat_error
        reasons.append("the logcat export failed: " + logcat_error)
    else:
        try:
            reading = parse_reading(logcat_text)
        except V8Error as error:
            reasons.append(str(error))
        else:
            reasons.extend(judge_reading(reading, hashes["app"]))
    if reasons:
        payload["reasons"] = reasons
        _write(evidence / HOST_FILE, json.dumps(payload, indent=2, sort_keys=True))
        raise V8Error("the case did not produce a bounded reading: " + "; ".join(reasons))
    payload["status"] = "PASS"
    _write(evidence / HOST_FILE, json.dumps(payload, indent=2, sort_keys=True))
    return payload


def _adb_devices(deadline: Deadline) -> str:
    result = subprocess.run(["adb", "devices"], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=deadline.bound(120.0))
    return result.stdout.decode("utf-8", errors="replace")


def gate_evidence(
    evidence: Path,
    sha: str,
    app: Path | None = None,
    test: Path | None = None,
) -> dict:
    """Re-derive the verdict from the evidence on disk; `host.json` only identifies the run.

    The driver's own status is trusted in ONE direction only: anything but `PASS` makes the run red,
    while a `PASS` claim still has to be proven by the transcript, the reading line, the recorded
    configuration and (when the APK paths are given) the installed artifacts' hashes recomputed here.
    """
    require_sha(sha)
    host_path = evidence / HOST_FILE
    if not host_path.is_file():
        raise V8Error("the session wrote no host.json; nothing to bind this verdict to")
    host = json.loads(host_path.read_text())
    if host.get("sha") != sha:
        raise V8Error(f"the evidence belongs to {host.get('sha')}, not {sha}")
    if host.get("case") != CASE:
        raise V8Error(f"the evidence was produced for {host.get('case')!r}, not {CASE}")
    if host.get("status") != "PASS":
        raise V8Error(
            f"the session's own status is {host.get('status')!r}; a self-reported failure is never "
            "re-judged green"
        )
    transcript_path = evidence / TRANSCRIPT_FILE
    if not transcript_path.is_file():
        raise V8Error("the session wrote no instrumentation transcript; nothing to judge")
    reasons = judge(transcript_path.read_text(), CASE)
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
        if str(configuration.get("avd") or "") != DEFAULT_AVD:
            reasons.append(f"the device's AVD is {configuration.get('avd')!r}, not {DEFAULT_AVD}")
    # The installed artifacts: recomputed from disk when the paths are given, otherwise the hashes
    # the session recorded at install time are the only authority.
    recorded_hashes = host.get("apk_sha256") or {}
    app_hash = str(recorded_hashes.get("app") or "")
    if not SHA256_PATTERN.fullmatch(app_hash):
        reasons.append(f"the session recorded no app APK sha256 (got {app_hash!r})")
    if app is not None:
        try:
            recomputed = apk_sha256(app)
        except V8Error as error:
            reasons.append(str(error))
        else:
            if recomputed != app_hash:
                reasons.append(f"the app APK on disk hashes to {recomputed}, the session installed {app_hash}")
            app_hash = recomputed
    if test is not None:
        try:
            recomputed_test = apk_sha256(test)
        except V8Error as error:
            reasons.append(str(error))
        else:
            if recomputed_test != str(recorded_hashes.get("test") or ""):
                reasons.append(
                    f"the test APK on disk hashes to {recomputed_test}, "
                    f"the session installed {recorded_hashes.get('test')!r}"
                )
    reading: dict[str, str] = {}
    logcat_path = evidence / LOGCAT_FILE
    if not logcat_path.is_file():
        reasons.append("the session wrote no logcat.txt; the reading line cannot be re-derived")
    else:
        try:
            reading = parse_reading(logcat_path.read_text())
        except V8Error as error:
            reasons.append(str(error))
        else:
            reasons.extend(judge_reading(reading, app_hash))
    execution = {
        "api_level": recorded_api,
        "avd": host.get("avd"),
        "case": CASE,
        "serial": host.get("serial"),
        "sha": sha,
    }
    verdict = {
        "verdict": "FAIL" if reasons else "PASS",
        "reasons": reasons,
        "execution": execution,
        "configuration": configuration,
        "apk_sha256": recorded_hashes,
        "reading": reading,
        "started_at": host.get("started_at"),
        "finished_at": host.get("finished_at"),
    }
    _write(evidence / GATE_FILE, json.dumps(verdict, indent=2, sort_keys=True))
    return verdict


def format_verdict(verdict: dict) -> str:
    execution = verdict["execution"]
    lines = [
        f"P8-02 V-8 render gate: {verdict['verdict']}",
        f"  sha={execution['sha']} avd={execution['avd']} serial={execution['serial']} api={execution['api_level']}",
        f"  case={execution['case']}",
    ]
    reading = verdict.get("reading") or {}
    if reading:
        lines.append("  reading=" + " ".join(f"{key}={reading[key]}" for key in sorted(reading)))
    for reason in verdict["reasons"]:
        lines.append(f"  FAIL: {reason}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    import argparse
    import sys

    parser = argparse.ArgumentParser(description="P8-02 V-8 first-result-page render reading on a cloud AVD")
    sub = parser.add_subparsers(dest="action", required=True)

    run = sub.add_parser("run", help="drive the one case inside the emulator session")
    run.add_argument("--app", type=Path, required=True)
    run.add_argument("--test", type=Path, required=True)
    run.add_argument("--evidence", type=Path, required=True)
    run.add_argument("--sha", required=True)
    run.add_argument("--avd-name", default=DEFAULT_AVD)
    run.add_argument("--outer-deadline-epoch", type=float, required=True)

    check = sub.add_parser("gate", help="re-derive the verdict from the evidence on disk")
    check.add_argument("--evidence", type=Path, required=True)
    check.add_argument("--sha", required=True)
    check.add_argument("--app", type=Path, default=None)
    check.add_argument("--test", type=Path, default=None)

    args = parser.parse_args(argv)
    try:
        if args.action == "run":
            require_hosted_runner()
            payload = run_session(
                args.app,
                args.test,
                args.evidence,
                args.sha,
                avd_name=args.avd_name,
                deadline=Deadline(args.outer_deadline_epoch),
            )
            print(f"case {payload['case']} status={payload['status']}")
            return 0
        verdict = gate_evidence(args.evidence, args.sha, args.app, args.test)
        print(format_verdict(verdict))
        return 0 if verdict["verdict"] == "PASS" else 1
    except V8Error as error:
        print(f"P8-02 V-8 gate: {error}", file=sys.stderr)
        return 2
