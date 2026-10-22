"""Surface the recorded scale readings from JUnit XML into the job summary.

The P7-07/P7-08 scale tests (P707BudgetScaleTest, TagMerchantFilterScaleTest)
record their measurements by printing lines under a stable prefix. Those lines
land in the JUnit XML's <system-out> — which a normal CI run neither displays
nor archives, so the readings a scale batch delivers would be unreadable the
moment the job ends. This script copies exactly the prefixed lines into the
GitHub Actions job summary, so a recorded figure can be cited later without
re-running a 20k/50k fixture build.

Report-only: it never changes a check result, and a run that printed nothing is
not an error (the 20k-only shards legitimately print nothing).
"""

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET

DEFAULT_PREFIXES = ("P707-A08 ", "P708-A09 ")


def class_of(root: str, path: str) -> str:
    """The module and test task a JUnit XML belongs to, for the summary heading."""
    parts = os.path.relpath(path, root).split(os.sep)
    module = parts[0] if parts else "?"
    task = "?"
    if "test-results" in parts:
        index = parts.index("test-results")
        if index + 1 < len(parts):
            task = parts[index + 1]
    return f"{module}:{task}"


def prefixed_lines(system_out: str, prefixes: tuple[str, ...]) -> list[str]:
    """Every stdout line that starts with one of the recorded-reading prefixes."""
    found = []
    for line in system_out.splitlines():
        stripped = line.strip()
        if stripped.startswith(prefixes):
            found.append(stripped)
    return found


def collect(root: str, prefixes: tuple[str, ...]) -> list[tuple[str, str, list[str]]]:
    """(owner, testcase name, lines) for every case that printed a reading."""
    rows = []
    pattern = os.path.join(root, "*", "build", "test-results", "*", "TEST-*.xml")
    for path in sorted(glob.glob(pattern)):
        try:
            tree = ET.parse(path)
        except ET.ParseError as failure:
            print(f"warning: unparsable {path}: {failure}", file=sys.stderr)
            continue
        owner = class_of(root, path)
        for case in tree.iter("testcase"):
            system_out = case.findtext("system-out") or ""
            lines = prefixed_lines(system_out, prefixes)
            if lines:
                rows.append((owner, case.get("name") or "?", lines))
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--summary", default=None, help="write to this file (e.g. $GITHUB_STEP_SUMMARY)")
    parser.add_argument("--only-missing", action="store_true", help="exit 1 when no reading was found")
    arguments = parser.parse_args()

    rows = collect(arguments.root, DEFAULT_PREFIXES)
    if not rows:
        if arguments.only_missing:
            print("no recorded scale reading found in any JUnit XML", file=sys.stderr)
            return 1
        print("no recorded scale reading found in any JUnit XML (not an error)")
        return 0

    out = []
    out.append("## Recorded scale readings")
    out.append("")
    out.append("Values are recorded, never threshold-asserted. JVM heap figures are JVM-heap-only.")
    for owner, name, lines in rows:
        out.append("")
        out.append(f"### {owner} — {name}")
        out.append("")
        out.append("```")
        out.extend(lines)
        out.append("```")

    text = "\n".join(out) + "\n"
    if arguments.summary:
        with open(arguments.summary, "a", encoding="utf-8") as handle:
            handle.write(text)
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
