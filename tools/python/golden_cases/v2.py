from __future__ import annotations

from collections import defaultdict
from copy import deepcopy
from datetime import datetime
from decimal import Decimal, InvalidOperation
import hashlib
import json
from pathlib import Path
import re
from typing import Any, Iterable
from uuid import UUID, uuid5
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError, ValidationError

from .loader import GoldenCaseError


_UUID_NAMESPACE = UUID("cfad3f84-edb1-5838-ae53-aae49684cf1a")
_DEFAULT_SCHEMA_PATH = (
    Path(__file__).resolve().parents[3] / "schemas" / "golden-case-v2.schema.json"
)
_DECIMAL_PATTERN = re.compile(r"^(?:0|-?[1-9][0-9]*)(?:\.[0-9]+)?$")
_TIMESTAMP_PATTERN = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
    r"(?:\.[0-9]+)?(?:Z|\+(?:[01][0-9]|2[0-3]):[0-5][0-9]"
    r"|-(?!00:00)(?:[01][0-9]|2[0-3]):[0-5][0-9])$"
)
_MIGRATION_SOURCE_LOCATOR_PATTERN = re.compile(
    r"^\$(?:\.[^.\[\]/\x00-\x1f\x7f]+|\[\*\])*$"
)
_ENTITY_COLLECTIONS = {
    "catalog_accounts": ("catalog", "accounts"),
    "catalog_categories": ("catalog", "categories"),
    "transactions": ("transactions",),
    "transaction_versions": ("transaction_versions",),
    "posting_sets": ("posting_sets",),
    "postings": ("postings",),
    "sources": ("sources",),
    "candidates": ("candidates",),
    "confirmations": ("confirmations",),
    "evidence": ("evidence",),
    "evidence_links": ("evidence_links",),
    "relations": ("relations",),
    "domain_entities": ("domain_entities",),
    "audit_links": ("audit_links",),
    "posting_reconciliations": ("posting_reconciliations",),
}
_ENTITY_CHANGE_FIELDS = ("added_ids", "changed_ids", "removed_ids")
_RG10_STRUCTURAL_ACTIONS = {
    "confirm_stored_value_recharge",
    "confirm_stored_value_spend",
    "ingest_stored_value_recharge_candidate",
    "ingest_stored_value_spend_candidate",
    "confirm_imported_stored_value_recharge",
    "confirm_imported_stored_value_spend",
    "record_expiry_reminder",
    "confirm_stored_value_expiry_loss",
    "reconcile_merchant_credit",
    "reconcile_bank_payment",
    "apply_merchant_lot_allocation",
    "confirm_stored_value_activation_balance",
    "rename_stored_value_labels",
}
_RG10_REJECTED_ACTIONS = {
    "confirm_stored_value_recharge",
    "confirm_stored_value_spend",
    "confirm_imported_stored_value_recharge",
    "confirm_imported_stored_value_spend",
    "confirm_stored_value_expiry_loss",
    "apply_merchant_lot_allocation",
}
_RG10_REJECTION_REASON_FIELDS = {
    "confirm_stored_value_recharge": {
        "exact_decimal_string_required": {"paid_amount", "credited_amount", "bonus_amount"},
        "must_be_positive": {"paid_amount"},
        "credited_amount_must_be_positive": {"credited_amount"},
        "bonus_amount_must_be_zero_or_positive": {"bonus_amount"},
        "credited_must_equal_paid_plus_bonus": {"credited_amount"},
        "component_sum_mismatch": {"bonus_amount"},
        "stored_value_account_not_enabled": {"stored_value_account_id"},
        "stored_value_models_must_not_overlap": {"model"},
        "unknown_payment_account": {"payment_account_id"},
        "owned_payment_asset_required": {"payment_account_id"},
        "same_cny_currency_required": {"currency"},
    },
    "confirm_stored_value_spend": {
        "insufficient_effective_stored_balance": {"amount"},
        "paid_bonus_composition_must_be_evidenced": {"paid_bonus_composition"},
        "active_secondary_category_required": {"category_id"},
        "enabled_restricted_stored_value_asset_required": {"stored_value_account_id"},
    },
    "confirm_imported_stored_value_recharge": {
        "bank_payment_model_and_all_recharge_facts_required": {"bank_payment_confirmed"},
    },
    "confirm_imported_stored_value_spend": {
        "spend_category_and_behavior_confirmation_required": {"category_confirmed"},
    },
    "confirm_stored_value_expiry_loss": {
        "actual_expiry_requires_explicit_confirmation": {"explicit_confirmation"},
    },
    "apply_merchant_lot_allocation": {
        "lot_allocation_exceeds_remaining_face_value": {"amount"},
    },
}
_RG10_UNOWNED_REJECTION_REASONS = {
    "insufficient_effective_stored_balance": "effective stored-value replay owner",
    "lot_allocation_exceeds_remaining_face_value": "lot remaining-face-value effect owner",
    "paid_bonus_composition_must_be_evidenced": "paid/bonus composition provenance owner",
    "bank_payment_model_and_all_recharge_facts_required": "stored-value import candidate/fact owner",
    "spend_category_and_behavior_confirmation_required": "stored-value import candidate/fact owner",
}
_ACCEPTED_ACTION_ENTITY_COUNTS = {
    "manual_expense": {
        "transactions": (1, 0, 0),
        "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0),
        "postings": (2, 0, 0),
        "confirmations": (1, 0, 0),
        "posting_reconciliations": (1, 0, 0),
    },
    "manual_income": {
        "transactions": (1, 0, 0),
        "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0),
        "postings": (2, 0, 0),
        "confirmations": (1, 0, 0),
        "posting_reconciliations": (1, 0, 0),
    },
    "category_rename": {
        "catalog_categories": (0, 1, 0),
    },
    "transaction_note_update": {
        "transactions": (0, 1, 0),
        "transaction_versions": (1, 0, 0),
        "confirmations": (1, 0, 0),
    },
    "preview_target_balance": {
        "sources": (1, 0, 0),
        "candidates": (1, 0, 0),
        "evidence": (1, 0, 0),
        "evidence_links": (1, 0, 0),
        "domain_entities": (1, 0, 0),
    },
    "confirm_balance_adjustment": {
        "transactions": (1, 0, 0),
        "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0),
        "postings": (2, 0, 0),
        "candidates": (0, 1, 0),
        "confirmations": (1, 0, 0),
        "domain_entities": (1, 0, 0),
        "audit_links": (1, 0, 0),
    },
    "confirm_real_transfer": {
        "transactions": (1, 0, 0),
        "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0),
        "postings": (2, 0, 0),
        "confirmations": (1, 0, 0),
        "posting_reconciliations": (2, 0, 0),
    },
    "confirm_explanation_allocation": {
        "transactions": (1, 0, 0),
        "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0),
        "postings": (2, 0, 0),
        "confirmations": (1, 0, 0),
        "domain_entities": (1, 0, 0),
        "audit_links": (2, 0, 0),
    },
}
_SET_LIKE_ARRAY_KEYS = {
    "accounts",
    "categories",
    "transactions",
    "transaction_versions",
    "posting_sets",
    "postings",
    "sources",
    "candidates",
    "confirmations",
    "evidence",
    "evidence_links",
    "relations",
    "domain_entities",
    "audit_links",
    "posting_reconciliations",
    "balances",
    "reports",
    "metrics",
    "derived_statuses",
    "posting_ids",
    "source_ids",
    "member_refs",
    "returned_ids",
    "added_ids",
    "changed_ids",
    "removed_ids",
}
_SCHEMA_CACHE: dict[Path, tuple[int, dict[str, Any], Draft202012Validator]] = {}


class _DuplicateKeyError(ValueError):
    def __init__(self, key: str):
        super().__init__(key)
        self.key = key


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise _DuplicateKeyError(key)
        result[key] = value
    return result


def _json_path(parts: Iterable[Any]) -> str:
    path = "$"
    for part in parts:
        if isinstance(part, int):
            path += f"[{part}]"
        elif re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", str(part)):
            path += f".{part}"
        else:
            path += f"[{json.dumps(str(part), ensure_ascii=True)}]"
    return path


def _load_schema(
    schema_path: str | Path | None,
) -> tuple[dict[str, Any], Draft202012Validator]:
    path = (Path(schema_path) if schema_path is not None else _DEFAULT_SCHEMA_PATH).resolve()
    try:
        modified = path.stat().st_mtime_ns
        cached = _SCHEMA_CACHE.get(path)
        if cached is not None and cached[0] == modified:
            return cached[1], cached[2]
        schema = json.loads(
            path.read_text(encoding="utf-8"), object_pairs_hook=_reject_duplicate_keys
        )
        Draft202012Validator.check_schema(schema)
    except _DuplicateKeyError as error:
        raise GoldenCaseError(f"$.{error.key}: duplicate object key in schema") from error
    except (OSError, json.JSONDecodeError, SchemaError) as error:
        raise GoldenCaseError(f"$: cannot load valid Golden Schema v2 from {path}: {error}") from error
    validator = Draft202012Validator(schema)
    _SCHEMA_CACHE[path] = (modified, schema, validator)
    return schema, validator


def _schema_error(error: ValidationError) -> GoldenCaseError:
    path = _json_path(error.absolute_path)
    if error.validator == "required":
        missing = re.search(r"'([^']+)' is a required property", error.message)
        if missing:
            path += f".{missing.group(1)}"
    elif error.validator in {"additionalProperties", "unevaluatedProperties"}:
        unexpected = re.search(r"'([^']+)' was unexpected", error.message)
        if unexpected:
            path += f".{unexpected.group(1)}"
    return GoldenCaseError(f"{path}: {error.message}")


def _specific_schema_errors(error: ValidationError) -> list[ValidationError]:
    if not error.context:
        return [error]
    result: list[ValidationError] = []
    for child in error.context:
        result.extend(_specific_schema_errors(child))
    return result


def _validate_schema(case: Any, schema_path: str | Path | None = None) -> None:
    _, validator = _load_schema(schema_path)
    errors = sorted(
        validator.iter_errors(case),
        key=lambda item: (list(item.absolute_path), item.message),
    )
    if errors:
        candidates = _specific_schema_errors(errors[0])

        def error_score(item: ValidationError) -> tuple[int, int, int]:
            if item.validator == "additionalProperties":
                unexpected_count = item.message.count("'") // 2
                return (4, -unexpected_count, len(item.absolute_path))
            if item.validator == "required":
                return (3, 0, len(item.absolute_path))
            if item.validator == "const":
                return (1, 0, len(item.absolute_path))
            return (2, 0, len(item.absolute_path))

        selected = max(
            candidates,
            key=error_score,
        )
        raise _schema_error(selected)


def load_golden_case_v2(path: str | Path) -> dict[str, Any]:
    case_path = Path(path)
    try:
        value = json.loads(
            case_path.read_text(encoding="utf-8"),
            object_pairs_hook=_reject_duplicate_keys,
        )
    except _DuplicateKeyError as error:
        raise GoldenCaseError(f"$.{error.key}: duplicate JSON object key") from error
    except (OSError, json.JSONDecodeError) as error:
        raise GoldenCaseError(f"$: cannot load golden case {case_path}: {error}") from error

    if not isinstance(value, dict):
        raise GoldenCaseError("$: golden case root must be an object")
    _validate_schema(value)
    return value


def _validate_identity_component(name: str, value: str) -> None:
    if not isinstance(value, str) or not value:
        raise GoldenCaseError(f"$.{name} must be non-empty")
    if any(ord(character) < 32 or ord(character) == 127 for character in value):
        raise GoldenCaseError(f"$.{name} must not contain control characters")


def deterministic_v2_id(
    case_id: str,
    root_id: str,
    entity_kind: str,
    semantic_key: str,
) -> str:
    for name, value in {
        "case_id": case_id,
        "root_id": root_id,
        "entity_kind": entity_kind,
        "semantic_key": semantic_key,
    }.items():
        _validate_identity_component(name, value)
    name = f"{case_id}\n{root_id}\n{entity_kind}\n{semantic_key}"
    return str(uuid5(_UUID_NAMESPACE, name))


def migration_semantic_key(
    source_locator: str,
    occurrence_discriminator: str,
) -> str:
    if (
        not isinstance(source_locator, str)
        or not _MIGRATION_SOURCE_LOCATOR_PATTERN.fullmatch(source_locator)
    ):
        raise GoldenCaseError(
            "$.source_locator must be a normalized source locator using $, .key, and [*]"
        )
    if not isinstance(occurrence_discriminator, str) or not occurrence_discriminator:
        raise GoldenCaseError(
            "$.occurrence_discriminator: occurrence discriminator must be non-empty"
        )
    if any(
        ord(character) < 32 or ord(character) == 127
        for character in occurrence_discriminator
    ):
        raise GoldenCaseError(
            "$.occurrence_discriminator: occurrence discriminator must not contain control characters"
        )
    return f"{source_locator}\noccurrence={occurrence_discriminator}"


def deterministic_v2_root_id(
    case_id: str,
    source_locator: str,
    occurrence_discriminator: str,
) -> str:
    _validate_identity_component("case_id", case_id)
    semantic_key = migration_semantic_key(
        source_locator, occurrence_discriminator
    )
    name = f"{case_id}\n@root\nroot\n{semantic_key}"
    return str(uuid5(_UUID_NAMESPACE, name))


def deterministic_v2_migration_id(
    case_id: str,
    root_id: str,
    entity_kind: str,
    source_locator: str,
    occurrence_discriminator: str,
) -> str:
    for name, value in {
        "case_id": case_id,
        "root_id": root_id,
        "entity_kind": entity_kind,
    }.items():
        _validate_identity_component(name, value)
    semantic_key = migration_semantic_key(
        source_locator, occurrence_discriminator
    )
    name = f"{case_id}\n{root_id}\n{entity_kind}\n{semantic_key}"
    return str(uuid5(_UUID_NAMESPACE, name))


def _fail(path: str, message: str) -> None:
    raise GoldenCaseError(f"{path}: {message}")


def _unique_index(items: list[dict[str, Any]], path: str) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for index, item in enumerate(items):
        item_id = item["id"]
        if item_id in result:
            _fail(f"{path}[{index}].id", f"duplicate stable id {item_id!r}")
        result[item_id] = item
    return result


def _unique_compound(
    items: list[dict[str, Any]],
    path: str,
    key_fields: tuple[str, ...],
) -> None:
    seen: set[tuple[Any, ...]] = set()
    for index, item in enumerate(items):
        key = tuple(item.get(field) for field in key_fields)
        if key in seen:
            _fail(f"{path}[{index}]", f"duplicate stable key {key!r}")
        seen.add(key)


def _decimal(value: str, path: str) -> Decimal:
    if not _DECIMAL_PATTERN.fullmatch(value):
        _fail(path, "must be a canonical decimal string")
    try:
        return Decimal(value)
    except InvalidOperation:
        _fail(path, "must be a valid decimal")


def _amount(
    value: str,
    currency: str,
    path: str,
    precisions: dict[str, int],
) -> Decimal:
    if currency not in precisions:
        _fail(path.rsplit(".", 1)[0] + ".currency", f"unknown currency {currency!r}")
    precision = precisions[currency]
    if precision == 0:
        pattern = r"^(?:0|-?[1-9][0-9]*)$"
    else:
        pattern = rf"^(?:0|-?[1-9][0-9]*)\.[0-9]{{{precision}}}$"
    if not re.fullmatch(pattern, value):
        _fail(path, f"must use exactly {precision} decimal places for {currency}")
    return _decimal(value, path)


def _attempted_amount(
    value: str,
    currency: str,
    path: str,
    precisions: dict[str, int],
) -> Decimal:
    if currency not in precisions:
        _fail(path.rsplit(".", 1)[0] + ".currency", f"unknown currency {currency!r}")
    precision = precisions[currency]
    if precision == 0:
        pattern = r"^-?(?:0|[1-9][0-9]*)$"
    else:
        pattern = rf"^-?(?:0|[1-9][0-9]*)\.[0-9]{{{precision}}}$"
    if not re.fullmatch(pattern, value):
        _fail(path, f"must use exactly {precision} decimal places for {currency}")
    return _decimal(value, path)


def _timestamp(value: str, path: str, timezone: ZoneInfo) -> datetime:
    if not _TIMESTAMP_PATTERN.fullmatch(value):
        _fail(path, "must be a strict RFC 3339 timestamp with seconds and an explicit offset")
    normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError:
        _fail(path, "must be a valid RFC 3339 timestamp")
    expected_offset = parsed.astimezone(timezone).utcoffset()
    if parsed.utcoffset() != expected_offset:
        _fail(path, "offset does not match case.timezone at that instant")
    return parsed


def _timestamp_instant(value: str) -> datetime:
    normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
    return datetime.fromisoformat(normalized)


def _utf16_lexicographic_key(value: str) -> bytes:
    return value.encode("utf-16-be", errors="surrogatepass")


def _replay_fingerprint_projection(
    state: dict[str, Any], effective_at: str
) -> dict[str, list[dict[str, str]]]:
    try:
        target_instant = _timestamp_instant(effective_at)
    except (TypeError, ValueError):
        _fail("$.effective_at", "must be a valid timezone-aware timestamp")
    if target_instant.tzinfo is None or target_instant.utcoffset() is None:
        _fail("$.effective_at", "must be timezone-aware")

    versions = {item["id"]: item for item in state["transaction_versions"]}
    posting_sets = {item["id"]: item for item in state["posting_sets"]}
    postings = {item["id"]: item for item in state["postings"]}
    projection: list[dict[str, str]] = []
    for transaction in state["transactions"]:
        version = versions[transaction["current_version_id"]]
        if _timestamp_instant(version["effective_at"]) > target_instant:
            continue
        posting_set = posting_sets[version["posting_set_id"]]
        for posting_id in posting_set["posting_ids"]:
            posting = postings[posting_id]
            projection.append(
                {
                    "transaction_id": transaction["id"],
                    "current_version_id": version["id"],
                    "effective_at": version["effective_at"],
                    "posting_id": posting["id"],
                    "account_id": posting["account_id"],
                    "currency": posting["currency"],
                    "amount": posting["amount"],
                }
            )
    projection.sort(
        key=lambda item: (
            _utf16_lexicographic_key(item["effective_at"]),
            _utf16_lexicographic_key(item["transaction_id"]),
            _utf16_lexicographic_key(item["current_version_id"]),
            _utf16_lexicographic_key(item["posting_id"]),
            _utf16_lexicographic_key(item["account_id"]),
            _utf16_lexicographic_key(item["currency"]),
            _utf16_lexicographic_key(item["amount"]),
        )
    )
    return {"postings": projection}


def _compute_replay_fingerprint(state: dict[str, Any], effective_at: str) -> str:
    projection = _replay_fingerprint_projection(state, effective_at)
    try:
        canonical = json.dumps(
            projection,
            ensure_ascii=False,
            allow_nan=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    except (TypeError, ValueError, UnicodeEncodeError) as error:
        raise GoldenCaseError(
            "ledger fingerprint projection is not RFC 8785 compatible"
        ) from error
    return "sha256:" + hashlib.sha256(canonical).hexdigest()


def _normalize_contract_value(value: Any, parent_key: str | None = None) -> Any:
    """Normalize only the contract's declared set-like arrays for equality checks."""
    if isinstance(value, dict):
        return {
            key: _normalize_contract_value(value[key], key) for key in sorted(value)
        }
    if isinstance(value, list):
        normalized = [_normalize_contract_value(item) for item in value]
        if parent_key in _SET_LIKE_ARRAY_KEYS:
            return sorted(
                normalized,
                key=lambda item: json.dumps(
                    item, sort_keys=True, ensure_ascii=False, separators=(",", ":")
                ),
            )
        return normalized
    return value


def _contract_equivalent(left: Any, right: Any) -> bool:
    return _normalize_contract_value(left) == _normalize_contract_value(right)


def _state_payload(state: dict[str, Any]) -> dict[str, Any]:
    result = deepcopy(state)
    result.pop("id", None)
    result.pop("as_of_operation_id", None)
    return result


def _collection(state: dict[str, Any], parts: tuple[str, ...]) -> list[dict[str, Any]]:
    value: Any = state
    for part in parts:
        value = value[part]
    return value


def _state_indexes(state: dict[str, Any], path: str) -> dict[str, dict[str, dict[str, Any]]]:
    indexes: dict[str, dict[str, dict[str, Any]]] = {}
    for name, parts in _ENTITY_COLLECTIONS.items():
        collection_path = path + "." + ".".join(parts)
        indexes[name] = _unique_index(_collection(state, parts), collection_path)
    return indexes


def _resolve_ref(
    state: dict[str, Any],
    indexes: dict[str, dict[str, dict[str, Any]]],
    operations: dict[str, dict[str, Any]],
    kind: str,
    target_id: str,
    path: str,
) -> dict[str, Any]:
    mapping = {
        "transaction": "transactions",
        "transaction_version": "transaction_versions",
        "posting_set": "posting_sets",
        "posting": "postings",
        "source": "sources",
        "candidate": "candidates",
        "confirmation": "confirmations",
        "evidence": "evidence",
        "evidence_link": "evidence_links",
        "relation": "relations",
        "domain_entity": "domain_entities",
    }
    if kind == "operation":
        target = operations.get(target_id)
        if target is None or target["root_id"] != state["root_id"]:
            _fail(path, f"dangling or cross-root operation reference {target_id!r}")
        return target
    if kind == "observation":
        target = indexes["domain_entities"].get(target_id)
        if target is None or target["type"] != "target_balance_observation":
            _fail(path, f"dangling or mistyped observation reference {target_id!r}")
        return target
    collection = mapping[kind]
    target = indexes[collection].get(target_id)
    if target is None:
        _fail(path, f"dangling {kind} reference {target_id!r}")
    return target


def _validate_catalog(
    state: dict[str, Any],
    path: str,
    indexes: dict[str, dict[str, dict[str, Any]]],
    precisions: dict[str, int],
) -> None:
    accounts = indexes["catalog_accounts"]
    categories = indexes["catalog_categories"]
    system_role_kinds = {
        "opening_balance": "equity",
        "balance_adjustments": "equity",
        "stored_value_bonus_right_income": "income",
        "stored_value_expiry_loss": "expense",
        "stored_value_pre_activation_adjustment": "equity",
    }
    for index, account in enumerate(state["catalog"]["accounts"]):
        account_path = f"{path}.catalog.accounts[{index}]"
        if account["currency"] not in precisions:
            _fail(account_path + ".currency", "currency is not declared")
        if account["reconciliation_eligible"] and not (
            account["owned_by_user"]
            and account["real_account"]
            and account["kind"] in {"asset", "liability"}
        ):
            _fail(
                account_path + ".reconciliation_eligible",
                "eligible accounts must be owned real asset or liability accounts",
            )
        if "stored_value" in account and not (
            account["kind"] == "asset"
            and account["owned_by_user"]
            and account["real_account"]
        ):
            _fail(
                account_path + ".stored_value",
                "stored-value capability belongs to an owned real asset account",
            )
        system_role = account.get("system_role")
        if system_role is not None and account["kind"] != system_role_kinds[system_role]:
            _fail(
                account_path + ".system_role",
                f"{system_role!r} requires account kind {system_role_kinds[system_role]!r}",
            )
    for index, category in enumerate(state["catalog"]["categories"]):
        category_path = f"{path}.catalog.categories[{index}]"
        parent_id = category["parent_id"]
        if parent_id is not None and parent_id not in categories:
            _fail(category_path + ".parent_id", "dangling category reference")
        if parent_id is not None and categories[parent_id]["parent_id"] is not None:
            _fail(
                category_path + ".parent_id",
                "category hierarchy is limited to two levels",
            )
        posting_account_id = category["posting_account_id"]
        if posting_account_id is not None and posting_account_id not in accounts:
            _fail(
                category_path + ".posting_account_id",
                "dangling account reference",
            )
        if parent_id is None and posting_account_id is not None:
            _fail(
                category_path + ".posting_account_id",
                "top-level categories are grouping nodes and cannot own postings",
            )
        if parent_id is not None and posting_account_id is None and category["active"]:
            _fail(
                category_path + ".posting_account_id",
                "active second-level categories must own a posting account",
            )
        if posting_account_id is not None and accounts[posting_account_id]["kind"] not in {
            "expense",
            "income",
        }:
            _fail(
                category_path + ".posting_account_id",
                "category posting accounts must be expense or income accounts",
            )

    if "category_name_history" in state["catalog"]:
        histories = state["catalog"]["category_name_history"]
        _unique_compound(
            histories,
            path + ".catalog.category_name_history",
            ("category_id", "version"),
        )
        by_category: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for index, history in enumerate(histories):
            history_path = f"{path}.catalog.category_name_history[{index}]"
            if history["category_id"] not in categories:
                _fail(history_path + ".category_id", "dangling category reference")
            by_category[history["category_id"]].append(history)
        if set(by_category) != set(categories):
            _fail(
                path + ".catalog.category_name_history",
                "must exactly cover every catalog category",
            )
        for category_id, records in by_category.items():
            ordered = sorted(records, key=lambda item: item["version"])
            versions = [item["version"] for item in ordered]
            if versions != list(range(1, len(ordered) + 1)):
                _fail(
                    path + ".catalog.category_name_history",
                    f"category {category_id!r} name versions must be consecutive from 1",
                )
            if [item["status"] for item in ordered] != [
                *(["superseded"] * (len(ordered) - 1)),
                "current",
            ]:
                _fail(
                    path + ".catalog.category_name_history",
                    f"category {category_id!r} must have exactly one final current name",
                )
            if categories[category_id]["name"] != ordered[-1]["name"]:
                _fail(
                    path + ".catalog.category_name_history",
                    f"category {category_id!r} name must match its current history record",
                )


def _validate_formal_ledger(
    state: dict[str, Any],
    path: str,
    indexes: dict[str, dict[str, dict[str, Any]]],
    precisions: dict[str, int],
    timezone: ZoneInfo,
) -> tuple[dict[str, Decimal], dict[str, tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]]]:
    transactions = indexes["transactions"]
    versions = indexes["transaction_versions"]
    posting_sets = indexes["posting_sets"]
    postings = indexes["postings"]
    accounts = indexes["catalog_accounts"]
    confirmations = indexes["confirmations"]

    versions_by_transaction: dict[str, list[dict[str, Any]]] = defaultdict(list)
    posting_set_owners: dict[str, set[str]] = defaultdict(set)
    for index, version in enumerate(state["transaction_versions"]):
        version_path = f"{path}.transaction_versions[{index}]"
        transaction = transactions.get(version["transaction_id"])
        if transaction is None:
            _fail(version_path + ".transaction_id", "dangling transaction reference")
        if version["posting_set_id"] not in posting_sets:
            _fail(version_path + ".posting_set_id", "dangling posting-set reference")
        if "confirmation_id" in version and version["confirmation_id"] not in confirmations:
            _fail(version_path + ".confirmation_id", "dangling confirmation reference")
        for field in ("occurred_at", "statistics_at", "effective_at", "created_at"):
            if field in version:
                _timestamp(version[field], version_path + f".{field}", timezone)
        versions_by_transaction[version["transaction_id"]].append(version)
        posting_set_owners[version["posting_set_id"]].add(version["transaction_id"])

    current: dict[
        str, tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]
    ] = {}
    for index, transaction in enumerate(state["transactions"]):
        transaction_path = f"{path}.transactions[{index}]"
        current_version = versions.get(transaction["current_version_id"])
        if current_version is None:
            _fail(transaction_path + ".current_version_id", "dangling current version reference")
        if current_version["transaction_id"] != transaction["id"]:
            _fail(transaction_path + ".current_version_id", "current version belongs to another transaction")
        owned_versions = versions_by_transaction[transaction["id"]]
        numbers = [item["version_number"] for item in owned_versions]
        if len(numbers) != len(set(numbers)):
            _fail(transaction_path + ".current_version_id", "transaction has duplicate version numbers")
        ordered_numbers = sorted(numbers)
        expected_numbers = list(range(1, len(owned_versions) + 1))
        if ordered_numbers != expected_numbers:
            _fail(
                transaction_path,
                f"transaction version numbers must start at 1 and remain unique and continuous; got {ordered_numbers}",
            )
        if current_version["version_number"] != ordered_numbers[-1]:
            _fail(
                transaction_path + ".current_version_id",
                "must point to the highest transaction version_number; non-current ghost versions are forbidden",
            )
        posting_set = posting_sets[current_version["posting_set_id"]]
        current_postings: list[dict[str, Any]] = []
        posting_set_index = state["posting_sets"].index(posting_set)
        for posting_index, posting_id in enumerate(posting_set["posting_ids"]):
            posting = postings.get(posting_id)
            if posting is None:
                _fail(
                    f"{path}.posting_sets[{posting_set_index}].posting_ids[{posting_index}]",
                    "dangling posting reference",
                )
            current_postings.append(posting)
        current[transaction["id"]] = (
            transaction,
            current_version,
            current_postings,
        )

    referenced_postings: dict[str, str] = {}
    for index, posting_set in enumerate(state["posting_sets"]):
        set_path = f"{path}.posting_sets[{index}]"
        if posting_set["id"] not in posting_set_owners:
            _fail(set_path + ".id", "posting set is not owned by a transaction version")
        if len(posting_set_owners[posting_set["id"]]) != 1:
            _fail(set_path + ".id", "posting set is shared by different transactions")
        if len(posting_set["posting_ids"]) != len(set(posting_set["posting_ids"])):
            _fail(set_path + ".posting_ids", "contains duplicate posting references")
        totals: dict[str, Decimal] = defaultdict(Decimal)
        for posting_index, posting_id in enumerate(posting_set["posting_ids"]):
            posting = postings.get(posting_id)
            posting_path = f"{set_path}.posting_ids[{posting_index}]"
            if posting is None:
                _fail(posting_path, "dangling posting reference")
            if posting["posting_set_id"] != posting_set["id"]:
                _fail(posting_path, "posting points to another posting set")
            if posting_id in referenced_postings:
                _fail(posting_path, "posting is owned by more than one posting set")
            referenced_postings[posting_id] = posting_set["id"]
            totals[posting["currency"]] += _amount(
                posting["amount"],
                posting["currency"],
                f"{path}.postings[{list(postings).index(posting_id)}].amount",
                precisions,
            )
        for currency, total in totals.items():
            if total != 0:
                _fail(set_path, f"posting set is not balanced for {currency}: {total}")

    if set(postings) != set(referenced_postings):
        orphan = sorted(set(postings) - set(referenced_postings))[0]
        _fail(path + ".postings", f"posting {orphan!r} is not owned by a posting set")

    for index, posting in enumerate(state["postings"]):
        posting_path = f"{path}.postings[{index}]"
        account = accounts.get(posting["account_id"])
        if account is None:
            _fail(posting_path + ".account_id", "dangling account reference")
        if posting["currency"] != account["currency"]:
            _fail(posting_path + ".currency", "posting currency differs from account currency")
        if posting["reconciliation_eligible"] and not account["reconciliation_eligible"]:
            _fail(posting_path + ".reconciliation_eligible", "account is not reconciliation eligible")
        owner_id = next(iter(posting_set_owners[posting["posting_set_id"]]))
        owner_type = transactions[owner_id]["type"]
        if owner_type != "opening_balance" and "role" not in posting:
            _fail(posting_path + ".role", "role is required outside opening_balance")
        role = posting.get("role")
        if role == "stored_value_asset" and not (
            account.get("stored_value", {}).get("enabled") is True
        ):
            _fail(
                posting_path + ".role",
                "stored_value_asset requires an enabled stored-value account",
            )
        required_system_roles = {
            "balance_adjustment_counterpart": "balance_adjustments",
            "balance_adjustment_reversal_counterpart": "balance_adjustments",
            "stored_value_bonus_income": "stored_value_bonus_right_income",
            "stored_value_expiry_loss": "stored_value_expiry_loss",
        }
        if role in required_system_roles and account.get("system_role") != required_system_roles[role]:
            _fail(
                posting_path + ".role",
                f"{role} requires system_role {required_system_roles[role]!r}",
            )

    replay: dict[str, Decimal] = defaultdict(Decimal)
    for _, _, current_postings in current.values():
        for posting in current_postings:
            replay[posting["account_id"]] += _amount(
                posting["amount"], posting["currency"], path + ".postings.amount", precisions
            )
    return replay, current


def _validate_balances(
    state: dict[str, Any],
    path: str,
    indexes: dict[str, dict[str, dict[str, Any]]],
    replay: dict[str, Decimal],
    precisions: dict[str, int],
) -> None:
    accounts = indexes["catalog_accounts"]
    _unique_compound(state["balances"], path + ".balances", ("account_id", "currency"))
    for index, balance in enumerate(state["balances"]):
        account = accounts.get(balance["account_id"])
        if account is None:
            _fail(f"{path}.balances[{index}].account_id", "dangling account reference")
        if balance["currency"] != account["currency"]:
            _fail(f"{path}.balances[{index}].currency", "must match account currency")
    expected_keys = {(account_id, account["currency"]) for account_id, account in accounts.items()}
    actual_keys = {(item["account_id"], item["currency"]) for item in state["balances"]}
    if actual_keys != expected_keys:
        missing = sorted(expected_keys - actual_keys)
        extra = sorted(actual_keys - expected_keys)
        _fail(path + ".balances", f"must exactly cover catalog accounts; missing={missing}, extra={extra}")
    for index, balance in enumerate(state["balances"]):
        balance_path = f"{path}.balances[{index}]"
        actual = _amount(
            balance["amount"], balance["currency"], balance_path + ".amount", precisions
        )
        expected = replay.get(balance["account_id"], Decimal(0))
        if actual != expected:
            _fail(balance_path + ".amount", f"must replay to {expected}, got {actual}")


def _validate_references(
    state: dict[str, Any],
    path: str,
    indexes: dict[str, dict[str, dict[str, Any]]],
    operations: dict[str, dict[str, Any]],
    precisions: dict[str, int],
    timezone: ZoneInfo,
) -> None:
    accounts = indexes["catalog_accounts"]
    sources = indexes["sources"]
    transactions = indexes["transactions"]
    domain_entities = indexes["domain_entities"]
    domain_entity_paths = {
        entity["id"]: f"{path}.domain_entities[{index}]"
        for index, entity in enumerate(state["domain_entities"])
    }

    for index, source in enumerate(state["sources"]):
        payload = source["payload"]
        source_path = f"{path}.sources[{index}].payload"
        if source["type"] == "account_transfer":
            source_account = accounts.get(payload["source_account_id"])
            if source_account is None:
                _fail(source_path + ".source_account_id", "dangling account reference")
            if not (
                source_account["owned_by_user"]
                and source_account["real_account"]
                and source_account["kind"] in {"asset", "liability"}
            ):
                _fail(source_path + ".source_account_id", "must be an owned real financial account")
            if payload["currency"] != source_account["currency"]:
                _fail(source_path + ".currency", "must match the source account currency")
            _timestamp(payload["observed_at"], source_path + ".observed_at", timezone)
            evidence = indexes["evidence"].get(payload["evidence_id"])
            if evidence is None:
                _fail(source_path + ".evidence_id", "dangling evidence reference")
            if evidence["type"] != "transfer_record":
                _fail(source_path + ".evidence_id", "must reference transfer_record evidence")
            if evidence["source_ids"] != [source["id"]]:
                _fail(source_path + ".evidence_id", "transfer_record evidence must reference this exact source")
            if evidence["payload"]["observed_at"] != payload["observed_at"]:
                _fail(source_path + ".evidence_id", "transfer_record evidence must have the same observed_at")
            if payload["completeness"] == "complete":
                destination_account = accounts.get(payload["destination_account_id"])
                if destination_account is None:
                    _fail(source_path + ".destination_account_id", "dangling account reference")
                if payload["destination_account_id"] == payload["source_account_id"]:
                    _fail(source_path + ".destination_account_id", "must differ from source_account_id")
                if not (
                    destination_account["owned_by_user"]
                    and destination_account["real_account"]
                    and destination_account["kind"] in {"asset", "liability"}
                ):
                    _fail(source_path + ".destination_account_id", "must be an owned real financial account")
                if payload["currency"] != destination_account["currency"]:
                    _fail(source_path + ".currency", "must match the destination account currency")
                debit = _amount(payload["source_debit_amount"], payload["currency"], source_path + ".source_debit_amount", precisions)
                credit = _amount(payload["destination_credit_amount"], payload["currency"], source_path + ".destination_credit_amount", precisions)
                fee = _amount(payload["fee_amount"], payload["currency"], source_path + ".fee_amount", precisions)
                if debit <= 0 or credit <= 0 or fee < 0:
                    _fail(source_path, "transfer amounts must have positive principal and non-negative fee")
                if debit != credit + fee:
                    _fail(source_path + ".source_debit_amount", "must equal destination_credit_amount + fee_amount")
            else:
                debit = _amount(payload["debit_amount"], payload["currency"], source_path + ".debit_amount", precisions)
                if debit <= 0:
                    _fail(source_path + ".debit_amount", "must be positive")
            continue
        account = accounts.get(payload["account_id"])
        if account is None:
            _fail(source_path + ".account_id", "dangling account reference")
        if payload["currency"] != account["currency"]:
            _fail(source_path + ".currency", "must match the observed account currency")
        _amount(payload["target_amount"], payload["currency"], source_path + ".target_amount", precisions)
        _timestamp(payload["target_observed_at"], source_path + ".target_observed_at", timezone)

    for index, candidate in enumerate(state["candidates"]):
        candidate_path = f"{path}.candidates[{index}]"
        if len(candidate["source_ids"]) != len(set(candidate["source_ids"])):
            _fail(candidate_path + ".source_ids", "contains duplicate source references")
        for source_index, source_id in enumerate(candidate["source_ids"]):
            if source_id not in sources:
                _fail(f"{candidate_path}.source_ids[{source_index}]", "dangling source reference")
        history = candidate["status_history"]
        _unique_index(history, candidate_path + ".status_history")
        sequences = [item["sequence"] for item in history]
        if sequences != list(range(1, len(history) + 1)):
            _fail(candidate_path + ".status_history", "sequence must be contiguous and ordered from 1")
        _decimal(candidate["confidence"], candidate_path + ".confidence")
        payload = candidate["payload"]
        if candidate["type"] == "account_transfer":
            if len(candidate["source_ids"]) != 1:
                _fail(candidate_path + ".source_ids", "must contain exactly one transfer source reference")
            source = sources[candidate["source_ids"][0]]
            if source["type"] != "account_transfer":
                _fail(candidate_path + ".source_ids[0]", "must reference an account_transfer source")
            source_payload = source["payload"]
            if len(payload["evidence_refs"]) != 1 or len(payload["evidence_refs"]) != len(set(payload["evidence_refs"])):
                _fail(candidate_path + ".payload.evidence_refs", "must contain exactly one transfer evidence reference")
            evidence = indexes["evidence"].get(payload["evidence_refs"][0])
            if evidence is None:
                _fail(candidate_path + ".payload.evidence_refs[0]", "dangling evidence reference")
            if evidence["type"] != "transfer_record":
                _fail(candidate_path + ".payload.evidence_refs[0]", "must reference transfer_record evidence")
            if evidence["source_ids"] != candidate["source_ids"]:
                _fail(candidate_path + ".payload.evidence_refs[0]", "must have the candidate's exact source reference")
            if source_payload["evidence_id"] != evidence["id"]:
                _fail(candidate_path + ".payload.evidence_refs[0]", "must match the transfer source evidence identity")
            if payload["currency"] != source_payload["currency"]:
                _fail(candidate_path + ".payload.currency", "must match the transfer source currency")
            history_statuses = [item["status"] for item in history]
            if history_statuses not in (
                ["pending_confirmation"],
                ["pending_confirmation", "confirmed"],
            ):
                _fail(
                    candidate_path + ".status_history",
                    "must be exactly pending_confirmation or pending_confirmation followed by confirmed",
                )
            confirmation_owners = [
                confirmation
                for confirmation in state["confirmations"]
                if confirmation["type"] == "candidate_confirmation"
                and confirmation["subject"]["id"] == candidate["id"]
            ]
            expected_confirmation_count = 1 if history_statuses[-1] == "confirmed" else 0
            if len(confirmation_owners) != expected_confirmation_count:
                _fail(
                    candidate_path + ".status_history",
                    "candidate_confirmation ownership must be absent while pending and exact once confirmed",
                )
            if source_payload["completeness"] == "complete":
                if "source_debit_amount" not in payload:
                    _fail(candidate_path + ".payload", "must use the complete transfer candidate payload")
                fields = (
                    "source_account_id", "destination_account_id", "source_debit_amount",
                    "destination_credit_amount", "fee_amount", "currency",
                )
                for field in fields:
                    if payload[field] != source_payload[field]:
                        _fail(candidate_path + ".payload." + field, "must match the complete transfer source")
            else:
                if "debit_amount" not in payload:
                    _fail(candidate_path + ".payload", "must use the incomplete transfer candidate payload")
                for field in ("source_account_id", "debit_amount", "currency"):
                    if payload[field] != source_payload[field]:
                        _fail(candidate_path + ".payload." + field, "must match the incomplete transfer source")
                if set(payload["requires_confirmation"]) != {
                    "destination_account_id", "formal_transaction_creation",
                }:
                    _fail(candidate_path + ".payload.requires_confirmation", "must require destination_account_id and formal_transaction_creation")
            continue
        account = accounts.get(payload["account_id"])
        if account is None:
            _fail(candidate_path + ".payload.account_id", "dangling account reference")
        if payload["currency"] != account["currency"]:
            _fail(candidate_path + ".payload.currency", "must match the candidate account currency")
        replayed = _amount(
            payload["replayed_amount"], payload["currency"], candidate_path + ".payload.replayed_amount", precisions
        )
        target = _amount(
            payload["target_amount"], payload["currency"], candidate_path + ".payload.target_amount", precisions
        )
        delta = _amount(payload["delta"], payload["currency"], candidate_path + ".payload.delta", precisions)
        if target - replayed != delta:
            _fail(candidate_path + ".payload.delta", "must equal target_amount - replayed_amount")
        _timestamp(payload["effective_at"], candidate_path + ".payload.effective_at", timezone)

    for index, confirmation in enumerate(state["confirmations"]):
        confirmation_path = f"{path}.confirmations[{index}]"
        operation = operations.get(confirmation["operation_id"])
        if operation is None or operation["root_id"] != state["root_id"]:
            _fail(confirmation_path + ".operation_id", "dangling or cross-root operation reference")
        subject = confirmation["subject"]
        _resolve_ref(
            state,
            indexes,
            operations,
            subject["kind"],
            subject["id"],
            confirmation_path + ".subject.id",
        )
        if "confirmed_at" in confirmation:
            _timestamp(confirmation["confirmed_at"], confirmation_path + ".confirmed_at", timezone)

    for index, evidence in enumerate(state["evidence"]):
        evidence_path = f"{path}.evidence[{index}]"
        if len(evidence["source_ids"]) != len(set(evidence["source_ids"])):
            _fail(evidence_path + ".source_ids", "contains duplicate source references")
        for source_index, source_id in enumerate(evidence["source_ids"]):
            if source_id not in sources:
                _fail(f"{evidence_path}.source_ids[{source_index}]", "dangling source reference")
        _timestamp(evidence["payload"]["observed_at"], evidence_path + ".payload.observed_at", timezone)
        if evidence["type"] == "transfer_record":
            if len(evidence["source_ids"]) != 1:
                _fail(evidence_path + ".source_ids", "must contain exactly one transfer source reference")
            source = sources[evidence["source_ids"][0]]
            if source["type"] != "account_transfer":
                _fail(evidence_path + ".source_ids[0]", "must reference an account_transfer source")
            if source["payload"]["evidence_id"] != evidence["id"]:
                _fail(evidence_path + ".source_ids[0]", "must match the transfer source evidence identity")
            if source["payload"]["observed_at"] != evidence["payload"]["observed_at"]:
                _fail(evidence_path + ".payload.observed_at", "must match the transfer source observed_at")

    evidence_link_keys: set[tuple[str, str, str]] = set()
    for index, link in enumerate(state["evidence_links"]):
        link_path = f"{path}.evidence_links[{index}]"
        link_key = (link["evidence_id"], link["target_id"], link["role"])
        if link_key in evidence_link_keys:
            _fail(
                link_path,
                f"evidence {link['evidence_id']!r} has a duplicate evidence link for the same target and role",
            )
        evidence_link_keys.add(link_key)
        if link["evidence_id"] not in indexes["evidence"]:
            _fail(link_path + ".evidence_id", "dangling evidence reference")
        role_target_kinds = {
            "target_balance_observation": "observation",
            "real_account_posting": "posting",
            "payment_asset_posting": "posting",
            "destination_asset_posting": "posting",
            "funding_asset_posting": "posting",
            "bank_payment_posting": "posting",
            "refund_relationship": "relation",
            "counterparty_lending_relationship": "relation",
            "stored_value_activation_balance_fact": "domain_entity",
            "item_allocation_fact": "domain_entity",
            "stored_value_asset_posting": "posting",
            "stored_value_lot_fact": "domain_entity",
            "stored_value_bonus_component": "domain_entity",
            "stored_value_expiry_confirmation": "domain_entity",
        }
        expected_kind = role_target_kinds[link["role"]]
        if link["target_kind"] != expected_kind:
            _fail(
                link_path + ".target_kind",
                f"role {link['role']} requires target_kind {expected_kind}",
            )
        target = _resolve_ref(
            state,
            indexes,
            operations,
            link["target_kind"],
            link["target_id"],
            link_path + ".target_id",
        )
        posting_role_targets = {
            "payment_asset_posting": "payment_asset",
            "destination_asset_posting": "destination_asset",
            "funding_asset_posting": "funding_asset",
            "bank_payment_posting": "bank_payment",
            "stored_value_asset_posting": "stored_value_asset",
        }
        posting_roles = {"real_account_posting", *posting_role_targets}
        if link["role"] in posting_roles:
            account = accounts[target["account_id"]]
            if not (
                target["reconciliation_eligible"]
                and account["reconciliation_eligible"]
                and account["real_account"]
                and account["owned_by_user"]
                and account["kind"] in {"asset", "liability"}
            ):
                _fail(
                    link_path + ".target_id",
                    f"{link['role']} must target an eligible owned real posting",
                )
        if link["role"] in posting_role_targets and target.get("role") != posting_role_targets[link["role"]]:
            _fail(link_path + ".target_id", f"must target posting role {posting_role_targets[link['role']]}")
        if link["role"] in {"refund_relationship", "counterparty_lending_relationship"}:
            if target.get("type") != link["role"]:
                _fail(
                    link_path + ".target_id",
                    f"requires the reserved {link['role']} relation subtype, which is not implemented by this prototype",
                )
        if link["role"] == "stored_value_activation_balance_fact":
            if target.get("type") != "activation_adjustment":
                _fail(
                    link_path + ".target_id",
                    "must target domain subtype activation_adjustment",
                )
        domain_role_targets = {
            "item_allocation_fact": "item_allocation",
            "stored_value_lot_fact": "stored_value_lot",
            "stored_value_bonus_component": "stored_value_bonus_component",
            "stored_value_expiry_confirmation": "stored_value_expiry_event",
        }
        if link["role"] in domain_role_targets:
            expected_type = domain_role_targets[link["role"]]
            if target.get("type") != expected_type:
                _fail(link_path + ".target_id", f"must target domain subtype {expected_type}")
        evidence = indexes["evidence"][link["evidence_id"]]
        evidence_role_types = {
            "item_allocation_fact": "item_receipt",
            "stored_value_asset_posting": "merchant_stored_value_credit",
            "stored_value_lot_fact": "merchant_stored_value_credit",
            "stored_value_bonus_component": "merchant_stored_value_credit",
            "stored_value_expiry_confirmation": "confirmed_actual_expiry",
        }
        if (
            link["role"] in evidence_role_types
            and evidence["type"] != evidence_role_types[link["role"]]
        ):
            _fail(
                link_path + ".evidence_id",
                f"role {link['role']} requires evidence type {evidence_role_types[link['role']]}",
            )

    links_by_evidence: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for link in state["evidence_links"]:
        links_by_evidence[link["evidence_id"]].append(link)
    for evidence in state["evidence"]:
        links = links_by_evidence[evidence["id"]]
        if evidence["type"] == "item_receipt":
            if len(links) != 1 or links[0]["role"] != "item_allocation_fact":
                _fail(
                    path + ".evidence_links",
                    f"item receipt evidence {evidence['id']!r} must link exactly one item_allocation fact",
                )
        if evidence["type"] == "merchant_stored_value_credit":
            roles = [link["role"] for link in links]
            allowed_roles = {
                "stored_value_asset_posting",
                "stored_value_lot_fact",
                "stored_value_bonus_component",
            }
            if (
                roles.count("stored_value_asset_posting") != 1
                or roles.count("stored_value_lot_fact") != 1
                or any(role not in allowed_roles for role in roles)
            ):
                _fail(
                    path + ".evidence_links",
                    f"merchant credit evidence {evidence['id']!r} must have one asset-posting link, one lot-fact link, and only optional bonus-component links",
                )
            posting_link = next(
                link for link in links if link["role"] == "stored_value_asset_posting"
            )
            lot_link = next(link for link in links if link["role"] == "stored_value_lot_fact")
            posting = indexes["postings"][posting_link["target_id"]]
            lot = domain_entities[lot_link["target_id"]]
            lot_payload = lot["payload"]
            for bonus_link in (
                link for link in links if link["role"] == "stored_value_bonus_component"
            ):
                bonus = domain_entities[bonus_link["target_id"]]
                if bonus["payload"]["lot_id"] != lot["id"]:
                    _fail(
                        domain_entity_paths[bonus["id"]] + ".payload.lot_id",
                        f"merchant credit evidence {evidence['id']!r} bonus component must belong to its linked lot",
                    )
            recharge = transactions.get(lot_payload["recharge_transaction_id"])
            if recharge is None or recharge["type"] != "stored_value_recharge":
                _fail(
                    domain_entity_paths[lot["id"]] + ".payload.recharge_transaction_id",
                    "must reference a stored_value_recharge transaction",
                )
            version = indexes["transaction_versions"][recharge["current_version_id"]]
            posting_set = indexes["posting_sets"][version["posting_set_id"]]
            if posting["id"] not in posting_set["posting_ids"]:
                _fail(
                    path + ".evidence_links",
                    f"merchant credit evidence {evidence['id']!r} links must target the same recharge transaction",
                )
            if lot_payload["currency"] != posting["currency"]:
                _fail(
                    path + ".evidence_links",
                    f"merchant credit evidence {evidence['id']!r} lot currency must match its asset posting",
                )
            face_value = _amount(
                lot_payload["face_value"],
                lot_payload["currency"],
                path + ".domain_entities.payload.face_value",
                precisions,
            )
            posting_amount = _amount(
                posting["amount"], posting["currency"], path + ".postings.amount", precisions
            )
            if posting_amount <= 0 or face_value != posting_amount:
                _fail(
                    path + ".evidence_links",
                    f"merchant credit evidence {evidence['id']!r} lot face_value must match its positive asset posting",
                )
        if evidence["type"] == "confirmed_actual_expiry":
            if len(links) != 1 or links[0]["role"] != "stored_value_expiry_confirmation":
                _fail(
                    path + ".evidence_links",
                    f"confirmed expiry evidence {evidence['id']!r} must link exactly one expiry event",
                )

    activation_transactions: dict[str, str] = {}
    reconstruction_transactions: dict[str, tuple[str, str]] = {}
    for entity_index, entity in enumerate(state["domain_entities"]):
        payload = entity["payload"]
        if entity["type"] == "activation_adjustment":
            transaction_id = payload["transaction_id"]
            prior_owner = activation_transactions.get(transaction_id)
            if prior_owner is not None:
                _fail(
                    f"{path}.domain_entities[{entity_index}].payload.transaction_id",
                    f"activation adjustment transaction is already owned by {prior_owner!r}",
                )
            activation_transactions[transaction_id] = entity["id"]
        elif entity["type"] == "stored_value_reconstruction":
            for transaction_index, transaction_id in enumerate(
                payload["reconstructed_transaction_ids"]
            ):
                transaction_path = (
                    f"{path}.domain_entities[{entity_index}].payload"
                    f".reconstructed_transaction_ids[{transaction_index}]"
                )
                prior_owner = reconstruction_transactions.get(transaction_id)
                if prior_owner is not None:
                    _fail(
                        transaction_path,
                        f"transaction endpoint already belongs to reconstruction group {prior_owner[0]!r}",
                    )
                reconstruction_transactions[transaction_id] = (
                    entity["id"],
                    transaction_path,
                )

    for transaction_id, (_, transaction_path) in reconstruction_transactions.items():
        adjustment_id = activation_transactions.get(transaction_id)
        if adjustment_id is None:
            continue
        _fail(
            transaction_path,
            f"activation adjustment transaction owned by {adjustment_id!r} cannot be a reconstructed endpoint",
        )

    reconstruction_adjustments: dict[str, str] = {}
    reconstructions: list[tuple[int, dict[str, Any]]] = []
    bonus_amounts_by_recharge: dict[tuple[str, str], Decimal] = defaultdict(Decimal)
    bonus_paths_by_recharge: dict[tuple[str, str], str] = {}
    expiry_loss_owners: dict[str, str] = {}
    expiry_history_owners: dict[str, str] = {}

    for entity in state["domain_entities"]:
        if entity["type"] != "stored_value_expiry_event":
            continue
        history_path = domain_entity_paths[entity["id"]] + ".payload.status_history"
        for history_index, event in enumerate(entity["payload"]["status_history"]):
            prior_owner = expiry_history_owners.get(event["id"])
            if prior_owner is not None:
                _fail(
                    f"{history_path}[{history_index}].id",
                    f"status_history ID is already owned by expiry event {prior_owner!r}; it cannot belong to more than one expiry event",
                )
            expiry_history_owners[event["id"]] = entity["id"]

    def current_postings(transaction: dict[str, Any]) -> list[dict[str, Any]]:
        version = indexes["transaction_versions"][transaction["current_version_id"]]
        posting_set = indexes["posting_sets"][version["posting_set_id"]]
        return [indexes["postings"][posting_id] for posting_id in posting_set["posting_ids"]]

    for index, entity in enumerate(state["domain_entities"]):
        entity_path = f"{path}.domain_entities[{index}].payload"
        payload = entity["payload"]
        if entity["type"] == "target_balance_observation":
            if payload["account_id"] not in accounts:
                _fail(entity_path + ".account_id", "dangling account reference")
            if payload["source_id"] not in sources:
                _fail(entity_path + ".source_id", "dangling source reference")
            _amount(payload["target_amount"], payload["currency"], entity_path + ".target_amount", precisions)
            _timestamp(payload["observed_at"], entity_path + ".observed_at", timezone)
        elif entity["type"] == "balance_adjustment":
            observation = domain_entities.get(payload["observation_id"])
            if observation is None or observation["type"] != "target_balance_observation":
                _fail(entity_path + ".observation_id", "dangling or mistyped observation reference")
            transaction = transactions.get(payload["transaction_id"])
            if transaction is None or transaction["type"] != "balance_adjustment":
                _fail(entity_path + ".transaction_id", "dangling or mistyped adjustment transaction")
            _amount(payload["original_delta"], payload["currency"], entity_path + ".original_delta", precisions)
        elif entity["type"] == "explanation_allocation":
            adjustment = domain_entities.get(payload["adjustment_id"])
            if adjustment is None or adjustment["type"] != "balance_adjustment":
                _fail(entity_path + ".adjustment_id", "dangling or mistyped adjustment reference")
            explanation = transactions.get(payload["explanation_transaction_id"])
            if explanation is None or explanation["type"] != "account_transfer":
                _fail(entity_path + ".explanation_transaction_id", "must reference an account transfer")
            reversal = transactions.get(payload["reversal_transaction_id"])
            if reversal is None or reversal["type"] != "balance_adjustment_reversal":
                _fail(entity_path + ".reversal_transaction_id", "must reference an adjustment reversal")
            amount = _amount(payload["amount"], payload["currency"], entity_path + ".amount", precisions)
            if amount <= 0:
                _fail(entity_path + ".amount", "must be positive")
            _timestamp(payload["confirmed_at"], entity_path + ".confirmed_at", timezone)
        elif entity["type"] == "consumption_record":
            posting = indexes["postings"].get(payload["expense_posting_id"])
            if posting is None or posting.get("role") != "expense":
                _fail(entity_path + ".expense_posting_id", "must reference an expense posting")
            category = indexes["catalog_categories"].get(payload["category_id"])
            if category is None or category["posting_account_id"] != posting["account_id"]:
                _fail(entity_path + ".category_id", "must reference the posting's category")
            if not category["active"]:
                _fail(entity_path + ".category_id", "category must be active")
            amount = _amount(payload["amount"], payload["currency"], entity_path + ".amount", precisions)
            if amount <= 0:
                _fail(entity_path + ".amount", "must be positive")
            if payload["currency"] != posting["currency"]:
                _fail(entity_path + ".currency", "must match the expense posting currency")
            if amount != _decimal(posting["amount"], entity_path + ".expense_posting_id"):
                _fail(entity_path + ".amount", "must match the expense posting amount")
            _timestamp(payload["statistics_at"], entity_path + ".statistics_at", timezone)
        elif entity["type"] == "item_allocation":
            consumption = domain_entities.get(payload["consumption_record_id"])
            if consumption is None or consumption["type"] != "consumption_record":
                _fail(
                    entity_path + ".consumption_record_id",
                    "must reference a consumption_record",
                )
            posting = indexes["postings"].get(payload["expense_posting_id"])
            if posting is None or posting.get("role") != "expense":
                _fail(entity_path + ".expense_posting_id", "must reference an expense posting")
            category = indexes["catalog_categories"].get(payload["category_id"])
            if category is None or category["posting_account_id"] != posting["account_id"]:
                _fail(entity_path + ".category_id", "must reference the posting's category")
            if not category["active"]:
                _fail(entity_path + ".category_id", "category must be active")
            amount = _amount(payload["amount"], payload["currency"], entity_path + ".amount", precisions)
            if amount <= 0:
                _fail(entity_path + ".amount", "must be positive")
            expected = consumption["payload"]
            for field in ("expense_posting_id", "category_id", "amount", "currency"):
                if payload[field] != expected[field]:
                    _fail(entity_path + f".{field}", "must match the consumption_record")
        elif entity["type"] == "stored_value_lot":
            recharge = transactions.get(payload["recharge_transaction_id"])
            if recharge is None or recharge["type"] != "stored_value_recharge":
                _fail(
                    entity_path + ".recharge_transaction_id",
                    "must reference a stored_value_recharge transaction",
                )
            version = indexes["transaction_versions"][recharge["current_version_id"]]
            face_value = _amount(
                payload["face_value"], payload["currency"], entity_path + ".face_value", precisions
            )
            if face_value <= 0:
                _fail(entity_path + ".face_value", "must be positive")
            _timestamp(payload["loaded_at"], entity_path + ".loaded_at", timezone)
            if payload["loaded_at"] != version["occurred_at"]:
                _fail(
                    entity_path + ".loaded_at",
                    "must match the recharge current version occurred_at",
                )
        elif entity["type"] == "stored_value_bonus_component":
            lot = domain_entities.get(payload["lot_id"])
            if lot is None or lot["type"] != "stored_value_lot":
                _fail(entity_path + ".lot_id", "must reference a stored_value_lot")
            if payload["recharge_transaction_id"] != lot["payload"]["recharge_transaction_id"]:
                _fail(
                    entity_path + ".recharge_transaction_id",
                    "must match the lot recharge transaction",
                )
            recharge = transactions.get(payload["recharge_transaction_id"])
            if recharge is None or recharge["type"] != "stored_value_recharge":
                _fail(
                    entity_path + ".recharge_transaction_id",
                    "must reference a stored_value_recharge transaction",
                )
            if payload["currency"] != lot["payload"]["currency"]:
                _fail(entity_path + ".currency", "must match the lot currency")
            amount = _amount(
                payload["amount"], payload["currency"], entity_path + ".amount", precisions
            )
            if amount < 0:
                _fail(entity_path + ".amount", "must be zero or positive")
            bonus_key = (payload["recharge_transaction_id"], payload["currency"])
            bonus_amounts_by_recharge[bonus_key] += amount
            bonus_paths_by_recharge[bonus_key] = entity_path
        elif entity["type"] == "stored_value_expiry_event":
            lot = domain_entities.get(payload["lot_id"])
            if lot is None or lot["type"] != "stored_value_lot":
                _fail(entity_path + ".lot_id", "must reference a stored_value_lot")
            if payload["currency"] != lot["payload"]["currency"]:
                _fail(entity_path + ".currency", "must match the lot currency")
            amount = _amount(
                payload["amount"], payload["currency"], entity_path + ".amount", precisions
            )
            if amount <= 0:
                _fail(entity_path + ".amount", "must be positive")

            history = payload["status_history"]
            _unique_index(history, entity_path + ".status_history")
            if [event["sequence"] for event in history] != list(
                range(1, len(history) + 1)
            ):
                _fail(
                    entity_path + ".status_history",
                    "sequence must be contiguous and ordered from 1",
                )
            statuses = [event["status"] for event in history]
            if statuses not in (["reminder"], ["reminder", "confirmed"]):
                _fail(
                    entity_path + ".status_history",
                    "must be reminder or the append-only transition reminder then confirmed",
                )
            previous_recorded_at: datetime | None = None
            for history_index, event in enumerate(history):
                recorded_at = _timestamp(
                    event["recorded_at"],
                    entity_path + f".status_history[{history_index}].recorded_at",
                    timezone,
                )
                if previous_recorded_at is not None and recorded_at <= previous_recorded_at:
                    _fail(
                        entity_path + f".status_history[{history_index}].recorded_at",
                        "must be strictly later than the previous history event",
                    )
                previous_recorded_at = recorded_at

            if statuses[-1] == "confirmed":
                transaction_id = history[-1]["loss_transaction_id"]
                transaction = transactions.get(transaction_id)
                if transaction is None or transaction["type"] != "stored_value_expiry_loss":
                    _fail(
                        entity_path + ".status_history[-1].loss_transaction_id",
                        "must reference a stored_value_expiry_loss transaction",
                    )
                prior_owner = expiry_loss_owners.get(transaction_id)
                if prior_owner is not None:
                    _fail(
                        entity_path + ".status_history[-1].loss_transaction_id",
                        f"expiry loss transaction is already owned by {prior_owner!r}",
                    )
                expiry_loss_owners[transaction_id] = entity["id"]
                loss_postings = current_postings(transaction)
                if (
                    len(loss_postings) != 2
                    or sum(
                        posting.get("role") == "stored_value_expiry_loss"
                        for posting in loss_postings
                    )
                    != 1
                    or sum(
                        posting.get("role") == "stored_value_asset"
                        for posting in loss_postings
                    )
                    != 1
                ):
                    _fail(
                        entity_path + ".status_history[-1].loss_transaction_id",
                        "expiry loss transaction must contain exactly two postings: one loss and one stored-value asset leg",
                    )
                if any(posting["currency"] != payload["currency"] for posting in loss_postings):
                    _fail(entity_path + ".currency", "must match every loss transaction posting")
                loss_amount = sum(
                    (
                        _decimal(posting["amount"], entity_path + ".amount")
                        for posting in loss_postings
                        if posting.get("role") == "stored_value_expiry_loss"
                    ),
                    Decimal(0),
                )
                asset_amount = sum(
                    (
                        _decimal(posting["amount"], entity_path + ".amount")
                        for posting in loss_postings
                        if posting.get("role") == "stored_value_asset"
                    ),
                    Decimal(0),
                )
                if loss_amount != amount or asset_amount != -amount:
                    _fail(
                        entity_path + ".amount",
                        "must match the loss and stored-value postings",
                    )
                recharge = transactions[lot["payload"]["recharge_transaction_id"]]
                recharge_accounts = {
                    posting["account_id"]
                    for posting in current_postings(recharge)
                    if posting.get("role") == "stored_value_asset"
                }
                loss_accounts = {
                    posting["account_id"]
                    for posting in loss_postings
                    if posting.get("role") == "stored_value_asset"
                }
                if loss_accounts != recharge_accounts:
                    _fail(
                        entity_path + ".lot_id",
                        "loss transaction must target the lot stored-value account",
                    )
                face_value = _amount(
                    lot["payload"]["face_value"],
                    lot["payload"]["currency"],
                    entity_path + ".lot_id.face_value",
                    precisions,
                )
                if amount > face_value:
                    _fail(entity_path + ".amount", "must not exceed the lot face_value")
                version = indexes["transaction_versions"][transaction["current_version_id"]]
                for time_field in ("occurred_at", "statistics_at", "effective_at"):
                    if _timestamp_instant(version[time_field]) != previous_recorded_at:
                        _fail(
                            path + f".transaction_versions[{version['id']}].{time_field}",
                            "must match the confirmed expiry recorded_at instant",
                        )
        elif entity["type"] == "activation_adjustment":
            transaction = transactions.get(payload["transaction_id"])
            if (
                transaction is None
                or transaction["type"]
                != "stored_value_pre_activation_balance_adjustment"
            ):
                _fail(
                    entity_path + ".transaction_id",
                    "must reference a stored_value_pre_activation_balance_adjustment transaction",
                )
        elif entity["type"] == "stored_value_reconstruction":
            reconstructions.append((index, entity))
            adjustment = domain_entities.get(payload["adjustment_id"])
            if adjustment is None or adjustment["type"] != "activation_adjustment":
                _fail(
                    entity_path + ".adjustment_id",
                    "must reference an activation_adjustment domain entity",
                )
            prior_group = reconstruction_adjustments.get(payload["adjustment_id"])
            if prior_group is not None:
                _fail(
                    entity_path + ".adjustment_id",
                    f"adjustment endpoint already belongs to reconstruction group {prior_group!r}",
                )
            reconstruction_adjustments[payload["adjustment_id"]] = entity["id"]

            transaction_ids = payload["reconstructed_transaction_ids"]
            if len(transaction_ids) != len(set(transaction_ids)):
                _fail(
                    entity_path + ".reconstructed_transaction_ids",
                    "contains duplicate transaction endpoints",
                )
            if payload["active_mode"] == "reconstructed" and not transaction_ids:
                _fail(
                    entity_path + ".reconstructed_transaction_ids",
                    "reconstructed mode requires at least one transaction endpoint",
                )
            for transaction_index, transaction_id in enumerate(transaction_ids):
                transaction_path = (
                    entity_path
                    + f".reconstructed_transaction_ids[{transaction_index}]"
                )
                if transaction_id not in transactions:
                    _fail(transaction_path, "dangling transaction endpoint")
            history = payload["history"]
            _unique_index(history, entity_path + ".history")
            if [event["sequence"] for event in history] != list(
                range(1, len(history) + 1)
            ):
                _fail(
                    entity_path + ".history",
                    "sequence must be contiguous and ordered from 1",
                )
            if history[0]["active_mode"] != "adjustment":
                _fail(
                    entity_path + ".history[0].active_mode",
                    "reconstruction history must begin with adjustment ownership",
                )
            previous_confirmed_at: datetime | None = None
            for history_index, event in enumerate(history):
                confirmed_at = _timestamp(
                    event["confirmed_at"],
                    entity_path + f".history[{history_index}].confirmed_at",
                    timezone,
                )
                if (
                    previous_confirmed_at is not None
                    and confirmed_at <= previous_confirmed_at
                ):
                    _fail(
                        entity_path + f".history[{history_index}].confirmed_at",
                        "must be strictly later than the previous history event",
                    )
                previous_confirmed_at = confirmed_at
                if (
                    history_index > 0
                    and event["active_mode"]
                    == history[history_index - 1]["active_mode"]
                ):
                    _fail(
                        entity_path + f".history[{history_index}].active_mode",
                        "history events must record an ownership mode change",
                    )
            if history[-1]["active_mode"] != payload["active_mode"]:
                _fail(
                    entity_path + ".active_mode",
                    "must match the latest reconstruction history event",
                )

    business_bonus_amounts: dict[tuple[str, str], Decimal] = defaultdict(Decimal)
    business_bonus_paths: dict[tuple[str, str], str] = {}
    for transaction_index, transaction in enumerate(state["transactions"]):
        if transaction["type"] != "stored_value_recharge":
            continue
        for posting in current_postings(transaction):
            if posting.get("role") != "stored_value_bonus_income":
                continue
            bonus_key = (transaction["id"], posting["currency"])
            business_bonus_amounts[bonus_key] -= _decimal(
                posting["amount"], path + ".postings.amount"
            )
            business_bonus_paths[bonus_key] = (
                f"{path}.transactions[{transaction_index}].bonus_component"
            )
    for bonus_key in set(bonus_amounts_by_recharge) | set(business_bonus_amounts):
        fact_amount = bonus_amounts_by_recharge.get(bonus_key, Decimal(0))
        business_amount = business_bonus_amounts.get(bonus_key, Decimal(0))
        bonus_path = (
            bonus_paths_by_recharge.get(bonus_key)
            or business_bonus_paths[bonus_key]
        )
        if fact_amount != business_amount:
            _fail(
                bonus_path + ".amount",
                "bonus-income postings must be exactly covered by bonus components",
            )

    for transaction_index, transaction in enumerate(state["transactions"]):
        if (
            transaction["type"] == "stored_value_expiry_loss"
            and transaction["id"] not in expiry_loss_owners
        ):
            _fail(
                f"{path}.transactions[{transaction_index}].type",
                "stored_value_expiry_loss transaction must be owned by exactly one confirmed expiry event",
            )

    audit_rules = {
        "adjustment_transaction": ("balance_adjustment", "balance_adjustment"),
        "explanation_transaction": ("explanation_allocation", "account_transfer"),
        "allocation_reversal": ("explanation_allocation", "balance_adjustment_reversal"),
    }
    for index, link in enumerate(state["audit_links"]):
        link_path = f"{path}.audit_links[{index}]"
        if link["type"] in {
            "reconstruction_adjustment",
            "reconstruction_transaction",
        }:
            if link["from"]["kind"] != "domain_entity":
                _fail(
                    link_path + ".from.kind",
                    "reconstruction audit source must be a domain_entity",
                )
            source = domain_entities.get(link["from"]["id"])
            if source is None or source["type"] != "stored_value_reconstruction":
                _fail(
                    link_path + ".from.id",
                    "reconstruction audit source must identify its reconstruction group",
                )
            if link["type"] == "reconstruction_adjustment":
                if link["to"]["kind"] != "domain_entity":
                    _fail(
                        link_path + ".to.kind",
                        "reconstruction adjustment endpoint must be a domain_entity",
                    )
                target = domain_entities.get(link["to"]["id"])
                if target is None or target["type"] != "activation_adjustment":
                    _fail(
                        link_path + ".to.id",
                        "dangling or mistyped reconstruction adjustment endpoint",
                    )
                expected_target_id = source["payload"]["adjustment_id"]
            else:
                if link["to"]["kind"] != "transaction":
                    _fail(
                        link_path + ".to.kind",
                        "reconstruction transaction endpoint must be a transaction",
                    )
                if link["to"]["id"] not in transactions:
                    _fail(
                        link_path + ".to.id",
                        "dangling reconstruction transaction endpoint",
                    )
                expected_transaction_ids = source["payload"][
                    "reconstructed_transaction_ids"
                ]
                if link["to"]["id"] not in expected_transaction_ids:
                    _fail(
                        link_path + ".to.id",
                        "audit target does not belong to the reconstruction group",
                    )
                continue
            if link["to"]["id"] != expected_target_id:
                _fail(
                    link_path + ".to.id",
                    "audit target does not match the reconstruction group payload",
                )
            continue
        from_type, to_type = audit_rules[link["type"]]
        if link["from"]["kind"] != "domain_entity":
            _fail(link_path + ".from.kind", "audit source must be a domain_entity")
        source = domain_entities.get(link["from"]["id"])
        if source is None or source["type"] != from_type:
            _fail(link_path + ".from.id", "dangling or mistyped audit source")
        if link["to"]["kind"] != "transaction":
            _fail(link_path + ".to.kind", "audit target must be a transaction")
        target = transactions.get(link["to"]["id"])
        if target is None or target["type"] != to_type:
            _fail(link_path + ".to.id", "dangling or mistyped audit target")
        if link["type"] == "adjustment_transaction":
            expected_target_id = source["payload"]["transaction_id"]
        elif link["type"] == "explanation_transaction":
            expected_target_id = source["payload"]["explanation_transaction_id"]
        else:
            expected_target_id = source["payload"]["reversal_transaction_id"]
        if link["to"]["id"] != expected_target_id:
            _fail(link_path + ".to.id", "audit target does not match the domain entity payload")

    reconstruction_links: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for link in state["audit_links"]:
        if link["type"].startswith("reconstruction_"):
            reconstruction_links[link["from"]["id"]].append(link)
    for entity_index, reconstruction in reconstructions:
        entity_path = f"{path}.domain_entities[{entity_index}].payload"
        payload = reconstruction["payload"]
        expected_endpoints = {
            ("reconstruction_adjustment", payload["adjustment_id"]): 1,
            **{
                ("reconstruction_transaction", transaction_id): 1
                for transaction_id in payload["reconstructed_transaction_ids"]
            },
        }
        actual_endpoints: dict[tuple[str, str], int] = defaultdict(int)
        for link in reconstruction_links[reconstruction["id"]]:
            actual_endpoints[(link["type"], link["to"]["id"])] += 1
        if dict(actual_endpoints) != expected_endpoints:
            _fail(
                path + ".audit_links",
                f"reconstruction group {reconstruction['id']!r} must have exactly one typed audit link per endpoint",
            )


def _validate_reconciliations(
    state: dict[str, Any],
    path: str,
    indexes: dict[str, dict[str, dict[str, Any]]],
) -> dict[str, str]:
    postings = indexes["postings"]
    accounts = indexes["catalog_accounts"]
    by_posting: dict[str, str] = {}
    for index, reconciliation in enumerate(state["posting_reconciliations"]):
        reconciliation_path = f"{path}.posting_reconciliations[{index}]"
        posting = postings.get(reconciliation["posting_id"])
        if posting is None:
            _fail(reconciliation_path + ".posting_id", "dangling posting reference")
        account = accounts[posting["account_id"]]
        if not (
            posting["reconciliation_eligible"]
            and account["reconciliation_eligible"]
            and account["owned_by_user"]
            and account["real_account"]
            and account["kind"] in {"asset", "liability"}
        ):
            _fail(
                reconciliation_path + ".posting_id",
                "reconciliation requires an eligible owned real account posting",
            )
        if posting["id"] in by_posting:
            _fail(reconciliation_path + ".posting_id", "posting has duplicate reconciliation records")
        by_posting[posting["id"]] = reconciliation["status"]
    eligible = {posting["id"] for posting in postings.values() if posting["reconciliation_eligible"]}
    if set(by_posting) != eligible:
        _fail(
            path + ".posting_reconciliations",
            f"must exactly cover eligible postings; missing={sorted(eligible - set(by_posting))}, extra={sorted(set(by_posting) - eligible)}",
        )
    return by_posting


def _in_period(version: dict[str, Any], report: dict[str, Any]) -> bool:
    timestamp = version["statistics_at"]
    if report["period_type"] == "day":
        return timestamp[:10] == report["period"]
    if report["period_type"] == "month":
        return timestamp[:7] == report["period"]
    return True


def _report_values(
    current: dict[str, tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]],
    accounts: dict[str, dict[str, Any]],
    report: dict[str, Any],
    currency: str,
) -> dict[str, Decimal]:
    values: dict[str, Decimal] = defaultdict(Decimal)
    for transaction, version, postings in current.values():
        if transaction["type"] == "opening_balance" or not _in_period(version, report):
            continue
        selected = [posting for posting in postings if posting["currency"] == currency]
        transaction_type = transaction["type"]
        if transaction_type == "expense":
            expense = sum(
                (_decimal(item["amount"], "$.postings.amount") for item in selected if item.get("role") == "expense"),
                Decimal(0),
            )
            values["consumption"] += expense
            values["ordinary_expense"] += expense
            values["expense"] += expense
            values["net_worth_change"] -= expense
            values["cash_outflow"] += sum(
                (-_decimal(item["amount"], "$.postings.amount") for item in selected if accounts[item["account_id"]]["real_account"] and _decimal(item["amount"], "$.postings.amount") < 0),
                Decimal(0),
            )
        elif transaction_type == "income":
            income = sum(
                (-_decimal(item["amount"], "$.postings.amount") for item in selected if accounts[item["account_id"]]["kind"] == "income"),
                Decimal(0),
            )
            values["income"] += income
            values["ordinary_income"] += income
            values["net_worth_change"] += income
            values["cash_inflow"] += sum(
                (_decimal(item["amount"], "$.postings.amount") for item in selected if accounts[item["account_id"]]["real_account"] and _decimal(item["amount"], "$.postings.amount") > 0),
                Decimal(0),
            )
        elif transaction_type == "account_transfer":
            principal = sum(
                (_decimal(item["amount"], "$.postings.amount") for item in selected if item.get("role") == "transfer_principal_in" and _decimal(item["amount"], "$.postings.amount") > 0),
                Decimal(0),
            )
            fee = sum(
                (_decimal(item["amount"], "$.postings.amount") for item in selected if item.get("role") == "transfer_fee"),
                Decimal(0),
            )
            values["internal_transfer_amount"] += principal
            values["consumption"] += fee
            values["ordinary_expense"] += fee
            values["expense"] += fee
            values["cash_outflow"] += fee
            values["net_worth_change"] -= fee
        elif transaction_type in {"balance_adjustment", "balance_adjustment_reversal"}:
            target_roles = {
                "balance_adjustment_target",
                "balance_adjustment_reversal_target",
            }
            change = sum(
                (_decimal(item["amount"], "$.postings.amount") for item in selected if item.get("role") in target_roles),
                Decimal(0),
            )
            values["balance_adjustment_net_worth_change"] += change
            values["net_worth_change"] += change
        elif transaction_type == "refund_receipt":
            correction = sum(
                (_decimal(item["amount"], "$.postings.amount") for item in selected if accounts[item["account_id"]]["kind"] == "expense"),
                Decimal(0),
            )
            values["consumption"] += correction
            values["cash_inflow"] += -correction
            values["net_worth_change"] -= correction
        elif transaction_type == "credit_repayment":
            values["cash_outflow"] += sum(
                (-_decimal(item["amount"], "$.postings.amount") for item in selected if accounts[item["account_id"]]["kind"] == "asset" and _decimal(item["amount"], "$.postings.amount") < 0),
                Decimal(0),
            )
    values["budget"] += Decimal(0)
    return values


def _validate_reports(
    case_id: str,
    state: dict[str, Any],
    path: str,
    indexes: dict[str, dict[str, dict[str, Any]]],
    current: dict[str, tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]],
    precisions: dict[str, int],
) -> None:
    _unique_compound(state["reports"], path + ".reports", ("period_type", "period"))
    if case_id == "RG-02":
        report_keys = {(item["period_type"], item["period"]) for item in state["reports"]}
        day_periods = [period for period_type, period in report_keys if period_type == "day"]
        month_periods = [period for period_type, period in report_keys if period_type == "month"]
        if (
            len(report_keys) != 2
            or len(day_periods) != 1
            or len(month_periods) != 1
            or month_periods[0] != day_periods[0][:7]
        ):
            _fail(
                path + ".reports",
                "RG-02 requires exactly one matching day and month report",
            )
    expected_metric_sets = {
        "RG-01": {"cash_outflow", "consumption", "income", "net_worth_change", "budget"},
        "RG-02": {
            "budget",
            "cash_inflow",
            "cash_outflow",
            "consumption",
            "income",
            "net_worth_change",
        },
        "RG-09": {
            "balance_adjustment_net_worth_change",
            "budget",
            "cash_inflow",
            "cash_outflow",
            "consumption",
            "internal_transfer_amount",
            "net_worth_change",
            "ordinary_expense",
            "ordinary_income",
        },
    }
    for report_index, report in enumerate(state["reports"]):
        report_path = f"{path}.reports[{report_index}]"
        _unique_compound(report["metrics"], report_path + ".metrics", ("metric", "currency"))
        if case_id in expected_metric_sets:
            actual_names = {metric["metric"] for metric in report["metrics"]}
            if actual_names != expected_metric_sets[case_id]:
                _fail(report_path + ".metrics", "does not match the representative metric registry")
        for metric_index, metric in enumerate(report["metrics"]):
            metric_path = f"{report_path}.metrics[{metric_index}]"
            expected_applicability = (
                "not_applicable"
                if case_id in {"RG-01", "RG-02"} and metric["metric"] == "budget"
                else "applicable"
            )
            if metric["applicability"] != expected_applicability:
                _fail(metric_path + ".applicability", f"must be {expected_applicability}")
            if expected_applicability == "not_applicable":
                continue
            currency = metric["currency"]
            actual = _amount(metric["amount"], currency, metric_path + ".amount", precisions)
            expected = _report_values(
                current, indexes["catalog_accounts"], report, currency
            )[metric["metric"]]
            if actual != expected:
                _fail(metric_path + ".amount", f"must recompute to {expected}, got {actual}")


def _transaction_reconciliation_status(statuses: list[str]) -> str:
    if any(status == "has_difference" for status in statuses):
        return "has_difference"
    if all(status == "matched" for status in statuses):
        return "matched"
    if all(status == "pending" for status in statuses):
        return "pending"
    return "partial"


def _expected_derived_statuses(
    state: dict[str, Any],
    indexes: dict[str, dict[str, dict[str, Any]]],
    current: dict[str, tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]],
    reconciliation_by_posting: dict[str, str],
) -> dict[tuple[str, str, str], str]:
    expected: dict[tuple[str, str, str], str] = {}
    for candidate in state["candidates"]:
        expected[("candidate", candidate["id"], "confirmation_status")] = candidate[
            "status_history"
        ][-1]["status"]

    for transaction, _, postings in current.values():
        eligible = [item for item in postings if item["reconciliation_eligible"]]
        if eligible:
            statuses = [reconciliation_by_posting[item["id"]] for item in eligible]
            expected[("transaction", transaction["id"], "reconciliation_summary")] = (
                _transaction_reconciliation_status(statuses)
            )

    entities = indexes["domain_entities"]
    transactions = indexes["transactions"]
    allocations_by_adjustment: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for entity in state["domain_entities"]:
        if entity["type"] == "explanation_allocation":
            allocations_by_adjustment[entity["payload"]["adjustment_id"]].append(entity)

    for adjustment in state["domain_entities"]:
        if adjustment["type"] != "balance_adjustment":
            continue
        original = abs(_decimal(adjustment["payload"]["original_delta"], "$.original_delta"))
        allocations = allocations_by_adjustment[adjustment["id"]]
        explained = sum(
            (abs(_decimal(item["payload"]["amount"], "$.amount")) for item in allocations),
            Decimal(0),
        )
        if explained > original:
            _fail("$.states.domain_entities", "explanation allocations exceed original adjustment")
        if explained == 0:
            explanation_status = "open"
        elif explained == original:
            explanation_status = "fully_explained"
        else:
            explanation_status = "partially_explained"
        expected[("domain_entity", adjustment["id"], "explanation_status")] = explanation_status

        observation_id = adjustment["payload"]["observation_id"]
        observation = entities[observation_id]
        target_account = observation["payload"]["account_id"]
        allocated_transactions = {
            item["payload"]["explanation_transaction_id"] for item in allocations
        }
        unexplained_transfers = []
        for transaction_id, (transaction, version, postings) in current.items():
            if transaction["type"] != "account_transfer" or transaction_id in allocated_transactions:
                continue
            if any(item["account_id"] == target_account for item in postings) and (
                _timestamp_instant(version["effective_at"])
                <= _timestamp_instant(observation["payload"]["observed_at"])
            ):
                unexplained_transfers.append(transaction_id)
        remaining = original - explained
        if remaining != 0:
            verification = (
                "difference_pending_explanation_confirmation"
                if unexplained_transfers
                else "balanced_with_unexplained_adjustment"
            )
        else:
            relevant_transactions = {
                item["payload"]["explanation_transaction_id"] for item in allocations
            }
            relevant_postings = []
            for transaction_id in relevant_transactions:
                _, _, postings = current[transaction_id]
                relevant_postings.extend(
                    item for item in postings if item["reconciliation_eligible"]
                )
            fully_matched = bool(relevant_postings) and all(
                reconciliation_by_posting[item["id"]] == "matched" for item in relevant_postings
            )
            evidence_targets = {
                link["target_id"]
                for link in state["evidence_links"]
                if link["role"] == "real_account_posting"
            }
            fully_evidenced = bool(relevant_postings) and all(
                item["id"] in evidence_targets for item in relevant_postings
            )
            verification = "fully_reconciled" if fully_matched and fully_evidenced else "evidence_incomplete"
        expected[("observation", observation_id, "verification_status")] = verification
    return expected


def _validate_derived_statuses(
    state: dict[str, Any],
    path: str,
    indexes: dict[str, dict[str, dict[str, Any]]],
    operations: dict[str, dict[str, Any]],
    current: dict[str, tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]],
    reconciliation_by_posting: dict[str, str],
) -> None:
    status_rules = {
        "explanation_status": (
            "domain_entity",
            {"open", "partially_explained", "fully_explained"},
        ),
        "verification_status": (
            "observation",
            {
                "balanced_with_unexplained_adjustment",
                "difference_pending_explanation_confirmation",
                "evidence_incomplete",
                "fully_reconciled",
            },
        ),
        "reconciliation_summary": (
            "transaction",
            {"pending", "partial", "matched", "has_difference"},
        ),
        "confirmation_status": (
            "candidate",
            {"pending_confirmation", "confirmed", "rejected", "incomplete"},
        ),
    }
    _unique_compound(
        state["derived_statuses"],
        path + ".derived_statuses",
        ("target_kind", "target_id", "status_name"),
    )
    actual: dict[tuple[str, str, str], str] = {}
    for index, status in enumerate(state["derived_statuses"]):
        status_path = f"{path}.derived_statuses[{index}]"
        rule = status_rules.get(status["status_name"])
        if rule is None:
            _fail(status_path + ".status_name", "is not registered")
        expected_kind, allowed_values = rule
        if status["target_kind"] != expected_kind:
            _fail(
                status_path + ".target_kind",
                f"{status['status_name']} requires target_kind {expected_kind}",
            )
        if status["value"] not in allowed_values:
            _fail(
                status_path + ".value",
                f"is not registered for {status['status_name']}",
            )
        _resolve_ref(
            state,
            indexes,
            operations,
            status["target_kind"],
            status["target_id"],
            status_path + ".target_id",
        )
        actual[(status["target_kind"], status["target_id"], status["status_name"])] = status[
            "value"
        ]
    expected = _expected_derived_statuses(state, indexes, current, reconciliation_by_posting)
    if actual != expected:
        _fail(path + ".derived_statuses", f"must exactly recompute to {expected}, got {actual}")


def _balance_map(state: dict[str, Any]) -> dict[tuple[str, str], str]:
    return {(item["account_id"], item["currency"]): item["amount"] for item in state["balances"]}


def _report_map(state: dict[str, Any]) -> dict[tuple[str, str, str, str | None], dict[str, Any]]:
    result: dict[tuple[str, str, str, str | None], dict[str, Any]] = {}
    for report in state["reports"]:
        for metric in report["metrics"]:
            currency = metric.get("currency")
            key = (report["period_type"], report["period"], metric["metric"], currency)
            result[key] = {key: value for key, value in metric.items() if key != "metric"}
    return result


def _status_map(state: dict[str, Any]) -> dict[tuple[str, str, str], str]:
    return {
        (item["target_kind"], item["target_id"], item["status_name"]): item["value"]
        for item in state["derived_statuses"]
    }


def _changes(before: dict[Any, Any], after: dict[Any, Any]) -> dict[Any, tuple[Any, Any]]:
    result: dict[Any, tuple[Any, Any]] = {}
    for key in set(before) | set(after):
        old = before.get(key)
        new = after.get(key)
        if not _contract_equivalent(old, new):
            result[key] = (old, new)
    return result


def _expected_entity_changes(
    baseline: dict[str, Any], result: dict[str, Any]
) -> dict[str, dict[str, list[str]]]:
    changes: dict[str, dict[str, list[str]]] = {}
    for name, parts in _ENTITY_COLLECTIONS.items():
        before = {item["id"]: item for item in _collection(baseline, parts)}
        after = {item["id"]: item for item in _collection(result, parts)}
        changes[name] = {
            "added_ids": sorted(set(after) - set(before)),
            "changed_ids": sorted(
                item_id
                for item_id in set(before) & set(after)
                if not _contract_equivalent(before[item_id], after[item_id])
            ),
            "removed_ids": sorted(set(before) - set(after)),
        }
    return changes


def _declared_id_changes(value: dict[str, Any], path: str) -> dict[str, list[str]]:
    result: dict[str, list[str]] = {}
    for name in ("added_ids", "changed_ids", "removed_ids"):
        ids = value[name]
        if len(ids) != len(set(ids)):
            _fail(path + f".{name}", "contains duplicate IDs")
        result[name] = sorted(ids)
    return result


def _declared_balance_changes(items: list[dict[str, Any]], path: str) -> dict[Any, tuple[Any, Any]]:
    result = {}
    for index, item in enumerate(items):
        key = (item["key"]["account_id"], item["key"]["currency"])
        if key in result:
            _fail(f"{path}[{index}].key", "duplicate balance change key")
        result[key] = (item["before"], item["after"])
    return result


def _declared_report_changes(items: list[dict[str, Any]], path: str) -> dict[Any, tuple[Any, Any]]:
    result = {}
    for index, item in enumerate(items):
        key_object = item["key"]
        key = (
            key_object["period_type"],
            key_object["period"],
            key_object["metric"],
            key_object.get("currency"),
        )
        if key in result:
            _fail(f"{path}[{index}].key", "duplicate report change key")
        result[key] = (item["before"], item["after"])
    return result


def _declared_status_changes(items: list[dict[str, Any]], path: str) -> dict[Any, tuple[Any, Any]]:
    result = {}
    for index, item in enumerate(items):
        key_object = item["key"]
        key = (key_object["kind"], key_object["target_id"], key_object["status_name"])
        if key in result:
            _fail(f"{path}[{index}].key", "duplicate derived-status change key")
        result[key] = (item["before"], item["after"])
    return result


def _validate_returned_ids(
    operation: dict[str, Any],
    operation_path: str,
    result: dict[str, Any],
    indexes: dict[str, dict[str, dict[str, Any]]],
    operations: dict[str, dict[str, Any]],
) -> None:
    seen: set[tuple[str, str]] = set()
    for index, reference in enumerate(operation["returned_ids"]):
        returned_path = f"{operation_path}.returned_ids[{index}]"
        key = (reference["kind"], reference["id"])
        if key in seen:
            _fail(returned_path, "duplicate returned ID")
        seen.add(key)
        if reference["kind"] == "operation":
            target = operations.get(reference["id"])
            if target is None or target["root_id"] != operation["root_id"] or target["sequence"] > operation["sequence"]:
                _fail(returned_path + ".id", "must reference this or a prior same-root operation")
        else:
            _resolve_ref(
                result,
                indexes,
                operations,
                reference["kind"],
                reference["id"],
                returned_path + ".id",
            )


def _validate_rejected_manual_expense_attempt(
    operation: dict[str, Any],
    operation_path: str,
    baseline: dict[str, Any],
    precisions: dict[str, int],
    timezone: ZoneInfo,
) -> None:
    attempted = operation["attempted_input"]
    attempted_path = operation_path + ".attempted_input"
    accounts = {item["id"]: item for item in baseline["catalog"]["accounts"]}
    categories = {item["id"]: item for item in baseline["catalog"]["categories"]}

    category_id = attempted.get("category_id")
    category = None
    if category_id is not None:
        category = categories.get(category_id)
        if category is None:
            _fail(attempted_path + ".category_id", "dangling category reference")

    payment_account_id = attempted.get("payment_account_id")
    payment_account = None
    if payment_account_id is not None:
        payment_account = accounts.get(payment_account_id)
        if payment_account is None:
            _fail(attempted_path + ".payment_account_id", "dangling account reference")

    currency = attempted.get("currency")
    amount = attempted.get("amount")
    if currency is not None:
        if currency not in precisions:
            _fail(attempted_path + ".currency", "undeclared currency")
        if payment_account is not None and payment_account["currency"] != currency:
            _fail(
                attempted_path + ".currency",
                "must match the attempted payment account",
            )
        if category is not None and category["posting_account_id"] is not None:
            posting_account = accounts.get(category["posting_account_id"])
            if posting_account is None or posting_account["currency"] != currency:
                _fail(
                    attempted_path + ".currency",
                    "must match the attempted category posting account",
                )
        if amount is not None:
            _attempted_amount(
                amount, currency, attempted_path + ".amount", precisions
            )

    if "occurred_at" in attempted:
        _timestamp(
            attempted["occurred_at"],
            attempted_path + ".occurred_at",
            timezone,
        )

    failure: tuple[str, str]
    if amount is None:
        failure = ("amount", "missing_required_field")
    elif payment_account_id is None:
        failure = ("payment_account_id", "missing_required_field")
    elif category_id is None:
        failure = ("category_id", "missing_required_field")
    elif Decimal(amount) <= 0:
        failure = ("amount", "must_be_positive")
    elif category is not None and category["parent_id"] is None:
        failure = ("category_id", "secondary_category_required")
    elif category is not None and not category["active"]:
        failure = ("category_id", "category_inactive")
    else:
        _fail(
            attempted_path,
            "does not match a registered rejected manual_expense failure",
        )

    expected_field, expected_reason = failure
    outcome = operation["outcome"]
    expected_path = f"$.attempted_input.{expected_field}"
    if outcome["field_path"] != expected_path:
        _fail(
            operation_path + ".outcome.field_path",
            f"must be {expected_path!r} for the first failing attempted field",
        )
    if outcome["reason_code"] != expected_reason:
        _fail(
            operation_path + ".outcome.reason_code",
            f"must be {expected_reason!r} for the first failing attempted field",
        )


def _validate_rejected_manual_income_attempt(
    operation: dict[str, Any],
    operation_path: str,
    baseline: dict[str, Any],
    precisions: dict[str, int],
    timezone: ZoneInfo,
) -> None:
    attempted = operation["attempted_input"]
    attempted_path = operation_path + ".attempted_input"
    accounts = {item["id"]: item for item in baseline["catalog"]["accounts"]}
    categories = {item["id"]: item for item in baseline["catalog"]["categories"]}

    account_id = attempted.get("receiving_account_id")
    account = accounts.get(account_id) if account_id is not None else None
    if account_id is not None and account is None:
        _fail(attempted_path + ".receiving_account_id", "dangling account reference")
    category_id = attempted.get("category_id")
    category = categories.get(category_id) if category_id is not None else None
    if category_id is not None and category is None:
        _fail(attempted_path + ".category_id", "dangling category reference")

    currency = attempted.get("currency")
    amount = attempted.get("amount")
    if currency is not None:
        if currency not in precisions:
            _fail(attempted_path + ".currency", "undeclared currency")
        if amount is not None:
            _attempted_amount(amount, currency, attempted_path + ".amount", precisions)
    if "occurred_at" in attempted:
        _timestamp(attempted["occurred_at"], attempted_path + ".occurred_at", timezone)

    if amount is None:
        failure = ("amount", "required")
    elif Decimal(amount) <= 0:
        failure = ("amount", "must_be_positive")
    elif account_id is None:
        failure = ("receiving_account_id", "required")
    elif category_id is None:
        failure = ("category_id", "required")
    elif category is not None and category["parent_id"] is None:
        failure = ("category_id", "secondary_category_required")
    elif category is not None and not category["active"]:
        failure = ("category_id", "category_inactive")
    elif category is not None and (
        category["posting_account_id"] is None
        or accounts[category["posting_account_id"]]["kind"] != "income"
    ):
        failure = ("category_id", "income_category_required")
    else:
        _fail(
            attempted_path,
            "does not match a registered rejected manual_income failure",
        )

    field, reason = failure
    outcome = operation["outcome"]
    if outcome["field_path"] != f"$.attempted_input.{field}":
        _fail(
            operation_path + ".outcome.field_path",
            f"must identify {field!r} as the first failing attempted field",
        )
    if outcome["reason_code"] != reason:
        _fail(
            operation_path + ".outcome.reason_code",
            f"must be {reason!r} for the first failing attempted field",
        )


def _validate_rg10_structural_input(
    input_value: dict[str, Any],
    input_path: str,
    baseline: dict[str, Any],
    precisions: dict[str, int],
    timezone: ZoneInfo,
    *,
    attempted: bool,
) -> None:
    accounts = {item["id"]: item for item in baseline["catalog"]["accounts"]}
    categories = {item["id"]: item for item in baseline["catalog"]["categories"]}
    lots = {
        item["id"]: item
        for item in baseline["domain_entities"]
        if item["type"] == "stored_value_lot"
    }
    references = {
        "account_id": accounts,
        "payment_account_id": accounts,
        "stored_value_account_id": accounts,
        "category_id": categories,
        "lot_id": lots,
        "source_id": {item["id"]: item for item in baseline["sources"]},
        "evidence_id": {item["id"]: item for item in baseline["evidence"]},
        "merchant_evidence_id": {item["id"]: item for item in baseline["evidence"]},
        "target_posting_id": {item["id"]: item for item in baseline["postings"]},
    }
    for field, collection in references.items():
        if field in input_value and input_value[field] not in collection:
            _fail(input_path + f".{field}", f"dangling or mistyped {field} reference")

    stored_value_account_id = input_value.get("stored_value_account_id")
    if stored_value_account_id in accounts and not accounts[stored_value_account_id].get(
        "stored_value", {}
    ).get("enabled", False):
        _fail(input_path + ".stored_value_account_id", "must reference an enabled stored-value account")

    currency = input_value.get("currency")
    if currency is not None and currency not in precisions:
        _fail(input_path + ".currency", "undeclared currency")
    for field in (
        "amount",
        "paid_amount",
        "credited_amount",
        "bonus_amount",
        "existing_balance",
    ):
        value = input_value.get(field)
        if value is None:
            continue
        if attempted:
            if currency is not None:
                _attempted_amount(value, currency, input_path + f".{field}", precisions)
            else:
                _decimal(value, input_path + f".{field}")
        elif currency is not None:
            _amount(value, currency, input_path + f".{field}", precisions)
        else:
            _decimal(value, input_path + f".{field}")

    for field in ("occurred_at", "created_at", "activation_at"):
        if field in input_value:
            _timestamp(input_value[field], input_path + f".{field}", timezone)

    for index, allocation in enumerate(input_value.get("allocations", [])):
        allocation_path = input_path + f".allocations[{index}]"
        if allocation["lot_id"] not in lots:
            _fail(allocation_path + ".lot_id", "dangling or mistyped lot reference")
        if currency is not None:
            _amount(allocation["amount"], currency, allocation_path + ".amount", precisions)
        else:
            _decimal(allocation["amount"], allocation_path + ".amount")
    for index, allocation in enumerate(input_value.get("lot_allocations", [])):
        allocation_path = input_path + f".lot_allocations[{index}]"
        if allocation["lot_id"] not in lots:
            _fail(allocation_path + ".lot_id", "dangling or mistyped lot reference")
        if currency is not None:
            _amount(allocation["amount"], currency, allocation_path + ".amount", precisions)
        else:
            _decimal(allocation["amount"], allocation_path + ".amount")


def _validate_rejected_rg10_attempt(
    operation: dict[str, Any],
    operation_path: str,
    baseline: dict[str, Any],
    precisions: dict[str, int],
    timezone: ZoneInfo,
) -> None:
    action = operation["action_type"]
    attempted = operation["attempted_input"]
    attempted_path = operation_path + ".attempted_input"
    outcome = operation["outcome"]
    reason = outcome["reason_code"]
    field_path = outcome["field_path"]
    registered = _RG10_REJECTION_REASON_FIELDS[action]
    if reason not in registered:
        _fail(operation_path + ".outcome.reason_code", "is not registered for this RG-10 action")
    expected_field = field_path.removeprefix("$.attempted_input.")
    if field_path != f"$.attempted_input.{expected_field}" or expected_field not in registered[reason]:
        _fail(
            operation_path + ".outcome.field_path",
            "does not match the registered field for this RG-10 rejection reason",
        )

    for field in ("occurred_at", "created_at", "activation_at", "actual_time"):
        if field in attempted:
            _timestamp(attempted[field], attempted_path + f".{field}", timezone)

    if reason in _RG10_UNOWNED_REJECTION_REASONS:
        if expected_field not in attempted:
            _fail(
                operation_path + ".outcome.field_path",
                "must locate the present attempted field for this gated RG-10 rejection",
            )
        _fail(
            attempted_path,
            f"cannot execute this rejection without a {_RG10_UNOWNED_REJECTION_REASONS[reason]}",
        )

    accounts = {item["id"]: item for item in baseline["catalog"]["accounts"]}
    categories = {item["id"]: item for item in baseline["catalog"]["categories"]}
    lots = {
        item["id"]: item
        for item in baseline["domain_entities"]
        if item["type"] == "stored_value_lot"
    }

    failure: tuple[str, str] | None = None
    if action == "confirm_stored_value_recharge":
        amounts: dict[str, Decimal] = {}
        for field in ("paid_amount", "credited_amount", "bonus_amount"):
            if field not in attempted:
                continue
            value = attempted[field]
            if not isinstance(value, str) or not _DECIMAL_PATTERN.fullmatch(value):
                failure = (field, "exact_decimal_string_required")
                break
            amounts[field] = Decimal(value)
        if failure is None and "paid_amount" in amounts and amounts["paid_amount"] <= 0:
            failure = ("paid_amount", "must_be_positive")
        elif failure is None and "credited_amount" in amounts and amounts["credited_amount"] <= 0:
            failure = ("credited_amount", "credited_amount_must_be_positive")
        elif failure is None and "bonus_amount" in amounts and amounts["bonus_amount"] < 0:
            failure = ("bonus_amount", "bonus_amount_must_be_zero_or_positive")
        elif failure is None and all(
            field in amounts for field in ("paid_amount", "credited_amount", "bonus_amount")
        ):
            if amounts["credited_amount"] < amounts["paid_amount"]:
                failure = ("credited_amount", "credited_must_equal_paid_plus_bonus")
            elif amounts["credited_amount"] != amounts["paid_amount"] + amounts["bonus_amount"]:
                failure = ("bonus_amount", "component_sum_mismatch")

        payment_id = attempted.get("payment_account_id")
        payment = accounts.get(payment_id) if payment_id is not None else None
        if failure is None and payment_id is not None and payment is None:
            failure = ("payment_account_id", "unknown_payment_account")
        elif failure is None and payment is not None and (
            payment["kind"] != "asset" or not payment["owned_by_user"]
        ):
            failure = ("payment_account_id", "owned_payment_asset_required")

        stored_id = attempted.get("stored_value_account_id")
        stored = accounts.get(stored_id) if stored_id is not None else None
        if failure is None and stored is not None and "stored_value" in stored:
            if not stored["stored_value"]["enabled"]:
                failure = ("stored_value_account_id", "stored_value_account_not_enabled")
        if failure is None and attempted.get("model") == "immediate_expense" and stored_id is not None:
            failure = ("model", "stored_value_models_must_not_overlap")
        if failure is None and "currency" in attempted and attempted["currency"] != "CNY":
            failure = ("currency", "same_cny_currency_required")

    elif action == "confirm_stored_value_spend":
        stored_id = attempted.get("stored_value_account_id")
        stored = accounts.get(stored_id) if stored_id is not None else None
        if stored_id is not None and (
            stored is None
            or stored["kind"] != "asset"
            or not stored["owned_by_user"]
            or not stored.get("stored_value", {}).get("enabled", False)
            or not stored["stored_value"].get("merchant_restricted", False)
        ):
            failure = (
                "stored_value_account_id",
                "enabled_restricted_stored_value_asset_required",
            )
        category_id = attempted.get("category_id")
        category = categories.get(category_id) if category_id is not None else None
        if failure is None and category_id is not None and (
            category is None
            or category["parent_id"] is None
            or category["posting_account_id"] is None
            or not category["active"]
        ):
            failure = ("category_id", "active_secondary_category_required")

    elif action == "confirm_stored_value_expiry_loss":
        if attempted.get("explicit_confirmation") is False:
            failure = (
                "explicit_confirmation",
                "actual_expiry_requires_explicit_confirmation",
            )
        lot_id = attempted.get("lot_id")
        if failure is None and lot_id is not None and lot_id not in lots:
            _fail(attempted_path + ".lot_id", "dangling or mistyped lot reference")

    if failure is None:
        _fail(attempted_path, "does not match an executable registered RG-10 rejection failure")
    expected_field, expected_reason = failure
    expected_path = f"$.attempted_input.{expected_field}"
    if field_path != expected_path:
        _fail(
            operation_path + ".outcome.field_path",
            f"must be {expected_path!r} for the first failing attempted field",
        )
    if reason != expected_reason:
        _fail(
            operation_path + ".outcome.reason_code",
            f"must be {expected_reason!r} for the first failing attempted field",
        )


def _validate_action_input(
    operation: dict[str, Any],
    operation_path: str,
    baseline: dict[str, Any],
    precisions: dict[str, int],
    timezone: ZoneInfo,
) -> None:
    action = operation["action_type"]
    if operation["outcome"]["status"] == "rejected":
        if action == "manual_expense":
            _validate_rejected_manual_expense_attempt(
                operation, operation_path, baseline, precisions, timezone
            )
        elif action == "manual_income":
            _validate_rejected_manual_income_attempt(
                operation, operation_path, baseline, precisions, timezone
            )
        elif action in _RG10_REJECTED_ACTIONS:
            _validate_rejected_rg10_attempt(
                operation,
                operation_path,
                baseline,
                precisions,
                timezone,
            )
        else:
            _fail(operation_path + ".action_type", "unregistered rejected action")
        return
    input_value = operation["input"]
    input_path = operation_path + ".input"
    accounts = {item["id"]: item for item in baseline["catalog"]["accounts"]}
    categories = {item["id"]: item for item in baseline["catalog"]["categories"]}
    transactions = {item["id"]: item for item in baseline["transactions"]}
    candidates = {item["id"]: item for item in baseline["candidates"]}
    entities = {item["id"]: item for item in baseline["domain_entities"]}

    if action in {"manual_expense", "manual_income"}:
        category = categories.get(input_value["category_id"])
        if category is None:
            _fail(input_path + ".category_id", "dangling category reference")
        account_field = (
            "payment_account_id" if action == "manual_expense" else "receiving_account_id"
        )
        payment_account = accounts.get(input_value[account_field])
        if payment_account is None:
            _fail(input_path + f".{account_field}", "dangling account reference")
        posting_account = accounts.get(category["posting_account_id"])
        if payment_account["currency"] != input_value["currency"] or (
            posting_account is not None
            and posting_account["currency"] != input_value["currency"]
        ):
            _fail(input_path + ".currency", "must match payment and category posting accounts")
        _amount(input_value["amount"], input_value["currency"], input_path + ".amount", precisions)
        if Decimal(input_value["amount"]) <= 0:
            _fail(input_path + ".amount", "must be positive")
        _timestamp(input_value["occurred_at"], input_path + ".occurred_at", timezone)
        if action == "manual_income":
            if not (
                payment_account["kind"] == "asset"
                and payment_account["owned_by_user"]
                and payment_account["real_account"]
            ):
                _fail(
                    input_path + ".receiving_account_id",
                    "must reference an owned real receiving asset",
                )
            if (
                category["parent_id"] is None
                or not category["active"]
                or posting_account is None
                or posting_account["kind"] != "income"
            ):
                _fail(
                    input_path + ".category_id",
                    "must reference an active second-level income category",
                )
    elif action == "category_rename":
        if input_value["category_id"] not in categories:
            _fail(input_path + ".category_id", "dangling category reference")
    elif action == "transaction_note_update":
        if input_value["transaction_id"] not in transactions:
            _fail(input_path + ".transaction_id", "dangling transaction reference")
    elif action == "preview_target_balance":
        account = accounts.get(input_value["account_id"])
        if account is None:
            _fail(input_path + ".account_id", "dangling account reference")
        if account["currency"] != input_value["currency"]:
            _fail(input_path + ".currency", "must match the observed account")
        _amount(
            input_value["target_amount"],
            input_value["currency"],
            input_path + ".target_amount",
            precisions,
        )
        _timestamp(
            input_value["target_observed_at"],
            input_path + ".target_observed_at",
            timezone,
        )
    elif action == "confirm_balance_adjustment":
        candidate = candidates.get(input_value["candidate_id"])
        if candidate is None:
            _fail(input_path + ".candidate_id", "dangling candidate reference")
        account = accounts.get(input_value["account_id"])
        if account is None:
            _fail(input_path + ".account_id", "dangling account reference")
        if (
            account["currency"] != input_value["currency"]
            or candidate["payload"]["currency"] != input_value["currency"]
        ):
            _fail(input_path + ".currency", "must match the account and candidate currency")
        for field in ("target_amount", "replayed_amount", "delta"):
            _amount(
                input_value[field],
                input_value["currency"],
                input_path + f".{field}",
                precisions,
            )
        for field in ("effective_at", "confirmed_at"):
            _timestamp(input_value[field], input_path + f".{field}", timezone)
    elif action == "confirm_real_transfer":
        for field in ("target_account_id", "counter_account_id"):
            if input_value[field] not in accounts:
                _fail(input_path + f".{field}", "dangling account reference")
            if accounts[input_value[field]]["currency"] != input_value["currency"]:
                _fail(input_path + ".currency", "must match both transfer accounts")
        _amount(input_value["amount"], input_value["currency"], input_path + ".amount", precisions)
        for field in ("actual_occurred_at", "discovered_at", "confirmed_at"):
            _timestamp(input_value[field], input_path + f".{field}", timezone)
    elif action == "confirm_explanation_allocation":
        adjustment = entities.get(input_value["adjustment_id"])
        if adjustment is None or adjustment["type"] != "balance_adjustment":
            _fail(input_path + ".adjustment_id", "dangling or mistyped adjustment reference")
        transaction = transactions.get(input_value["transaction_id"])
        if transaction is None or transaction["type"] != "account_transfer":
            _fail(input_path + ".transaction_id", "dangling or mistyped transfer reference")
        if input_value["target_account_id"] not in accounts:
            _fail(input_path + ".target_account_id", "dangling account reference")
        if accounts[input_value["target_account_id"]]["currency"] != input_value["currency"]:
            _fail(input_path + ".currency", "must match the target account")
        for field in ("real_transaction_amount", "explanation_amount"):
            _amount(
                input_value[field],
                input_value["currency"],
                input_path + f".{field}",
                precisions,
            )
        for field in ("actual_occurred_at", "target_observed_at", "confirmed_at"):
            _timestamp(input_value[field], input_path + f".{field}", timezone)
    elif action in _RG10_STRUCTURAL_ACTIONS:
        _validate_rg10_structural_input(
            input_value,
            input_path,
            baseline,
            precisions,
            timezone,
            attempted=False,
        )


def _validate_history_prefix(
    before: dict[str, Any],
    after: dict[str, Any],
    path: str,
    history_field: str,
) -> None:
    before_without_history = {key: value for key, value in before.items() if key != history_field}
    after_without_history = {key: value for key, value in after.items() if key != history_field}
    if not _contract_equivalent(before_without_history, after_without_history):
        _fail(path, "non-history fields are immutable")
    before_history = before[history_field]
    after_history = after[history_field]
    if before_history == after_history:
        return
    if len(after_history) <= len(before_history) or after_history[: len(before_history)] != before_history:
        _fail(path + f".{history_field}", "existing history must remain an exact prefix and only append")


def _validate_reconstruction_history_prefix(
    before: dict[str, Any],
    after: dict[str, Any],
    path: str,
) -> None:
    immutable_fields = {
        key: value
        for key, value in before.items()
        if key not in {"active_mode", "history"}
    }
    after_immutable_fields = {
        key: value
        for key, value in after.items()
        if key not in {"active_mode", "history"}
    }
    if not _contract_equivalent(immutable_fields, after_immutable_fields):
        _fail(path, "reconstruction endpoints are immutable")
    before_history = before["history"]
    after_history = after["history"]
    if after_history[: len(before_history)] != before_history:
        _fail(
            path + ".history",
            "existing history must remain an exact prefix and only append",
        )
    if len(after_history) == len(before_history):
        if after["active_mode"] != before["active_mode"]:
            _fail(path + ".active_mode", "mode changes require an appended history event")
        return
    if len(after_history) < len(before_history):
        _fail(
            path + ".history",
            "existing history must remain an exact prefix and only append",
        )
    if after["active_mode"] != after_history[-1]["active_mode"]:
        _fail(path + ".active_mode", "must match the appended history event")


def _validate_append_only_transition(
    baseline: dict[str, Any],
    result: dict[str, Any],
    operation_path: str,
) -> None:
    immutable_collections = {
        "transaction_versions",
        "posting_sets",
        "postings",
        "sources",
        "confirmations",
        "evidence",
        "evidence_links",
        "relations",
        "audit_links",
        "posting_reconciliations",
    }
    for collection_name, parts in _ENTITY_COLLECTIONS.items():
        before = {item["id"]: item for item in _collection(baseline, parts)}
        after = {item["id"]: item for item in _collection(result, parts)}
        removed = sorted(set(before) - set(after))
        if removed:
            _fail(
                operation_path + f".append_only.{collection_name}",
                f"append-only state forbids removals: {removed}",
            )
        for item_id in set(before) & set(after):
            item_path = operation_path + f".append_only.{collection_name}[{item_id}]"
            if collection_name in immutable_collections:
                if not _contract_equivalent(before[item_id], after[item_id]):
                    _fail(item_path, f"existing {collection_name} entities are immutable")
            elif collection_name == "candidates":
                _validate_history_prefix(
                    before[item_id], after[item_id], item_path, "status_history"
                )
            elif collection_name == "domain_entities":
                old_payload = before[item_id].get("payload", {})
                new_payload = after[item_id].get("payload", {})
                if (
                    before[item_id].get("type") == "stored_value_reconstruction"
                    and new_payload is not None
                ):
                    old_outer = {
                        key: value
                        for key, value in before[item_id].items()
                        if key != "payload"
                    }
                    new_outer = {
                        key: value
                        for key, value in after[item_id].items()
                        if key != "payload"
                    }
                    if not _contract_equivalent(old_outer, new_outer):
                        _fail(item_path, "domain entity stable identity is immutable")
                    _validate_reconstruction_history_prefix(
                        old_payload, new_payload, item_path + ".payload"
                    )
                elif "status_history" in old_payload and "status_history" in new_payload:
                    old_outer = {key: value for key, value in before[item_id].items() if key != "payload"}
                    new_outer = {key: value for key, value in after[item_id].items() if key != "payload"}
                    if not _contract_equivalent(old_outer, new_outer):
                        _fail(item_path, "domain entity stable identity is immutable")
                    _validate_history_prefix(
                        old_payload, new_payload, item_path + ".payload", "status_history"
                    )
                elif not _contract_equivalent(before[item_id], after[item_id]):
                    _fail(item_path, "existing domain_entities entities are immutable")
            elif collection_name == "transactions":
                old_transaction = before[item_id]
                new_transaction = after[item_id]
                if old_transaction["type"] != new_transaction["type"]:
                    _fail(item_path + ".type", "transaction stable type is immutable")
                if old_transaction["current_version_id"] == new_transaction["current_version_id"]:
                    if not _contract_equivalent(old_transaction, new_transaction):
                        _fail(item_path, "transaction stable identity is immutable")
                    continue
                old_versions = {
                    item["id"]: item
                    for item in baseline["transaction_versions"]
                    if item["transaction_id"] == item_id
                }
                new_versions = {
                    item["id"]: item
                    for item in result["transaction_versions"]
                    if item["transaction_id"] == item_id
                }
                next_version_id = new_transaction["current_version_id"]
                if next_version_id in old_versions or next_version_id not in new_versions:
                    _fail(
                        item_path + ".current_version_id",
                        "must advance to a newly appended version owned by the transaction",
                    )
                old_max = max(item["version_number"] for item in old_versions.values())
                if new_versions[next_version_id]["version_number"] != old_max + 1:
                    _fail(
                        item_path + ".current_version_id",
                        "must advance to the next version number",
                    )
            elif collection_name == "catalog_accounts":
                if not _contract_equivalent(before[item_id], after[item_id]):
                    _fail(item_path, "existing catalog accounts are immutable")
            elif collection_name == "catalog_categories":
                old_category = before[item_id]
                new_category = after[item_id]
                if not _contract_equivalent(
                    {key: value for key, value in old_category.items() if key != "name"},
                    {key: value for key, value in new_category.items() if key != "name"},
                ):
                    _fail(item_path, "category rename may only change the name")

    before_history = baseline["catalog"].get("category_name_history", [])
    after_history = result["catalog"].get("category_name_history", [])
    before_by_key = {
        (item["category_id"], item["version"]): item for item in before_history
    }
    after_by_key = {
        (item["category_id"], item["version"]): item for item in after_history
    }
    if not set(before_by_key).issubset(after_by_key):
        _fail(
            operation_path + ".append_only.category_name_history",
            "category name history records cannot be removed",
        )
    for key, old_record in before_by_key.items():
        new_record = after_by_key[key]
        if old_record["category_id"] != new_record["category_id"] or old_record[
            "name"
        ] != new_record["name"]:
            _fail(
                operation_path + ".append_only.category_name_history",
                "existing category name and version records are immutable",
            )
        if old_record["status"] != new_record["status"] and not (
            old_record["status"] == "current" and new_record["status"] == "superseded"
        ):
            _fail(
                operation_path + ".append_only.category_name_history",
                "history status may only transition from current to superseded",
            )


def _validate_no_change_retry(
    operation: dict[str, Any],
    operation_path: str,
    earlier_operations: list[dict[str, Any]],
) -> None:
    if operation["outcome"]["status"] != "no_change":
        return
    request_id = operation["input"].get("request_id")
    prior = [
        item
        for item in earlier_operations
        if item["outcome"]["status"] == "accepted"
        and item["action_type"] == operation["action_type"]
        and (
            (request_id is not None and item["input"].get("request_id") == request_id)
            or (request_id is None and _contract_equivalent(item["input"], operation["input"]))
        )
    ]
    if not prior:
        identity_path = ".input.request_id" if request_id is not None else ".input"
        _fail(
            operation_path + identity_path,
            "no prior accepted operation matches this action and closed input identity",
        )
    accepted = prior[-1]
    if not _contract_equivalent(operation["input"], accepted["input"]):
        _fail(
            operation_path + ".input",
            "must be contract-equivalent input to the prior accepted request after declared set-like normalization",
        )
    if not operation["returned_ids"]:
        _fail(operation_path + ".returned_ids", "no_change returned_ids must be non-empty")
    if not _contract_equivalent(
        {"returned_ids": operation["returned_ids"]},
        {"returned_ids": accepted["returned_ids"]},
    ):
        _fail(operation_path + ".returned_ids", "must exactly return the prior accepted result IDs")


def _validate_registered_action_effects(
    operation: dict[str, Any],
    operation_path: str,
    result: dict[str, Any],
    expected_entities: dict[str, dict[str, list[str]]],
) -> None:
    action = operation["action_type"]
    accepted = operation["outcome"]["status"] == "accepted"
    if action in _RG10_STRUCTURAL_ACTIONS:
        if operation["outcome"]["status"] != "rejected":
            _fail(
                operation_path + ".action_type",
                "is structurally registered but economic effects are not implemented",
            )
        registered_counts: dict[str, tuple[int, int, int]] | None = {}
    else:
        registered_counts = _ACCEPTED_ACTION_ENTITY_COUNTS.get(action)
    if registered_counts is None:
        _fail(operation_path + ".action_type", "unregistered action type")

    for collection_name, changes in expected_entities.items():
        required = registered_counts.get(collection_name, (0, 0, 0)) if accepted else (0, 0, 0)
        actual = tuple(len(changes[field]) for field in _ENTITY_CHANGE_FIELDS)
        if actual != required:
            _fail(
                operation_path + f".deltas.entity_changes.{collection_name}",
                f"{operation['outcome']['status']} {action} requires exact "
                f"(added, changed, removed) counts {required}, got {actual}",
            )

    if not accepted:
        return

    result_transactions = {item["id"]: item for item in result["transactions"]}
    result_versions = {item["id"]: item for item in result["transaction_versions"]}
    result_sets = {item["id"]: item for item in result["posting_sets"]}
    result_postings = {item["id"]: item for item in result["postings"]}
    result_candidates = {item["id"]: item for item in result["candidates"]}
    result_confirmations = {item["id"]: item for item in result["confirmations"]}
    result_domain_entities = {item["id"]: item for item in result["domain_entities"]}
    result_audit_links = {item["id"]: item for item in result["audit_links"]}
    result_reconciliations = {
        item["id"]: item for item in result["posting_reconciliations"]
    }

    def effect_path(collection_name: str) -> str:
        return operation_path + f".deltas.entity_changes.{collection_name}"

    def added_item(collection_name: str, items: dict[str, dict[str, Any]]) -> dict[str, Any]:
        item_id = expected_entities[collection_name]["added_ids"][0]
        return items[item_id]

    def validate_confirmation(
        expected_type: str,
        subject_kind: str,
        subject_id: str,
        confirmed_at: str | None = None,
    ) -> dict[str, Any]:
        confirmation = added_item("confirmations", result_confirmations)
        if (
            confirmation["type"] != expected_type
            or confirmation["operation_id"] != operation["id"]
            or confirmation["subject"] != {"kind": subject_kind, "id": subject_id}
        ):
            _fail(
                effect_path("confirmations"),
                "added confirmation must have the registered type and belong to this action subject",
            )
        if confirmed_at is not None and confirmation.get("confirmed_at") != confirmed_at:
            _fail(
                effect_path("confirmations"),
                "added confirmation timestamp must match the action input",
            )
        return confirmation

    if action == "category_rename":
        category_id = operation["input"]["category_id"]
        if expected_entities["catalog_categories"]["changed_ids"] != [category_id]:
            _fail(
                effect_path("catalog_categories"),
                "category_rename may only change its target category",
            )
        return

    if action == "preview_target_balance":
        source = added_item(
            "sources", {item["id"]: item for item in result["sources"]}
        )
        candidate = added_item("candidates", result_candidates)
        evidence = added_item(
            "evidence", {item["id"]: item for item in result["evidence"]}
        )
        evidence_link = added_item(
            "evidence_links", {item["id"]: item for item in result["evidence_links"]}
        )
        observation = added_item("domain_entities", result_domain_entities)
        input_value = operation["input"]
        expected_source_payload = {
            "account_id": input_value["account_id"],
            "target_amount": input_value["target_amount"],
            "currency": input_value["currency"],
            "target_observed_at": input_value["target_observed_at"],
        }
        if source["type"] != "explicit_balance_observation" or source["payload"] != expected_source_payload:
            _fail(effect_path("sources"), "preview source must exactly represent the action input")
        if candidate["type"] != "balance_adjustment" or candidate["source_ids"] != [source["id"]]:
            _fail(effect_path("candidates"), "preview candidate must belong to the added source")
        if (
            evidence["type"] != "user_balance_observation"
            or evidence["source_ids"] != [source["id"]]
            or evidence["payload"].get("observed_at") != input_value["target_observed_at"]
        ):
            _fail(effect_path("evidence"), "preview evidence must belong to the added source and observation time")
        expected_observation_payload = {
            "account_id": input_value["account_id"],
            "target_amount": input_value["target_amount"],
            "currency": input_value["currency"],
            "observed_at": input_value["target_observed_at"],
            "source_id": source["id"],
        }
        if (
            observation["type"] != "target_balance_observation"
            or observation["payload"] != expected_observation_payload
        ):
            _fail(
                effect_path("domain_entities"),
                "preview observation must exactly represent the added source and action input",
            )
        if evidence_link != {
            "id": evidence_link["id"],
            "evidence_id": evidence["id"],
            "target_kind": "observation",
            "target_id": observation["id"],
            "role": "target_balance_observation",
        }:
            _fail(
                effect_path("evidence_links"),
                "preview evidence link must join the added evidence to the added observation",
            )
        return

    added_versions = expected_entities["transaction_versions"]["added_ids"]
    if action == "transaction_note_update":
        transaction_id = operation["input"]["transaction_id"]
        if expected_entities["transactions"]["changed_ids"] != [transaction_id]:
            _fail(
                effect_path("transactions"),
                "transaction_note_update may only advance its target transaction",
            )
        version = result_versions[added_versions[0]]
        confirmation = validate_confirmation(
            "explicit_manual_save", "operation", operation["id"]
        )
        if (
            version["transaction_id"] != transaction_id
            or version.get("confirmation_id") != confirmation["id"]
        ):
            _fail(
                effect_path("confirmations"),
                "transaction_note_update confirmation must own the appended target version",
            )
        return

    created_type_by_action = {
        "manual_expense": "expense",
        "manual_income": "income",
        "confirm_balance_adjustment": "balance_adjustment",
        "confirm_real_transfer": "account_transfer",
        "confirm_explanation_allocation": "balance_adjustment_reversal",
    }
    expected_type = created_type_by_action[action]
    added_transactions = expected_entities["transactions"]["added_ids"]
    transaction = result_transactions[added_transactions[0]]
    version = result_versions[added_versions[0]]
    posting_set = added_item("posting_sets", result_sets)
    added_posting_ids = expected_entities["postings"]["added_ids"]
    added_postings = [result_postings[item_id] for item_id in added_posting_ids]
    if transaction["type"] != expected_type:
        _fail(
            effect_path("transactions"),
            f"{action} must create transaction type {expected_type}",
        )
    if (
        version["transaction_id"] != transaction["id"]
        or version["version_number"] != 1
        or transaction["current_version_id"] != version["id"]
    ):
        _fail(
            effect_path("transaction_versions"),
            f"{action} must add the created transaction's current v1 and no unrelated version",
        )
    if (
        version["posting_set_id"] != posting_set["id"]
        or set(posting_set["posting_ids"]) != set(added_posting_ids)
        or any(item["posting_set_id"] != posting_set["id"] for item in added_postings)
    ):
        _fail(
            effect_path("posting_sets"),
            f"{action} posting set must contain exactly the postings added by the action",
        )

    if action in {"manual_expense", "manual_income"}:
        confirmation = validate_confirmation(
            "explicit_manual_save", "operation", operation["id"]
        )
    elif action == "confirm_balance_adjustment":
        candidate_id = operation["input"]["candidate_id"]
        if expected_entities["candidates"]["changed_ids"] != [candidate_id]:
            _fail(
                effect_path("candidates"),
                "confirm_balance_adjustment may only append status to its input candidate",
            )
        candidate = result_candidates[candidate_id]
        if candidate["status_history"][-1]["status"] != "confirmed":
            _fail(effect_path("candidates"), "confirmed candidate history must end in confirmed")
        confirmation = validate_confirmation(
            "candidate_confirmation",
            "candidate",
            candidate_id,
            operation["input"]["confirmed_at"],
        )
        adjustment = added_item("domain_entities", result_domain_entities)
        if (
            adjustment["type"] != "balance_adjustment"
            or adjustment["payload"].get("transaction_id") != transaction["id"]
        ):
            _fail(
                effect_path("domain_entities"),
                "confirm_balance_adjustment must add its transaction-owned adjustment entity",
            )
        audit_link = added_item("audit_links", result_audit_links)
        if (
            audit_link["type"] != "adjustment_transaction"
            or audit_link["from"] != {"kind": "domain_entity", "id": adjustment["id"]}
            or audit_link["to"] != {"kind": "transaction", "id": transaction["id"]}
        ):
            _fail(
                effect_path("audit_links"),
                "adjustment audit link must join the entities created by this action",
            )
    elif action == "confirm_real_transfer":
        confirmation = validate_confirmation(
            "explicit_operation_confirmation",
            "operation",
            operation["id"],
            operation["input"]["confirmed_at"],
        )
    else:
        confirmation = validate_confirmation(
            "explicit_operation_confirmation",
            "operation",
            operation["id"],
            operation["input"]["confirmed_at"],
        )
        allocation = added_item("domain_entities", result_domain_entities)
        if (
            allocation["type"] != "explanation_allocation"
            or allocation["payload"].get("reversal_transaction_id") != transaction["id"]
            or allocation["payload"].get("adjustment_id") != operation["input"]["adjustment_id"]
            or allocation["payload"].get("explanation_transaction_id")
            != operation["input"]["transaction_id"]
        ):
            _fail(
                effect_path("domain_entities"),
                "confirm_explanation_allocation must add its action-owned allocation entity",
            )
        audit_links = [
            result_audit_links[item_id]
            for item_id in expected_entities["audit_links"]["added_ids"]
        ]
        audit_targets = {item["type"]: item for item in audit_links}
        if set(audit_targets) != {"allocation_reversal", "explanation_transaction"} or any(
            item["from"] != {"kind": "domain_entity", "id": allocation["id"]}
            for item in audit_links
        ):
            _fail(
                effect_path("audit_links"),
                "allocation audit links must originate from the allocation created by this action",
            )
        if (
            audit_targets["allocation_reversal"]["to"]
            != {"kind": "transaction", "id": transaction["id"]}
            or audit_targets["explanation_transaction"]["to"]
            != {"kind": "transaction", "id": operation["input"]["transaction_id"]}
        ):
            _fail(
                effect_path("audit_links"),
                "allocation audit links must target this action's reversal and explanation transactions",
            )

    if version.get("confirmation_id") != confirmation["id"]:
        _fail(
            effect_path("confirmations"),
            f"{action} confirmation must own the created transaction version",
        )

    if action == "manual_income" and not _contract_equivalent(
        {"returned_ids": operation["returned_ids"]},
        {"returned_ids": [{"kind": "transaction", "id": transaction["id"]}]},
    ):
        _fail(
            operation_path + ".returned_ids",
            "manual_income must return exactly its created transaction",
        )

    if action in {"manual_expense", "manual_income", "confirm_real_transfer"}:
        reconciliations = [
            result_reconciliations[item_id]
            for item_id in expected_entities["posting_reconciliations"]["added_ids"]
        ]
        eligible_posting_ids = {
            item["id"] for item in added_postings if item["reconciliation_eligible"]
        }
        if (
            {item["posting_id"] for item in reconciliations} != eligible_posting_ids
            or any(item["status"] != "pending" for item in reconciliations)
        ):
            _fail(
                effect_path("posting_reconciliations"),
                f"{action} reconciliations must cover exactly its eligible postings as pending",
            )


def _validate_action_semantics(
    operation: dict[str, Any],
    operation_path: str,
    baseline: dict[str, Any],
    result: dict[str, Any],
    expected_entities: dict[str, dict[str, list[str]]],
) -> None:
    if operation["outcome"]["status"] != "accepted":
        return
    action = operation["action_type"]
    input_value = operation["input"]
    baseline_accounts = {item["id"]: item for item in baseline["catalog"]["accounts"]}
    baseline_categories = {item["id"]: item for item in baseline["catalog"]["categories"]}
    baseline_transactions = {item["id"]: item for item in baseline["transactions"]}
    baseline_candidates = {item["id"]: item for item in baseline["candidates"]}
    baseline_entities = {item["id"]: item for item in baseline["domain_entities"]}
    result_transactions = {item["id"]: item for item in result["transactions"]}
    result_versions = {item["id"]: item for item in result["transaction_versions"]}
    result_sets = {item["id"]: item for item in result["posting_sets"]}
    result_postings = {item["id"]: item for item in result["postings"]}

    def transaction_parts(transaction_id: str) -> tuple[dict[str, Any], list[dict[str, Any]]]:
        transaction = result_transactions[transaction_id]
        version = result_versions[transaction["current_version_id"]]
        postings = [
            result_postings[posting_id]
            for posting_id in result_sets[version["posting_set_id"]]["posting_ids"]
        ]
        return version, postings

    if action == "manual_expense":
        category = baseline_categories.get(input_value["category_id"])
        if category is None:
            _fail(operation_path + ".input.category_id", "dangling category reference")
        if not category["active"] or category["posting_account_id"] is None:
            _fail(operation_path + ".input.category_id", "must reference an active posting category")
        if input_value["payment_account_id"] not in baseline_accounts:
            _fail(operation_path + ".input.payment_account_id", "dangling account reference")
        if Decimal(input_value["amount"]) <= 0:
            _fail(operation_path + ".input.amount", "must be positive")
        added = expected_entities["transactions"]["added_ids"]
        if len(added) != 1:
            _fail(operation_path + ".deltas.entity_changes.transactions", "manual_expense must add one transaction")
        transaction = result_transactions[added[0]]
        if transaction["type"] != "expense":
            _fail(operation_path + ".result_state_id", "manual_expense must create an expense")
        version, postings = transaction_parts(transaction["id"])
        if any(version[field] != input_value["occurred_at"] for field in ("occurred_at", "statistics_at", "effective_at")):
            _fail(operation_path + ".input.occurred_at", "must be preserved in all economic time roles")
        if version.get("note") != input_value["note"]:
            _fail(operation_path + ".input.note", "does not match the created version")
        expected = {
            ("expense", category["posting_account_id"]),
            ("payment_asset", input_value["payment_account_id"]),
        }
        if {(item.get("role"), item["account_id"]) for item in postings} != expected:
            _fail(operation_path + ".result_state_id", "manual_expense postings do not match the input")
        amounts = {item["role"]: Decimal(item["amount"]) for item in postings}
        if amounts["expense"] != Decimal(input_value["amount"]) or amounts["payment_asset"] != -Decimal(input_value["amount"]):
            _fail(operation_path + ".input.amount", "does not match the created postings")
    elif action == "manual_income":
        category = baseline_categories.get(input_value["category_id"])
        if category is None:
            _fail(operation_path + ".input.category_id", "dangling category reference")
        receiving_account = baseline_accounts.get(input_value["receiving_account_id"])
        if receiving_account is None:
            _fail(operation_path + ".input.receiving_account_id", "dangling account reference")
        posting_account = baseline_accounts.get(category["posting_account_id"])
        if not (
            category["active"]
            and category["parent_id"] is not None
            and posting_account is not None
            and posting_account["kind"] == "income"
        ):
            _fail(
                operation_path + ".input.category_id",
                "must reference an active second-level income category",
            )
        if not (
            receiving_account["kind"] == "asset"
            and receiving_account["owned_by_user"]
            and receiving_account["real_account"]
            and receiving_account["reconciliation_eligible"]
        ):
            _fail(
                operation_path + ".input.receiving_account_id",
                "must reference an eligible owned real receiving asset",
            )
        added = expected_entities["transactions"]["added_ids"]
        transaction = result_transactions[added[0]]
        if transaction["type"] != "income":
            _fail(operation_path + ".result_state_id", "manual_income must create income")
        version, postings = transaction_parts(transaction["id"])
        if any(
            version[field] != input_value["occurred_at"]
            for field in ("occurred_at", "statistics_at", "effective_at")
        ):
            _fail(
                operation_path + ".input.occurred_at",
                "must be preserved in all economic time roles",
            )
        if version.get("note", "") != input_value.get("note", ""):
            _fail(operation_path + ".input.note", "does not match the created version")
        expected = {
            ("income_classification", category["posting_account_id"]),
            ("receiving_asset", input_value["receiving_account_id"]),
        }
        if {(item.get("role"), item["account_id"]) for item in postings} != expected:
            _fail(
                operation_path + ".result_state_id",
                "manual_income postings do not match exact category and receiving roles",
            )
        amounts = {item["role"]: Decimal(item["amount"]) for item in postings}
        amount = Decimal(input_value["amount"])
        if amounts["income_classification"] != -amount or amounts[
            "receiving_asset"
        ] != amount:
            _fail(
                operation_path + ".input.amount",
                "does not match the exact income posting signs",
            )
    elif action == "category_rename":
        category_id = input_value["category_id"]
        before = baseline_categories.get(category_id)
        after_categories = {
            item["id"]: item for item in result["catalog"]["categories"]
        }
        if before is None:
            _fail(operation_path + ".input.category_id", "dangling category reference")
        after = after_categories[category_id]
        if after["name"] != input_value["new_name"]:
            _fail(
                operation_path + ".input.new_name",
                "must match the renamed category current name",
            )
        if not _contract_equivalent(
            {key: value for key, value in before.items() if key != "name"},
            {key: value for key, value in after.items() if key != "name"},
        ):
            _fail(
                operation_path + ".result_state_id",
                "category rename must preserve every non-name association",
            )
        before_history = [
            item
            for item in baseline["catalog"].get("category_name_history", [])
            if item["category_id"] == category_id
        ]
        after_history = [
            item
            for item in result["catalog"].get("category_name_history", [])
            if item["category_id"] == category_id
        ]
        before_other_history = [
            item
            for item in baseline["catalog"].get("category_name_history", [])
            if item["category_id"] != category_id
        ]
        after_other_history = [
            item
            for item in result["catalog"].get("category_name_history", [])
            if item["category_id"] != category_id
        ]
        if not _contract_equivalent(before_other_history, after_other_history):
            _fail(
                operation_path + ".append_only.category_name_history",
                "category_rename may only change history for its target category",
            )
        if len(after_history) != len(before_history) + 1:
            _fail(
                operation_path + ".result_state_id",
                "category rename must append exactly one name-history version",
            )
        newest = max(after_history, key=lambda item: item["version"])
        if newest != {
            "category_id": category_id,
            "name": input_value["new_name"],
            "version": len(after_history),
            "status": "current",
        }:
            _fail(
                operation_path + ".result_state_id",
                "category rename must append the exact consecutive current name record",
            )
    elif action == "transaction_note_update":
        transaction_id = input_value["transaction_id"]
        before = baseline_transactions.get(transaction_id)
        if before is None:
            _fail(operation_path + ".input.transaction_id", "dangling transaction reference")
        after = result_transactions[transaction_id]
        added_versions = expected_entities["transaction_versions"]["added_ids"]
        if len(added_versions) != 1:
            _fail(
                operation_path + ".deltas.entity_changes.transaction_versions",
                "transaction_note_update must append exactly one transaction_version",
            )
        if after["current_version_id"] != added_versions[0]:
            _fail(
                operation_path + ".result_state_id",
                "transaction current_version_id must point to the appended transaction_version",
            )
        old_version = next(item for item in baseline["transaction_versions"] if item["id"] == before["current_version_id"])
        new_version = result_versions[after["current_version_id"]]
        if (
            new_version["transaction_id"] != transaction_id
            or new_version["version_number"] != old_version["version_number"] + 1
        ):
            _fail(
                operation_path + ".result_state_id",
                "appended transaction_version must be the next version owned by the transaction",
            )
        if new_version["posting_set_id"] != old_version["posting_set_id"]:
            _fail(operation_path + ".result_state_id", "metadata-only update must reuse the posting set")
        if new_version.get("note") != input_value["note"]:
            _fail(operation_path + ".input.note", "does not match the new version")
    elif action == "preview_target_balance":
        added_candidates = expected_entities["candidates"]["added_ids"]
        if len(added_candidates) != 1:
            _fail(operation_path + ".deltas.entity_changes.candidates", "preview must add one candidate")
        candidate = next(item for item in result["candidates"] if item["id"] == added_candidates[0])
        payload = candidate["payload"]
        for input_key, payload_key in (
            ("account_id", "account_id"),
            ("target_amount", "target_amount"),
            ("currency", "currency"),
            ("target_observed_at", "effective_at"),
        ):
            if input_value[input_key] != payload[payload_key]:
                _fail(operation_path + f".input.{input_key}", "does not match the preview candidate")
    elif action == "confirm_balance_adjustment":
        candidate = baseline_candidates.get(input_value["candidate_id"])
        if candidate is None:
            _fail(operation_path + ".input.candidate_id", "dangling candidate reference")
        candidate_payload = candidate["payload"]
        for input_key, payload_key in (
            ("account_id", "account_id"),
            ("target_amount", "target_amount"),
            ("replayed_amount", "replayed_amount"),
            ("delta", "delta"),
            ("currency", "currency"),
            ("effective_at", "effective_at"),
        ):
            if input_value[input_key] != candidate_payload[payload_key]:
                _fail(operation_path + f".input.{input_key}", "does not match the confirmed candidate")
        if Decimal(input_value["target_amount"]) - Decimal(input_value["replayed_amount"]) != Decimal(input_value["delta"]):
            _fail(operation_path + ".input.delta", "must equal target_amount - replayed_amount")
        added = expected_entities["transactions"]["added_ids"]
        if len(added) != 1 or result_transactions[added[0]]["type"] != "balance_adjustment":
            _fail(operation_path + ".result_state_id", "must add one balance_adjustment transaction")
        version, postings = transaction_parts(added[0])
        if any(version[field] != input_value["effective_at"] for field in ("occurred_at", "statistics_at", "effective_at")):
            _fail(operation_path + ".input.effective_at", "must be preserved in all adjustment time roles")
        if version.get("created_at") != input_value["confirmed_at"]:
            _fail(operation_path + ".input.confirmed_at", "must be preserved as adjustment creation time")
        by_role = {posting.get("role"): posting for posting in postings}
        if set(by_role) != {"balance_adjustment_target", "balance_adjustment_counterpart"}:
            _fail(operation_path + ".result_state_id", "adjustment must contain target and counterpart roles")
        target_posting = by_role["balance_adjustment_target"]
        counterpart = by_role["balance_adjustment_counterpart"]
        if target_posting["account_id"] != input_value["account_id"] or target_posting["amount"] != input_value["delta"]:
            _fail(operation_path + ".input.delta", "does not match the adjustment target posting")
        counterpart_account = next(
            (item for item in result["catalog"]["accounts"] if item["id"] == counterpart["account_id"]),
            None,
        )
        if counterpart_account is None or counterpart_account.get("system_role") != "balance_adjustments":
            _fail(operation_path + ".result_state_id", "adjustment counterpart must use the dedicated system account")
        if Decimal(counterpart["amount"]) != -Decimal(input_value["delta"]):
            _fail(operation_path + ".input.delta", "does not match the adjustment counterpart posting")
        added_adjustments = [
            item
            for item in result["domain_entities"]
            if item["id"] in expected_entities["domain_entities"]["added_ids"]
            and item["type"] == "balance_adjustment"
        ]
        if len(added_adjustments) != 1:
            _fail(operation_path + ".result_state_id", "must add one balance adjustment domain entity")
        adjustment_payload = added_adjustments[0]["payload"]
        if adjustment_payload["transaction_id"] != added[0] or adjustment_payload["original_delta"] != input_value["delta"]:
            _fail(operation_path + ".result_state_id", "adjustment domain entity does not match the formal transaction")
    elif action == "confirm_real_transfer":
        for field in ("target_account_id", "counter_account_id"):
            if input_value[field] not in baseline_accounts:
                _fail(operation_path + f".input.{field}", "dangling account reference")
        if input_value["target_account_id"] == input_value["counter_account_id"]:
            _fail(operation_path + ".input.counter_account_id", "must differ from target_account_id")
        if Decimal(input_value["amount"]) <= 0:
            _fail(operation_path + ".input.amount", "must be positive")
        added = expected_entities["transactions"]["added_ids"]
        if len(added) != 1 or result_transactions[added[0]]["type"] != "account_transfer":
            _fail(operation_path + ".result_state_id", "must add one account_transfer transaction")
        transaction = result_transactions[added[0]]
        version, postings = transaction_parts(transaction["id"])
        if any(version[field] != input_value["actual_occurred_at"] for field in ("occurred_at", "statistics_at", "effective_at")):
            _fail(operation_path + ".input.actual_occurred_at", "must be preserved as the transfer time")
        if version.get("created_at") != input_value["confirmed_at"]:
            _fail(operation_path + ".input.confirmed_at", "must be preserved as transfer creation time")
        by_role = {posting.get("role"): posting for posting in postings}
        if set(by_role) != {"transfer_principal_in", "transfer_principal_out"}:
            _fail(operation_path + ".result_state_id", "transfer must contain exact principal roles")
        incoming = by_role["transfer_principal_in"]
        outgoing = by_role["transfer_principal_out"]
        if incoming["account_id"] != input_value["target_account_id"] or incoming["amount"] != input_value["amount"]:
            _fail(operation_path + ".input.amount", "does not match the transfer-in posting")
        if outgoing["account_id"] != input_value["counter_account_id"] or Decimal(outgoing["amount"]) != -Decimal(input_value["amount"]):
            _fail(operation_path + ".input.amount", "does not match the transfer-out posting")
    elif action == "confirm_explanation_allocation":
        adjustment = baseline_entities.get(input_value["adjustment_id"])
        if adjustment is None or adjustment["type"] != "balance_adjustment":
            _fail(operation_path + ".input.adjustment_id", "dangling or mistyped adjustment reference")
        explanation = baseline_transactions.get(input_value["transaction_id"])
        if explanation is None or explanation["type"] != "account_transfer":
            _fail(operation_path + ".input.transaction_id", "dangling or mistyped transfer reference")
        observation = baseline_entities[adjustment["payload"]["observation_id"]]
        observation_payload = observation["payload"]
        for input_key, expected_value in (
            ("target_account_id", observation_payload["account_id"]),
            ("currency", observation_payload["currency"]),
            ("target_observed_at", observation_payload["observed_at"]),
        ):
            if input_value[input_key] != expected_value:
                _fail(operation_path + f".input.{input_key}", "does not match the target observation")
        baseline_versions = {item["id"]: item for item in baseline["transaction_versions"]}
        baseline_sets = {item["id"]: item for item in baseline["posting_sets"]}
        baseline_postings = {item["id"]: item for item in baseline["postings"]}
        explanation_version = baseline_versions[explanation["current_version_id"]]
        if explanation_version["occurred_at"] != input_value["actual_occurred_at"]:
            _fail(operation_path + ".input.actual_occurred_at", "does not match the explanation transaction")
        explanation_postings = [
            baseline_postings[item]
            for item in baseline_sets[explanation_version["posting_set_id"]]["posting_ids"]
        ]
        target_legs = [
            item
            for item in explanation_postings
            if item["account_id"] == input_value["target_account_id"]
            and item.get("role") == "transfer_principal_in"
        ]
        if len(target_legs) != 1 or target_legs[0]["amount"] != input_value["real_transaction_amount"]:
            _fail(operation_path + ".input.real_transaction_amount", "does not match the transfer target posting")
        if Decimal(input_value["explanation_amount"]) <= 0 or Decimal(input_value["explanation_amount"]) > Decimal(input_value["real_transaction_amount"]):
            _fail(operation_path + ".input.explanation_amount", "must be positive and no greater than the real transaction amount")
        added_transactions = expected_entities["transactions"]["added_ids"]
        added_entities = expected_entities["domain_entities"]["added_ids"]
        if len(added_transactions) != 1 or result_transactions[added_transactions[0]]["type"] != "balance_adjustment_reversal":
            _fail(operation_path + ".result_state_id", "must add one adjustment reversal")
        allocations = [
            item for item in result["domain_entities"] if item["id"] in added_entities and item["type"] == "explanation_allocation"
        ]
        if len(allocations) != 1:
            _fail(operation_path + ".result_state_id", "must add one explanation allocation")
        allocation = allocations[0]["payload"]
        if (
            allocation["adjustment_id"] != input_value["adjustment_id"]
            or allocation["explanation_transaction_id"] != input_value["transaction_id"]
            or allocation["amount"] != input_value["explanation_amount"]
            or allocation["currency"] != input_value["currency"]
            or allocation["confirmed_at"] != input_value["confirmed_at"]
        ):
            _fail(operation_path + ".input.explanation_amount", "does not match the allocation")
        reversal_version, reversal_postings = transaction_parts(added_transactions[0])
        if any(reversal_version[field] != input_value["target_observed_at"] for field in ("occurred_at", "statistics_at", "effective_at")):
            _fail(operation_path + ".input.target_observed_at", "must be preserved in reversal time roles")
        if reversal_version.get("created_at") != input_value["confirmed_at"]:
            _fail(operation_path + ".input.confirmed_at", "must be preserved as reversal creation time")
        by_role = {posting.get("role"): posting for posting in reversal_postings}
        if set(by_role) != {"balance_adjustment_reversal_target", "balance_adjustment_reversal_counterpart"}:
            _fail(operation_path + ".result_state_id", "reversal must contain exact target and counterpart roles")
        reversal_target = by_role["balance_adjustment_reversal_target"]
        reversal_counterpart = by_role["balance_adjustment_reversal_counterpart"]
        if reversal_target["account_id"] != input_value["target_account_id"] or Decimal(reversal_target["amount"]) != -Decimal(input_value["explanation_amount"]):
            _fail(operation_path + ".input.explanation_amount", "does not match the reversal target posting")
        if Decimal(reversal_counterpart["amount"]) != Decimal(input_value["explanation_amount"]):
            _fail(operation_path + ".input.explanation_amount", "does not match the reversal counterpart posting")


def _validate_operations(
    case: dict[str, Any],
    states: dict[str, dict[str, Any]],
    operations: dict[str, dict[str, Any]],
    state_indexes: dict[str, dict[str, dict[str, dict[str, Any]]]],
    precisions: dict[str, int],
    timezone: ZoneInfo,
) -> None:
    roots = {root["id"]: root for root in case["roots"]}
    expected_order = sorted(case["operations"], key=lambda item: (item["root_id"], item["sequence"]))
    if [item["id"] for item in case["operations"]] != [item["id"] for item in expected_order]:
        _fail("$.operations", "must be ordered by root_id and sequence")

    for root_index, root in enumerate(case["roots"]):
        root_path = f"$.roots[{root_index}]"
        initial = states.get(root["initial_state_id"])
        if initial is None or initial["root_id"] != root["id"]:
            _fail(root_path + ".initial_state_id", "must reference a same-root state")
        if initial["as_of_operation_id"] is not None:
            _fail(root_path + ".initial_state_id", "initial state must have null as_of_operation_id")
        root_operations = sorted(
            [item for item in case["operations"] if item["root_id"] == root["id"]],
            key=lambda item: item["sequence"],
        )
        if [item["sequence"] for item in root_operations] != list(range(1, len(root_operations) + 1)):
            _fail(root_path + ".operation_ids", "operation sequence must be contiguous from 1")
        if root["operation_ids"] != [item["id"] for item in root_operations]:
            _fail(root_path + ".operation_ids", "must match operation sequence exactly")
        previous_state_id = root["initial_state_id"]
        for operation in root_operations:
            operation_index = case["operations"].index(operation)
            operation_path = f"$.operations[{operation_index}]"
            if operation["baseline_state_id"] != previous_state_id:
                _fail(operation_path + ".baseline_state_id", "must follow the root execution path")
            baseline = states.get(operation["baseline_state_id"])
            result = states.get(operation["result_state_id"])
            if baseline is None or baseline["root_id"] != root["id"]:
                _fail(operation_path + ".baseline_state_id", "must reference a same-root state")
            if result is None or result["root_id"] != root["id"]:
                _fail(operation_path + ".result_state_id", "must reference a same-root state")
            if result["as_of_operation_id"] != operation["id"]:
                _fail(operation_path + ".result_state_id", "result state as_of_operation_id must match")

            if (
                operation["action_type"] in _RG10_STRUCTURAL_ACTIONS
                and operation["outcome"]["status"] != "rejected"
            ):
                _fail(
                    operation_path + ".action_type",
                    "is structurally registered but economic effects are not implemented",
                )

            _validate_action_input(
                operation, operation_path, baseline, precisions, timezone
            )
            expected_entities = _expected_entity_changes(baseline, result)
            _validate_append_only_transition(baseline, result, operation_path)
            if operation["outcome"]["status"] in {"rejected", "no_change"} and not _contract_equivalent(
                _state_payload(baseline), _state_payload(result)
            ):
                _fail(
                    operation_path,
                    "rejected and no_change baseline/result states must be contract-equivalent after set-like normalization",
                )
            _validate_registered_action_effects(
                operation, operation_path, result, expected_entities
            )
            earlier_operations = [
                item for item in root_operations if item["sequence"] < operation["sequence"]
            ]
            _validate_no_change_retry(operation, operation_path, earlier_operations)

            declared_entities = operation["deltas"]["entity_changes"]
            for collection_name, expected_change in expected_entities.items():
                change_path = operation_path + f".deltas.entity_changes.{collection_name}"
                declared_change = _declared_id_changes(declared_entities[collection_name], change_path)
                if declared_change != expected_change:
                    _fail(change_path, f"must exactly recompute to {expected_change}, got {declared_change}")
                if expected_change["removed_ids"]:
                    _fail(change_path + ".removed_ids", "append-only state forbids removals")

            expected_balances = _changes(_balance_map(baseline), _balance_map(result))
            expected_reports = _changes(_report_map(baseline), _report_map(result))
            expected_statuses = _changes(_status_map(baseline), _status_map(result))
            declared_values = operation["deltas"]["value_changes"]
            declared_balances = _declared_balance_changes(
                declared_values["balances"], operation_path + ".deltas.value_changes.balances"
            )
            declared_reports = _declared_report_changes(
                declared_values["reports"], operation_path + ".deltas.value_changes.reports"
            )
            declared_statuses = _declared_status_changes(
                declared_values["derived_statuses"],
                operation_path + ".deltas.value_changes.derived_statuses",
            )
            if declared_balances != expected_balances:
                _fail(operation_path + ".deltas.value_changes.balances", "does not exactly match complete states")
            if declared_reports != expected_reports:
                _fail(operation_path + ".deltas.value_changes.reports", "does not exactly match complete states")
            if declared_statuses != expected_statuses:
                _fail(operation_path + ".deltas.value_changes.derived_statuses", "does not exactly match complete states")

            status_changes: dict[Any, tuple[Any, Any]] = {}
            for index, change in enumerate(operation["status_changes"]):
                key = (change["target_kind"], change["target_id"], change["status_name"])
                if key in status_changes:
                    _fail(f"{operation_path}.status_changes[{index}]", "duplicate status change key")
                status_changes[key] = (change["before"], change["after"])
            if status_changes != expected_statuses or status_changes != declared_statuses:
                _fail(operation_path + ".status_changes", "must be isomorphic with derived status value changes")

            if operation["outcome"]["status"] == "accepted" and not any(
                change[change_type]
                for change in expected_entities.values()
                for change_type in ("added_ids", "changed_ids", "removed_ids")
            ) and not (expected_balances or expected_reports or expected_statuses):
                _fail(
                    operation_path + ".outcome",
                    "accepted operation must declare a state or intake effect; use no_change for a valid replay",
                )

            if (
                operation["outcome"]["status"] == "rejected"
                and operation["returned_ids"]
            ):
                _fail(
                    operation_path + ".returned_ids",
                    "rejected operation must return no IDs",
                )

            result_indexes = state_indexes[result["id"]]
            _validate_returned_ids(operation, operation_path, result, result_indexes, operations)
            for confirmation_id in expected_entities["confirmations"]["added_ids"]:
                confirmation = result_indexes["confirmations"][confirmation_id]
                if confirmation["operation_id"] != operation["id"]:
                    result_state_index = case["states"].index(result)
                    confirmation_index = result["confirmations"].index(confirmation)
                    _fail(
                        f"$.states[{result_state_index}].confirmations[{confirmation_index}].operation_id",
                        "new confirmation must name its creating operation",
                    )
            _validate_action_semantics(
                operation, operation_path, baseline, result, expected_entities
            )
            previous_state_id = operation["result_state_id"]

    expected_state_ids = {root["initial_state_id"] for root in case["roots"]} | {
        operation["result_state_id"] for operation in case["operations"]
    }
    if set(states) != expected_state_ids:
        _fail("$.states", "must contain exactly root initial and operation result states")
    for operation in case["operations"]:
        if operation["root_id"] not in roots:
            _fail("$.operations", "operation references an unknown root")


def validate_golden_case_v2(
    case: dict[str, Any],
    *,
    schema_path: str | Path | None = None,
) -> None:
    _validate_schema(case, schema_path)

    supported_transaction_types = {
        "RG-01": {"opening_balance", "expense"},
        "RG-02": {"opening_balance", "income"},
        "RG-09": {
            "opening_balance",
            "account_transfer",
            "balance_adjustment",
            "balance_adjustment_reversal",
        },
    }
    case_id = case["case"]["id"]
    if case_id not in supported_transaction_types:
        _fail(
            "$.case.id",
            "semantic prototype supports only RG-01, RG-02, and RG-09 representative cases",
        )

    precisions: dict[str, int] = {}
    for index, declaration in enumerate(case["case"]["currencies"]):
        code = declaration["code"]
        if code in precisions:
            _fail(f"$.case.currencies[{index}].code", f"duplicate currency {code!r}")
        precisions[code] = declaration["precision"]
    try:
        timezone = ZoneInfo(case["case"]["timezone"])
    except ZoneInfoNotFoundError:
        _fail("$.case.timezone", "must name an available IANA timezone")

    roots = _unique_index(case["roots"], "$.roots")
    states = _unique_index(case["states"], "$.states")
    operations = _unique_index(case["operations"], "$.operations")
    state_indexes: dict[str, dict[str, dict[str, dict[str, Any]]]] = {}

    for state_index, state in enumerate(case["states"]):
        state_path = f"$.states[{state_index}]"
        if state["root_id"] not in roots:
            _fail(state_path + ".root_id", "references an unknown root")
        if state["as_of_operation_id"] is not None:
            operation = operations.get(state["as_of_operation_id"])
            if operation is None or operation["root_id"] != state["root_id"]:
                _fail(state_path + ".as_of_operation_id", "dangling or cross-root operation reference")
        indexes = _state_indexes(state, state_path)
        state_indexes[state["id"]] = indexes
        if case_id == "RG-02" and "category_name_history" not in state["catalog"]:
            _fail(
                state_path + ".catalog.category_name_history",
                "RG-02 complete states require category name history",
            )
        for transaction_index, transaction in enumerate(state["transactions"]):
            if transaction["type"] not in supported_transaction_types[case_id]:
                _fail(
                    f"{state_path}.transactions[{transaction_index}].type",
                    f"is registered structurally but not implemented for the {case_id} semantic prototype",
                )
        _validate_catalog(state, state_path, indexes, precisions)
        replay, current = _validate_formal_ledger(
            state, state_path, indexes, precisions, timezone
        )
        _validate_balances(state, state_path, indexes, replay, precisions)
        _validate_references(
            state, state_path, indexes, operations, precisions, timezone
        )
        reconciliation_by_posting = _validate_reconciliations(state, state_path, indexes)
        _validate_reports(
            case_id, state, state_path, indexes, current, precisions
        )
        _validate_derived_statuses(
            state,
            state_path,
            indexes,
            operations,
            current,
            reconciliation_by_posting,
        )

    _validate_operations(
        case, states, operations, state_indexes, precisions, timezone
    )

    # D-065 projection and diagnostic shapes are frozen, but fingerprint action surfaces
    # remain outside this prototype until fixture and operation gates open together.
