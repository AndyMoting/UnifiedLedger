"""Transactional publication helpers for the approved RG-11 direct-v2 candidate."""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
from typing import Any
from uuid import uuid4

from .publication_integrity import verify_publication_integrity
from .v2 import validate_golden_case_v2


CASE_ID = "RG-11"
CONTRACT = "unifiedledger.golden-case"
CONTRACT_VERSION = "2.0.0"

_PHASES = (
    "prepared",
    "output_backup",
    "output_installed",
    "manifest_backup",
    "manifest_installed",
)
_OUTPUT_INSTALLED_PHASES = ("output_installed", "manifest_backup", "manifest_installed")
_DOTFILE_PATTERN = re.compile(r"^\.(?P<base>.+)\.(?P<token>[0-9a-f]{32})\.(?P<suffix>tmp|bak)$")


class PublicationRecoveryError(RuntimeError):
    """A publication journal failed validation or recovery could not complete.

    The journal is preserved and no further file actions are taken, so the
    failure can be inspected and recovery retried.
    """


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
            raise ValueError(f"unsupported RG-11 operation status: {status}")
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
            "comparison": "22-operation full comparison",
            "explicit_user_approval": True,
            "full_comparison_operation_count": object_counts["operations"],
            "full_state_comparison": True,
            "status": "approved expected output independently reviewed and explicitly approved",
        },
        "canonical_sha256": canonical_sha256,
        "expected_byte_sha256": expected_sha256,
        "expected_path": "docs/migrations/golden-v2/rg-11-expected.json",
        "hashes": hashes,
        "object_counts": object_counts,
        "operation_status_counts": operation_status_counts,
        "output_path": "golden/rules-v2/rg-11.json",
        "publication_status": "published",
        "source_byte_sha256": source_sha256,
        "source_path": "golden/rules/rg-11.json",
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
    return manifest_path.with_name(".rg-11-publication.journal.json")


def _remove(path: Path) -> None:
    try:
        path.unlink()
    except FileNotFoundError:
        pass
    except OSError as exc:
        raise PublicationRecoveryError(
            f"publication cleanup failed removing {path}: {exc}; "
            "no further file actions were taken"
        ) from exc


def _invalid_journal(case_id: str, journal_path: Path, reason: str) -> PublicationRecoveryError:
    return PublicationRecoveryError(
        f"{case_id} publication recovery rejected journal {journal_path}: {reason}. "
        "The journal was preserved and no files were touched. Inspect the journal "
        "and any sibling .tmp/.bak files, restore the publication directory to its "
        "pre-transaction state manually if needed, then delete the journal to "
        "resume publication."
    )


def _write_journal(journal_path: Path, document: dict[str, Any]) -> None:
    """Atomically replace the journal with a new phase document.

    The new content is written to a sibling ``.tmp`` file, fsynced, then
    atomically renamed over the journal, so the journal always contains either
    the old phase or the new phase, never a truncated mix.
    """
    tmp = journal_path.with_name(journal_path.name + ".tmp")
    _write_fsync(
        tmp,
        (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8"),
    )
    os.replace(tmp, journal_path)


def _load_journal(
    journal_path: Path,
    case_id: str,
    publish_dir: Path,
    manifest_path: Path,
) -> dict[str, Any]:
    """Validate a publication journal before any recovery action.

    Containment is enforced on resolved physical paths: every recorded path
    must resolve to a direct child of ``publish_dir`` (itself the resolved
    manifest parent directory), so a symlink or junction inside the publication
    directory that points elsewhere resolves to a different parent and is
    rejected. The check therefore confines recovery to the resolved
    publication directory rather than to a logical (unresolved) path. The
    journal's recorded manifest path must also equal the resolved manifest
    that located the journal, and the two targets must carry this case's
    derived names (``<case>.json`` and ``manifest.json``), so a crafted
    journal cannot name an arbitrary sibling file as a target. Any validation
    failure raises ``PublicationRecoveryError``, preserves the journal, and
    performs no file actions.
    """
    try:
        journal = json.loads(journal_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError, OSError) as exc:
        raise _invalid_journal(case_id, journal_path, f"journal is not readable JSON ({exc})") from exc
    if not isinstance(journal, dict):
        raise _invalid_journal(case_id, journal_path, "journal root is not an object")
    if journal.get("format_version") != 2:
        raise _invalid_journal(case_id, journal_path, "unsupported journal format_version")
    if journal.get("case") != case_id:
        raise _invalid_journal(case_id, journal_path, "journal belongs to another case")
    phase = journal.get("phase")
    if phase not in _PHASES:
        raise _invalid_journal(case_id, journal_path, f"unknown journal phase {phase!r}")
    path_fields = (
        "output_path",
        "manifest_path",
        "output_temp",
        "manifest_temp",
        "output_backup",
        "manifest_backup",
    )
    resolved: dict[str, Path] = {}
    for field in path_fields:
        raw = journal.get(field)
        if not isinstance(raw, str) or not raw:
            raise _invalid_journal(case_id, journal_path, f"{field} is not a path string")
        try:
            resolved[field] = Path(raw).resolve()
        except (OSError, ValueError) as exc:
            raise _invalid_journal(case_id, journal_path, f"{field} cannot be resolved ({exc})") from exc
        if os.path.normcase(str(resolved[field].parent)) != os.path.normcase(str(publish_dir)):
            raise _invalid_journal(case_id, journal_path, f"{field} escapes the publication directory")
    if len({str(path) for path in resolved.values()}) != len(path_fields):
        raise _invalid_journal(case_id, journal_path, "recorded paths are not pairwise distinct")
    if os.path.normcase(str(resolved["manifest_path"])) != os.path.normcase(str(manifest_path)):
        raise _invalid_journal(
            case_id,
            journal_path,
            "journal manifest path does not match the manifest that located it",
        )
    if resolved["output_path"].name != f"{case_id.lower()}.json":
        raise _invalid_journal(case_id, journal_path, "output path is not this case's target name")
    if resolved["manifest_path"].name != "manifest.json":
        raise _invalid_journal(case_id, journal_path, "manifest path is not the shared manifest name")
    names = {field: resolved[field].name for field in path_fields}
    for field in ("output_temp", "manifest_temp", "output_backup", "manifest_backup"):
        match = _DOTFILE_PATTERN.match(names[field])
        if match is None:
            raise _invalid_journal(case_id, journal_path, f"{field} does not match the dotfile pattern")
        expected_base = names["output_path"] if field.startswith("output") else names["manifest_path"]
        if match.group("base") != expected_base:
            raise _invalid_journal(case_id, journal_path, f"{field} base does not match its target name")
    tokens = {
        _DOTFILE_PATTERN.match(names[field]).group("token")
        for field in ("output_temp", "manifest_temp", "output_backup", "manifest_backup")
    }
    if len(tokens) != 1:
        raise _invalid_journal(case_id, journal_path, "dotfile tokens are inconsistent")
    for field in ("output_had_original", "manifest_had_original"):
        if not isinstance(journal.get(field), bool):
            raise _invalid_journal(case_id, journal_path, f"{field} is not a boolean")
    output_backup = resolved["output_backup"]
    manifest_backup = resolved["manifest_backup"]
    if not journal["output_had_original"] and output_backup.is_file():
        raise _invalid_journal(case_id, journal_path, "output backup exists but output_had_original is false")
    if not journal["manifest_had_original"] and manifest_backup.is_file():
        raise _invalid_journal(case_id, journal_path, "manifest backup exists but manifest_had_original is false")
    if (
        journal["output_had_original"]
        and not output_backup.is_file()
        and not resolved["output_path"].is_file()
    ):
        raise _invalid_journal(case_id, journal_path, "original output is missing and has no backup")
    if (
        journal["manifest_had_original"]
        and not manifest_backup.is_file()
        and not resolved["manifest_path"].is_file()
    ):
        raise _invalid_journal(case_id, journal_path, "original manifest is missing and has no backup")
    if output_backup.is_file() and phase == "prepared":
        raise _invalid_journal(case_id, journal_path, "output backup exists before the output_backup phase")
    if manifest_backup.is_file() and phase in ("prepared", "output_backup", "output_installed"):
        raise _invalid_journal(case_id, journal_path, "manifest backup exists before the manifest_backup phase")
    return journal


def _restore_target(target: Path, backup: Path, had_original: bool, installed: bool) -> None:
    """Restore one publication target per the recovery decision table.

    - backup exists: the original was moved away, so atomically move it back
      over the target (had_original is guaranteed True by ``_load_journal``).
    - no backup, original existed: the original was never moved; leave the
      target in place.
    - no backup, first publication, phase claims installed: the installed
      file was created by this transaction, so remove it (PUB-001).
    - no backup, first publication, not installed: nothing to do.
    """
    if backup.is_file():
        try:
            os.replace(backup, target)
        except OSError as exc:
            raise PublicationRecoveryError(
                f"recovery failed restoring {target} from {backup}: {exc}; "
                "the journal is preserved and recovery can be retried"
            ) from exc
    elif not had_original and installed:
        _remove(target)


def _recover_transaction(
    journal_path: Path,
    case_id: str,
    publish_dir: Path,
    manifest_path: Path,
) -> None:
    """Roll back one journaled publication transaction and remove its journal.

    The journal is the commit record: while it exists the transaction is not
    committed and every target is restored to its pre-transaction state; once
    it is gone the transaction is committed and recovery does nothing. Every
    action is idempotent, so a crash during recovery converges when rerun.
    """
    journal = _load_journal(journal_path, case_id, publish_dir, manifest_path)
    phase = journal["phase"]
    _restore_target(
        Path(journal["output_path"]),
        Path(journal["output_backup"]),
        journal["output_had_original"],
        phase in _OUTPUT_INSTALLED_PHASES,
    )
    _restore_target(
        Path(journal["manifest_path"]),
        Path(journal["manifest_backup"]),
        journal["manifest_had_original"],
        phase == "manifest_installed",
    )
    _remove(Path(journal["output_temp"]))
    _remove(Path(journal["manifest_temp"]))
    _remove(Path(journal["output_backup"]))
    _remove(Path(journal["manifest_backup"]))
    _remove(journal_path.with_name(journal_path.name + ".tmp"))
    _remove(journal_path)


def recover_rg11_publication(manifest_path: Path) -> bool:
    """Recover an interrupted RG-11 publication transaction, if its journal exists."""
    manifest_path = manifest_path.resolve()
    journal_path = _journal_path(manifest_path)
    if not journal_path.exists():
        return False
    _recover_transaction(journal_path, CASE_ID, manifest_path.parent, manifest_path)
    return True


def _sweep_stale_dotfiles(publish_dir: Path, output_name: str, manifest_name: str) -> None:
    """Remove leftover dotfiles of an already-committed transaction.

    A crash after the journal was deleted (the commit point) can leave stale
    ``.bak`` files behind. Only dotfiles matching this publisher's exact
    naming pattern for its two targets are removed; arbitrary files are never
    touched.
    """
    names = "|".join(re.escape(name) for name in (output_name, manifest_name))
    pattern = re.compile(rf"^\.(?:{names})\.[0-9a-f]{{32}}\.(?:bak|tmp)$")
    for child in publish_dir.iterdir():
        if pattern.match(child.name):
            _remove(child)


def publish_rg11(
    source_path: Path,
    expected_path: Path,
    output_path: Path,
    manifest_path: Path,
    *,
    fail_after_output_backup: bool = False,
    fail_after_output_swap: bool = False,
    fail_after_manifest_backup: bool = False,
    fail_after_manifest_swap: bool = False,
    fail_after_commit: bool = False,
) -> PublicationResult:
    """Publish one RG-11 candidate with rollback and idempotent replay."""
    source_path = source_path.resolve()
    expected_path = expected_path.resolve()
    output_path = output_path.resolve()
    manifest_path = manifest_path.resolve()
    if output_path == source_path or output_path == expected_path:
        raise ValueError("RG-11 publication target must be distinct from inputs")
    if output_path.parent != manifest_path.parent:
        raise ValueError("RG-11 output and manifest must share a publication directory")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    publish_dir = manifest_path.parent

    recovered = recover_rg11_publication(manifest_path)
    verify_publication_integrity(manifest_path)
    if not recovered:
        _sweep_stale_dotfiles(publish_dir, output_path.name, manifest_path.name)
    source_bytes, source = _load_json(source_path)
    expected_bytes, expected = _load_json(expected_path)
    if source_bytes != expected_bytes:
        raise ValueError(
            "RG-11 direct-v2 publication requires expected to be a byte-identical "
            "copy of the frozen source contract (D-086); "
            f"source_sha256={_sha256(source_bytes)} expected_sha256={_sha256(expected_bytes)}"
        )
    if source.get("contract") != CONTRACT or source.get("contract_version") != CONTRACT_VERSION:
        raise ValueError("RG-11 source is not the frozen v2 contract")
    if source.get("case", {}).get("id") != CASE_ID:
        raise ValueError("RG-11 source has the wrong case id")
    validate_golden_case_v2(source)
    if expected.get("contract") != CONTRACT or expected.get("contract_version") != CONTRACT_VERSION:
        raise ValueError("RG-11 expected has the wrong contract")
    if expected.get("case", {}).get("id") != CASE_ID:
        raise ValueError("RG-11 expected has the wrong case id")
    if expected.get("case", {}).get("approval_status") != "approved":
        raise ValueError("RG-11 expected must be approved before publication")
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
        raise ValueError("RG-11 publication requires the existing v2 manifest")
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
        raise ValueError("RG-11 source changed during publication preparation")
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
    output_had_original = output_path.exists()
    manifest_had_original = manifest_path.exists()
    journal = {
        "format_version": 2,
        "case": CASE_ID,
        "phase": "prepared",
        "output_path": str(output_path),
        "manifest_path": str(manifest_path),
        "output_temp": str(output_temp),
        "manifest_temp": str(manifest_temp),
        "output_backup": str(output_backup),
        "manifest_backup": str(manifest_backup),
        "output_had_original": output_had_original,
        "manifest_had_original": manifest_had_original,
    }
    _write_journal(journal_path, journal)
    try:
        _write_fsync(output_temp, expected_bytes)
        _write_json_fsync(manifest_temp, updated_manifest)
        # Each phase is written before its action, so the journal can only
        # claim more progress than actually happened; recovery treats claims
        # about missing files as no-ops.
        journal["phase"] = "output_backup"
        _write_journal(journal_path, journal)
        if output_had_original:
            os.replace(output_path, output_backup)
        if fail_after_output_backup:
            raise RuntimeError("injected RG-11 output backup failure")
        journal["phase"] = "output_installed"
        _write_journal(journal_path, journal)
        os.replace(output_temp, output_path)
        if fail_after_output_swap:
            raise RuntimeError("injected RG-11 output swap failure")
        journal["phase"] = "manifest_backup"
        _write_journal(journal_path, journal)
        if manifest_had_original:
            os.replace(manifest_path, manifest_backup)
        if fail_after_manifest_backup:
            raise RuntimeError("injected RG-11 manifest backup failure")
        journal["phase"] = "manifest_installed"
        _write_journal(journal_path, journal)
        os.replace(manifest_temp, manifest_path)
        if fail_after_manifest_swap:
            raise RuntimeError("injected RG-11 manifest swap failure")
        # The commit point is the journal deletion: after this the transaction
        # is committed and recovery does nothing; the backups are then cleaned
        # up, and a crash in between leaves only harmless stale dotfiles.
        _remove(journal_path)
        if fail_after_commit:
            raise RuntimeError("injected RG-11 post-commit failure")
        _remove(output_backup)
        _remove(manifest_backup)
    except Exception:
        if journal_path.exists():
            _recover_transaction(journal_path, CASE_ID, publish_dir, manifest_path)
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


__all__ = ["PublicationResult", "publish_rg11", "recover_rg11_publication"]
