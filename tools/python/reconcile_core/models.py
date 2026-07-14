from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import Enum

from .evidence import Evidence
from .money import Money


class Direction(str, Enum):
    OUT = "out"
    IN = "in"
    NEUTRAL = "neutral"


class SourceKind(str, Enum):
    PLATFORM_EXPORT = "platform_export"
    BANK_STATEMENT = "bank_statement"
    MANUAL = "manual"
    IMPORT = "import"


@dataclass(frozen=True, slots=True)
class TransactionFact:
    fact_id: str
    source_id: str
    account_id: str
    platform: str
    occurred_at: datetime
    direction: Direction
    amount: Money
    source_kind: SourceKind
    evidence: Evidence
    status: str = ""
    payment_method: str = ""
    order_id: str = ""
    merchant_order_id: str = ""

    def __post_init__(self) -> None:
        for field_name in ("fact_id", "source_id", "account_id", "platform"):
            if not str(getattr(self, field_name)).strip():
                raise ValueError(f"{field_name} is required")
        if self.occurred_at.tzinfo is None or self.occurred_at.utcoffset() is None:
            raise ValueError("occurred_at must be timezone-aware")
