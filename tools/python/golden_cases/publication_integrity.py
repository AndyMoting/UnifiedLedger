"""D-090 pre-publish integrity gate for golden v2 publication.

Contract: docs/DECISIONS.md D-090 (publication LF integrity contract). The
repository byte domain for every publication raw-byte hash is UTF-8 + LF:
all registered source, expected and output artifacts, and the manifest
itself, must be strict-UTF-8 LF-only files whose raw bytes reproduce the
registered sha256 metadata.

This gate reads filesystem bytes directly, decodes them with strict UTF-8
and requires LF-only (rejecting CRLF and bare CR). It never runs Git and
never silently normalizes line endings: a stale CRLF checkout must fail
closed and the worktree must be refreshed outside the publisher before
retrying (D-090 publisher timing clause).

For every case registered in the manifest (not only the case being
published) it verifies, failing closed on the first mismatch:

- registration relations: repository-relative registered paths that resolve
  inside the repository root, the role-owning parent directory and file
  name pattern for each of source / expected / output, and no output
  aliasing the manifest itself;
- raw-byte hashes: top-level ``source_byte_sha256`` / ``source_sha256`` /
  ``expected_byte_sha256`` and ``hashes.source_sha256`` /
  ``hashes.expected_sha256`` / ``hashes.output_sha256`` must equal the
  sha256 of the corresponding file bytes;
- expected/output byte equality: registered ``hashes.output_sha256`` must
  equal ``expected_byte_sha256`` and the output file bytes must equal the
  expected file bytes;
- canonical hash: the canonical serialization of the parsed expected
  document (see ``canonical_bytes``) must reproduce ``canonical_sha256``
  and ``hashes.canonical_sha256`` (which must equal each other).

Any mismatch raises ``PublicationIntegrityError`` before any publication
mutation, so a failing gate produces no new publication state.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
from typing import Any

_CONTRACT = "unifiedledger.golden-case"
_CONTRACT_VERSION = "2.0.0"
_CASE_PATTERN = re.compile(r"^rg-\d{2}\.json$")
_EXPECTED_PATTERN = re.compile(r"^rg-\d{2}-expected\.json$")
# role -> (registered parent directory below the repository root, name pattern)
_ROLE_PARTS = {
    "source": (("golden", "rules"), _CASE_PATTERN),
    "expected": (("docs", "migrations", "golden-v2"), _EXPECTED_PATTERN),
    "output": (("golden", "rules-v2"), _CASE_PATTERN),
}


class PublicationIntegrityError(RuntimeError):
    """The publication corpus does not satisfy the D-090 integrity contract.

    Raised before any publication mutation; the publisher stops and no new
    publication state is produced.
    """


def canonical_bytes(document: Any) -> bytes:
    """Serialize ``document`` with the repository canonical serializer.

    This is the exact implementation used by the publishers
    (``tools/python/golden_cases/rg*_publication.py:_canonical_bytes``):
    Python ``json.dumps(document, ensure_ascii=False, allow_nan=False,
    sort_keys=True, separators=(",", ":"))`` encoded as UTF-8. It is a
    Python-specific deterministic serialization, not RFC 8785 JCS: whole
    numbers render as floats (``130.0``), so cross-language recomputation is
    only defined against this exact serializer.
    """
    return json.dumps(
        document,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _read_lf_only(path: Path) -> bytes:
    """Read ``path`` as strict-UTF-8 LF-only bytes, failing closed otherwise."""
    try:
        data = path.read_bytes()
    except OSError as exc:
        raise PublicationIntegrityError(f"integrity gate cannot read {path}: {exc}") from exc
    if b"\r" in data:
        raise PublicationIntegrityError(
            f"integrity gate rejected {path}: file is not LF-only (contains CR "
            "bytes; CRLF and bare CR are outside the D-090 repository byte "
            "domain). Refresh the checkout to LF and retry."
        )
    try:
        data.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise PublicationIntegrityError(
            f"integrity gate rejected {path}: not strict UTF-8 ({exc})"
        ) from exc
    return data


def _fail(case_id: str, message: str) -> None:
    raise PublicationIntegrityError(f"RG case {case_id}: {message}")


def _registered_relation(case_id: str, role: str, registered: Any, base: Path) -> Path:
    """Validate one registered path and return its resolved location.

    The registered path must be a repository-relative path whose parent
    directory and file name match the role contract and whose resolution
    stays inside the repository root.
    """
    if not isinstance(registered, str) or not registered:
        _fail(case_id, f"{role}_path is not a non-empty path string")
    rel = Path(registered)
    if rel.is_absolute() or ".." in rel.parts:
        _fail(case_id, f"{role}_path {registered!r} is not repository-relative")
    role_parts, pattern = _ROLE_PARTS[role]
    if rel.parts[:-1] != role_parts:
        _fail(
            case_id,
            f"{role}_path {registered!r} parent must be "
            + "/".join(role_parts),
        )
    if pattern.fullmatch(rel.name) is None:
        _fail(case_id, f"{role}_path {registered!r} name does not match {pattern.pattern}")
    resolved = (base / rel).resolve()
    if not resolved.is_relative_to(base):
        _fail(case_id, f"{role}_path {registered!r} escapes the repository root")
    return resolved


def verify_publication_integrity(
    manifest_path: Path,
    *,
    repo_root: Path | None = None,
) -> None:
    """Verify the full registered publication corpus before any mutation.

    ``manifest_path`` must be the v2 manifest (``golden/rules-v2/
    manifest.json``). Registered repository-relative paths are resolved
    against ``repo_root``; when omitted the current working directory is
    used and must be the repository root. Raises
    ``PublicationIntegrityError`` on any mismatch; no file is modified.
    """
    base = Path(repo_root).resolve() if repo_root is not None else Path.cwd().resolve()
    manifest_path = manifest_path.resolve()
    if manifest_path.name != "manifest.json":
        raise PublicationIntegrityError(
            f"integrity gate rejected {manifest_path}: manifest must be named manifest.json"
        )
    manifest_bytes = _read_lf_only(manifest_path)
    try:
        manifest = json.loads(manifest_bytes.decode("utf-8"))
    except json.JSONDecodeError as exc:
        raise PublicationIntegrityError(
            f"integrity gate rejected {manifest_path}: manifest is not valid JSON ({exc})"
        ) from exc
    if not isinstance(manifest, dict):
        raise PublicationIntegrityError("integrity gate rejected manifest: root must be an object")
    if manifest.get("contract") != _CONTRACT or manifest.get("contract_version") != _CONTRACT_VERSION:
        raise PublicationIntegrityError(
            f"integrity gate rejected manifest: unexpected contract {manifest.get('contract')!r} "
            f"or contract_version {manifest.get('contract_version')!r}"
        )
    cases = manifest.get("cases")
    if not isinstance(cases, list) or not cases:
        raise PublicationIntegrityError(
            "integrity gate rejected manifest: cases must be a non-empty list"
        )

    for case in cases:
        if not isinstance(case, dict):
            raise PublicationIntegrityError("integrity gate rejected manifest: case entry must be an object")
        case_id = case.get("case")
        if not isinstance(case_id, str) or not case_id:
            raise PublicationIntegrityError(
                "integrity gate rejected manifest: case entry has no case id"
            )

        hashes = case.get("hashes")
        if not isinstance(hashes, dict):
            _fail(case_id, "hashes is not an object")

        locations = {
            role: _registered_relation(case_id, role, case.get(f"{role}_path"), base)
            for role in _ROLE_PARTS
        }
        if os.path.normcase(str(locations["output"])) == os.path.normcase(str(manifest_path)):
            _fail(case_id, "output path aliases the manifest itself")

        source_bytes = _read_lf_only(locations["source"])
        expected_bytes = _read_lf_only(locations["expected"])
        output_bytes = _read_lf_only(locations["output"])

        source_sha = _sha256(source_bytes)
        registered = {
            "source_byte_sha256": case.get("source_byte_sha256"),
            "source_sha256": case.get("source_sha256"),
            "hashes.source_sha256": hashes.get("source_sha256"),
        }
        for field, expected in registered.items():
            if expected != source_sha:
                _fail(
                    case_id,
                    f"{field} {expected!r} does not match sha256 of {locations['source']} "
                    f"({source_sha})",
                )
        expected_sha = _sha256(expected_bytes)
        registered = {
            "expected_byte_sha256": case.get("expected_byte_sha256"),
            "hashes.expected_sha256": hashes.get("expected_sha256"),
        }
        for field, expected in registered.items():
            if expected != expected_sha:
                _fail(
                    case_id,
                    f"{field} {expected!r} does not match sha256 of {locations['expected']} "
                    f"({expected_sha})",
                )
        output_sha = _sha256(output_bytes)
        if hashes.get("output_sha256") != output_sha:
            _fail(
                case_id,
                f"hashes.output_sha256 {hashes.get('output_sha256')!r} does not match "
                f"sha256 of {locations['output']} ({output_sha})",
            )
        if hashes.get("output_sha256") != case.get("expected_byte_sha256"):
            _fail(
                case_id,
                "registered hashes.output_sha256 differs from registered "
                f"expected_byte_sha256 ({hashes.get('output_sha256')!r} vs "
                f"{case.get('expected_byte_sha256')!r})",
            )
        if output_bytes != expected_bytes:
            _fail(
                case_id,
                f"output {locations['output']} bytes differ from expected "
                f"{locations['expected']} bytes",
            )

        canonical = _sha256(canonical_bytes(json.loads(expected_bytes.decode("utf-8"))))
        if case.get("canonical_sha256") != canonical:
            _fail(
                case_id,
                f"canonical_sha256 {case.get('canonical_sha256')!r} does not match "
                f"recomputed canonical sha256 ({canonical}) of {locations['expected']}",
            )
        if hashes.get("canonical_sha256") != canonical:
            _fail(
                case_id,
                f"hashes.canonical_sha256 {hashes.get('canonical_sha256')!r} does not "
                f"match recomputed canonical sha256 ({canonical})",
            )


__all__ = [
    "PublicationIntegrityError",
    "canonical_bytes",
    "verify_publication_integrity",
]
