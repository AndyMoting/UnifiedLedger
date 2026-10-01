#!/usr/bin/env python3
import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))
from android_scale.fixture import generate_fixture, load_manifest, validate_manifest  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate and validate deterministic Android scale fixtures")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=197198)
    parser.add_argument("--validate", action="store_true")
    args = parser.parse_args()
    if args.validate:
        manifest = load_manifest(args.out / "manifest.json")
    else:
        manifest = generate_fixture(args.out, args.seed)
    validate_manifest(manifest, args.out)
    print(f"fixture PASS candidates={manifest.final_candidates} relations={manifest.final_duplicate_relations}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
