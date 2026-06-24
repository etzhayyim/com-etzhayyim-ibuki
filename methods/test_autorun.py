"""test_autorun.py — 息吹 (ibuki) autonomous heartbeat loop. ADR-2606101200."""
from __future__ import annotations

import pathlib
import tempfile

import autorun
import datoms
import joucho
from _t import run


def _run(dr, cycles, fresh):
    log = pathlib.Path(dr) / "log.edn"
    q = pathlib.Path(dr) / "queue.ndjson"
    return autorun.autorun(cycles, fresh=fresh, log_path=log, queue_path=q), log, q


def test_three_beats_chain_verifies():
    with tempfile.TemporaryDirectory() as dr:
        res, log, _ = _run(dr, 3, True)
        assert res["beats"] == 3 and res["chain"]["ok"] is True
        assert res["head"].startswith("b")


def test_deterministic_same_head_cid():
    with tempfile.TemporaryDirectory() as d1, tempfile.TemporaryDirectory() as d2:
        r1, _, _ = _run(d1, 3, True)
        r2, _, _ = _run(d2, 3, True)
        assert r1["head"] == r2["head"]              # same seed + cycles → same head


def test_crash_resume_equals_uninterrupted_run():
    """The Gap-1/2 closure, end-to-end: 2 beats, process 'dies', 1 more beat — the chain is
    byte-identical to an uninterrupted 3-beat life (nothing lived only in RAM)."""
    with tempfile.TemporaryDirectory() as d1, tempfile.TemporaryDirectory() as d2:
        _run(d1, 2, True)
        resumed, log1, _ = _run(d1, 1, False)        # a brand-new process picks up the log
        straight, _, _ = _run(d2, 3, True)
        assert resumed["head"] == straight["head"]


def test_every_post_is_dry_run():
    with tempfile.TemporaryDirectory() as dr:
        _, log, _ = _run(dr, 4, True)
        txs = datoms.read_log(log)
        statuses = [d[3] for tx in txs for d in tx[":tx/datoms"] if d[2] == ":post/status"]
        assert statuses and set(statuses) == {":dry-run"}    # G8


def test_queue_lines_match_adr_2605240100_schema():
    import json
    with tempfile.TemporaryDirectory() as dr:
        _, _, q = _run(dr, 2, True)
        lines = [json.loads(x) for x in q.read_text(encoding="utf-8").splitlines() if x]
        assert lines
        for ln in lines:
            assert ln["v"] == 1
            for k in ("ts", "actorDid", "code", "title", "mood", "contentSourceKind",
                      "text", "lexicon", "createdAt"):
                assert k in ln


def test_drain_prepares_member_sign_envelopes():
    with tempfile.TemporaryDirectory() as dr:
        _, log, _ = _run(dr, 2, True)
        txs = datoms.read_log(log)
        held = [d[3] for tx in txs for d in tx[":tx/datoms"]
                if d[2] == ":drain/server-held-key"]
        sigs = [d[3] for tx in txs for d in tx[":tx/datoms"]
                if d[2] == ":drain/requires-member-sig"]
        assert held and set(held) == {False} and set(sigs) == {True}   # G7


def test_mood_is_as_of_queryable_from_log():
    """The Gap-3/4 closure, end-to-end: 'what was the organism's mood at tx N' is answered
    purely from the log — and the lived history actually moves it off the stub default."""
    with tempfile.TemporaryDirectory() as dr:
        res, log, _ = _run(dr, 6, True)
        txs = datoms.read_log(log)
        code = "10101500"
        base = joucho.personality_baseline(code)
        early = joucho.replay_events(base, datoms.events_for(txs, code, up_to_tx=1))
        late = joucho.replay_events(base, datoms.events_for(txs, code, up_to_tx=res["beats"]))
        assert datoms.events_for(txs, code, up_to_tx=1) != datoms.events_for(txs, code)
        assert (early.as_dict(), joucho.determine_mood(early)) is not None
        assert late.as_dict() != base.as_dict()      # the organism grew (縁起)


def test_heartbeat_state_durable_across_beats():
    import heartbeat
    with tempfile.TemporaryDirectory() as dr:
        _, log, _ = _run(dr, 5, True)
        txs = datoms.read_log(log)
        st = heartbeat.replay(txs, "10101500")
        assert st.beats == 5 and st.posts >= 1 and st.last_post_at_ms >= 0


def test_narration_offline_via_template():
    with tempfile.TemporaryDirectory() as dr:
        _, log, _ = _run(dr, 2, True)
        txs = datoms.read_log(log)
        vias = [d[3] for tx in txs for d in tx[":tx/datoms"] if d[2] == ":post/via"]
        assert vias and set(vias) <= {":template", ":murakumo"}   # Murakumo-only or offline


def test_event_entities_never_shadowed():
    """Regression (2026-06-10 health audit): the post-emitted event used to reuse
    jev-{code}-{beat}-0 and SHADOW that beat's idle event in the as-of fold — losing
    homeostasis drift on every posting beat (stress crept up until an organism went
    permanently mute). Every event entity must carry exactly ONE kind assertion."""
    import collections
    with tempfile.TemporaryDirectory() as dr:
        _, log, _ = _run(dr, 10, True)
        kinds = collections.Counter()
        for tx in datoms.read_log(log):
            for _op, e, a, _v in tx[":tx/datoms"]:
                if a == ":joucho.event/kind":
                    kinds[e] += 1
        assert kinds and max(kinds.values()) == 1


def test_checkpointed_joucho_equals_as_of_replay():
    """The :joucho/* checkpoint written by the beat and the pure as-of replay of the
    event log must agree — two views of one history, never two histories."""
    with tempfile.TemporaryDirectory() as dr:
        res, log, _ = _run(dr, 7, True)
        txs = datoms.read_log(log)
        for code in ("10101500", "14111500", "50221000"):
            base = joucho.personality_baseline(code)
            replayed = joucho.replay_events(base, datoms.events_for(txs, code))
            ck = datoms.fold_entity(txs, f"joucho-{code}-{res['beats']}")
            for axis, v in replayed.as_dict().items():
                assert ck[f":joucho/{axis}"] == v, (code, axis, ck, replayed)


def test_homeostasis_holds_over_a_long_life():
    """健全な成長: with drift intact, stress stays inside the designed equilibrium band
    (baseline .. baseline+4 under the representative pattern) — no organism drifts into
    permanent stressed muteness from ordinary life."""
    with tempfile.TemporaryDirectory() as dr:
        _, log, _ = _run(dr, 60, True)
        txs = datoms.read_log(log)
        for code in ("10101500", "14111500", "50221000"):
            base = joucho.personality_baseline(code)
            head = joucho.replay_events(base, datoms.events_for(txs, code))
            assert head.stress <= base.stress + 4, (code, base.stress, head.stress)
            assert joucho.determine_mood(head) != "stressed"


if __name__ == "__main__":
    run("autorun", [(n, f) for n, f in sorted(globals().items())
                    if n.startswith("test_") and callable(f)])
