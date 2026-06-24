"""test_kaizen_feedback.py — 息吹 (ibuki) Wave-4 kaizen feedback loop. ADR-2606101200."""
from __future__ import annotations

import json
import pathlib
import tempfile

import kaizen_feedback as kf
from _t import expect_raises, run


def _ndjson(dr, name, rows):
    p = pathlib.Path(dr) / name
    p.write_text("\n".join(json.dumps(r) for r in rows) + "\n", encoding="utf-8")
    return p


def test_read_proposals_filters_malformed():
    with tempfile.TemporaryDirectory() as dr:
        p = _ndjson(dr, "p.ndjson", [{"proposalId": "p1", "rule": "error-rate"},
                                     {"noise": True}])
        assert len(kf.read_proposals(p)) == 1


def test_read_outcomes_closed_vocab():
    with tempfile.TemporaryDirectory() as dr:
        p = _ndjson(dr, "o.ndjson", [{"proposalId": "p1", "rule": "r", "outcome": "shipped"}])
        expect_raises(lambda: kf.read_outcomes(p), contains="closed vocab")


def test_missing_files_are_empty():
    assert kf.read_proposals(pathlib.Path("/nonexistent.ndjson")) == []
    assert kf.read_outcomes(pathlib.Path("/nonexistent.ndjson")) == []


def test_fold_counts_and_consecutive_rejections():
    proposals = [{"proposalId": f"p{i}", "rule": "lru-saturation"} for i in range(4)]
    outcomes = [{"proposalId": "p0", "rule": "lru-saturation", "outcome": "rejected"},
                {"proposalId": "p1", "rule": "lru-saturation", "outcome": "merged"},
                {"proposalId": "p2", "rule": "lru-saturation", "outcome": "rejected"},
                {"proposalId": "p3", "rule": "lru-saturation", "outcome": "rejected"}]
    s = kf.fold(proposals, outcomes)["lru-saturation"]
    assert s["proposed"] == 4 and s["merged"] == 1 and s["rejected"] == 3
    assert s["consecutive_rejected"] == 2            # merge reset the run


def test_suppression_threshold():
    stats = {"a": {"proposed": 3, "merged": 0, "rejected": 3, "pending": 0,
                   "consecutive_rejected": 3},
             "b": {"proposed": 3, "merged": 1, "rejected": 2, "pending": 0,
                   "consecutive_rejected": 2}}
    table = kf.suppression(stats, now_beat=10)
    assert table == {"a": 10 + kf.SUPPRESS_BEATS}    # b stays free to propose


def test_should_emit_gate_lifts_after_window():
    table = {"a": 22}
    assert kf.should_emit("a", table, 21) is False   # the observer LEARNED to stop
    assert kf.should_emit("a", table, 22) is True    # ...for a season, not forever
    assert kf.should_emit("never-suppressed", table, 0) is True


def test_mood_events_mapping():
    outcomes = [{"proposalId": "p", "rule": "r", "outcome": "merged"},
                {"proposalId": "q", "rule": "r", "outcome": "rejected"},
                {"proposalId": "s", "rule": "r", "outcome": "pending"}]
    assert kf.mood_events(outcomes) == [":event/kaizen-merged", ":event/kaizen-rejected"]


def test_feedback_datoms_checkpoint_learning():
    stats = kf.fold([{"proposalId": "p", "rule": "error-rate"}],
                    [{"proposalId": "p", "rule": "error-rate", "outcome": "merged"}])
    ds = kf.feedback_datoms(stats, {}, beat=2, as_of=2606100002)
    attrs = {d[2] for d in ds}
    assert ":kaizen.rule/merged" in attrs and ":kaizen.rule/suppressed-until-beat" in attrs
    assert all(d[0] == ":db/add" for d in ds)


def test_loop_closes_deterministically():
    """The Gap-7 closure: same proposals + same outcomes → same learned state, replayable
    from the log at any later time."""
    proposals = [{"proposalId": f"p{i}", "rule": "flap"} for i in range(3)]
    outcomes = [{"proposalId": f"p{i}", "rule": "flap", "outcome": "rejected"}
                for i in range(3)]
    t1 = kf.suppression(kf.fold(proposals, outcomes), now_beat=5)
    t2 = kf.suppression(kf.fold(proposals, outcomes), now_beat=5)
    assert t1 == t2 == {"flap": 5 + kf.SUPPRESS_BEATS}


if __name__ == "__main__":
    run("kaizen_feedback", [(n, f) for n, f in sorted(globals().items())
                            if n.startswith("test_") and callable(f)])
