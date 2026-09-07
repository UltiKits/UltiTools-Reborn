"""Tests for tools/uat/render_handover.py (Phase 10 plan 10-01, Task 1)."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import render_handover  # noqa: E402  (path must be adjusted before this import)


def write_surface(tmp_path, items, config_entities=None):
    path = tmp_path / 'surface.json'
    document = {'schema_version': 1, 'items': items}
    if config_entities is not None:
        document['config_entities'] = config_entities
    path.write_text(json.dumps(document), encoding='utf-8')
    return str(path)


def write_assertions(tmp_path, assertions, filename='assertions.yaml'):
    lines = ['schema_version: 1', 'assertions:']
    for assertion in assertions:
        lines.append('  - id: {}'.format(assertion['id']))
        lines.append('    truth: "{}"'.format(assertion['truth']))
        lines.append('    layer: {}'.format(assertion['layer']))
    path = tmp_path / filename
    path.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    return str(path)


def write_empty_assertions(tmp_path):
    path = tmp_path / 'assertions.yaml'
    path.write_text('schema_version: 1\nassertions: []\n', encoding='utf-8')
    return str(path)


ARTIFACT_ARGS = ['--jar', 'X.jar', '--version', '1.0.0', '--bytes', '123',
                  '--sha256', 'deadbeefdeadbeef', '--commit', 'abc123def']


def test_renders_table_for_asserted_rows(tmp_path):
    surface = write_surface(tmp_path, [
        {'id': 'COM-11111111', 'kind': 'command', 'trigger': '/x reload'},
    ])
    assertions = write_assertions(tmp_path, [
        {'id': 'COM-11111111', 'truth': 'chat line X', 'layer': 'protocol'},
    ])
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert '| ID | Type | Steps | Expected | Layer |' in document
    assert '| COM-11111111 | command | /x reload | chat line X | protocol |' in document
    assert 'Every surface row has an assertion.' in document


def test_unasserted_rows_go_to_their_own_section_not_omitted(tmp_path):
    surface = write_surface(tmp_path, [
        {'id': 'COM-22222222', 'kind': 'command', 'trigger': '/y go'},
    ])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert 'No assertions are defined for this surface yet.' in document
    assert '## Rows with no assertion yet' in document
    assert 'COM-22222222' in document
    assert '| ID | Type | Steps |' in document


def test_mixed_rows_split_correctly(tmp_path):
    surface = write_surface(tmp_path, [
        {'id': 'COM-11111111', 'kind': 'command', 'trigger': '/x reload'},
        {'id': 'COM-22222222', 'kind': 'command', 'trigger': '/y go'},
    ])
    assertions = write_assertions(tmp_path, [
        {'id': 'COM-11111111', 'truth': 'chat line X', 'layer': 'protocol'},
    ])
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    rows_section = document.split('## Rows with no assertion yet')
    assert 'COM-11111111' in rows_section[0]
    assert 'COM-22222222' not in rows_section[0]
    assert 'COM-22222222' in rows_section[1]


def test_artifact_five_tuple_present(tmp_path):
    surface = write_surface(tmp_path, [])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert 'X.jar' in document
    assert '1.0.0' in document
    assert '123' in document
    assert 'deadbeefdeadbeef' in document
    assert 'abc123def' in document


def test_ids_filter_restricts_rendered_rows(tmp_path):
    surface = write_surface(tmp_path, [
        {'id': 'COM-11111111', 'kind': 'command', 'trigger': '/x reload'},
        {'id': 'COM-22222222', 'kind': 'command', 'trigger': '/y go'},
    ])
    assertions = write_assertions(tmp_path, [
        {'id': 'COM-11111111', 'truth': 'chat line X', 'layer': 'protocol'},
        {'id': 'COM-22222222', 'truth': 'chat line Y', 'layer': 'protocol'},
    ])
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--ids', 'COM-11111111', '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert 'COM-11111111' in document
    assert 'COM-22222222' not in document


def test_empty_surface_renders_valid_document_not_a_crash(tmp_path):
    surface = write_surface(tmp_path, [])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    result = render_handover.main(['--surface', surface, '--assertions', assertions,
                                    '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert result == 0
    assert 'No assertions are defined for this surface yet.' in document
    assert 'Every surface row has an assertion.' in document


def test_output_ends_with_single_trailing_newline_no_cr(tmp_path):
    surface = write_surface(tmp_path, [])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    raw = output.read_bytes()

    assert raw.endswith(b'\n')
    assert not raw.endswith(b'\n\n')
    assert b'\r' not in raw


def test_duplicate_assertion_id_refuses_to_render_instead_of_silently_picking_one(tmp_path):
    # A copy-paste error in assertions.yaml repeating an id must not silently render
    # whichever entry happens to come last while the other vanishes with no diagnostic --
    # a real-machine session reading this document would act on the wrong (or missing) truth.
    surface = write_surface(tmp_path, [
        {'id': 'COM-11111111', 'kind': 'command', 'trigger': '/x reload'},
    ])
    lines = [
        'schema_version: 1',
        'assertions:',
        '  - id: COM-11111111',
        '    truth: "first truth"',
        '    layer: protocol',
        '  - id: COM-11111111',
        '    truth: "second truth, would silently overwrite the first"',
        '    layer: server',
    ]
    assertions_path = tmp_path / 'assertions.yaml'
    assertions_path.write_text('\n'.join(lines) + '\n', encoding='utf-8')

    try:
        render_handover.main(['--surface', surface, '--assertions', str(assertions_path)]
                              + ARTIFACT_ARGS)
        raised = False
        message = ''
    except SystemExit as exc:
        raised = True
        message = str(exc)

    assert raised
    assert 'duplicate' in message.lower()
    assert 'COM-11111111' in message


def test_config_row_is_asserted_through_its_owning_entity_not_its_own_id(tmp_path):
    # D-10-10: a config row is never asserted by its own id -- the entity it belongs to is.
    # A renderer that discards config_entities (or looks a config row up by its own id) can
    # never resolve this join and would report every config field as unasserted, even when
    # its entity has a real, matching assertion.
    entity = {'id': 'CFE-11111111', 'class': 'com.example.Cfg', 'file': 'config/cfg.yml', 'entry_count': 1}
    row = {'id': 'CFG-aaaaaaaa', 'kind': 'config', 'config_entity': 'com.example.Cfg',
           'config_file': 'config/cfg.yml', 'path': 'enabled'}
    surface = write_surface(tmp_path, [row], config_entities=[entity])
    assertions = write_assertions(tmp_path, [
        {'id': 'CFE-11111111', 'truth': 'config/cfg.yml loads with enabled=true', 'layer': 'protocol'},
    ])
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert 'CFG-aaaaaaaa' in document
    assert 'config/cfg.yml loads with enabled=true' in document
    assert 'Every surface row has an assertion.' in document


def test_non_command_kinds_get_a_descriptive_steps_column_not_an_empty_trigger(tmp_path):
    # Only command/help rows carry `trigger`. Reading it unconditionally for every kind
    # silently renders an empty Steps cell for a listener, scheduled task, or persistence
    # row -- exactly the rows a real-machine executor needs the most explicit guidance for.
    surface = write_surface(tmp_path, [
        {'id': 'LIS-11111111', 'kind': 'listener', 'event': 'PlayerJoinEvent', 'handler_priority': 'HIGH'},
        {'id': 'SCH-11111111', 'kind': 'scheduled', 'delay_seconds': 1, 'one_shot': False, 'period_seconds': 60},
        {'id': 'PER-11111111', 'kind': 'persistence', 'table': 'accounts'},
    ])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert 'event PlayerJoinEvent' in document
    assert 'runs every 60s' in document
    assert 'table accounts' in document


def test_a_literal_pipe_in_truth_is_escaped_not_a_broken_table_column(tmp_path):
    surface = write_surface(tmp_path, [
        {'id': 'COM-11111111', 'kind': 'command', 'trigger': '/x reload'},
    ])
    assertions_path = tmp_path / 'assertions.yaml'
    assertions_path.write_text(
        'schema_version: 1\nassertions:\n  - id: COM-11111111\n'
        '    truth: "chat line reads enabled | disabled"\n    layer: protocol\n',
        encoding='utf-8')
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', str(assertions_path),
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')
    row_line = next(line for line in document.splitlines() if line.startswith('| COM-11111111'))
    # Real column separators are ' | ' (space-pipe-space); the escaped pipe inside the
    # truth text is '\|' with no bare pipe adjacent to a space on both sides, so splitting
    # on the real separator must still yield exactly 5 columns, not 6.
    columns = row_line.strip('|').split(' | ')

    assert len(columns) == 5
    assert 'enabled \\| disabled' in document
