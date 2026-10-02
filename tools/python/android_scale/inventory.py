"""Exact ordinary device-test inventory, checked against source and real XML."""
from __future__ import annotations

import argparse
import re
from pathlib import Path
from xml.etree import ElementTree as ET

EXCLUDED = {"ImportScaleTraversalInstrumentedTest", "AndroidScaleLongInstrumentedTest", "AndroidScalePreflightInstrumentedTest"}


def source_inventory(source: Path) -> set[str]:
    cases = []
    for path in source.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        # Remove comments so commented-out cases cannot satisfy this gate.
        text = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.DOTALL)
        if "@Test" not in text:
            continue
        package = re.search(r"^package\s+([\w.]+)", text, re.MULTILINE)
        classes = re.findall(r"\bclass\s+(\w+)", text)
        if not package or not classes:
            raise ValueError("unrecognized device test source: " + path.name)
        class_name = classes[0]
        if class_name in EXCLUDED:
            continue
        methods = re.findall(r"@Test\s+fun\s+(\w+)\s*\(", text)
        if len(methods) != len(re.findall(r"@Test\b", text)):
            raise ValueError("unrecognized @Test declaration; update inventory parser explicitly: " + path.name)
        cases.extend(package[1] + "." + class_name + "#" + method for method in methods)
    if not cases or len(cases) != len(set(cases)):
        raise ValueError("empty or duplicated source test inventory")
    return set(cases)


def check_inventory(source: Path, manifest: Path, results: Path | None = None) -> int:
    lines = [line.strip() for line in manifest.read_text(encoding="utf-8").splitlines() if line.strip() and not line.startswith("#")]
    expected = set(lines)
    if not expected or len(expected) != len(lines) or expected != source_inventory(source):
        raise ValueError("source test inventory differs from explicit manifest")
    if results is not None:
        files = list(results.rglob("TEST-*.xml"))
        if not files:
            raise ValueError("missing actual device JUnit reports")
        observed = []
        for path in files:
            root = ET.parse(path).getroot()
            suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
            if not suites:
                raise ValueError("missing JUnit test suites")
            for suite in suites:
                cases = suite.findall("testcase")
                if int(suite.get("tests", "-1")) != len(cases) or not cases:
                    raise ValueError("zero or inconsistent JUnit test count")
                if any(int(suite.get(key, "0")) != 0 for key in ("failures", "errors", "skipped", "disabled")):
                    raise ValueError("device tests failed or skipped")
                for case in cases:
                    if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                        raise ValueError("device test did not pass")
                    observed.append(case.get("classname", "") + "#" + case.get("name", ""))
        if len(observed) != len(set(observed)) or set(observed) != expected:
            raise ValueError("actual device test inventory differs from expected manifest")
    return len(expected)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--results", type=Path)
    args = parser.parse_args()
    count = check_inventory(args.source, args.manifest, args.results)
    print(f"PASS exact ordinary device inventory: {count} cases" + (" executed" if args.results else " in source"))
