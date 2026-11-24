#!/usr/bin/env python3
"""Drive or gate the P8-07 Q2 flow-2 device evidence on a cloud AVD."""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "python"))
from android_p807_q2 import main

if __name__ == "__main__":
    raise SystemExit(main())
