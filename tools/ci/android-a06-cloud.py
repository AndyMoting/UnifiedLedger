#!/usr/bin/env python3
"""P706-A06 nineteen-boundary process-kill matrix on cloud AVD shards.

WHAT THIS COVERS, EXACTLY
-------------------------
P706-A06 (D-246; spec `docs/specs/2026-10-24-p706-a02-a06-acceptance-design.md`
sections 3.3/3.4) asks for evidence at every persistent-state boundary of the
stable-storage switch, upgrade, recovery and startup paths. This script drives
the two-phase protocol those tests implement:

  * run A (kill leg) - `P706A06BoundaryKillInstrumentedTest#bNN...` drives the
    REAL production flow until the armed boundary parks, writes the marker, and
    calls `Process.killProcess(Process.myPid())`. The process death IS the
    phenomenon under test, so `am instrument` terminates abnormally here and a
    non-zero shell status is the EXPECTED outcome, never a verdict.
  * run B (verify leg) - `P706A06BoundaryVerifyInstrumentedTest#vNN...` reads
    the marker strictly and drives the REAL production startup over the disk the
    kill left behind.

A green run therefore proves **process kill** convergence at 19 boundaries. It
does NOT prove anything about real power loss: process death preserves the page
cache and the platform has no directory fsync, and the spec registers real power
loss as a separate leg a managed AVD cannot exercise. Every report therefore
stamps `a06_verdict: not-passed` so a green matrix can never be read as an A06
pass.

WHY THE SHELL STATUS IS NOT THE GATE
------------------------------------
Three independent signals must agree per boundary, and `check-shard` re-derives
all of them from disk in a step that runs outside the emulator session:

  1. the instrumentation transcript shows exactly the requested class#method
     started with `numtests=1` and no failure record;
  2. the marker file inside the app-private stable root names exactly this
     boundary (a foreign or duplicated marker is a failure), and the paired
     verify leg removes it;
  3. logcat carries the tag lines this boundary's park and deliberate kill emit,
     and no ART or native crash signature - a `FATAL EXCEPTION` or a tombstone
     would mean the process died of a defect rather than of the kill under test.

SELECTION DISCIPLINE
--------------------
One boundary per instrumentation start, one AVD per runner: the tests kill their
own process inside every method, so a class-wide run would execute exactly one
boundary. Each shard therefore starts two instrumentation runs per boundary
(19 boundaries over 4 shards = 38 starts), and `plan` is the single place that
maps a boundary to its two exact class#method targets.

The APK bundle is not built here: the workflow reuses a producer-attested
whole-tree bundle for the exact SHA through `tools/ci/android-apks.py reuse` and
refuses to run without one.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

TOOLS_PYTHON = Path(__file__).resolve().parents[1] / "python"
if str(TOOLS_PYTHON) not in sys.path:
    sys.path.insert(0, str(TOOLS_PYTHON))

from android_scale.result import PACKAGE, instrumentation_pass, strict_json  # noqa: E402

SCHEMA = 1

KILL_CLASS = "com.unifiedledger.android.P706A06BoundaryKillInstrumentedTest"
VERIFY_CLASS = "com.unifiedledger.android.P706A06BoundaryVerifyInstrumentedTest"
TEST_PACKAGE = PACKAGE + ".test"
TEST_RUNNER = TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"

# The two constants the suite itself resolves (P706A06BoundaryFixturePayloads and
# P706A06BoundaryKillInstrumentedTest.RUN_A_LOG_NAME). They must stay literal here
# because the device files are read back over `run-as` by path.
STABLE_ROOT = "cache/p706a06-boundary-root"
MARKER_FILE = "p706a06-boundary-marker"
RUN_A_LOG = "p706a06-run-a.log"
LOG_TAG = "P706A06Boundary"
MARKER_HEADER = "p706a06-boundary-marker-v1"

CONVERGENCES = (
    "OPENS_OLD_GENERATION",
    "OPENS_NEW_GENERATION",
    "POINTER_MISSING_THEN_ADOPT",
    "POINTER_MISSING_THEN_ADOPT_FRESH",
    "POINTER_MISSING_THEN_DISCARD_RE_UPGRADE",
)

# Hosted-runner triple. This script installs into and kills an emulator process, so
# it refuses to run anywhere else: a local or user device is never operated by it.
HOSTED_RUNNER = ("true", "github-hosted", "Linux")

PROCESS_KILL_SCOPE = {
    "covers": "process-kill-only",
    "power_loss": "not-covered",
    "a06_verdict": "not-passed",
    "statement": (
        "Nineteen-boundary process-kill convergence only. Process death preserves the page cache and "
        "the Android file system has no directory fsync, so this evidence neither exercises nor implies "
        "real power loss; P706-A06 stays non-PASS (spec sections 3.2/3.6)."
    ),
}

# A deliberate SIGKILL leaves none of these; a Java or native crash does. That is why
# the crash signature - not the non-zero shell status - separates "the kill we asked
# for" from "the process died of a defect". `INSTRUMENTATION_ABORTED` is deliberately
# absent: the system reports an aborted instrumentation as the expected consequence
# of a killed instrumented process.
DEATH_DENYLIST = re.compile(
    r"FATAL EXCEPTION|Fatal signal|\*\*\* \*\*\* \*\*\*|OutOfMemoryError|ANR in " + re.escape(PACKAGE)
)

JUNIT_FAILURE = re.compile(r"FAILURES!!!|junit\.framework\.|AssertionFailedError")


class A06Error(RuntimeError):
    """A defect this script reports as a named failure instead of a traceback."""


class A06DeadlineError(A06Error):
    """The outer budget is exhausted; remaining device work is abandoned."""


class BoundaryRow:
    """One matrix row: the boundary id and the two exact instrumented methods.

    A plain class, not a dataclass: the driver is also loaded by path from the
    offline test suite, and a dataclass needs its module registered in
    `sys.modules` before the decorator can resolve annotations.
    """

    __slots__ = ("boundary", "kill_method", "verify_method")

    def __init__(self, boundary: str, kill_method: str, verify_method: str) -> None:
        self.boundary = boundary
        self.kill_method = kill_method
        self.verify_method = verify_method

    @property
    def kill_target(self) -> str:
        return KILL_CLASS + "#" + self.kill_method

    @property
    def verify_target(self) -> str:
        return VERIFY_CLASS + "#" + self.verify_method


# Spec section 3.1's nineteen rows in order; methods are the @Test names of the two
# suites. `tests/python/test_android_ci_repair.py` re-derives them from the Kotlin
# sources, so a rename on either side fails a required check instead of silently
# running nothing.
BOUNDARIES = (
    BoundaryRow("B1", "b01PreStageDeleteKillsMidDeletion", "v01PreStageDeleteMidKillConvergesToTheOldGeneration"),
    BoundaryRow("B2", "b02MainFileCopyKillsAfterTheCopy", "v02MainFileCopyMidKillLeavesTheStagedGenerationInert"),
    BoundaryRow("B3", "b03SidecarFsyncSequenceKillsAfterTheGenerationFsync",
                "v03FsyncSequenceMidKillLeavesTheStagedGenerationInert"),
    BoundaryRow("B4", "b04PreparedJournalKillsAfterItsDurableWrite", "v04PreparedJournalRollsBackAtStartup"),
    BoundaryRow("B5", "b05PointerPublishKillsAfterThePublish", "v05PublishedPointerPreparedJournalRollsBackAtStartup"),
    BoundaryRow("B6", "b06SwitchedJournalKillsAfterItsDurableWrite", "v06SwitchedJournalRollsBackAtStartup"),
    BoundaryRow("B7", "b07StepEightReopenKillsDuringTheReopenGate", "v07ReopenWindowSwitchedJournalRollsBackAtStartup"),
    BoundaryRow("B8", "b08JournalRemovalKillsAfterTheDurableDelete",
                "v08RemovedJournalCommitsTheSwitchOnRestart"),
    BoundaryRow("B9", "b09EscapeRepairKillsMidRepairDeletion",
                "v09KilledEscapeRepairConvergesByJournalAndPointerState"),
    BoundaryRow("B10", "b10LegacyUpgradeKillsAfterTheStageFsync", "v10LegacyUpgradeStageKillConvergesThroughAdoption"),
    BoundaryRow("B11", "b11LegacyUpgradeKillsBeforeThePointerPublish",
                "v11LegacyUpgradePrePublishWindowKillConvergesThroughAdoption"),
    BoundaryRow("B12", "b12UpgradePublishKillsAfterThePublish",
                "v12UpgradePublishKilledAfterPublishSelectsTheNewGeneration"),
    BoundaryRow("B13", "b13LegacyRemovalKillsAfterTheMainDelete",
                "v13LegacyRemovalMidKillKeepsTheNewGenerationAuthoritative"),
    BoundaryRow("B14", "b14FreshInstallKillsBeforeThePointerPublish",
                "v14FreshInstallPrePublishKillStaysFailClosedUntilAdoption"),
    BoundaryRow("B15", "b15AdoptionKillsAfterThePublish", "v15AdoptionPublishKilledAfterPublishOpensTheCandidate"),
    BoundaryRow("B16", "b16DiscardKillsMidCandidateDeletion", "v16DiscardMidKillStaysFailClosedUntilReUpgrade"),
    BoundaryRow("B17", "b17StartupRecoveryKillsAfterTheOldPointerRepublish",
                "v17RecoveryRepublishKilledMidRecoveryReRunsAtStartup"),
    BoundaryRow("B18", "b18StartupRecoveryKillsAfterTheJournalDelete",
                "v18RecoveryJournalDeleteKilledKeepsTheRecoveredState"),
    BoundaryRow("B19", "b19StartupRecoveryKillsMidStagedDeletion",
                "v19RecoveryDeletionKilledMidDeletionLeavesTheRemnantInert"),
)

# The four shard ranges (5/5/5/4) are this batch's own split for four parallel
# runners; spec section 3.4's matrix defines the nineteen boundary rows, not a shard
# layout. Each shard job owns one AVD and exactly this range, so no runner ever hosts
# two emulators.
SHARDS = {
    "1": tuple(row.boundary for row in BOUNDARIES[0:5]),
    "2": tuple(row.boundary for row in BOUNDARIES[5:10]),
    "3": tuple(row.boundary for row in BOUNDARIES[10:15]),
    "4": tuple(row.boundary for row in BOUNDARIES[15:19]),
}

BY_BOUNDARY = {row.boundary: row for row in BOUNDARIES}

# Every shard owns exactly one AVD, named for its shard, and the persisted shard
# record is checked against that name - so two shards can never share, or silently
# swap, one emulator.
AVD_PREFIX = "ul-a06-s"


def expected_avd(shard: str) -> str:
    """The one AVD name this shard may run on."""
    if shard not in SHARDS:
        raise A06Error(f"unknown shard {shard!r}; expected one of {sorted(SHARDS)}")
    return AVD_PREFIX + shard


# --------------------------------------------------------------------------- plan


def shard_boundaries(shard: str) -> tuple[BoundaryRow, ...]:
    """The rows one shard job owns; an unknown shard is a caller defect, not a skip."""
    if shard not in SHARDS:
        raise A06Error(f"unknown shard {shard!r}; expected one of {sorted(SHARDS)}")
    return tuple(BY_BOUNDARY[name] for name in SHARDS[shard])


def plan(shard: str) -> list[dict]:
    """The machine-readable work order for one shard (boundary -> exact targets)."""
    return [
        {"boundary": row.boundary, "kill": row.kill_target, "verify": row.verify_target}
        for row in shard_boundaries(shard)
    ]


# ------------------------------------------------------- instrumentation transcripts


def instrumentation_records(transcript: str) -> list[tuple[int, dict]]:
    """Parse `am instrument -r` status pairs exactly as the strict reducer does."""
    records: list[tuple[int, dict]] = []
    status: dict = {}
    for line in transcript.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            field = line[len("INSTRUMENTATION_STATUS: "):]
            if "=" in field:
                key, value = field.split("=", 1)
                status[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            raw = line.split(": ", 1)[1].strip()
            try:
                records.append((int(raw), status))
            except ValueError:
                raise A06Error(f"unparsable instrumentation status code: {raw!r}") from None
            status = {}
    return records


def started_test(transcript: str, test_class: str, test_method: str) -> dict:
    """Require exactly one started record for exactly this class#method and count 1."""
    records = instrumentation_records(transcript)
    if not records:
        raise A06Error("instrumentation transcript carries no status records at all")
    failures = [code for code, _ in records if code != 1]
    if failures:
        raise A06Error(
            f"instrumentation reported a non-start status {failures}: the case failed instead of parking"
        )
    starts = [(code, fields) for code, fields in records if "test" in fields or "class" in fields]
    if len(starts) != 1:
        raise A06Error(f"instrumentation must start exactly one test, saw {len(starts)}")
    _, fields = starts[0]
    if fields.get("class") != test_class or fields.get("test") != test_method:
        raise A06Error(
            "instrumentation ran the wrong case: "
            f"{fields.get('class', '-')}#{fields.get('test', '-')} instead of {test_class}#{test_method}"
        )
    if fields.get("numtests") != "1":
        raise A06Error(f"instrumentation ran more than one test (numtests={fields.get('numtests')!r})")
    return fields


# ---------------------------------------------------------------- marker / run A log


def parse_marker(text: str, expected: str) -> dict:
    """Strict marker read: header, unique keys, required fields, exact boundary."""
    lines = text.strip().splitlines()
    if not lines or lines[0] != MARKER_HEADER:
        raise A06Error(f"marker header missing or wrong: {lines[0] if lines else '<empty>'!r}")
    fields: dict[str, str] = {}
    for line in lines[1:]:
        index = line.find("=")
        if index <= 0:
            raise A06Error(f"corrupt marker line (expected key=value): {line!r}")
        key = line[:index]
        if key in fields:
            raise A06Error(f"marker repeats the key {key!r}: a duplicated marker is not evidence")
        fields[key] = line[index + 1:]
    required = {"boundary", "convergence", "authoritativePayload", "legacyPayload", "legacySha256",
                "authoritativeSha256"}
    missing = required - fields.keys()
    if missing:
        raise A06Error(f"marker is missing required fields: {sorted(missing)}")
    if fields["boundary"] != expected:
        raise A06Error(
            f"foreign or stale marker: names {fields['boundary']!r} but this run armed {expected}"
        )
    if fields["convergence"] not in CONVERGENCES:
        raise A06Error(f"marker names an unknown convergence {fields['convergence']!r}")
    if not re.fullmatch(r"[0-9a-f]{64}", fields["authoritativeSha256"]):
        raise A06Error("marker authoritative fingerprint is not a sha256 hex digest")
    legacy = fields["legacySha256"]
    if legacy != "-" and not re.fullmatch(r"[0-9a-f]{64}", legacy):
        raise A06Error("marker legacy fingerprint is neither absent nor a sha256 hex digest")
    return fields


def parse_run_a_log(text: str, expected: str) -> dict:
    """Run A's own note: which boundary parked and on which decorated call."""
    fields: dict[str, str] = {}
    for line in text.strip().splitlines():
        index = line.find("=")
        if index > 0:
            fields[line[:index]] = line[index + 1:]
    if fields.get("boundary") != expected:
        raise A06Error(f"run A log names {fields.get('boundary')!r}, not the armed {expected}")
    if not fields.get("parkedOn"):
        raise A06Error("run A log carries no parked-on call; the park was never observed")
    return fields


def parked_on(logcat: str, boundary: str) -> str:
    """The decorated call the flow parked on, read back from the tag line."""
    prefix = f"I {LOG_TAG}: parked at {boundary} on "
    for line in logcat.splitlines():
        index = line.find(prefix)
        if index >= 0:
            return line[index + len(prefix):].strip()
    return "-"


def crash_signatures(logcat: str) -> list[str]:
    """The crash markers a deliberate kill cannot produce."""
    return sorted({match.group(0) for match in DEATH_DENYLIST.finditer(logcat)})


def read_optional(path: Path) -> str | None:
    """A persisted artifact's text, or None when the kill left nothing behind."""
    return path.read_text(encoding="utf-8") if path.is_file() else None


# ------------------------------------------------------------------- leg validators


def validate_kill_leg(
    *,
    row: BoundaryRow,
    transcript: str,
    logcat: str,
    marker: str | None,
    run_a_log: str | None,
) -> dict:
    """Run A's acceptance: the armed boundary parked, wrote its marker, and died.

    A non-zero `am instrument` status is not consulted on purpose - the kill ends
    the run. The positive proofs (transcript identity, marker, tag lines) and the
    crash exclusion decide.
    """
    started_test(transcript, KILL_CLASS, row.kill_method)
    if JUNIT_FAILURE.search(transcript) or JUNIT_FAILURE.search(logcat):
        raise A06Error("the kill leg failed its own assertions before reaching the kill")
    if marker is None:
        raise A06Error(f"no marker file at {STABLE_ROOT}/{MARKER_FILE}: the killed process left no evidence")
    if run_a_log is None:
        raise A06Error(f"no run A log at {STABLE_ROOT}/{RUN_A_LOG}: the park was not recorded")
    fields = parse_marker(marker, row.boundary)
    parse_run_a_log(run_a_log, row.boundary)
    if parked_on(logcat, row.boundary) == "-":
        raise A06Error(f"logcat carries no park line for {row.boundary}")
    kill = f"run A marker written for {row.boundary}; killing the process"
    if not re.search(r"I " + re.escape(LOG_TAG) + r": " + re.escape(kill), logcat):
        raise A06Error(
            f"logcat carries no kill line for {row.boundary}: the process died without the deliberate kill"
        )
    denials = crash_signatures(logcat)
    if denials:
        raise A06Error(f"the kill leg shows a real crash signature ({denials}), not the deliberate kill")
    return {"status": "PASS", "method": row.kill_target, "convergence": fields["convergence"],
            "parkedOn": parked_on(logcat, row.boundary)}


def validate_verify_leg(*, row: BoundaryRow, transcript: str, marker: str | None) -> dict:
    """Run B's acceptance: one clean passing case, and the pair consumed its marker.

    `instrumentation_pass` is the same strict reducer the maximum-scale chain uses:
    it refuses a missing, extra, skipped or failed case. A marker that survives a
    passing verify leg means the pair did not clean up - the next boundary could then
    read a stale marker, so that is a failure.
    """
    instrumentation_pass(transcript, VERIFY_CLASS, row.verify_method)
    if marker is not None:
        raise A06Error(
            f"the verify leg passed but left {STABLE_ROOT}/{MARKER_FILE} in place; "
            "a surviving marker makes the next boundary's evidence ambiguous"
        )
    return {"status": "PASS", "method": row.verify_target}


# ------------------------------------------------------------------------------- adb


class Deadline:
    """One monotonic budget converted once from the workflow's absolute deadline.

    Both clocks are injectable so the offline tests can prove the arithmetic and the
    clamp without waiting on the wall clock.
    """

    def __init__(self, outer_deadline_epoch: float | None, *, clock=None, epoch_clock=None):
        self.clock = clock or time.monotonic
        start = self.clock()
        wall = (epoch_clock or time.time)()
        self.started = start
        self.deadline = None if outer_deadline_epoch is None else start + outer_deadline_epoch - wall

    def remaining(self) -> float:
        if self.deadline is None:
            return float("inf")
        return self.deadline - self.clock()

    def bound(self, timeout: float) -> float:
        """The smaller of a step's own limit and what is left of the outer budget."""
        left = self.remaining()
        if left <= 0:
            raise A06DeadlineError("the outer deadline is exhausted")
        return min(timeout, left)


def require_hosted_runner(environ: dict | None = None) -> None:
    """This script is cloud-only: it installs into and kills an emulator process."""
    env = os.environ if environ is None else environ
    triple = (env.get("GITHUB_ACTIONS"), env.get("RUNNER_ENVIRONMENT"), env.get("RUNNER_OS"))
    if triple != HOSTED_RUNNER:
        raise A06Error("this matrix driver is cloud-only; a local or user device is never operated by it")


def owned_serial(devices: str) -> str:
    """Exactly one attached emulator; a runner is never shared between AVDs."""
    serials = []
    for line in devices.splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[0].startswith("emulator-") and fields[1] == "device":
            serials.append(fields[0])
    if len(serials) != 1:
        raise A06Error(
            f"exactly one owned emulator must be attached, saw {serials or 'none'}: "
            "this matrix refuses to share a runner between AVDs"
        )
    return serials[0]


def parse_avd_name(output: str) -> str:
    """The AVD name in an `adb -s <serial> emu avd name` answer.

    The emulator console terminates its answer with its own `OK` line
    (`<name>\nOK\n`), and the host adds a CR that text-mode translation can double -
    the same transcript the maximum-scale driver normalizes before comparing. Anything
    other than "one name line, optionally followed by OK" yields an empty string, so the
    caller fails with the raw transcript instead of guessing which line was the name.
    """
    lines = [line.strip() for line in output.replace("\r", "").splitlines() if line.strip()]
    if len(lines) == 2 and lines[1] == "OK":
        return lines[0]
    if len(lines) == 1 and lines[0] != "OK":
        return lines[0]
    return ""


class Device:
    """The only place this script talks to adb; every call is bounded by a deadline."""

    def __init__(self, serial: str, deadline: Deadline, package: str = PACKAGE):
        self.serial = serial
        self.deadline = deadline
        self.package = package

    def run(self, args: list[str], *, timeout: float = 120) -> tuple[int, str, str]:
        command = ["adb", "-s", self.serial, *args]
        bound = self.deadline.bound(timeout)
        try:
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=bound)
        except subprocess.TimeoutExpired:
            raise A06Error(f"adb timed out after {bound:.0f}s: {' '.join(args)}") from None
        return (result.returncode,
                result.stdout.decode("utf-8", errors="replace"),
                result.stderr.decode("utf-8", errors="replace"))

    def text(self, *args: str, timeout: float = 120, best_effort: bool = False) -> str:
        code, out, err = self.run(list(args), timeout=timeout)
        if code and not best_effort:
            raise A06Error(f"adb failed ({code}): {' '.join(args)}: {(err or out).strip()[:300]}")
        return out

    def instrument(self, target: str, timeout: float) -> tuple[int, str]:
        """Run one `am instrument`; its status is returned, never raised on."""
        code, out, err = self.run(["shell", "am", "instrument", "-w", "-r", "-e", "class", target,
                                   TEST_RUNNER], timeout=timeout)
        return code, out + err

    def read_private(self, relative: str) -> str | None:
        """Read one file from the app-private tree, or None when it is absent."""
        code, out, _ = self.run(["exec-out", "run-as", self.package, "cat", relative], timeout=120)
        return out if code == 0 and out else None


# --------------------------------------------------------------------------- driver


def install_apks(device: Device, app: Path, test: Path) -> dict:
    """Install both APKs and prove the runner targets the app under test."""
    hashes = {}
    for role, apk in (("app", app), ("test", test)):
        if not apk.is_file() or apk.stat().st_size == 0:
            raise A06Error(f"the {role} APK is missing or empty: {apk}")
        hashes[role] = hashlib.sha256(apk.read_bytes()).hexdigest()
        output = device.text("install", "-t", str(apk), timeout=300)
        if "Success" not in output or "Failure" in output:
            raise A06Error(f"install of the {role} APK failed: {output.strip()[:300]}")
    listed = device.text("shell", "pm", "list", "instrumentation")
    if f"instrumentation:{TEST_RUNNER} (target={PACKAGE})" not in listed.splitlines():
        raise A06Error("the installed instrumentation does not target the app under test")
    for package in (PACKAGE, TEST_PACKAGE):
        if not device.text("shell", "pm", "path", "--user", "0", package).strip().startswith("package:"):
            raise A06Error(f"the installed package is not available: {package}")
    return hashes


def run_boundary(device: Device, row: BoundaryRow, directory: Path, *,
                 kill_timeout: float = 300, verify_timeout: float = 300) -> dict:
    """One paired boundary: clean, kill leg, read the marker, verify leg, re-read."""
    directory.mkdir(parents=True, exist_ok=True)
    record = {"schema": SCHEMA, "boundary": row.boundary, "kill": {"status": "ERROR"},
              "verify": {"status": "ERROR"}}

    # A previous boundary's verify leg deletes the root itself; this force-stop and
    # removal also cover a pair that died halfway, so the marker read below can only
    # ever see this boundary's own file.
    device.text("shell", "am", "force-stop", PACKAGE, best_effort=True)
    device.text("shell", "run-as", PACKAGE, "rm", "-rf", STABLE_ROOT, best_effort=True)
    device.text("logcat", "-c", best_effort=True)

    status, transcript = device.instrument(row.kill_target, kill_timeout)
    (directory / "kill-transcript.txt").write_text(transcript, encoding="utf-8")
    logcat = device.text("logcat", "-d", "-v", "threadtime", best_effort=True)
    (directory / "kill-logcat.txt").write_text(logcat, encoding="utf-8")
    marker = device.read_private(STABLE_ROOT + "/" + MARKER_FILE)
    if marker is not None:
        (directory / "marker.txt").write_text(marker, encoding="utf-8")
    run_a_log = device.read_private(STABLE_ROOT + "/" + RUN_A_LOG)
    if run_a_log is not None:
        (directory / "run-a.log").write_text(run_a_log, encoding="utf-8")
    record["kill"] = validate_kill_leg(row=row, transcript=transcript, logcat=logcat,
                                       marker=marker, run_a_log=run_a_log)
    record["kill"]["instrumentationStatus"] = status

    device.text("logcat", "-c", best_effort=True)
    status, transcript = device.instrument(row.verify_target, verify_timeout)
    (directory / "verify-transcript.txt").write_text(transcript, encoding="utf-8")
    (directory / "verify-logcat.txt").write_text(
        device.text("logcat", "-d", "-v", "threadtime", best_effort=True), encoding="utf-8")
    if status != 0:
        raise A06Error(f"the verify leg's instrumentation shell reported {status} (a passing case reports 0)")
    surviving = device.read_private(STABLE_ROOT + "/" + MARKER_FILE)
    record["verify"] = validate_verify_leg(row=row, transcript=transcript, marker=surviving)
    (directory / "result.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    return record


def drive_shard(shard: str, app: Path, test: Path, evidence: Path, sha: str, avd_name: str,
                deadline: Deadline) -> int:
    """The emulator-session entry point: one AVD, this shard's boundaries, one pair each."""
    require_hosted_runner()
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise A06Error("expected SHA must be the exact 40-character lowercase commit SHA")
    if avd_name != expected_avd(shard):
        raise A06Error(
            f"shard {shard} must run on its own AVD {expected_avd(shard)!r}, not {avd_name!r}"
        )
    rows = shard_boundaries(shard)
    directory = evidence / f"shard-{shard}"
    directory.mkdir(parents=True, exist_ok=True)
    devices = subprocess.run(["adb", "devices"], stdout=subprocess.PIPE, timeout=60)
    serial = owned_serial(devices.stdout.decode("utf-8", errors="replace"))
    probe = subprocess.run(["adb", "-s", serial, "emu", "avd", "name"], stdout=subprocess.PIPE,
                           timeout=60).stdout.decode("utf-8", errors="replace")
    reported = parse_avd_name(probe)
    if reported != avd_name:
        raise A06Error(
            f"the attached emulator reports AVD {reported!r}, not the requested {avd_name!r} "
            f"(raw answer {probe.strip()!r})"
        )

    device = Device(serial, deadline)
    apk_hashes: dict = {}
    started = time.monotonic()
    completed: list[str] = []
    report = {
        "schema": SCHEMA,
        "shard": shard,
        "sha": sha,
        "avd": avd_name,
        "serial": serial,
        "boundaries": [row.boundary for row in rows],
        "completed": completed,
        "apk_sha256": apk_hashes,
        "elapsed_seconds": 0.0,
        "scope": PROCESS_KILL_SCOPE,
    }
    try:
        apk_hashes.update(install_apks(device, app, test))
        for row in rows:
            run_boundary(device, row, directory / row.boundary)
            completed.append(row.boundary)
            print(f"{row.boundary}: kill PASS, verify PASS", flush=True)
    finally:
        # Always leave a shard record, even when a boundary pair failed: the gate then
        # reports which boundaries the shard reached instead of only "file missing".
        report["elapsed_seconds"] = round(time.monotonic() - started, 3)
        (directory / "shard.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"shard {shard} completed {len(completed)} of {len(rows)} boundary pairs", flush=True)
    return 0


def check_shard(evidence: Path, shard: str, sha: str) -> dict:
    """Re-derive one shard's verdict from disk; independent of the emulator step's status."""
    if shard not in SHARDS:
        raise A06Error(f"unknown shard {shard!r}; expected one of {sorted(SHARDS)}")
    directory = evidence / f"shard-{shard}"
    report = strict_json(directory / "shard.json")
    if report.get("shard") != shard:
        raise A06Error(f"shard evidence {directory} names shard {report.get('shard')!r}")
    if report.get("sha") != sha:
        raise A06Error(f"shard {shard} evidence is bound to {report.get('sha')!r}, not {sha}")
    expected = list(SHARDS[shard])
    if report.get("boundaries") != expected:
        raise A06Error(f"shard {shard} claims {report.get('boundaries')}, expected {expected}")
    if report.get("completed") != expected:
        raise A06Error(f"shard {shard} completed {report.get('completed')}, expected {expected}")
    if set(report.get("apk_sha256", {})) != {"app", "test"}:
        raise A06Error(f"shard {shard} carries no APK digests")
    if report.get("avd") != expected_avd(shard):
        raise A06Error(
            f"shard {shard} ran on AVD {report.get('avd')!r}, expected {expected_avd(shard)!r}: "
            "a shard owns exactly one AVD"
        )
    for row in shard_boundaries(shard):
        folder = directory / row.boundary
        record = strict_json(folder / "result.json")
        if record.get("boundary") != row.boundary:
            raise A06Error(f"{row.boundary}: result file names {record.get('boundary')!r}")
        for leg in ("kill", "verify"):
            if record.get(leg, {}).get("status") != "PASS":
                raise A06Error(f"{row.boundary}: the {leg} leg is not PASS")
        if record["kill"].get("method") != row.kill_target or record["verify"].get("method") != row.verify_target:
            raise A06Error(f"{row.boundary}: recorded targets do not match the plan")
        for name in ("kill-transcript.txt", "kill-logcat.txt", "verify-transcript.txt", "verify-logcat.txt"):
            if not (folder / name).is_file():
                raise A06Error(f"{row.boundary}: required evidence is absent: {name}")
        # Re-derive both legs from the raw artifacts: the verdict file is a claim, while
        # the marker, the run A log, the transcript and the logcat are the evidence. A
        # row whose marker or run A log went missing is red here, never "PASS by record".
        kill = validate_kill_leg(
            row=row,
            transcript=(folder / "kill-transcript.txt").read_text(encoding="utf-8"),
            logcat=(folder / "kill-logcat.txt").read_text(encoding="utf-8"),
            marker=read_optional(folder / "marker.txt"),
            run_a_log=read_optional(folder / "run-a.log"),
        )
        verify = validate_verify_leg(
            row=row,
            transcript=(folder / "verify-transcript.txt").read_text(encoding="utf-8"),
            marker=None,
        )
        if record["kill"].get("convergence") != kill["convergence"]:
            raise A06Error(
                f"{row.boundary}: the verdict file claims convergence "
                f"{record['kill'].get('convergence')!r} but the on-disk marker says {kill['convergence']!r}"
            )
        if record["kill"].get("parkedOn") != kill["parkedOn"]:
            raise A06Error(f"{row.boundary}: the recorded park disagrees with the kill logcat")
        if record["verify"].get("method") != verify["method"]:
            raise A06Error(f"{row.boundary}: the recorded verify target disagrees with the transcript")
    return report


def producer_manifest(directory: Path | None) -> dict | None:
    """The producer-attested bundle manifest that travelled with the APKs.

    This only reports what the bundle says and binds it to the requested SHA; the
    authoritative re-verification happened in `android-apks.py verify` in the device
    job, before any instrumentation ran.
    """
    if directory is None or not (directory / "provenance.json").is_file():
        return None
    manifest = strict_json(directory / "provenance.json")
    if set(manifest.get("sha256", {})) != {"app", "test"}:
        raise A06Error("the APK provenance manifest is incomplete")
    for key in ("source_sha", "tree"):
        if not isinstance(manifest.get(key), str) or not re.fullmatch(r"[0-9a-f]{40}", manifest[key]):
            raise A06Error(f"the APK provenance manifest carries an invalid {key}")
    return {"attested": True, "producer": manifest.get("workflow"), "source_sha": manifest.get("source_sha"),
            "tree": manifest.get("tree"), "run_id": manifest.get("run_id"),
            "run_attempt": manifest.get("run_attempt"), "sha256": manifest["sha256"]}


def require_producer_bundle(directory: Path, sha: str) -> dict:
    """Require a producer bundle that attests THIS commit, and say which one it is.

    `android-apks.py reuse` matches a bundle by whole-tree identity and has already
    re-verified the producer (trusted workflow, successful job, GitHub commit/tree
    binding, both digests). A tree match still admits one mismatch: a different commit
    with an identical tree is bound to another `source_sha`. Checking it here - in the
    APK job, before any shard starts - fails in a minute instead of after four shards
    finish, and keeps one criterion for the early gate and the final gate.
    """
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise A06Error("expected SHA must be the exact 40-character lowercase commit SHA")
    manifest = producer_manifest(directory)
    if manifest is None:
        raise A06Error(f"no producer provenance manifest under {directory}")
    if manifest["source_sha"] != sha:
        raise A06Error(
            f"the reusable bundle was produced from commit {manifest['source_sha']} (same tree), "
            f"not from the requested {sha}: dispatch the producer workflow for {sha}, then "
            "dispatch this matrix again"
        )
    return manifest


def validate_gate(evidence: Path, sha: str, apks: Path | None = None) -> dict:
    """All nineteen boundaries, once each, both legs PASS; anything less is a failure."""
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise A06Error("expected SHA must be the exact 40-character lowercase commit SHA")
    present = sorted(path.name for path in evidence.glob("shard-*") if path.is_dir())
    expected = [f"shard-{shard}" for shard in sorted(SHARDS)]
    if present != expected:
        raise A06Error(f"shard evidence is incomplete: found {present}, expected {expected}")
    executed: list[str] = []
    shard_reports = []
    for shard in sorted(SHARDS):
        report = check_shard(evidence, shard, sha)
        shard_reports.append({"shard": shard, "avd": report.get("avd"),
                              "boundaries": report["boundaries"],
                              "elapsed_seconds": report.get("elapsed_seconds")})
        executed.extend(report["boundaries"])
    # The gate states completeness itself instead of trusting four shard claims: the
    # concatenation must equal the whole matrix, in order and exactly once, so a
    # dropped boundary and a boundary claimed twice both fail here.
    expected_matrix = [row.boundary for row in BOUNDARIES]
    if executed != expected_matrix:
        raise A06Error(f"the nineteen boundaries were not executed exactly once: {executed}")
    manifest = require_producer_bundle(apks, sha) if apks is not None else None
    return {
        "schema": SCHEMA,
        "sha": sha,
        "boundaries_executed": expected_matrix,
        "boundary_count": len(executed),
        "shards": shard_reports,
        "apk": manifest,
        "scope": PROCESS_KILL_SCOPE,
    }


# ------------------------------------------------------------------------------- cli


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="P706-A06 process-kill boundary matrix (cloud AVD shards)")
    sub = parser.add_subparsers(dest="action", required=True)

    plan_parser = sub.add_parser("plan", help="print one shard's boundary work order")
    plan_parser.add_argument("--shard", required=True)
    plan_parser.add_argument("--json", action="store_true")

    bundle = sub.add_parser("check-bundle", help="prove the reused bundle attests this exact commit")
    bundle.add_argument("--directory", type=Path, required=True)
    bundle.add_argument("--sha", required=True)

    run = sub.add_parser("run", help="drive one shard inside the emulator session")
    run.add_argument("--shard", required=True)
    run.add_argument("--app", type=Path, required=True)
    run.add_argument("--test", type=Path, required=True)
    run.add_argument("--evidence", type=Path, required=True)
    run.add_argument("--sha", required=True)
    run.add_argument("--avd-name", required=True)
    run.add_argument("--outer-deadline-epoch", type=float, required=True)

    check = sub.add_parser("check-shard", help="re-derive one shard's verdict from disk")
    check.add_argument("--evidence", type=Path, required=True)
    check.add_argument("--shard", required=True)
    check.add_argument("--sha", required=True)

    gate = sub.add_parser("gate", help="the nineteen-boundary gate over every shard's evidence")
    gate.add_argument("--evidence", type=Path, required=True)
    gate.add_argument("--sha", required=True)
    gate.add_argument("--apks", type=Path)

    args = parser.parse_args(argv)
    try:
        if args.action == "plan":
            rows = plan(args.shard)
            if args.json:
                print(json.dumps(rows, indent=2))
            else:
                for row in rows:
                    print(f"{row['boundary']} {row['kill']} {row['verify']}")
            return 0
        if args.action == "check-bundle":
            manifest = require_producer_bundle(args.directory, args.sha)
            print(f"PASS producer bundle attests {manifest['source_sha']} (tree {manifest['tree']})",
                  flush=True)
            return 0
        if args.action == "run":
            return drive_shard(args.shard, args.app, args.test, args.evidence, args.sha, args.avd_name,
                               Deadline(args.outer_deadline_epoch))
        if args.action == "check-shard":
            check_shard(args.evidence, args.shard, args.sha)
            print(f"PASS shard {args.shard}: every boundary pair is complete", flush=True)
            return 0
        if args.action == "gate":
            report = validate_gate(args.evidence, args.sha, args.apks)
            (args.evidence / "gate.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
            for row in BOUNDARIES:
                print(f"{row.boundary}: kill PASS, verify PASS", flush=True)
            print(f"PASS {report['boundary_count']}/19 boundary pairs "
                  f"({PROCESS_KILL_SCOPE['covers']}; {PROCESS_KILL_SCOPE['a06_verdict']})", flush=True)
            return 0
    except A06DeadlineError as error:
        print(f"FAIL deadline: {error}", flush=True)
        return 1
    except A06Error as error:
        print(f"FAIL {args.action}: {error}", flush=True)
        return 1
    except (OSError, ValueError, KeyError) as error:
        print(f"FAIL {args.action}: {type(error).__name__}: {error}", flush=True)
        return 1
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
