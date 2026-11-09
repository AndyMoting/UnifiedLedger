#!/usr/bin/env python3
"""Drive or gate the P8-00 V-8 font-scale sample on a cloud AVD.

WHAT ONE GREEN RUN PROVES
-------------------------
V-8 of `docs/specs/2026-11-08-p8-00-visual-nav-baseline-design.md` section 5 asks for the
empty-state samples plus "light theme and 1.3x font scale must not overflow". The light
theme is a canvas-side sample; the font-scale leg is device-observable, so this rig boots a
hosted AVD, sets `system font_scale` to the requested value, and records a screenshot and the
accessibility tree of the three screens on a **fresh empty install** — an empty ledger is
the state V-8 samples, so no fixture is needed.

This rig is NOT a PR gate and never runs on a pull request: dispatch it against a commit
that is already on a branch, and read the artifact.

WHAT IT DOES AND DOES NOT CLAIM
-------------------------------
The screenshot is the evidence a human reads; the gate re-checks only what is mechanically
decidable: the font scale actually took effect, every screenshot is a real image rather
than a blank frame, and each screen's empty-state copy is present in the accessibility tree
at that scale (a clipped row loses its text node, an overflowing one pushes it out of the
window). "No visual overflow" is still a human judgement on the artifact — this rig makes
that judgement cheap, it does not replace it.
"""
from __future__ import annotations

import argparse
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

# (artifact stem, dock label, empty-state copy the accessibility tree must contain)
SCREENS: tuple[tuple[str, str | None, str], ...] = (
    ("home", None, "账本为空"),
    ("asset", "账户", "还没有可管理的账户"),
    ("analysis", "分析", "账本为空"),
)

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


def find_center(xml_text: str, label: str) -> tuple[int, int] | None:
    """Centre of the first node whose text or content-description equals `label`."""
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as error:
        raise SampleError(f"unparsable accessibility dump: {error}") from error
    for node in root.iter("node"):
        if node.get("text") == label or node.get("content-desc") == label:
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


def parse_font_scale(raw: str) -> float:
    """The device's `settings get system font_scale` answer."""
    try:
        return float(raw.strip())
    except ValueError as error:
        raise SampleError(f"font_scale is not a number: {raw!r}") from error


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
        """Poll until the tree differs from `previous` — proof that the tap landed.

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
        raise SampleError(f"the screen did not change within {timeout_s}s after the tap; see wait.xml")

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
            self.adb("shell", "uiautomator", "dump", remote, best_effort=True)
            if "ok" in self.adb("shell", "sh", "-c", f"test -s {remote} && echo ok", best_effort=True):
                break
            time.sleep(2)
        else:
            raise SampleError(f"uiautomator produced no {stem}.xml after 4 attempts")
        self.adb("pull", remote, str(self.evidence / f"{stem}.xml"))
        self.dumps.append(f"{stem}.xml")
        return (self.evidence / f"{stem}.xml").read_text(encoding="utf-8")

    def tap_label(self, label: str) -> None:
        center = find_center(self.dump("tap-probe"), label)
        if center is None:
            raise SampleError(f"dock label {label!r} is not in the accessibility tree")
        self.adb("shell", "input", "tap", str(center[0]), str(center[1]))
        time.sleep(2)


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
    sampler.launch()

    observations: dict[str, dict[str, object]] = {}
    previous: str | None = None
    for stem, label, copy in SCREENS:
        if label is not None:
            sampler.tap_label(label)
            previous = sampler.wait_for_change(previous or "")
        # Wait for the screen to be the one we are about to photograph, then photograph it.
        tree = sampler.wait_for(copy)
        previous = tree
        sampler.screenshot(stem)
        visible = dump_contains(tree, copy)
        observations[stem] = {"label": label, "copy": copy, "copy_visible": visible}
        if not visible:
            raise SampleError(
                f"screen {stem}: empty-state copy {copy!r} is not in the accessibility tree at "
                f"font_scale {applied:g}"
            )

    host = {
        "sha": args.sha,
        "avd_name": args.avd_name,
        "font_scale": applied,
        "screenshots": sampler.screenshots,
        "dumps": sampler.dumps,
        "observations": observations,
    }
    (evidence / "host.json").write_text(json.dumps(host, indent=2), encoding="utf-8")
    return 0


def gate(args: argparse.Namespace) -> int:
    """Re-derive the verdict from the evidence on disk; never trust the driver's own word."""
    evidence = Path(args.evidence)
    host_path = evidence / "host.json"
    if not host_path.exists():
        print("gate: host.json is missing — the run produced no evidence", file=sys.stderr)
        return 1
    host = json.loads(host_path.read_text(encoding="utf-8"))
    problems: list[str] = []
    if host.get("sha") != args.sha:
        problems.append(f"host.json records sha {host.get('sha')!r}, expected {args.sha!r}")
    applied = host.get("font_scale")
    if not isinstance(applied, (int, float)) or abs(float(applied) - args.font_scale) > 0.001:
        problems.append(f"font_scale {applied!r} is not the requested {args.font_scale:g}")
    for stem, _label, copy in SCREENS:
        shot = evidence / f"{stem}.png"
        tree = evidence / f"{stem}.xml"
        if not shot.exists() or shot.stat().st_size < MIN_SCREENSHOT_BYTES:
            problems.append(f"{stem}: screenshot missing or blank")
        if not tree.exists():
            problems.append(f"{stem}: accessibility dump missing")
            continue
        if not dump_contains(tree.read_text(encoding="utf-8"), copy):
            problems.append(f"{stem}: empty-state copy {copy!r} absent at this scale")
    if problems:
        for problem in problems:
            print(f"gate: {problem}", file=sys.stderr)
        return 1
    print("gate: font-scale sample complete — three screens captured with their empty-state copy visible")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("run", "gate"))
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--sha", required=True)
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
