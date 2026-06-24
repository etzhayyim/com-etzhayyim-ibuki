"""test_quorum.py — 息吹 (ibuki) quorum sensing: emergent collective behaviour. ADR-2606101200."""
from __future__ import annotations

import pathlib
import tempfile

import autorun
import datoms
import quorum
from _t import run


def test_below_min_is_neutral():
    assert quorum.phenotype({"a": "joyful", "b": "grateful"})["state"] == ":neutral"


def test_flourishing_quorum():
    moods = {"a": "joyful", "b": "grateful", "c": "joyful", "d": "neutral"}
    ph = quorum.phenotype(moods)        # 3/4 flourishing ≥ ceil(4*2/3)=3
    assert ph["state"] == ":flourishing" and ph["flourish"] == 3


def test_dormant_quorum():
    moods = {"a": "stressed", "b": "stressed", "c": "stressed"}
    assert quorum.phenotype(moods)["state"] == ":dormant"


def test_no_quorum_is_neutral():
    moods = {"a": "joyful", "b": "stressed", "c": "neutral", "d": "calm"}
    assert quorum.phenotype(moods)["state"] == ":neutral"


def test_fruiting_emits_collective_commons():
    moods = {"a": "joyful", "b": "grateful", "c": "joyful"}
    out = quorum.sense(moods, beat_commons_nutrient=40, beat=2, as_of=2606100002)
    assert out["state"] == ":flourishing"
    assert out["fruiting_nutrient"] == 40 * quorum.FRUIT_BONUS_NUM // quorum.FRUIT_BONUS_DEN
    src = [d[3] for d in out["datoms"] if d[2] == ":metabolite/source"]
    commons = [d[3] for d in out["datoms"] if d[2] == ":metabolite/commons"]
    assert src == [":fruiting"] and commons == [True]   # collective gift to humanity


def test_fruiting_is_bounded_fraction():
    out = quorum.sense({"a": "joyful", "b": "joyful", "c": "grateful"},
                       beat_commons_nutrient=100, beat=1, as_of=1)
    assert out["fruiting_nutrient"] == 25 < 100         # bounded, cannot run away


def test_dormant_emits_no_fruiting():
    out = quorum.sense({"a": "stressed", "b": "stressed", "c": "stressed"},
                       beat_commons_nutrient=40, beat=1, as_of=1)
    assert out["state"] == ":dormant" and out["fruiting_nutrient"] == 0
    assert not any(d[2] == ":metabolite/kind" for d in out["datoms"])


def test_quorum_datoms_append_only_and_aggregate():
    out = quorum.sense({"a": "joyful", "b": "joyful", "c": "grateful"}, 40, beat=1, as_of=1)
    assert all(d[0] == ":db/add" for d in out["datoms"])
    # colony-level entity only, no per-organism quorum verdict
    qids = {d[1] for d in out["datoms"] if d[2].startswith(":quorum/")}
    assert qids == {"quorum-1"}


def test_deterministic():
    a = quorum.sense({"a": "joyful", "b": "grateful", "c": "joyful"}, 40, beat=3, as_of=3)
    b = quorum.sense({"a": "joyful", "b": "grateful", "c": "joyful"}, 40, beat=3, as_of=3)
    assert a["datoms"] == b["datoms"]


# ── end-to-end on the autonomous loop ───────────────────────────────────────


def test_autorun_records_quorum_history():
    with tempfile.TemporaryDirectory() as dr:
        log = pathlib.Path(dr) / "log.edn"
        autorun.autorun(20, fresh=True, log_path=log,
                        queue_path=pathlib.Path(dr) / "q.ndjson")
        txs = datoms.read_log(log)
        hist = quorum.quorum_history(txs)
        assert sum(hist["states"].values()) == 20      # one phenotype per beat
        assert set(hist["states"]) <= set(quorum.STATES)


def test_fruiting_feeds_the_symbiosis_pool():
    """When the colony fruits, the collective commons burst lands in the symbiosis pool —
    the colony's thriving becomes an extra gift to humanity (共生)."""
    import symbiosis as sym
    # a deterministic seed colony whose moods reach a flourishing quorum at some beat:
    with tempfile.TemporaryDirectory() as dr:
        log = pathlib.Path(dr) / "log.edn"
        autorun.autorun(20, fresh=True, log_path=log,
                        queue_path=pathlib.Path(dr) / "q.ndjson")
        txs = datoms.read_log(log)
        hist = quorum.quorum_history(txs)
        pool = sym.commons_pool(txs)
        if hist["states"].get(":flourishing", 0) > 0:
            assert hist["fruiting_nutrient_total"] > 0
            # the fruiting nutrient is part of the offered pool
            assert pool["offered"] >= hist["fruiting_nutrient_total"]
        else:
            assert hist["fruiting_nutrient_total"] == 0   # honest: no fruit, no bonus


if __name__ == "__main__":
    run("quorum", [(n, f) for n, f in sorted(globals().items())
                   if n.startswith("test_") and callable(f)])
