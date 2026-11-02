"""Two-shard execution of the ordinary Android device regression.

Case authority is ``tools/ci/android-instrumented-inventory.txt`` (41
``class#method`` entries over 9 classes). Shard authority is
``tools/ci/android-instrumented-shards.txt`` (one ``shard<TAB>class`` line per
test class).

Each shard boots its own CI-owned AVD, installs both APKs directly, and runs
``am instrument -w -r -e class <class#method,...>`` for exactly its own cases,
so no Gradle task ever runs inside the emulator session. The proof of a shard is
the instrumentation transcript plus the JUnit XML written here: the executed
case set must equal the manifest subset exactly, so a missing, extra, failed, or
skipped case cannot pass as a shell exit code relayed by a third-party action.
"""
from __future__ import annotations

import hashlib
import json
import re
import shlex
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree as ET

from .result import strict_json
from .runner import owned_device_matches, owned_serial

SHARDS = (1, 2)
PACKAGE = "com.unifiedledger.android"
TEST_PACKAGE = PACKAGE + ".test"
RUNNER = TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"
AVD_PREFIX = "ul-instrumented"
# Evidence schema and the two execution modes:
#   shard - one half of the ordinary inventory on its own runner (trusted
#           same-repository pull request or manual dispatch);
#   fork  - the whole ordinary inventory on one runner for a fork pull request,
#           which cannot produce or consume a trusted APK bundle. It builds its
#           own APKs in the runner and shares no artifact with the shard path.
SCHEMA = 2
MODE_SHARD = "shard"
MODE_FORK = "fork"
FORK_AVD = "ul-instrumented-fork"

# The pinned emulator configuration the workflow declares. These are the
# observable facts a shard re-checks before installing anything, so a silently
# re-imaged or re-profiled runner cannot produce evidence under a false label.
EXPECTED_SDK = "36"
EXPECTED_ABI = "x86_64"
EXPECTED_SIZE = "1080x2400"
EXPECTED_CORES = 2
# 2048M of guest RAM reports slightly under 2 GiB in /proc/meminfo; the band
# catches a default-sized emulator without failing on kernel accounting.
MEM_TOTAL_BAND_KB = (1_500_000, 2_600_000)

CASE = re.compile(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*#[A-Za-z_$][\w$]*")
SHARD_LINE = re.compile(r"([0-9]+)\t([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)")

STATUS_FIELD = "INSTRUMENTATION_STATUS: "
STATUS_CODE = "INSTRUMENTATION_STATUS_CODE: "
COMPLETION_CODE = "INSTRUMENTATION_CODE: "
# Raw `am instrument` markers that mean the run did not complete as a clean
# JUnit pass. They are checked verbatim in addition to the parsed records.
FAILURE_MARKERS = ("FAILURES!!!", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED",
                   "Process crashed", "shortMsg=")
TERMINAL_STATUS = (-1, -2, -3, -4)
SKIPPED_STATUS = (-3, -4)


def avd_name(shard: int) -> str:
    if shard not in SHARDS:
        raise ValueError(f"shard must be one of {SHARDS}")
    return f"{AVD_PREFIX}-{shard}"


def read_cases(manifest: Path) -> tuple[str, ...]:
    """Explicit ordinary inventory, comments and blank lines ignored."""
    cases = [line.strip() for line in manifest.read_text(encoding="utf-8").splitlines()]
    cases = [line for line in cases if line and not line.startswith("#")]
    if not cases:
        raise ValueError("empty ordinary device inventory")
    if len(cases) != len(set(cases)):
        raise ValueError("duplicated ordinary device inventory case")
    for case in cases:
        if not CASE.fullmatch(case):
            raise ValueError("invalid ordinary device inventory entry: " + case)
    return tuple(cases)


def read_shards(manifest: Path) -> dict[int, tuple[str, ...]]:
    """`shard<TAB>class` lines, the same shape as the ledger-data shard list."""
    entries: dict[int, list[str]] = {}
    owner: dict[str, int] = {}
    for number, line in enumerate(manifest.read_text(encoding="utf-8").splitlines(), 1):
        text = line.strip()
        if not text or text.startswith("#"):
            continue
        match = SHARD_LINE.fullmatch(text)
        if not match:
            raise ValueError(f"invalid shard manifest line {number}")
        shard, name = int(match[1]), match[2]
        if shard not in SHARDS:
            raise ValueError(f"shard {shard} is not one of {SHARDS}")
        if name in owner:
            raise ValueError("class assigned to more than one shard: " + name)
        owner[name] = shard
        entries.setdefault(shard, []).append(name)
    if not entries:
        raise ValueError("empty shard manifest")
    return {shard: tuple(names) for shard, names in sorted(entries.items())}


def resolve(cases: tuple[str, ...], shards: dict[int, tuple[str, ...]]) -> dict[int, tuple[str, ...]]:
    """Per-shard expected cases; the split must partition the inventory exactly."""
    if set(shards) != set(SHARDS):
        raise ValueError(f"shard manifest must define exactly shards {SHARDS}")
    classes = {case.split("#", 1)[0] for case in cases}
    assigned = [name for names in shards.values() for name in names]
    if len(assigned) != len(set(assigned)):
        raise ValueError("class assigned to more than one shard")
    unknown = sorted(set(assigned) - classes)
    missing = sorted(classes - set(assigned))
    if unknown or missing:
        raise ValueError(f"shard manifest does not partition the inventory classes: "
                         f"missing={missing} unknown={unknown}")
    plan = {shard: tuple(case for case in cases if case.split("#", 1)[0] in set(names))
            for shard, names in shards.items()}
    for shard in SHARDS:
        if not plan[shard]:
            raise ValueError(f"shard {shard} has no cases")
    union = [case for shard in SHARDS for case in plan[shard]]
    if len(union) != len(set(union)) or set(union) != set(cases):
        raise ValueError("shard case sets are not disjoint over the whole inventory")
    return plan


def check_partition(inventory: Path, shards: Path) -> dict[int, tuple[str, ...]]:
    return resolve(read_cases(inventory), read_shards(shards))


@dataclass(frozen=True)
class InstrumentationRun:
    """Parsed `am instrument -w -r` transcript."""

    started: tuple[str, ...]
    passed: tuple[str, ...]
    failed: tuple[tuple[str, int], ...]
    code: int | None
    reported: int | None
    markers: tuple[str, ...]


def _identity(fields: dict[str, str]) -> str | None:
    classname, test = fields.get("class", ""), fields.get("test", "")
    if not classname or not test:
        return None
    return classname + "#" + test


def parse_instrumentation(text: str) -> InstrumentationRun:
    """One record per status code; the completion code and final count are kept."""
    started: list[str] = []
    passed: list[str] = []
    failed: list[tuple[str, int]] = []
    markers: list[str] = []
    fields: dict[str, str] = {}
    code: int | None = None
    reported: int | None = None
    for line in text.splitlines():
        if line.startswith(STATUS_FIELD):
            key, _, value = line[len(STATUS_FIELD):].partition("=")
            if key:
                fields[key] = value
            continue
        if line.startswith(STATUS_CODE):
            try:
                value = int(line[len(STATUS_CODE):].strip())
            except ValueError:
                raise ValueError("invalid instrumentation status code") from None
            identity, fields = _identity(fields), {}
            if identity is None:
                # Records that carry no test identity are not test events; a
                # terminal one, however, is a failure the runner could not
                # attribute to a case and must not be dropped.
                if value in TERMINAL_STATUS:
                    raise ValueError("instrumentation failure without a test identity")
                continue
            if value == 1:
                started.append(identity)
            elif value == 0:
                passed.append(identity)
            elif value in TERMINAL_STATUS:
                failed.append((identity, value))
            else:
                raise ValueError(f"unexpected instrumentation status code: {value}")
            continue
        if line.startswith(COMPLETION_CODE):
            try:
                code = int(line[len(COMPLETION_CODE):].strip())
            except ValueError:
                raise ValueError("invalid instrumentation completion code") from None
            continue
        count = re.fullmatch(r"OK \((\d+) tests?\)", line.strip())
        if count:
            reported = int(count[1])
        total = re.match(r"^Tests run: (\d+),", line)
        if total:
            reported = int(total[1])
        for marker in FAILURE_MARKERS:
            if marker in line and marker not in markers:
                markers.append(marker)
    return InstrumentationRun(tuple(started), tuple(passed), tuple(failed), code, reported, tuple(markers))


def check_run(run: InstrumentationRun, expected: tuple[str, ...]) -> None:
    """Require exactly the shard's cases to have started and passed."""
    expected_set = set(expected)
    if run.markers:
        raise ValueError("instrumentation reported failure markers: " + ", ".join(run.markers))
    if run.failed:
        case, status = run.failed[0]
        raise ValueError(f"instrumented case did not pass: {case} (status {status})")
    if run.code != -1:
        raise ValueError("missing instrumentation completion code -1")
    for label, values in (("started", run.started), ("passed", run.passed)):
        if len(values) != len(set(values)):
            raise ValueError(f"duplicated {label} instrumented case")
    if set(run.started) != expected_set:
        raise ValueError("started case set differs from the shard manifest")
    missing = sorted(expected_set - set(run.passed))
    extra = sorted(set(run.passed) - expected_set)
    if missing or extra:
        raise ValueError(f"executed case set differs from the shard manifest: "
                         f"missing={missing[:3]} extra={extra[:3]}")
    if run.reported != len(expected):
        raise ValueError(f"runner reported {run.reported} tests, expected {len(expected)}")


def junit_name(mode: str, shard: int | None) -> str:
    return "junit-fork.xml" if mode == MODE_FORK else f"junit-shard-{shard}.xml"


def junit_suite(mode: str, shard: int | None) -> str:
    return "AndroidInstrumentedFork" if mode == MODE_FORK else f"AndroidInstrumentedShard{shard}"


def write_junit(path: Path, name: str, expected: tuple[str, ...], run: InstrumentationRun | None,
                error: str | None = None) -> None:
    """JUnit XML that states failure/error/skipped per case, including missing ones."""
    observed: dict[str, int] = {}
    if run is not None:
        for case, code in run.failed:
            observed[case] = code
        for case in run.passed:
            observed.setdefault(case, 0)
    failures = errors = skipped = children = 0
    root = ET.Element("testsuite", name=name)
    for case in sorted(expected):
        classname, _, name = case.partition("#")
        child = ET.SubElement(root, "testcase", classname=classname, name=name, time="0")
        children += 1
        code = observed.get(case)
        if code == 0:
            continue
        if code == -2:
            ET.SubElement(child, "failure", message="instrumented case failed")
            failures += 1
        elif code == -1:
            ET.SubElement(child, "error", message="instrumented case errored")
            errors += 1
        elif code in SKIPPED_STATUS:
            ET.SubElement(child, "skipped", message=f"instrumented case did not execute (status {code})")
            skipped += 1
        elif run is not None and case in run.started:
            ET.SubElement(child, "error", message="case started but never completed")
            errors += 1
        else:
            ET.SubElement(child, "error", message="expected case did not run")
            errors += 1
    for case in sorted(set(observed) - set(expected)):
        classname, _, name = case.partition("#")
        child = ET.SubElement(root, "testcase", classname=classname, name=name, time="0")
        ET.SubElement(child, "error", message="executed case is not in the shard manifest")
        children += 1
        errors += 1
    if error is not None:
        child = ET.SubElement(root, "testcase", classname="AndroidCI", name="setup", time="0")
        ET.SubElement(child, "error", message=error[:800])
        children += 1
        errors += 1
    root.set("tests", str(children))
    root.set("failures", str(failures))
    root.set("errors", str(errors))
    root.set("skipped", str(skipped))
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)


def _check_junit(path: Path, expected: tuple[str, ...]) -> None:
    if not path.is_file():
        raise ValueError("missing shard JUnit report")
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
    if not suites:
        raise ValueError("missing JUnit test suites")
    observed: list[str] = []
    for suite in suites:
        cases = suite.findall("testcase")
        if int(suite.get("tests", "-1")) != len(cases) or not cases:
            raise ValueError("zero or inconsistent JUnit test count")
        if any(int(suite.get(key, "0")) != 0 for key in ("failures", "errors", "skipped", "disabled")):
            raise ValueError("shard JUnit report records a failure, error or skip")
        for case in cases:
            if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                raise ValueError("shard JUnit report contains a non-passing case")
            observed.append(case.get("classname", "") + "#" + case.get("name", ""))
    if len(observed) != len(set(observed)) or set(observed) != set(expected):
        raise ValueError("shard JUnit report case set differs from the shard manifest")


def _adb(*args: str, serial: str | None = None, timeout: float = 30, best_effort: bool = False) -> str:
    command = ["adb"] + (["-s", serial] if serial else []) + list(args)
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if result.returncode and not best_effort:
        detail = (result.stderr or result.stdout).decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"adb failed ({result.returncode}): {detail[:400]}")
    return result.stdout.decode("utf-8", errors="replace")


def _await_boot(serial: str, timeout: float = 180) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if _adb("shell", "getprop", "sys.boot_completed", serial=serial).strip() == "1":
                return
        except (RuntimeError, subprocess.TimeoutExpired):
            pass
        time.sleep(2)
    raise RuntimeError("emulator did not finish booting")


def _claim_device(avd: str) -> tuple[str, dict]:
    devices = _adb("devices", timeout=60)
    matches = owned_device_matches(devices)
    if len(matches) != 1:
        raise ValueError("one owned CI emulator required")
    candidate = matches[0]
    # Identity is the only permitted query before ownership is established.
    reported = _adb("-s", candidate, "emu", "avd", "name")
    serial = owned_serial(devices, reported, avd)
    _adb("wait-for-device", serial=serial, timeout=120)
    _await_boot(serial)
    sdk = _adb("shell", "getprop", "ro.build.version.sdk", serial=serial).strip()
    abi = _adb("shell", "getprop", "ro.product.cpu.abi", serial=serial).strip()
    size_text = _adb("shell", "wm", "size", serial=serial)
    size = re.search(r"(?:Physical|Override) size: ([0-9]+x[0-9]+)", size_text)
    density = re.search(r"(?:Physical|Override) density: ([0-9]+)", _adb("shell", "wm", "density", serial=serial))
    cpuinfo = _adb("shell", "cat", "/proc/cpuinfo", serial=serial)
    cores = len(re.findall(r"^processor\s*:", cpuinfo, re.MULTILINE))
    memory = re.search(r"MemTotal:\s*(\d+)\s*kB", _adb("shell", "cat", "/proc/meminfo", serial=serial))
    facts = {"serial": serial, "sdk": sdk, "abi": abi, "size": size[1] if size else "",
             "density": density[1] if density else "", "cores": cores,
             "mem_total_kb": int(memory[1]) if memory else 0}
    if sdk != EXPECTED_SDK:
        raise ValueError(f"emulator API {sdk!r} is not {EXPECTED_SDK}")
    if abi != EXPECTED_ABI:
        raise ValueError(f"emulator ABI {abi!r} is not {EXPECTED_ABI}")
    if facts["size"] != EXPECTED_SIZE:
        raise ValueError(f"emulator display {facts['size']!r} is not {EXPECTED_SIZE}")
    if cores != EXPECTED_CORES:
        raise ValueError(f"emulator has {cores} CPUs, expected {EXPECTED_CORES}")
    low, high = MEM_TOTAL_BAND_KB
    if not low <= facts["mem_total_kb"] <= high:
        raise ValueError(f"emulator memory {facts['mem_total_kb']} kB is outside {low}-{high} kB")
    return serial, facts


def _install(serial: str, role: str, apk: Path) -> None:
    output = _adb("install", "-t", str(apk), serial=serial, timeout=180)
    # `adb install` exits 0 even when the install fails; the verdict is in the
    # output, so a silent failure would surface much later as a confusing
    # "activity does not exist" or a missing device evidence file.
    if not re.search(r"^Success\s*$", output, re.MULTILINE) or "Failure" in output:
        raise RuntimeError(f"install of the {role} APK failed: {output.strip()[:400]}")


def _check_installed(serial: str) -> None:
    listed = _adb("shell", "pm", "list", "instrumentation", serial=serial)
    if f"instrumentation:{RUNNER} (target={PACKAGE})" not in listed.splitlines():
        raise RuntimeError("installed test runner does not target the app")
    for package in (PACKAGE, TEST_PACKAGE):
        if not _adb("shell", "pm", "path", "--user", "0", package, serial=serial).strip().startswith("package:"):
            raise RuntimeError(f"installed package is not available: {package}")


def _instrument(serial: str, expected: tuple[str, ...], raw: Path, timeout: float) -> int:
    # `#` is literal inside a shell word, but the class list is quoted anyway so
    # the device shell sees one argument regardless of its parser.
    command = f"am instrument -w -r -e class {shlex.quote(','.join(expected))} {RUNNER}"
    with raw.open("wb") as output:
        process = subprocess.Popen(["adb", "-s", serial, "shell", command],
                                   stdout=output, stderr=subprocess.STDOUT)
        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            process.kill()  # This exact adb client only; never the shared adb server.
            process.wait(timeout=10)
            raise RuntimeError("instrumentation exceeded its time budget") from None
    return process.returncode


def _dump_logcat(serial: str, path: Path) -> None:
    result = subprocess.run(["adb", "-s", serial, "logcat", "-d", "-v", "threadtime"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    path.write_bytes(result.stdout)


def _execute(*, mode: str, shard: int | None, expected: tuple[str, ...], evidence: Path, app: Path,
             test: Path, sha: str, avd: str, timeout: float) -> int:
    """Run `expected` on one owned emulator and write its evidence."""
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("full lowercase SHA required")
    label = "fork" if mode == MODE_FORK else f"shard {shard}"
    evidence.mkdir(parents=True, exist_ok=False)
    raw_path = evidence / "instrumentation-raw.txt"
    junit_path = evidence / junit_name(mode, shard)
    logcat_path = evidence / "logcat.txt"
    classes = [case.split("#", 1)[0] for case in expected]
    report = {"schema": SCHEMA, "mode": mode, "shard": shard, "sha": sha, "avd": avd, "runner": RUNNER,
              "package": PACKAGE, "classes": sorted(set(classes)), "expected": sorted(expected),
              "device": {}, "apk_sha256": {}, "instrumentation_exit": None,
              "instrumentation": {"started": 0, "passed": 0, "failed": 0},
              "status": "ERROR", "error": None}
    serial = None
    error: str | None = None
    try:
        serial, facts = _claim_device(avd)
        report["device"] = facts
        for role, apk in (("app", app), ("test", test)):
            report["apk_sha256"][role] = hashlib.sha256(apk.read_bytes()).hexdigest()
            _install(serial, role, apk)
        _check_installed(serial)
        _adb("logcat", "-c", serial=serial, best_effort=True)
        report["instrumentation_exit"] = _instrument(serial, expected, raw_path, timeout)
    except Exception as failure:  # noqa: BLE001 - every device failure is evidence, not a crash
        error = f"{type(failure).__name__}: {failure}"
    finally:
        if serial:
            try:
                _dump_logcat(serial, logcat_path)
            except Exception as log_failure:  # noqa: BLE001 - logcat must not mask the first cause
                error = error or f"{type(log_failure).__name__}: logcat export failed: {log_failure}"
    run: InstrumentationRun | None = None
    if raw_path.exists():
        try:
            run = parse_instrumentation(raw_path.read_text(encoding="utf-8", errors="replace"))
        except ValueError as parse_failure:
            error = error or f"ValueError: {parse_failure}"
    if run is not None:
        report["instrumentation"] = {"started": len(run.started), "passed": len(run.passed),
                                     "failed": len(run.failed)}
    if error is None and run is not None:
        try:
            check_run(run, expected)
            report["status"] = "PASS"
        except ValueError as check_failure:
            error = f"ValueError: {check_failure}"
    report["error"] = error
    write_junit(junit_path, junit_suite(mode, shard), expected, run, error=error if run is None else None)
    (evidence / "shard.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if report["status"] != "PASS":
        print(f"FAIL {label}: {error}", flush=True)
        return 1
    print(f"PASS {label}: {len(expected)} cases executed and passed", flush=True)
    return 0


def execute(*, shard: int, evidence: Path, inventory: Path, shards: Path, app: Path, test: Path,
            sha: str, avd: str, timeout: float = 900) -> int:
    """Run one shard's cases on one owned emulator and write its evidence."""
    expected = check_partition(inventory, shards)[shard]
    if avd != avd_name(shard):
        raise ValueError("AVD name does not match the shard")
    return _execute(mode=MODE_SHARD, shard=shard, expected=expected, evidence=evidence,
                    app=app, test=test, sha=sha, avd=avd, timeout=timeout)


def execute_fork(*, evidence: Path, inventory: Path, app: Path, test: Path, sha: str,
                 avd: str = FORK_AVD, timeout: float = 900) -> int:
    """Run the whole ordinary inventory on one emulator (fork pull requests)."""
    if avd != FORK_AVD:
        raise ValueError("fork fallback AVD name mismatch")
    return _execute(mode=MODE_FORK, shard=None, expected=read_cases(inventory), evidence=evidence,
                    app=app, test=test, sha=sha, avd=avd, timeout=timeout)


def _validate(*, mode: str, shard: int | None, expected: tuple[str, ...], evidence: Path, avd: str,
              sha: str | None) -> int:
    """Evidence, transcript, JUnit and logcat must agree with the manifest subset."""
    label = "fork" if mode == MODE_FORK else f"shard {shard}"
    report = strict_json(evidence / "shard.json")
    if report.get("schema") != SCHEMA or report.get("mode") != mode or report.get("shard") != shard:
        raise ValueError(f"{label} evidence identity mismatch")
    if sha is not None and report.get("sha") != sha:
        raise ValueError(f"{label} evidence SHA mismatch")
    if report.get("avd") != avd:
        raise ValueError(f"{label} evidence AVD mismatch")
    if report.get("runner") != RUNNER or report.get("package") != PACKAGE:
        raise ValueError(f"{label} evidence runner/package mismatch")
    if report.get("expected") != sorted(expected):
        raise ValueError(f"{label} evidence expected set differs from the manifest")
    if report.get("status") != "PASS":
        raise ValueError(f"{label} run did not pass: " + str(report.get("error")))
    device = report.get("device", {})
    if (device.get("sdk") != EXPECTED_SDK or device.get("abi") != EXPECTED_ABI
            or device.get("size") != EXPECTED_SIZE or device.get("cores") != EXPECTED_CORES):
        raise ValueError(f"{label} evidence device configuration mismatch")
    hashes = report.get("apk_sha256", {})
    if set(hashes) != {"app", "test"} or not all(
            isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) for value in hashes.values()):
        raise ValueError(f"{label} evidence APK digests incomplete")
    raw = evidence / "instrumentation-raw.txt"
    if not raw.is_file():
        raise ValueError("missing instrumentation transcript")
    check_run(parse_instrumentation(raw.read_text(encoding="utf-8", errors="replace")), expected)
    _check_junit(evidence / junit_name(mode, shard), expected)
    logcat = evidence / "logcat.txt"
    if not logcat.is_file() or logcat.stat().st_size == 0:
        raise ValueError("missing logcat evidence")
    print(f"PASS {label}: {len(expected)} cases match the manifest", flush=True)
    return len(expected)


def validate(*, shard: int, evidence: Path, inventory: Path, shards: Path, sha: str | None = None) -> int:
    """Independent post-run gate for one shard."""
    return _validate(mode=MODE_SHARD, shard=shard, expected=check_partition(inventory, shards)[shard],
                     evidence=evidence, avd=avd_name(shard), sha=sha)


def validate_fork(*, evidence: Path, inventory: Path, sha: str | None = None) -> int:
    """Independent post-run gate for the fork fallback (whole inventory)."""
    return _validate(mode=MODE_FORK, shard=None, expected=read_cases(inventory),
                     evidence=evidence, avd=FORK_AVD, sha=sha)
