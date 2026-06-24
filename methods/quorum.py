"""quorum.py — quorum sensing: emergent COLLECTIVE behaviour the colony shows, not the cell.

ADR-2606101200 §定足数. Molds and slime molds do not act only as individuals — at a density
threshold of a shared signal they undergo a collective phase transition: a 粘菌 aggregates into
a fruiting body and sporulates; a fungal mat fruits under favourable conditions and forms
hardy sclerotia under stress (quorum sensing / Physarum aggregation). That is the essence of
"ecosystem, not a single organism" — a colony phenotype no cell could trigger alone.

This module reads the colony's mood distribution this beat and derives a COLONY phenotype:

  - **:flourishing** — ≥ QUORUM_FRACTION of the colony is flourishing (joyful/grateful):
    the colony FRUITS — a collective `:metabolite/commons` burst (source `:fruiting`), an
    extra gift to humanity precisely when the colony thrives (deepens 共生).
  - **:dormant**     — ≥ QUORUM_FRACTION is stressed: the colony SPORULATES (conserves) —
    an observational `:quorum/state :dormant` (individuals already self-quiet when stressed;
    dormancy adds no mood pressure, only a recorded collective state).
  - **:neutral**     — no quorum; the colony acts as a loose collection of individuals.

The phenotype is a COLONY-level emergent state, checkpointed as `:quorum/*` datoms (as-of
queryable). It is an aggregate, never a per-organism verdict (edge-primary). The fruiting
bonus is bounded (a fraction of the beat's realised production), so it cannot run away.
Stdlib only. Deterministic. Append-only.
"""

from __future__ import annotations

import joucho

QUORUM_FRACTION_NUM, QUORUM_FRACTION_DEN = 2, 3   # ≥ 2/3 sharing a condition = quorum
QUORUM_MIN = 3                                     # need ≥3 organisms for a colony phenotype
FRUIT_BONUS_NUM, FRUIT_BONUS_DEN = 1, 4           # fruiting burst = 1/4 of the beat's commons

FLOURISH_MOODS = ("joyful", "grateful")
STRESS_MOODS = ("stressed",)

STATES = (":flourishing", ":dormant", ":neutral")


def phenotype(moods: dict[str, str]) -> dict:
    """The colony phenotype from this beat's per-organism moods (code → mood string).
    Returns {state, flourish, stressed, n}. Deterministic."""
    n = len(moods)
    flourish = sum(1 for m in moods.values() if m in FLOURISH_MOODS)
    stressed = sum(1 for m in moods.values() if m in STRESS_MOODS)
    state = ":neutral"
    if n >= QUORUM_MIN:
        need = (n * QUORUM_FRACTION_NUM + QUORUM_FRACTION_DEN - 1) // QUORUM_FRACTION_DEN
        if flourish >= need:
            state = ":flourishing"
        elif stressed >= need:
            state = ":dormant"
    return {"state": state, "flourish": flourish, "stressed": stressed, "n": n}


def sense(moods: dict[str, str], beat_commons_nutrient: int, *, beat: int, as_of: int) -> dict:
    """Sense the quorum and emit `:quorum/*` datoms. On `:flourishing`, the colony fruits: a
    collective commons metabolite worth FRUIT_BONUS of this beat's realised commons nutrient
    (a gift that grows when the colony thrives). Returns {datoms, state, fruiting_nutrient}."""
    from datoms import add
    ph = phenotype(moods)
    e = f"quorum-{beat}"
    out = [add(e, ":quorum/state", ph["state"]),
           add(e, ":quorum/flourish", ph["flourish"]),
           add(e, ":quorum/stressed", ph["stressed"]),
           add(e, ":quorum/n", ph["n"]),
           add(e, ":quorum/beat", beat),
           add(e, ":quorum/as-of", as_of)]
    fruiting = 0
    if ph["state"] == ":flourishing" and beat_commons_nutrient > 0:
        fruiting = beat_commons_nutrient * FRUIT_BONUS_NUM // FRUIT_BONUS_DEN
        if fruiting > 0:
            fe = f"quorum-fruit-{beat}"
            out += [add(fe, ":metabolite/kind", ":refined"),
                    add(fe, ":metabolite/of", e),
                    add(fe, ":metabolite/nutrient", fruiting),
                    add(fe, ":metabolite/source", ":fruiting"),  # collective, not a single カビ
                    add(fe, ":metabolite/commons", True),
                    add(fe, ":metabolite/beat", beat),
                    add(fe, ":metabolite/as-of", as_of)]
    return {"datoms": out, "state": ph["state"], "fruiting_nutrient": fruiting}


def quorum_history(txs: list[dict]) -> dict:
    """Single-pass tally of colony phenotypes over the log — the colony's collective-behaviour
    history (how often it fruited / went dormant). Aggregate, deterministic."""
    counts: dict[str, int] = {s: 0 for s in STATES}
    fruiting_total = 0
    meta: dict[str, dict] = {}
    for tx in txs:
        for _op, e, a, v in tx.get(":tx/datoms", []):
            if a == ":quorum/state":
                counts[v] = counts.get(v, 0) + 1
            elif a == ":metabolite/source":
                meta.setdefault(e, {})["source"] = v
            elif a == ":metabolite/nutrient":
                meta.setdefault(e, {})["nutrient"] = v
    for m in meta.values():
        if m.get("source") == ":fruiting":
            fruiting_total += m.get("nutrient", 0)
    return {"states": counts, "fruiting_nutrient_total": fruiting_total}
