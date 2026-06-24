"""test_perception.py — 息吹 (ibuki) R2 live perception membrane. ADR-2606101200 §R2."""
from __future__ import annotations

import os

import perception
from _t import expect_raises, run


def test_representative_pattern_deterministic():
    assert perception.representative_events(1) == [":event/idle"]
    assert perception.representative_events(3) == [":event/idle", ":event/follower-gained"]
    assert perception.representative_events(15) == [
        ":event/idle", ":event/follower-gained", ":event/inbox-pressure"]
    assert perception.representative_events(7) == perception.representative_events(7)


def test_allowlist_is_public_appview_only():
    for host in perception.ALLOWED_XRPC_HOSTS:
        assert host == "public.api.bsky.app"


def test_non_allowlisted_host_unrepresentable():
    for bad in ("https://evil.example.com/xrpc/app.bsky.actor.getProfile?actor=x",
                "http://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=x",
                "https://api.openai.example/v1"):
        expect_raises(lambda b=bad: perception.assert_allowed(b), contains="allowlist")


def test_offline_default_is_representative_no_snapshot():
    os.environ.pop(perception.LIVE_ENV, None)
    events, snap = perception.events_for_beat(4, actor="did:web:x")
    assert events == perception.representative_events(4) and snap is None


def test_live_observation_maps_follower_delta():
    fake = lambda url: {"followersCount": 12}
    events, snap = perception.events_for_beat(
        1, actor="did:web:x", prev_followers=10, live=True, fetch=fake)
    assert snap == {"followers": 12}
    assert events == [":event/idle", ":event/follower-gained", ":event/follower-gained"]


def test_live_follower_spike_is_capped():
    fake = lambda url: {"followersCount": 10_000}
    events, _ = perception.events_for_beat(
        1, actor="did:web:x", prev_followers=0, live=True, fetch=fake)
    assert events.count(":event/follower-gained") == perception.FOLLOWER_EVENT_CAP


def test_live_first_observation_emits_no_gain():
    fake = lambda url: {"followersCount": 99}
    events, snap = perception.events_for_beat(
        1, actor="did:web:x", prev_followers=None, live=True, fetch=fake)
    assert events == [":event/idle"] and snap == {"followers": 99}   # baseline, not a spike


def test_live_failure_fails_open_to_representative():
    def boom(url):
        raise OSError("network down")
    events, snap = perception.events_for_beat(
        6, actor="did:web:x", prev_followers=5, live=True, fetch=boom)
    assert events == perception.representative_events(6) and snap is None


def test_events_are_closed_vocab():
    import joucho
    fake = lambda url: {"followersCount": 3}
    events, _ = perception.events_for_beat(
        1, actor="did:web:x", prev_followers=1, live=True, fetch=fake)
    for ev in events:
        assert ev in joucho.EVENT_DELTAS


def test_perception_datoms_shape():
    ds = perception.perception_datoms("10101500", 42, beat=3, as_of=2606100003)
    attrs = {d[2] for d in ds}
    assert attrs == {":perception/of", ":perception/followers", ":perception/beat",
                     ":perception/as-of"}
    assert all(d[0] == ":db/add" and d[1] == "perc-10101500-3" for d in ds)


def test_module_reads_no_credentials():
    import pathlib
    src = pathlib.Path(perception.__file__).read_text(encoding="utf-8")
    for needle in ("password", "accessJwt", "Authorization", "PRIVATE_KEY"):
        assert needle not in src, f"perception must stay unauthenticated read-only: {needle}"


if __name__ == "__main__":
    run("perception", [(n, f) for n, f in sorted(globals().items())
                       if n.startswith("test_") and callable(f)])
