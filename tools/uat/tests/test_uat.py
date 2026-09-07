"""
Tests for tools/uat/uat.py's `next` batching (Phase 10 plan 10-05, Codex review of PR #427).

Covers the mixed-kind batch case a `--kind`-filtered test cannot reach: D-10-15's own batching
plan dispatches whole modules (filtered by `--origin`, not `--kind`), so a real batch commonly
mixes command/listener/other rows together, not just listener rows in isolation.
"""
import io
import json
import sys
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import uat  # noqa: E402  (path must be adjusted before this import)


def write_registry(tmp_path, items, artifact_sha256='deadbeef'):
    path = tmp_path / 'registry.json'
    path.write_text(json.dumps({
        'framework_version': '1.0.0',
        'artifact_sha256': artifact_sha256,
        'items': items,
    }), encoding='utf-8')
    return str(path)


def write_ledger(tmp_path, artifact_sha256='deadbeef', results=None):
    path = tmp_path / 'ledger.json'
    path.write_text(json.dumps({
        'framework_version': '1.0.0',
        'artifact_sha256': artifact_sha256,
        'results': results or {},
        'scope': ['*'],
    }), encoding='utf-8')
    return str(path)


def run_next(registry, ledger, kind=None, origin=None, group_by_event=False, size=25):
    args = type('Args', (), {
        'registry': registry, 'ledger': ledger, 'kind': kind, 'origin': origin,
        'group_by_event': group_by_event, 'size': size,
    })()
    buf = io.StringIO()
    with redirect_stdout(buf):
        uat.cmd_next(args)
    return buf.getvalue()


COMMAND_ITEM = {
    'id': 'COM-aaaaaaaa', 'kind': 'command', 'origin': 'X', 'cls': 'A', 'file': 'A.java',
    'trigger': '/x a', 'who': 'op', 'expect': 'ok', 'branches': [],
}


def listener_item(item_id, cls, member):
    return {
        'id': item_id, 'kind': 'listener', 'origin': 'X', 'cls': cls, 'file': f'{cls}.java',
        'event': 'PlayerJoinEvent', 'trigger': 'join', 'who': 'anyone', 'expect': 'ok',
        'member': member, 'branches': [],
    }


def test_default_mixed_batch_still_groups_listener_handlers_by_event():
    # The old behaviour only grouped when --kind was exactly 'listener'; a default, unfiltered
    # batch (the common case per D-10-15's own module-dispatch plan) rendered listener rows
    # flat instead, letting handlers for the same event be split across separate `next` calls.
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        tmp_path = Path(d)
        items = [COMMAND_ITEM, listener_item('LIS-11111111', 'L1', 'onJoin'),
                 listener_item('LIS-22222222', 'L2', 'onJoin')]
        registry = write_registry(tmp_path, items)
        ledger = write_ledger(tmp_path)

        out = run_next(registry, ledger)

        assert 'COM-aaaaaaaa' in out
        assert 'TRIGGER: join' in out
        assert 'LIS-11111111' in out
        assert 'LIS-22222222' in out
        # Both handlers for the one event appear under the SAME trigger header, not two.
        assert out.count('TRIGGER: join') == 1


def test_size_never_splits_one_event_across_the_boundary():
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        tmp_path = Path(d)
        items = [COMMAND_ITEM, listener_item('LIS-11111111', 'L1', 'onJoin'),
                 listener_item('LIS-22222222', 'L2', 'onJoin')]
        registry = write_registry(tmp_path, items)
        ledger = write_ledger(tmp_path)

        # size=1 fills the one non-listener item first; no budget remains for the event, so
        # neither handler should appear yet -- never one handler without the other.
        out = run_next(registry, ledger, size=1)

        assert 'COM-aaaaaaaa' in out
        assert 'LIS-11111111' not in out
        assert 'LIS-22222222' not in out


def test_kind_filtered_to_listener_still_groups_exactly_as_before():
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        tmp_path = Path(d)
        items = [COMMAND_ITEM, listener_item('LIS-11111111', 'L1', 'onJoin'),
                 listener_item('LIS-22222222', 'L2', 'onJoin')]
        registry = write_registry(tmp_path, items)
        ledger = write_ledger(tmp_path)

        out = run_next(registry, ledger, kind='listener')

        assert 'COM-aaaaaaaa' not in out
        assert 'TRIGGER: join' in out
        assert 'LIS-11111111' in out
        assert 'LIS-22222222' in out


def test_record_rejects_an_id_excluded_by_the_ledgers_own_scope():
    # After a narrowed `rebase --scope framework`, the manual record path must reject an
    # excluded module's id the same way import_verdicts.py's own scope check does (Codex
    # review of PR #427) -- otherwise a hidden stale result gets written in, counting
    # against a later, wider scope even though this ledger was never meant to track it.
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        tmp_path = Path(d)
        items = [
            {'id': 'COM-aaaaaaaa', 'kind': 'command', 'origin': 'framework', 'cls': 'A'},
            {'id': 'COM-eeeeeeee', 'kind': 'command', 'origin': 'ExcludedModule', 'cls': 'E'},
        ]
        registry = write_registry(tmp_path, items)
        ledger_path_str = write_ledger(tmp_path)
        with open(ledger_path_str) as f:
            doc = json.load(f)
        doc['scope'] = ['framework']
        with open(ledger_path_str, 'w') as f:
            json.dump(doc, f)
        before = Path(ledger_path_str).read_bytes()

        args = type('Args', (), {
            'registry': registry, 'ledger': ledger_path_str, 'id': 'COM-eeeeeeee',
            'status': 'pass', 'note': 'stale, out of scope',
        })()

        raised = False
        try:
            uat.cmd_record(args)
        except SystemExit:
            raised = True

        assert raised
        assert Path(ledger_path_str).read_bytes() == before


def test_record_accepts_an_id_within_the_ledgers_own_scope():
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        tmp_path = Path(d)
        items = [
            {'id': 'COM-aaaaaaaa', 'kind': 'command', 'origin': 'framework', 'cls': 'A'},
            {'id': 'COM-eeeeeeee', 'kind': 'command', 'origin': 'ExcludedModule', 'cls': 'E'},
        ]
        registry = write_registry(tmp_path, items)
        ledger_path_str = write_ledger(tmp_path)
        with open(ledger_path_str) as f:
            doc = json.load(f)
        doc['scope'] = ['framework']
        with open(ledger_path_str, 'w') as f:
            json.dump(doc, f)

        args = type('Args', (), {
            'registry': registry, 'ledger': ledger_path_str, 'id': 'COM-aaaaaaaa',
            'status': 'pass', 'note': 'in scope',
        })()
        uat.cmd_record(args)

        with open(ledger_path_str) as f:
            led_after = json.load(f)
        assert led_after['results']['COM-aaaaaaaa']['status'] == 'pass'


def test_size_argument_rejects_a_value_above_the_60_unit_ceiling():
    # UAT-MATRIX-SCHEMA.md fixes 60 execution rows as an absolute per-dispatch ceiling (Codex
    # review of PR #427). --size was an unrestricted `type=int` before this test, so a value
    # above the ceiling emitted more units than any one real-machine session is meant to receive.
    try:
        uat.build_parser().parse_args(['next', '--size', '61'])
        assert False, 'expected argparse to reject --size 61'
    except SystemExit:
        pass


def test_size_argument_rejects_a_negative_value():
    # A negative --size sliced from the wrong end of the pending list (Python's negative-index
    # slicing), silently returning nearly the entire backlog for a typo like --size -1.
    try:
        uat.build_parser().parse_args(['next', '--size', '-1'])
        assert False, 'expected argparse to reject --size -1'
    except SystemExit:
        pass


def test_size_argument_rejects_zero():
    try:
        uat.build_parser().parse_args(['next', '--size', '0'])
        assert False, 'expected argparse to reject --size 0'
    except SystemExit:
        pass


def test_size_argument_accepts_the_ceiling_value_itself():
    args = uat.build_parser().parse_args(['next', '--size', '60'])
    assert args.size == 60


def test_size_argument_accepts_the_default_when_omitted():
    args = uat.build_parser().parse_args(['next'])
    assert args.size == 25
