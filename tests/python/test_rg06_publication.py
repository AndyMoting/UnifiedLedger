from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from golden_cases.rg06_publication import publish_rg06, recover_rg06_publication


ROOT = Path(__file__).resolve().parents[2]
SOURCE_PATH = ROOT / "golden" / "rules" / "rg-06.json"
EXPECTED_PATH = ROOT / "docs" / "migrations" / "golden-v2" / "rg-06-expected.json"
MANIFEST_PATH = ROOT / "golden" / "rules-v2" / "manifest.json"


class Rg06PublicationTests(unittest.TestCase):
    def _copy_inputs(self, directory: Path) -> tuple[Path, Path, Path]:
        source = directory / "v1-rg-06.json"
        expected = directory / "expected-rg-06.json"
        manifest = directory / "manifest.json"
        source.write_bytes(SOURCE_PATH.read_bytes())
        expected.write_bytes(EXPECTED_PATH.read_bytes())
        manifest.write_bytes(MANIFEST_PATH.read_bytes())
        return source, expected, manifest

    def test_publication_hashes_is_idempotent_and_preserves_source(self):
        with tempfile.TemporaryDirectory() as name:
            source, expected, manifest = self._copy_inputs(Path(name))
            output = Path(name) / "rg-06.json"
            source_before = source.read_bytes()
            expected_bytes = expected.read_bytes()

            first = publish_rg06(source, expected, output, manifest)
            self.assertTrue(first.changed)
            self.assertEqual(source_before, source.read_bytes())
            self.assertEqual(expected_bytes, output.read_bytes())
            self.assertEqual(
                hashlib.sha256(expected_bytes).hexdigest(), first.output_sha256
            )
            self.assertEqual(
                {"operations": 41, "roots": 20, "states": 61},
                first.object_counts,
            )
            self.assertEqual(
                {"accepted": 13, "no_change": 10, "rejected": 18},
                first.operation_status_counts,
            )

            output_after_first = output.read_bytes()
            manifest_after_first = manifest.read_bytes()
            second = publish_rg06(source, expected, output, manifest)
            self.assertFalse(second.changed)
            self.assertEqual(output_after_first, output.read_bytes())
            self.assertEqual(manifest_after_first, manifest.read_bytes())

            published = json.loads(manifest.read_text(encoding="utf-8"))
            entries = {item["case"]: item for item in published["cases"]}
            self.assertIn("RG-06", entries)
            self.assertEqual(first.canonical_sha256, entries["RG-06"]["canonical_sha256"])
            self.assertEqual(first.expected_sha256, entries["RG-06"]["expected_byte_sha256"])
            self.assertEqual(first.output_sha256, entries["RG-06"]["hashes"]["output_sha256"])

    def test_output_swap_failure_rolls_back_and_isolates_other_cases(self):
        with tempfile.TemporaryDirectory() as name:
            source, expected, manifest = self._copy_inputs(Path(name))
            output = Path(name) / "rg-06.json"
            original_output = b"old RG-06 output\n"
            output.write_bytes(original_output)
            original_manifest = manifest.read_bytes()
            original_cases = {
                item["case"]: item
                for item in json.loads(original_manifest)["cases"]
            }

            with self.assertRaisesRegex(RuntimeError, "output swap failure"):
                publish_rg06(
                    source,
                    expected,
                    output,
                    manifest,
                    fail_after_output_swap=True,
                )

            self.assertEqual(original_output, output.read_bytes())
            self.assertEqual(original_manifest, manifest.read_bytes())
            self.assertEqual(original_cases, {
                item["case"]: item
                for item in json.loads(manifest.read_text(encoding="utf-8"))["cases"]
            })
            self.assertEqual(SOURCE_PATH.read_bytes(), source.read_bytes())
            self.assertEqual([], list(Path(name).glob(".*.tmp")))
            self.assertEqual([], list(Path(name).glob(".*.bak")))
            self.assertFalse((Path(name) / ".rg-06-publication.journal.json").exists())

    def test_manifest_swap_failure_rolls_back_both_files(self):
        with tempfile.TemporaryDirectory() as name:
            source, expected, manifest = self._copy_inputs(Path(name))
            output = Path(name) / "rg-06.json"
            original_output = b"old RG-06 output\n"
            output.write_bytes(original_output)
            original_manifest = manifest.read_bytes()

            with self.assertRaisesRegex(RuntimeError, "manifest swap failure"):
                publish_rg06(
                    source,
                    expected,
                    output,
                    manifest,
                    fail_after_manifest_swap=True,
                )

            self.assertEqual(original_output, output.read_bytes())
            self.assertEqual(original_manifest, manifest.read_bytes())
            self.assertFalse((Path(name) / ".rg-06-publication.journal.json").exists())

    def test_prepared_journal_recovery_keeps_original_files(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            source, expected, manifest = self._copy_inputs(directory)
            output = directory / "rg-06.json"
            output.write_bytes(b"old RG-06 output\n")
            original_output = output.read_bytes()
            original_manifest = manifest.read_bytes()
            token = "prepared-recovery"
            output_temp = directory / f".rg-06.json.{token}.tmp"
            manifest_temp = directory / f".manifest.json.{token}.tmp"
            output_backup = directory / f".rg-06.json.{token}.bak"
            manifest_backup = directory / f".manifest.json.{token}.bak"
            output_temp.write_bytes(b"incomplete output")
            manifest_temp.write_bytes(b"incomplete manifest")
            journal = directory / ".rg-06-publication.journal.json"
            journal.write_text(
                json.dumps(
                    {
                        "phase": "prepared",
                        "output_path": str(output.resolve()),
                        "manifest_path": str(manifest.resolve()),
                        "output_temp": str(output_temp.resolve()),
                        "manifest_temp": str(manifest_temp.resolve()),
                        "output_backup": str(output_backup.resolve()),
                        "manifest_backup": str(manifest_backup.resolve()),
                        "output_had_original": True,
                        "manifest_had_original": True,
                    }
                ),
                encoding="utf-8",
            )

            self.assertTrue(recover_rg06_publication(manifest))
            self.assertEqual(original_output, output.read_bytes())
            self.assertEqual(original_manifest, manifest.read_bytes())
            self.assertFalse(journal.exists())
            self.assertFalse(output_temp.exists())
            self.assertFalse(manifest_temp.exists())


if __name__ == "__main__":
    unittest.main()
