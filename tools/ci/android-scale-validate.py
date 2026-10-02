#!/usr/bin/env python3
"""Independent post-run gate for the maximum-scale long test.

The workflow must not rely solely on the host driver's exit code relayed through
a third-party emulator action. This entry point re-runs the strict reducer on the
collected evidence, re-validates the generated fixture against its own manifest,
and binds the device-reported counters to that manifest. Any mismatch exits
non-zero, so a green job always means the evidence itself was judged clean.
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path
from xml.etree import ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "python"))

from android_scale.fixture import load_manifest, validate_manifest  # noqa: E402
from android_scale.result import cross_check_manifest, validate_evidence  # noqa: E402
from android_scale.preflight import validate_preflight  # noqa: E402

FAILURES = (ValueError, OSError, KeyError, json.JSONDecodeError, ET.ParseError)


def main() -> int:
    parser = argparse.ArgumentParser(description="Strictly validate maximum-scale long-test evidence")
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--mode", choices=("preflight", "maximum"), default="maximum")
    args = parser.parse_args()

    try:
        if args.mode == "preflight":
            validate_preflight(args.evidence, args.sha)
            print("PASS infrastructure preflight (not maximum-scale acceptance)", flush=True)
            return 0
        validate_evidence(args.evidence, args.sha)
    except FAILURES as error:
        print(f"FAIL evidence validation: {type(error).__name__}: {error}", flush=True)
        return 1

    try:
        manifest = load_manifest(args.fixture / "manifest.json")
        validate_manifest(manifest, args.fixture)
        device = json.loads((args.evidence / "device.json").read_text(encoding="utf-8"))
        cross_check_manifest(device, asdict(manifest))
    except FAILURES as error:
        print(f"FAIL manifest cross-check: {type(error).__name__}: {error}", flush=True)
        return 1

    print("PASS strict evidence validation and manifest cross-check", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
