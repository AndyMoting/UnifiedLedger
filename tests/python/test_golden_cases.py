import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from golden_cases import GoldenCaseError, load_golden_case


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
RG01_PATH = REPOSITORY_ROOT / "golden" / "rules" / "rg-01.json"


class GoldenCaseLoaderTests(unittest.TestCase):
    def test_loader_rejects_non_object_root(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "case.json"
            path.write_text("[]", encoding="utf-8")

            with self.assertRaisesRegex(GoldenCaseError, "root must be an object"):
                load_golden_case(path)

    def test_loader_rejects_unknown_schema(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "case.json"
            path.write_text(json.dumps({"schema_version": 2}), encoding="utf-8")

            with self.assertRaisesRegex(GoldenCaseError, "unsupported schema_version: 2"):
                load_golden_case(path)


class RG01FrozenAnswerTests(unittest.TestCase):
    def test_rg01_contains_the_approved_user_visible_results(self):
        case = load_golden_case(RG01_PATH)

        self.assertEqual(case["case"]["id"], "RG-01")
        self.assertEqual(case["case"]["level"], "core_required")
        self.assertEqual(case["case"]["timezone"], "Asia/Shanghai")
        self.assertEqual(case["create"]["request"]["amount"], "35.80")
        self.assertEqual(case["create"]["request"]["payment_account_id"], "asset-bank-a")
        self.assertEqual(case["create"]["request"]["category_id"], "expense-category-breakfast")
        self.assertEqual(case["create"]["expected"]["balances"]["asset-bank-a"], "964.20")
        self.assertEqual(case["create"]["expected"]["statistics"]["month_consumption"], "35.80")
        self.assertEqual(case["create"]["expected"]["reconciliation"]["transaction"], "pending")
        self.assertEqual(case["note_update"]["request"]["note"], "早餐")
        self.assertEqual(case["note_update"]["expected"]["funding_effect_count"], 1)
        self.assertEqual(case["idempotency"]["expected"]["new_transaction_count"], 0)
        self.assertEqual(case["idempotency"]["expected"]["new_posting_set_count"], 0)
        self.assertEqual(case["idempotency"]["expected"]["funding_effect_count"], 1)
        zero_amount = next(item for item in case["invalid_inputs"] if item["id"] == "zero-amount")
        self.assertEqual(zero_amount["expected"]["reason"], "must_be_positive")
        self.assertEqual(len(case["invalid_inputs"]), 4)


if __name__ == "__main__":
    unittest.main()
