"""Offline proofs for the measured shard packer (D-303, batch D).

The packer is the only thing that decides which runner executes which test
class, so a silent regression here can move a multi-minute class into a shard
that already has one. These tests drive the real CLI on a synthetic timing table.
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GENERATOR = ROOT / "tools" / "ci" / "make-ledger-data-shards.py"

# Five deliberately heavy classes plus a tail: enough to exercise spreading.
ROWS = [
    (240.0, "com.example.HeaviestTest"),
    (180.0, "com.example.SecondTest"),
    (120.0, "com.example.ThirdTest"),
    (90.0, "com.example.FourthTest"),
    (60.0, "com.example.FifthTest"),
    (20.0, "com.example.SixthTest"),
    (20.0, "com.example.SeventhTest"),
    (10.0, "com.example.EighthTest"),
    (10.0, "com.example.NinthTest"),
    (5.0, "com.example.TenthTest"),
]


class Packer(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        self.times = self.root / "times.tsv"
        self.times.write_text(
            "".join(f"{seconds:.3f}\t{name}\n" for seconds, name in ROWS), encoding="utf-8"
        )

    def pack(self, *extra: str) -> list[tuple[int, str]]:
        out = self.root / ("manifest" + "".join(extra).replace("=", "").replace("-", "") + ".txt")
        completed = subprocess.run(
            [sys.executable, str(GENERATOR), "--times", str(self.times), "--out", str(out), *extra],
            capture_output=True,
            text=True,
            check=True,
        )
        self.assertIn("wrote", completed.stdout)
        rows = []
        for line in out.read_text(encoding="utf-8").splitlines():
            if line.strip():
                shard, name = line.split("\t")
                rows.append((int(shard), name))
        return rows

    def test_default_packing_keeps_every_class_once(self):
        rows = self.pack("--shards", "5")
        self.assertEqual(sorted(name for _shard, name in rows), sorted(name for _s, name in ROWS))
        self.assertEqual({shard for shard, _name in rows}, {1, 2, 3, 4, 5})

    def test_spread_heavy_puts_the_slowest_classes_in_distinct_shards(self):
        rows = self.pack("--shards", "5", "--spread-heavy", "5")
        shards = {name: shard for shard, name in rows}
        heavy = [name for _seconds, name in ROWS[:5]]
        self.assertEqual(len({shards[name] for name in heavy}), 5, shards)

    def test_spread_heavy_is_deterministic(self):
        first = self.pack("--shards", "5", "--spread-heavy", "5")
        second = self.pack("--shards", "5", "--spread-heavy", "5")
        self.assertEqual(first, second)

    def test_spread_heavy_larger_than_the_matrix_is_ignored(self):
        rows = self.pack("--shards", "5", "--spread-heavy", "9")
        self.assertEqual(len(rows), len(ROWS))
        self.assertEqual({shard for shard, _name in rows}, {1, 2, 3, 4, 5})


class ShardManifest(unittest.TestCase):
    def test_this_module_is_in_its_shard(self):
        manifest = (ROOT / "tools" / "ci" / "python-shards.txt").read_text(encoding="utf-8")
        self.assertIn("tests.python.test_shard_packing", manifest)


if __name__ == "__main__":
    unittest.main()
