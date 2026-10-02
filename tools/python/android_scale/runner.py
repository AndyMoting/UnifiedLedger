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
from .result import PHASES, STAGES, TEST_CLASS, TEST_METHOD, crash_present, instrumentation_pass, validate_evidence

PACKAGE = "com.unifiedledger.android"
RUNNER = "com.unifiedledger.android.test/androidx.test.runner.AndroidJUnitRunner"


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
        config["size"] = re.findall(r"(?:Physical|Override) size: ([0-9]+x[0-9]+)", size)[-1]
        config["density"] = re.findall(r"(?:Physical|Override) density: ([0-9]+)", density)[-1]
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

    def ensure_app_data_dir(self, attempts: int = 5) -> None:
        """Materialize the app's data directory before using `run-as`.

        `pm install` does not create `/data/user/0/<pkg>` on this image (the
        platform creates it on first launch), and `run-as` refuses to stat a
        missing directory. Launch the app once, then stop it again so the chain
        still starts from a cold start.

        Launching immediately after the install hits a package-manager race: the
        install is complete (the platform logs `installation completed`) but
        ATMS still answers `START_CLASS_NOT_FOUND` (`result code=-92`) about a
        fifth of a second later. Resolve the launcher component and retry
        instead of trusting a single `am start`.
        """
        last = "no attempt made"
        for _ in range(attempts):
            resolved = self.adb("shell", "cmd", "package", "resolve-activity", "--brief", PACKAGE, timeout=60)
            component = resolved.strip().splitlines()[-1].strip() if resolved.strip() else ""
            if "/" not in component:
                last = f"launcher component not resolvable: {resolved.strip()!r}"
                time.sleep(2)
                continue
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
            data = json.loads(text)
            (self.evidence / "device.json").write_text(json.dumps(data, indent=2), encoding="utf-8")
            return data
        return None

    def diagnostics(self, *, failure: bool):
        if not self.serial:
            return
        try:
            log = self.adb("logcat", "-d", "-v", "threadtime", best_effort=True)
            (self.evidence / "logcat.txt").write_text(log, encoding="utf-8")
            self.report["crash_detected"] = crash_present(log)
            self.collect_device(best_effort=True)
            if failure:
                png = self.adb("exec-out", "screencap", "-p", binary=True, best_effort=True)
                (self.evidence / "failure.png").write_bytes(png)
                self.adb("shell", "uiautomator", "dump", "/data/local/tmp/ul-scale-window.xml", best_effort=True)
                tree = self.adb("exec-out", "cat", "/data/local/tmp/ul-scale-window.xml", best_effort=True)
                (self.evidence / "failure-ui.xml").write_text(tree, encoding="utf-8")
        except (OSError, subprocess.TimeoutExpired, ValueError):
            self.report["diagnostic_error"] = True

    def phase(self, phase: str):
        # Only the host kills the exact app between phases; instrumentation never kills itself.
        self.adb("shell", "am", "force-stop", PACKAGE)
        command = ["adb", "-s", self.serial, "shell", "am", "instrument", "-w", "-r",
                   "-e", "class", TEST_CLASS + "#" + TEST_METHOD, "-e", "scalePhase", phase,
                   "-e", "expectedSha", self.sha, "-e", "deadlineElapsedMs", str(self.device_deadline), RUNNER]
        started = time.monotonic()
        path = self.evidence / f"instrumentation-{phase}.txt"
        case = {"name": phase, "status": "ERROR", "seconds": 0}
        self.cases.append(case)
        with path.open("wb") as output:
            process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
            try:
                while process.poll() is None:
                    remaining_seconds(self.deadline, time.monotonic())
                    time.sleep(min(3, remaining_seconds(self.deadline, time.monotonic())))
                    device = self.collect_device(best_effort=True)
                    if device and device.get("activeDeadlineElapsedMs"):
                        uptime = float(self.adb("shell", "cat", "/proc/uptime").split()[0]) * 1000
                        if uptime >= device["activeDeadlineElapsedMs"]:
                            raise ScaleDeadlineError("instrumentation operation deadline exceeded")
                    with (self.evidence / "memory.txt").open("a", encoding="utf-8") as memory:
                        memory.write(f"phase={phase} elapsed={time.monotonic() - self.started:.3f}\n")
                        memory.write(self.adb("shell", "dumpsys", "meminfo", PACKAGE, timeout=15))
                if process.returncode != 0:
                    raise RuntimeError("instrumentation shell failed")
                instrumentation_pass(path.read_text(encoding="utf-8", errors="replace"))
                self.collect_device()
                case["status"] = "PASS"
                self.report["phases"].append(phase)
            except (ScaleDeadlineError, subprocess.TimeoutExpired):
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
