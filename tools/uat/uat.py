#!/usr/bin/env python3
"""
UAT batch driver. The ledger, not anyone's memory, is the source of truth.

  uat.py status                  what is done, pending, failed, for this framework build
  uat.py next --size 25 [--kind command] [--origin UltiEssentials]
                                 emit a self-contained brief for the next pending batch
  uat.py record <id> <pass|fail|blocked|human-uat-pending> "<what actually happened>"
                                 human-uat-pending is D-10-16's named exit -- reserved for
                                 a row that genuinely needs a human (pixel layer, personal
                                 credentials), not a routine substitute for blocked
  uat.py rebase --scope <origin> [--scope <origin> ...] [--dry-run]
                                 the ONLY way past an artifact-hash change: archives the
                                 superseded ledger whole, carries forward only the results
                                 whose origin is in scope, and stamps a scope on the result
  uat.py reset                   start a fresh cycle (new release)

A batch brief is self-contained on purpose: the executor is stateless and its context
will not survive between batches. Nothing in a brief may depend on remembering an
earlier one.

Every subcommand accepts --registry/--ledger path overrides, falling back to the
UAT_REGISTRY/UAT_LEDGER environment variables (Phase 10, D-10-13). There is no default
that names a user directory -- this file is tracked in a public repository, and every
filesystem root a caller needs must arrive as a CLI argument or an environment variable.
They also exist so a rebase can be proven on a copy before it is ever run for real --
pointing them at scratch paths is how that proof is done without touching production.

`--runs`/UAT_RUNS is accepted for the same reason but is not read by any subcommand in
this file yet -- it names the root the real-machine evidence tree lives under, reserved
for a future subcommand that needs it.
"""
import json
import os
import sys
import argparse
import datetime
import tempfile
from collections import Counter

REGISTRY_ENV = 'UAT_REGISTRY'
LEDGER_ENV = 'UAT_LEDGER'
RUNS_ENV = 'UAT_RUNS'

# UAT-MATRIX-SCHEMA.md's "Handover and verdict protocol" section fixes this as an absolute
# per-dispatch ceiling on execution rows. `--size` counts units (an item or a whole listener
# event), not raw rows, but the ceiling is still binding at the unit level: a value above it
# lets a single call emit more non-listener items than any one real-machine session is meant
# to receive, and a value at or below zero slices from the wrong end of the pending list
# (Python's negative-index slicing), silently returning nearly the entire backlog for a
# simple typo like `--size -1`.
MAX_BATCH_SIZE = 60


def positive_capped_int(raw):
    """argparse `type=` for `--size`: an integer in [1, MAX_BATCH_SIZE], nothing else."""
    try:
        value = int(raw)
    except ValueError:
        raise argparse.ArgumentTypeError(f'{raw!r} is not an integer')
    if not 1 <= value <= MAX_BATCH_SIZE:
        raise argparse.ArgumentTypeError(
            f'{raw!r} is out of range -- must be between 1 and {MAX_BATCH_SIZE} inclusive')
    return value


def resolve_path(cli_value, env_var, what):
    """
    Resolve one filesystem root.

    An explicit CLI value wins, then the named environment variable, then a fail-closed
    error naming both ways to supply it. Never falls back to a hardcoded developer path
    (Phase 10, D-10-13).
    """
    if cli_value:
        return cli_value
    from_env = os.environ.get(env_var)
    if from_env:
        return from_env
    sys.exit(f'{what} not given: pass it explicitly or set {env_var}')


def registry_path(a):
    return resolve_path(a.registry, REGISTRY_ENV, '--registry')


def ledger_path(a):
    return resolve_path(a.ledger, LEDGER_ENV, '--ledger')


def load_reg(path):
    if not os.path.exists(path):
        sys.exit(f'{path} missing - run gen-registry.py first')
    return json.load(open(path, encoding='utf-8'))


def migrate_superseded_key(led):
    """
    Migrate the singular `superseded_ledger` key to the `superseded_ledgers` list.

    A one-shot schema migration (Phase 10, D-10-14): the singular `superseded_ledger` object
    becomes a `superseded_ledgers` list holding that one entry. A ledger already carrying the
    list is left unchanged -- idempotent, and safe to call on every read. Recurses into the
    nested ledger in case an older chain still carries its own singular key, though no ledger
    observed in this migration carries more than one level of nesting.
    """
    if 'superseded_ledgers' in led:
        return led
    if 'superseded_ledger' in led:
        nested = migrate_superseded_key(led['superseded_ledger'])
        migrated = dict(led)
        del migrated['superseded_ledger']
        migrated['superseded_ledgers'] = [nested]
        return migrated
    return led


def read_ledger_file(path):
    """
    Read a ledger file from disk and apply the superseded-key migration.

    The single read path every subcommand goes through, so the migration always applies
    regardless of which subcommand touches the ledger first.
    """
    with open(path, encoding='utf-8') as fh:
        led = json.load(fh)
    return migrate_superseded_key(led)


def in_scope(item, scope):
    """
    Return True if this registry item's origin is covered by `scope`.

    `scope == ['*']` is the "every origin" sentinel -- the whole-ecosystem case is a
    STATED value of scope, not the absence of one. A real, narrower scope is always an
    explicit list of origin strings (e.g. ['framework']).
    """
    return scope == ['*'] or item['origin'] in scope


def resolve_scope(reg, led, requested):
    """
    Return the effective scope list.

    Precedence, in order:

      1. `requested` -- an explicitly requested list (e.g. from a CLI --scope).
      2. `led['scope']` -- the ledger's own declared scope.
      3. A backfill from the registry's own origin set, and ONLY when the ledger's
         artifact hash already matches the registry's -- i.e. only on a legacy ledger
         written before the `scope` key existed, never across a build transition.

    The backfill path prints one line to stderr naming itself and the count. A scope is
    never silently derived from nothing; the one ledger this project has that predates
    `scope` gets its coverage stated out loud the first time it is read, not assumed.
    """
    if requested:
        return sorted(set(requested))
    if led.get('scope'):
        return led['scope']
    if led.get('artifact_sha256') == reg.get('artifact_sha256'):
        backfill = sorted({i['origin'] for i in reg['items']})
        print(f"note: ledger has no 'scope' key (pre-dates this field) -- backfilling scope "
              f"from the registry's own origin set: {len(backfill)} origins "
              f"({', '.join(backfill)})", file=sys.stderr)
        return backfill
    # Should be unreachable: load_ledger refuses before ever returning a ledger whose
    # hash does not match the registry's, so there is no live path that reaches this
    # with an unscoped, hash-mismatched ledger. Refuse rather than guess if it happens.
    sys.exit("resolve_scope: ledger/registry hash mismatch with no declared scope -- "
             "this should be unreachable; run 'uat.py rebase' to establish a scope")


def load_ledger(reg, path):
    """
    Load the ledger, refusing if the artifact under test has changed.

    Keyed on the deployed jar's SHA-256, NOT on the repository revision. The revision was the
    original key and it was wrong: it describes the checkout, and the checkout moves whenever a
    fix is written -- while the server keeps running the jar that was installed. Regenerating the
    registry after writing a fix therefore looked like "a different build" and put recorded
    results one `record` call away from being erased, without a single byte of the thing under
    test having changed.

    An artifact hash changes exactly when the tested thing changes. That is the property this
    key needs, and the reason the same discipline already governs handing a build to UAT.

    A hash change used to reset `results` to `{}` here, silently, the moment `status` or `next`
    was next run. It no longer does. A hash change now REFUSES -- the reset is a deliberate act
    performed by `uat.py rebase`, not a side effect of reading the ledger. `rebase` still
    archives the superseded ledger whole (nothing is destroyed), but which results survive into
    the new build's denominator is now a stated `scope`, not an accident of call order.
    """
    key = reg['artifact_sha256']
    if os.path.exists(path):
        led = read_ledger_file(path)
        if led.get('artifact_sha256') == key:
            return led
        old_hash = led.get('artifact_sha256') or '<none>'
        at_risk = len(led.get('results', {}))
        sys.exit(
            "REFUSING to load: the deployed artifact changed.\n"
            f"  ledger was measured against  {old_hash[:12]}\n"
            f"  registry now reports         {key[:12]}\n"
            f"  {at_risk} recorded result(s) are at risk if this reset silently.\n"
            "This is now a deliberate act, not an automatic reset. Run:\n"
            "  uat.py rebase --scope <origin> [--scope <origin> ...]\n"
            "to archive the superseded ledger and carry forward only the in-scope results."
        )
    return dict(framework_version=reg['framework_version'],
                artifact_sha256=key, results={}, scope=['*'])


def save_ledger(led, path):
    # sort_keys makes the write deterministic with respect to insertion order: two ledgers
    # holding the same content always serialize to the same bytes, regardless of which order
    # their keys (or their `results` entries) were added in -- import_verdicts.py's
    # order-independence guarantee (Phase 10, D-10-14) relies on this.
    #
    # Written to a sibling temp file and atomically replaced via os.replace, not opened
    # directly with 'w' (Codex review of PR #427): opening the real ledger path in truncate
    # mode destroys the only copy the instant open() succeeds, BEFORE json.dump ever runs --
    # a process kill or a full filesystem partway through serialization then leaves the
    # ledger empty or truncated, losing both the current results and the embedded
    # superseded_ledgers history. os.replace is atomic on both POSIX and Windows, so a reader
    # never observes a partially-written file: the ledger is either the old complete content
    # or the new complete content, never something in between.
    directory = os.path.dirname(os.path.abspath(path)) or '.'
    fd, tmp_path = tempfile.mkstemp(prefix='.ledger-', suffix='.tmp', dir=directory)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as handle:
            json.dump(led, handle, ensure_ascii=False, indent=1, sort_keys=True)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp_path, path)
    except BaseException:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise


def cmd_status(a):
    reg = load_reg(registry_path(a)); led = load_ledger(reg, ledger_path(a)); res = led['results']
    scope = resolve_scope(reg, led, None)
    all_origins = sorted({i['origin'] for i in reg['items']})
    excluded_origins = [] if scope == ['*'] else sorted(set(all_origins) - set(scope))
    scope_label = 'ALL' if scope == ['*'] else ', '.join(scope)
    tally = {}
    for it in reg['items']:
        if not in_scope(it, scope):
            continue
        st = res.get(it['id'], {}).get('status', 'pending')
        tally.setdefault(it['kind'], {}).setdefault(st, 0)
        tally[it['kind']][st] += 1
    print(f"framework {reg['framework_version']} @ jar {reg['artifact_sha256'][:12]}  "
          f"scope: {scope_label}"
          + (f"  ({len(excluded_origins)} origin(s) excluded: {', '.join(excluded_origins)})"
             if excluded_origins else ""))
    if led.get('superseded'):
        print(f"  (rebased from previous build {led['superseded'][:8]}; that build's full "
              f"result set is archived under superseded_ledgers, not discarded)")
    print(f"{'kind':<14}{'pass':>7}{'fail':>7}{'blocked':>9}{'human':>7}{'pending':>9}{'total':>7}")
    tp = tf = tb = th = tpe = 0
    for k in sorted(tally):
        d = tally[k]
        # D-10-16's named exit, distinct from 'blocked': the row was carried as far as
        # automation can take it and genuinely needs a human, not merely "not yet
        # exercised". Counted in its own column so it is never silently missing from the
        # total, the way it would be if this table only recognised pass/fail/blocked/pending.
        p, f, b, h, pe = (d.get('pass', 0), d.get('fail', 0), d.get('blocked', 0),
                           d.get('human-uat-pending', 0), d.get('pending', 0))
        tp += p; tf += f; tb += b; th += h; tpe += pe
        print(f"{k:<14}{p:>7}{f:>7}{b:>9}{h:>7}{pe:>9}{p+f+b+h+pe:>7}")
    print(f"{'TOTAL':<14}{tp:>7}{tf:>7}{tb:>9}{th:>7}{tpe:>9}{tp+tf+tb+th+tpe:>7}")
    if tf:
        print(f"\n{tf} FAILED:")
        for it in reg['items']:
            if not in_scope(it, scope):
                continue
            r = res.get(it['id'])
            if r and r['status'] == 'fail':
                print(f"  {it['id']}  {it['origin']}/{it['cls']}  {r.get('note','')[:80]}")


def group_listener_items_by_event(listener_items):
    """
    Group listener items by their event key, in first-seen order.

    An event fires every handler registered for it at once -- keying by `event` (falling back
    to `trigger` when absent) is what lets `cmd_next` render one trigger per section instead of
    one per handler row.
    """
    seen, order = {}, []
    for item in listener_items:
        key = item.get('event') or item['trigger']
        if key not in seen:
            seen[key] = []
            order.append(key)
        seen[key].append(item)
    return seen, order


def select_events_within_row_budget(order, seen, remaining_size, other_count):
    """
    Greedily select whole events, never splitting one across the row budget.

    Selects from `order[:remaining_size]`, stopping before the next event would push the
    expanded execution-row total (including `other_count`) past `MAX_BATCH_SIZE`. Counting
    events as --size's own units (each "however many handlers it reaches" counts as one) does
    not by itself bound the raw execution-row total UAT-MATRIX-SCHEMA.md fixes at 60 -- an
    event with multiple handlers can expand well past --size's own count (Codex review of PR
    #427).
    """
    chosen_events = []
    expanded_row_budget = MAX_BATCH_SIZE - other_count
    for key in order[:remaining_size]:
        group_size = len(seen[key])
        if group_size > expanded_row_budget:
            break
        chosen_events.append(key)
        expanded_row_budget -= group_size
    return chosen_events


def cmd_next(a):
    reg = load_reg(registry_path(a)); led = load_ledger(reg, ledger_path(a)); res = led['results']
    scope = resolve_scope(reg, led, None)
    pend = [i for i in reg['items']
            if in_scope(i, scope)
            and res.get(i['id'], {}).get('status', 'pending') == 'pending']
    if a.kind: pend = [i for i in pend if i['kind'] == a.kind]
    if a.origin: pend = [i for i in pend if i['origin'] in set(a.origin)]
    pend.sort(key=lambda i: (i['origin'] != 'framework', i['origin'], i['kind'], i['cls'], i.get('member') or ''))

    # An event fires every handler registered for it at once: many listener items sit on far
    # fewer distinct events, and one player join can run several handlers. Batching those
    # one-per-item would make the executor either repeat the same trigger N times or record N
    # rows off a single observation with nothing telling it that is the right thing to do. So
    # for listeners the unit of a batch is the trigger, not the registry row: --size counts
    # events, and every handler that event reaches is listed under it.
    #
    # Grouping applies whenever `pend` contains ANY listener items -- not only when `a.kind`
    # was explicitly narrowed to 'listener'. D-10-15's own batching plan dispatches whole
    # modules (filtered by --origin, not --kind), so the common real batch is a MIX of kinds;
    # gating grouping on an exact --kind='listener' filter silently ungroups listener rows in
    # exactly that common case, letting handlers for one event be split across batches.
    other_items = [i for i in pend if i['kind'] != 'listener']
    listener_items = [i for i in pend if i['kind'] == 'listener']
    seen, order = group_listener_items_by_event(listener_items)

    # --size counts UNITS: one non-listener item, or one listener event (however many
    # handlers it reaches) -- never a raw listener row. Non-listener items are filled first,
    # then remaining budget goes to whole events, so an event is never split across the size
    # boundary the way per-row slicing would risk.
    chosen_other = other_items[:a.size]
    remaining_size = max(0, a.size - len(chosen_other))
    chosen_events = select_events_within_row_budget(order, seen, remaining_size, len(chosen_other))
    batch = chosen_other + [i for k in chosen_events for i in seen[k]]
    groups = [(k, seen[k]) for k in chosen_events] if chosen_events else None
    remaining_units = (len(other_items) - len(chosen_other)) + (len(order) - len(chosen_events))
    if not batch:
        print('nothing pending for that filter'); return
    # Scoped the same way cmd_status computes its TOTAL row -- an unscoped `reg['total']`
    # here would silently count items this ledger's own `scope` declares out of scope and will
    # never record a result for.
    scoped_items = [i for i in reg['items'] if in_scope(i, scope)]
    done = sum(1 for i in scoped_items if res.get(i['id'], {}).get('status', 'pending') != 'pending')
    print(f"# UAT batch - framework {reg['framework_version']} @ jar {reg['artifact_sha256'][:12]}")
    if groups and chosen_other:
        print(f"\nProgress overall: {done}/{len(scoped_items)} recorded. This batch: {len(chosen_other)} "
              f"item(s) plus {len(batch) - len(chosen_other)} handler(s) across {len(groups)} event(s); "
              f"{remaining_units} unit(s) still pending for this filter.\n")
    elif groups:
        print(f"\nProgress overall: {done}/{len(scoped_items)} recorded. This batch: {len(batch)} handlers "
              f"across {len(groups)} events; {remaining_units} events still pending after it.\n")
    else:
        print(f"\nProgress overall: {done}/{len(scoped_items)} recorded. This batch: {len(batch)} items; "
              f"{remaining_units} still pending for this filter.\n")
    print("Every item must be triggered for real on the server. Record the ACTUAL response text, "
          "not the word 'expected'. An item whose branches were not exercised is not done.\n")
    print("ADJUDICATION RULES - read before recording anything:\n")
    print("  1. `Unknown or incomplete command` does NOT by itself mean the command is missing.")
    print("     CommandManager registers the Bukkit-level permission on every command, so Paper")
    print("     filters the command out of the command tree of any sender lacking the permission")
    print("     and answers unknown-command; the plugin's onCommand is never reached. Getting")
    print("     this backwards has produced false failures before. To prove a command is")
    print("     registered, run it as an OP (or from console for a console-capable command).")
    print("  2. Record `blocked`, never a guess, when the item cannot be exercised (class absent,")
    print("     prerequisite state missing, world/config not set up). A guess is worse than a gap.")
    print("  3. Quote the server's own words in the note. `as expected` is not a measurement.\n")
    print("Record each result with:")
    print("  python3 tools/uat/uat.py record <ID> <pass|fail|blocked> \"<what actually happened>\"\n")
    if groups:
        print("THE LISTENER SECTION BELOW IS GROUPED BY EVENT" + (" (this batch also carries "
              "non-listener items, printed first)" if chosen_other else "")
              + ". One trigger per section adjudicates every handler listed under it -- do NOT "
              "fire the same event once per row.\n")
        print("A handler you cannot see the effect of is still measurable. Two checks apply to")
        print("every handler, and both are observable from a single trigger:")
        print("  a. REGISTERED - the handler's class must appear in that event's registered-listener")
        print("     list. Dump it (Paper's listener dump, or reflect over HandlerList) BEFORE firing.")
        print("     A class absent from the list is usually a fail with a definite cause -- but")
        print("     CHECK WHETHER REGISTRATION IS CONDITIONAL FIRST. Some listeners register only")
        print("     when a capability, a config flag, or a live UltiPanel connection is present;")
        print("     an absent handler whose registration precondition is not met on this server is")
        print("     BLOCKED, not failed -- it was not verified, and it is also not broken. Read the")
        print("     registration site before recording a fail.")
        print("     An anonymous or inner listener registers under a name like PluginManager$1,")
        print("     which will not string-match the registry's class name. Match on the enclosing")
        print("     class, and say in the note which inner class you matched.")
        print("  b. NO THROW - after firing, scan the server log for a stack trace naming that class.")
        print("Record each handler its own row. Where a handler has a visible effect, quote it;")
        print("where it has none, say so and record what (a) and (b) showed.\n")

    # Non-listener items always render as flat entries, whether or not this batch also
    # carries a grouped listener section -- a mixed per-module batch (the common real case,
    # since D-10-15's own plan dispatches whole modules rather than filtering by --kind)
    # must show both, not one at the silent expense of the other.
    for it in chosen_other:
        print(f"## {it['id']}  [{it['kind']}]  {it['origin']} / {it['cls']}" + (f".{it['member']}" if it.get('member') else ''))
        print(f"  file    {it['file']}")
        print(f"  trigger {it['trigger']}")
        print(f"  who     {it['who']}")
        print(f"  expect  {it['expect']}")
        for b in it.get('branches', []):
            print(f"  branch  {b}")
        print()

    if groups:
        for _, its in groups:
            print(f"### TRIGGER: {its[0]['trigger']}   ({len(its)} handler"
                  f"{'s' if len(its) > 1 else ''} "
                  f"{'fire' if len(its) > 1 else 'fires'} on this one event)\n")
            for it in its:
                print(f"  {it['id']}  {it['origin']} / {it['cls']}.{it.get('member')}")
                print(f"      file   {it['file']}")
                for b in it.get('branches', []):
                    print(f"      branch {b}")
            print()


def cmd_record(a):
    reg = load_reg(registry_path(a)); ledger_file = ledger_path(a); led = load_ledger(reg, ledger_file)
    if not led.get('scope'):
        # Backfill and persist, so a ledger written from here on always carries a scope --
        # resolve_scope prints the same one-line note load_ledger's callers already rely on.
        led['scope'] = resolve_scope(reg, led, None)
    # Scoped the same way import_verdicts.py's own known-ids check is (Codex review of PR
    # #427): after a narrowed `rebase --scope framework`, recording a result for an excluded
    # module's id would write a hidden stale result into the ledger, one that silently counts
    # against a later, wider scope even though this ledger was never meant to track it.
    items_by_id = {i['id']: i for i in reg['items']}
    item = items_by_id.get(a.id)
    if item is None or not in_scope(item, led['scope']):
        sys.exit(f'unknown id {a.id} -- not present in the registry, or not in this ledger\'s current scope')
    led['results'][a.id] = dict(status=a.status, note=a.note,
                                at=datetime.datetime.now().astimezone().isoformat(timespec='seconds'))
    save_ledger(led, ledger_file)
    print(f'{a.id} = {a.status}')


def cmd_rebase(a):
    """
    Run the `rebase` subcommand, the only path past a hash change.

    Loads the on-disk ledger and the new registry, refuses if their hashes already match
    (rebase performs a BUILD transition; using it on an unchanged build would launder a
    same-build reset through the one command meant to make resets deliberate), then builds a
    new ledger carrying forward only the results whose registry item is in the requested
    scope. Every carried result is stamped `carried_from` with the superseded hash, and the
    whole superseded ledger is appended -- with its own prior history flattened in, per
    D-10-14 -- to `superseded_ledgers`; nothing dropped from this build's denominator is
    destroyed.
    """
    reg = load_reg(registry_path(a))
    ledger_file = ledger_path(a)
    if not os.path.exists(ledger_file):
        sys.exit(f'{ledger_file} missing - nothing to rebase from')
    old = read_ledger_file(ledger_file)
    old_hash = old.get('artifact_sha256')
    new_hash = reg['artifact_sha256']
    if old_hash == new_hash:
        sys.exit(
            f"REFUSING: registry and ledger already share the same artifact hash "
            f"({(new_hash or '<none>')[:12]}). `rebase` only performs a build transition; "
            f"running it against an unchanged build would launder a same-build reset through "
            f"the one command this project built to make resets deliberate. If you want to "
            f"widen or narrow the CURRENT ledger's scope without a build change, edit its "
            f"'scope' key directly."
        )

    scope = sorted(set(a.scope))
    if '*' in scope and scope != ['*']:
        # in_scope only recognizes the wildcard when scope equals EXACTLY ['*'] -- a mixed
        # invocation like `--scope '*' --scope framework` stored the literal two-element list
        # verbatim, and since the string '*' never equals any real origin, in_scope's
        # `item['origin'] in scope` fallback then matched ONLY 'framework', silently narrowing
        # an otherwise-valid "everything" request down to one origin with no error raised
        # (Codex review of PR #427). Normalize rather than reject: `*` unions with everything
        # by definition, so any scope containing it collapses to `['*']`.
        scope = ['*']
    # Refuse a typo'd/unknown origin outright rather than silently carrying forward 0 results
    # indistinguishable from a deliberate "start with nothing" choice.
    known_origins = {i['origin'] for i in reg['items']}
    unknown = [s for s in scope if s != '*' and s not in known_origins]
    if unknown:
        sys.exit(f"REFUSING: --scope value(s) {unknown} match no origin in the registry "
                  f"(known origins: {sorted(known_origins)}). Check for a typo.")
    items_by_id = {i['id']: i for i in reg['items']}
    old_results = old.get('results', {})

    carried = {}
    for rid, r in old_results.items():
        item = items_by_id.get(rid)
        if item is not None and in_scope(item, scope):
            nr = dict(r)
            nr['carried_from'] = old_hash
            carried[rid] = nr

    # "lost" answers: of the old-ledger ids the NEW registry still recognises as in-scope,
    # how many failed to make it into `carried`? By construction that is always 0 today
    # (both sets are built from the same lookup) -- the check exists to catch a future
    # change to this function that breaks that invariant, not to catch registry drift.
    expected_in_scope_ids = {rid for rid in old_results
                              if items_by_id.get(rid) is not None
                              and in_scope(items_by_id[rid], scope)}
    lost = sorted(expected_in_scope_ids - set(carried))

    # Ids the old ledger recorded that the new registry does not recognise AT ALL cannot be
    # classified in/out of scope -- their origin is unknown here. Flagged, not silently
    # dropped into "out of scope".
    vanished = sorted(rid for rid in old_results if rid not in items_by_id)

    dropped = len(old_results) - len(carried) - len(vanished)

    tally = Counter(r['status'] for r in carried.values())
    print(f"rebase: {(old_hash or '<none>')[:12]} -> {new_hash[:12]}  scope: {', '.join(scope)}")
    print(f"  carried {len(carried)} in-scope result(s): "
          f"{tally.get('pass',0)} pass / {tally.get('fail',0)} fail / "
          f"{tally.get('blocked',0)} blocked / {tally.get('pending',0)} pending")
    print(f"  dropped {dropped} out-of-scope result(s) -- archived whole under "
          f"superseded_ledgers, not destroyed")
    print(f"  lost {len(lost)} in-scope id(s)" + (f": {lost}" if lost else ""))
    if vanished:
        print(f"  NOTE: {len(vanished)} old-ledger id(s) no longer exist in the registry at "
              f"all (origin unknown, not classifiable in/out of scope): {vanished}")

    old_history = old.get('superseded_ledgers', [])
    old_without_history = dict(old)
    old_without_history.pop('superseded_ledgers', None)
    old_without_history.pop('superseded_ledger', None)

    new_led = dict(
        framework_version=reg['framework_version'],
        artifact_sha256=new_hash,
        built_from_revision_label=reg.get('checkout_revision_label'),
        superseded=old_hash,
        superseded_ledgers=list(old_history) + [old_without_history],
        scope=scope,
        results=carried,
    )

    if a.dry_run:
        print("  (dry run -- nothing written)")
        return

    save_ledger(new_led, ledger_file)
    print(f'wrote {ledger_file}')


def cmd_reset(a):
    """
    Run the `reset` subcommand, starting a fresh cycle.

    Unlike the old behaviour, this is no longer a silent, unconditional wipe: it is the one
    remaining destructive code path in this file, and `cmd_rebase` was built this same phase
    specifically so a build transition never silently destroys recorded evidence. `reset` now
    gets the same shape: refuse unless `--force` is passed when there is anything to lose, and
    archive the discarded ledger under `superseded_ledgers` rather than dropping it -- nothing
    this tool clears is ever unrecoverable from the resulting file alone.
    """
    reg = load_reg(registry_path(a))
    old_path = ledger_path(a)
    old = read_ledger_file(old_path) if os.path.exists(old_path) else None
    if old and old.get('results') and not a.force:
        sys.exit(
            f"REFUSING: {len(old['results'])} recorded result(s) would be discarded with no "
            f"archive. Re-run with --force, or use 'uat.py rebase --scope ...' instead."
        )
    new_led = dict(framework_version=reg['framework_version'],
                   artifact_sha256=reg['artifact_sha256'], scope=['*'], results={})
    if old:
        old_history = old.get('superseded_ledgers', [])
        old_without_history = dict(old)
        old_without_history.pop('superseded_ledgers', None)
        old_without_history.pop('superseded_ledger', None)
        new_led['superseded_ledgers'] = list(old_history) + [old_without_history]
    save_ledger(new_led, old_path)
    print('ledger cleared' + (' (previous ledger archived under superseded_ledgers)' if old else ''))


def build_parser():
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument('--registry', default=None,
                         help=f'registry.json path; falls back to ${REGISTRY_ENV} (for a dry '
                              'run on a copy; never point this at production during a real cycle)')
    common.add_argument('--ledger', default=None,
                         help=f'ledger.json path; falls back to ${LEDGER_ENV} (for a dry run on '
                              'a copy; never point this at production during a real cycle)')
    common.add_argument('--runs', default=None,
                         help=f'evidence-run root; falls back to ${RUNS_ENV} (reserved -- not '
                              'yet read by any subcommand)')

    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest='c', required=True)
    sub.add_parser('status', parents=[common]).set_defaults(f=cmd_status)
    n = sub.add_parser('next', parents=[common]); n.add_argument('--size', type=positive_capped_int, default=25)
    n.add_argument('--kind'); n.add_argument('--origin', action='append'); n.add_argument('--group-by-event', action='store_true'); n.set_defaults(f=cmd_next)
    r = sub.add_parser('record', parents=[common]); r.add_argument('id'); r.add_argument(
        'status', choices=['pass', 'fail', 'blocked', 'human-uat-pending'])
    r.add_argument('note'); r.set_defaults(f=cmd_record)
    rb = sub.add_parser('rebase', parents=[common])
    rb.add_argument('--scope', action='append', required=True,
                    help='an origin value (e.g. framework, UltiEssentials) to carry forward; '
                         'repeatable')
    rb.add_argument('--dry-run', action='store_true', dest='dry_run',
                    help='print the transition summary and write nothing')
    rb.set_defaults(f=cmd_rebase)
    rs = sub.add_parser('reset', parents=[common])
    rs.add_argument('--force', action='store_true',
                     help='required if the current ledger has any recorded results; without it, '
                          "reset refuses rather than silently discarding them")
    rs.set_defaults(f=cmd_reset)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    args.f(args)
    return 0


if __name__ == '__main__':
    sys.exit(main())
