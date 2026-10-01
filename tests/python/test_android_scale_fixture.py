import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from collections import Counter
from dataclasses import asdict, replace
from pathlib import Path

from android_scale.fixture import generate_fixture, load_manifest, validate_manifest

ROOT = Path(__file__).resolve().parents[2]
TINY = ROOT / "tests/fixtures/android-scale"


def independent_facts(path):
    # Independent of generator constants/decoder; never use a description as identity.
    lines = path.read_bytes().decode("utf-8").split("\n")
    assert lines[23].split(",") == [
        "交易时间", "交易分类", "交易对方", "对方账号", "商品说明", "收/支", "金额",
        "收/付款方式", "交易状态", "交易订单号", "商家订单号", "备注", "",
    ]
    assert lines[-1] == ""
    result = []
    for line in lines[24:-1]:
        fields = line.split(",")
        assert len(fields) == 13 and fields[-1] == ""
        assert "\r" not in line and line.count("\t") == 1 and fields[9].endswith("\t")
        assert (fields[1], fields[5], fields[7], fields[8]) == ("网上支付", "支出", "", "交易成功")
        whole, fraction = fields[6].split(".")
        assert len(fraction) == 2
        result.append((fields[0], 100 * int(whole) + int(fraction), "CNY", 2, "out", "settled"))
    return result


class AndroidScaleFixtureTests(unittest.TestCase):
    def test_maximum_actual_bytes_have_independent_relationship_oracle(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            manifest = generate_fixture(path)
            seen = Counter()
            relations = 0
            for index in range(1, 6):
                facts = independent_facts(path / f"session-{index:02d}.csv")
                self.assertEqual(len(facts), 10000)
                self.assertEqual(len(set(facts)), 10000)
                self.assertEqual([fact[1] for fact in facts], list(range(197199, 207199)))
                for fact in facts:
                    relations += seen[fact]
                    seen[fact] += 1
            self.assertEqual(relations, 100000)
            unique = independent_facts(path / "unique-rows.csv")
            self.assertEqual(len(unique), 1000)
            self.assertEqual(len(set(unique)), 1000)
            self.assertFalse(set(unique).intersection(seen))
            seen.update(unique)
            self.assertEqual(sum(seen.values()), 51000)
            for fact in independent_facts(path / "session-06.csv"):
                relations += seen[fact]
                seen[fact] += 1
            self.assertEqual((sum(seen.values()), relations), (61000, 150000))
            self.assertEqual(manifest.new_session_duplicate_relations, 50000)
            self.assertEqual(manifest.initially_confirmed_relations, 100)
            self.assertEqual(len({entry["input_ref"] for entry in manifest.files.values()}), 7)
            for name, entry in manifest.files.items():
                payload = (path / name).read_bytes()
                self.assertLessEqual(len(payload), 10 * 1024 * 1024)
                self.assertEqual(entry["bytes"], len(payload))
                self.assertEqual(entry["sha256"], hashlib.sha256(payload).hexdigest())
            validate_manifest(load_manifest(path / "manifest.json"), path)

    def test_tiny_generated_bytes_are_exact_jvm_parser_inputs(self):
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            a, b = Path(first), Path(second)
            manifest = generate_fixture(a, profile="parser-small")
            self.assertEqual(manifest, generate_fixture(b, profile="parser-small"))
            for file in a.iterdir():
                self.assertEqual(file.read_bytes(), (b / file.name).read_bytes())
            for index in range(1, 7):
                self.assertEqual((a / f"session-{index:02d}.csv").read_bytes(), (TINY / "shared-small.csv").read_bytes())
            self.assertEqual((a / "unique-rows.csv").read_bytes(), (TINY / "unique-small.csv").read_bytes())
            sources = independent_facts(TINY / "shared-small.csv") * 6 + independent_facts(TINY / "unique-small.csv")
            pairs = [(left, right) for left in range(len(sources)) for right in range(left)
                     if sources[left] == sources[right]]
            self.assertEqual((len(sources), len(pairs)), (20, 45))
            self.assertEqual((manifest.initial_candidates, manifest.initial_duplicate_relations), (17, 30))
            self.assertEqual(manifest.new_session_duplicate_relations, 15)
            validate_manifest(manifest, a, expected_profile="parser-small")
            with self.assertRaisesRegex(ValueError, "profile"):
                validate_manifest(manifest, a)

    def test_every_manifest_field_and_integer_type_is_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            manifest = generate_fixture(path, profile="parser-small")
            for key, value in asdict(manifest).items():
                changed = value + 1 if type(value) is int else {} if key == "files" else "invalid"
                with self.subTest(field=key), self.assertRaises(ValueError):
                    validate_manifest(replace(manifest, **{key: changed}), path, expected_profile="parser-small")
                if type(value) is int:
                    with self.subTest(type_field=key), self.assertRaises(ValueError):
                        validate_manifest(replace(manifest, **{key: float(value)}), path, expected_profile="parser-small")
            with self.assertRaises(ValueError):
                validate_manifest(replace(manifest, formal_transactions_before_confirmation=False), path, expected_profile="parser-small")

    def test_hash_role_row_size_and_path_entries_are_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            manifest = generate_fixture(path, profile="parser-small")
            for field in ("role", "input_ref", "rows", "bytes", "sha256"):
                data = asdict(manifest)
                data["files"]["session-01.csv"][field] = "wrong"
                with self.subTest(field=field), self.assertRaises(ValueError):
                    validate_manifest(type(manifest)(**data), path, expected_profile="parser-small")
            data = asdict(manifest)
            data["files"]["../escape.csv"] = data["files"].pop("session-01.csv")
            with self.assertRaises(ValueError):
                validate_manifest(type(manifest)(**data), path, expected_profile="parser-small")

    def test_bad_csv_with_updated_hash_is_rejected_without_rewriting(self):
        corruptions = [
            lambda p: p.replace("交易时间".encode(), b"time", 1),
            lambda p: p.replace(b"\t", b"", 1),
            lambda p: p.replace(b"1971.99", b"1971.98", 1),
            lambda p: p.replace(b"1971.99", b"1972.00", 1),
            lambda p: p.replace(b"\n", b"\r\n"),
            lambda p: p.replace(b"SYNTHETIC ITEM", b"CHANGED ITEM", 1),
            lambda p: p + p.split(b"\n")[-2] + b"\n",
            lambda p: p[:p.rfind(b"\n", 0, -1) + 1],
        ]
        for index, corruption in enumerate(corruptions):
            with self.subTest(index=index), tempfile.TemporaryDirectory() as directory:
                path = Path(directory)
                manifest = generate_fixture(path, profile="parser-small")
                csv = path / "session-01.csv"
                changed = corruption(csv.read_bytes())
                csv.write_bytes(changed)
                data = asdict(manifest)
                data["files"][csv.name].update(sha256=hashlib.sha256(changed).hexdigest(), bytes=len(changed))
                with self.assertRaises(ValueError):
                    validate_manifest(type(manifest)(**data), path, expected_profile="parser-small")
                self.assertEqual(csv.read_bytes(), changed)

    def test_missing_extra_files_and_nonempty_output_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            manifest = generate_fixture(path, profile="parser-small")
            with self.assertRaisesRegex(ValueError, "empty"):
                generate_fixture(path)
            extra = path / "unexpected.txt"
            extra.write_text("synthetic")
            with self.assertRaisesRegex(ValueError, "file set"):
                validate_manifest(manifest, path, expected_profile="parser-small")
            extra.unlink()
            (path / "session-06.csv").unlink()
            with self.assertRaisesRegex(ValueError, "file set"):
                validate_manifest(manifest, path, expected_profile="parser-small")

    def test_manifest_json_shape_and_duplicate_keys_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "manifest.json"
            for content in ('[]', '{}', '{"seed":1,"seed":2}', 'not json'):
                file.write_text(content)
                with self.subTest(content=content), self.assertRaises(ValueError):
                    load_manifest(file)

    def test_cli_checks_maximum_and_rejects_small_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            command = [sys.executable, str(ROOT / "tools/ci/android-scale-fixture.py"), "--out", directory]
            completed = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            checked = subprocess.run(command + ["--validate"], capture_output=True, text=True)
            self.assertEqual(checked.returncode, 0, checked.stderr)
            self.assertIn("candidates=61000 relations=150000", checked.stdout)
        with tempfile.TemporaryDirectory() as directory:
            generate_fixture(Path(directory), profile="parser-small")
            checked = subprocess.run([sys.executable, str(ROOT / "tools/ci/android-scale-fixture.py"),
                                      "--out", directory, "--validate"], capture_output=True, text=True)
            self.assertNotEqual(checked.returncode, 0)

    def test_seed_is_bounded_and_changes_business_facts(self):
        for seed in (-1, True, 1.5, 1_000_000_001):
            with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
                generate_fixture(Path(directory), seed)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            manifest = generate_fixture(path, 0, profile="parser-small")
            self.assertEqual([fact[1] for fact in independent_facts(path / "session-01.csv")], [1, 2, 3])
            validate_manifest(manifest, path, expected_profile="parser-small")


if __name__ == "__main__":
    unittest.main()
