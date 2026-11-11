#!/usr/bin/env python3
"""Drive or gate the P8-00 V-8 / P8-01a font-scale sample on a cloud AVD.

WHAT ONE GREEN RUN PROVES
-------------------------
V-8 of `docs/specs/2026-11-08-p8-00-visual-nav-baseline-design.md` section 5 asks for the
empty-state samples plus "light theme and 1.3x font scale must not overflow"; P8-01a's
`docs/specs/2026-11-11-p8-01-account-reconciliation-design.md` section 6 additionally requires a
pre-merge device read of the detail screen and the queue copy. The light theme is a canvas-side
sample; the device legs run here against a hosted AVD at the requested `system font_scale`:

- `profile=empty` (the P8-00 leg): the three empty-ledger screens on a **fresh empty install** —
  an empty ledger is exactly the state V-8 samples, so no fixture is needed;
- `profile=seeded` (the P8-01a i3b / P8-02 V-9 leg): one prepared v36 database is pushed into the
  app's stable storage before launch, so the search guide face, the search result face, the
  read-only 交易详情 screen and the 账户对账 queue are sampled on a ledger that actually holds one
  transfer. The seeded instant is clock-relative (D7-9), so the database bytes — and the recorded
  seed sha256 — differ per dispatch; the database itself is never uploaded.

This rig is NOT a PR gate and never runs on a pull request: dispatch it against a commit that is
already on a branch, and read the artifact.

WHAT IT DOES AND DOES NOT CLAIM
-------------------------------
The screenshot is the evidence a human reads; the gate re-checks only what is mechanically
decidable: the font scale actually took effect, every screenshot is a real image rather than a
blank frame, and each screen's marker text is present in the accessibility tree at that scale (a
clipped row loses its text node, an overflowing one pushes it out of the window) while the strings
its previous screen renders are gone (a capture whose navigation did not land is not evidence).
"No visual overflow" is still a human judgement on the artifact — this rig makes that judgement
cheap, it does not replace it.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import ClassVar

APP_PACKAGE = "com.unifiedledger.android"
MAIN_ACTIVITY = f"{APP_PACKAGE}/.MainActivity"

# The app's stable-storage host (`androidStableStoragePaths` in AndroidLedgerFileSystem.kt): the
# host is the app-private `databases/` directory, and `databases/ledger.db` is the legacy path the
# first generation-aware start migrates. `ledger-generations/` is AUTHORITATIVE whenever it exists
# (resolve rules 2/4), so a reseed must remove the whole generation set — removing only the
# pointer would fail closed with POINTER_MISSING instead of falling back to the legacy file.
APP_DATABASES = "databases"
APP_LEGACY_DATABASE = f"{APP_DATABASES}/ledger.db"
APP_STORAGE_PATHS = (
    f"{APP_DATABASES}/ledger-generations",
    f"{APP_DATABASES}/active-generation",
    f"{APP_DATABASES}/active-generation.new",
    f"{APP_DATABASES}/switch-journal",
    APP_LEGACY_DATABASE,
    APP_LEGACY_DATABASE + "-wal",
    APP_LEGACY_DATABASE + "-shm",
    APP_LEGACY_DATABASE + "-journal",
)
# /data/local/tmp is the one path the shell user always owns (the android_scale runner's staging
# directory precedent: push there, then `run-as … cp` into the app-private host).
REMOTE_SEED_DIR = "/data/local/tmp/ul-fontscale-seed"
REMOTE_SEED = f"{REMOTE_SEED_DIR}/ledger.db"

# The seeded ledger's literals. The app fixes its single ledger identity in
# `android-app/.../App.kt` (`PRODUCT_LEDGER_ID = "ledger-local-test"`); the seed writer in
# `ledger-data/src/jvmTest` uses the same two account/note literals, so a drift shows up as a
# missing tap target or marker rather than as a silently empty sample.
SEEDED_LEDGER_ID = "ledger-local-test"
SEEDED_ACCOUNT_NAME = "对账样本账户"
SEEDED_TRANSFER_NOTE = "对账样本转账"
RECONCILIATION_TITLE = "账户对账"

# Capture markers that only a SUCCESSFUL read renders. A screen title is not one: the detail's
# 交易详情 is also in the 无法读取交易详情 failure copy, and the queue's 账户对账 is also the
# account-flow header button that opens it (so that wait could be satisfied on the previous screen).
# - P503LedgerView.kt renders `备注：${data.note ?: "无"}` inside the Success-only 账目信息 card; no
#   failure branch renders a 备注 row. The home flow row composes `，备注 <note>` (a comma, not the
#   full-width colon), so this marker cannot match the previous screen either.
SEEDED_DETAIL_MARKER = f"备注：{SEEDED_TRANSFER_NOTE}"
# - reconciliationUncheckedPartitionText (P503ReconciliationPresentation.kt) renders
#   `未核对 <count> 条` only in the queue's LIST face (P503ReconciliationScreen.kt); the seed
#   writes exactly one PENDING leg, so the count is 1.
SEEDED_QUEUE_MARKER = "未核对 1 条"
# The queue sits one navigation step behind the account-flow screen, whose header renders 账户流水;
# its absence from the queue dump proves the 账户对账 tap actually left that screen.
ACCOUNT_FLOW_HEADER = "账户流水"

# P8-02 i2b-1 (V-9, spec section 5; D7-10) markers for the two search faces the 1.3x sample now
# captures first. Both are strings only the face that follows a successful step renders:
# - the guide copy exists ONLY before the first submit (P503SearchPresentation.searchFace's
#   `!readIssued` branch), so it cannot be satisfied by the home screen the capsule leaves;
# - the result header is `搜索结果 · 共 N 笔`, and the empty-result face's copy (没有找到匹配的交易。)
#   shares none of it, so a failed read cannot pass as a result list.
# Neither marker is a count form (…… · N 笔, 共 1 笔): those already ride the seeded home screen.
SEARCH_GUIDE_MARKER = "输入条件后开始搜索"
SEARCH_RESULTS_MARKER = "搜索结果 ·"
# The seeded home's count header. It is the launch marker AND the string the search screens must not
# show, so "the capsule tap left the home tab" is one literal with one source.
SEEDED_HOME_COUNT = "流水 · 共 1 笔"

MIN_SCREENSHOT_BYTES = 20_000
DEFAULT_TIMEOUT = 60


class SampleError(RuntimeError):
    """A precondition or observation failed; the run must not be read as evidence."""


def screen_center(bounds: str) -> tuple[int, int]:
    """Centre of a `[x1,y1][x2,y2]` uiautomator bounds attribute."""
    match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", bounds.strip())
    if match is None:
        raise SampleError(f"unparsable bounds: {bounds!r}")
    x1, y1, x2, y2 = (int(value) for value in match.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)


def find_center(xml_text: str, label: str, contains: bool = False) -> tuple[int, int] | None:
    """Centre of the first node whose text or content-description matches `label`.

    `contains=False` is the exact match the dock labels need (账户 is a substring of 账户对账 and
    of every account name, so an exact match is what keeps the dock tap honest). `contains=True`
    is the substring match the composed rows need: an account row appends 已停用 to an inactive
    name, and a flow row's content-description is one composed sentence, so no visible node equals
    the account name or the note.
    """
    if not label:
        raise SampleError("an empty tap/marker label would match every node")
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as error:
        raise SampleError(f"unparsable accessibility dump: {error}") from error
    for node in root.iter("node"):
        for attribute in ("text", "content-desc"):
            value = node.get(attribute) or ""
            if (label in value) if contains else (value == label):
                return screen_center(node.get("bounds", ""))
    return None


def dump_contains(xml_text: str, needle: str) -> bool:
    """True when any node's text or content-description carries `needle`."""
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as error:
        raise SampleError(f"unparsable accessibility dump: {error}") from error
    for node in root.iter("node"):
        for attribute in ("text", "content-desc"):
            value = node.get(attribute) or ""
            if needle in value:
                return True
    return False


def marker_problems(xml_text: str, marker: str, absent: tuple[str, ...] = ()) -> list[str]:
    """One screen's capture verdict: its marker is present and no previous-screen string is.

    A marker alone cannot always prove the tap landed (the queue's title is also the account-flow
    button that opens it), so a screen may declare strings its previous screen renders and it never
    does; a stuck navigation then fails the capture instead of passing it.
    """
    problems: list[str] = []
    if not dump_contains(xml_text, marker):
        problems.append(f"marker {marker!r} is absent")
    for needle in absent:
        if dump_contains(xml_text, needle):
            problems.append(f"previous-screen string {needle!r} is still visible")
    return problems


def sha256_file(path: Path) -> str:
    """The exact digest of a file's bytes (the seed a run pushed, re-read by a gate)."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_font_scale(raw: str) -> float:
    """The device's `settings get system font_scale` answer."""
    try:
        return float(raw.strip())
    except ValueError as error:
        raise SampleError(f"font_scale is not a number: {raw!r}") from error


@dataclass(frozen=True)
class TapStep:
    """Tap the centre of the first node whose text/content-description matches `target`."""

    target: str
    # Composed strings (an account row's name suffix, a flow row's content-description) need a
    # substring; the dock labels are exact so 账户 never taps 账户对账.
    contains: bool = False


@dataclass(frozen=True)
class BackStep:
    """The system back key — the host's back dispatch (`input keyevent 4`)."""


BACK = BackStep()

Step = TapStep | BackStep


def describe_step(step: Step) -> str:
    """The step's short name for host.json and every failure message."""
    if isinstance(step, TapStep):
        return f"tap containing {step.target!r}" if step.contains else f"tap {step.target!r}"
    return "back"


@dataclass(frozen=True)
class Screen:
    """One artifact: its stem, the steps from the previous captured screen (the launch frame before
    the first capture), its capture marker, and the previous-screen strings the capture must not
    show (the negative marker)."""

    stem: str
    steps: tuple[Step, ...]
    marker: str
    absent: tuple[str, ...] = ()


@dataclass(frozen=True)
class Profile:
    """One dispatch profile: what the launch waits for and the screens it captures in order."""

    name: str
    launch_marker: str
    screens: tuple[Screen, ...]
    # Seeded profiles need the prepared v36 database pushed before launch; empty profiles must
    # never touch the app's data directory (a fresh empty install is the state V-8 samples).
    seeded: bool = False


# The P8-00 V-8 screens: a fresh install bootstraps one default account, so the accounts tab is
# never empty and 资产 samples the 有户无余额 state the E2s canvas documents. Since D-307 the home
# tab paints 账本为空 for a ledger with no transactions at all — before that the branch was
# unreachable and the screen showed 流水 · 共 0 笔 instead. The rig samples what the device shows.
EMPTY_SCREENS: tuple[Screen, ...] = (
    Screen("home", (), "账本为空"),
    Screen("asset", (TapStep("账户"),), "总借入"),
    Screen("analysis", (TapStep("分析"),), "账本为空"),
)

# The seeded profile's screens: the P8-02 V-9 search leg (D7-10) leads, then the P8-01a i3b detail
# screen and the reconciliation queue. The search leg starts from the home tab through the search
# capsule (P8HomeFlow.kt: content-description 搜索账目, visible text 搜索账目、金额、备注 — the longer
# rendered text is why the tap is a substring match) and submits an EMPTY keyword (D7-6 allows it),
# which lands the one seeded transaction in the default 近 90 天 window. The detail screen therefore
# backs out of the result face first; every other marker and absent string is unchanged from i3b.
#
# Fold margin: both search markers are the LAST LazyColumn slot on their screen, and the seeded
# catalog holds no tags and no merchants (SqlDelightCatalogStore seeds accounts and categories
# only). Adding a tag or a merchant to the seed grows the condition area and can push a marker
# below the fold, which surfaces as a 180 s wait_for timeout — loud, never a false green.
SEEDED_SCREENS: tuple[Screen, ...] = (
    Screen(
        "search-guide",
        (TapStep("搜索账目", contains=True),),
        SEARCH_GUIDE_MARKER,
        absent=(SEEDED_HOME_COUNT,),
    ),
    # D7-8: the submit button is tapped by its unique semantics name — its visible text 搜索 is also
    # the screen title, and `find_center` returns the first node in document order.
    Screen(
        "search-results",
        (TapStep("提交搜索"),),
        SEARCH_RESULTS_MARKER,
        absent=(SEARCH_GUIDE_MARKER, SEEDED_HOME_COUNT),
    ),
    Screen("detail", (BACK, TapStep(SEEDED_TRANSFER_NOTE, contains=True)), SEEDED_DETAIL_MARKER),
    Screen(
        "reconciliation",
        (
            BACK,
            TapStep("账户"),
            TapStep(SEEDED_ACCOUNT_NAME, contains=True),
            TapStep(RECONCILIATION_TITLE),
        ),
        SEEDED_QUEUE_MARKER,
        absent=(ACCOUNT_FLOW_HEADER,),
    ),
)

PROFILES: dict[str, Profile] = {
    "empty": Profile("empty", "账本为空", EMPTY_SCREENS),
    "seeded": Profile("seeded", SEEDED_HOME_COUNT, SEEDED_SCREENS, seeded=True),
}


def profile_by_name(name: str) -> Profile:
    """The named profile; an unknown name is a loud failure, never a default."""
    profile = PROFILES.get(name)
    if profile is None:
        raise SampleError(f"unknown profile {name!r}; expected one of {sorted(PROFILES)}")
    return profile


def gate_profile_problems(host: dict[str, object], profile_name: str) -> list[str]:
    """The gate's profile consistency check, re-derived from host.json alone.

    The driver and the gate must select the SAME profile set: a run recorded under one profile
    cannot be gated as another, and the recorded screen list must be exactly the profile's stems
    (an artifact set that silently lost or gained a screen is not evidence for this profile).
    """
    profile = profile_by_name(profile_name)
    problems: list[str] = []
    recorded = host.get("profile")
    if recorded != profile_name:
        problems.append(f"host.json records profile {recorded!r}, expected {profile_name!r}")
    expected = [screen.stem for screen in profile.screens]
    recorded_screens = host.get("screens")
    if recorded_screens != expected:
        problems.append(
            f"host.json records screens {recorded_screens!r}, the {profile_name!r} profile captures {expected!r}"
        )
    return problems


@dataclass
class Sampler:
    """Owns every device interaction; the workflow must not duplicate any of it."""

    # /data/local/tmp is the one path the shell user always owns; scoped storage made /sdcard
    # unreliable for `uiautomator dump` on API 36.
    DUMP_DIR: ClassVar[str] = "/data/local/tmp/ul-fontscale"

    adb_serial: str | None
    evidence: Path
    font_scale: float
    avd_name: str
    screenshots: list[str] = field(default_factory=list)
    dumps: list[str] = field(default_factory=list)

    def adb(self, *args: str, timeout: int = DEFAULT_TIMEOUT, best_effort: bool = False) -> str:
        command = ["adb"]
        if self.adb_serial:
            command += ["-s", self.adb_serial]
        command += list(args)
        completed = subprocess.run(
            command, capture_output=True, text=True, timeout=timeout, check=False
        )
        if completed.returncode != 0 and not best_effort:
            raise SampleError(
                f"{' '.join(command)} failed ({completed.returncode}): "
                f"{(completed.stderr or completed.stdout).strip()[:400]}"
            )
        return completed.stdout

    def adb_bytes(self, *args: str, timeout: int = DEFAULT_TIMEOUT) -> bytes:
        """The binary adb surface (`exec-out`), used for the seed read-back digest."""
        command = ["adb"]
        if self.adb_serial:
            command += ["-s", self.adb_serial]
        command += list(args)
        completed = subprocess.run(command, capture_output=True, timeout=timeout, check=False)
        if completed.returncode != 0:
            raise SampleError(
                f"{' '.join(command)} failed ({completed.returncode}): "
                f"{completed.stderr.decode(errors='replace').strip()[:400]}"
            )
        return completed.stdout

    def require_owned_avd(self) -> None:
        """Refuse to touch any emulator this run did not boot itself."""
        name = self.adb("emu", "avd", "name").strip().splitlines()
        if not name or name[0] != self.avd_name:
            raise SampleError(
                f"device answers emu avd name {name!r}; this rig only drives {self.avd_name!r}"
            )

    def apply_font_scale(self) -> float:
        self.adb("shell", "settings", "put", "system", "font_scale", f"{self.font_scale:g}")
        # The framework re-reads the scale on the next configuration change, so restart the app
        # instead of trusting the setting alone.
        self.adb("shell", "am", "force-stop", APP_PACKAGE)
        time.sleep(2)
        observed = parse_font_scale(self.adb("shell", "settings", "get", "system", "font_scale"))
        if abs(observed - self.font_scale) > 0.001:
            raise SampleError(f"font_scale did not take effect: asked {self.font_scale}, read {observed}")
        return observed

    def seed(self, seed_path: str) -> str:
        """Replace the app's ledger with the prepared database (the seeded profile only).

        The stable-storage resolution makes `databases/ledger.db` authoritative ONLY while no
        `ledger-generations/` directory exists, so the whole generation set is removed before the
        push; the app then runs its own non-destructive legacy upgrade on the next start (copy ->
        fsync -> open -> publish pointer -> remove legacy). The pushed bytes are read back through
        `run-as cat` and hashed, so a truncated push can never be sampled as evidence. Returns the
        digest of the bytes this run pushed (host.json records it; the gate compares it).
        """
        source = Path(seed_path)
        if not source.is_file():
            raise SampleError(f"seed database {seed_path!r} does not exist")
        self.adb("shell", "am", "force-stop", APP_PACKAGE)
        self.adb("shell", "run-as", APP_PACKAGE, "mkdir", "-p", APP_DATABASES)
        for path in APP_STORAGE_PATHS:
            self.adb("shell", "run-as", APP_PACKAGE, "rm", "-rf", path)
        self.adb("shell", "mkdir", "-p", REMOTE_SEED_DIR)
        self.adb("push", str(source), REMOTE_SEED, timeout=180)
        self.adb("shell", "run-as", APP_PACKAGE, "cp", REMOTE_SEED, APP_LEGACY_DATABASE)
        expected = sha256_file(source)
        observed = hashlib.sha256(
            self.adb_bytes("exec-out", "run-as", APP_PACKAGE, "cat", APP_LEGACY_DATABASE)
        ).hexdigest()
        if observed != expected:
            raise SampleError(
                f"the pushed seed did not read back byte-for-byte (host {expected}, device {observed})"
            )
        return expected

    def launch(self) -> None:
        self.adb("shell", "am", "start", "-W", "-n", MAIN_ACTIVITY, timeout=120)

    def wait_for(self, needle: str, timeout_s: int = 180) -> str:
        """Poll the accessibility tree until `needle` appears.

        A cold cloud AVD spends several seconds on the startup screen (正在打开本地账本…), so a
        fixed sleep would capture the loading frame and call it a sample. The last dump stays on
        disk as `wait.xml` when the wait fails, so a red run still shows what the device held.
        """
        deadline = time.monotonic() + timeout_s
        last = ""
        while time.monotonic() < deadline:
            last = self.dump("wait")
            if dump_contains(last, needle):
                return last
            time.sleep(3)
        raise SampleError(f"timed out after {timeout_s}s waiting for {needle!r}; see wait.xml")

    def wait_for_change(self, previous: str, timeout_s: int = 60) -> str:
        """Poll until the tree differs from `previous` — proof that the step landed.

        首页 and 分析 both render 账本为空, so the copy alone cannot prove the tab changed; the
        frame that follows the tap has to be a different frame.
        """
        deadline = time.monotonic() + timeout_s
        last = previous
        while time.monotonic() < deadline:
            last = self.dump("wait")
            if last != previous:
                return last
            time.sleep(2)
        raise SampleError(f"the screen did not change within {timeout_s}s after the step; see wait.xml")

    def screenshot(self, stem: str) -> Path:
        target = self.evidence / f"{stem}.png"
        remote = f"/sdcard/{stem}.png"
        self.adb("shell", "screencap", "-p", remote)
        self.adb("pull", remote, str(target))
        self.adb("shell", "rm", "-f", remote)
        if not target.exists() or target.stat().st_size < MIN_SCREENSHOT_BYTES:
            raise SampleError(f"screenshot {stem} is missing or blank ({target})")
        self.screenshots.append(target.name)
        return target

    def dump(self, stem: str) -> str:
        remote = f"{self.DUMP_DIR}/{stem}.xml"
        # `uiautomator dump` answers exit 0 while writing nothing when the window will not settle
        # ("could not get idle state"), so the file's presence is the only proof it worked.
        for _attempt in range(4):
            dump_output = self.adb("shell", "uiautomator", "dump", remote, best_effort=True)
            # The probe is a plain `ls`: adb hands the arguments to the device shell and drops the
            # quoting, so a `sh -c "test -s … && echo ok"` probe answers for the wrong command.
            listing = self.adb("shell", "ls", "-l", remote, best_effort=True)
            if remote.rsplit("/", 1)[-1] in listing:
                (self.evidence / f"uiautomator-{stem}.txt").write_text(
                    dump_output, encoding="utf-8"
                )
                break
            time.sleep(2)
        else:
            raise SampleError(
                f"uiautomator produced no {stem}.xml after 4 attempts; last answer: {dump_output.strip()[:200]!r}"
            )
        self.adb("pull", remote, str(self.evidence / f"{stem}.xml"))
        self.dumps.append(f"{stem}.xml")
        return (self.evidence / f"{stem}.xml").read_text(encoding="utf-8")

    def save_tree(self, stem: str, tree: str) -> None:
        """Write the frame the sample was taken from under the screen's own name.

        `wait_for` reuses one remote name, so without this the artifact would carry only
        `wait.xml` and the gate would have nothing per-screen to re-check.
        """
        (self.evidence / f"{stem}.xml").write_text(tree, encoding="utf-8")
        self.dumps.append(f"{stem}.xml")

    def tap_label(self, label: str, contains: bool = False) -> str:
        """Tap the first matching node; return the pre-tap frame for change detection."""
        tree = self.dump("tap-probe")
        center = find_center(tree, label, contains=contains)
        if center is None:
            match = "containing" if contains else "equal to"
            raise SampleError(f"tap target {label!r} ({match}) is not in the accessibility tree")
        self.adb("shell", "input", "tap", str(center[0]), str(center[1]))
        time.sleep(2)
        return tree

    def press_back(self) -> None:
        self.adb("shell", "input", "keyevent", "4")
        time.sleep(2)

    def apply_step(self, step: Step, previous: str) -> str:
        """Run one step and wait for the tree to change; returns the new frame."""
        if isinstance(step, TapStep):
            return self.wait_for_change(self.tap_label(step.target, contains=step.contains))
        if isinstance(step, BackStep):
            if not previous:
                raise SampleError("the back step has no frame to compare against")
            self.press_back()
            return self.wait_for_change(previous)
        raise SampleError(f"unknown step {step!r}")


def require_cloud_runner() -> None:
    triple = (
        os.environ.get("GITHUB_ACTIONS"),
        os.environ.get("RUNNER_ENVIRONMENT"),
        os.environ.get("RUNNER_OS"),
    )
    if triple != ("true", "github-hosted", "Linux"):
        raise SampleError(
            "this rig only runs on a GitHub-hosted Linux runner "
            f"(GITHUB_ACTIONS/RUNNER_ENVIRONMENT/RUNNER_OS = {triple}); the development host's "
            "adb surface belongs to the user's own emulator"
        )


def run(args: argparse.Namespace) -> int:
    require_cloud_runner()
    profile = profile_by_name(args.profile)
    if profile.seeded and not args.seed:
        raise SampleError(f"profile {profile.name!r} needs --seed <path to the prepared ledger database>")
    evidence = Path(args.evidence)
    evidence.mkdir(parents=True, exist_ok=True)
    sampler = Sampler(
        adb_serial=args.serial or None,
        evidence=evidence,
        font_scale=args.font_scale,
        avd_name=args.avd_name,
    )
    sampler.require_owned_avd()
    sampler.adb("shell", "mkdir", "-p", Sampler.DUMP_DIR, best_effort=True)
    applied = sampler.apply_font_scale()
    sampler.adb("install", "-r", args.apk, timeout=180)
    seed_sha256 = ""
    if profile.seeded:
        seed_sha256 = sampler.seed(args.seed)
    sampler.launch()
    # The launch frame is the change-detection baseline for the first tap, and its marker proves
    # the expected ledger state (empty, or the seeded transaction) before any step runs.
    previous = sampler.wait_for(profile.launch_marker)

    observations: dict[str, dict[str, object]] = {}
    for screen in profile.screens:
        for index, step in enumerate(screen.steps):
            try:
                previous = sampler.apply_step(step, previous)
            except SampleError as error:
                raise SampleError(
                    f"screen {screen.stem!r} step {index} ({describe_step(step)}) while navigating "
                    f"to marker {screen.marker!r}: {error}"
                ) from error
        # Wait for the screen to be the one we are about to photograph, then photograph it.
        try:
            tree = sampler.wait_for(screen.marker)
        except SampleError as error:
            raise SampleError(f"screen {screen.stem!r} marker {screen.marker!r}: {error}") from error
        previous = tree
        sampler.save_tree(screen.stem, tree)
        sampler.screenshot(screen.stem)
        observations[screen.stem] = {
            "steps": [describe_step(step) for step in screen.steps],
            "marker": screen.marker,
            "marker_visible": dump_contains(tree, screen.marker),
            "absent": list(screen.absent),
        }
        problems = marker_problems(tree, screen.marker, screen.absent)
        if problems:
            raise SampleError(
                f"screen {screen.stem}: {'; '.join(problems)} at font_scale {applied:g}"
            )

    host = {
        "sha": args.sha,
        "profile": profile.name,
        "avd_name": args.avd_name,
        "font_scale": applied,
        "screens": [screen.stem for screen in profile.screens],
        "screenshots": sampler.screenshots,
        "dumps": sampler.dumps,
        "observations": observations,
    }
    if profile.seeded:
        # The seed contract this evidence was produced against (the ledger id must be the app's
        # PRODUCT_LEDGER_ID; a mismatch would have failed the launch marker above). The digest is
        # of the bytes actually pushed, so a gate can prove it re-derived against the same seed.
        host["seed"] = {
            "ledger_id": SEEDED_LEDGER_ID,
            "account": SEEDED_ACCOUNT_NAME,
            "note": SEEDED_TRANSFER_NOTE,
            "sha256": seed_sha256,
        }
    (evidence / "host.json").write_text(json.dumps(host, indent=2), encoding="utf-8")
    return 0


def gate_seed_problems(host: dict[str, object], seed_sha256: str | None) -> list[str]:
    """The gate's seed-identity check: host.json's digest must be the gate seed file's own digest.

    Without it a gate can be re-derived over a different database than the run pushed: profile,
    screens and markers would all still match. `seed_sha256` is None when the gate cannot read a
    seed file (missing --seed, or a path that is not a file), which is itself a failure.
    """
    seed = host.get("seed")
    recorded = seed.get("sha256") if isinstance(seed, dict) else None
    if not isinstance(recorded, str) or not recorded:
        return ["host.json records no seed sha256 — the evidence is not tied to a seed"]
    if seed_sha256 is None:
        return [f"the gate's seed file cannot be read; host.json records {recorded}"]
    if recorded != seed_sha256:
        return [f"host.json records seed sha256 {recorded}, the gate's seed is {seed_sha256}"]
    return []


def gate(args: argparse.Namespace) -> int:
    """Re-derive the verdict from the evidence on disk; never trust the driver's own word."""
    profile = profile_by_name(args.profile)
    evidence = Path(args.evidence)
    host_path = evidence / "host.json"
    if not host_path.exists():
        print("gate: host.json is missing — the run produced no evidence", file=sys.stderr)
        return 1
    host = json.loads(host_path.read_text(encoding="utf-8"))
    problems: list[str] = gate_profile_problems(host, profile.name)
    if host.get("sha") != args.sha:
        problems.append(f"host.json records sha {host.get('sha')!r}, expected {args.sha!r}")
    applied = host.get("font_scale")
    if not isinstance(applied, (int, float)) or abs(float(applied) - args.font_scale) > 0.001:
        problems.append(f"font_scale {applied!r} is not the requested {args.font_scale:g}")
    for screen in profile.screens:
        shot = evidence / f"{screen.stem}.png"
        tree = evidence / f"{screen.stem}.xml"
        if not shot.exists() or shot.stat().st_size < MIN_SCREENSHOT_BYTES:
            problems.append(f"{screen.stem}: screenshot missing or blank")
        if not tree.exists():
            problems.append(f"{screen.stem}: accessibility dump missing")
            continue
        for problem in marker_problems(
            tree.read_text(encoding="utf-8"), screen.marker, screen.absent
        ):
            problems.append(f"{screen.stem}: {problem}")
    if profile.seeded:
        seed_file = Path(args.seed) if args.seed else None
        actual = sha256_file(seed_file) if seed_file is not None and seed_file.is_file() else None
        problems.extend(gate_seed_problems(host, actual))
    if problems:
        for problem in problems:
            print(f"gate: {problem}", file=sys.stderr)
        return 1
    print(
        f"gate: font-scale sample complete — {len(profile.screens)} screens captured for profile "
        f"{profile.name!r} with their markers visible and no previous-screen string left"
    )
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("run", "gate"))
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--profile", choices=tuple(PROFILES), default="empty")
    parser.add_argument(
        "--seed",
        default="",
        help=(
            "prepared v36 ledger database: pushed for profile=seeded, and hash-compared by the "
            "gate against the digest host.json recorded"
        ),
    )
    parser.add_argument("--font-scale", type=float, default=1.3)
    parser.add_argument("--avd-name", default="ul-fontscale")
    parser.add_argument("--apk", default="android-app/build/outputs/apk/debug/android-app-debug.apk")
    parser.add_argument("--serial", default="")
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])
    try:
        return run(args) if args.command == "run" else gate(args)
    except SampleError as error:
        print(f"{args.command}: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
