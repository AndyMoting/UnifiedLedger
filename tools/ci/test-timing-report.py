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


def owner(root: str, path: str) -> str:
    """Name a JUnit XML path as `<module>:<task>` for <root>/<module>/build/test-results/<task>/."""
    parts = os.path.relpath(path, root).split(os.sep)
    module = parts[0] if parts else "?"
    if "test-results" in parts:
        index = parts.index("test-results")
        task = parts[index + 1] if index + 1 < len(parts) else "?"
    else:
        task = "?"
    return f"{module}:{task}"


def class_name(element: ET.Element, fallback: str) -> str:
    """The fully qualified class name of a suite.

    Gradle names a KMP test task's suite with the SHORT class name plus a task
    suffix (e.g. "LedgerDatabaseMigrationTest[jvm]"); the testcase elements carry
    the qualified name, which is what the shard manifest and the rebalance tool
    use. Suites that already report a qualified name are kept as they are.
    """
    name = element.get("name") or fallback
    if "." in name:
        return name
    case = element.find("testcase")
    if case is not None:
        classname = case.get("classname") or ""
        if "." in classname:
            return classname
    return name


def collect(root: str) -> list[tuple[float, int, str, str]]:
    rows: list[tuple[float, int, str, str]] = []
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
                class_name(element, path),
                owner(root, path),
            )
        )
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--summary", default="")
    parser.add_argument("--top", type=int, default=15, help="0 lists every class")
    args = parser.parse_args()

    rows = sorted(collect(args.root), reverse=True)
    total_seconds = sum(item[0] for item in rows)
    total_tests = sum(item[1] for item in rows)

    grouped: dict[str, list[float]] = {}
    for seconds, tests, _name, key in rows:
        bucket = grouped.setdefault(key, [0.0, 0.0, 0.0])
        bucket[0] += 1
        bucket[1] += tests
        bucket[2] += seconds

    out = [
        "## Test timing report",
        "",
        f"- classes: {len(rows)}",
        f"- tests: {total_tests}",
        f"- class seconds (sum): {total_seconds:.0f}",
        "",
    ]
    if rows:
        out += [
            "### slowest classes",
            "",
            "| seconds | tests | class |",
            "| --- | --- | --- |",
        ]
        selected = rows if args.top <= 0 else rows[: args.top]
        out += [f"| {t:.1f} | {n} | {name} |" for t, n, name, _key in selected]
        out.append("")
    if grouped:
        out += [
            "### per module:task",
            "",
            "| module:task | classes | tests | seconds |",
            "| --- | --- | --- | --- |",
        ]
        for key in sorted(grouped, key=lambda item: -grouped[item][2]):
            classes, tests, seconds = grouped[key]
            out.append(f"| {key} | {int(classes)} | {int(tests)} | {seconds:.1f} |")
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
