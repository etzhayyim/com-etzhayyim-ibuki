"""test_kaizen_outcomes.py — 息吹 (ibuki) R3 operator-principal outcome collector."""
from __future__ import annotations

import json
import os
import pathlib
import tempfile

import kaizen_feedback as kf
import kaizen_outcomes as ko
from _t import expect_raises, run


def _proposals(dr, rows):
    p = pathlib.Path(dr) / "proposals.ndjson"
    p.write_text("\n".join(json.dumps(r) for r in rows) + "\n", encoding="utf-8")
    return p


def test_cron_context_refused():
    os.environ[ko.ENV_CRON] = "1"
    try:
        expect_raises(lambda: ko.collect(pathlib.Path("/nonexistent")), contains="cron")
    finally:
        os.environ.pop(ko.ENV_CRON, None)


def test_states_map_to_closed_outcomes():
    states = {101: "MERGED", 102: "CLOSED", 103: "OPEN"}
    with tempfile.TemporaryDirectory() as dr:
        p = _proposals(dr, [{"proposalId": f"p{n}", "rule": "error-rate", "pr": n}
                            for n in states])
        out = ko.collect(p, runner=lambda pr: states[pr])
        assert [o["outcome"] for o in out] == ["merged", "rejected", "pending"]


def test_proposals_without_pr_are_skipped():
    with tempfile.TemporaryDirectory() as dr:
        p = _proposals(dr, [{"proposalId": "p1", "rule": "r"},
                            {"proposalId": "p2", "rule": "r", "pr": 7}])
        out = ko.collect(p, runner=lambda pr: "MERGED")
        assert len(out) == 1 and out[0]["pr"] == 7


def test_unknown_state_raises():
    with tempfile.TemporaryDirectory() as dr:
        p = _proposals(dr, [{"proposalId": "p1", "rule": "r", "pr": 1}])
        expect_raises(lambda: ko.collect(p, runner=lambda pr: "DRAFT"),
                      contains="closed vocab")


def test_collected_outcomes_feed_kaizen_feedback_end_to_end():
    """The full Wave-4 loop on real plumbing: proposals + gh states → outcomes file →
    kaizen_feedback fold → suppression of the thrice-rejected rule."""
    with tempfile.TemporaryDirectory() as dr:
        rows = [{"proposalId": f"p{i}", "rule": "flappy-rule", "pr": 200 + i}
                for i in range(3)]
        p = _proposals(dr, rows)
        out = ko.collect(p, runner=lambda pr: "CLOSED")
        opath = pathlib.Path(dr) / "outcomes.ndjson"
        assert ko.write_outcomes(out, opath) == 3
        outcomes = kf.read_outcomes(opath)
        table = kf.suppression(kf.fold(kf.read_proposals(p), outcomes), now_beat=4)
        assert table == {"flappy-rule": 4 + kf.SUPPRESS_BEATS}
        assert kf.should_emit("flappy-rule", table, 4) is False


def test_write_is_a_snapshot_not_append():
    with tempfile.TemporaryDirectory() as dr:
        opath = pathlib.Path(dr) / "outcomes.ndjson"
        ko.write_outcomes([{"proposalId": "a", "rule": "r", "pr": 1,
                            "outcome": "pending"}], opath)
        ko.write_outcomes([{"proposalId": "a", "rule": "r", "pr": 1,
                            "outcome": "merged"}], opath)
        lines = opath.read_text(encoding="utf-8").splitlines()
        assert len(lines) == 1 and '"merged"' in lines[0]   # current state, not history


def test_module_is_read_only_toward_github():
    src = pathlib.Path(ko.__file__).read_text(encoding="utf-8")
    assert '"view"' in src.replace("'", '"')
    for verb in ("merge", "close", "comment", "review", "edit"):
        assert f'"{verb}"' not in src.replace("'", '"'), \
            f"kaizen_outcomes must stay read-only: gh pr {verb}"


if __name__ == "__main__":
    run("kaizen_outcomes", [(n, f) for n, f in sorted(globals().items())
                            if n.startswith("test_") and callable(f)])
