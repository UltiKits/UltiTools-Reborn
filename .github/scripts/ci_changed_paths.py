#!/usr/bin/env python3
"""Classify the complete PR or pushed range; fail closed on unreadable Git evidence."""

import json
import os
import re
import subprocess
import sys
from pathlib import Path


def git(*args):
    return subprocess.check_output(["git", *args], input=b"")


def commit(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError("event endpoint must be a full commit SHA")
    git("cat-file", "-e", value + "^{commit}")
    return value


def classify(event_name, event, head):
    if event_name == "workflow_dispatch":
        # The workflow's job-level main-only guard still applies to manual publication.
        return {"product": True, "build": True, "tooling": True}
    if event_name == "pull_request":
        base = commit(event["pull_request"]["base"]["sha"])
        head = commit(event["pull_request"]["head"]["sha"])
        base = git("merge-base", base, head).decode("ascii").strip()
    elif event_name == "push":
        head = commit(head)
        base = event["before"]
        if base == "0" * 40:
            base = git("hash-object", "-t", "tree", "--stdin").decode("ascii").strip()
        else:
            base = commit(base)
    else:
        raise ValueError("unsupported event: " + event_name)
    # No rename detection: moving product code out of scope is still a product deletion.
    paths = git("diff", "--no-renames", "--name-only", "-z", base, head).split(b"\0")
    product = any(p == b"pom.xml" or p.startswith(b"src/main/") for p in paths)
    return {"product": product,
            "build": product or any(p.startswith(b"src/test/") for p in paths),
            "tooling": any(p.startswith((b".github/scripts/", b".github/workflows/")) for p in paths)}


def main():
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    result = classify(os.environ["GITHUB_EVENT_NAME"], event, os.environ["GITHUB_SHA"])
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="ascii") as output:
        for key, value in result.items():
            print("%s=%s" % (key, str(value).lower()), file=output)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (KeyError, ValueError, OSError, subprocess.CalledProcessError) as error:
        print("::error::cannot classify changed paths: %s" % error, file=sys.stderr)
        sys.exit(1)
