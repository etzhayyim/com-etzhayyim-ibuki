"""test_drainer.py — 息吹 (ibuki) Wave-3 member-signed drainer. ADR-2606101200 + ADR-2605240100."""
from __future__ import annotations

import json
import pathlib
import tempfile

import drainer
from _t import expect_raises, run


def _line(**over):
    base = {"v": 1, "ts": 1000, "actorDid": "did:web:etzhayyim.com:actor:unspsc-10101500",
            "code": "10101500", "title": "Live cattle stewardship", "mood": "calm",
            "contentSourceKind": "recordAnalysis", "text": "観測ノート",
            "lexicon": "app.bsky.feed.post", "createdAt": "2026-06-10T00:00:00.000Z"}
    base.update(over)
    return base


def _write_queue(dr, lines):
    q = pathlib.Path(dr) / "queue.ndjson"
    q.write_text("\n".join(json.dumps(x) if isinstance(x, dict) else x for x in lines) + "\n",
                 encoding="utf-8")
    return q


def test_parse_valid_queue():
    with tempfile.TemporaryDirectory() as dr:
        q = _write_queue(dr, [_line(), _line(ts=2000)])
        posts, errors = drainer.parse_queue(q)
        assert len(posts) == 2 and errors == []


def test_parse_rejects_bad_lines_never_guesses():
    with tempfile.TemporaryDirectory() as dr:
        bad_v = _line(); bad_v["v"] = 99
        missing = _line(); missing.pop("createdAt")
        q = _write_queue(dr, [_line(), bad_v, missing, "not-json{"])
        posts, errors = drainer.parse_queue(q)
        assert len(posts) == 1 and len(errors) == 3


def test_missing_queue_is_empty_not_error():
    posts, errors = drainer.parse_queue(pathlib.Path("/nonexistent/queue.ndjson"))
    assert posts == [] and errors == []


def test_envelope_shape_member_signed():
    env = drainer.envelope(_line())
    assert env["xrpc"] == "com.atproto.repo.createRecord"
    assert env["repo"].startswith("did:web:etzhayyim.com:actor:")
    assert env["record"]["$type"] == "app.bsky.feed.post"
    assert env["requiresMemberSignature"] is True
    assert env["serverHeldKey"] is False                 # G7 structural constant


def test_drain_emits_prepared_only():
    with tempfile.TemporaryDirectory() as dr:
        q = _write_queue(dr, [_line(), _line(ts=2000)])
        out = drainer.drain(q, as_of=2606100001, beat=1)
        assert len(out["envelopes"]) == 2
        statuses = [d[3] for d in out["datoms"] if d[2] == ":drain/status"]
        assert statuses == [":prepared", ":prepared"]    # :published unwritable by ibuki
        held = [d[3] for d in out["datoms"] if d[2] == ":drain/server-held-key"]
        assert held == [False, False]


def test_submit_without_signer_is_impossible():
    expect_raises(lambda: drainer.submit([drainer.envelope(_line())]),
                  contains="no member signer")


def test_submit_without_operator_ack_refused():
    expect_raises(lambda: drainer.submit([drainer.envelope(_line())],
                                         member_signer=lambda e: e),
                  contains="operator_ack")


def test_submit_forwards_to_injected_member_runtime():
    seen = []
    def member_signer(env):                              # the member's OWN runtime, injected
        seen.append(env)
        return {"signedBy": "member", "uri": "at://did:.../app.bsky.feed.post/1"}
    res = drainer.submit([drainer.envelope(_line())], member_signer=member_signer,
                         operator_ack=True)
    assert len(seen) == 1 and res[0]["signedBy"] == "member"


def test_module_holds_no_credentials():
    src = pathlib.Path(drainer.__file__).read_text(encoding="utf-8")
    for needle in ("token", "secret", "PRIVATE_KEY", "urllib", "requests", "http.client"):
        assert needle not in src, f"drainer must stay credential-free + offline: {needle}"


if __name__ == "__main__":
    run("drainer", [(n, f) for n, f in sorted(globals().items())
                    if n.startswith("test_") and callable(f)])
