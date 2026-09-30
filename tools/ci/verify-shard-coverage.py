"""Coverage guard for the ledger-data shard split.

Two modes, both fail loudly when the manifest (tools/ci/ledger-data-shards.txt)
and reality disagree, so a new or renamed test class can never be skipped
silently:

Mode 1, in the main job after the test sources are compiled:
    every compiled jvmTest class must be listed in the manifest.

    python tools/ci/verify-shard-coverage.py \
        --manifest tools/ci/ledger-data-shards.txt \
        --classes ledger-data/build/classes/kotlin/jvm/test

Mode 2, in each shard job after its test task:
    the classes that produced results must equal the classes listed for that shard.

    python tools/ci/verify-shard-coverage.py \
        --manifest tools/ci/ledger-data-shards.txt \
        --shard 2 --results ledger-data/build/test-results/jvmTest
"""

import argparse
import glob
import os
import sys
from pathlib import Path


def load_manifest(path: str) -> dict[str, int]:
    entries: dict[str, int] = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        shard, name = line.split("\t")
        entries[name] = int(shard)
    return entries


def compiled_classes(root: str) -> set[str]:
    base = Path(root)
    found: set[str] = set()
    for path in base.rglob("*.class"):
        stem = path.stem.split("$")[0]
        if not stem.endswith("Test"):
            continue
        relative = path.with_name(stem + ".class").relative_to(base).with_suffix("")
        found.add(".".join(relative.parts))
    return found


def executed_classes(results: str) -> set[str]:
    found: set[str] = set()
    for path in glob.glob(os.path.join(results, "TEST-*.xml")):
        name = os.path.basename(path)
        found.add(name[len("TEST-"):-len(".xml")])
    return found


def report(label: str, expected: set[str], actual: set[str]) -> bool:
    missing = sorted(expected - actual)
    extra = sorted(actual - expected)
    print(f"{label}: expected {len(expected)}, actual {len(actual)}")
    if missing:
        print(f"  not run / not compiled ({len(missing)}):")
        for name in missing[:20]:
            print("   -", name)
    if extra:
        print(f"  unexpected ({len(extra)}):")
        for name in extra[:20]:
            print("   +", name)
    return not missing and not extra


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--classes", default="")
    parser.add_argument("--results", default="")
    parser.add_argument("--shard", type=int, default=0)
    args = parser.parse_args()

    manifest = load_manifest(args.manifest)
    if not manifest:
        print(f"empty manifest: {args.manifest}", file=sys.stderr)
        return 2

    ok = True
    if args.classes:
        expected = set(manifest)
        ok &= report(f"compiled classes vs manifest",
                     expected, compiled_classes(args.classes))
    if args.shard and args.results:
        expected = {name for name, shard in manifest.items() if shard == args.shard}
        ok &= report(f"shard {args.shard} executed classes vs manifest",
                     expected, executed_classes(args.results))
    if not args.classes and not (args.shard and args.results):
        parser.error("give --classes, or --shard with --results")

    if not ok:
        print("\ncoverage mismatch: the shard manifest and reality disagree.")
        print("Regenerate with tools/ci/make-ledger-data-shards.py")
        return 1
    print("coverage ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
