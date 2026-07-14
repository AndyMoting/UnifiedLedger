from __future__ import annotations

import json
from pathlib import Path
from typing import Any


class GoldenCaseError(ValueError):
    pass


def load_golden_case(path: str | Path) -> dict[str, Any]:
    case_path = Path(path)
    try:
        value = json.loads(case_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise GoldenCaseError(f"cannot load golden case {case_path}: {error}") from error

    if not isinstance(value, dict):
        raise GoldenCaseError("golden case root must be an object")
    schema_version = value.get("schema_version")
    if schema_version != 1:
        raise GoldenCaseError(f"unsupported schema_version: {schema_version}")
    return value
