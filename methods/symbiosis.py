"""symbiosis.py — the 共生 ledger: humanity draws the colony's commons output. ADR-2606101200 §共生.

The founder's image is bidirectional: the colony LIVES and, as a byproduct (黒カビ→クエン酸),
excretes a refined commons metabolite — and HUMANITY consumes it in symbiosis. The food web
(ecosystem.py) builds the producing side: producer→粘菌→カビ→`:metabolite/commons`. This module
is the consuming side, kept honest:

  - `commons_pool(txs)` — the standing commons available to humanity = Σ offered commons
    metabolite nutrient − Σ already-drawn. Log-derived, deterministic. The colony's metabolic
    byproduct ACCUMULATES into a pool (like moyai 入会権 commons-draw-rights, ADR-2606062100).
  - `draw(...)` — a MEMBER draws from the pool. Member-principal + operator-gated, exactly the
    no-server-key discipline of member_submit (drainer.MemberSignatureRequired): no member
    signer + operator ack → refusal. ibuki NEVER auto-draws (the colony does not consume its
    own gift, and the platform cannot fabricate a human benefit). A draw cannot exceed the
    available pool. Records a `:symbiosis/draw` datom ATTRIBUTED to the drawing member.

So the symbiosis is real and measured (offer vs draw) yet un-fakeable: offers are the colony's
byproduct on the log; draws exist only when a member actually took the gift. Stdlib only.
Deterministic. Append-only.
"""

from __future__ import annotations

from drainer import MemberSignatureRequired


def commons_pool(txs: list[dict]) -> dict:
    """The standing commons pool (single pass, fleet-scale safe). Returns
    {offered, drawn, available} in nutrient units. offered = Σ commons metabolite nutrient;
    drawn = Σ :symbiosis/draw amount; available = offered − drawn (≥0)."""
    meta: dict[str, dict] = {}
    drawn = 0
    for tx in txs:
        for _op, e, a, v in tx.get(":tx/datoms", []):
            if a == ":metabolite/kind":
                meta.setdefault(e, {})["kind"] = v
            elif a == ":metabolite/commons":
                meta.setdefault(e, {})["commons"] = v
            elif a == ":metabolite/nutrient":
                meta.setdefault(e, {})["nutrient"] = v
            elif a == ":symbiosis/amount":
                drawn += v
    offered = sum(m.get("nutrient", 0) for m in meta.values()
                  if m.get("kind") == ":refined" and m.get("commons") is True)
    return {"offered": offered, "drawn": drawn, "available": max(0, offered - drawn)}


def draw(txs: list[dict], amount: int, *, member: str, beat: int, as_of: int,
         member_signer=None, operator_ack: bool = False) -> dict:
    """A MEMBER draws `amount` nutrient of commons from the standing pool. Member-principal +
    operator-gated (no-server-key, ADR-2605231525): refuses without an injected member signer
    AND an explicit operator ack — ibuki holds no key and never auto-draws. Refuses if the
    draw exceeds the available pool. Returns {datoms, receipt, available_after}; the
    `:symbiosis/draw` datom is ATTRIBUTED to the member (a human benefit is never fabricated).
    """
    from datoms import add
    if member_signer is None:
        raise MemberSignatureRequired(
            "no member signer injected — the platform holds no key and never draws the "
            "colony's commons on a human's behalf (ADR-2605231525)")
    if not operator_ack:
        raise MemberSignatureRequired(
            "operator_ack=True required — a commons draw is an outward, member-principal act "
            "(G8)")
    if amount <= 0:
        raise ValueError("draw amount must be positive")
    pool = commons_pool(txs)
    if amount > pool["available"]:
        raise ValueError(f"draw {amount} exceeds available commons pool {pool['available']}")
    # the member signs the draw with their OWN runtime (like member_submit); ibuki only
    # records what the member attests
    receipt = member_signer({"kind": "symbiosis-draw", "member": member,
                             "amount": amount, "beat": beat})
    e = f"symbiosis-draw-{member}-{beat}"
    datoms = [
        add(e, ":symbiosis/by", member),
        add(e, ":symbiosis/amount", amount),
        add(e, ":symbiosis/kind", ":draw"),
        add(e, ":symbiosis/drawn-by-member", True),   # never platform-drawn (structural)
        add(e, ":symbiosis/beat", beat),
        add(e, ":symbiosis/as-of", as_of),
    ]
    return {"datoms": datoms, "receipt": receipt,
            "available_after": pool["available"] - amount}
