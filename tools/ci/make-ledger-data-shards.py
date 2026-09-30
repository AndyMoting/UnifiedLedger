"""Regenerate tools/ci/ledger-data-shards.txt from JUnit XML timings.

The manifest is the single source of truth for which ledger-data jvmTest classes
each runner executes. Balanced greedily (longest first) from measured per-class
seconds, so the three runners finish at roughly the same time.

Usage:
    python tools/ci/make-ledger-data-shards.py \
        --results ledger-data/build/test-results/jvmTest \
        --out tools/ci/ledger-data-shards.txt --shards 3

Regenerate whenever test classes are added, removed or renamed.
verify-shard-coverage.py fails the build when the manifest and the compiled
classes disagree, so a stale manifest cannot silently skip tests.
"""

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET


def collect(results: str) -> list[tuple[float, str]]:
    rows: list[tuple[float, str]] = []
    for path in glob.glob(os.path.join(results, "TEST-*.xml")):
        name = os.path.basename(path)
        fqcn = name[len("TEST-"):-len(".xml")]
        try:
            root = ET.parse(path).getroot()
            seconds = float(root.get("time") or 0)
        except Exception:
            seconds = 0.0
        rows.append((seconds, fqcn))
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--shards", type=int, default=3)
    args = parser.parse_args()

    rows = sorted(collect(args.results), reverse=True)
    if not rows:
        print(f"no TEST-*.xml found under {args.results}", file=sys.stderr)
        return 2

    bins: list[list[str]] = [[] for _ in range(args.shards)]
    loads = [0.0] * args.shards
    for seconds, fqcn in rows:
        index = loads.index(min(loads))
        bins[index].append(fqcn)
        loads[index] += seconds

    with open(args.out, "w", encoding="utf-8", newline="\n") as handle:
        for index, names in enumerate(bins, 1):
            for fqcn in sorted(names):
                handle.write(f"{index}\t{fqcn}\n")

    total = sum(item[0] for item in rows)
    print(f"{len(rows)} classes, {total:.0f} s total")
    for index, names in enumerate(bins, 1):
        print(f"  shard {index}: {len(names)} classes")
    print("wrote", args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
