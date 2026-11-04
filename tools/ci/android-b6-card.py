#!/usr/bin/env python3
"""Drive or gate the B6 group-card open measurement on a cloud AVD."""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "python"))
from android_b6_card import main

if __name__ == "__main__":
    raise SystemExit(main())
