#!/usr/bin/env python3
"""Drive or gate the P8-00 V-8 font-scale sample on a cloud AVD."""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "python"))
from android_fontscale import main

if __name__ == "__main__":
    raise SystemExit(main())
