"""Decide whether a landed merge on main needs a full CI re-run (D-196, D-302).

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

Squash evidence (D-302, T3): a squash merge has one parent, so the criterion
above cannot see it. `--squash-evidence` judges such a commit from records that
cannot drift - the pull request GitHub merged into it:

    the landed subject ends with " (#<number>)", or GitHub's
    commit-to-pull-request association names exactly one merged pull request
    whose merge_commit_sha is this commit
    that pull request's mergeCommit.oid == the landed commit
    that pull request is MERGED
    tree(pull request head) == tree(landed commit)
    every required check on that pull request is green (SUCCESS or SKIPPED,
    which is how a job-level skip reports and what branch protection accepts)

Every other outcome - no unambiguous pull request, unreadable API or commit,
missing required check, differing tree - returns 1, so the caller keeps
dispatching the full run. Nothing here trusts a caller-supplied SHA.

Exit codes:
    0  zero action: the merge criterion holds, or the squash evidence is complete
    1  needs dispatch: linear commit, differing trees, incomplete squash
       evidence, or an object that cannot be read (conservative fail-closed)
    2  input error: bad arguments, or an explicit parent triple that
       contradicts the commit object
    3  execution error: git itself failed (not a repository, git missing, ...)

Usage:
    python tools/ci/verify-merge-tree.py <merge-sha>
    python tools/ci/verify-merge-tree.py $(git rev-list --parents -n 1 HEAD)
    python tools/ci/verify-merge-tree.py --head <merge-sha> --base <p1> --tip <p2>
    python tools/ci/verify-merge-tree.py --squash-evidence [--landed <sha>]

The first form derives the parents from the commit object. The second passes
the raw `git rev-list --parents` output (one head SHA plus one or two parent
SHAs); the parents are cross-checked against the commit object. The third form
is the explicit triple, cross-checked the same way. The fourth form needs `gh`
and `GITHUB_REPOSITORY`; it never touches the network from the tests, which
patch `run_gh`.
"""

import argparse
import json
import os
import re
import subprocess
import sys

REQUIRED_CHECKS = ("Kotlin tests", "Android compile", "Python tests", "Trace scan")
# Branch protection accepts a skipped job as a satisfied required check, and a
# docs-only pull request legitimately skips the heavy ones.
PASSING_CONCLUSIONS = {"SUCCESS", "SKIPPED"}


def run_git(*args: str) -> tuple[int, str, str]:
    try:
        completed = subprocess.run(
            ["git", *args], capture_output=True, text=True, encoding="utf-8"
        )
    except OSError as error:  # git missing or not executable: fail closed
        return 1, "", str(error)
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


def run_gh(*args: str) -> tuple[int, str, str]:
    """Single seam for every `gh` call; the tests patch this function."""
    try:
        completed = subprocess.run(
            ["gh", *args], capture_output=True, text=True, encoding="utf-8"
        )
    except OSError as error:  # gh missing or not executable: fail closed
        return 1, "", str(error)
    return completed.returncode, completed.stdout.strip(), completed.stderr.strip()


def pull_request_number(landed: str, slug: str, subject: str) -> tuple[int, str]:
    """Resolve the pull request a landed squash commit came from.

    `gh pr merge --subject` writes the given subject verbatim, so the usual
    "subject ends with (#N)" suffix only exists when the message was generated
    by GitHub. The fallback is GitHub's own commit-to-pull-request association,
    accepted only when exactly one merged pull request points at this commit.
    """
    match = re.search(r"\(#(\d+)\)\s*$", subject)
    if match:
        return int(match.group(1)), ""

    code, out, err = run_gh("api", f"repos/{slug}/commits/{landed}/pulls")
    if code != 0:
        return 0, f"cannot read the commit-to-pull-request association: {err or code}"
    try:
        associated = json.loads(out)
    except (ValueError, TypeError):
        return 0, "unreadable commit-to-pull-request association"
    if not isinstance(associated, list):
        return 0, "unreadable commit-to-pull-request association"
    merged = [
        pull for pull in associated
        if isinstance(pull, dict)
        and (pull.get("merge_commit_sha") or "") == landed
        and pull.get("merged_at")
    ]
    if len(merged) != 1:
        return 0, (
            "the landed commit is associated with "
            f"{len(merged)} merged pull requests"
        )
    number = merged[0].get("number")
    if type(number) is not int or number <= 0:
        return 0, "the associated pull request has no usable number"
    return number, ""


def squash_evidence(landed: str) -> tuple[int, str]:
    """Judge a one-parent landing from the pull request GitHub merged into it."""
    code, resolved, err = run_git("rev-parse", "--verify", f"{landed}^{{commit}}")
    if code != 0 or not re.fullmatch(r"[0-9a-f]{40}", resolved or ""):
        return 1, f"needs-dispatch: cannot resolve {landed!r} to a commit: {err}"
    landed = resolved

    actual = parents_of(landed)
    if actual is None:
        return 1, f"needs-dispatch: cannot read commit {landed}"
    if len(actual) != 1:
        return 1, (
            f"needs-dispatch: {landed} has {len(actual)} parents; squash evidence "
            "applies to a one-parent landing only"
        )

    slug = os.environ.get("GITHUB_REPOSITORY", "").strip()
    if not slug:
        return 1, "needs-dispatch: GITHUB_REPOSITORY is not set"

    code, subject, err = run_git("log", "-1", "--format=%s", landed)
    if code != 0:
        return 1, f"needs-dispatch: cannot read the subject of {landed}: {err}"

    number, problem = pull_request_number(landed, slug, subject)
    if not number:
        return 1, f"needs-dispatch: {problem}"

    code, out, err = run_gh(
        "pr", "view", str(number), "--repo", slug,
        "--json", "mergeCommit,headRefOid,state,statusCheckRollup",
    )
    if code != 0:
        return 1, f"needs-dispatch: cannot read pull request #{number}: {err or code}"
    try:
        pull = json.loads(out)
    except (ValueError, TypeError):
        return 1, f"needs-dispatch: unreadable pull request payload for #{number}"

    merged_oid = (pull.get("mergeCommit") or {}).get("oid", "")
    if merged_oid != landed:
        return 1, (
            f"needs-dispatch: pull request #{number} merged as {merged_oid or 'none'}, "
            f"not as the landed commit {landed}"
        )
    if pull.get("state") != "MERGED":
        return 1, f"needs-dispatch: pull request #{number} is {pull.get('state')!r}"

    head_oid = pull.get("headRefOid", "")
    if not re.fullmatch(r"[0-9a-f]{40}", head_oid):
        return 1, f"needs-dispatch: pull request #{number} has no usable head commit"

    code, out, err = run_gh("api", f"repos/{slug}/git/commits/{head_oid}", "--jq", ".tree.sha")
    head_tree = out.strip() if code == 0 else ""
    if not re.fullmatch(r"[0-9a-f]{40}", head_tree):
        return 1, (
            f"needs-dispatch: cannot read the tree of pull request #{number} head "
            f"{head_oid}: {err or code}"
        )
    landed_tree = tree_of(landed)
    if landed_tree is None:
        return 1, f"needs-dispatch: cannot read the tree of {landed}"
    if head_tree != landed_tree:
        return 1, (
            f"needs-dispatch: tree({landed}) == {landed_tree} but the pull request "
            f"head tree is {head_tree}"
        )

    conclusions: dict[str, list[str]] = {}
    for entry in pull.get("statusCheckRollup") or []:
        if not isinstance(entry, dict):
            continue
        name = str(entry.get("name") or entry.get("context") or "")
        conclusions.setdefault(name, []).append(str(entry.get("conclusion") or "").upper())
    # Every entry for a required name must pass: a duplicate name must not be
    # able to shadow a failing conclusion behind a later successful one.
    missing = [
        name for name in REQUIRED_CHECKS
        if not conclusions.get(name)
        or any(value not in PASSING_CONCLUSIONS for value in conclusions[name])
    ]
    if missing:
        detail = ", ".join(
            f"{name}={'/'.join(conclusions[name]) if conclusions.get(name) else 'absent'}"
            for name in missing
        )
        return 1, f"needs-dispatch: required checks are not green on #{number}: {detail}"

    return 0, (
        f"zero-action: squash #{number} landed as {landed} with "
        f"tree == verified pull-request head tree {head_tree} and all required "
        "checks green"
    )


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
    parser.add_argument(
        "--squash-evidence",
        action="store_true",
        help="judge a one-parent landing from the pull request GitHub merged into it",
    )
    parser.add_argument(
        "--landed",
        default="HEAD",
        help="landed commit for --squash-evidence (default: HEAD)",
    )
    args = parser.parse_args()

    if args.squash_evidence:
        if args.commits or args.head or args.base or args.tip:
            parser.error("--squash-evidence takes no merge arguments")
        code, verdict = squash_evidence(args.landed)
        print(verdict)
        return code

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
