"""kaizen_feedback.py — Wave-4 feedback that closes the Kaizen loop. ADR-2606101200.

Closes Gap 7 of the organism autonomy survey ("Kaizen is fire-and-forget: proposals flow out,
nothing flows back"). The kotodama Kaizen observer emits KaizenProposal NDJSON and the (already
existing) PR agent drafts human-reviewed PRs; what was missing is the RETURN edge — did the
proposal merge? was it rejected? — folded back into durable state so the observer LEARNS.

  - read_proposals(path)   → KaizenProposal NDJSON lines ({proposalId, rule, ...})
  - read_outcomes(path)    → outcome records ({proposalId, rule, outcome ∈ merged|rejected|
                             pending}) — written by the operator / PR-agent runner from
                             `gh pr view` results (never by ibuki reaching out to GitHub)
  - fold(...)              → per-rule stats incl. CONSECUTIVE rejections
  - suppression(stats, …)  → rules with ≥ SUPPRESS_AFTER consecutive rejections are suppressed
                             for SUPPRESS_BEATS beats (the observer stops re-proposing what
                             humans keep declining — that is the learning)
  - should_emit(rule, …)   → the observer-side gate
  - feedback_datoms(...)   → `:kaizen.rule/*` checkpoints on the kotoba log (as-of history of
                             what the colony learned about its own proposals)

Outcomes also feed the organism mood: `mood_events(...)` maps merged → :event/kaizen-merged,
rejected → :event/kaizen-rejected (joucho.py) — acceptance literally calms the colony.
Stdlib only. Deterministic. Human review stays the decision-maker (ADR-2605240200: auto-apply
is rejected; the loop closes through people, the LEARNING closes through the log).
"""

from __future__ import annotations

import json
import pathlib

SUPPRESS_AFTER = 3    # consecutive rejections before a rule is suppressed
SUPPRESS_BEATS = 12   # how many beats a suppressed rule stays quiet

OUTCOMES = ("merged", "rejected", "pending")


def _read_ndjson(path: pathlib.Path) -> list[dict]:
    p = pathlib.Path(path)
    if not p.exists():
        return []
    out = []
    for line in p.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            out.append(json.loads(line))
    return out


def read_proposals(path: pathlib.Path) -> list[dict]:
    """KaizenProposal NDJSON (kotodama kaizen_cell_main schema; needs proposalId + rule)."""
    return [p for p in _read_ndjson(path) if "proposalId" in p and "rule" in p]


def read_outcomes(path: pathlib.Path) -> list[dict]:
    """Outcome NDJSON. An unknown outcome value raises — closed vocab, never guessed."""
    out = []
    for o in _read_ndjson(path):
        if "proposalId" not in o or "rule" not in o:
            continue
        if o.get("outcome") not in OUTCOMES:
            raise ValueError(f"unknown kaizen outcome (closed vocab {OUTCOMES}): "
                             f"{o.get('outcome')!r}")
        out.append(o)
    return out


def fold(proposals: list[dict], outcomes: list[dict]) -> dict[str, dict]:
    """Per-rule stats. Outcomes are folded in file order (the operator appends as PRs
    resolve), so `consecutive_rejected` reflects the latest run of rejections."""
    stats: dict[str, dict] = {}
    for p in proposals:
        s = stats.setdefault(p["rule"], {"proposed": 0, "merged": 0, "rejected": 0,
                                         "pending": 0, "consecutive_rejected": 0})
        s["proposed"] += 1
    for o in outcomes:
        s = stats.setdefault(o["rule"], {"proposed": 0, "merged": 0, "rejected": 0,
                                         "pending": 0, "consecutive_rejected": 0})
        s[o["outcome"]] += 1
        if o["outcome"] == "rejected":
            s["consecutive_rejected"] += 1
        elif o["outcome"] == "merged":
            s["consecutive_rejected"] = 0
    return stats


def suppression(stats: dict[str, dict], *, now_beat: int) -> dict[str, int]:
    """rule → suppressed-until-beat for every rule whose consecutive rejections reached the
    threshold. This is the colony declining to repeat what its humans keep declining."""
    return {rule: now_beat + SUPPRESS_BEATS
            for rule, s in stats.items()
            if s["consecutive_rejected"] >= SUPPRESS_AFTER}


def should_emit(rule: str, suppress_table: dict[str, int], beat: int) -> bool:
    """Observer-side gate: may this rule emit a proposal at this beat?"""
    return beat >= suppress_table.get(rule, -1)


def mood_events(outcomes: list[dict]) -> list[str]:
    """Map PR outcomes to joucho events — acceptance calms, rejection stresses (and
    sharpens focus). Pending moves nothing."""
    out = []
    for o in outcomes:
        if o["outcome"] == "merged":
            out.append(":event/kaizen-merged")
        elif o["outcome"] == "rejected":
            out.append(":event/kaizen-rejected")
    return out


def feedback_datoms(stats: dict[str, dict], suppress_table: dict[str, int], *,
                    beat: int, as_of: int) -> list[list]:
    """Checkpoint what the colony learned as `:kaizen.rule/*` datoms (new entity per beat —
    the learning history is as-of queryable like everything else)."""
    from datoms import add
    out: list[list] = []
    for rule, s in sorted(stats.items()):
        e = f"kzr-{rule}-{beat}"
        out += [
            add(e, ":kaizen.rule/id", rule),
            add(e, ":kaizen.rule/beat", beat),
            add(e, ":kaizen.rule/as-of", as_of),
            add(e, ":kaizen.rule/proposed", s["proposed"]),
            add(e, ":kaizen.rule/merged", s["merged"]),
            add(e, ":kaizen.rule/rejected", s["rejected"]),
            add(e, ":kaizen.rule/consecutive-rejected", s["consecutive_rejected"]),
            add(e, ":kaizen.rule/suppressed-until-beat", suppress_table.get(rule, -1)),
        ]
    return out
