#!/usr/bin/env python3
r"""
Render a Laojun-ready UAT handover document.

Combines a module's generated `surface.json`, its hand-written `assertions.yaml`, and the
release artifact five-tuple (path, semantic version, byte size, SHA-256, source commit) into
one Markdown document in the shape Phase 13's handover documents already established: an
artifact table, then a table of `ID / Type / Steps / Expected / Layer` for every surface row
that has a matching assertion, and a separate "Rows with no assertion yet" section for every
row that does not (Phase 10, D-10-12). A surface row is never silently omitted just because it
has no assertion yet -- that omission is exactly the "new, unasserted entries" gap the checker
this document feeds is meant to surface.

Usage:
    render_handover.py --surface uat/surface.json --assertions uat/assertions.yaml \
        --jar path/to/Module.jar --version 1.2.3 --bytes 12345 \
        --sha256 <hex> --commit <sha> [--ids ID1,ID2,...] [--output uat/handover.md]

With no `--output`, the document is written to stdout.
"""
import argparse
import json
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - environment-dependent; see tools/uat/README.md
    yaml = None


def load_surface(path):
    """
    Load `surface.json` and return its (items, config_entities, switches).

    `items`/`config_entities` default to empty lists when the document omits them.
    `config_entities` is required, not optional: a `config` row is asserted through its
    owning entity's id, never its own (D-10-10), so a renderer that drops `config_entities`
    can never resolve that join and would report every config field as unasserted even when
    its entity has a real assertion.

    `switches` carries `registers_commands`/`registers_listeners`/`registers_config` verbatim
    (each `None` when the document omits it, e.g. no `@UltiToolsModule` entry class was found
    among the scanned classes -- `SurfaceAssembler` only writes these keys when one was).
    `SurfaceAssembler`'s rows are deliberately retained even when a switch is `false` -- the
    suppressed/manual registration behavior is still a fact worth verifying -- but a renderer
    that discards the switch itself gives the executor ordinary trigger steps with no
    indication that automatic registration is off, turning an expected absence into a false
    failure (Codex review of PR #427).
    """
    with open(path, encoding='utf-8') as handle:
        document = json.load(handle)
    switches = {
        'registers_commands': document.get('registers_commands'),
        'registers_listeners': document.get('registers_listeners'),
        'registers_config': document.get('registers_config'),
    }
    return document.get('items') or [], document.get('config_entities') or [], switches


def load_assertions(path):
    """
    Load `assertions.yaml` and return a dict keyed by assertion id.

    An assertions file with no `assertions` key, or an empty list, is a legitimate "nothing
    asserted yet" state -- it yields an empty dict, not an error.

    A repeated `id` is a fatal error, not a silent last-write-wins collapse: this document is
    what a real-machine session reads to decide what to do for a given row, and rendering the
    wrong one of two differently-worded assertions under the same id -- with the other simply
    vanishing -- is worse than refusing to render at all.
    """
    if yaml is None:
        raise SystemExit(
            'render_handover.py requires PyYAML to read assertions.yaml; '
            'see tools/uat/README.md for the chosen reader.')
    with open(path, encoding='utf-8') as handle:
        document = yaml.safe_load(handle) or {}
    assertions = document.get('assertions') or []
    by_id = {}
    for assertion in assertions:
        assertion_id = assertion['id']
        if assertion_id in by_id:
            raise SystemExit(
                'render_handover.py: duplicate assertion id {!r} in {} -- refusing to render, '
                'since one of the two entries would silently vanish.'.format(assertion_id, path))
        by_id[assertion_id] = assertion
    return by_id


def escape_cell(value):
    """
    Escape a value for safe embedding in one Markdown table cell.

    A literal `|` would otherwise be read as a column delimiter, silently shifting every
    later column in the row; a literal newline would silently split one logical row across
    several rendered table lines. Both are real risks here: `truth` is hand-written prose
    that can legitimately contain either (an expected response like `enabled | disabled`,
    or a multi-line expected log excerpt).
    """
    text = '' if value is None else str(value)
    text = text.replace('|', '\\|')
    text = text.replace('\r\n', ' ').replace('\n', ' ').replace('\r', ' ')
    return text


def describe_steps(item, switches=None):
    """
    Describe the "Steps" a real-machine session must exercise for one surface row.

    Only `command`/`help` rows carry a `trigger` string. Every other kind's own fields
    describe what to exercise instead -- reading `trigger` unconditionally would silently
    render an empty Steps cell for a listener, scheduled task, persistence entity, or
    conditional gate, which is exactly the kind of row a real-machine executor needs the
    most explicit guidance for, since there is no command to simply retype. `config` items
    are never passed here directly -- see `describe_entity_steps` for the entity-level row
    D-10-10's granularity actually executes.

    A command/help/listener/scheduled row belonging to a gated class carries its own `gate`
    (attached by `SurfaceAssembler`); that gate is appended here rather than only rendered on
    the row's separate standalone `conditional` entry. Batches are meant to be self-contained
    and may not include that separate row alongside this one, so an executor working from
    this row alone could otherwise test while the class is disabled and record a false
    failure. A `listener` row additionally states when it is `manual_register`, since Bukkit
    never fires that handler through the normal automatic registration path an executor would
    otherwise expect to observe.

    `switches` (from `load_surface`, `None`-valued keys treated as "not applicable") carries
    the document-level `registers_commands`/`registers_listeners`/`registers_config` a module's
    own `@UltiToolsModule(cmdExecutor=/eventListener=/config=)` declares -- distinct from a
    per-row `manual_register`, this is a MODULE-WIDE suppression of the whole registration
    class. Surfaced the same way: a note on every affected row, never used to suppress the row
    itself (Codex review of PR #427).
    """
    kind = item.get('kind')
    if kind in ('command', 'help'):
        steps = item.get('trigger', '')
        if item.get('manual_register'):
            # CommandManager.register's autowire-and-register path is skipped entirely for
            # an @CmdExecutor(manualRegister = true) class -- the module itself must call
            # CommandManager.register(...) somewhere in its own startup path. Without this
            # note the handover looks identical to an automatically registered command, and
            # an executor who only checks the normal registration path records a false
            # failure for a command that fires through the module's own manual call instead
            # (Codex review of PR #427).
            steps += ' [manually registered -- verify the module\'s own registration path, not the automatic one]'
        if switches and switches.get('registers_commands') is False:
            steps += (
                ' [this module declares @UltiToolsModule(cmdExecutor=false) -- automatic '
                'command registration is disabled for the whole module; verify its own '
                'registration path]')
    elif kind == 'listener':
        event = item.get('event', '')
        priority = item.get('handler_priority')
        steps = 'event {} (priority {})'.format(event, priority) if priority else 'event {}'.format(event)
        if item.get('ignore_cancelled'):
            # Bukkit's own dispatch skips this handler entirely for an already-cancelled
            # event, before the method is ever called -- without this note, triggering a
            # cancelled event and observing no effect looks identical to a broken handler
            # (Codex review of PR #427).
            steps += ' [ignoreCancelled=true -- does NOT fire for an already-cancelled event]'
        if item.get('manual_register'):
            steps += ' [manually registered -- verify the module\'s own registration path, not the automatic one]'
        if switches and switches.get('registers_listeners') is False:
            steps += (
                ' [this module declares @UltiToolsModule(eventListener=false) -- automatic '
                'listener registration is disabled for the whole module; verify its own '
                'registration path]')
    elif kind == 'scheduled':
        if item.get('one_shot'):
            steps = 'runs once, {}s after enable'.format(item.get('delay_seconds', 0))
        else:
            steps = 'runs every {}s (first fire after {}s)'.format(
                item.get('period_seconds', 0), item.get('delay_seconds', 0))
    elif kind == 'persistence':
        steps = 'table {}'.format(item.get('table', ''))
    elif kind == 'conditional':
        return describe_gate(item.get('gate') or {})
    else:
        steps = item.get('trigger', '')

    if kind in ('command', 'help', 'listener', 'scheduled') and item.get('gate'):
        steps = '{} [{}]'.format(steps, describe_gate(item['gate']))
    return steps


def describe_gate(gate):
    """
    Describe a `@ConditionalOnConfig` gate: which file, which key, and the required value.

    The annotation's own attribute names are file-oriented -- `value()` is the config FILE
    path, `path()` is the dot/slash-separated KEY inside it -- the reverse of what their
    names suggest next to each other. Swapping them (`gate {path}={value}`) rendered the
    file path as if it were the value to assign and the key as if it named the setting,
    which would tell an executor to configure the wrong thing entirely. `negate` also needs
    stating explicitly: the class registers when the key equals `not negate`, not always
    `true`.
    """
    required_value = 'false' if gate.get('negate') else 'true'
    return 'config key {} in {} must be {}'.format(
        gate.get('path', ''), gate.get('value', ''), required_value)


def describe_entity_steps(entity, switches=None):
    """
    Describe the "Steps" for one config-entity execution row (D-10-10's granularity).

    A config entity is exercised as a whole -- load the file, confirm every documented
    default, flip a representative key -- never per field, so the row names the file and
    field count rather than any single key.

    `switches` mirrors `describe_steps`'s own -- a module declaring
    `@UltiToolsModule(config=false)` disables automatic config registration entirely.
    """
    steps = 'config entity {} in {} ({} field(s))'.format(
        entity.get('class', ''), entity.get('file', ''), entity.get('entry_count', 0))
    if switches and switches.get('registers_config') is False:
        steps += (
            ' [this module declares @UltiToolsModule(config=false) -- automatic config '
            'registration is disabled for the whole module; verify its own registration path]')
    return steps


def with_preconditions(steps, assertion):
    """
    Append an assertion's `preconditions` to its rendered Steps text.

    Without this, a config key, permission, credential, or second-player requirement the
    assertion documents is silently dropped from this supposedly self-contained handover,
    and an executor exercising the row under the wrong setup can record a false failure it
    has no way to know was avoidable.
    """
    preconditions = assertion.get('preconditions') or []
    if not preconditions:
        return steps
    return '{} [preconditions: {}]'.format(steps, ', '.join(str(p) for p in preconditions))


def render_artifact_table(jar, version, byte_size, sha256, commit):
    return '\n'.join([
        '| Path | Version | Bytes | SHA-256 | Commit |',
        '|---|---|---|---|---|',
        '| {} | {} | {} | {} | {} |'.format(
            escape_cell(jar), escape_cell(version), escape_cell(byte_size),
            escape_cell(sha256), escape_cell(commit)),
    ])


def render_rows(items, assertions_by_id, config_entities=None, ids_filter=None, switches=None):
    """
    Render the asserted-rows table plus the "Rows with no assertion yet" section.

    Renders one execution row per `config_entities` entry, not per `config` surface item
    (D-10-10: a config entity is asserted and executed as a whole, keyed by its own id --
    never per field). A `config`-kind item never produces a row of its own here; it exists
    in `surface.json` purely so `check_matrix.py` can prove completeness field by field.
    An entity with zero fields (a legitimate shape `ConfigRowScanner` now supports) still
    gets its row, since it has no `config` item to have been rendered from at all otherwise.

    Every other kind's row is asserted by its own id, with `Steps` derived per kind by
    `describe_steps` (only `command`/`help` rows carry a `trigger` string). Every rendered
    Expected cell also carries the assertion's `preconditions`, when declared, so this
    document stays genuinely self-contained rather than silently dropping a documented
    setup requirement (a config key, permission, credential, or second player).
    """
    if ids_filter is not None:
        items = [item for item in items if item.get('id') in ids_filter]
        config_entities = [entity for entity in (config_entities or []) if entity.get('id') in ids_filter]

    asserted = []
    unasserted = []
    for item in items:
        if item.get('kind') == 'config':
            continue  # rendered once per entity below, never once per field
        assertion = assertions_by_id.get(item.get('id'))
        row_id, row_kind, steps = item.get('id', ''), item.get('kind', ''), describe_steps(item, switches)
        if assertion is None:
            unasserted.append((row_id, row_kind, steps))
        else:
            asserted.append((row_id, row_kind, with_preconditions(steps, assertion), assertion))

    for entity in (config_entities or []):
        assertion = assertions_by_id.get(entity.get('id'))
        row_id, row_kind, steps = entity.get('id', ''), 'config_entity', describe_entity_steps(entity, switches)
        if assertion is None:
            unasserted.append((row_id, row_kind, steps))
        else:
            asserted.append((row_id, row_kind, with_preconditions(steps, assertion), assertion))

    lines = []
    if asserted:
        lines.append('| ID | Type | Steps | Expected | Layer |')
        lines.append('|---|---|---|---|---|')
        for row_id, row_kind, steps, assertion in sorted(asserted, key=lambda row: row[0]):
            lines.append('| {} | {} | {} | {} | {} |'.format(
                escape_cell(row_id), escape_cell(row_kind), escape_cell(steps),
                escape_cell(assertion.get('truth', '')), escape_cell(assertion.get('layer', ''))))
    else:
        lines.append('No assertions are defined for this surface yet.')

    lines.append('')
    lines.append('## Rows with no assertion yet')
    lines.append('')
    if unasserted:
        lines.append('| ID | Type | Steps |')
        lines.append('|---|---|---|')
        for row_id, row_kind, steps in sorted(unasserted, key=lambda row: row[0]):
            lines.append('| {} | {} | {} |'.format(
                escape_cell(row_id), escape_cell(row_kind), escape_cell(steps)))
    else:
        lines.append('Every surface row has an assertion.')

    return '\n'.join(lines)


def build_document(items, assertions_by_id, jar, version, byte_size, sha256, commit,
                    config_entities=None, ids_filter=None, switches=None):
    return '\n'.join([
        '# UAT Handover',
        '',
        '## Artifact',
        '',
        render_artifact_table(jar, version, byte_size, sha256, commit),
        '',
        '## Rows',
        '',
        render_rows(items, assertions_by_id, config_entities, ids_filter, switches),
    ]) + '\n'


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--surface', required=True, help='path to the module\'s generated surface.json')
    parser.add_argument('--assertions', required=True, help='path to the module\'s hand-written assertions.yaml')
    parser.add_argument('--jar', required=True, help='the artifact\'s path')
    parser.add_argument('--version', required=True, help='the artifact\'s semantic version')
    parser.add_argument('--bytes', dest='byte_size', required=True, help='the artifact\'s byte size')
    parser.add_argument('--sha256', required=True, help='the artifact\'s SHA-256, from a clean build')
    parser.add_argument('--commit', required=True, help='the source commit the artifact was built from')
    parser.add_argument('--ids', default=None,
                         help='comma-separated row ids to include; default is all. Every id must '
                              'exist in the surface -- an unknown id is rejected, not dropped')
    parser.add_argument('--output', default=None, help='write the document here instead of stdout')
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    items, config_entities, switches = load_surface(args.surface)
    assertions_by_id = load_assertions(args.assertions)
    ids_filter = set(args.ids.split(',')) if args.ids else None

    if ids_filter is not None:
        # --ids selects the exact batch being dispatched -- a typo or a stale row id used to
        # be silently dropped, still producing a "successful" document that could end up with
        # NO execution rows at all, quietly losing whatever the dispatcher meant to send
        # (Codex review of PR #427). Fail closed instead: validate every requested id against
        # both items and config_entities before rendering anything.
        known_ids = {item.get('id') for item in items} | {entity.get('id') for entity in (config_entities or [])}
        unknown = sorted(ids_filter - known_ids)
        if unknown:
            sys.exit(f'--ids named {len(unknown)} id(s) not present in this surface: {unknown}. '
                      f'Check for a typo or a stale id from a previous build.')

    document = build_document(
        items, assertions_by_id, args.jar, args.version, args.byte_size, args.sha256, args.commit,
        config_entities, ids_filter, switches)

    if args.output:
        with open(args.output, 'w', encoding='utf-8', newline='\n') as handle:
            handle.write(document)
    else:
        sys.stdout.write(document)
    return 0


if __name__ == '__main__':
    sys.exit(main())
