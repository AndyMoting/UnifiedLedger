"""Decide whether a landed merge on main needs a full CI re-run (D-196).

Single-pass CI (D-190) verifies the pull-request preview merge tree, so the
release evidence is "merge tree == PR tip tree, byte for byte". This script is
the single implementation of that criterion: a commit M landed on main is
equivalent to its verified run exactly when

    tree(M) == tree(M^2)

i.e. the merge introduced nothing beyond the tip it merged (its second parent).
A different tree (stale base, conflict resolution), a linear commit that was
never verified by a pull-request run, or an unreadable object conservatively
require a full re-run; the caller (.github/workflows/merge-tree-guard.yml)
dispatches `ci.yml --ref main` in that case. The comparison baseline is always
the merge commit's own recorded second parent, never an externally supplied
"PR head" tree, which can drift after the merge and misfire.

Exit codes:
    0  zero action: merge commit whose tree equals its second parent's tree
    1  needs dispatch: linear commit, differing trees, or an object that cannot
       be read (conservative fail-closed; the workflow runs the full suite)
    2  input error: bad arguments, or an explicit parent triple that
       contradicts the commit object
    3  execution error: git itself failed (not a repository, git missing, ...)

Usage:
    python tools/ci/verify-merge-tree.py <merge-sha>
    python tools/ci/verify-merge-tree.py $(git rev-list --parents -n 1 HEAD)
    python tools/ci/verify-merge-tree.py --head <merge-sha> --base <p1> --tip <p2>

The first form derives the parents from the commit object. The second passes
the raw `git rev-list --parents` output (one head SHA plus one or two parent
SHAs); the parents are cross-checked against the commit object. The third form
is the explicit triple, cross-checked the same way.
"""

import argparse
import subprocess
import sys


def run_git(*args: str) -> tuple[int, str, str]:
    completed = subprocess.run(
        ["git", *args], capture_output=True, text=True, encoding="utf-8"
    )
    return completed.returncode, completed.stdout.strip(), completed.stderr.strip()


def git_environment_ok() -> bool:
    code, _out, _err = run_git("rev-parse", "--git-dir")
    return code == 0


def parents_of(commit: str) -> list[str] | None:
    """Return the commit's parent list, or None when the object is unreadable."""
    code, out, _err = run_git("rev-list", "--parents", "-n", "1", commit)
    if code != 0:
        return None
    tokens = out.split()
    if not tokens:
        return None
    return tokens[1:]


def tree_of(commit: str) -> str | None:
    code, out, _err = run_git("rev-parse", f"{commit}^{{tree}}")
    if code != 0:
        return None
    return out or None


def classify(head: str, claimed_parents: tuple[str, ...] | None) -> tuple[int, str]:
    """Apply the criterion once. Returns (exit code, human-readable verdict)."""
    if not git_environment_ok():
        return 3, "execution error: git is not usable in this directory"

    actual = parents_of(head)
    if actual is None:
        return 1, (
            f"needs-dispatch: cannot read commit {head}; "
            "conservatively requesting a full run"
        )

    if claimed_parents is not None and tuple(actual) != claimed_parents:
        return 2, (
            f"input error: claimed parents {list(claimed_parents)} of {head} "
            f"contradict the commit object {actual}"
        )

    if len(actual) < 2:
        return 1, (
            f"needs-dispatch: {head} is not a merge commit "
            f"(parents: {actual or 'none'}); a commit without a verified "
            "pull-request run requires a full run"
        )

    second = actual[1]
    head_tree = tree_of(head)
    second_tree = tree_of(second)
    if head_tree is None or second_tree is None:
        return 1, (
            f"needs-dispatch: cannot read the tree of {head} or {second}; "
            "conservatively requesting a full run"
        )

    if head_tree == second_tree:
        return 0, (
            f"zero-action: tree({head}) == tree({second}) == {head_tree}; "
            "the merge introduced nothing beyond the verified pull-request tip"
        )
    return 1, (
        f"needs-dispatch: tree({head}) == {head_tree} but tree({second}) == "
        f"{second_tree}; the merge introduced content beyond the verified "
        "pull-request tip (stale base or conflict resolution)"
    )


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "commits",
        nargs="*",
        help="a merge SHA, or the whitespace-separated output of "
        "`git rev-list --parents -n 1 <sha>`",
    )
    parser.add_argument("--head", default="", help="candidate merge commit")
    parser.add_argument("--base", default="", help="expected first parent")
    parser.add_argument("--tip", default="", help="expected second parent")
    args = parser.parse_args()

    if args.commits and (args.head or args.base or args.tip):
        parser.error("pass either positional commits or --head/--base/--tip, not both")
    if args.commits:
        if len(args.commits) > 3:
            parser.error("expected a merge SHA or `rev-list --parents` output (<= 3 SHAs)")
        head = args.commits[0]
        claimed = tuple(args.commits[1:]) or None
    elif args.head:
        if bool(args.base) != bool(args.tip):
            parser.error("--base and --tip must be given together")
        head = args.head
        claimed = (args.base, args.tip) if args.base else None
    else:
        parser.error("give a merge SHA, rev-list --parents output, or --head")

    code, verdict = classify(head, claimed)
    print(verdict)
    return code


if __name__ == "__main__":
    sys.exit(main())
