from dataclasses import FrozenInstanceError
from datetime import datetime, timezone
from decimal import Decimal
import unittest

from reconcile_core.evidence import Confidence, Evidence, EvidenceOrigin
from reconcile_core.models import Direction, SourceKind, TransactionFact
from reconcile_core.money import Money, parse_decimal
from reconcile_core.status import RecordEffect, evaluate_record_status


class MoneyTests(unittest.TestCase):
    def test_parses_exported_amount_text_without_binary_floating_point(self):
        self.assertEqual(parse_decimal("CNY 1,234.567"), Decimal("1234.567"))
        self.assertEqual(Money.of("1.005", "cny").amount, Decimal("1.01"))
        self.assertEqual(Money.of("1.005", "cny").currency, "CNY")

    def test_rejects_float_and_negative_money(self):
        with self.assertRaises(TypeError):
            Money.of(0.1, "CNY")
        with self.assertRaises(ValueError):
            Money.of("-0.01", "CNY")
        with self.assertRaises(ValueError):
            Money(Decimal("-0.01"), "CNY")

    def test_rejects_amounts_with_unrelated_surrounding_text(self):
        for value in (
            "oops 1.23 junk", "1.23 USD extra", "1.2 3.4", "bad 1.23",
            "fee 12.34", "abc 4.56", "USD 1.23", "1234,567", "CNY 1234,567",
            "1,23.45", "1,234,56", "CNY 1.23 4.56",
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_decimal(value)

    def test_preserves_exact_plain_numbers_and_well_formed_export_groups(self):
        for value, expected in (
            (".5", ".5"), ("1.", "1"), ("+1.25", "1.25"), ("-1e-3", "-.001"),
            ("1,234,567.89", "1234567.89"), (" CNY -1,234.50 ", "-1234.50"),
            ("CNY 1234.50", "1234.50"),
        ):
            with self.subTest(value=value):
                self.assertEqual(parse_decimal(value), Decimal(expected))

    def test_rejects_nonfinite_text_and_decimal_values(self):
        for text in ("NaN", "sNaN", "Infinity", "-Infinity"):
            for value in (text, Decimal(text)):
                with self.subTest(value=value), self.assertRaises(ValueError):
                    parse_decimal(value)

    def test_money_arithmetic_requires_matching_currency(self):
        self.assertEqual(Money.of("1.20", "CNY") + Money.of("2.30", "CNY"), Money.of("3.50", "CNY"))
        with self.assertRaises(ValueError):
            _ = Money.of("1.00", "CNY") + Money.of("1.00", "USD")


class ModelAndEvidenceTests(unittest.TestCase):
    def test_transaction_fact_is_immutable_and_keeps_source_evidence(self):
        evidence = Evidence(
            origin=EvidenceOrigin.SOURCE_FACT,
            confidence=Confidence.HIGH,
            description="synthetic bank statement",
            source_refs=("source-001",),
        )
        fact = TransactionFact(
            fact_id="fact-001",
            source_id="source-001",
            account_id="bank-a",
            platform="bank",
            occurred_at=datetime(2026, 1, 2, 3, 4, tzinfo=timezone.utc),
            direction=Direction.OUT,
            amount=Money.of("12.34", "CNY"),
            source_kind=SourceKind.BANK_STATEMENT,
            evidence=evidence,
            order_id="order-001",
        )

        self.assertEqual(fact.amount.amount, Decimal("12.34"))
        self.assertEqual(fact.evidence.origin, EvidenceOrigin.SOURCE_FACT)
        with self.assertRaises(FrozenInstanceError):
            fact.account_id = "wallet-a"

    def test_transaction_fact_requires_timezone_and_stable_ids(self):
        common = dict(
            source_id="source-001",
            account_id="wallet-a",
            platform="wallet",
            occurred_at=datetime(2026, 1, 2, 3, 4),
            direction=Direction.IN,
            amount=Money.of("1.00", "CNY"),
            source_kind=SourceKind.PLATFORM_EXPORT,
            evidence=Evidence(
                origin=EvidenceOrigin.PARSER_FACT,
                confidence=Confidence.HIGH,
                description="synthetic platform export",
            ),
        )
        with self.assertRaises(ValueError):
            TransactionFact(fact_id="fact-001", **common)
        with self.assertRaises(ValueError):
            TransactionFact(fact_id="", **{**common, "occurred_at": datetime.now(timezone.utc)})


class StatusTests(unittest.TestCase):
    def test_failed_pending_and_unpaid_closed_records_have_no_effect(self):
        for status in ("等待付款", "交易失败", "还款失败", "代付失败"):
            with self.subTest(status=status):
                decision = evaluate_record_status(platform="wallet", status=status, payment_method="balance")
                self.assertEqual(decision.effect, RecordEffect.IGNORED)

        closed = evaluate_record_status(platform="alipay", status="交易关闭", payment_method="")
        self.assertEqual(closed.effect, RecordEffect.IGNORED)
        self.assertEqual(closed.reason_code, "closed_without_payment")

    def test_success_and_closed_record_with_payment_evidence_are_effective(self):
        success = evaluate_record_status(platform="wallet", status="支付成功", payment_method="balance")
        closed_paid = evaluate_record_status(platform="alipay", status="交易关闭", payment_method="balance")
        self.assertEqual(success.effect, RecordEffect.EFFECTIVE)
        self.assertEqual(closed_paid.effect, RecordEffect.EFFECTIVE)


if __name__ == "__main__":
    unittest.main()
