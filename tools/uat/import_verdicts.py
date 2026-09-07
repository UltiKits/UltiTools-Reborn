#!/usr/bin/env python3
"""
Import a Laojun verdicts file into the UAT ledger, through uat.py's own validation.

Usage:
    import_verdicts.py --verdicts uat-verdicts.json --registry <path> --ledger <path> [--dry-run]

Every write goes through the same two checks `uat.py record` itself performs -- the row id
must be a real registry id, and the status must be one of `pass`, `fail`, `blocked`,
`human-uat-pending` (D-10-16's named exit) -- and the
final write goes through `uat.py`'s own `load_ledger`/`save_ledger`, never a raw edit of the
ledger file (Phase 10, D-10-14). All rows are validated BEFORE any write happens: an unknown
id, a bad status, or malformed input JSON is reported and nothing is written.

`--dry-run` previews what would happen without writing anything and without failing the
process on a row-level problem (an unknown id, a bad status) -- those are reported to stderr
as "would fail" rather than raised, since nothing was ever going to be written either way. A
structurally broken input (the verdicts file itself is missing or is not valid JSON) is still
fatal in `--dry-run`: there is nothing to preview.

Re-importing the same file is a no-op: a row whose already-recorded status and note exactly
match what this run would write is left untouched (including its original timestamp), so the
ledger is byte-identical to the previous run's output. Import order never affects the result --
`uat.py`'s own `save_ledger` writes with sorted keys, so two runs over the same content produce
the same bytes regardless of which order the rows were listed in.
"""
import argparse
import datetime
import json
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import uat  # noqa: E402  (sys.path must be adjusted before this import)

VALID_STATUSES = ('pass', 'fail', 'blocked', 'human-uat-pending')


def load_verdicts(path):
    try:
        with open(path, encoding='utf-8') as handle:
            document = json.load(handle)
    except OSError as e:
        sys.exit(f'Failed to read verdicts file {path}: {e}')
    except json.JSONDecodeError as e:
        sys.exit(f'Verdicts file {path} is not valid JSON: {e}')
    rows = document.get('rows')
    if rows is None:
        rows = []
    return rows


def build_note(row):
    """
    Build the note stored in the ledger for one row.

    The actual observation, quoted, plus the evidence file list if any -- matching this
    project's own "quote the server's own words" adjudication rule rather than a bare status
    flip.
    """
    observed = row.get('observed', '')
    evidence = row.get('evidence') or []
    if evidence:
        return f"{observed} [evidence: {', '.join(evidence)}]"
    return observed


def validate_rows(rows, known_ids):
    """
    Validate every row before any write.

    Returns a list of problem strings; empty means every row is safe to write. `known_ids`
    must already be narrowed to the CURRENT ledger's own scope (see `resolve_known_ids`) --
    a stale verdicts file naming a row this ledger no longer tracks is exactly the kind of
    unknown id this check exists to catch, not something a broader "any registry id" check
    would let through.

    A repeated `id` within the same file is also a problem, not merely a duplicate key:
    `apply_rows` writes rows in list order, so two rows sharing an id with different
    status/observed content would silently let whichever comes LAST win -- reversing the
    list would then change the recorded verdict, contradicting this importer's own stated
    "import order never affects the result" guarantee.
    """
    problems = []
    seen_ids = {}
    for index, row in enumerate(rows):
        row_id = row.get('id')
        status = row.get('status')
        if row_id not in known_ids:
            problems.append(f'row {index} (id={row_id!r}): unknown id -- not present in the registry, '
                             f'or not in this ledger\'s current scope')
            continue
        if status not in VALID_STATUSES:
            problems.append(
                f'row {index} (id={row_id!r}): status {status!r} is not one of {VALID_STATUSES}')
            continue
        if row_id in seen_ids:
            problems.append(f'row {index} (id={row_id!r}): duplicate id, first declared at row {seen_ids[row_id]}')
        else:
            seen_ids[row_id] = index
    return problems


def resolve_known_ids(reg, led):
    """
    Compute the set of ids a verdicts file may legitimately reference.

    Scoped to the CURRENT ledger's own `scope` (via `uat.resolve_scope`/`uat.in_scope`), not
    every id the registry happens to contain. After a narrowed `rebase --scope framework`,
    a stale verdicts file for an excluded module must be rejected the same way an unknown id
    is -- this ledger no longer tracks that module's rows, and writing one back in would
    silently reintroduce a result the rebase deliberately dropped from this build's scope.
    """
    scope = uat.resolve_scope(reg, led, None)
    return {item['id'] for item in reg['items'] if uat.in_scope(item, scope)}


def summarize(counter):
    if not counter:
        return '0 results'
    return ', '.join(f'{count} {status}' for status, count in sorted(counter.items()))


def apply_rows(led, rows):
    """
    Write every row into `led['results']`.

    Skips a row whose already-recorded status and note are unchanged (idempotent re-import:
    the original `at` timestamp is preserved and the ledger stays byte-identical on a repeat
    run). Returns (written_by_status, defects_to_file, any_changed).
    """
    written_by_status = Counter()
    defects_to_file = []
    any_changed = False
    for row in rows:
        row_id = row['id']
        status = row['status']
        note = build_note(row)
        existing = led['results'].get(row_id)
        if existing is not None and existing.get('status') == status and existing.get('note') == note:
            continue  # unchanged: leave the prior entry (and its timestamp) untouched
        led['results'][row_id] = dict(
            status=status, note=note,
            at=datetime.datetime.now().astimezone().isoformat(timespec='seconds'))
        written_by_status[status] += 1
        any_changed = True
        if status == 'fail' and row.get('return_to'):
            defects_to_file.append((row_id, row['return_to']))
    return written_by_status, defects_to_file, any_changed


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--verdicts', required=True, help='path to the Laojun verdicts JSON file')
    parser.add_argument('--registry', required=True, help='path to registry.json')
    parser.add_argument('--ledger', required=True, help='path to ledger.json')
    parser.add_argument('--dry-run', action='store_true', dest='dry_run',
                         help='preview without writing; row-level problems are reported, not fatal')
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)

    rows = load_verdicts(args.verdicts)
    reg = uat.load_reg(args.registry)
    led = uat.load_ledger(reg, args.ledger)
    # Backfilled here, before resolve_known_ids, so a legacy pre-scope ledger's one-line
    # backfill note (uat.resolve_scope's own stderr print) is emitted once, not twice.
    if not led.get('scope'):
        led['scope'] = uat.resolve_scope(reg, led, None)
    known_ids = resolve_known_ids(reg, led)

    problems = validate_rows(rows, known_ids)
    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        if args.dry_run:
            print(f'(dry run) {len(problems)} problem(s) found above; nothing would be written '
                  f'for the invalid row(s), and nothing was written for any row this run.')
            return 0
        sys.exit(f'{len(problems)} problem(s) found; nothing written. Fix the row(s) above and re-run.')

    if not rows:
        print('wrote 0 result(s)')
        return 0

    written_by_status, defects_to_file, any_changed = apply_rows(led, rows)

    if args.dry_run:
        print(f'(dry run) would write {sum(written_by_status.values())} result(s): '
              f'{summarize(written_by_status)}')
        return 0

    if any_changed:
        uat.save_ledger(led, args.ledger)
    print(f'wrote {sum(written_by_status.values())} result(s): {summarize(written_by_status)}')
    if defects_to_file:
        print('\nDefects to file (not to fix) -- rows carrying return_to:')
        for row_id, return_to in defects_to_file:
            print(f'  {row_id}: {return_to}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
