#!/usr/bin/env python3
"""Render a Laojun-ready UAT handover document.

Combines a module's generated `surface.json`, its hand-written `assertions.yaml`, and the
release artifact five-tuple (path, semantic version, byte size, SHA-256, source commit) into
one Markdown document in the shape Phase 13's handover documents already established: an
artifact table, then a table of `ID / Type / Steps / Expected / Layer` for every surface row
that has a matching assertion, and a separate "Rows with no assertion yet" section for every
row that does not (Phase 10, D-10-12). A surface row is never silently omitted just because it
has no assertion yet -- that omission is exactly the "new, unasserted entries" gap the checker
this document feeds is meant to surface.

Usage:
    render_handover.py --surface uat/surface.json --assertions uat/assertions.yaml \\
        --jar path/to/Module.jar --version 1.2.3 --bytes 12345 \\
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
    """Load `surface.json` and return its `items` list (empty if absent)."""
    with open(path, encoding='utf-8') as handle:
        document = json.load(handle)
    return document.get('items', [])


def load_assertions(path):
    """Load `assertions.yaml` and return a dict keyed by assertion id.

    An assertions file with no `assertions` key, or an empty list, is a legitimate "nothing
    asserted yet" state -- it yields an empty dict, not an error.
    """
    if yaml is None:
        raise SystemExit(
            'render_handover.py requires PyYAML to read assertions.yaml; '
            'see tools/uat/README.md for the chosen reader.')
    with open(path, encoding='utf-8') as handle:
        document = yaml.safe_load(handle) or {}
    assertions = document.get('assertions') or []
    return {assertion['id']: assertion for assertion in assertions}


def render_artifact_table(jar, version, byte_size, sha256, commit):
    return '\n'.join([
        '| Path | Version | Bytes | SHA-256 | Commit |',
        '|---|---|---|---|---|',
        '| {} | {} | {} | {} | {} |'.format(jar, version, byte_size, sha256, commit),
    ])


def render_rows(items, assertions_by_id, ids_filter=None):
    """Render the asserted-rows table plus the "Rows with no assertion yet" section.

    Every surface row lands in exactly one of the two: `Expected`/`Layer` come from the
    matching assertion's `truth`/`layer`; `Steps` always comes from the surface row's own
    `trigger`, regardless of which section the row lands in.
    """
    if ids_filter is not None:
        items = [item for item in items if item.get('id') in ids_filter]

    asserted = []
    unasserted = []
    for item in items:
        assertion = assertions_by_id.get(item.get('id'))
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
                item.get('id', ''), item.get('kind', ''), item.get('trigger', ''),
                assertion.get('truth', ''), assertion.get('layer', '')))
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
                item.get('id', ''), item.get('kind', ''), item.get('trigger', '')))
    else:
        lines.append('Every surface row has an assertion.')

    return '\n'.join(lines)


def build_document(items, assertions_by_id, jar, version, byte_size, sha256, commit, ids_filter=None):
    return '\n'.join([
        '# UAT Handover',
        '',
        '## Artifact',
        '',
        render_artifact_table(jar, version, byte_size, sha256, commit),
        '',
        '## Rows',
        '',
        render_rows(items, assertions_by_id, ids_filter),
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
    items = load_surface(args.surface)
    assertions_by_id = load_assertions(args.assertions)
    ids_filter = set(args.ids.split(',')) if args.ids else None

    document = build_document(
        items, assertions_by_id, args.jar, args.version, args.byte_size, args.sha256, args.commit, ids_filter)

    if args.output:
        with open(args.output, 'w', encoding='utf-8', newline='\n') as handle:
            handle.write(document)
    else:
        sys.stdout.write(document)
    return 0


if __name__ == '__main__':
    sys.exit(main())
