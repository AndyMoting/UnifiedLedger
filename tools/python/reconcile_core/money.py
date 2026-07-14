from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
import re


_CENT = Decimal("0.01")
_AMOUNT_PATTERN = re.compile(r"-?\d+(?:\.\d+)?")


def parse_decimal(value: str | int | Decimal) -> Decimal:
    """Parse an exact decimal from common exported amount text."""
    if isinstance(value, float):
        raise TypeError("binary floating-point values are not accepted")
    if isinstance(value, Decimal):
        return value
    if isinstance(value, int):
        return Decimal(value)

    text = str(value).strip().replace(",", "")
    try:
        return Decimal(text)
    except InvalidOperation:
        match = _AMOUNT_PATTERN.search(text)
        if not match:
            raise ValueError(f"invalid amount: {value!r}") from None
        return Decimal(match.group(0))


@dataclass(frozen=True, slots=True)
class Money:
    amount: Decimal
    currency: str

    def __post_init__(self) -> None:
        if isinstance(self.amount, float):
            raise TypeError("binary floating-point values are not accepted")
        amount = parse_decimal(self.amount).quantize(_CENT, rounding=ROUND_HALF_UP)
        if amount < 0:
            raise ValueError("money amount must be nonnegative")
        currency = str(self.currency).strip().upper()
        if not currency:
            raise ValueError("currency is required")
        object.__setattr__(self, "amount", amount)
        object.__setattr__(self, "currency", currency)

    @classmethod
    def of(cls, amount: str | int | Decimal, currency: str) -> "Money":
        return cls(amount=parse_decimal(amount), currency=currency)

    def __add__(self, other: object) -> "Money":
        if not isinstance(other, Money):
            return NotImplemented
        if self.currency != other.currency:
            raise ValueError("cannot add money with different currencies")
        return Money.of(self.amount + other.amount, self.currency)
