from __future__ import annotations

from collections import defaultdict
from decimal import Decimal, InvalidOperation
import re
from typing import Any

from .loader import GoldenCaseError


_AMOUNT_PATTERN = re.compile(r"^-?(?:0|[1-9]\d*)\.\d{2}$")


def _amount(value: Any, path: str) -> Decimal:
    if not isinstance(value, str) or not _AMOUNT_PATTERN.fullmatch(value):
        raise GoldenCaseError(f"{path} must be an exact two-decimal string")
    try:
        return Decimal(value)
    except InvalidOperation:
        raise GoldenCaseError(f"{path} is not a valid decimal") from None


def _require_unique_ids(items: list[dict[str, Any]], path: str) -> None:
    ids = [item.get("id") for item in items]
    if any(not isinstance(item_id, str) or not item_id for item_id in ids):
        raise GoldenCaseError(f"{path} contains a missing stable id")
    if len(ids) != len(set(ids)):
        raise GoldenCaseError(f"{path} contains duplicate stable ids")


def _validate_transaction(transaction: dict[str, Any], path: str) -> None:
    postings = transaction.get("postings")
    if not isinstance(postings, list) or len(postings) < 2:
        raise GoldenCaseError(f"{path} must contain at least two postings")
    _require_unique_ids(postings, f"{path}.postings")

    totals: dict[str, Decimal] = defaultdict(Decimal)
    for index, posting in enumerate(postings):
        currency = posting.get("currency")
        if not isinstance(currency, str) or not currency:
            raise GoldenCaseError(f"{path}.postings[{index}].currency is required")
        totals[currency] += _amount(
            posting.get("amount"),
            f"{path}.postings[{index}].amount",
        )

    for currency, total in totals.items():
        if total != Decimal("0.00"):
            raise GoldenCaseError(f"{path} is not balanced for {currency}: {total:.2f}")


def validate_case_envelope(case: dict[str, Any]) -> None:
    if case.get("schema_version") != 1:
        raise GoldenCaseError(f"unsupported schema_version: {case.get('schema_version')}")

    metadata = case.get("case", {})
    case_id = metadata.get("id")
    if not isinstance(case_id, str) or not re.fullmatch(r"RG-\d{2}", case_id):
        raise GoldenCaseError("case.id must use the RG-00 form")
    if metadata.get("level") not in {"core_required", "core_reserved"}:
        raise GoldenCaseError("case.level must be a formal core level")
    currency = metadata.get("currency")
    if not isinstance(currency, str) or not currency:
        raise GoldenCaseError("case.currency is required")
    if metadata.get("precision") != 2:
        raise GoldenCaseError("schema v1 requires two-decimal amounts")

    catalog = case.get("catalog", {})
    _require_unique_ids(catalog.get("accounts", []), "catalog.accounts")
    _require_unique_ids(catalog.get("categories", []), "catalog.categories")


def validate_transactions(
    transactions: list[dict[str, Any]],
    accounts: list[dict[str, Any]],
) -> None:
    _require_unique_ids(transactions, "transactions")
    account_ids = {account["id"] for account in accounts}
    posting_ids: list[str] = []
    for transaction_index, transaction in enumerate(transactions):
        path = f"transactions[{transaction_index}]"
        _validate_transaction(transaction, path)
        for posting in transaction["postings"]:
            posting_ids.append(posting["id"])
            account_id = posting.get("account_id")
            if account_id not in account_ids:
                raise GoldenCaseError(f"posting references unknown account: {account_id}")
    if len(posting_ids) != len(set(posting_ids)):
        raise GoldenCaseError("transactions contain duplicate posting ids")


def replay_balances(transactions: list[dict[str, Any]]) -> dict[str, Decimal]:
    replay: dict[str, Decimal] = defaultdict(Decimal)
    for transaction in transactions:
        if not transaction.get("effective"):
            continue
        for index, posting in enumerate(transaction["postings"]):
            account_id = posting.get("account_id")
            replay[account_id] += _amount(
                posting.get("amount"),
                f"{transaction['id']}.postings[{index}].amount",
            )
    return dict(replay)


def assert_expected_balances(
    actual_balances: dict[str, Decimal],
    expected_balances: dict[str, Any],
) -> None:
    for account_id, expected_text in expected_balances.items():
        expected = _amount(expected_text, f"expected_balances.{account_id}")
        actual = actual_balances.get(account_id, Decimal("0.00"))
        if actual != expected:
            raise GoldenCaseError(
                f"{account_id} balance must replay to {actual:.2f}, got {expected:.2f}"
            )
