"""test_health.py — 息吹 (ibuki) colony 健全性 audit. ADR-2606101200 §健全化."""
from __future__ import annotations

import pathlib
import tempfile

import autorun
import datoms
import health
import joucho
import kaizen_feedback as kf
from _t import run


def _autorun(dr, cycles):
    log = pathlib.Path(dr) / "log.edn"
    autorun.autorun(cycles, fresh=True, log_path=log,
                    queue_path=pathlib.Path(dr) / "q.ndjson")
    return datoms.read_log(log)


def _synthetic_life(code: str, kinds_per_beat: list[list[str]]) -> list[dict]:
    """Hand-built log: one tx per beat carrying the given event kinds for one organism."""
    txs, prev = [], ""
    for i, kinds in enumerate(kinds_per_beat, start=1):
        body = joucho.event_datoms(code, kinds, beat=i, as_of=2606100000 + i)
        tx = datoms.make_tx(body, tx_id=i, as_of=2606100000 + i, prev_cid=prev)
        prev = tx[":tx/cid"]
        txs.append(tx)
    return txs


def test_healthy_colony_audits_healthy():
    with tempfile.TemporaryDirectory() as dr:
        rep = health.audit(_autorun(dr, 30))
        assert rep["healthy"] is True and rep["findings"] == []
        assert rep["colony"]["count"] == 3
        assert len(rep["colony"]["mood_diversity"]) == 3       # no monoculture


def test_muted_organism_detected():
    """A kaizen-rejection flood drives stress past 70 and keeps it there — the audit must
    name the muted organism (the exact pathology the shadowing bug caused)."""
    txs = _synthetic_life("10101500", [[":event/kaizen-rejected"]] * 20)
    rep = health.audit(txs)
    org = rep["organisms"]["10101500"]
    assert org["mood"] == "stressed" and org["muted"] is True
    rules = {f["rule"] for f in rep["findings"]}
    assert "organism-muted" in rules and "stress-excess" in rules
    assert rep["healthy"] is False


def test_muteness_is_recoverable_by_drift():
    """構造保証: baseline stress ≤65 + idle drift ⇒ muteness heals. After the flood stops,
    idle beats pull stress back under the threshold and the audit goes quiet."""
    life = [[":event/kaizen-rejected"]] * 20 + [[":event/idle"]] * 40
    rep = health.audit(_synthetic_life("10101500", life))
    org = rep["organisms"]["10101500"]
    assert org["mood"] != "stressed" and org["muted"] is False
    assert org["stress_excess"] == 0


def test_checkpoint_divergence_detected():
    """The bug's signature — a joucho checkpoint that disagrees with the as-of replay."""
    code = "10101500"
    body = joucho.event_datoms(code, [":event/idle"], beat=1, as_of=2606100001)
    wrong = joucho.JouchoScores(joy=1, calm=1, stress=1, gratitude=1, focus=1)
    body += joucho.joucho_datoms(code, wrong, "neutral", beat=1, as_of=2606100001)
    txs = [datoms.make_tx(body, tx_id=1, as_of=2606100001, prev_cid="")]
    rep = health.audit(txs)
    assert rep["organisms"][code]["checkpoint_diverged"] is True
    assert "checkpoint-divergence" in {f["rule"] for f in rep["findings"]}


def test_mood_monoculture_detected():
    """3+ organisms all in one mood = personality collapse — a colony-level finding."""
    txs = []
    prev = ""
    for i, code in enumerate(("10101500", "14111500", "50221000"), start=1):
        body = joucho.event_datoms(code, [":event/kaizen-rejected"] * 25,
                                   beat=1, as_of=2606100001)
        tx = datoms.make_tx(body, tx_id=i, as_of=2606100000 + i, prev_cid=prev)
        prev = tx[":tx/cid"]
        txs.append(tx)
    rep = health.audit(txs)
    assert "mood-monoculture" in {f["rule"] for f in rep["findings"]}


def test_ecosystem_starved_detected():
    """Primary production with no commons output past the grace period = broken food web."""
    code = "10101500"
    txs, prev = [], ""
    for i in range(1, health.ECO_GRACE_BEATS + 3):       # substrate every beat, never refined
        body = (joucho.event_datoms(code, [":event/idle"], beat=i, as_of=2606100000 + i)
                + [datoms.add(f"eco-sub-{code}-{i}", ":metabolite/kind", ":substrate"),
                   datoms.add(f"eco-sub-{code}-{i}", ":metabolite/beat", i)])
        tx = datoms.make_tx(body, tx_id=i, as_of=2606100000 + i, prev_cid=prev)
        prev = tx[":tx/cid"]
        txs.append(tx)
    rep = health.audit(txs)
    assert "ecosystem-starved" in {f["rule"] for f in rep["findings"]}


def test_complete_food_web_is_not_starved():
    """The wired autorun (producer->router->decomposer) feeds humanity -> never starved."""
    with tempfile.TemporaryDirectory() as dr:
        rep = health.audit(_autorun(dr, 30))
        assert "ecosystem-starved" not in {f["rule"] for f in rep["findings"]}
        assert rep["healthy"] is True


def _niche_log(*niches):
    """A minimal log asserting one organism per given niche (birth :organism/niche)."""
    body, prev, txs = [], "", []
    for i, nn in enumerate(niches):
        body = [datoms.add(f"org-c{i}", ":organism/niche", nn),
                datoms.add(f"org-c{i}", ":organism/born-beat", 1)]
        tx = datoms.make_tx(body, tx_id=i + 1, as_of=2606100000 + i, prev_cid=prev)
        prev = tx[":tx/cid"]
        txs.append(tx)
    return txs


def test_evenness_pielou():
    assert health._evenness({}) == 0.0
    assert health._evenness({":niche/producer": 5}) == 0.0          # one niche → 0
    assert abs(health._evenness({"a": 3, "b": 3, "c": 3}) - 1.0) < 1e-9   # perfectly even
    assert 0 < health._evenness({"a": 10, "b": 1, "c": 1}) < 0.7    # dominated → low


def test_keystone_niche_absent_detected():
    """A colony of producers + a router but NO decomposer → the web cannot close; the
    precise diagnosis behind a starved web."""
    rep = health.audit(_niche_log(":niche/producer", ":niche/producer", ":niche/router"))
    rules = {f["rule"] for f in rep["findings"]}
    assert "keystone-niche-absent" in rules
    assert rep["healthy"] is False


def test_niche_imbalance_detected():
    """All three niches present but wildly skewed (one role dominates) = fragile."""
    rep = health.audit(_niche_log(*([":niche/producer"] * 12
                                    + [":niche/router", ":niche/decomposer"])))
    assert "niche-imbalance" in {f["rule"] for f in rep["findings"]}
    # but a missing niche takes precedence (keystone) over imbalance — mutually exclusive
    assert "keystone-niche-absent" not in {f["rule"] for f in rep["findings"]}


def test_balanced_colony_is_resilient():
    rep = health.audit(_niche_log(":niche/producer", ":niche/router", ":niche/decomposer"))
    rules = {f["rule"] for f in rep["findings"]}
    assert "keystone-niche-absent" not in rules and "niche-imbalance" not in rules
    assert abs(rep["colony"]["eco_maturity"] - 1.0) < 1e-9   # perfectly even


def test_seed_colony_eco_maturity_logged():
    """The wired seed (1 producer/1 router/1 decomposer) is perfectly even → maturity 1.0,
    checkpointed on the log."""
    with tempfile.TemporaryDirectory() as dr:
        txs = _autorun(dr, 12)
        rep = health.audit(txs)
        assert abs(rep["colony"]["eco_maturity"] - 1.0) < 1e-9
        assert "keystone-niche-absent" not in {f["rule"] for f in rep["findings"]}
        mats = [d[3] for tx in txs for d in tx[":tx/datoms"] if d[2] == ":health/eco-maturity"]
        assert mats and mats[-1] == 1.0       # rounded to 4dp at checkpoint → exactly 1.0


def test_health_datoms_checkpoint_shape():
    with tempfile.TemporaryDirectory() as dr:
        rep = health.audit(_autorun(dr, 12))
        ds = health.health_datoms(rep, beat=12, as_of=2606100012)
        attrs = {d[2] for d in ds}
        assert {":health/healthy", ":health/findings", ":health/mood-kinds"} <= attrs
        assert all(d[0] == ":db/add" for d in ds)
        # no per-organism wellbeing score is ever asserted (edge-primary)
        assert not any("score" in d[2] for d in ds)


def test_autorun_checkpoints_health_every_10_beats():
    with tempfile.TemporaryDirectory() as dr:
        txs = _autorun(dr, 20)
        healthy_flags = [(tx[":tx/id"], d[3]) for tx in txs for d in tx[":tx/datoms"]
                         if d[2] == ":health/healthy"]
        assert [b for b, _ in healthy_flags] == [10, 20]
        assert all(v is True for _, v in healthy_flags)        # the fixed loop stays healthy


def test_findings_feed_the_kaizen_loop():
    """Pathologies are KaizenProposal-shaped: the colony's Wave-4 loop can carry its own
    health complaints to humans."""
    txs = _synthetic_life("10101500", [[":event/kaizen-rejected"]] * 20)
    rep = health.audit(txs)
    with tempfile.TemporaryDirectory() as dr:
        p = pathlib.Path(dr) / "proposals.ndjson"
        n = health.write_proposals(rep, p)
        assert n == len(rep["findings"]) > 0
        proposals = kf.read_proposals(p)
        assert {pr["rule"] for pr in proposals} <= set(health.RULES)


def test_audit_deterministic():
    with tempfile.TemporaryDirectory() as d1, tempfile.TemporaryDirectory() as d2:
        assert health.audit(_autorun(d1, 15)) == health.audit(_autorun(d2, 15))


if __name__ == "__main__":
    run("health", [(n, f) for n, f in sorted(globals().items())
                   if n.startswith("test_") and callable(f)])
