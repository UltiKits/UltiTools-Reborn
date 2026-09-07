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
    Load `surface.json` and return its (items, config_entities).

    Both default to empty lists when the document omits them. `config_entities` is required,
    not optional: a `config` row is asserted through its owning entity's id, never its own
    (D-10-10), so a renderer that drops `config_entities` can never resolve that join and
    would report every config field as unasserted even when its entity has a real assertion.
    """
    with open(path, encoding='utf-8') as handle:
        document = json.load(handle)
    return document.get('items') or [], document.get('config_entities') or []


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


def describe_steps(item):
    """
    Describe the "Steps" a real-machine session must exercise for one surface row.

    Only `command`/`help` rows carry a `trigger` string. Every other kind's own fields
    describe what to exercise instead -- reading `trigger` unconditionally would silently
    render an empty Steps cell for a listener, scheduled task, persistence entity, config
    field, or conditional gate, which is exactly the kind of row a real-machine executor
    needs the most explicit guidance for, since there is no command to simply retype.
    """
    kind = item.get('kind')
    if kind in ('command', 'help'):
        return item.get('trigger', '')
    if kind == 'listener':
        event = item.get('event', '')
        priority = item.get('handler_priority')
        return 'event {} (priority {})'.format(event, priority) if priority else 'event {}'.format(event)
    if kind == 'scheduled':
        if item.get('one_shot'):
            return 'runs once, {}s after enable'.format(item.get('delay_seconds', 0))
        return 'runs every {}s (first fire after {}s)'.format(
            item.get('period_seconds', 0), item.get('delay_seconds', 0))
    if kind == 'persistence':
        return 'table {}'.format(item.get('table', ''))
    if kind == 'config':
        return 'config key {} in {}'.format(item.get('path', ''), item.get('config_file', ''))
    if kind == 'conditional':
        gate = item.get('gate') or {}
        return 'gate {}={} (negate={})'.format(gate.get('path', ''), gate.get('value', ''), gate.get('negate', False))
    return item.get('trigger', '')


def resolve_assertion(item, assertions_by_id, entity_by_class):
    """
    Resolve the assertion covering one surface row.

    Every kind but `config` is asserted by its own id. A `config` row is asserted through
    its owning `@ConfigEntity`'s id instead (D-10-10's accepted granularity -- a config
    field never carries its own assertion), so looking the field's own id up directly, as
    every other kind does, would always report it unasserted even when its entity has a
    real, matching assertion.
    """
    if item.get('kind') == 'config':
        entity = entity_by_class.get(item.get('config_entity'))
        lookup_id = entity.get('id') if entity else item.get('id')
    else:
        lookup_id = item.get('id')
    return assertions_by_id.get(lookup_id)


def render_artifact_table(jar, version, byte_size, sha256, commit):
    return '\n'.join([
        '| Path | Version | Bytes | SHA-256 | Commit |',
        '|---|---|---|---|---|',
        '| {} | {} | {} | {} | {} |'.format(
            escape_cell(jar), escape_cell(version), escape_cell(byte_size),
            escape_cell(sha256), escape_cell(commit)),
    ])


def render_rows(items, assertions_by_id, config_entities=None, ids_filter=None):
    """
    Render the asserted-rows table plus the "Rows with no assertion yet" section.

    Every surface row lands in exactly one of the two: `Expected`/`Layer` come from the
    matching assertion's `truth`/`layer` (resolved via `resolve_assertion`, which joins a
    `config` row through its owning entity rather than its own id); `Steps` is derived per
    kind by `describe_steps`, since only `command`/`help` rows carry a `trigger` string.
    """
    entity_by_class = {entity.get('class'): entity for entity in (config_entities or [])}

    if ids_filter is not None:
        items = [item for item in items if item.get('id') in ids_filter]

    asserted = []
    unasserted = []
    for item in items:
        assertion = resolve_assertion(item, assertions_by_id, entity_by_class)
        if assertion is None:
            unasserted.append(item)
        else:
            asserted.append((item, assertion))

    lines = []
    if asserted:
        lines.append('| ID | Type | Steps | Expected | Layer |')
        lines.append('|---|---|---|---|---|')
        for item, assertion in sorted(asserted, key=lambda pair: pair[0]['id']):
            lines.append('| {} | {} | {} | {} | {} |'.format(
                escape_cell(item.get('id', '')), escape_cell(item.get('kind', '')),
                escape_cell(describe_steps(item)),
                escape_cell(assertion.get('truth', '')), escape_cell(assertion.get('layer', ''))))
    else:
        lines.append('No assertions are defined for this surface yet.')

    lines.append('')
    lines.append('## Rows with no assertion yet')
    lines.append('')
    if unasserted:
        lines.append('| ID | Type | Steps |')
        lines.append('|---|---|---|')
        for item in sorted(unasserted, key=lambda row: row['id']):
            lines.append('| {} | {} | {} |'.format(
                escape_cell(item.get('id', '')), escape_cell(item.get('kind', '')),
                escape_cell(describe_steps(item))))
    else:
        lines.append('Every surface row has an assertion.')

    return '\n'.join(lines)


def build_document(items, assertions_by_id, jar, version, byte_size, sha256, commit,
                    config_entities=None, ids_filter=None):
    return '\n'.join([
        '# UAT Handover',
        '',
        '## Artifact',
        '',
        render_artifact_table(jar, version, byte_size, sha256, commit),
        '',
        '## Rows',
        '',
        render_rows(items, assertions_by_id, config_entities, ids_filter),
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
    parser.add_argument('--ids', default=None, help='comma-separated row ids to include; default is all')
    parser.add_argument('--output', default=None, help='write the document here instead of stdout')
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    items, config_entities = load_surface(args.surface)
    assertions_by_id = load_assertions(args.assertions)
    ids_filter = set(args.ids.split(',')) if args.ids else None

    document = build_document(
        items, assertions_by_id, args.jar, args.version, args.byte_size, args.sha256, args.commit,
        config_entities, ids_filter)

    if args.output:
        with open(args.output, 'w', encoding='utf-8', newline='\n') as handle:
            handle.write(document)
    else:
        sys.stdout.write(document)
    return 0


if __name__ == '__main__':
    sys.exit(main())
