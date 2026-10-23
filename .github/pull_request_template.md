## Summary

<!-- What this pull request changes, and why. English-first, Chinese as a supplement. -->

## Issue closure

<!--
REQUIRED - do not delete this section.

List every issue this pull request closes, one per line, using a closing keyword:

    Closes #1234

Write `None` if it closes nothing, so that an empty section reads as a decision rather than
an oversight.

Feature pull requests target `main`, the default branch. GitHub closes declared issues when
this pull request merges. Verify issue closure and board state after merging.
Put real declarations outside this comment and outside code fences or backticks.
-->

## Verification

<!-- The commands you ran and what they actually reported. Paste the counts, not "tests pass". -->

## Checklist

<!-- Delete any line that does not apply. -->

- [ ] Targets `main`; releases are marked by tags
- [ ] Line endings preserved per file (`file <path>` before and after; this tree is mixed CRLF/LF)
- [ ] Every comment, javadoc, workflow comment, and this PR's own title and body are English-first with Chinese as a supplement, and nothing was added to `.github/cjk-allowlist.txt`
- [ ] Documentation synced in `UltiTools-Dev-Doc` (branch `alpha`) if documented behaviour changed
- [ ] `FEATURES.md` and `UAT-CHECKLIST.md` updated for every feature change in this PR, or N/A with the reason
