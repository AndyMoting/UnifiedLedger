#!/usr/bin/env python3
"""Publish or consume a verified whole-tree Android APK bundle."""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "python"))
from android_scale.apks import main

if __name__ == "__main__":
    raise SystemExit(main())
