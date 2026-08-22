#!/usr/bin/env python3
"""Publish a Mr Nobody release by rewriting website/update.json.

The update endpoint serves website/update.json verbatim, so publishing a
release is a one-file change. This script keeps that file honest:

  - validates the version (dotted triple, e.g. 1.1.0)
  - computes the APK's sha256 when --apk is given
  - stamps publishedAt (UTC)
  - writes website/update.json

Usage:
  python3 tools/publish_update.py \
      --version 1.1.0 \
      --notes "Faster tabs and fixed previews." \
      --apk app/build/app/outputs/flutter-apk/app-release.apk \
      --url https://example.com/releases/mr-nobody-1.1.0.apk \
      [--required]

The server only distributes metadata. The APK itself is hosted wherever
distribution is decided; nothing executable ever enters this file.
"""

import argparse
import datetime
import hashlib
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
TARGET = REPO / "website" / "update.json"
VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")
SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
CHUNK = 1024 * 1024


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--version", required=True, help="new release, e.g. 1.1.0")
    parser.add_argument("--notes", required=True, help="short release notes")
    parser.add_argument("--url", default="", help="public URL of the signed APK")
    parser.add_argument("--apk", default="", help="local APK path (sha256 source)")
    parser.add_argument("--required", action="store_true",
                        help="mark the release as required (persistent nudge only)")
    parser.add_argument("--published-at", default="",
                        help="ISO-8601 UTC timestamp (default: now)")
    parser.add_argument("--target", default="",
                        help="output file (default: website/update.json)")
    args = parser.parse_args()

    if not VERSION_RE.match(args.version):
        sys.exit(f"error: --version must look like 1.1.0, got {args.version!r}")

    url = args.url.strip()
    if url and not url.startswith("https://"):
        sys.exit("error: --url must be an https:// URL (the app rejects anything else)")

    sha = ""
    if args.apk:
        apk = Path(args.apk)
        if not apk.is_file():
            sys.exit(f"error: --apk not found: {apk}")
        sha = sha256_of(apk)
        if not url:
            sys.exit("error: --apk given without --url; the metadata needs the public URL")

    if not url:
        print("note: no --url given; keeping a placeholder downloadUrl", file=sys.stderr)
        url = "https://example.com/releases/mr-nobody-%s.apk" % args.version

    published = args.published_at.strip() or datetime.datetime.now(
        datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    doc = {
        "latestVersion": args.version,
        "releaseNotes": args.notes,
        "downloadUrl": url,
        "required": args.required,
        "sha256": sha,
        # Filled by hand once signing metadata is settled: the apksigner
        # "Signer #1 certificate SHA-256 digest". The app treats a
        # placeholder as "signature not provided", not as a failure.
        "signature": "REPLACE_WITH_ANDROID_SIGNING_METADATA",
        "publishedAt": published,
    }

    target = Path(args.target) if args.target else TARGET
    target.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {target}")
    print(json.dumps(doc, indent=2))


if __name__ == "__main__":
    main()
