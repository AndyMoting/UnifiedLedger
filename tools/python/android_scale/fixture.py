"""Synthetic Alipay CSVs with byte-checked manifests (not device PASS evidence)."""
from __future__ import annotations

import hashlib
import json
import re
from collections import Counter
from dataclasses import asdict, dataclass
from pathlib import Path

SCHEMA_VERSION = 2
DEFAULT_SEED = 197198
INITIAL_SESSIONS = 5
# Dimensions are (rows_per_session, unique_rows, initially_confirmed_relations).
# "maximum" and "parser-small" keep their exact values. "local-small" is the
# D-216 local-diagnostic profile: 20 shared rows per session (5 prepare
# sessions + 1 main-SAF session) + 5 unique rows = 105 initial / 125 final
# candidates with 200 initial / 300 final duplicate relations (6 copies of each
# shared value: C(6,2)=15 relations each), and 4 initially confirmed relations
# to drive the detail-decision stage. Small enough to complete every stage in a
# few minutes on one local emulator, large enough to exercise SAF import,
# detail decision, traversal, group disposition (100 new-session relations) and
# batch confirmation with many relations. Diagnostic only: result.py and the
# maximum cross-check still accept the "maximum" profile alone.
#
# "local-medium" is the D-217 diagnostic tier: 2,000 shared rows per session
# + 100 unique rows = 10,100 initial / 11,100 final candidates with 20,000 /
# 30,000 duplicate relations and 20 initially confirmed relations. Its
# post-import write burst approaches the cloud scale that produced the
# D-212 rollback-journal lock wall, so the wall can be reproduced and
# attributed on the local channel instead of through 20-40 minute cloud
# round trips. Diagnostic only: result.py still accepts "maximum" alone.
PROFILES = {
    "maximum": (10_000, 1_000, 100),
    "parser-small": (3, 2, 1),
    "local-small": (20, 5, 4),
    "local-medium": (2_000, 100, 20),
}
HEADER = "交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注,"
OCCURRED_AT = "2026-01-15 08:00:00"


@dataclass(frozen=True)
class FixtureManifest:
    schema_version: int
    profile: str
    seed: int
    initial_sessions: int
    rows_per_session: int
    unique_rows: int
    main_session_rows: int
    initial_candidates: int
    final_candidates: int
    initial_duplicate_relations: int
    final_duplicate_relations: int
    new_session_duplicate_relations: int
    initially_confirmed_relations: int
    formal_transactions_before_confirmation: int
    expected_formal_transactions_after_confirmation: int
    files: dict[str, dict[str, str | int]]


def _dimensions(profile: str, seed: int) -> tuple[int, int, int]:
    if profile not in PROFILES:
        raise ValueError("unknown fixture profile")
    if type(seed) is not int or not 0 <= seed <= 1_000_000_000:
        raise ValueError("seed must be an integer in 0..1000000000")
    return PROFILES[profile]


def _layout(rows: int, unique: int):
    for session in range(1, INITIAL_SESSIONS + 2):
        yield (f"session-{session:02d}.csv", "prepare" if session <= INITIAL_SESSIONS else "main-saf",
               f"scale-session-{session:02d}", 0, rows)
    yield ("unique-rows.csv", "prepare-unique", "scale-unique", rows, unique)


def _csv(start: int, count: int, seed: int) -> bytes:
    lines = [f"SYNTHETIC SCALE METADATA {index:02d}" for index in range(23)] + [HEADER]
    for index in range(start, start + count):
        # Amount is part of production duplicate identity; descriptions/order IDs are not.
        minor = seed + index + 1
        fields = [OCCURRED_AT, "网上支付", "SYNTHETIC MERCHANT", "/", "SYNTHETIC ITEM",
                  "支出", f"{minor // 100}.{minor % 100:02d}", "", "交易成功",
                  f"SYN-ORDER-{index:05d}\t", "", "", ""]
        lines.append(",".join(fields))
    return ("\n".join(lines) + "\n").encode("utf-8")


def _facts(payload: bytes) -> list[tuple]:
    """Read actual bytes; never use names/descriptions as duplicate business facts."""
    if not 0 < len(payload) <= 10 * 1024 * 1024:
        raise ValueError("CSV byte limit")
    lines = payload.decode("utf-8").split("\n")
    if len(lines) < 26 or lines[-1] != "" or lines[23] != HEADER:
        raise ValueError("CSV header/line shape")
    data = lines[24:-1]
    if not 1 <= len(data) <= 10_000:
        raise ValueError("CSV intake row limit")
    facts = []
    for line in data:
        fields = line.split(",")
        if len(fields) != 13 or fields[12] or "\r" in line:
            raise ValueError("CSV field shape")
        for index, field in enumerate(fields):
            if index == 9 or (index == 10 and field):
                if not field.endswith("\t") or field.count("\t") != 1 or len(field) == 1:
                    raise ValueError("CSV order tab shape")
            elif "\t" in field:
                raise ValueError("CSV misplaced tab")
        if (fields[0], fields[1], fields[5], fields[7], fields[8]) != (
                OCCURRED_AT, "网上支付", "支出", "", "交易成功"):
            raise ValueError("CSV ordinary source facts")
        if not re.fullmatch(r"[0-9]+\.[0-9]{2}", fields[6]):
            raise ValueError("CSV exact decimal amount")
        whole, fraction = fields[6].split(".")
        facts.append((int(whole) * 100 + int(fraction), "CNY", 2,
                      fields[0].replace(" ", "T") + "+08:00", "out", "settled"))
    return facts


def _manifest(directory: Path, profile: str, seed: int) -> FixtureManifest:
    rows, unique, confirmed = _dimensions(profile, seed)
    files = {}
    initial: Counter = Counter()
    final: Counter = Counter()
    for name, role, input_ref, start, count in _layout(rows, unique):
        path = directory / name
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"missing or linked fixture: {name}")
        payload = path.read_bytes()
        facts = _facts(payload)
        if len(facts) != count or [fact[0] for fact in facts] != list(range(seed + start + 1, seed + start + count + 1)):
            raise ValueError(f"business facts/order mismatch: {name}")
        # Non-fact bytes are pinned too: changing a file AND its hash cannot evade validation.
        if payload != _csv(start, count, seed):
            raise ValueError(f"noncanonical fixture: {name}")
        files[name] = {"role": role, "input_ref": input_ref, "rows": len(facts),
                       "bytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest()}
        final.update(facts)
        if role != "main-saf":
            initial.update(facts)
    initial_relations = sum(n * (n - 1) // 2 for n in initial.values())
    final_relations = sum(n * (n - 1) // 2 for n in final.values())
    return FixtureManifest(
        SCHEMA_VERSION, profile, seed, INITIAL_SESSIONS, rows, unique, rows,
        sum(initial.values()), sum(final.values()), initial_relations, final_relations,
        final_relations - initial_relations, confirmed, 0, 1, files,
    )


def generate_fixture(output: Path, seed: int = DEFAULT_SEED, *, profile: str = "maximum") -> FixtureManifest:
    rows, unique, _ = _dimensions(profile, seed)
    if output.is_symlink():
        raise ValueError("linked fixture directory")
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("fixture directory must be empty; existing files are never overwritten")
    for name, _, _, start, count in _layout(rows, unique):
        with (output / name).open("xb") as stream:
            stream.write(_csv(start, count, seed))
    manifest = _manifest(output, profile, seed)
    with (output / "manifest.json").open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(asdict(manifest), indent=2, sort_keys=True) + "\n")
    return manifest


def load_manifest(path: Path) -> FixtureManifest:
    if path.is_symlink() or not path.is_file():
        raise ValueError("missing or linked manifest")

    def unique_keys(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate manifest key")
            result[key] = value
        return result

    try:
        data = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_keys)
        return FixtureManifest(**data)
    except (TypeError, json.JSONDecodeError) as error:
        raise ValueError("invalid manifest shape") from error


def validate_manifest(manifest: FixtureManifest, directory: Path, *, expected_profile: str = "maximum") -> None:
    if manifest.profile != expected_profile:
        raise ValueError("fixture profile mismatch")
    rows, unique, _ = _dimensions(expected_profile, manifest.seed)
    expected_names = {item[0] for item in _layout(rows, unique)} | {"manifest.json"}
    if directory.is_symlink() or {path.name for path in directory.iterdir()} != expected_names:
        raise ValueError("fixture file set mismatch")
    expected = _manifest(directory, expected_profile, manifest.seed)
    # JSON distinguishes booleans/floats from integers, unlike Python equality.
    if json.dumps(asdict(manifest), sort_keys=True) != json.dumps(asdict(expected), sort_keys=True):
        raise ValueError("manifest evidence/oracle mismatch")
