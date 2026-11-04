"""B6 group-card open gate: drive the product UI on a cloud AVD, then re-derive the verdict.

WHAT ONE GREEN RUN PROVES
-------------------------
`P704GP-D-PERF-01` of spec `docs/specs/2026-11-04-b6-group-card-paged-enumeration-design.md`
section 4: with the deterministic 20k duplicate-group fixture published by the product's own
intake, the group card becomes visible within `--limit-ms` in every round and in the worst of
them. The statistic is max-of-3 (A-PERF spec section 4), never the median: a single slow round
is the failure the gate exists for.

WHAT IT DOES NOT PROVE
----------------------
Nothing about `P704GP-D-SCALE-01` (a small group must not regress), which needs its own fixture,
and nothing about any layer the product does not show on screen: the number is click ->
card-visible read from the accessibility tree by
`ImportScaleTraversalInstrumentedTest#b6CardOpen`, so it is a UI observable and not an internal
timer.

WHY THE HOST IS A CLOUD RUNNER
------------------------------
The development host's adb surface belongs to the user's MuMu instance, so no device chain here
boots a local emulator (same doctrine as `.github/workflows/android-scale.yml`). `run` therefore
refuses to start outside a GitHub-hosted Linux runner, and the workflow asserts the checked-out
commit before it builds, so the evidence is bound to one exact SHA without an APK bundle.

HOW THE TWO SUBCOMMANDS SPLIT
-----------------------------
`run` drives the device and writes raw per-round logs plus `host.json`. `gate` never trusts
`host.json`'s numbers: it re-parses the round logs from disk and re-applies every bound, so a
driver that mis-parsed its own output cannot make the job green.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import time
from pathlib import Path

PACKAGE = "com.unifiedledger.android"
TEST_RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "com.unifiedledger.android.ImportScaleTraversalInstrumentedTest"
TRAVERSAL_LOG = "files/ul-scale-traversal.log"
HOSTED_RUNNER = ("true", "github-hosted", "Linux")

DEFAULT_AVD = "ul-b6-card"
DEFAULT_ROUNDS = 3
DEFAULT_LIMIT_MS = 3000
FIXTURE_RELATIONS = 20_000
SEED_TIMEOUT = 900.0
ROUND_TIMEOUT = 900.0

STAGE_PATTERN = re.compile(
    r"D278STAGE: progress-seen=(?P<progress_seen>true|false) "
    r"click-to-progress-ms=(?P<click_to_progress>null|\d+) "
    r"progress-to-card-ms=(?P<progress_to_card>null|\d+) "
    r"click-to-card-ms=(?P<click_to_card>null|\d+)"
)
OPEN_PATTERN = re.compile(r"group card open=(?P<open>true|false) elapsedMs=(?P<elapsed>\d+)")
REUSE_PATTERN = re.compile(r"b6 fixture reuse: generation=(?P<generation>\d+) relations=(?P<relations>\d+)")
COUNT_PATTERN = re.compile(r"group card itemCount=(?P<item_count>-?\d+|unavailable)")
START_MARKER = "== b6CardOpen start"


class B6Error(Exception):
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
            raise B6Error("the outer deadline is exhausted")
        return min(timeout, left)


def require_hosted_runner(environ: dict | None = None) -> None:
    """Cloud-only: this driver installs into and kills an emulator it does not own."""
    env = os.environ if environ is None else environ
    triple = (env.get("GITHUB_ACTIONS"), env.get("RUNNER_ENVIRONMENT"), env.get("RUNNER_OS"))
    if triple != HOSTED_RUNNER:
        raise B6Error("this driver is cloud-only; a local or user device is never operated by it")


def owned_serial(devices: str) -> str:
    """Exactly one attached emulator: a runner is never shared between AVDs."""
    serials = [
        line.split()[0]
        for line in devices.splitlines()
        if len(line.split()) >= 2 and line.split()[0].startswith("emulator-") and line.split()[1] == "device"
    ]
    if len(serials) != 1:
        raise B6Error(f"exactly one owned emulator must be attached, saw {serials or 'none'}")
    return serials[0]


def parse_avd_name(output: str) -> str:
    """The AVD name in an `adb emu avd name` answer, which ends with the console's own `OK`."""
    lines = [line.strip() for line in output.replace("\r", "").splitlines() if line.strip()]
    if len(lines) == 2 and lines[1] == "OK":
        return "" if lines[0] == "OK" else lines[0]
    if len(lines) == 1 and lines[0] != "OK":
        return lines[0]
    return ""


def _optional_int(value: str) -> int | None:
    return None if value == "null" else int(value)


def parse_round(text: str) -> dict:
    """The staged marks of one `b6CardOpen` round; a missing mark line is a refusal."""
    stage = STAGE_PATTERN.search(text)
    opened = OPEN_PATTERN.search(text)
    if stage is None:
        raise B6Error("the round log carries no D278STAGE line")
    if opened is None:
        raise B6Error("the round log carries no group-card-open line")
    reuse = REUSE_PATTERN.search(text)
    count = COUNT_PATTERN.search(text)
    return {
        "card_open": opened["open"] == "true",
        "click_to_card_ms": _optional_int(stage["click_to_card"]),
        "click_to_progress_ms": _optional_int(stage["click_to_progress"]),
        "progress_seen": stage["progress_seen"] == "true",
        "progress_to_card_ms": _optional_int(stage["progress_to_card"]),
        "fixture_relations": None if reuse is None else int(reuse["relations"]),
        "item_count": None if count is None or count["item_count"] == "unavailable" else int(count["item_count"]),
    }


def slice_last_round(text: str) -> str:
    """The last `b6CardOpen` block of the accumulated traversal log.

    The log is rewritten once per instrumentation run (the test appends its whole evidence list
    in a finally block), so the last start marker is this round's block; reading the whole file
    would mix every earlier round into one verdict.
    """
    index = text.rfind(START_MARKER)
    if index < 0:
        raise B6Error("the traversal log carries no b6CardOpen block; the mode never ran")
    return text[index:]


def judge(rounds: list[dict], expected_rounds: int, limit_ms: int) -> dict:
    """Apply every bound to the parsed rounds and return the verdict, never raising."""
    reasons: list[str] = []
    if len(rounds) != expected_rounds:
        reasons.append(f"{len(rounds)} round log(s) are on disk, {expected_rounds} were requested")
    for index, item in enumerate(rounds, start=1):
        if not item["card_open"]:
            reasons.append(f"round {index}: the group card never became visible")
        if item["click_to_card_ms"] is None:
            reasons.append(f"round {index}: no click-to-card mark was staged")
        relations = item["fixture_relations"]
        if relations is None or relations < FIXTURE_RELATIONS:
            reasons.append(
                f"round {index}: the {FIXTURE_RELATIONS}-relation duplicate-group fixture was not "
                f"reused (relations={relations})"
            )
    values = [item["click_to_card_ms"] for item in rounds if item["click_to_card_ms"] is not None]
    worst = max(values) if values else None
    if worst is not None and worst > limit_ms:
        reasons.append(f"the worst round is {worst} ms, above the {limit_ms} ms bound")
    return {
        "limit_ms": limit_ms,
        "max_click_to_card_ms": worst,
        "reasons": reasons,
        "rounds": rounds,
        "verdict": "FAIL" if reasons or worst is None else "PASS",
    }


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
            raise B6Error(f"adb timed out after {bound:.0f}s: {' '.join(args)}") from None
        return (
            result.returncode,
            result.stdout.decode("utf-8", errors="replace"),
            result.stderr.decode("utf-8", errors="replace"),
        )

    def text(self, *args: str, timeout: float = 120.0) -> str:
        code, out, err = self.run(list(args), timeout=timeout)
        if code:
            raise B6Error(f"adb failed ({code}): {' '.join(args)}: {(err or out).strip()[:300]}")
        return out

    def instrument(self, target: str, timeout: float, extra: tuple[tuple[str, str], ...] = ()) -> tuple[int, str]:
        """Run one `am instrument`; its status is returned, never raised on."""
        args = ["shell", "am", "instrument", "-w", "-r", "-e", "class", target]
        for key, value in extra:
            args += ["-e", key, value]
        code, out, err = self.run([*args, TEST_RUNNER], timeout=timeout)
        return code, out + err

    def read_private(self, relative: str) -> str | None:
        code, out, _ = self.run(["exec-out", "run-as", self.package, "cat", relative])
        return out if code == 0 and out else None


def install_apks(device: Device, app: Path, test: Path) -> dict:
    """Install both APKs, prove the installed runner targets the app, and hash what was sent."""
    hashes: dict[str, str] = {}
    for role, apk in (("app", app), ("test", test)):
        if not apk.is_file() or apk.stat().st_size == 0:
            raise B6Error(f"the {role} APK is missing or empty: {apk}")
        hashes[role] = hashlib.sha256(apk.read_bytes()).hexdigest()
        output = device.text("install", "-t", str(apk), timeout=300.0)
        if "Success" not in output or "Failure" in output:
            raise B6Error(f"install of the {role} APK failed: {output.strip()[:300]}")
    listed = device.text("shell", "pm", "list", "instrumentation")
    if f"instrumentation:{TEST_RUNNER} (target={PACKAGE})" not in listed.splitlines():
        raise B6Error("the installed instrumentation does not target the app under test")
    return hashes


def run_session(
    app: Path,
    test: Path,
    evidence: Path,
    sha: str,
    avd_name: str = DEFAULT_AVD,
    rounds: int = DEFAULT_ROUNDS,
    deadline: Deadline | None = None,
    device_factory=None,
) -> dict:
    """Seed once, then measure `rounds` fresh processes; returns the `host.json` payload."""
    if rounds < 1:
        raise B6Error("at least one round must be requested")
    budget = Deadline(None) if deadline is None else deadline
    evidence.mkdir(parents=True, exist_ok=True)
    if device_factory is None:
        serial = owned_serial(_adb_devices(budget))
        device = Device(serial, budget)
    else:
        device = device_factory(budget)
        serial = device.serial
    name = parse_avd_name(device.text("emu", "avd", "name"))
    if name != avd_name:
        raise B6Error(f"this runner owns {name or 'an unnamed emulator'}, not {avd_name}")
    hashes = install_apks(device, app, test)
    device.text("shell", "am", "force-stop", PACKAGE)
    device.text("shell", "pm", "clear", PACKAGE)
    _, seed_output = device.instrument(f"{TEST_CLASS}#seedFixture", SEED_TIMEOUT)
    (evidence / "seed.txt").write_text(seed_output)
    if "OK (1 test)" not in seed_output:
        raise B6Error("the duplicate-group fixture seed did not pass")
    measured: list[dict] = []
    for index in range(1, rounds + 1):
        device.text("shell", "am", "force-stop", PACKAGE)
        _, output = device.instrument(
            f"{TEST_CLASS}#b6CardOpen", ROUND_TIMEOUT, extra=(("seedFixture", "true"),)
        )
        (evidence / f"round-{index}.stdout.txt").write_text(output)
        raw = device.read_private(TRAVERSAL_LOG) or ""
        block = slice_last_round(raw)
        (evidence / f"round-{index}.log").write_text(block)
        measured.append(parse_round(block))
    payload = {
        "apk_sha256": hashes,
        "avd": name,
        "rounds": measured,
        "serial": serial,
        "sha": sha,
    }
    (evidence / "host.json").write_text(json.dumps(payload, indent=2, sort_keys=True))
    return payload


def _adb_devices(deadline: Deadline) -> str:
    result = subprocess.run(["adb", "devices"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=deadline.bound(120.0))
    return result.stdout.decode("utf-8", errors="replace")


def gate_evidence(evidence: Path, sha: str, expected_rounds: int = DEFAULT_ROUNDS, limit_ms: int = DEFAULT_LIMIT_MS) -> dict:
    """Re-derive the verdict from the round logs on disk; `host.json` only identifies the run."""
    host_path = evidence / "host.json"
    if not host_path.is_file():
        raise B6Error("the session wrote no host.json; nothing to gate")
    host = json.loads(host_path.read_text())
    if host.get("sha") != sha:
        raise B6Error(f"the evidence belongs to {host.get('sha')}, not {sha}")
    paths = sorted(evidence.glob("round-*.log"))
    rounds = [parse_round(path.read_text()) for path in paths]
    verdict = judge(rounds, expected_rounds, limit_ms)
    verdict["apk_sha256"] = host.get("apk_sha256")
    verdict["avd"] = host.get("avd")
    verdict["sha"] = sha
    (evidence / "gate.json").write_text(json.dumps(verdict, indent=2, sort_keys=True))
    return verdict


def format_verdict(verdict: dict) -> str:
    lines = [
        f"B6 group-card open gate: {verdict['verdict']}",
        f"  sha={verdict.get('sha')} avd={verdict.get('avd')} limit={verdict['limit_ms']} ms",
        f"  worst-of-{len(verdict['rounds'])}={verdict['max_click_to_card_ms']} ms",
    ]
    for index, item in enumerate(verdict["rounds"], start=1):
        lines.append(
            f"  round {index}: click-to-card={item['click_to_card_ms']} ms "
            f"click-to-progress={item['click_to_progress_ms']} ms "
            f"progress-to-card={item['progress_to_card_ms']} ms "
            f"items={item['item_count']} relations={item['fixture_relations']} "
            f"cardOpen={item['card_open']}"
        )
    for reason in verdict["reasons"]:
        lines.append(f"  FAIL: {reason}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    import argparse
    import sys

    parser = argparse.ArgumentParser(description="B6 group-card open gate on a cloud AVD")
    sub = parser.add_subparsers(dest="action", required=True)

    run = sub.add_parser("run", help="drive the device session inside the emulator")
    run.add_argument("--app", type=Path, required=True)
    run.add_argument("--test", type=Path, required=True)
    run.add_argument("--evidence", type=Path, required=True)
    run.add_argument("--sha", required=True)
    run.add_argument("--avd-name", default=DEFAULT_AVD)
    run.add_argument("--rounds", type=int, default=DEFAULT_ROUNDS)
    run.add_argument("--outer-deadline-epoch", type=float, required=True)

    check = sub.add_parser("gate", help="re-derive the verdict from the evidence on disk")
    check.add_argument("--evidence", type=Path, required=True)
    check.add_argument("--sha", required=True)
    check.add_argument("--rounds", type=int, default=DEFAULT_ROUNDS)
    check.add_argument("--limit-ms", type=int, default=DEFAULT_LIMIT_MS)

    args = parser.parse_args(argv)
    try:
        if args.action == "run":
            require_hosted_runner()
            payload = run_session(
                args.app, args.test, args.evidence, args.sha,
                avd_name=args.avd_name, rounds=args.rounds, deadline=Deadline(args.outer_deadline_epoch),
            )
            for index, item in enumerate(payload["rounds"], start=1):
                print(f"round {index}: {json.dumps(item, sort_keys=True)}")
            return 0
        verdict = gate_evidence(args.evidence, args.sha, args.rounds, args.limit_ms)
        print(format_verdict(verdict))
        return 0 if verdict["verdict"] == "PASS" else 1
    except B6Error as error:
        print(f"B6 gate: {error}", file=sys.stderr)
        return 2
