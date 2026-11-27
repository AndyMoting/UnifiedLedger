#!/usr/bin/env python3
"""Run or gate the four D-369 presentation combinations from one command line.

WHY THIS SCRIPT EXISTS
----------------------
`reactivecircus/android-emulator-runner` executes its `script:` input ONE LINE AT A TIME, each
line as its own `sh -c`, so a multi-line shell loop there is never one program. Run 46054038653
shows the shape: `[command]/usr/bin/sh -c set -u`, `[command]/usr/bin/sh -c failures=0`,
`[command]/usr/bin/sh -c for ui in light dark; do`, then `/usr/bin/sh: 1: Syntax error: end of
file unexpected (expecting "done")`, exit 2 — the loop header was executed alone, failed at once,
and no capture line ever ran, so the four combinations produced no evidence. The same run's
ordinary `run:` gate step executed its identical loop four times, because GitHub runs a `run:`
block as one script.

The four combinations have to stay inside ONE emulator session (one boot, one seed, one build),
and `script:` is the only hook inside that session. So the loop lives here, the workflow calls
this script on a single line, and the combination list has exactly one definition for both the
capture and the gate.

Usage:
    python3 tools/ci/android-presentation-sweep.py run \
        --evidence-root "${EVIDENCE_ROOT}" --sha "${EXPECTED_SHA}" --seed "${SEED_DB}" \
        --avd-name ul-presentation --test-apk <androidTest apk>

    python3 tools/ci/android-presentation-sweep.py gate <the same arguments>

Each combination runs `tools/ci/android-fontscale.py <command>` in its own subprocess with
`--evidence <evidence-root>/<ui_mode>-<font_scale>` and the remaining arguments forwarded
unchanged. Every combination runs even after one fails: four directories of evidence are worth
more than an early exit. The exit code is non-zero whenever any combination's was.

The measured cost is kept because the plan quotes it: each combination is timed with
`time.monotonic()`, the summary table prints every combination's exit code and seconds, and the
same data is written under the evidence root as `sweep.json` for `run` and `sweep-gate.json` for
`gate` (distinct names so a gate can never overwrite the capture record it is judging). The JSON
is also printed, so it can be pasted into the evidence registry verbatim.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, TextIO

# The four combinations of plan section 2.3, in capture order. This is their only definition:
# `run` and `gate` both read it, so the two steps cannot drift apart.
COMBINATIONS: tuple[tuple[str, str], ...] = (
    ("light", "1.0"),
    ("light", "1.3"),
    ("dark", "1.0"),
    ("dark", "1.3"),
)

PROFILE = "presentation"
FONTSCALE_SCRIPT = Path(__file__).resolve().parent / "android-fontscale.py"
RECORD_NAMES = {"run": "sweep.json", "gate": "sweep-gate.json"}

Dispatch = Callable[[list[str]], int]
Clock = Callable[[], float]


@dataclass(frozen=True)
class SweepConfig:
    """One invocation's forwarded arguments; every combination reuses them."""

    command: str
    evidence_root: Path
    sha: str
    seed: str
    avd_name: str
    test_apk: str


def evidence_dir(config: SweepConfig, ui_mode: str, font_scale: str) -> Path:
    """The combination's own evidence directory under the run's root."""
    return config.evidence_root / f"{ui_mode}-{font_scale}"


def fontscale_argv(config: SweepConfig, ui_mode: str, font_scale: str) -> list[str]:
    """The exact `tools/ci/android-fontscale.py` call for one combination."""
    return [
        sys.executable,
        str(FONTSCALE_SCRIPT),
        config.command,
        "--evidence",
        str(evidence_dir(config, ui_mode, font_scale)),
        "--sha",
        config.sha,
        "--profile",
        PROFILE,
        "--seed",
        config.seed,
        "--font-scale",
        font_scale,
        "--ui-mode",
        ui_mode,
        "--avd-name",
        config.avd_name,
        "--test-apk",
        config.test_apk,
    ]


def execute(argv: list[str]) -> int:
    """Run one combination's subprocess and return its exit code; a failure is a code, not a raise."""
    try:
        return subprocess.run(argv, check=False).returncode
    except OSError as error:
        # Fail closed: a combination whose process cannot even start is a failed combination.
        print(f"sweep: cannot start {' '.join(argv[:2])}: {error}", file=sys.stderr)
        return 127


def sweep(
    config: SweepConfig,
    *,
    dispatch: Dispatch | None = None,
    clock: Clock = time.monotonic,
    out: TextIO | None = None,
) -> int:
    """Run every combination, record what each did, and return non-zero on any failure."""
    run_one = execute if dispatch is None else dispatch
    stream = sys.stdout if out is None else out
    rows: list[dict[str, object]] = []
    for ui_mode, font_scale in COMBINATIONS:
        evidence = evidence_dir(config, ui_mode, font_scale)
        started = clock()
        exit_code = run_one(fontscale_argv(config, ui_mode, font_scale))
        seconds = round(clock() - started, 3)
        rows.append(
            {
                "ui_mode": ui_mode,
                "font_scale": font_scale,
                "evidence": str(evidence),
                "exit_code": exit_code,
                "seconds": seconds,
            }
        )
        print(
            f"sweep {config.command}: {ui_mode}-{font_scale} -> exit {exit_code} "
            f"in {seconds:.1f} s",
            file=stream,
        )
    failures = sum(1 for row in rows if row["exit_code"] != 0)
    record = {
        "command": config.command,
        "profile": PROFILE,
        "sha": config.sha,
        "seed": config.seed,
        "combinations": rows,
        "failures": failures,
        "seconds": round(sum(float(row["seconds"]) for row in rows), 3),
    }
    record_path = config.evidence_root / RECORD_NAMES[config.command]
    record_path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(record, ensure_ascii=False, indent=2)
    record_path.write_text(payload + "\n", encoding="utf-8")
    print_summary(rows, config.command, failures, stream)
    print(f"{record_path}:", file=stream)
    print(payload, file=stream)
    return 1 if failures else 0


def print_summary(
    rows: list[dict[str, object]], command: str, failures: int, stream: TextIO
) -> None:
    """One row per combination plus the sentence a reader scans for."""
    print(file=stream)
    print(f"{'combination':<13}{'exit':>5}{'status':>7}{'seconds':>10}", file=stream)
    for row in rows:
        name = f"{row['ui_mode']}-{row['font_scale']}"
        status = "ok" if row["exit_code"] == 0 else "FAIL"
        print(
            f"{name:<13}{row['exit_code']:>5}{status:>7}{float(row['seconds']):>10.1f}",
            file=stream,
        )
    print(f"sweep {command}: {failures} of {len(rows)} combinations failed", file=stream)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("run", "gate"))
    parser.add_argument("--evidence-root", required=True, help="the run's evidence root")
    parser.add_argument("--sha", required=True, help="the 40-character commit SHA being sampled")
    parser.add_argument("--seed", default="", help="the prepared ledger database both steps share")
    parser.add_argument("--avd-name", default="ul-presentation")
    parser.add_argument(
        "--test-apk",
        default="android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk",
        help="the androidTest APK that hosts the D-292 focus probe",
    )
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])
    return sweep(
        SweepConfig(
            command=args.command,
            evidence_root=Path(args.evidence_root),
            sha=args.sha,
            seed=args.seed,
            avd_name=args.avd_name,
            test_apk=args.test_apk,
        )
    )


if __name__ == "__main__":
    raise SystemExit(main())
