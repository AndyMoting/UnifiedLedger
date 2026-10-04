"""Strict scale evidence reducer. Missing evidence is never success."""
from __future__ import annotations

import hashlib
import json
import math
import re
from pathlib import Path
from xml.etree import ElementTree as ET

PHASES = ("prepare", "chain", "reopen", "replay", "final-reopen")
STAGES = ("preparation", "coldstart", "saf_import", "detail_decision", "traversal",
          "group_disposition", "batch_confirmation", "detail_monthly_refresh", "reopen",
          "same_request_replay", "final_reopen")
EXPECTED = {"preparedCandidates": 51000, "preparedRelations": 100000, "preparedDispositions": 100,
            "finalCandidates": 61000, "finalRelations": 150000, "observedCandidates": 61000,
            "mainGroupRelations": 50000, "groupDispositions": 50000,
            "formalTransactions": 1, "balancedPostings": 2}
TEST_CLASS = "com.unifiedledger.android.AndroidScaleLongInstrumentedTest"
TEST_METHOD = "maximumScalePhase"
PREFLIGHT_CLASS = "com.unifiedledger.android.AndroidScalePreflightInstrumentedTest"
PREFLIGHT_METHOD = "privateFixtureRoundTrip"


def strict_json(path: Path) -> dict:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = value
        return result
    data = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError("nonfinite JSON")))
    if not isinstance(data, dict):
        raise ValueError("JSON report must be object")
    return data


PACKAGE = "com.unifiedledger.android"
READY_MARKER = "账本："


def app_ui_state(xml: str) -> str:
    """Classify visible app nodes only; startup failure takes precedence."""
    root = ET.fromstring(xml)
    if root.tag != "hierarchy" or any(node.tag != "node" for node in root.iter() if node is not root):
        raise ValueError("unknown UI hierarchy")
    texts = []

    def visit(node, visible=True):
        visible = visible and node.get("visible-to-user", "true") == "true"
        if node.tag == "node":
            bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            visible = visible and bounds is not None
            if bounds is not None:
                x1, y1, x2, y2 = map(int, bounds.groups())
                visible = visible and x2 > x1 and y2 > y1
            if visible and node.get("package") == PACKAGE:
                texts.extend((node.get("text", ""), node.get("content-desc", "")))
        for child in node:
            visit(child, visible)

    visit(root)
    if any(text.startswith("无法打开本地账本") for text in texts):
        return "Error"
    if any(text.startswith(READY_MARKER) for text in texts):
        return "Ready"
    return "Starting" if "正在打开本地账本…" in texts else "Unknown"


def validate_app_ready(directory: Path, report: dict) -> None:
    xml = (directory / "ready-ui.xml").read_bytes()
    expected = {"state": "Ready", "package": PACKAGE, "marker": READY_MARKER, "fresh_dump": True,
                "xml_sha256": hashlib.sha256(xml).hexdigest()}
    proof = report.get("app_ready")
    if not isinstance(proof, dict) or proof.get("fresh_dump") is not True or proof != expected:
        raise ValueError("missing/invalid app Ready proof")
    if app_ui_state(xml.decode("utf-8")) != "Ready":
        raise ValueError("app Ready XML does not prove readiness")


CRASH_MARKER = re.compile(r"FATAL EXCEPTION|Fatal signal|OutOfMemoryError|ANR in |am_anr|am_crash|INSTRUMENTATION_ABORTED")
CRASH_ATTRIBUTION_WINDOW = 5
PROCESS_NAME = re.compile(r"Process:\s*([^\s,]+)|>>>\s*([^\s]+)\s*<<<")


def crash_markers(log: str, package: str = PACKAGE) -> tuple[list[str], list[str]]:
    """Split crash markers into those attributable to `package` and the rest.

    Attribution is explicit and never a fuzzy "the package appears nearby":
    `am_crash`/`am_anr` carry the package on their own line,
    `INSTRUMENTATION_ABORTED` is always ours, and a crash block is matched
    through the process line that follows it (`Process: com..., PID: ...` for
    AndroidRuntime, `>>> com... <<<` for a tombstone).

    Markers that name another process, or nothing at all, come back as foreign
    evidence: they are recorded and reviewed instead of failing a maximum-scale
    run, because emulator system processes (SystemUI, launcher, gms) crash and
    ANR on their own over a multi-hour chain.
    """
    lines = log.splitlines()
    ours: list[str] = []
    foreign: list[str] = []
    for index, line in enumerate(lines):
        if not CRASH_MARKER.search(line):
            continue
        if "INSTRUMENTATION_ABORTED" in line:
            ours.append(line.strip())
            continue
        if package in line:
            # `ANR in com...` and `am_crash: [... com...]` name the package on the
            # marker line itself; only the marker line is consulted here, never a
            # window, so a foreign crash cannot be claimed by a nearby log line.
            ours.append(line.strip())
            continue
        if re.search(r"am_(?:crash|anr)", line):
            foreign.append(line.strip())
            continue
        attributed = None
        for following in lines[index + 1:index + CRASH_ATTRIBUTION_WINDOW + 1]:
            found = PROCESS_NAME.search(following)
            if found:
                attributed = found.group(1) or found.group(2)
                break
        (ours if attributed == package else foreign).append(line.strip())
    return ours, foreign


def crash_present(log: str, package: str = PACKAGE) -> bool:
    """True only for a crash attributable to `package`."""
    return bool(crash_markers(log, package)[0])


def transcript_shape(records: list[tuple[int, dict]]) -> str:
    return str([(code, fields.get("class", "-"), fields.get("test", "-"), fields.get("numtests", "-"))
                for code, fields in records][:6])


def instrumentation_pass(log: str, test_class: str = TEST_CLASS, test_method: str = TEST_METHOD) -> None:
    """Require one named executed JUnit case and complete runner termination, not shell rc."""
    if re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_ABORTED", log):
        raise ValueError("instrumentation failure")
    records = []
    status = {}
    for line in log.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            field = line[len("INSTRUMENTATION_STATUS: "):]
            if "=" in field:
                key, value = field.split("=", 1)
                status[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            records.append((int(line.split(": ", 1)[1]), status))
            status = {}
    tests = [(code, fields) for code, fields in records if "test" in fields or "class" in fields]
    # Reject any status code outside the clean {start=1, ok=0} pair, including
    # records that carry no class/test field at all.
    if any(code not in (1, 0) for code, _ in records):
        raise ValueError(f"unexpected instrumentation status code: {transcript_shape(records)}")
    if len(tests) != 2 or [code for code, _ in tests] != [1, 0]:
        raise ValueError(f"missing, extra, skipped, or failed instrumented test: {transcript_shape(records)}")
    for _, fields in tests:
        if fields.get("class") != test_class or fields.get("test") != test_method or fields.get("numtests") != "1":
            raise ValueError(f"unexpected instrumented test identity/count: {transcript_shape(tests)}")
    if re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)$", log, re.MULTILINE) != ["-1"]:
        raise ValueError("missing instrumentation completion")
    if not re.search(r"^OK \(1 test\)\s*$", log, re.MULTILINE):
        raise ValueError("missing JUnit completion")


def validate_log_collection(directory: Path) -> None:
    collection = strict_json(directory / "logcat-collection.json")
    if collection.get("started") is not True or collection.get("stopped") is not True:
        raise ValueError("continuous logcat collector did not run/stop cleanly")
    if type(collection.get("stream_bytes")) is not int or collection["stream_bytes"] <= 0:
        raise ValueError("continuous logcat did not collect any bytes")
    if not isinstance(collection.get("gaps"), list):
        raise ValueError("continuous logcat gaps not recorded")


def validate_evidence(directory: Path, expected_sha: str) -> dict:
    if not re.fullmatch(r"[0-9a-f]{40}", expected_sha):
        raise ValueError("expected SHA must be exact full lowercase SHA")
    report = strict_json(directory / "host.json")
    if report.get("mode") != "maximum":
        raise ValueError("maximum evidence mode required")
    # D-216 authority boundary: a local diagnostic run (opt-in runner flag) is
    # never acceptance evidence, even when it targeted the five-phase maximum
    # mode. The runner stamps `authority: local-diagnostic` on that channel; a
    # cloud run omits the key entirely. Any other authority value is refused
    # too, so a rewritten report cannot smuggle a local run past acceptance.
    authority = report.get("authority")
    if authority is not None and authority != "cloud":
        raise ValueError("cloud acceptance authority required; local-diagnostic evidence is not acceptance")
    validate_log_collection(directory)
    validate_app_ready(directory, report)
    device = strict_json(directory / "device.json")
    # `type(...) is not int` also rejects JSON true/false, which compare equal to 1/0.
    if type(device.get("schema")) is not int or device.get("schema") != 1:
        raise ValueError("unsupported device evidence schema")
    if report.get("sha") != expected_sha or device.get("sha") != expected_sha:
        raise ValueError("evidence SHA mismatch")
    if report.get("status") != "PASS" or report.get("phases") != list(PHASES):
        raise ValueError("host did not complete all phases")
    if report.get("timed_out") is not False or report.get("crash_detected") is not False:
        raise ValueError("timeout/crash/OOM/ANR evidence")
    config = report.get("config", {})
    expected_config = {"api": "36", "abi": "x86_64", "size": "1080x2400", "density": "420",
                       "font_scale": "1.0", "locale": "zh-CN", "timezone": "Asia/Shanghai",
                       "window_animation_scale": "1.0", "transition_animation_scale": "1.0",
                       "animator_duration_scale": "1.0"}
    if not isinstance(config, dict) or any(config.get(key) != value for key, value in expected_config.items()):
        raise ValueError("actual device configuration mismatch")
    if set(report.get("apk_sha256", {})) != {"app", "test"} or any(
            not re.fullmatch(r"[0-9a-f]{64}", value) for value in report["apk_sha256"].values()):
        raise ValueError("APK hashes missing/invalid")
    stages = device.get("stages", {})
    if set(stages) != set(STAGES):
        raise ValueError("missing/extra stages")
    previous_end = 0
    limits = {"preparation": 14400000, "saf_import": 1800000, "traversal": 14400000, "group_disposition": 5400000}
    for stage in STAGES:
        item = stages[stage]
        if not isinstance(item, dict) or item.get("status") != "PASS" or type(item.get("elapsedMs")) is not int or not 0 < item["elapsedMs"] <= limits.get(stage, 180000):
            raise ValueError(f"stage did not pass: {stage}")
        if type(item.get("startedMs")) is not int or item["startedMs"] < previous_end:
            raise ValueError("stage ordering/timer mismatch")
        previous_end = item["startedMs"] + item["elapsedMs"]
    for key, value in EXPECTED.items():
        if type(device.get(key)) is not int or device[key] != value:
            raise ValueError(f"scale/economic oracle mismatch: {key}")
    if device.get("firstLastObserved") is not True or device.get("uiIdentityScope") != "projected-sequence-multiplicity-order":
        raise ValueError("UI traversal scope evidence missing")
    if {p.name for p in directory.glob("instrumentation-*.txt")} != {f"instrumentation-{phase}.txt" for phase in PHASES}:
        raise ValueError("extra/stale/missing phase logs")
    for phase in PHASES:
        instrumentation_pass((directory / f"instrumentation-{phase}.txt").read_text(encoding="utf-8"))
    root = ET.parse(directory / "junit.xml").getroot()
    cases = root.findall("testcase")
    if (root.get("tests"), root.get("failures"), root.get("errors"), root.get("skipped")) != ("5", "0", "0", "0"):
        raise ValueError("JUnit aggregate not clean")
    if [case.get("name") for case in cases] != list(PHASES) or any(len(case) for case in cases) or any(case.get("classname") != TEST_CLASS for case in cases):
        raise ValueError("JUnit cases missing/failed/skipped")
    for case in cases:
        seconds = float(case.get("time", "nan"))
        if not math.isfinite(seconds) or not 0 < seconds <= 14400:
            raise ValueError("invalid JUnit timer")
    for name in ("logcat.txt", "memory.txt", "configuration.txt"):
        if not (directory / name).is_file() or (directory / name).stat().st_size == 0:
            raise ValueError(f"required evidence absent: {name}")
    elapsed = report.get("elapsed_seconds")
    if type(elapsed) not in (int, float) or not math.isfinite(elapsed) or not 0 < elapsed <= 14400:
        raise ValueError("driver elapsed-time bound")
    if crash_present((directory / "logcat.txt").read_text(encoding="utf-8")):
        raise ValueError("crash/OOM/ANR in actual logcat")
    return report


MANIFEST_COUNTERS = (
    ("preparedCandidates", "initial_candidates"),
    ("preparedRelations", "initial_duplicate_relations"),
    ("preparedDispositions", "initially_confirmed_relations"),
    ("finalCandidates", "final_candidates"),
    ("finalRelations", "final_duplicate_relations"),
    ("observedCandidates", "final_candidates"),
    ("mainGroupRelations", "new_session_duplicate_relations"),
    ("groupDispositions", "new_session_duplicate_relations"),
    ("formalTransactions", "expected_formal_transactions_after_confirmation"),
)


def cross_check_manifest(device: dict, manifest: dict) -> None:
    """Bind the device-reported counters to the independently generated manifest.

    `validate_evidence` compares the device counters with literals held by the
    reducer; this second gate compares them with the manifest that the fixture
    generator computed from the generated bytes, so the app's own assertions are
    not the only source of truth.
    """
    if manifest.get("profile") != "maximum":
        raise ValueError("manifest profile is not maximum")
    for device_key, manifest_key in MANIFEST_COUNTERS:
        expected = manifest.get(manifest_key)
        if type(expected) is not int:
            raise ValueError(f"manifest field missing or not an integer: {manifest_key}")
        if device.get(device_key) != expected:
            raise ValueError(
                f"device counter {device_key}={device.get(device_key)!r} disagrees with manifest {manifest_key}={expected!r}"
            )
