"""
Tests for tools/uat/import_verdicts.py and the ledger migration it depends on.

(Phase 10 plan 10-01, Task 2).
"""
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import import_verdicts  # noqa: E402  (path must be adjusted before these imports)
import uat  # noqa: E402

REG_ITEMS = [
    {'id': 'COM-aaaaaaaa', 'kind': 'command', 'origin': 'X', 'cls': 'A', 'trigger': '/x a'},
    {'id': 'COM-bbbbbbbb', 'kind': 'command', 'origin': 'X', 'cls': 'B', 'trigger': '/x b'},
]


def write_registry(directory, items, artifact_sha256='deadbeef'):
    path = directory / 'registry.json'
    directory.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({
        'framework_version': '1.0.0',
        'artifact_sha256': artifact_sha256,
        'items': items,
    }), encoding='utf-8')
    return str(path)


def write_ledger(directory, artifact_sha256='deadbeef', results=None, extra=None):
    directory.mkdir(parents=True, exist_ok=True)
    doc = dict(framework_version='1.0.0', artifact_sha256=artifact_sha256,
               results=results or {}, scope=['*'])
    if extra:
        doc.update(extra)
    path = directory / 'ledger.json'
    path.write_text(json.dumps(doc), encoding='utf-8')
    return str(path)


def write_verdicts(directory, rows, filename='verdicts.json'):
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / filename
    path.write_text(json.dumps({'rows': rows}), encoding='utf-8')
    return str(path)


def make_row(**overrides):
    """
    Build a verdict row carrying every field the real protocol requires.

    Sensible defaults mean a test exercising one specific field (an id, a status, a
    duplicate) does not also have to restate every other required field just to get past
    schema validation.
    """
    row = dict(
        id='COM-aaaaaaaa', repository='X', type='repro', steps='run it', observed='ok',
        status='pass', reason='matches expectation', actions=['probe'], evidence=[],
    )
    row.update(overrides)
    return row


class TestRowValidation:

    def test_rejects_unknown_id_before_any_write(self, tmp_path):
        registry = write_registry(tmp_path, REG_ITEMS)
        ledger = write_ledger(tmp_path)
        before = Path(ledger).read_bytes()
        verdicts = write_verdicts(tmp_path, [
            make_row(id='COM-aaaaaaaa'),
            make_row(id='COM-nonexistent'),
        ])

        with pytest.raises(SystemExit):
            import_verdicts.main(['--verdicts', verdicts, '--registry', registry, '--ledger', ledger])

        assert Path(ledger).read_bytes() == before

    def test_rejects_bad_status_before_any_write(self, tmp_path):
        registry = write_registry(tmp_path, REG_ITEMS)
        ledger = write_ledger(tmp_path)
        before = Path(ledger).read_bytes()
        verdicts = write_verdicts(tmp_path, [
            make_row(id='COM-aaaaaaaa', status='maybe'),
        ])

        with pytest.raises(SystemExit):
            import_verdicts.main(['--verdicts', verdicts, '--registry', registry, '--ledger', ledger])

        assert Path(ledger).read_bytes() == before

    def test_rejects_malformed_json_before_any_write(self, tmp_path):
        registry = write_registry(tmp_path, REG_ITEMS)
        ledger = write_ledger(tmp_path)
        before = Path(ledger).read_bytes()
        bad = tmp_path / 'bad.json'
        bad.write_text('{not valid json', encoding='utf-8')

        with pytest.raises(SystemExit):
            import_verdicts.main(['--verdicts', str(bad), '--registry', registry, '--ledger', ledger])

        assert Path(ledger).read_bytes() == before

    def test_rejects_duplicate_id_within_the_same_file_before_any_write(self, tmp_path):
        # apply_rows writes in list order; two rows sharing an id with DIFFERENT content
        # would let whichever comes last silently win, contradicting this importer's own
        # "import order never affects the result" guarantee.
        registry = write_registry(tmp_path, REG_ITEMS)
        ledger = write_ledger(tmp_path)
        before = Path(ledger).read_bytes()
        verdicts = write_verdicts(tmp_path, [
            make_row(id='COM-aaaaaaaa', status='pass', observed='first observation'),
            make_row(id='COM-aaaaaaaa', status='fail', observed='second observation, would win silently'),
        ])

        with pytest.raises(SystemExit):
            import_verdicts.main(['--verdicts', verdicts, '--registry', registry, '--ledger', ledger])

        assert Path(ledger).read_bytes() == before

    def test_rejects_a_verdict_row_for_an_id_excluded_by_the_ledgers_own_scope(self, tmp_path):
        # After a narrowed `rebase --scope framework`, a stale verdicts file for an
        # excluded module's id must be rejected the same way an unknown id is -- this
        # ledger no longer tracks that module, and writing it back in would silently
        # reintroduce a result the rebase deliberately dropped from this build's scope.
        registry = write_registry(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'kind': 'command', 'origin': 'framework', 'cls': 'A', 'trigger': '/x a'},
            {'id': 'COM-eeeeeeee', 'kind': 'command', 'origin': 'ExcludedModule', 'cls': 'E', 'trigger': '/e go'},
        ])
        ledger = write_ledger(tmp_path, extra={'scope': ['framework']})
        before = Path(ledger).read_bytes()
        verdicts = write_verdicts(tmp_path, [
            make_row(id='COM-eeeeeeee', observed='stale, out-of-scope result'),
        ])

        with pytest.raises(SystemExit):
            import_verdicts.main(['--verdicts', verdicts, '--registry', registry, '--ledger', ledger])

        assert Path(ledger).read_bytes() == before

    def test_accepts_a_verdict_row_for_an_id_within_the_ledgers_own_scope(self, tmp_path):
        registry = write_registry(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'kind': 'command', 'origin': 'framework', 'cls': 'A', 'trigger': '/x a'},
            {'id': 'COM-eeeeeeee', 'kind': 'command', 'origin': 'ExcludedModule', 'cls': 'E', 'trigger': '/e go'},
        ])
        ledger = write_ledger(tmp_path, extra={'scope': ['framework']})
        verdicts = write_verdicts(tmp_path, [
            make_row(id='COM-aaaaaaaa', observed='in-scope result'),
        ])

        import_verdicts.main(['--verdicts', verdicts, '--registry', registry, '--ledger', ledger])

        led_after = json.loads(Path(ledger).read_text(encoding='utf-8'))
        assert led_after['results']['COM-aaaaaaaa']['status'] == 'pass'

    def test_rejects_a_row_missing_a_required_protocol_field_before_any_write(self, tmp_path):
        # A row carrying only id/status/observed used to pass validation, converting the
        # missing repository/type/steps/reason into an empty ledger note -- a malformed
        # "pass" committed as a completed measurement with no record of what was actually
        # exercised (Codex review of PR #427).
        registry = write_registry(tmp_path, REG_ITEMS)
        ledger = write_ledger(tmp_path)
        before = Path(ledger).read_bytes()
        verdicts = write_verdicts(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'status': 'pass', 'observed': 'ok'},
        ])

        with pytest.raises(SystemExit):
            import_verdicts.main(['--verdicts', verdicts, '--registry', registry, '--ledger', ledger])

        assert Path(ledger).read_bytes() == before

    def test_accepts_an_empty_actions_or_evidence_list_a_static_review_row_can_legitimately_have(self, tmp_path):
        # Confirmed against the real Phase 13 uat-verdicts.json: rows exist with an empty
        # `actions` (a pure source/static-review row with no dispatched action identifier)
        # and rows with an empty `evidence` (an observation made live with nothing
        # separately saved). Requiring non-empty here would reject real, legitimate data.
        registry = write_registry(tmp_path, REG_ITEMS)
        ledger = write_ledger(tmp_path)
        verdicts = write_verdicts(tmp_path, [
            make_row(id='COM-aaaaaaaa', actions=[], evidence=[]),
        ])

        import_verdicts.main(['--verdicts', verdicts, '--registry', registry, '--ledger', ledger])

        led_after = json.loads(Path(ledger).read_text(encoding='utf-8'))
        assert led_after['results']['COM-aaaaaaaa']['status'] == 'pass'


class TestDryRun:

    def test_dry_run_reports_but_never_fails_on_row_level_problems(self, tmp_path):
        registry = write_registry(tmp_path, REG_ITEMS)
        ledger = write_ledger(tmp_path)
        before = Path(ledger).read_bytes()
        verdicts = write_verdicts(tmp_path, [
            make_row(id='NOT-A-REAL-ID', observed='n/a'),
        ])

        result = import_verdicts.main(
            ['--verdicts', verdicts, '--registry', registry, '--ledger', ledger, '--dry-run'])

        assert result == 0
        assert Path(ledger).read_bytes() == before

    def test_dry_run_still_fails_on_malformed_verdicts_json(self, tmp_path):
        registry = write_registry(tmp_path, REG_ITEMS)
        ledger = write_ledger(tmp_path)
        bad = tmp_path / 'bad.json'
        bad.write_text('{not valid json', encoding='utf-8')

        with pytest.raises(SystemExit):
            import_verdicts.main(
                ['--verdicts', str(bad), '--registry', registry, '--ledger', ledger, '--dry-run'])

    def test_dry_run_writes_nothing_even_for_valid_rows(self, tmp_path):
        registry = write_registry(tmp_path, REG_ITEMS)
        ledger = write_ledger(tmp_path)
        before = Path(ledger).read_bytes()
        verdicts = write_verdicts(tmp_path, [
            make_row(id='COM-aaaaaaaa', observed='saw pong'),
        ])

        result = import_verdicts.main(
            ['--verdicts', verdicts, '--registry', registry, '--ledger', ledger, '--dry-run'])

        assert result == 0
        assert Path(ledger).read_bytes() == before


class TestWriteBehavior:

    def test_empty_rows_writes_nothing_and_exits_zero(self, tmp_path, capsys):
        registry = write_registry(tmp_path, REG_ITEMS)
        ledger = write_ledger(tmp_path)
        before = Path(ledger).read_bytes()
        verdicts = write_verdicts(tmp_path, [])

        result = import_verdicts.main(['--verdicts', verdicts, '--registry', registry, '--ledger', ledger])

        assert result == 0
        assert Path(ledger).read_bytes() == before
        assert 'wrote 0 result' in capsys.readouterr().out

    def test_valid_row_is_recorded(self, tmp_path):
        registry = write_registry(tmp_path, REG_ITEMS)
        ledger = write_ledger(tmp_path)
        verdicts = write_verdicts(tmp_path, [
            make_row(id='COM-aaaaaaaa', observed='saw pong',
                     evidence=['machine-observations.json']),
        ])

        import_verdicts.main(['--verdicts', verdicts, '--registry', registry, '--ledger', ledger])

        led_after = json.loads(Path(ledger).read_text(encoding='utf-8'))
        assert led_after['results']['COM-aaaaaaaa']['status'] == 'pass'
        assert 'saw pong' in led_after['results']['COM-aaaaaaaa']['note']
        assert 'machine-observations.json' in led_after['results']['COM-aaaaaaaa']['note']

    def test_human_uat_pending_is_accepted_not_rejected_as_an_unknown_status(self, tmp_path):
        # UAT-MATRIX-SCHEMA.md's "Handover and verdict protocol" section (D-10-16) names
        # human-uat-pending a legitimate terminal status for a row that genuinely needs a
        # human -- a verdicts file exercising that documented status must import cleanly,
        # not have its whole batch rejected over one row this importer doesn't recognise.
        registry = write_registry(tmp_path, REG_ITEMS)
        ledger = write_ledger(tmp_path)
        verdicts = write_verdicts(tmp_path, [
            make_row(id='COM-aaaaaaaa', status='human-uat-pending',
                     observed='requires the panel UltiCloud login, not enabled on this server'),
        ])

        import_verdicts.main(['--verdicts', verdicts, '--registry', registry, '--ledger', ledger])

        led_after = json.loads(Path(ledger).read_text(encoding='utf-8'))
        assert led_after['results']['COM-aaaaaaaa']['status'] == 'human-uat-pending'

    def test_importing_twice_is_byte_identical(self, tmp_path):
        registry = write_registry(tmp_path, REG_ITEMS)
        ledger = write_ledger(tmp_path)
        verdicts = write_verdicts(tmp_path, [
            make_row(id='COM-aaaaaaaa', observed='saw pong'),
            make_row(id='COM-bbbbbbbb', status='fail', observed='saw nothing'),
        ])

        import_verdicts.main(['--verdicts', verdicts, '--registry', registry, '--ledger', ledger])
        first = Path(ledger).read_bytes()
        import_verdicts.main(['--verdicts', verdicts, '--registry', registry, '--ledger', ledger])
        second = Path(ledger).read_bytes()

        assert second == first

    def test_import_order_does_not_affect_result(self, tmp_path):
        registry = write_registry(tmp_path, REG_ITEMS)
        rows = [
            make_row(id='COM-aaaaaaaa', observed='saw pong'),
            make_row(id='COM-bbbbbbbb', status='fail', observed='saw nothing'),
        ]

        ledger_a = write_ledger(tmp_path / 'a')
        import_verdicts.main(['--verdicts', write_verdicts(tmp_path / 'a', rows),
                               '--registry', registry, '--ledger', ledger_a])

        ledger_b = write_ledger(tmp_path / 'b')
        shuffled = list(reversed(rows))
        import_verdicts.main(['--verdicts', write_verdicts(tmp_path / 'b', shuffled),
                               '--registry', registry, '--ledger', ledger_b])

        assert Path(ledger_a).read_bytes() == Path(ledger_b).read_bytes()

    def test_fail_row_with_return_to_reported_and_still_recorded(self, tmp_path, capsys):
        registry = write_registry(tmp_path, REG_ITEMS)
        ledger = write_ledger(tmp_path)
        verdicts = write_verdicts(tmp_path, [
            make_row(id='COM-aaaaaaaa', status='fail', observed='broken',
                     return_to=['SomeModule#16 -> master']),
        ])

        import_verdicts.main(['--verdicts', verdicts, '--registry', registry, '--ledger', ledger])
        out = capsys.readouterr().out

        assert 'Defects to file' in out
        assert 'SomeModule#16' in out
        led_after = json.loads(Path(ledger).read_text(encoding='utf-8'))
        assert led_after['results']['COM-aaaaaaaa']['status'] == 'fail'


class TestSupersededLedgerMigration:

    def test_migrates_singular_key_to_a_one_element_list(self):
        old = {'framework_version': '1.0.0', 'results': {}}
        led = {'framework_version': '1.0.1', 'superseded_ledger': old, 'results': {}}

        migrated = uat.migrate_superseded_key(led)

        assert migrated['superseded_ledgers'] == [old]
        assert 'superseded_ledger' not in migrated

    def test_leaves_an_already_list_form_ledger_unchanged(self):
        already = {'framework_version': '1.0.1', 'superseded_ledgers': [{'a': 1}], 'results': {}}

        migrated = uat.migrate_superseded_key(already)

        assert migrated is already
        assert migrated['superseded_ledgers'] == [{'a': 1}]

    def test_is_idempotent_on_reapplication(self):
        old = {'framework_version': '1.0.0', 'results': {}}
        led = {'framework_version': '1.0.1', 'superseded_ledger': old, 'results': {}}

        once = uat.migrate_superseded_key(led)
        twice = uat.migrate_superseded_key(once)

        assert twice == once
