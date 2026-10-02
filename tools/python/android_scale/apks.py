"""Whole-tree APK bundles with independently checked GitHub provenance.

Only an absent/expired artifact is a cache miss. A discovered bundle that fails
identity, producer or byte checks is an error, never a silent rebuild.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

from .result import strict_json

FILES = {"app": "android-app-debug.apk", "test": "android-app-debug-androidTest.apk"}
PRODUCERS = {".github/workflows/ci.yml": "Android compile",
             ".github/workflows/android-scale.yml": "Android scale APKs",
             ".github/workflows/android-preflight.yml": "Android preflight APKs"}


class ArtifactUnavailable(RuntimeError):
    """The server reports a deleted or expired artifact, not corrupt content."""


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True).strip()


def api(path: str, *, binary: bool = False):
    try:
        value = subprocess.check_output(["gh", "api", path], stderr=subprocess.PIPE, timeout=60)
    except subprocess.CalledProcessError as error:
        if binary and re.search(rb"\(HTTP (404|410)\)", error.stderr or b""):
            raise ArtifactUnavailable("artifact expired or removed during download") from None
        raise
    return value if binary else json.loads(value)


def pages(path: str, key: str | None):
    separator = "&" if "?" in path else "?"
    for page in range(1, 101):
        response = api(f"{path}{separator}per_page=100&page={page}")
        data = response[key] if key is not None else response
        if not isinstance(data, list):
            raise ValueError("invalid paginated GitHub response")
        yield from data
        if len(data) < 100:
            return
    raise ValueError("artifact discovery pagination exceeded safety bound")


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def check_bundle(directory: Path, expected_tree: str) -> dict:
    manifest = strict_json(directory / "provenance.json")
    if type(manifest.get("schema")) is not int or manifest.get("schema") != 1 or manifest.get("tree") != expected_tree:
        raise ValueError("APK source tree mismatch")
    if {p.name for p in directory.iterdir()} != {*FILES.values(), "provenance.json"}:
        raise ValueError("APK bundle missing files or contains unexpected members")
    if set(manifest.get("sha256", {})) != set(FILES):
        raise ValueError("APK digest manifest incomplete")
    for role, filename in FILES.items():
        path = directory / filename
        if not path.is_file() or path.is_symlink() or path.stat().st_size == 0 or digest(path) != manifest["sha256"][role]:
            raise ValueError("APK digest mismatch: " + role)
    return manifest


def check_source(manifest: dict, repository: str, artifact_run: int) -> None:
    if manifest.get("repository") != repository or manifest.get("run_id") != artifact_run:
        raise ValueError("APK source run/repository mismatch")
    run_id, attempt = manifest["run_id"], manifest.get("run_attempt")
    if type(run_id) is not int or type(attempt) is not int or run_id <= 0 or attempt <= 0:
        raise ValueError("invalid producer run/attempt")
    run = api(f"repos/{repository}/actions/runs/{run_id}/attempts/{attempt}")
    workflow = run.get("path", "").split("@", 1)[0]
    if workflow not in PRODUCERS or manifest.get("workflow") != workflow:
        raise ValueError("untrusted APK producer workflow")
    if run.get("repository", {}).get("full_name") != repository or run.get("head_repository", {}).get("full_name") != repository:
        raise ValueError("fork or foreign APK producer")
    if run.get("run_attempt") != attempt or run.get("id") != run_id:
        raise ValueError("producer attempt mismatch")
    source = manifest.get("source_sha", "")
    if not re.fullmatch(r"[0-9a-f]{40}", source):
        raise ValueError("invalid source commit")
    commit = api(f"repos/{repository}/git/commits/{source}")
    if commit.get("sha") != source or commit.get("tree", {}).get("sha") != manifest["tree"]:
        raise ValueError("GitHub commit/tree binding mismatch")
    # GitHub can clear run.pull_requests after merge. Bind the event's historical
    # parents to the actual preview commit, then corroborate its PR association
    # independently; a PR's current branch SHAs may have advanced meanwhile.
    if run.get("event") == "pull_request":
        parents = [entry["sha"] for entry in commit.get("parents", [])]
        historical = manifest.get("pull_request", {})
        if not isinstance(historical, dict):
            raise ValueError("invalid historical PR event identity")
        number, base, head = (historical.get(key) for key in ("number", "base_sha", "head_sha"))
        if (type(number) is not int or number <= 0 or not isinstance(base, str) or not isinstance(head, str)
                or not re.fullmatch(r"[0-9a-f]{40}", base) or not re.fullmatch(r"[0-9a-f]{40}", head)
                or parents != [base, head] or head != run.get("head_sha")):
            raise ValueError("unbound PR preview merge")
        associated = [pr for pr in pages(f"repos/{repository}/commits/{head}/pulls", None) if pr.get("number") == number]
        if len(associated) != 1:
            raise ValueError("historical source commit is not associated with the recorded PR")
        pr = associated[0]
        if (pr.get("base", {}).get("ref") != "main"
                or any(pr.get(side, {}).get("repo", {}).get("full_name") != repository for side in ("head", "base"))):
            raise ValueError("fork, foreign repository or wrong base in recorded PR association")
        if pr.get("merged_at") is not None and pr.get("head", {}).get("sha") == head:
            merged_sha = pr.get("merge_commit_sha", "")
            if not isinstance(merged_sha, str) or not re.fullmatch(r"[0-9a-f]{40}", merged_sha):
                raise ValueError("merged PR lacks immutable merge identity")
            merged = api(f"repos/{repository}/git/commits/{merged_sha}")
            merged_parents = [entry["sha"] for entry in merged.get("parents", [])]
            if merged.get("sha") != merged_sha or (len(merged_parents) == 2 and merged_parents[1] != head):
                raise ValueError("actual merged PR does not corroborate historical head")
    elif run.get("event") == "workflow_dispatch":
        if source != run.get("head_sha"):
            raise ValueError("manual producer checkout mismatch")
    else:
        raise ValueError("untrusted producer event")
    jobs = list(pages(f"repos/{repository}/actions/runs/{run_id}/attempts/{attempt}/jobs", "jobs"))
    matches = [job for job in jobs if job.get("name") == PRODUCERS[workflow]]
    if len(matches) != 1 or matches[0].get("conclusion") != "success":
        raise ValueError("APK producer job did not succeed")


def unpack(payload: bytes, output: Path) -> None:
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        members = archive.infolist()
        expected = {*FILES.values(), "provenance.json"}
        if len(members) != 3 or {member.filename for member in members} != expected:
            raise ValueError("invalid APK archive members")
        if any(member.file_size > 512 * 1024 * 1024 or member.is_dir()
               or (member.external_attr >> 16) & 0o170000 == 0o120000 for member in members):
            raise ValueError("invalid APK archive entry")
        output.mkdir(parents=True, exist_ok=False)
        for member in members:
            with archive.open(member) as source, (output / member.filename).open("wb") as target:
                shutil.copyfileobj(source, target)


def reuse(repository: str, tree: str, output: Path) -> bool:
    prefix = f"android-apks-{tree}-"
    for artifact in pages(f"repos/{repository}/actions/artifacts", "artifacts"):
        if not artifact.get("name", "").startswith(prefix) or artifact.get("expired"):
            continue
        source_run = artifact.get("workflow_run", {}).get("id")
        if type(source_run) is not int:
            raise ValueError("artifact lacks run identity")
        run = api(f"repos/{repository}/actions/runs/{source_run}")
        # An in-flight producer cannot yet attest success. Other trusted runs
        # may still provide the same whole-tree bundle.
        if run.get("path", "").split("@", 1)[0] not in PRODUCERS:
            continue
        if run.get("head_repository", {}).get("full_name") != repository:
            continue
        identity = re.fullmatch(re.escape(prefix) + r"([0-9]+)-([0-9]+)", artifact["name"])
        if not identity or int(identity[1]) != source_run:
            raise ValueError("artifact name/source run mismatch")
        attempt = int(identity[2])
        jobs = list(pages(f"repos/{repository}/actions/runs/{source_run}/attempts/{attempt}/jobs", "jobs"))
        name = PRODUCERS[run["path"].split("@", 1)[0]]
        if not any(job.get("name") == name and job.get("conclusion") == "success" for job in jobs):
            continue
        try:
            payload = api(f"repos/{repository}/actions/artifacts/{artifact['id']}/zip", binary=True)
        except ArtifactUnavailable:
            continue
        unpack(payload, output)
        manifest = check_bundle(output, tree)
        expected_name = f"{prefix}{manifest.get('run_id')}-{manifest.get('run_attempt')}"
        if artifact["name"] != expected_name:
            raise ValueError("artifact name/run attempt mismatch")
        check_source(manifest, repository, source_run)
        print(f"Reused APK artifact {artifact['id']} from run {source_run}")
        return True
    return False


def emit(**values):
    for key, value in values.items():
        print(f"{key}={value}")
    if os.environ.get("GITHUB_OUTPUT"):
        with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
            for key, value in values.items():
                output.write(f"{key}={value}\n")


def pack(output: Path, app: Path, test: Path, reused_from: Path | None = None) -> dict:
    source_sha, tree = git("rev-parse", "HEAD"), git("rev-parse", "HEAD^{tree}")
    repository = os.environ["GITHUB_REPOSITORY"]
    workflow_ref = os.environ["GITHUB_WORKFLOW_REF"]
    workflow = workflow_ref.removeprefix(repository + "/").split("@", 1)[0]
    if workflow not in PRODUCERS:
        raise ValueError("untrusted producer workflow")
    output.mkdir(parents=True, exist_ok=False)
    for role, source in (("app", app), ("test", test)):
        shutil.copyfile(source, output / FILES[role])
    manifest = {"schema": 1, "repository": repository, "source_sha": source_sha, "tree": tree,
                "run_id": int(os.environ["GITHUB_RUN_ID"]), "run_attempt": int(os.environ["GITHUB_RUN_ATTEMPT"]),
                "workflow": workflow, "sha256": {role: digest(output / name) for role, name in FILES.items()}}
    if os.environ.get("GITHUB_EVENT_NAME") == "pull_request":
        event = strict_json(Path(os.environ["GITHUB_EVENT_PATH"]))
        pr = event.get("pull_request", {})
        number, base, head = event.get("number"), pr.get("base", {}).get("sha"), pr.get("head", {}).get("sha")
        if (type(number) is not int or number <= 0 or not isinstance(base, str) or not isinstance(head, str)
                or not re.fullmatch(r"[0-9a-f]{40}", base) or not re.fullmatch(r"[0-9a-f]{40}", head)
                or pr.get("number") != number or pr.get("base", {}).get("ref") != "main"
                or any(pr.get(side, {}).get("repo", {}).get("full_name") != repository for side in ("head", "base"))):
            raise ValueError("invalid historical PR event identity")
        manifest["pull_request"] = {"number": number, "base_sha": base, "head_sha": head}
    if reused_from is not None:
        previous = check_bundle(reused_from, tree)
        if previous["sha256"] != manifest["sha256"]:
            raise ValueError("repack differs from verified reused bundle")
        manifest["reused_from"] = {key: previous[key] for key in ("repository", "run_id", "run_attempt", "source_sha", "workflow")}
        manifest["build_origin"] = previous.get("build_origin", manifest["reused_from"])
    else:
        manifest["build_origin"] = {key: manifest[key] for key in ("repository", "run_id", "run_attempt", "source_sha", "workflow")}
    (output / "provenance.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    check_bundle(output, tree)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("reuse", "pack", "verify"))
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--app", type=Path)
    parser.add_argument("--test", type=Path)
    parser.add_argument("--reused-from", type=Path)
    args = parser.parse_args()
    tree = git("rev-parse", "HEAD^{tree}")
    if args.action == "reuse":
        hit = reuse(os.environ["GITHUB_REPOSITORY"], tree, args.directory)
        emit(hit=str(hit).lower(), tree=tree)
    elif args.action == "pack":
        manifest = pack(args.directory, args.app, args.test, args.reused_from)
        emit(tree=tree, artifact=f"android-apks-{tree}-{manifest['run_id']}-{manifest['run_attempt']}")
    else:
        manifest = check_bundle(args.directory, tree)
        check_source(manifest, os.environ["GITHUB_REPOSITORY"], manifest["run_id"])
        emit(verified="true")
    return 0
