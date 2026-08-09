"""Transactional publication helpers for the approved RG-03 v2 candidate."""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import tempfile
from typing import Any
from uuid import uuid4

from .v2 import validate_golden_case_v2


CASE_ID = "RG-03"
CONTRACT = "unifiedledger.golden-case"
CONTRACT_VERSION = "2.0.0"


@dataclass(frozen=True)
class PublicationResult:
    changed: bool
    source_sha256: str
    expected_sha256: str
    canonical_sha256: str
    output_sha256: str
    object_counts: dict[str, int]
    operation_status_counts: dict[str, int]


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _canonical_bytes(document: Any) -> bytes:
    return json.dumps(
        document,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def _load_json(path: Path) -> tuple[bytes, Any]:
    data = path.read_bytes()
    return data, json.loads(data.decode("utf-8"))


def _object_counts(document: dict[str, Any]) -> dict[str, int]:
    return {
        "operations": len(document["operations"]),
        "roots": len(document["roots"]),
        "states": len(document["states"]),
    }


def _operation_status_counts(document: dict[str, Any]) -> dict[str, int]:
    counts = {"accepted": 0, "no_change": 0, "rejected": 0}
    for operation in document["operations"]:
        status = operation["outcome"]["status"]
        if status not in counts:
            raise ValueError(f"unsupported RG-03 operation status: {status}")
        counts[status] += 1
    return counts


def _manifest_entry(
    source_sha256: str,
    expected_sha256: str,
    canonical_sha256: str,
    output_sha256: str,
    object_counts: dict[str, int],
    operation_status_counts: dict[str, int],
) -> dict[str, Any]:
    hashes = {
        "canonical_sha256": canonical_sha256,
        "expected_sha256": expected_sha256,
        "output_sha256": output_sha256,
        "source_sha256": source_sha256,
    }
    return {
        "approval_status": "approved",
        "case": CASE_ID,
        "discovery": {
            "approval": "explicit user approval",
            "comparison": "20-operation full comparison",
            "explicit_user_approval": True,
            "full_comparison_operation_count": object_counts["operations"],
            "full_state_comparison": True,
            "status": "approved expected output independently reviewed and explicitly approved",
        },
        "canonical_sha256": canonical_sha256,
        "expected_byte_sha256": expected_sha256,
        "expected_path": "docs/migrations/golden-v2/rg-03-expected.json",
        "hashes": hashes,
        "object_counts": object_counts,
        "operation_status_counts": operation_status_counts,
        "output_path": "golden/rules-v2/rg-03.json",
        "publication_status": "published",
        "source_byte_sha256": source_sha256,
        "source_path": "golden/rules/rg-03.json",
        "source_sha256": source_sha256,
    }


def _write_fsync(path: Path, data: bytes) -> None:
    with path.open("wb") as handle:
        handle.write(data)
        handle.flush()
        os.fsync(handle.fileno())


def _write_json_fsync(path: Path, document: Any) -> None:
    _write_fsync(
        path,
        (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8"),
    )


def _same_bytes(path: Path, data: bytes) -> bool:
    return path.is_file() and path.read_bytes() == data


def _journal_path(manifest_path: Path) -> Path:
    return manifest_path.with_name(".rg-03-publication.journal.json")


def _remove(path: Path) -> None:
    try:
        path.unlink()
    except FileNotFoundError:
        pass


def _restore_path(path: Path, backup: Path, had_original: bool) -> None:
    if path.exists():
        _remove(path)
    if had_original:
        os.replace(backup, path)


def _restore_published_target(path: Path, backup: Path, had_original: bool) -> None:
    """Restore one publication target from its backup when that backup exists.

    The journal phase is advanced before each ``os.replace`` so an interrupted
    transaction can never leave a moved original only in ``.bak`` while the
    journal still claims ``prepared``. This guard additionally recovers the
    legacy crash window: if the target path is missing but its backup exists,
    the original is restored from the backup instead of being deleted.
    """
    if not backup.is_file():
        return
    _restore_path(path, backup, had_original)


def recover_rg03_publication(manifest_path: Path) -> bool:
    """Recover an interrupted publication transaction, if its journal exists."""
    journal_path = _journal_path(manifest_path)
    if not journal_path.exists():
        return False

    journal = json.loads(journal_path.read_text(encoding="utf-8"))
    output_path = Path(journal["output_path"])
    recorded_manifest = Path(journal["manifest_path"])
    if recorded_manifest != manifest_path:
        raise ValueError("RG-03 publication journal belongs to another manifest")
    output_temp = Path(journal["output_temp"])
    manifest_temp = Path(journal["manifest_temp"])
    output_backup = Path(journal["output_backup"])
    manifest_backup = Path(journal["manifest_backup"])
    # The transaction commits only when the journal is removed. Any surviving
    # journal restores every original from its backup when that backup exists;
    # a missing backup means that target was never moved. A crash after both
    # swaps but before cleanup is also rolled back safely: with no backups the
    # restore is a no-op and the already-installed new files remain.
    _restore_published_target(
        output_path,
        output_backup,
        bool(journal["output_had_original"]),
    )
    _restore_published_target(
        manifest_path,
        manifest_backup,
        bool(journal["manifest_had_original"]),
    )
    _remove(output_temp)
    _remove(manifest_temp)
    _remove(output_backup)
    _remove(manifest_backup)
    _remove(journal_path)
    return True


def publish_rg03(
    source_path: Path,
    expected_path: Path,
    output_path: Path,
    manifest_path: Path,
    *,
    fail_after_output_backup: bool = False,
    fail_after_output_swap: bool = False,
    fail_after_manifest_swap: bool = False,
) -> PublicationResult:
    """Publish one RG-03 candidate with rollback and idempotent replay."""
    source_path = source_path.resolve()
    expected_path = expected_path.resolve()
    output_path = output_path.resolve()
    manifest_path = manifest_path.resolve()
    if output_path == source_path or output_path == expected_path:
        raise ValueError("RG-03 publication target must be distinct from inputs")
    if output_path.parent != manifest_path.parent:
        raise ValueError("RG-03 output and manifest must share a publication directory")
    output_path.parent.mkdir(parents=True, exist_ok=True)

    recover_rg03_publication(manifest_path)
    source_bytes, source = _load_json(source_path)
    expected_bytes, expected = _load_json(expected_path)
    if source.get("schema_version") != 1 or source.get("case", {}).get("id") != CASE_ID:
        raise ValueError("RG-03 source is not the frozen v1 fixture")
    if expected.get("contract") != CONTRACT or expected.get("contract_version") != CONTRACT_VERSION:
        raise ValueError("RG-03 expected has the wrong contract")
    if expected.get("case", {}).get("id") != CASE_ID:
        raise ValueError("RG-03 expected has the wrong case id")
    if expected.get("case", {}).get("approval_status") != "approved":
        raise ValueError("RG-03 expected must be approved before publication")
    validate_golden_case_v2(expected)
    canonical_sha256 = _sha256(_canonical_bytes(expected))
    source_sha256 = _sha256(source_bytes)
    expected_sha256 = _sha256(expected_bytes)
    object_counts = _object_counts(expected)
    operation_status_counts = _operation_status_counts(expected)
    entry = _manifest_entry(
        source_sha256,
        expected_sha256,
        canonical_sha256,
        expected_sha256,
        object_counts,
        operation_status_counts,
    )

    if not manifest_path.is_file():
        raise ValueError("RG-03 publication requires the existing v2 manifest")
    manifest_bytes, manifest = _load_json(manifest_path)
    if manifest.get("contract") != CONTRACT or manifest.get("contract_version") != CONTRACT_VERSION:
        raise ValueError("existing v2 manifest has the wrong contract")
    cases = manifest.get("cases")
    if not isinstance(cases, list):
        raise ValueError("existing v2 manifest cases must be a list")
    old_entry = next((item for item in cases if item.get("case") == CASE_ID), None)
    output_is_current = _same_bytes(output_path, expected_bytes)
    manifest_is_current = old_entry == entry
    if output_is_current and manifest_is_current:
        return PublicationResult(
            changed=False,
            source_sha256=source_sha256,
            expected_sha256=expected_sha256,
            canonical_sha256=canonical_sha256,
            output_sha256=expected_sha256,
            object_counts=object_counts,
            operation_status_counts=operation_status_counts,
        )

    if not _same_bytes(source_path, source_bytes):
        raise ValueError("RG-03 source changed during publication preparation")
    updated_manifest = dict(manifest)
    updated_cases = [item for item in cases if item.get("case") != CASE_ID]
    updated_cases.append(entry)
    updated_cases.sort(key=lambda item: item.get("case", ""))
    updated_manifest["cases"] = updated_cases

    token = uuid4().hex
    output_temp = output_path.with_name(f".{output_path.name}.{token}.tmp")
    manifest_temp = manifest_path.with_name(f".{manifest_path.name}.{token}.tmp")
    output_backup = output_path.with_name(f".{output_path.name}.{token}.bak")
    manifest_backup = manifest_path.with_name(f".{manifest_path.name}.{token}.bak")
    journal_path = _journal_path(manifest_path)
    journal = {
        "phase": "prepared",
        "output_path": str(output_path),
        "manifest_path": str(manifest_path),
        "output_temp": str(output_temp),
        "manifest_temp": str(manifest_temp),
        "output_backup": str(output_backup),
        "manifest_backup": str(manifest_backup),
        "output_had_original": output_path.exists(),
        "manifest_had_original": manifest_path.exists(),
    }
    _write_json_fsync(journal_path, journal)
    try:
        _write_fsync(output_temp, expected_bytes)
        _write_json_fsync(manifest_temp, updated_manifest)
        # Advance the journal phase before every os.replace so an interrupted
        # run can always distinguish "originals still in place" from "originals
        # moved to backups" and restore them instead of deleting them.
        journal["phase"] = "backup_output_moved"
        _write_json_fsync(journal_path, journal)
        if output_path.exists():
            os.replace(output_path, output_backup)
        journal["phase"] = "backup_manifest_moved"
        _write_json_fsync(journal_path, journal)
        if fail_after_output_backup:
            raise RuntimeError("injected RG-03 output backup failure")
        if manifest_path.exists():
            os.replace(manifest_path, manifest_backup)
        journal["phase"] = "output_installed"
        _write_json_fsync(journal_path, journal)
        os.replace(output_temp, output_path)
        if fail_after_output_swap:
            raise RuntimeError("injected RG-03 output swap failure")
        journal["phase"] = "manifest_installed"
        _write_json_fsync(journal_path, journal)
        os.replace(manifest_temp, manifest_path)
        if fail_after_manifest_swap:
            raise RuntimeError("injected RG-03 manifest swap failure")
        _remove(output_backup)
        _remove(manifest_backup)
        _remove(journal_path)
    except Exception:
        _restore_published_target(
            output_path,
            output_backup,
            bool(journal["output_had_original"]),
        )
        _restore_published_target(
            manifest_path,
            manifest_backup,
            bool(journal["manifest_had_original"]),
        )
        _remove(output_temp)
        _remove(manifest_temp)
        _remove(output_backup)
        _remove(manifest_backup)
        _remove(journal_path)
        raise

    return PublicationResult(
        changed=True,
        source_sha256=source_sha256,
        expected_sha256=expected_sha256,
        canonical_sha256=canonical_sha256,
        output_sha256=_sha256(output_path.read_bytes()),
        object_counts=object_counts,
        operation_status_counts=operation_status_counts,
    )


__all__ = ["PublicationResult", "publish_rg03", "recover_rg03_publication"]
