#!/usr/bin/env python3
"""Enforce the APK size gate.

Usage:
    python3 tools/apk_size_check.py path/to/app-release.apk [max_mb]

Exit code 0 if the APK is within budget, 1 otherwise.

The universal APK (all ABIs in one artifact) must be at most 80 MiB. The
build-failing default matches that product limit; CI does not keep a looser
second ceiling. See README.md and ROADMAP.md.
"""
import os
import sys

DEFAULT_MAX_MB = 80.0


def human(n: int) -> str:
    return f"{n / (1024 * 1024):.2f} MB"


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    path = sys.argv[1]
    max_mb = float(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_MAX_MB

    if not os.path.exists(path):
        print(f"ERROR: APK not found: {path}")
        return 1

    size = os.path.getsize(path)
    mb = size / (1024 * 1024)
    status = "OK" if mb <= max_mb else "FAIL"
    print(f"APK: {path}")
    print(f"Size: {human(size)}  (budget {max_mb:.0f} MB)  -> {status}")
    return 0 if mb <= max_mb else 1


if __name__ == "__main__":
    sys.exit(main())
