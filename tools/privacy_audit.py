#!/usr/bin/env python3
"""Static privacy audit for the Mr Nobody codebase.

Checks for the things the product must never contain (see README.md):
  - prohibited Android permissions (contacts, SMS, phone, ...)
  - prohibited dependency coordinates (analytics/ad SDKs)
  - addJavascriptInterface / JS bridge usage
  - disableWebViewUrlCheck / clearTextTraffic / debug cert overrides
  - plaintext provider credentials or granted account-cookie storage

Usage:
    python3 tools/privacy_audit.py [repo_root]

Exit code 0 = clean, 1 = violation found.
"""
import sys
from pathlib import Path

PROHIBITED_PERMISSIONS = [
    "READ_CONTACTS", "WRITE_CONTACTS",
    "READ_SMS", "SEND_SMS", "RECEIVE_SMS",
    "READ_PHONE_STATE", "CALL_PHONE", "READ_CALL_LOG",
    "GET_ACCOUNTS", "READ_PHONE_NUMBERS",
]

PROHIBITED_DEPS = [
    "com.google.android.gms.ads",
    "com.google.firebase",
    "com.google.android.gms:play-services-analytics",
    "com.appsflyer", "com.adjust", "com.mixpanel", "com.segment.analytics",
    "com.google.android.gms.ads.identifier",
]

PROHIBITED_CODE = [
    "addJavascriptInterface",
    "setAllowFileAccess(true",
    "setAllowUniversalAccessFromFileURLs(true",
    "onReceivedSslError",   # we never bypass cert validation — flag any use
    "usesCleartextTraffic=\"true\"",
]

ALLOWED_PERMISSIONS = {
    "INTERNET", "WRITE_EXTERNAL_STORAGE",
    "CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
}

# Every outbound connection must go through browser/net/NetworkGate, which is
# where fail-closed routing is enforced. A bare openConnection() anywhere else
# bypasses the privacy route entirely: the browser would ride Tor while that
# call went direct, under a UI telling the user they were protected. Five such
# calls existed before the gate, so this is a regression guard, not a
# hypothetical.
GATE_FILE = "NetworkGate.java"
UNGATED_CONNECT = "openConnection("

# These classes persist credentials and must use the Keystore-backed seam.
# Naming the exact owners keeps this check precise instead of flagging benign
# SharedPreferences such as theme or block counters.
CREDENTIAL_STORES = {
    "Settings.java": "provider API keys",
    "AccountStore.java": "granted account cookies",
}
SECURE_STORE = "EncryptedPreferences"


SKIP_DIRS = {".git", "build", ".dart_tool", ".gradle", "node_modules", ".idea"}


def _pruned_walk(base: Path):
    """Yield files under base, skipping build/VCS noise."""
    if not base.exists():
        return
    for path in base.rglob("*"):
        if not path.is_file():
            continue
        if SKIP_DIRS.intersection(path.parts):
            continue
        yield path


def find_android_trees(root: Path):
    """Locate every Android source tree in the repo.

    The audit must not hardcode a single path. This repository has carried more
    than one Android tree at a time (a legacy `android/` and the live
    `app/android/`), and an audit pointed at only one of them reports CLEAN
    while the app that actually ships goes unchecked. Any directory holding an
    AndroidManifest.xml under src/main is a tree we are responsible for.
    """
    trees = []
    for manifest in _pruned_walk(root):
        if manifest.name != "AndroidManifest.xml":
            continue
        if manifest.parent.name != "main":
            continue
        # .../<module>/src/main/AndroidManifest.xml  ->  .../<module>
        module = manifest.parent.parent.parent
        trees.append((module, manifest))
    return sorted(set(trees))


def main() -> int:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent
    problems = []

    trees = find_android_trees(root)
    if not trees:
        print("PRIVACY AUDIT — VIOLATIONS FOUND:")
        print("  ✗ no AndroidManifest.xml found anywhere under " + str(root))
        return 1

    for module, manifest in trees:
        label = module.relative_to(root) if module.is_relative_to(root) else module

        # 1. Manifest permissions
        text = manifest.read_text(encoding="utf-8", errors="ignore")
        for perm in PROHIBITED_PERMISSIONS:
            if f'android.permission.{perm}' in text:
                problems.append(f"[{label}] prohibited permission: {perm}")
        for m in PROHIBITED_CODE:
            if m in text:
                problems.append(f"[{label}] prohibited manifest/code pattern: {m}")

        # 2. Dependencies
        for g in _pruned_walk(module):
            if g.suffix != ".gradle" and g.name not in ("build.gradle.kts", "settings.gradle.kts"):
                continue
            gtext = g.read_text(encoding="utf-8", errors="ignore")
            for dep in PROHIBITED_DEPS:
                if dep in gtext:
                    problems.append(f"[{label}] prohibited dependency in {g.name}: {dep}")

        # 3. Source code patterns
        for src in _pruned_walk(module / "src" / "main"):
            if src.suffix not in (".java", ".kt"):
                continue
            stext = src.read_text(encoding="utf-8", errors="ignore")
            for pat in PROHIBITED_CODE:
                if pat in stext:
                    problems.append(f"[{label}] prohibited pattern in {src.name}: {pat}")

            # 3b. Ungated network egress.
            if src.name != GATE_FILE and UNGATED_CONNECT in stext:
                problems.append(
                    f"[{label}] {src.name} calls openConnection() directly -- "
                    f"route it through NetworkGate or a privacy route will leak")

            # 3c. Credential owners must not regress to plaintext preferences.
            if src.name in CREDENTIAL_STORES and SECURE_STORE not in stext:
                problems.append(
                    f"[{label}] {src.name} stores {CREDENTIAL_STORES[src.name]} "
                    f"without {SECURE_STORE}")

    if problems:
        print("PRIVACY AUDIT — VIOLATIONS FOUND:")
        for p in problems:
            print("  ✗ " + p)
        return 1

    print("PRIVACY AUDIT — CLEAN")
    for module, _ in trees:
        label = module.relative_to(root) if module.is_relative_to(root) else module
        print(f"  audited: {label}")
    print("  no prohibited permissions, dependencies, or JS-bridge code detected")
    return 0


if __name__ == "__main__":
    sys.exit(main())
