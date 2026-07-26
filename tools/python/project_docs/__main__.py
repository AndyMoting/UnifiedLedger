import argparse
from pathlib import Path

from .validator import validate_formal_docs, validate_test_evidence


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", default=".")
    parser.add_argument(
        "--check-test-evidence",
        action="store_true",
        help=(
            "also check the test counts recorded in the current state document against the "
            "saved JVM reports; run only straight after a full suite, because a focused run "
            "leaves partial reports behind"
        ),
    )
    args = parser.parse_args()
    root = Path(args.root).resolve()
    issues = validate_formal_docs(root)
    if args.check_test_evidence:
        issues = issues + validate_test_evidence(root)
    for issue in issues:
        print(f"{issue.path}: {issue.code}: {issue.message}")
    return 1 if issues else 0


if __name__ == "__main__":
    raise SystemExit(main())
