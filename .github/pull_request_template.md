## Summary

<!-- What this pull request changes, and why. English-first, Chinese as a supplement. -->

## Issue closure

<!--
REQUIRED - do not delete this section.

List every issue this pull request closes, one per line, using a closing keyword:

    Closes #1234

Write `None` if it closes nothing, so that an empty section reads as a decision rather than
an oversight.

Why this section exists: feature pull requests here target `alpha`, not the default branch
`main`. GitHub acts on closing keywords only for pull requests merged into the default branch,
so `Closes #1234` on its own has never closed anything in this repository. After the merge,
`.github/workflows/phase-closeout.yml` reads this section and performs the closure.

The workflow ignores anything inside an HTML comment, a code fence, or backticks - which is why
the example above closes nothing. Put real declarations outside this comment.
-->

## Verification

<!-- The commands you ran and what they actually reported. Paste the counts, not "tests pass". -->

## Checklist

<!-- Delete any line that does not apply. -->

- [ ] Targets `alpha`, unless this is an `alpha` -> `main` release promotion
- [ ] Line endings preserved per file (`file <path>` before and after; this tree is mixed CRLF/LF)
- [ ] New and modified comments are English-first
- [ ] Documentation synced in `UltiTools-Dev-Doc` (branch `alpha`) if documented behaviour changed
