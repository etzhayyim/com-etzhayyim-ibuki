"""digest.py — the colony REASONS about its own ecosystem and reports to humanity. ADR-2606101800.

The original ask was an organism that *infers*; the ecosystem waves gave the colony a rich
log-derived state (health, web, symbiosis pool, quorum phenotype). This module closes the
"reason + speak" loop at the COLONY level: it assembles that state from the log, narrates it in
human-readable words via the Murakumo fleet ONLY (infer.infer_text — allowlist enforced,
fail-open to a deterministic template), and emits a `:digest/*` DRY-RUN post — the colony's
report to humanity on the symbiosis (黒カビ→クエン酸→人類).

  - `assemble(txs)` — structured colony state (health verdict + eco-maturity + commons offered
    to humanity + quorum history), single pass, log-derived, deterministic.
  - `template_digest(state)` — the deterministic human-readable fallback (no I/O).
  - `narrate(state, beat)` — Murakumo-only narration (G6; via ∈ {template, murakumo}).
  - `digest_datoms(...)` — a `:digest/*` post, `:digest/status :dry-run` ONLY (G8; :published
    is unrepresentable, exactly like organism posts). It carries no advice — a mirror report.

Stdlib only. Deterministic (logical beat; no wall clock). Append-only.
"""

from __future__ import annotations

import health
import quorum
import symbiosis
import ecosystem
from infer import infer_text


def assemble(txs: list[dict]) -> dict:
    """The colony's self-state from the log alone — the substance the digest reasons over."""
    rep = health.audit(txs)
    pool = symbiosis.commons_pool(txs)
    web = ecosystem.web_report(txs)
    qh = quorum.quorum_history(txs)
    return {
        "organisms": rep["colony"]["count"],
        "healthy": rep["healthy"],
        "findings": sorted({f["rule"] for f in rep["findings"]}),
        "eco_maturity": round(rep["colony"].get("eco_maturity", 0.0), 3),
        "niche_population": rep["colony"].get("niche_population", {}),
        "commons_offered": pool["offered"],
        "commons_drawn": pool["drawn"],
        "commons_available": pool["available"],
        "commons_by_source": web.get("commons_by_source", {}),
        "quorum_states": qh["states"],
        "fruiting_nutrient_total": qh["fruiting_nutrient_total"],
    }


def template_digest(state: dict) -> str:
    """Deterministic, human-readable colony report — the offline default + fail-open fallback.
    Mirror tone: it describes the symbiosis, never advises."""
    health_word = "healthy" if state["healthy"] else f"attending to {state['findings']}"
    fruited = state["quorum_states"].get(":flourishing", 0)
    return (f"息吹 colony report: {state['organisms']} organisms, {health_word}; "
            f"ecological maturity {state['eco_maturity']}. "
            f"The web has offered {state['commons_offered']} nutrient of commons to humanity "
            f"({state['commons_available']} still available to draw); fruited {fruited} times. "
            f"A mirror of where the colony's life became a gift — no advice.")


def narrate(state: dict, *, beat: int) -> dict:
    """Narrate the colony state via Murakumo-only (G6), fail-open to the template."""
    prompt = (
        "You are 息吹 (ibuki), an artificial-organism COLONY that lives on an append-only log "
        "and, as a byproduct of living, refines a commons gift for humanity (like black mould "
        "excreting citric acid). Write ONE short observational report (<280 chars) of your "
        f"current state. Mirror tone: describe, never advise. State: {state}.")
    return infer_text(prompt, template_digest(state))


def digest_datoms(state: dict, narration: dict, *, beat: int, as_of: int) -> list[list]:
    """A `:digest/*` colony report post. `:digest/status` is `:dry-run` ONLY (G8); :published
    is unrepresentable. Aggregate colony state, never a per-organism verdict."""
    from datoms import add
    e = f"digest-{beat}"
    return [
        add(e, ":digest/text", narration["text"]),
        add(e, ":digest/via", f":{narration['via']}"),
        add(e, ":digest/status", ":dry-run"),
        add(e, ":digest/organisms", state["organisms"]),
        add(e, ":digest/healthy", state["healthy"]),
        add(e, ":digest/eco-maturity", state["eco_maturity"]),
        add(e, ":digest/commons-offered", state["commons_offered"]),
        add(e, ":digest/commons-available", state["commons_available"]),
        add(e, ":digest/beat", beat),
        add(e, ":digest/as-of", as_of),
    ]


def make(txs: list[dict], *, beat: int, as_of: int) -> dict:
    """Assemble → narrate → datoms in one call (the autorun/fleet entry point)."""
    state = assemble(txs)
    narration = narrate(state, beat=beat)
    return {"state": state, "narration": narration,
            "datoms": digest_datoms(state, narration, beat=beat, as_of=as_of)}
