# tools/uat

The tracked, reviewable half of the UltiTools UAT workflow (Phase 10, D-10-13): the module
surface extractor's Python-side counterpart -- a batch driver, a handover-document renderer, and
a verdict importer. `~/servers/uat/` (or wherever `UAT_REGISTRY`/`UAT_LEDGER` point) holds data
only from here on: `registry.json`, `ledger.json`, and any real-machine run artifacts. Neither
this directory's scripts nor the workflow they drive live outside version control any more --
862 lines of workflow-critical Python that used to sit unreviewed on one machine are now
reviewable in git.

## What's here

| File | Role |
|---|---|
| `uat.py` | The batch driver: `status`, `next`, `record`, `rebase`, `reset` |
| `render_handover.py` | Turns `surface.json` + `assertions.yaml` + the artifact five-tuple into a Laojun-ready Markdown document |
| `import_verdicts.py` | Maps a Laojun verdicts file onto `uat.py`'s own `record` validation |
| `tests/` | pytest suite for `import_verdicts.py`, `render_handover.py`, and the ledger migration helper |

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
