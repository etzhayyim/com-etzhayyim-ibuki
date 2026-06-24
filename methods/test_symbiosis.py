"""test_symbiosis.py — 息吹 (ibuki) the 共生 ledger: humanity draws the commons. ADR-2606101200."""
from __future__ import annotations

import pathlib
import tempfile

import autorun
import datoms
import symbiosis as sym
from drainer import MemberSignatureRequired
from _t import expect_raises, run

MEMBER = "did:web:etzhayyim.com:member:alice"


def _commons_log(*nutrients):
    """A log offering one commons metabolite per given nutrient value."""
    txs, prev = [], ""
    for i, n in enumerate(nutrients, start=1):
        e = f"eco-refined-d-{i}"
        body = [datoms.add(e, ":metabolite/kind", ":refined"),
                datoms.add(e, ":metabolite/commons", True),
                datoms.add(e, ":metabolite/nutrient", n),
                datoms.add(e, ":metabolite/beat", i)]
        tx = datoms.make_tx(body, tx_id=i, as_of=2606100000 + i, prev_cid=prev)
        prev = tx[":tx/cid"]
        txs.append(tx)
    return txs


def _signer(calls):
    def sign(claim):
        calls.append(claim)
        return {"signedBy": "member", "claim": claim["kind"]}
    return sign


def test_pool_offered_drawn_available():
    txs = _commons_log(10, 20, 30)
    pool = sym.commons_pool(txs)
    assert pool == {"offered": 60, "drawn": 0, "available": 60}


def test_only_commons_refined_counts_offered():
    # a non-commons refined + a substrate must NOT count toward the offer
    txs = [datoms.make_tx([
        datoms.add("eco-refined-x", ":metabolite/kind", ":refined"),
        datoms.add("eco-refined-x", ":metabolite/commons", True),
        datoms.add("eco-refined-x", ":metabolite/nutrient", 40),
        datoms.add("eco-sub-p", ":metabolite/kind", ":substrate"),
        datoms.add("eco-sub-p", ":metabolite/nutrient", 99)],
        tx_id=1, as_of=1, prev_cid="")]
    assert sym.commons_pool(txs)["offered"] == 40


def test_draw_without_signer_refused():
    txs = _commons_log(50)
    expect_raises(lambda: sym.draw(txs, 10, member=MEMBER, beat=2, as_of=2),
                  contains="no member signer")


def test_draw_without_operator_ack_refused():
    txs = _commons_log(50)
    expect_raises(lambda: sym.draw(txs, 10, member=MEMBER, beat=2, as_of=2,
                                   member_signer=_signer([])),
                  contains="operator_ack")


def test_draw_cannot_exceed_pool():
    txs = _commons_log(30)
    expect_raises(lambda: sym.draw(txs, 31, member=MEMBER, beat=2, as_of=2,
                                   member_signer=_signer([]), operator_ack=True),
                  contains="exceeds available")


def test_draw_must_be_positive():
    txs = _commons_log(30)
    expect_raises(lambda: sym.draw(txs, 0, member=MEMBER, beat=2, as_of=2,
                                   member_signer=_signer([]), operator_ack=True),
                  contains="positive")


def test_member_draw_is_attributed_and_signed():
    txs = _commons_log(50)
    calls = []
    out = sym.draw(txs, 20, member=MEMBER, beat=3, as_of=2606100003,
                   member_signer=_signer(calls), operator_ack=True)
    assert out["available_after"] == 30
    assert calls and calls[0]["member"] == MEMBER          # the MEMBER signed the draw
    by = [d[3] for d in out["datoms"] if d[2] == ":symbiosis/by"]
    drawn_by_member = [d[3] for d in out["datoms"] if d[2] == ":symbiosis/drawn-by-member"]
    assert by == [MEMBER] and drawn_by_member == [True]    # never platform-drawn
    assert all(d[0] == ":db/add" for d in out["datoms"])


def test_draw_depletes_pool_on_the_log():
    txs = _commons_log(50)
    out = sym.draw(txs, 20, member=MEMBER, beat=2, as_of=2,
                   member_signer=_signer([]), operator_ack=True)
    # append the draw datoms as a new tx, re-read pool
    tx = datoms.make_tx(out["datoms"], tx_id=99, as_of=99,
                        prev_cid=txs[-1][":tx/cid"])
    assert sym.commons_pool(txs + [tx]) == {"offered": 50, "drawn": 20, "available": 30}


def test_autonomous_loop_offers_but_never_self_draws():
    """The colony PRODUCES commons every beat (offer grows) but ibuki never draws — the
    pool stands available to humanity, undrawn, until a member takes it."""
    with tempfile.TemporaryDirectory() as dr:
        log = pathlib.Path(dr) / "log.edn"
        autorun.autorun(20, fresh=True, log_path=log,
                        queue_path=pathlib.Path(dr) / "q.ndjson")
        pool = sym.commons_pool(datoms.read_log(log))
        assert pool["offered"] > 0 and pool["drawn"] == 0     # never self-drawn
        assert pool["available"] == pool["offered"]


def test_module_holds_no_key():
    src = pathlib.Path(sym.__file__).read_text(encoding="utf-8")
    for needle in ("password", "accessJwt", "Authorization", "urllib", "PRIVATE_KEY"):
        assert needle not in src, f"symbiosis must stay key-free + offline: {needle}"


if __name__ == "__main__":
    run("symbiosis", [(n, f) for n, f in sorted(globals().items())
                      if n.startswith("test_") and callable(f)])
