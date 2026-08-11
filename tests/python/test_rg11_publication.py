from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from golden_cases.publication_integrity import PublicationIntegrityError
from golden_cases.rg11_publication import (
    PublicationRecoveryError,
    publish_rg11,
    recover_rg11_publication,
)


ROOT = Path(__file__).resolve().parents[2]
SOURCE_PATH = ROOT / "golden" / "rules" / "rg-11.json"
EXPECTED_PATH = ROOT / "docs" / "migrations" / "golden-v2" / "rg-11-expected.json"
MANIFEST_PATH = ROOT / "golden" / "rules-v2" / "manifest.json"


def _snapshot(root: Path) -> dict[str, bytes]:
    return {
        str(path.relative_to(root)): path.read_bytes()
        for path in sorted(root.rglob("*"))
        if path.is_file()
    }


def _craft_journal(directory: Path, case_id: str, phase_name: str, **overrides) -> Path:
    """Write a hand-crafted journal for ``case_id`` at ``phase_name``.

    Any field can be overridden by keyword (including ``case`` and ``phase``);
    an override of ``None`` drops the field entirely so tests can craft
    journals with missing fields.
    """
    overrides = dict(overrides)
    token = overrides.pop("token", "a" * 32)
    lower = case_id.lower()
    output = directory / f"{lower}.json"
    manifest = directory / "manifest.json"
    journal = {
        "format_version": 2,
        "case": case_id,
        "phase": phase_name,
        "output_path": str(output.resolve()),
        "manifest_path": str(manifest.resolve()),
        "output_temp": str((directory / f".{output.name}.{token}.tmp").resolve()),
        "manifest_temp": str((directory / f".{manifest.name}.{token}.tmp").resolve()),
        "output_backup": str((directory / f".{output.name}.{token}.bak").resolve()),
        "manifest_backup": str((directory / f".{manifest.name}.{token}.bak").resolve()),
        "output_had_original": True,
        "manifest_had_original": True,
    }
    for key, value in overrides.items():
        if value is None:
            journal.pop(key, None)
        elif isinstance(value, Path):
            journal[key] = str(value)
        else:
            journal[key] = value
    journal_path = directory / f".{lower}-publication.journal.json"
    journal_path.write_text(json.dumps(journal), encoding="utf-8")
    return journal_path


class Rg11PublicationTests(unittest.TestCase):
    def _copy_inputs(self, directory: Path) -> tuple[Path, Path, Path]:
        source = directory / "contract-rg-11.json"
        expected = directory / "expected-rg-11.json"
        manifest = directory / "manifest.json"
        source.write_bytes(SOURCE_PATH.read_bytes())
        expected.write_bytes(EXPECTED_PATH.read_bytes())
        manifest.write_bytes(MANIFEST_PATH.read_bytes())
        return source, expected, manifest

    def test_publication_hashes_is_idempotent_and_preserves_source(self):
        with tempfile.TemporaryDirectory() as name:
            source, expected, manifest = self._copy_inputs(Path(name))
            output = Path(name) / "rg-11.json"
            source_before = source.read_bytes()
            expected_bytes = expected.read_bytes()

            first = publish_rg11(source, expected, output, manifest)
            self.assertTrue(first.changed)
            self.assertEqual(source_before, source.read_bytes())
            self.assertEqual(expected_bytes, output.read_bytes())
            self.assertEqual(
                hashlib.sha256(expected_bytes).hexdigest(), first.output_sha256
            )
            self.assertEqual(
                {"operations": 22, "roots": 3, "states": 25},
                first.object_counts,
            )
            self.assertEqual(
                {"accepted": 11, "no_change": 1, "rejected": 10},
                first.operation_status_counts,
            )

            output_after_first = output.read_bytes()
            manifest_after_first = manifest.read_bytes()
            second = publish_rg11(source, expected, output, manifest)
            self.assertFalse(second.changed)
            self.assertEqual(output_after_first, output.read_bytes())
            self.assertEqual(manifest_after_first, manifest.read_bytes())

            published = json.loads(manifest.read_text(encoding="utf-8"))
            entries = {item["case"]: item for item in published["cases"]}
            self.assertIn("RG-11", entries)
            self.assertEqual(first.canonical_sha256, entries["RG-11"]["canonical_sha256"])
            self.assertEqual(first.expected_sha256, entries["RG-11"]["expected_byte_sha256"])
            self.assertEqual(first.output_sha256, entries["RG-11"]["hashes"]["output_sha256"])
            # Manifest registration: the four hash groups, object counts and
            # operation status counts must match the publication result, and
            # the discovery record must carry the 22-operation full comparison
            # (D-086 manifest registration format). RG-11 is direct-v2: the
            # expected artifact is a frozen byte-identical copy of the source
            # contract, so the source and expected hash groups coincide.
            self.assertEqual(first.source_sha256, entries["RG-11"]["source_byte_sha256"])
            self.assertEqual(first.source_sha256, entries["RG-11"]["source_sha256"])
            self.assertEqual(first.source_sha256, entries["RG-11"]["hashes"]["source_sha256"])
            self.assertEqual(first.expected_sha256, entries["RG-11"]["hashes"]["expected_sha256"])
            self.assertEqual(first.canonical_sha256, entries["RG-11"]["hashes"]["canonical_sha256"])
            self.assertEqual(
                {"operations": 22, "roots": 3, "states": 25},
                entries["RG-11"]["object_counts"],
            )
            self.assertEqual(
                {"accepted": 11, "no_change": 1, "rejected": 10},
                entries["RG-11"]["operation_status_counts"],
            )
            self.assertEqual(
                "22-operation full comparison",
                entries["RG-11"]["discovery"]["comparison"],
            )
            self.assertEqual(
                "approved", entries["RG-11"]["approval_status"]
            )
            self.assertEqual("published", entries["RG-11"]["publication_status"])

    def test_output_swap_failure_rolls_back_and_isolates_other_cases(self):
        with tempfile.TemporaryDirectory() as name:
            source, expected, manifest = self._copy_inputs(Path(name))
            output = Path(name) / "rg-11.json"
            original_output = b"old RG-11 output\n"
            output.write_bytes(original_output)
            original_manifest = manifest.read_bytes()
            original_cases = {
                item["case"]: item
                for item in json.loads(original_manifest)["cases"]
            }

            with self.assertRaisesRegex(RuntimeError, "output swap failure"):
                publish_rg11(
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
            self.assertFalse((Path(name) / ".rg-11-publication.journal.json").exists())

    def test_manifest_swap_failure_rolls_back_both_files(self):
        with tempfile.TemporaryDirectory() as name:
            source, expected, manifest = self._copy_inputs(Path(name))
            output = Path(name) / "rg-11.json"
            original_output = b"old RG-11 output\n"
            output.write_bytes(original_output)
            original_manifest = manifest.read_bytes()

            with self.assertRaisesRegex(RuntimeError, "manifest swap failure"):
                publish_rg11(
                    source,
                    expected,
                    output,
                    manifest,
                    fail_after_manifest_swap=True,
                )

            self.assertEqual(original_output, output.read_bytes())
            self.assertEqual(original_manifest, manifest.read_bytes())
            self.assertFalse((Path(name) / ".rg-11-publication.journal.json").exists())

    def test_output_backup_failure_keeps_original_files_and_allows_retry(self):
        with tempfile.TemporaryDirectory() as name:
            source, expected, manifest = self._copy_inputs(Path(name))
            output = Path(name) / "rg-11.json"
            original_output = b"old RG-11 output\n"
            output.write_bytes(original_output)
            original_manifest = manifest.read_bytes()

            # The failure is injected between the two os.replace calls: the
            # original output has already moved to .bak but the manifest has
            # not moved yet. The rollback must restore the original instead of
            # deleting the backup.
            with self.assertRaisesRegex(RuntimeError, "output backup failure"):
                publish_rg11(
                    source,
                    expected,
                    output,
                    manifest,
                    fail_after_output_backup=True,
                )

            self.assertEqual(original_output, output.read_bytes())
            self.assertEqual(original_manifest, manifest.read_bytes())
            self.assertEqual([], list(Path(name).glob(".*.tmp")))
            self.assertEqual([], list(Path(name).glob(".*.bak")))
            self.assertFalse((Path(name) / ".rg-11-publication.journal.json").exists())

            # The rollback leaves a clean state: the publication can be retried.
            retried = publish_rg11(source, expected, output, manifest)
            self.assertTrue(retried.changed)
            self.assertEqual(expected.read_bytes(), output.read_bytes())

    def test_output_backup_moved_journal_recovery_restores_missing_original(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            source, expected, manifest = self._copy_inputs(directory)
            output = directory / "rg-11.json"
            original_output = b"old RG-11 output\n"
            output.write_bytes(original_output)
            original_manifest = manifest.read_bytes()
            token = "a" * 32
            output_temp = directory / f".rg-11.json.{token}.tmp"
            manifest_temp = directory / f".manifest.json.{token}.tmp"
            output_backup = directory / f".rg-11.json.{token}.bak"
            manifest_backup = directory / f".manifest.json.{token}.bak"
            output_temp.write_bytes(b"incomplete output")
            manifest_temp.write_bytes(b"incomplete manifest")
            # Simulate a crash during the output move: the original output is
            # missing from its path but survives in .bak, and the manifest was
            # never moved. Recovery must restore the original from the backup.
            output_backup.write_bytes(original_output)
            output.unlink()
            journal_path = _craft_journal(directory, "RG-11", "output_backup")

            self.assertTrue(recover_rg11_publication(manifest))
            self.assertEqual(original_output, output.read_bytes())
            self.assertEqual(original_manifest, manifest.read_bytes())
            self.assertFalse(journal_path.exists())
            self.assertFalse(output_temp.exists())
            self.assertFalse(manifest_temp.exists())
            self.assertFalse(output_backup.exists())
            self.assertFalse(manifest_backup.exists())

    def test_prepared_journal_recovery_keeps_original_files(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            source, expected, manifest = self._copy_inputs(directory)
            output = directory / "rg-11.json"
            output.write_bytes(b"old RG-11 output\n")
            original_output = output.read_bytes()
            original_manifest = manifest.read_bytes()
            token = "a" * 32
            output_temp = directory / f".rg-11.json.{token}.tmp"
            manifest_temp = directory / f".manifest.json.{token}.tmp"
            output_temp.write_bytes(b"incomplete output")
            manifest_temp.write_bytes(b"incomplete manifest")
            _craft_journal(directory, "RG-11", "prepared")

            self.assertTrue(recover_rg11_publication(manifest))
            self.assertEqual(original_output, output.read_bytes())
            self.assertEqual(original_manifest, manifest.read_bytes())
            self.assertFalse((directory / ".rg-11-publication.journal.json").exists())
            self.assertFalse(output_temp.exists())
            self.assertFalse(manifest_temp.exists())

    def test_first_publication_failure_at_each_phase_removes_installed_output(self):
        flags = (
            "fail_after_output_backup",
            "fail_after_output_swap",
            "fail_after_manifest_backup",
            "fail_after_manifest_swap",
        )
        for flag in flags:
            with self.subTest(flag=flag), tempfile.TemporaryDirectory() as name:
                directory = Path(name)
                source, expected, manifest = self._copy_inputs(directory)
                output = directory / "rg-11.json"
                original_manifest = manifest.read_bytes()
                with self.assertRaisesRegex(
                    RuntimeError, r"(output backup|output swap|manifest backup|manifest swap) failure"
                ):
                    publish_rg11(source, expected, output, manifest, **{flag: True})
                # First publication: the installed new output must not survive
                # (PUB-001) and the manifest must be back to its original bytes.
                self.assertFalse(output.exists())
                self.assertEqual(original_manifest, manifest.read_bytes())
                self.assertFalse((directory / ".rg-11-publication.journal.json").exists())
                self.assertEqual([], list(directory.glob(".*.tmp")))
                self.assertEqual([], list(directory.glob(".*.bak")))
                retried = publish_rg11(source, expected, output, manifest)
                self.assertTrue(retried.changed)
                self.assertEqual(expected.read_bytes(), output.read_bytes())

    def test_existing_original_failure_at_each_phase_restores_originals(self):
        flags = (
            "fail_after_output_backup",
            "fail_after_output_swap",
            "fail_after_manifest_backup",
            "fail_after_manifest_swap",
        )
        for flag in flags:
            with self.subTest(flag=flag), tempfile.TemporaryDirectory() as name:
                directory = Path(name)
                source, expected, manifest = self._copy_inputs(directory)
                output = directory / "rg-11.json"
                original_output = b"old RG-11 output\n"
                output.write_bytes(original_output)
                original_manifest = manifest.read_bytes()
                with self.assertRaisesRegex(
                    RuntimeError, r"(output backup|output swap|manifest backup|manifest swap) failure"
                ):
                    publish_rg11(source, expected, output, manifest, **{flag: True})
                self.assertEqual(original_output, output.read_bytes())
                self.assertEqual(original_manifest, manifest.read_bytes())
                self.assertFalse((directory / ".rg-11-publication.journal.json").exists())
                self.assertEqual([], list(directory.glob(".*.tmp")))
                self.assertEqual([], list(directory.glob(".*.bak")))

    def test_commit_failure_keeps_committed_state_and_stale_backups_are_swept(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            source, expected, manifest = self._copy_inputs(directory)
            output = directory / "rg-11.json"
            original_output = b"old RG-11 output\n"
            output.write_bytes(original_output)
            original_manifest = manifest.read_bytes()
            with self.assertRaisesRegex(RuntimeError, "post-commit failure"):
                publish_rg11(source, expected, output, manifest, fail_after_commit=True)
            # The transaction is committed: the journal is gone and both
            # targets carry the new versions (no mixed state).
            self.assertFalse((directory / ".rg-11-publication.journal.json").exists())
            self.assertEqual(expected.read_bytes(), output.read_bytes())
            published = json.loads(manifest.read_text(encoding="utf-8"))
            self.assertIn("RG-11", {item["case"] for item in published["cases"]})
            # 模板 manifest 已含本 case 条目，发布幂等重写后字节与模板相同；manifest 安装已由 .bak 残留断言证明，此处不做字节级比较（LF/CRLF 检出无关）。
            self.assertNotEqual(original_output, output.read_bytes())
            # The stale .bak files survive until the next publication sweeps them.
            self.assertEqual(2, len(list(directory.glob(".*.bak"))))
            self.assertFalse(recover_rg11_publication(manifest))
            retried = publish_rg11(source, expected, output, manifest)
            self.assertFalse(retried.changed)
            self.assertEqual([], list(directory.glob(".*.tmp")))
            self.assertEqual([], list(directory.glob(".*.bak")))

    def test_corrupt_journal_is_rejected_and_preserved(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            source, expected, manifest = self._copy_inputs(directory)
            output = directory / "rg-11.json"
            original_output = b"old RG-11 output\n"
            output.write_bytes(original_output)
            original_manifest = manifest.read_bytes()
            journal_path = directory / ".rg-11-publication.journal.json"
            raw_cases = {
                "truncated": b'{"format_version": 2, "case": "RG-11", "phase": "prep',
                "empty": b"",
                "garbage": b"\x00\xff\xfe not json",
            }
            for label, raw in raw_cases.items():
                with self.subTest(label=label):
                    journal_path.write_bytes(raw)
                    with self.assertRaises(PublicationRecoveryError):
                        recover_rg11_publication(manifest)
                    self.assertEqual(raw, journal_path.read_bytes())
                    self.assertEqual(original_output, output.read_bytes())
                    self.assertEqual(original_manifest, manifest.read_bytes())
            crafted_cases = {
                "format_version_1": lambda: _craft_journal(
                    directory, "RG-11", "output_backup", format_version=1
                ),
                "illegal_phase_committed": lambda: _craft_journal(
                    directory, "RG-11", "committed"
                ),
                "missing_phase": lambda: _craft_journal(
                    directory, "RG-11", "output_backup", phase=None
                ),
                "missing_output_path": lambda: _craft_journal(
                    directory, "RG-11", "output_backup", output_path=None
                ),
                "wrong_case": lambda: _craft_journal(
                    directory, "RG-11", "output_backup", case="RG-08"
                ),
            }
            for label, craft in crafted_cases.items():
                with self.subTest(label=label):
                    crafted = craft()
                    raw = crafted.read_bytes()
                    with self.assertRaises(PublicationRecoveryError):
                        recover_rg11_publication(manifest)
                    self.assertEqual(raw, crafted.read_bytes())
                    self.assertEqual(original_output, output.read_bytes())
                    self.assertEqual(original_manifest, manifest.read_bytes())
            # Manual handling: deleting the corrupt journal unblocks publication.
            journal_path.unlink()
            published = publish_rg11(source, expected, output, manifest)
            self.assertTrue(published.changed)
            self.assertEqual(expected.read_bytes(), output.read_bytes())

    def test_journal_with_escaped_paths_is_rejected_without_touching_victim(self):
        with tempfile.TemporaryDirectory() as name, tempfile.TemporaryDirectory() as outside_name:
            directory = Path(name)
            outside = Path(outside_name)
            source, expected, manifest = self._copy_inputs(directory)
            output = directory / "rg-11.json"
            original_output = b"old RG-11 output\n"
            output.write_bytes(original_output)
            original_manifest = manifest.read_bytes()
            victim = outside / "victim-outside.txt"
            victim.write_bytes(b"ATTACKER PAYLOAD\n")
            journal_path = directory / ".rg-11-publication.journal.json"
            for field in (
                "output_path",
                "manifest_path",
                "output_temp",
                "manifest_temp",
                "output_backup",
                "manifest_backup",
            ):
                with self.subTest(field=field):
                    _craft_journal(directory, "RG-11", "output_installed", **{field: victim})
                    with self.assertRaises(PublicationRecoveryError):
                        recover_rg11_publication(manifest)
                    self.assertEqual(b"ATTACKER PAYLOAD\n", victim.read_bytes())
                    self.assertEqual(original_output, output.read_bytes())
                    self.assertEqual(original_manifest, manifest.read_bytes())
                    self.assertTrue(journal_path.exists())
            try:
                os.symlink(victim, directory / "linked-victim.txt")
            except OSError:
                pass  # symlinks unavailable on this platform
            else:
                _craft_journal(
                    directory, "RG-11", "output_backup",
                    output_temp=directory / "linked-victim.txt",
                )
                with self.assertRaises(PublicationRecoveryError):
                    recover_rg11_publication(manifest)
                self.assertEqual(b"ATTACKER PAYLOAD\n", victim.read_bytes())

    def test_journal_with_nonconforming_names_or_tokens_is_rejected(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            source, expected, manifest = self._copy_inputs(directory)
            output = directory / "rg-11.json"
            original_output = b"old RG-11 output\n"
            output.write_bytes(original_output)
            original_manifest = manifest.read_bytes()
            journal_path = directory / ".rg-11-publication.journal.json"
            crafted_cases = {
                "token_not_hex": lambda: _craft_journal(
                    directory, "RG-11", "output_backup", token="z" * 32
                ),
                "temp_base_mismatch": lambda: _craft_journal(
                    directory, "RG-11", "output_backup",
                    output_temp=directory / f".manifest.json.{'a' * 32}.tmp",
                ),
                "token_mismatch": lambda: _craft_journal(
                    directory, "RG-11", "output_backup",
                    manifest_temp=directory / f".manifest.json.{'b' * 32}.tmp",
                ),
                "output_equals_manifest": lambda: _craft_journal(
                    directory, "RG-11", "output_backup",
                    output_path=directory / "manifest.json",
                ),
                "dotdot_traversal": lambda: _craft_journal(
                    directory, "RG-11", "output_backup",
                    output_temp=directory.parent / f".rg-11.json.{'a' * 32}.tmp",
                ),
            }
            for label, craft in crafted_cases.items():
                with self.subTest(label=label):
                    crafted = craft()
                    raw = crafted.read_bytes()
                    with self.assertRaises(PublicationRecoveryError):
                        recover_rg11_publication(manifest)
                    self.assertEqual(raw, crafted.read_bytes())
                    self.assertEqual(original_output, output.read_bytes())
                    self.assertEqual(original_manifest, manifest.read_bytes())
                    self.assertTrue(journal_path.exists())
            # The original vanished without a backup: reject and preserve.
            output.unlink()
            crafted = _craft_journal(directory, "RG-11", "prepared")
            raw = crafted.read_bytes()
            with self.assertRaises(PublicationRecoveryError):
                recover_rg11_publication(manifest)
            self.assertEqual(raw, crafted.read_bytes())
            self.assertEqual(original_manifest, manifest.read_bytes())
            self.assertTrue(journal_path.exists())
            # A backup exists before its phase could have run: reject.
            output.write_bytes(original_output)
            (directory / f".rg-11.json.{'a' * 32}.bak").write_bytes(original_output)
            crafted = _craft_journal(directory, "RG-11", "prepared")
            raw = crafted.read_bytes()
            with self.assertRaises(PublicationRecoveryError):
                recover_rg11_publication(manifest)
            self.assertEqual(raw, crafted.read_bytes())
            self.assertEqual(original_output, output.read_bytes())
            self.assertEqual(original_manifest, manifest.read_bytes())
            self.assertTrue(journal_path.exists())

    def test_journal_with_backup_but_no_original_is_rejected(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            source, expected, manifest = self._copy_inputs(directory)
            output = directory / "rg-11.json"
            original_output = b"old RG-11 output\n"
            output.write_bytes(original_output)
            original_manifest = manifest.read_bytes()
            token = "a" * 32
            output_backup = directory / f".rg-11.json.{token}.bak"
            manifest_backup = directory / f".manifest.json.{token}.bak"
            output_backup.write_bytes(original_output)
            manifest_backup.write_bytes(original_manifest)
            journal_path = _craft_journal(
                directory, "RG-11", "output_backup",
                output_had_original=False, manifest_had_original=False,
            )
            with self.assertRaises(PublicationRecoveryError):
                recover_rg11_publication(manifest)
            self.assertEqual(original_output, output.read_bytes())
            self.assertEqual(original_manifest, manifest.read_bytes())
            self.assertTrue(journal_path.exists())
            self.assertEqual(original_output, output_backup.read_bytes())
            self.assertEqual(original_manifest, manifest_backup.read_bytes())

    def test_recovery_is_idempotent_and_converges(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            source, expected, manifest = self._copy_inputs(directory)
            output = directory / "rg-11.json"
            original_output = b"old RG-11 output\n"
            output.write_bytes(original_output)
            original_manifest = manifest.read_bytes()
            token = "a" * 32
            output_temp = directory / f".rg-11.json.{token}.tmp"
            manifest_temp = directory / f".manifest.json.{token}.tmp"
            output_backup = directory / f".rg-11.json.{token}.bak"
            manifest_backup = directory / f".manifest.json.{token}.bak"
            output_backup.write_bytes(original_output)
            manifest_backup.write_bytes(original_manifest)
            # Simulate a crash after both targets were installed: the new
            # files are in place, the backups hold the originals, and the
            # journal claims manifest_installed.
            output.write_bytes(b"new output bytes")
            manifest.write_bytes(b"new manifest bytes")
            _craft_journal(directory, "RG-11", "manifest_installed")

            self.assertTrue(recover_rg11_publication(manifest))
            self.assertEqual(original_output, output.read_bytes())
            self.assertEqual(original_manifest, manifest.read_bytes())
            self.assertFalse((directory / ".rg-11-publication.journal.json").exists())
            self.assertFalse(output_temp.exists())
            self.assertFalse(manifest_temp.exists())
            self.assertFalse(output_backup.exists())
            self.assertFalse(manifest_backup.exists())
            # Recovery is idempotent: a second run finds no journal and is a no-op.
            self.assertFalse(recover_rg11_publication(manifest))

    def test_journal_claiming_another_manifest_is_rejected(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            source, expected, manifest = self._copy_inputs(directory)
            output = directory / "rg-11.json"
            original_output = b"old RG-11 output\n"
            output.write_bytes(original_output)
            original_manifest = manifest.read_bytes()
            sibling = directory / "rg-04.json"
            sibling.write_bytes(b"sibling artifact payload\n")
            journal_path = directory / ".rg-11-publication.journal.json"
            crafted_cases = {
                "sibling_as_manifest": lambda: _craft_journal(
                    directory, "RG-11", "manifest_installed",
                    manifest_path=sibling,
                    manifest_had_original=False,
                ),
                "sibling_as_output": lambda: _craft_journal(
                    directory, "RG-11", "output_installed",
                    output_path=sibling,
                    output_had_original=False,
                ),
            }
            for label, craft in crafted_cases.items():
                with self.subTest(label=label):
                    crafted = craft()
                    raw = crafted.read_bytes()
                    with self.assertRaises(PublicationRecoveryError):
                        recover_rg11_publication(manifest)
                    self.assertEqual(raw, crafted.read_bytes())
                    self.assertEqual(b"sibling artifact payload\n", sibling.read_bytes())
                    self.assertEqual(original_output, output.read_bytes())
                    self.assertEqual(original_manifest, manifest.read_bytes())
                    self.assertTrue(journal_path.exists())

    def test_first_publication_commit_failure_keeps_committed_state(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            source, expected, manifest = self._copy_inputs(directory)
            output = directory / "rg-11.json"
            original_manifest = manifest.read_bytes()
            with self.assertRaisesRegex(RuntimeError, "post-commit failure"):
                publish_rg11(source, expected, output, manifest, fail_after_commit=True)
            # The transaction is committed: the journal is gone and the new
            # files are in place; nothing was rolled back.
            self.assertFalse((directory / ".rg-11-publication.journal.json").exists())
            self.assertEqual(expected.read_bytes(), output.read_bytes())
            published = json.loads(manifest.read_text(encoding="utf-8"))
            self.assertIn("RG-11", {item["case"] for item in published["cases"]})
            # 模板 manifest 已含本 case 条目，发布幂等重写后字节与模板相同；manifest 安装已由 .bak 残留断言证明，此处不做字节级比较（LF/CRLF 检出无关）。
            self.assertEqual([], list(directory.glob(".*.tmp")))
            # Only the manifest backup remains: the output had no original.
            self.assertEqual(1, len(list(directory.glob(".*.bak"))))

    def test_output_installed_and_manifest_backup_journals_recover(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            source, expected, manifest = self._copy_inputs(directory)
            output = directory / "rg-11.json"
            original_output = b"old RG-11 output\n"
            output.write_bytes(original_output)
            original_manifest = manifest.read_bytes()
            token = "a" * 32
            output_backup = directory / f".rg-11.json.{token}.bak"
            manifest_backup = directory / f".manifest.json.{token}.bak"
            with self.subTest(phase="output_installed"):
                # Crash after the output install: the new output is in place,
                # the original survives in .bak, and the journal claims
                # output_installed.
                output_backup.write_bytes(original_output)
                output.write_bytes(b"new output bytes")
                _craft_journal(directory, "RG-11", "output_installed")
                self.assertTrue(recover_rg11_publication(manifest))
                self.assertEqual(original_output, output.read_bytes())
                self.assertEqual(original_manifest, manifest.read_bytes())
                self.assertFalse(output_backup.exists())
                self.assertFalse(manifest_backup.exists())
            with self.subTest(phase="output_installed_journal_ahead"):
                # The journal claims output_installed but the install replace
                # never ran: the original is still in .bak and the temp file
                # survives. Recovery restores the original and cleans up.
                output_backup.write_bytes(original_output)
                output.unlink()
                output_temp = directory / f".rg-11.json.{token}.tmp"
                output_temp.write_bytes(b"new output bytes")
                _craft_journal(directory, "RG-11", "output_installed")
                self.assertTrue(recover_rg11_publication(manifest))
                self.assertEqual(original_output, output.read_bytes())
                self.assertEqual(original_manifest, manifest.read_bytes())
                self.assertFalse(output_temp.exists())
                self.assertFalse(output_backup.exists())
            with self.subTest(phase="manifest_backup"):
                # Crash after the manifest move: the new output is in place,
                # the original manifest survives in .bak, and the journal
                # claims manifest_backup.
                output_backup.write_bytes(original_output)
                output.write_bytes(b"new output bytes")
                manifest_backup.write_bytes(original_manifest)
                manifest.unlink()
                _craft_journal(directory, "RG-11", "manifest_backup")
                self.assertTrue(recover_rg11_publication(manifest))
                self.assertEqual(original_output, output.read_bytes())
                self.assertEqual(original_manifest, manifest.read_bytes())
                self.assertFalse(output_backup.exists())
                self.assertFalse(manifest_backup.exists())
            self.assertFalse((directory / ".rg-11-publication.journal.json").exists())



    def test_direct_v2_expected_must_be_byte_identical_to_source(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            source, expected, manifest = self._copy_inputs(directory)
            output = directory / "rg-11.json"
            original_manifest = manifest.read_bytes()
            original_expected = expected.read_bytes()
            tampered = {
                "trailing newline": original_expected + b"\n",
                "truncated last byte": original_expected[:-1],
                "one byte changed": original_expected[:10] + b"X" + original_expected[11:],
            }
            for label, data in tampered.items():
                with self.subTest(label=label):
                    expected.write_bytes(data)
                    with self.assertRaisesRegex(ValueError, "byte-identical"):
                        publish_rg11(source, expected, output, manifest)
                    self.assertFalse(output.exists())
                    self.assertEqual(original_manifest, manifest.read_bytes())
                    self.assertFalse((directory / ".rg-11-publication.journal.json").exists())
                    self.assertEqual([], list(directory.glob(".*.tmp")))
                    self.assertEqual([], list(directory.glob(".*.bak")))

    def test_gate_failure_stops_publication_with_zero_mutation(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            source, expected, manifest = self._copy_inputs(directory)
            output = directory / "rg-11.json"
            before = _snapshot(directory)
            with patch(
                "golden_cases.rg11_publication.verify_publication_integrity",
                side_effect=PublicationIntegrityError("injected gate failure"),
            ) as gate_mock, patch(
                "golden_cases.rg11_publication._load_json"
            ) as load_mock:
                with self.assertRaises(PublicationIntegrityError):
                    publish_rg11(source, expected, output, manifest)
                gate_mock.assert_called_once()
                self.assertEqual(manifest.resolve(), gate_mock.call_args[0][0])
                load_mock.assert_not_called()
            self.assertEqual(before, _snapshot(directory))

    def test_published_output_is_lf_only(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            source, expected, manifest = self._copy_inputs(directory)
            output = directory / "rg-11.json"
            result = publish_rg11(source, expected, output, manifest)
            self.assertTrue(result.changed)
            output_bytes = output.read_bytes()
            # D-090 LF contract: publication output bytes are UTF-8 + LF and
            # reproduce the expected artifact bytes exactly.
            self.assertNotIn(b"\r", output_bytes)
            self.assertEqual(b"\n", output_bytes[-1:])
            self.assertEqual(expected.read_bytes(), output_bytes)
            self.assertNotIn(b"\r", expected.read_bytes())

    def test_expected_artifact_has_22_operations(self):
        document = json.loads(EXPECTED_PATH.read_text(encoding="utf-8"))
        self.assertEqual(22, len(document["operations"]))
        self.assertEqual(3, len(document["roots"]))
        self.assertEqual(25, len(document["states"]))
        counts: dict[str, int] = {}
        for operation in document["operations"]:
            status = operation["outcome"]["status"]
            counts[status] = counts.get(status, 0) + 1
        self.assertEqual({"accepted": 11, "no_change": 1, "rejected": 10}, counts)


if __name__ == "__main__":
    unittest.main()
