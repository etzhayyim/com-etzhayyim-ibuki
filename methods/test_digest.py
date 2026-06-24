"""test_digest.py — 息吹 (ibuki) colony digest: the colony reasons + reports. ADR-2606101800."""
from __future__ import annotations

import os
import pathlib
import tempfile

import autorun
import datoms
import digest
from _t import run


def _autorun(dr, cycles):
    log = pathlib.Path(dr) / "log.edn"
    autorun.autorun(cycles, fresh=True, log_path=log,
                    queue_path=pathlib.Path(dr) / "q.ndjson")
    return datoms.read_log(log)


def test_assemble_is_log_derived_and_complete():
    with tempfile.TemporaryDirectory() as dr:
        st = digest.assemble(_autorun(dr, 12))
        for k in ("organisms", "healthy", "eco_maturity", "commons_offered",
                  "commons_available", "quorum_states", "findings"):
            assert k in st
        assert st["organisms"] == 3 and st["commons_offered"] > 0


def test_template_digest_is_mirror_no_advice():
    st = digest.assemble([])
    text = digest.template_digest(st)
    assert "息吹" in text and "no advice" in text


def test_narrate_offline_uses_template():
    os.environ.pop("IBUKI_MURAKUMO_LIVE", None)
    with tempfile.TemporaryDirectory() as dr:
        st = digest.assemble(_autorun(dr, 12))
        n = digest.narrate(st, beat=12)
        assert n["via"] == "template" and "息吹" in n["text"]


def test_digest_datoms_dry_run_only():
    st = digest.assemble([])
    ds = digest.digest_datoms(st, {"text": "x", "via": "template"}, beat=1, as_of=1)
    statuses = [d[3] for d in ds if d[2] == ":digest/status"]
    assert statuses == [":dry-run"]                      # G8: :published unrepresentable
    assert all(d[0] == ":db/add" for d in ds)
    # aggregate colony entity only — no per-organism verdict
    assert {d[1] for d in ds} == {"digest-1"}


def test_make_deterministic_offline():
    os.environ.pop("IBUKI_MURAKUMO_LIVE", None)
    with tempfile.TemporaryDirectory() as d1, tempfile.TemporaryDirectory() as d2:
        a = digest.make(_autorun(d1, 12), beat=12, as_of=2606100012)
        b = digest.make(_autorun(d2, 12), beat=12, as_of=2606100012)
        assert a["datoms"] == b["datoms"]


def test_autorun_emits_digest_at_health_cadence():
    with tempfile.TemporaryDirectory() as dr:
        txs = _autorun(dr, 20)
        digests = [d for tx in txs for d in tx[":tx/datoms"] if d[2] == ":digest/text"]
        beats = sorted(d[3] for tx in txs for d in tx[":tx/datoms"]
                       if d[2] == ":digest/beat")
        assert beats == [10, 20]                          # every HEALTH_EVERY beats
        assert digests and all(isinstance(d[3], str) for d in digests)


def test_digest_reports_commons_offered_to_humanity():
    with tempfile.TemporaryDirectory() as dr:
        txs = _autorun(dr, 20)
        offered = [d[3] for tx in txs for d in tx[":tx/datoms"]
                   if d[2] == ":digest/commons-offered"]
        assert offered and offered[-1] > 0                # the gift is reported, growing


def test_digest_via_is_murakumo_or_template_only():
    with tempfile.TemporaryDirectory() as dr:
        txs = _autorun(dr, 10)
        vias = {d[3] for tx in txs for d in tx[":tx/datoms"] if d[2] == ":digest/via"}
        assert vias <= {":template", ":murakumo"}


if __name__ == "__main__":
    run("digest", [(n, f) for n, f in sorted(globals().items())
                   if n.startswith("test_") and callable(f)])
