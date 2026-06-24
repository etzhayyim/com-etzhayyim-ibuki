"""joucho.py — 情緒 5-axis mood that EVOLVES from observed events. ADR-2606101200.

Closes Gap 4 of the organism autonomy survey ("joucho provider is a constant stub → personality
never emerges"). Three pieces:

  1. `personality_baseline(code)` — deterministic per-organism 5-axis baseline (hash → [25,75]
     per axis, kotodama personality.py-compatible idea: every organism gets a distinct,
     repeatable temperament with no network round-trip).
  2. `fold_event / replay_events` — a CLOSED event vocabulary folds observed history into the
     scores. Because events are persisted as `:joucho.event/*` datoms on the append-only kotoba
     log (datoms.py), mood is **as-of replayable**: replay the event stream up to tx N and you
     have the organism's mood at tx N. The mood is the fold of the life lived — 縁起.
  3. `determine_mood` + `post_cooldown_ms` — mood thresholds identical to kotodama joucho.py
     (stress ≥70 trumps; dominant axis ≥60 wins; else neutral) + the ibuki R0 post-cadence table.

Stdlib only. Deterministic. No wall clock.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

MOODS = ("joyful", "calm", "stressed", "grateful", "focused", "neutral")

_AXES = ("joy", "calm", "stress", "gratitude", "focus")

_MIN_MS = 60_000


@dataclass
class JouchoScores:
    """5-axis 情緒 scores (each 0-100). Defaults match the kotodama constant fallback —
    the very stub this module replaces (joy=50, calm=50, stress=30, gratitude=50, focus=50)."""

    joy: int = 50
    calm: int = 50
    stress: int = 30
    gratitude: int = 50
    focus: int = 50

    def as_dict(self) -> dict[str, int]:
        return {a: getattr(self, a) for a in _AXES}


def personality_baseline(code: str) -> JouchoScores:
    """Deterministic per-organism baseline: sha256(code) → 5 evenly-distributed ints in
    [25, 75]. Distinct, repeatable temperament per organism — no I/O. The stress axis is
    bounded to [25, 65]: stress comes from LIVED events, never from temperament (a baseline
    ≥70 would mean born-permanently-stressed — homeostasis would hold the organism above the
    stressed threshold forever, muting it for life)."""
    h = hashlib.sha256(code.encode("utf-8")).digest()
    vals = [25 + (int.from_bytes(h[i * 2:i * 2 + 2], "big") % 51) for i in range(5)]
    vals[2] = 25 + (vals[2] - 25) * 41 // 51            # stress → [25, 65]
    return JouchoScores(*vals)


def determine_mood(j: JouchoScores) -> str:
    """High stress trumps; otherwise the dominant axis ≥60 wins; else neutral.
    Thresholds identical to kotodama joucho.determine_mood."""
    if j.stress >= 70:
        return "stressed"
    axes = sorted(
        [("joyful", j.joy), ("calm", j.calm), ("grateful", j.gratitude), ("focused", j.focus)],
        key=lambda p: p[1], reverse=True,
    )
    if axes[0][1] < 60:
        return "neutral"
    return axes[0][0]


# ── mood → post cadence (ibuki R0 table) ──────────────────────────────────

POST_COOLDOWN_MS: dict[str, int] = {
    "joyful": 30 * _MIN_MS,
    "grateful": 60 * _MIN_MS,
    "calm": 120 * _MIN_MS,
    "neutral": 120 * _MIN_MS,
    "focused": 180 * _MIN_MS,
    "stressed": 240 * _MIN_MS,
}

POST_ENABLED: dict[str, bool] = {m: m != "stressed" for m in MOODS}


# ── event fold (the growth loop) ──────────────────────────────────────────

# CLOSED vocabulary — an unknown event kind raises (charter discipline: an unrepresentable
# stimulus cannot move a mood). Deltas are (joy, calm, stress, gratitude, focus).
EVENT_DELTAS: dict[str, tuple[int, int, int, int, int]] = {
    ":event/post-emitted":    (0, 0, 0, 0, +1),
    ":event/follower-gained": (+2, 0, 0, +2, 0),
    ":event/inbox-pressure":  (0, -1, +4, 0, 0),
    ":event/kaizen-merged":   (0, +3, -3, +1, 0),
    ":event/kaizen-rejected": (0, 0, +2, 0, +1),
    ":event/idle":            (0, 0, 0, 0, 0),  # handled as drift-toward-baseline below
}


def _clamp(v: int) -> int:
    return max(0, min(100, v))


def _drift(v: int, base: int) -> int:
    """One step of homeostasis: idle beats pull each axis 1 toward its baseline."""
    if v > base:
        return v - 1
    if v < base:
        return v + 1
    return v


def fold_event(scores: JouchoScores, event: str, baseline: JouchoScores) -> JouchoScores:
    """Fold ONE observed event into the scores (pure — returns a new JouchoScores)."""
    if event not in EVENT_DELTAS:
        raise ValueError(f"unknown joucho event kind (closed vocab): {event}")
    if event == ":event/idle":
        return JouchoScores(*[
            _drift(getattr(scores, a), getattr(baseline, a)) for a in _AXES
        ])
    d = EVENT_DELTAS[event]
    return JouchoScores(*[_clamp(getattr(scores, a) + d[i]) for i, a in enumerate(_AXES)])


def replay_events(baseline: JouchoScores, events: list[str]) -> JouchoScores:
    """Replay a full event stream from the baseline — THE as-of mood query: feed it
    `datoms.events_for(txs, of, up_to_tx=N)` and you get the mood at tx N."""
    scores = baseline
    for ev in events:
        scores = fold_event(scores, ev, baseline)
    return scores


def joucho_datoms(of: str, scores: JouchoScores, mood: str, *, beat: int, as_of: int) -> list[list]:
    """Checkpoint the folded scores + mood as `:joucho/*` datoms (a NEW entity per beat —
    re-observation is a new datom, never an overwrite; 非終末論)."""
    from datoms import add
    e = f"joucho-{of}-{beat}"
    out = [add(e, ":joucho/of", of), add(e, ":joucho/beat", beat), add(e, ":joucho/as-of", as_of)]
    for axis, v in scores.as_dict().items():
        out.append(add(e, f":joucho/{axis}", v))
    out.append(add(e, ":joucho/mood", f":{mood}"))
    return out


def event_datoms(of: str, kinds: list[str], *, beat: int, as_of: int) -> list[list]:
    """Persist this beat's observed events as `:joucho.event/*` datoms — the replayable
    history that makes the mood as-of queryable."""
    from datoms import add
    out: list[list] = []
    for i, kind in enumerate(kinds):
        if kind not in EVENT_DELTAS:
            raise ValueError(f"unknown joucho event kind (closed vocab): {kind}")
        e = f"jev-{of}-{beat}-{i}"
        out += [add(e, ":joucho.event/of", of),
                add(e, ":joucho.event/kind", kind),
                add(e, ":joucho.event/beat", beat),
                add(e, ":joucho.event/as-of", as_of)]
    return out
