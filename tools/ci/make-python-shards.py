"""Regenerate tools/ci/python-shards.txt from measured per-module durations.

Balances the test modules greedily (longest first) across N shards so the shard
jobs finish at roughly the same time.

Usage:
    python tools/ci/make-python-shards.py \
        --times python-module-times.tsv \
        --out tools/ci/python-shards.txt --shards 2

The TSV has one row per module:
    seconds<TAB>cases<TAB>exit-code<TAB>tests.python.<module>

Regenerate whenever test modules are added, removed or renamed.
verify-shard-coverage.py fails the build when the manifest and tests/python disagree.
"""

import argparse
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--times", required=True, help="TSV produced by the measurement run")
    parser.add_argument("--out", required=True)
    parser.add_argument("--shards", type=int, default=2)
    args = parser.parse_args()

    rows: list[tuple[float, str]] = []
    with open(args.times, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            seconds, _cases, _code, module = line.split("\t")
            rows.append((float(seconds), module))
    if not rows:
        print(f"no rows in {args.times}", file=sys.stderr)
        return 2

    rows.sort(reverse=True)
    bins: list[list[str]] = [[] for _ in range(args.shards)]
    loads = [0.0] * args.shards
    for seconds, module in rows:
        index = loads.index(min(loads))
        bins[index].append(module)
        loads[index] += seconds

    with open(args.out, "w", encoding="utf-8", newline="\n") as handle:
        for index, modules in enumerate(bins, 1):
            for module in sorted(modules):
                handle.write(f"{index}\t{module}\n")

    total = sum(item[0] for item in rows)
    print(f"{len(rows)} modules, {total:.0f} s total")
    for index, modules in enumerate(bins, 1):
        print(f"  shard {index}: {len(modules)} modules")
    print("wrote", args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
