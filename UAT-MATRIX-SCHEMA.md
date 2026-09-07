# UAT Matrix Schema

This document explains the `uat/` directory every UltiTools module repository carries: what
`surface.json` and `assertions.yaml` mean, how to regenerate the former, how to write the latter,
and how a real-machine execution session hands results back into the ledger. It is written for a
module author who has never seen this workflow before — someone who did not build it, following
only this document, should be able to regenerate a surface, write an assertion, and read a
handover document.

The workflow's tooling lives in two places: the extractor (`com.ultikits.ultitools.uat.*`, shipped
in the framework jar) and the Python side (`tools/uat/`, tracked in the framework repository).
`tools/uat/README.md` documents the Python tooling's own command-line usage, the two per-module CI
gates, and the exact reproduction procedure for the repeatability demonstration; this document is
the schema those tools implement, and does not duplicate their command reference.

## Surface schema

`uat/surface.json` is a generated artifact. It is never hand-edited — every field in it is
recomputed from a module's compiled classes by `com.ultikits.ultitools.uat.SurfaceExtractorMain`,
and any edit made by hand is silently discarded the next time it regenerates.

The document has exactly two top-level keys plus a small set of document-level extras, and nothing
else:

- `schema_version` — an integer.
- `items` — the array of surface rows, sorted by `id`.
- `config_entities` — an array, one entry per `@ConfigEntity` class found, sorted by `id`. Present
  even when empty.
- `registers_commands` / `registers_listeners` / `registers_config` — the three booleans read from
  the module's `@UltiToolsModule` annotation, present when the module has one. A `false` value is
  reported, never used to suppress the rows it governs — a suppressed registration is a fact to
  verify, not a reason to hide the function it would have registered.

Deliberately absent: an artifact hash, a framework version, a commit, or a timestamp. Those three
facts live in the local ledger (`ledger.json`) instead, keyed to the artifact that was actually
measured. Embedding them in `surface.json` is what made the previous registry impossible to diff —
two runs against byte-identical source would never produce byte-identical output, because the
timestamp alone always changed. `surface.json`'s only reason to change between two runs is a real
change to the module's compiled surface.

Every row in `items` carries at minimum `id`, `kind`, `origin`, `cls` (simple class name), and
`class` (fully qualified class name). `origin` is the module name (or `framework`, for the
framework's own rows). Beyond that, fields vary by `kind`:

| Kind | Additional fields |
|---|---|
| `command` | `member`, `format`, `aliases[]`, `permission`, `require_op`, `manual_register`, `cmd_target` (when a `@CmdTarget` applies), `params[]` (each `{name, type, suggest}`), `senders[]` (parameter types carrying `@CmdSender`), `cooldown_seconds` (when `@CmdCD` applies), `usage_limit` (when `@UsageLimit` applies), `trigger` (the literal command string, e.g. `/mycmd action <param>`), `gate` (when the class carries `@ConditionalOnConfig` — see below) |
| `help` | The same shape as `command`, one row per `@CmdExecutor` class, with `member` fixed to `handleHelp` and `format`/`trigger` naming the bare command. The `handleHelp` output is the first thing a player types, which is why it is a row of its own rather than folded into a command row. |
| `listener` | `member`, `event` (the first parameter's fully qualified type name, when the handler takes one — qualified rather than simple, so two distinct event classes in different packages sharing a simple name group as separate triggers in `tools/uat/uat.py next`, not as one), `handler_priority`. One row per Bukkit `@EventHandler` method, not one row per `@EventListener` class — a class with four handler methods produces four listener rows. `gate` when applicable. |
| `scheduled` | `member`, `delay_seconds`, `one_shot` (boolean), `period_seconds` (omitted entirely when `one_shot` is true — never emitted as a negative number), `async`. Raw tick counts never appear in the row; `@Scheduled`'s delay/period are always converted to seconds. `gate` when applicable. |
| `config` | `member` (the field name), `config_file` (the owning `@ConfigEntity`'s yml path), `config_entity` (the owning `@ConfigEntity` class's fully qualified name — the join key into `config_entities`), `path` (the `@ConfigEntry` path), `comment` (when non-empty), `field_type`. |
| `persistence` | No `member` — row identity is `kind, origin, cls` only, one row per `@Table` class. Carries `table` (the table name). |
| `conditional` | One row per class carrying `@ConditionalOnConfig`, carrying `gate` (the same `{value, path, negate}` object attached to that class's other rows). |

**The `gate` object.** A class carrying `@ConditionalOnConfig` is not registered at all when its
key is off — the framework never creates the command, listener, or scheduled task in the first
place. Every `command`, `help`, `listener`, and `scheduled` row belonging to a gated class carries
a `gate: {value, path, negate}` object naming the config key that must be true (or false, if
`negate`) for that row to exist at runtime. A tester who does not know the gate will record a false
failure against a row that was never going to register in the current configuration. UltiChat's
`ChannelCommands` is a real instance of this: every row it produces — two command rows, one help
row, and its own standalone `conditional` row — carries the gate.

The extractor's declared scope is thirteen annotation types, fixed and guarded by a test that
fails in either direction if the set ever drifts: `@CmdExecutor`, `@CmdMapping`, `@CmdParam`,
`@CmdSender`, `@CmdCD`, `@CmdTarget`, `@UsageLimit`, `@ConfigEntity`, `@ConfigEntry`,
`@EventListener`, `@Scheduled`, `@ConditionalOnConfig`, `@UltiToolsModule`. `@AsyncCommand` and
`@RunAsync` describe how a command runs, not what the surface is, so they contribute no row of
their own; `@CmdSuggest`'s contribution is already folded into a command row's `params[].suggest`
field.

## Assertion schema

`uat/assertions.yaml` is hand-written. It has two top-level keys:

- `assertions` — a list of assertion objects.
- `gui_excluded_classes` — a list of class names (Phase 9's coverage-gate carve-out, D-09), present
  only in the modules that need it.

Each assertion carries:

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | The surface row id (from `items`) or a `config_entities` entry's id this assertion covers, and nothing else. An assertion's `id` never names anything not present in one of those two arrays. |
| `truth` | yes | An **observable statement** — what must be true after the row's trigger fires. A chat line the player receives, a server log line, a database row's value, an inventory's contents. **"The command ran without error" is not a truth.** It states nothing an observer could disagree with; a truth must name a specific, checkable outcome. |
| `layer` | yes | One of `protocol`, `java-client`, `pixel`, `server`, `human` — the existing automation-fidelity ladder Laojun's `persistent-paper-uat` skill (step 9) already defines. This document does not restate that ladder; it names the layer an assertion was actually proven at. |
| `preconditions` | no | Config keys, permissions, or a second player the assertion needs before its trigger can be exercised meaningfully. |
| `covers_classes` | no | Class names this assertion demonstrates for the purpose of Phase 9's GUI coverage carve-out — every entry in the module's `gui_excluded_classes` must be named by at least one assertion's `covers_classes`, or the checker reports it as uncovered. |
| `negative_controls` | no | What was checked to rule out a false pass — for example, that the same action failed before the fix, or that an unrelated row was unaffected. |

## Config granularity

`surface.json` carries **one row per `@ConfigEntry` field**. That never changes: it is what makes
a newly added config key show up as a new row in the next release's diff.

`assertions.yaml` carries **one assertion per `@ConfigEntity`**, keyed by that entity's id from the
document-level `config_entities` array — **not one per field**. Its `truth` states three things:
the config file loads, every key in it is present with its documented default, and at least one
named representative key, when flipped, changes the behaviour it claims to change.

`assertions.yaml` also carries **one assertion per `@ConditionalOnConfig` gate**, which is already
a `conditional` row in `items`, so it is asserted exactly like any other row.

`check_matrix.py`, the per-module completeness gate, enforces this rule through three named
buckets — a module author meets these by name in the checker's own error output:

- **`unasserted`** — a `command`, `help`, `listener`, `scheduled`, `persistence`, or `conditional`
  row with no assertion. This IS the phase's fifth criterion's "new, unasserted entries" — the
  finding a repeatability check must surface when a function is added.
- **`entity-covered`** — a `config` row whose owning `@ConfigEntity` has an assertion. Not a gap,
  and not something to write a per-field assertion for. Informational only, never a reason to
  fail.
- **`uncovered-entity`** — a `@ConfigEntity` with no assertion at all. A gap, and it exits
  non-zero exactly like `unasserted` does.

A config field added tomorrow appears in the surface diff as a new row either way, and lands in
`entity-covered` if its entity is already asserted or in `uncovered-entity` if it is not —
repeatability is preserved without any per-field assertion.

**Worked example, UltiChat.** UltiChat has 45 `@ConfigEntry` fields across 5 `@ConfigEntity`
classes, plus 1 `@ConditionalOnConfig` gate. Those become 45 surface rows and 6 assertions — the
5 entity assertions plus the 1 conditional assertion — not 45 assertions.

A config field is a parameter, not a player-invocable function, and the phase's fourth criterion
asks for config behaviour to be log-correlated rather than for the possibility space to be
exhausted — that is the whole reason this granularity split exists.

## Regenerating a surface

Run from inside a module checkout, after `mvn test-compile` or `mvn verify`:

```bash
mvn -B org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath \
    -Dmdep.outputFile=target/uat-cp.txt -Dmdep.includeScope=test

java -cp "$(cat target/uat-cp.txt):target/classes" com.ultikits.ultitools.uat.SurfaceExtractorMain \
    --module <ModuleName> --classes target/classes --output uat/surface.json
```

The `maven-dependency-plugin` coordinate is pinned to `3.11.0` explicitly — a module's own
unpinned default can resolve the old `2.8` goal implementation, which behaves differently.

**Why a shorter, single-jar invocation does not work.** `java -cp UltiTools-API-<version>.jar
com.ultikits.ultitools.uat.SurfaceExtractorMain ...` looks like it should be sufficient — the
extractor's classes are in that jar — but it is not. Gson and ByteBuddy are declared `provided`
scope in the framework's own `pom.xml`, supplied at runtime by Paper's `libraries:` loader, and are
therefore absent from the shaded jar entirely. `CanonicalJsonWriter` needs Gson to run at all. The
module's own resolved compile classpath (the first command above) is what actually supplies these
— every module already depends on the framework and, transitively, on Paper's declared libraries —
so the classpath, not the jar alone, is the correct unit of "what the extractor needs to run".

**The UltiBot variant.** UltiBot is a multi-module Maven reactor with no single `src/main/java`
root; its shaded artifact is `ultibot-dist`'s dist jar. `ultibot-dist`'s own
`dependency:build-classpath` output does not carry the `provided`-scope Paper/framework/NMS
dependencies its sibling reactor modules declare — Maven does not propagate `provided` scope
transitively through another module. Use `ultibot-v1_21_R1`'s own classpath (which carries these as
direct dependencies) plus the installed framework jar, and point `--classes` at `ultibot-dist`'s
real shaded jar — not the pre-shade `original-*.jar` that sits alongside it in the same directory:

```bash
mvn -B -pl ultibot-v1_21_R1 org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath \
    -Dmdep.outputFile=target/uat-cp.txt -Dmdep.includeScope=test

java -cp "$(cat target/uat-cp.txt)" com.ultikits.ultitools.uat.SurfaceExtractorMain \
    --module UltiBot --classes ultibot-dist/target/UltiBot-<version>.jar --output uat/surface.json
```

## Byte identity and the drift guard

`surface.json`'s canonical form has one rule: two runs against unchanged source produce
byte-identical output. Every object's keys are sorted, `items` is sorted by `id`, indentation is
two spaces, line endings are LF, encoding is UTF-8, and the file ends with exactly one trailing
newline. Nothing that varies between two runs of the same source — a timestamp, a hash, a map's
insertion order — is ever emitted.

Byte identity, not semantic equality, is the property every module's CI checks, because byte
identity is what makes the next release's diff readable: a real change produces a real, minimal
diff; an unrelated run produces no diff at all. Every one of the seventeen module repositories runs
the same script, `tools/uat/ci-drift-guard.sh`, as a CI step after its existing build/verify step:

```yaml
      - name: Regenerate uat/surface.json and check for drift
        run: bash tools/uat/ci-drift-guard.sh
```

The guard regenerates `uat/surface.json` from the module's own compiled classes and fails, naming
the problem, in three cases: the resolved classpath does not carry the framework jar; the file is
not tracked by git (a completely untracked path is invisible to a plain `git diff`, which reports
zero differences for a file it has never seen — this is the false-clean the guard exists to
prevent); or regeneration produced a file that differs from the one already committed, with the
diff printed before the failure. A file staged for the first time via `git add -N` (intent-to-add,
carrying no real committed content yet) passes — there is nothing yet to have drifted from. Full
detail, including the exact commands and the reasoning behind each check, is in
`tools/uat/README.md`.

## Handover and verdict protocol

This section is the specification a Laojun skill implements against. It can be read and extracted
on its own, without the rest of this document.

**The artifact five-tuple.** Every artifact handed to a real-machine session carries: repository
path, semantic version, byte size, SHA-256, and source commit. The SHA-256 must come from a clean
build made **after the final commit** — a commit hook may rebuild the jar, and a zip's own
timestamps make two semantically identical builds hash differently. A jar built earlier during
development does not count. A hash mismatch against the stated five-tuple is a fail-closed
condition: stop loading that artifact and mark the session failed-closed on it, rather than
assuming "probably the same thing" and continuing.

**The handover table.** A handover document renders one row per assertion (or, for a gated or
config-entity assertion, one row covering everything that assertion's `truth` requires), with
columns `ID`, `Type`, `Steps`, `Expected`, `Layer` — the same shape used by prior real-machine
handovers in this project — plus a leading artifact table carrying the five-tuple for every jar in
the set. `tools/uat/render_handover.py` builds this document mechanically from `surface.json` +
`assertions.yaml` + the five-tuple; a surface row with no assertion yet is listed separately, in
its own section, never silently omitted.

**Batch size.** At most 60 **execution rows** per dispatch. An execution row is an assertion, not a
surface row — a config entity is one execution row regardless of how many `@ConfigEntry` fields it
owns, because its fields contribute zero rows of their own to the execution count. Under this
counting, the accepted execution budget is roughly 470 rows against a roughly 834-row surface,
roughly 8 dispatches at the 60-row ceiling, and roughly 19 hours of session time at a measured 0.41
rows per minute. **The surface count and the execution count are two different numbers and must
never be read as the same one:** 834 counts every `@ConfigEntry` field individually, because that
is what keeps the next release's diff complete; 470 counts what a real-machine session actually
executes, because a config field is a parameter of its entity, not a function of its own.

**Batching by event, for listeners.** An event fires every handler registered for it at once.
Batching listener rows one-per-item makes the executor either repeat the same trigger once per
handler, or record several rows off a single observation with nothing telling it that is correct —
93 listener rows across this ecosystem sit on 34 distinct events, and recording sixteen rows from
one observation has already been a real mistake once. So for listener rows, the unit of a batch is
the *trigger event*, not the registry row: a batch's size counts distinct events, and every handler
that event reaches is listed under it.

**The verdict row schema.** A completed batch is imported back via `uat.py import-verdicts
<verdicts-file>`. Each row carries: `id`, `repository`, `issue` (when the row exists to reconfirm a
specific defect), `type` (`repro`, `control`, or `deferred`), `steps`, `observed`, `status`,
`reason`, `actions[]` (identifiers of the concrete actions taken), `evidence[]` (paths to retained
evidence), and, when the row is a `fail` that also names a real product defect, `return_to[]`
(where that defect should be filed). `status` is one of `pass`, `fail`, or `human-uat-pending` —
the last is a legitimate terminal state for a row, not a placeholder for one that was skipped.

**Three adjudication traps, carried from the original tooling.** These produced real false
failures before they were written down as rules, and are restated inside every batch brief because
a rule the executor never reads is not a rule:

1. An `Unknown or incomplete command` response does not by itself prove a command is unregistered.
   `CommandManager` registers the Bukkit-level permission on every command, so Paper filters an
   unpermitted sender's command tree before the plugin's own `onCommand` is ever reached, and
   answers unknown-command for a command that *is* registered. Prove registration by running as an
   OP (or from console, for a console-capable command).
2. A bare `@CmdMapping(format = "")` method is the handler for the bare command, not a request to
   print help. The framework only prints help when args are exactly `["help"]` or `matchMethod`
   finds nothing. Demanding help output from precisely the method that declares the bare command is
   backwards.
3. A listener absent from a registered-listener dump can mean "conditionally not registered in this
   configuration," not "broken." Some listeners register only when a capability, a config flag, or
   a live UltiPanel connection is present. Read the registration call site before recording a
   `fail` for an absent handler — it may correctly be `blocked` instead.

**Defects are filed, never fixed during the run.** A `fail` a run uncovers is recorded as `fail` in
the ledger and filed as an issue in the owning module's repository, carrying the row id and the
evidence path — never corrected inside the same session. `human-uat-pending` is the terminal state
for a row that genuinely needs a human (personal credentials, subjective visual judgment, or an
interaction no available automation can reach), and **passing a real-machine session is not release
authorisation** — that is a separate decision the maintainer makes explicitly, unrelated to whether
a given batch's rows came back green.

## Adding a module

A repository that does not yet have a `uat/` directory needs four steps:

1. Build the module (`mvn -B test-compile` or `mvn -B verify`) so `target/classes` exists.
2. Run the two-command regeneration procedure in "Regenerating a surface" above, writing the
   output to `uat/surface.json`.
3. Write `uat/assertions.yaml`, one entry per non-config surface row and one per `@ConfigEntity`
   and `@ConditionalOnConfig` gate, per "Config granularity" above. Run
   `tools/uat/check_matrix.py --surface uat/surface.json --assertions uat/assertions.yaml --module
   <ModuleName>` until it exits zero.
4. Add the `ci-drift-guard.sh` step to the module's `maven-ci.yml`, per "Byte identity and the
   drift guard" above, so every future change to the module's compiled surface is caught in CI
   rather than discovered later by hand.
