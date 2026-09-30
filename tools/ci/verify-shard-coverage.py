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

Mode 3, for the Python shards (static, no test execution):
    every tests/python/test_*.py module must be listed exactly once.

    python tools/ci/verify-shard-coverage.py \
        --python-manifest tools/ci/python-shards.txt --tests-dir tests/python
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


def python_modules(tests_dir: str) -> set[str]:
    base = Path(tests_dir)
    return {f"tests.python.{path.stem}" for path in base.glob("test_*.py")}


def manifest_lines(path: str) -> list[tuple[int, str]]:
    rows: list[tuple[int, str]] = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        shard, name = line.split("\t")
        rows.append((int(shard), name))
    return rows


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
    parser.add_argument("--manifest", default="")
    parser.add_argument("--classes", default="")
    parser.add_argument("--results", default="")
    parser.add_argument("--shard", type=int, default=0)
    parser.add_argument("--python-manifest", default="")
    parser.add_argument("--tests-dir", default="tests/python")
    args = parser.parse_args()

    ok = True
    if args.manifest:
        manifest = load_manifest(args.manifest)
        if not manifest:
            print(f"empty manifest: {args.manifest}", file=sys.stderr)
            return 2
        if args.classes:
            expected = set(manifest)
            ok &= report("compiled classes vs manifest",
                         expected, compiled_classes(args.classes))
        if args.shard and args.results:
            expected = {name for name, shard in manifest.items() if shard == args.shard}
            ok &= report(f"shard {args.shard} executed classes vs manifest",
                         expected, executed_classes(args.results))
    if args.python_manifest:
        rows = manifest_lines(args.python_manifest)
        names = [name for _shard, name in rows]
        duplicates = sorted({name for name in names if names.count(name) > 1})
        ok &= report("tests/python modules vs manifest",
                     set(names), python_modules(args.tests_dir))
        if duplicates:
            print(f"  listed more than once ({len(duplicates)}):")
            for name in duplicates[:20]:
                print("   =", name)
            ok = False

    if not (args.manifest or args.python_manifest):
        parser.error("give --manifest, or --python-manifest")
    if args.manifest and not (args.classes or (args.shard and args.results)):
        parser.error("with --manifest give --classes, or --shard with --results")

    if not ok:
        print("\ncoverage mismatch: the shard manifest and reality disagree.")
        print("Regenerate with tools/ci/make-ledger-data-shards.py (Kotlin)")
        print("or tools/ci/make-python-shards.py (Python).")
        return 1
    print("coverage ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
