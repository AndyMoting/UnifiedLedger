"""Small, separate infrastructure proof; never accepted as maximum-scale evidence."""
from __future__ import annotations

import hashlib
import json
import math
import re
from pathlib import Path
from xml.etree import ElementTree as ET

from .result import PREFLIGHT_CLASS, PREFLIGHT_METHOD, crash_present, instrumentation_pass, strict_json, validate_log_collection


def probe_bytes(sha: str) -> bytes:
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("full lowercase SHA required")
    return ("unifiedledger-ci-preflight:" + sha + "\n").encode("ascii")


def prepare_probe(directory: Path, sha: str) -> None:
    directory.mkdir(parents=True, exist_ok=False)
    (directory / "probe.txt").write_bytes(probe_bytes(sha))


def validate_preflight(directory: Path, sha: str) -> dict:
    host = strict_json(directory / "host.json")
    validate_log_collection(directory)
    device = strict_json(directory / "device.json")
    if host.get("mode") != "preflight" or host.get("sha") != sha or device.get("sha") != sha:
        raise ValueError("preflight identity mismatch")
    if host.get("status") != "PASS" or host.get("phases") != ["preflight"]:
        raise ValueError("preflight did not complete")
    if host.get("timed_out") is not False or host.get("crash_detected") is not False:
        raise ValueError("preflight timeout/crash")
    if host.get("staging_verified") is not True or host.get("readiness") != {
        "user": "RUNNING_UNLOCKED", "package_service": True, "window_service": True,
    }:
        raise ValueError("preflight missing readiness/staging proof")
    config = host.get("config", {})
    expected = {"api": "36", "abi": "x86_64", "size": "1080x2400", "density": "420",
                "font_scale": "1.0", "locale": "zh-CN", "timezone": "Asia/Shanghai",
                "window_animation_scale": "1.0", "transition_animation_scale": "1.0", "animator_duration_scale": "1.0"}
    if any(config.get(key) != value for key, value in expected.items()):
        raise ValueError("preflight configuration mismatch")
    if set(host.get("apk_sha256", {})) != {"app", "test"} or any(
        not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value) for value in host["apk_sha256"].values()
    ):
        raise ValueError("preflight APK hashes missing")
    digest = hashlib.sha256(probe_bytes(sha)).hexdigest()
    if type(device.get("schema")) is not int or device != {"schema": 1, "mode": "preflight", "sha": sha, "probeSha256": digest, "roundTrip": True}:
        raise ValueError("preflight private roundtrip evidence mismatch")
    if {path.name for path in directory.glob("instrumentation-*.txt")} != {"instrumentation-preflight.txt"}:
        raise ValueError("unexpected preflight logs")
    instrumentation_pass((directory / "instrumentation-preflight.txt").read_text(encoding="utf-8"), PREFLIGHT_CLASS, PREFLIGHT_METHOD)
    suite = ET.parse(directory / "junit.xml").getroot()
    cases = suite.findall("testcase")
    if [suite.get(key) for key in ("tests", "failures", "errors", "skipped")] != ["1", "0", "0", "0"] or len(cases) != 1:
        raise ValueError("preflight JUnit not clean")
    if cases[0].get("name") != "preflight" or cases[0].get("classname") != PREFLIGHT_CLASS or len(cases[0]):
        raise ValueError("preflight JUnit identity mismatch")
    for value, limit in ((host.get("elapsed_seconds"), 720), (float(cases[0].get("time", "nan")), 600)):
        if type(value) not in (int, float) or not math.isfinite(value) or not 0 < value <= limit:
            raise ValueError("preflight elapsed bound")
    for name in ("logcat.txt", "configuration.txt", "pm.txt", "logcat-collection.json"):
        if not (directory / name).is_file() or (directory / name).stat().st_size == 0:
            raise ValueError("missing preflight evidence: " + name)
    if crash_present((directory / "logcat.txt").read_text(encoding="utf-8")):
        raise ValueError("preflight app crash")
    return host
