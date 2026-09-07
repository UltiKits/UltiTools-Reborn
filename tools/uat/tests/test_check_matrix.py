"""
Tests for tools/uat/check_matrix.py (Phase 10 plan 10-04, Task 1).

check_matrix.py is the per-module completeness gate (D-10-09/D-10-10): it sorts every surface
row into `unasserted`, `entity-covered` or `uncovered-entity`, reports orphan assertion ids and
uncovered GUI-excluded classes, and exits non-zero exactly when any of `unasserted`,
`uncovered-entity`, the orphan list or the GUI list is non-empty. `entity-covered` is
informational only and never drives a non-zero exit on its own.
"""
import json
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import check_matrix  # noqa: E402  (path must be adjusted before this import)


def write_surface(tmp_path, items, config_entities=None, filename='surface.json'):
    path = tmp_path / filename
    document = {
        'schema_version': 1,
        'items': items,
        'config_entities': config_entities or [],
    }
    path.write_text(json.dumps(document), encoding='utf-8')
    return str(path)


def write_assertions(tmp_path, assertions=None, gui_excluded_classes=None, filename='assertions.yaml'):
    path = tmp_path / filename
    document = {
        'schema_version': 1,
        'assertions': assertions or [],
    }
    if gui_excluded_classes is not None:
        document['gui_excluded_classes'] = gui_excluded_classes
    path.write_text(yaml.safe_dump(document, sort_keys=False), encoding='utf-8')
    return str(path)


def run(surface, assertions, extra_args=None, module='TestModule'):
    args = ['--surface', surface, '--assertions', assertions, '--module', module]
    if extra_args:
        args += extra_args
    return check_matrix.main(args)


def run_json(surface, assertions, module='TestModule'):
    result = run(surface, assertions, extra_args=['--json'], module=module)
    return result


class TestEmptyAndTrivialMatrices:

    def test_empty_matrix_passes(self, tmp_path):
        surface = write_surface(tmp_path, [])
        assertions = write_assertions(tmp_path, [])

        result = run(surface, assertions)

        assert result == 0

    def test_matrix_with_no_assertions_reports_every_row_unasserted_and_fails(self, tmp_path, capsys):
        surface = write_surface(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'kind': 'command', 'trigger': '/x reload'},
            {'id': 'LIS-bbbbbbbb', 'kind': 'listener', 'trigger': 'PlayerJoinEvent'},
        ])
        assertions = write_assertions(tmp_path, [])

        result = run(surface, assertions)
        out = capsys.readouterr().out

        assert result != 0
        assert 'COM-aaaaaaaa' in out
        assert 'LIS-bbbbbbbb' in out

    def test_matrix_with_no_assertions_reports_every_config_entity_uncovered(self, tmp_path, capsys):
        entity = {'id': 'CFE-11111111', 'class': 'com.example.Cfg', 'file': 'config/a.yml', 'entry_count': 1}
        row = {'id': 'CFG-aaaaaaaa', 'kind': 'config', 'config_entity': 'com.example.Cfg',
               'config_file': 'config/a.yml', 'path': 'k1'}
        surface = write_surface(tmp_path, [row], config_entities=[entity])
        assertions = write_assertions(tmp_path, [])

        result = run_json(surface, assertions)
        out = json.loads(capsys.readouterr().out)

        assert result != 0
        assert out['unasserted'] == []
        assert out['entity_covered'] == []
        assert any('com.example.Cfg' in entry for entry in out['uncovered_entity'])


class TestConfigGranularity:

    def test_config_fields_under_an_asserted_entity_land_in_entity_covered_and_pass(self, tmp_path):
        entity = {'id': 'CFE-11111111', 'class': 'com.example.Cfg', 'file': 'config/a.yml', 'entry_count': 2}
        rows = [
            {'id': 'CFG-aaaaaaaa', 'kind': 'config', 'config_entity': 'com.example.Cfg',
             'config_file': 'config/a.yml', 'path': 'k1'},
            {'id': 'CFG-bbbbbbbb', 'kind': 'config', 'config_entity': 'com.example.Cfg',
             'config_file': 'config/a.yml', 'path': 'k2'},
        ]
        surface = write_surface(tmp_path, rows, config_entities=[entity])
        assertions = write_assertions(tmp_path, [
            {'id': 'CFE-11111111', 'truth': 'config/a.yml loads and both keys apply', 'layer': 'protocol'},
        ])

        result = run_json(surface, assertions)

        assert result == 0

    def test_config_fields_under_an_asserted_entity_are_named_in_entity_covered(self, tmp_path, capsys):
        entity = {'id': 'CFE-11111111', 'class': 'com.example.Cfg', 'file': 'config/a.yml', 'entry_count': 2}
        rows = [
            {'id': 'CFG-aaaaaaaa', 'kind': 'config', 'config_entity': 'com.example.Cfg',
             'config_file': 'config/a.yml', 'path': 'k1'},
            {'id': 'CFG-bbbbbbbb', 'kind': 'config', 'config_entity': 'com.example.Cfg',
             'config_file': 'config/a.yml', 'path': 'k2'},
        ]
        surface = write_surface(tmp_path, rows, config_entities=[entity])
        assertions = write_assertions(tmp_path, [
            {'id': 'CFE-11111111', 'truth': 'config/a.yml loads and both keys apply', 'layer': 'protocol'},
        ])

        run_json(surface, assertions)
        out = json.loads(capsys.readouterr().out)

        assert out['uncovered_entity'] == []
        assert out['unasserted'] == []
        assert sorted(out['entity_covered']) == ['CFG-aaaaaaaa', 'CFG-bbbbbbbb']
        assert out['orphan_assertions'] == []

    def test_a_field_never_needs_its_own_assertion(self, tmp_path):
        """78 config fields under one asserted entity: zero per-field assertions required."""
        entity = {'id': 'CFE-22222222', 'class': 'com.example.Big', 'file': 'config/big.yml', 'entry_count': 78}
        rows = [
            {'id': 'CFG-{:08d}'.format(i), 'kind': 'config', 'config_entity': 'com.example.Big',
             'config_file': 'config/big.yml', 'path': 'k{}'.format(i)}
            for i in range(78)
        ]
        surface = write_surface(tmp_path, rows, config_entities=[entity])
        assertions = write_assertions(tmp_path, [
            {'id': 'CFE-22222222', 'truth': 'config/big.yml loads with all documented defaults', 'layer': 'protocol'},
        ])

        assert run(surface, assertions) == 0

    def test_entity_with_no_assertion_fails_through_uncovered_entity_not_unasserted(self, tmp_path, capsys):
        entity = {'id': 'CFE-11111111', 'class': 'com.example.Cfg', 'file': 'config/a.yml', 'entry_count': 1}
        row = {'id': 'CFG-aaaaaaaa', 'kind': 'config', 'config_entity': 'com.example.Cfg',
               'config_file': 'config/a.yml', 'path': 'k1'}
        surface = write_surface(tmp_path, [row], config_entities=[entity])
        assertions = write_assertions(tmp_path, [])

        result = run_json(surface, assertions)
        out = json.loads(capsys.readouterr().out)

        assert result != 0
        assert out['unasserted'] == []
        assert any('com.example.Cfg' in entry and 'config/a.yml' in entry for entry in out['uncovered_entity'])


class TestRepeatabilityFixture:
    """
    Exercise the two-sided repeatability guarantee D-10-10 requires.

    A config field added under an asserted entity stays green through entity-covered; the
    same field under an unasserted entity fails through uncovered-entity. Neither depends on
    assertion granularity changing.
    """

    def test_adding_a_field_under_an_asserted_entity_keeps_the_matrix_green(self, tmp_path):
        entity = {'id': 'CFE-33333333', 'class': 'com.example.Grown', 'file': 'config/grown.yml', 'entry_count': 1}
        baseline_row = {'id': 'CFG-11111111', 'kind': 'config', 'config_entity': 'com.example.Grown',
                         'config_file': 'config/grown.yml', 'path': 'k1'}
        assertions = write_assertions(tmp_path, [
            {'id': 'CFE-33333333', 'truth': 'config/grown.yml loads with k1 and k2 at their defaults',
             'layer': 'protocol'},
        ])

        baseline_surface = write_surface(tmp_path, [baseline_row], config_entities=[entity],
                                          filename='baseline.json')
        assert run(baseline_surface, assertions) == 0

        grown_entity = dict(entity, entry_count=2)
        new_row = {'id': 'CFG-22222222', 'kind': 'config', 'config_entity': 'com.example.Grown',
                   'config_file': 'config/grown.yml', 'path': 'k2'}
        grown_surface = write_surface(tmp_path, [baseline_row, new_row], config_entities=[grown_entity],
                                       filename='grown.json')
        assert run(grown_surface, assertions) == 0

    def test_adding_a_field_under_a_new_unasserted_entity_fails(self, tmp_path, capsys):
        new_entity = {'id': 'CFE-44444444', 'class': 'com.example.NewEntity', 'file': 'config/new.yml',
                       'entry_count': 1}
        new_row = {'id': 'CFG-33333333', 'kind': 'config', 'config_entity': 'com.example.NewEntity',
                   'config_file': 'config/new.yml', 'path': 'k1'}
        surface = write_surface(tmp_path, [new_row], config_entities=[new_entity])
        assertions = write_assertions(tmp_path, [])

        result = run_json(surface, assertions)
        out = json.loads(capsys.readouterr().out)

        assert result != 0
        assert any('com.example.NewEntity' in entry for entry in out['uncovered_entity'])


class TestOrphanAssertions:

    def test_assertion_id_matching_neither_items_nor_config_entities_is_an_orphan_and_fails(self, tmp_path, capsys):
        surface = write_surface(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'kind': 'command', 'trigger': '/x reload'},
        ])
        assertions = write_assertions(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'truth': 'chat line X', 'layer': 'protocol'},
            {'id': 'COM-ffffffff', 'truth': 'a row that does not exist', 'layer': 'protocol'},
        ])

        result = run_json(surface, assertions)
        out = json.loads(capsys.readouterr().out)

        assert result != 0
        assert out['orphan_assertions'] == ['COM-ffffffff']

    def test_assertion_id_matching_a_config_entities_id_is_not_an_orphan(self, tmp_path, capsys):
        entity = {'id': 'CFE-11111111', 'class': 'com.example.Cfg', 'file': 'config/a.yml', 'entry_count': 1}
        row = {'id': 'CFG-aaaaaaaa', 'kind': 'config', 'config_entity': 'com.example.Cfg',
               'config_file': 'config/a.yml', 'path': 'k1'}
        surface = write_surface(tmp_path, [row], config_entities=[entity])
        assertions = write_assertions(tmp_path, [
            {'id': 'CFE-11111111', 'truth': 'config/a.yml loads with k1 at its default', 'layer': 'protocol'},
        ])

        result = run_json(surface, assertions)
        out = json.loads(capsys.readouterr().out)

        assert result == 0
        assert out['orphan_assertions'] == []


class TestGuiExclusionBackReference:

    def test_gui_excluded_class_named_by_no_assertion_is_reported_and_fails(self, tmp_path, capsys):
        surface = write_surface(tmp_path, [])
        assertions = write_assertions(tmp_path, [], gui_excluded_classes=[
            'com.ultikits.plugins.login.gui.LoginGUIPage',
        ])

        result = run_json(surface, assertions)
        out = json.loads(capsys.readouterr().out)

        assert result != 0
        assert out['uncovered_gui_classes'] == ['com.ultikits.plugins.login.gui.LoginGUIPage']

    def test_gui_excluded_class_named_by_covers_classes_is_not_reported(self, tmp_path, capsys):
        surface = write_surface(tmp_path, [
            {'id': 'HUM-aaaaaaaa', 'kind': 'gui', 'trigger': 'open /login gui'},
        ])
        assertions = write_assertions(tmp_path, [
            {'id': 'HUM-aaaaaaaa', 'truth': 'the login GUI opens with two buttons', 'layer': 'human',
             'covers_classes': ['com.ultikits.plugins.login.gui.LoginGUIPage']},
        ], gui_excluded_classes=['com.ultikits.plugins.login.gui.LoginGUIPage'])

        result = run_json(surface, assertions)
        out = json.loads(capsys.readouterr().out)

        assert result == 0
        assert out['uncovered_gui_classes'] == []


class TestSchemaValidation:

    def test_assertion_missing_id_is_a_schema_error_before_any_list_is_printed(self, tmp_path, capsys):
        surface = write_surface(tmp_path, [])
        assertions = write_assertions(tmp_path, [
            {'truth': 'something happens', 'layer': 'protocol'},
        ])

        result = run(surface, assertions)
        err = capsys.readouterr().err

        assert result != 0
        assert 'id' in err.lower()

    def test_assertion_missing_truth_is_a_schema_error(self, tmp_path, capsys):
        surface = write_surface(tmp_path, [])
        assertions = write_assertions(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'layer': 'protocol'},
        ])

        result = run(surface, assertions)
        err = capsys.readouterr().err

        assert result != 0
        assert 'truth' in err.lower()

    def test_assertion_with_empty_string_truth_is_also_a_schema_error(self, tmp_path, capsys):
        # truth: "" used to pass the required-field check (only None counted as missing),
        # then removed the row from `unasserted` entirely -- a module could pass this
        # completeness gate with every row asserted by a truth that states nothing an
        # observer could check (Codex review of PR #427).
        row = {'id': 'COM-aaaaaaaa', 'kind': 'command', 'trigger': '/x reload'}
        surface = write_surface(tmp_path, [row])
        assertions = write_assertions(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'truth': '', 'layer': 'protocol'},
        ])

        result = run(surface, assertions)
        err = capsys.readouterr().err

        assert result != 0
        assert 'truth' in err.lower()

    def test_assertion_with_whitespace_only_truth_is_also_a_schema_error(self, tmp_path, capsys):
        surface = write_surface(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'kind': 'command', 'trigger': '/x reload'},
        ])
        assertions = write_assertions(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'truth': '   ', 'layer': 'protocol'},
        ])

        result = run(surface, assertions)
        err = capsys.readouterr().err

        assert result != 0
        assert 'truth' in err.lower()

    def test_assertion_with_a_non_string_truth_is_also_a_schema_error(self, tmp_path, capsys):
        # YAML parses an unquoted `truth: yes`/`truth: true` as a Python bool. The prior fix
        # only rejected a blank/whitespace-only truth when it was ALREADY a string
        # (isinstance(..., str) gated the whitespace check), so a bool sailed straight
        # through as "present" with a valid id/layer -- removing the row from `unasserted`
        # and never tripping the exit-triggering weak-truth check either, despite carrying no
        # observable statement an executor could act on (Codex review of PR #427).
        surface = write_surface(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'kind': 'command', 'trigger': '/x reload'},
        ])
        assertions = write_assertions(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'truth': True, 'layer': 'protocol'},
        ])

        result = run(surface, assertions)
        err = capsys.readouterr().err

        assert result != 0
        assert 'truth' in err.lower()

    def test_assertion_missing_layer_is_a_schema_error(self, tmp_path, capsys):
        surface = write_surface(tmp_path, [])
        assertions = write_assertions(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'truth': 'something happens'},
        ])

        result = run(surface, assertions)
        err = capsys.readouterr().err

        assert result != 0
        assert 'layer' in err.lower()

    def test_assertion_with_unknown_layer_value_is_rejected_naming_the_value(self, tmp_path, capsys):
        surface = write_surface(tmp_path, [])
        assertions = write_assertions(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'truth': 'something happens', 'layer': 'orbital'},
        ])

        result = run(surface, assertions)
        err = capsys.readouterr().err

        assert result != 0
        assert 'orbital' in err

    def test_config_row_referencing_a_missing_entity_is_a_schema_error(self, tmp_path, capsys):
        row = {'id': 'CFG-aaaaaaaa', 'kind': 'config', 'config_entity': 'com.example.Ghost',
               'config_file': 'config/ghost.yml', 'path': 'k1'}
        surface = write_surface(tmp_path, [row], config_entities=[])
        assertions = write_assertions(tmp_path, [])

        result = run(surface, assertions)
        err = capsys.readouterr().err

        assert result != 0
        assert 'com.example.Ghost' in err

    def test_schema_errors_are_reported_before_any_of_the_five_sections(self, tmp_path, capsys):
        surface = write_surface(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'kind': 'command', 'trigger': '/x reload'},
        ])
        assertions = write_assertions(tmp_path, [
            {'id': 'COM-bbbbbbbb', 'truth': 'x', 'layer': 'not-a-real-layer'},
        ])

        run(surface, assertions)
        out = capsys.readouterr().out

        assert 'unasserted' not in out
        assert 'entity-covered' not in out

    def test_duplicate_assertion_id_is_a_schema_error_not_a_silent_last_write_wins(self, tmp_path, capsys):
        # A copy-paste error in assertions.yaml repeating an id must never be silently
        # collapsed to whichever entry happens to come last -- that would discard real
        # coverage with no diagnostic, which is exactly the gap this checker exists to catch.
        surface = write_surface(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'kind': 'command', 'trigger': '/x reload'},
        ])
        assertions = write_assertions(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'truth': 'first truth', 'layer': 'protocol'},
            {'id': 'COM-aaaaaaaa', 'truth': 'second truth, overwrote the first', 'layer': 'server'},
        ])

        result = run(surface, assertions)
        err = capsys.readouterr().err

        assert result != 0
        assert 'duplicate' in err.lower()
        assert 'COM-aaaaaaaa' in err


class TestByteWiseIdComparison:

    def test_id_differing_only_in_case_is_reported_as_absent(self, tmp_path, capsys):
        surface = write_surface(tmp_path, [
            {'id': 'COM-AAAAAAAA', 'kind': 'command', 'trigger': '/x reload'},
        ])
        assertions = write_assertions(tmp_path, [
            {'id': 'com-aaaaaaaa', 'truth': 'chat line X', 'layer': 'protocol'},
        ])

        result = run_json(surface, assertions)
        out = json.loads(capsys.readouterr().out)

        assert result != 0
        assert out['unasserted'] == ['COM-AAAAAAAA']
        assert out['orphan_assertions'] == ['com-aaaaaaaa']

    def test_id_differing_only_by_surrounding_whitespace_is_reported_as_absent(self, tmp_path, capsys):
        surface = write_surface(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'kind': 'command', 'trigger': '/x reload'},
        ])
        assertions = write_assertions(tmp_path, [
            {'id': ' COM-aaaaaaaa', 'truth': 'chat line X', 'layer': 'protocol'},
        ])

        result = run_json(surface, assertions)
        out = json.loads(capsys.readouterr().out)

        assert result != 0
        assert out['unasserted'] == ['COM-aaaaaaaa']


class TestJsonOutput:

    def test_json_output_carries_the_five_keys_sorted(self, tmp_path):
        surface = write_surface(tmp_path, [])
        assertions = write_assertions(tmp_path, [])

        result = run(surface, assertions, extra_args=['--json'])
        assert result == 0

    def test_json_output_parses_and_has_expected_keys(self, tmp_path, capsys):
        surface = write_surface(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'kind': 'command', 'trigger': '/x reload'},
        ])
        assertions = write_assertions(tmp_path, [])

        run_json(surface, assertions)
        out = json.loads(capsys.readouterr().out)

        assert set(out.keys()) == {
            'unasserted', 'entity_covered', 'uncovered_entity', 'orphan_assertions', 'uncovered_gui_classes',
        }
        assert out['unasserted'] == ['COM-aaaaaaaa']

    def test_json_output_is_sorted_keys_serialization(self, tmp_path):
        surface = write_surface(tmp_path, [])
        assertions = write_assertions(tmp_path, [])

        # Invokes this repo's own check_matrix.py with a fixed argument list, never
        # external or attacker-supplied input.
        import subprocess  # nosec B404
        proc = subprocess.run(
            [sys.executable, str(Path(__file__).resolve().parents[1] / 'check_matrix.py'),
             '--surface', surface, '--assertions', assertions, '--json'],
            capture_output=True, text=True)  # nosec B603 -- fixed args, never external input
        parsed = json.loads(proc.stdout)
        reserialized = json.dumps(parsed, sort_keys=True, indent=2)
        assert proc.stdout.strip() == reserialized.strip()


class TestUnassertedKindCoverage:

    def test_every_non_config_kind_without_an_assertion_lands_in_unasserted(self, tmp_path, capsys):
        kinds = ['command', 'help', 'listener', 'scheduled', 'gui', 'persistence', 'placeholder',
                 'behaviour', 'conditional']
        rows = [{'id': 'ROW-{:08d}'.format(i), 'kind': kind, 'trigger': 't{}'.format(i)}
                for i, kind in enumerate(kinds)]
        surface = write_surface(tmp_path, rows)
        assertions = write_assertions(tmp_path, [])

        result = run_json(surface, assertions)
        out = json.loads(capsys.readouterr().out)

        assert result != 0
        assert sorted(out['unasserted']) == sorted(row['id'] for row in rows)
