#!/usr/bin/env python3
"""Compile the blocklist source into the Android assets directory.

Source of truth: filters/bundled/blocklist.txt
Output:          android/app/src/main/assets/blocklist.txt

Also validates the list (delegates to filter_validation.py) and will, in a
future version, be able to ingest EasyList/EasyPrivacy (license permitting).

Usage:
    python3 tools/filter_compile.py
"""
import sys
import shutil
from pathlib import Path

from filter_validation import main as validate


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    source = root / "filters" / "bundled" / "blocklist.txt"
    dest = root / "android" / "app" / "src" / "main" / "assets" / "blocklist.txt"

    if not source.exists():
        print(f"ERROR: source blocklist not found: {source}")
        return 1

    if validate(str(source)) != 0:
        print("Refusing to compile an invalid blocklist.")
        return 1

    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, dest)
    print(f"Compiled blocklist -> {dest} ({dest.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
