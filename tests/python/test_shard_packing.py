"""Offline proofs for the measured shard packer (D-303).

The packer is the only thing that decides which runner executes which test
class, so a silent regression here can move a multi-minute class into a shard
that already has one. These tests drive the real CLI on synthetic timing tables.

`--spread-heavy` was tried in this batch and removed: plain largest-processing-time
packing already hands the N slowest classes to the first N empty shards, so the
flag was a no-op. The heavy-class outcome is still asserted below, because a
future change to the packing order could quietly break it.
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GENERATOR = ROOT / "tools" / "ci" / "make-ledger-data-shards.py"

HEAVY = [
    (240.0, "com.example.HeaviestTest"),
    (180.0, "com.example.SecondTest"),
    (120.0, "com.example.ThirdTest"),
    (90.0, "com.example.FourthTest"),
    (60.0, "com.example.FifthTest"),
]
TAIL = [(40.0, f"com.example.Tail{i}Test") for i in range(1, 16)]


class Packer(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)

    def pack(self, rows: list[tuple[float, str]], shards: int = 5) -> list[tuple[int, str]]:
        times = self.root / "times.tsv"
        out = self.root / "manifest.txt"
        times.write_text(
            "".join(f"{seconds:.3f}\t{name}\n" for seconds, name in rows), encoding="utf-8"
        )
        completed = subprocess.run(
            [sys.executable, str(GENERATOR), "--times", str(times), "--out", str(out),
             "--shards", str(shards)],
            capture_output=True,
            text=True,
            check=True,
        )
        self.assertIn("wrote", completed.stdout)
        return [
            (int(shard), name)
            for shard, name in (line.split("\t") for line in out.read_text(encoding="utf-8").splitlines() if line.strip())
        ]

    def test_every_class_lands_in_exactly_one_contiguous_shard(self):
        rows = self.pack(HEAVY + TAIL)
        self.assertEqual(sorted(name for _shard, name in rows), sorted(name for _s, name in HEAVY + TAIL))
        self.assertEqual({shard for shard, _name in rows}, {1, 2, 3, 4, 5})

    def test_packing_is_deterministic(self):
        self.assertEqual(self.pack(HEAVY + TAIL), self.pack(HEAVY + TAIL))

    def test_balanced_tail_keeps_every_shard_close_to_the_mean(self):
        rows = self.pack(TAIL)
        loads: dict[int, float] = {}
        for shard, name in rows:
            loads[shard] = loads.get(shard, 0.0) + {name: seconds for seconds, name in TAIL}[name]
        mean = sum(loads.values()) / len(loads)
        self.assertLessEqual(max(loads.values()), mean * 1.10, loads)
        self.assertGreaterEqual(min(loads.values()), mean * 0.90, loads)

    def test_the_heaviest_classes_end_up_in_distinct_shards(self):
        rows = self.pack(HEAVY + TAIL)
        shards = {name: shard for shard, name in rows}
        self.assertEqual(len({shards[name] for _s, name in HEAVY}), len(HEAVY), shards)

    def test_an_empty_timing_table_fails_loudly(self):
        times = self.root / "empty.tsv"
        times.write_text("", encoding="utf-8")
        completed = subprocess.run(
            [sys.executable, str(GENERATOR), "--times", str(times), "--out", str(self.root / "out.txt")],
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(completed.returncode, 0)


class ShardManifest(unittest.TestCase):
    def test_this_module_is_in_its_shard(self):
        manifest = (ROOT / "tools" / "ci" / "python-shards.txt").read_text(encoding="utf-8")
        self.assertIn("tests.python.test_shard_packing", manifest)


if __name__ == "__main__":
    unittest.main()
