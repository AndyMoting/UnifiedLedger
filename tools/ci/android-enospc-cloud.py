#!/usr/bin/env python3
"""One-AVD cloud run of the import-intake ENOSPC rig (D-276 item 2).

What this drives
----------------
Exactly one instrumented case — ``ImportEnospcInstrumentedTest``, which the
ordinary device regression excludes in both of its exclusion lists — on one
GitHub-hosted emulator, against the trusted whole-tree APK bundle of the exact
commit under test. The case fills the device's ``/data`` until the filesystem
refuses a write, runs the real import-intake commit against the full disk, and
asserts a typed ``ImportIntakeBatchFailure.StorageUnavailable`` plus an intact
ledger (``PRAGMA quick_check`` ok, active pointer and generation set unchanged,
no half-written candidate).

What this is not
----------------
Not the ordinary device regression, not a required PR check, not acceptance for
any P7 vector. A PASS here is the device evidence D-276 item 2 asks the fix
batch to pin; it does not add a vector and does not change the PASS table. The
distinction lives in one sentence, :data:`ENOSPC_SCOPE`, shared by this report
and the workflow's job summary.

Why a dedicated rig instead of the ordinary regression
------------------------------------------------------
While the fill is held, ``/data`` is genuinely full, so every other write on the
device can fail — including another test's report. The case therefore runs
alone, and it SKIPS rather than passes on a device it cannot exhaust.

Why the verdict is not ``am instrument``'s exit code
---------------------------------------------------
``am instrument -w`` exits 0 for a failed or skipped case as well. The single
authority is the strict transcript reducer ``android_scale.result
.instrumentation_pass`` — one case, started once, one clean status pair, the
runner's completion code, and the JUnit completion line. An assumption failure
(the rig's "this host cannot reach ENOSPC" skip) is a red run, never a pass.

Host cleanup boundary
---------------------
The case removes both of its own directories in ``finally``. The host-side
fallback is limited to the rig's own cache fill directories
(``cache/enospc-fill-<digits>``): exact names, re-validated before deletion, no
wildcard, nothing under ``files/``, no other package, and only when the device is
still critically low on space. A failed run additionally stops this exact app
package, and nothing else.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "python"))

from android_scale.apks import PRODUCERS  # noqa: E402  (bundle layout authority: provenance.json)
from android_scale.result import (  # noqa: E402
    crash_present,
    instrumentation_pass,
    strict_json,
    validate_log_collection,
)
from android_scale.runner import (  # noqa: E402
    PACKAGE,
    RUNNER,
    ScaleDeadlineError,
    owned_device_matches,
    owned_serial,
    remaining_seconds,
)

TEST_CLASS = PACKAGE + ".ImportEnospcInstrumentedTest"
TEST_METHOD = "aRealEnospcOnTheIntakeCommitIsTypedAndLeavesTheLedgerIntact"
OPT_IN_ARGUMENT = "ul.enospc"
DEFAULT_AVD = "ul-enospc"
EXPECTED_API = "36"
EXPECTED_ABI = "x86_64"

# Mirror of ImportEnospcInstrumentedTest.MAX_FILL_BYTES (16 GiB). The rig skips —
# never passes — when its fill loop reaches this cap without a refused write, so a
# device that starts with at least this much free space can never produce
# evidence: it would burn minutes and then report a skip. `preflight` therefore
# refuses such a device up front, and the unit test binds this literal to the
# Kotlin source so the two cannot drift.
MAX_FILL_BYTES = 16 * 1024 * 1024 * 1024

# Execution budget and reserves follow the scale chain: the workflow records an
# absolute outer deadline before boot, and this driver keeps diagnostic time plus
# a report/cleanup reserve inside it so no step is ever killed mid-write.
EXECUTION_BUDGET_SECONDS = 1800
DIAGNOSTIC_SECONDS = 120
CLEANUP_RESERVE_SECONDS = 30

# Free-space sampling. Best effort by design: while the disk is full a probe can
# fail, and a failed probe is recorded as a gap instead of failing the run.
SAMPLE_INTERVAL_SECONDS = 15.0
SAMPLE_LIMIT = 240

# "Still critically low" after the run — a leftover fill leaves ~0-1 MiB. The
# bound is far below a healthy device's free space (gigabytes) and far above the
# rig's own 64 KiB near-full tolerance, so the fallback only fires on residue.
RESIDUAL_FILL_FREE_BYTES = 256 * 1024 * 1024

CACHE_FILL_NAME = re.compile(r"enospc-fill-[0-9]+")
DF_ROW = re.compile(r"^\s*(\S+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)%\s+(\S+)\s*$")

# The one sentence that keeps this rig from being read as a regression run.
# The workflow's always-on summary step carries the identical text, and the unit
# test binds the two copies so they cannot drift apart.
ENOSPC_SCOPE = (
    "This is the dedicated real-out-of-space rig for DEF-IMPORT-ENOSPC-CRASH-01 "
    "(D-276 item 2). It is not the ordinary device regression, it is not a "
    "required check, and it carries no general-regression signal; a PASS here "
    "adds no P7 vector and does not change the PASS table."
)


class RigError(RuntimeError):
    """A run-level failure whose message is the operator-facing cause."""


def parse_df(text: str, mount: str = "/data") -> dict:
    """Total/used/available KiB of ``mount`` from ``adb shell df -k`` output.

    Reversed scan with an exact mount match: the header row, ``tmpfs``/``/dev``
    rows and a wrapped long device name can all appear above the row that
    matters, and the last exact match is the authoritative one.
    """
    for line in reversed(text.splitlines()):
        row = DF_ROW.match(line)
        if row is not None and row.group(6) == mount:
            return {
                "filesystem": row.group(1),
                "total_kib": int(row.group(2)),
                "used_kib": int(row.group(3)),
                "available_kib": int(row.group(4)),
                "use_percent": int(row.group(5)),
                "mount": mount,
            }
    raise ValueError("df output carries no " + mount + " row")


def residual_fill_names(listing: str) -> list[str]:
    """Fill directories this rig created, from one ``ls -1 cache`` transcript.

    ``fullmatch`` only: a name is deletable exactly when it is
    ``enospc-fill-<ascii digits>``. A negative ``System.nanoTime()`` would
    produce a second leading dash and is deliberately NOT matched — the fallback
    stays conservative instead of guessing.
    """
    return sorted(line.strip() for line in listing.splitlines() if CACHE_FILL_NAME.fullmatch(line.strip()))


def cleanup_commands(names: list[str]) -> list[list[str]]:
    """``adb`` arguments removing only the rig's own cache fill directories.

    Every name is re-validated, so a listing that carried anything else can never
    turn into a delete. The commands never touch ``files/`` (the redirected
    ledger lives there), never name another package, and contain no wildcard.
    """
    commands = []
    for name in names:
        if not CACHE_FILL_NAME.fullmatch(name):
            raise ValueError("refusing to delete a directory the rig did not create: " + name)
        commands.append(["shell", "run-as", PACKAGE, "rm", "-rf", "cache/" + name])
    return commands


def instrument_argv(serial: str) -> list[str]:
    """The exact documented invocation: one class, opted in, raw and waiting.

    ``-e class`` names the class, not a method, so the runner reports
    ``numtests=1`` for the single ``@Test``; the opt-in argument is the same one
    the case's ``@Before`` requires, and without it the case would be an
    assumption skip — which is a red run here.
    """
    return [
        "adb", "-s", serial, "shell", "am", "instrument", "-w", "-r",
        "-e", OPT_IN_ARGUMENT, "1", "-e", "class", TEST_CLASS, RUNNER,
    ]


def build_summary(report: dict) -> str:
    """The human gate summary: verdict, scope boundary, and the measured facts."""
    status = report.get("status", "ERROR")
    lines = [f"## ENOSPC device rig — {status}", "", ENOSPC_SCOPE, ""]
    lines.append(f"- case: `{TEST_CLASS}#{TEST_METHOD}` (single case, `{OPT_IN_ARGUMENT}=1`)")
    lines.append(f"- SHA under test: `{report.get('sha', '')}`")
    producer = report.get("apk_producer") or {}
    if producer:
        lines.append(
            "- APKs: trusted bundle from run {run} attempt {attempt} of `{workflow}` "
            "(source `{source}`); installed digests match that producer's manifest".format(
                run=producer.get("run_id"), attempt=producer.get("run_attempt"),
                workflow=producer.get("workflow"), source=producer.get("source_sha"),
            )
        )
    hashes = report.get("apk_sha256") or {}
    if hashes:
        lines.append(f"- APK sha256: app `{hashes.get('app', '')}`, test `{hashes.get('test', '')}`")
    disk = report.get("disk") or {}
    before = disk.get("before") or {}
    after = disk.get("after") or {}
    minimum = disk.get("min_available_kib")
    if before:
        lines.append(
            "- device `/data`: {total} KiB total; free before {before} KiB; free after the run {after}; "
            "minimum sampled while the case ran {minimum}".format(
                total=before.get("total_kib"),
                before=before.get("available_kib"),
                after=f"{after.get('available_kib')} KiB" if after else "not sampled",
                minimum=f"{minimum} KiB" if isinstance(minimum, int) else "not sampled",
            )
        )
    lines.append(
        "- strict transcript: one started case, one clean status pair, `INSTRUMENTATION_CODE: -1`, "
        "`OK (1 test)`; any assumption skip or failure is red"
    )
    cleanup = report.get("cleanup") or {}
    if cleanup:
        removed = cleanup.get("removed") or []
        note = cleanup.get("skipped") or (
            "removed only exact `cache/enospc-fill-<digits>` names" if removed
            else "nothing to remove; the case removes its own directories in `finally`"
        )
        noun = "directory" if len(removed) == 1 else "directories"
        lines.append(f"- host cleanup: {len(removed)} rig cache {noun} removed by the narrow fallback; {note}")
    if isinstance(report.get("elapsed_seconds"), (int, float)):
        lines.append(f"- elapsed: {report['elapsed_seconds']} s")
    if status != "PASS":
        lines.extend(["", f"**{report.get('errorType', 'Error')}**: {report.get('errorMessage', 'no detail')}"])
        if report.get("timed_out"):
            lines.append("")
            lines.append("The execution budget expired. A timeout is not a product verdict.")
    return "\n".join(lines) + "\n"


def validate_evidence(directory: Path, sha: str) -> dict:
    """Re-check a frozen evidence directory without a device.

    Every decisive claim is re-derived from an artifact rather than trusted from
    the report: the transcript is parsed again, the logcat collector contract is
    re-validated, and the installed digests are bound to the producer manifest
    copied into the evidence. A directory that cannot support a PASS raises.
    """
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("evidence SHA must be exact full lowercase SHA")
    report = strict_json(directory / "result.json")
    if report.get("mode") != "enospc":
        raise ValueError("not an ENOSPC rig report")
    if report.get("sha") != sha:
        raise ValueError("evidence SHA mismatch")
    if report.get("status") != "PASS":
        raise ValueError(f"rig did not pass: {report.get('status')} {report.get('errorMessage', '')}".strip())
    if report.get("timed_out") is not False:
        raise ValueError("execution budget expired")
    if report.get("crashed") is not False:
        raise ValueError("crash attributed to the app in logcat")
    instrumented = report.get("instrumented_test") or {}
    if instrumented.get("class") != TEST_CLASS or instrumented.get("method") != TEST_METHOD:
        raise ValueError("report names a different instrumented case")
    if instrumented.get("skipped") is not False or (
            type(instrumented.get("numtests")) is not int) or instrumented["numtests"] != 1:
        raise ValueError("report does not claim exactly one executed case")
    instrumentation_pass(
        (directory / "instrumentation-enospc.txt").read_text(encoding="utf-8", errors="replace"),
        TEST_CLASS,
        TEST_METHOD,
    )
    validate_log_collection(directory)
    logcat = directory / "logcat.txt"
    if not logcat.is_file() or logcat.stat().st_size == 0:
        raise ValueError("missing logcat evidence")
    if crash_present(logcat.read_text(encoding="utf-8", errors="replace")):
        raise ValueError("crash marker attributed to the app in logcat")
    disk = strict_json(directory / "disk.json")
    before = disk.get("before") or {}
    if type(before.get("available_kib")) is not int:
        raise ValueError("disk evidence lacks the pre-run free space")
    if before["available_kib"] * 1024 >= MAX_FILL_BYTES:
        raise ValueError("device had enough free space to skip the rig; a skip is never a pass")
    hashes = report.get("apk_sha256") or {}
    if set(hashes) != {"app", "test"} or any(
        not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value) for value in hashes.values()
    ):
        raise ValueError("installed APK digests missing")
    provenance = strict_json(directory / "apk-provenance.json")
    if provenance.get("sha256") != hashes:
        raise ValueError("installed APK digests do not match the producer manifest")
    if provenance.get("workflow") not in PRODUCERS or type(provenance.get("schema")) is not int or provenance["schema"] != 1:
        raise ValueError("producer manifest is not a trusted bundle")
    if not re.fullmatch(r"[0-9a-f]{40}", str(provenance.get("tree", ""))):
        raise ValueError("producer manifest carries no tree identity")
    return report


class EnospcRig:
    """One cloud AVD, one opted-in ENOSPC case, one evidence directory."""

    def __init__(self, app: Path, test: Path, evidence: Path, sha: str, *,
                 outer_deadline_epoch: float | None = None, avd_name: str = DEFAULT_AVD,
                 sample_interval: float = SAMPLE_INTERVAL_SECONDS):
        # Same CI-only guard as the scale chain: this rig never drives a device
        # the operator did not boot on a hosted runner, so the host adb surface
        # that the user's MuMu/ALas automation shares is never touched. There is
        # deliberately no local opt-in channel in this batch.
        environment = (os.environ.get("GITHUB_ACTIONS"), os.environ.get("RUNNER_ENVIRONMENT"), os.environ.get("RUNNER_OS"))
        if environment != ("true", "github-hosted", "Linux"):
            raise ValueError("this rig is CI-only; local devices are forbidden")
        if not re.fullmatch(r"[0-9a-f]{40}", sha):
            raise ValueError("full lowercase SHA required")
        self.app, self.test, self.evidence, self.sha = app, test, evidence, sha
        self.avd_name = avd_name
        self.sample_interval = sample_interval
        self.serial: str | None = None
        self.started = time.monotonic()
        if outer_deadline_epoch is None:
            raise ValueError("--outer-deadline-epoch is required: the boot consumes the same budget")
        if not isinstance(outer_deadline_epoch, (int, float)) or not math.isfinite(outer_deadline_epoch):
            raise ValueError("outer deadline must be a finite epoch")
        self.outer_deadline = self.started + float(outer_deadline_epoch) - time.time()
        self.deadline = min(self.started + EXECUTION_BUDGET_SECONDS,
                            self.outer_deadline - DIAGNOSTIC_SECONDS - CLEANUP_RESERVE_SECONDS)
        self.cleanup_deadline = self.deadline
        self.report = {
            "schema": 1,
            "mode": "enospc",
            "rig": "import-intake-enospc",
            "scope": ENOSPC_SCOPE,
            "sha": sha,
            "status": "ERROR",
            "timed_out": False,
            "crashed": False,
            "setup_complete": False,
            "instrumented_test": {
                "class": TEST_CLASS,
                "method": TEST_METHOD,
                "opt_in": f"{OPT_IN_ARGUMENT}=1",
                "numtests": None,
                "skipped": None,
            },
            "apk_sha256": {},
            "apk_producer": {},
            "disk": {},
            "cleanup": {},
            "config": {},
        }
        self.log_stop = threading.Event()
        self.log_thread: threading.Thread | None = None
        self.log_process: subprocess.Popen | None = None
        self.log_started = False
        self.log_gaps: list[dict] = []
        self.sample_stop = threading.Event()
        self.sample_thread: threading.Thread | None = None
        self.samples: list[dict] = []
        self.sample_gaps: list[dict] = []
        evidence.mkdir(parents=True, exist_ok=False)

    # ------------------------------------------------------------------ device I/O

    def command(self, argv: list[str], *, timeout: float = 60.0, deadline: float | None = None) -> str:
        bound = remaining_seconds(deadline or self.deadline, time.monotonic(), timeout)
        result = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=bound)
        if result.returncode:
            detail = (result.stderr or result.stdout).decode("utf-8", errors="replace").strip()
            raise RigError(f"command failed: {argv[0]} ({result.returncode}): {detail[:400]}")
        return result.stdout.decode("utf-8", errors="replace")

    def adb(self, *args: str, **kwargs) -> str:
        prefix = ["adb"] + (["-s", self.serial] if self.serial else [])
        return self.command(prefix + list(args), **kwargs)

    def configure(self) -> None:
        devices = self.command(["adb", "devices"], timeout=30)
        matches = owned_device_matches(devices)
        if len(matches) != 1:
            raise RigError("exactly one owned CI emulator is required")
        candidate = matches[0]
        # Identity is the only permitted query before ownership is established;
        # `emu avd name` is the console query a user-owned MuMu VM refuses.
        avd = self.command(["adb", "-s", candidate, "emu", "avd", "name"], timeout=30)
        self.serial = owned_serial(devices, avd, self.avd_name)
        self.adb("wait-for-device", timeout=60)
        self.start_logcat()
        config = {
            "api": self.adb("shell", "getprop", "ro.build.version.sdk").strip(),
            "abi": self.adb("shell", "getprop", "ro.product.cpu.abi").strip(),
            "fingerprint": self.adb("shell", "getprop", "ro.build.fingerprint").strip(),
            "serial": self.serial,
        }
        if config["api"] != EXPECTED_API or config["abi"] != EXPECTED_ABI:
            raise RigError(
                f"device is API {config['api']}/{config['abi']}, the rig is pinned to "
                f"API {EXPECTED_API}/{EXPECTED_ABI}"
            )
        self.report["config"] = config
        (self.evidence / "configuration.txt").write_text(
            "\n".join(f"{key}={value}" for key, value in config.items()) + "\n",
            encoding="utf-8",
        )

    def install(self) -> None:
        provenance = self.app.parent / "provenance.json"
        if not provenance.is_file():
            raise RigError("the bundle carries no provenance.json; this rig never builds its own APKs")
        manifest = strict_json(provenance)
        if set(manifest.get("sha256") or {}) != {"app", "test"}:
            raise RigError("producer manifest is incomplete")
        for role, apk in (("app", self.app), ("test", self.test)):
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            if digest != manifest["sha256"][role]:
                raise RigError(f"APK bytes do not match the producer manifest: {role}")
            self.report["apk_sha256"][role] = digest
        self.report["apk_producer"] = {key: manifest.get(key) for key in
                                       ("repository", "run_id", "run_attempt", "source_sha", "workflow")}
        shutil.copyfile(provenance, self.evidence / "apk-provenance.json")
        for role, apk in (("app", self.app), ("test", self.test)):
            output = self.adb("install", "-t", str(apk), timeout=180)
            # `adb install` exits 0 even when the install fails; the verdict is in
            # the output, so a silent failure must not reach the case as a
            # confusing "instrumentation not found".
            if not re.search(r"(?m)^Success\s*$", output) or "Failure" in output:
                raise RigError(f"install of the {role} APK failed: {output.strip()[:400]}")
        listed = self.adb("shell", "pm", "list", "instrumentation")
        if f"instrumentation:{RUNNER} (target={PACKAGE})" not in listed.splitlines():
            raise RigError("the installed test runner does not target the app")
        for package in (PACKAGE, PACKAGE + ".test"):
            if not self.adb("shell", "pm", "path", "--user", "0", package).strip().startswith("package:"):
                raise RigError(f"installed package is not available: {package}")

    def disk(self, deadline: float | None = None) -> dict:
        return parse_df(self.adb("shell", "df", "-k", "/data", deadline=deadline))

    def preflight(self) -> None:
        """Refuse a device the rig cannot exhaust, before minutes of filling."""
        before = self.disk()
        self.report["disk"] = {"before": before}
        free = before["available_kib"] * 1024
        if free >= MAX_FILL_BYTES:
            raise RigError(
                f"the device leaves {free} bytes free on /data, at or above the rig's "
                f"{MAX_FILL_BYTES}-byte fill cap: the case could only SKIP. Pin a smaller data "
                "partition (the workflow passes -partition-size) and dispatch again."
            )

    # ------------------------------------------------------------------ collectors

    def start_logcat(self) -> None:
        """Streaming log across the fill; every reconnect is recorded as a gap."""
        def collect() -> None:
            try:
                with (self.evidence / "logcat.txt").open("ab") as output, \
                        (self.evidence / "logcat-stderr.txt").open("ab") as errors:
                    while not self.log_stop.is_set():
                        try:
                            self.log_process = subprocess.Popen(
                                ["adb", "-s", self.serial, "logcat", "-v", "threadtime"],
                                stdout=output, stderr=errors)
                            self.log_started = True
                            while self.log_process.poll() is None and not self.log_stop.wait(0.2):
                                pass
                            if self.log_stop.is_set():
                                break
                            self.log_gaps.append({"elapsed_seconds": time.monotonic() - self.started,
                                                  "reason": "logcat disconnected; ring-buffer replay on reconnect"})
                        except OSError as error:
                            self.log_gaps.append({"elapsed_seconds": time.monotonic() - self.started,
                                                  "reason": type(error).__name__})
                        self.log_stop.wait(1)
            finally:
                if self.log_process is not None and self.log_process.poll() is None:
                    self.log_process.kill()  # this exact adb client only; never the shared server
                    self.log_process.wait(timeout=5)
        self.log_thread = threading.Thread(target=collect, daemon=True)
        self.log_thread.start()

    def stop_logcat(self) -> None:
        self.log_stop.set()
        if self.log_thread is not None:
            if self.log_process is not None and self.log_process.poll() is None:
                self.log_process.kill()
            self.log_thread.join(timeout=10)
            if self.log_thread.is_alive():
                self.log_gaps.append({"reason": "collector did not stop within ten seconds"})
        path = self.evidence / "logcat.txt"
        collection = {
            "started": self.log_started,
            "stream_bytes": path.stat().st_size if path.exists() else 0,
            "stopped": self.log_thread is not None and not self.log_thread.is_alive(),
            "gaps": self.log_gaps,
        }
        (self.evidence / "logcat-collection.json").write_text(json.dumps(collection), encoding="utf-8")

    def start_sampler(self) -> None:
        """Sample /data while the case runs, so a full disk is observable host-side."""
        def sample() -> None:
            while not self.sample_stop.wait(self.sample_interval) and len(self.samples) < SAMPLE_LIMIT:
                try:
                    row = self.disk(deadline=self.deadline)
                except (RigError, ValueError, ScaleDeadlineError, subprocess.TimeoutExpired, OSError) as error:
                    self.sample_gaps.append({"elapsed_seconds": time.monotonic() - self.started,
                                             "reason": type(error).__name__})
                    continue
                self.samples.append({"elapsed_seconds": round(time.monotonic() - self.started, 3),
                                     "available_kib": row["available_kib"]})
        self.sample_thread = threading.Thread(target=sample, daemon=True)
        self.sample_thread.start()

    def stop_sampler(self) -> None:
        self.sample_stop.set()
        if self.sample_thread is not None:
            self.sample_thread.join(timeout=10)
        minimum = min((entry["available_kib"] for entry in self.samples), default=None)
        self.report["disk"].update({"samples": self.samples, "gaps": self.sample_gaps,
                                    "min_available_kib": minimum})

    # ------------------------------------------------------------------ the run

    def instrument(self) -> None:
        self.adb("shell", "am", "force-stop", PACKAGE)
        transcript = self.evidence / "instrumentation-enospc.txt"
        started = time.monotonic()
        with transcript.open("wb") as output:
            process = subprocess.Popen(instrument_argv(self.serial), stdout=output, stderr=subprocess.STDOUT)
            try:
                while process.poll() is None:
                    time.sleep(1.0)
                    remaining_seconds(self.deadline, time.monotonic())
            finally:
                if process.poll() is None:
                    process.kill()  # this exact adb client only
                    process.wait(timeout=10)
        self.report["instrumented_seconds"] = round(time.monotonic() - started, 3)
        if process.returncode:
            raise RigError(f"`am instrument` shell exited {process.returncode}; see instrumentation-enospc.txt")
        text = transcript.read_text(encoding="utf-8", errors="replace")
        instrumentation_pass(text, TEST_CLASS, TEST_METHOD)
        self.report["instrumented_test"].update({"numtests": 1, "skipped": False})

    def cleanup(self, *, failure: bool) -> None:
        """Narrow fallback for a fill the case could not remove itself.

        The case deletes both directories in ``finally``, so a normal run has
        nothing to do here. When it does fire, it removes only exact
        ``cache/enospc-fill-<digits>`` names, after a free-space probe proves the
        device is still critically low; a healthy device is left alone.
        """
        record: dict = {"removed": [], "failed": [], "skipped": None}
        self.report["cleanup"] = record
        self.cleanup_deadline = min(time.monotonic() + DIAGNOSTIC_SECONDS,
                                    self.outer_deadline - CLEANUP_RESERVE_SECONDS)
        if failure and self.serial:
            try:
                self.adb("shell", "am", "force-stop", PACKAGE, deadline=self.cleanup_deadline, timeout=15)
            except (RigError, ScaleDeadlineError, subprocess.TimeoutExpired, OSError) as error:
                record["failed"].append(f"force-stop: {type(error).__name__}")
        if not self.serial:
            record["skipped"] = "no owned device was established"
            return
        try:
            free = self.disk(deadline=self.cleanup_deadline)
        except (RigError, ValueError, ScaleDeadlineError, subprocess.TimeoutExpired, OSError) as error:
            record["skipped"] = f"the device did not answer df: {type(error).__name__}"
            return
        record["free_kib_after_run"] = free["available_kib"]
        # The post-run reading is the evidence "after": it shows whether the fill
        # really came back, independent of the case's own teardown claim.
        self.report["disk"]["after"] = free
        if free["available_kib"] * 1024 > RESIDUAL_FILL_FREE_BYTES:
            record["skipped"] = "the device is not critically low on space; nothing to remove"
            return
        try:
            listing = self.adb("shell", "run-as", PACKAGE, "ls", "-1", "cache",
                               deadline=self.cleanup_deadline, timeout=30)
        except (RigError, ScaleDeadlineError, subprocess.TimeoutExpired, OSError) as error:
            record["skipped"] = f"the app cache could not be listed: {type(error).__name__}"
            return
        names = residual_fill_names(listing)
        record["listed"] = names
        for argv in cleanup_commands(names):
            try:
                self.adb(*argv, deadline=self.cleanup_deadline, timeout=120)
                record["removed"].append(argv[-1])
            except (RigError, ScaleDeadlineError, subprocess.TimeoutExpired, OSError) as error:
                record["failed"].append(f"{argv[-1]}: {type(error).__name__}")
        try:
            record["free_kib_after_removal"] = self.disk(deadline=self.cleanup_deadline)["available_kib"]
        except (RigError, ValueError, ScaleDeadlineError, subprocess.TimeoutExpired, OSError):
            record["free_kib_after_removal"] = None

    def write_reports(self) -> None:
        self.report["elapsed_seconds"] = round(time.monotonic() - self.started, 3)
        (self.evidence / "disk.json").write_text(json.dumps(self.report["disk"], indent=2), encoding="utf-8")
        (self.evidence / "result.json").write_text(json.dumps(self.report, indent=2), encoding="utf-8")
        (self.evidence / "summary.md").write_text(build_summary(self.report), encoding="utf-8")

    def run(self) -> int:
        try:
            remaining_seconds(self.deadline, time.monotonic())
            self.configure()
            self.install()
            self.preflight()
            self.report["setup_complete"] = True
            self.start_sampler()
            self.instrument()
            self.report["status"] = "PASS"
        except (Exception, KeyboardInterrupt) as error:  # noqa: BLE001 — every failure must reach the evidence
            self.report["errorType"] = type(error).__name__
            self.report["errorMessage"] = str(error)[:800]
            self.report["status"] = "ERROR"
            if isinstance(error, ScaleDeadlineError):
                self.report["timed_out"] = True
        finally:
            self.stop_sampler()
            self.stop_logcat()
            try:
                self.cleanup(failure=self.report["status"] != "PASS")
            except (Exception, KeyboardInterrupt) as error:  # noqa: BLE001 — cleanup must never mask the first cause
                self.report.setdefault("cleanup", {})["failed"] = [f"cleanup aborted: {type(error).__name__}"]
            logcat = self.evidence / "logcat.txt"
            if logcat.is_file():
                ours = crash_present(logcat.read_text(encoding="utf-8", errors="replace"))
                self.report["crashed"] = bool(ours)
                if ours:
                    self.report["crash_markers"] = ours[:10]
                    if self.report["status"] == "PASS":
                        self.report["status"] = "FAIL"
                        self.report["errorType"] = "CrashAttributedToApp"
                        self.report["errorMessage"] = "the app logged a crash marker while the rig ran"
            self.write_reports()
        try:
            validate_evidence(self.evidence, self.sha)
        except (ValueError, OSError, KeyError) as error:
            if self.report["status"] == "PASS":
                self.report["status"] = "FAIL"
            self.report["validationError"] = str(error)[:800]
            self.write_reports()
            return 1
        return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run or re-validate the ENOSPC device rig evidence.")
    subparsers = parser.add_subparsers(dest="action", required=True)
    runner = subparsers.add_parser("run", help="drive one cloud AVD and write evidence")
    runner.add_argument("--app", type=Path, required=True)
    runner.add_argument("--test", type=Path, required=True)
    runner.add_argument("--evidence", type=Path, required=True)
    runner.add_argument("--sha", required=True)
    runner.add_argument("--outer-deadline-epoch", type=float, required=True)
    runner.add_argument("--avd-name", default=DEFAULT_AVD)
    validator = subparsers.add_parser("validate", help="re-check frozen evidence without a device")
    validator.add_argument("--evidence", type=Path, required=True)
    validator.add_argument("--sha", required=True)
    arguments = parser.parse_args(argv)
    if arguments.action == "run":
        try:
            rig = EnospcRig(arguments.app, arguments.test, arguments.evidence, arguments.sha,
                            outer_deadline_epoch=arguments.outer_deadline_epoch,
                            avd_name=arguments.avd_name)
        except (ValueError, OSError) as error:
            print(f"FAIL ENOSPC rig could not start: {error}", file=sys.stderr)
            return 1
        return rig.run()
    try:
        report = validate_evidence(arguments.evidence, arguments.sha)
    except (ValueError, OSError, KeyError) as error:
        print(f"FAIL ENOSPC evidence: {error}", file=sys.stderr)
        return 1
    print(f"PASS ENOSPC evidence: {report['instrumented_test']['class']}#{report['instrumented_test']['method']} "
          f"on sha {report['sha']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
