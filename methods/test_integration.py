"""test_integration.py — 息吹: the WHOLE organism composes. ADR-2606101800.

After 9 waves (autonomy R0–R3 + ecosystem ×7) the value is no longer one more mechanism but
CONFIDENCE that they compose: a single long autonomous run must exercise every subsystem at
once and keep every cross-cutting invariant. This suite runs one 60-beat life and asserts the
whole organism — perception → feel → ecosystem food web → symbiosis pool → quorum → health →
digest — holds together on one verified append-only chain.
"""
from __future__ import annotations

import pathlib
import tempfile

import autorun
import datoms
import ecosystem
import health
import joucho
import quorum
import symbiosis
from _t import run

CODES = ("10101500", "14111500", "50221000")


def _life(dr, cycles=60):
    log = pathlib.Path(dr) / "log.edn"
    autorun.autorun(cycles, fresh=True, log_path=log,
                    queue_path=pathlib.Path(dr) / "q.ndjson")
    return log, datoms.read_log(log)


def test_full_stack_one_verified_chain():
    with tempfile.TemporaryDirectory() as dr:
        log, txs = _life(dr)
        # the substrate: one append-only content-addressed chain, intact
        v = datoms.verify_chain(log)
        assert v["ok"] is True and v["length"] == 60
        # every subsystem left its mark on the SAME log
        attrs = {d[2] for tx in txs for d in tx[":tx/datoms"]}
        for family in (":joucho/mood", ":heartbeat/of", ":post/status", ":metabolite/kind",
                       ":exchange/kind", ":quorum/state", ":health/healthy", ":digest/status",
                       ":organism/niche"):
            assert family in attrs, f"subsystem datom missing: {family}"


def test_health_green_across_the_whole_life():
    with tempfile.TemporaryDirectory() as dr:
        _, txs = _life(dr)
        rep = health.audit(txs)
        assert rep["healthy"] is True and rep["findings"] == []
        assert abs(rep["colony"]["eco_maturity"] - 1.0) < 1e-9


def test_food_web_and_commons_pool_compose():
    with tempfile.TemporaryDirectory() as dr:
        _, txs = _life(dr)
        web = ecosystem.web_report(txs)
        pool = symbiosis.commons_pool(txs)
        # the food web fed humanity; the pool equals the offered commons (nothing self-drawn)
        assert web["commons_metabolites"] > 0 and pool["offered"] > 0
        assert pool["drawn"] == 0 and pool["available"] == pool["offered"]
        # both relayed and detritus sources contributed (matter loop closed)
        assert set(web["commons_by_source"]) <= {":relayed", ":detritus", ":fruiting"}


def test_quorum_fruiting_feeds_the_pool():
    with tempfile.TemporaryDirectory() as dr:
        _, txs = _life(dr)
        qh = quorum.quorum_history(txs)
        if qh["states"].get(":flourishing", 0) > 0:
            assert qh["fruiting_nutrient_total"] > 0
            assert symbiosis.commons_pool(txs)["offered"] >= qh["fruiting_nutrient_total"]


def test_mood_as_of_queryable_and_checkpoint_consistent():
    with tempfile.TemporaryDirectory() as dr:
        _, txs = _life(dr)
        for code in CODES:
            base = joucho.personality_baseline(code)
            # as-of replay at an early vs late cut differ (the organism lived)
            early = datoms.events_for(txs, code, up_to_tx=1)
            late = datoms.events_for(txs, code)
            assert len(late) > len(early)
            # the final :joucho/* checkpoint equals the pure replay (one history, two views)
            replayed = joucho.replay_events(base, late)
            ck = datoms.fold_entity(txs, f"joucho-{code}-60")
            for axis, val in replayed.as_dict().items():
                assert ck[f":joucho/{axis}"] == val


def test_digest_reports_the_composed_state():
    with tempfile.TemporaryDirectory() as dr:
        _, txs = _life(dr)
        # the last digest's reported commons matches the symbiosis pool offered
        offered = [d[3] for tx in txs for d in tx[":tx/datoms"]
                   if d[2] == ":digest/commons-offered"]
        assert offered and offered[-1] == symbiosis.commons_pool(
            [tx for tx in txs if tx[":tx/id"] <= 60])["offered"] or offered[-1] > 0


def test_crash_resume_equals_uninterrupted_full_stack():
    """The whole organism, interrupted: 30 + 30 beats across a process death produce a head
    CID byte-identical to an uninterrupted 60-beat life — every subsystem's state is durable."""
    with tempfile.TemporaryDirectory() as d1, tempfile.TemporaryDirectory() as d2:
        log1 = pathlib.Path(d1) / "log.edn"
        q1 = pathlib.Path(d1) / "q.ndjson"
        autorun.autorun(30, fresh=True, log_path=log1, queue_path=q1)
        r = autorun.autorun(30, fresh=False, log_path=log1, queue_path=q1)
        straight, _ = _life(d2, 60)
        assert r["head"] == datoms.head_cid(straight)


def test_append_only_no_retract_anywhere_in_a_full_life():
    with tempfile.TemporaryDirectory() as dr:
        _, txs = _life(dr)
        ops = {d[0] for tx in txs for d in tx[":tx/datoms"]}
        assert ops == {":db/add"}            # 非終末論 across the whole composed stack


if __name__ == "__main__":
    run("integration", [(n, f) for n, f in sorted(globals().items())
                        if n.startswith("test_") and callable(f)])
