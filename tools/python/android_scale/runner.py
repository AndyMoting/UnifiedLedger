"""CI-only bounded host driver. Never invokes Gradle or touches a non-owned device."""
from __future__ import annotations

import hashlib
import json
import math
import os
import re
import subprocess
import threading
import time
import uuid
from pathlib import Path
from xml.etree import ElementTree as ET

from .fixture import load_manifest, validate_manifest
from .result import (PHASES, PACKAGE, STAGES, TEST_CLASS, TEST_METHOD, PREFLIGHT_CLASS, PREFLIGHT_METHOD, crash_markers,
                     READY_MARKER, app_ui_state, instrumentation_pass, validate_app_ready, validate_evidence)
from .preflight import prepare_probe, validate_preflight

RUNNER = "com.unifiedledger.android.test/androidx.test.runner.AndroidJUnitRunner"

# Device-side coldstart forensics the instrumentation may have written before
# teardown (files/<name> inside the app's private dir, fixed names). Purely
# supplementary diagnostics: the oracle never reads them.
COLDSTART_FORENSICS_DEVICE_FILES = {
    "files/android-scale-coldstart-forensics.json": "coldstart-forensics.json",
    "files/android-scale-coldstart-forensics.png": "coldstart-forensics.png",
}

# Host-side coldstart discriminating probe (D-204, diagnostics only; it never
# feeds validation or any PASS/FAIL judgment). While the device evidence shows
# the coldstart stage running for >=30s, an independent host a11y client
# (`uiautomator dump`, a separate connection from the in-process observer) is
# sampled at most twice per run, >=60s apart, to distinguish "product not
# ready" from "in-process observer blind" if the observer ever sticks again.
COLDSTART_HOST_DUMP = "coldstart-hostdump.xml"
COLDSTART_HOST_DUMP_DEVICE_PREFIX = "/data/local/tmp/ul-coldstart-host-"
COLDSTART_HOST_PROBE_MIN_ELAPSED_MS = 30000
COLDSTART_HOST_PROBE_SPACING_MS = 60000
COLDSTART_HOST_PROBE_MAX = 2
COLDSTART_HOST_LOADING_MARKER = "正在打开本地账本"

# Preparation and chain deliberately share the remaining global budget.
# Only the three short reopen/replay phases have independent host limits.
PHASE_BUDGETS = {"prepare": None, "chain": None, "reopen": 480, "replay": 480, "final-reopen": 480}
DIAGNOSTIC_SECONDS = 120
CLEANUP_RESERVE_SECONDS = 30


def last_match(pattern: str, text: str, label: str) -> str:
    matches = re.findall(pattern, text)
    if not matches:
        raise RuntimeError(f"cannot read {label} from the device: {text.strip()[:200]!r}")
    return matches[-1]


class ScaleDeadlineError(RuntimeError):
    pass


def remaining_seconds(deadline: float, now: float, cap: float = 30) -> float:
    remaining = deadline - now
    if remaining <= 0:
        raise ScaleDeadlineError("global deadline exceeded")
    return min(cap, remaining)


def owned_serial(devices: str, avd: str) -> str:
    connected = [line.split() for line in devices.splitlines() if line.startswith("emulator-")]
    if len(connected) != 1 or connected[0][1:] != ["device"] or avd.strip().splitlines() != ["ul-scale", "OK"]:
        raise ValueError("expected exactly the CI-owned ul-scale emulator")
    return connected[0][0]


class ScaleRunner:
    def __init__(self, fixture: Path, evidence: Path, app: Path, test: Path, sha: str, mode: str = "maximum",
                 *, outer_deadline_epoch: float | None = None):
        if (os.environ.get("GITHUB_ACTIONS"), os.environ.get("RUNNER_ENVIRONMENT"), os.environ.get("RUNNER_OS")) != ("true", "github-hosted", "Linux"):
            raise ValueError("this driver is CI-only; local devices are forbidden")
        if not re.fullmatch(r"[0-9a-f]{40}", sha):
            raise ValueError("full lowercase SHA required")
        if mode not in ("maximum", "preflight"):
            raise ValueError("unknown execution mode")
        self.mode = mode
        self.fixture, self.evidence, self.app, self.test, self.sha = fixture, evidence, app, test, sha
        self.started = time.monotonic()
        # Workflow records this BEFORE the emulator action, so boot/action setup
        # consumes the same outer budget as execution, diagnostics and cleanup.
        if outer_deadline_epoch is not None and not math.isfinite(outer_deadline_epoch):
            raise ValueError("outer deadline must be finite")
        self.outer_deadline = None if outer_deadline_epoch is None else self.started + outer_deadline_epoch - time.time()
        mode_limit = 600 if mode == "preflight" else 13800
        self.deadline = self.started + mode_limit
        if self.outer_deadline is not None:
            self.deadline = min(self.deadline, self.outer_deadline - DIAGNOSTIC_SECONDS - CLEANUP_RESERVE_SECONDS)
        self.active_deadline = self.deadline
        self.log_stop = threading.Event()
        self.log_thread = None
        self.log_process = None
        self.log_started = False
        self.log_gaps = []
        self.setup_complete = False
        self.serial = None
        self.device_deadline = 0
        self.report = {"sha": sha, "mode": mode, "status": "ERROR", "timed_out": False, "crash_detected": False,
                       "phases": [], "apk_sha256": {}, "config": {},
                       "execution_budget_seconds": max(0, self.deadline - self.started),
                       "outer_remaining_seconds_at_start": None if self.outer_deadline is None else self.outer_deadline - self.started}
        self.cases = []
        evidence.mkdir(parents=True, exist_ok=False)

    def command(self, command: list[str], *, timeout: float = 30, binary: bool = False,
                best_effort: bool = False) -> str | bytes:
        bound = remaining_seconds(self.active_deadline, time.monotonic(), timeout)
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=bound)
        if result.returncode and not best_effort:
            # Carry the command's own diagnostics into the failure: without this
            # the cloud log and host.json say only "a command failed", which is
            # not enough to tell a transient device hiccup from a real defect.
            detail = (result.stderr or result.stdout).decode("utf-8", errors="replace").strip()
            raise RuntimeError(f"command failed: {command[0]} ({result.returncode}): {detail[:400]}")
        return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")

    def adb(self, *args: str, **kwargs):
        prefix = ["adb"] + (["-s", self.serial] if self.serial else [])
        return self.command(prefix + list(args), **kwargs)

    def root_and_settle(self, attempts: int = 5) -> None:
        """Restart adbd as root, tolerating the window where the device drops.

        `adb root` restarts adbd, so the device is briefly unreachable; right
        after boot the first attempt can legitimately fail (the emulator action
        observes the same window). Retry with a wait-for-device in between
        instead of abandoning the whole run on the first failure.
        """
        last: Exception | None = None
        for _ in range(attempts):
            try:
                self.adb("wait-for-device", timeout=60)
                self.adb("root", timeout=60)
                self.adb("wait-for-device", timeout=60)
                return
            except ScaleDeadlineError:
                raise
            except (RuntimeError, subprocess.TimeoutExpired) as error:
                last = error
                time.sleep(2)
        raise RuntimeError(f"adb root did not settle after {attempts} attempts: {last}")

    def await_framework(self, timeout: float = 300) -> None:
        """Wait for window/package services AND credential-encrypted user storage."""
        deadline = min(self.deadline, time.monotonic() + timeout)
        prior = self.active_deadline
        self.active_deadline = deadline
        try:
            while True:
                remaining_seconds(deadline, time.monotonic())
                try:
                    state = self.adb("shell", "am", "get-started-user-state", "0").strip()
                    packages = self.adb("shell", "cmd", "package", "list", "packages", "--user", "0", "android")
                    size = self.adb("shell", "wm", "size")
                    self.report["readiness"] = {"user": state, "package_service": "package:android" in packages.splitlines(),
                                                "window_service": bool(re.search(r"size: [0-9]+x[0-9]+", size))}
                    if state == "RUNNING_UNLOCKED" and all(self.report["readiness"][k] for k in ("package_service", "window_service")):
                        return
                    self.adb("shell", "input", "keyevent", "82", best_effort=True)
                except ScaleDeadlineError:
                    raise
                except (RuntimeError, subprocess.TimeoutExpired):
                    pass
                time.sleep(remaining_seconds(deadline, time.monotonic(), 2))
        finally:
            self.active_deadline = prior

    def configure(self):
        devices = self.adb("devices")
        matches = re.findall(r"^(emulator-[0-9]+)\s+device$", devices, re.MULTILINE)
        if len(matches) != 1:
            raise ValueError("one owned CI emulator required")
        candidate = matches[0]
        # Identity is the only permitted query before ownership is established.
        avd = self.command(["adb", "-s", candidate, "emu", "avd", "name"])
        self.serial = owned_serial(devices, avd)
        self.root_and_settle()
        self.start_logcat()
        self.adb("shell", "setprop", "persist.sys.locale", "zh-CN")
        self.adb("shell", "setprop", "persist.sys.timezone", "Asia/Shanghai")
        self.adb("shell", "stop")
        self.adb("shell", "start")
        self.await_framework()
        self.pin_display()
        self.adb("shell", "settings", "put", "system", "font_scale", "1.0")
        for name in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            self.adb("shell", "settings", "put", "global", name, "1.0")
        config = {
            "api": self.adb("shell", "getprop", "ro.build.version.sdk").strip(),
            "abi": self.adb("shell", "getprop", "ro.product.cpu.abi").strip(),
            "locale": self.adb("shell", "getprop", "persist.sys.locale").strip(),
            "timezone": self.adb("shell", "getprop", "persist.sys.timezone").strip(),
            "font_scale": self.adb("shell", "settings", "get", "system", "font_scale").strip(),
        }
        size = self.adb("shell", "wm", "size")
        density = self.adb("shell", "wm", "density")
        config["size"] = last_match(r"(?:Physical|Override) size: ([0-9]+x[0-9]+)", size, "display size")
        config["density"] = last_match(r"(?:Physical|Override) density: ([0-9]+)", density, "display density")
        for name in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            config[name] = self.adb("shell", "settings", "get", "global", name).strip()
        actual = self.adb("shell", "am", "get-config")
        if "zh-rCN" not in actual and "zh-CN" not in actual:
            raise ValueError("effective Android locale is not Chinese")
        self.report["config"] = config
        (self.evidence / "configuration.txt").write_text(json.dumps(config, sort_keys=True) + "\n" + actual +
            self.adb("shell", "cat", "/proc/meminfo") + self.adb("shell", "cat", "/sys/devices/system/cpu/present"), encoding="utf-8")
        if config["api"] != "36" or config["abi"] != "x86_64":
            raise ValueError("wrong device image")
        self.device_deadline = int(float(self.adb("shell", "cat", "/proc/uptime").split()[0]) * 1000 +
                                   remaining_seconds(self.deadline, time.monotonic(), 14400) * 1000)

    def pin_display(self, size: str = "1080x2400", density: str = "420", attempts: int = 5) -> None:
        """Pin the display size/density and confirm the override took effect.

        The framework can still apply its boot-time display configuration after
        the first `wm size` write and silently revert the override, so a single
        write is not enough: write, read back, retry, and fail loudly with the
        observed value.
        """
        observed = "unknown"
        for _ in range(attempts):
            self.adb("shell", "wm", "size", size, timeout=60)
            self.adb("shell", "wm", "density", density, timeout=60)
            reported = self.adb("shell", "wm", "size", timeout=60)
            matches = re.findall(r"(?:Physical|Override) size: ([0-9]+x[0-9]+)", reported)
            observed = matches[-1] if matches else "unknown"
            if observed == size:
                return
            time.sleep(2)
        raise RuntimeError(f"display override did not take effect: requested {size}, observed {observed}")

    def launcher_component(self) -> str:
        resolved = self.adb("shell", "cmd", "package", "resolve-activity", "--brief", "--user", "0",
                            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", PACKAGE)
        components = [line.strip() for line in resolved.splitlines()
                      if re.fullmatch(re.escape(PACKAGE) + r"/[.A-Za-z0-9_$]+", line.strip())]
        if len(components) != 1:
            raise RuntimeError(f"launcher not resolvable: {resolved[:300]}")
        return components[0]

    def ensure_app_data_dir(self, attempts: int = 5) -> None:
        """Wait for completed ledger startup before any normal process stop."""
        last = "no attempt made"
        for _ in range(attempts):
            try:
                component = self.launcher_component()
                output = self.adb("shell", "am", "start", "-W", "--user", "0", "-n", component, timeout=60)
                if not re.search(r"^Status: ok\s*$", output, re.MULTILINE) or re.search(r"Error:|Error type|Exception", output):
                    raise RuntimeError(f"launcher failed: {output[:400]}")
                self.report["launcher"] = component
                break
            except ScaleDeadlineError:
                raise
            except RuntimeError as error:
                last = str(error)
                time.sleep(2)
        else:
            raise RuntimeError(f"could not launch {PACKAGE} to create its data directory: {last}")
        # Outside the launch retry: a recovery/error screen must fail immediately,
        # not relaunch or mutate the ledger in an attempt to hide startup failure.
        self.await_app_ready()
        self.adb("shell", "am", "force-stop", PACKAGE)

    def await_app_ready(self, timeout: float = 180) -> None:
        prior = self.active_deadline
        deadline = min(prior, self.deadline, time.monotonic() + min(timeout, 180))
        self.active_deadline = deadline
        self.report.pop("app_ready", None)
        last = "no fresh UI dump"
        try:
            while True:
                remaining_seconds(deadline, time.monotonic())
                state = "Unknown"
                try:
                    # Never read the fixed path used by diagnostics, or reuse an
                    # earlier poll's XML after a failed/non-writing dump command.
                    path = "/data/local/tmp/ul-ready-" + uuid.uuid4().hex + ".xml"
                    output = self.adb("shell", "uiautomator", "dump", path)
                    if "dumped to:" not in output or not output.strip().endswith(path):
                        raise ValueError("UI dump did not confirm its fresh output path")
                    xml = self.adb("exec-out", "cat", path)
                    state = app_ui_state(xml)
                    if state != "Error":
                        self.adb("shell", "rm", "-f", path, best_effort=True)
                    last = state
                except ScaleDeadlineError:
                    raise
                except (RuntimeError, subprocess.TimeoutExpired, ValueError, ET.ParseError) as error:
                    last = str(error)[:200]
                if state == "Error":
                    raise RuntimeError("app startup error/recovery UI; refusing instrumentation")
                if state == "Ready":
                    data = xml.encode("utf-8")
                    (self.evidence / "ready-ui.xml").write_bytes(data)
                    self.report["app_ready"] = {"state": "Ready", "package": PACKAGE, "marker": READY_MARKER,
                                                "fresh_dump": True, "xml_sha256": hashlib.sha256(data).hexdigest()}
                    return
                self.report["app_ready_last_observation"] = last
                time.sleep(remaining_seconds(deadline, time.monotonic(), 2))
        finally:
            self.active_deadline = prior

    def install(self):
        if self.mode == "maximum":
            validate_manifest(load_manifest(self.fixture / "manifest.json"), self.fixture)
        else:
            prepare_probe(self.fixture, self.sha)
        for role, apk in (("app", self.app), ("test", self.test)):
            self.report["apk_sha256"][role] = hashlib.sha256(apk.read_bytes()).hexdigest()
            output = self.adb("install", "-t", str(apk), timeout=120)
            # `adb install` exits 0 even when the install fails; the verdict is in
            # the output, so a silent failure here would surface much later as a
            # confusing "activity does not exist" or a missing device evidence file.
            if not re.search(r"^Success\s*$", output, re.MULTILINE) or "Failure" in output:
                raise RuntimeError(f"install of the {role} APK failed: {output.strip()[:400]}")
        listed = self.adb("shell", "pm", "list", "instrumentation")
        if f"instrumentation:{RUNNER} (target={PACKAGE})" not in listed.splitlines():
            raise RuntimeError("installed test runner does not target the app")
        for package in (PACKAGE, PACKAGE + ".test"):
            if not self.adb("shell", "pm", "path", "--user", "0", package).strip().startswith("package:"):
                raise RuntimeError(f"installed package is not available: {package}")
        # Private preparation files never rely on targetSdk scoped shared-storage access.
        self.adb("shell", "mkdir", "-p", "/data/local/tmp/ul-scale")
        # `push` is an adb host command: `adb shell push` fails with exit 127.
        self.adb("push", str(self.fixture) + "/.", "/data/local/tmp/ul-scale/", timeout=120)
        self.ensure_app_data_dir()
        self.adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files/scale-fixture")
        for file in sorted(self.fixture.iterdir()):
            self.adb("shell", "run-as", PACKAGE, "cp", "/data/local/tmp/ul-scale/" + file.name, "files/scale-fixture/" + file.name)
        # Byte-for-byte readback proves the actual run-as staging path.
        for file in sorted(self.fixture.iterdir()):
            copied = self.adb("exec-out", "run-as", PACKAGE, "cat", "files/scale-fixture/" + file.name, binary=True, timeout=120)
            if hashlib.sha256(copied).digest() != hashlib.sha256(file.read_bytes()).digest():
                raise RuntimeError(f"private staging readback mismatch: {file.name}")
        self.report["staging_verified"] = True

    def collect_device(self, *, best_effort=False):
        filename = "android-preflight-evidence.json" if self.mode == "preflight" else "android-scale-evidence.json"
        text = self.adb("exec-out", "run-as", PACKAGE, "cat", "files/" + filename, best_effort=best_effort)
        if text:
            try:
                data = json.loads(text)
            except json.JSONDecodeError as error:
                # The device writes with AtomicFile, so this should not happen; if
                # it does, say what happened instead of leaking a parse trace.
                if best_effort:
                    return None
                raise ValueError(f"device evidence is not valid JSON: {error}") from None
            (self.evidence / "device.json").write_text(json.dumps(data, indent=2), encoding="utf-8")
            return data
        return None

    def start_logcat(self):
        """Keep a streaming log across framework restarts; record every reconnect."""
        def collect():
            try:
                with (self.evidence / "logcat.txt").open("ab") as output, (self.evidence / "logcat-stderr.txt").open("ab") as errors:
                    while not self.log_stop.is_set():
                        try:
                            self.log_process = subprocess.Popen(
                                ["adb", "-s", self.serial, "logcat", "-v", "threadtime"],
                                stdout=output, stderr=errors)
                            self.log_started = True
                            while self.log_process.poll() is None and not self.log_stop.wait(0.2):
                                pass
                            if self.log_stop.is_set():
                                break
                            self.log_gaps.append({"elapsed_seconds": time.monotonic() - self.started,
                                                  "reason": "logcat disconnected; ring-buffer replay on reconnect"})
                        except OSError as error:
                            self.log_gaps.append({"elapsed_seconds": time.monotonic() - self.started,
                                                  "reason": type(error).__name__})
                        self.log_stop.wait(1)
            finally:
                if self.log_process is not None and self.log_process.poll() is None:
                    self.log_process.kill()
                    self.log_process.wait(timeout=5)
        self.log_thread = threading.Thread(target=collect, daemon=True)
        self.log_thread.start()

    def stop_logcat(self):
        self.log_stop.set()
        if self.log_thread:
            if self.log_process is not None and self.log_process.poll() is None:
                self.log_process.kill()
            self.log_thread.join(timeout=max(0, min(6, self.active_deadline - time.monotonic())))
            if self.log_thread.is_alive():
                self.log_gaps.append({"reason": "collector did not stop within six seconds"})
        self.report["logcat_gaps"] = self.log_gaps
        path = self.evidence / "logcat.txt"
        collection = {"started": self.log_started, "stream_bytes": path.stat().st_size if path.exists() else 0,
                      "stopped": self.log_thread is not None and not self.log_thread.is_alive(), "gaps": self.log_gaps}
        (self.evidence / "logcat-collection.json").write_text(json.dumps(collection), encoding="utf-8")

    def diagnostics(self, *, failure: bool):
        if not self.serial:
            return
        try:
            sections = []
            for args in (("shell", "am", "get-started-user-state", "0"),
                         ("shell", "cmd", "package", "list", "packages", "-U", PACKAGE),
                         ("shell", "pm", "list", "instrumentation"),
                         ("shell", "dumpsys", "package", PACKAGE)):
                sections.append(" ".join(args) + "\n" + "\n".join(self.adb(*args, best_effort=True, timeout=10).splitlines()[:120]))
            (self.evidence / "pm.txt").write_text("\n".join(sections), encoding="utf-8")
            tail = self.adb("logcat", "-d", "-v", "threadtime", best_effort=True, timeout=15)
            with (self.evidence / "logcat.txt").open("a", encoding="utf-8") as output:
                output.write(tail)
            log = (self.evidence / "logcat.txt").read_text(encoding="utf-8", errors="replace")
            ours, foreign = crash_markers(log)
            self.report["crash_detected"] = bool(ours)
            if foreign:
                # Recorded, never silent: unrelated emulator system processes
                # (SystemUI, launcher, gms) crash and ANR on their own over a
                # multi-hour chain, and failing the run for those would report a
                # product defect that does not exist.
                self.report["foreign_crash_markers"] = foreign[:20]
            self.collect_device(best_effort=True)
            if failure:
                png = self.adb("exec-out", "screencap", "-p", binary=True, best_effort=True)
                (self.evidence / "failure.png").write_bytes(png)
                self.adb("shell", "uiautomator", "dump", "/data/local/tmp/ul-scale-window.xml", best_effort=True)
                tree = self.adb("exec-out", "cat", "/data/local/tmp/ul-scale-window.xml", best_effort=True)
                (self.evidence / "failure-ui.xml").write_text(tree, encoding="utf-8")
                self.collect_coldstart_forensics()
        except (OSError, RuntimeError, subprocess.TimeoutExpired, ValueError):
            self.report["diagnostic_error"] = True

    def collect_coldstart_forensics(self) -> None:
        """Best-effort retrieval of the device-side coldstart forensics the
        instrumentation wrote before teardown. Never faked and never required:
        absence is recorded as absent, a malformed payload is kept and flagged,
        and neither changes a validation verdict (the oracle never reads these).
        """
        collected: dict = {}
        try:
            for source, target in COLDSTART_FORENSICS_DEVICE_FILES.items():
                payload = self.adb("exec-out", "run-as", PACKAGE, "cat", source, binary=True, best_effort=True, timeout=15)
                if not payload or not isinstance(payload, (bytes, bytearray)):
                    collected[target] = "absent"
                    continue
                (self.evidence / target).write_bytes(payload)
                record = {"sha256": hashlib.sha256(payload).hexdigest(), "bytes": len(payload)}
                if target.endswith(".json"):
                    try:
                        parsed = json.loads(payload.decode("utf-8"))
                        record["json_valid"] = isinstance(parsed, dict)
                        record["sha_match"] = isinstance(parsed, dict) and parsed.get("sha") == self.sha
                    except (UnicodeDecodeError, json.JSONDecodeError):
                        record["json_valid"] = False
                collected[target] = record
        except (OSError, RuntimeError, subprocess.TimeoutExpired, ValueError):
            collected["error"] = True
        self.report["coldstart_forensics"] = collected

    def record_memory(self, phase: str, text: str) -> None:
        with (self.evidence / "memory.txt").open("a", encoding="utf-8") as memory:
            memory.write(f"phase={phase} elapsed={time.monotonic() - self.started:.3f}\n")
            memory.write(text)

    def uptime_ms(self) -> float | None:
        """Best-effort device uptime; None when the probe itself hiccups."""
        text = self.adb("shell", "cat", "/proc/uptime", timeout=30, best_effort=True)
        try:
            return float(text.split()[0]) * 1000
        except (IndexError, ValueError):
            return None

    def poll_once(self, phase: str) -> None:
        """One polling tick: sample the device and record memory evidence.

        Every probe here is best effort on purpose. A transient adb hiccup under
        a heavy chain must not be recorded as a timeout -- only a real deadline
        does that -- but it is written to memory.txt so it stays visible.
        """
        device = None
        try:
            device = self.collect_device(best_effort=True)
            if device and device.get("activeDeadlineElapsedMs"):
                uptime = self.uptime_ms()
                if uptime is not None and uptime >= device["activeDeadlineElapsedMs"]:
                    raise ScaleDeadlineError("instrumentation operation deadline exceeded")
            self.record_memory(phase, self.adb("shell", "dumpsys", "meminfo", PACKAGE,
                                               timeout=60, best_effort=True))
        except ScaleDeadlineError:
            raise
        except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
            self.record_memory(phase, f"device probe failed: {type(error).__name__}: {str(error)[:200]}\n")
        self.coldstart_host_probe(phase, device)

    def coldstart_host_probe(self, phase: str, device: dict | None) -> None:
        """Independent host a11y sample of a stuck coldstart (D-204, diagnostics).

        Only a `uiautomator dump` from a separate client connection can tell
        whether a screen the in-process observer keeps reading as the loading
        tree is actually ready (the D-203 situation). The probe is strictly
        bounded (at most two dumps per run, >=60s apart, only while the device
        evidence shows the coldstart stage running >=30s) and strictly
        diagnostic: every failure degrades into a probeFailed record and never
        raises into the run's judgment or validation.
        """
        state = self.report.setdefault("coldstart_host_probe", {"attempts": 0, "probes": [], "probeFailed": False})
        if phase != "chain" or state["attempts"] >= COLDSTART_HOST_PROBE_MAX or not isinstance(device, dict):
            return
        try:
            stages = device.get("stages")
            coldstart = stages.get("coldstart") if isinstance(stages, dict) else None
            if not isinstance(coldstart, dict) or coldstart.get("status") != "NOT_RUN" or "startedMs" not in coldstart:
                return
            uptime = self.uptime_ms()
            if uptime is None:
                return
            elapsed = float(uptime) - float(coldstart["startedMs"])
        except ScaleDeadlineError:
            raise
        except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired, TypeError) as error:
            # The gate itself reads device state; a hiccup while deciding must
            # not escape into the run. No dump was attempted, so the recorded
            # state stays untouched and the hiccup is written to memory.txt the
            # same way poll_once records its own probe failures.
            self.record_memory(phase, f"coldstart-host-probe gate-failed: {type(error).__name__}: {str(error)[:200]}\n")
            return
        if not math.isfinite(elapsed) or elapsed < COLDSTART_HOST_PROBE_MIN_ELAPSED_MS:
            return
        if state["probes"] and elapsed - state["probes"][-1].get("elapsed", 0) < COLDSTART_HOST_PROBE_SPACING_MS:
            return
        state["attempts"] += 1
        record = {"elapsed": round(elapsed)}
        path = COLDSTART_HOST_DUMP_DEVICE_PREFIX + uuid.uuid4().hex + ".xml"
        try:
            output = self.adb("shell", "uiautomator", "dump", path, timeout=30, best_effort=True)
            if "dumped to:" not in output or not output.strip().endswith(path):
                raise ValueError("UI dump did not confirm its fresh output path")
            xml = self.adb("exec-out", "cat", path, timeout=30, best_effort=True)
            if not xml.strip():
                # An empty read-back is not a dump: recording it as a successful
                # sample with zero bytes would be a silent false negative.
                raise ValueError("UI dump read back empty")
            data = xml.encode("utf-8")
            (self.evidence / COLDSTART_HOST_DUMP).write_bytes(data)
            record.update({
                "sha256": hashlib.sha256(data).hexdigest(),
                "bytes": len(data),
                "containsReadyMarker": READY_MARKER in xml,
                "containsLoadingMarker": COLDSTART_HOST_LOADING_MARKER in xml,
            })
        except ScaleDeadlineError:
            raise
        except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
            record["probeFailed"] = True
            record["error"] = str(error)[:200]
            state["probeFailed"] = True
        finally:
            # Cleanup is the last ancillary act of a diagnostic probe, so a
            # stuck adb here must not surface as an exception from a finally
            # block -- that would let a diagnostic change the run outcome. An
            # exhausted deadline (ScaleDeadlineError is a RuntimeError) is
            # deliberately included: the phase loop re-checks the budget on its
            # next tick, so nothing is lost by not raising from here.
            try:
                self.adb("shell", "rm", "-f", path, best_effort=True, timeout=15)
            except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
                # A successful capture keeps its hash and markers: the sample is
                # real, only its device-side leftover could not be removed. It
                # is flagged as a cleanup anomaly, never as a lost sample.
                record["cleanupFailed"] = str(error)[:200]
                state["probeFailed"] = True
            state["probes"].append(record)
            self.record_memory(phase, f"coldstart-host-probe {json.dumps(record, sort_keys=True)}\n")

    def check_phase_budget(self, phase: str, deadline: float) -> float:
        try:
            return remaining_seconds(deadline, time.monotonic())
        except ScaleDeadlineError:
            raise ScaleDeadlineError(
                f"phase {phase} exceeded {'remaining global' if PHASE_BUDGETS.get(phase) is None else str(PHASE_BUDGETS[phase]) + 's'} budget") from None

    def phase(self, phase: str):
        # Only the host kills the exact app between phases; instrumentation never kills itself.
        validate_app_ready(self.evidence, self.report)
        self.adb("shell", "am", "force-stop", PACKAGE)
        test_class, test_method = (PREFLIGHT_CLASS, PREFLIGHT_METHOD) if self.mode == "preflight" else (TEST_CLASS, TEST_METHOD)
        command = ["adb", "-s", self.serial, "shell", "am", "instrument", "-w", "-r",
                   "-e", "class", test_class + "#" + test_method, "-e", "scalePhase", phase,
                   "-e", "expectedSha", self.sha, "-e", "deadlineElapsedMs", str(self.device_deadline), RUNNER]
        started = time.monotonic()
        limit = PHASE_BUDGETS.get(phase)
        phase_deadline = min(self.deadline, started + limit) if limit is not None else self.deadline
        self.active_deadline = phase_deadline
        path = self.evidence / f"instrumentation-{phase}.txt"
        case = {"name": phase, "status": "ERROR", "seconds": 0}
        self.cases.append(case)
        with path.open("wb") as output:
            process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
            try:
                while process.poll() is None:
                    time.sleep(min(3, self.check_phase_budget(phase, phase_deadline)))
                    self.poll_once(phase)
                if process.returncode != 0:
                    raise RuntimeError("instrumentation shell failed")
                self.check_phase_budget(phase, phase_deadline)
                instrumentation_pass(path.read_text(encoding="utf-8", errors="replace"), test_class, test_method)
                self.collect_device()
                case["status"] = "PASS"
                self.report["phases"].append(phase)
            except ScaleDeadlineError:
                self.report["timed_out"] = True
                raise
            finally:
                if process.poll() is None:
                    process.kill()  # This exact adb client only; never the shared adb server.
                    process.wait(timeout=10)
                case["seconds"] = time.monotonic() - started
                self.active_deadline = self.deadline

    def write_reports(self):
        self.report["elapsed_seconds"] = time.monotonic() - self.started
        (self.evidence / "host.json").write_text(json.dumps(self.report, indent=2), encoding="utf-8")
        phases = ("preflight",) if self.mode == "preflight" else PHASES
        setup_error = not self.setup_complete
        report_error = self.report["status"] != "PASS" and not setup_error and all(case["status"] == "PASS" for case in self.cases)
        root = ET.Element("testsuite", name="AndroidPreflight" if self.mode == "preflight" else "AndroidMaximumScale",
                          tests=str(len(phases) + int(setup_error) + int(report_error)), failures="0",
                          errors=str(sum(case["status"] != "PASS" for case in self.cases) + int(setup_error) + int(report_error)),
                          skipped=str(len(phases) - len(self.cases)))
        if setup_error or report_error:
            extra = ET.SubElement(root, "testcase", name="setup" if setup_error else "evidence", classname="AndroidCI", time="0")
            ET.SubElement(extra, "error", message=self.report.get("errorMessage", self.report.get("validationError", "incomplete evidence")))
        by_phase = {case["name"]: case for case in self.cases}
        for phase in phases:
            case = by_phase.get(phase)
            child = ET.SubElement(root, "testcase", name=phase, classname=PREFLIGHT_CLASS if self.mode == "preflight" else TEST_CLASS, time=str(case["seconds"] if case else 0))
            if not case:
                ET.SubElement(child, "skipped", message="NOT_RUN after earlier failure")
            elif case["status"] != "PASS":
                ET.SubElement(child, "error", message="instrumentation incomplete or failed; see phase log")
        ET.ElementTree(root).write(self.evidence / "junit.xml", encoding="utf-8", xml_declaration=True)
        device_path = self.evidence / "device.json"
        if device_path.exists() and self.mode == "maximum":
            device = json.loads(device_path.read_text(encoding="utf-8"))
            for stage in STAGES:
                device.setdefault("stages", {}).setdefault(stage, {"status": "NOT_RUN"})
            device_path.write_text(json.dumps(device, indent=2), encoding="utf-8")

    def run(self) -> int:
        try:
            # Even when boot used all execution time, emit host/JUnit reports
            # without performing another device command.
            remaining_seconds(self.deadline, time.monotonic())
            self.configure()
            self.install()
            self.setup_complete = True
            for phase in (("preflight",) if self.mode == "preflight" else PHASES):
                self.phase(phase)
            self.report["status"] = "PASS"
        except (Exception, KeyboardInterrupt) as error:
            self.report["errorType"] = type(error).__name__
            self.report["errorMessage"] = str(error)[:800]
            self.report["status"] = "ERROR"
            if isinstance(error, ScaleDeadlineError):
                self.report["timed_out"] = True
        finally:
            # Diagnostics share the action deadline and leave room for report
            # writes plus the emulator action's own cleanup.
            self.active_deadline = time.monotonic() + DIAGNOSTIC_SECONDS
            if self.outer_deadline is not None:
                self.active_deadline = min(self.active_deadline, self.outer_deadline - CLEANUP_RESERVE_SECONDS)
            self.stop_logcat()
            self.diagnostics(failure=self.report["status"] != "PASS")
            if self.serial and self.report["status"] != "PASS":
                try:
                    self.adb("shell", "am", "force-stop", PACKAGE, best_effort=True, timeout=10)
                except (RuntimeError, OSError, subprocess.TimeoutExpired):
                    pass
            if self.report["crash_detected"] and self.report["status"] == "PASS":
                self.report["status"] = "FAIL"
            self.write_reports()
        try:
            (validate_preflight if self.mode == "preflight" else validate_evidence)(self.evidence, self.sha)
        except (ValueError, OSError, KeyError, ET.ParseError) as error:
            if self.report["status"] == "PASS":
                self.report["status"] = "FAIL"
            self.report["validationError"] = str(error)
            self.write_reports()
            return 1
        return 0
