from __future__ import annotations

from collections import defaultdict
from copy import deepcopy
import calendar
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
_RG07_ACTIONS = {
    "record_refund_request_status",
    "ingest_refund_status_source",
    "confirm_manual_refund_receipt",
    "attach_original_payment_evidence",
    "attach_refund_destination_evidence",
    "attach_refund_dual_role_evidence",
    "confirm_refund_receipt",
    "allocate_refund_receipt",
    "ingest_refund_credit_source",
    "confirm_imported_refund",
    "merge_refund_mirror_evidence",
    "validate_refund_receipt",
}
_RG09_ACCEPTED_ACTIONS = {
    "save_zero_delta_observation",
    "receive_import_candidate",
    "confirm_imported_real_transfer",
    "confirm_imported_explanation_allocation",
    "link_real_posting_evidence",
    "confirm_second_real_transfer",
    "confirm_second_explanation_allocation",
}
_RG09_REJECTED_ACTIONS = {
    "reject_invalid_rg09_input",
    "reject_incomplete_import_confirmation",
    "reject_stale_preview",
}
_RG09_TRANSACTION_ACTIONS = {
    "confirm_imported_real_transfer",
    "confirm_imported_explanation_allocation",
    "confirm_second_real_transfer",
    "confirm_second_explanation_allocation",
}
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
        "component_sum_mismatch": {"credited_amount"},
        "stored_value_account_not_enabled": {"stored_value_account_id"},
        "stored_value_models_must_not_overlap": {"model"},
        "unknown_payment_account": {"payment_account_id"},
        "owned_payment_asset_required": {"payment_account_id"},
        "enabled_restricted_stored_value_asset_required": {"stored_value_account_id"},
        "same_cny_currency_required": {"currency"},
    },
    "confirm_stored_value_spend": {
        "insufficient_effective_stored_balance": {"amount"},
        "paid_bonus_composition_must_be_evidenced": {"paid_bonus_composition"},
        "active_secondary_category_required": {"category_id"},
        "enabled_restricted_stored_value_asset_required": {"stored_value_account_id"},
    },
    "confirm_imported_stored_value_recharge": {
        "bank_payment_model_and_all_recharge_facts_required": {"explicit_confirmation"},
    },
    "confirm_imported_stored_value_spend": {
        "spend_category_and_behavior_confirmation_required": {"explicit_confirmation"},
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
_RG08_ACTIONS = {
    "validate_lending_event",
    "validate_lending_settlement",
    "confirm_imported_lending_collection",
    "allocate_lending_collection",
    "retry_idempotent_input",
}
_RG08_REJECTION_FIELDS = {
    "exact_decimal_string_required": "$.attempted_input.total_received",
    "total_must_be_positive": "$.attempted_input.total_received",
    "components_must_equal_total": "$.attempted_input.components",
    "component_must_be_nonnegative": "$.attempted_input.principal_amount",
    "fee_must_be_zero_in_rg08_v1": "$.attempted_input.fee_amount",
    "nonzero_fee_accounting_out_of_scope": "$.attempted_input.fee_amount",
    "principal_exceeds_outstanding_position": "$.attempted_input.principal_amount",
    "unknown_account": None,
    "owned_account_required": "$.attempted_input.destination_account_id",
    "financial_asset_account_required": "$.attempted_input.destination_account_id",
    "unknown_counterparty": "$.attempted_input.counterparty_id",
    "invalid_lending_behavior": "$.attempted_input.behavior_code",
    "explicit_component_split_required": "$.attempted_input.split_source",
    "same_currency_required": "$.attempted_input.currency",
    "active_exact_interest_category_required": "$.attempted_input.interest_category_id",
    "behavior_confirmation_required": "$.attempted_input.behavior_code",
    "counterparty_confirmation_required": "$.attempted_input.counterparty_id",
    "destination_confirmation_required": "$.attempted_input.destination_account_id",
    "principal_confirmation_required": "$.attempted_input.principal_amount",
    "interest_and_fee_confirmation_required": "$.attempted_input.interest_and_fee_amounts",
    "actual_receipt_time_confirmation_required": "$.attempted_input.actual_receipt_time",
}
_RG08_INCOMPLETE_FAILURES = {
    "behavior_code": ("behavior_confirmation_required", "behavior_code"),
    "counterparty_id": ("counterparty_confirmation_required", "counterparty_id"),
    "destination_account_id": ("destination_confirmation_required", "destination_account_id"),
    "principal_amount": ("principal_confirmation_required", "principal_amount"),
    "interest_and_fee_amounts": (
        "interest_and_fee_confirmation_required",
        "interest_and_fee_amounts",
    ),
    "actual_receipt_time": (
        "actual_receipt_time_confirmation_required",
        "actual_receipt_time",
    ),
}
_RG08_EXPECTED_STATUS_COUNTS = {"accepted": 6, "rejected": 25, "no_change": 13}
_RG08_EXPECTED_VARIANT_COUNTS = {
    "lend": 1,
    "rename_counterparty": 1,
    "manual_collection": 1,
    "maximum_allocation": 1,
    "import_intake": 1,
    "formal_confirmation": 1,
    "mirror_merge": 1,
    "retry": 12,
}


def _validate_rg08_inventory(case: dict[str, Any]) -> None:
    status_counts: dict[str, int] = defaultdict(int)
    variant_counts: dict[str, int] = defaultdict(int)
    rejected_action_counts: dict[str, int] = defaultdict(int)
    retry_anchors: list[str] = []
    retry_roots: list[str] = []
    for operation in case["operations"]:
        status = operation["outcome"]["status"]
        status_counts[status] += 1
        if status == "rejected":
            rejected_action_counts[operation["action_type"]] += 1
        else:
            variant = operation["input"]["variant"]
            variant_counts[variant] += 1
            if variant == "retry":
                retry_anchors.append(operation["input"]["input_anchor_id"])
                retry_roots.append(operation["root_id"])
    if dict(status_counts) != _RG08_EXPECTED_STATUS_COUNTS:
        _fail("$.operations", f"RG-08 requires exact status cardinality {_RG08_EXPECTED_STATUS_COUNTS}")
    if dict(variant_counts) != _RG08_EXPECTED_VARIANT_COUNTS:
        _fail("$.operations", f"RG-08 requires exact accepted/no-change variant cardinality {_RG08_EXPECTED_VARIANT_COUNTS}")
    expected_rejected_actions = {
        "validate_lending_event": 1,
        "validate_lending_settlement": 17,
        "confirm_imported_lending_collection": 6,
        "allocate_lending_collection": 1,
    }
    if dict(rejected_action_counts) != expected_rejected_actions:
        _fail("$.operations", f"RG-08 requires exact rejected action cardinality {expected_rejected_actions}")
    if len(set(retry_anchors)) != 12 or len(set(retry_roots)) != 12:
        _fail("$.operations", "RG-08 requires exactly twelve distinct retry anchors in twelve independent roots")
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
    "confirm_second_real_transfer": {
        "transactions": (1, 0, 0),
        "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0),
        "postings": (2, 0, 0),
        "confirmations": (1, 0, 0),
        "sources": (1, 0, 0),
        "posting_reconciliations": (2, 0, 0),
    },
    "confirm_second_explanation_allocation": {
        "transactions": (1, 0, 0),
        "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0),
        "postings": (2, 0, 0),
        "confirmations": (1, 0, 0),
        "domain_entities": (1, 0, 0),
        "audit_links": (3, 0, 0),
    },
    "save_zero_delta_observation": {
        "sources": (1, 0, 0),
        "evidence": (1, 0, 0),
        "evidence_links": (1, 0, 0),
        "domain_entities": (1, 0, 0),
    },
    "receive_import_candidate": {
        "sources": (1, 0, 0),
        "candidates": (1, 0, 0),
        "evidence": (1, 0, 0),
    },
    "confirm_imported_real_transfer": {
        "transactions": (1, 0, 0),
        "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0),
        "postings": (2, 0, 0),
        "confirmations": (1, 0, 0),
    },
    "confirm_imported_explanation_allocation": {
        "transactions": (1, 0, 0),
        "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0),
        "postings": (2, 0, 0),
        "confirmations": (1, 0, 0),
        "candidates": (0, 1, 0),
        "domain_entities": (1, 0, 0),
        "audit_links": (3, 0, 0),
    },
    "link_real_posting_evidence": {
        "sources": (1, 0, 0),
        "evidence": (1, 0, 0),
        "evidence_links": (1, 0, 0),
        "posting_reconciliations": (0, 1, 0),
    },
    "manual_account_transfer": {
        "transactions": (1, 0, 0), "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0), "postings": (3, 0, 0),
        "confirmations": (1, 0, 0), "posting_reconciliations": (2, 0, 0),
    },
    "import_source_record": {"sources": (1, 0, 0), "candidates": (1, 0, 0), "evidence": (1, 0, 0)},
    "confirm_account_transfer_candidate": {
        "transactions": (1, 0, 0), "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0), "postings": (3, 0, 0), "candidates": (0, 1, 0),
        "confirmations": (1, 0, 0), "evidence_links": (1, 0, 0), "posting_reconciliations": (2, 0, 0),
    },
    "import_mirror_record": {"sources": (1, 0, 0), "evidence": (1, 0, 0), "evidence_links": (1, 0, 0), "posting_reconciliations": (0, 1, 0)},
    "import_incomplete_source": {"sources": (1, 0, 0), "candidates": (1, 0, 0), "evidence": (1, 0, 0)},
    "manual_mixed_expense": {"transactions": (1, 0, 0), "transaction_versions": (1, 0, 0), "posting_sets": (1, 0, 0), "postings": (3, 0, 0), "confirmations": (1, 0, 0), "relations": (1, 0, 0), "posting_reconciliations": (2, 0, 0)},
    "credit_principal_repayment": {"transactions": (1, 0, 0), "transaction_versions": (1, 0, 0), "posting_sets": (1, 0, 0), "postings": (2, 0, 0), "confirmations": (1, 0, 0), "posting_reconciliations": (2, 0, 0)},
    "ingest_mixed_payment_source": {"sources": (1, 0, 0), "candidates": (1, 0, 0), "evidence": (1, 0, 0)},
    "confirm_mixed_payment_candidate": {"transactions": (1, 0, 0), "transaction_versions": (1, 0, 0), "posting_sets": (1, 0, 0), "postings": (3, 0, 0), "candidates": (0, 1, 0), "confirmations": (1, 0, 0), "evidence_links": (1, 0, 0), "relations": (1, 0, 0), "posting_reconciliations": (2, 0, 0)},
    "merge_mixed_payment_mirror_evidence": {"sources": (1, 0, 0), "evidence": (1, 0, 0), "evidence_links": (1, 0, 0), "posting_reconciliations": (0, 1, 0)},
    "manual_merged_payment": {"transactions": (1, 0, 0), "transaction_versions": (1, 0, 0), "posting_sets": (1, 0, 0), "postings": (3, 0, 0), "confirmations": (1, 0, 0), "relations": (1, 0, 0), "domain_entities": (4, 0, 0), "posting_reconciliations": (1, 0, 0)},
    "ingest_merged_payment_facts": {"sources": (3, 0, 0), "candidates": (1, 0, 0), "evidence": (3, 0, 0)},
    "confirm_merged_payment_candidate": {"transactions": (1, 0, 0), "transaction_versions": (1, 0, 0), "posting_sets": (1, 0, 0), "postings": (3, 0, 0), "candidates": (0, 1, 0), "confirmations": (1, 0, 0), "evidence_links": (2, 0, 0), "relations": (1, 0, 0), "domain_entities": (4, 0, 0), "posting_reconciliations": (1, 0, 0)},
    "merge_item_receipt_evidence": {"sources": (1, 0, 0), "evidence": (1, 0, 0), "evidence_links": (1, 0, 0)},
    "create_periodic_allocation": {
        "transactions": (1, 0, 0), "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0), "postings": (2, 0, 0),
        "domain_entities": (5, 0, 0), "posting_reconciliations": (1, 0, 0),
    },
    "recognize_periodic_allocation_installment": {
        "transactions": (1, 0, 0), "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0), "postings": (2, 0, 0), "audit_links": (1, 0, 0),
    },
    "revise_periodic_allocation": {"domain_entities": (4, 0, 0)},
    "correct_transaction_version": {
        "transactions": (0, 1, 0), "transaction_versions": (1, 0, 0),
        "confirmations": (1, 0, 0),
    },
    "create_staged_payment": {
        "relations": (1, 0, 0),
        "domain_entities": (1, 0, 0),
    },
    "record_staged_payment_installment": {
        "transactions": (1, 0, 0),
        "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0),
        "postings": (2, 0, 0),
        "confirmations": (1, 0, 0),
        "relations": (0, 1, 0),
        "domain_entities": (1, 1, 0),
        "posting_reconciliations": (1, 0, 0),
    },
    "change_staged_payment_fulfillment": {
        "domain_entities": (0, 1, 0),
    },
    "confirm_staged_payment_completion": {
        "domain_entities": (0, 1, 0),
    },
    "ingest_staged_payment_bank_fact": {
        "sources": (1, 0, 0),
        "evidence": (1, 0, 0),
        "candidates": (1, 0, 0),
    },
    "confirm_staged_payment_candidate": {
        "transactions": (1, 0, 0),
        "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0),
        "postings": (2, 0, 0),
        "evidence": (0, 1, 0),
        "candidates": (0, 1, 0),
        "confirmations": (1, 0, 0),
        "evidence_links": (1, 0, 0),
        "relations": (0, 1, 0),
        "domain_entities": (1, 1, 0),
        "posting_reconciliations": (1, 0, 0),
    },
    "link_staged_payment_evidence": {
        "sources": (1, 0, 0),
        "evidence": (1, 0, 0),
        "evidence_links": (1, 0, 0),
        "posting_reconciliations": (0, 1, 0),
    },
    "merge_staged_payment_mirror_evidence": {
        "sources": (1, 0, 0),
        "evidence": (1, 0, 0),
    },
    "record_refund_request_status": {
        "relations": (1, 0, 0),
        "domain_entities": (1, 0, 0),
    },
    "ingest_refund_status_source": {
        "sources": (1, 0, 0),
        "evidence": (1, 0, 0),
        "evidence_links": (1, 0, 0),
    },
    "confirm_manual_refund_receipt": {
        "transactions": (1, 0, 0),
        "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0),
        "postings": (2, 0, 0),
        "confirmations": (1, 0, 0),
        "relations": (0, 1, 0),
        "domain_entities": (0, 1, 0),
        "posting_reconciliations": (1, 0, 0),
    },
    "attach_original_payment_evidence": {
        "sources": (1, 0, 0),
        "evidence": (1, 0, 0),
        "evidence_links": (1, 0, 0),
        "posting_reconciliations": (0, 1, 0),
    },
    "attach_refund_destination_evidence": {
        "sources": (1, 0, 0),
        "evidence": (1, 0, 0),
        "evidence_links": (1, 0, 0),
        "posting_reconciliations": (0, 1, 0),
    },
    "attach_refund_dual_role_evidence": {
        "sources": (1, 0, 0),
        "evidence": (1, 0, 0),
        "evidence_links": (2, 0, 0),
    },
    "confirm_refund_receipt": {
        "transactions": (1, 0, 0),
        "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0),
        "postings": (2, 0, 0),
        "confirmations": (1, 0, 0),
        "relations": (1, 0, 0),
        "domain_entities": (1, 0, 0),
        "posting_reconciliations": (1, 0, 0),
    },
    "ingest_refund_credit_source": {
        "sources": (1, 0, 0),
        "evidence": (1, 0, 0),
        "candidates": (1, 0, 0),
    },
    "confirm_imported_refund": {
        "transactions": (1, 0, 0),
        "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0),
        "postings": (2, 0, 0),
        "candidates": (0, 1, 0),
        "confirmations": (1, 0, 0),
        "evidence_links": (1, 0, 0),
        "relations": (1, 0, 0),
        "domain_entities": (1, 0, 0),
        "posting_reconciliations": (1, 0, 0),
    },
    "merge_refund_mirror_evidence": {
        "sources": (1, 0, 0),
        "evidence": (1, 0, 0),
        "evidence_links": (1, 0, 0),
    },
    # RG-10 registered accepted budgets (D-083 expected artifact phase). The
    # registered counts mirror the v2 projections of the 44-operation expected:
    # the recharge expands the legacy 3-link merchant shape to the 4 split-role
    # links, and the activation audit link stays unprojected (the v2 schema
    # owns no explicit_confirmation_provenance link type). confirm_stored_value_spend
    # has two accepted instances with different consumption counts (main spend 1,
    # synthetic multi-lot 4), so its budget is asserted by the artifact tests instead.
    "confirm_stored_value_recharge": {
        "transactions": (1, 0, 0), "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0), "postings": (3, 0, 0),
        "sources": (2, 0, 0), "confirmations": (1, 0, 0),
        "evidence": (2, 0, 0), "evidence_links": (4, 0, 0),
        "domain_entities": (2, 0, 0), "posting_reconciliations": (2, 0, 0),
    },
    "confirm_stored_value_spend": {
        "transactions": (1, 0, 0), "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0), "postings": (2, 0, 0),
        "confirmations": (1, 0, 0), "domain_entities": (1, 0, 0),
        "posting_reconciliations": (1, 0, 0),
    },
    "ingest_stored_value_recharge_candidate": {
        "sources": (1, 0, 0), "candidates": (1, 0, 0), "evidence": (1, 0, 0),
    },
    "ingest_stored_value_spend_candidate": {
        "sources": (1, 0, 0), "candidates": (1, 0, 0), "evidence": (1, 0, 0),
    },
    "confirm_imported_stored_value_recharge": {},
    "confirm_imported_stored_value_spend": {},
    "record_expiry_reminder": {},
    "confirm_stored_value_expiry_loss": {
        "transactions": (1, 0, 0), "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0), "postings": (2, 0, 0),
        "sources": (1, 0, 0), "confirmations": (1, 0, 0),
        "evidence": (1, 0, 0), "evidence_links": (1, 0, 0),
        "domain_entities": (1, 0, 0), "posting_reconciliations": (1, 0, 0),
    },
    "reconcile_merchant_credit": {
        "posting_reconciliations": (0, 1, 0),
    },
    "reconcile_bank_payment": {
        "posting_reconciliations": (0, 1, 0),
    },
    "apply_merchant_lot_allocation": {
        "domain_entities": (2, 0, 0),
    },
    "confirm_stored_value_activation_balance": {
        "transactions": (1, 0, 0), "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0), "postings": (2, 0, 0),
        "sources": (1, 0, 0), "confirmations": (1, 0, 0),
        "evidence": (1, 0, 0), "evidence_links": (1, 0, 0),
        "domain_entities": (2, 0, 0), "audit_links": (1, 0, 0),
        "posting_reconciliations": (1, 0, 0),
    },
    "rename_stored_value_labels": {},
}

_RG06_ACTIONS = {
    "create_staged_payment",
    "record_staged_payment_installment",
    "change_staged_payment_fulfillment",
    "confirm_staged_payment_completion",
    "link_staged_payment_evidence",
    "ingest_staged_payment_bank_fact",
    "confirm_staged_payment_candidate",
    "merge_staged_payment_mirror_evidence",
}


def _posting_facts_correction_failure(
    attempted: dict[str, Any],
    baseline: dict[str, Any],
    precisions: dict[str, int],
    *,
    reject_changed_asset: bool = True,
) -> tuple[str, str] | None:
    """Return the frozen first failure for a posting-facts correction attempt."""
    transactions = {item["id"]: item for item in baseline["transactions"]}
    versions = {item["id"]: item for item in baseline["transaction_versions"]}
    posting_sets = {item["id"]: item for item in baseline["posting_sets"]}
    postings = {item["id"]: item for item in baseline["postings"]}
    accounts = {item["id"]: item for item in baseline["catalog"]["accounts"]}
    transaction = transactions.get(attempted.get("transaction_id"))
    if transaction is None:
        return "complete_replacement_postings_required", "replacement_postings"
    version = versions.get(transaction["current_version_id"])
    posting_set = posting_sets.get(version["posting_set_id"]) if version else None
    old_postings = (
        [postings[item_id] for item_id in posting_set["posting_ids"]]
        if posting_set and all(item_id in postings for item_id in posting_set["posting_ids"])
        else []
    )
    replacements = attempted.get("replacement_postings")
    if not isinstance(replacements, list):
        return "complete_replacement_postings_required", "replacement_postings"
    source_ids = [item.get("source_posting_id") for item in replacements if isinstance(item, dict)]
    old_ids = [item["id"] for item in old_postings]
    if len(replacements) != len(old_ids):
        return "complete_replacement_postings_required", "replacement_postings"

    totals: dict[str, Decimal] = defaultdict(Decimal)
    balanceable = True
    for item in replacements:
        if not isinstance(item, dict):
            balanceable = False
            break
        try:
            totals[item.get("currency")] += Decimal(str(item.get("amount")))
        except (InvalidOperation, TypeError):
            balanceable = False
            break
    if not balanceable or any(total != 0 for total in totals.values()):
        return "replacement_postings_must_balance", "replacement_postings"

    seen_sources: set[str] = set()
    for index, source_id in enumerate(source_ids):
        if not isinstance(source_id, str) or source_id in seen_sources:
            return "duplicate_source_posting_id", f"replacement_postings[{index}].source_posting_id"
        seen_sources.add(source_id)
    if set(source_ids) != set(old_ids):
        return "complete_replacement_postings_required", "replacement_postings"

    for index, item in enumerate(replacements):
        account = accounts.get(item.get("account_id"))
        if account is None:
            return "known_account_required", f"replacement_postings[{index}].account_id"
        if not account["owned_by_user"] and not (
            account["kind"] == "expense" and account["real_account"] is False
        ):
            return "owned_account_required", f"replacement_postings[{index}].account_id"
        if account["currency"] != item.get("currency"):
            return "account_currency_mismatch", f"replacement_postings[{index}].account_id"

    reconciliations = {
        item["posting_id"]: item["status"]
        for item in baseline["posting_reconciliations"]
    }
    old_by_id = {item["id"]: item for item in old_postings}
    for index, item in enumerate(replacements):
        old = old_by_id[item["source_posting_id"]]
        facts = ("account_id", "amount", "currency", "role", "category_id")
        replacement_facts = tuple(item.get(field) for field in facts)
        old_facts = tuple(old.get(field) for field in facts)
        if (
            reject_changed_asset
            and
            reconciliations.get(old["id"]) == "matched"
            and accounts[old["account_id"]]["kind"] == "asset"
            and replacement_facts != old_facts
        ):
            return "matched_unaffected_posting_must_be_preserved", f"replacement_postings[{index}]"

    if attempted.get("explicit_confirmation") is not True:
        return "explicit_confirmation_required", "explicit_confirmation"

    for index, item in enumerate(replacements):
        value = item.get("amount")
        currency = item.get("currency")
        if _attempted_decimal_value(value, currency, precisions) is None:
            return "exact_decimal_string_required", f"replacement_postings[{index}].amount"
    if "history_mutation" in attempted:
        return "historical_facts_immutable", "history_mutation"
    return None
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
    if not isinstance(value, str):
        _fail(path, "must be a canonical decimal string")
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
    if not isinstance(currency, str):
        _fail(path.rsplit(".", 1)[0] + ".currency", "must be a declared currency string")
    if not isinstance(value, str):
        _fail(path, "must be a decimal string")
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
    if not isinstance(currency, str):
        _fail(path.rsplit(".", 1)[0] + ".currency", "must be a declared currency string")
    if not isinstance(value, str):
        _fail(path, "must be a decimal string")
    if currency not in precisions:
        _fail(path.rsplit(".", 1)[0] + ".currency", f"unknown currency {currency!r}")
    precision = precisions[currency]
    if precision == 0:
        pattern = r"^-?(?:0|[1-9][0-9]*)$"
    else:
        pattern = rf"^-?(?:0|[1-9][0-9]*)\.[0-9]{{{precision}}}$"
    if not re.fullmatch(pattern, value):
        _fail(path, f"must use exactly {precision} decimal places for {currency}")
    try:
        return Decimal(value)
    except InvalidOperation:
        _fail(path, "must be a valid decimal")


def _timestamp(value: str, path: str, timezone: ZoneInfo) -> datetime:
    if not isinstance(value, str):
        _fail(path, "must be a timestamp string")
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


def _local_datetime(value: str, path: str, timezone: ZoneInfo) -> datetime:
    return _timestamp(value, path, timezone).astimezone(timezone)


def _anchor_day(year: int, month: int, anchor: dict[str, Any]) -> int:
    if anchor["type"] == "month_end":
        return calendar.monthrange(year, month)[1]
    return anchor["day"]


def _shift_month(year: int, month: int, offset: int) -> tuple[int, int]:
    absolute = year * 12 + month - 1 + offset
    return absolute // 12, absolute % 12 + 1


def _anchored_month_date(
    start: datetime,
    anchor: dict[str, Any],
    offset: int,
) -> tuple[int, int, int]:
    year, month = _shift_month(start.year, start.month, offset)
    return year, month, _anchor_day(year, month, anchor)


def _equal_split(total: Decimal, count: int, precision: int) -> list[Decimal]:
    scale = 10 ** precision
    minor_units = int(total * scale)
    quotient, remainder = divmod(minor_units, count)
    return [
        Decimal(quotient + (remainder if index == count - 1 else 0)) / scale
        for index in range(count)
    ]


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
        "audit_link": "audit_links",
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
    if kind == "component":
        matches = [
            component
            for entity in state["domain_entities"]
            if entity.get("type") == "lending_settlement"
            for component in entity["payload"]["components"]
            if component["id"] == target_id
        ]
        if len(matches) != 1:
            _fail(path, f"dangling or duplicate component reference {target_id!r}")
        return matches[0]
    if kind == "counterparty":
        # RG-08 counterparties are stable catalog identities projected through every
        # lending position/settlement and are not a generic v2 entity collection.
        matches = {
            entity["payload"]["counterparty_id"]
            for entity in state["domain_entities"]
            if entity.get("type") in {"lending_position", "lending_settlement"}
        }
        if target_id not in matches:
            _fail(path, f"dangling counterparty reference {target_id!r}")
        return {"id": target_id}
    if kind == "name_history":
        # The frozen rename is zero-state-effect; its history identity is therefore
        # owned by the closed rename input instead of a state collection.
        owners = [
            item
            for item in operations.values()
            if item["root_id"] == state["root_id"]
            and item.get("input", {}).get("variant") == "rename_counterparty"
            and item["input"].get("name_history_id") == target_id
        ]
        if len(owners) != 1:
            _fail(path, f"dangling or duplicate name-history reference {target_id!r}")
        return {"id": target_id}
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
            account["real_account"]
            and account["kind"] in {"asset", "liability"}
        ):
            _fail(
                account_path + ".reconciliation_eligible",
                "eligible accounts must be real asset or liability accounts",
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


def _validate_transaction_posting_semantics(
    transaction: dict[str, Any],
    postings: list[dict[str, Any]],
    accounts: dict[str, dict[str, Any]],
    path: str,
    precisions: dict[str, int],
    categories: dict[str, dict[str, Any]] | None = None,
) -> None:
    def account_for(posting: dict[str, Any], role: str) -> dict[str, Any]:
        account_id = posting["account_id"]
        account = accounts.get(account_id)
        if account is None:
            _fail(
                f"{path}.posting_set.{role}.account_id",
                f"dangling account reference {account_id!r}",
            )
        return account

    transaction_type = transaction["type"]
    roles = [posting.get("role") for posting in postings]
    if transaction_type == "lending_disbursement":
        if len(postings) != 2 or set(roles) != {"lending_receivable", "lending_principal_out"}:
            _fail(path + ".posting_set", "lending disbursement requires receivable and principal-out postings")
        by_role = {posting["role"]: posting for posting in postings}
        receivable, funding = by_role["lending_receivable"], by_role["lending_principal_out"]
        receivable_account = account_for(receivable, "lending_receivable")
        funding_account = account_for(funding, "lending_principal_out")
        if not (receivable_account["kind"] == "asset" and not receivable_account["real_account"] and not receivable_account["owned_by_user"] and receivable["reconciliation_eligible"] is False and _decimal(receivable["amount"], path) > 0):
            _fail(path + ".posting_set.lending_receivable", "must increase a non-real lending receivable")
        if not (funding_account["kind"] == "asset" and funding_account["real_account"] and funding_account["owned_by_user"] and funding["reconciliation_eligible"] is True and _decimal(funding["amount"], path) < 0):
            _fail(path + ".posting_set.lending_principal_out", "must reduce an eligible owned real funding asset")
        if receivable["currency"] != funding["currency"] or _decimal(receivable["amount"], path) + _decimal(funding["amount"], path) != 0:
            _fail(path + ".posting_set", "lending disbursement must balance in one currency")
        return
    if transaction_type == "lending_collection":
        expected = {"lending_principal_in", "lending_receivable", "lending_interest"}
        if len(postings) != 3 or set(roles) != expected or "lending_fee" in roles:
            _fail(path + ".posting_set", "lending collection requires destination, receivable, and interest postings with no fee posting")
        by_role = {posting["role"]: posting for posting in postings}
        destination, receivable, interest = (by_role[name] for name in ("lending_principal_in", "lending_receivable", "lending_interest"))
        destination_account = account_for(destination, "lending_principal_in")
        receivable_account = account_for(receivable, "lending_receivable")
        interest_account = account_for(interest, "lending_interest")
        if not (destination_account["kind"] == "asset" and destination_account["real_account"] and destination_account["owned_by_user"] and destination["reconciliation_eligible"] is True and _decimal(destination["amount"], path) > 0):
            _fail(path + ".posting_set.lending_principal_in", "must increase an eligible owned real destination asset")
        if not (receivable_account["kind"] == "asset" and not receivable_account["real_account"] and receivable["reconciliation_eligible"] is False and _decimal(receivable["amount"], path) <= 0):
            _fail(path + ".posting_set.lending_receivable", "must reduce the non-real lending receivable")
        if not (interest_account["kind"] == "income" and not interest_account["real_account"] and interest["reconciliation_eligible"] is False and _decimal(interest["amount"], path) <= 0):
            _fail(path + ".posting_set.lending_interest", "must credit a non-real interest income account")
        if categories is not None:
            category = categories.get(interest.get("category_id"))
            if category is None or category.get("posting_account_id") != interest["account_id"] or not category.get("active"):
                _fail(path + ".posting_set.lending_interest.category_id", "must name the active exact interest category")
        if len({posting["currency"] for posting in postings}) != 1 or sum((_decimal(posting["amount"], path) for posting in postings), Decimal(0)) != 0:
            _fail(path + ".posting_set", "lending collection must balance in one currency")
        return
    mixed_roles = {"mixed_expense_asset_funding", "mixed_expense_credit_funding"}
    if transaction_type == "expense" and not mixed_roles.intersection(roles):
        return

    if transaction_type == "prepaid_purchase":
        expected_roles = {"payment_asset", "prepaid_asset"}
        if len(postings) != 2 or set(roles) != expected_roles:
            _fail(path + ".posting_set", "prepaid purchase requires payment and prepaid asset postings")
        by_role = {posting["role"]: posting for posting in postings}
        payment, prepaid = by_role["payment_asset"], by_role["prepaid_asset"]
        payment_account, prepaid_account = account_for(payment, "payment_asset"), account_for(prepaid, "prepaid_asset")
        if not (payment_account["kind"] == "asset" and payment_account["owned_by_user"] and payment_account["real_account"] and payment["reconciliation_eligible"] is True and _decimal(payment["amount"], path) < 0):
            _fail(path + ".posting_set.payment_asset", "must be a negative eligible owned real asset posting")
        if not (prepaid_account["kind"] == "asset" and prepaid_account["owned_by_user"] and not prepaid_account["real_account"] and prepaid_account.get("hidden") is True and prepaid["reconciliation_eligible"] is False and _decimal(prepaid["amount"], path) > 0):
            _fail(path + ".posting_set.prepaid_asset", "must be a positive hidden non-real prepaid asset posting")
        if payment["currency"] != prepaid["currency"] or _decimal(payment["amount"], path) + _decimal(prepaid["amount"], path) != 0:
            _fail(path + ".posting_set", "prepaid purchase postings must balance in one currency")
        return

    if transaction_type == "prepaid_recognition":
        expected_roles = {"expense", "prepaid_asset"}
        if len(postings) != 2 or set(roles) != expected_roles:
            _fail(path + ".posting_set", "prepaid recognition requires expense and prepaid asset postings")
        by_role = {posting["role"]: posting for posting in postings}
        expense, prepaid = by_role["expense"], by_role["prepaid_asset"]
        expense_account, prepaid_account = account_for(expense, "expense"), account_for(prepaid, "prepaid_asset")
        if not (expense_account["kind"] == "expense" and not expense_account["owned_by_user"] and not expense_account["real_account"] and expense["reconciliation_eligible"] is False and _decimal(expense["amount"], path) > 0):
            _fail(path + ".posting_set.expense", "must be a positive non-owned non-real category expense posting")
        if categories is not None:
            category = categories.get(expense.get("category_id"))
            if category is None or category["parent_id"] is None or category["posting_account_id"] != expense["account_id"]:
                _fail(path + ".posting_set.expense.category_id", "must match an existing second-level category posting account")
        if not (prepaid_account["kind"] == "asset" and prepaid_account["owned_by_user"] and not prepaid_account["real_account"] and prepaid_account.get("hidden") is True and prepaid["reconciliation_eligible"] is False and _decimal(prepaid["amount"], path) < 0):
            _fail(path + ".posting_set.prepaid_asset", "must release the hidden non-real prepaid asset")
        if expense["currency"] != prepaid["currency"] or _decimal(expense["amount"], path) + _decimal(prepaid["amount"], path) != 0:
            _fail(path + ".posting_set", "prepaid recognition postings must balance in one currency")
        return

    if transaction_type == "expense":
        expected_roles = {"expense", *mixed_roles}
        if len(postings) != 3 or set(roles) != expected_roles:
            _fail(path + ".posting_set", "mixed expense requires exactly one category and two funding postings")
        by_role = {posting["role"]: posting for posting in postings}
        expense = by_role["expense"]
        expense_account = account_for(expense, "expense")
        if not (
            expense_account["kind"] == "expense"
            and not expense_account["owned_by_user"]
            and not expense_account["real_account"]
            and expense["reconciliation_eligible"] is False
        ):
            _fail(path + ".posting_set", "expense role requires a non-real category account and no reconciliation")
        if categories is not None:
            category_id = expense.get("category_id")
            category = categories.get(category_id) if category_id is not None else None
            if category is None or category["parent_id"] is None:
                _fail(path + ".posting_set.expense.category_id", "must reference an existing second-level expense category")
            if category["posting_account_id"] != expense["account_id"]:
                _fail(path + ".posting_set.expense.account_id", "must match the category posting account")
        expected_kinds = {
            "mixed_expense_asset_funding": "asset",
            "mixed_expense_credit_funding": "liability",
        }
        for role, kind in expected_kinds.items():
            posting = by_role[role]
            if "category_id" in posting:
                _fail(path + f".posting_set.{role}.category_id", "funding postings must not carry category_id")
            account = account_for(posting, role)
            if not (
                account["owned_by_user"]
                and account["real_account"]
                and account["kind"] == kind
                and posting["reconciliation_eligible"] is True
            ):
                _fail(path + ".posting_set", f"{role} requires an eligible owned real {kind} account")
            if _decimal(posting["amount"], path + ".posting_set") >= 0:
                _fail(path + ".posting_set", f"{role} must be negative")
        expense_amount = _decimal(expense["amount"], path + ".posting_set")
        if expense_amount <= 0:
            _fail(path + ".posting_set", "expense role must be positive")
        if len({posting["currency"] for posting in postings}) != 1:
            _fail(path + ".posting_set", "mixed expense postings must use the same currency")
        currency = postings[0]["currency"]
        total = sum(
            (_amount(posting["amount"], currency, path + ".posting_set", precisions) for posting in postings),
            Decimal(0),
        )
        if total != 0:
            _fail(path + ".posting_set", f"mixed expense postings must balance for {currency}")
        return

    if transaction_type == "credit_repayment":
        expected_roles = {
            "credit_repayment_asset_outflow",
            "credit_repayment_liability_principal",
        }
        if len(postings) != 2 or set(roles) != expected_roles:
            _fail(path + ".posting_set", "credit repayment requires exactly one asset outflow and one liability principal")
        by_role = {posting["role"]: posting for posting in postings}
        expected_kinds = {
            "credit_repayment_asset_outflow": ("asset", -1),
            "credit_repayment_liability_principal": ("liability", 1),
        }
        for role, (kind, sign) in expected_kinds.items():
            posting = by_role[role]
            account = account_for(posting, role)
            amount = _decimal(posting["amount"], path + ".posting_set")
            if not (
                account["owned_by_user"]
                and account["real_account"]
                and account["kind"] == kind
                and posting["reconciliation_eligible"] is True
                and ((amount < 0) if sign < 0 else (amount > 0))
            ):
                _fail(path + ".posting_set", f"{role} has invalid account, eligibility, or sign")
        if len({posting["currency"] for posting in postings}) != 1:
            _fail(path + ".posting_set", "credit repayment postings must use the same currency")
        currency = postings[0]["currency"]
        total = sum(
            (_amount(posting["amount"], currency, path + ".posting_set", precisions) for posting in postings),
            Decimal(0),
        )
        if total != 0:
            _fail(path + ".posting_set", f"credit repayment postings must balance for {currency}")


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
    categories = indexes["catalog_categories"]
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

        _validate_transaction_posting_semantics(
            transaction,
            current_postings,
            accounts,
            transaction_path,
            precisions,
            categories,
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
        if "category_id" in posting:
            category_id = posting["category_id"]
            category = categories.get(category_id) if isinstance(category_id, str) else None
            if category is None or category["parent_id"] is None:
                _fail(posting_path + ".category_id", "must reference an existing second-level category")
            if category["posting_account_id"] != posting["account_id"]:
                _fail(posting_path + ".category_id", "category posting_account_id must equal posting account_id")
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


def _validate_staged_payment_relation(
    relation: dict[str, Any],
    relation_path: str,
    indexes: dict[str, dict[str, dict[str, Any]]],
    current: dict[str, tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]],
    precisions: dict[str, int],
    member_owners: dict[str, str],
) -> None:
    accounts = indexes["catalog_accounts"]
    categories = indexes["catalog_categories"]
    entities = indexes["domain_entities"]
    transactions = indexes["transactions"]
    postings = indexes["postings"]
    refs = relation["member_refs"]

    if len({(ref["kind"], ref["id"]) for ref in refs}) != len(refs):
        _fail(relation_path + ".member_refs", "contains duplicate references")

    members: list[dict[str, Any]] = []
    for ref_index, ref in enumerate(refs):
        ref_path = f"{relation_path}.member_refs[{ref_index}]"
        if ref["kind"] != "domain_entity":
            _fail(ref_path + ".kind", "staged_payment members must be domain entities")
        member = entities.get(ref["id"])
        if member is None:
            _fail(ref_path + ".id", "dangling staged-payment member reference")
        if member["type"] not in {"staged_payment_lifecycle", "installment_payment"}:
            _fail(ref_path + ".id", "must reference a staged-payment lifecycle or installment")
        owner = member_owners.get(member["id"])
        if owner is not None and owner != relation["id"]:
            _fail(ref_path + ".id", "staged-payment member is owned by another relation")
        member_owners[member["id"]] = relation["id"]
        members.append(member)

    lifecycles = [
        member for member in members if member["type"] == "staged_payment_lifecycle"
    ]
    installments = [
        member for member in members if member["type"] == "installment_payment"
    ]
    if len(lifecycles) != 1:
        _fail(
            relation_path + ".member_refs",
            "staged_payment requires exactly one lifecycle",
        )

    installments_by_role: dict[str, dict[str, Any]] = {}
    for member in installments:
        role = member["payload"]["role"]
        if role not in {"deposit", "final"}:
            _fail(
                relation_path + ".member_refs",
                "installment role must be deposit or final",
            )
        if role in installments_by_role:
            _fail(
                relation_path + ".member_refs",
                f"staged_payment permits at most one {role} installment",
            )
        installments_by_role[role] = member
    if "final" in installments_by_role and "deposit" not in installments_by_role:
        _fail(
            relation_path + ".member_refs",
            "a final installment requires a deposit installment",
        )

    lifecycle = lifecycles[0]
    lifecycle_path = relation_path + ".member_refs"
    lifecycle_payload = lifecycle["payload"]
    currency = lifecycle_payload["currency"]
    total = _amount(
        lifecycle_payload["total_amount"],
        currency,
        lifecycle_path + ".total_amount",
        precisions,
    )
    paid = _amount(
        lifecycle_payload["paid_amount"],
        currency,
        lifecycle_path + ".paid_amount",
        precisions,
    )
    due = _amount(
        lifecycle_payload["due_amount"],
        currency,
        lifecycle_path + ".due_amount",
        precisions,
    )
    if total <= 0 or paid < 0 or due < 0 or total != paid + due:
        _fail(
            lifecycle_path,
            "lifecycle requires positive total and exact total = paid + due arithmetic",
        )

    installment_amounts: dict[str, Decimal] = {}
    for member in installments:
        member_path = lifecycle_path + f"[{member['id']!r}]"
        payload = member["payload"]
        if payload["currency"] != currency:
            _fail(member_path + ".payload.currency", "must match lifecycle currency")
        amount = _amount(
            payload["amount"],
            currency,
            member_path + ".payload.amount",
            precisions,
        )
        if amount <= 0:
            _fail(member_path + ".payload.amount", "installment amount must be positive")
        installment_amounts[member["id"]] = amount
    if paid != sum(installment_amounts.values(), Decimal(0)):
        _fail(
            lifecycle_path + ".paid_amount",
            "must equal the sum of distinct relation installment amounts",
        )

    if "final" in installments_by_role:
        deposit_payload = installments_by_role["deposit"]["payload"]
        final_payload = installments_by_role["final"]["payload"]
        if _timestamp_instant(final_payload["actual_payment_at"]) <= _timestamp_instant(
            deposit_payload["actual_payment_at"]
        ):
            _fail(
                lifecycle_path,
                "final actual_payment_at must be strictly later than deposit actual_payment_at",
            )
        if (
            "source_payment_at" in deposit_payload
            and "source_payment_at" in final_payload
            and _timestamp_instant(final_payload["source_payment_at"])
            <= _timestamp_instant(deposit_payload["source_payment_at"])
        ):
            _fail(
                lifecycle_path,
                "final source_payment_at must be strictly later than deposit source_payment_at",
            )

    category_id = lifecycle_payload["category_id"]
    category = categories.get(category_id)
    if category is None or category["parent_id"] is None:
        _fail(
            lifecycle_path + ".category_id",
            "must reference a secondary category",
        )
    expense_account_id = category["posting_account_id"]
    expense_account = accounts.get(expense_account_id)
    if expense_account is None or expense_account["kind"] != "expense":
        _fail(
            lifecycle_path + ".category_id",
            "secondary category must resolve to an expense posting account",
        )

    for member in installments:
        member_path = lifecycle_path + f"[{member['id']!r}]"
        payload = member["payload"]
        transaction_id = payload["transaction_id"]
        transaction = transactions.get(transaction_id)
        current_entry = current.get(transaction_id)
        if transaction is None or transaction["type"] != "expense" or current_entry is None:
            _fail(
                member_path + ".payload.transaction_id",
                "must reference one current expense transaction",
            )
        current_transaction, version, current_postings = current_entry
        if (
            current_transaction["id"] != transaction_id
            or version["transaction_id"] != transaction_id
            or transaction["current_version_id"] != version["id"]
        ):
            _fail(
                member_path + ".payload.transaction_id",
                "must bind the exact current transaction version",
            )

        expense_posting_id = payload["expense_posting_id"]
        asset_posting_id = payload["asset_posting_id"]
        if expense_posting_id == asset_posting_id:
            _fail(member_path + ".payload", "expense and asset postings must be distinct")
        current_posting_ids = {posting["id"] for posting in current_postings}
        if {
            expense_posting_id,
            asset_posting_id,
        } - current_posting_ids:
            _fail(
                member_path + ".payload",
                "both installment postings must belong to the current posting set",
            )
        expense_posting = postings.get(expense_posting_id)
        asset_posting = postings.get(asset_posting_id)
        if expense_posting is None or asset_posting is None:
            _fail(member_path + ".payload", "installment posting reference is dangling")
        if (
            expense_posting["posting_set_id"] != version["posting_set_id"]
            or asset_posting["posting_set_id"] != version["posting_set_id"]
        ):
            _fail(
                member_path + ".payload",
                "installment postings must identify the current posting set",
            )

        amount = installment_amounts[member["id"]]
        if expense_posting.get("role") != "expense":
            _fail(
                member_path + ".payload.expense_posting_id",
                "must reference the expense posting",
            )
        if asset_posting.get("role") != "payment_asset":
            _fail(
                member_path + ".payload.asset_posting_id",
                "must reference the payment_asset posting",
            )
        if expense_posting.get("category_id") != category_id:
            _fail(
                member_path + ".payload.expense_posting_id",
                "expense posting category must match the lifecycle category",
            )
        if expense_posting["account_id"] != expense_account_id:
            _fail(
                member_path + ".payload.expense_posting_id",
                "expense posting account must match the category posting account",
            )
        if expense_posting["currency"] != currency or asset_posting["currency"] != currency:
            _fail(member_path + ".payload", "installment postings must match lifecycle currency")
        if _amount(
            expense_posting["amount"],
            currency,
            member_path + ".payload.expense_posting_id",
            precisions,
        ) != amount:
            _fail(
                member_path + ".payload.expense_posting_id",
                "expense posting must equal the positive installment amount",
            )
        if _amount(
            asset_posting["amount"],
            currency,
            member_path + ".payload.asset_posting_id",
            precisions,
        ) != -amount:
            _fail(
                member_path + ".payload.asset_posting_id",
                "asset posting must equal the negative installment amount",
            )

        funding_account_id = payload["funding_account_id"]
        funding_account = accounts.get(funding_account_id)
        if funding_account is None or asset_posting["account_id"] != funding_account_id:
            _fail(
                member_path + ".payload.funding_account_id",
                "must match the asset posting account",
            )
        if not (
            funding_account["owned_by_user"]
            and funding_account["real_account"]
            and funding_account["kind"] == "asset"
            and funding_account["reconciliation_eligible"]
            and asset_posting["reconciliation_eligible"]
        ):
            _fail(
                member_path + ".payload.funding_account_id",
                "must reference an eligible owned real asset account and posting",
            )
        if funding_account["currency"] != currency:
            _fail(
                member_path + ".payload.funding_account_id",
                "funding account currency must match the installment",
            )
        if version["occurred_at"] != payload["actual_payment_at"]:
            _fail(
                member_path + ".payload.actual_payment_at",
                "must equal the current transaction version occurred_at",
            )
        if version["statistics_at"] != payload["statistics_at"]:
            _fail(
                member_path + ".payload.statistics_at",
                "must equal the current transaction version statistics_at",
            )

    history = lifecycle_payload["state_history"]
    history_path = lifecycle_path + ".state_history"
    _unique_index(history, history_path)
    if [item["sequence"] for item in history] != list(range(1, len(history) + 1)):
        _fail(history_path, "sequence must be contiguous and ordered from 1")
    if not history or history[0]["event"] != "group_created":
        _fail(history_path, "must begin with group_created")

    cumulative_paid = Decimal(0)
    seen_payment_ids: set[str] = set()
    previous_instant: datetime | None = None
    previous_fulfillment = "in_progress"
    history_by_payment: dict[str, dict[str, Any]] = {}
    for history_index, item in enumerate(history):
        item_path = f"{history_path}[{history_index}]"
        instant = _timestamp_instant(item["occurred_at"])
        if previous_instant is not None and instant < previous_instant:
            _fail(item_path + ".occurred_at", "history times must be chronologically ordered")
        previous_instant = instant

        item_total = _amount(
            item["total_amount"], currency, item_path + ".total_amount", precisions
        )
        item_paid = _amount(
            item["paid_amount"], currency, item_path + ".paid_amount", precisions
        )
        item_due = _amount(
            item["due_amount"], currency, item_path + ".due_amount", precisions
        )
        if item_total != total or item_total != item_paid + item_due:
            _fail(
                item_path,
                "history snapshot must preserve lifecycle total = paid + due arithmetic",
            )

        payment_id = item["payment_id"]
        if item["event"] == "payment_confirmed":
            if payment_id not in installment_amounts or payment_id in seen_payment_ids:
                _fail(
                    item_path + ".payment_id",
                    "must name one previously unconfirmed relation installment",
                )
            seen_payment_ids.add(payment_id)
            history_by_payment[payment_id] = item
            cumulative_paid += installment_amounts[payment_id]
        elif payment_id is not None:
            _fail(
                item_path + ".payment_id",
                "only payment_confirmed may name an installment",
            )
        if item_paid != cumulative_paid or item_due != total - cumulative_paid:
            _fail(
                item_path,
                "history paid and due must equal the cumulative confirmed installments",
            )

        expected_progress = (
            "unpaid"
            if item_paid == 0
            else "paid_in_full"
            if item_due == 0
            else "partially_paid"
        )
        if item["payment_progress"] != expected_progress:
            _fail(
                item_path + ".payment_progress",
                "must match the history monetary snapshot",
            )
        if history_index == 0 and item["fulfillment_status"] != "in_progress":
            _fail(item_path + ".fulfillment_status", "group_created must be in_progress")
        if item["event"] == "fulfillment_changed":
            if item["fulfillment_status"] != "fulfilled":
                _fail(
                    item_path + ".fulfillment_status",
                    "fulfillment_changed must project fulfilled",
                )
            previous_fulfillment = "fulfilled"
        elif item["fulfillment_status"] != previous_fulfillment:
            _fail(
                item_path + ".fulfillment_status",
                "non-fulfillment events must preserve fulfillment status",
            )
        if item["event"] == "completion_confirmed" and item_due != 0:
            _fail(item_path, "completion_confirmed requires zero due amount")
        if item["state_transition_effect_count"] != 0:
            _fail(item_path + ".state_transition_effect_count", "must be zero")

    if seen_payment_ids != set(installment_amounts):
        _fail(
            history_path,
            "payment_confirmed history must cover every relation installment exactly once",
        )
    latest = history[-1]
    if (
        latest["total_amount"] != lifecycle_payload["total_amount"]
        or latest["paid_amount"] != lifecycle_payload["paid_amount"]
        or latest["due_amount"] != lifecycle_payload["due_amount"]
    ):
        _fail(history_path, "latest history snapshot must equal current lifecycle amounts")

    if "final" in installments_by_role:
        deposit_id = installments_by_role["deposit"]["id"]
        final_id = installments_by_role["final"]["id"]
        deposit_event = history_by_payment[deposit_id]
        final_event = history_by_payment[final_id]
        if (
            deposit_event["sequence"] >= final_event["sequence"]
            or _timestamp_instant(deposit_event["occurred_at"])
            >= _timestamp_instant(final_event["occurred_at"])
        ):
            _fail(
                history_path,
                "deposit payment history must precede final payment history",
            )


def _validate_relations(
    state: dict[str, Any],
    path: str,
    indexes: dict[str, dict[str, dict[str, Any]]],
    current: dict[str, tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]],
    precisions: dict[str, int],
) -> None:
    accounts = indexes["catalog_accounts"]
    transactions = indexes["transactions"]
    postings = indexes["postings"]
    staged_member_owners: dict[str, str] = {}
    for index, relation in enumerate(state["relations"]):
        if relation["type"] == "refund":
            relation_path = f"{path}.relations[{index}]"
            refs = relation["member_refs"]
            if len(refs) not in {1, 2} or any(ref["kind"] != "transaction" for ref in refs):
                _fail(relation_path + ".member_refs", "refund requires one original transaction and at most one refund transaction")
            if len({ref["id"] for ref in refs}) != len(refs):
                _fail(relation_path + ".member_refs", "contains duplicate references")
            if any(ref["id"] not in transactions for ref in refs):
                _fail(relation_path + ".member_refs", "references an unknown transaction")
            member_types = [transactions[ref["id"]]["type"] for ref in refs]
            if member_types.count("expense") != 1:
                _fail(relation_path + ".member_refs", "refund must contain exactly one original expense transaction")
            if len(refs) == 2 and member_types.count("refund_receipt") != 1:
                _fail(relation_path + ".member_refs", "refund member must reference one refund_receipt transaction")
            if len(refs) == 1 and member_types != ["expense"]:
                _fail(relation_path + ".member_refs", "pre-receipt refund must contain only the original expense")
            if relation["payload"] != {}:
                _fail(relation_path + ".payload", "refund relation payload is owned by refund_relationship domain entity")
            continue
        if relation["type"] == "staged_payment":
            _validate_staged_payment_relation(
                relation,
                f"{path}.relations[{index}]",
                indexes,
                current,
                precisions,
                staged_member_owners,
            )
            continue
        if relation["type"] == "merged_payment":
            _validate_merged_payment_relation(
                relation,
                f"{path}.relations[{index}]",
                indexes,
                current,
                precisions,
            )
            continue
        if relation["type"] != "mixed_payment":
            continue
        relation_path = f"{path}.relations[{index}]"
        member_refs = relation["member_refs"]
        transaction_refs = [ref for ref in member_refs if ref["kind"] == "transaction"]
        posting_refs = [ref for ref in member_refs if ref["kind"] == "posting"]
        if len(transaction_refs) != 1 or len(posting_refs) != 2:
            _fail(relation_path + ".member_refs", "mixed_payment requires exactly one transaction and two postings")
        if len({ref["id"] for ref in member_refs}) != len(member_refs):
            _fail(relation_path + ".member_refs", "contains duplicate references")

        transaction_id = transaction_refs[0]["id"]
        if transaction_id not in transactions:
            _fail(relation_path + ".member_refs", "mixed_payment transaction reference is dangling")
        if transactions[transaction_id]["type"] != "expense":
            _fail(relation_path + ".member_refs", "mixed_payment must reference an expense transaction")
        current_entry = current.get(transaction_id)
        if current_entry is None:
            _fail(relation_path + ".member_refs", "mixed_payment transaction is not current")
        current_postings = current_entry[2]
        referenced_posting_ids = [ref["id"] for ref in posting_refs]
        if any(posting_id not in postings for posting_id in referenced_posting_ids):
            _fail(relation_path + ".member_refs", "mixed_payment posting reference is dangling")
        current_posting_ids = {posting["id"] for posting in current_postings}
        if not set(referenced_posting_ids).issubset(current_posting_ids):
            _fail(relation_path + ".member_refs", "funding postings must belong to the current transaction posting set")

        posting_by_role: dict[str, dict[str, Any]] = {}
        for posting in (postings[posting_id] for posting_id in referenced_posting_ids):
            role = posting.get("role")
            if role not in {"mixed_expense_asset_funding", "mixed_expense_credit_funding"}:
                _fail(relation_path + ".member_refs", "postings must have mixed payment funding roles")
            if role in posting_by_role:
                _fail(relation_path + ".member_refs", "funding roles must be unique")
            account = accounts.get(posting["account_id"])
            expected_kind = (
                "asset"
                if role == "mixed_expense_asset_funding"
                else "liability"
            )
            if account is None or not (
                account["owned_by_user"]
                and account["real_account"]
                and account["kind"] == expected_kind
            ):
                _fail(relation_path + ".member_refs", f"{role} must belong to an owned real {expected_kind} account")
            if posting["currency"] != account["currency"]:
                _fail(relation_path + ".member_refs", "funding posting currency must match its account")
            amount = _amount(posting["amount"], posting["currency"], relation_path + ".member_refs", precisions)
            if amount >= 0:
                _fail(relation_path + ".member_refs", "funding postings must have negative amounts")
            posting_by_role[role] = posting
        if set(posting_by_role) != {"mixed_expense_asset_funding", "mixed_expense_credit_funding"}:
            _fail(relation_path + ".member_refs", "must contain one asset and one credit funding posting")
        currencies = {posting["currency"] for posting in posting_by_role.values()}
        if len(currencies) != 1:
            _fail(relation_path + ".member_refs", "funding postings must use the same currency")

        payload = relation["payload"]
        if payload["system_managed"] is not True:
            _fail(relation_path + ".payload.system_managed", "must be true")
        if payload["generic_order_lifecycle"] is not False:
            _fail(relation_path + ".payload.generic_order_lifecycle", "must be false")
        currency = next(iter(posting_by_role.values()))["currency"]
        total = _amount(payload["payment_composition_total"], currency, relation_path + ".payload.payment_composition_total", precisions)
        expected_total = sum((abs(_decimal(posting["amount"], relation_path + ".member_refs")) for posting in posting_by_role.values()), Decimal(0))
        if total <= 0 or total != expected_total:
            _fail(relation_path + ".payload.payment_composition_total", "must equal the absolute funding amounts")

        components = payload["funding_components"]
        if len(components) != 2:
            _fail(relation_path + ".payload.funding_components", "must contain exactly two components")
        expected_posting_ids = {posting["id"] for posting in posting_by_role.values()}
        seen_components: set[str] = set()
        for component_index, component in enumerate(components):
            component_path = f"{relation_path}.payload.funding_components[{component_index}]"
            posting_id = component["posting_id"]
            if posting_id in seen_components or posting_id not in expected_posting_ids:
                _fail(component_path + ".posting_id", "must reference each funding posting exactly once")
            seen_components.add(posting_id)
            posting = postings[posting_id]
            if component["account_id"] != posting["account_id"]:
                _fail(component_path + ".account_id", "must match the referenced posting")
            if component["currency"] != posting["currency"]:
                _fail(component_path + ".currency", "must match the referenced posting")
            amount = _amount(component["funding_amount"], component["currency"], component_path + ".funding_amount", precisions)
            if amount != abs(_decimal(posting["amount"], component_path + ".posting_id")):
                _fail(component_path + ".funding_amount", "must match the absolute referenced posting amount")
        if seen_components != expected_posting_ids:
            _fail(relation_path + ".payload.funding_components", "must cover both funding postings")


def _validate_merged_payment_relation(
    relation: dict[str, Any],
    relation_path: str,
    indexes: dict[str, dict[str, dict[str, Any]]],
    current: dict[str, tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]],
    precisions: dict[str, int],
) -> None:
    accounts = indexes["catalog_accounts"]
    transactions = indexes["transactions"]
    postings = indexes["postings"]
    entities = indexes["domain_entities"]
    refs = relation["member_refs"]
    transaction_refs = [ref for ref in refs if ref["kind"] == "transaction"]
    posting_refs = [ref for ref in refs if ref["kind"] == "posting"]
    allocation_refs = [ref for ref in refs if ref["kind"] == "domain_entity"]
    if len(transaction_refs) != 1 or len(posting_refs) != 1 or len(allocation_refs) != 2:
        _fail(
            relation_path + ".member_refs",
            "merged_payment requires one transaction, one payment posting, and two allocations",
        )
    if len({(ref["kind"], ref["id"]) for ref in refs}) != 4:
        _fail(relation_path + ".member_refs", "contains duplicate references")

    transaction_id = transaction_refs[0]["id"]
    transaction = transactions.get(transaction_id)
    if transaction is None or transaction["type"] != "expense":
        _fail(relation_path + ".member_refs", "must reference one current expense transaction")
    current_entry = current.get(transaction_id)
    if current_entry is None:
        _fail(relation_path + ".member_refs", "transaction is not current")
    current_postings = current_entry[2]
    current_posting_ids = {posting["id"] for posting in current_postings}
    payment_id = posting_refs[0]["id"]
    payment = postings.get(payment_id)
    if payment is None or payment_id not in current_posting_ids or payment.get("role") != "payment_asset":
        _fail(relation_path + ".member_refs", "payment member must be the current payment_asset posting")
    account = accounts.get(payment["account_id"])
    if account is None or not (
        account["owned_by_user"] and account["real_account"] and account["kind"] == "asset"
    ):
        _fail(relation_path + ".member_refs", "payment posting must use an owned real asset account")
    payment_amount = _amount(
        payment["amount"], payment["currency"], relation_path + ".member_refs", precisions
    )
    if payment_amount >= 0:
        _fail(relation_path + ".member_refs", "payment posting must be negative")
    if sum(1 for posting in current_postings if posting.get("role") == "payment_asset") != 1:
        _fail(relation_path + ".member_refs", "transaction must have exactly one payment_asset posting")

    allocation_total = Decimal(0)
    allocation_posting_ids: set[str] = set()
    for allocation_ref in allocation_refs:
        allocation = entities.get(allocation_ref["id"])
        if allocation is None or allocation.get("type") != "item_allocation":
            _fail(relation_path + ".member_refs", "allocation members must reference item_allocation entities")
        payload = allocation["payload"]
        posting_id = payload["expense_posting_id"]
        posting = postings.get(posting_id)
        if (
            posting is None
            or posting_id not in current_posting_ids
            or posting.get("role") != "expense"
        ):
            _fail(relation_path + ".member_refs", "allocations must bind current expense postings")
        if posting_id in allocation_posting_ids:
            _fail(relation_path + ".member_refs", "allocations must bind distinct expense postings")
        allocation_posting_ids.add(posting_id)
        if payload["currency"] != payment["currency"]:
            _fail(relation_path + ".member_refs", "allocation currency must match payment currency")
        amount = _amount(
            payload["amount"], payload["currency"], relation_path + ".member_refs", precisions
        )
        if amount <= 0:
            _fail(relation_path + ".member_refs", "allocation amounts must be positive")
        allocation_total += amount
        consumption = entities.get(payload["consumption_record_id"])
        if consumption is None or consumption.get("type") != "consumption_record":
            _fail(relation_path + ".member_refs", "allocation must bind a consumption record")
        consumption_payload = consumption["payload"]
        for required in ("details", "source_observed_at"):
            if required not in consumption_payload:
                _fail(
                    relation_path + ".member_refs",
                    f"merged-payment consumption requires {required}",
                )
        if consumption_payload["statistics_at"] != current_entry[1]["statistics_at"]:
            _fail(
                relation_path + ".member_refs",
                "consumption statistics_at must match the common payment statistics time",
            )

    payload = relation["payload"]
    if payload["currency"] != payment["currency"]:
        _fail(relation_path + ".payload.currency", "must match the payment posting currency")
    total = _amount(
        payload["payment_total"], payload["currency"], relation_path + ".payload.payment_total", precisions
    )
    if total != -payment_amount or total != allocation_total:
        _fail(
            relation_path + ".payload.payment_total",
            "must equal the payment absolute amount and both allocations",
        )


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


def _mixed_payment_candidate_contract(
    source_payload: dict[str, Any],
    source_path: str,
) -> tuple[str, dict[str, Any]]:
    if not isinstance(source_payload, dict):
        _fail(source_path, "mixed payment source payload must be an object")
    currency = source_payload.get("currency")
    total_value = source_payload.get("total_amount")
    if not isinstance(currency, str) or not isinstance(total_value, str):
        _fail(source_path, "mixed payment source must own total_amount and currency")
    if not _DECIMAL_PATTERN.fullmatch(total_value):
        _fail(source_path + ".total_amount", "must be a decimal string")
    total = Decimal(total_value)
    completeness = source_payload.get("completeness")
    common = {
        "total_amount": total_value,
        "currency": currency,
        "evidence_refs": [source_payload.get("evidence_id")],
    }
    if completeness == "complete":
        return "1.00", {
            **common,
            "suggested_category_id": source_payload.get("suggested_category_id"),
            "known_funding_components": deepcopy(source_payload.get("funding_components")),
            "provenance": {"rule": "complete_mixed_payment_source", "rule_version": 1},
            "requires_confirmation": ["category_id", "funding_components", "formal_transaction_creation"],
        }
    if completeness == "missing_funding_leg":
        known_value = source_payload.get("known_asset_funding_amount")
        if not isinstance(known_value, str):
            _fail(source_path + ".known_asset_funding_amount", "must be a decimal string")
        if not _DECIMAL_PATTERN.fullmatch(known_value):
            _fail(source_path + ".known_asset_funding_amount", "must be a decimal string")
        known = Decimal(known_value)
        missing = total - known
        precision = len(total_value.partition(".")[2])
        return "0.58", {
            **common,
            "known_funding_amount": known_value,
            "missing_funding_amount": f"{missing:.{precision}f}",
            "provenance": {"rule": "incomplete_mixed_payment_source", "rule_version": 1},
            "requires_confirmation": ["funding_account_id", "missing_funding_amount", "category_id", "formal_transaction_creation"],
        }
    _fail(source_path + ".completeness", "must be complete or missing_funding_leg")


def _validate_references(
    state: dict[str, Any],
    path: str,
    indexes: dict[str, dict[str, dict[str, Any]]],
    operations: dict[str, dict[str, Any]],
    precisions: dict[str, int],
    timezone: ZoneInfo,
    case_id: str | None = None,
) -> None:
    accounts = indexes["catalog_accounts"]
    categories = indexes["catalog_categories"]
    sources = indexes["sources"]
    transactions = indexes["transactions"]
    versions = indexes["transaction_versions"]
    domain_entities = indexes["domain_entities"]
    domain_entity_paths = {
        entity["id"]: f"{path}.domain_entities[{index}]"
        for index, entity in enumerate(state["domain_entities"])
    }
    source_positions = {
        source["id"]: index for index, source in enumerate(state["sources"])
    }
    evidence_positions = {
        evidence["id"]: index for index, evidence in enumerate(state["evidence"])
    }
    staged_evidence_by_source: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for evidence in state["evidence"]:
        if evidence["type"] == "staged_payment_bank_payment":
            for source_id in evidence["source_ids"]:
                staged_evidence_by_source[source_id].append(evidence)
    links_by_evidence_id: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for link in state["evidence_links"]:
        links_by_evidence_id[link["evidence_id"]].append(link)

    for index, source in enumerate(state["sources"]):
        payload = source["payload"]
        source_path = f"{path}.sources[{index}].payload"
        if not isinstance(payload, dict):
            _fail(source_path, "must be an object")
        if source["type"] == "staged_payment_bank_fact":
            staged_evidence = staged_evidence_by_source[source["id"]]
            if len(staged_evidence) != 1:
                _fail(
                    source_path,
                    "staged payment bank fact must have exactly one staged payment evidence",
                )
            evidence = staged_evidence[0]
            evidence_index = evidence_positions[evidence["id"]]
            evidence_path = f"{path}.evidence[{evidence_index}]"
            if evidence["source_ids"] != [source["id"]]:
                _fail(
                    evidence_path + ".source_ids",
                    "must reference the exact staged payment bank fact",
                )
            evidence_payload = evidence["payload"]
            source_time_keys = [
                key for key in ("observed_at", "source_payment_at") if key in payload
            ]
            evidence_time_keys = [
                key
                for key in ("observed_at", "source_payment_at")
                if key in evidence_payload
            ]
            if len(source_time_keys) != 1:
                _fail(
                    source_path,
                    "must contain exactly one observed_at or source_payment_at",
                )
            if evidence_time_keys != source_time_keys:
                _fail(
                    evidence_path + ".payload",
                    "must use the source's exact payment time field",
                )
            time_key = source_time_keys[0]
            source_time = payload[time_key]
            evidence_time = evidence_payload[time_key]
            _timestamp(source_time, source_path + "." + time_key, timezone)
            _timestamp(
                evidence_time,
                evidence_path + ".payload." + time_key,
                timezone,
            )
            if evidence_time != source_time:
                _fail(
                    evidence_path + ".payload." + time_key,
                    "must byte-equal the staged payment source time",
                )

            evidence_links = links_by_evidence_id[evidence["id"]]
            mirror_source_id = payload.get("mirror_of_source_id")
            payment_id = evidence_payload.get("payment_id")
            if payment_id is None:
                if (
                    time_key != "source_payment_at"
                    or mirror_source_id is not None
                    or any(
                        key in evidence_payload
                        for key in (
                            "mirror_of_evidence_id",
                            "merged_into_evidence_link_id",
                        )
                    )
                    or evidence_links
                ):
                    _fail(
                        evidence_path + ".payload.payment_id",
                        "only non-mirror imported evidence may remain unbound",
                    )
                continue
            payment = domain_entities.get(payment_id)
            if payment is None or payment["type"] != "installment_payment":
                _fail(
                    evidence_path + ".payload.payment_id",
                    "must reference an installment payment",
                )
            relation_owners = [
                relation
                for relation in state["relations"]
                if relation["type"] == "staged_payment"
                and any(
                    ref["kind"] == "domain_entity" and ref["id"] == payment_id
                    for ref in relation["member_refs"]
                )
            ]
            if len(relation_owners) != 1:
                _fail(
                    evidence_path + ".payload.payment_id",
                    "installment payment must belong to exactly one staged payment relation",
                )

            payment_payload = payment["payload"]
            currency = payload["currency"]
            source_amount = _amount(
                payload["amount"], currency, source_path + ".amount", precisions
            )
            payment_amount = _amount(
                payment_payload["amount"],
                payment_payload["currency"],
                evidence_path + ".payload.payment_id",
                precisions,
            )
            if currency != payment_payload["currency"] or abs(source_amount) != payment_amount:
                _fail(
                    source_path + ".amount",
                    "absolute source amount and currency must match the installment",
                )
            posting = indexes["postings"].get(payment_payload["asset_posting_id"])
            if posting is None:
                _fail(
                    evidence_path + ".payload.payment_id",
                    "installment has a dangling payment_asset posting",
                )
            transaction = transactions.get(payment_payload["transaction_id"])
            if transaction is None:
                _fail(
                    evidence_path + ".payload.payment_id",
                    "installment has a dangling transaction",
                )
            version = versions[transaction["current_version_id"]]
            posting_set = indexes["posting_sets"][version["posting_set_id"]]
            if posting["id"] not in posting_set["posting_ids"]:
                _fail(
                    evidence_path + ".payload.payment_id",
                    "payment_asset posting must belong to the installment's current transaction",
                )
            posting_amount = _amount(
                posting["amount"], posting["currency"], source_path + ".amount", precisions
            )
            account = accounts[posting["account_id"]]
            if (
                posting["role"] != "payment_asset"
                or not posting["reconciliation_eligible"]
                or posting_amount != -payment_amount
                or posting["currency"] != currency
                or posting["account_id"] != payment_payload["funding_account_id"]
                or not account["owned_by_user"]
                or not account["real_account"]
                or not account["reconciliation_eligible"]
                or account["kind"] != "asset"
            ):
                _fail(
                    evidence_path + ".payload.payment_id",
                    "must bind the exact eligible owned-real negative payment_asset posting",
                )

            if mirror_source_id is None:
                if any(
                    key in evidence_payload
                    for key in ("mirror_of_evidence_id", "merged_into_evidence_link_id")
                ):
                    _fail(
                        evidence_path + ".payload",
                        "non-mirror evidence cannot declare mirror lineage",
                    )
                expected_link = {
                    "evidence_id": evidence["id"],
                    "target_kind": "posting",
                    "target_id": posting["id"],
                    "role": "payment_asset_posting",
                }
                if len(evidence_links) != 1 or any(
                    evidence_links[0][key] != value
                    for key, value in expected_link.items()
                ):
                    _fail(
                        path + ".evidence_links",
                        "staged payment evidence must have one exact payment_asset_posting link",
                    )
            else:
                if time_key != "source_payment_at":
                    _fail(source_path, "mirror source must use source_payment_at")
                original = sources.get(mirror_source_id)
                if (
                    original is None
                    or original["type"] != "staged_payment_bank_fact"
                    or source_positions[original["id"]] >= index
                    or "mirror_of_source_id" in original["payload"]
                ):
                    _fail(
                        source_path + ".mirror_of_source_id",
                        "must reference an earlier original staged payment source",
                    )
                original_evidence = staged_evidence_by_source[original["id"]]
                if len(original_evidence) != 1:
                    _fail(
                        source_path + ".mirror_of_source_id",
                        "original source must own exactly one staged payment evidence",
                    )
                original_evidence = original_evidence[0]
                original_payload = original_evidence["payload"]
                original_amount = _amount(
                    original["payload"]["amount"],
                    original["payload"]["currency"],
                    source_path + ".mirror_of_source_id",
                    precisions,
                )
                if (
                    original["payload"]["currency"] != currency
                    or original_amount != -source_amount
                    or original_payload["payment_id"] != payment_id
                    or original["payload"].get("source_payment_at") != source_time
                    or evidence_positions[original_evidence["id"]] >= evidence_index
                    or evidence_payload.get("mirror_of_evidence_id")
                    != original_evidence["id"]
                ):
                    _fail(
                        evidence_path + ".payload.mirror_of_evidence_id",
                        "mirror must pair the earlier opposite-signed original payment evidence",
                    )
                merged_link_id = evidence_payload.get("merged_into_evidence_link_id")
                merged_link = indexes["evidence_links"].get(merged_link_id)
                if (
                    merged_link is None
                    or merged_link["evidence_id"] != original_evidence["id"]
                    or merged_link["target_kind"] != "posting"
                    or merged_link["target_id"] != posting["id"]
                    or merged_link["role"] != "payment_asset_posting"
                    or evidence_links
                ):
                    _fail(
                        evidence_path + ".payload.merged_into_evidence_link_id",
                        "must merge into the original evidence's exact canonical posting link",
                    )
            continue
        if source["type"] in {"merged_payment_bank_fact", "merged_payment_item_fact"}:
            currency = payload["currency"]
            _timestamp(payload["observed_at"], source_path + ".observed_at", timezone)
            evidence = indexes["evidence"].get(payload["evidence_id"])
            expected_evidence_type = (
                "bank_payment"
                if source["type"] == "merged_payment_bank_fact"
                else payload["evidence_kind"]
            )
            if evidence is None or evidence["type"] != expected_evidence_type:
                _fail(source_path + ".evidence_id", "must reference the exact RG-05 evidence subtype")
            if evidence["source_ids"] != [source["id"]]:
                _fail(source_path + ".evidence_id", "evidence must reference this exact source")
            if evidence["payload"]["observed_at"] != payload["observed_at"]:
                _fail(source_path + ".observed_at", "must match evidence observed_at")
            amount = _amount(payload["amount"], currency, source_path + ".amount", precisions)
            if source["type"] == "merged_payment_bank_fact":
                if amount >= 0:
                    _fail(source_path + ".amount", "bank payment source amount must be negative")
            else:
                if amount <= 0:
                    _fail(source_path + ".amount", "item source amount must be positive")
                category = categories.get(payload["suggested_category_id"])
                if category is None:
                    _fail(source_path + ".suggested_category_id", "dangling category suggestion")
                if payload["completeness"] != (
                    "complete" if payload["evidence_kind"] == "item_receipt" else "summary_only"
                ):
                    _fail(source_path + ".completeness", "must match evidence_kind")
            continue
        if source["type"] == "account_credit_observation":
            account = accounts.get(payload["account_id"])
            if account is None:
                _fail(source_path + ".account_id", "dangling account reference")
            if not (account["owned_by_user"] and account["real_account"] and account["kind"] in {"asset", "liability"}):
                _fail(source_path + ".account_id", "must be an owned real financial account")
            if payload["currency"] != account["currency"]:
                _fail(source_path + ".currency", "must match the observed account currency")
            credit = _amount(payload["credit_amount"], payload["currency"], source_path + ".credit_amount", precisions)
            if credit <= 0:
                _fail(source_path + ".credit_amount", "must be positive")
            _timestamp(payload["observed_at"], source_path + ".observed_at", timezone)
            evidence = indexes["evidence"].get(payload["evidence_id"])
            if evidence is None or evidence["type"] != "transfer_record":
                _fail(source_path + ".evidence_id", "must reference transfer_record evidence")
            if evidence["source_ids"] != [source["id"]] or evidence["payload"]["observed_at"] != payload["observed_at"]:
                _fail(source_path + ".evidence_id", "transfer evidence must bind this source and observed_at")
            continue
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
        if source["type"] == "mixed_payment":
            currency = payload.get("currency")
            observed_at = payload.get("observed_at")
            evidence_id = payload.get("evidence_id")
            if not isinstance(currency, str):
                _fail(source_path + ".currency", "must be a declared currency string")
            if not isinstance(observed_at, str):
                _fail(source_path + ".observed_at", "must be a timestamp string")
            _timestamp(observed_at, source_path + ".observed_at", timezone)
            if "completeness" not in payload:
                expected_fields = {"evidence_id", "observed_at", "account_id", "amount", "currency"}
                if set(payload) != expected_fields:
                    _fail(source_path, "mirror source payload must contain exactly its closed evidence fields")
                account_id = payload.get("account_id")
                account = accounts.get(account_id) if isinstance(account_id, str) else None
                if account is None or not (
                    account["owned_by_user"]
                    and account["real_account"]
                    and account["kind"] == "liability"
                ):
                    _fail(source_path + ".account_id", "must reference an owned real liability account")
                if account["currency"] != currency:
                    _fail(source_path + ".currency", "must match the mirror liability account currency")
                amount = _amount(payload.get("amount"), currency, source_path + ".amount", precisions)
                if amount <= 0:
                    _fail(source_path + ".amount", "must be positive")
                evidence = indexes["evidence"].get(evidence_id) if isinstance(evidence_id, str) else None
                if evidence is None or evidence["type"] != "credit_liability_mirror":
                    _fail(source_path + ".evidence_id", "must reference credit_liability_mirror evidence")
                if evidence["source_ids"] != [source["id"]] or evidence["payload"] != {"observed_at": observed_at}:
                    _fail(source_path + ".evidence_id", "mirror evidence must bind this source and observed_at")
                continue
            _mixed_payment_candidate_contract(payload, source_path)
            evidence = indexes["evidence"].get(evidence_id) if isinstance(evidence_id, str) else None
            if evidence is None or evidence["type"] != "asset_funding_debit":
                _fail(source_path + ".evidence_id", "must reference asset_funding_debit evidence")
            if evidence["source_ids"] != [source["id"]] or evidence["payload"] != {"observed_at": observed_at}:
                _fail(source_path + ".evidence_id", "mixed payment evidence must bind this source and observed_at")
            total = _amount(payload["total_amount"], currency, source_path + ".total_amount", precisions)
            if total <= 0:
                _fail(source_path + ".total_amount", "must be positive")
            if payload["completeness"] == "complete":
                suggested_category_id = payload.get("suggested_category_id")
                if not isinstance(suggested_category_id, str) or suggested_category_id not in categories:
                    _fail(source_path + ".suggested_category_id", "must reference an existing category")
                components = payload.get("funding_components")
                if not isinstance(components, list) or len(components) != 2:
                    _fail(source_path + ".funding_components", "must contain exactly two components")
                seen_accounts: set[str] = set()
                kinds: set[str] = set()
                component_total = Decimal(0)
                for component_index, component in enumerate(components):
                    component_path = f"{source_path}.funding_components[{component_index}]"
                    if not isinstance(component, dict):
                        _fail(component_path, "must be an object")
                    account_id = component.get("account_id")
                    account = accounts.get(account_id) if isinstance(account_id, str) else None
                    if account is None or not (
                        account["owned_by_user"]
                        and account["real_account"]
                        and account["kind"] in {"asset", "liability"}
                    ):
                        _fail(component_path + ".account_id", "must reference an owned real asset or liability account")
                    if account_id in seen_accounts:
                        _fail(component_path + ".account_id", "funding accounts must be distinct")
                    seen_accounts.add(account_id)
                    kinds.add(account["kind"])
                    if component.get("currency") != currency or account["currency"] != currency:
                        _fail(component_path + ".currency", "must match source and account currency")
                    amount_value = component.get("funding_amount")
                    if not isinstance(amount_value, str):
                        _fail(component_path + ".funding_amount", "must be a decimal string")
                    amount = _amount(amount_value, currency, component_path + ".funding_amount", precisions)
                    if amount <= 0:
                        _fail(component_path + ".funding_amount", "must be positive")
                    component_total += amount
                if kinds != {"asset", "liability"}:
                    _fail(source_path + ".funding_components", "must contain one asset and one liability")
                if component_total != total:
                    _fail(source_path + ".funding_components", "must sum to total_amount")
            else:
                known = _amount(payload["known_asset_funding_amount"], currency, source_path + ".known_asset_funding_amount", precisions)
                if known <= 0 or known >= total:
                    _fail(source_path + ".known_asset_funding_amount", "must be positive and less than total_amount")
            continue
        if source["type"] == "reconciliation_evidence":
            _timestamp(payload["observed_at"], source_path + ".observed_at", timezone)
            continue
        if source["type"] in {
            "bank_debit",
            "bank_credit",
            "bank_credit_mirror",
            "lending_agreement",
            "explicit_manual_lending_confirmation",
            "merchant_refund_notice",
            "wallet_credit",
            "combined_refund_statement",
            "wallet_credit_mirror",
        }:
            for field in (
                "observed_at",
                "processor_reported_at",
                "source_observed_at",
                "booking_at",
                "value_at",
            ):
                if field in payload:
                    _timestamp(payload[field], source_path + "." + field, timezone)
            if "amount" in payload:
                _amount(
                    payload["amount"],
                    payload["currency"],
                    source_path + ".amount",
                    precisions,
                )
            continue
        if source["type"] == "imported_transfer_candidate":
            account = accounts.get(payload["account_id"])
            if account is None:
                _fail(source_path + ".account_id", "dangling account reference")
            counter_account = accounts.get(payload["counter_account_id"])
            if counter_account is None:
                _fail(source_path + ".counter_account_id", "dangling account reference")
            if payload["currency"] != account["currency"]:
                _fail(source_path + ".currency", "must match the observed account currency")
            if payload["currency"] != counter_account["currency"]:
                _fail(source_path + ".currency", "must match the counter account currency")
            _amount(payload["amount"], payload["currency"], source_path + ".amount", precisions)
            for field in ("observed_at", "actual_at"):
                _timestamp(payload[field], source_path + "." + field, timezone)
            continue
        if source["type"] == "account_statement":
            account = accounts.get(payload["account_id"])
            if account is None:
                _fail(source_path + ".account_id", "dangling account reference")
            if payload["currency"] != account["currency"]:
                _fail(source_path + ".currency", "must match the observed account currency")
            _amount(payload["amount"], payload["currency"], source_path + ".amount", precisions)
            for field in ("observed_at", "booking_at"):
                _timestamp(payload[field], source_path + "." + field, timezone)
            continue
        if source["type"] == "manual_transaction_confirmation":
            account = accounts.get(payload["account_id"])
            if account is None:
                _fail(source_path + ".account_id", "dangling account reference")
            counter_account = accounts.get(payload["counter_account_id"])
            if counter_account is None:
                _fail(source_path + ".counter_account_id", "dangling account reference")
            if payload["currency"] != account["currency"]:
                _fail(source_path + ".currency", "must match the observed account currency")
            if payload["currency"] != counter_account["currency"]:
                _fail(source_path + ".currency", "must match the counter account currency")
            _amount(payload["amount"], payload["currency"], source_path + ".amount", precisions)
            for field in ("observed_at", "actual_at"):
                _timestamp(payload[field], source_path + "." + field, timezone)
            continue
        if source["type"] == "stored_value_source":
            # RG-10 intake sources carry the frozen v1 source_type token and
            # payload facts; every present reference must resolve.
            _timestamp(payload["observed_at"], source_path + ".observed_at", timezone)
            if "account_id" in payload:
                account = accounts.get(payload["account_id"])
                if account is None:
                    _fail(source_path + ".account_id", "dangling account reference")
                if "currency" in payload and payload["currency"] != account["currency"]:
                    _fail(source_path + ".currency", "must match the observed account currency")
            if "amount" in payload:
                currency = payload.get("currency")
                if currency is None:
                    _fail(source_path + ".amount", "requires the source currency")
                _amount(payload["amount"], currency, source_path + ".amount", precisions)
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
        if candidate["type"] == "lending_collection_credit":
            # RG-08 lending collection candidates are fully validated by
            # _validate_rg08_contract (bank credit + lending agreement source
            # pairing, six confirmation gates, pending/confirmed status
            # sequence); no generic candidate branch applies to them.
            continue
        payload = candidate["payload"]
        if candidate["type"] == "staged_payment":
            if len(candidate["source_ids"]) != 1:
                _fail(
                    candidate_path + ".source_ids",
                    "must contain exactly one staged payment bank fact",
                )
            source = sources[candidate["source_ids"][0]]
            if source["type"] != "staged_payment_bank_fact":
                _fail(
                    candidate_path + ".source_ids[0]",
                    "must reference a staged payment bank fact",
                )
            source_payload = source["payload"]
            common_payload_fields = {
                "payment_role",
                "amount",
                "currency",
                "source_payment_at",
                "evidence_ref",
                "provenance",
                "requires_confirmation",
            }
            payment_role = payload.get("payment_role")
            if (
                candidate["confidence"] == "1.00"
                and payment_role in {"deposit", "final"}
                and set(payload) == common_payload_fields
            ):
                ambiguous = False
            elif (
                candidate["confidence"] == "0.50"
                and payment_role is None
                and "guessed_payment_role" in payload
                and payload["guessed_payment_role"] is None
                and set(payload) == common_payload_fields | {"guessed_payment_role"}
            ):
                ambiguous = True
            else:
                _fail(
                    candidate_path + ".payload.payment_role",
                    "role/null shape and confidence must match the exact staged payment candidate variant",
                )
            if payload["provenance"] != {
                "rule": "staged_payment_bank_fact",
                "rule_version": 1,
            }:
                _fail(
                    candidate_path + ".payload.provenance",
                    "must contain exact staged payment bank fact provenance",
                )
            if payload["requires_confirmation"] != [
                "relation_id",
                "payment_role",
                "category_id",
                "funding_account_id",
            ]:
                _fail(
                    candidate_path + ".payload.requires_confirmation",
                    "must contain the exact staged payment confirmation requirements",
                )
            evidence = indexes["evidence"].get(payload["evidence_ref"])
            if (
                evidence is None
                or evidence["type"] != "staged_payment_bank_payment"
                or evidence["source_ids"] != candidate["source_ids"]
            ):
                _fail(
                    candidate_path + ".payload.evidence_ref",
                    "must reference the source's exact staged payment evidence",
                )
            if "source_payment_at" not in source_payload:
                _fail(
                    candidate_path + ".payload.source_payment_at",
                    "candidate source must preserve source_payment_at",
                )
            source_time = source_payload["source_payment_at"]
            if (
                payload["source_payment_at"] != source_time
                or evidence["payload"].get("source_payment_at") != source_time
            ):
                _fail(
                    candidate_path + ".payload.source_payment_at",
                    "must byte-equal the source and evidence payment time",
                )
            currency = payload["currency"]
            candidate_amount = _amount(
                payload["amount"],
                currency,
                candidate_path + ".payload.amount",
                precisions,
            )
            source_amount = _amount(
                source_payload["amount"],
                source_payload["currency"],
                candidate_path + ".source.amount",
                precisions,
            )
            if (
                candidate_amount <= 0
                or currency != source_payload["currency"]
                or candidate_amount != abs(source_amount)
            ):
                _fail(
                    candidate_path + ".payload.amount",
                    "positive amount and currency must exactly match the staged payment source",
                )
            matching_payments = []
            for entity in domain_entities.values():
                if entity["type"] != "installment_payment":
                    continue
                entity_payload = entity["payload"]
                entity_amount = _amount(
                    entity_payload["amount"],
                    entity_payload["currency"],
                    candidate_path + ".payload.payment_role",
                    precisions,
                )
                entity_source_time = entity_payload.get(
                    "source_payment_at", entity_payload["actual_payment_at"]
                )
                if (
                    entity_amount == candidate_amount
                    and entity_payload["currency"] == currency
                    and entity_source_time == source_time
                ):
                    matching_payments.append(entity)
            if (
                not ambiguous
                and len(matching_payments) == 1
                and matching_payments[0]["payload"]["role"] != payment_role
            ):
                _fail(
                    candidate_path + ".payload.payment_role",
                    "known candidate role must match the unique installment facts",
                )
            payment_id = evidence["payload"].get("payment_id")
            payment = domain_entities.get(payment_id) if payment_id else None
            if payment_id is not None:
                if payment is None or payment["type"] != "installment_payment":
                    _fail(
                        candidate_path + ".payload.evidence_ref",
                        "staged payment evidence must bind an installment payment",
                    )
                payment_payload = payment["payload"]
                payment_amount = _amount(
                    payment_payload["amount"],
                    payment_payload["currency"],
                    candidate_path + ".payload.evidence_ref",
                    precisions,
                )
                if (
                    candidate_amount != payment_amount
                    or currency != payment_payload["currency"]
                    or source_time != payment_payload["actual_payment_at"]
                    or (not ambiguous and payment_role != payment_payload["role"])
                ):
                    _fail(
                        candidate_path + ".payload.evidence_ref",
                        "must bind the exact installment amount, currency, payment time, and known role",
                    )
            history_statuses = [item["status"] for item in history]
            if history_statuses not in (
                ["pending_confirmation"],
                ["pending_confirmation", "confirmed"],
            ):
                _fail(
                    candidate_path + ".status_history",
                    "must be pending or append confirmed exactly once",
                )
            confirmation_owners = [
                confirmation
                for confirmation in state["confirmations"]
                if confirmation["type"] == "candidate_confirmation"
                and confirmation["subject"]["kind"] == "candidate"
                and confirmation["subject"]["id"] == candidate["id"]
            ]
            confirmed = history_statuses[-1] == "confirmed"
            if len(confirmation_owners) != (1 if confirmed else 0):
                _fail(
                    candidate_path + ".status_history",
                    "candidate confirmation ownership must be absent while pending and exact once confirmed",
                )
            if not confirmed and payment_id is not None:
                _fail(
                    candidate_path + ".payload.evidence_ref",
                    "pending candidate evidence must remain unbound",
                )
            if confirmed:
                if payment is None:
                    _fail(
                        candidate_path + ".payload.evidence_ref",
                        "confirmed candidate evidence must bind the exact installment payment",
                    )
                confirmation_operation = operations.get(confirmation_owners[0]["operation_id"], {})
                confirmation_input = confirmation_operation.get("input", {})
                payment_payload = payment["payload"]
                expected_source_time = (
                    payment_payload.get("source_payment_at")
                    if confirmation_input
                    else payment_payload.get("actual_payment_at")
                )
                if (
                    _amount(payment_payload["amount"], payment_payload["currency"], candidate_path + ".status_history", precisions) != candidate_amount
                    or payment_payload["currency"] != currency
                    or payment_payload["role"] != (payment_role if not ambiguous else confirmation_input.get("payment_role"))
                    or expected_source_time != source_time
                ):
                    _fail(
                        candidate_path + ".status_history",
                        "confirmed candidate installment must preserve the candidate source payment time and exact payment facts",
                    )
                relation_owners = [
                    relation
                    for relation in state["relations"]
                    if relation["type"] == "staged_payment"
                    and any(
                        ref["kind"] == "domain_entity" and ref["id"] == payment_id
                        for ref in relation["member_refs"]
                    )
                ]
                if len(relation_owners) != 1:
                    _fail(
                        candidate_path + ".payload.evidence_ref",
                        "confirmed candidate installment must belong to exactly one staged payment relation",
                    )
                transaction_id = payment_payload["transaction_id"]
                transaction = transactions.get(transaction_id)
                if transaction is None or transaction["type"] != "expense":
                    _fail(
                        candidate_path + ".payload.evidence_ref",
                        "confirmed candidate installment must bind an expense transaction",
                    )
                confirmation_id = confirmation_owners[0]["id"]
                bound_versions = [
                    version
                    for version in state["transaction_versions"]
                    if version.get("confirmation_id") == confirmation_id
                ]
                if (
                    len(bound_versions) != 1
                    or bound_versions[0]["transaction_id"] != transaction_id
                ):
                    _fail(
                        candidate_path + ".status_history",
                        "candidate confirmation must own exactly one version of the installment transaction",
                    )
                evidence_links = links_by_evidence_id[evidence["id"]]
                expected_link = {
                    "evidence_id": evidence["id"],
                    "target_kind": "posting",
                    "target_id": payment_payload["asset_posting_id"],
                    "role": "payment_asset_posting",
                }
                if len(evidence_links) != 1 or any(
                    evidence_links[0][key] != value
                    for key, value in expected_link.items()
                ):
                    _fail(
                        candidate_path + ".status_history",
                        "confirmed candidate must own one exact payment_asset evidence link",
                    )
            continue
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
            if history_statuses[-1] == "confirmed":
                transaction_id = payload.get("transaction_id")
                transaction = transactions.get(transaction_id) if transaction_id is not None else None
                if transaction is None or transaction["type"] != "account_transfer":
                    _fail(candidate_path + ".payload.transaction_id", "confirmed transfer candidate must bind its formal account_transfer")
            elif "transaction_id" in payload:
                _fail(candidate_path + ".payload.transaction_id", "pending transfer candidate cannot bind a formal transaction")
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
        if candidate["type"] == "mixed_payment":
            if len(candidate["source_ids"]) != 1:
                _fail(candidate_path + ".source_ids", "must contain exactly one mixed payment source reference")
            source = sources.get(candidate["source_ids"][0])
            if source is None or source["type"] != "mixed_payment":
                _fail(candidate_path + ".source_ids[0]", "must reference a mixed payment source")
            payload = candidate["payload"]
            source_payload = source["payload"]
            if not isinstance(source_payload, dict) or "completeness" not in source_payload:
                _fail(candidate_path + ".source_ids[0]", "must reference a mixed payment intake source")
            expected_confidence, expected_payload = _mixed_payment_candidate_contract(
                source_payload,
                candidate_path + ".source",
            )
            transaction_id = payload.get("transaction_id")
            if transaction_id is not None:
                expected_payload = {**expected_payload, "transaction_id": transaction_id}
            if candidate["confidence"] != expected_confidence:
                _fail(candidate_path + ".confidence", "must exactly match the mixed payment source contract")
            if payload != expected_payload:
                _fail(candidate_path + ".payload", "must exactly match the mixed payment source and provenance contract")
            history_statuses = [item["status"] for item in history]
            if history_statuses not in (
                ["pending_confirmation"],
                ["pending_confirmation", "confirmed"],
            ):
                _fail(candidate_path + ".status_history", "must be pending or confirmed exactly once")
            if payload.get("evidence_refs") != [source_payload["evidence_id"]]:
                _fail(candidate_path + ".payload.evidence_refs", "must reference the mixed payment source evidence")
            confirmation_owners = [
                confirmation
                for confirmation in state["confirmations"]
                if confirmation["type"] == "candidate_confirmation"
                and confirmation["subject"]["kind"] == "candidate"
                and confirmation["subject"]["id"] == candidate["id"]
            ]
            expected_confirmation_count = 1 if history_statuses[-1] == "confirmed" else 0
            if len(confirmation_owners) != expected_confirmation_count:
                _fail(candidate_path + ".status_history", "candidate confirmation ownership must match status")
            if history_statuses[-1] == "pending_confirmation":
                if "transaction_id" in payload:
                    _fail(candidate_path + ".payload.transaction_id", "pending candidate cannot bind a formal transaction")
            else:
                transaction = transactions.get(transaction_id) if transaction_id is not None else None
                if transaction is None or transaction["type"] != "expense":
                    _fail(candidate_path + ".payload.transaction_id", "confirmed mixed payment candidate must bind an expense transaction")
                confirmation = confirmation_owners[0]
                created_versions = [
                    version
                    for version in state["transaction_versions"]
                    if version["transaction_id"] == transaction_id
                    and version.get("confirmation_id") == confirmation["id"]
                ]
                if len(created_versions) != 1:
                    _fail(
                        candidate_path + ".payload.transaction_id",
                        "candidate confirmation must own exactly one version of its bound transaction",
                    )
                version = created_versions[0]
                observed_at = source_payload["observed_at"]
                if any(
                    version.get(field) != observed_at
                    for field in ("occurred_at", "statistics_at", "effective_at")
                ):
                    _fail(candidate_path + ".payload.transaction_id", "candidate-confirmed version times must match source observed_at")
                if "created_at" in version:
                    _fail(candidate_path + ".payload.transaction_id", "source observed_at cannot synthesize created_at")
            continue
        if candidate["type"] == "merged_payment":
            source_objects = [sources[source_id] for source_id in candidate["source_ids"]]
            bank_sources = [source for source in source_objects if source["type"] == "merged_payment_bank_fact"]
            item_sources = [source for source in source_objects if source["type"] == "merged_payment_item_fact"]
            if len(bank_sources) != 1 or len(item_sources) != 2:
                _fail(candidate_path + ".source_ids", "must contain one bank fact and two item facts")
            payload = candidate["payload"]
            bank_source = bank_sources[0]
            if payload["bank_source_id"] != bank_source["id"]:
                _fail(candidate_path + ".payload.bank_source_id", "must match the bank source")
            if set(payload["item_source_ids"]) != {source["id"] for source in item_sources}:
                _fail(candidate_path + ".payload.item_source_ids", "must match both item sources")
            bank_amount = _amount(
                bank_source["payload"]["amount"],
                bank_source["payload"]["currency"],
                candidate_path + ".payload.payment_total",
                precisions,
            )
            total = _amount(
                payload["payment_total"], payload["currency"], candidate_path + ".payload.payment_total", precisions
            )
            if total != -bank_amount or payload["currency"] != bank_source["payload"]["currency"]:
                _fail(candidate_path + ".payload.payment_total", "must match the bank debit")
            proposals = {proposal["source_id"]: proposal for proposal in payload["item_proposals"]}
            item_total = Decimal(0)
            for item_source in item_sources:
                source_payload = item_source["payload"]
                proposal = proposals.get(item_source["id"])
                if proposal is None:
                    _fail(candidate_path + ".payload.item_proposals", "must cover both item sources")
                expected = {
                    "item_id": source_payload["item_id"],
                    "amount": source_payload["amount"],
                    "currency": source_payload["currency"],
                    "suggested_category_id": source_payload["suggested_category_id"],
                    "source_id": item_source["id"],
                    "evidence_id": source_payload["evidence_id"],
                }
                if proposal != expected:
                    _fail(candidate_path + ".payload.item_proposals", "must exactly copy item source proposals")
                item_total += _amount(
                    source_payload["amount"], source_payload["currency"], candidate_path + ".payload.item_proposals", precisions
                )
            if item_total != total:
                _fail(candidate_path + ".payload.item_proposals", "item proposals must close to payment total")
            expected_evidence = {
                bank_source["payload"]["evidence_id"],
                *(source["payload"]["evidence_id"] for source in item_sources),
            }
            if set(payload["evidence_refs"]) != expected_evidence:
                _fail(candidate_path + ".payload.evidence_refs", "must match all three source evidence IDs")
            statuses = [event["status"] for event in candidate["status_history"]]
            if statuses not in (["pending_confirmation"], ["pending_confirmation", "confirmed"]):
                _fail(candidate_path + ".status_history", "must be pending or append one confirmed status")
            confirmations = [
                confirmation
                for confirmation in state["confirmations"]
                if confirmation["type"] == "candidate_confirmation"
                and confirmation["subject"]["kind"] == "candidate"
                and confirmation["subject"]["id"] == candidate["id"]
            ]
            confirmed = statuses[-1] == "confirmed"
            if len(confirmations) != (1 if confirmed else 0):
                _fail(candidate_path + ".status_history", "candidate confirmation ownership must match status")
            if confirmed:
                transaction_id = payload.get("transaction_id")
                transaction = transactions.get(transaction_id) if isinstance(transaction_id, str) else None
                if transaction is None or transaction["type"] != "expense":
                    _fail(candidate_path + ".payload.transaction_id", "confirmed candidate must bind an expense transaction")
            elif "transaction_id" in payload:
                _fail(candidate_path + ".payload.transaction_id", "pending candidate cannot bind a transaction")
            continue
        if candidate["type"] == "refund_credit":
            statuses = [event["status"] for event in history]
            if statuses not in (["pending_confirmation"], ["pending_confirmation", "confirmed"]):
                _fail(
                    candidate_path + ".status_history",
                    "refund credit must be pending or append one confirmed status",
                )
            expected_effects = [0] if len(history) == 1 else [0, 1]
            if [event["formal_effect_count"] for event in history] != expected_effects:
                _fail(
                    candidate_path + ".status_history",
                    "refund credit formal effects must be zero then one",
                )
            for history_index, event in enumerate(history):
                _timestamp(
                    event["occurred_at"],
                    f"{candidate_path}.status_history[{history_index}].occurred_at",
                    timezone,
                )
            continue
        if candidate["type"] == "omitted_real_transaction_and_adjustment_explanation":
            statuses = [event["status"] for event in history]
            if statuses not in (["pending_confirmation"], ["pending_confirmation", "confirmed"]):
                _fail(
                    candidate_path + ".status_history",
                    "imported transfer candidate must be pending or append one confirmed status",
                )
            if len(candidate["source_ids"]) != 1:
                _fail(
                    candidate_path + ".source_ids",
                    "must contain exactly one imported transfer source reference",
                )
            source = sources[candidate["source_ids"][0]]
            if source["type"] != "imported_transfer_candidate":
                _fail(
                    candidate_path + ".source_ids[0]",
                    "must reference an imported_transfer_candidate source",
                )
            source_payload = source["payload"]
            confirmed = statuses[-1] == "confirmed"
            if confirmed:
                event = history[-1]
                adjustment = domain_entities.get(event.get("adjustment_id"))
                if adjustment is None or adjustment["type"] != "balance_adjustment":
                    _fail(candidate_path + ".status_history", "confirmed imported candidate must retain its adjustment provenance")
                if not isinstance(event.get("confirmation_request_id"), str):
                    _fail(candidate_path + ".status_history", "confirmed imported candidate must retain its confirmation request")
            elif payload["proposed_transaction_id"] is not None:
                _fail(candidate_path + ".payload.proposed_transaction_id", "pending imported candidate cannot bind a transaction")
            for field in ("proposed_target_account_id", "proposed_counter_account_id"):
                if payload[field] not in accounts:
                    _fail(candidate_path + f".payload.{field}", "dangling account reference")
            if payload["proposed_currency"] != source_payload["currency"]:
                _fail(candidate_path + ".payload.proposed_currency", "must match the source currency")
            if payload["proposed_actual_at"] != source_payload["actual_at"]:
                _fail(candidate_path + ".payload.proposed_actual_at", "must match the source actual time")
            if Decimal(payload["proposed_allocation_amount"]) != Decimal(source_payload["amount"]):
                _fail(
                    candidate_path + ".payload.proposed_allocation_amount",
                    "must match the source amount",
                )
            continue
        if candidate["type"] == "stored_value_candidate":
            # RG-10 imported candidates stay pending_confirmation with zero
            # formal effect; their source binds one stored-value intake source
            # and the payload reproduces the frozen v1 candidate facts.
            source = sources.get(candidate["source_ids"][0])
            if source is None or source["type"] != "stored_value_source":
                _fail(
                    candidate_path + ".source_ids[0]",
                    "must reference a stored_value_source",
                )
            expected_source_type = {
                "stored_value_recharge": "imported_stored_value_recharge",
                "stored_value_spend": "imported_stored_value_spend",
            }[payload["candidate_type"]]
            if source["payload"]["source_type"] != expected_source_type:
                _fail(
                    candidate_path + ".source_ids[0]",
                    "must reference the matching stored-value import source",
                )
            if [event["status"] for event in history] != ["pending_confirmation"]:
                _fail(
                    candidate_path + ".status_history",
                    "RG-10 imported candidates must stay pending_confirmation",
                )
            if payload["currency"] != "CNY":
                _fail(candidate_path + ".payload.currency", "must be CNY")
            for field in ("paid_amount", "credited_amount", "bonus_amount", "amount"):
                if field in payload:
                    amount = _decimal(payload[field], candidate_path + f".payload.{field}")
                    if amount <= 0:
                        _fail(candidate_path + f".payload.{field}", "must be positive")
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
        if operation is None or (
            case_id != "RG-08" and operation["root_id"] != state["root_id"]
        ):
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
        if evidence["type"] == "staged_payment_bank_payment":
            if (
                len(evidence["source_ids"]) != 1
                or sources[evidence["source_ids"][0]]["type"]
                != "staged_payment_bank_fact"
            ):
                _fail(
                    evidence_path + ".source_ids",
                    "must reference exactly one staged payment bank fact",
                )
            continue
        _timestamp(evidence["payload"]["observed_at"], evidence_path + ".payload.observed_at", timezone)
        if evidence["type"] == "transfer_record":
            if len(evidence["source_ids"]) != 1:
                _fail(evidence_path + ".source_ids", "must contain exactly one transfer source reference")
            source = sources[evidence["source_ids"][0]]
            if source["type"] not in {"account_transfer", "account_credit_observation"}:
                _fail(evidence_path + ".source_ids[0]", "must reference a transfer or account-credit source")
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
            "counterparty_lending_relationship": "domain_entity",
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
            "payment_asset_posting": {"payment_asset"},
            "destination_asset_posting": {
                "destination_asset",
                "transfer_principal_in",
                "lending_principal_in",
            },
            "funding_asset_posting": {"funding_asset", "lending_principal_out"},
            "bank_payment_posting": {"bank_payment"},
            "stored_value_asset_posting": {"stored_value_asset"},
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
        if (
            link["role"] in posting_role_targets
            and target.get("role") not in posting_role_targets[link["role"]]
        ):
            _fail(
                link_path + ".target_id",
                f"must target posting role in {sorted(posting_role_targets[link['role']])}",
            )
        if link["role"] == "refund_relationship":
            if target.get("type") != "refund":
                _fail(
                    link_path + ".target_id",
                    "must target the refund relation",
                )
        if link["role"] == "counterparty_lending_relationship":
            if target.get("type") != "lending_position":
                _fail(link_path + ".target_id", "must target a lending position")
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
            "payment_asset_posting": {
                "bank_payment",
                "staged_payment_bank_payment",
                "asset_debit",
            },
            "item_allocation_fact": {"item_receipt"},
            "stored_value_asset_posting": {"merchant_stored_value_credit"},
            "stored_value_lot_fact": {"merchant_stored_value_credit"},
            "stored_value_bonus_component": {"merchant_stored_value_credit"},
            "stored_value_expiry_confirmation": {"confirmed_actual_expiry"},
            "refund_relationship": {"refund_notice", "combined_refund_statement"},
            "destination_asset_posting": {"transfer_record", "asset_credit", "combined_refund_statement", "asset_credit_mirror"},
            "funding_asset_posting": {"asset_debit"},
            "counterparty_lending_relationship": {"lending_agreement"},
        }
        if (
            link["role"] in evidence_role_types
            and evidence["type"] not in evidence_role_types[link["role"]]
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
            reconciliation_evidence = (
                len(evidence["source_ids"]) == 1
                and indexes["sources"][evidence["source_ids"][0]]["type"] == "reconciliation_evidence"
            )
            merged_item_source = (
                len(evidence["source_ids"]) == 1
                and indexes["sources"][evidence["source_ids"][0]]["type"] == "merged_payment_item_fact"
            )
            expected_role = "real_account_posting" if reconciliation_evidence else "item_allocation_fact"
            expected_count = 1
            if merged_item_source:
                source = indexes["sources"][evidence["source_ids"][0]]
                matching_allocations = [
                    entity
                    for entity in state["domain_entities"]
                    if entity["type"] == "item_allocation"
                    and entity["payload"].get("source_item_id") == source["payload"]["item_id"]
                ]
                expected_count = 1 if matching_allocations else 0
                if links and (
                    links[0]["target_id"] not in {item["id"] for item in matching_allocations}
                    or matching_allocations[0]["payload"]["amount"] != source["payload"]["amount"]
                    or matching_allocations[0]["payload"]["currency"] != source["payload"]["currency"]
                ):
                    _fail(
                        path + ".evidence_links",
                        f"item receipt evidence {evidence['id']!r} must match its exact item allocation",
                    )
            if len(links) != expected_count or any(link["role"] != expected_role for link in links):
                _fail(
                    path + ".evidence_links",
                    f"item receipt evidence {evidence['id']!r} must link exactly {expected_count} {expected_role}",
                )
        if evidence["type"] == "bank_payment" and case_id != "RG-10":
            # RG-10 reuses the bank_payment evidence token for the recharge's
            # frozen bank source; its registered link role is
            # bank_payment_posting (no payment_asset_posting binding).
            source = indexes["sources"][evidence["source_ids"][0]]
            matching_postings = [
                posting
                for posting in state["postings"]
                if posting.get("role") == "payment_asset"
                and posting["amount"] == source["payload"]["amount"]
                and posting["currency"] == source["payload"]["currency"]
            ]
            expected_count = 1 if matching_postings else 0
            if (
                len(links) != expected_count
                or any(link["role"] != "payment_asset_posting" for link in links)
                or (links and links[0]["target_id"] != matching_postings[0]["id"])
            ):
                _fail(
                    path + ".evidence_links",
                    f"bank payment evidence {evidence['id']!r} must link exactly {expected_count} payment_asset_posting",
                )
        if evidence["type"] == "item_summary" and any(
            link["role"] == "item_allocation_fact" for link in links
        ):
            _fail(
                path + ".evidence_links",
                f"item summary evidence {evidence['id']!r} cannot prove an item allocation",
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
        if entity["type"] == "reconciliation_match":
            posting = indexes["postings"].get(payload["posting_id"])
            evidence = indexes["evidence"].get(payload["evidence_id"])
            if posting is None:
                _fail(entity_path + ".posting_id", "dangling posting reference")
            account = accounts[posting["account_id"]]
            if not (
                posting["reconciliation_eligible"]
                and account["reconciliation_eligible"]
                and account["owned_by_user"]
                and account["real_account"]
            ):
                _fail(entity_path + ".posting_id", "must reference an eligible owned real account posting")
            if evidence is None:
                _fail(entity_path + ".evidence_id", "dangling evidence reference")
            direct_evidence_link = any(
                link["evidence_id"] == evidence["id"]
                and link["target_kind"] == "posting"
                and link["target_id"] == posting["id"]
                for link in state["evidence_links"]
            )
            inherited_evidence_link = any(
                replacement["type"] == "posting_replacement"
                and replacement["to"]["id"] == posting["id"]
                and replacement["payload"]["reconciliation_effect"] in {"preserved", "invalidated"}
                and any(
                    link["evidence_id"] == evidence["id"]
                    and link["target_kind"] == "posting"
                    and link["target_id"] == replacement["from"]["id"]
                    for link in state["evidence_links"]
                )
                for replacement in state["audit_links"]
            )
            if not direct_evidence_link and not inherited_evidence_link:
                _fail(entity_path + ".evidence_id", "must have an exact evidence link to its posting")
            history = payload["status_history"]
            _unique_index(history, entity_path + ".status_history")
            if [item["sequence"] for item in history] != list(range(1, len(history) + 1)):
                _fail(entity_path + ".status_history", "sequence must be contiguous and ordered from 1")
            statuses = [item["status"] for item in history]
            if statuses not in (["matched"], ["matched", "invalidated"]):
                _fail(entity_path + ".status_history", "must start matched and may append one invalidated event")
            for history_index, event in enumerate(history):
                _timestamp(event["at"], f"{entity_path}.status_history[{history_index}].at", timezone)
        elif entity["type"] == "target_balance_observation":
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
            binding_fields = {"source_item_id", "source_id", "evidence_id"}
            present_bindings = binding_fields.intersection(payload)
            if present_bindings and present_bindings != binding_fields:
                _fail(entity_path, "import source bindings must be present as a complete group")
            if present_bindings:
                source = sources.get(payload["source_id"])
                evidence = indexes["evidence"].get(payload["evidence_id"])
                if source is None or source.get("type") != "merged_payment_item_fact":
                    _fail(entity_path + ".source_id", "must reference a merged payment item source")
                if source["payload"]["item_id"] != payload["source_item_id"]:
                    _fail(entity_path + ".source_item_id", "must match the item source")
                if source["payload"]["evidence_id"] != payload["evidence_id"]:
                    _fail(entity_path + ".evidence_id", "must match the item source evidence")
                if evidence is None or evidence["source_ids"] != [source["id"]]:
                    _fail(entity_path + ".evidence_id", "must bind evidence owned by the item source")
                if payload.get("source_observed_at") != source["payload"]["observed_at"]:
                    _fail(entity_path + ".source_observed_at", "must retain immutable source time")
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
            binding_fields = {"source_item_id", "source_id", "evidence_id"}
            present_bindings = binding_fields.intersection(payload)
            if present_bindings and present_bindings != binding_fields:
                _fail(entity_path, "import source bindings must be present as a complete group")
            if present_bindings:
                for field in binding_fields:
                    if payload[field] != expected.get(field):
                        _fail(entity_path + f".{field}", "must match the consumption_record source binding")
        elif entity["type"] == "stored_value_lot":
            recharge_transaction_id = payload["recharge_transaction_id"]
            if recharge_transaction_id is not None:
                recharge = transactions.get(recharge_transaction_id)
                if recharge is None or recharge["type"] != "stored_value_recharge":
                    _fail(
                        entity_path + ".recharge_transaction_id",
                        "must reference a stored_value_recharge transaction",
                    )
                version = indexes["transaction_versions"][recharge["current_version_id"]]
                if payload["loaded_at"] != version["occurred_at"]:
                    _fail(
                        entity_path + ".loaded_at",
                        "must match the recharge current version occurred_at",
                    )
            # Synthetic lots (multi-lot base, merchant-allocation baseline) own no
            # recharge transaction; their loaded_at stays a frozen v1 fact.
            face_value = _amount(
                payload["face_value"], payload["currency"], entity_path + ".face_value", precisions
            )
            if face_value <= 0:
                _fail(entity_path + ".face_value", "must be positive")
            _timestamp(payload["loaded_at"], entity_path + ".loaded_at", timezone)
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

    active_match_owners: dict[str, str] = {}
    active_matches_by_posting: dict[str, dict[str, Any]] = {}
    for entity in state["domain_entities"]:
        if entity["type"] != "reconciliation_match":
            continue
        payload = entity["payload"]
        if payload["status_history"][-1]["status"] == "matched":
            key = payload["posting_id"]
            prior = active_match_owners.get(key)
            if prior is not None:
                _fail(path + ".domain_entities", f"active reconciliation match {key!r} is already owned by {prior!r}")
            active_match_owners[key] = entity["id"]
            active_matches_by_posting[key] = entity

    audit_rules = {
        "adjustment_transaction": ("balance_adjustment", "balance_adjustment"),
        "explanation_transaction": ("explanation_allocation", "account_transfer"),
        "allocation_reversal": ("explanation_allocation", "balance_adjustment_reversal"),
        "periodic_allocation_recognition": (
            "periodic_allocation_installment", "prepaid_recognition",
        ),
    }
    for index, link in enumerate(state["audit_links"]):
        link_path = f"{path}.audit_links[{index}]"
        if link["type"] in {"mirror_of_evidence_id", "merged_into_evidence_link_id"}:
            expected_kind = (
                "evidence" if link["type"] == "mirror_of_evidence_id" else "evidence_link"
            )
            if link["from"]["kind"] != expected_kind or link["to"]["kind"] != expected_kind:
                _fail(link_path, f"{link['type']} endpoints must both be {expected_kind} references")
            source = _resolve_ref(state, indexes, operations, expected_kind, link["from"]["id"], link_path + ".from.id")
            target = _resolve_ref(state, indexes, operations, expected_kind, link["to"]["id"], link_path + ".to.id")
            if source["id"] == target["id"]:
                _fail(link_path, "lending mirror/merge audit endpoints must be distinct")
            continue
        if link["type"] == "posting_replacement":
            if link["from"]["kind"] != "posting" or link["to"]["kind"] != "posting":
                _fail(link_path, "posting replacement endpoints must both be postings")
            old = indexes["postings"].get(link["from"]["id"])
            new = indexes["postings"].get(link["to"]["id"])
            if old is None or new is None:
                _fail(link_path, "posting replacement endpoints must exist")
            posting_to_version = {
                posting_id: version
                for version in versions.values()
                for posting_id in indexes["posting_sets"][version["posting_set_id"]]["posting_ids"]
            }
            old_version = posting_to_version.get(old["id"])
            new_version = posting_to_version.get(new["id"])
            if (
                old_version is None
                or new_version is None
                or old_version["transaction_id"] != new_version["transaction_id"]
                or new_version["version_number"] != old_version["version_number"] + 1
            ):
                _fail(link_path, "replacement endpoints must be consecutive versions of one transaction")
            effect = link["payload"]["reconciliation_effect"]
            facts = ("account_id", "amount", "currency", "role", "category_id")
            same_facts = all(old.get(field) == new.get(field) for field in facts)
            old_account = accounts[old["account_id"]]
            is_real = old_account["owned_by_user"] and old_account["real_account"]
            if effect == "not_applicable" and is_real:
                _fail(link_path + ".payload.reconciliation_effect", "not_applicable is limited to non-real postings")
            if effect == "preserved" and not same_facts:
                _fail(link_path + ".payload.reconciliation_effect", "preserved requires unchanged posting facts")
            if effect == "preserved":
                predecessor = active_matches_by_posting.get(old["id"])
                successor = active_matches_by_posting.get(new["id"])
                if (
                    predecessor is None
                    or successor is None
                    or predecessor["payload"]["evidence_id"] != successor["payload"]["evidence_id"]
                ):
                    _fail(link_path + ".payload.reconciliation_effect", "preserved requires an active predecessor and inherited successor match for the same evidence")
            if effect == "invalidated" and (not is_real or same_facts):
                _fail(link_path + ".payload.reconciliation_effect", "invalidated requires a changed reconciliation-relevant real posting")
            continue
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
        if link["type"] == "periodic_allocation_recognition":
            continue
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


def _validate_rg07_contract(
    state: dict[str, Any],
    path: str,
    indexes: dict[str, dict[str, dict[str, Any]]],
    operations: dict[str, dict[str, Any]],
    current: dict[str, tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]],
    precisions: dict[str, int],
    timezone: ZoneInfo,
) -> None:
    accounts = indexes["catalog_accounts"]
    categories = indexes["catalog_categories"]
    transactions = indexes["transactions"]
    relations = indexes["relations"]
    evidence = indexes["evidence"]
    evidence_links = indexes["evidence_links"]
    sources = indexes["sources"]

    relationships = [
        entity
        for entity in state["domain_entities"]
        if entity["type"] == "refund_relationship"
    ]
    relationships_by_relation: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for entity in relationships:
        relationships_by_relation[entity["payload"]["relation_id"]].append(entity)

    received_by_original: dict[str, Decimal] = defaultdict(Decimal)
    original_caps: dict[str, Decimal] = {}
    for relation_index, relation in enumerate(state["relations"]):
        if relation["type"] != "refund":
            continue
        relation_path = f"{path}.relations[{relation_index}]"
        members = [transactions[ref["id"]] for ref in relation["member_refs"]]
        originals = [item for item in members if item["type"] == "expense"]
        receipts = [item for item in members if item["type"] == "refund_receipt"]
        if len(originals) != 1 or len(receipts) > 1 or len(members) != len(originals) + len(receipts):
            _fail(
                relation_path + ".member_refs",
                "refund membership must contain one expense and at most one refund receipt independent of array order",
            )
        owners = relationships_by_relation.get(relation["id"], [])
        if len(owners) != 1:
            _fail(
                relation_path,
                "refund relation must own exactly one refund_relationship domain entity",
            )
        relationship = owners[0]
        payload = relationship["payload"]
        relationship_path = (
            f"{path}.domain_entities[{state['domain_entities'].index(relationship)}].payload"
        )
        original = originals[0]
        if payload["original_transaction_id"] != original["id"]:
            _fail(
                relationship_path + ".original_transaction_id",
                "must identify the relation's original expense",
            )
        original_entry = current.get(original["id"])
        if original_entry is None:
            _fail(
                relationship_path + ".original_transaction_id",
                "must identify a current effective expense",
            )
        original_expense_postings = [
            posting
            for posting in original_entry[2]
            if posting.get("role") == "expense"
            and _decimal(posting["amount"], relationship_path + ".category_id") > 0
        ]
        if len(original_expense_postings) != 1:
            _fail(
                relationship_path + ".original_transaction_id",
                "original expense must have exactly one positive expense posting",
            )
        original_posting = original_expense_postings[0]
        category = categories.get(payload["category_id"])
        if (
            category is None
            or category["parent_id"] is None
            or not category["active"]
            or category["posting_account_id"] != original_posting["account_id"]
        ):
            _fail(
                relationship_path + ".category_id",
                "must inherit the original exact active secondary expense category",
            )
        original_amount = _amount(
            original_posting["amount"],
            original_posting["currency"],
            relationship_path + ".original_transaction_id",
            precisions,
        )
        requested = _amount(
            payload["requested_amount"],
            payload["currency"],
            relationship_path + ".requested_amount",
            precisions,
        )
        received = _amount(
            payload["received_amount"],
            payload["currency"],
            relationship_path + ".received_amount",
            precisions,
        )
        if requested <= 0 or received < 0:
            _fail(relationship_path, "refund amounts must be positive requested and non-negative received")
        if payload["currency"] != original_posting["currency"]:
            _fail(relationship_path + ".currency", "must match the original expense currency")
        original_caps[original["id"]] = original_amount
        received_by_original[original["id"]] += received

        for time_name, time_value in payload["times"].items():
            _timestamp(time_value, relationship_path + ".times." + time_name, timezone)
        history = payload["state_history"]
        if [item["sequence"] for item in history] != list(range(1, len(history) + 1)):
            _fail(relationship_path + ".state_history", "sequence must be contiguous from 1")
        states = [item["state"] for item in history]
        if states not in (
            ["received"],
            ["requested"],
            ["requested", "approved"],
            ["requested", "approved", "processing"],
            ["requested", "approved", "processing", "received"],
        ):
            _fail(
                relationship_path + ".state_history",
                "refund lifecycle must follow requested -> approved -> processing -> received; imported receipts may begin received",
            )
        for history_index, event in enumerate(history):
            event_path = f"{relationship_path}.state_history[{history_index}]"
            _timestamp(event["occurred_at"], event_path + ".occurred_at", timezone)
            received_event = event["state"] == "received"
            if received_event != (event["formal_effect_count"] == 1):
                _fail(event_path + ".formal_effect_count", "received owns one formal effect; other states own zero")
            if received_event:
                if event["transaction_id"] is None:
                    _fail(event_path + ".transaction_id", "received must identify the receipt transaction")
            elif event["transaction_id"] is not None or event["formal_effect_count"] != 0:
                _fail(event_path, "non-received history cannot identify a formal transaction")
        latest_state = history[-1]["state"]
        receipt = receipts[0] if receipts else None
        if receipt is None:
            if (
                payload["refund_transaction_id"] is not None
                or payload["destination_account_id"] is not None
                or received != 0
                or latest_state == "received"
            ):
                _fail(relationship_path, "pre-receipt relationship cannot own received facts")
            continue

        if (
            payload["refund_transaction_id"] != receipt["id"]
            or payload["destination_account_id"] is None
            or received <= 0
            or latest_state != "received"
            or history[-1]["transaction_id"] != receipt["id"]
        ):
            _fail(relationship_path, "received relationship must exactly own its receipt and destination")
        for required_time in ("confirmed_at", "arrived_at"):
            if required_time not in payload["times"]:
                _fail(relationship_path + ".times", f"received relationship requires {required_time}")
        destination = accounts.get(payload["destination_account_id"])
        if destination is None or not (
            destination["owned_by_user"]
            and destination["real_account"]
            and destination["reconciliation_eligible"]
            and destination["kind"] == "asset"
            and destination["currency"] == payload["currency"]
        ):
            _fail(
                relationship_path + ".destination_account_id",
                "must identify an owned reconciliation-eligible real asset in the refund currency",
            )
        receipt_entry = current.get(receipt["id"])
        if receipt_entry is None:
            _fail(relationship_path + ".refund_transaction_id", "refund receipt must be current")
        receipt_version, receipt_postings = receipt_entry[1], receipt_entry[2]
        if any(
            receipt_version[field] != payload["times"]["arrived_at"]
            for field in ("occurred_at", "statistics_at", "effective_at")
        ):
            _fail(relationship_path + ".times.arrived_at", "must own all receipt economic time roles")
        by_role = {posting.get("role"): posting for posting in receipt_postings}
        if set(by_role) != {"destination_asset", "expense"} or len(receipt_postings) != 2:
            _fail(relationship_path + ".refund_transaction_id", "refund receipt requires exact destination_asset and expense postings")
        destination_posting = by_role["destination_asset"]
        expense_posting = by_role["expense"]
        destination_amount = _amount(
            destination_posting["amount"], payload["currency"], relationship_path, precisions
        )
        expense_amount = _amount(
            expense_posting["amount"], payload["currency"], relationship_path, precisions
        )
        if (
            destination_amount != received
            or expense_amount != -received
            or destination_posting["account_id"] != payload["destination_account_id"]
            or destination_posting["currency"] != payload["currency"]
            or not destination_posting["reconciliation_eligible"]
            or expense_posting["account_id"] != original_posting["account_id"]
            or expense_posting.get("category_id") != payload["category_id"]
            or expense_posting["currency"] != payload["currency"]
            or expense_posting["reconciliation_eligible"]
        ):
            _fail(relationship_path + ".refund_transaction_id", "refund receipt postings must exactly inherit category and balance the received amount")
        confirmations = [
            item
            for item in state["confirmations"]
            if item["type"] == "refund_relationship_confirmation"
            and item["subject"] == {"kind": "relation", "id": relation["id"]}
        ]
        if len(confirmations) != 1:
            _fail(relation_path, "received refund relation requires exactly one relationship confirmation")
        confirmation = confirmations[0]
        if (
            confirmation["payload"] != {"original_transaction_id": original["id"]}
            or confirmation.get("confirmed_at") != payload["times"]["confirmed_at"]
            or receipt_version.get("confirmation_id") != confirmation["id"]
        ):
            _fail(relation_path, "relationship confirmation must own the original identity, time, and receipt version")

    for relationship in relationships:
        relation_id = relationship["payload"]["relation_id"]
        if relation_id not in relations or relations[relation_id]["type"] != "refund":
            _fail(path + ".domain_entities", "refund_relationship has a dangling or mistyped relation")
        if len(relationships_by_relation[relation_id]) != 1:
            _fail(path + ".domain_entities", "refund relation/domain ownership must be one-to-one")
    for original_id, received_total in received_by_original.items():
        if received_total > original_caps[original_id]:
            _fail(path + ".domain_entities", "cumulative active refunds exceed the original categorized expense")

    source_to_evidence_type = {
        "bank_debit": "asset_debit",
        "merchant_refund_notice": "refund_notice",
        "wallet_credit": "asset_credit",
        "combined_refund_statement": "combined_refund_statement",
        "wallet_credit_mirror": "asset_credit_mirror",
    }
    source_positions = {item["id"]: index for index, item in enumerate(state["sources"])}
    for source_index, source in enumerate(state["sources"]):
        if source["type"] not in source_to_evidence_type:
            continue
        source_path = f"{path}.sources[{source_index}].payload"
        payload = source["payload"]
        if payload["kind"] != source["type"]:
            _fail(source_path + ".kind", "must equal the closed source subtype")
        owned_evidence = evidence.get(payload["evidence_id"])
        if (
            owned_evidence is None
            or owned_evidence["type"] != source_to_evidence_type[source["type"]]
            or owned_evidence["source_ids"] != [source["id"]]
            or owned_evidence["payload"]["observed_at"] != payload["observed_at"]
        ):
            _fail(source_path + ".evidence_id", "must own one exact matching RG-07 evidence item")
        if "account_id" in payload:
            account = accounts.get(payload["account_id"])
            if account is None or not (
                account["owned_by_user"] and account["real_account"] and account["kind"] == "asset"
            ):
                _fail(source_path + ".account_id", "must reference an owned real asset")
            if payload.get("currency") != account["currency"]:
                _fail(source_path + ".currency", "must match the source account currency")
        if source["type"] == "bank_debit" and Decimal(payload["amount"]) >= 0:
            _fail(source_path + ".amount", "bank debit must be negative")
        if source["type"] in {"wallet_credit", "wallet_credit_mirror"} and Decimal(payload["amount"]) <= 0:
            _fail(source_path + ".amount", "wallet credit must be positive")
        mirror_source_id = payload.get("mirror_of_source_id")
        if source["type"] == "wallet_credit_mirror":
            original_source = sources.get(mirror_source_id)
            if (
                original_source is None
                or original_source["type"] != "wallet_credit"
                or source_positions[original_source["id"]] >= source_index
                or original_source["payload"].get("amount") != payload.get("amount")
                or original_source["payload"].get("currency") != payload.get("currency")
            ):
                _fail(source_path + ".mirror_of_source_id", "must identify one earlier equal wallet credit source")
        elif mirror_source_id is not None:
            _fail(source_path + ".mirror_of_source_id", "only wallet_credit_mirror owns source lineage")

    for evidence_index, item in enumerate(state["evidence"]):
        if item["type"] not in set(source_to_evidence_type.values()):
            continue
        item_path = f"{path}.evidence[{evidence_index}].payload"
        lineage = {"mirror_of_evidence_id", "merged_into_evidence_link_id"}
        if item["type"] != "asset_credit_mirror":
            if lineage & set(item["payload"]):
                _fail(item_path, "only asset_credit_mirror owns evidence lineage")
            continue
        if not lineage.issubset(item["payload"]):
            _fail(item_path, "asset_credit_mirror requires complete evidence lineage")
        original_evidence = evidence.get(item["payload"]["mirror_of_evidence_id"])
        merged_link = evidence_links.get(item["payload"]["merged_into_evidence_link_id"])
        if (
            original_evidence is None
            or original_evidence["type"] != "asset_credit"
            or merged_link is None
            or merged_link["evidence_id"] != original_evidence["id"]
            or merged_link["role"] != "destination_asset_posting"
        ):
            _fail(item_path, "mirror evidence must identify the earlier asset credit and its exact posting link")

    required_candidate_confirmations = {
        "original_transaction_id",
        "category_id_and_allocation",
        "destination_account_id",
        "arrival",
    }
    for candidate_index, candidate in enumerate(state["candidates"]):
        if candidate["type"] != "refund_credit":
            continue
        candidate_path = f"{path}.candidates[{candidate_index}]"
        source = sources[candidate["source_ids"][0]]
        payload = candidate["payload"]
        if source["type"] != "wallet_credit":
            _fail(candidate_path + ".source_ids", "refund credit candidate requires one wallet credit source")
        source_payload = source["payload"]
        if (
            payload["proposed_amount"] != source_payload["amount"]
            or payload["currency"] != source_payload["currency"]
            or payload["proposed_destination_account_id"] != source_payload["account_id"]
            or payload["proposed_arrived_at"] != source_payload["value_at"]
            or payload["original_source_payload_hash"]
            != source_payload["original_source_payload_hash"]
        ):
            _fail(candidate_path + ".payload", "refund candidate proposals must exactly derive from its wallet credit source")
        confirmed = candidate["status_history"][-1]["status"] == "confirmed"
        required = set(payload["requires_confirmation"])
        if required != required_candidate_confirmations:
            _fail(candidate_path + ".payload.requires_confirmation", "must preserve the exact confirmation registry")
        if confirmed:
            confirming_operations = [
                operation
                for operation in operations.values()
                if operation["action_type"] == "confirm_imported_refund"
                and operation["outcome"]["status"] == "accepted"
                and operation.get("input", {}).get("candidate_id") == candidate["id"]
            ]
            if len(confirming_operations) != 1:
                _fail(candidate_path, "confirmed refund candidate must be owned by one accepted imported confirmation")


def _periodic_allocation_statuses(
    state: dict[str, Any],
    indexes: dict[str, dict[str, dict[str, Any]]],
) -> dict[tuple[str, str, str], str]:
    entities = indexes["domain_entities"]
    recognized_installments = {
        link["from"]["id"]
        for link in state["audit_links"]
        if link["type"] == "periodic_allocation_recognition"
    }
    revisions_by_schedule: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for entity in state["domain_entities"]:
        if entity["type"] == "periodic_allocation_revision":
            revisions_by_schedule[entity["payload"]["schedule_id"]].append(entity)

    expected: dict[tuple[str, str, str], str] = {}
    for schedule_id, revisions in revisions_by_schedule.items():
        current_revision = max(revisions, key=lambda item: item["payload"]["revision_number"])
        current_ids = set(current_revision["payload"]["installment_ids"])
        for revision in revisions:
            for installment_id in revision["payload"]["installment_ids"]:
                value = (
                    "recognized" if installment_id in recognized_installments
                    else "pending" if installment_id in current_ids else "superseded"
                )
                expected[("domain_entity", installment_id, "allocation_status")] = value
        expected[("domain_entity", schedule_id, "allocation_status")] = (
            "recognized" if all(item_id in recognized_installments for item_id in current_ids) else "active"
        )
    return expected


def _validate_rg08_contract(
    state: dict[str, Any],
    path: str,
    indexes: dict[str, dict[str, dict[str, Any]]],
    operations: dict[str, dict[str, Any]],
    precisions: dict[str, int],
) -> None:
    sources = indexes["sources"]
    transactions = indexes["transactions"]
    postings = indexes["postings"]
    accounts = indexes["catalog_accounts"]
    categories = indexes["catalog_categories"]
    entities = indexes["domain_entities"]
    source_evidence_types = {
        "bank_debit": "asset_debit",
        "bank_credit": "asset_credit",
        "bank_credit_mirror": "asset_credit_mirror",
        "lending_agreement": "lending_agreement",
    }
    for index, evidence in enumerate(state["evidence"]):
        if evidence["type"] not in set(source_evidence_types.values()):
            continue
        evidence_path = f"{path}.evidence[{index}]"
        source = sources[evidence["source_ids"][0]]
        if source_evidence_types.get(source["type"]) != evidence["type"]:
            _fail(evidence_path + ".type", "must match the exact RG-08 source subtype")
        if evidence["payload"]["observed_at"] != source["payload"]["observed_at"]:
            _fail(evidence_path + ".payload.observed_at", "must byte-equal its source observed_at")

    # Source semantic bindings (batch 0 F3, RG-07 :4798-4822 precedent): a
    # bank_debit/bank_credit account anchor must resolve to a catalog account
    # whose currency byte-equals the source currency; a lending_agreement
    # counterparty must resolve to a counterparty projected through a lending
    # position/settlement; a bank_credit_mirror lineage must identify one
    # earlier same-state bank_credit source with the exact same amount and
    # currency, and no other RG-08 source subtype may own source lineage.
    source_positions = {
        source["id"]: index for index, source in enumerate(state["sources"])
    }
    known_counterparties = {
        entity["payload"]["counterparty_id"]
        for entity in state["domain_entities"]
        if entity.get("type") in {"lending_position", "lending_settlement"}
    }
    for index, source in enumerate(state["sources"]):
        if source["type"] not in source_evidence_types:
            continue
        source_path = f"{path}.sources[{index}].payload"
        payload = source["payload"]
        if "account_id" in payload:
            account = accounts.get(payload["account_id"])
            if account is None:
                _fail(source_path + ".account_id", "must reference a catalog account")
            if payload.get("currency") != account["currency"]:
                _fail(source_path + ".currency", "must match the source account currency")
        if source["type"] == "lending_agreement" and payload["counterparty_id"] not in known_counterparties:
            _fail(source_path + ".counterparty_id", "must reference a known lending counterparty")
        mirror_source_id = payload.get("mirror_of_source_id")
        if source["type"] == "bank_credit_mirror":
            original_source = sources.get(mirror_source_id)
            if (
                original_source is None
                or original_source["type"] != "bank_credit"
                or source_positions[original_source["id"]] >= index
                or original_source["payload"].get("amount") != payload.get("amount")
                or original_source["payload"].get("currency") != payload.get("currency")
            ):
                _fail(source_path + ".mirror_of_source_id", "must identify one earlier equal bank credit source")
        elif mirror_source_id is not None:
            _fail(source_path + ".mirror_of_source_id", "only bank_credit_mirror owns source lineage")

    positions: dict[str, dict[str, Any]] = {}
    settlements: dict[str, dict[str, Any]] = {}
    for index, entity in enumerate(state["domain_entities"]):
        entity_path = f"{path}.domain_entities[{index}]"
        if entity["type"] == "lending_position":
            positions[entity["id"]] = entity
            payload = entity["payload"]
            account = accounts.get(payload["receivable_account_id"])
            if account is None or not (
                account["kind"] == "asset" and not account["real_account"]
                and not account["owned_by_user"] and account["currency"] == payload["currency"]
            ):
                _fail(entity_path + ".payload.receivable_account_id", "must identify the non-real counterparty receivable asset")
            running = Decimal(0)
            for history_index, history in enumerate(payload["history"]):
                history_path = f"{entity_path}.payload.history[{history_index}]"
                if history["sequence"] != history_index + 1:
                    _fail(history_path + ".sequence", "position history must be append-only and contiguous")
                transaction = transactions.get(history["transaction_id"])
                expected_type = "lending_disbursement" if history["behavior_code"] == "lend" else "lending_collection"
                if transaction is None or transaction["type"] != expected_type:
                    _fail(history_path + ".transaction_id", "must reference the matching lending transaction subtype")
                amount = _amount(history["amount"], payload["currency"], history_path + ".amount", precisions)
                if (history["behavior_code"] == "lend" and amount <= 0) or (history["behavior_code"] == "collect" and amount >= 0):
                    _fail(history_path + ".amount", "lend must increase and collection must reduce principal")
                running += amount
                if running < 0 or _decimal(history["principal_balance_after"], history_path) != running:
                    _fail(history_path + ".principal_balance_after", "must equal the nonnegative running principal")
            if _decimal(payload["principal_balance"], entity_path) != running:
                _fail(entity_path + ".payload.principal_balance", "must equal the final history balance")
        elif entity["type"] == "lending_settlement":
            settlements[entity["id"]] = entity
            payload = entity["payload"]
            transaction = transactions.get(payload["transaction_id"])
            position = entities.get(payload["linked_position_id"])
            if transaction is None or transaction["type"] != "lending_collection":
                _fail(entity_path + ".payload.transaction_id", "must reference a lending collection")
            if position is None or position["type"] != "lending_position":
                _fail(entity_path + ".payload.linked_position_id", "must reference a lending position")
            if payload["counterparty_id"] != position["payload"]["counterparty_id"]:
                _fail(entity_path + ".payload.counterparty_id", "must match the linked position")
            components = {item["kind"]: item for item in payload["components"]}
            if set(components) != {"principal", "interest", "fee"}:
                _fail(entity_path + ".payload.components", "must contain exactly principal, interest, and fee")
            amounts = {
                kind: _amount(item["amount"], payload["currency"], entity_path + ".payload.components", precisions)
                for kind, item in components.items()
            }
            if amounts["principal"] < 0 or amounts["interest"] < 0 or amounts["fee"] != 0:
                _fail(entity_path + ".payload.components", "principal and interest must be nonnegative and fee zero")
            if sum(amounts.values(), Decimal(0)) != _decimal(payload["total_received"], entity_path):
                _fail(entity_path + ".payload.total_received", "must equal the exact component sum")
            principal_posting = postings.get(components["principal"]["posting_id"])
            interest_posting = postings.get(components["interest"]["posting_id"])
            if principal_posting is None or principal_posting.get("role") != "lending_receivable" or _decimal(principal_posting["amount"], entity_path) != -amounts["principal"]:
                _fail(entity_path + ".payload.components", "principal must bind the exact receivable posting")
            if interest_posting is None or interest_posting.get("role") != "lending_interest" or _decimal(interest_posting["amount"], entity_path) != -amounts["interest"]:
                _fail(entity_path + ".payload.components", "interest must bind the exact income posting")
            if components["fee"]["posting_id"] is not None:
                _fail(entity_path + ".payload.components", "zero fee must not create a posting")
            category = categories.get(payload["interest_category_id"])
            if category is None or not category["active"] or category["posting_account_id"] != interest_posting["account_id"]:
                _fail(entity_path + ".payload.interest_category_id", "must identify the active exact interest category")
            if len(payload["history"]) != 1 or payload["history"][0]["transaction_id"] != transaction["id"] or payload["history"][0]["occurred_at"] != payload["confirmed_at"]:
                _fail(entity_path + ".payload.history", "must contain the confirmed settlement event")

    for index, relation in enumerate(state["relations"]):
        if relation["type"] != "counterparty_lending_relationship":
            continue
        relation_path = f"{path}.relations[{index}]"
        members = [entities.get(ref["id"]) for ref in relation["member_refs"]]
        if not members or any(item is None or item["type"] != "lending_position" for item in members):
            _fail(relation_path + ".member_refs", "must contain only lending positions")
        if any(item["payload"]["counterparty_id"] != relation["payload"]["counterparty_id"] for item in members):
            _fail(relation_path + ".payload.counterparty_id", "must own positions for one counterparty")

    required_gates = {"behavior_code", "counterparty_id", "destination_account_id", "principal_amount", "interest_and_fee_amounts", "actual_receipt_time"}
    for index, candidate in enumerate(state["candidates"]):
        if candidate["type"] != "lending_collection_credit":
            continue
        candidate_path = f"{path}.candidates[{index}]"
        if {sources[item]["type"] for item in candidate["source_ids"]} != {"bank_credit", "lending_agreement"}:
            _fail(candidate_path + ".source_ids", "must bind one bank credit and one lending agreement")
        if set(candidate["payload"]["requires_confirmation"]) != required_gates:
            _fail(candidate_path + ".payload.requires_confirmation", "must retain all six confirmation gates")
        statuses = candidate["status_history"]
        if [item["sequence"] for item in statuses] != list(range(1, len(statuses) + 1)):
            _fail(candidate_path + ".status_history", "must be append-only and contiguous")
        if [item["status"] for item in statuses] not in (["pending_confirmation"], ["pending_confirmation", "confirmed"]):
            _fail(candidate_path + ".status_history", "must start pending and transition at most once")
        if [item["formal_effect_count"] for item in statuses] != ([0] if len(statuses) == 1 else [0, 1]):
            _fail(candidate_path + ".status_history", "formal effect is zero until explicit confirmation")

    for index, confirmation in enumerate(state["confirmations"]):
        if confirmation["type"] not in {"lending_event_confirmation", "lending_settlement_confirmation"}:
            continue
        confirmation_path = f"{path}.confirmations[{index}]"
        transaction = transactions.get(confirmation["payload"]["transaction_id"])
        expected_type = "lending_disbursement" if confirmation["type"] == "lending_event_confirmation" else "lending_collection"
        if confirmation["operation_id"] not in operations or transaction is None or transaction["type"] != expected_type:
            _fail(confirmation_path, "must bind its creating operation and matching transaction")
        if confirmation["subject"] != {"kind": "transaction", "id": transaction["id"]}:
            _fail(confirmation_path + ".subject", "must identify the confirmed transaction")
        settlement_id = confirmation["payload"].get("settlement_id")
        candidate_id = confirmation["payload"].get("candidate_id")
        if confirmation["type"] == "lending_event_confirmation" and (settlement_id is not None or candidate_id is not None):
            _fail(confirmation_path + ".payload", "event confirmation cannot own settlement or candidate")
        if confirmation["type"] == "lending_settlement_confirmation":
            if settlement_id not in settlements or settlements[settlement_id]["payload"]["transaction_id"] != transaction["id"]:
                _fail(confirmation_path + ".payload.settlement_id", "must identify the transaction settlement")
            if candidate_id is not None and candidate_id not in indexes["candidates"]:
                _fail(confirmation_path + ".payload.candidate_id", "must identify the confirmed candidate")


def _validate_periodic_allocations(
    state: dict[str, Any],
    path: str,
    indexes: dict[str, dict[str, dict[str, Any]]],
    current: dict[str, tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]],
    precisions: dict[str, int],
    timezone: ZoneInfo,
) -> None:
    entities = indexes["domain_entities"]
    accounts = indexes["catalog_accounts"]
    categories = indexes["catalog_categories"]
    schedules = [item for item in state["domain_entities"] if item["type"] == "periodic_allocation_schedule"]
    revisions = [item for item in state["domain_entities"] if item["type"] == "periodic_allocation_revision"]
    installments = [item for item in state["domain_entities"] if item["type"] == "periodic_allocation_installment"]
    if not schedules and not revisions and not installments:
        return
    if not schedules:
        _fail(path + ".domain_entities", "periodic revisions and installments require a schedule")

    revisions_by_schedule: dict[str, list[dict[str, Any]]] = defaultdict(list)
    installments_by_revision: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for revision in revisions:
        revisions_by_schedule[revision["payload"]["schedule_id"]].append(revision)
    for installment in installments:
        installments_by_revision[installment["payload"]["revision_id"]].append(installment)
    schedule_ids = {item["id"] for item in schedules}
    revision_ids = {item["id"] for item in revisions}
    if set(revisions_by_schedule) - schedule_ids:
        _fail(path + ".domain_entities", "periodic revisions must reference an existing schedule")
    if set(installments_by_revision) - revision_ids:
        _fail(path + ".domain_entities", "periodic installments must reference an existing revision")

    recognition_links = [
        item for item in state["audit_links"]
        if item["type"] == "periodic_allocation_recognition"
    ]
    link_by_installment = {item["from"]["id"]: item for item in recognition_links}
    if len(link_by_installment) != len(recognition_links):
        _fail(path + ".audit_links", "an installment may have only one recognition link")
    recognition_transaction_ids = {
        transaction["id"] for transaction in state["transactions"] if transaction["type"] == "prepaid_recognition"
    }
    if {item["to"]["id"] for item in recognition_links} != recognition_transaction_ids:
        _fail(path + ".audit_links", "each prepaid recognition transaction requires exactly one installment link")
    if len({item["to"]["id"] for item in recognition_links}) != len(recognition_links):
        _fail(path + ".audit_links", "a recognition transaction may link to only one installment")

    for schedule in schedules:
        schedule_path = path + ".domain_entities[" + str(state["domain_entities"].index(schedule)) + "].payload"
        payload = schedule["payload"]
        start_local = _local_datetime(payload["start_at"], schedule_path + ".start_at", timezone)
        if start_local.day != _anchor_day(start_local.year, start_local.month, payload["anchor"]):
            _fail(schedule_path + ".start_at", "must match the schedule anchor in the case timezone")
        transaction = current.get(payload["payment_transaction_id"])
        prepaid_account = accounts.get(payload["prepaid_account_id"])
        category = categories.get(payload["category_id"])
        category_account = accounts.get(category["posting_account_id"]) if category else None
        if transaction is None or transaction[0]["type"] != "prepaid_purchase":
            _fail(schedule_path + ".payment_transaction_id", "must reference a prepaid purchase transaction")
        if not (prepaid_account and prepaid_account["kind"] == "asset" and prepaid_account["owned_by_user"] and not prepaid_account["real_account"] and prepaid_account.get("hidden") is True):
            _fail(schedule_path + ".prepaid_account_id", "must reference an owned non-real system-hidden prepaid asset")
        if not (category and category["parent_id"] is not None and category["active"] and category_account and category_account["kind"] == "expense" and not category_account["owned_by_user"] and not category_account["real_account"]):
            _fail(schedule_path + ".category_id", "must reference an active second-level non-owned category posting account")
        total = _amount(payload["total_amount"], payload["currency"], schedule_path + ".total_amount", precisions)
        if total <= 0 or prepaid_account["currency"] != payload["currency"] or category_account["currency"] != payload["currency"]:
            _fail(schedule_path, "schedule amount and referenced accounts must use one positive currency amount")
        purchase_postings = transaction[2]
        if {(item.get("role"), item["account_id"], item["amount"]) for item in purchase_postings} != {
            ("payment_asset", next(item["account_id"] for item in purchase_postings if item.get("role") == "payment_asset"), "-" + payload["total_amount"]),
            ("prepaid_asset", payload["prepaid_account_id"], payload["total_amount"]),
        }:
            _fail(schedule_path + ".payment_transaction_id", "purchase postings must fund this exact prepaid amount")
        schedule_revisions = sorted(revisions_by_schedule.get(schedule["id"], []), key=lambda item: item["payload"]["revision_number"])
        if not schedule_revisions or [item["payload"]["revision_number"] for item in schedule_revisions] != list(range(1, len(schedule_revisions) + 1)):
            _fail(schedule_path, "revisions must start at 1 and remain continuous")
        seen_sequences: set[int] = set()
        for revision_index, revision in enumerate(schedule_revisions):
            revision_path = path + ".domain_entities[" + str(state["domain_entities"].index(revision)) + "].payload"
            revision_payload = revision["payload"]
            owned_installments = installments_by_revision.get(revision["id"], [])
            if revision_payload["currency"] != payload["currency"] or {item["id"] for item in owned_installments} != set(revision_payload["installment_ids"]):
                _fail(revision_path, "revision must own exactly its schedule currency installments")
            revision_installments = [entities[item_id] for item_id in revision_payload["installment_ids"]]
            if revision_index == 0:
                if revision_payload["recognized_through"] is not None:
                    _fail(revision_path + ".recognized_through", "revision 1 boundary must be null")
                first_expected = _anchored_month_date(start_local, payload["anchor"], 0)
                recognized_before = Decimal(0)
            else:
                previous = schedule_revisions[revision_index - 1]
                previous_installments = [entities[item_id] for item_id in previous["payload"]["installment_ids"]]
                flags = [item["id"] in link_by_installment for item in previous_installments]
                if not any(flags) or any(flags[index] and not flags[index - 1] for index in range(1, len(flags))):
                    _fail(revision_path + ".recognized_through", "previous revision recognition must be a non-empty contiguous prefix")
                latest_index = max(index for index, recognized in enumerate(flags) if recognized)
                if any(flags[latest_index + 1 :]):
                    _fail(revision_path + ".recognized_through", "recognized installments cannot be hidden after the boundary")
                latest = previous_installments[latest_index]
                if revision_payload["recognized_through"] != latest["id"]:
                    _fail(revision_path + ".recognized_through", "must equal the latest contiguous recognized installment in the immediately previous revision")
                latest_local = _local_datetime(latest["payload"]["scheduled_at"], revision_path + ".recognized_through", timezone)
                first_expected = _anchored_month_date(latest_local, payload["anchor"], 1)
                prior_revision_ids = {
                    item_id
                    for prior in schedule_revisions[:revision_index]
                    for item_id in prior["payload"]["installment_ids"]
                }
                recognized_before = sum(
                    (_decimal(entities[item_id]["payload"]["amount"], revision_path) for item_id in prior_revision_ids if item_id in link_by_installment),
                    Decimal(0),
                )
            installment_total = Decimal(0)
            expected_split = _equal_split(
                _amount(revision_payload["remaining_amount"], payload["currency"], revision_path + ".remaining_amount", precisions),
                len(revision_installments),
                precisions[payload["currency"]],
            )
            previous_sequence: int | None = None
            for installment_index, installment in enumerate(revision_installments):
                installment_path = path + ".domain_entities[" + str(state["domain_entities"].index(installment)) + "].payload"
                installment_payload = installment["payload"]
                if installment_payload["schedule_id"] != schedule["id"] or installment_payload["currency"] != payload["currency"]:
                    _fail(installment_path, "installment must belong to its schedule and currency")
                if installment_payload["sequence"] in seen_sequences or (previous_sequence is not None and installment_payload["sequence"] != previous_sequence + 1):
                    _fail(installment_path + ".sequence", "installment sequences must be unique and consecutive in revision order")
                seen_sequences.add(installment_payload["sequence"])
                previous_sequence = installment_payload["sequence"]
                scheduled_local = _local_datetime(installment_payload["scheduled_at"], installment_path + ".scheduled_at", timezone)
                expected_date = first_expected if installment_index == 0 else _anchored_month_date(
                    datetime(first_expected[0], first_expected[1], first_expected[2]),
                    payload["anchor"],
                    installment_index,
                )
                if (scheduled_local.year, scheduled_local.month, scheduled_local.day) != expected_date or scheduled_local < start_local:
                    _fail(installment_path + ".scheduled_at", "must be a consecutive anchored monthly local date at or after start_at")
                amount = _amount(installment_payload["amount"], payload["currency"], installment_path + ".amount", precisions)
                if amount <= 0 or amount != expected_split[installment_index]:
                    _fail(installment_path + ".amount", "must use deterministic equal split with final installment remainder")
                installment_total += amount
            remaining = _amount(revision_payload["remaining_amount"], payload["currency"], revision_path + ".remaining_amount", precisions)
            if installment_total != remaining:
                _fail(revision_path + ".installment_ids", "revision installments must sum to remaining_amount")
            if revision_payload["revision_number"] == 1 and remaining != total:
                _fail(revision_path + ".remaining_amount", "initial revision must sum to the schedule total")
            if revision_payload["revision_number"] > 1 and remaining != total - recognized_before:
                _fail(revision_path + ".remaining_amount", "must equal schedule total minus all prior immutable recognized amounts")

        recognized_total = Decimal(0)
        for installment_id, link in link_by_installment.items():
            installment = entities.get(installment_id)
            if installment is None or installment["type"] != "periodic_allocation_installment" or installment["payload"]["schedule_id"] != schedule["id"]:
                continue
            installment_payload = installment["payload"]
            transaction, version, postings = current[link["to"]["id"]]
            by_role = {item.get("role"): item for item in postings}
            expense = by_role["expense"]
            prepaid = by_role["prepaid_asset"]
            if expense.get("category_id") != payload["category_id"] or expense["amount"] != installment_payload["amount"] or prepaid["account_id"] != payload["prepaid_account_id"] or prepaid["amount"] != "-" + installment_payload["amount"] or version["occurred_at"] != installment_payload["scheduled_at"] or version["effective_at"] != installment_payload["scheduled_at"]:
                _fail(path + ".audit_links", "recognition must release the exact scheduled installment to its category")
            recognized_total += _decimal(installment_payload["amount"], path)
        if recognized_total > total:
            _fail(path + ".audit_links", "recognitions cannot exceed the prepaid purchase amount")


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
            values["category_consumption"] += expense
            values["ordinary_expense"] += expense
            values["expense"] += expense
            values["net_worth_change"] -= expense
            mixed_asset_funding = sum(
                (
                    -_decimal(item["amount"], "$.postings.amount")
                    for item in selected
                    if item.get("role") == "mixed_expense_asset_funding"
                ),
                Decimal(0),
            )
            if mixed_asset_funding:
                values["cash_outflow"] += mixed_asset_funding
            else:
                values["cash_outflow"] += sum(
                    (
                        -_decimal(item["amount"], "$.postings.amount")
                        for item in selected
                        if accounts[item["account_id"]]["real_account"]
                        and accounts[item["account_id"]]["kind"] == "asset"
                        and _decimal(item["amount"], "$.postings.amount") < 0
                    ),
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
                (
                    -_decimal(item["amount"], "$.postings.amount")
                    for item in selected
                    if item.get("role") == "credit_repayment_asset_outflow"
                ),
                Decimal(0),
            )
        elif transaction_type == "lending_disbursement":
            principal = sum(
                (-_decimal(item["amount"], "$.postings.amount") for item in selected if item.get("role") == "lending_principal_out"),
                Decimal(0),
            )
            values["cash_outflow"] += principal
            values["principal_external_cash_flow"] -= principal
        elif transaction_type == "lending_collection":
            principal = sum(
                (-_decimal(item["amount"], "$.postings.amount") for item in selected if item.get("role") == "lending_receivable"),
                Decimal(0),
            )
            interest = sum(
                (-_decimal(item["amount"], "$.postings.amount") for item in selected if item.get("role") == "lending_interest"),
                Decimal(0),
            )
            values["principal_external_cash_flow"] += principal
            values["interest_cash_flow"] += interest
            values["cash_inflow"] += principal + interest
            values["ordinary_income"] += interest
            values["income"] += interest
            values["net_worth_change"] += interest
        elif transaction_type == "prepaid_purchase":
            values["cash_outflow"] += sum(
                (-_decimal(item["amount"], "$.postings.amount") for item in selected if item.get("role") == "payment_asset"),
                Decimal(0),
            )
        elif transaction_type == "prepaid_recognition":
            expense = sum(
                (_decimal(item["amount"], "$.postings.amount") for item in selected if item.get("role") == "expense"),
                Decimal(0),
            )
            values["consumption"] += expense
            values["category_consumption"] += expense
            values["ordinary_expense"] += expense
            values["expense"] += expense
            values["budget"] += expense
            values["category_effect"] += expense
            values["net_worth_change"] -= expense
        elif transaction_type == "stored_value_recharge":
            # RG-10 recharge: paid amount is a real cash outflow, the bonus is a
            # special non-cash rights income (v1 report special_non_cash_bonus_income
            # projects onto the v2 special_income metric), and net worth rises by
            # exactly the bonus component.
            paid = sum(
                (
                    -_decimal(item["amount"], "$.postings.amount")
                    for item in selected
                    if item.get("role") == "bank_payment"
                ),
                Decimal(0),
            )
            bonus = sum(
                (
                    -_decimal(item["amount"], "$.postings.amount")
                    for item in selected
                    if item.get("role") == "stored_value_bonus_income"
                ),
                Decimal(0),
            )
            values["cash_outflow"] += paid
            values["special_income"] += bonus
            values["net_worth_change"] += bonus
        elif transaction_type == "stored_value_spend":
            spent = sum(
                (
                    _decimal(item["amount"], "$.postings.amount")
                    for item in selected
                    if item.get("role") == "expense"
                ),
                Decimal(0),
            )
            values["consumption"] += spent
            values["category_consumption"] += spent
            values["ordinary_expense"] += spent
            values["expense"] += spent
            values["category_effect"] += spent
            values["net_worth_change"] -= spent
        elif transaction_type == "stored_value_expiry_loss":
            lost = sum(
                (
                    _decimal(item["amount"], "$.postings.amount")
                    for item in selected
                    if item.get("role") == "stored_value_expiry_loss"
                ),
                Decimal(0),
            )
            values["expiry_loss"] += lost
            values["net_worth_change"] -= lost
        elif transaction_type == "stored_value_pre_activation_balance_adjustment":
            adjustment = sum(
                (
                    _decimal(item["amount"], "$.postings.amount")
                    for item in selected
                    if item.get("role") == "stored_value_asset"
                ),
                Decimal(0),
            )
            values["net_worth_change"] += adjustment
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
        "RG-03": {
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
        "RG-04": {
            "balance_adjustment_net_worth_change", "budget", "cash_inflow", "cash_outflow",
            "consumption", "income", "internal_transfer_amount", "net_worth_change",
            "ordinary_expense", "ordinary_income",
        },
        "RG-05": {
            "balance_adjustment_net_worth_change", "budget", "cash_inflow", "cash_outflow",
            "consumption", "income", "internal_transfer_amount", "net_worth_change",
            "ordinary_expense", "ordinary_income",
        },
        "RG-11": {
            "budget", "cash_outflow", "category_effect", "consumption", "income",
            "net_worth_change",
        },
        "RG-10": {
            "budget", "cash_inflow", "cash_outflow", "category_effect", "consumption",
            "expiry_loss", "net_worth_change", "ordinary_expense", "ordinary_income",
            "special_income",
        },
        "RG-07": {
            "cash_inflow", "cash_outflow", "consumption", "income", "net_worth_change"
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
                if case_id in {"RG-01", "RG-02", "RG-04", "RG-05"} and metric["metric"] == "budget"
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

    for entity in state["domain_entities"]:
        if entity["type"] == "refund_relationship":
            expected[("domain_entity", entity["id"], "refund_status")] = entity[
                "payload"
            ]["state_history"][-1]["state"]

    for transaction, _, postings in current.values():
        eligible = [item for item in postings if item["reconciliation_eligible"]]
        if eligible:
            statuses = [reconciliation_by_posting[item["id"]] for item in eligible]
            expected[("transaction", transaction["id"], "reconciliation_summary")] = (
                _transaction_reconciliation_status(statuses)
            )

    entities = indexes["domain_entities"]
    postings = indexes["postings"]
    for relation in state["relations"]:
        if relation["type"] != "staged_payment":
            continue
        members = [
            entities.get(ref["id"])
            for ref in relation["member_refs"]
            if ref["kind"] == "domain_entity"
        ]
        lifecycles = [
            member
            for member in members
            if member is not None and member["type"] == "staged_payment_lifecycle"
        ]
        installment_ids = {
            member["id"]
            for member in members
            if member is not None and member["type"] == "installment_payment"
        }
        matched_count = 0
        for installment_id in installment_ids:
            asset_posting_id = entities[installment_id]["payload"]["asset_posting_id"]
            posting = postings.get(asset_posting_id)
            if (
                posting is not None
                and posting.get("role") == "payment_asset"
                and posting.get("reconciliation_eligible") is True
                and reconciliation_by_posting.get(asset_posting_id) == "matched"
            ):
                matched_count += 1
        installment_count = len(installment_ids)
        reconciliation = (
            "pending"
            if installment_count == 0 or matched_count == 0
            else "complete"
            if matched_count == installment_count
            else "partial"
        )
        for lifecycle in lifecycles:
            latest_history = lifecycle["payload"]["state_history"][-1]
            expected[("domain_entity", lifecycle["id"], "payment_progress")] = (
                latest_history["payment_progress"]
            )
            expected[("domain_entity", lifecycle["id"], "fulfillment_status")] = (
                latest_history["fulfillment_status"]
            )
            expected[("domain_entity", lifecycle["id"], "reconciliation")] = (
                reconciliation
            )

    links_by_target = defaultdict(list)
    for link in state["evidence_links"]:
        if link["role"] == "item_allocation_fact":
            links_by_target[link["target_id"]].append(link)
    for relation in state["relations"]:
        if relation["type"] != "merged_payment":
            continue
        allocation_ids = [
            ref["id"] for ref in relation["member_refs"]
            if ref["kind"] == "domain_entity"
        ]
        matched = sum(bool(links_by_target[allocation_id]) for allocation_id in allocation_ids)
        completeness = "none" if matched == 0 else "complete" if matched == len(allocation_ids) else "partial"
        expected[("relation", relation["id"], "item_evidence_completeness")] = completeness

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
    expected.update(_periodic_allocation_statuses(state, indexes))
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
        "refund_status": (
            "domain_entity",
            {"requested", "approved", "processing", "received"},
        ),
        "item_evidence_completeness": (
            "relation",
            {"none", "partial", "complete"},
        ),
        "allocation_status": (
            "domain_entity",
            {"active", "pending", "recognized", "superseded"},
        ),
        "payment_progress": (
            "domain_entity",
            {"unpaid", "partially_paid", "paid_in_full"},
        ),
        "fulfillment_status": (
            "domain_entity",
            {"in_progress", "fulfilled"},
        ),
        "reconciliation": (
            "domain_entity",
            {"pending", "partial", "complete"},
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
    ordered_operations = list(operations.values())
    operation_index = next(
        (index for index, item in enumerate(ordered_operations) if item["id"] == operation["id"]),
        -1,
    )
    if operation_index < 0:
        _fail(operation_path + ".id", "operation is absent from the case inventory")
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
        elif reference["kind"] == "name_history" and operation.get("input", {}).get("variant") == "retry":
            anchor = operation["input"]["input_anchor_id"]
            owners = [
                item
                for item in ordered_operations[:operation_index]
                if item.get("input", {}).get("variant") == "rename_counterparty"
                and item["input"].get("request_id") == anchor
                and item["input"].get("name_history_id") == reference["id"]
            ]
            if len(owners) != 1:
                _fail(returned_path + ".id", "retry name-history identity must have one unique earlier rename anchor owner")
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


def _transfer_account_failure(
    input_value: dict[str, Any],
    accounts: dict[str, dict[str, Any]],
    *,
    attempted: bool,
    require_destination: bool = True,
) -> tuple[str, str] | None:
    source_id = input_value.get("source_account_id")
    destination_id = input_value.get("destination_account_id")
    if source_id is None:
        return "source_account_id", "required"
    if destination_id is None and require_destination:
        return "destination_account_id", "required"
    if destination_id is not None and source_id == destination_id:
        return "destination_account_id", "distinct_own_real_financial_accounts_required"
    for field, account_id in (("source_account_id", source_id), ("destination_account_id", destination_id)):
        if account_id is None:
            continue
        account = accounts.get(account_id)
        if account is None:
            return field, "known_account_required"
        if not (account["real_account"] and account["kind"] in {"asset", "liability"}):
            return field, "real_financial_account_required"
        if not account["owned_by_user"]:
            return field, "own_account_required"
    if attempted:
        destination_amount = input_value.get("destination_credit_amount")
        if destination_amount is not None and Decimal(destination_amount) <= 0:
            return "destination_credit_amount", "must_be_positive"
        source_amount = input_value.get("source_debit_amount")
        fee_amount = input_value.get("fee_amount")
        if None not in (source_amount, destination_amount, fee_amount) and Decimal(source_amount) != Decimal(destination_amount) + Decimal(fee_amount):
            return "fee_amount", "amounts_must_balance"
        source_currency = input_value.get("source_currency", input_value.get("currency"))
        destination_currency = input_value.get("destination_currency", input_value.get("currency"))
        if source_currency is not None and destination_currency is not None and source_currency != destination_currency:
            return "destination_currency", "same_currency_required"
    return None


def _validate_rejected_manual_account_transfer_attempt(
    operation: dict[str, Any], operation_path: str, baseline: dict[str, Any], precisions: dict[str, int], timezone: ZoneInfo
) -> None:
    attempted = operation["attempted_input"]
    attempted_path = operation_path + ".attempted_input"
    accounts = {item["id"]: item for item in baseline["catalog"]["accounts"]}
    if "currency" in attempted and attempted["currency"] not in precisions:
        _fail(attempted_path + ".currency", "undeclared currency")
    currency = attempted.get("currency")
    if currency is not None:
        for field in ("source_debit_amount", "destination_credit_amount", "fee_amount"):
            if attempted.get(field) is not None:
                _attempted_amount(attempted[field], currency, attempted_path + f".{field}", precisions)
    if "occurred_at" in attempted:
        _timestamp(attempted["occurred_at"], attempted_path + ".occurred_at", timezone)
    failure = _transfer_account_failure(attempted, accounts, attempted=True)
    if failure is None:
        _fail(attempted_path, "does not match a registered rejected manual_account_transfer failure")
    field, reason = failure
    if operation["outcome"]["field_path"] != f"$.attempted_input.{field}":
        _fail(operation_path + ".outcome.field_path", "must identify the first failing attempted field")
    if operation["outcome"]["reason_code"] != reason:
        _fail(operation_path + ".outcome.reason_code", "must match the first failing attempted reason")


def _validate_rejected_manual_mixed_expense_attempt(
    operation: dict[str, Any], operation_path: str, baseline: dict[str, Any]
) -> None:
    attempted = operation["attempted_input"]
    attempted_path = operation_path + ".attempted_input"
    accounts = {item["id"]: item for item in baseline["catalog"]["accounts"]}
    categories = {item["id"]: item for item in baseline["catalog"]["categories"]}

    category_id = attempted.get("category_id")
    category = categories.get(category_id) if category_id is not None else None
    if category_id is not None and category is None:
        _fail(attempted_path + ".category_id", "dangling category reference")

    if "funding_components" in attempted:
        components = attempted["funding_components"]
    else:
        components = []
        for account_field, amount_field in (
            ("asset_account_id", "asset_funding_amount"),
            ("liability_account_id", "liability_funding_amount"),
        ):
            if account_field in attempted or amount_field in attempted:
                components.append({
                    "account_id": attempted.get(account_field),
                    "funding_amount": attempted.get(amount_field),
                    "currency": attempted.get("currency"),
                })

    total = attempted.get("total_amount")
    failure: tuple[str, str] | None = None
    if category_id is None or (category is not None and category["parent_id"] is None):
        failure = ("category_id", "secondary_category_required")
    elif category is not None and not category["active"]:
        failure = ("category_id", "category_inactive")
    elif category is not None and (
        category["posting_account_id"] is None
        or accounts[category["posting_account_id"]]["kind"] != "expense"
    ):
        failure = ("category_id", "expense_category_required")
    elif total is not None and Decimal(total) <= 0:
        failure = ("total_amount", "must_be_positive")

    if failure is None:
        for component in components:
            amount = component.get("funding_amount")
            if amount is not None and Decimal(amount) <= 0:
                failure = ("funding_components", "funding_leg_must_be_positive")
                break

    if failure is None and total is not None and all(
        component.get("funding_amount") is not None for component in components
    ):
        if sum((Decimal(component["funding_amount"]) for component in components), Decimal("0")) != Decimal(total):
            failure = ("funding_components", "funding_total_must_equal_expense")

    if failure is None:
        seen_accounts: set[str] = set()
        for component in components:
            account_id = component.get("account_id")
            account = accounts.get(account_id)
            if account is None:
                failure = ("funding_components", "unknown_real_account")
                break
            if not account["real_account"] or account["kind"] not in {"asset", "liability"}:
                failure = ("funding_components", "real_financial_account_required")
                break
            if not account["owned_by_user"]:
                failure = ("funding_components", "owned_account_required")
                break
            if account_id in seen_accounts:
                failure = ("funding_components", "duplicate_funding_account")
                break
            seen_accounts.add(account_id)

    if failure is None:
        currencies = {component.get("currency") for component in components}
        if len(currencies) > 1 or (
            currencies and attempted.get("currency") not in currencies
        ):
            failure = ("funding_components", "single_currency_required")

    if failure is None:
        _fail(attempted_path, "does not match a registered rejected manual_mixed_expense failure")

    field, reason = failure
    expected_path = f"$.attempted_input.{field}"
    if operation["outcome"]["field_path"] != expected_path:
        _fail(operation_path + ".outcome.field_path", f"must be {expected_path!r} for the first failing attempted field")
    if operation["outcome"]["reason_code"] != reason:
        _fail(operation_path + ".outcome.reason_code", f"must be {reason!r} for the first failing attempted field")


def _validate_rejected_rg05_attempt(
    operation: dict[str, Any], operation_path: str, baseline: dict[str, Any]
) -> None:
    attempted = operation["attempted_input"]
    attempted_path = operation_path + ".attempted_input"
    if operation["outcome"]["reason_code"] == "identity_conflict":
        if operation["outcome"]["field_path"] != "$.attempted_input.request_id":
            _fail(operation_path + ".outcome.field_path", "identity conflict must identify request_id")
        return
    if operation["action_type"] == "confirm_merged_payment_candidate":
        candidate = next(
            (
                item
                for item in baseline["candidates"]
                if item["id"] == attempted["candidate_id"]
            ),
            None,
        )
        if (
            candidate is None
            or candidate.get("type") != "merged_payment"
            or [event["status"] for event in candidate["status_history"]]
            != ["pending_confirmation"]
            or "transaction_id" in candidate.get("payload", {})
        ):
            _fail(
                attempted_path + ".candidate_id",
                "allocation rejection requires the baseline pending merged-payment candidate",
            )
        payment = attempted["payment_total"]
        allocation = attempted["allocation_total"]
        if attempted["currency"] != candidate["payload"]["currency"]:
            _fail(attempted_path + ".currency", "must match the pending candidate currency")
        payment_value = Decimal(payment)
        allocation_value = Decimal(allocation)
        if payment_value != Decimal(candidate["payload"]["payment_total"]):
            _fail(attempted_path + ".payment_total", "must match the pending candidate payment total")
        if allocation_value < payment_value:
            failure = ("allocation_total", "allocation_incomplete")
        elif allocation_value > payment_value:
            failure = ("allocation_total", "allocation_conflict")
        else:
            _fail(attempted_path, "does not reproduce an allocation failure")
    elif operation["action_type"] == "manual_merged_payment":
        accounts = {item["id"]: item for item in baseline["catalog"]["accounts"]}
        categories = {item["id"]: item for item in baseline["catalog"]["categories"]}
        total = attempted.get("total_amount")
        items = attempted.get("items", [])
        failure: tuple[str, str] | None = None
        if isinstance(items, list):
            for item in items:
                category_id = item.get("category_id") if isinstance(item, dict) else None
                category = categories.get(category_id) if isinstance(category_id, str) else None
                if category_id is None or (category is not None and category.get("parent_id") is None):
                    failure = ("items", "secondary_category_required")
                    break
                if category is not None and not category.get("active"):
                    failure = ("items", "category_inactive")
                    break
                posting_account = accounts.get(category.get("posting_account_id")) if category else None
                if category is None or posting_account is None or posting_account.get("kind") != "expense":
                    failure = ("items", "expense_category_required")
                    break
        if failure is None and isinstance(total, str) and Decimal(total) <= 0:
            failure = ("total_amount", "must_be_positive")
        if failure is None:
            for item in items:
                amount = item.get("amount") if isinstance(item, dict) else None
                if isinstance(amount, str) and Decimal(amount) <= 0:
                    failure = ("items", "item_amount_must_be_positive")
                    break
        if failure is None and isinstance(total, str) and items and all(
            isinstance(item, dict) and isinstance(item.get("amount"), str) for item in items
        ):
            if sum((Decimal(item["amount"]) for item in items), Decimal(0)) != Decimal(total):
                failure = ("items", "allocation_total_must_equal_payment")
        if failure is None and len({item.get("item_id", item.get("id")) for item in items if isinstance(item, dict)}) != len(items):
            failure = ("items", "duplicate_item_id")
        if failure is None and "funding_account_id" in attempted:
            account = accounts.get(attempted.get("funding_account_id"))
            if account is None:
                failure = ("funding_account_id", "unknown_real_account")
            elif not account.get("real_account"):
                failure = ("funding_account_id", "real_financial_account_required")
            elif account.get("kind") != "asset":
                failure = ("funding_account_id", "asset_account_required")
            elif not account.get("owned_by_user"):
                failure = ("funding_account_id", "owned_account_required")
        if failure is None and items:
            currencies = {item.get("currency") for item in items if isinstance(item, dict)}
            if len(currencies) != 1 or attempted.get("currency") not in currencies:
                failure = ("items", "single_currency_required")
        if failure is None:
            _fail(attempted_path, "does not reproduce a registered manual merged-payment failure")
    else:
        _fail(
            operation_path + ".outcome.reason_code",
            "this RG-05 action only registers identity_conflict rejection",
        )

    field, reason = failure
    expected_path = f"$.attempted_input.{field}"
    if operation["outcome"]["field_path"] != expected_path:
        _fail(operation_path + ".outcome.field_path", f"must be {expected_path!r}")
    if operation["outcome"]["reason_code"] != reason:
        _fail(operation_path + ".outcome.reason_code", f"must be {reason!r}")


def _validate_rejected_rg06_attempt(
    operation: dict[str, Any], operation_path: str, baseline: dict[str, Any]
) -> None:
    attempted = operation["attempted_input"]
    attempted_path = operation_path + ".attempted_input"
    action = operation["action_type"]
    accounts = {item["id"]: item for item in baseline["catalog"]["accounts"]}
    categories = {item["id"]: item for item in baseline["catalog"]["categories"]}
    lifecycles = [
        item["payload"]
        for item in baseline["domain_entities"]
        if item["type"] == "staged_payment_lifecycle"
    ]
    lifecycle = lifecycles[0] if len(lifecycles) == 1 else None
    failure: tuple[str, str] | None = None

    if action == "create_staged_payment":
        if "total_amount" in attempted:
            if Decimal(attempted["total_amount"]) <= 0:
                failure = ("total_amount", "must_be_positive")
        elif "category_id" in attempted:
            category_id = attempted["category_id"]
            category = categories.get(category_id) if isinstance(category_id, str) else None
            if category_id is None or (
                category is not None and category.get("parent_id") is None
            ):
                failure = ("category_id", "secondary_category_required")
            elif category is not None and not category.get("active"):
                failure = ("category_id", "category_inactive")
            else:
                posting_account = (
                    accounts.get(category.get("posting_account_id"))
                    if category is not None
                    else None
                )
                if posting_account is None or posting_account.get("kind") != "expense":
                    failure = ("category_id", "expense_category_required")
    elif action == "record_staged_payment_installment":
        if "payment_amount" in attempted:
            payment = Decimal(attempted["payment_amount"])
            if payment <= 0:
                failure = ("payment_amount", "must_be_positive")
            elif attempted.get("payment_role") == "deposit":
                if lifecycle is None:
                    _fail(
                        attempted_path,
                        "deposit rejection requires exactly one baseline staged-payment lifecycle",
                    )
                if payment >= Decimal(lifecycle["total_amount"]):
                    failure = (
                        "payment_amount",
                        "deposit_must_be_less_than_total",
                    )
            elif attempted.get("payment_role") == "final":
                if lifecycle is None:
                    _fail(
                        attempted_path,
                        "final rejection requires exactly one baseline staged-payment lifecycle",
                    )
                due = Decimal(lifecycle["due_amount"])
                if payment > due:
                    failure = ("payment_amount", "payment_exceeds_due")
                elif payment != due:
                    failure = (
                        "payment_amount",
                        "final_must_equal_remaining_due",
                    )
        elif {"total_currency", "payment_currency"}.issubset(attempted):
            if attempted["total_currency"] != attempted["payment_currency"]:
                failure = ("currency", "single_currency_required")
        elif "funding_account_id" in attempted:
            account = accounts.get(attempted["funding_account_id"])
            if account is None:
                failure = ("funding_account_id", "unknown_real_account")
            elif not account.get("real_account"):
                failure = (
                    "funding_account_id",
                    "real_financial_account_required",
                )
            elif not account.get("owned_by_user"):
                failure = ("funding_account_id", "owned_account_required")
            elif account.get("kind") != "asset":
                failure = ("funding_account_id", "asset_account_required")
    elif action == "confirm_staged_payment_completion":
        if attempted.get("payment_progress") == "paid_in_full":
            if lifecycle is None:
                _fail(
                    attempted_path,
                    "completion rejection requires exactly one baseline staged-payment lifecycle",
                )
            if Decimal(lifecycle["due_amount"]) != 0:
                failure = ("payment_progress", "due_must_be_zero")
    else:
        _fail(operation_path + ".action_type", "unregistered RG-06 rejected action")

    if failure is None:
        _fail(attempted_path, "does not reproduce a registered RG-06 rejection")
    field, reason = failure
    expected_path = f"$.attempted_input.{field}"
    if operation["outcome"]["field_path"] != expected_path:
        _fail(operation_path + ".outcome.field_path", f"must be {expected_path!r}")
    if operation["outcome"]["reason_code"] != reason:
        _fail(operation_path + ".outcome.reason_code", f"must be {reason!r}")


def _validate_rg05_identity_conflict(
    operation: dict[str, Any],
    operation_path: str,
    earlier_operations: list[dict[str, Any]],
) -> None:
    if (
        operation["outcome"]["status"] != "rejected"
        or operation["outcome"].get("reason_code") != "identity_conflict"
        or operation["action_type"]
        not in {
            "manual_merged_payment",
            "ingest_merged_payment_facts",
            "confirm_merged_payment_candidate",
            "merge_item_receipt_evidence",
        }
    ):
        return
    attempted = operation["attempted_input"]
    request_id = attempted.get("request_id")
    accepted = [
        item
        for item in earlier_operations
        if item["action_type"] == operation["action_type"]
        and item["outcome"]["status"] == "accepted"
        and item.get("input", {}).get("request_id") == request_id
    ]
    if not accepted:
        _fail(
            operation_path + ".attempted_input.request_id",
            "identity_conflict requires the same root's earlier accepted request identity",
        )
    if _contract_equivalent(attempted, accepted[-1]["input"]):
        _fail(
            operation_path + ".attempted_input",
            "unchanged canonical input is an idempotent replay, not identity_conflict",
        )


def _validate_rg10_structural_input(
    action: str,
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

    if action not in {"ingest_stored_value_recharge_candidate", "ingest_stored_value_spend_candidate"}:
        # Ingest lot_id is the candidate's proposed lot fact (the frozen v1 complete
        # import declares lot-rg10-import-recharge, which no baseline owns); every other
        # RG-10 lot reference must point at an existing stored_value_lot entity.
        if "lot_id" in input_value and input_value["lot_id"] not in lots:
            _fail(input_path + ".lot_id", "dangling or mistyped lot reference")

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
        # D-083: these five reasons are contract-registered through the reason->field
        # mapping (mirroring the Kotlin rejectInvalidInput table); their predicates are
        # implemented oracle-side, so the registered rejection is accepted as-is and no
        # v2 failure re-derivation applies.
        return

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
                failure = ("credited_amount", "component_sum_mismatch")

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
        if failure is None and stored_id is not None and (
            stored is None
            or stored["kind"] != "asset"
            or not stored["owned_by_user"]
            or "stored_value" not in stored
            or not stored["stored_value"].get("merchant_restricted", False)
        ):
            failure = ("stored_value_account_id", "enabled_restricted_stored_value_asset_required")
        elif (
            failure is None
            and stored is not None
            and "stored_value" in stored
            and not stored["stored_value"]["enabled"]
        ):
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


_PERIODIC_ALLOCATION_ACTIONS = {
    "create_periodic_allocation",
    "recognize_periodic_allocation_installment",
    "revise_periodic_allocation",
    "correct_transaction_version",
}


def _attempted_decimal_value(
    value: Any,
    currency: Any,
    precisions: dict[str, int],
) -> Decimal | None:
    if not isinstance(value, str) or not isinstance(currency, str):
        return None
    precision = precisions.get(currency)
    if precision is None:
        if not re.fullmatch(r"^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$", value):
            return None
    else:
        pattern = r"^-?(?:0|[1-9][0-9]*)$" if precision == 0 else rf"^-?(?:0|[1-9][0-9]*)\.[0-9]{{{precision}}}$"
        if not re.fullmatch(pattern, value):
            return None
    try:
        return Decimal(value)
    except InvalidOperation:
        return None


def _periodic_allocation_rejection(
    action: str,
    attempted: dict[str, Any],
    baseline: dict[str, Any],
    precisions: dict[str, int],
    timezone: ZoneInfo,
) -> tuple[str, str] | None:
    accounts = {item["id"]: item for item in baseline["catalog"]["accounts"]}
    entities = {item["id"]: item for item in baseline["domain_entities"]}
    transactions = {item["id"]: item for item in baseline["transactions"]}
    currency = attempted.get("currency")
    amount_field = "remaining_amount" if action == "revise_periodic_allocation" else "amount"
    if action != "correct_transaction_version":
        amount = _attempted_decimal_value(attempted.get(amount_field), currency, precisions)
        if amount is None:
            return "exact_decimal_string_required", amount_field
        if amount <= 0:
            return "must_be_positive", amount_field
        if currency not in precisions:
            return "unsupported_currency", "currency"

    if action == "create_periodic_allocation":
        anchor = attempted.get("anchor")
        if not isinstance(anchor, dict) or set(anchor) not in ({"type"}, {"type", "day"}) or anchor.get("type") not in {"month_end", "day_of_month"} or (anchor.get("type") == "month_end" and set(anchor) != {"type"}) or (anchor.get("type") == "day_of_month" and (set(anchor) != {"type", "day"} or not isinstance(anchor.get("day"), int) or not 1 <= anchor["day"] <= 28)):
            return "invalid_anchor", "anchor"
        if not isinstance(attempted.get("installment_count"), int) or attempted["installment_count"] < 1:
            return "invalid_installment_count", "installment_count"
        payment = accounts.get(attempted.get("payment_account_id"))
        prepaid = accounts.get(attempted.get("prepaid_account_id"))
        if payment is None or prepaid is None or payment["currency"] != currency or prepaid["currency"] != currency:
            return "currency_mismatch", "currency"
        start = _local_datetime(attempted["start_at"], "$.attempted_input.start_at", timezone)
        if start.day != _anchor_day(start.year, start.month, anchor):
            return "invalid_anchor", "anchor"
        return None

    if action == "recognize_periodic_allocation_installment":
        schedule = entities.get(attempted.get("schedule_id"))
        installment = entities.get(attempted.get("installment_id"))
        if schedule is None or schedule.get("type") != "periodic_allocation_schedule" or installment is None or installment.get("type") != "periodic_allocation_installment" or installment["payload"]["schedule_id"] != schedule["id"]:
            return "installment_not_pending", "installment_id"
        statuses = _periodic_allocation_statuses(baseline, _state_indexes(baseline, "$.attempted_input"))
        if statuses.get(("domain_entity", installment["id"], "allocation_status")) != "pending":
            return "installment_not_pending", "installment_id"
        prepaid_account_id = schedule["payload"]["prepaid_account_id"]
        balance = next((Decimal(item["amount"]) for item in baseline["balances"] if item["account_id"] == prepaid_account_id and item["currency"] == currency), Decimal(0))
        if amount > balance:
            return "exceeds_remaining_prepaid", "amount"
        if currency != installment["payload"]["currency"]:
            return "currency_mismatch", "currency"
        if amount != Decimal(installment["payload"]["amount"]):
            return "installment_amount_mismatch", "amount"
        return None

    if action == "revise_periodic_allocation":
        schedule = entities.get(attempted.get("schedule_id"))
        if schedule is None or schedule.get("type") != "periodic_allocation_schedule":
            return "invalid_revision_boundary", "recognized_through"
        revisions = sorted(
            (item for item in baseline["domain_entities"] if item["type"] == "periodic_allocation_revision" and item["payload"]["schedule_id"] == schedule["id"]),
            key=lambda item: item["payload"]["revision_number"],
        )
        current_ids = revisions[-1]["payload"]["installment_ids"]
        recognized = {link["from"]["id"] for link in baseline["audit_links"] if link["type"] == "periodic_allocation_recognition"}
        flags = [item_id in recognized for item_id in current_ids]
        if not any(flags) or any(flags[index] and not flags[index - 1] for index in range(1, len(flags))):
            return "invalid_revision_boundary", "recognized_through"
        boundary = current_ids[max(index for index, value in enumerate(flags) if value)]
        if attempted.get("recognized_through") != boundary:
            return "invalid_revision_boundary", "recognized_through"
        if not isinstance(attempted.get("remaining_installment_count"), int) or attempted["remaining_installment_count"] < 1:
            return "invalid_installment_count", "remaining_installment_count"
        if currency != schedule["payload"]["currency"]:
            return "currency_mismatch", "currency"
        recognized_total = sum(
            (Decimal(entities[item_id]["payload"]["amount"]) for item_id in recognized if item_id in entities and entities[item_id]["payload"].get("schedule_id") == schedule["id"]),
            Decimal(0),
        )
        if amount != Decimal(schedule["payload"]["total_amount"]) - recognized_total:
            return "remaining_amount_mismatch", "remaining_amount"
        return None

    transaction = transactions.get(attempted.get("transaction_id"))
    if transaction is None or transaction["type"] != "prepaid_recognition":
        return "transaction_not_correctable", "transaction_id"
    return None


def _validate_rejected_periodic_allocation_attempt(
    operation: dict[str, Any],
    operation_path: str,
    baseline: dict[str, Any],
    precisions: dict[str, int],
    timezone: ZoneInfo,
) -> None:
    failure = _periodic_allocation_rejection(
        operation["action_type"], operation["attempted_input"], baseline, precisions, timezone
    )
    if failure is None:
        _fail(operation_path + ".attempted_input", "does not reproduce a registered rejection")
    reason, field = failure
    if operation["outcome"]["reason_code"] != reason:
        _fail(operation_path + ".outcome.reason_code", f"must be {reason!r} for the first failing attempted field")
    expected_path = f"$.attempted_input.{field}"
    if operation["outcome"]["field_path"] != expected_path:
        _fail(operation_path + ".outcome.field_path", f"must be {expected_path!r}")


def _validate_rejected_rg07_attempt(
    operation: dict[str, Any],
    operation_path: str,
    baseline: dict[str, Any],
) -> None:
    action = operation["action_type"]
    attempted = operation["attempted_input"]
    if action == "confirm_manual_refund_receipt":
        failure = ("arrival_confirmation_required", "arrival_confirmed")
    elif action == "allocate_refund_receipt":
        if Decimal(attempted["requested_allocation"]) <= Decimal(
            attempted["available_allocation"]
        ):
            _fail(
                operation_path + ".attempted_input.requested_allocation",
                "does not exceed the available refund allocation",
            )
        failure = ("refund_amount_exceeds_remaining_refundable", "requested_allocation")
    elif action == "confirm_imported_refund":
        if not attempted.get("original_transaction_id"):
            failure = ("original_transaction_confirmation_required", "original_transaction_id")
        elif not attempted.get("category_id") or not attempted.get("allocated_amount"):
            failure = ("category_allocation_confirmation_required", "category_id")
        elif not attempted.get("destination_account_id"):
            failure = ("destination_confirmation_required", "destination_account_id")
        elif (
            not attempted.get("arrived_at")
            or not attempted.get("confirmed_at")
            or attempted.get("arrival_confirmed") is not True
        ):
            failure = ("arrival_confirmation_required", "arrival_confirmed")
        else:
            _fail(
                operation_path + ".attempted_input",
                "does not omit a registered imported-refund confirmation",
            )
    elif action == "validate_refund_receipt":
        transactions = {item["id"]: item for item in baseline["transactions"]}
        versions = {item["id"]: item for item in baseline["transaction_versions"]}
        posting_sets = {item["id"]: item for item in baseline["posting_sets"]}
        postings = {item["id"]: item for item in baseline["postings"]}
        accounts = {item["id"]: item for item in baseline["catalog"]["accounts"]}
        original_id = attempted.get("original_transaction_id")
        original = transactions.get(original_id) if original_id is not None else None
        original_posting = None
        if original is not None:
            version = versions.get(original["current_version_id"])
            posting_set = posting_sets.get(version["posting_set_id"]) if version else None
            if posting_set is not None:
                expense_postings = [
                    postings[posting_id]
                    for posting_id in posting_set["posting_ids"]
                    if postings[posting_id].get("role") == "expense"
                    and Decimal(postings[posting_id]["amount"]) > 0
                ]
                if len(expense_postings) == 1:
                    original_posting = expense_postings[0]
        amount = attempted.get("amount")
        destination_id = attempted.get("destination_account_id")
        destination = accounts.get(destination_id) if destination_id is not None else None
        if amount is not None and Decimal(str(amount)) <= 0:
            failure = ("must_be_positive", "amount")
        elif original_id is None:
            failure = ("original_transaction_confirmation_required", "original_transaction_id")
        elif original is None or original["type"] != "expense" or original_posting is None:
            failure = ("effective_confirmed_original_expense_required", "original_transaction_id")
        elif attempted.get("currency") != original_posting["currency"]:
            failure = ("same_currency_required", "currency")
        elif destination is None:
            failure = ("known_destination_account_required", "destination_account_id")
        elif not (
            destination["owned_by_user"]
            and destination["real_account"]
            and destination["kind"] == "asset"
            and destination["reconciliation_eligible"]
        ):
            failure = ("owned_real_asset_destination_required", "destination_account_id")
        elif attempted.get("category_id") != original_posting.get("category_id"):
            failure = ("exact_original_secondary_category_required", "category_id")
        elif (
            amount is not None
            and attempted.get("remaining_refundable") is not None
            and Decimal(str(amount)) > Decimal(str(attempted["remaining_refundable"]))
        ):
            failure = ("refund_amount_exceeds_remaining_refundable", "amount")
        elif attempted.get("destination_confirmed") is False:
            failure = ("destination_confirmation_required", "destination_account_id")
        else:
            _fail(
                operation_path + ".attempted_input",
                "does not reproduce a registered refund validation rejection",
            )
    else:
        _fail(operation_path + ".action_type", "unregistered RG-07 rejection")

    reason, field = failure
    outcome = operation["outcome"]
    if outcome["reason_code"] != reason:
        _fail(operation_path + ".outcome.reason_code", f"must be {reason!r}")
    expected_path = f"$.attempted_input.{field}"
    if outcome["field_path"] != expected_path:
        _fail(operation_path + ".outcome.field_path", f"must be {expected_path!r}")


def _validate_rejected_rg09_attempt(
    operation: dict[str, Any],
    operation_path: str,
    baseline: dict[str, Any],
) -> None:
    action = operation["action_type"]
    attempted = operation["attempted_input"]
    if action == "reject_stale_preview":
        failure = ("ledger_changed_since_preview", "current_ledger_fingerprint")
    elif action == "reject_incomplete_import_confirmation":
        if "transaction_id" in attempted and attempted["transaction_id"] is None:
            failure = ("exact_transaction_required", "transaction_id")
        elif "target_account_id" in attempted and attempted["target_account_id"] is None:
            failure = ("exact_target_account_required", "target_account_id")
        elif "actual_at" in attempted and attempted["actual_at"] is None:
            failure = ("actual_time_required", "actual_at")
        elif "currency" in attempted and attempted["currency"] is None:
            failure = ("exact_currency_required", "currency")
        elif "allocation_amount" in attempted and attempted["allocation_amount"] is None:
            failure = ("explicit_explanation_allocation_required", "allocation_amount")
        else:
            _fail(
                operation_path + ".attempted_input",
                "does not omit a registered imported-confirmation fact",
            )
    elif action == "reject_invalid_rg09_input":
        accounts = {item["id"]: item for item in baseline["catalog"]["accounts"]}
        if "target_amount" in attempted and not isinstance(attempted["target_amount"], str):
            failure = ("exact_decimal_string_required", "target_amount")
        elif "target_observed_at" in attempted and not _TIMESTAMP_PATTERN.fullmatch(
            attempted["target_observed_at"] or ""
        ):
            failure = ("timezone_aware_target_time_required", "target_observed_at")
        elif "target_observed_at" in attempted:
            failure = ("ledger_timezone_required", "target_observed_at")
        elif "account_id" in attempted and attempted["account_id"] not in accounts:
            failure = ("unknown_account", "account_id")
        elif "account_id" in attempted:
            account = accounts[attempted["account_id"]]
            if not (
                account["owned_by_user"]
                and account["real_account"]
                and account["kind"] == "asset"
            ):
                failure = ("owned_real_asset_required", "account_id")
            else:
                failure = ("same_target_account_required", "account_id")
        elif "currency" in attempted and attempted["currency"] != "CNY":
            failure = ("same_currency_required", "currency")
        elif "equity_account_id" in attempted and attempted["equity_account_id"] != "equity-balance-adjustments":
            failure = ("dedicated_adjustment_equity_required", "equity_account_id")
        elif "direction" in attempted and attempted["direction"] != "increase_target_account":
            failure = ("explanation_direction_mismatch", "direction")
        elif "actual_at" in attempted:
            observation = next(
                (
                    entity
                    for entity in baseline["domain_entities"]
                    if entity["type"] == "target_balance_observation"
                ),
                None,
            )
            target_observed_at = (
                observation["payload"]["observed_at"] if observation is not None else None
            )
            if (
                target_observed_at is not None
                and _timestamp_instant(attempted["actual_at"])
                >= _timestamp_instant(target_observed_at)
            ):
                failure = ("explanation_must_not_follow_target_time", "actual_at")
            else:
                _fail(
                    operation_path + ".attempted_input",
                    "does not reproduce a registered RG-09 input rejection",
                )
        elif "requested_amount" in attempted and Decimal(attempted["requested_amount"]) > Decimal(
            attempted["remaining_amount"]
        ):
            failure = ("allocation_exceeds_remaining_adjustment", "requested_amount")
        elif "matcher_confidence" in attempted and attempted.get("explicit_confirmation") is not True:
            failure = ("explicit_link_confirmation_required", "explicit_confirmation")
        elif "request_id" in attempted:
            failure = ("idempotency_key_conflict", "request_id")
        else:
            _fail(
                operation_path + ".attempted_input",
                "does not reproduce a registered RG-09 input rejection",
            )
    else:
        _fail(operation_path + ".action_type", "unregistered RG-09 rejection")

    reason, field = failure
    outcome = operation["outcome"]
    if outcome["reason_code"] != reason:
        _fail(operation_path + ".outcome.reason_code", f"must be {reason!r}")
    expected_path = f"$.attempted_input.{field}"
    if outcome["field_path"] != expected_path:
        _fail(operation_path + ".outcome.field_path", f"must be {expected_path!r}")


def _validate_imported_source_binding(
    baseline: dict[str, Any],
    operation_path: str,
    input_value: dict[str, Any],
) -> None:
    """Confirmed imports must reference the exact pending imported candidate's source.

    The intake source named by the confirmation input must be the sole source of the
    baseline's imported candidate (v1 import_path.pending), and must not be shared with
    or replaced by any other candidate or import.
    """
    candidates = [
        item
        for item in baseline["candidates"]
        if item.get("type") == "omitted_real_transaction_and_adjustment_explanation"
    ]
    if len(candidates) != 1:
        _fail(
            operation_path + ".input.source_id",
            "requires exactly one pending imported-transfer candidate in the baseline",
        )
    candidate = candidates[0]
    source_ids = candidate.get("source_ids")
    if source_ids != [input_value["source_id"]]:
        _fail(
            operation_path + ".input.source_id",
            "must be the exact imported candidate source and must not be reused for a different import",
        )
    source = next(
        (item for item in baseline["sources"] if item["id"] == input_value["source_id"]),
        None,
    )
    if source is None or source.get("type") != "imported_transfer_candidate":
        _fail(
            operation_path + ".input.source_id",
            "must reference the imported transfer candidate source",
        )
    shared = [
        item["id"]
        for item in baseline["candidates"]
        if item.get("source_ids") == source_ids and item["id"] != candidate["id"]
    ]
    if shared:
        _fail(
            operation_path + ".input.source_id",
            "source is bound to more than one candidate and cannot confirm a different import",
        )


def _validate_rg07_action_input(
    operation: dict[str, Any],
    operation_path: str,
    baseline: dict[str, Any],
    precisions: dict[str, int],
    timezone: ZoneInfo,
) -> None:
    action = operation["action_type"]
    value = operation["input"]
    input_path = operation_path + ".input"
    accounts = {item["id"]: item for item in baseline["catalog"]["accounts"]}
    categories = {item["id"]: item for item in baseline["catalog"]["categories"]}
    transactions = {item["id"]: item for item in baseline["transactions"]}
    versions = {item["id"]: item for item in baseline["transaction_versions"]}
    posting_sets = {item["id"]: item for item in baseline["posting_sets"]}
    postings = {item["id"]: item for item in baseline["postings"]}

    for field in (
        "requested_at",
        "approved_at",
        "processor_reported_at",
        "observed_at",
        "source_observed_at",
        "booking_at",
        "value_at",
        "arrived_at",
        "confirmed_at",
    ):
        if field in value:
            _timestamp(value[field], input_path + "." + field, timezone)
    currency = value.get("currency")
    if currency is not None and currency not in precisions:
        _fail(input_path + ".currency", "undeclared currency")
    for field in ("requested_amount", "amount", "allocated_amount"):
        if field in value:
            amount_currency = currency or (
                value.get("currency") if field != "allocated_amount" else "CNY"
            )
            amount = _amount(value[field], amount_currency, input_path + "." + field, precisions)
            if amount <= 0 and action != "attach_original_payment_evidence":
                _fail(input_path + "." + field, "must be positive")

    original_id = value.get("original_transaction_id")
    original_posting = None
    if original_id is not None:
        original = transactions.get(original_id)
        if original is None or original["type"] != "expense":
            _fail(input_path + ".original_transaction_id", "must reference the original expense")
        version = versions.get(original["current_version_id"])
        posting_set = posting_sets.get(version["posting_set_id"]) if version else None
        expense_postings = (
            [
                postings[posting_id]
                for posting_id in posting_set["posting_ids"]
                if postings[posting_id].get("role") == "expense"
                and Decimal(postings[posting_id]["amount"]) > 0
            ]
            if posting_set is not None
            else []
        )
        if len(expense_postings) != 1:
            _fail(input_path + ".original_transaction_id", "must have one current positive expense posting")
        original_posting = expense_postings[0]
        if currency is not None and original_posting["currency"] != currency:
            _fail(input_path + ".currency", "must match the original expense currency")

    if "category_id" in value:
        category = categories.get(value["category_id"])
        if (
            category is None
            or category["parent_id"] is None
            or not category["active"]
            or original_posting is None
            or category["posting_account_id"] != original_posting["account_id"]
        ):
            _fail(input_path + ".category_id", "must be the original exact active secondary category")
    account_field = "account_id" if "account_id" in value else "destination_account_id"
    if account_field in value:
        account = accounts.get(value[account_field])
        if account is None or not (
            account["owned_by_user"]
            and account["real_account"]
            and account["kind"] == "asset"
            and account["reconciliation_eligible"]
        ):
            _fail(input_path + "." + account_field, "must reference an owned eligible real asset")
        if currency is not None and account["currency"] != currency:
            _fail(input_path + ".currency", "must match the destination account currency")

    if action == "ingest_refund_status_source":
        if value["reported_state"] not in {"requested", "approved", "processing"}:
            _fail(input_path + ".reported_state", "merchant status evidence cannot report receipt")
        if value["proves_arrival"]:
            _fail(input_path + ".proves_arrival", "merchant status evidence cannot prove asset arrival")
        relation = next((item for item in baseline["relations"] if item["id"] == value["refund_relation_id"]), None)
        if relation is None or relation["type"] != "refund":
            _fail(input_path + ".refund_relation_id", "must reference the exact refund relation")
    if action == "attach_original_payment_evidence":
        posting = postings.get(value["payment_asset_posting_id"])
        if posting is None or posting.get("role") != "payment_asset":
            _fail(input_path + ".payment_asset_posting_id", "must reference the original payment-asset posting")
        if not posting["reconciliation_eligible"]:
            _fail(input_path + ".payment_asset_posting_id", "must reference an eligible real-account posting")
        if Decimal(value["amount"]) >= 0:
            _fail(input_path + ".amount", "must preserve the signed negative original debit")
        if posting["amount"] != value["amount"] or posting["currency"] != value["currency"]:
            _fail(input_path + ".amount", "must exactly match the original payment-asset posting")
    if action == "confirm_manual_refund_receipt":
        relation = next(
            (item for item in baseline["relations"] if item["id"] == value["refund_relation_id"]),
            None,
        )
        if relation is None or relation["type"] != "refund":
            _fail(input_path + ".refund_relation_id", "must reference the existing refund relation")
        if not any(ref["id"] == value["original_transaction_id"] for ref in relation["member_refs"]):
            _fail(input_path + ".original_transaction_id", "must match the refund relation original")
    if action in {"attach_refund_destination_evidence", "attach_refund_dual_role_evidence"}:
        relation = next((item for item in baseline["relations"] if item["id"] == value["refund_relation_id"]), None)
        if relation is None or relation["type"] != "refund":
            _fail(input_path + ".refund_relation_id", "must reference the exact refund relation")
        posting = postings.get(value["destination_asset_posting_id"])
        if posting is None or posting.get("role") != "destination_asset":
            _fail(input_path + ".destination_asset_posting_id", "must reference the exact refund destination posting")
        if not any(ref["id"] == posting["id"] or ref["id"] == value["refund_relation_id"] for ref in relation["member_refs"]):
            refund_ids = {ref["id"] for ref in relation["member_refs"]}
            owners = {tx_id for tx_id in refund_ids if transactions.get(tx_id, {}).get("type") == "refund_receipt"}
            posting_owner = next((tx_id for tx_id in owners if postings[value["destination_asset_posting_id"]]["posting_set_id"] == versions[transactions[tx_id]["current_version_id"]]["posting_set_id"]), None)
            if posting_owner is None:
                _fail(input_path + ".destination_asset_posting_id", "posting must belong to the named refund relation")
    if action == "attach_refund_dual_role_evidence" and (
        len(value["roles"]) != 2 or set(value["roles"]) != {"refund_relationship", "destination_asset_posting"}
    ):
        _fail(input_path + ".roles", "must be the exact set-like dual-role pair")
    if action == "confirm_imported_refund":
        candidate = next(
            (item for item in baseline["candidates"] if item["id"] == value["candidate_id"]),
            None,
        )
        if candidate is None or candidate["type"] != "refund_credit":
            _fail(input_path + ".candidate_id", "must reference a pending refund credit candidate")
        allowed_statuses = (
            {"pending_confirmation"}
            if operation["outcome"]["status"] == "accepted"
            else {"confirmed"}
        )
        if candidate["status_history"][-1]["status"] not in allowed_statuses:
            _fail(input_path + ".candidate_id", "candidate must be pending confirmation")
        if Decimal(value["allocated_amount"]) != Decimal(candidate["payload"]["proposed_amount"]):
            _fail(input_path + ".allocated_amount", "must exactly match the candidate proposed amount")
        if value["destination_account_id"] != candidate["payload"]["proposed_destination_account_id"]:
            _fail(input_path + ".destination_account_id", "must exactly match the candidate source account")


def _rg08_rejection_failure(
    action: str,
    attempted: dict[str, Any],
    baseline: dict[str, Any],
    precisions: dict[str, int],
) -> tuple[str, str] | None:
    accounts = {item["id"]: item for item in baseline["catalog"]["accounts"]}
    categories = {item["id"]: item for item in baseline["catalog"]["categories"]}
    positions = [
        item for item in baseline["domain_entities"]
        if item.get("type") == "lending_position"
    ]

    if action == "confirm_imported_lending_collection":
        if set(attempted) != {"confirmation_request_id", "missing_field"}:
            return None
        return _RG08_INCOMPLETE_FAILURES.get(attempted["missing_field"])

    if action == "validate_lending_event":
        funding_id = attempted.get("funding_account_id")
        if funding_id is not None and funding_id not in accounts:
            return "unknown_account", "funding_account_id"
        return None

    def attempted_decimal(field: str) -> Decimal | None:
        value = attempted.get(field)
        if not isinstance(value, str) or not _DECIMAL_PATTERN.fullmatch(value):
            return None
        try:
            return Decimal(value)
        except InvalidOperation:
            return None

    total = attempted_decimal("total_received")
    if "total_received" not in attempted or total is None:
        return "exact_decimal_string_required", "total_received"
    currency = attempted.get("currency", "CNY")
    precision = precisions.get(currency)
    if precision is not None and not re.fullmatch(
        r"^(?:0|-?[1-9][0-9]*)$" if precision == 0
        else rf"^(?:0|-?[1-9][0-9]*)\.[0-9]{{{precision}}}$",
        attempted["total_received"],
    ):
        return "exact_decimal_string_required", "total_received"
    if total <= 0:
        return "total_must_be_positive", "total_received"

    component_fields = ("principal_amount", "interest_amount", "fee_amount")
    components = {field: attempted_decimal(field) for field in component_fields}
    if all(value is not None for value in components.values()):
        if sum(components.values(), Decimal(0)) != total:
            return "components_must_equal_total", "components"
        # D-090 intentionally mirrors either negative principal or negative interest
        # at the principal field path.
        if components["principal_amount"] < 0 or components["interest_amount"] < 0:
            return "component_must_be_nonnegative", "principal_amount"
        if components["fee_amount"] < 0:
            return "fee_must_be_zero_in_rg08_v1", "fee_amount"
        if components["fee_amount"] > 0:
            return "nonzero_fee_accounting_out_of_scope", "fee_amount"
        outstanding = sum(
            (Decimal(item["payload"]["principal_balance"]) for item in positions),
            Decimal(0),
        )
        if components["principal_amount"] > outstanding:
            return "principal_exceeds_outstanding_position", "principal_amount"

    destination_id = attempted.get("destination_account_id")
    if destination_id is not None:
        destination = accounts.get(destination_id)
        if destination is None:
            return "unknown_account", "destination_account_id"
        if destination["kind"] != "asset" or not destination["real_account"]:
            return "financial_asset_account_required", "destination_account_id"
        if not destination["owned_by_user"]:
            return "owned_account_required", "destination_account_id"
    if attempted.get("counterparty_id") is not None:
        known_counterparties = {
            item["payload"]["counterparty_id"] for item in positions
        }
        if attempted["counterparty_id"] not in known_counterparties:
            return "unknown_counterparty", "counterparty_id"
    if attempted.get("behavior_code") is not None and attempted["behavior_code"] != "collect":
        return "invalid_lending_behavior", "behavior_code"
    if attempted.get("split_source") is not None:
        return "explicit_component_split_required", "split_source"
    if attempted.get("currency") is not None and attempted["currency"] not in precisions:
        return "same_currency_required", "currency"
    if attempted.get("interest_category_id") is not None:
        category = categories.get(attempted["interest_category_id"])
        if category is None or not category["active"] or category.get("posting_account_id") is None:
            return "active_exact_interest_category_required", "interest_category_id"
    if action == "allocate_lending_collection" and components["principal_amount"] is not None:
        outstanding = sum(
            (Decimal(item["payload"]["principal_balance"]) for item in positions),
            Decimal(0),
        )
        if components["principal_amount"] > outstanding:
            return "principal_exceeds_outstanding_position", "principal_amount"
    return None


def _validate_rejected_rg08_attempt(
    operation: dict[str, Any],
    operation_path: str,
    baseline: dict[str, Any],
    precisions: dict[str, int],
) -> None:
    failure = _rg08_rejection_failure(
        operation["action_type"], operation["attempted_input"], baseline, precisions
    )
    if failure is None:
        _fail(operation_path + ".attempted_input", "does not reproduce a registered RG-08 rejection")
    reason, field = failure
    outcome = operation["outcome"]
    if outcome["reason_code"] != reason:
        _fail(operation_path + ".outcome.reason_code", f"must be {reason!r} for the first failing attempted field")
    expected_path = f"$.attempted_input.{field}"
    if outcome["field_path"] != expected_path:
        _fail(operation_path + ".outcome.field_path", f"must be {expected_path!r}")


def _validate_action_input(
    operation: dict[str, Any],
    operation_path: str,
    baseline: dict[str, Any],
    precisions: dict[str, int],
    timezone: ZoneInfo,
) -> None:
    action = operation["action_type"]
    if operation["outcome"]["status"] == "rejected":
        if action in _RG08_ACTIONS:
            _validate_rejected_rg08_attempt(
                operation, operation_path, baseline, precisions
            )
            return
        if action == "manual_expense":
            _validate_rejected_manual_expense_attempt(
                operation, operation_path, baseline, precisions, timezone
            )
        elif action == "manual_income":
            _validate_rejected_manual_income_attempt(
                operation, operation_path, baseline, precisions, timezone
            )
        elif action == "manual_account_transfer":
            _validate_rejected_manual_account_transfer_attempt(
                operation, operation_path, baseline, precisions, timezone
            )
        elif action == "manual_mixed_expense":
            _validate_rejected_manual_mixed_expense_attempt(
                operation, operation_path, baseline
            )
            return
        elif action in {
            "manual_merged_payment",
            "ingest_merged_payment_facts",
            "confirm_merged_payment_candidate",
            "merge_item_receipt_evidence",
        }:
            _validate_rejected_rg05_attempt(operation, operation_path, baseline)
            return
        elif action in {
            "create_staged_payment",
            "record_staged_payment_installment",
            "confirm_staged_payment_completion",
        }:
            _validate_rejected_rg06_attempt(operation, operation_path, baseline)
            return
        elif action in _RG07_ACTIONS:
            _validate_rejected_rg07_attempt(operation, operation_path, baseline)
            return
        elif action in _RG09_REJECTED_ACTIONS:
            _validate_rejected_rg09_attempt(operation, operation_path, baseline)
            return
        elif action in _RG10_REJECTED_ACTIONS:
            _validate_rejected_rg10_attempt(
                operation,
                operation_path,
                baseline,
                precisions,
                timezone,
            )
        elif action in _PERIODIC_ALLOCATION_ACTIONS:
            if action == "correct_transaction_version" and operation["attempted_input"].get("correction_kind") == "posting_facts":
                failure = _posting_facts_correction_failure(
                    operation["attempted_input"], baseline, precisions
                )
                if failure is None:
                    _fail(operation_path + ".attempted_input", "does not reproduce a registered rejection")
                reason, field = failure
                if operation["outcome"]["reason_code"] != reason:
                    _fail(operation_path + ".outcome.reason_code", f"must be {reason!r} for the first failing attempted field")
                expected_path = f"$.attempted_input.{field}"
                if operation["outcome"]["field_path"] != expected_path:
                    _fail(operation_path + ".outcome.field_path", f"must be {expected_path!r}")
            else:
                _validate_rejected_periodic_allocation_attempt(
                    operation, operation_path, baseline, precisions, timezone
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

    if action in _RG08_ACTIONS:
        variant = input_value["variant"]
        allowed_variants = {
            "validate_lending_event": {"lend", "rename_counterparty"},
            "validate_lending_settlement": {"manual_collection"},
            "confirm_imported_lending_collection": {"import_intake", "formal_confirmation", "mirror_merge"},
            "allocate_lending_collection": {"maximum_allocation"},
            "retry_idempotent_input": {"retry"},
        }
        if variant not in allowed_variants[action]:
            _fail(input_path + ".variant", "does not belong to the RG-08 action")
        if action == "retry_idempotent_input":
            return
        if variant == "rename_counterparty":
            return
        for field in ("currency",):
            if field in input_value and input_value[field] not in precisions:
                _fail(input_path + ".currency", "currency is not declared")
        for field in ("total_received", "principal_amount", "interest_amount", "fee_amount"):
            if field in input_value:
                value = _amount(input_value[field], input_value["currency"], input_path + "." + field, precisions)
                if field == "total_received" and value <= 0:
                    _fail(input_path + ".total_received", "must be positive")
                if field != "total_received" and value < 0:
                    _fail(input_path + "." + field, "must be nonnegative")
        if "fee_amount" in input_value and _decimal(input_value["fee_amount"], input_path + ".fee_amount") != 0:
            _fail(input_path + ".fee_amount", "RG-08 fee must be zero")
        if all(field in input_value for field in ("total_received", "principal_amount", "interest_amount", "fee_amount")):
            if sum((_decimal(input_value[field], input_path + "." + field) for field in ("principal_amount", "interest_amount", "fee_amount")), Decimal(0)) != _decimal(input_value["total_received"], input_path + ".total_received"):
                _fail(input_path + ".components", "principal, interest, and fee must equal total_received")
        if variant == "lend":
            account = accounts.get(input_value["funding_account_id"])
            if account is None or not (account["kind"] == "asset" and account["owned_by_user"] and account["real_account"]):
                _fail(input_path + ".funding_account_id", "must be an owned real funding asset")
        if variant in {"manual_collection", "maximum_allocation", "formal_confirmation"}:
            account = accounts.get(input_value["destination_account_id"])
            if account is None or not (account["kind"] == "asset" and account["owned_by_user"] and account["real_account"]):
                _fail(input_path + ".destination_account_id", "must be an owned real destination asset")
            category = categories.get(input_value["interest_category_id"])
            if category is None or not category["active"] or categories[input_value["interest_category_id"]]["posting_account_id"] is None:
                _fail(input_path + ".interest_category_id", "must be the active exact interest category")
        if variant == "formal_confirmation":
            candidate = candidates.get(input_value["candidate_id"])
            if candidate is None or candidate["type"] != "lending_collection_credit" or candidate["status_history"][-1]["status"] != "pending_confirmation":
                _fail(input_path + ".candidate_id", "must identify the pending lending collection candidate")
            if set(input_value["explicitly_confirmed_fields"]) != {
                "behavior_code", "counterparty_id", "destination_account_id",
                "principal_amount", "interest_and_fee_amounts", "actual_receipt_time",
            }:
                _fail(input_path + ".explicitly_confirmed_fields", "must explicitly confirm all six gates")
        return

    if action in _RG07_ACTIONS:
        _validate_rg07_action_input(
            operation, operation_path, baseline, precisions, timezone
        )
        return

    if action == "manual_mixed_expense":
        for field in ("asset_account_id", "liability_account_id"):
            account = accounts.get(input_value[field])
            if account is None or account["kind"] != ("asset" if field.startswith("asset") else "liability") or not account["owned_by_user"] or not account["real_account"]:
                _fail(input_path + "." + field, "must reference the matching owned real account")
            if account["currency"] != input_value["currency"]:
                _fail(input_path + ".currency", "must match both funding accounts")
        category = categories.get(input_value["category_id"])
        if category is None or category["parent_id"] is None or not category["active"]:
            _fail(input_path + ".category_id", "must reference an active second-level category")
        for field in ("asset_funding_amount", "liability_funding_amount", "total_amount"):
            _amount(input_value[field], input_value["currency"], input_path + "." + field, precisions)
        if Decimal(input_value["asset_funding_amount"]) + Decimal(input_value["liability_funding_amount"]) != Decimal(input_value["total_amount"]):
            _fail(input_path + ".total_amount", "must equal the two funding components")
        explanation = input_value["settlement_explanation"]
        if Decimal(explanation["original_amount"]) - Decimal(explanation["discount_amount"]) != Decimal(explanation["settled_amount"]) or Decimal(explanation["settled_amount"]) != Decimal(input_value["total_amount"]):
            _fail(input_path + ".settlement_explanation", "must reconcile original, discount, settled, and total amounts")
        _timestamp(input_value["occurred_at"], input_path + ".occurred_at", timezone)
    elif action == "manual_merged_payment":
        account = accounts.get(input_value["funding_account_id"])
        if account is None or not (
            account["owned_by_user"] and account["real_account"] and account["kind"] == "asset"
        ):
            _fail(input_path + ".funding_account_id", "must reference an owned real asset account")
        currency = input_value["currency"]
        if account["currency"] != currency:
            _fail(input_path + ".currency", "must match the funding account")
        total = _amount(input_value["total_amount"], currency, input_path + ".total_amount", precisions)
        if total <= 0:
            _fail(input_path + ".total_amount", "must be positive")
        seen_items: set[str] = set()
        item_total = Decimal(0)
        for index, item in enumerate(input_value["items"]):
            item_path = f"{input_path}.items[{index}]"
            if item["item_id"] in seen_items:
                _fail(item_path + ".item_id", "item IDs must be distinct")
            seen_items.add(item["item_id"])
            category = categories.get(item["category_id"])
            posting_account = accounts.get(category.get("posting_account_id")) if category else None
            if (
                category is None
                or category.get("parent_id") is None
                or not category.get("active")
                or posting_account is None
                or posting_account.get("kind") != "expense"
            ):
                _fail(item_path + ".category_id", "must reference an active second-level expense category")
            if item["currency"] != currency or posting_account["currency"] != currency:
                _fail(item_path + ".currency", "must match the payment currency")
            amount = _amount(item["amount"], currency, item_path + ".amount", precisions)
            if amount <= 0:
                _fail(item_path + ".amount", "must be positive")
            item_total += amount
            _timestamp(item["source_observed_at"], item_path + ".source_observed_at", timezone)
        if item_total != total:
            _fail(input_path + ".items", "item amounts must equal payment total")
        _timestamp(input_value["payment_at"], input_path + ".payment_at", timezone)
        explanation = input_value.get("settlement_explanation")
        if explanation is not None and (
            Decimal(explanation["original_amount"]) - Decimal(explanation["discount_amount"])
            != Decimal(explanation["settled_amount"])
            or Decimal(explanation["settled_amount"]) != total
        ):
            _fail(input_path + ".settlement_explanation", "must reconcile to the payment total")
    elif action == "ingest_merged_payment_facts":
        bank = input_value["bank_fact"]
        items = input_value["item_facts"]
        currency = bank["currency"]
        bank_amount = _amount(bank["amount"], currency, input_path + ".bank_fact.amount", precisions)
        if bank_amount >= 0:
            _fail(input_path + ".bank_fact.amount", "must be a negative bank debit")
        _timestamp(bank["observed_at"], input_path + ".bank_fact.observed_at", timezone)
        identities = {bank["source_id"], bank["evidence_id"]}
        item_total = Decimal(0)
        item_ids: set[str] = set()
        for index, item in enumerate(items):
            item_path = f"{input_path}.item_facts[{index}]"
            if item["currency"] != currency:
                _fail(item_path + ".currency", "must match the bank fact currency")
            if item["item_id"] in item_ids:
                _fail(item_path + ".item_id", "item IDs must be distinct")
            item_ids.add(item["item_id"])
            if item["source_id"] in identities or item["evidence_id"] in identities:
                _fail(item_path, "source and evidence identities must be distinct")
            identities.update({item["source_id"], item["evidence_id"]})
            amount = _amount(item["amount"], currency, item_path + ".amount", precisions)
            if amount <= 0:
                _fail(item_path + ".amount", "must be positive")
            item_total += amount
            _timestamp(item["observed_at"], item_path + ".observed_at", timezone)
            if item["suggested_category_id"] not in categories:
                _fail(item_path + ".suggested_category_id", "dangling category suggestion")
        if item_total != -bank_amount:
            _fail(input_path + ".item_facts", "item facts must close to the bank debit")
    elif action == "confirm_merged_payment_candidate":
        candidate = candidates.get(input_value["candidate_id"])
        if candidate is None or candidate.get("type") != "merged_payment":
            _fail(input_path + ".candidate_id", "must reference a merged_payment candidate")
        payload = candidate["payload"]
        replay = operation["outcome"]["status"] == "no_change"
        statuses = [event["status"] for event in candidate["status_history"]]
        if replay:
            if statuses[-1] != "confirmed" or "transaction_id" not in payload:
                _fail(input_path + ".candidate_id", "replay requires a confirmed bound candidate")
        elif statuses != ["pending_confirmation"] or "transaction_id" in payload:
            _fail(input_path + ".candidate_id", "fresh confirmation requires a pending candidate")
        account = accounts.get(input_value["funding_account_id"])
        currency = payload["currency"]
        if account is None or not (
            account["owned_by_user"] and account["real_account"] and account["kind"] == "asset"
        ):
            _fail(input_path + ".funding_account_id", "must reference an owned real asset account")
        if account["currency"] != currency:
            _fail(input_path + ".funding_account_id", "must match candidate currency")
        proposals = {proposal["item_id"]: proposal for proposal in payload["item_proposals"]}
        allocation_total = Decimal(0)
        for index, item in enumerate(input_value["items"]):
            item_path = f"{input_path}.items[{index}]"
            proposal = proposals.get(item["item_id"])
            if proposal is None:
                _fail(item_path + ".item_id", "must match a candidate item")
            category = categories.get(item["category_id"])
            posting_account = accounts.get(category.get("posting_account_id")) if category else None
            if (
                category is None
                or category.get("parent_id") is None
                or (not replay and not category.get("active"))
                or posting_account is None
                or posting_account.get("kind") != "expense"
            ):
                _fail(item_path + ".category_id", "must reference a valid second-level expense category")
            if item["currency"] != currency or proposal["currency"] != currency:
                _fail(item_path + ".currency", "must match candidate currency")
            amount = _amount(item["allocation_amount"], currency, item_path + ".allocation_amount", precisions)
            if amount != Decimal(proposal["amount"]):
                _fail(item_path + ".allocation_amount", "must match the candidate item amount")
            allocation_total += amount
        if set(proposals) != {item["item_id"] for item in input_value["items"]}:
            _fail(input_path + ".items", "must confirm both candidate items")
        if allocation_total != Decimal(payload["payment_total"]):
            _fail(input_path + ".items", "allocations must close to payment total")
        _timestamp(input_value["payment_at"], input_path + ".payment_at", timezone)
        _timestamp(input_value["common_statistics_at"], input_path + ".common_statistics_at", timezone)
        if input_value["common_statistics_at"] != input_value["payment_at"]:
            _fail(input_path + ".common_statistics_at", "v1 requires the common payment time")
    elif action == "merge_item_receipt_evidence":
        allocation = entities.get(input_value["item_allocation_id"])
        if allocation is None or allocation.get("type") != "item_allocation":
            _fail(input_path + ".item_allocation_id", "must reference an item allocation")
        payload = allocation["payload"]
        if input_value["currency"] != payload["currency"]:
            _fail(input_path + ".currency", "must match the allocation currency")
        amount = _amount(input_value["amount"], input_value["currency"], input_path + ".amount", precisions)
        if amount != Decimal(payload["amount"]):
            _fail(input_path + ".amount", "must match the allocation amount")
        _timestamp(input_value["observed_at"], input_path + ".observed_at", timezone)
    elif action == "credit_principal_repayment":
        for field, kind in (("asset_account_id", "asset"), ("liability_account_id", "liability")):
            account = accounts.get(input_value[field])
            if account is None or account["kind"] != kind or not account["owned_by_user"] or not account["real_account"]:
                _fail(input_path + "." + field, "must reference the matching owned real account")
            if account["currency"] != input_value["currency"]:
                _fail(input_path + ".currency", "must match both repayment accounts")
        _amount(input_value["principal_amount"], input_value["currency"], input_path + ".principal_amount", precisions)
        _timestamp(input_value["occurred_at"], input_path + ".occurred_at", timezone)
    elif action == "ingest_mixed_payment_source":
        record = input_value["source_record"]
        _timestamp(record["observed_at"], input_path + ".source_record.observed_at", timezone)
        source_path = input_path + ".source_record"
        currency = record["currency"]
        total = _amount(record["total_amount"], currency, source_path + ".total_amount", precisions)
        if total <= 0:
            _fail(source_path + ".total_amount", "must be positive")

        if record["completeness"] == "missing_funding_leg":
            known = _amount(
                record["known_asset_funding_amount"],
                currency,
                source_path + ".known_asset_funding_amount",
                precisions,
            )
            if known <= 0:
                _fail(source_path + ".known_asset_funding_amount", "must be positive")
            missing = total - known
            if missing <= 0:
                _fail(source_path + ".known_asset_funding_amount", "must be less than total_amount")
        else:
            components = record["funding_components"]
            if len(components) != 2:
                _fail(source_path + ".funding_components", "complete source must contain exactly two components")
            seen_accounts: set[str] = set()
            component_total = Decimal(0)
            for index, component in enumerate(components):
                component_path = f"{source_path}.funding_components[{index}]"
                account_id = component["account_id"]
                account = accounts.get(account_id)
                if account is None:
                    _fail(component_path + ".account_id", "must reference a known account")
                if not (account["owned_by_user"] and account["real_account"] and account["kind"] in {"asset", "liability"}):
                    _fail(component_path + ".account_id", "must reference an owned real asset or liability account")
                if account_id in seen_accounts:
                    _fail(component_path + ".account_id", "funding accounts must be distinct")
                seen_accounts.add(account_id)
                if component["currency"] != currency or account["currency"] != currency:
                    _fail(component_path + ".currency", "must match source and account currency")
                amount = _amount(
                    component["funding_amount"],
                    component["currency"],
                    component_path + ".funding_amount",
                    precisions,
                )
                if amount <= 0:
                    _fail(component_path + ".funding_amount", "must be positive")
                component_total += amount
            if component_total != total:
                _fail(source_path + ".funding_components", "funding amounts must sum to total_amount")
    elif action == "confirm_mixed_payment_candidate":
        if input_value.get("explicit_confirmation") is not True:
            _fail(input_path + ".explicit_confirmation", "must be true")
        candidate_id = input_value.get("candidate_id")
        candidate = candidates.get(candidate_id) if isinstance(candidate_id, str) else None
        if candidate is None or candidate.get("type") != "mixed_payment":
            _fail(input_path + ".candidate_id", "must reference a mixed_payment_expense candidate")
        status_history = candidate.get("status_history")
        payload = candidate.get("payload")
        if not isinstance(payload, dict):
            _fail(input_path + ".candidate_id", "candidate payload must be an object")
        idempotent_replay = (
            operation["outcome"]["status"] == "no_change"
            and operation["outcome"].get("reason_code") == "idempotent_replay"
        )
        if (
            not isinstance(status_history, list)
            or not status_history
            or not isinstance(status_history[-1], dict)
        ):
            _fail(input_path + ".candidate_id", "candidate status history must be non-empty")
        current_status = status_history[-1].get("status")
        if idempotent_replay:
            if current_status != "confirmed" or not isinstance(payload.get("transaction_id"), str):
                _fail(input_path + ".candidate_id", "idempotent replay requires the confirmed bound candidate")
        elif current_status != "pending_confirmation" or "transaction_id" in payload:
            _fail(input_path + ".candidate_id", "must reference a pending unbound candidate")

        category_id = input_value.get("category_id")
        category = categories.get(category_id) if isinstance(category_id, str) else None
        parent_id = category.get("parent_id") if category is not None else None
        posting_account_id = category.get("posting_account_id") if category is not None else None
        parent = categories.get(parent_id) if isinstance(parent_id, str) else None
        posting_account = accounts.get(posting_account_id) if isinstance(posting_account_id, str) else None
        if (
            category is None
            or parent is None
            or parent.get("parent_id") is not None
            or posting_account is None
            or posting_account.get("kind") != "expense"
            or (not idempotent_replay and category.get("active") is not True)
        ):
            _fail(
                input_path + ".category_id",
                "must reference a second-level expense category with a valid posting_account_id; fresh confirmation requires active",
            )

        currency = payload.get("currency")
        total_amount = payload.get("total_amount")
        if not isinstance(currency, str) or not isinstance(total_amount, str):
            _fail(input_path + ".candidate_id", "candidate must own total_amount and currency")
        total = _amount(total_amount, currency, input_path + ".candidate_id.total_amount", precisions)
        if total <= 0:
            _fail(input_path + ".candidate_id.total_amount", "must be positive")
        components = input_value.get("confirmed_funding_components")
        if not isinstance(components, list) or len(components) != 2:
            _fail(input_path + ".confirmed_funding_components", "must contain exactly two components")
        seen_accounts: set[str] = set()
        component_kinds: set[str] = set()
        component_total = Decimal(0)
        for index, component in enumerate(components):
            component_path = f"{input_path}.confirmed_funding_components[{index}]"
            if not isinstance(component, dict):
                _fail(component_path, "must be an object")
            account_id = component.get("account_id")
            account = accounts.get(account_id) if isinstance(account_id, str) else None
            if account is None:
                _fail(component_path + ".account_id", "must reference a known account")
            if not (
                account.get("owned_by_user") is True
                and account.get("real_account") is True
                and account.get("kind") in {"asset", "liability"}
            ):
                _fail(component_path + ".account_id", "must reference an owned real asset or liability account")
            if account_id in seen_accounts:
                _fail(component_path + ".account_id", "funding accounts must be distinct")
            seen_accounts.add(account_id)
            component_kinds.add(account["kind"])
            component_currency = component.get("currency")
            if component_currency != currency or account.get("currency") != currency:
                _fail(component_path + ".currency", "must match candidate and account currency")
            funding_amount = component.get("funding_amount")
            if not isinstance(funding_amount, str):
                _fail(component_path + ".funding_amount", "must be a decimal string")
            amount = _amount(funding_amount, component_currency, component_path + ".funding_amount", precisions)
            if amount <= 0:
                _fail(component_path + ".funding_amount", "must be positive")
            component_total += amount
        if component_kinds != {"asset", "liability"}:
            _fail(input_path + ".confirmed_funding_components", "must contain exactly one asset and one liability")
        if component_total != total:
            _fail(input_path + ".confirmed_funding_components", "funding amounts must sum to candidate total_amount")
        if posting_account.get("currency") != currency:
            _fail(input_path + ".category_id", "category posting account currency must match candidate currency")
    elif action == "merge_mixed_payment_mirror_evidence":
        for field in ("source_record_id", "evidence_id"):
            if not isinstance(input_value.get(field), str):
                _fail(input_path + "." + field, "must be an ID string")
        for field, collection in (("transaction_id", transactions), ("candidate_id", candidates), ("account_id", accounts)):
            value = input_value.get(field)
            if not isinstance(value, str) or value not in collection:
                _fail(input_path + "." + field, "dangling reference")
        candidate = candidates[input_value["candidate_id"]]
        if candidate["type"] != "mixed_payment":
            _fail(input_path + ".candidate_id", "must reference a mixed payment candidate")
        status_history = candidate.get("status_history")
        if (
            not isinstance(status_history, list)
            or not status_history
            or not isinstance(status_history[-1], dict)
            or status_history[-1].get("status") != "confirmed"
        ):
            _fail(input_path + ".candidate_id", "must reference a confirmed mixed payment candidate")
        candidate_payload = candidate.get("payload")
        if not isinstance(candidate_payload, dict):
            _fail(input_path + ".candidate_id", "candidate payload must be an object")
        if candidate_payload.get("transaction_id") != input_value["transaction_id"]:
            _fail(input_path + ".transaction_id", "must match the confirmed candidate transaction")

        transaction = transactions[input_value["transaction_id"]]
        if transaction["type"] != "expense":
            _fail(input_path + ".transaction_id", "must reference an expense mixed payment transaction")
        versions = {item["id"]: item for item in baseline["transaction_versions"]}
        posting_sets = {item["id"]: item for item in baseline["posting_sets"]}
        postings = {item["id"]: item for item in baseline["postings"]}
        version = versions.get(transaction["current_version_id"])
        posting_set = posting_sets.get(version["posting_set_id"]) if version else None
        current_postings = [postings[posting_id] for posting_id in posting_set["posting_ids"]] if posting_set else []
        postings_by_role = {posting.get("role"): posting for posting in current_postings}
        if set(postings_by_role) != {"expense", "mixed_expense_asset_funding", "mixed_expense_credit_funding"}:
            _fail(input_path + ".transaction_id", "must reference a mixed payment expense transaction")
        liability_posting = postings_by_role["mixed_expense_credit_funding"]
        liability_account = accounts[input_value["account_id"]]
        if liability_account["kind"] != "liability" or not liability_account["owned_by_user"] or not liability_account["real_account"]:
            _fail(input_path + ".account_id", "must reference the mixed payment liability funding account")
        if liability_posting["account_id"] != input_value["account_id"]:
            _fail(input_path + ".account_id", "must match the transaction liability funding posting")
        currency = input_value.get("currency")
        if not isinstance(currency, str):
            _fail(input_path + ".currency", "must be a declared currency string")
        if liability_posting["currency"] != currency or liability_account["currency"] != currency:
            _fail(input_path + ".currency", "must match the transaction liability funding posting")
        amount = _amount(input_value.get("amount"), currency, input_path + ".amount", precisions)
        if amount != -Decimal(liability_posting["amount"]):
            _fail(input_path + ".amount", "must match the transaction liability funding posting")
        mixed_payment_relations = [
            relation
            for relation in baseline["relations"]
            if relation["type"] == "mixed_payment"
            and {ref["id"] for ref in relation["member_refs"] if ref["kind"] == "transaction"} == {input_value["transaction_id"]}
        ]
        if len(mixed_payment_relations) != 1:
            _fail(input_path + ".transaction_id", "must reference the transaction's mixed_payment relation")
        relation_posting_ids = {
            ref["id"] for ref in mixed_payment_relations[0]["member_refs"] if ref["kind"] == "posting"
        }
        if relation_posting_ids != {
            postings_by_role["mixed_expense_asset_funding"]["id"],
            liability_posting["id"],
        }:
            _fail(input_path + ".transaction_id", "must reference the transaction's funding postings")
        reconciliation_by_posting = {
            item["posting_id"]: item["status"]
            for item in baseline["posting_reconciliations"]
        }
        asset_status = reconciliation_by_posting.get(
            postings_by_role["mixed_expense_asset_funding"]["id"]
        )
        liability_status = reconciliation_by_posting.get(liability_posting["id"])
        if operation["outcome"]["status"] == "no_change":
            if asset_status != "matched" or liability_status != "matched":
                _fail(
                    input_path + ".transaction_id",
                    "mirror replay requires both funding postings matched",
                )
        elif asset_status != "matched" or liability_status != "pending":
            _fail(
                input_path + ".transaction_id",
                "mirror evidence requires the asset funding posting matched and the liability funding posting pending before merge",
            )
        _timestamp(input_value.get("observed_at"), input_path + ".observed_at", timezone)
    elif action == "create_periodic_allocation":
        payment = accounts.get(input_value["payment_account_id"])
        prepaid = accounts.get(input_value["prepaid_account_id"])
        category = categories.get(input_value["category_id"])
        category_account = accounts.get(category["posting_account_id"]) if category else None
        if not (payment and payment["kind"] == "asset" and payment["owned_by_user"] and payment["real_account"] and payment["reconciliation_eligible"]):
            _fail(input_path + ".payment_account_id", "must reference an eligible owned real payment asset")
        if not (prepaid and prepaid["kind"] == "asset" and prepaid["owned_by_user"] and not prepaid["real_account"] and prepaid.get("hidden") is True):
            _fail(input_path + ".prepaid_account_id", "must reference an owned non-real system-hidden prepaid asset")
        if not (category and category["parent_id"] is not None and category["active"] and category_account and category_account["kind"] == "expense" and not category_account["owned_by_user"] and not category_account["real_account"]):
            _fail(input_path + ".category_id", "must reference an active second-level category expense account")
        amount = _amount(input_value["amount"], input_value["currency"], input_path + ".amount", precisions)
        if amount <= 0:
            _fail(input_path + ".amount", "must be positive")
        if payment["currency"] != input_value["currency"] or prepaid["currency"] != input_value["currency"] or category_account["currency"] != input_value["currency"]:
            _fail(input_path + ".currency", "must match payment, prepaid, and category accounts")
        _timestamp(input_value["occurred_at"], input_path + ".occurred_at", timezone)
        _timestamp(input_value["start_at"], input_path + ".start_at", timezone)
        if input_value["installment_count"] < 1:
            _fail(input_path + ".installment_count", "must be at least 1")
    elif action == "recognize_periodic_allocation_installment":
        schedule = entities.get(input_value["schedule_id"])
        installment = entities.get(input_value["installment_id"])
        if schedule is None or schedule["type"] != "periodic_allocation_schedule":
            _fail(input_path + ".schedule_id", "must reference a periodic allocation schedule")
        if installment is None or installment["type"] != "periodic_allocation_installment":
            _fail(input_path + ".installment_id", "must reference a periodic allocation installment")
        payload = installment["payload"]
        if payload["schedule_id"] != schedule["id"] or payload["amount"] != input_value["amount"] or payload["currency"] != input_value["currency"]:
            _fail(input_path, "must exactly match the scheduled installment")
        status = _periodic_allocation_statuses(baseline, _state_indexes(baseline, input_path))
        value = status.get(("domain_entity", installment["id"], "allocation_status"))
        if operation["outcome"]["status"] == "accepted" and value != "pending":
            _fail(input_path + ".installment_id", "must reference a current pending installment")
        if operation["outcome"]["status"] == "no_change" and value != "recognized":
            _fail(input_path + ".installment_id", "idempotent replay requires an already recognized installment")
    elif action == "revise_periodic_allocation":
        schedule = entities.get(input_value["schedule_id"])
        if schedule is None or schedule["type"] != "periodic_allocation_schedule":
            _fail(input_path + ".schedule_id", "must reference a periodic allocation schedule")
        schedule_payload = schedule["payload"]
        if input_value["currency"] != schedule_payload["currency"]:
            _fail(input_path + ".currency", "must match the schedule currency")
        revision_entities = [item for item in baseline["domain_entities"] if item["type"] == "periodic_allocation_revision" and item["payload"]["schedule_id"] == schedule["id"]]
        current_revision = max(revision_entities, key=lambda item: item["payload"]["revision_number"])
        recognized_links = {
            link["from"]["id"]
            for link in baseline["audit_links"]
            if link["type"] == "periodic_allocation_recognition"
        }
        recognized_ids = [item_id for item_id in current_revision["payload"]["installment_ids"] if item_id in recognized_links]
        if input_value["recognized_through"] not in recognized_ids:
            _fail(input_path + ".recognized_through", "must identify a recognized installment in the current revision")
        remaining = _amount(input_value["remaining_amount"], input_value["currency"], input_path + ".remaining_amount", precisions)
        expected_remaining = _amount(schedule_payload["total_amount"], input_value["currency"], input_path + ".schedule_id", precisions) - sum(
            (_decimal(entities[item_id]["payload"]["amount"], input_path) for item_id in recognized_links if item_id in entities and entities[item_id]["type"] == "periodic_allocation_installment" and entities[item_id]["payload"]["schedule_id"] == schedule["id"]),
            Decimal(0),
        )
        if remaining != expected_remaining:
            _fail(input_path + ".remaining_amount", "must exactly redistribute the unrecognized schedule remainder")
        if input_value["remaining_installment_count"] < 1:
            _fail(input_path + ".remaining_installment_count", "must be at least 1")
    elif action == "correct_transaction_version":
        if input_value["correction_kind"] == "statistics_time":
            transaction = transactions.get(input_value["transaction_id"])
            if transaction is None or transaction["type"] != "prepaid_recognition":
                _fail(input_path + ".transaction_id", "must reference a prepaid recognition transaction")
            _timestamp(input_value["statistics_at"], input_path + ".statistics_at", timezone)
        else:
            if operation["outcome"]["status"] == "no_change":
                _timestamp(input_value["corrected_at"], input_path + ".corrected_at", timezone)
                return
            failure = _posting_facts_correction_failure(
                input_value, baseline, precisions, reject_changed_asset=False
            )
            if failure is not None:
                _, field = failure
                _fail(input_path + "." + field, "does not satisfy the complete posting-facts correction contract")
            _timestamp(input_value["corrected_at"], input_path + ".corrected_at", timezone)
            for index, item in enumerate(input_value["replacement_postings"]):
                account = accounts[item["account_id"]]
                category_id = item.get("category_id")
                if category_id is not None:
                    category = categories.get(category_id)
                    if category is None or not category["active"] or category["posting_account_id"] != account["id"]:
                        _fail(f"{input_path}.replacement_postings[{index}].category_id", "must reference an active category owned by the replacement account")
    elif action in {"manual_expense", "manual_income"}:
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
    elif action in {"manual_account_transfer", "import_source_record", "confirm_account_transfer_candidate", "import_incomplete_source"}:
        account_fields = ("source_account_id", "destination_account_id") if action != "import_incomplete_source" else ("source_account_id",)
        for field in account_fields:
            if input_value[field] not in accounts:
                _fail(input_path + f".{field}", "dangling account reference")
        failure = _transfer_account_failure(
            input_value,
            accounts,
            attempted=False,
            require_destination=action != "import_incomplete_source",
        )
        if failure is not None:
            _fail(input_path + f".{failure[0]}", failure[1])
        if action == "import_incomplete_source":
            if accounts[input_value["source_account_id"]]["currency"] != input_value["currency"]:
                _fail(input_path + ".currency", "must match the source account")
            _amount(input_value["debit_amount"], input_value["currency"], input_path + ".debit_amount", precisions)
            if Decimal(input_value["debit_amount"]) <= 0:
                _fail(input_path + ".debit_amount", "must be positive")
        else:
            if any(accounts[input_value[field]]["currency"] != input_value["currency"] for field in account_fields):
                _fail(input_path + ".currency", "must match both transfer accounts")
            for field in ("source_debit_amount", "destination_credit_amount", "fee_amount"):
                _amount(input_value[field], input_value["currency"], input_path + f".{field}", precisions)
            if Decimal(input_value["destination_credit_amount"]) <= 0:
                _fail(input_path + ".destination_credit_amount", "must be positive")
            if Decimal(input_value["fee_amount"]) < 0 or Decimal(input_value["source_debit_amount"]) != Decimal(input_value["destination_credit_amount"]) + Decimal(input_value["fee_amount"]):
                _fail(input_path + ".fee_amount", "must balance source debit and destination credit")
            if action in {"manual_account_transfer", "confirm_account_transfer_candidate"}:
                fee_category = categories.get(input_value["fee_category_id"])
                fee_account = accounts.get(fee_category["posting_account_id"]) if fee_category and fee_category.get("posting_account_id") else None
                if fee_category is None or fee_category["parent_id"] is None or not fee_category["active"] or fee_account is None or fee_account["kind"] != "expense":
                    _fail(input_path + ".fee_category_id", "must reference the active second-level financial fee category")
        _timestamp(input_value["observed_at"] if "observed_at" in input_value else input_value["occurred_at"], input_path + (".observed_at" if "observed_at" in input_value else ".occurred_at"), timezone)
    elif action == "import_mirror_record":
        for field, collection in (("transaction_id", transactions), ("candidate_id", candidates), ("account_id", accounts)):
            if input_value[field] not in collection:
                _fail(input_path + f".{field}", "dangling reference")
        if accounts[input_value["account_id"]]["currency"] != input_value["currency"]:
            _fail(input_path + ".currency", "must match mirror account")
        _amount(input_value["credit_amount"], input_value["currency"], input_path + ".credit_amount", precisions)
        _timestamp(input_value["observed_at"], input_path + ".observed_at", timezone)
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
    elif action == "save_zero_delta_observation":
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
    elif action == "receive_import_candidate":
        source_ids = {item["id"] for item in baseline["sources"]}
        if operation["outcome"]["status"] == "accepted" and input_value["source_id"] in source_ids:
            _fail(input_path + ".source_id", "must be a new intake source")
        _decimal(input_value["confidence"], input_path + ".confidence")
    elif action == "confirm_imported_real_transfer":
        _validate_imported_source_binding(baseline, operation_path, input_value)
        for field in ("target_account_id", "counter_account_id"):
            if input_value[field] not in accounts:
                _fail(input_path + f".{field}", "dangling account reference")
            if accounts[input_value[field]]["currency"] != input_value["currency"]:
                _fail(input_path + ".currency", "must match both transfer accounts")
        _amount(input_value["amount"], input_value["currency"], input_path + ".amount", precisions)
        _amount(
            input_value["explanation_allocation"],
            input_value["currency"],
            input_path + ".explanation_allocation",
            precisions,
        )
        for field in ("actual_occurred_at", "confirmed_at"):
            _timestamp(input_value[field], input_path + f".{field}", timezone)
        for field, expected in (
            ("confirms_target_account", True),
            ("confirms_counter_account", True),
            ("confirms_actual_occurred_at", True),
            ("confirms_currency", True),
            ("confirms_amount", True),
            ("confirms_explanation_allocation", False),
        ):
            if input_value[field] is not expected:
                _fail(input_path + f".{field}", f"must be {expected} under the frozen v1 contract")
    elif action == "confirm_imported_explanation_allocation":
        _validate_imported_source_binding(baseline, operation_path, input_value)
        transaction = transactions.get(input_value["transaction_id"])
        if transaction is None or transaction["type"] != "account_transfer":
            _fail(input_path + ".transaction_id", "dangling or mistyped transfer reference")
        for field in ("target_account_id", "counter_account_id"):
            if input_value[field] not in accounts:
                _fail(input_path + f".{field}", "dangling account reference")
            if accounts[input_value[field]]["currency"] != input_value["currency"]:
                _fail(input_path + ".currency", "must match both transfer accounts")
        _amount(input_value["amount"], input_value["currency"], input_path + ".amount", precisions)
        _amount(
            input_value["explanation_allocation"],
            input_value["currency"],
            input_path + ".explanation_allocation",
            precisions,
        )
        for field in ("actual_occurred_at", "confirmed_at"):
            _timestamp(input_value[field], input_path + f".{field}", timezone)
        for field, expected in (
            ("confirms_target_account", True),
            ("confirms_counter_account", True),
            ("confirms_actual_occurred_at", True),
            ("confirms_currency", True),
            ("confirms_amount", True),
            ("confirms_explanation_allocation", True),
        ):
            if input_value[field] is not expected:
                _fail(input_path + f".{field}", f"must be {expected} under the frozen v1 contract")
    elif action == "link_real_posting_evidence":
        source_ids = {item["id"] for item in baseline["sources"]}
        if operation["outcome"]["status"] == "accepted" and input_value["source_id"] in source_ids:
            _fail(input_path + ".source_id", "must be a new evidence source")
        if operation["outcome"]["status"] == "accepted" and input_value["evidence_id"] in {
            item["id"] for item in baseline["evidence"]
        }:
            _fail(input_path + ".evidence_id", "must be a new evidence identity")
        posting = next(
            (item for item in baseline["postings"] if item["id"] == input_value["target_posting_id"]),
            None,
        )
        if posting is None or not posting["reconciliation_eligible"]:
            _fail(input_path + ".target_posting_id", "must reference an eligible posting")
        if input_value["account_id"] != posting["account_id"]:
            _fail(input_path + ".account_id", "must match the target posting account")
        if input_value["currency"] != posting["currency"]:
            _fail(input_path + ".currency", "must match the target posting currency")
        if input_value["amount"] != posting["amount"]:
            _fail(input_path + ".amount", "must exactly match the target posting amount")
        if input_value["posting_side"] != (
            "increase" if Decimal(posting["amount"]) > 0 else "decrease"
        ):
            _fail(input_path + ".posting_side", "must match the target posting amount sign")
        if input_value["explicit_confirmation"] is not True:
            _fail(input_path + ".explicit_confirmation", "must be true")
        _timestamp(input_value["observed_at"], input_path + ".observed_at", timezone)
    elif action in {"confirm_second_real_transfer", "confirm_second_explanation_allocation"}:
        if action == "confirm_second_real_transfer":
            for field in ("target_account_id", "counter_account_id"):
                if input_value[field] not in accounts:
                    _fail(input_path + f".{field}", "dangling account reference")
                if accounts[input_value[field]]["currency"] != input_value["currency"]:
                    _fail(input_path + ".currency", "must match both transfer accounts")
            _amount(input_value["amount"], input_value["currency"], input_path + ".amount", precisions)
            _amount(
                input_value["explanation_allocation"],
                input_value["currency"],
                input_path + ".explanation_allocation",
                precisions,
            )
            for field in ("actual_occurred_at", "confirmed_at"):
                _timestamp(input_value[field], input_path + f".{field}", timezone)
            for field, expected in (
                ("confirms_target_account", True),
                ("confirms_counter_account", True),
                ("confirms_actual_occurred_at", True),
                ("confirms_currency", True),
                ("confirms_amount", True),
                ("confirms_explanation_allocation", False),
            ):
                if input_value[field] is not expected:
                    _fail(input_path + f".{field}", f"must be {expected} under the frozen v1 contract")
        else:
            transaction = transactions.get(input_value["transaction_id"])
            if transaction is None or transaction["type"] != "account_transfer":
                _fail(input_path + ".transaction_id", "dangling or mistyped transfer reference")
            for field in ("target_account_id", "counter_account_id"):
                if input_value[field] not in accounts:
                    _fail(input_path + f".{field}", "dangling account reference")
                if accounts[input_value[field]]["currency"] != input_value["currency"]:
                    _fail(input_path + ".currency", "must match both transfer accounts")
            _amount(input_value["amount"], input_value["currency"], input_path + ".amount", precisions)
            _amount(
                input_value["explanation_allocation"],
                input_value["currency"],
                input_path + ".explanation_allocation",
                precisions,
            )
            for field in ("actual_occurred_at", "confirmed_at"):
                _timestamp(input_value[field], input_path + f".{field}", timezone)
            for field, expected in (
                ("confirms_target_account", True),
                ("confirms_counter_account", True),
                ("confirms_actual_occurred_at", True),
                ("confirms_currency", True),
                ("confirms_amount", True),
                ("confirms_explanation_allocation", True),
            ):
                if input_value[field] is not expected:
                    _fail(input_path + f".{field}", f"must be {expected} under the frozen v1 contract")
    elif action in _RG10_STRUCTURAL_ACTIONS:
        _validate_rg10_structural_input(
            action,
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


def _validate_candidate_confirmation_transition(
    before: dict[str, Any],
    after: dict[str, Any],
    path: str,
    *,
    immutable_payload: bool = False,
) -> None:
    before_outer = {
        key: value
        for key, value in before.items()
        if key not in {"payload", "status_history"}
    }
    after_outer = {
        key: value
        for key, value in after.items()
        if key not in {"payload", "status_history"}
    }
    if not _contract_equivalent(before_outer, after_outer):
        _fail(path, "candidate stable identity is immutable")
    if immutable_payload:
        if "transaction_id" in before["payload"] or "transaction_id" in after["payload"]:
            _fail(path + ".payload.transaction_id", "candidate payload cannot bind a formal transaction")
        if not _contract_equivalent(before["payload"], after["payload"]):
            _fail(path + ".payload", "candidate confirmation cannot mutate candidate payload")
    else:
        if "transaction_id" in before["payload"]:
            _fail(path + ".payload.transaction_id", "pending candidate cannot replace a transaction binding")
        transaction_id = after["payload"].get("transaction_id")
        if transaction_id is None:
            _fail(path + ".payload.transaction_id", "candidate confirmation must add a transaction binding")
        expected_payload = {**before["payload"], "transaction_id": transaction_id}
        if not _contract_equivalent(expected_payload, after["payload"]):
            _fail(path + ".payload", "candidate confirmation may only add transaction_id")
    before_history = before["status_history"]
    after_history = after["status_history"]
    if len(after_history) != len(before_history) + 1 or after_history[: len(before_history)] != before_history:
        _fail(path + ".status_history", "confirmation must append exactly one status history event")
    appended = after_history[-1]
    if appended.get("status") != "confirmed":
        _fail(path + ".status_history", "candidate confirmation must append confirmed")
    if appended.get("sequence") != before_history[-1].get("sequence") + 1:
        _fail(path + ".status_history", "confirmed status sequence must follow the pending status")


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
    *,
    case_id: str | None = None,
    action_type: str | None = None,
    outcome_status: str | None = None,
    target_candidate_id: str | None = None,
    target_relation_id: str | None = None,
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
    if (
        case_id == "RG-06"
        and outcome_status in {"rejected", "no_change"}
        and not _contract_equivalent(
            _state_payload(baseline), _state_payload(result)
        )
    ):
        _fail(
            operation_path,
            "rejected and no_change baseline/result states must be contract-equivalent after set-like normalization",
        )
    target_lifecycle_id: str | None = None
    target_evidence_id: str | None = None
    if (
        case_id == "RG-06"
        and action_type == "confirm_staged_payment_candidate"
        and target_candidate_id is not None
    ):
        target_candidate = next(
            (
                candidate
                for candidate in baseline["candidates"]
                if candidate["id"] == target_candidate_id
            ),
            None,
        )
        if target_candidate is not None:
            target_evidence_id = target_candidate.get("payload", {}).get("evidence_ref")
    if case_id == "RG-06" and target_relation_id is not None:
        for relation in baseline["relations"]:
            if relation["id"] != target_relation_id:
                continue
            entities = {item["id"]: item for item in baseline["domain_entities"]}
            lifecycles = [
                ref["id"]
                for ref in relation["member_refs"]
                if ref["kind"] == "domain_entity"
                and entities.get(ref["id"], {}).get("type") == "staged_payment_lifecycle"
            ]
            if len(lifecycles) == 1:
                target_lifecycle_id = lifecycles[0]
            break
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
                if (
                    collection_name == "evidence"
                    and case_id == "RG-06"
                    and action_type == "confirm_staged_payment_candidate"
                    and outcome_status in {None, "accepted"}
                    and item_id == target_evidence_id
                ):
                    old = before[item_id]
                    new = after[item_id]
                    expected_payload = {
                        **old["payload"],
                        "payment_id": new.get("payload", {}).get("payment_id"),
                    }
                    if (
                        "payment_id" in old["payload"]
                        or not isinstance(expected_payload["payment_id"], str)
                        or {key: value for key, value in old.items() if key != "payload"}
                        != {key: value for key, value in new.items() if key != "payload"}
                        or new.get("payload") != expected_payload
                    ):
                        _fail(
                            item_path,
                            "candidate confirmation may add only the exact evidence payment binding",
                        )
                    continue
                if (
                    collection_name == "relations"
                    and case_id == "RG-06"
                    and action_type
                    in {
                        "record_staged_payment_installment",
                        "confirm_staged_payment_candidate",
                    }
                    and outcome_status in {None, "accepted"}
                    and item_id == target_relation_id
                    and before[item_id].get("type") == "staged_payment"
                    and after[item_id].get("type") == "staged_payment"
                    and before[item_id].get("payload") == after[item_id].get("payload") == {}
                ):
                    old_refs = before[item_id]["member_refs"]
                    new_refs = after[item_id]["member_refs"]
                    if (
                        len(new_refs) != len(old_refs) + 1
                        or any(ref not in new_refs for ref in old_refs)
                        or len({(ref["kind"], ref["id"]) for ref in new_refs})
                        != len(new_refs)
                    ):
                        _fail(item_path + ".member_refs", "RG-06 relation may append exactly one unique member")
                    continue
                if (
                    collection_name == "relations"
                    and case_id == "RG-07"
                    and action_type == "confirm_manual_refund_receipt"
                    and outcome_status == "accepted"
                    and item_id == target_relation_id
                    and before[item_id].get("type") == after[item_id].get("type") == "refund"
                    and before[item_id].get("payload") == after[item_id].get("payload") == {}
                ):
                    old_refs = before[item_id]["member_refs"]
                    new_refs = after[item_id]["member_refs"]
                    if (
                        len(new_refs) != len(old_refs) + 1
                        or any(ref not in new_refs for ref in old_refs)
                        or len({(ref["kind"], ref["id"]) for ref in new_refs}) != len(new_refs)
                    ):
                        _fail(item_path + ".member_refs", "RG-07 receipt may append exactly one unique transaction member")
                    continue
                if (
                    collection_name == "posting_reconciliations"
                    and (
                        (
                            case_id == "RG-03"
                            and action_type in {"confirm_account_transfer_candidate", "import_mirror_record"}
                        )
                        or (
                            case_id == "RG-04"
                            and action_type == "merge_mixed_payment_mirror_evidence"
                        )
                        or (
                            case_id == "RG-06"
                            and action_type == "link_staged_payment_evidence"
                            and outcome_status in {None, "accepted"}
                        )
                        or (
                            case_id == "RG-07"
                            and action_type in {
                                "attach_original_payment_evidence",
                                "attach_refund_destination_evidence",
                            }
                        )
                        or (
                            case_id == "RG-09"
                            and action_type == "link_real_posting_evidence"
                            and outcome_status in {None, "accepted"}
                        )
                        or (
                            case_id == "RG-10"
                            and action_type
                            in {"reconcile_merchant_credit", "reconcile_bank_payment"}
                            and outcome_status in {None, "accepted"}
                        )
                    )
                ):
                    old = before[item_id]
                    new = after[item_id]
                    if _contract_equivalent(old, new):
                        continue
                    old_identity = {key: value for key, value in old.items() if key != "status"}
                    new_identity = {key: value for key, value in new.items() if key != "status"}
                    if not _contract_equivalent(old_identity, new_identity):
                        _fail(item_path, "reconciliation transition may change status only")
                    if (old["status"], new["status"]) != ("pending", "matched"):
                        _fail(item_path + ".status", "reconciliation transition must be pending to matched")
                    continue
                if not _contract_equivalent(before[item_id], after[item_id]):
                    _fail(item_path, f"existing {collection_name} entities are immutable")
            elif collection_name == "candidates":
                if (
                    item_id == target_candidate_id
                    and "transaction_id" not in before[item_id].get("payload", {})
                    and (
                        (
                            case_id == "RG-03"
                            and action_type == "confirm_account_transfer_candidate"
                            and before[item_id].get("type") == "account_transfer"
                        )
                        or (
                            case_id == "RG-04"
                            and action_type == "confirm_mixed_payment_candidate"
                            and before[item_id].get("type") == "mixed_payment"
                        )
                        or (
                            case_id == "RG-05"
                            and action_type == "confirm_merged_payment_candidate"
                            and before[item_id].get("type") == "merged_payment"
                        )
                        or (
                            case_id == "RG-06"
                            and action_type == "confirm_staged_payment_candidate"
                            and outcome_status in {None, "accepted"}
                            and before[item_id].get("type") == "staged_payment"
                        )
                        or (
                            case_id == "RG-07"
                            and action_type == "confirm_imported_refund"
                            and outcome_status == "accepted"
                            and before[item_id].get("type") == "refund_credit"
                        )
                    )
                ):
                    _validate_candidate_confirmation_transition(
                        before[item_id],
                        after[item_id],
                        item_path,
                        immutable_payload=case_id in {"RG-06", "RG-07"},
                    )
                else:
                    _validate_history_prefix(
                        before[item_id], after[item_id], item_path, "status_history"
                    )
            elif collection_name == "domain_entities":
                old_payload = before[item_id].get("payload", {})
                new_payload = after[item_id].get("payload", {})
                if (
                    case_id == "RG-06"
                    and action_type
                    in {
                        "record_staged_payment_installment",
                        "change_staged_payment_fulfillment",
                        "confirm_staged_payment_completion",
                        "confirm_staged_payment_candidate",
                    }
                    and outcome_status in {None, "accepted"}
                    and item_id == target_lifecycle_id
                    and before[item_id].get("type") == "staged_payment_lifecycle"
                    and after[item_id].get("type") == "staged_payment_lifecycle"
                ):
                    old_outer = {key: value for key, value in before[item_id].items() if key != "payload"}
                    new_outer = {key: value for key, value in after[item_id].items() if key != "payload"}
                    immutable_payload = {
                        key: value
                        for key, value in old_payload.items()
                        if key not in {"paid_amount", "due_amount", "state_history"}
                    }
                    new_immutable_payload = {
                        key: value
                        for key, value in new_payload.items()
                        if key not in {"paid_amount", "due_amount", "state_history"}
                    }
                    if not _contract_equivalent(old_outer, new_outer) or not _contract_equivalent(immutable_payload, new_immutable_payload):
                        _fail(item_path, "RG-06 lifecycle identity and immutable payload are unchanged")
                    old_history = old_payload.get("state_history", [])
                    new_history = new_payload.get("state_history", [])
                    if len(new_history) != len(old_history) + 1 or new_history[: len(old_history)] != old_history:
                        _fail(item_path + ".payload.state_history", "RG-06 lifecycle must append exactly one history event")
                    if Decimal(new_payload["total_amount"]) != Decimal(new_payload["paid_amount"]) + Decimal(new_payload["due_amount"]):
                        _fail(item_path + ".payload", "RG-06 lifecycle totals must remain balanced")
                    if action_type in {"change_staged_payment_fulfillment", "confirm_staged_payment_completion"} and (
                        new_payload["paid_amount"] != old_payload["paid_amount"]
                        or new_payload["due_amount"] != old_payload["due_amount"]
                    ):
                        _fail(item_path + ".payload", "RG-06 status transitions cannot change payment arithmetic")
                    continue
                if (
                    case_id == "RG-07"
                    and action_type == "confirm_manual_refund_receipt"
                    and outcome_status == "accepted"
                    and before[item_id].get("type") == after[item_id].get("type") == "refund_relationship"
                    and before[item_id].get("payload", {}).get("relation_id") == target_relation_id
                ):
                    old_outer = {key: value for key, value in before[item_id].items() if key != "payload"}
                    new_outer = {key: value for key, value in after[item_id].items() if key != "payload"}
                    if not _contract_equivalent(old_outer, new_outer):
                        _fail(item_path, "RG-07 relationship stable identity is immutable")
                    mutable = {"refund_transaction_id", "received_amount", "destination_account_id", "times", "state_history"}
                    if not _contract_equivalent(
                        {key: value for key, value in old_payload.items() if key not in mutable},
                        {key: value for key, value in new_payload.items() if key not in mutable},
                    ):
                        _fail(item_path + ".payload", "RG-07 receipt cannot change relationship identity or original allocation")
                    old_history = old_payload["state_history"]
                    new_history = new_payload["state_history"]
                    if len(new_history) != len(old_history) + 1 or new_history[: len(old_history)] != old_history:
                        _fail(item_path + ".payload.state_history", "RG-07 receipt must append exactly one history event")
                    continue
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
                elif (
                    case_id == "RG-08"
                    and outcome_status == "accepted"
                    and not _contract_equivalent(before[item_id], after[item_id])
                    and before[item_id].get("type") == after[item_id].get("type") == "lending_position"
                ):
                    if before[item_id]["id"] != after[item_id]["id"]:
                        _fail(item_path, "lending position stable identity is immutable")
                    immutable = {
                        key: value for key, value in old_payload.items()
                        if key not in {"principal_balance", "history"}
                    }
                    new_immutable = {
                        key: value for key, value in new_payload.items()
                        if key not in {"principal_balance", "history"}
                    }
                    if not _contract_equivalent(immutable, new_immutable):
                        _fail(item_path + ".payload", "collection cannot change lending position identity")
                    old_history = old_payload["history"]
                    new_history = new_payload["history"]
                    if len(new_history) != len(old_history) + 1 or new_history[: len(old_history)] != old_history:
                        _fail(item_path + ".payload.history", "collection must append exactly one position event")
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


def _rg08_retry_receipts(
    operation: dict[str, Any],
    baseline: dict[str, Any],
    result: dict[str, Any],
) -> dict[str, list[dict[str, str]]]:
    input_value = operation["input"]
    variant = input_value["variant"]
    changes = _expected_entity_changes(baseline, result)
    by_collection = {
        name: {item["id"]: item for item in _collection(result, parts)}
        for name, parts in _ENTITY_COLLECTIONS.items()
    }

    def ids(name: str) -> list[str]:
        return changes[name]["added_ids"]

    def one(name: str) -> dict[str, Any]:
        values = ids(name)
        if len(values) != 1:
            return {}
        return by_collection[name][values[0]]

    receipts: dict[str, list[dict[str, str]]] = {}
    if variant == "rename_counterparty":
        receipts[input_value["request_id"]] = [
            {"kind": "counterparty", "id": input_value["counterparty_id"]},
            {"kind": "name_history", "id": input_value["name_history_id"]},
        ]
        return receipts
    if variant == "lend":
        transaction, version, position = one("transactions"), one("transaction_versions"), one("domain_entities")
        receipts[input_value["request_id"]] = [
            {"kind": "transaction", "id": transaction.get("id", "")},
            {"kind": "transaction_version", "id": version.get("id", "")},
            {"kind": "domain_entity", "id": position.get("id", "")},
        ]
        source, evidence, link = one("sources"), one("evidence"), one("evidence_links")
        postings = [by_collection["postings"][item_id] for item_id in ids("postings")]
        target = next((item for item in postings if item.get("role") == "lending_principal_out"), {})
        receipts[source.get("id", "")] = [
            {"kind": "source", "id": source.get("id", "")},
            {"kind": "evidence", "id": evidence.get("id", "")},
            {"kind": "evidence_link", "id": link.get("id", "")},
            {"kind": "posting", "id": target.get("id", "")},
        ]
    elif variant in {"manual_collection", "maximum_allocation"}:
        transaction, version = one("transactions"), one("transaction_versions")
        settlement = next(
            (by_collection["domain_entities"][item_id] for item_id in ids("domain_entities")
             if by_collection["domain_entities"][item_id].get("type") == "lending_settlement"),
            {},
        )
        request_ids = [
            {"kind": "transaction", "id": transaction.get("id", "")},
            {"kind": "transaction_version", "id": version.get("id", "")},
            {"kind": "domain_entity", "id": settlement.get("id", "")},
        ]
        if variant == "maximum_allocation":
            position = next(
                (item for item in result["domain_entities"] if item.get("type") == "lending_position"
                 and item["id"] == settlement.get("payload", {}).get("linked_position_id")),
                {},
            )
            request_ids.append({"kind": "domain_entity", "id": position.get("id", "")})
        else:
            request_ids.extend(
                {"kind": "component", "id": item["id"]}
                for item in settlement.get("payload", {}).get("components", [])
            )
        receipts[input_value["request_id"]] = request_ids
        if variant == "manual_collection":
            for source_id in ids("sources"):
                source = by_collection["sources"][source_id]
                if source["type"] == "explicit_manual_lending_confirmation":
                    receipts[source_id] = [
                        {"kind": "source", "id": source_id},
                        {"kind": "transaction", "id": transaction.get("id", "")},
                        {"kind": "domain_entity", "id": settlement.get("id", "")},
                    ]
                elif source["type"] == "bank_credit":
                    evidence = next((item for item in result["evidence"] if item["source_ids"] == [source_id]), {})
                    link = next((item for item in result["evidence_links"] if item["evidence_id"] == evidence.get("id")), {})
                    receipts[source_id] = [
                        {"kind": "source", "id": source_id},
                        {"kind": "evidence", "id": evidence.get("id", "")},
                        {"kind": "evidence_link", "id": link.get("id", "")},
                        {"kind": "posting", "id": link.get("target_id", "")},
                    ]
    elif variant == "import_intake":
        candidate = one("candidates")
        receipts[input_value["credit_source_id"]] = [
            {"kind": "source", "id": input_value["credit_source_id"]},
            {"kind": "candidate", "id": candidate.get("id", "")},
        ]
        agreement_id = input_value["agreement_source_id"]
        evidence = next((item for item in result["evidence"] if item["source_ids"] == [agreement_id]), {})
        link = next((item for item in result["evidence_links"] if item["evidence_id"] == evidence.get("id")), {})
        receipts[agreement_id] = [
            {"kind": "source", "id": agreement_id},
            {"kind": "evidence", "id": evidence.get("id", "")},
            {"kind": "evidence_link", "id": link.get("id", "")},
            {"kind": "domain_entity", "id": link.get("target_id", "")},
        ]
    elif variant == "formal_confirmation":
        transaction, version = one("transactions"), one("transaction_versions")
        settlement = next(
            (by_collection["domain_entities"][item_id] for item_id in ids("domain_entities")
             if by_collection["domain_entities"][item_id].get("type") == "lending_settlement"),
            {},
        )
        receipts[input_value["request_id"]] = [
            {"kind": "candidate", "id": input_value["candidate_id"]},
            {"kind": "transaction", "id": transaction.get("id", "")},
            {"kind": "transaction_version", "id": version.get("id", "")},
            {"kind": "domain_entity", "id": settlement.get("id", "")},
        ]
    elif variant == "mirror_merge":
        source, evidence, link = one("sources"), one("evidence"), one("evidence_links")
        returned = [
            {"kind": "source", "id": source.get("id", "")},
            {"kind": "evidence", "id": evidence.get("id", "")},
            {"kind": "evidence_link", "id": link.get("id", "")},
            {"kind": "posting", "id": input_value["target_posting_id"]},
        ]
        receipts[input_value["request_id"]] = returned
        receipts[input_value["source_id"]] = returned
    return {key: value for key, value in receipts.items() if key and all(item["id"] for item in value)}


def _rg08_cross_root_state_payload(state: dict[str, Any]) -> dict[str, Any]:
    value = _state_payload(state)
    value.pop("root_id", None)
    return value


def _validate_no_change_retry(
    operation: dict[str, Any],
    operation_path: str,
    earlier_operations: list[dict[str, Any]],
    *,
    all_operations: list[dict[str, Any]] | None = None,
    states: dict[str, dict[str, Any]] | None = None,
) -> None:
    if operation["outcome"]["status"] != "no_change":
        return
    if operation["action_type"] == "validate_lending_event":
        if operation["input"].get("variant") != "rename_counterparty":
            _fail(operation_path + ".input.variant", "RG-08 no-change lending event must be the rename variant")
        expected = [
            {"kind": "counterparty", "id": operation["input"]["counterparty_id"]},
            {"kind": "name_history", "id": operation["input"]["name_history_id"]},
        ]
        if operation["returned_ids"] != expected:
            _fail(operation_path + ".returned_ids", "rename must return the exact counterparty and name-history identities")
        return
    if operation["action_type"] == "retry_idempotent_input":
        if all_operations is None or states is None:
            _fail(operation_path, "RG-08 retry validation requires the complete case operation/state inventory")
        anchor = operation["input"]["input_anchor_id"]
        owners: list[tuple[dict[str, Any], list[dict[str, str]]]] = []
        retry_index = all_operations.index(operation)
        for candidate_index, candidate in enumerate(all_operations):
            if candidate["id"] == operation["id"] or candidate["action_type"] not in _RG08_ACTIONS:
                continue
            if candidate_index >= retry_index:
                continue
            if candidate["outcome"]["status"] not in {"accepted", "no_change"}:
                continue
            if candidate.get("input", {}).get("variant") == "retry":
                continue
            candidate_baseline = states[candidate["baseline_state_id"]]
            candidate_result = states[candidate["result_state_id"]]
            receipt = _rg08_retry_receipts(candidate, candidate_baseline, candidate_result).get(anchor)
            if receipt is not None:
                owners.append((candidate, receipt))
        if len(owners) != 1:
            _fail(operation_path + ".input.input_anchor_id", "must resolve exactly one RG-08 first-time anchor owner")
        owner, expected_returned = owners[0]
        owner_result = states[owner["result_state_id"]]
        retry_baseline = states[operation["baseline_state_id"]]
        if not _contract_equivalent(
            _rg08_cross_root_state_payload(owner_result),
            _rg08_cross_root_state_payload(retry_baseline),
        ):
            _fail(operation_path + ".baseline_state_id", "must be contract-equivalent to the unique anchor owner's result state")
        if operation["returned_ids"] != expected_returned:
            _fail(operation_path + ".returned_ids", "must exactly return the anchored accepted result IDs")
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


def _rg08_effect_counts(operation: dict[str, Any], path: str) -> dict[str, tuple[int, int, int]]:
    if operation["outcome"]["status"] != "accepted":
        return {}
    variant = operation["input"]["variant"]
    common_formal = {
        "transactions": (1, 0, 0),
        "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0),
        "confirmations": (1, 0, 0),
    }
    variants = {
        "lend": {
            **common_formal, "postings": (2, 0, 0), "sources": (1, 0, 0),
            "evidence": (1, 0, 0), "evidence_links": (1, 0, 0),
            "relations": (1, 0, 0), "domain_entities": (1, 0, 0),
            "posting_reconciliations": (1, 0, 0),
        },
        "manual_collection": {
            **common_formal, "postings": (3, 0, 0), "sources": (2, 0, 0),
            "evidence": (1, 0, 0), "evidence_links": (1, 0, 0),
            "domain_entities": (1, 1, 0), "posting_reconciliations": (1, 0, 0),
        },
        "maximum_allocation": {
            **common_formal, "postings": (3, 0, 0),
            "domain_entities": (1, 1, 0), "posting_reconciliations": (1, 0, 0),
        },
        "import_intake": {
            "sources": (2, 0, 0), "evidence": (2, 0, 0),
            "candidates": (1, 0, 0), "evidence_links": (1, 0, 0),
        },
        "formal_confirmation": {
            **common_formal, "postings": (3, 0, 0), "candidates": (0, 1, 0),
            "evidence_links": (1, 0, 0), "domain_entities": (1, 1, 0),
            "posting_reconciliations": (1, 0, 0),
        },
        "mirror_merge": {
            "sources": (1, 0, 0), "evidence": (1, 0, 0),
            "evidence_links": (1, 0, 0), "audit_links": (2, 0, 0),
            "posting_reconciliations": (0, 0, 0),
        },
    }
    counts = variants.get(variant)
    if counts is None:
        _fail(path + ".input.variant", "unregistered accepted RG-08 variant")
    return counts


def _validate_rg07_action_effects(
    operation: dict[str, Any],
    operation_path: str,
    result: dict[str, Any],
    expected_entities: dict[str, dict[str, list[str]]],
) -> None:
    action = operation["action_type"]
    by_collection = {
        name: {item["id"]: item for item in _collection(result, parts)}
        for name, parts in _ENTITY_COLLECTIONS.items()
    }

    def changed(collection: str) -> list[dict[str, Any]]:
        ids = (
            expected_entities[collection]["added_ids"]
            + expected_entities[collection]["changed_ids"]
        )
        return [by_collection[collection][item_id] for item_id in ids]

    expected_types = {
        "record_refund_request_status": {
            "relations": {"refund"},
            "domain_entities": {"refund_relationship"},
        },
        "ingest_refund_status_source": {
            "sources": {"merchant_refund_notice"},
            "evidence": {"refund_notice"},
        },
        "confirm_manual_refund_receipt": {
            "transactions": {"refund_receipt"},
            "confirmations": {"refund_relationship_confirmation"},
            "relations": {"refund"},
            "domain_entities": {"refund_relationship"},
        },
        "attach_original_payment_evidence": {
            "sources": {"bank_debit"},
            "evidence": {"asset_debit"},
        },
        "attach_refund_destination_evidence": {
            "sources": {"wallet_credit"},
            "evidence": {"asset_credit"},
        },
        "attach_refund_dual_role_evidence": {
            "sources": {"combined_refund_statement"},
            "evidence": {"combined_refund_statement"},
        },
        "confirm_refund_receipt": {
            "transactions": {"refund_receipt"},
            "confirmations": {"refund_relationship_confirmation"},
            "relations": {"refund"},
            "domain_entities": {"refund_relationship"},
        },
        "ingest_refund_credit_source": {
            "sources": {"wallet_credit"},
            "evidence": {"asset_credit"},
            "candidates": {"refund_credit"},
        },
        "confirm_imported_refund": {
            "transactions": {"refund_receipt"},
            "candidates": {"refund_credit"},
            "confirmations": {"refund_relationship_confirmation"},
            "relations": {"refund"},
            "domain_entities": {"refund_relationship"},
        },
        "merge_refund_mirror_evidence": {
            "sources": {"wallet_credit_mirror"},
            "evidence": {"asset_credit_mirror"},
        },
    }
    for collection, allowed_types in expected_types[action].items():
        items = changed(collection)
        if not items or {item["type"] for item in items} != allowed_types:
            _fail(
                operation_path + f".deltas.entity_changes.{collection}",
                f"{action} must own exactly the registered RG-07 {collection} subtype",
            )

    for confirmation in changed("confirmations"):
        if confirmation["operation_id"] != operation["id"]:
            _fail(
                operation_path + ".deltas.entity_changes.confirmations",
                "RG-07 confirmation must belong to the creating operation",
            )
    links = changed("evidence_links")
    expected_roles = {
        "attach_original_payment_evidence": {"payment_asset_posting"},
        "ingest_refund_status_source": {"refund_relationship"},
        "attach_refund_destination_evidence": {"destination_asset_posting"},
        "attach_refund_dual_role_evidence": {
            "refund_relationship",
            "destination_asset_posting",
        },
        "confirm_imported_refund": {"destination_asset_posting"},
        "merge_refund_mirror_evidence": {"destination_asset_posting"},
    }
    if action in expected_roles and {item["role"] for item in links} != expected_roles[action]:
        _fail(
            operation_path + ".deltas.entity_changes.evidence_links",
            "RG-07 evidence links must have the action's exact role set",
        )


def _validate_rg07_input_bindings(
    operation: dict[str, Any],
    operation_path: str,
    baseline: dict[str, Any],
    result: dict[str, Any],
    expected_entities: dict[str, dict[str, list[str]]],
) -> None:
    """Bind accepted RG-07 input facts to the exact entities they create or mutate."""
    action = operation["action_type"]
    value = operation["input"]

    def changed(collection: str) -> list[dict[str, Any]]:
        by_id = {item["id"]: item for item in _collection(result, _ENTITY_COLLECTIONS[collection])}
        ids = expected_entities[collection]["added_ids"] + expected_entities[collection]["changed_ids"]
        return [by_id[item_id] for item_id in ids]

    def require(condition: bool, field: str, message: str) -> None:
        if not condition:
            _fail(operation_path + ".input." + field, message)

    if action == "record_refund_request_status":
        relation = changed("relations")[0]
        entity = changed("domain_entities")[0]
        payload = entity["payload"]
        require(relation["id"] == "refund-relation-rg07-manual", "request_id", "request must own the created refund relation")
        require(payload["original_transaction_id"] == value["original_transaction_id"], "original_transaction_id", "must bind the relationship original")
        require(payload["requested_amount"] == value["requested_amount"] and payload["currency"] == value["currency"], "requested_amount", "must bind requested amount and currency")
        require(payload["times"]["requested_at"] == value["requested_at"] and payload["times"]["approved_at"] == value["approved_at"] and payload["times"]["processor_reported_at"] == value["processor_reported_at"], "requested_at", "must bind all request lifecycle times")
    elif action in {"ingest_refund_status_source", "attach_original_payment_evidence", "attach_refund_destination_evidence", "attach_refund_dual_role_evidence", "merge_refund_mirror_evidence"}:
        source = changed("sources")[0]
        evidence = changed("evidence")[0]
        link = changed("evidence_links")[0]
        require(source["id"] == value["source_id"], "source_id", "must bind the created source")
        if "evidence_id" in value:
            require(evidence["id"] == value["evidence_id"], "evidence_id", "must bind the created evidence")
        require(evidence["source_ids"] == [source["id"]], "source_id", "must bind the created evidence to the source")
        require(link["evidence_id"] == evidence["id"], "evidence_id", "must bind the evidence link")
        source_payload = source["payload"]
        for field in ("amount", "currency", "account_id", "observed_at", "booking_at", "value_at"):
            if field in value:
                require(source_payload.get(field) == value[field], field, "must exactly bind the source payload")
        if action == "ingest_refund_status_source":
            require(source_payload.get("observed_at") == value["observed_at"], "observed_at", "must bind source observation time")
            require(link["target_id"] == value["refund_relation_id"], "refund_relation_id", "must target the exact refund relation")
        if action == "attach_original_payment_evidence":
            require(link["target_id"] == value["payment_asset_posting_id"], "payment_asset_posting_id", "must target the exact payment posting")
        elif action == "attach_refund_destination_evidence":
            require(link["target_kind"] == "posting", "destination_asset_posting_id", "must target a posting")
            require(link["target_id"] == value["destination_asset_posting_id"], "destination_asset_posting_id", "must target the exact destination posting")
        elif action == "attach_refund_dual_role_evidence":
            links_by_role = {item["role"]: item for item in changed("evidence_links")}
            require(links_by_role["refund_relationship"]["target_id"] == value["refund_relation_id"], "refund_relation_id", "must target the exact refund relation")
            require(links_by_role["destination_asset_posting"]["target_id"] == value["destination_asset_posting_id"], "destination_asset_posting_id", "must target the exact destination posting")
        elif action == "merge_refund_mirror_evidence":
            require(link["target_kind"] == "posting", "evidence_id", "must target a posting")
    elif action == "ingest_refund_credit_source":
        source = changed("sources")[0]
        evidence = changed("evidence")[0]
        candidate = changed("candidates")[0]
        require(source["id"] == value["source_id"], "source_id", "must bind the created source")
        require(evidence["id"] == value.get("evidence_id", evidence["id"]) and evidence["source_ids"] == [source["id"]], "source_id", "candidate evidence must bind the source")
        require(candidate["source_ids"] == [source["id"]], "source_id", "candidate must bind the source")
        require(candidate["payload"]["proposed_amount"] == value["amount"] and candidate["payload"]["currency"] == value["currency"], "amount", "candidate must preserve source amount and currency")
    elif action in {"confirm_manual_refund_receipt", "confirm_refund_receipt", "confirm_imported_refund"}:
        transaction = changed("transactions")[0]
        relation = changed("relations")[0]
        entity = changed("domain_entities")[0]
        confirmation = changed("confirmations")[0]
        payload = entity["payload"]
        require(transaction["type"] == "refund_receipt", "amount", "must create a refund receipt transaction")
        require(payload["original_transaction_id"] == value["original_transaction_id"], "original_transaction_id", "must bind the relationship original")
        require(payload["received_amount"] == value.get("amount", value.get("allocated_amount")), "amount", "must bind the received allocation")
        require(payload["refund_transaction_id"] == transaction["id"], "original_transaction_id", "relationship must own the created receipt")
        require(confirmation["subject"] == {"kind": "relation", "id": relation["id"]}, "original_transaction_id", "confirmation must bind the created relation")
        if action == "confirm_imported_refund":
            candidate = changed("candidates")[0]
            require(candidate["id"] == value["candidate_id"] and candidate["status_history"][-1]["status"] == "confirmed", "candidate_id", "must confirm the exact candidate")


def _validate_rg09_action_effects(
    operation: dict[str, Any],
    operation_path: str,
    result: dict[str, Any],
    expected_entities: dict[str, dict[str, list[str]]],
) -> None:
    """Validate the RG-09 accepted families' entity ownership and bindings."""
    action = operation["action_type"]
    by_collection = {
        name: {item["id"]: item for item in _collection(result, parts)}
        for name, parts in _ENTITY_COLLECTIONS.items()
    }

    def changed(collection: str) -> list[dict[str, Any]]:
        ids = (
            expected_entities[collection]["added_ids"]
            + expected_entities[collection]["changed_ids"]
        )
        return [by_collection[collection][item_id] for item_id in ids]

    if action == "save_zero_delta_observation":
        source = changed("sources")[0]
        evidence = changed("evidence")[0]
        link = changed("evidence_links")[0]
        observation = changed("domain_entities")[0]
        if (
            source["type"] != "explicit_balance_observation"
            or evidence["type"] != "user_balance_observation"
            or evidence["source_ids"] != [source["id"]]
            or link["evidence_id"] != evidence["id"]
            or link["target_kind"] != "observation"
            or link["target_id"] != observation["id"]
            or link["role"] != "target_balance_observation"
            or observation["type"] != "target_balance_observation"
            or observation["payload"].get("source_id") != source["id"]
        ):
            _fail(
                operation_path + ".deltas.entity_changes",
                "zero-delta observation must own exactly one source, evidence, observation, and link",
            )
        return

    if action == "receive_import_candidate":
        source = changed("sources")[0]
        evidence = changed("evidence")[0]
        candidate = changed("candidates")[0]
        if (
            source["type"] != "imported_transfer_candidate"
            or evidence["type"] != "imported_real_transaction_candidate"
            or evidence["source_ids"] != [source["id"]]
            or candidate["source_ids"] != [source["id"]]
            or [item["status"] for item in candidate["status_history"]]
            != ["pending_confirmation"]
        ):
            _fail(
                operation_path + ".deltas.entity_changes",
                "import intake must create one pending imported-transfer candidate bound to its source",
            )
        return

    if action == "link_real_posting_evidence":
        source = changed("sources")[0]
        evidence = changed("evidence")[0]
        link = changed("evidence_links")[0]
        reconciliation_changes = expected_entities["posting_reconciliations"]
        if (
            source["type"] != "account_statement"
            or evidence["type"] != "real_account_posting"
            or evidence["source_ids"] != [source["id"]]
            or link["evidence_id"] != evidence["id"]
            or link["target_kind"] != "posting"
            or link["role"] != "real_account_posting"
            or reconciliation_changes["added_ids"]
            or reconciliation_changes["removed_ids"]
            or len(reconciliation_changes["changed_ids"]) != 1
        ):
            _fail(
                operation_path + ".deltas.entity_changes",
                "real-posting evidence must bind one source, evidence, posting link, and exactly one reconciliation",
            )
        reconciliation = by_collection["posting_reconciliations"][
            reconciliation_changes["changed_ids"][0]
        ]
        if (
            reconciliation["posting_id"] != link["target_id"]
            or reconciliation["status"] != "matched"
        ):
            _fail(
                operation_path + ".deltas.entity_changes.posting_reconciliations",
                "linked posting reconciliation must become matched",
            )
        return

    result_transactions = {
        item["id"]: item for item in result["transactions"]
    }
    result_versions = {
        item["id"]: item for item in result["transaction_versions"]
    }
    result_sets = {item["id"]: item for item in result["posting_sets"]}
    result_postings = {item["id"]: item for item in result["postings"]}
    result_confirmations = {
        item["id"]: item for item in result["confirmations"]
    }

    def added_item(collection_name: str, items: dict[str, dict[str, Any]]) -> dict[str, Any]:
        item_id = expected_entities[collection_name]["added_ids"][0]
        return items[item_id]

    added_transactions = expected_entities["transactions"]["added_ids"]
    if len(added_transactions) != 1:
        _fail(
            operation_path + ".deltas.entity_changes.transactions",
            "RG-09 confirmation must add exactly one transaction",
        )
    transaction = result_transactions[added_transactions[0]]
    version = result_versions[expected_entities["transaction_versions"]["added_ids"][0]]
    posting_set = added_item("posting_sets", result_sets)
    added_posting_ids = expected_entities["postings"]["added_ids"]
    added_postings = [result_postings[item_id] for item_id in added_posting_ids]
    if (
        version["transaction_id"] != transaction["id"]
        or version["version_number"] != 1
        or transaction["current_version_id"] != version["id"]
        or version["posting_set_id"] != posting_set["id"]
        or set(posting_set["posting_ids"]) != set(added_posting_ids)
        or any(item["posting_set_id"] != posting_set["id"] for item in added_postings)
    ):
        _fail(
            operation_path + ".deltas.entity_changes",
            f"{action} must add a v1 transaction with exactly its own posting set and postings",
        )
    if action in {"confirm_imported_real_transfer", "confirm_second_real_transfer"}:
        expected_type = "account_transfer"
        expected_roles = {"transfer_principal_in", "transfer_principal_out"}
    else:
        expected_type = "balance_adjustment_reversal"
        expected_roles = {
            "balance_adjustment_reversal_target",
            "balance_adjustment_reversal_counterpart",
        }
    if transaction["type"] != expected_type or {
        item.get("role") for item in added_postings
    } != expected_roles:
        _fail(
            operation_path + ".deltas.entity_changes.postings",
            f"{action} must create transaction type {expected_type} with exact roles",
        )
    confirmation = added_item("confirmations", result_confirmations)
    if (
        confirmation["type"] != "explicit_operation_confirmation"
        or confirmation["operation_id"] != operation["id"]
        or confirmation["subject"] != {"kind": "operation", "id": operation["id"]}
        or confirmation.get("confirmed_at") != operation["input"]["confirmed_at"]
        or version.get("confirmation_id") != confirmation["id"]
    ):
        _fail(
            operation_path + ".deltas.entity_changes.confirmations",
            "RG-09 confirmation must own the created transaction version",
        )
    if action == "confirm_imported_real_transfer":
        candidate_changes = expected_entities["candidates"]
        if (
            candidate_changes["added_ids"]
            or candidate_changes["removed_ids"]
            or candidate_changes["changed_ids"]
        ):
            _fail(
                operation_path + ".deltas.entity_changes.candidates",
                "real-transfer confirmation alone must leave the imported candidate pending",
            )
    if action == "confirm_imported_explanation_allocation":
        candidates = changed("candidates")
        if len(candidates) != 1:
            _fail(operation_path + ".deltas.entity_changes.candidates", "complete imported explanation must confirm exactly one candidate")
        candidate = candidates[0]
        event = candidate["status_history"][-1]
        if (
            [item["status"] for item in candidate["status_history"]] != ["pending_confirmation", "confirmed"]
            or event.get("adjustment_id") != operation["input"].get("adjustment_id", "adjustment-rg09")
            or event.get("confirmation_request_id") != operation["input"]["request_id"]
        ):
            _fail(operation_path + ".deltas.entity_changes.candidates", "complete imported explanation must own adjustment and request provenance")
    if action in {"confirm_imported_explanation_allocation", "confirm_second_explanation_allocation"}:
        allocation = added_item("domain_entities", {
            item["id"]: item for item in result["domain_entities"]
        })
        if allocation["type"] != "explanation_allocation":
            _fail(
                operation_path + ".deltas.entity_changes.domain_entities",
                "explanation confirmation must add one allocation entity",
            )
        audit_links = [
            item for item in result["audit_links"]
            if item["id"] in expected_entities["audit_links"]["added_ids"]
        ]
        expected_link_types = {
            "adjustment_transaction", "explanation_transaction", "allocation_reversal"
        }
        if {item["type"] for item in audit_links} != expected_link_types:
            _fail(
                operation_path + ".deltas.entity_changes.audit_links",
                f"{action} must add exactly the registered audit link types",
            )
        for link in audit_links:
            if link["type"] == "adjustment_transaction":
                adjustment = next(
                    (
                        item
                        for item in result["domain_entities"]
                        if item.get("type") == "balance_adjustment"
                    ),
                    None,
                )
                if adjustment is None or (
                    link["from"] != {"kind": "domain_entity", "id": adjustment["id"]}
                    or link["to"] != {"kind": "transaction", "id": "transaction-adjustment-rg09"}
                ):
                    _fail(
                        operation_path + ".deltas.entity_changes.audit_links",
                        "adjustment_transaction audit link must join the adjustment to its transaction",
                    )
            elif link["from"] != {"kind": "domain_entity", "id": allocation["id"]}:
                _fail(
                    operation_path + ".deltas.entity_changes.audit_links",
                    f"{action} allocation audit links must originate from the added allocation",
                )
    if action == "confirm_second_real_transfer":
        source = added_item("sources", by_collection["sources"])
        input_value = operation["input"]
        if (
            source["type"] != "manual_transaction_confirmation"
            or source["payload"].get("account_id") != input_value["target_account_id"]
            or source["payload"].get("counter_account_id") != input_value["counter_account_id"]
            or source["payload"].get("amount") != input_value["amount"]
            or source["payload"].get("currency") != input_value["currency"]
            or source["payload"].get("actual_at") != input_value["actual_occurred_at"]
            or not isinstance(source["payload"].get("immutable_payload_digest"), str)
            or not source["payload"]["immutable_payload_digest"].startswith("sha256:")
        ):
            _fail(
                operation_path + ".deltas.entity_changes.sources",
                "second real transfer must add one manual_transaction_confirmation source bound to the action input",
            )


def _validate_rg08_action_effects(
    operation: dict[str, Any],
    operation_path: str,
    baseline: dict[str, Any],
    result: dict[str, Any],
    expected_entities: dict[str, dict[str, list[str]]],
) -> None:
    variant = operation["input"]["variant"]
    added = lambda name: expected_entities[name]["added_ids"]
    changed = lambda name: expected_entities[name]["changed_ids"]
    result_by = {
        name: {item["id"]: item for item in _collection(result, parts)}
        for name, parts in _ENTITY_COLLECTIONS.items()
    }
    receipts = _rg08_retry_receipts(operation, baseline, result)
    identity_field = {
        "lend": "request_id",
        "manual_collection": "request_id",
        "maximum_allocation": "request_id",
        "import_intake": "credit_source_id",
        "formal_confirmation": "request_id",
        "mirror_merge": "source_id",
    }[variant]
    expected_returned = receipts.get(operation["input"][identity_field])
    if expected_returned is None or operation["returned_ids"] != expected_returned:
        _fail(operation_path + ".returned_ids", "must exactly return the RG-08 input-owned result identities")
    if variant in {"lend", "manual_collection", "maximum_allocation", "formal_confirmation"}:
        transaction = result_by["transactions"][added("transactions")[0]]
        version = result_by["transaction_versions"][added("transaction_versions")[0]]
        if version["transaction_id"] != transaction["id"] or version["id"] != transaction["current_version_id"]:
            _fail(operation_path + ".result_state_id", "RG-08 transaction/version ownership is not closed")
        expected_type = "lending_disbursement" if variant == "lend" else "lending_collection"
        if transaction["type"] != expected_type:
            _fail(operation_path + ".result_state_id", f"{variant} must create {expected_type}")
        confirmation = result_by["confirmations"][added("confirmations")[0]]
        expected_confirmation = "lending_event_confirmation" if variant == "lend" else "lending_settlement_confirmation"
        if confirmation["type"] != expected_confirmation or confirmation["operation_id"] != operation["id"]:
            _fail(operation_path + ".result_state_id", "RG-08 confirmation subtype and operation owner must match")
        if version.get("confirmation_id") != confirmation["id"]:
            _fail(operation_path + ".result_state_id", "RG-08 version must bind its confirmation")
        input_value = operation["input"]
        confirmation_payload = confirmation["payload"]
        if (
            confirmation["subject"] != {"kind": "transaction", "id": transaction["id"]}
            or confirmation_payload.get("confirmation_request_id") != input_value["request_id"]
            or confirmation_payload.get("transaction_id") != transaction["id"]
            or confirmation_payload.get("counterparty_id") != input_value["counterparty_id"]
            or confirmation.get("confirmed_at") != input_value["confirmed_at"]
        ):
            _fail(operation_path + ".result_state_id", "RG-08 confirmation identity, subject, counterparty, request, and time must bind input")
        if variant == "lend":
            position = result_by["domain_entities"][added("domain_entities")[0]]
            postings = [result_by["postings"][item_id] for item_id in added("postings")]
            roles = {item.get("role"): item for item in postings}
            if (
                position.get("type") != "lending_position"
                or position["payload"]["counterparty_id"] != input_value["counterparty_id"]
                or position["payload"]["currency"] != input_value["currency"]
                or position["payload"]["principal_balance"] != input_value["principal_amount"]
                or roles.get("lending_principal_out", {}).get("account_id") != input_value["funding_account_id"]
                or roles.get("lending_principal_out", {}).get("amount") != "-" + input_value["principal_amount"]
                or roles.get("lending_receivable", {}).get("amount") != input_value["principal_amount"]
            ):
                _fail(operation_path + ".result_state_id", "lend effects must exactly bind counterparty, principal, currency, and funding input")
        else:
            settlement = next(
                result_by["domain_entities"][item_id]
                for item_id in added("domain_entities")
                if result_by["domain_entities"][item_id].get("type") == "lending_settlement"
            )
            payload = settlement["payload"]
            components = {item["kind"]: item for item in payload["components"]}
            expected_values = {
                "behavior_code": input_value.get("behavior_code", "collect"),
                "counterparty_id": input_value["counterparty_id"],
                "destination_account_id": input_value["destination_account_id"],
                "interest_category_id": input_value["interest_category_id"],
                "total_received": input_value.get("total_received", payload["total_received"]),
                "currency": input_value["currency"],
                "actual_receipt_at": input_value["actual_receipt_at"],
                "confirmed_at": input_value["confirmed_at"],
            }
            if any(payload[field] != value for field, value in expected_values.items()) or any(
                components[kind]["amount"] != input_value[f"{kind}_amount"]
                for kind in ("principal", "interest", "fee")
            ):
                _fail(operation_path + ".result_state_id", f"{variant} settlement and components must exactly bind action input")
            expected_position = input_value.get("linked_position_id")
            if expected_position is None:
                expected_position = next(
                    (
                        item["id"]
                        for item in baseline["domain_entities"]
                        if item.get("type") == "lending_position"
                        and item["payload"]["counterparty_id"] == input_value["counterparty_id"]
                    ),
                    None,
                )
            if (
                payload["linked_position_id"] != expected_position
                or payload["allocated_lend_transaction_id"] != input_value.get("allocated_lend_transaction_id")
                or confirmation_payload.get("settlement_id") != settlement["id"]
            ):
                _fail(operation_path + ".result_state_id", f"{variant} settlement must bind the exact position and allocation owner")
            if variant == "formal_confirmation" and confirmation["payload"].get("candidate_id") != input_value["candidate_id"]:
                _fail(operation_path + ".result_state_id", "formal confirmation must bind the input candidate")
    if variant == "import_intake":
        if any(added(name) or changed(name) for name in ("transactions", "transaction_versions", "posting_sets", "postings", "confirmations", "posting_reconciliations")):
            _fail(operation_path + ".deltas", "import intake must have zero formal and reconciliation effect")
        candidate = result_by["candidates"][added("candidates")[0]]
        if candidate["type"] != "lending_collection_credit" or [item["status"] for item in candidate["status_history"]] != ["pending_confirmation"]:
            _fail(operation_path + ".result_state_id", "import intake must add one pending lending collection candidate")
        input_value = operation["input"]
        if (
            candidate["id"] != input_value["candidate_id"]
            or set(candidate["source_ids"]) != {input_value["credit_source_id"], input_value["agreement_source_id"]}
            or candidate["confidence"] != input_value["confidence"]
            or candidate["payload"]["proposed_total_received"] != input_value["proposed_total_received"]
            or candidate["payload"]["proposed_destination_account_id"] != input_value["proposed_destination_account_id"]
            or candidate["payload"]["proposed_actual_receipt_at"] != input_value["proposed_actual_receipt_at"]
            or candidate["payload"]["currency"] != input_value["currency"]
            or candidate["payload"]["rule_version"] != input_value["rule_version"]
        ):
            _fail(operation_path + ".result_state_id", "import intake candidate must exactly bind source and proposed input")
    if variant == "formal_confirmation":
        candidate = result_by["candidates"][changed("candidates")[0]]
        if candidate["id"] != operation["input"]["candidate_id"]:
            _fail(operation_path + ".deltas.entity_changes.candidates", "formal confirmation must change only the input candidate")
        if [item["status"] for item in candidate["status_history"]] != ["pending_confirmation", "confirmed"]:
            _fail(operation_path + ".result_state_id", "formal confirmation must append the candidate confirmed status")
    if variant == "mirror_merge":
        if any(added(name) or changed(name) for name in ("transactions", "transaction_versions", "posting_sets", "postings", "confirmations", "domain_entities")):
            _fail(operation_path + ".deltas", "mirror merge must have zero formal and lending-domain effect")
        audits = [result_by["audit_links"][item_id] for item_id in added("audit_links")]
        if {item["type"] for item in audits} != {"mirror_of_evidence_id", "merged_into_evidence_link_id"}:
            _fail(operation_path + ".result_state_id", "mirror merge must add both typed lending audit links")
        input_value = operation["input"]
        source = result_by["sources"][added("sources")[0]]
        evidence = result_by["evidence"][added("evidence")[0]]
        link = result_by["evidence_links"][added("evidence_links")[0]]
        if (
            source["id"] != input_value["source_id"]
            or source["payload"].get("mirror_of_source_id") != input_value["mirror_of_source_id"]
            or source["payload"].get("amount") != input_value["amount"]
            or source["payload"].get("currency") != input_value["currency"]
            or source["payload"].get("observed_at") != input_value["observed_at"]
            or evidence["source_ids"] != [source["id"]]
            or link["target_id"] != input_value["target_posting_id"]
            or any(item["to"]["id"] not in {input_value["mirror_of_evidence_id"], input_value["merged_into_evidence_link_id"]} for item in audits)
        ):
            _fail(operation_path + ".result_state_id", "mirror merge lineage must exactly bind input and existing evidence targets")


def _validate_registered_action_effects(
    operation: dict[str, Any],
    operation_path: str,
    result: dict[str, Any],
    expected_entities: dict[str, dict[str, list[str]]],
) -> None:
    action = operation["action_type"]
    accepted = operation["outcome"]["status"] == "accepted"
    registered_counts = (
        _rg08_effect_counts(operation, operation_path)
        if action in _RG08_ACTIONS
        else _ACCEPTED_ACTION_ENTITY_COUNTS.get(action)
    )
    if not accepted and action in _RG07_ACTIONS:
        registered_counts = {}
    if not accepted and action in _RG09_REJECTED_ACTIONS:
        registered_counts = {}
    if registered_counts is None:
        _fail(operation_path + ".action_type", "unregistered action type")
    if accepted and action == "create_periodic_allocation":
        registered_counts = {
            **registered_counts,
            "domain_entities": (operation["input"]["installment_count"] + 2, 0, 0),
        }
    elif accepted and action == "revise_periodic_allocation":
        registered_counts = {
            **registered_counts,
            "domain_entities": (operation["input"]["remaining_installment_count"] + 1, 0, 0),
        }
    elif accepted and action == "correct_transaction_version" and operation["input"]["correction_kind"] == "posting_facts":
        posting_count = len(operation["input"]["replacement_postings"])
        registered_counts = {
            **registered_counts,
            "posting_sets": (1, 0, 0),
            "postings": (posting_count, 0, 0),
            "domain_entities": (2, len(expected_entities["domain_entities"]["changed_ids"]), 0),
            "audit_links": (posting_count, 0, 0),
            "posting_reconciliations": (2, 0, 0),
        }

    for collection_name, changes in expected_entities.items():
        if accepted and action == "confirm_stored_value_spend":
            # The frozen contract owns two accepted spend instances with different
            # consumption counts (main-path spend 1, synthetic multi-lot base 4); the
            # per-action budget cannot be a single tuple, so the artifact tests assert
            # these counts instead (declared deltas still exactly recompute below).
            continue
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

    if action in _RG08_ACTIONS:
        # RG-08 accepted effect contracts are enforced by _rg08_effect_counts
        # and _validate_rg08_action_effects; no generic action branch applies.
        return

    if action in _RG07_ACTIONS:
        _validate_rg07_action_effects(
            operation, operation_path, result, expected_entities
        )
        _validate_rg07_input_bindings(
            operation, operation_path, {}, result, expected_entities
        )
        return

    if action in _RG09_ACCEPTED_ACTIONS:
        _validate_rg09_action_effects(
            operation, operation_path, result, expected_entities
        )
        return

    if action in _RG06_ACTIONS:
        return

    if action in _RG10_STRUCTURAL_ACTIONS:
        # D-083: RG-10 accepted economic effects (transaction types, posting shapes,
        # lot/consumption/expiry entities) are contract-registered with the expected
        # artifact; per-action effect contracts are registered with it (phase 2).
        return

    if action == "import_mirror_record":
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

    if action == "import_source_record":
        input_value = operation["input"]
        source = next(item for item in result["sources"] if item["id"] in expected_entities["sources"]["added_ids"])
        candidate = next(item for item in result["candidates"] if item["id"] in expected_entities["candidates"]["added_ids"])
        evidence = next(item for item in result["evidence"] if item["id"] in expected_entities["evidence"]["added_ids"])
        if source["id"] != input_value["source_id"] or evidence["id"] != input_value["evidence_id"] or candidate["source_ids"] != [source["id"]]:
            _fail(operation_path + ".result_state_id", "intake identities must be owned by the action input")
        expected_source_payload = {
            "source_account_id": input_value["source_account_id"], "destination_account_id": input_value["destination_account_id"],
            "source_debit_amount": input_value["source_debit_amount"], "destination_credit_amount": input_value["destination_credit_amount"],
            "fee_amount": input_value["fee_amount"], "currency": input_value["currency"], "completeness": "complete",
            "observed_at": input_value["observed_at"], "evidence_id": input_value["evidence_id"],
        }
        if source["type"] != "account_transfer" or source["payload"] != expected_source_payload:
            _fail(operation_path + ".result_state_id", "complete intake source must exactly equal the action input")
        if evidence["type"] != "transfer_record" or evidence["source_ids"] != [source["id"]] or evidence["payload"] != {"observed_at": input_value["observed_at"]}:
            _fail(operation_path + ".result_state_id", "complete intake evidence must exactly equal the source identity and observed_at")
        expected_candidate_payload = {
            "source_account_id": input_value["source_account_id"], "destination_account_id": input_value["destination_account_id"],
            "source_debit_amount": input_value["source_debit_amount"], "destination_credit_amount": input_value["destination_credit_amount"],
            "fee_amount": input_value["fee_amount"], "currency": input_value["currency"], "evidence_refs": [input_value["evidence_id"]],
            "provenance": {"rule": "complete_transfer_source", "rule_version": 1},
            "requires_confirmation": ["formal_transaction_creation"],
        }
        if candidate["type"] != "account_transfer" or candidate["confidence"] != "1.00" or candidate["payload"] != expected_candidate_payload:
            _fail(operation_path + ".result_state_id", "complete intake candidate must exactly equal the source-derived candidate contract")
        if [item["status"] for item in candidate["status_history"]] != ["pending_confirmation"]:
            _fail(operation_path + ".result_state_id", "complete intake must remain pending")
        expected_returned = [
            {"kind": "source", "id": source["id"]},
            {"kind": "evidence", "id": evidence["id"]},
            {"kind": "candidate", "id": candidate["id"]},
        ]
        if operation["returned_ids"] != expected_returned:
            _fail(operation_path + ".returned_ids", "complete intake must return exactly its source, evidence, and candidate")
        return

    if action == "ingest_mixed_payment_source":
        input_record = operation["input"]["source_record"]
        source = added_item("sources", {item["id"]: item for item in result["sources"]})
        candidate = added_item("candidates", result_candidates)
        evidence = added_item("evidence", {item["id"]: item for item in result["evidence"]})
        expected_payload = {key: value for key, value in input_record.items() if key != "id"}
        if source["id"] != input_record["id"] or source["type"] != "mixed_payment" or source["payload"] != expected_payload:
            _fail(operation_path + ".result_state_id", "mixed payment source must exactly preserve the source record")
        if evidence["type"] != "asset_funding_debit" or evidence["source_ids"] != [source["id"]] or evidence["payload"] != {"observed_at": input_record["observed_at"]}:
            _fail(operation_path + ".result_state_id", "mixed payment evidence must bind source identity and observed_at")
        expected_confidence, expected_candidate_payload = _mixed_payment_candidate_contract(
            source["payload"],
            operation_path + ".result_state.source",
        )
        if (
            candidate["type"] != "mixed_payment"
            or candidate["source_ids"] != [source["id"]]
            or candidate["confidence"] != expected_confidence
            or candidate["payload"] != expected_candidate_payload
            or [item["status"] for item in candidate["status_history"]] != ["pending_confirmation"]
        ):
            _fail(operation_path + ".result_state_id", "mixed payment candidate must exactly match its source-derived pending contract")
        expected_returned = [
            {"kind": "source", "id": source["id"]},
            {"kind": "evidence", "id": evidence["id"]},
            {"kind": "candidate", "id": candidate["id"]},
        ]
        if operation["returned_ids"] != expected_returned:
            _fail(operation_path + ".returned_ids", "mixed payment intake must return exactly its source, evidence, and candidate")
        return

    if action == "merge_mixed_payment_mirror_evidence":
        for collection_name in ("transactions", "transaction_versions", "posting_sets", "postings", "relations"):
            changes = expected_entities[collection_name]
            if changes["added_ids"] or changes["changed_ids"] or changes["removed_ids"]:
                _fail(operation_path + ".deltas.entity_changes", "mirror evidence cannot change formal ledger or relation effects")
        source = added_item("sources", {item["id"]: item for item in result["sources"]})
        evidence = added_item("evidence", {item["id"]: item for item in result["evidence"]})
        link = added_item("evidence_links", {item["id"]: item for item in result["evidence_links"]})
        input_value = operation["input"]
        candidate = next(item for item in result["candidates"] if item["id"] == input_value["candidate_id"])
        if candidate["type"] != "mixed_payment" or candidate["status_history"][-1]["status"] != "confirmed" or candidate["payload"].get("transaction_id") != input_value["transaction_id"]:
            _fail(operation_path + ".input.candidate_id", "must bind a confirmed mixed payment candidate to the input transaction")
        transaction = next(item for item in result["transactions"] if item["id"] == input_value["transaction_id"])
        if transaction["type"] != "expense":
            _fail(operation_path + ".input.transaction_id", "must reference an expense mixed payment transaction")
        if source["id"] != input_value["source_record_id"] or evidence["id"] != input_value["evidence_id"]:
            _fail(operation_path + ".result_state_id", "mirror identities must exactly match the action input")
        expected_source_payload = {
            "evidence_id": evidence["id"],
            "observed_at": input_value["observed_at"],
            "account_id": input_value["account_id"],
            "amount": input_value["amount"],
            "currency": input_value["currency"],
        }
        if source["type"] != "mixed_payment" or source["payload"] != expected_source_payload:
            _fail(operation_path + ".result_state_id", "mirror source must preserve the mixed payment evidence identity and observed_at")
        if evidence["type"] != "credit_liability_mirror" or evidence["source_ids"] != [source["id"]] or evidence["payload"] != {"observed_at": input_value["observed_at"]}:
            _fail(operation_path + ".result_state_id", "mirror evidence must bind the mixed source and observed_at")
        if link["evidence_id"] != evidence["id"] or link["target_kind"] != "posting" or link["role"] != "real_account_posting":
            _fail(operation_path + ".result_state_id", "mirror evidence link must use the RG04 real_account_posting role")
        versions = {item["id"]: item for item in result["transaction_versions"]}
        posting_sets = {item["id"]: item for item in result["posting_sets"]}
        postings = {item["id"]: item for item in result["postings"]}
        version = versions[transaction["current_version_id"]]
        posting_set = posting_sets[version["posting_set_id"]]
        current_postings = {posting_id: postings[posting_id] for posting_id in posting_set["posting_ids"]}
        liability_postings = [
            posting for posting in current_postings.values()
            if posting.get("role") == "mixed_expense_credit_funding"
            and posting["account_id"] == input_value["account_id"]
            and posting["currency"] == input_value["currency"]
            and -Decimal(posting["amount"]) == Decimal(input_value["amount"])
        ]
        if len(liability_postings) != 1 or link["target_id"] != liability_postings[0]["id"]:
            _fail(operation_path + ".result_state_id", "mirror evidence link must target the exact liability funding posting")
        reconciliation_changes = expected_entities["posting_reconciliations"]
        changed_reconciliation_ids = reconciliation_changes["changed_ids"]
        if (
            reconciliation_changes["added_ids"]
            or reconciliation_changes["removed_ids"]
            or len(changed_reconciliation_ids) != 1
        ):
            _fail(
                operation_path + ".deltas.entity_changes.posting_reconciliations",
                "mirror evidence must change exactly one existing reconciliation",
            )
        changed_reconciliation = result_reconciliations[changed_reconciliation_ids[0]]
        if (
            changed_reconciliation["posting_id"] != liability_postings[0]["id"]
            or changed_reconciliation["status"] != "matched"
        ):
            _fail(
                operation_path + ".result_state_id",
                "mirror reconciliation must target the liability funding posting and be matched",
            )
        return

    if action == "import_incomplete_source":
        input_value = operation["input"]
        source = next(item for item in result["sources"] if item["id"] in expected_entities["sources"]["added_ids"])
        candidate = next(item for item in result["candidates"] if item["id"] in expected_entities["candidates"]["added_ids"])
        if source["id"] != input_value["source_id"] or candidate["source_ids"] != [source["id"]] or "destination_account_id" in candidate["payload"]:
            _fail(operation_path + ".result_state_id", "incomplete intake must not guess a destination")
        expected_source_payload = {
            "source_account_id": input_value["source_account_id"], "debit_amount": input_value["debit_amount"],
            "currency": input_value["currency"], "completeness": "missing_destination",
            "observed_at": input_value["observed_at"], "evidence_id": input_value["evidence_id"],
        }
        if source["type"] != "account_transfer" or source["payload"] != expected_source_payload:
            _fail(operation_path + ".result_state_id", "incomplete intake source must exactly equal the action input and omit destination")
        evidence = next(item for item in result["evidence"] if item["id"] in expected_entities["evidence"]["added_ids"])
        if evidence["type"] != "transfer_record" or evidence["source_ids"] != [source["id"]] or evidence["payload"] != {"observed_at": input_value["observed_at"]}:
            _fail(operation_path + ".result_state_id", "incomplete intake evidence must exactly equal the source identity and observed_at")
        expected_candidate_payload = {
            "source_account_id": input_value["source_account_id"], "debit_amount": input_value["debit_amount"],
            "currency": input_value["currency"], "evidence_refs": [input_value["evidence_id"]],
            "provenance": {"rule": "complete_transfer_source", "rule_version": 1},
            "requires_confirmation": ["destination_account_id", "formal_transaction_creation"],
        }
        if candidate["type"] != "account_transfer" or candidate["confidence"] != "1.00" or candidate["payload"] != expected_candidate_payload:
            _fail(operation_path + ".result_state_id", "incomplete intake candidate must exactly equal the source-derived incomplete contract")
        expected_returned = [
            {"kind": "source", "id": source["id"]},
            {"kind": "evidence", "id": evidence["id"]},
            {"kind": "candidate", "id": candidate["id"]},
        ]
        if operation["returned_ids"] != expected_returned:
            _fail(operation_path + ".returned_ids", "incomplete intake must return exactly its source, evidence, and candidate")
        return

    if action in {
        "create_periodic_allocation",
        "recognize_periodic_allocation_installment",
        "revise_periodic_allocation",
        "correct_transaction_version",
        "manual_merged_payment",
        "ingest_merged_payment_facts",
        "confirm_merged_payment_candidate",
        "merge_item_receipt_evidence",
    }:
        return

    created_type_by_action = {
        "manual_expense": "expense",
        "manual_income": "income",
        "manual_account_transfer": "account_transfer",
        "confirm_account_transfer_candidate": "account_transfer",
        "confirm_balance_adjustment": "balance_adjustment",
        "confirm_real_transfer": "account_transfer",
        "confirm_explanation_allocation": "balance_adjustment_reversal",
        "manual_mixed_expense": "expense",
        "credit_principal_repayment": "credit_repayment",
        "confirm_mixed_payment_candidate": "expense",
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

    if action in {"manual_expense", "manual_income", "manual_account_transfer", "manual_mixed_expense", "credit_principal_repayment"}:
        confirmation = validate_confirmation(
            "explicit_manual_save", "operation", operation["id"]
        )
        if action == "manual_mixed_expense":
            relation = added_item(
                "relations", {item["id"]: item for item in result["relations"]}
            )
            expected_members = {
                ("transaction", transaction["id"]),
                *[
                    ("posting", posting["id"])
                    for posting in added_postings
                    if posting.get("role")
                    in {
                        "mixed_expense_asset_funding",
                        "mixed_expense_credit_funding",
                    }
                ],
            }
            actual_members = {
                (item["kind"], item["id"]) for item in relation["member_refs"]
            }
            if (
                relation["type"] != "mixed_payment"
                or len(relation["member_refs"]) != len(expected_members)
                or actual_members != expected_members
            ):
                _fail(
                    effect_path("relations"),
                    "manual mixed expense must add exactly one mixed_payment relation for its transaction and funding postings",
                )
    elif action == "confirm_mixed_payment_candidate":
        candidate_id = operation["input"]["candidate_id"]
        if expected_entities["candidates"]["changed_ids"] != [candidate_id]:
            _fail(
                effect_path("candidates"),
                "mixed payment confirmation may only append status to its input candidate",
            )
        candidate = result_candidates[candidate_id]
        if candidate["status_history"][-1]["status"] != "confirmed":
            _fail(
                effect_path("candidates"),
                "confirmed mixed payment candidate history must end in confirmed",
            )
        confirmation = validate_confirmation(
            "candidate_confirmation", "candidate", candidate_id
        )
        transaction_id = expected_entities["transactions"]["added_ids"]
        if len(transaction_id) != 1:
            _fail(
                effect_path("transactions"),
                "mixed payment confirmation must add exactly one transaction",
            )
        transaction_id = transaction_id[0]
        if candidate["payload"].get("transaction_id") != transaction_id:
            _fail(
                effect_path("candidates"),
                "confirmed mixed payment candidate must bind the created transaction",
            )
        version = result_versions[expected_entities["transaction_versions"]["added_ids"][0]]
        if (
            version["transaction_id"] != transaction_id
            or version.get("confirmation_id") != confirmation["id"]
        ):
            _fail(
                effect_path("transaction_versions"),
                "candidate confirmation must own the created transaction version",
            )
        relation = added_item("relations", {item["id"]: item for item in result["relations"]})
        posting_ids = expected_entities["postings"]["added_ids"]
        expected_members = {
            ("transaction", transaction_id),
            *[("posting", posting_id) for posting_id in posting_ids if result_postings[posting_id]["role"] != "expense"],
        }
        actual_members = {
            (item["kind"], item["id"]) for item in relation["member_refs"]
        }
        if (
            relation["type"] != "mixed_payment"
            or len(relation["member_refs"]) != len(expected_members)
            or actual_members != expected_members
        ):
            _fail(
                effect_path("relations"),
                "candidate confirmation must add a mixed_payment relation for the transaction funding postings",
            )
        source_ids = candidate["source_ids"]
        if len(source_ids) != 1:
            _fail(
                effect_path("candidates"),
                "mixed payment confirmation requires exactly one candidate source",
            )
        asset_evidence = [
            item
            for item in result["evidence"]
            if item["type"] == "asset_funding_debit"
            and item["source_ids"] == source_ids
        ]
        if len(asset_evidence) != 1:
            _fail(
                effect_path("evidence_links"),
                "mixed payment confirmation requires exactly one asset funding debit evidence for the candidate source",
            )
        asset_postings = [
            result_postings[posting_id]
            for posting_id in posting_ids
            if result_postings[posting_id].get("role")
            == "mixed_expense_asset_funding"
        ]
        if len(asset_postings) != 1:
            _fail(
                effect_path("postings"),
                "mixed payment confirmation requires exactly one asset funding posting",
            )
        evidence_link = added_item(
            "evidence_links",
            {item["id"]: item for item in result["evidence_links"]},
        )
        if evidence_link != {
            "id": evidence_link["id"],
            "evidence_id": asset_evidence[0]["id"],
            "target_kind": "posting",
            "target_id": asset_postings[0]["id"],
            "role": "real_account_posting",
        }:
            _fail(
                effect_path("evidence_links"),
                "mixed payment confirmation must link the source asset evidence to the exact asset funding posting",
            )
        reconciliations = [
            result_reconciliations[item_id]
            for item_id in expected_entities["posting_reconciliations"]["added_ids"]
        ]
        roles_by_posting = {
            item["id"]: item.get("role") for item in added_postings
        }
        expected_status_by_role = {
            "mixed_expense_asset_funding": "matched",
            "mixed_expense_credit_funding": "pending",
        }
        expected_reconciliation_postings = {
            posting_id
            for posting_id, role in roles_by_posting.items()
            if role in expected_status_by_role
        }
        if {
            item["posting_id"] for item in reconciliations
        } != expected_reconciliation_postings or any(
            item["status"]
            != expected_status_by_role[roles_by_posting[item["posting_id"]]]
            for item in reconciliations
        ):
            _fail(
                effect_path("posting_reconciliations"),
                "mixed payment confirmation must match the asset funding posting and leave the credit funding posting pending",
            )
        expected_returned = [
            {"kind": "confirmation", "id": confirmation["id"]},
            {"kind": "transaction", "id": transaction_id},
        ]
        if operation["returned_ids"] != expected_returned:
            _fail(
                operation_path + ".returned_ids",
                "candidate confirmation must return exactly its confirmation and transaction",
            )
    elif action == "confirm_account_transfer_candidate":
        candidate_id = operation["input"]["candidate_id"]
        if expected_entities["candidates"]["changed_ids"] != [candidate_id]:
            _fail(effect_path("candidates"), "candidate confirmation may only append status to its input candidate")
        candidate = result_candidates[candidate_id]
        if candidate["status_history"][-1]["status"] != "confirmed":
            _fail(effect_path("candidates"), "confirmed candidate history must end in confirmed")
        confirmation = validate_confirmation("candidate_confirmation", "candidate", candidate_id)
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

    if action in {"manual_account_transfer", "confirm_account_transfer_candidate", "manual_mixed_expense", "credit_principal_repayment", "confirm_mixed_payment_candidate"}:
        expected_returned = [
            {"kind": "confirmation", "id": confirmation["id"]},
            {"kind": "transaction", "id": transaction["id"]},
        ]
        if operation["returned_ids"] != expected_returned:
            _fail(operation_path + ".returned_ids", f"{action} must return exactly its confirmation and created transaction")

    if action == "manual_income" and not _contract_equivalent(
        {"returned_ids": operation["returned_ids"]},
        {"returned_ids": [{"kind": "transaction", "id": transaction["id"]}]},
    ):
        _fail(
            operation_path + ".returned_ids",
            "manual_income must return exactly its created transaction",
        )

    if action in {"manual_expense", "manual_income", "confirm_real_transfer", "manual_account_transfer", "confirm_account_transfer_candidate"}:
        reconciliations = [
            result_reconciliations[item_id]
            for item_id in expected_entities["posting_reconciliations"]["added_ids"]
        ]
        eligible_posting_ids = {
            item["id"] for item in added_postings if item["reconciliation_eligible"]
        }
        statuses_ok = all(item["status"] == "pending" for item in reconciliations)
        if action == "confirm_account_transfer_candidate":
            roles_by_posting = {item["id"]: item.get("role") for item in added_postings}
            statuses_ok = all(
                item["status"] == ("matched" if roles_by_posting.get(item["posting_id"]) == "transfer_principal_out" else "pending")
                for item in reconciliations
            )
        elif action in {"manual_mixed_expense", "credit_principal_repayment", "confirm_mixed_payment_candidate"}:
            statuses_ok = all(item["status"] == "pending" for item in reconciliations)
        if {item["posting_id"] for item in reconciliations} != eligible_posting_ids or not statuses_ok:
            _fail(
                effect_path("posting_reconciliations"),
                f"{action} reconciliations must cover exactly its eligible postings with the registered statuses",
            )


def _validate_mixed_expense_postings(
    postings: list[dict[str, Any]],
    accounts: dict[str, dict[str, Any]],
    categories: dict[str, dict[str, Any]],
    path: str,
) -> None:
    expected_roles = {"expense", "mixed_expense_asset_funding", "mixed_expense_credit_funding"}
    if len(postings) != 3 or {posting.get("role") for posting in postings} != expected_roles:
        _fail(path, "correction must preserve an expense with exactly two mixed-payment funding roles")
    by_role = {posting["role"]: posting for posting in postings}
    expense = by_role["expense"]
    expense_account = accounts.get(expense["account_id"])
    category = categories.get(expense.get("category_id"))
    if not (
        expense_account is not None
        and expense_account["kind"] == "expense"
        and expense_account["owned_by_user"] is False
        and expense_account["real_account"] is False
        and expense["reconciliation_eligible"] is False
        and category is not None
        and category["active"] is True
        and category["parent_id"] is not None
        and category["posting_account_id"] == expense["account_id"]
    ):
        _fail(path + ".expense", "must be a valid non-owned non-real expense category posting")
    if _decimal(expense["amount"], path) <= 0:
        _fail(path + ".expense.amount", "must be positive")
    expected_kinds = {
        "mixed_expense_asset_funding": "asset",
        "mixed_expense_credit_funding": "liability",
    }
    currencies = {posting["currency"] for posting in postings}
    if len(currencies) != 1:
        _fail(path, "mixed expense postings must use one currency")
    currency = next(iter(currencies))
    total = Decimal(0)
    for role, kind in expected_kinds.items():
        posting = by_role[role]
        account = accounts.get(posting["account_id"])
        if posting.get("category_id") is not None:
            _fail(path + f".{role}.category_id", "funding postings must not carry category_id")
        if not (
            account is not None
            and account["kind"] == kind
            and account["owned_by_user"] is True
            and account["real_account"] is True
            and account["currency"] == currency
            and posting["currency"] == currency
            and posting["reconciliation_eligible"] is True
            and _decimal(posting["amount"], path) < 0
        ):
            _fail(path + f".{role}", "must be an eligible owned real funding posting with the correct kind and sign")
        total += _decimal(posting["amount"], path)
    total += _decimal(expense["amount"], path)
    if total != 0:
        _fail(path, "mixed expense postings must balance exactly per currency")


def _validate_rg06_action_effects(
    operation: dict[str, Any],
    operation_path: str,
    baseline: dict[str, Any],
    result: dict[str, Any],
    expected_entities: dict[str, dict[str, list[str]]],
) -> None:
    action = operation["action_type"]
    input_value = operation["input"]
    baseline_entities = {item["id"]: item for item in baseline["domain_entities"]}
    result_entities = {item["id"]: item for item in result["domain_entities"]}
    baseline_relations = {item["id"]: item for item in baseline["relations"]}
    result_relations = {item["id"]: item for item in result["relations"]}
    result_transactions = {item["id"]: item for item in result["transactions"]}
    result_versions = {item["id"]: item for item in result["transaction_versions"]}
    result_sets = {item["id"]: item for item in result["posting_sets"]}
    result_postings = {item["id"]: item for item in result["postings"]}
    result_sources = {item["id"]: item for item in result["sources"]}
    result_evidence = {item["id"]: item for item in result["evidence"]}
    result_links = {item["id"]: item for item in result["evidence_links"]}
    result_candidates = {item["id"]: item for item in result["candidates"]}
    result_confirmations = {item["id"]: item for item in result["confirmations"]}
    result_reconciliations = {
        item["id"]: item for item in result["posting_reconciliations"]
    }

    def added(collection: str, index: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
        return [index[item_id] for item_id in expected_entities[collection]["added_ids"]]

    def changed(collection: str) -> list[str]:
        return expected_entities[collection]["changed_ids"]

    def relation_lifecycle(
        relation_id: str, relations: dict[str, dict[str, Any]], entities: dict[str, dict[str, Any]]
    ) -> tuple[dict[str, Any], dict[str, Any]]:
        relation = relations.get(relation_id)
        if relation is None or relation.get("type") != "staged_payment":
            _fail(operation_path + ".input.relation_id", "must bind one staged_payment relation")
        lifecycles = [
            entities.get(ref["id"])
            for ref in relation["member_refs"]
            if ref["kind"] == "domain_entity"
            and entities.get(ref["id"], {}).get("type") == "staged_payment_lifecycle"
        ]
        if len(lifecycles) != 1:
            _fail(operation_path + ".input.relation_id", "must bind exactly one lifecycle")
        return relation, lifecycles[0]

    def exact_history_append(
        before_lifecycle: dict[str, Any],
        after_lifecycle: dict[str, Any],
        event: str,
        occurred_at: str,
        payment_id: str | None,
    ) -> dict[str, Any]:
        before_history = before_lifecycle["payload"]["state_history"]
        after_history = after_lifecycle["payload"]["state_history"]
        if len(after_history) != len(before_history) + 1 or after_history[:-1] != before_history:
            _fail(operation_path + ".result_state_id", "lifecycle history must append exactly one event")
        appended = after_history[-1]
        latest = before_history[-1]
        if (
            appended["sequence"] != latest["sequence"] + 1
            or appended["event"] != event
            or appended["occurred_at"] != occurred_at
            or appended["payment_id"] != payment_id
            or appended["state_transition_effect_count"] != 0
        ):
            _fail(operation_path + ".result_state_id", "appended lifecycle event must exactly bind the action")
        return appended

    def validate_installment(
        relation_id: str,
        role: str,
        amount_text: str,
        currency: str,
        funding_account_id: str,
        occurred_at: str,
        *,
        candidate_id: str | None = None,
    ) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
        before_relation, before_lifecycle = relation_lifecycle(
            relation_id, baseline_relations, baseline_entities
        )
        after_relation, after_lifecycle = relation_lifecycle(
            relation_id, result_relations, result_entities
        )
        if changed("relations") != [relation_id] or changed("domain_entities") != [before_lifecycle["id"]]:
            _fail(operation_path + ".result_state_id", "installment may change only its relation and lifecycle")
        payments = added("domain_entities", result_entities)
        if len(payments) != 1 or payments[0].get("type") != "installment_payment":
            _fail(operation_path + ".result_state_id", "installment action must add one payment")
        payment = payments[0]
        payload = payment["payload"]
        expected_payment = {
            "role": role,
            "amount": amount_text,
            "currency": currency,
            "funding_account_id": funding_account_id,
            "actual_payment_at": occurred_at,
            "statistics_at": occurred_at,
        }
        if any(payload.get(key) != value for key, value in expected_payment.items()):
            _fail(operation_path + ".result_state_id", "installment payload must exactly bind role, amount, account, currency, and time")
        old_refs = before_relation["member_refs"]
        new_ref = {"kind": "domain_entity", "id": payment["id"]}
        if (
            after_relation.get("payload") != {}
            or len(after_relation["member_refs"]) != len(old_refs) + 1
            or after_relation["member_refs"].count(new_ref) != 1
            or any(ref not in after_relation["member_refs"] for ref in old_refs)
            or len({(ref["kind"], ref["id"]) for ref in after_relation["member_refs"]})
            != len(after_relation["member_refs"])
        ):
            _fail(operation_path + ".result_state_id", "relation must append the installment exactly once")
        old_payments = [
            baseline_entities[ref["id"]]
            for ref in old_refs
            if ref["kind"] == "domain_entity"
            and baseline_entities.get(ref["id"], {}).get("type") == "installment_payment"
        ]
        if any(item["payload"]["role"] == role for item in old_payments):
            _fail(operation_path + ".input.payment_role", "relation already contains this installment role")
        if role == "final":
            deposits = [item for item in old_payments if item["payload"]["role"] == "deposit"]
            if (
                len(deposits) != 1
                or _timestamp_instant(occurred_at)
                <= _timestamp_instant(deposits[0]["payload"]["actual_payment_at"])
            ):
                _fail(operation_path + ".input.actual_payment_at", "final must be strictly later than the unique deposit")
            if (
                "source_payment_at" in payload
                and "source_payment_at" in deposits[0]["payload"]
                and _timestamp_instant(payload["source_payment_at"])
                <= _timestamp_instant(deposits[0]["payload"]["source_payment_at"])
            ):
                _fail(operation_path + ".result_state_id", "final source time must be strictly later than deposit source time")
        elif old_payments:
            _fail(operation_path + ".input.payment_role", "deposit must be the first installment")
        before_paid = Decimal(before_lifecycle["payload"]["paid_amount"])
        total = Decimal(before_lifecycle["payload"]["total_amount"])
        amount = Decimal(amount_text)
        expected_paid = before_paid + amount
        expected_due = total - expected_paid
        after_payload = after_lifecycle["payload"]
        if (
            after_payload["total_amount"] != before_lifecycle["payload"]["total_amount"]
            or Decimal(after_payload["paid_amount"]) != expected_paid
            or Decimal(after_payload["due_amount"]) != expected_due
            or after_payload["currency"] != currency
        ):
            _fail(operation_path + ".result_state_id", "lifecycle totals must exactly include the installment")
        appended = exact_history_append(
            before_lifecycle, after_lifecycle, "payment_confirmed", occurred_at, payment["id"]
        )
        expected_progress = "paid_in_full" if expected_due == 0 else "partially_paid"
        if (
            Decimal(appended["total_amount"]) != total
            or Decimal(appended["paid_amount"]) != expected_paid
            or Decimal(appended["due_amount"]) != expected_due
            or appended["payment_progress"] != expected_progress
            or appended["fulfillment_status"]
            != before_lifecycle["payload"]["state_history"][-1]["fulfillment_status"]
        ):
            _fail(operation_path + ".result_state_id", "payment history must exactly project installment arithmetic")
        transactions = added("transactions", result_transactions)
        versions = added("transaction_versions", result_versions)
        posting_sets = added("posting_sets", result_sets)
        postings = added("postings", result_postings)
        if len(transactions) != 1 or len(versions) != 1 or len(posting_sets) != 1 or len(postings) != 2:
            _fail(operation_path + ".result_state_id", "installment must add one balanced expense transaction")
        transaction, version, posting_set = transactions[0], versions[0], posting_sets[0]
        if (
            transaction.get("type") != "expense"
            or payload.get("transaction_id") != transaction["id"]
            or transaction["current_version_id"] != version["id"]
            or version["transaction_id"] != transaction["id"]
            or version["posting_set_id"] != posting_set["id"]
            or set(posting_set["posting_ids"]) != {item["id"] for item in postings}
            or version["occurred_at"] != occurred_at
            or version["statistics_at"] != payload["statistics_at"]
        ):
            _fail(operation_path + ".result_state_id", "formal transaction identities and times must bind the installment")
        by_role = {item.get("role"): item for item in postings}
        category_id = input_value.get("category_id", before_lifecycle["payload"]["category_id"])
        if set(by_role) != {"expense", "payment_asset"}:
            _fail(operation_path + ".result_state_id", "installment requires expense and payment_asset postings")
        if (
            by_role["payment_asset"]["id"] != payload.get("asset_posting_id")
            or by_role["payment_asset"]["account_id"] != funding_account_id
            or by_role["payment_asset"]["currency"] != currency
            or Decimal(by_role["payment_asset"]["amount"]) != -amount
            or by_role["expense"]["id"] != payload.get("expense_posting_id")
            or by_role["expense"].get("category_id") != category_id
            or by_role["expense"]["currency"] != currency
            or Decimal(by_role["expense"]["amount"]) != amount
        ):
            _fail(operation_path + ".result_state_id", "postings must exactly bind installment amount, account, category, and currency")
        confirmations = added("confirmations", result_confirmations)
        expected_confirmation_type = "candidate_confirmation" if candidate_id else "explicit_manual_save"
        expected_subject = (
            {"kind": "candidate", "id": candidate_id}
            if candidate_id
            else {"kind": "operation", "id": operation["id"]}
        )
        if (
            len(confirmations) != 1
            or confirmations[0]["type"] != expected_confirmation_type
            or confirmations[0]["operation_id"] != operation["id"]
            or confirmations[0]["subject"] != expected_subject
            or version.get("confirmation_id") != confirmations[0]["id"]
        ):
            _fail(operation_path + ".result_state_id", "confirmation must own the formal installment version")
        reconciliations = added("posting_reconciliations", result_reconciliations)
        expected_reconciliation_status = "matched" if candidate_id else "pending"
        if len(reconciliations) != 1 or reconciliations[0] != {
            "id": reconciliations[0]["id"],
            "posting_id": by_role["payment_asset"]["id"],
            "status": expected_reconciliation_status,
        }:
            _fail(
                operation_path + ".result_state_id",
                "new payment_asset reconciliation must be matched for exact imported confirmation and pending for manual creation",
            )
        return payment, transaction, confirmations[0]

    if action == "create_staged_payment":
        relations = added("relations", result_relations)
        lifecycles = added("domain_entities", result_entities)
        if len(relations) != 1 or len(lifecycles) != 1 or lifecycles[0].get("type") != "staged_payment_lifecycle":
            _fail(operation_path + ".result_state_id", "creation must add one relation and one lifecycle")
        relation, lifecycle = relations[0], lifecycles[0]
        payload = lifecycle["payload"]
        history = payload["state_history"]
        if (
            relation != {"id": relation["id"], "type": "staged_payment", "member_refs": [{"kind": "domain_entity", "id": lifecycle["id"]}], "payload": {}}
            or payload["total_amount"] != input_value["total_amount"]
            or payload["paid_amount"] != "0.00"
            or payload["due_amount"] != input_value["total_amount"]
            or payload["currency"] != input_value["currency"]
            or payload["category_id"] != input_value["category_id"]
            or len(history) != 1
        ):
            _fail(operation_path + ".result_state_id", "creation result must exactly bind relation and lifecycle input")
        first = history[0]
        if (
            first["sequence"] != 1
            or first["event"] != "group_created"
            or first["occurred_at"] != input_value["created_at"]
            or first["total_amount"] != input_value["total_amount"]
            or first["paid_amount"] != "0.00"
            or first["due_amount"] != input_value["total_amount"]
            or first["payment_id"] is not None
            or first["payment_progress"] != "unpaid"
            or first["fulfillment_status"] != "in_progress"
            or first["state_transition_effect_count"] != 0
        ):
            _fail(operation_path + ".result_state_id", "group_created history must exactly bind creation input")
        expected_returned = [
            {"kind": "relation", "id": relation["id"]},
            {"kind": "domain_entity", "id": lifecycle["id"]},
        ]
    elif action == "record_staged_payment_installment":
        payment, transaction, confirmation = validate_installment(
            input_value["relation_id"], input_value["payment_role"], input_value["payment_amount"],
            input_value["currency"], input_value["funding_account_id"], input_value["actual_payment_at"]
        )
        expected_returned = [
            {"kind": "confirmation", "id": confirmation["id"]},
            {"kind": "transaction", "id": transaction["id"]},
            {"kind": "domain_entity", "id": payment["id"]},
        ]
    elif action in {"change_staged_payment_fulfillment", "confirm_staged_payment_completion"}:
        relation_id = input_value["relation_id"]
        _, before_lifecycle = relation_lifecycle(relation_id, baseline_relations, baseline_entities)
        _, after_lifecycle = relation_lifecycle(relation_id, result_relations, result_entities)
        if changed("domain_entities") != [before_lifecycle["id"]]:
            _fail(operation_path + ".result_state_id", "status action may change only its lifecycle")
        event = "fulfillment_changed" if action == "change_staged_payment_fulfillment" else "completion_confirmed"
        appended = exact_history_append(before_lifecycle, after_lifecycle, event, input_value["occurred_at"], None)
        before_latest = before_lifecycle["payload"]["state_history"][-1]
        if any(appended[key] != before_latest[key] for key in ("total_amount", "paid_amount", "due_amount", "payment_progress")):
            _fail(operation_path + ".result_state_id", "status history cannot change payment arithmetic")
        expected_fulfillment = input_value.get("fulfillment_status", before_latest["fulfillment_status"])
        if appended["fulfillment_status"] != expected_fulfillment:
            _fail(operation_path + ".result_state_id", "status history must bind fulfillment input")
        if action == "confirm_staged_payment_completion" and Decimal(appended["due_amount"]) != 0:
            _fail(operation_path + ".input.confirmed", "completion requires zero due")
        expected_returned = [{"kind": "domain_entity", "id": after_lifecycle["id"]}]
    elif action == "ingest_staged_payment_bank_fact":
        sources = added("sources", result_sources)
        evidence_items = added("evidence", result_evidence)
        candidates = added("candidates", result_candidates)
        if len(sources) != 1 or len(evidence_items) != 1 or len(candidates) != 1:
            _fail(operation_path + ".result_state_id", "ingest must add one source, evidence, and candidate")
        source, evidence, candidate = sources[0], evidence_items[0], candidates[0]
        expected_source_payload = {"amount": input_value["amount"], "currency": input_value["currency"], "source_payment_at": input_value["source_payment_at"]}
        ambiguous = "suggested_payment_role" not in input_value
        payload = candidate["payload"]
        if (
            source["id"] != input_value["source_id"]
            or source["type"] != "staged_payment_bank_fact"
            or source["payload"] != expected_source_payload
            or evidence["id"] != input_value["evidence_id"]
            or evidence["type"] != "staged_payment_bank_payment"
            or evidence["source_ids"] != [source["id"]]
            or evidence["payload"] != {"source_payment_at": input_value["source_payment_at"]}
            or candidate["type"] != "staged_payment"
            or candidate["source_ids"] != [source["id"]]
            or payload.get("payment_role") != input_value.get("suggested_payment_role")
            or payload.get("amount") != str(abs(Decimal(input_value["amount"])))
            or payload.get("currency") != input_value["currency"]
            or payload.get("source_payment_at") != input_value["source_payment_at"]
            or payload.get("evidence_ref") != evidence["id"]
            or payload.get("provenance") != {"rule": "staged_payment_bank_fact", "rule_version": 1}
            or payload.get("requires_confirmation") != ["relation_id", "payment_role", "category_id", "funding_account_id"]
            or candidate["status_history"] != [candidate["status_history"][0]]
            or candidate["status_history"][0].get("sequence") != 1
            or candidate["status_history"][0].get("status") != "pending_confirmation"
            or candidate["confidence"] != ("0.50" if ambiguous else "1.00")
            or (ambiguous and payload.get("guessed_payment_role", object()) is not None)
            or (not ambiguous and "guessed_payment_role" in payload)
        ):
            _fail(operation_path + ".result_state_id", "ingest source, evidence, and candidate must exactly bind input")
        expected_returned = [
            {"kind": "source", "id": source["id"]},
            {"kind": "evidence", "id": evidence["id"]},
            {"kind": "candidate", "id": candidate["id"]},
        ]
    elif action == "link_staged_payment_evidence":
        sources = added("sources", result_sources)
        evidence_items = added("evidence", result_evidence)
        links = added("evidence_links", result_links)
        payment = result_entities.get(input_value["payment_id"])
        if len(sources) != 1 or len(evidence_items) != 1 or len(links) != 1 or payment is None:
            _fail(operation_path + ".result_state_id", "evidence link must add one source, evidence, and link")
        source, evidence, link = sources[0], evidence_items[0], links[0]
        payment_payload = payment["payload"]
        if (
            source["id"] != input_value["source_id"]
            or source["type"] != "staged_payment_bank_fact"
            or evidence["id"] != input_value["evidence_id"]
            or evidence["type"] != "staged_payment_bank_payment"
            or evidence["source_ids"] != [source["id"]]
            or evidence["payload"].get("payment_id") != payment["id"]
            or link != {"id": link["id"], "evidence_id": evidence["id"], "target_kind": "posting", "target_id": input_value["posting_id"], "role": "payment_asset_posting"}
            or payment_payload.get("asset_posting_id") != input_value["posting_id"]
            or source["payload"].get("currency") != payment_payload["currency"]
            or abs(Decimal(source["payload"].get("amount"))) != Decimal(payment_payload["amount"])
        ):
            _fail(operation_path + ".result_state_id", "manual evidence must exactly bind payment and payment_asset posting")
        reconciliations = [result_reconciliations[item_id] for item_id in changed("posting_reconciliations")]
        baseline_reconciliations = {item["id"]: item for item in baseline["posting_reconciliations"]}
        if len(reconciliations) != 1 or baseline_reconciliations[reconciliations[0]["id"]]["status"] != "pending" or reconciliations[0]["status"] != "matched" or reconciliations[0]["posting_id"] != input_value["posting_id"]:
            _fail(operation_path + ".result_state_id", "evidence link must match only the target posting reconciliation")
        expected_returned = [
            {"kind": "source", "id": source["id"]},
            {"kind": "evidence", "id": evidence["id"]},
            {"kind": "evidence_link", "id": link["id"]},
        ]
    elif action == "confirm_staged_payment_candidate":
        candidate_id = input_value["candidate_id"]
        before_candidate = next((item for item in baseline["candidates"] if item["id"] == candidate_id), None)
        after_candidate = result_candidates.get(candidate_id)
        if before_candidate is None or after_candidate is None or changed("candidates") != [candidate_id]:
            _fail(operation_path + ".input.candidate_id", "must confirm exactly the input candidate")
        _validate_candidate_confirmation_transition(
            before_candidate,
            after_candidate,
            operation_path + ".result_state_id",
            immutable_payload=True,
        )
        candidate_payload = before_candidate["payload"]
        if (
            input_value["exact_binding_confirmed"] is not True
            or candidate_payload.get("payment_role") not in {None, input_value["payment_role"]}
        ):
            _fail(operation_path + ".input.payment_role", "must explicitly bind the pending candidate role")
        payment, transaction, confirmation = validate_installment(
            input_value["relation_id"], input_value["payment_role"], candidate_payload["amount"],
            candidate_payload["currency"], input_value["funding_account_id"], candidate_payload["source_payment_at"],
            candidate_id=candidate_id,
        )
        evidence_id = candidate_payload["evidence_ref"]
        before_evidence = next(
            (item for item in baseline["evidence"] if item["id"] == evidence_id), None
        )
        after_evidence = result_evidence.get(evidence_id)
        if (
            before_evidence is None
            or after_evidence is None
            or changed("evidence") != [evidence_id]
            or "payment_id" in before_evidence["payload"]
            or after_evidence
            != {
                **before_evidence,
                "payload": {
                    **before_evidence["payload"],
                    "payment_id": payment["id"],
                },
            }
        ):
            _fail(
                operation_path + ".result_state_id",
                "candidate confirmation must add the evidence's exact payment binding",
            )
        links = added("evidence_links", result_links)
        if len(links) != 1 or links[0] != {
            "id": links[0]["id"], "evidence_id": candidate_payload["evidence_ref"],
            "target_kind": "posting", "target_id": payment["payload"]["asset_posting_id"],
            "role": "payment_asset_posting",
        }:
            _fail(operation_path + ".result_state_id", "candidate confirmation must link its evidence to payment_asset")
        expected_returned = [
            {"kind": "confirmation", "id": confirmation["id"]},
            {"kind": "transaction", "id": transaction["id"]},
            {"kind": "domain_entity", "id": payment["id"]},
            {"kind": "evidence_link", "id": links[0]["id"]},
        ]
    else:
        sources = added("sources", result_sources)
        evidence_items = added("evidence", result_evidence)
        payment = result_entities.get(input_value["payment_id"])
        original_links = [
            item for item in baseline["evidence_links"]
            if item["target_kind"] == "posting" and item["target_id"] == input_value["posting_id"]
        ]
        if len(sources) != 1 or len(evidence_items) != 1 or payment is None or len(original_links) != 1:
            _fail(operation_path + ".result_state_id", "mirror must bind one existing payment evidence link")
        source, evidence, original_link = sources[0], evidence_items[0], original_links[0]
        original_evidence = next(item for item in baseline["evidence"] if item["id"] == original_link["evidence_id"])
        original_source_id = original_evidence["source_ids"][0]
        if (
            payment["payload"].get("asset_posting_id") != input_value["posting_id"]
            or source["id"] != input_value["source_id"]
            or source["type"] != "staged_payment_bank_fact"
            or source["payload"] != {"mirror_of_source_id": original_source_id, "amount": input_value["amount"], "currency": input_value["currency"], "source_payment_at": input_value["source_payment_at"]}
            or evidence["id"] != input_value["evidence_id"]
            or evidence["type"] != "staged_payment_bank_payment"
            or evidence["source_ids"] != [source["id"]]
            or evidence["payload"] != {"payment_id": payment["id"], "source_payment_at": input_value["source_payment_at"], "mirror_of_evidence_id": original_evidence["id"], "merged_into_evidence_link_id": original_link["id"]}
            or input_value["currency"] != payment["payload"]["currency"]
            or abs(Decimal(input_value["amount"])) != Decimal(payment["payload"]["amount"])
        ):
            _fail(operation_path + ".result_state_id", "mirror source and evidence must exactly bind input and original lineage")
        expected_returned = [
            {"kind": "source", "id": source["id"]},
            {"kind": "evidence", "id": evidence["id"]},
        ]

    if operation["returned_ids"] != expected_returned:
        _fail(operation_path + ".returned_ids", "must exactly return the RG-06 action result identities")


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
    if action in _RG08_ACTIONS:
        _validate_rg08_action_effects(
            operation, operation_path, baseline, result, expected_entities
        )
        return
    if action in _RG06_ACTIONS:
        _validate_rg06_action_effects(
            operation, operation_path, baseline, result, expected_entities
        )
        return
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
    result_candidates = {item["id"]: item for item in result["candidates"]}
    result_confirmations = {item["id"]: item for item in result["confirmations"]}
    result_entities = {item["id"]: item for item in result["domain_entities"]}
    result_audit_links = {item["id"]: item for item in result["audit_links"]}
    baseline_reconciliations = {item["id"]: item for item in baseline["posting_reconciliations"]}
    result_reconciliations = {item["id"]: item for item in result["posting_reconciliations"]}
    baseline_reconciliations_by_posting = {item["posting_id"]: item for item in baseline["posting_reconciliations"]}
    result_reconciliations_by_posting = {item["posting_id"]: item for item in result["posting_reconciliations"]}

    def transaction_parts(transaction_id: str) -> tuple[dict[str, Any], list[dict[str, Any]]]:
        transaction = result_transactions[transaction_id]
        version = result_versions[transaction["current_version_id"]]
        postings = [
            result_postings[posting_id]
            for posting_id in result_sets[version["posting_set_id"]]["posting_ids"]
        ]
        return version, postings

    if action in {
        "manual_merged_payment",
        "ingest_merged_payment_facts",
        "confirm_merged_payment_candidate",
        "merge_item_receipt_evidence",
    }:
        added = expected_entities

        def added_items(collection: str, index: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
            return [index[item_id] for item_id in added[collection]["added_ids"]]

        if action == "ingest_merged_payment_facts":
            bank = input_value["bank_fact"]
            items = input_value["item_facts"]
            sources = {item["id"]: item for item in result["sources"]}
            evidence = {item["id"]: item for item in result["evidence"]}
            added_sources = added_items("sources", sources)
            if [item["id"] for item in added_sources] != [bank["source_id"], *[item["source_id"] for item in items]]:
                _fail(operation_path + ".result_state_id", "ingest must add sources in input order")
            expected_source_payloads = [
                {key: value for key, value in bank.items() if key != "source_id"} | {"completeness": "complete"},
                *[
                    {key: value for key, value in item.items() if key != "source_id"}
                    | {"completeness": "complete" if item["evidence_kind"] == "item_receipt" else "summary_only"}
                    for item in items
                ],
            ]
            for source, expected_payload in zip(added_sources, expected_source_payloads, strict=True):
                if source["payload"] != expected_payload:
                    _fail(operation_path + ".result_state_id", "ingest source facts must exactly equal the input")
            added_evidence = added_items("evidence", evidence)
            for source, evidence_item in zip(added_sources, added_evidence, strict=True):
                expected_type = "bank_payment" if source["type"] == "merged_payment_bank_fact" else source["payload"]["evidence_kind"]
                if evidence_item != {
                    "id": evidence_item["id"], "type": expected_type,
                    "source_ids": [source["id"]],
                    "payload": {"observed_at": source["payload"]["observed_at"]},
                }:
                    _fail(operation_path + ".result_state_id", "ingest evidence must bind one exact source and observed time")
            candidate = added_items("candidates", result_candidates)
            if len(candidate) != 1:
                _fail(operation_path + ".result_state_id", "ingest must add one candidate")
            candidate = candidate[0]
            expected_payload = {
                "payment_total": str(-Decimal(bank["amount"])), "currency": bank["currency"],
                "bank_source_id": bank["source_id"],
                "item_source_ids": [item["source_id"] for item in items],
                "item_proposals": [
                    {
                        "item_id": item["item_id"], "amount": item["amount"], "currency": item["currency"],
                        "suggested_category_id": item["suggested_category_id"],
                        "source_id": item["source_id"], "evidence_id": item["evidence_id"],
                    }
                    for item in items
                ],
                "evidence_refs": [bank["evidence_id"], *[item["evidence_id"] for item in items]],
                "provenance": {"rule": "merged_payment_facts", "rule_version": 1},
                "requires_confirmation": [
                    "funding_account_id", "secondary_categories", "allocation_closure",
                    "formal_transaction_creation",
                ],
            }
            if candidate["type"] != "merged_payment" or candidate["source_ids"] != [bank["source_id"], *[item["source_id"] for item in items]] or candidate["confidence"] != "1.00" or candidate["payload"] != expected_payload or [event["status"] for event in candidate["status_history"]] != ["pending_confirmation"]:
                _fail(operation_path + ".result_state_id", "ingest candidate must exactly preserve source-owned facts and remain pending")
            if operation["returned_ids"] != [
                *[{"kind": "source", "id": item["id"]} for item in added_sources],
                *[{"kind": "evidence", "id": item["id"]} for item in added_evidence],
                {"kind": "candidate", "id": candidate["id"]},
            ]:
                _fail(operation_path + ".returned_ids", "ingest returned IDs must exactly cover sources, evidence, and candidate")
            return

        if action == "merge_item_receipt_evidence":
            source = added_items("sources", {item["id"]: item for item in result["sources"]})[0]
            evidence = added_items("evidence", {item["id"]: item for item in result["evidence"]})[0]
            link = added_items("evidence_links", {item["id"]: item for item in result["evidence_links"]})[0]
            allocation = baseline_entities[input_value["item_allocation_id"]]
            expected_source = {
                "item_id": allocation["payload"]["source_item_id"], "evidence_id": input_value["evidence_id"],
                "evidence_kind": "item_receipt", "observed_at": input_value["observed_at"],
                "details": input_value["details"], "amount": input_value["amount"],
                "currency": input_value["currency"], "suggested_category_id": allocation["payload"]["category_id"],
                "completeness": "complete",
            }
            if source["id"] != input_value["source_id"] or source["type"] != "merged_payment_item_fact" or source["payload"] != expected_source:
                _fail(operation_path + ".result_state_id", "receipt source must exactly preserve its input and allocation binding")
            if evidence["id"] != input_value["evidence_id"] or evidence["type"] != "item_receipt" or evidence["source_ids"] != [source["id"]] or evidence["payload"] != {"observed_at": input_value["observed_at"]}:
                _fail(operation_path + ".result_state_id", "receipt evidence must bind exactly to its source")
            if link["evidence_id"] != evidence["id"] or link["target_kind"] != "domain_entity" or link["target_id"] != input_value["item_allocation_id"] or link["role"] != "item_allocation_fact":
                _fail(operation_path + ".result_state_id", "receipt link must target exactly one item allocation")
            if operation["returned_ids"] != [
                {"kind": "source", "id": source["id"]},
                {"kind": "evidence", "id": evidence["id"]},
                {"kind": "evidence_link", "id": link["id"]},
            ]:
                _fail(operation_path + ".returned_ids", "receipt must return exactly source, evidence, and link")
            return

        # Both formal paths share the same one-payment ownership contract.
        added_transaction_ids = added["transactions"]["added_ids"]
        transaction_id = added_transaction_ids[0]
        version, postings = transaction_parts(transaction_id)
        if result_transactions[transaction_id]["type"] != "expense":
            _fail(operation_path + ".result_state_id", "RG-05 formal result must be an expense transaction")
        expected_time = input_value["payment_at"]
        if action == "confirm_merged_payment_candidate":
            expected_time = input_value["payment_at"]
        if set(version) != {"id", "transaction_id", "version_number", "posting_set_id", "occurred_at", "statistics_at", "effective_at", "confirmation_id"} or any(version[field] != expected_time for field in ("occurred_at", "effective_at")):
            _fail(operation_path + ".result_state_id", "RG-05 payment version owns only payment economic times")
        if action == "manual_merged_payment":
            if version["statistics_at"] != input_value["payment_at"]:
                _fail(operation_path + ".result_state_id", "manual payment statistics time must equal payment time")
            item_specs = input_value["items"]
            source_bindings = {item["item_id"]: None for item in item_specs}
            expected_currency = input_value["currency"]
            expected_total = Decimal(input_value["total_amount"])
        else:
            candidate_before = baseline_candidates[input_value["candidate_id"]]
            if candidate_before["type"] != "merged_payment" or [event["status"] for event in candidate_before["status_history"]] != ["pending_confirmation"]:
                _fail(operation_path + ".input.candidate_id", "must confirm the baseline pending merged-payment candidate")
            proposals = {item["item_id"]: item for item in candidate_before["payload"]["item_proposals"]}
            item_specs = [
                {
                    "item_id": item["item_id"], "amount": item["allocation_amount"], "currency": item["currency"],
                    "category_id": item["category_id"],
                    "details": next(source["payload"]["details"] for source in baseline["sources"] if source["id"] == proposals[item["item_id"]]["source_id"]),
                    "source_observed_at": next(source["payload"]["observed_at"] for source in baseline["sources"] if source["id"] == proposals[item["item_id"]]["source_id"]),
                }
                for item in input_value["items"]
            ]
            source_bindings = {item["item_id"]: proposals[item["item_id"]] for item in item_specs}
            expected_currency = candidate_before["payload"]["currency"]
            expected_total = Decimal(candidate_before["payload"]["payment_total"])
        payment_postings = [item for item in postings if item.get("role") == "payment_asset"]
        expense_postings = [item for item in postings if item.get("role") == "expense"]
        if len(payment_postings) != 1 or len(expense_postings) != 2:
            _fail(operation_path + ".result_state_id", "RG-05 must contain exactly two expense postings and one payment asset posting")
        payment = payment_postings[0]
        if payment["account_id"] != input_value["funding_account_id"] or payment["currency"] != expected_currency or Decimal(payment["amount"]) != -expected_total or payment.get("category_id") is not None or payment["reconciliation_eligible"] is not True:
            _fail(operation_path + ".result_state_id", "payment_asset posting must be the unique owned funding leg")
        ordered_consumptions: list[dict[str, Any]] = []
        ordered_allocations: list[dict[str, Any]] = []
        for spec in item_specs:
            expected_statistics_at = input_value["common_statistics_at"] if action == "confirm_merged_payment_candidate" else input_value["payment_at"]
            matches = [
                entity for entity in result_entities.values()
                if entity["type"] == "consumption_record"
                and entity["payload"]["category_id"] == spec["category_id"]
                and entity["payload"]["amount"] == spec["amount"]
                and entity["payload"]["currency"] == spec["currency"]
                and entity["payload"]["statistics_at"] == expected_statistics_at
            ]
            if len(matches) != 1:
                _fail(operation_path + ".result_state_id", "each input item must own exactly one consumption record")
            consumption = matches[0]
            ordered_consumptions.append(consumption)
            expected_consumption = {
                "category_id": spec["category_id"], "amount": spec["amount"], "currency": spec["currency"],
                "statistics_at": expected_statistics_at,
                "details": spec["details"], "source_observed_at": spec["source_observed_at"],
            }
            if any(consumption["payload"].get(field) != value for field, value in expected_consumption.items()):
                _fail(operation_path + ".result_state_id", "consumption record fields must be owned by the item input")
            if action == "manual_merged_payment" and any(field in consumption["payload"] for field in ("source_item_id", "source_id", "evidence_id")):
                _fail(operation_path + ".result_state_id", "manual consumption must not fabricate source bindings")
            if action == "confirm_merged_payment_candidate":
                proposal = source_bindings[spec["item_id"]]
                expected_binding = {"source_item_id": spec["item_id"], "source_id": proposal["source_id"], "evidence_id": proposal["evidence_id"]}
                if any(consumption["payload"].get(field) != value for field, value in expected_binding.items()):
                    _fail(operation_path + ".result_state_id", "confirmed consumption must preserve source/evidence bindings")
            expense = next((item for item in expense_postings if item["id"] == consumption["payload"]["expense_posting_id"]), None)
            if expense is None or expense["account_id"] != baseline_categories[spec["category_id"]]["posting_account_id"] or Decimal(expense["amount"]) != Decimal(spec["amount"]) or expense["reconciliation_eligible"] is not False:
                _fail(operation_path + ".result_state_id", "consumption must bind its exact category expense posting")
            allocations = [entity for entity in result_entities.values() if entity["type"] == "item_allocation" and entity["payload"].get("consumption_record_id") == consumption["id"]]
            if len(allocations) != 1 or allocations[0]["payload"]["expense_posting_id"] != expense["id"] or allocations[0]["payload"]["amount"] != spec["amount"] or allocations[0]["payload"]["category_id"] != spec["category_id"]:
                _fail(operation_path + ".result_state_id", "each consumption must own one exact item allocation")
            ordered_allocations.append(allocations[0])
            if action == "manual_merged_payment" and any(field in allocations[0]["payload"] for field in ("source_item_id", "source_id", "evidence_id")):
                _fail(operation_path + ".result_state_id", "manual allocation must not fabricate source bindings")
        relation = next(item for item in result["relations"] if item["id"] in added["relations"]["added_ids"])
        expected_members = {("transaction", transaction_id), ("posting", payment["id"]), *[("domain_entity", item["id"]) for item in result_entities.values() if item["id"] in added["domain_entities"]["added_ids"] and item["type"] == "item_allocation"]}
        actual_members = {(item["kind"], item["id"]) for item in relation["member_refs"]}
        if actual_members != expected_members:
            _fail(operation_path + ".result_state_id", "merged_payment relation must own one transaction, payment posting, and two allocations")
        reconciliation = [item for item in result_reconciliations.values() if item["id"] in added["posting_reconciliations"]["added_ids"]]
        if len(reconciliation) != 1 or reconciliation[0]["posting_id"] != payment["id"] or reconciliation[0]["status"] != ("pending" if action == "manual_merged_payment" else "matched"):
            _fail(operation_path + ".result_state_id", "only the unique payment posting may receive financial reconciliation")
        confirmation = result_confirmations[added["confirmations"]["added_ids"][0]]
        expected_confirmation_type = "explicit_manual_save" if action == "manual_merged_payment" else "candidate_confirmation"
        expected_subject = {"kind": "operation", "id": operation["id"]} if action == "manual_merged_payment" else {"kind": "candidate", "id": input_value["candidate_id"]}
        if confirmation["type"] != expected_confirmation_type or confirmation["subject"] != expected_subject or confirmation["operation_id"] != operation["id"] or "confirmed_at" in confirmation:
            _fail(operation_path + ".result_state_id", "RG-05 confirmation must be owned by the operation without a synthesized confirmed_at")
        expected_returned = [
            {"kind": "confirmation", "id": confirmation["id"]},
            {"kind": "transaction", "id": transaction_id},
            *[{"kind": "domain_entity", "id": item["id"]} for item in ordered_consumptions],
            *[{"kind": "domain_entity", "id": item["id"]} for item in ordered_allocations],
            {"kind": "relation", "id": relation["id"]},
        ]
        if action == "confirm_merged_payment_candidate":
            candidate_after = result_candidates[input_value["candidate_id"]]
            if [event["status"] for event in candidate_after["status_history"]] != ["pending_confirmation", "confirmed"] or candidate_after["payload"].get("transaction_id") != transaction_id:
                _fail(operation_path + ".result_state_id", "candidate confirmation must append one confirmed status and bind the created transaction")
            links = [item for item in result["evidence_links"] if item["id"] in added["evidence_links"]["added_ids"]]
            if len(links) != 2 or not any(item["target_id"] == payment["id"] and item["role"] == "payment_asset_posting" for item in links) or not any(item["role"] == "item_allocation_fact" for item in links):
                _fail(operation_path + ".result_state_id", "confirmation evidence links must isolate payment posting and one receipt allocation")
            links_by_role = {item["role"]: item for item in links}
            expected_returned = [
                {"kind": "candidate", "id": input_value["candidate_id"]},
                *expected_returned,
                {"kind": "evidence_link", "id": links_by_role["payment_asset_posting"]["id"]},
                {"kind": "evidence_link", "id": links_by_role["item_allocation_fact"]["id"]},
            ]
        if operation["returned_ids"] != expected_returned:
            _fail(operation_path + ".returned_ids", "RG-05 formal action must return exactly its owned result identities")
        return

    if action in {"manual_mixed_expense", "credit_principal_repayment", "confirm_mixed_payment_candidate"}:
        added = expected_entities["transactions"]["added_ids"]
        if len(added) != 1:
            _fail(operation_path + ".result_state_id", "RG04 action must add exactly one transaction")
        version, postings = transaction_parts(added[0])
        if "occurred_at" in input_value and version["occurred_at"] != input_value["occurred_at"]:
            _fail(operation_path + ".input.occurred_at", "must own the economic time")
        by_role = {posting.get("role"): posting for posting in postings}
        if action in {"manual_mixed_expense", "confirm_mixed_payment_candidate"}:
            required = {"expense", "mixed_expense_asset_funding", "mixed_expense_credit_funding"}
            if set(by_role) != required:
                _fail(operation_path + ".result_state_id", "mixed expense must use exact expense and two funding roles")
            if "total_amount" in input_value and Decimal(by_role["expense"]["amount"]) != Decimal(input_value["total_amount"]):
                _fail(operation_path + ".result_state_id", "expense posting must equal total amount")
            if action == "manual_mixed_expense":
                category = baseline_categories[input_value["category_id"]]
                if by_role["expense"].get("category_id") != input_value["category_id"]:
                    _fail(operation_path + ".result_state_id", "expense posting must preserve the confirmed category")
                if by_role["expense"]["account_id"] != category["posting_account_id"]:
                    _fail(operation_path + ".result_state_id", "expense posting must target the selected category account")
                if by_role["mixed_expense_asset_funding"]["account_id"] != input_value["asset_account_id"] or -Decimal(by_role["mixed_expense_asset_funding"]["amount"]) != Decimal(input_value["asset_funding_amount"]):
                    _fail(operation_path + ".result_state_id", "asset funding posting must exactly match the input")
                if by_role["mixed_expense_credit_funding"]["account_id"] != input_value["liability_account_id"] or -Decimal(by_role["mixed_expense_credit_funding"]["amount"]) != Decimal(input_value["liability_funding_amount"]):
                    _fail(operation_path + ".result_state_id", "liability funding posting must exactly match the input")
                if any("category_id" in by_role[role] for role in ("mixed_expense_asset_funding", "mixed_expense_credit_funding")):
                    _fail(operation_path + ".result_state_id", "funding postings must not carry category_id")
            else:
                candidate_id = input_value["candidate_id"]
                before_candidate = baseline_candidates[candidate_id]
                after_candidate = result_candidates.get(candidate_id)
                if after_candidate is None:
                    _fail(operation_path + ".result_state_id", "must preserve and confirm the input candidate")
                _validate_candidate_confirmation_transition(
                    before_candidate,
                    after_candidate,
                    operation_path + ".result_state.candidates[" + candidate_id + "]",
                )
                category = baseline_categories[input_value["category_id"]]
                if by_role["expense"]["account_id"] != category["posting_account_id"]:
                    _fail(operation_path + ".result_state_id", "expense posting must target the selected category account")
                if by_role["expense"].get("category_id") != input_value["category_id"]:
                    _fail(operation_path + ".result_state_id", "expense posting must preserve the confirmed category")
                candidate_total = Decimal(before_candidate["payload"]["total_amount"])
                candidate_currency = before_candidate["payload"]["currency"]
                if (
                    Decimal(by_role["expense"]["amount"]) != candidate_total
                    or by_role["expense"]["currency"] != candidate_currency
                ):
                    _fail(operation_path + ".result_state_id", "expense posting must equal candidate total amount and currency")
                components_by_kind = {}
                for component in input_value["confirmed_funding_components"]:
                    account = baseline_accounts[component["account_id"]]
                    components_by_kind[account["kind"]] = component
                for role, kind in (
                    ("mixed_expense_asset_funding", "asset"),
                    ("mixed_expense_credit_funding", "liability"),
                ):
                    component = components_by_kind[kind]
                    posting = by_role[role]
                    if "category_id" in posting:
                        _fail(operation_path + ".result_state_id", "funding postings must not carry category_id")
                    if (
                        posting["account_id"] != component["account_id"]
                        or posting["currency"] != component["currency"]
                        or Decimal(posting["amount"]) != -Decimal(component["funding_amount"])
                        or "category_id" in posting
                    ):
                        _fail(operation_path + ".result_state_id", f"{role} posting must match confirmed funding component")
        else:
            if set(by_role) != {"credit_repayment_asset_outflow", "credit_repayment_liability_principal"}:
                _fail(operation_path + ".result_state_id", "repayment must use exact principal roles")
            if -Decimal(by_role["credit_repayment_asset_outflow"]["amount"]) != Decimal(input_value["principal_amount"]):
                _fail(operation_path + ".result_state_id", "asset outflow must equal principal amount")
            if by_role["credit_repayment_asset_outflow"]["account_id"] != input_value["asset_account_id"] or by_role["credit_repayment_liability_principal"]["account_id"] != input_value["liability_account_id"]:
                _fail(operation_path + ".result_state_id", "repayment postings must preserve both account identities")
        return

    if action in {"manual_account_transfer", "confirm_account_transfer_candidate"}:
        added = expected_entities["transactions"]["added_ids"]
        if len(added) != 1 or result_transactions[added[0]]["type"] != "account_transfer":
            _fail(operation_path + ".result_state_id", "must add one account_transfer transaction")
        version, postings = transaction_parts(added[0])
        if any(version[field] != input_value["occurred_at"] for field in ("occurred_at", "statistics_at", "effective_at")):
            _fail(operation_path + ".input.occurred_at", "must own every economic time role")
        roles = {posting.get("role"): posting for posting in postings}
        if set(roles) != {"transfer_principal_out", "transfer_principal_in", "transfer_fee"}:
            _fail(operation_path + ".result_state_id", "must contain exact transfer principal and fee roles")
        expected = {
            "transfer_principal_out": (input_value["source_account_id"], -Decimal(input_value["source_debit_amount"])),
            "transfer_principal_in": (input_value["destination_account_id"], Decimal(input_value["destination_credit_amount"])),
        }
        for role, (account_id, amount) in expected.items():
            if roles[role]["account_id"] != account_id or Decimal(roles[role]["amount"]) != amount:
                _fail(operation_path + ".result_state_id", f"{role} does not match input")
        fee_category = input_value.get("fee_category_id")
        if action == "manual_account_transfer" and fee_category is None:
            _fail(operation_path + ".input.fee_category_id", "manual transfer requires a fee category")
        if fee_category is not None:
            category = baseline_categories.get(fee_category)
            if category is None or not category["active"] or category["posting_account_id"] != roles["transfer_fee"]["account_id"]:
                _fail(operation_path + ".input.fee_category_id", "must own the transfer-fee posting account")
        elif baseline_accounts.get(roles["transfer_fee"]["account_id"], {}).get("kind") != "expense":
            _fail(operation_path + ".result_state_id", "transfer_fee must post to an expense account")
        if Decimal(roles["transfer_fee"]["amount"]) != Decimal(input_value["fee_amount"]):
            _fail(operation_path + ".input.fee_amount", "does not match transfer_fee posting")
        if action == "confirm_account_transfer_candidate":
            candidate = baseline_candidates.get(input_value["candidate_id"])
            if candidate is None or candidate["type"] != "account_transfer":
                _fail(operation_path + ".input.candidate_id", "must reference the pending transfer candidate")
            if [item["status"] for item in candidate["status_history"]] != ["pending_confirmation"]:
                _fail(operation_path + ".input.candidate_id", "must reference a pending transfer candidate")
            for field in ("source_account_id", "destination_account_id", "source_debit_amount", "destination_credit_amount", "fee_amount", "currency"):
                if candidate["payload"][field] != input_value[field]:
                    _fail(operation_path + f".input.{field}", "must exactly match the pending transfer candidate")
            after_candidate = next(item for item in result["candidates"] if item["id"] == candidate["id"])
            if [item["status"] for item in after_candidate["status_history"]] != ["pending_confirmation", "confirmed"]:
                _fail(operation_path + ".result_state_id", "must append the confirmed candidate history")
            if after_candidate["payload"].get("transaction_id") != added[0]:
                _fail(operation_path + ".result_state_id", "confirmed candidate must bind the created account_transfer")
            added_link_id = expected_entities["evidence_links"]["added_ids"][0]
            added_link = next(item for item in result["evidence_links"] if item["id"] == added_link_id)
            if added_link["evidence_id"] != candidate["payload"]["evidence_refs"][0] or added_link["target_kind"] != "posting" or added_link["role"] != "real_account_posting":
                _fail(operation_path + ".result_state_id", "candidate confirmation must bind source evidence to a real account posting")
            source_postings = {
                item["id"]: item for item in postings
                if item.get("role") == "transfer_principal_out" and item["account_id"] == input_value["source_account_id"]
            }
            if added_link["target_id"] not in source_postings or source_postings[added_link["target_id"]]["amount"] != "-" + input_value["source_debit_amount"]:
                _fail(operation_path + ".result_state_id", "candidate confirmation evidence must target the source principal posting")
    elif action == "import_source_record":
        source = next(item for item in result["sources"] if item["id"] in expected_entities["sources"]["added_ids"])
        candidate = next(item for item in result["candidates"] if item["id"] in expected_entities["candidates"]["added_ids"])
        evidence = next(item for item in result["evidence"] if item["id"] in expected_entities["evidence"]["added_ids"])
        if source["id"] != input_value["source_id"] or evidence["id"] != input_value["evidence_id"] or candidate["source_ids"] != [source["id"]]:
            _fail(operation_path + ".result_state_id", "intake identities must be owned by the action input")
        expected_source_payload = {
            "source_account_id": input_value["source_account_id"],
            "destination_account_id": input_value["destination_account_id"],
            "source_debit_amount": input_value["source_debit_amount"],
            "destination_credit_amount": input_value["destination_credit_amount"],
            "fee_amount": input_value["fee_amount"],
            "currency": input_value["currency"],
            "completeness": "complete",
            "observed_at": input_value["observed_at"],
            "evidence_id": input_value["evidence_id"],
        }
        if source["type"] != "account_transfer" or source["payload"] != expected_source_payload:
            _fail(operation_path + ".result_state_id", "complete intake source must exactly equal the action input")
        if evidence["type"] != "transfer_record" or evidence["source_ids"] != [source["id"]] or evidence["payload"] != {"observed_at": input_value["observed_at"]}:
            _fail(operation_path + ".result_state_id", "complete intake evidence must exactly equal the source identity and observed_at")
        expected_candidate_payload = {
            "source_account_id": input_value["source_account_id"],
            "destination_account_id": input_value["destination_account_id"],
            "source_debit_amount": input_value["source_debit_amount"],
            "destination_credit_amount": input_value["destination_credit_amount"],
            "fee_amount": input_value["fee_amount"],
            "currency": input_value["currency"],
            "evidence_refs": [input_value["evidence_id"]],
            "provenance": {"rule": "complete_transfer_source", "rule_version": 1},
            "requires_confirmation": ["formal_transaction_creation"],
        }
        if candidate["type"] != "account_transfer" or candidate["confidence"] != "1.00" or candidate["payload"] != expected_candidate_payload:
            _fail(operation_path + ".result_state_id", "complete intake candidate must exactly equal the source-derived candidate contract")
        if [item["status"] for item in candidate["status_history"]] != ["pending_confirmation"]:
            _fail(operation_path + ".result_state_id", "complete intake must remain pending")
        return
    elif action == "import_incomplete_source":
        source = next(item for item in result["sources"] if item["id"] in expected_entities["sources"]["added_ids"])
        candidate = next(item for item in result["candidates"] if item["id"] in expected_entities["candidates"]["added_ids"])
        if source["id"] != input_value["source_id"] or candidate["source_ids"] != [source["id"]] or "destination_account_id" in candidate["payload"]:
            _fail(operation_path + ".result_state_id", "incomplete intake must not guess a destination")
        expected_source_payload = {
            "source_account_id": input_value["source_account_id"],
            "debit_amount": input_value["debit_amount"],
            "currency": input_value["currency"],
            "completeness": "missing_destination",
            "observed_at": input_value["observed_at"],
            "evidence_id": input_value["evidence_id"],
        }
        if source["type"] != "account_transfer" or source["payload"] != expected_source_payload:
            _fail(operation_path + ".result_state_id", "incomplete intake source must exactly equal the action input and omit destination")
        evidence = next(item for item in result["evidence"] if item["id"] in expected_entities["evidence"]["added_ids"])
        if evidence["type"] != "transfer_record" or evidence["source_ids"] != [source["id"]] or evidence["payload"] != {"observed_at": input_value["observed_at"]}:
            _fail(operation_path + ".result_state_id", "incomplete intake evidence must exactly equal the source identity and observed_at")
        expected_candidate_payload = {
            "source_account_id": input_value["source_account_id"],
            "debit_amount": input_value["debit_amount"],
            "currency": input_value["currency"],
            "evidence_refs": [input_value["evidence_id"]],
            "provenance": {"rule": "complete_transfer_source", "rule_version": 1},
            "requires_confirmation": ["destination_account_id", "formal_transaction_creation"],
        }
        if candidate["type"] != "account_transfer" or candidate["confidence"] != "1.00" or candidate["payload"] != expected_candidate_payload:
            _fail(operation_path + ".result_state_id", "incomplete intake candidate must exactly equal the source-derived incomplete contract")
    elif action == "import_mirror_record":
        transaction = baseline_transactions[input_value["transaction_id"]]
        if transaction["type"] != "account_transfer":
            _fail(operation_path + ".input.transaction_id", "must reference account_transfer")
        candidate = baseline_candidates.get(input_value["candidate_id"])
        if candidate is None:
            _fail(operation_path + ".input.candidate_id", "must reference transfer candidate")
        if candidate["type"] != "account_transfer" or candidate["status_history"][-1]["status"] != "confirmed" or candidate["payload"].get("transaction_id") != input_value["transaction_id"]:
            _fail(operation_path + ".input.candidate_id", "must bind the confirmed candidate to the input account_transfer")
        if len(expected_entities["transactions"]["added_ids"]) or len(expected_entities["postings"]["added_ids"]):
            _fail(operation_path + ".deltas.entity_changes", "mirror evidence cannot create another transfer")
        baseline_version = next(item for item in baseline["transaction_versions"] if item["id"] == transaction["current_version_id"])
        baseline_set = next(item for item in baseline["posting_sets"] if item["id"] == baseline_version["posting_set_id"])
        current_postings = [item for item in baseline["postings"] if item["id"] in baseline_set["posting_ids"]]
        source = next(item for item in result["sources"] if item["id"] in expected_entities["sources"]["added_ids"])
        evidence = next(item for item in result["evidence"] if item["id"] in expected_entities["evidence"]["added_ids"])
        link = next(item for item in result["evidence_links"] if item["id"] in expected_entities["evidence_links"]["added_ids"])
        source_payload = source["payload"]
        if source["type"] != "account_credit_observation" or source["id"] != input_value["source_id"]:
            _fail(operation_path + ".result_state_id", "mirror source must be account_credit_observation with the input identity")
        if set(source_payload) != {"account_id", "credit_amount", "currency", "observed_at", "evidence_id"}:
            _fail(operation_path + ".result_state_id", "mirror source payload must be closed to account-credit observation fields")
        if source_payload["account_id"] != input_value["account_id"] or source_payload["credit_amount"] != input_value["credit_amount"] or source_payload["currency"] != input_value["currency"] or source_payload["observed_at"] != input_value["observed_at"] or source_payload["evidence_id"] != input_value["evidence_id"]:
            _fail(operation_path + ".result_state_id", "mirror source payload must exactly preserve the source record")
        if evidence["type"] != "transfer_record" or evidence["id"] != input_value["evidence_id"] or evidence["source_ids"] != [source["id"]] or evidence["payload"]["observed_at"] != source_payload["observed_at"]:
            _fail(operation_path + ".result_state_id", "mirror evidence must bind exactly to the mirror source")
        if link["evidence_id"] != evidence["id"] or link["target_kind"] != "posting" or link["role"] != "destination_asset_posting":
            _fail(operation_path + ".result_state_id", "mirror evidence link must target the destination asset posting")
        destination_postings = [
            item for item in current_postings
            if item.get("role") == "transfer_principal_in"
            and item["account_id"] == input_value["account_id"]
            and item["amount"] == input_value["credit_amount"]
        ]
        if len(destination_postings) != 1 or link["target_id"] != destination_postings[0]["id"]:
            _fail(operation_path + ".result_state_id", "mirror evidence link must target the unique destination posting in the transaction current posting set")
        if any(item["id"] == link["target_id"] for item in baseline["postings"] if item["id"] not in baseline_set["posting_ids"]):
            _fail(operation_path + ".result_state_id", "mirror evidence cannot target an unrelated same-account same-amount posting")
        changed_reconciliation_ids = expected_entities["posting_reconciliations"]["changed_ids"]
        if len(changed_reconciliation_ids) > 1:
            _fail(operation_path + ".deltas.entity_changes.posting_reconciliations", "mirror may change at most its destination reconciliation")
        if changed_reconciliation_ids:
            reconciliation_id = changed_reconciliation_ids[0]
            before_reconciliation = baseline_reconciliations[reconciliation_id]
            after_reconciliation = result_reconciliations[reconciliation_id]
            if before_reconciliation["posting_id"] != link["target_id"] or before_reconciliation["status"] != "pending" or after_reconciliation["status"] != "matched":
                _fail(operation_path + ".result_state_id", "mirror reconciliation must match the existing destination posting pending-to-matched transition")
        expected_returned = [
            {"kind": "source", "id": source["id"]},
            {"kind": "evidence", "id": evidence["id"]},
            {"kind": "evidence_link", "id": link["id"]},
        ]
        if operation["returned_ids"] != expected_returned:
            _fail(operation_path + ".returned_ids", "mirror must return exactly its new source, evidence, link, and reconciliation identities")
    elif action == "create_periodic_allocation":
        transaction_id = expected_entities["transactions"]["added_ids"][0]
        transaction = result_transactions[transaction_id]
        if transaction["type"] != "prepaid_purchase":
            _fail(operation_path + ".result_state_id", "must add one prepaid purchase transaction")
        version, postings = transaction_parts(transaction_id)
        by_role = {item.get("role"): item for item in postings}
        if set(by_role) != {"payment_asset", "prepaid_asset"}:
            _fail(operation_path + ".result_state_id", "purchase must use the exact payment and prepaid posting roles")
        if any(version[field] != input_value["occurred_at"] for field in ("occurred_at", "statistics_at", "effective_at")):
            _fail(operation_path + ".input.occurred_at", "must own all payment transaction time roles")
        if by_role["payment_asset"]["account_id"] != input_value["payment_account_id"] or by_role["payment_asset"]["amount"] != "-" + input_value["amount"] or by_role["prepaid_asset"]["account_id"] != input_value["prepaid_account_id"] or by_role["prepaid_asset"]["amount"] != input_value["amount"]:
            _fail(operation_path + ".result_state_id", "purchase postings must match the closed input")
        added_entities = {
            item["id"]: item for item in result["domain_entities"]
            if item["id"] in expected_entities["domain_entities"]["added_ids"]
        }
        schedules = [item for item in added_entities.values() if item["type"] == "periodic_allocation_schedule"]
        revisions = [item for item in added_entities.values() if item["type"] == "periodic_allocation_revision"]
        installments = [item for item in added_entities.values() if item["type"] == "periodic_allocation_installment"]
        if len(schedules) != 1 or len(revisions) != 1 or len(installments) != input_value["installment_count"]:
            _fail(operation_path + ".result_state_id", "creation must add one schedule, one initial revision, and its installments")
        schedule = schedules[0]["payload"]
        for input_key, payload_key in (("prepaid_account_id", "prepaid_account_id"), ("category_id", "category_id"), ("amount", "total_amount"), ("currency", "currency"), ("start_at", "start_at"), ("anchor", "anchor"), ("cadence", "cadence")):
            if schedule[payload_key] != input_value[input_key]:
                _fail(operation_path + ".result_state_id", "created schedule must exactly preserve the closed input")
        if schedule["payment_transaction_id"] != transaction_id:
            _fail(operation_path + ".result_state_id", "schedule must own the created purchase transaction")
        expected_returned = [
            {"kind": "transaction", "id": transaction_id},
            {"kind": "domain_entity", "id": schedules[0]["id"]},
        ]
        if operation["returned_ids"] != expected_returned:
            _fail(operation_path + ".returned_ids", "creation must return its transaction and schedule in contract order")
    elif action == "recognize_periodic_allocation_installment":
        transaction_id = expected_entities["transactions"]["added_ids"][0]
        transaction = result_transactions[transaction_id]
        version, postings = transaction_parts(transaction_id)
        by_role = {item.get("role"): item for item in postings}
        schedule = baseline_entities[input_value["schedule_id"]]["payload"]
        if transaction["type"] != "prepaid_recognition" or set(by_role) != {"expense", "prepaid_asset"}:
            _fail(operation_path + ".result_state_id", "must add one prepaid recognition with exact release roles")
        if by_role["expense"].get("category_id") != schedule["category_id"] or by_role["expense"]["amount"] != input_value["amount"] or by_role["prepaid_asset"]["account_id"] != schedule["prepaid_account_id"] or by_role["prepaid_asset"]["amount"] != "-" + input_value["amount"]:
            _fail(operation_path + ".result_state_id", "recognition postings must match the schedule installment")
        installment = baseline_entities[input_value["installment_id"]]["payload"]
        if version["occurred_at"] != installment["scheduled_at"] or version["statistics_at"] != installment["scheduled_at"] or version["effective_at"] != installment["scheduled_at"]:
            _fail(operation_path + ".result_state_id", "recognition must use the installment economic time")
        audit_id = expected_entities["audit_links"]["added_ids"][0]
        audit = next(item for item in result["audit_links"] if item["id"] == audit_id)
        if audit != {"id": audit_id, "type": "periodic_allocation_recognition", "from": {"kind": "domain_entity", "id": input_value["installment_id"]}, "to": {"kind": "transaction", "id": transaction_id}, "payload": {}}:
            _fail(operation_path + ".result_state_id", "recognition must add the exact installment audit link")
        if operation["returned_ids"] != [{"kind": "transaction", "id": transaction_id}]:
            _fail(operation_path + ".returned_ids", "recognition must return exactly its created transaction")
    elif action == "revise_periodic_allocation":
        added_entities = [item for item in result["domain_entities"] if item["id"] in expected_entities["domain_entities"]["added_ids"]]
        revisions = [item for item in added_entities if item["type"] == "periodic_allocation_revision"]
        installments = [item for item in added_entities if item["type"] == "periodic_allocation_installment"]
        if len(revisions) != 1 or len(installments) != input_value["remaining_installment_count"]:
            _fail(operation_path + ".result_state_id", "revision must add exactly one revision and its immutable installments")
        revision = revisions[0]["payload"]
        if revision["schedule_id"] != input_value["schedule_id"] or revision["recognized_through"] != input_value["recognized_through"] or revision["remaining_amount"] != input_value["remaining_amount"] or revision["currency"] != input_value["currency"]:
            _fail(operation_path + ".result_state_id", "new revision must exactly preserve the closed remainder input")
        if operation["returned_ids"] != [{"kind": "domain_entity", "id": revisions[0]["id"]}]:
            _fail(operation_path + ".returned_ids", "revision must return exactly its new revision")
    elif action == "correct_transaction_version":
        transaction_id = input_value["transaction_id"]
        before = baseline_transactions[transaction_id]
        after = result_transactions[transaction_id]
        old_version = next(item for item in baseline["transaction_versions"] if item["id"] == before["current_version_id"])
        new_version = result_versions[after["current_version_id"]]
        confirmation_id = expected_entities["confirmations"]["added_ids"][0]
        confirmation = result_confirmations[confirmation_id]
        expected_confirmation = {"id": confirmation_id, "type": "explicit_operation_confirmation", "operation_id": operation["id"], "subject": {"kind": "operation", "id": operation["id"]}, "payload": {}}
        if input_value["correction_kind"] == "posting_facts":
            expected_confirmation["confirmed_at"] = input_value["corrected_at"]
        if confirmation != expected_confirmation:
            _fail(operation_path + ".result_state_id", "correction requires its exact operation confirmation owner")
        if input_value["correction_kind"] == "statistics_time":
            expected_version = deepcopy(old_version)
            expected_version.update({
                "id": new_version["id"],
                "version_number": old_version["version_number"] + 1,
                "statistics_at": input_value["statistics_at"],
                "confirmation_id": confirmation_id,
            })
            if new_version != expected_version:
                _fail(operation_path + ".result_state_id", "statistics-time correction may change only id, next version_number, statistics_at, and confirmation ownership")
        else:
            old_set = next(item for item in baseline["posting_sets"] if item["id"] == old_version["posting_set_id"])
            old_postings = {item["id"]: item for item in baseline["postings"] if item["id"] in old_set["posting_ids"]}
            new_set = result_sets.get(new_version["posting_set_id"])
            if new_set is None:
                _fail(operation_path + ".result_state_id", "posting-facts correction must append a posting set")
            new_postings = {item["id"]: item for item in result_postings.values() if item["id"] in new_set["posting_ids"]}
            _validate_mixed_expense_postings(
                list(new_postings.values()),
                baseline_accounts,
                baseline_categories,
                operation_path + ".result_state_id.posting_set",
            )
            if set(old_postings) != {item["source_posting_id"] for item in input_value["replacement_postings"]}:
                _fail(operation_path + ".input.replacement_postings", "must cover the old current posting set exactly once")
            if len(new_postings) != len(input_value["replacement_postings"]):
                _fail(operation_path + ".result_state_id", "must create exactly one replacement posting per source posting")
            if any(old_postings[item_id] != result_postings.get(item_id) for item_id in old_postings):
                _fail(operation_path + ".result_state_id", "old postings are immutable historical facts")
            links = [
                result_audit_links[item_id]
                for item_id in expected_entities["audit_links"]["added_ids"]
            ]
            links_by_old = {link["from"]["id"]: link for link in links}
            if set(links_by_old) != set(old_postings) or len(links_by_old) != len(links):
                _fail(operation_path + ".result_state_id", "must add one replacement link for every old current posting")
            link_targets = {link["to"]["id"] for link in links}
            if link_targets != set(new_postings) or len(link_targets) != len(links):
                _fail(operation_path + ".result_state_id", "every new current posting must have exactly one source replacement link")
            baseline_matches = {
                item["payload"]["posting_id"]: item
                for item in baseline["domain_entities"]
                if item["type"] == "reconciliation_match"
            }
            result_matches = {
                item["payload"]["posting_id"]: item
                for item in result["domain_entities"]
                if item["type"] == "reconciliation_match"
            }
            for item in input_value["replacement_postings"]:
                old = old_postings[item["source_posting_id"]]
                link = links_by_old[old["id"]]
                new = new_postings.get(link["to"]["id"])
                if new is None:
                    _fail(operation_path + ".result_state_id", "replacement link must target the new current posting set")
                expected_posting = {key: value for key, value in item.items() if key != "source_posting_id"}
                actual_posting = {key: value for key, value in new.items() if key not in {"id", "posting_set_id", "reconciliation_eligible"}}
                if actual_posting != expected_posting:
                    _fail(operation_path + ".result_state_id", "new postings must exactly equal the explicit replacement input")
                old_account = baseline_accounts[old["account_id"]]
                same_facts = all(old.get(field) == new.get(field) for field in ("account_id", "amount", "currency", "role", "category_id"))
                effect = link["payload"]["reconciliation_effect"]
                old_reconciliation = baseline_reconciliations_by_posting.get(old["id"])
                result_old_reconciliation = result_reconciliations_by_posting.get(old["id"])
                new_reconciliation = result_reconciliations_by_posting.get(new["id"])
                old_match = baseline_matches.get(old["id"])
                result_old_match = result_matches.get(old["id"])
                result_new_match = result_matches.get(new["id"])
                eligible_real = (
                    old_account["owned_by_user"] is True
                    and old_account["real_account"] is True
                    and old_reconciliation is not None
                )
                if not eligible_real:
                    if effect != "not_applicable" or new_reconciliation is not None or result_new_match is not None:
                        _fail(operation_path + ".result_state_id", "non-real replacement postings must be not_applicable and unreconciled")
                    continue
                if old_match is None or old_match["payload"]["status_history"][-1]["status"] != "matched":
                    _fail(operation_path + ".result_state_id", "eligible real replacement requires an active predecessor reconciliation match")
                if result_old_reconciliation != old_reconciliation:
                    _fail(operation_path + ".result_state_id", "old posting reconciliation facts must remain unchanged")
                if same_facts:
                    if effect != "preserved" or old_reconciliation["status"] != "matched" or new_reconciliation is None or new_reconciliation["status"] != "matched":
                        _fail(operation_path + ".result_state_id", "unchanged eligible real posting must preserve a matched reconciliation")
                    if result_old_match != old_match:
                        _fail(operation_path + ".result_state_id", "preserved correction must leave the predecessor match unchanged")
                    if (
                        result_new_match is None
                        or result_new_match["payload"]["evidence_id"] != old_match["payload"]["evidence_id"]
                        or result_new_match["payload"]["status_history"][-1]["status"] != "matched"
                    ):
                        _fail(operation_path + ".result_state_id", "preserved correction must inherit the active predecessor evidence match")
                else:
                    if effect != "invalidated" or new_reconciliation is None or new_reconciliation["status"] != "pending":
                        _fail(operation_path + ".result_state_id", "changed eligible real posting must invalidate and leave the replacement pending")
                    if result_new_match is not None and result_new_match["payload"]["status_history"][-1]["status"] == "matched":
                        _fail(operation_path + ".result_state_id", "invalidated correction cannot inherit an active match")
                    if result_old_match is None:
                        _fail(operation_path + ".result_state_id", "changed eligible real posting must retain its predecessor match history")
                    old_history = old_match["payload"]["status_history"]
                    result_history = result_old_match["payload"]["status_history"]
                    if (
                        result_old_match.get("id") != old_match.get("id")
                        or result_old_match["payload"].get("posting_id") != old_match["payload"].get("posting_id")
                        or result_old_match["payload"].get("evidence_id") != old_match["payload"].get("evidence_id")
                        or result_history[: len(old_history)] != old_history
                        or len(result_history) != len(old_history) + 1
                        or result_history[-1]["status"] != "invalidated"
                        or result_history[-1]["sequence"] != len(old_history) + 1
                        or result_history[-1]["at"] != input_value["corrected_at"]
                    ):
                        _fail(operation_path + ".result_state_id", "changed eligible real posting must append exactly one invalidation event to its predecessor match")
            if any(new_version[field] != old_version[field] for field in ("occurred_at", "statistics_at", "effective_at")):
                _fail(operation_path + ".result_state_id", "posting-facts correction must preserve economic time roles")
            expected_version = deepcopy(old_version)
            expected_version.update({
                "id": new_version["id"], "version_number": old_version["version_number"] + 1,
                "posting_set_id": new_set["id"], "created_at": input_value["corrected_at"],
                "confirmation_id": confirmation_id,
            })
            if new_version != expected_version:
                _fail(operation_path + ".result_state_id", "posting-facts correction must preserve version facts and use corrected_at as creation time")
            changed_entities = [result_entities[item_id] for item_id in expected_entities["domain_entities"]["changed_ids"]]
            added_entities = [result_entities[item_id] for item_id in expected_entities["domain_entities"]["added_ids"]]
            changed_match_ids = {
                old["id"]
                for item in input_value["replacement_postings"]
                for old in [old_postings[item["source_posting_id"]]]
                if old["id"] in baseline_matches
                and baseline_matches[old["id"]]["id"]
                and not all(old.get(field) == new_postings[links_by_old[old["id"]]["to"]["id"]].get(field) for field in ("account_id", "amount", "currency", "role", "category_id"))
            }
            actual_changed_match_ids = {
                entity["id"] for entity in changed_entities if entity["type"] == "reconciliation_match"
            }
            if any(entity["type"] != "reconciliation_match" for entity in changed_entities) or actual_changed_match_ids != {baseline_matches[posting_id]["id"] for posting_id in changed_match_ids}:
                _fail(operation_path + ".result_state_id", "every changed matched eligible posting must append exactly its predecessor match history")
            if sum(entity["type"] == "consumption_record" for entity in added_entities) != 1:
                _fail(operation_path + ".result_state_id", "correction must add exactly one replacement consumption record")
        if operation["returned_ids"] != [{"kind": "transaction_version", "id": new_version["id"]}]:
            _fail(operation_path + ".returned_ids", "correction must return exactly its appended transaction version")
    elif action == "manual_expense":
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
    elif action in {"confirm_real_transfer", "confirm_second_real_transfer"}:
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
    elif action in {"confirm_explanation_allocation", "confirm_second_explanation_allocation"}:
        if action == "confirm_explanation_allocation":
            adjustment = baseline_entities.get(input_value["adjustment_id"])
            if adjustment is None or adjustment["type"] != "balance_adjustment":
                _fail(operation_path + ".input.adjustment_id", "dangling or mistyped adjustment reference")
        else:
            adjustments = [
                item
                for item in baseline["domain_entities"]
                if item.get("type") == "balance_adjustment"
            ]
            if len(adjustments) != 1:
                _fail(operation_path + ".input.transaction_id", "baseline must own exactly one balance adjustment")
            adjustment = adjustments[0]
        explanation = baseline_transactions.get(input_value["transaction_id"])
        if explanation is None or explanation["type"] != "account_transfer":
            _fail(operation_path + ".input.transaction_id", "dangling or mistyped transfer reference")
        observation = baseline_entities[adjustment["payload"]["observation_id"]]
        observation_payload = observation["payload"]
        if action == "confirm_explanation_allocation":
            for input_key, expected_value in (
                ("target_account_id", observation_payload["account_id"]),
                ("currency", observation_payload["currency"]),
                ("target_observed_at", observation_payload["observed_at"]),
            ):
                if input_value[input_key] != expected_value:
                    _fail(operation_path + f".input.{input_key}", "does not match the target observation")
        else:
            for input_key, expected_value in (
                ("target_account_id", observation_payload["account_id"]),
                ("currency", observation_payload["currency"]),
            ):
                if input_value[input_key] != expected_value:
                    _fail(operation_path + f".input.{input_key}", "does not match the target observation")
            target_observed_at = observation_payload["observed_at"]
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
        if action == "confirm_explanation_allocation":
            if len(target_legs) != 1 or target_legs[0]["amount"] != input_value["real_transaction_amount"]:
                _fail(operation_path + ".input.real_transaction_amount", "does not match the transfer target posting")
            if Decimal(input_value["explanation_amount"]) <= 0 or Decimal(input_value["explanation_amount"]) > Decimal(input_value["real_transaction_amount"]):
                _fail(operation_path + ".input.explanation_amount", "must be positive and no greater than the real transaction amount")
        else:
            if len(target_legs) != 1 or target_legs[0]["amount"] != input_value["amount"]:
                _fail(operation_path + ".input.amount", "does not match the transfer target posting")
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
        allocation_amount = (
            input_value["explanation_amount"]
            if action == "confirm_explanation_allocation"
            else input_value["explanation_allocation"]
        )
        if (
            allocation["adjustment_id"] != adjustment["id"]
            or allocation["explanation_transaction_id"] != input_value["transaction_id"]
            or allocation["amount"] != allocation_amount
            or allocation["currency"] != input_value["currency"]
            or allocation["confirmed_at"] != input_value["confirmed_at"]
        ):
            _fail(operation_path + ".input.explanation_amount", "does not match the allocation")
        reversal_version, reversal_postings = transaction_parts(added_transactions[0])
        reversal_observed_at = (
            input_value["target_observed_at"]
            if action == "confirm_explanation_allocation"
            else target_observed_at
        )
        if any(reversal_version[field] != reversal_observed_at for field in ("occurred_at", "statistics_at", "effective_at")):
            _fail(operation_path + ".input.target_observed_at", "must be preserved in reversal time roles")
        if reversal_version.get("created_at") != input_value["confirmed_at"]:
            _fail(operation_path + ".input.confirmed_at", "must be preserved as reversal creation time")
        by_role = {posting.get("role"): posting for posting in reversal_postings}
        if set(by_role) != {"balance_adjustment_reversal_target", "balance_adjustment_reversal_counterpart"}:
            _fail(operation_path + ".result_state_id", "reversal must contain exact target and counterpart roles")
        reversal_target = by_role["balance_adjustment_reversal_target"]
        reversal_counterpart = by_role["balance_adjustment_reversal_counterpart"]
        if reversal_target["account_id"] != input_value["target_account_id"] or Decimal(reversal_target["amount"]) != -Decimal(allocation_amount):
            _fail(operation_path + ".input.explanation_amount", "does not match the reversal target posting")
        if Decimal(reversal_counterpart["amount"]) != Decimal(allocation_amount):
            _fail(operation_path + ".input.explanation_amount", "does not match the reversal counterpart posting")
    elif action == "save_zero_delta_observation":
        added_observations = [
            item
            for item in result["domain_entities"]
            if item["id"] in expected_entities["domain_entities"]["added_ids"]
            and item["type"] == "target_balance_observation"
        ]
        if len(added_observations) != 1:
            _fail(operation_path + ".result_state_id", "must add one target balance observation")
        observation = added_observations[0]
        expected_payload = {
            "account_id": input_value["account_id"],
            "target_amount": input_value["target_amount"],
            "currency": input_value["currency"],
            "observed_at": input_value["target_observed_at"],
            "source_id": expected_entities["sources"]["added_ids"][0],
        }
        if observation["payload"] != expected_payload:
            _fail(operation_path + ".input.target_amount", "does not match the saved observation")
        source = next(
            item for item in result["sources"]
            if item["id"] == expected_entities["sources"]["added_ids"][0]
        )
        expected_source_payload = {
            "account_id": input_value["account_id"],
            "target_amount": input_value["target_amount"],
            "currency": input_value["currency"],
            "target_observed_at": input_value["target_observed_at"],
        }
        if source["type"] != "explicit_balance_observation" or source["payload"] != expected_source_payload:
            _fail(operation_path + ".input.target_amount", "does not match the saved source")
        evidence = next(
            item for item in result["evidence"]
            if item["id"] == expected_entities["evidence"]["added_ids"][0]
        )
        if (
            evidence["type"] != "user_balance_observation"
            or evidence["source_ids"] != [source["id"]]
            or evidence["payload"] != {"observed_at": input_value["target_observed_at"]}
        ):
            _fail(operation_path + ".input.target_observed_at", "does not match the saved evidence")
    elif action == "receive_import_candidate":
        source = next(
            item for item in result["sources"]
            if item["id"] == expected_entities["sources"]["added_ids"][0]
        )
        candidate = next(
            item for item in result["candidates"]
            if item["id"] == expected_entities["candidates"]["added_ids"][0]
        )
        if source["id"] != input_value["source_id"] or source["type"] != "imported_transfer_candidate":
            _fail(operation_path + ".input.source_id", "does not match the imported candidate source")
        if candidate["confidence"] != input_value["confidence"]:
            _fail(operation_path + ".input.confidence", "does not match the imported candidate confidence")
        source_payload = source["payload"]
        digest = source_payload.get("immutable_payload_digest")
        if (
            not isinstance(digest, str)
            or not digest.startswith("sha256:")
            or {key for key in source_payload if key != "immutable_payload_digest"}
            != {
                "observed_at", "actual_at", "account_id", "counter_account_id",
                "amount", "currency",
            }
        ):
            _fail(operation_path + ".input.source_id", "imported source payload must carry the frozen v1 intake facts")
        expected_candidate_payload = {
            "proposed_transaction_id": None,
            "proposed_target_account_id": source_payload["account_id"],
            "proposed_counter_account_id": source_payload["counter_account_id"],
            "proposed_actual_at": source_payload["actual_at"],
            "proposed_currency": source_payload["currency"],
            "proposed_allocation_amount": source_payload["amount"],
            "requires_confirmation": [
                "transaction_id", "target_account_id", "actual_time", "currency", "explanation_allocation"
            ],
        }
        if (
            candidate["source_ids"] != [source["id"]]
            or candidate["payload"] != expected_candidate_payload
            or [item["status"] for item in candidate["status_history"]] != ["pending_confirmation"]
        ):
            _fail(operation_path + ".input.source_id", "import candidate must stay pending under the frozen v1 contract")
        evidence = next(
            item for item in result["evidence"]
            if item["id"] == expected_entities["evidence"]["added_ids"][0]
        )
        if (
            evidence["type"] != "imported_real_transaction_candidate"
            or evidence["source_ids"] != [source["id"]]
            or evidence["payload"] != {"observed_at": source_payload["observed_at"]}
        ):
            _fail(operation_path + ".input.source_id", "does not match the imported candidate evidence")
    elif action == "confirm_imported_real_transfer":
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
            _fail(operation_path + ".result_state_id", "imported transfer must contain exact principal roles")
        incoming = by_role["transfer_principal_in"]
        outgoing = by_role["transfer_principal_out"]
        if incoming["account_id"] != input_value["target_account_id"] or incoming["amount"] != input_value["amount"]:
            _fail(operation_path + ".input.amount", "does not match the transfer-in posting")
        if outgoing["account_id"] != input_value["counter_account_id"] or Decimal(outgoing["amount"]) != -Decimal(input_value["amount"]):
            _fail(operation_path + ".input.amount", "does not match the transfer-out posting")
    elif action == "confirm_imported_explanation_allocation":
        adjustments = [
            item
            for item in baseline["domain_entities"]
            if item.get("type") == "balance_adjustment"
        ]
        if len(adjustments) != 1:
            _fail(operation_path + ".input.transaction_id", "baseline must own exactly one balance adjustment")
        adjustment = adjustments[0]
        observation = baseline_entities[adjustment["payload"]["observation_id"]]
        observation_payload = observation["payload"]
        for input_key, expected_value in (
            ("target_account_id", observation_payload["account_id"]),
            ("currency", observation_payload["currency"]),
        ):
            if input_value[input_key] != expected_value:
                _fail(operation_path + f".input.{input_key}", "does not match the target observation")
        target_observed_at = observation_payload["observed_at"]
        explanation = baseline_transactions.get(input_value["transaction_id"])
        if explanation is None or explanation["type"] != "account_transfer":
            _fail(operation_path + ".input.transaction_id", "dangling or mistyped transfer reference")
        explanation_version = next(
            item for item in baseline["transaction_versions"]
            if item["id"] == explanation["current_version_id"]
        )
        if explanation_version["occurred_at"] != input_value["actual_occurred_at"]:
            _fail(operation_path + ".input.actual_occurred_at", "does not match the explanation transaction")
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
            allocation["adjustment_id"] != adjustment["id"]
            or allocation["explanation_transaction_id"] != input_value["transaction_id"]
            or allocation["amount"] != input_value["explanation_allocation"]
            or allocation["currency"] != input_value["currency"]
            or allocation["confirmed_at"] != input_value["confirmed_at"]
        ):
            _fail(operation_path + ".input.explanation_allocation", "does not match the allocation")
        reversal_version, reversal_postings = transaction_parts(added_transactions[0])
        if any(reversal_version[field] != target_observed_at for field in ("occurred_at", "statistics_at", "effective_at")):
            _fail(operation_path + ".input.target_observed_at", "must be preserved in reversal time roles")
        if reversal_version.get("created_at") != input_value["confirmed_at"]:
            _fail(operation_path + ".input.confirmed_at", "must be preserved as reversal creation time")
        by_role = {posting.get("role"): posting for posting in reversal_postings}
        if set(by_role) != {"balance_adjustment_reversal_target", "balance_adjustment_reversal_counterpart"}:
            _fail(operation_path + ".result_state_id", "reversal must contain exact target and counterpart roles")
        reversal_target = by_role["balance_adjustment_reversal_target"]
        reversal_counterpart = by_role["balance_adjustment_reversal_counterpart"]
        if reversal_target["account_id"] != input_value["target_account_id"] or Decimal(reversal_target["amount"]) != -Decimal(input_value["explanation_allocation"]):
            _fail(operation_path + ".input.explanation_allocation", "does not match the reversal target posting")
        if Decimal(reversal_counterpart["amount"]) != Decimal(input_value["explanation_allocation"]):
            _fail(operation_path + ".input.explanation_allocation", "does not match the reversal counterpart posting")
    elif action == "link_real_posting_evidence":
        source = next(
            item for item in result["sources"]
            if item["id"] == expected_entities["sources"]["added_ids"][0]
        )
        evidence = next(
            item for item in result["evidence"]
            if item["id"] == expected_entities["evidence"]["added_ids"][0]
        )
        link = next(
            item for item in result["evidence_links"]
            if item["id"] == expected_entities["evidence_links"]["added_ids"][0]
        )
        if source["id"] != input_value["source_id"] or source["type"] != "account_statement":
            _fail(operation_path + ".input.source_id", "does not match the account statement source")
        if source["payload"].get("observed_at") != input_value["observed_at"]:
            _fail(operation_path + ".input.observed_at", "does not match the account statement source")
        if (
            evidence["type"] != "real_account_posting"
            or evidence["source_ids"] != [source["id"]]
            or evidence["payload"] != {"observed_at": input_value["observed_at"]}
        ):
            _fail(operation_path + ".input.evidence_id", "does not match the real posting evidence")
        if (
            link["evidence_id"] != evidence["id"]
            or link["target_kind"] != "posting"
            or link["target_id"] != input_value["target_posting_id"]
            or link["role"] != "real_account_posting"
        ):
            _fail(operation_path + ".input.target_posting_id", "does not match the linked posting")


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

            _validate_action_input(
                operation, operation_path, baseline, precisions, timezone
            )
            expected_entities = _expected_entity_changes(baseline, result)
            _validate_append_only_transition(
                baseline,
                result,
                operation_path,
                case_id=case["case"]["id"],
                action_type=operation["action_type"],
                outcome_status=operation["outcome"]["status"],
                target_candidate_id=(operation.get("input") or {}).get("candidate_id"),
                target_relation_id=(operation.get("input") or {}).get(
                    "relation_id",
                    (operation.get("input") or {}).get("refund_relation_id"),
                ),
            )
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
            _validate_rg05_identity_conflict(
                operation, operation_path, earlier_operations
            )
            _validate_no_change_retry(
                operation,
                operation_path,
                earlier_operations,
                all_operations=case["operations"],
                states=states,
            )

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

            if (
                operation["outcome"]["status"] == "accepted"
                and operation["action_type"]
                not in {"record_expiry_reminder", "rename_stored_value_labels"}
                and not any(
                    change[change_type]
                    for change in expected_entities.values()
                    for change_type in ("added_ids", "changed_ids", "removed_ids")
                )
                and not (expected_balances or expected_reports or expected_statuses)
            ):
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
        "RG-10": {
            "opening_balance",
            "stored_value_recharge",
            "stored_value_spend",
            "stored_value_expiry_loss",
            "stored_value_pre_activation_balance_adjustment",
        },
        "RG-03": {"opening_balance", "account_transfer"},
        "RG-04": {"opening_balance", "expense", "credit_repayment"},
        "RG-05": {"opening_balance", "expense"},
        "RG-06": {"opening_balance", "expense"},
        "RG-11": {"opening_balance", "prepaid_purchase", "prepaid_recognition"},
        "RG-12": {"expense"},
        "RG-07": {"opening_balance", "expense", "refund_receipt"},
        "RG-08": {"opening_balance", "lending_disbursement", "lending_collection"},
    }
    case_id = case["case"]["id"]
    if case_id not in supported_transaction_types:
        _fail(
            "$.case.id",
            "semantic prototype does not support this case",
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
        if case_id == "RG-05":
            for version_index, version in enumerate(state["transaction_versions"]):
                transaction = indexes["transactions"].get(version["transaction_id"])
                if transaction is not None and transaction["type"] == "opening_balance":
                    version_path = f"{state_path}.transaction_versions[{version_index}]"
                    if "created_at" in version:
                        _fail(version_path + ".created_at", "RG-05 opening versions must not synthesize created_at")
                    if any(version[field] != version["occurred_at"] for field in ("statistics_at", "effective_at")):
                        _fail(version_path, "RG-05 opening occurred_at may expand only to statistics_at and effective_at")
            for confirmation_index, confirmation in enumerate(state["confirmations"]):
                if "confirmed_at" in confirmation:
                    _fail(
                        f"{state_path}.confirmations[{confirmation_index}].confirmed_at",
                        "RG-05 does not synthesize confirmed_at from source or payment time",
                    )
        _validate_relations(state, state_path, indexes, current, precisions)
        _validate_balances(state, state_path, indexes, replay, precisions)
        _validate_references(
            state, state_path, indexes, operations, precisions, timezone,
            case_id=case_id,
        )
        if case_id == "RG-07":
            _validate_rg07_contract(
                state,
                state_path,
                indexes,
                operations,
                current,
                precisions,
                timezone,
            )
        if case_id == "RG-08":
            _validate_rg08_contract(
                state, state_path, indexes, operations, precisions
            )
        _validate_periodic_allocations(
            state, state_path, indexes, current, precisions, timezone
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
    if case_id == "RG-08" and case["case"]["approval_status"] == "approved":
        _validate_rg08_inventory(case)

    # D-065 projection and diagnostic shapes are frozen, but fingerprint action surfaces
    # remain outside this prototype until fixture and operation gates open together.
