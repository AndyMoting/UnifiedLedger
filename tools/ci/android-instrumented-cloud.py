#!/usr/bin/env python3
"""Thin CLI for the ordinary Android device regression (sharded, plus fork fallback).

    python tools/ci/android-instrumented-cloud.py check \\
        --inventory tools/ci/android-instrumented-inventory.txt \\
        --shards tools/ci/android-instrumented-shards.txt

    python tools/ci/android-instrumented-cloud.py run --shard 1 \\
        --avd ul-instrumented-1 --evidence instrumented-evidence/shard-1 \\
        --app <app.apk> --test <test.apk> --sha <40-hex> \\
        --inventory tools/ci/android-instrumented-inventory.txt \\
        --shards tools/ci/android-instrumented-shards.txt

    python tools/ci/android-instrumented-cloud.py validate --shard 1 \\
        --evidence instrumented-evidence/shard-1 --sha <40-hex> \\
        --inventory tools/ci/android-instrumented-inventory.txt \\
        --shards tools/ci/android-instrumented-shards.txt

    python tools/ci/android-instrumented-cloud.py run-fork \\
        --evidence instrumented-evidence/fork --app <app.apk> --test <test.apk> \\
        --sha <40-hex> --inventory tools/ci/android-instrumented-inventory.txt

    python tools/ci/android-instrumented-cloud.py validate-fork \\
        --evidence instrumented-evidence/fork --sha <40-hex> \\
        --inventory tools/ci/android-instrumented-inventory.txt

`run`/`run-fork` execute inside the emulator action's script and only talk to
adb (no Gradle). `validate`/`validate-fork` are the authoritative gates: they
re-parse the transcript, the JUnit report and the evidence and fail when
anything disagrees.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "python"))

from android_scale import instrumented  # noqa: E402

FAILURES = (ValueError, OSError, KeyError, RuntimeError, subprocess.SubprocessError)


def main() -> int:
    parser = argparse.ArgumentParser()
    actions = parser.add_subparsers(dest="action", required=True)

    check = actions.add_parser("check", help="prove the shard split partitions the inventory")
    check.add_argument("--inventory", type=Path, required=True)
    check.add_argument("--shards", type=Path, required=True)

    run = actions.add_parser("run", help="run one shard's cases on one owned emulator")
    run.add_argument("--shard", type=int, required=True)
    run.add_argument("--avd", required=True)
    run.add_argument("--app", type=Path, required=True)
    run.add_argument("--test", type=Path, required=True)
    run.add_argument("--evidence", type=Path, required=True)
    run.add_argument("--inventory", type=Path, required=True)
    run.add_argument("--shards", type=Path, required=True)
    run.add_argument("--sha", required=True)
    run.add_argument("--timeout", type=float, default=900)

    validate = actions.add_parser("validate", help="independent post-run gate for one shard")
    validate.add_argument("--shard", type=int, required=True)
    validate.add_argument("--evidence", type=Path, required=True)
    validate.add_argument("--inventory", type=Path, required=True)
    validate.add_argument("--shards", type=Path, required=True)
    validate.add_argument("--sha")

    fork = actions.add_parser("run-fork", help="run the whole inventory on one owned emulator (fork pull requests)")
    fork.add_argument("--avd", default=instrumented.FORK_AVD)
    fork.add_argument("--app", type=Path, required=True)
    fork.add_argument("--test", type=Path, required=True)
    fork.add_argument("--evidence", type=Path, required=True)
    fork.add_argument("--inventory", type=Path, required=True)
    fork.add_argument("--sha", required=True)
    fork.add_argument("--timeout", type=float, default=900)

    validate_fork = actions.add_parser("validate-fork", help="independent post-run gate for the fork fallback")
    validate_fork.add_argument("--evidence", type=Path, required=True)
    validate_fork.add_argument("--inventory", type=Path, required=True)
    validate_fork.add_argument("--sha")

    args = parser.parse_args()
    try:
        if args.action == "check":
            plan = instrumented.check_partition(args.inventory, args.shards)
            print("PASS shard manifest partitions the ordinary inventory: " + ", ".join(
                f"shard {shard}={len(cases)}" for shard, cases in sorted(plan.items())), flush=True)
            return 0
        if args.action == "run":
            return instrumented.execute(shard=args.shard, evidence=args.evidence, inventory=args.inventory,
                                        shards=args.shards, app=args.app, test=args.test,
                                        sha=args.sha, avd=args.avd, timeout=args.timeout)
        if args.action == "validate":
            instrumented.validate(shard=args.shard, evidence=args.evidence, inventory=args.inventory,
                                  shards=args.shards, sha=args.sha)
            return 0
        if args.action == "run-fork":
            return instrumented.execute_fork(evidence=args.evidence, inventory=args.inventory,
                                             app=args.app, test=args.test, sha=args.sha,
                                             avd=args.avd, timeout=args.timeout)
        instrumented.validate_fork(evidence=args.evidence, inventory=args.inventory, sha=args.sha)
        return 0
    except FAILURES as error:
        print(f"FAIL {args.action}: {type(error).__name__}: {error}", flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
