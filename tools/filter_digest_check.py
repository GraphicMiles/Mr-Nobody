#!/usr/bin/env python3
"""Check that the digest pinned in FilterEngine matches the bundled blocklist.

FilterEngine verifies the bundled blocklist against a SHA-256 pinned in the
source, and fails closed when they disagree -- blocking silently switches off.
That is the right runtime behaviour and a terrible way to find out: the person
who edits the list is not the person who sees ad blocking stop working three
releases later.

So the pin is checked at build time, where a mismatch is a broken build with an
obvious cause instead of a silent loss of protection on someone's phone.

Usage:
    python3 tools/filter_digest_check.py [repo_root]

Exit codes:
    0  digest matches
    1  mismatch, missing file, or unreadable pin
"""

import hashlib
import re
import sys
from pathlib import Path

ASSET = "app/android/app/src/main/assets/blocklist.txt"
MIRROR = "filters/bundled/blocklist.txt"
ENGINE = "app/android/app/src/main/java/com/mrnobody/browser/blocking/FilterEngine.java"

PIN_RE = re.compile(
    r'BUNDLED_DIGEST\s*=\s*"([0-9a-fA-F]{64})"',
    re.MULTILINE,
)


def fail(msg):
    print(f"FILTER DIGEST — FAIL\n  {msg}")
    return 1


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

    asset = root / ASSET
    engine = root / ENGINE

    if not asset.is_file():
        return fail(f"bundled blocklist not found: {ASSET}")
    if not engine.is_file():
        return fail(f"FilterEngine not found: {ENGINE}")

    actual = hashlib.sha256(asset.read_bytes()).hexdigest()

    match = PIN_RE.search(engine.read_text(encoding="utf-8"))
    if not match:
        return fail(
            "no BUNDLED_DIGEST pin found in FilterEngine. "
            "The integrity check cannot pass without one."
        )
    pinned = match.group(1).lower()

    if pinned != actual:
        return fail(
            f"blocklist digest does not match the pin.\n"
            f"  asset  : {actual}\n"
            f"  pinned : {pinned}\n"
            f"  Update BUNDLED_DIGEST in FilterEngine.java to the asset value.\n"
            f"  Left alone, FilterEngine fails closed and blocking is disabled."
        )

    # The mirror is what tooling and any future distribution path read. If it
    # has drifted from the asset, one of the two is stale and it is not
    # knowable from here which -- so say so rather than picking.
    mirror = root / MIRROR
    if mirror.is_file():
        mirror_digest = hashlib.sha256(mirror.read_bytes()).hexdigest()
        if mirror_digest != actual:
            return fail(
                f"{MIRROR} has drifted from the shipped asset.\n"
                f"  asset  : {actual}\n"
                f"  mirror : {mirror_digest}\n"
                f"  These must be the same file; re-sync before releasing."
            )

    print(f"FILTER DIGEST — OK ({actual[:12]}…)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
