#!/usr/bin/env python3
"""Classify a pull request change as `docs` or `code` for CI job skipping (D-302).

`docs` means every changed path is under `docs/` or is a Markdown file at the
repository root. Everything else - a single other path, an empty diff, a missing
base, or an unreadable diff - is `code`, so an unclassifiable change always runs
the full verification set.

Usage:
    python3 tools/ci/classify-changes.py --base <sha> [--head <rev>]

Prints exactly `docs` or `code` on stdout; diagnostics go to stderr. Exit code
is 0 whenever a classification was produced, including the `code` fallback.
"""
from __future__ import annotations

import argparse
import subprocess
import sys


def is_docs_path(path: str) -> bool:
    cleaned = path.strip().replace("\\", "/")
    while cleaned.startswith("./"):
        cleaned = cleaned[2:]
    if cleaned.startswith("docs/"):
        return True
    return "/" not in cleaned and cleaned.endswith(".md")


def changed_paths(base: str, head: str) -> list[str]:
    completed = subprocess.run(
        ["git", "diff", "--name-only", "--no-renames", base, head],
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            completed.stderr.strip() or f"git diff exited {completed.returncode}"
        )
    return [line for line in completed.stdout.splitlines() if line.strip()]


def classify(paths: list[str]) -> str:
    if not paths:
        return "code"
    return "docs" if all(is_docs_path(path) for path in paths) else "code"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="", help="base commit; empty means unclassifiable")
    parser.add_argument("--head", default="HEAD", help="head revision (default: HEAD)")
    args = parser.parse_args(argv)

    if not args.base:
        print("classify-changes: no base commit, treating as code", file=sys.stderr)
        print("code")
        return 0

    try:
        paths = changed_paths(args.base, args.head)
    except Exception as error:  # any unreadable diff is a code change
        print(f"classify-changes: {error}", file=sys.stderr)
        print("code")
        return 0

    kind = classify(paths)
    print(f"classify-changes: {kind} ({len(paths)} changed paths)", file=sys.stderr)
    print(kind)
    return 0


if __name__ == "__main__":
    sys.exit(main())
