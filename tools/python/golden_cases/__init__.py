"""Language-neutral golden case loading and validation tools."""

from importlib import import_module

from .loader import GoldenCaseError, load_golden_case
from .validator import (
    assert_expected_balances,
    replay_balances,
    validate_case_envelope,
    validate_transactions,
)


_V2_EXPORTS = {
    "deterministic_v2_id",
    "load_golden_case_v2",
    "validate_golden_case_v2",
}


def __getattr__(name: str):
    if name not in _V2_EXPORTS:
        raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
    try:
        v2 = import_module(".v2", __name__)
    except ModuleNotFoundError as error:
        raise ImportError(
            "Golden Schema v2 APIs require the jsonschema 4.x development dependency"
        ) from error
    value = getattr(v2, name)
    globals()[name] = value
    return value

__all__ = [
    "GoldenCaseError",
    "assert_expected_balances",
    "deterministic_v2_id",
    "load_golden_case",
    "load_golden_case_v2",
    "replay_balances",
    "validate_case_envelope",
    "validate_golden_case_v2",
    "validate_transactions",
]
