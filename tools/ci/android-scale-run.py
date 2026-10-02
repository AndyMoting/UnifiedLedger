#!/usr/bin/env python3
"""Execute the CI-owned emulator chain, without running a build inside the emulator session."""
import argparse
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "python"))
from android_scale.runner import ScaleRunner


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--test", type=Path, required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--mode", choices=("preflight", "maximum"), default="maximum")
    parser.add_argument("--outer-deadline-epoch", type=float, required=True)
    args = parser.parse_args()
    return ScaleRunner(args.fixture, args.evidence, args.app, args.test, args.sha, args.mode,
                       outer_deadline_epoch=args.outer_deadline_epoch).run()


if __name__ == "__main__":
    raise SystemExit(main())
