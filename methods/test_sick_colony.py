"""test_sick_colony.py — adversarial: a BROKEN colony detects + reports its own ill-health.

ADR-2606101800. The self-monitoring claims (health.py audit, digest report, kaizen feedback)
are only worth anything if the loop CLOSES under failure. This suite induces a pathology — a
colony with NO decomposer niche, so the food web cannot close — runs it end-to-end through
autorun, and asserts the whole self-monitoring stack catches and reports it:

  health.audit flags keystone-niche-absent (+ ecosystem-starved past the grace window) →
  the colony is NOT healthy → the digest reports the unhealthy state → health proposals
  carry the complaint to the Wave-4 kaizen loop.

Contrast with the healthy seed (producer+router+decomposer), which stays green.
"""
from __future__ import annotations

import pathlib
import tempfile

import autorun
import datoms
import digest
import health
from _t import run

SICK_SEED = """;; deliberately broken colony — NO decomposer niche, the web cannot close.
{:seed/kind :representative
 :seed/organisms
 [{:organism/code "10101500" :organism/title "Producer A"
   :organism/did "did:web:etzhayyim.com:actor:sick-a" :organism/niche :niche/producer}
  {:organism/code "14111500" :organism/title "Producer B"
   :organism/did "did:web:etzhayyim.com:actor:sick-b" :organism/niche :niche/producer}
  {:organism/code "50221000" :organism/title "Router C"
   :organism/did "did:web:etzhayyim.com:actor:sick-c" :organism/niche :niche/router}]}
"""


def _run_sick(dr, cycles=12):
    seed = pathlib.Path(dr) / "sick-seed.edn"
    seed.write_text(SICK_SEED, encoding="utf-8")
    saved = autorun.SEED
    autorun.SEED = seed
    try:
        log = pathlib.Path(dr) / "log.edn"
        autorun.autorun(cycles, fresh=True, log_path=log,
                        queue_path=pathlib.Path(dr) / "q.ndjson")
        return log, datoms.read_log(log)
    finally:
        autorun.SEED = saved


def test_sick_colony_chain_still_verifies():
    """Even broken, the substrate is sound — a pathology is DATA, not corruption."""
    with tempfile.TemporaryDirectory() as dr:
        log, _ = _run_sick(dr)
        assert datoms.verify_chain(log)["ok"] is True


def test_health_flags_keystone_absent():
    with tempfile.TemporaryDirectory() as dr:
        _, txs = _run_sick(dr)
        rep = health.audit(txs)
        rules = {f["rule"] for f in rep["findings"]}
        assert "keystone-niche-absent" in rules        # no decomposer → web cannot close
        assert rep["healthy"] is False


def test_no_commons_reaches_humanity():
    """No カビ → no refining → the symbiosis offer is zero (the gift never forms)."""
    import symbiosis
    with tempfile.TemporaryDirectory() as dr:
        _, txs = _run_sick(dr)
        assert symbiosis.commons_pool(txs)["offered"] == 0
        # past the grace window this is also surfaced as ecosystem-starved
        assert "ecosystem-starved" in {f["rule"] for f in health.audit(txs)["findings"]}


def test_digest_reports_the_illness():
    with tempfile.TemporaryDirectory() as dr:
        _, txs = _run_sick(dr)
        state = digest.assemble(txs)
        assert state["healthy"] is False
        assert "keystone-niche-absent" in state["findings"]
        text = digest.template_digest(state)
        assert "attending to" in text                  # the report names the illness honestly
        # the on-log digest checkpoint also carries the unhealthy verdict
        healthy_flags = [d[3] for tx in txs for d in tx[":tx/datoms"]
                         if d[2] == ":digest/healthy"]
        assert healthy_flags and healthy_flags[-1] is False


def test_health_proposals_carry_the_complaint_to_kaizen():
    import kaizen_feedback as kf
    with tempfile.TemporaryDirectory() as dr:
        _, txs = _run_sick(dr)
        rep = health.audit(txs)
        p = pathlib.Path(dr) / "health-proposals.ndjson"
        n = health.write_proposals(rep, p)
        assert n > 0
        proposals = kf.read_proposals(p)
        rules = {pr["rule"] for pr in proposals}
        assert "keystone-niche-absent" in rules        # the Wave-4 loop will carry it to humans


def test_healthy_seed_stays_green_by_contrast():
    """Control: the real seed (producer+router+decomposer) shows none of the above."""
    with tempfile.TemporaryDirectory() as dr:
        log = pathlib.Path(dr) / "log.edn"
        autorun.autorun(12, fresh=True, log_path=log,
                        queue_path=pathlib.Path(dr) / "q.ndjson")
        rep = health.audit(datoms.read_log(log))
        assert rep["healthy"] is True
        assert "keystone-niche-absent" not in {f["rule"] for f in rep["findings"]}


if __name__ == "__main__":
    run("sick_colony", [(n, f) for n, f in sorted(globals().items())
                        if n.startswith("test_") and callable(f)])
