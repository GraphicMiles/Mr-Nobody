#!/usr/bin/env python3
"""Compile the blocklist source into the Android assets directory.

Source of truth: filters/bundled/blocklist.txt
Output:          <every Android module>/src/main/assets/blocklist.txt

The destination is discovered, not hardcoded. This script used to write into a
tree that no build consumed, so recompiling the blocklist changed nothing that
shipped. Targets are located the same way the privacy audit locates them: by
finding AndroidManifest.xml.

Also validates the list (delegates to filter_validation.py) and will, in a
future version, be able to ingest EasyList/EasyPrivacy (license permitting).

Usage:
    python3 tools/filter_compile.py
"""
import sys
import shutil
from pathlib import Path

from filter_validation import main as validate
from privacy_audit import find_android_trees


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    source = root / "filters" / "bundled" / "blocklist.txt"

    if not source.exists():
        print(f"ERROR: source blocklist not found: {source}")
        return 1

    if validate(str(source)) != 0:
        print("Refusing to compile an invalid blocklist.")
        return 1

    trees = find_android_trees(root)
    if not trees:
        print(f"ERROR: no Android module found under {root}")
        print("Nothing to compile into — refusing to report success.")
        return 1

    for module, _manifest in trees:
        dest = module / "src" / "main" / "assets" / "blocklist.txt"
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)
        label = dest.relative_to(root) if dest.is_relative_to(root) else dest
        print(f"Compiled blocklist -> {label} ({dest.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
