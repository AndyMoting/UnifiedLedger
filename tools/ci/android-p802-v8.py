#!/usr/bin/env python3
"""Drive or gate the P8-02 V-8 first-result-page render reading on a cloud AVD."""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "python"))
from android_p802_v8 import main

if __name__ == "__main__":
    raise SystemExit(main())
