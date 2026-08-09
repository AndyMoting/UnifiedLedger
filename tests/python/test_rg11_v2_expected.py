"""RG-11 direct-v2 expected artifact: frozen-contract copy and publication-shape regression tests.

Authorities
-----------
- ``golden/rules/rg-11.json`` (frozen direct-v2 contract, contract_version
  2.0.0, approved at commit ``efbb13a`` per D-085): the 22 operation
  documents across 3 roots.  RG-11 is a direct-v2 scenario with no v1
  mapping and no adapter by design (D-085), so the expected artifact is the
  frozen contract itself, byte for byte.
- ``docs/migrations/golden-v2/rg-11-expected.json``: the expected artifact
  under test.  It must be an exact byte copy of the frozen contract and is
  never re-serialized.
- ``tools/python/golden_cases/v2.py``: ``validate_golden_case_v2``, the
  validation gate; RG-11 is in ``supported_transaction_types``.
- ``schemas/golden-case-v2.schema.json``: contract 2.0.0 (read-only).
- ``tools/python/golden_cases/rg11_publication.py``: the publication tool
  whose manifest entry registers ``source_sha256 == expected_byte_sha256``
  for the direct-v2 shape and computes ``canonical_sha256`` separately from
  the sort_keys + (",", ":") UTF-8 serialization.

The tests run entirely against the real frozen contract and the expected
artifact, and any manifest write happens only inside a tempfile directory.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from golden_cases import validate_golden_case_v2
from golden_cases.rg11_publication import publish_rg11

ROOT = Path(__file__).resolve().parents[2]
CONTRACT_PATH = ROOT / "golden" / "rules" / "rg-11.json"
EXPECTED_PATH = ROOT / "docs" / "migrations" / "golden-v2" / "rg-11-expected.json"
MANIFEST_PATH = ROOT / "golden" / "rules-v2" / "manifest.json"

# Counted directly from the frozen contract (D-085:1105 and the RG-11
# closure proposal raw-operation registry agree).
EXPECTED_OBJECT_COUNTS = {"operations": 22, "roots": 3, "states": 25}
EXPECTED_STATUS_COUNTS = {"accepted": 11, "no_change": 1, "rejected": 10}


def canonical_sha256(document) -> str:
    """sort_keys + (",", ":") UTF-8 serialization hash of the v2 document."""
    return hashlib.sha256(
        json.dumps(
            document,
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()


class Rg11V2ExpectedTests(unittest.TestCase):
    def setUp(self) -> None:
        self.contract_bytes = CONTRACT_PATH.read_bytes()
        self.expected_bytes = EXPECTED_PATH.read_bytes()
        self.contract = json.loads(self.contract_bytes.decode("utf-8"))
        self.expected = json.loads(self.expected_bytes.decode("utf-8"))

    def test_expected_is_byte_copy_of_frozen_contract(self):
        self.assertEqual(self.expected_bytes, self.contract_bytes)
        self.assertEqual(
            hashlib.sha256(self.expected_bytes).hexdigest(),
            hashlib.sha256(self.contract_bytes).hexdigest(),
        )

    def test_expected_passes_validate_golden_case_v2(self):
        validate_golden_case_v2(self.expected)

    def test_expected_contract_identity_and_approval(self):
        self.assertEqual(self.expected["contract"], "unifiedledger.golden-case")
        self.assertEqual(self.expected["contract_version"], "2.0.0")
        self.assertEqual(self.expected["case"]["id"], "RG-11")
        self.assertEqual(self.expected["case"]["approval_status"], "approved")

    def test_publication_shape_registers_source_equal_expected_byte_hash(self):
        byte_sha256 = hashlib.sha256(self.expected_bytes).hexdigest()
        canonical = canonical_sha256(self.expected)
        # The canonical serialization re-encodes the document, so its hash
        # differs from the byte hash of the frozen contract file.
        self.assertNotEqual(canonical, byte_sha256)
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            source = directory / "contract-rg-11.json"
            expected = directory / "expected-rg-11.json"
            manifest = directory / "manifest.json"
            output = directory / "rg-11.json"
            source.write_bytes(self.contract_bytes)
            expected.write_bytes(self.expected_bytes)
            manifest.write_bytes(MANIFEST_PATH.read_bytes())

            result = publish_rg11(source, expected, output, manifest)
            self.assertTrue(result.changed)
            self.assertEqual(byte_sha256, result.source_sha256)
            self.assertEqual(byte_sha256, result.expected_sha256)
            self.assertEqual(canonical, result.canonical_sha256)
            self.assertNotEqual(canonical, result.expected_sha256)

            published = json.loads(manifest.read_text(encoding="utf-8"))
            entry = next(
                item for item in published["cases"] if item["case"] == "RG-11"
            )
            # direct-v2 manifest registration: the source byte hash equals the
            # expected byte hash, and the canonical hash stays distinct.
            self.assertEqual(entry["source_sha256"], entry["expected_byte_sha256"])
            self.assertEqual(entry["source_byte_sha256"], byte_sha256)
            self.assertEqual(entry["expected_byte_sha256"], byte_sha256)
            self.assertEqual(entry["canonical_sha256"], canonical)
            self.assertNotEqual(entry["canonical_sha256"], byte_sha256)
            self.assertEqual(
                entry["hashes"],
                {
                    "canonical_sha256": canonical,
                    "expected_sha256": byte_sha256,
                    "output_sha256": byte_sha256,
                    "source_sha256": byte_sha256,
                },
            )
            self.assertEqual(
                entry["object_counts"], EXPECTED_OBJECT_COUNTS
            )
            self.assertEqual(
                entry["operation_status_counts"], EXPECTED_STATUS_COUNTS
            )

    def test_inventory_and_status_counts_from_contract(self):
        self.assertEqual(len(self.expected["operations"]), EXPECTED_OBJECT_COUNTS["operations"])
        self.assertEqual(len(self.expected["roots"]), EXPECTED_OBJECT_COUNTS["roots"])
        self.assertEqual(len(self.expected["states"]), EXPECTED_OBJECT_COUNTS["states"])
        status_counts = {
            status: sum(
                operation["outcome"]["status"] == status
                for operation in self.expected["operations"]
            )
            for status in ("accepted", "no_change", "rejected")
        }
        self.assertEqual(status_counts, EXPECTED_STATUS_COUNTS)


if __name__ == "__main__":
    unittest.main()
