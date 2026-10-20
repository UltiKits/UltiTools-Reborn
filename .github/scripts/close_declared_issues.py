#!/usr/bin/env python3
"""Close the issues a merged pull request declares in its body."""

# GitHub acts on closing keywords only for pull requests merged into the default branch. This
# repository's feature pull requests target `alpha`, so that never fires and the declaration in
# the body is inert. This script consumes it instead.
#
# Run from a workflow with GH_TOKEN, GH_REPO, PR_BODY, PR_NUMBER and BASE_REF in the environment.
#
# The docstrings here are deliberately single-line: Codacy runs pydocstyle with both D212
# ("summary on the first line") and D213 ("summary on the second line") enabled, which are
# mutually exclusive, so any multi-line docstring trips one of them whichever way it is written.

import os
import re
import subprocess
import sys

# The nine keywords GitHub itself recognises. Anchored with \b on the left so `unfixes #1` and
# `prefix #1` do not match, and requiring whitespace before `#` so a cross-repository reference
# (`Closes owner/repo#12`) is left alone - closing an issue in another repository is not this
# workflow's business.
KEYWORDS = r"clos(?:e|es|ed)|fix(?:|es|ed)|resolv(?:e|es|ed)"
SHORTHAND = re.compile(rf"\b(?:{KEYWORDS})\s+#(\d+)\b", re.IGNORECASE)


def issue_url_pattern(repo: str) -> re.Pattern:
    """Match `Closes https://github.com/<this repo>/issues/123`, which GitHub also accepts."""
    return re.compile(
        rf"\b(?:{KEYWORDS})\s+https://github\.com/{re.escape(repo)}/issues/(\d+)\b",
        re.IGNORECASE,
    )


def strip_non_prose(body: str) -> str:
    """Remove regions where an issue reference is not a declaration."""
    # Order matters and all three are required:
    #
    # * HTML comments - the pull request template puts a worked `Closes #1234` example inside one.
    #   An author who leaves it in place would otherwise close whatever that example names.
    # * Fenced code blocks - bodies here routinely quote build output and configuration.
    # * Inline code - a `Closes #12` quoted while discussing the convention is not a declaration.
    body = re.sub(r"<!--.*?-->", " ", body, flags=re.DOTALL)
    body = re.sub(r"```.*?```", " ", body, flags=re.DOTALL)
    body = re.sub(r"~~~.*?~~~", " ", body, flags=re.DOTALL)
    body = re.sub(r"`[^`\n]*`", " ", body)
    return body


def declared_issues(body: str, repo: str) -> list:
    prose = strip_non_prose(body)
    found = {int(n) for n in SHORTHAND.findall(prose)}
    found |= {int(n) for n in issue_url_pattern(repo).findall(prose)}
    return sorted(found)


def gh(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["gh", *args], capture_output=True, text=True)


def last_line(stream: str) -> str:
    """The most specific line of a gh error. It is the only diagnostic a failed run leaves."""
    lines = [line for line in (stream or "").strip().splitlines() if line.strip()]
    return lines[-1] if lines else "no error output"


def summary(line: str) -> None:
    print(line)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(line + "\n")


def main() -> int:
    body = os.environ.get("PR_BODY") or ""
    repo = os.environ["GH_REPO"]
    pr = os.environ["PR_NUMBER"]
    base = os.environ.get("BASE_REF", "")

    numbers = declared_issues(body, repo)
    if not numbers:
        summary(f"No issue declared in #{pr}. Nothing to close.")
        return 0

    summary(f"#{pr} declares: {', '.join('#' + str(n) for n in numbers)}")

    comment = (
        f"Closed by #{pr}, merged into `{base}`.\n\n"
        "This repository's feature pull requests target `alpha` rather than the default branch, "
        "so GitHub's own closing keywords do not fire on merge. "
        "`.github/workflows/phase-closeout.yml` reads the declaration in the pull request body "
        "and performs the closure."
    )

    failed = []
    for number in numbers:
        state = gh("issue", "view", str(number), "--json", "state", "--jq", ".state")
        if state.returncode != 0:
            summary(f"  #{number}: SKIPPED - cannot read it ({last_line(state.stderr)})")
            failed.append(number)
            continue
        if state.stdout.strip() == "CLOSED":
            summary(f"  #{number}: already closed, left alone")
            continue

        closed = gh("issue", "close", str(number), "-r", "completed", "-c", comment)
        if closed.returncode == 0:
            summary(f"  #{number}: closed")
        else:
            summary(f"  #{number}: FAILED - {closed.stderr.strip()}")
            failed.append(number)

    if failed:
        # Surface the failure rather than passing quietly: a silent miss here reproduces exactly
        # the defect this workflow exists to prevent.
        print(f"::error::Could not close: {', '.join('#' + str(n) for n in failed)}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
