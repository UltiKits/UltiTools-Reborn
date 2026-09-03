# check-comment-only-diff.sh self-proof transcript

D-21 (08-CONTEXT.md) requires this check to be proved firing on a real diff before it stands in
for per-PR real-machine UAT on GATE-02's four comment-conversion batches. This file is the
durable record of that proof — the throwaway branch it was produced on
(`tmp/d21-selfproof`) was deleted after the run, per the plan's own instruction that the
transcript, not the branch, is the artifact that persists.

## Setup

- Base ref: the phase branch tip at the time of the proof
  (`gsd/phase-08-gate-hardening-release-closeout`, commit `b350e97`, immediately after Task 1's
  `check-comment-only-diff.sh` was committed).
- Four commits were made in sequence on `tmp/d21-selfproof`, each editing
  `src/main/java/com/ultikits/ultitools/UltiTools.java`, a real CRLF file that also carries
  several `i18n("...")` literals.
- After each commit, the check was run as:
  `check-comment-only-diff.sh --base gsd/phase-08-gate-hardening-release-closeout --head HEAD`
- Because the four commits are sequential on one branch, each run's diff is cumulative — a
  violation introduced by an earlier case is still present (and still reported) in every later
  run. This is expected and does not contradict any case's own pass/fail requirement below.

## Case 1 — comment-only change (must pass all three)

Edit: reworded one word inside an existing English `//` comment (the "Deliberately
java.util.logging" comment near the top of the class). No code line, no i18n literal, and no
line-ending byte touched.

Exit code (exit status): **0**

Output:
```
assertion A (comments-only): PASS
assertion B (i18n literal set unchanged): PASS
assertion C (line-ending type unchanged): PASS
SUMMARY: assertion A PASS, assertion B PASS, assertion C PASS.
```

## Case 2 — one-character change inside an i18n literal (must fail assertion B)

Edit: removed one character (one trailing `.` of an ellipsis) from inside the string literal
argument of a real `i18n("...")` call site.

Exit code (exit status): **1**

Output (assertion B section):
```
::error::assertion B (i18n literal set unchanged): literal set changed
  removed: i18n("<original literal, ending in three dots>")
  added:   i18n("<same literal, ending in two dots>")
```
(The literal's own characters are Chinese-language text and are not reproduced verbatim in this
English-only record; see the fixture-generating commit's diff for the exact bytes.)

Because the edited literal lives on an ordinary code line (not inside a comment), this case also
failed assertion A — the line itself is not comment-shaped, so the same line is independently
reported as a non-comment change. Both failures are expected together here; only assertion B's
firing is the property this case exists to prove.

## Case 3 — change to one executable statement (must fail assertion A)

Edit: reworded a plain string-literal argument to a `getLogger().log(...)` call that has no
i18n involvement and does not touch any line-ending byte.

Exit code (exit status): **1**

Output (assertion A section, new violation from this case):
```
::error::assertion A (comments-only): src/main/java/com/ultikits/ultitools/UltiTools.java:470: non-comment change
```
Assertion B still failed, carried over unchanged from case 2 (this case did not touch any i18n
literal, so the reported added/removed pair is identical to case 2's). Assertion C still passed.

## Case 4 — whole-file CRLF to LF conversion (must fail assertions A and C)

Edit: stripped the trailing carriage return from every line of the same file (`sed -i
's/\r$//'`), with no other textual change. `file` reported the file as CRLF before this edit and
as plain LF after.

Exit code (exit status): **1**

Output (assertion A and C sections):
```
::error::assertion A (comments-only): src/main/java/com/ultikits/ultitools/UltiTools.java:775: non-comment change
::error::assertion A (comments-only): src/main/java/com/ultikits/ultitools/UltiTools.java:776: non-comment change
::error::assertion A (comments-only): src/main/java/com/ultikits/ultitools/UltiTools.java:778: non-comment change
::error::assertion A (comments-only): src/main/java/com/ultikits/ultitools/UltiTools.java:779: non-comment change
::error::assertion A (comments-only): src/main/java/com/ultikits/ultitools/UltiTools.java:780: non-comment change
::error::assertion C (line-ending type unchanged): src/main/java/com/ultikits/ultitools/UltiTools.java: line-ending type changed CRLF -> LF
SUMMARY: assertion A FAIL, assertion B FAIL, assertion C FAIL.
```
Assertion A failed because stripping the carriage return changes the byte content of every line
in the diff (git sees old-CRLF-line vs new-LF-line as different content on nearly all of the
file's 780 lines, and the vast majority are code, not comments) — this is the expected
"proving both are reported together" case named in the plan. Assertion C failed on exactly the
one file whose CRLF-ness flipped. Assertion B's failure is carried over from case 2, unrelated to
this case's own edit.

## Cleanup

- `tmp/d21-selfproof` was deleted after the fourth run: `git branch -D tmp/d21-selfproof`.
- The working tree was restored to `gsd/phase-08-gate-hardening-release-closeout`'s own state —
  `git status --porcelain -- src/main/java` returned empty, and `git status --porcelain` for the
  whole tree returned empty.

## Summary of exit codes

| Case | Edit | Exit | Assertions failing |
|---|---|---|---|
| 1 | comment-only | 0 | none |
| 2 | i18n literal, one character | 1 | A, B |
| 3 | executable statement | 1 | A (B carried over from case 2) |
| 4 | whole-file CRLF to LF | 1 | A, C (B carried over from case 2) |

## Known limitation observed during this proof

Case 4's full-file rewrite made the check take about one minute to run, dominated by the
per-changed-line block-comment-open-state lookup (`sed -n`, invoked once per candidate line).
Real GATE-02 batches touch far fewer lines per file than a whole-file line-ending flip, so this
is not expected to be a practical problem for the four actual comment-conversion PRs — but a
whole-file rewrite of a large source file is a slow case worth knowing about if this check is
ever pointed at a bigger diff.
