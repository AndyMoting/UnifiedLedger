import re
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree


FORMAL_DOCUMENTS = (
    "README.md",
    "docs/PROJECT_CHARTER.md",
    "docs/PRODUCT_REQUIREMENTS.md",
    "docs/ACCOUNTING_RULES.md",
    "docs/ARCHITECTURE.md",
    "docs/DECISIONS.md",
    "docs/ROADMAP.md",
    "docs/CURRENT_STATE.md",
    "docs/GOLDEN_TESTS.md",
    "docs/CONTRIBUTING.md",
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


TEST_EVIDENCE_DOCUMENT = "docs/CURRENT_STATE.md"
TEST_EVIDENCE_CLAIM = re.compile(r"`(ledger-[a-z-]+)`\s*(\d+)\s*项")
TEST_EVIDENCE_CLEAN_CLAIM = "零 failure、零 error"


@dataclass(frozen=True)
class ValidationIssue:
    code: str
    path: str
    message: str


@dataclass(frozen=True)
class ModuleTestTotals:
    tests: int
    failures: int
    errors: int


def _read_jvm_test_totals(root: Path, module: str) -> ModuleTestTotals | None:
    """Sum the saved JVM test reports for one module, or None when none are saved."""
    reports = sorted((root / module / "build" / "test-results" / "jvmTest").glob("*.xml"))
    if not reports:
        return None
    tests = failures = errors = 0
    for report in reports:
        try:
            suite = ElementTree.parse(report).getroot()
        except ElementTree.ParseError:
            return None
        tests += int(suite.get("tests", 0))
        failures += int(suite.get("failures", 0))
        errors += int(suite.get("errors", 0))
    return ModuleTestTotals(tests, failures, errors)


def _validate_test_evidence(root: Path, text: str) -> list[ValidationIssue]:
    """Check the recorded per-module test counts against the saved reports.

    The counts are hand-written verification evidence, so they drift silently as tests
    are added. A checkout that has never run Gradle has no reports to compare against,
    and is skipped rather than failed.
    """
    issues: list[ValidationIssue] = []
    claims_clean = TEST_EVIDENCE_CLEAN_CLAIM in text
    for module, claimed_text in TEST_EVIDENCE_CLAIM.findall(text):
        actual = _read_jvm_test_totals(root, module)
        if actual is None:
            continue
        claimed = int(claimed_text)
        if claimed != actual.tests:
            issues.append(
                ValidationIssue(
                    "stale-test-evidence",
                    TEST_EVIDENCE_DOCUMENT,
                    f"{module} records {claimed} tests but the saved reports have {actual.tests}",
                )
            )
        if claims_clean and (actual.failures or actual.errors):
            issues.append(
                ValidationIssue(
                    "stale-test-evidence",
                    TEST_EVIDENCE_DOCUMENT,
                    f"{module} is recorded as clean but the saved reports have "
                    f"{actual.failures} failures and {actual.errors} errors",
                )
            )
    return issues


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

    for relative_path, _, text in documents:
        if relative_path == TEST_EVIDENCE_DOCUMENT:
            issues.extend(_validate_test_evidence(root, text))

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
