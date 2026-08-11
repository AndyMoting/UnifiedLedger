"""D-090 publication integrity gate regression tests.

Covers the shared pre-publish integrity gate
(``golden_cases.publication_integrity``) and the minimal publisher wiring:

- LF corpus success path (synthetic fixture, no worktree line-ending
  dependence);
- CRLF and bare-CR rejection with zero publication mutation;
- fresh-checkout consistency: registered raw-byte hashes reproduce the Git
  LF blobs (the test may use Git; the gate itself never does);
- repeated execution idempotence with no state residue;
- full real-manifest per-case verification in an LF environment built from
  ``git show`` blob bytes, independent of worktree line endings;
- publisher wiring: a failing gate stops the publisher before it reads the
  source, and all ten publishers call the gate at the same unconditional
  seam (after recovery completes, before the stale-dotfile sweep and before
  ``_load_json(source_path)``);
- fail-closed registration-relation and corruption rejection.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from golden_cases.publication_integrity import (
    PublicationIntegrityError,
    canonical_bytes,
    verify_publication_integrity,
)
from golden_cases.rg03_publication import publish_rg03

ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = ROOT / "golden" / "rules-v2" / "manifest.json"
PUBLISHERS = ("rg01", "rg02", "rg03", "rg05", "rg06", "rg08", "rg09", "rg10", "rg11", "rg12")


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _write_lf(path: Path, text: str) -> None:
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def _git_show(repo_path: str) -> bytes:
    return subprocess.run(
        ["git", "show", f"HEAD:{repo_path}"], capture_output=True, check=True
    ).stdout


def _snapshot(root: Path) -> dict[str, bytes]:
    return {
        str(path.relative_to(root)): path.read_bytes()
        for path in sorted(root.rglob("*"))
        if path.is_file()
    }


def _make_corpus(root: Path, case_id: str = "RG-01") -> Path:
    """Create a single-case LF publication corpus under ``root``.

    Returns the manifest path. All hashes are computed from the LF bytes
    actually written, so the corpus is self-consistent and worktree line
    endings do not matter.
    """
    lower = case_id.lower()
    source_path = root / "golden" / "rules" / f"{lower}.json"
    expected_path = root / "docs" / "migrations" / "golden-v2" / f"{lower}-expected.json"
    output_path = root / "golden" / "rules-v2" / f"{lower}.json"
    source_path.parent.mkdir(parents=True, exist_ok=True)
    expected_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    source_doc = {"schema_version": 1, "case": {"id": case_id}}
    expected_doc = {
        "contract": "unifiedledger.golden-case",
        "contract_version": "2.0.0",
        "case": {"id": case_id},
        "operations": [],
        "roots": [],
        "states": [],
    }
    source_bytes = (json.dumps(source_doc, indent=2) + "\n").encode("utf-8")
    expected_bytes = (json.dumps(expected_doc, indent=2) + "\n").encode("utf-8")
    source_path.write_bytes(source_bytes)
    expected_path.write_bytes(expected_bytes)
    output_path.write_bytes(expected_bytes)

    source_sha = _sha256(source_bytes)
    expected_sha = _sha256(expected_bytes)
    canonical = _sha256(canonical_bytes(expected_doc))
    manifest = {
        "canonicalization": {
            "encoding": "UTF-8",
            "key_order": "sorted",
            "separators": [",", ":"],
            "scope": "parsed expected/output JSON",
        },
        "contract": "unifiedledger.golden-case",
        "contract_version": "2.0.0",
        "publication_status": "published",
        "cases": [
            {
                "approval_status": "approved",
                "case": case_id,
                "canonical_sha256": canonical,
                "expected_byte_sha256": expected_sha,
                "expected_path": f"docs/migrations/golden-v2/{lower}-expected.json",
                "hashes": {
                    "canonical_sha256": canonical,
                    "expected_sha256": expected_sha,
                    "output_sha256": expected_sha,
                    "source_sha256": source_sha,
                },
                "object_counts": {"operations": 0, "roots": 0, "states": 0},
                "operation_status_counts": {"accepted": 0, "no_change": 0, "rejected": 0},
                "output_path": f"golden/rules-v2/{lower}.json",
                "publication_status": "published",
                "source_byte_sha256": source_sha,
                "source_path": f"golden/rules/{lower}.json",
                "source_sha256": source_sha,
            }
        ],
    }
    manifest_path = root / "golden" / "rules-v2" / "manifest.json"
    _write_lf(manifest_path, json.dumps(manifest, indent=2) + "\n")
    return manifest_path


class PublicationIntegrityTests(unittest.TestCase):
    def test_lf_corpus_passes(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            manifest = _make_corpus(root)
            verify_publication_integrity(manifest, repo_root=root)

    def test_crlf_rejected_with_zero_mutation(self):
        for label, key in (
            ("manifest", None),
            ("source", "source_path"),
            ("expected", "expected_path"),
            ("output", "output_path"),
        ):
            with self.subTest(target=label), tempfile.TemporaryDirectory() as name:
                root = Path(name)
                manifest = _make_corpus(root)
                if key is None:
                    target = manifest
                else:
                    entry = json.loads(manifest.read_text(encoding="utf-8"))["cases"][0]
                    target = root / Path(entry[key])
                target.write_bytes(target.read_bytes().replace(b"\n", b"\r\n"))
                before = _snapshot(root)
                with self.assertRaises(PublicationIntegrityError):
                    verify_publication_integrity(manifest, repo_root=root)
                self.assertEqual(before, _snapshot(root))

    def test_bare_cr_rejected_with_zero_mutation(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            manifest = _make_corpus(root)
            entry = json.loads(manifest.read_text(encoding="utf-8"))["cases"][0]
            source = root / Path(entry["source_path"])
            source.write_bytes(source.read_bytes().replace(b"\n", b"\r"))
            before = _snapshot(root)
            with self.assertRaises(PublicationIntegrityError):
                verify_publication_integrity(manifest, repo_root=root)
            self.assertEqual(before, _snapshot(root))

    def test_fresh_checkout_recomputes_registered_hashes(self):
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        total = 0
        for case in manifest["cases"]:
            hashes = case["hashes"]
            for base, path in (
                ("source", case["source_path"]),
                ("expected", case["expected_path"]),
                ("output", case["output_path"]),
            ):
                digest = _sha256(_git_show(path))
                self.assertEqual(hashes[f"{base}_sha256"], digest)
                total += 1
        # One source/expected/output raw-byte hash per registered case (12
        # cases after the RG-08 publication, 3 hashes each).
        self.assertEqual(3 * len(manifest["cases"]), total)
        for case in manifest["cases"]:
            expected_bytes = _git_show(case["expected_path"])
            canonical = _sha256(
                canonical_bytes(json.loads(expected_bytes.decode("utf-8")))
            )
            self.assertEqual(case["canonical_sha256"], canonical)
            self.assertEqual(case["hashes"]["canonical_sha256"], canonical)

    def test_repeated_execution_is_idempotent(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            manifest = _make_corpus(root)
            before = _snapshot(root)
            verify_publication_integrity(manifest, repo_root=root)
            verify_publication_integrity(manifest, repo_root=root)
            self.assertEqual(before, _snapshot(root))

    def test_real_manifest_passes_in_lf_environment(self):
        manifest_bytes = MANIFEST_PATH.read_bytes()
        self.assertNotIn(
            b"\r",
            manifest_bytes,
            "manifest must be LF-only after the D-090 raw-byte hash metadata fix",
        )
        manifest = json.loads(manifest_bytes.decode("utf-8"))
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            for case in manifest["cases"]:
                for key in ("source_path", "expected_path", "output_path"):
                    target = root / Path(case[key])
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(_git_show(case[key]))
            rel_manifest = Path("golden/rules-v2/manifest.json")
            (root / rel_manifest).parent.mkdir(parents=True, exist_ok=True)
            (root / rel_manifest).write_bytes(manifest_bytes)
            verify_publication_integrity(root / rel_manifest, repo_root=root)

    def test_publisher_stops_before_reading_source_when_gate_fails(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            source = directory / "v1-rg-03.json"
            expected = directory / "expected-rg-03.json"
            manifest = directory / "manifest.json"
            output = directory / "rg-03.json"
            source.write_bytes(b'{"schema_version": 1, "case": {"id": "RG-03"}}\n')
            expected.write_bytes(
                b'{"contract": "unifiedledger.golden-case", "contract_version": "2.0.0"}\n'
            )
            manifest.write_bytes(
                b'{"contract": "unifiedledger.golden-case", "contract_version": "2.0.0", "cases": []}\n'
            )
            before = _snapshot(directory)
            with patch(
                "golden_cases.rg03_publication.verify_publication_integrity",
                side_effect=PublicationIntegrityError("injected gate failure"),
            ) as gate_mock, patch(
                "golden_cases.rg03_publication._load_json"
            ) as load_mock:
                with self.assertRaises(PublicationIntegrityError):
                    publish_rg03(source, expected, output, manifest)
                gate_mock.assert_called_once()
                self.assertEqual(manifest.resolve(), gate_mock.call_args[0][0])
                load_mock.assert_not_called()
            self.assertEqual(before, _snapshot(directory))

    def test_all_publishers_wire_gate_at_unconditional_seam(self):
        for case in PUBLISHERS:
            with self.subTest(publisher=case):
                path = ROOT / "tools" / "python" / "golden_cases" / f"{case}_publication.py"
                lines = path.read_text(encoding="utf-8").splitlines()
                self.assertIn(
                    "from .publication_integrity import verify_publication_integrity",
                    lines,
                )
                recover_idx = next(
                    i for i, line in enumerate(lines)
                    if line.strip().startswith("recovered = recover_rg")
                )
                gate_idx = next(
                    i for i, line in enumerate(lines)
                    if line.strip().startswith("verify_publication_integrity(")
                )
                sweep_if_idx = next(
                    i for i, line in enumerate(lines)
                    if line.strip().startswith("if not recovered:")
                )
                sweep_idx = next(
                    i for i, line in enumerate(lines)
                    if "sweep_stale_dotfiles" in line and "def " not in line
                )
                load_idx = next(
                    i for i, line in enumerate(lines)
                    if "_load_json(source_path)" in line
                )
                # D-090 timing: recovery completes first, then the full
                # integrity gate, then the stale-dotfile sweep, then any new
                # publication transaction (the gate must precede the sweep).
                self.assertLess(recover_idx, gate_idx)
                self.assertLess(gate_idx, sweep_if_idx)
                self.assertLess(sweep_if_idx, sweep_idx)
                self.assertLess(sweep_idx, load_idx)
                base_indent = len(lines[recover_idx]) - len(lines[recover_idx].lstrip())
                gate_indent = len(lines[gate_idx]) - len(lines[gate_idx].lstrip())
                self.assertEqual(
                    base_indent, gate_indent, "gate call must be unconditional, not inside the if body"
                )

    def test_registration_relation_violations_fail_closed(self):
        cases = {
            "escaped_source": ("source_path", "golden/rules/../outside.json"),
            "wrong_parent": ("source_path", "golden/rules-v2/rg-01.json"),
            "wrong_source_name": ("source_path", "golden/rules/rg-01-expected.json"),
            "wrong_expected_parent": ("expected_path", "golden/rules/rg-01.json"),
            "wrong_output_parent": ("output_path", "docs/migrations/golden-v2/rg-01.json"),
            "output_aliases_manifest": ("output_path", "golden/rules-v2/manifest.json"),
        }
        for label, (field, value) in cases.items():
            with self.subTest(violation=label), tempfile.TemporaryDirectory() as name:
                root = Path(name)
                manifest = _make_corpus(root)
                document = json.loads(manifest.read_text(encoding="utf-8"))
                document["cases"][0][field] = value
                _write_lf(manifest, json.dumps(document, indent=2) + "\n")
                with self.assertRaises(PublicationIntegrityError):
                    verify_publication_integrity(manifest, repo_root=root)

    def test_missing_file_rejected_fail_closed(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            manifest = _make_corpus(root)
            entry = json.loads(manifest.read_text(encoding="utf-8"))["cases"][0]
            expected = root / Path(entry["expected_path"])
            expected.unlink()
            before = _snapshot(root)
            with self.assertRaises(PublicationIntegrityError):
                verify_publication_integrity(manifest, repo_root=root)
            self.assertEqual(before, _snapshot(root))

    def test_non_utf8_bytes_rejected_fail_closed(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            manifest = _make_corpus(root)
            entry = json.loads(manifest.read_text(encoding="utf-8"))["cases"][0]
            source = root / Path(entry["source_path"])
            source.write_bytes(b"\xff\xfe\x00 not utf-8")
            before = _snapshot(root)
            with self.assertRaises(PublicationIntegrityError):
                verify_publication_integrity(manifest, repo_root=root)
            self.assertEqual(before, _snapshot(root))

    def test_case_id_filename_mismatch_rejected_fail_closed(self):
        cases = {
            "source": ("source_path", "golden/rules/rg-02.json"),
            "expected": ("expected_path", "docs/migrations/golden-v2/rg-02-expected.json"),
            "output": ("output_path", "golden/rules-v2/rg-02.json"),
        }
        for label, (field, value) in cases.items():
            with self.subTest(role=label), tempfile.TemporaryDirectory() as name:
                root = Path(name)
                manifest = _make_corpus(root)
                document = json.loads(manifest.read_text(encoding="utf-8"))
                document["cases"][0][field] = value
                _write_lf(manifest, json.dumps(document, indent=2) + "\n")
                with self.assertRaises(PublicationIntegrityError):
                    verify_publication_integrity(manifest, repo_root=root)

    def test_corruption_rejected_fail_closed(self):
        with self.subTest(corruption="expected_hash_tamper"), tempfile.TemporaryDirectory() as name:
            root = Path(name)
            manifest = _make_corpus(root)
            entry = json.loads(manifest.read_text(encoding="utf-8"))["cases"][0]
            expected = root / Path(entry["expected_path"])
            expected.write_bytes(expected.read_bytes() + b"\n")
            with self.assertRaises(PublicationIntegrityError):
                verify_publication_integrity(manifest, repo_root=root)
        with self.subTest(corruption="output_bytes_differ"), tempfile.TemporaryDirectory() as name:
            root = Path(name)
            manifest = _make_corpus(root)
            document = json.loads(manifest.read_text(encoding="utf-8"))
            entry = document["cases"][0]
            output = root / Path(entry["output_path"])
            output.write_bytes(b'{"tampered": true}\n')
            entry["hashes"]["output_sha256"] = _sha256(output.read_bytes())
            _write_lf(manifest, json.dumps(document, indent=2) + "\n")
            with self.assertRaises(PublicationIntegrityError):
                verify_publication_integrity(manifest, repo_root=root)
        with self.subTest(corruption="canonical_hash_tamper"), tempfile.TemporaryDirectory() as name:
            root = Path(name)
            manifest = _make_corpus(root)
            document = json.loads(manifest.read_text(encoding="utf-8"))
            entry = document["cases"][0]
            expected = root / Path(entry["expected_path"])
            tampered_doc = {
                "contract": "unifiedledger.golden-case",
                "contract_version": "2.0.0",
                "case": {"id": "RG-01"},
                "operations": [],
                "roots": [],
                "states": [],
                "tampered": True,
            }
            tampered_bytes = (json.dumps(tampered_doc, indent=2) + "\n").encode("utf-8")
            expected.write_bytes(tampered_bytes)
            entry["expected_byte_sha256"] = _sha256(tampered_bytes)
            entry["hashes"]["expected_sha256"] = _sha256(tampered_bytes)
            _write_lf(manifest, json.dumps(document, indent=2) + "\n")
            with self.assertRaises(PublicationIntegrityError):
                verify_publication_integrity(manifest, repo_root=root)


if __name__ == "__main__":
    unittest.main()
