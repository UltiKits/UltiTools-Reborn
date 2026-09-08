# tools/uat

The tracked, reviewable half of the UltiTools UAT workflow (Phase 10, D-10-13): the module
surface extractor's Python-side counterpart -- a batch driver, a handover-document renderer, and
a verdict importer -- plus the two per-module gates every one of the seventeen module-side
repositories runs before opening its pull request. `~/servers/uat/` (or wherever
`UAT_REGISTRY`/`UAT_LEDGER` point) holds data only from here on: `registry.json`, `ledger.json`,
and any real-machine run artifacts. Neither this directory's scripts nor the workflow they drive
live outside version control any more -- 862 lines of workflow-critical Python that used to sit
unreviewed on one machine are now reviewable in git.

## What's here

| File | Role |
|---|---|
| `uat.py` | The batch driver: `status`, `next`, `record`, `rebase`, `reset` |
| `render_handover.py` | Turns `surface.json` + `assertions.yaml` + the artifact five-tuple into a Laojun-ready Markdown document |
| `import_verdicts.py` | Maps a Laojun verdicts file onto `uat.py`'s own `record` validation |
| `check_matrix.py` | The per-module completeness gate: names every surface row with no stated truth yet (D-10-09/D-10-10) |
| `ci-drift-guard.sh` | The per-module byte-identity gate: regenerates `uat/surface.json` and proves it matches the committed one (D-10-07) |
| `tests/` | pytest suite for `import_verdicts.py`, `render_handover.py`, `check_matrix.py`, `ci-drift-guard.sh`, and the ledger migration helper |

## No default names a developer's home directory

Every filesystem root this tooling needs -- the registry, the ledger, the run-evidence tree --
arrives as a CLI argument or an environment variable, never a hardcoded path. This repository is
public. The three environment variables:

| Variable | What it points at |
|---|---|
| `UAT_REGISTRY` | `registry.json` |
| `UAT_LEDGER` | `ledger.json` |
| `UAT_RUNS` | the real-machine evidence-run tree (reserved; no subcommand reads it yet) |

Every subcommand also accepts `--registry`/`--ledger` explicitly, which take precedence over the
environment variables -- this is how a rebase (or an import) is proven safe on a scratch copy
before it ever touches the real files.

## Running a cycle

```bash
export UAT_REGISTRY=/path/to/uat/registry.json
export UAT_LEDGER=/path/to/uat/ledger.json

python3 tools/uat/uat.py status                    # where the cycle stands
python3 tools/uat/uat.py next --size 25             # hand this brief to the executor
python3 tools/uat/uat.py record <ID> pass "actual response text"   # record one result by hand
```

When Laojun (or any executor) returns a verdicts file instead of recording rows one at a time:

```bash
python3 tools/uat/import_verdicts.py --verdicts uat-verdicts.json --dry-run   # preview first
python3 tools/uat/import_verdicts.py --verdicts uat-verdicts.json            # then write for real
```

`import_verdicts.py` never edits the ledger file directly -- every write goes through the same
id-membership and status checks `uat.py record` itself performs. An unknown id, a bad status, or
malformed input JSON is reported and **nothing is written**, for any row, even if other rows in
the same file are valid. `--dry-run` previews without writing and does not fail the process over
a row-level problem (there was never going to be a write either way) -- only a structurally
broken input (the file itself missing or not valid JSON) is still fatal under `--dry-run`.
Re-importing the same file is a no-op: a row whose recorded status and note already match what
this run would write is left untouched, including its original timestamp, so the ledger stays
byte-identical across a repeat import, and import order never affects the result (the ledger is
always written with sorted keys).

A `fail` row carrying `return_to` is still recorded as `fail` -- it is also printed under a
"Defects to file (not to fix)" heading, naming the destination, because these are separate
concerns: whether the row passed, and where a real product defect it uncovered should be filed.

### A hash change now refuses instead of resetting

`load_ledger` never silently resets `results` to `{}` when it sees a different
`artifact_sha256`. A hash change **stops the tool**, naming both hashes and the exact command to
run instead:

```
REFUSING to load: the deployed artifact changed.
  ledger was measured against  <old hash>
  registry now reports         <new hash>
  <N> recorded result(s) are at risk if this reset silently.
This is now a deliberate act, not an automatic reset. Run:
  uat.py rebase --scope <origin> [--scope <origin> ...]
```

`rebase` is the only path past that refusal. It archives the entire superseded ledger -- with its
own prior history flattened in -- under `superseded_ledgers` (a list, one entry per archived
artifact; see the schema note below) and carries forward only the results whose registry item is
in the requested `--scope`. Nothing a rebase drops from scope is destroyed; it is archived whole.

### Schema note: `superseded_ledgers` is a list

Earlier ledgers nest one prior build under the singular key `superseded_ledger`. As of this
plan, that becomes `superseded_ledgers`, a list with one entry per archived artifact -- so a
ledger's full lineage across several rebases is recoverable from the file alone, not just the
most recent transition. Loading an old-shape ledger migrates it in memory automatically (and any
subcommand that writes the ledger persists the migrated shape); a ledger already carrying the
list is left unchanged.

## The two per-module gates (Phase 10 plan 10-04)

Every one of the seventeen module-side repositories runs both of these before opening its pull
request. Neither reads module source directly -- both operate on the two files a module's own
`uat/` directory carries (`surface.json`, generated; `assertions.yaml`, hand-written).

### `check_matrix.py` -- is this matrix complete?

```bash
python3 tools/uat/check_matrix.py --surface uat/surface.json --assertions uat/assertions.yaml \
    --module MyModule                 # human-readable report
python3 tools/uat/check_matrix.py --surface uat/surface.json --assertions uat/assertions.yaml \
    --json                            # the same five findings as sorted JSON, for a machine caller
```

It sorts every surface row into one of three named buckets, per D-10-09/D-10-10's accepted
config granularity (a config field never needs its own assertion; the entity it belongs to does):

| Bucket | Meaning | Drives a non-zero exit? |
|---|---|---|
| `unasserted` | A command/help/listener/scheduled/gui/persistence/placeholder/behaviour/conditional row with no assertion. This IS criterion 5's "new, unasserted entries". | Yes |
| `entity-covered` | A `config` row whose owning `@ConfigEntity` has at least one assertion. Informational only. | No, never |
| `uncovered-entity` | A `@ConfigEntity` with no assertion at all -- a real gap. | Yes |

It also reports **orphan assertions** (an assertion id matching neither a surface item nor a
`config_entities` entry) and **uncovered GUI classes** (a `gui_excluded_classes` entry -- Phase
9's coverage-gate carve-out, D-09 -- named by no assertion's `covers_classes`); both drive a
non-zero exit when non-empty. Exit is zero only when `unasserted`, `uncovered-entity`, the orphan
list, and the GUI list are all empty. Assertion ids are compared byte-wise -- a case or whitespace
difference is reported as absent, never silently matched.

### `ci-drift-guard.sh` -- is this matrix's surface still true?

```bash
bash tools/uat/ci-drift-guard.sh [MODULE_NAME] [CLASSES_PATH]
```

Run from inside a module checkout, after `mvn test-compile` or `mvn verify`. `MODULE_NAME`
defaults to the checkout directory's base name; `CLASSES_PATH` defaults to `target/classes` --
pass an explicit jar path for a module whose classes live in a shaded dist jar (UltiBot's
`ultibot-dist`). It regenerates `uat/surface.json` from the module's own compiled classes and
proves the result is byte-identical to the one already committed (D-10-07).

The extractor, `com.ultikits.ultitools.uat.SurfaceExtractorMain`, is **not** carried by the
`UltiTools-API` plugin jar (Phase 10, D-10-03 as amended 2026-09-08) -- it is its own published
artifact, `com.ultikits:ultitools-uat-tools`, resolved by explicit coordinate at the module's own
resolved framework version and prepended to the classpath before the extractor runs. See
`UAT-MATRIX-SCHEMA.md`'s "Regenerating a surface" section for why build-time tooling lives outside
the runtime plugin jar and for the two-command form a module author runs by hand.

**The CI step every module's `maven-ci.yml` adds, after its existing `Verify` step:**

```yaml
      - name: Regenerate uat/surface.json and check for drift
        run: bash tools/uat/ci-drift-guard.sh
```

(A module vendoring this script locally instead of referencing the framework checkout should
copy `ci-drift-guard.sh` verbatim -- the script is the single source of truth for all seventeen
CI steps, so they never drift from each other.)

The `maven-dependency-plugin` coordinate is pinned to `3.11.0` explicitly, rather than left to
resolve a module's own default: UltiChat's own unpinned default resolves the old `2.8` goal
implementation, while the framework's own build resolves `3.11.0` -- pinning makes all seventeen
module repositories behave identically regardless of each repo's own unpinned-plugin default.

The guard fails, naming the problem, in these cases: the resolved classpath does not carry the
`UltiTools-API` framework jar (the extractor still needs the framework's classes at runtime);
`com.ultikits:ultitools-uat-tools` cannot be resolved at the module's own framework version from
the repositories the module's build already declares; the resolved tool jar does not contain
`com/ultikits/ultitools/uat/SurfaceExtractorMain.class` (never treated as a silent skip);
`uat/surface.json` is not tracked by git (a completely untracked path is invisible to plain
`git diff`, which reports zero differences for a file it has never seen -- exactly the
false-clean this check exists to prevent, per T-10-14); or regeneration produced a file that
differs from the one already committed (the diff is printed before the failure message).

## Reproducing the repeatability demonstration (Phase 10 success criterion 5)

Criterion 5 has two halves. The first is mechanical: adding one function to a module and
re-running the extractor must surface it in that module's matrix diff as a new entry carrying no
assertion yet, and this must hold for a config field too, whichever side of D-10-10's
per-entity-assertion granularity it lands on. The procedure below names the exact commands that
prove it, against a real module checkout. The second half of criterion 5 asks whether this run is
**reproducible by someone who did not build it** -- that is a human judgement this document
cannot make on its own behalf; a reviewer following the numbered steps below, not the fact that
the steps exist, is the actual test of that claim.

1. **Build the framework, the tool artifact, and pick a module.** From the framework repository
   root -- the extractor is a separate artifact from the framework jar (D-10-03 as amended), so
   both need installing. Capture the version while still at the repository root, before `cd`ing
   into the module checkout:
   ```bash
   mvn -B -q clean install -DskipTests
   mvn -B -q -f tools/uat-surface/pom.xml clean install -DskipTests
   UAT_TOOL_VERSION="$(mvn -B -q -f tools/uat-surface/pom.xml help:evaluate -Dexpression=project.version -DforceStdout)"
   cd <path-to-a-module-checkout>       # e.g. Modules/UltiChat
   mvn -B -q clean test-compile
   ```
2. **Take a baseline.** Build the classpath, regenerate the surface, and hash it. The tool jar is
   resolved by explicit coordinate from the local repository step 1 just installed into --
   mirroring `tools/uat/ci-drift-guard.sh`'s own resolution, not a `find` over the local
   repository (a `find` can come up empty on a cache that never resolved this artifact before,
   and picks an arbitrary entry if more than one version was ever resolved locally):
   ```bash
   mvn -B -q org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath \
       -Dmdep.outputFile=target/uat-cp.txt -Dmdep.includeScope=test
   mvn -B -q org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy \
       -Dartifact=com.ultikits:ultitools-uat-tools:${UAT_TOOL_VERSION}:jar \
       -DoutputDirectory=target/uat-tool -Dmdep.stripVersion=false
   java -cp "target/uat-tool/ultitools-uat-tools-${UAT_TOOL_VERSION}.jar:$(cat target/uat-cp.txt):target/classes" \
       com.ultikits.ultitools.uat.SurfaceExtractorMain \
       --module <ModuleName> --classes target/classes --output /tmp/baseline-surface.json
   sha256sum /tmp/baseline-surface.json
   ```
3. **Add one throwaway `@CmdMapping` method** to any existing command-executor class in that
   module. Repeat step 2's regeneration command against a new output path
   (`/tmp/added-surface.json`). Diff the two documents' `items` by `id`: exactly one id should be
   present in the new surface and absent from the baseline, none should be missing, and none of
   the ids common to both should have changed.
4. **Run the checker against both surfaces** (see above) with `--json`, and diff their
   `unasserted` lists. The one new id from step 3 should be the only difference.
5. **Revert the method from step 3**, regenerate a third time, and confirm the sha256 matches
   step 2's baseline exactly -- the demonstration left nothing behind.
6. **Add one throwaway `@ConfigEntry` field** to an existing `@ConfigEntity` class in the same
   module. Regenerate, and confirm: exactly one new row in `items`; no new entry in
   `config_entities`; the owning entity's `entry_count` incremented by exactly one. Assert that
   entity (write a one-entry scratch `assertions.yaml`, or use the module's real one if it
   already asserts that entity) and confirm the checker places the new row in `entity-covered`
   and does not fail because of it.
7. **Move the same field to a brand new `@ConfigEntity` class** in the module. Regenerate, and
   confirm: exactly one new row in `items`; exactly one new entry in `config_entities`. Run the
   checker with no assertion for the new entity and confirm it fails, naming the new entity (by
   class and yml path) under `uncovered-entity`.
8. **Revert both edits from steps 6-7**, regenerate a final time, and confirm the sha256 again
   matches step 2's baseline. Confirm the module's source tree is clean
   (`git status --porcelain -- src` empty).

Steps 1-8, run in full against a real UltiChat checkout, are recorded with actual command output,
row-level diffs, and every sha256, in this plan's local (non-committed) evidence file.

## Three adjudication traps, carried over from the original tool

These cost real false failures before they were named as rules. They are restated inside every
`next` batch brief too -- a rule the executor never reads is not a rule.

1. **`Unknown or incomplete command` does not by itself prove a command is unregistered.**
   `CommandManager` registers the Bukkit-level permission on every command, so Paper filters the
   command out of an unpermitted sender's command tree and answers unknown-command for commands
   that *are* registered -- the plugin's `onCommand` is never reached. To prove a command is
   registered, run it as an OP (or from console for a console-capable command).
2. **A bare `@CmdMapping(format = "")` method is the handler for the bare command, not a request
   for it to print help.** The framework only prints help when args are exactly `["help"]` or
   `matchMethod` finds nothing. Demanding help output from precisely the method that declares
   bare is an action is backwards.
3. **A listener absent from a registered-listener dump can mean "conditionally not registered in
   this configuration," not "broken."** Some listeners register only when a capability, a config
   flag, or a live UltiPanel connection is present. Read the registration call site before
   recording a `fail` for an absent handler -- it may be correctly `blocked` instead.

## `next`'s event-grouped batches for listeners

An event fires every handler registered for it at once. Batching listener rows one-per-item
would make the executor either repeat the same trigger once per handler or record several rows
off a single observation with nothing telling it that is correct. So for `--kind listener` (or
any batch passing `--group-by-event`), the unit of a batch is the *trigger*, not the registry
row: `--size` counts distinct events, and every handler that event reaches is listed under it.
