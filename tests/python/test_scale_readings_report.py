"""Offline proofs for the V-4 progress fallback of the recorded-scale-reading report (P8-06).

A 50k fixture job that is cancelled writes no JUnit XML, so the progress file the test
flushes while it runs is then the only surviving reading. These tests drive the real CLI
on synthetic trees: progress lines alone, nothing at all, and a progress file whose
unrelated lines must stay out of the reading.
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "tools" / "ci" / "scale-readings-report.py"

PROGRESS_LINES = [
    "P806-V4 seed start",
    "P806-V4 seed rows=10000",
    "P806-V4 seed done elapsed_ms=4210",
    "P806-V4 report start",
    "P806-V4 report done elapsed_ms=310",
]


class ProgressFallback(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        self.summary = self.root / "summary.md"

    def write_progress(self, lines: list[str]) -> None:
        path = self.root / "ledger-data" / "build" / "p806-seed-progress.txt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def run_report(self) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(REPORT), "--root", str(self.root), "--summary", str(self.summary)],
            capture_output=True,
            text=True,
        )

    def test_progress_lines_reach_stdout_and_the_summary_without_any_junit_xml(self):
        self.write_progress(PROGRESS_LINES)
        completed = self.run_report()
        self.assertEqual(completed.returncode, 0, completed.stderr)
        summary = self.summary.read_text(encoding="utf-8")
        self.assertIn("ledger-data", completed.stdout)
        for line in PROGRESS_LINES:
            self.assertIn(line, completed.stdout)
            self.assertIn(line, summary)

    def test_no_progress_file_and_no_xml_stays_a_non_error(self):
        completed = self.run_report()
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn(
            "no recorded scale reading found in any JUnit XML (not an error)",
            completed.stdout,
        )
        self.assertFalse(self.summary.exists())

    def test_unrelated_progress_lines_stay_out_of_the_reading(self):
        self.write_progress(["build started", "elapsed 12s", "P806-V4 report done elapsed_ms=310"])
        completed = self.run_report()
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("P806-V4 report done elapsed_ms=310", completed.stdout)
        self.assertNotIn("build started", completed.stdout)
        self.assertNotIn("elapsed 12s", completed.stdout)
        self.assertNotIn("P806-V4 seed", completed.stdout)


if __name__ == "__main__":
    unittest.main()
