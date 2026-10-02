"""CI-only bounded host driver. Never invokes Gradle or touches a non-owned device."""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree as ET

from .fixture import load_manifest, validate_manifest
from .result import (PHASES, PACKAGE, STAGES, TEST_CLASS, TEST_METHOD, crash_markers,
                     instrumentation_pass, validate_evidence)

RUNNER = "com.unifiedledger.android.test/androidx.test.runner.AndroidJUnitRunner"

# Host-side per-phase budgets. The device enforces its own stage limits (180 s
# by default, 4 h for preparation), so these only catch a phase that is wedged
# rather than slow; the margin keeps a slow-but-progressing phase alive. Without
# them one stuck phase consumes the whole global budget and the remaining phases
# are never attempted at all.
PHASE_BUDGETS = {"prepare": 14700, "chain": 22500, "reopen": 480, "replay": 480, "final-reopen": 480}

# The app manifest declares this under its own LAUNCHER filter; it is the
# fallback when the platform's `resolve-activity` does not answer.
LAUNCHER_FALLBACK = f"{PACKAGE}/.MainActivity"


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
    def __init__(self, fixture: Path, evidence: Path, app: Path, test: Path, sha: str):
        if (os.environ.get("GITHUB_ACTIONS"), os.environ.get("RUNNER_ENVIRONMENT"), os.environ.get("RUNNER_OS")) != ("true", "github-hosted", "Linux"):
            raise ValueError("this driver is CI-only; local devices are forbidden")
        if not re.fullmatch(r"[0-9a-f]{40}", sha):
            raise ValueError("full lowercase SHA required")
        self.fixture, self.evidence, self.app, self.test, self.sha = fixture, evidence, app, test, sha
        self.started = time.monotonic()
        # Bounded below the 240-minute emulator step so that reports are written
        # and evidence can still be uploaded before the step is killed.
        self.deadline = self.started + 13800
        self.serial = None
        self.device_deadline = 0
        self.report = {"sha": sha, "status": "ERROR", "timed_out": False, "crash_detected": False,
                       "phases": [], "apk_sha256": {}, "config": {}}
        self.cases = []
        evidence.mkdir(parents=True, exist_ok=False)

    def command(self, command: list[str], *, timeout: float = 30, binary: bool = False,
                best_effort: bool = False) -> str | bytes:
        bound = timeout if best_effort else remaining_seconds(self.deadline, time.monotonic(), timeout)
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
            except (RuntimeError, subprocess.TimeoutExpired) as error:
                last = error
                time.sleep(2)
        raise RuntimeError(f"adb root did not settle after {attempts} attempts: {last}")

    def await_framework(self, timeout: float = 300) -> None:
        """Wait for the framework to answer again after `stop`/`start`.

        `sys.boot_completed` keeps its value from the previous boot across a
        framework restart, so waiting on that property alone lets the driver
        issue `wm`/`settings` calls while the window service is still down
        ("Failure calling service window: Broken pipe"). Probe a real framework
        command instead.
        """
        deadline = min(self.deadline, time.monotonic() + timeout)
        while True:
            remaining_seconds(deadline, time.monotonic())
            try:
                self.adb("shell", "wm", "size", timeout=30)
                return
            except (RuntimeError, subprocess.TimeoutExpired):
                time.sleep(2)

    def configure(self):
        devices = self.adb("devices")
        matches = re.findall(r"^(emulator-[0-9]+)\s+device$", devices, re.MULTILINE)
        if len(matches) != 1:
            raise ValueError("one owned CI emulator required")
        self.serial = matches[0]
        self.serial = owned_serial(devices, self.adb("emu", "avd", "name"))
        self.root_and_settle()
        self.adb("shell", "setprop", "persist.sys.locale", "zh-CN")
        self.adb("shell", "setprop", "persist.sys.timezone", "Asia/Shanghai")
        self.adb("shell", "stop")
        self.adb("shell", "start")
        boot_deadline = min(self.deadline, time.monotonic() + 300)
        while self.adb("shell", "getprop", "sys.boot_completed").strip() != "1":
            remaining_seconds(boot_deadline, time.monotonic())
            time.sleep(1)
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
        """The app's launcher component, from the platform when it answers.

        `cmd package resolve-activity` needs an explicit MAIN/LAUNCHER intent:
        the bare-package form answers "No activity found" on API 36, and asking
        it five times only wastes ten seconds. Fall back to the component the
        app manifest declares under its own LAUNCHER filter, and let `am start`
        fail loudly if that is wrong.
        """
        queries = (
            ("--brief", "-a", "android.intent.action.MAIN",
             "-c", "android.intent.category.LAUNCHER", "-p", PACKAGE),
            ("--brief", PACKAGE),
        )
        for query in queries:
            resolved = self.adb("shell", "cmd", "package", "resolve-activity", *query, timeout=60)
            component = resolved.strip().splitlines()[-1].strip() if resolved.strip() else ""
            if "/" in component and "No activity found" not in resolved:
                return component
        return LAUNCHER_FALLBACK

    def ensure_app_data_dir(self, attempts: int = 5) -> None:
        """Materialize the app's data directory before using `run-as`.

        `pm install` does not create `/data/user/0/<pkg>` on this image (the
        platform creates it on first launch), and `run-as` refuses to stat a
        missing directory. Launch the app once, then stop it again so the chain
        still starts from a cold start.

        Launching immediately after the install hits a package-manager race: the
        install is complete (the platform logs `installation completed`) but
        ATMS still answers `START_CLASS_NOT_FOUND` (`result code=-92`) about a
        fifth of a second later, so the launch is retried.
        """
        component = self.launcher_component()
        last = "no attempt made"
        for _ in range(attempts):
            try:
                self.adb("shell", "am", "start", "-W", "-n", component, timeout=180)
                self.adb("shell", "am", "force-stop", PACKAGE)
                return
            except RuntimeError as error:
                last = str(error)
                time.sleep(2)
        raise RuntimeError(f"could not launch {PACKAGE} to create its data directory: {last}")

    def install(self):
        validate_manifest(load_manifest(self.fixture / "manifest.json"), self.fixture)
        for role, apk in (("app", self.app), ("test", self.test)):
            self.report["apk_sha256"][role] = hashlib.sha256(apk.read_bytes()).hexdigest()
            output = self.adb("install", "-t", str(apk), timeout=120)
            # `adb install` exits 0 even when the install fails; the verdict is in
            # the output, so a silent failure here would surface much later as a
            # confusing "activity does not exist" or a missing device evidence file.
            if "Success" not in output:
                raise RuntimeError(f"install of the {role} APK failed: {output.strip()[:400]}")
        # Private preparation files never rely on targetSdk scoped shared-storage access.
        self.adb("shell", "mkdir", "-p", "/data/local/tmp/ul-scale")
        # `push` is an adb host command: `adb shell push` fails with exit 127.
        self.adb("push", str(self.fixture) + "/.", "/data/local/tmp/ul-scale/", timeout=120)
        self.ensure_app_data_dir()
        self.adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files/scale-fixture")
        for file in sorted(self.fixture.iterdir()):
            self.adb("shell", "run-as", PACKAGE, "cp", "/data/local/tmp/ul-scale/" + file.name, "files/scale-fixture/" + file.name)
        self.adb("logcat", "-c")

    def collect_device(self, *, best_effort=False):
        text = self.adb("exec-out", "run-as", PACKAGE, "cat", "files/android-scale-evidence.json", best_effort=best_effort)
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

    def diagnostics(self, *, failure: bool):
        if not self.serial:
            return
        try:
            log = self.adb("logcat", "-d", "-v", "threadtime", best_effort=True)
            (self.evidence / "logcat.txt").write_text(log, encoding="utf-8")
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
        except (OSError, subprocess.TimeoutExpired, ValueError):
            self.report["diagnostic_error"] = True

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

    def check_phase_budget(self, phase: str, deadline: float) -> float:
        try:
            return remaining_seconds(deadline, time.monotonic())
        except ScaleDeadlineError:
            raise ScaleDeadlineError(
                f"phase {phase} did not finish within its {PHASE_BUDGETS[phase]}s host budget") from None

    def phase(self, phase: str):
        # Only the host kills the exact app between phases; instrumentation never kills itself.
        self.adb("shell", "am", "force-stop", PACKAGE)
        command = ["adb", "-s", self.serial, "shell", "am", "instrument", "-w", "-r",
                   "-e", "class", TEST_CLASS + "#" + TEST_METHOD, "-e", "scalePhase", phase,
                   "-e", "expectedSha", self.sha, "-e", "deadlineElapsedMs", str(self.device_deadline), RUNNER]
        started = time.monotonic()
        phase_deadline = min(self.deadline, started + PHASE_BUDGETS[phase])
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
                instrumentation_pass(path.read_text(encoding="utf-8", errors="replace"))
                self.collect_device()
                case["status"] = "PASS"
                self.report["phases"].append(phase)
            except ScaleDeadlineError:
                self.report["timed_out"] = True
                self.diagnostics(failure=True)
                self.adb("shell", "am", "force-stop", PACKAGE, best_effort=True)
                raise
            finally:
                if process.poll() is None:
                    process.kill()  # This exact adb client only; never the shared adb server.
                    process.wait(timeout=10)
                case["seconds"] = time.monotonic() - started

    def write_reports(self):
        self.report["elapsed_seconds"] = time.monotonic() - self.started
        (self.evidence / "host.json").write_text(json.dumps(self.report, indent=2), encoding="utf-8")
        root = ET.Element("testsuite", name="AndroidMaximumScale", tests="5", failures="0",
                          errors=str(sum(case["status"] != "PASS" for case in self.cases)),
                          skipped=str(len(PHASES) - len(self.cases)))
        by_phase = {case["name"]: case for case in self.cases}
        for phase in PHASES:
            case = by_phase.get(phase)
            child = ET.SubElement(root, "testcase", name=phase, classname=TEST_CLASS, time=str(case["seconds"] if case else 0))
            if not case:
                ET.SubElement(child, "skipped", message="NOT_RUN after earlier failure")
            elif case["status"] != "PASS":
                ET.SubElement(child, "error", message="instrumentation incomplete or failed; see phase log")
        ET.ElementTree(root).write(self.evidence / "junit.xml", encoding="utf-8", xml_declaration=True)
        device_path = self.evidence / "device.json"
        if device_path.exists():
            device = json.loads(device_path.read_text(encoding="utf-8"))
            for stage in STAGES:
                device.setdefault("stages", {}).setdefault(stage, {"status": "NOT_RUN"})
            device_path.write_text(json.dumps(device, indent=2), encoding="utf-8")

    def run(self) -> int:
        try:
            self.configure()
            self.install()
            for phase in PHASES:
                self.phase(phase)
            self.report["status"] = "PASS"
        except (Exception, KeyboardInterrupt) as error:
            self.report["errorType"] = type(error).__name__
            self.report["errorMessage"] = str(error)[:800]
            self.report["status"] = "ERROR"
        finally:
            self.diagnostics(failure=self.report["status"] != "PASS")
            if self.report["crash_detected"]:
                self.report["status"] = "FAIL"
            self.write_reports()
        try:
            validate_evidence(self.evidence, self.sha)
        except (ValueError, OSError, KeyError, ET.ParseError) as error:
            self.report["status"] = "FAIL"
            self.report["validationError"] = str(error)
            self.write_reports()
            return 1
        return 0
