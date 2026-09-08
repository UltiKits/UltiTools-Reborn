"""Tests for tools/uat/render_handover.py (Phase 10 plan 10-01, Task 1)."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import render_handover  # noqa: E402  (path must be adjusted before this import)


def write_surface(tmp_path, items, config_entities=None, switches=None):
    path = tmp_path / 'surface.json'
    document = {'schema_version': 1, 'items': items}
    if config_entities is not None:
        document['config_entities'] = config_entities
    if switches:
        document.update(switches)
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


def test_ids_filter_rejects_an_unknown_id_instead_of_silently_dropping_it(tmp_path):
    # --ids selects the exact batch being dispatched; a typo or a stale row id used to be
    # silently dropped, still producing a "successful" document -- one with NO execution rows
    # at all if every requested id was unknown (Codex review of PR #427).
    surface = write_surface(tmp_path, [
        {'id': 'COM-11111111', 'kind': 'command', 'trigger': '/x reload'},
    ])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'

    try:
        render_handover.main(['--surface', surface, '--assertions', assertions,
                               '--ids', 'COM-99999999', '--output', str(output)] + ARTIFACT_ARGS)
        raised = False
    except SystemExit:
        raised = True

    assert raised
    assert not output.exists()


def test_ids_filter_rejects_an_unknown_id_even_when_other_requested_ids_are_valid(tmp_path):
    surface = write_surface(tmp_path, [
        {'id': 'COM-11111111', 'kind': 'command', 'trigger': '/x reload'},
    ])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'

    try:
        render_handover.main(['--surface', surface, '--assertions', assertions,
                               '--ids', 'COM-11111111,COM-99999999', '--output', str(output)]
                              + ARTIFACT_ARGS)
        raised = False
    except SystemExit:
        raised = True

    assert raised


def test_ids_filter_accepts_a_config_entity_id_not_just_an_item_id(tmp_path):
    surface = write_surface(tmp_path, [], config_entities=[
        {'id': 'CFG-11111111', 'class': 'my.Config', 'file': 'config/my.yml', 'entry_count': 1},
    ])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'

    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--ids', 'CFG-11111111', '--output', str(output)] + ARTIFACT_ARGS)

    assert output.exists()


def test_ids_filter_rejects_a_config_field_item_id_which_is_never_independently_renderable(tmp_path):
    # render_rows always skips `config`-kind items (D-10-10 -- a config entity is asserted and
    # executed as a whole, keyed by its OWNING config_entities id, never its own field-level
    # id). A `config` item's own id passed the first --ids fix's naive "is it in items" check,
    # then silently produced a handover with zero rows (Codex review of PR #427).
    surface = write_surface(tmp_path, [
        {'id': 'CFG-field-11111111', 'kind': 'config', 'config_entity': 'my.Config'},
    ], config_entities=[
        {'id': 'CFG-11111111', 'class': 'my.Config', 'file': 'config/my.yml', 'entry_count': 1},
    ])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'

    try:
        render_handover.main(['--surface', surface, '--assertions', assertions,
                               '--ids', 'CFG-field-11111111', '--output', str(output)]
                              + ARTIFACT_ARGS)
        raised = False
    except SystemExit:
        raised = True

    assert raised
    assert not output.exists()


def test_ids_filter_rejects_more_than_60_ids_even_when_all_are_valid(tmp_path):
    # --ids is treated as the EXACT batch being dispatched, so it must obey the same absolute
    # 60-execution-row ceiling `uat.py next` enforces -- an operator hand-assembling --ids from
    # several prior batches could otherwise generate a handover the protocol itself forbids
    # (Codex review of PR #427).
    items = [{'id': f'COM-{i:08d}', 'kind': 'command', 'trigger': f'/x {i}'} for i in range(61)]
    surface = write_surface(tmp_path, items)
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    ids_arg = ','.join(item['id'] for item in items)

    try:
        render_handover.main(['--surface', surface, '--assertions', assertions,
                               '--ids', ids_arg, '--output', str(output)] + ARTIFACT_ARGS)
        raised = False
    except SystemExit:
        raised = True

    assert raised
    assert not output.exists()


def test_ids_filter_accepts_exactly_60_ids(tmp_path):
    items = [{'id': f'COM-{i:08d}', 'kind': 'command', 'trigger': f'/x {i}'} for i in range(60)]
    surface = write_surface(tmp_path, items)
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    ids_arg = ','.join(item['id'] for item in items)

    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--ids', ids_arg, '--output', str(output)] + ARTIFACT_ARGS)

    assert output.exists()


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


def test_config_entity_renders_one_execution_row_keyed_by_the_entity_id_not_per_field(tmp_path):
    # D-10-10: a config entity is executed as a whole, keyed by its own id -- never once per
    # field. A renderer that iterates `config` surface items directly (instead of rendering
    # config_entities once each) would either report every field as unasserted despite a
    # real entity assertion, or -- worse -- render one duplicate execution row per field.
    entity = {'id': 'CFE-11111111', 'class': 'com.example.Cfg', 'file': 'config/cfg.yml', 'entry_count': 2}
    row_a = {'id': 'CFG-aaaaaaaa', 'kind': 'config', 'config_entity': 'com.example.Cfg',
             'config_file': 'config/cfg.yml', 'path': 'enabled'}
    row_b = {'id': 'CFG-bbbbbbbb', 'kind': 'config', 'config_entity': 'com.example.Cfg',
             'config_file': 'config/cfg.yml', 'path': 'timeout'}
    surface = write_surface(tmp_path, [row_a, row_b], config_entities=[entity])
    assertions = write_assertions(tmp_path, [
        {'id': 'CFE-11111111', 'truth': 'config/cfg.yml loads with enabled=true', 'layer': 'protocol'},
    ])
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert 'CFE-11111111' in document
    assert 'config/cfg.yml loads with enabled=true' in document
    assert 'config entity com.example.Cfg in config/cfg.yml (2 field(s))' in document
    assert document.count('CFE-11111111') == 1  # rendered once, not once per field
    assert 'CFG-aaaaaaaa' not in document
    assert 'CFG-bbbbbbbb' not in document
    assert 'Every surface row has an assertion.' in document


def test_config_entity_with_no_assertion_appears_in_the_unasserted_section(tmp_path):
    entity = {'id': 'CFE-22222222', 'class': 'com.example.Other', 'file': 'config/other.yml', 'entry_count': 0}
    surface = write_surface(tmp_path, [], config_entities=[entity])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    unasserted_section = document.split('## Rows with no assertion yet')[1]
    assert 'CFE-22222222' in unasserted_section
    assert 'config entity com.example.Other in config/other.yml (0 field(s))' in unasserted_section


def test_assertion_preconditions_are_rendered_not_silently_dropped(tmp_path):
    surface = write_surface(tmp_path, [
        {'id': 'COM-11111111', 'kind': 'command', 'trigger': '/x reload'},
    ])
    assertions_path = tmp_path / 'assertions.yaml'
    assertions_path.write_text(
        'schema_version: 1\nassertions:\n  - id: COM-11111111\n'
        '    truth: "chat line X"\n    layer: protocol\n'
        '    preconditions:\n      - permission: ultikits.tools.reload\n'
        '      - "a second player online"\n',
        encoding='utf-8')
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', str(assertions_path),
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert 'preconditions:' in document
    assert 'a second player online' in document


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


def test_conditional_gate_steps_name_the_file_and_key_separately_with_the_required_boolean(tmp_path):
    # @ConditionalOnConfig(value=<file>, path=<key>) -- the annotation's own attribute names
    # are file-oriented, the reverse of what they suggest side by side. The old rendering
    # ("gate {path}={value}") treated the file path as the value to assign and the key as the
    # setting name, which would tell an executor to configure the wrong thing entirely.
    surface = write_surface(tmp_path, [
        {'id': 'CON-11111111', 'kind': 'conditional',
         'gate': {'value': 'config/config.yml', 'path': 'enableWarp', 'negate': False}},
        {'id': 'CON-22222222', 'kind': 'conditional',
         'gate': {'value': 'config/config.yml', 'path': 'disableWarp', 'negate': True}},
    ])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert 'config key enableWarp in config/config.yml must be true' in document
    assert 'config key disableWarp in config/config.yml must be false' in document
    # The old (wrong) rendering is not present anywhere in the document.
    assert 'gate enableWarp=config/config.yml' not in document


def test_a_gated_commands_row_carries_the_gate_in_its_own_steps_not_only_on_the_separate_conditional_row(tmp_path):
    # Batches are meant to be self-contained and may include the functional row without its
    # separate standalone conditional row -- an executor working from this row alone could
    # otherwise test while the class is disabled and record a false failure (Codex review of
    # PR #427).
    gate = {'value': 'config/config.yml', 'path': 'enableWarp', 'negate': False}
    surface = write_surface(tmp_path, [
        {'id': 'COM-11111111', 'kind': 'command', 'trigger': '/warp go', 'gate': gate},
    ])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert '/warp go' in document
    assert 'config key enableWarp in config/config.yml must be true' in document


def test_a_manually_registered_listener_row_states_that_in_its_steps(tmp_path):
    # ListenerManager.registerAll deliberately skips automatic registration for a
    # manualRegister = true class; the surface preserves that flag, but rendering exactly
    # the same event step as an automatically-registered listener leaves an executor unable
    # to tell it must inspect the module-specific registration path (Codex review of PR
    # #427).
    surface = write_surface(tmp_path, [
        {'id': 'LIS-11111111', 'kind': 'listener', 'event': 'PlayerJoinEvent',
         'handler_priority': 'HIGH', 'manual_register': True},
    ])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert 'event PlayerJoinEvent' in document
    assert 'manually registered' in document


def test_a_listener_row_with_ignore_cancelled_states_that_in_its_steps(tmp_path):
    # Bukkit skips this handler entirely for an already-cancelled event, before the method is
    # ever called -- omitting this from the rendered steps makes it indistinguishable from a
    # default handler, so triggering a cancelled event and observing no effect looks like a
    # broken handler rather than Bukkit's own documented behavior (Codex review of PR #427).
    surface = write_surface(tmp_path, [
        {'id': 'LIS-11111111', 'kind': 'listener', 'event': 'PlayerJoinEvent',
         'handler_priority': 'HIGH', 'ignore_cancelled': True},
    ])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert 'event PlayerJoinEvent' in document
    assert 'ignoreCancelled=true' in document


def test_a_listener_row_without_ignore_cancelled_has_no_such_note(tmp_path):
    surface = write_surface(tmp_path, [
        {'id': 'LIS-11111111', 'kind': 'listener', 'event': 'PlayerJoinEvent',
         'handler_priority': 'HIGH', 'ignore_cancelled': False},
    ])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert 'ignoreCancelled' not in document


def test_a_manually_registered_command_row_states_that_in_its_steps(tmp_path):
    # CommandManager.register's autowire-and-register path is skipped entirely for an
    # @CmdExecutor(manualRegister = true) class -- the surface preserves that flag, but
    # rendering only the bare command trigger leaves an executor unable to tell it must check
    # the module's own manual registration path rather than the automatic one (Codex review
    # of PR #427).
    surface = write_surface(tmp_path, [
        {'id': 'COM-11111111', 'kind': 'command', 'trigger': '/warp go', 'manual_register': True},
    ])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert '/warp go' in document
    assert 'manually registered' in document


def test_registers_commands_false_notes_disabled_auto_registration_on_command_rows(tmp_path):
    # SurfaceAssembler writes registers_commands/registers_listeners/registers_config at
    # document level from @UltiToolsModule's own cmdExecutor=/eventListener=/config()
    # switches; load_surface used to discard all three, giving the executor ordinary
    # trigger steps with no indication that automatic registration was off for the WHOLE
    # module (Codex review of PR #427).
    surface = write_surface(tmp_path, [
        {'id': 'COM-11111111', 'kind': 'command', 'trigger': '/warp go'},
    ], switches={'registers_commands': False})
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert '/warp go' in document
    assert 'cmdExecutor=false' in document


def test_registers_listeners_false_notes_disabled_auto_registration_on_listener_rows(tmp_path):
    surface = write_surface(tmp_path, [
        {'id': 'LIS-11111111', 'kind': 'listener', 'event': 'PlayerJoinEvent', 'handler_priority': 'HIGH'},
    ], switches={'registers_listeners': False})
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert 'event PlayerJoinEvent' in document
    assert 'eventListener=false' in document


def test_registers_config_false_notes_disabled_auto_registration_on_config_entity_rows(tmp_path):
    surface = write_surface(tmp_path, [], config_entities=[
        {'id': 'CFG-11111111', 'class': 'my.Config', 'file': 'config/my.yml', 'entry_count': 2},
    ], switches={'registers_config': False})
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert 'config entity my.Config' in document
    assert 'config=false' in document


def test_absent_registration_switches_add_no_note(tmp_path):
    # A module with no @UltiToolsModule entry class among the scanned classes writes none
    # of the three switch keys at all -- absence must be treated as "not applicable", never
    # as a false-y false.
    surface = write_surface(tmp_path, [
        {'id': 'COM-11111111', 'kind': 'command', 'trigger': '/warp go'},
    ])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert '/warp go' in document
    assert 'cmdExecutor=false' not in document


def test_a_player_only_command_row_states_that_in_its_steps(tmp_path):
    # SenderTypeValidator rejects invocation from the OTHER sender type -- omitting this from
    # the rendered steps let an executor try from the wrong context and record a false
    # failure for a command that was never reachable from there at all (Codex review of PR
    # #427).
    surface = write_surface(tmp_path, [
        {'id': 'COM-11111111', 'kind': 'command', 'trigger': '/warp go', 'cmd_target': 'PLAYER'},
    ])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert '/warp go' in document
    assert 'PLAYER-only' in document


def test_a_console_only_help_row_states_that_in_its_steps(tmp_path):
    surface = write_surface(tmp_path, [
        {'id': 'HLP-11111111', 'kind': 'help', 'trigger': '/warp help', 'cmd_target': 'CONSOLE'},
    ])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert '/warp help' in document
    assert 'CONSOLE-only' in document


def test_a_both_sender_command_row_has_no_sender_restriction_note(tmp_path):
    # BOTH is not a real restriction -- both sender types can invoke it, so no note applies.
    surface = write_surface(tmp_path, [
        {'id': 'COM-11111111', 'kind': 'command', 'trigger': '/warp go', 'cmd_target': 'BOTH'},
    ])
    assertions = write_empty_assertions(tmp_path)
    output = tmp_path / 'handover.md'
    render_handover.main(['--surface', surface, '--assertions', assertions,
                           '--output', str(output)] + ARTIFACT_ARGS)
    document = output.read_text(encoding='utf-8')

    assert '/warp go' in document
    assert '-only' not in document


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
