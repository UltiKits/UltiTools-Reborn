"""Tests for tools/uat/render_handover.py (Phase 10 plan 10-01, Task 1)."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import render_handover  # noqa: E402  (path must be adjusted before this import)


def write_surface(tmp_path, items):
    path = tmp_path / 'surface.json'
    path.write_text(json.dumps({'schema_version': 1, 'items': items}), encoding='utf-8')
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
