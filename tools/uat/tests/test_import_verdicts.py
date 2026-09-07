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


class TestRowValidation:

    def test_rejects_unknown_id_before_any_write(self, tmp_path):
        registry = write_registry(tmp_path, REG_ITEMS)
        ledger = write_ledger(tmp_path)
        before = Path(ledger).read_bytes()
        verdicts = write_verdicts(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'status': 'pass', 'observed': 'ok'},
            {'id': 'COM-nonexistent', 'status': 'pass', 'observed': 'ok'},
        ])

        with pytest.raises(SystemExit):
            import_verdicts.main(['--verdicts', verdicts, '--registry', registry, '--ledger', ledger])

        assert Path(ledger).read_bytes() == before

    def test_rejects_bad_status_before_any_write(self, tmp_path):
        registry = write_registry(tmp_path, REG_ITEMS)
        ledger = write_ledger(tmp_path)
        before = Path(ledger).read_bytes()
        verdicts = write_verdicts(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'status': 'maybe', 'observed': 'ok'},
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


class TestDryRun:

    def test_dry_run_reports_but_never_fails_on_row_level_problems(self, tmp_path):
        registry = write_registry(tmp_path, REG_ITEMS)
        ledger = write_ledger(tmp_path)
        before = Path(ledger).read_bytes()
        verdicts = write_verdicts(tmp_path, [
            {'id': 'NOT-A-REAL-ID', 'status': 'pass', 'observed': 'n/a'},
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
            {'id': 'COM-aaaaaaaa', 'status': 'pass', 'observed': 'saw pong'},
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
            {'id': 'COM-aaaaaaaa', 'status': 'pass', 'observed': 'saw pong',
             'evidence': ['machine-observations.json']},
        ])

        import_verdicts.main(['--verdicts', verdicts, '--registry', registry, '--ledger', ledger])

        led_after = json.loads(Path(ledger).read_text(encoding='utf-8'))
        assert led_after['results']['COM-aaaaaaaa']['status'] == 'pass'
        assert 'saw pong' in led_after['results']['COM-aaaaaaaa']['note']
        assert 'machine-observations.json' in led_after['results']['COM-aaaaaaaa']['note']

    def test_importing_twice_is_byte_identical(self, tmp_path):
        registry = write_registry(tmp_path, REG_ITEMS)
        ledger = write_ledger(tmp_path)
        verdicts = write_verdicts(tmp_path, [
            {'id': 'COM-aaaaaaaa', 'status': 'pass', 'observed': 'saw pong'},
            {'id': 'COM-bbbbbbbb', 'status': 'fail', 'observed': 'saw nothing'},
        ])

        import_verdicts.main(['--verdicts', verdicts, '--registry', registry, '--ledger', ledger])
        first = Path(ledger).read_bytes()
        import_verdicts.main(['--verdicts', verdicts, '--registry', registry, '--ledger', ledger])
        second = Path(ledger).read_bytes()

        assert second == first

    def test_import_order_does_not_affect_result(self, tmp_path):
        registry = write_registry(tmp_path, REG_ITEMS)
        rows = [
            {'id': 'COM-aaaaaaaa', 'status': 'pass', 'observed': 'saw pong'},
            {'id': 'COM-bbbbbbbb', 'status': 'fail', 'observed': 'saw nothing'},
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
            {'id': 'COM-aaaaaaaa', 'status': 'fail', 'observed': 'broken',
             'return_to': ['SomeModule#16 -> master']},
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
