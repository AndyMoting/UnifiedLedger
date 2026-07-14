from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class RecordEffect(str, Enum):
    EFFECTIVE = "effective"
    IGNORED = "ignored"


@dataclass(frozen=True, slots=True)
class StatusDecision:
    effect: RecordEffect
    reason_code: str


_IGNORED_STATUS_WORDS = {
    "等待付款": "pending_payment",
    "交易失败": "transaction_failed",
    "还款失败": "repayment_failed",
    "代付失败": "delegated_payment_failed",
}


def evaluate_record_status(*, platform: str, status: str, payment_method: str) -> StatusDecision:
    normalized_status = str(status).strip()
    for word, reason_code in _IGNORED_STATUS_WORDS.items():
        if word in normalized_status:
            return StatusDecision(RecordEffect.IGNORED, reason_code)

    normalized_platform = str(platform).strip().lower()
    if normalized_platform in {"alipay", "支付宝"}:
        if "交易关闭" in normalized_status and not str(payment_method).strip():
            return StatusDecision(RecordEffect.IGNORED, "closed_without_payment")

    return StatusDecision(RecordEffect.EFFECTIVE, "source_record_effective")
