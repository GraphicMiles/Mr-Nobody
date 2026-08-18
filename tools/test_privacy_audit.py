#!/usr/bin/env python3
"""Tests for the privacy audit itself.

Why this exists: the audit hardcoded `root/android/...` while the app that
actually ships is `app/android/`. It printed CLEAN for months without ever
reading the shipping source. A green audit that inspects nothing is worse than
no audit, because it is trusted.

So these tests do not check that the audit passes on a clean tree — that is the
easy half, and it was already true while the audit was blind. They plant a real
violation in each tree and require the audit to FAIL. An audit that cannot fail
cannot pass.

Usage:  python3 tools/test_privacy_audit.py
"""
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
AUDIT = REPO / "tools" / "privacy_audit.py"

MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application android:label="x" />
</manifest>
"""

CLEAN_JAVA = """package com.example;
final class Page {
    void configure(android.webkit.WebSettings s) {
        s.setAllowFileAccess(false);
        s.setJavaScriptEnabled(true);
    }
}
"""

GRADLE = """dependencies {
    implementation "androidx.webkit:webkit:1.8.0"
}
"""


def run_audit(root: Path):
    proc = subprocess.run(
        [sys.executable, str(AUDIT), str(root)],
        capture_output=True, text=True,
    )
    return proc.returncode, proc.stdout + proc.stderr


def make_tree(root: Path, module: str) -> Path:
    """Create a minimal, clean Android module at root/<module>."""
    src = root / module / "app" / "src" / "main"
    (src / "java" / "com" / "example").mkdir(parents=True)
    (src / "AndroidManifest.xml").write_text(MANIFEST)
    (src / "java" / "com" / "example" / "Page.java").write_text(CLEAN_JAVA)
    (root / module / "app" / "build.gradle").write_text(GRADLE)
    return root / module / "app"


class PrivacyAuditTest(unittest.TestCase):

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    # ---------------------------------------------------------------- baseline

    def test_clean_repo_passes(self):
        make_tree(self.tmp, "android")
        code, out = run_audit(self.tmp)
        self.assertEqual(code, 0, out)
        self.assertIn("CLEAN", out)

    def test_repo_with_no_manifest_fails(self):
        """A misconfigured path must be loud, not silently 'clean'."""
        (self.tmp / "empty").mkdir()
        code, out = run_audit(self.tmp)
        self.assertEqual(code, 1, out)

    # ------------------------------------------------- the bug that shipped

    def test_audits_every_tree_not_just_the_first(self):
        """The regression: two trees, violation in the SECOND one."""
        make_tree(self.tmp, "android")          # legacy, clean
        live = make_tree(self.tmp, "app/android")  # live, violated below

        page = live / "src" / "main" / "java" / "com" / "example" / "Page.java"
        page.write_text(CLEAN_JAVA.replace(
            "s.setAllowFileAccess(false);",
            'webView.addJavascriptInterface(this, "bridge");',
        ))

        code, out = run_audit(self.tmp)
        self.assertEqual(code, 1, "audit missed a JS bridge in the live tree:\n" + out)
        self.assertIn("addJavascriptInterface", out)
        self.assertIn("app/android", out)

    def test_reports_which_tree_the_violation_is_in(self):
        make_tree(self.tmp, "android")
        live = make_tree(self.tmp, "app/android")
        (live / "src" / "main" / "AndroidManifest.xml").write_text(
            MANIFEST.replace("INTERNET", "READ_CONTACTS"))

        code, out = run_audit(self.tmp)
        self.assertEqual(code, 1, out)
        self.assertIn("app/android", out)
        self.assertIn("READ_CONTACTS", out)

    # ------------------------------------------------ each detector still bites

    def test_detects_prohibited_permission(self):
        tree = make_tree(self.tmp, "android")
        (tree / "src" / "main" / "AndroidManifest.xml").write_text(
            MANIFEST.replace("INTERNET", "READ_SMS"))
        code, out = run_audit(self.tmp)
        self.assertEqual(code, 1, out)
        self.assertIn("READ_SMS", out)

    def test_detects_analytics_dependency(self):
        tree = make_tree(self.tmp, "android")
        (tree / "build.gradle").write_text(
            GRADLE.replace("androidx.webkit:webkit:1.8.0",
                           "com.google.firebase:firebase-analytics:21.0.0"))
        code, out = run_audit(self.tmp)
        self.assertEqual(code, 1, out)
        self.assertIn("firebase", out)

    def test_detects_ssl_bypass(self):
        tree = make_tree(self.tmp, "android")
        page = tree / "src" / "main" / "java" / "com" / "example" / "Page.java"
        page.write_text(CLEAN_JAVA.replace(
            "s.setJavaScriptEnabled(true);",
            "public void onReceivedSslError(WebView v, SslErrorHandler h) { h.proceed(); }",
        ))
        code, out = run_audit(self.tmp)
        self.assertEqual(code, 1, out)
        self.assertIn("onReceivedSslError", out)

    def test_detects_violation_in_kotlin_source(self):
        """Kotlin ships the same risk as Java; .kt was previously unscanned."""
        tree = make_tree(self.tmp, "android")
        kt = tree / "src" / "main" / "java" / "com" / "example" / "Leak.kt"
        kt.write_text('fun go(w: WebView) { w.addJavascriptInterface(this, "b") }')
        code, out = run_audit(self.tmp)
        self.assertEqual(code, 1, out)
        self.assertIn("Leak.kt", out)

    # ---------------------------------------------------------------- hygiene

    def test_build_output_is_not_scanned(self):
        """Stale copies under build/ must not fail an otherwise clean tree."""
        tree = make_tree(self.tmp, "android")
        stale = tree / "build" / "intermediates" / "Old.java"
        stale.parent.mkdir(parents=True)
        stale.write_text('void x() { w.addJavascriptInterface(this, "b"); }')
        code, out = run_audit(self.tmp)
        self.assertEqual(code, 0, "build/ artifacts should be ignored:\n" + out)


if __name__ == "__main__":
    unittest.main(verbosity=2)
