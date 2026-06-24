"""heartbeat.py — durable heartbeat cadence on the kotoba Datom log. ADR-2606101200.

Closes Gap 1+2 of the organism autonomy survey ("no durable scheduler — if the pod dies between
ticks, cooldowns reset"). The cadence state is no longer process memory: every beat appends a
`:heartbeat/*` checkpoint datom, and a restarted runner REPLAYS the log to recover exactly where
it was (crash-resume = read the append-only history; nothing is lost because nothing was only
in RAM).

  - replay(txs, of)                 → HeartbeatState folded from the log (latest checkpoint)
  - due_to_post(state, mood, now)   → (bool, reason) under the mood cooldown table (joucho.py)
  - checkpoint_datoms(...)          → this beat's `:heartbeat/*` assertions

Time is LOGICAL: a beat index + a caller-supplied now_ms (no wall clock) — deterministic and
replay-safe. The cron layer (Murakumo cells / k8s) only decides *when* to call tick; *whether*
an organism acts is decided here, from durable state.

Stdlib only.
"""

from __future__ import annotations

from dataclasses import dataclass

from joucho import POST_COOLDOWN_MS, POST_ENABLED


@dataclass
class HeartbeatState:
    """The durable cadence state of one organism (everything a restart must recover)."""

    last_post_at_ms: int = -1   # -1 = never posted
    beats: int = 0              # total beats lived (monotone)
    posts: int = 0              # total posts emitted (monotone)


def replay(txs: list[dict], of: str) -> HeartbeatState:
    """Fold the `:heartbeat/*` checkpoints for one organism out of the log. The LAST
    checkpoint wins (append-only shadowing) — this is the crash-resume read."""
    from datoms import fold_entity, entities
    state = HeartbeatState()
    latest = None
    for e in entities(txs, ":heartbeat/of"):
        ent = fold_entity(txs, e)
        if ent.get(":heartbeat/of") != of:
            continue
        beat = ent.get(":heartbeat/beat", -1)
        if latest is None or beat > latest[0]:
            latest = (beat, ent)
    if latest is not None:
        ent = latest[1]
        state.last_post_at_ms = ent.get(":heartbeat/last-post-at-ms", -1)
        state.beats = ent.get(":heartbeat/beats", 0)
        state.posts = ent.get(":heartbeat/posts", 0)
    return state


def due_to_post(state: HeartbeatState, mood: str, now_ms: int) -> tuple[bool, str]:
    """Is this organism due to post at logical time now_ms, given its mood? Pure function of
    durable state — the same answer before and after a crash."""
    if not POST_ENABLED.get(mood, False):
        return False, f"post disabled while {mood}"
    cooldown = POST_COOLDOWN_MS[mood]
    if state.last_post_at_ms < 0:
        return True, "first post"
    elapsed = now_ms - state.last_post_at_ms
    if elapsed >= cooldown:
        return True, f"cooldown elapsed ({elapsed}ms >= {cooldown}ms while {mood})"
    return False, f"cooling down ({elapsed}ms < {cooldown}ms while {mood})"


def checkpoint_datoms(of: str, state: HeartbeatState, mood: str, *, beat: int,
                      as_of: int) -> list[list]:
    """This beat's `:heartbeat/*` checkpoint (a NEW entity per beat — the cadence history is
    itself as-of queryable, like every other organism fact)."""
    from datoms import add
    e = f"hb-{of}-{beat}"
    return [
        add(e, ":heartbeat/of", of),
        add(e, ":heartbeat/beat", beat),
        add(e, ":heartbeat/as-of", as_of),
        add(e, ":heartbeat/mood", f":{mood}"),
        add(e, ":heartbeat/last-post-at-ms", state.last_post_at_ms),
        add(e, ":heartbeat/beats", state.beats),
        add(e, ":heartbeat/posts", state.posts),
    ]
