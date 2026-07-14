from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class EvidenceOrigin(str, Enum):
    SOURCE_FACT = "source_fact"
    PARSER_FACT = "parser_fact"
    MANUAL_ANCHOR = "manual_anchor"
    CONSTRAINT_SOLVED = "constraint_solved"
    INFERRED = "inferred"
    SUGGESTION = "suggestion"


class Confidence(str, Enum):
    HIGH = "high"
    MEDIUM = "medium"
    LOW = "low"


@dataclass(frozen=True, slots=True)
class Evidence:
    origin: EvidenceOrigin
    confidence: Confidence
    description: str
    source_refs: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if not self.description.strip():
            raise ValueError("evidence description is required")
