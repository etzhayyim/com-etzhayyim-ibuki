"""perception.py — R2: the live perception membrane. ADR-2606101200 §R2.

What the organism PERCEIVES each beat. Two sources, one closed vocabulary (joucho.py):

  - `representative_events(beat)` — the bounded deterministic R0/R1 stimulus pattern
    (idle every beat, a follower every 3rd, inbox pressure every 5th). The default, and
    the fail-open fallback: offline, no I/O, replay-safe.
  - `LivePerception` — READ-ONLY public XRPC observation (substrate boundary: "read-only
    RPC / firehose subscribe" is in the Allowed column; the organism only *looks*). It
    fetches the actor's public profile from an ALLOWLISTED AppView host (GET only — a
    non-allowlisted host or a non-GET shape raises before any I/O), compares follower
    count against the last `:perception/*` snapshot on the Datom log, and maps the delta
    into closed joucho events. Any failure falls back to the representative pattern —
    the organism keeps living offline.

Live mode is enabled by `IBUKI_PERCEPTION_LIVE=1` (operator env, per the Council-gate-as-
PR-merge direction of 2026-06-10: the code path is complete; *turning it on* is an explicit
runtime act). Offline beats emit NO perception datoms, so offline head CIDs are unchanged
from R1 — determinism tests stay byte-identical.

Stdlib only (urllib for the gated live path). No credential is ever read here: public
endpoints only, the platform signs nothing (G7).
"""

from __future__ import annotations

import json
import os
import urllib.request
from urllib.parse import quote, urlsplit

# READ-ONLY public AppView hosts the membrane may observe. HTTPS GET only.
ALLOWED_XRPC_HOSTS: frozenset[str] = frozenset({
    "public.api.bsky.app",
})

LIVE_ENV = "IBUKI_PERCEPTION_LIVE"

# how many :event/follower-gained one beat may fold (a 10k-follower spike must not
# saturate joy in a single beat — growth is a trajectory, not a step function)
FOLLOWER_EVENT_CAP = 3


class PerceptionBoundaryViolation(ValueError):
    """Raised when a perception fetch is requested outside the read-only allowlist."""


def assert_allowed(url: str) -> None:
    parts = urlsplit(url)
    if parts.scheme != "https" or parts.netloc.lower() not in ALLOWED_XRPC_HOSTS:
        raise PerceptionBoundaryViolation(
            f"perception endpoint {url!r} is outside the read-only allowlist "
            f"({sorted(ALLOWED_XRPC_HOSTS)})")


def representative_events(beat: int) -> list[str]:
    """The bounded deterministic stimulus pattern (R0/R1 default + live fallback)."""
    ev = [":event/idle"]
    if beat % 3 == 0:
        ev.append(":event/follower-gained")
    if beat % 5 == 0:
        ev.append(":event/inbox-pressure")
    return ev


def _default_fetch(url: str, timeout_s: float = 5.0) -> dict:
    """GET an allowlisted public XRPC endpoint and parse JSON. The ONLY I/O in this
    module — read-only, unauthenticated, allowlist-guarded."""
    assert_allowed(url)
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout_s) as resp:
        return json.loads(resp.read().decode("utf-8"))


class LivePerception:
    """Public-profile observation → closed joucho events. `fetch` is injectable for
    hermetic tests; the default fetch enforces the allowlist."""

    def __init__(self, fetch=None) -> None:
        self.fetch = fetch or _default_fetch

    def observe(self, actor: str, prev_followers: int | None) -> dict:
        """One observation of one actor (did or handle). Returns
        {"followers": int, "events": [closed-vocab kinds]}."""
        url = ("https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile"
               f"?actor={quote(actor, safe='')}")
        profile = self.fetch(url)
        followers = int(profile.get("followersCount", 0))
        events: list[str] = [":event/idle"]
        if prev_followers is not None and followers > prev_followers:
            gained = min(followers - prev_followers, FOLLOWER_EVENT_CAP)
            events += [":event/follower-gained"] * gained
        return {"followers": followers, "events": events}


def events_for_beat(beat: int, *, actor: str = "", prev_followers: int | None = None,
                    live: bool | None = None, fetch=None) -> tuple[list[str], dict | None]:
    """The membrane's single entry point: (events, snapshot|None) for one organism-beat.

    snapshot is None on every offline/representative beat — offline logs carry no
    perception datoms, so R1 head CIDs are unchanged. Live failures fall back to the
    representative pattern (fail-open)."""
    is_live = (os.environ.get(LIVE_ENV) == "1") if live is None else live
    if not is_live or not actor:
        return representative_events(beat), None
    try:
        obs = LivePerception(fetch=fetch).observe(actor, prev_followers)
        return obs["events"], {"followers": obs["followers"]}
    except PerceptionBoundaryViolation:
        raise   # a boundary violation is a bug, never silently absorbed
    except Exception:
        return representative_events(beat), None


def perception_datoms(of: str, followers: int, *, beat: int, as_of: int) -> list[list]:
    """Checkpoint a live observation as `:perception/*` datoms (the durable prev-follower
    snapshot the NEXT beat diffs against — perception history is as-of like everything)."""
    from datoms import add
    e = f"perc-{of}-{beat}"
    return [
        add(e, ":perception/of", of),
        add(e, ":perception/followers", followers),
        add(e, ":perception/beat", beat),
        add(e, ":perception/as-of", as_of),
    ]
