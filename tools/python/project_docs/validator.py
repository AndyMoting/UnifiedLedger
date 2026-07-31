import re
from dataclasses import dataclass
from pathlib import Path


FORMAL_DOCUMENTS = (
    "README.md",
    "docs/PROJECT_MAP.md",
    "docs/PROJECT_CHARTER.md",
    "docs/PRODUCT_REQUIREMENTS.md",
    "docs/ACCOUNTING_RULES.md",
    "docs/ARCHITECTURE.md",
    "docs/DECISIONS.md",
    "docs/ROADMAP.md",
    "docs/CURRENT_STATE.md",
    "docs/GOLDEN_TESTS.md",
    "docs/CONTRIBUTING.md",
    "docs/modules/ledger-domain.md",
    "docs/modules/ledger-application.md",
    "docs/modules/ledger-data.md",
)

PROHIBITED_REFERENCES = (
    "Tal" + "ly",
    "Ji" + "Zhang",
    "XX" + "JZ",
    "小" + "星",
    "Gnu" + "Cash",
    "Mi" + "uix",
)

ASSISTANT_TRACES = (
    "Code" + "x",
    "Clau" + "de",
    "Chat" + "GPT",
    "Open" + "AI",
    "Co" + "pilot",
    "Gem" + "ini",
    "Co-authored-" + "by",
    "Generated-" + "by",
    "Assisted-" + "by",
)


@dataclass(frozen=True)
class ValidationIssue:
    code: str
    path: str
    message: str


def validate_formal_docs(root: Path) -> list[ValidationIssue]:
    """Validate only the formal tracked project documents."""
    root = root.resolve()
    issues: list[ValidationIssue] = []
    documents: list[tuple[str, Path, str]] = []

    for relative_path in FORMAL_DOCUMENTS:
        path = root / relative_path
        if not path.is_file():
            issues.append(
                ValidationIssue(
                    "missing-document", relative_path, "required document is missing"
                )
            )
            continue
        documents.append((relative_path, path, path.read_text(encoding="utf-8")))

    decision_text = next(
        (text for relative, _, text in documents if relative == "docs/DECISIONS.md"),
        "",
    )
    decision_ids = re.findall(r"(?m)^##\s+(D-\d{3})\b", decision_text)
    decision_tokens = re.findall(r"(?m)^##\s+(D-[^\s]+)", decision_text)
    for decision_token in decision_tokens:
        if not re.fullmatch(r"D-\d{3}", decision_token):
            issues.append(
                ValidationIssue(
                    "invalid-decision-id",
                    "docs/DECISIONS.md",
                    f"invalid decision id: {decision_token}",
                )
            )
    seen: set[str] = set()
    for decision_id in decision_ids:
        if decision_id in seen:
            issues.append(
                ValidationIssue(
                    "duplicate-decision-id",
                    "docs/DECISIONS.md",
                    f"duplicate decision id: {decision_id}",
                )
            )
        seen.add(decision_id)

    path_boundary = r"(?:^|[\s`\"'(])"
    absolute_paths = (
        re.compile(path_boundary + r"(?i:[a-z]):[\\/]"),
        re.compile(path_boundary + r"\\\\[^\\/\s]+[\\/][^\\/\s]+"),
        re.compile(path_boundary + r"/(?:Users|home)/[^/\s]+(?:/|$)"),
        re.compile(path_boundary + r"~[\\/]"),
    )
    unfinished = re.compile(r"(?i)\b(?:TODO|TBD)\b|待补充|待定")
    markdown_link = re.compile(r"\[[^\]]+\]\(([^)]+)\)")

    for relative_path, path, text in documents:
        folded_text = text.casefold()
        if any(name.casefold() in folded_text for name in PROHIBITED_REFERENCES):
            issues.append(
                ValidationIssue(
                    "prohibited-reference",
                    relative_path,
                    "external reference name found",
                )
            )
        if any(name.casefold() in folded_text for name in ASSISTANT_TRACES):
            issues.append(
                ValidationIssue(
                    "assistant-trace",
                    relative_path,
                    "development assistant trace found",
                )
            )
        if any(pattern.search(text) for pattern in absolute_paths):
            issues.append(
                ValidationIssue("absolute-path", relative_path, "machine path found")
            )
        if unfinished.search(text):
            issues.append(
                ValidationIssue(
                    "unfinished-marker", relative_path, "unfinished marker found"
                )
            )
        for raw_target in markdown_link.findall(text):
            target = raw_target.strip().strip("<>")
            target_lower = target.lower()
            if target.startswith("#") or target_lower.startswith(
                ("https://", "http://", "mailto:")
            ):
                continue
            target_path = target.split("#", 1)[0]
            if not target_path:
                continue
            resolved_target = (path.parent / target_path).resolve()
            try:
                resolved_target.relative_to(root)
            except ValueError:
                issues.append(
                    ValidationIssue(
                        "outside-root-link",
                        relative_path,
                        f"link target is outside repository: {target_path}",
                    )
                )
                continue
            if not resolved_target.exists():
                issues.append(
                    ValidationIssue(
                        "broken-link", relative_path, f"missing link target: {target_path}"
                    )
                )

    return issues
