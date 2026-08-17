#!/usr/bin/env python3
"""Validate the bundled blocklist format and report statistics.

Usage:
    python3 tools/filter_validation.py [path/to/blocklist.txt]

Exit code 0 = valid, 1 = problems found.

Checks:
  - every non-comment line is one of the supported rule shapes
  - no duplicate domains across ADS/TRACKERS (would be ambiguous)
  - counts per category

This is development-only tooling; Python never ships in the APK.
"""
import sys
import re
from collections import Counter


def classify(line: str) -> str:
    """Return 'ads', 'trackers', 'comment', 'empty', or 'invalid'."""
    line = line.strip()
    if not line:
        return "empty"
    if line.startswith("!"):
        return "comment"
    if line.upper() in ("[ADS]", "[TRACKERS]"):
        return "section"
    # allowlist exceptions and element-hiding rules are accepted but not blocking
    if line.startswith("@@"):
        return "exception"
    if "##" in line or "#@#" in line:
        return "element-hiding"
    # a valid network rule in our subset: ||domain, ||domain/path, *wild*, or bare domain
    body = line
    if body.startswith("||"):
        body = body[2:]
    body = body.split("$", 1)[0]          # strip options
    body = body.rstrip("|^")
    if not body:
        return "invalid"
    # domain part must look sane
    if "*" not in body.split("/", 1)[0]:
        host = body.split("/", 1)[0]
        if not re.fullmatch(r"[a-zA-Z0-9.\-]+", host):
            return "invalid"
    return "rule"


def main(path: str) -> int:
    try:
        with open(path, "r", encoding="utf-8") as f:
            lines = f.read().splitlines()
    except OSError as e:
        print(f"ERROR: cannot read {path}: {e}")
        return 1

    current = "ads"
    counts = Counter()
    domains: dict[str, str] = {}  # domain -> category (duplicate detection)
    problems = []

    for i, line in enumerate(lines, 1):
        kind = classify(line)
        counts[kind] += 1
        stripped = line.strip()
        if stripped.upper() == "[ADS]":
            current = "ads"
            continue
        if stripped.upper() == "[TRACKERS]":
            current = "trackers"
            continue
        if kind == "rule":
            body = stripped[2:] if stripped.startswith("||") else stripped
            body = body.split("$", 1)[0].rstrip("|^")
            host = body.split("/", 1)[0]
            if "*" not in host:
                if host in domains and domains[host] != current:
                    problems.append(
                        f"line {i}: domain '{host}' appears in both {domains[host]} and {current}")
                domains[host] = current
        elif kind == "invalid":
            problems.append(f"line {i}: invalid rule: {stripped!r}")

    print(f"File: {path}")
    print(f"  ADS rules:      {sum(1 for d in domains.values() if d == 'ads')} domains "
          f"(+{counts['rule'] - len(domains)} path/wildcard rules)")
    print(f"  TRACKER rules:  {sum(1 for d in domains.values() if d == 'trackers')} domains")
    print(f"  comments: {counts['comment']}  exceptions: {counts['exception']}  "
          f"element-hiding: {counts['element-hiding']}")

    if problems:
        print("\nPROBLEMS:")
        for p in problems:
            print("  " + p)
        return 1
    print("\nOK — blocklist is valid.")
    return 0


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else "filters/bundled/blocklist.txt"
    sys.exit(main(path))
