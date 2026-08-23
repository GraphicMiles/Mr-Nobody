#!/usr/bin/env python3
"""Publish a Mr Nobody release end-to-end on GitHub Releases.

Cutting a release used to be five manual steps that had to agree: build the
signed APK, compute its sha256, create the GitHub Release and upload the APK,
copy the asset's public URL into the metadata, and fill the signing-cert
digest by hand. Get one wrong and the landing page and the app disagree (they
have before: update.json said 1.0.1 while index.html linked 1.0.0).

This script does the whole thing in one command. It uploads the signed APK to
a GitHub Release, derives the canonical download URL itself, computes the
sha256, reads the real signing-cert digest from the APK, rewrites
website/update.json, and rewrites the download button in every site page so
nothing drifts.

The app still only ever reads metadata (website/update.json, served by the
update endpoint). The APK lives on GitHub Releases; no executable code is
served from the metadata host.

Prerequisites (checked at runtime, with clear errors):
  - gh (GitHub CLI), authenticated to a repo you can release to
  - apksigner (optional; from the Android build-tools) for the cert digest

Examples:
  # Full release: upload APK, create release, rewrite metadata + site.
  python3 tools/publish_update.py \\
      --version 1.1.0 \\
      --notes "Faster tabs and fixed previews." \\
      --apk app/build/app/outputs/flutter-apk/app-release.apk

  # Preview without creating anything.
  python3 tools/publish_update.py --version 1.1.0 --notes "..." --apk X.apk --dry-run

  # Metadata only (URL already known, no GitHub upload).
  python3 tools/publish_update.py --version 1.1.0 --notes "..." \\
      --apk X.apk --url https://host/mr-nobody-1.1.0.apk

  # Release notes from a file (handy for long notes).
  python3 tools/publish_update.py --version 1.1.0 --notes-file NOTES.md --apk X.apk
"""

import argparse
import datetime
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
TARGET = REPO / "website" / "update.json"
SITE_PAGES = [REPO / "website" / "index.html",
              REPO / "website" / "desktop.html",
              REPO / "website" / "mobile.html"]

VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")
SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
# Any prior GitHub-Releases APK link, regardless of version/casing, so a
# re-publish rewrites every page even after several releases.
SITE_APK_LINK_RE = re.compile(
    r"https://github\.com/[^\"' )]+/releases/download/v?\d+(?:\.\d+)+/[^\"' )]+\.apk")
CHUNK = 1024 * 1024


class PublishError(Exception):
    pass


def run(cmd, capture=True, check=True):
    """Run a command; return CompletedProcess. Raises PublishError on failure."""
    try:
        return subprocess.run(cmd, capture_output=capture, text=True, check=check)
    except FileNotFoundError:
        raise PublishError(f"`{cmd[0]}` not found on PATH. Install it and retry.")
    except subprocess.CalledProcessError as e:
        detail = (e.stderr or e.stdout or "").strip()
        raise PublishError(
            f"`{' '.join(cmd)}` failed (exit {e.returncode}).\n{detail}") from e


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def detect_repo():
    """owner/name from the origin remote, or None."""
    if not shutil.which("git"):
        return None
    try:
        out = run(["git", "-C", str(REPO), "remote", "get-url", "origin"],
                  capture=True, check=True).stdout.strip()
    except PublishError:
        return None
    for pat in (r"github\.com[:/](?P<r>[^/]+/[^/]+?)(?:\.git)?$",
                r"github\.com[:/](?P<r>[^/]+/[^/]+)$"):
        m = re.search(pat, out, re.IGNORECASE)
        if m:
            return m.group("r")
    return None


def asset_name(version, override):
    return override or f"mr-nobody-{version}.apk"


def asset_url(repo, tag, name):
    return f"https://github.com/{repo}/releases/download/{tag}/{name}"


def signing_cert_digest(apk_path):
    """The 'Signer #1 certificate SHA-256 digest' from apksigner, or ''."""
    apksigner = shutil.which("apksigner")
    if not apksigner:
        print("warning: apksigner not on PATH; leaving 'signature' empty "
              "(the app treats that as 'not provided').", file=sys.stderr)
        return ""
    out = run([apksigner, "verify", "--print-certs", str(apk_path)],
              capture=True, check=True).stdout
    m = re.search(r"SHA-256 digest:\s*([0-9a-fA-F]{64})", out)
    return m.group(1).lower() if m else ""


def create_github_release(repo, tag, name, notes, apk_path, latest):
    """Create the release + upload the APK via gh. Returns the asset URL."""
    if not shutil.which("gh"):
        raise PublishError("`gh` (GitHub CLI) not found. Install it and run "
                           "`gh auth login`, or pass --url to skip uploading.")
    # Authenticate? fail fast with a clear message.
    run(["gh", "auth", "status"], capture=True, check=True)

    # Refuse to clobber an existing release tag.
    view = subprocess.run(["gh", "release", "view", tag, "--repo", repo],
                          capture_output=True, text=True)
    if view.returncode == 0:
        raise PublishError(
            f"Release {tag} already exists in {repo}. Bump the version, or "
            f"delete it first: gh release delete {tag} --repo {repo} --yes")

    cmd = ["gh", "release", "create", tag,
           str(apk_path),
           "--repo", repo,
           "--title", name,
           "--notes", notes]
    if not latest:
        cmd.append("--latest=false")
    run(cmd, capture=True, check=True)
    return asset_url(repo, tag, apk_path.name)


def write_update_json(target, doc):
    target.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")


def rewrite_site_pages(url):
    """Replace every hardcoded GitHub-Releases APK link with `url`."""
    changed = []
    for page in SITE_PAGES:
        if not page.is_file():
            continue
        text = page.read_text(encoding="utf-8")
        new = SITE_APK_LINK_RE.sub(url, text)
        if new != text:
            page.write_text(new, encoding="utf-8")
            changed.append(page.name)
    return changed


def main():
    parser = argparse.ArgumentParser(
        description=__doc__.splitlines()[0],
        formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--version", required=True, help="e.g. 1.1.0")
    parser.add_argument("--notes", default="", help="short release notes")
    parser.add_argument("--notes-file", default="",
                        help="read release notes from a file (use - for stdin)")
    parser.add_argument("--apk", default="",
                        help="local signed APK to publish (enables GitHub upload)")
    parser.add_argument("--url", default="",
                        help="public APK URL; skip GitHub upload (metadata-only)")
    parser.add_argument("--repo", default="",
                        help="GitHub owner/name (default: detected from origin)")
    parser.add_argument("--asset-name", default="",
                        help="asset filename (default: mr-nobody-<version>.apk)")
    parser.add_argument("--tag", default="",
                        help="release tag (default: v<version>)")
    parser.add_argument("--required", action="store_true",
                        help="mark the release required (persistent nudge only)")
    parser.add_argument("--no-latest", action="store_true",
                        help="do not mark this release as 'latest'")
    parser.add_argument("--published-at", default="",
                        help="ISO-8601 UTC timestamp (default: now)")
    parser.add_argument("--target", default="",
                        help="output file (default: website/update.json)")
    parser.add_argument("--dry-run", action="store_true",
                        help="show what would happen; change nothing")
    args = parser.parse_args()

    if not VERSION_RE.match(args.version):
        sys.exit(f"error: --version must look like 1.1.0, got {args.version!r}")

    notes = args.notes
    if args.notes_file:
        notes = (sys.stdin.read() if args.notes_file == "-"
                 else Path(args.notes_file).read_text(encoding="utf-8"))
    notes = notes.strip()
    if not notes:
        sys.exit("error: provide --notes or --notes-file")

    apk = Path(args.apk) if args.apk else None
    if apk and not apk.is_file():
        sys.exit(f"error: --apk not found: {apk}")

    repo = (args.repo or detect_repo() or "").strip()
    tag = args.tag or f"v{args.version}"
    name = asset_name(args.version, args.asset_name)

    # Decide the download URL and the sha256.
    url = args.url.strip()
    if not apk and not url:
        sys.exit("error: provide --apk (GitHub upload) or --url (metadata-only)")

    if apk and not url:
        # Full GitHub Releases flow.
        if not repo:
            sys.exit("error: could not detect the GitHub repo from git remote; "
                     "pass --repo owner/name")
        url = asset_url(repo, tag, name)
        print(f"==> GitHub Releases flow: {repo} @ {tag}")
        if args.dry_run:
            print(f"  [dry-run] would: gh release create {tag} {apk.name} "
                  f"--repo {repo}")
        else:
            url = create_github_release(repo, tag, f"Mr Nobody {args.version}",
                                        notes, apk, latest=not args.no_latest)
            print(f"  created release {tag}; asset: {url}")
    elif apk and url:
        print(f"==> metadata-only flow (URL given): {url}")
    else:
        print(f"==> metadata-only flow (no APK): {url}")

    sha = sha256_of(apk) if apk else ""
    if apk and not SHA256_RE.match(sha):
        sys.exit("error: computed sha256 is malformed — refusing to publish")

    signature = signing_cert_digest(apk) if apk else ""

    published = args.published_at.strip() or datetime.datetime.now(
        datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    doc = {
        "latestVersion": args.version,
        "releaseNotes": notes,
        "downloadUrl": url,
        "required": args.required,
        "sha256": sha,
        "signature": signature,
        "publishedAt": published,
    }

    target = Path(args.target) if args.target else TARGET
    print("==> metadata")
    print(json.dumps(doc, indent=2))
    if args.dry_run:
        print(f"  [dry-run] would write {target} and rewrite site download links")
        return 0

    write_update_json(target, doc)
    print(f"  wrote {target.relative_to(REPO)}")
    changed = rewrite_site_pages(url)
    for c in changed:
        print(f"  updated download link in {c}")
    if not changed and apk:
        print("  note: no hardcoded download link found to update in site pages")

    print("\nNext:")
    print(f"  git add website/update.json {' '.join(c for c in changed)}")
    print(f'  git commit -m "release v{args.version}"')
    print("  git push")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except PublishError as e:
        sys.exit(f"error: {e}")
