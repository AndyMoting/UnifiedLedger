"""Summarize per-class JUnit XML timings into the GitHub Actions job summary.

Report-only: this script never changes a check result. It exists so later CI
tuning uses measured per-class data instead of estimates.
"""

import argparse
import glob
import os
import platform
import subprocess
import sys
import xml.etree.ElementTree as ET


def machine_facts() -> list[str]:
    lines = [f"- cpu_count: {os.cpu_count()}", "- platform: " + platform.platform()]
    for command in (["nproc"], ["free", "-m"], ["df", "-h", "/"]):
        try:
            completed = subprocess.run(
                command, capture_output=True, text=True, check=False
            )
        except OSError:
            continue
        output = (completed.stdout or "").strip()
        if output:
            lines.append("")
            lines.append("```")
            lines.append(output)
            lines.append("```")
    return lines


def collect(root: str) -> list[tuple[float, int, str]]:
    rows: list[tuple[float, int, str]] = []
    pattern = os.path.join(root, "**", "build", "test-results", "**", "TEST-*.xml")
    for path in glob.glob(pattern, recursive=True):
        try:
            element = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        rows.append(
            (
                float(element.get("time") or 0),
                int(element.get("tests") or 0),
                element.get("name") or path,
            )
        )
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--summary", default="")
    parser.add_argument("--top", type=int, default=15)
    args = parser.parse_args()

    rows = sorted(collect(args.root), reverse=True)
    total_seconds = sum(item[0] for item in rows)
    total_tests = sum(item[1] for item in rows)

    out = [
        "## Test timing report",
        "",
        f"- classes: {len(rows)}",
        f"- tests: {total_tests}",
        f"- class seconds (sum): {total_seconds:.0f}",
        "",
    ]
    if rows:
        out += ["| seconds | tests | class |", "| --- | --- | --- |"]
        out += [f"| {t:.1f} | {n} | {name} |" for t, n, name in rows[: args.top]]
        out.append("")
    out += ["### runner", ""]
    out += machine_facts()
    text = "\n".join(out) + "\n"

    print(text)
    if args.summary:
        with open(args.summary, "a", encoding="utf-8") as handle:
            handle.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
