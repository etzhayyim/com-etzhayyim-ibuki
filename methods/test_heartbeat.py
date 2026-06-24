"""test_heartbeat.py — 息吹 (ibuki) durable heartbeat cadence. ADR-2606101200."""
from __future__ import annotations

import datoms
import heartbeat
from _t import run


def _tx(body, tx_id, prev=""):
    return datoms.make_tx(body, tx_id=tx_id, as_of=2606100000 + tx_id, prev_cid=prev)


def test_fresh_state_never_posted():
    s = heartbeat.HeartbeatState()
    assert s.last_post_at_ms == -1 and s.beats == 0 and s.posts == 0


def test_first_post_is_due():
    due, reason = heartbeat.due_to_post(heartbeat.HeartbeatState(), "neutral", 0)
    assert due and reason == "first post"


def test_cooldown_blocks_then_elapses():
    s = heartbeat.HeartbeatState(last_post_at_ms=0)
    due, _ = heartbeat.due_to_post(s, "neutral", 60 * 60_000)         # 1h < 2h
    assert not due
    due, _ = heartbeat.due_to_post(s, "neutral", 121 * 60_000)        # >2h
    assert due


def test_mood_changes_cadence():
    s = heartbeat.HeartbeatState(last_post_at_ms=0)
    now = 45 * 60_000                                                  # 45 min
    assert heartbeat.due_to_post(s, "joyful", now)[0] is True          # 30m cooldown
    assert heartbeat.due_to_post(s, "neutral", now)[0] is False        # 2h cooldown


def test_stressed_never_posts():
    due, reason = heartbeat.due_to_post(heartbeat.HeartbeatState(), "stressed", 10**9)
    assert not due and "disabled" in reason


def test_checkpoint_replay_roundtrip():
    s = heartbeat.HeartbeatState(last_post_at_ms=90_000, beats=3, posts=2)
    txs = [_tx(heartbeat.checkpoint_datoms("c1", s, "calm", beat=3, as_of=2606100003), 1)]
    back = heartbeat.replay(txs, "c1")
    assert back == s


def test_replay_latest_checkpoint_wins():
    s1 = heartbeat.HeartbeatState(last_post_at_ms=10, beats=1, posts=1)
    s2 = heartbeat.HeartbeatState(last_post_at_ms=99, beats=2, posts=1)
    txs = [_tx(heartbeat.checkpoint_datoms("c1", s1, "calm", beat=1, as_of=1), 1),
           _tx(heartbeat.checkpoint_datoms("c1", s2, "calm", beat=2, as_of=2), 2)]
    assert heartbeat.replay(txs, "c1").last_post_at_ms == 99


def test_replay_isolated_per_organism():
    s1 = heartbeat.HeartbeatState(last_post_at_ms=10, beats=1, posts=1)
    txs = [_tx(heartbeat.checkpoint_datoms("c1", s1, "calm", beat=1, as_of=1), 1)]
    assert heartbeat.replay(txs, "c2") == heartbeat.HeartbeatState()   # untouched organism


def test_crash_resume_same_answer():
    """The Gap-1/2 closure: the due decision is a pure function of (log, mood, now) — a
    process restart (re-replay) cannot change it."""
    s = heartbeat.HeartbeatState(last_post_at_ms=0, beats=5, posts=3)
    txs = [_tx(heartbeat.checkpoint_datoms("c1", s, "focused", beat=5, as_of=5), 1)]
    before = heartbeat.due_to_post(heartbeat.replay(txs, "c1"), "focused", 100 * 60_000)
    after = heartbeat.due_to_post(heartbeat.replay(txs, "c1"), "focused", 100 * 60_000)
    assert before == after


if __name__ == "__main__":
    run("heartbeat", [(n, f) for n, f in sorted(globals().items())
                      if n.startswith("test_") and callable(f)])
