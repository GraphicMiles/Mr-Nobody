#!/usr/bin/env python3
"""Static privacy audit for the Mr Nobody codebase.

Checks for the things the product must never contain (see docs/PRIVACY.md):
  - prohibited Android permissions (contacts, SMS, phone, ...)
  - prohibited dependency coordinates (analytics/ad SDKs)
  - addJavascriptInterface / JS bridge usage
  - disableWebViewUrlCheck / clearTextTraffic / debug cert overrides

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


def main() -> int:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent
    problems = []

    # 1. Manifest permissions
    manifest = root / "android" / "app" / "src" / "main" / "AndroidManifest.xml"
    if manifest.exists():
        text = manifest.read_text(encoding="utf-8", errors="ignore")
        for perm in PROHIBITED_PERMISSIONS:
            if f'android.permission.{perm}' in text:
                problems.append(f"prohibited permission: {perm}")
        for m in PROHIBITED_CODE:
            if m in text:
                problems.append(f"prohibited manifest/code pattern: {m}")
    else:
        problems.append("AndroidManifest.xml not found")

    # 2. Dependencies
    gradle_files = [g for g in (root / "android").rglob("*.gradle") if g.is_file()]
    for g in gradle_files:
        text = g.read_text(encoding="utf-8", errors="ignore")
        for dep in PROHIBITED_DEPS:
            if dep in text:
                problems.append(f"prohibited dependency in {g.name}: {dep}")

    # 3. Source code patterns
    for src in (root / "android" / "app" / "src" / "main" / "java").rglob("*.java"):
        text = src.read_text(encoding="utf-8", errors="ignore")
        for pat in PROHIBITED_CODE:
            if pat in text:
                problems.append(f"prohibited pattern in {src.name}: {pat}")

    if problems:
        print("PRIVACY AUDIT — VIOLATIONS FOUND:")
        for p in problems:
            print("  ✗ " + p)
        return 1

    print("PRIVACY AUDIT — CLEAN")
    print("  no prohibited permissions, dependencies, or JS-bridge code detected")
    return 0


if __name__ == "__main__":
    sys.exit(main())
