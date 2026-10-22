#!/usr/bin/env python3
"""Fail closed on common credentials and local runtime files in the Git index.

Reads only tracked/index blobs. Never opens ignored local secrets or prints matches.
"""
import re
import subprocess
import sys


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", *args], stderr=subprocess.DEVNULL)


paths = [p.decode("utf-8", "surrogateescape") for p in git("ls-files", "-z").split(b"\0") if p]
forbidden_path = re.compile(r"(^|/)(?:\.env(?:\.(?!example$)[^/]*)?|\.hradar|\.recruitment-radar|desktop-instance\.json|telegram-(?:api|bot)\.json|tdlib|[^/]+\.(?:sqlite3?(?:-[^/]*)?|db(?:-[^/]*)?|session|pem|key))($|/)", re.I)
patterns = [
    re.compile(rb"\b[1-9][0-9]{5,}:[A-Za-z0-9_-]{30,}\b"),  # Bot API token
    re.compile(rb"(?i)\b(?:TG_API_HASH|TELEGRAM_API_HASH)\s*[:=]\s*['\"]?[0-9a-f]{32}\b"),
    re.compile(rb"(?i)\b(?:TG_API_ID|TELEGRAM_API_ID)\s*[:=]\s*['\"]?[1-9][0-9]{4,}\b"),
    re.compile(rb"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(rb"\b(?:github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{30,}|sk-[A-Za-z0-9]{32,})\b"),
]
bad_paths = sum(bool(forbidden_path.search(p)) for p in paths if p != ".env.example")
bad_contents = 0
for path in paths:
    try:
        blob = git("show", ":" + path)
    except subprocess.CalledProcessError:
        continue  # A staged deletion has no index blob.
    bad_contents += any(pattern.search(blob) for pattern in patterns)
if bad_paths or bad_contents:
    print(f"Secret check failed: {bad_paths} sensitive tracked paths, {bad_contents} credential-pattern files. Values withheld.", file=sys.stderr)
    sys.exit(1)
print(f"Secret check passed: {len(paths)} tracked paths inspected; no credential patterns found.")
