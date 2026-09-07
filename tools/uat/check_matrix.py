#!/usr/bin/env python3
"""Check a module's UAT matrix for completeness (Phase 10, D-10-09/D-10-10).

Given a generated `surface.json` and a hand-written `assertions.yaml`, this is the objective
per-module gate every one of the seventeen module-side repositories runs before opening its
pull request: it names exactly which functions still have no stated truth.

It sorts every surface row into one of three named buckets:

    unasserted        -- a command/help/listener/scheduled/gui/persistence/placeholder/
                          behaviour/conditional row (anything but `config`) carrying no
                          assertion. This IS criterion 5's "new, unasserted entries".
    entity-covered     -- a `config` row whose owning `@ConfigEntity` (surfaced as the row's
                          `config_entity` field, joined against the document-level
                          `config_entities` array) has at least one assertion. Informational
                          only -- never a reason to fail on its own. A config field never
                          needs its own assertion (D-10-10's accepted granularity).
    uncovered-entity   -- a `config_entities` entry with no assertion at all. A real gap,
                          exactly like `unasserted`.

It also reports two more findings that are not buckets of surface rows:

    orphan assertions        -- assertion ids matching neither an `items` id nor a
                                 `config_entities` id.
    uncovered GUI classes    -- `gui_excluded_classes` (Phase 9's coverage-gate carve-out,
                                 D-09) entries not named by any assertion's `covers_classes`.

Exit code is non-zero exactly when `unasserted`, `uncovered-entity`, the orphan list, or the
GUI list is non-empty. A non-empty `entity-covered` is never on its own a failure.

Usage:
    check_matrix.py --surface uat/surface.json --assertions uat/assertions.yaml
                     [--module Name] [--json]
"""
import argparse
import json
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - environment-dependent; see tools/uat/README.md
    yaml = None

VALID_LAYERS = ('protocol', 'java-client', 'pixel', 'server', 'human')
CONFIG_KIND = 'config'


def load_surface(path):
    """Load `surface.json`, returning (items, config_entities). Both default to empty lists
    when the document omits them -- an empty-but-valid surface is legitimate, not an error.
    """
    with open(path, encoding='utf-8') as handle:
        document = json.load(handle)
    items = document.get('items') or []
    config_entities = document.get('config_entities') or []
    return items, config_entities


def load_assertions(path):
    """Load `assertions.yaml`, returning (assertions, gui_excluded_classes). An assertions
    file with no `assertions` key, or an empty list, is a legitimate "nothing asserted yet"
    state -- an empty list, not an error.
    """
    if yaml is None:
        raise SystemExit(
            'check_matrix.py requires PyYAML to read assertions.yaml; '
            'see tools/uat/README.md for the chosen reader.')
    with open(path, encoding='utf-8') as handle:
        document = yaml.safe_load(handle) or {}
    assertions = document.get('assertions') or []
    gui_excluded_classes = document.get('gui_excluded_classes') or []
    return assertions, gui_excluded_classes


def validate_assertions_schema(assertions):
    """Return a list of schema-error strings for `assertions`; empty means well-formed.

    `id`, `truth` and `layer` are required on every entry (missing or `None` is a violation;
    an empty string is not -- an empty truth is a *weak* truth, reported separately, never a
    schema error). `layer` must be one of the five known values.

    A repeated `id` is also a schema error, not merely a duplicate key. `assertions.yaml` is
    hand-written, and every downstream consumer (this checker's own bucket computation, plus
    `render_handover.py`) builds a `{id: assertion}` dict from the list -- a copy-paste error
    that repeats an id would otherwise be silently collapsed to whichever entry happens to
    come last, with the discarded entry's coverage vanishing with no diagnostic. That is
    exactly the silent-gap failure mode this checker exists to catch, one layer up.
    """
    errors = []
    seen_ids = {}
    for index, assertion in enumerate(assertions):
        if not isinstance(assertion, dict):
            errors.append('assertion[{}]: not a mapping'.format(index))
            continue
        label = 'assertion {}'.format(assertion['id']) if assertion.get('id') else 'assertion[{}]'.format(index)
        missing = [key for key in ('id', 'truth', 'layer') if assertion.get(key) is None]
        if missing:
            errors.append('{}: missing required field(s): {}'.format(label, ', '.join(missing)))
            continue
        layer = assertion.get('layer')
        if layer not in VALID_LAYERS:
            errors.append('{}: layer {!r} is not one of {}'.format(label, layer, VALID_LAYERS))
        assertion_id = assertion['id']
        if assertion_id in seen_ids:
            errors.append('{}: duplicate id, first declared at assertion[{}]'.format(
                label, seen_ids[assertion_id]))
        else:
            seen_ids[assertion_id] = index
    return errors


def validate_surface_schema(items, config_entities):
    """Return (errors, entities_by_class). A `config` row whose `config_entity` names an
    entity absent from `config_entities` is a schema error naming the entity -- never a row
    silently bucketed or dropped.
    """
    errors = []
    entities_by_class = {}
    for entity in config_entities:
        entities_by_class[entity.get('class')] = entity

    for item in items:
        if item.get('kind') != CONFIG_KIND:
            continue
        entity_class = item.get('config_entity')
        if entity_class not in entities_by_class:
            errors.append(
                'config row {} names config_entity {!r}, absent from config_entities'.format(
                    item.get('id'), entity_class))
    return errors, entities_by_class


def find_weak_truths(items, assertions_by_id):
    """An assertion whose truth is empty, or which merely repeats the row's own `trigger`
    string, names no observable outcome and makes the row untestable. Reported as a listed
    finding -- never silently accepted, but never a reason to fail on its own.
    """
    items_by_id = {item.get('id'): item for item in items}
    weak = []
    for assertion_id, assertion in assertions_by_id.items():
        truth = assertion.get('truth') or ''
        if truth == '':
            weak.append(assertion_id)
            continue
        row = items_by_id.get(assertion_id)
        if row is not None and truth == row.get('trigger'):
            weak.append(assertion_id)
    return sorted(weak)


def compute_buckets(items, entities_by_class, assertions_by_id, config_entities):
    """Sort every surface row and every config entity into the three named buckets.

    Join is by the row's own `config_entity` field against `config_entities`, never by
    re-reading module source (D-10-04's own rationale for the compiled-class extractor).
    """
    unasserted = []
    entity_covered = []
    uncovered_entity = []

    for item in items:
        kind = item.get('kind')
        row_id = item.get('id')
        if kind == CONFIG_KIND:
            entity_class = item.get('config_entity')
            entity = entities_by_class.get(entity_class)
            if entity is None:
                continue  # already reported as a schema error; not a row to bucket
            if entity.get('id') in assertions_by_id:
                entity_covered.append(row_id)
            # else: no per-field assertion is ever demanded -- the entity itself is
            # reported once, below, via uncovered_entity.
        else:
            if row_id not in assertions_by_id:
                unasserted.append(row_id)

    for entity in config_entities:
        if entity.get('id') not in assertions_by_id:
            uncovered_entity.append('{} ({})'.format(entity.get('class'), entity.get('file')))

    return sorted(unasserted), sorted(entity_covered), sorted(uncovered_entity)


def compute_orphan_assertions(assertions_by_id, items, config_entities):
    """An assertion id matching neither a surface item id nor a config_entities id."""
    known_ids = {item.get('id') for item in items} | {entity.get('id') for entity in config_entities}
    return sorted(assertion_id for assertion_id in assertions_by_id if assertion_id not in known_ids)


def compute_uncovered_gui_classes(gui_excluded_classes, assertions):
    """A `gui_excluded_classes` entry not named by any assertion's `covers_classes`."""
    covered = set()
    for assertion in assertions:
        covered.update(assertion.get('covers_classes') or [])
    return sorted(cls for cls in gui_excluded_classes if cls not in covered)


def print_text_report(module, unasserted, entity_covered, uncovered_entity,
                       orphan_assertions, uncovered_gui_classes, weak_truths):
    label = ' ({})'.format(module) if module else ''
    print('UAT matrix check{}'.format(label))
    print()
    print('unasserted ({}):'.format(len(unasserted)))
    for row_id in unasserted:
        print('  {}'.format(row_id))
    print()
    print('entity-covered ({}):'.format(len(entity_covered)))
    for row_id in entity_covered:
        print('  {}'.format(row_id))
    print()
    print('uncovered-entity ({}):'.format(len(uncovered_entity)))
    for entry in uncovered_entity:
        print('  {}'.format(entry))
    print()
    print('orphan-assertions ({}):'.format(len(orphan_assertions)))
    for assertion_id in orphan_assertions:
        print('  {}'.format(assertion_id))
    print()
    print('uncovered-gui-classes ({}):'.format(len(uncovered_gui_classes)))
    for cls in uncovered_gui_classes:
        print('  {}'.format(cls))
    if weak_truths:
        print()
        print('weak-truths ({}):'.format(len(weak_truths)))
        for assertion_id in weak_truths:
            print('  {}'.format(assertion_id))


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--surface', required=True, help='path to the module\'s generated surface.json')
    parser.add_argument('--assertions', required=True, help='path to the module\'s hand-written assertions.yaml')
    parser.add_argument('--module', default=None, help='module name, for message context only')
    parser.add_argument('--json', action='store_true', dest='as_json',
                         help='emit the five findings as sorted JSON instead of a text report')
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)

    items, config_entities = load_surface(args.surface)
    assertions, gui_excluded_classes = load_assertions(args.assertions)

    assertion_errors = validate_assertions_schema(assertions)
    surface_errors, entities_by_class = validate_surface_schema(items, config_entities)
    schema_errors = assertion_errors + surface_errors
    if schema_errors:
        for error in schema_errors:
            print(error, file=sys.stderr)
        print('{} schema error(s) found; matrix not evaluated.'.format(len(schema_errors)), file=sys.stderr)
        return 2

    assertions_by_id = {assertion['id']: assertion for assertion in assertions}

    unasserted, entity_covered, uncovered_entity = compute_buckets(
        items, entities_by_class, assertions_by_id, config_entities)
    orphan_assertions = compute_orphan_assertions(assertions_by_id, items, config_entities)
    uncovered_gui_classes = compute_uncovered_gui_classes(gui_excluded_classes, assertions)
    weak_truths = find_weak_truths(items, assertions_by_id)

    if args.as_json:
        result = {
            'unasserted': unasserted,
            'entity_covered': entity_covered,
            'uncovered_entity': uncovered_entity,
            'orphan_assertions': orphan_assertions,
            'uncovered_gui_classes': uncovered_gui_classes,
        }
        print(json.dumps(result, sort_keys=True, indent=2))
    else:
        print_text_report(args.module, unasserted, entity_covered, uncovered_entity,
                           orphan_assertions, uncovered_gui_classes, weak_truths)

    exit_reasons = []
    if unasserted:
        exit_reasons.append('unasserted')
    if uncovered_entity:
        exit_reasons.append('uncovered-entity')
    if orphan_assertions:
        exit_reasons.append('orphan-assertions')
    if uncovered_gui_classes:
        exit_reasons.append('uncovered-gui-classes')

    if exit_reasons:
        print('FAIL: non-empty {}'.format(', '.join(exit_reasons)), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
