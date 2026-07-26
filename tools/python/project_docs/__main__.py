import argparse
from pathlib import Path

from .validator import validate_formal_docs


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", default=".")
    args = parser.parse_args()
    issues = validate_formal_docs(Path(args.root).resolve())
    for issue in issues:
        print(f"{issue.path}: {issue.code}: {issue.message}")
    return 1 if issues else 0


if __name__ == "__main__":
    raise SystemExit(main())
