"""test_member_submit.py — 息吹 (ibuki) R2 member-principal submission + receipts.

ADR-2606101200 §R2 + ADR-2605231525 (no-server-key). The structural claims under test:
the platform can NEVER post (no env → refusal; cron → refusal; no ack → refusal), and when
the MEMBER posts with their own credentials, the log records an attributed receipt — never
a `:published` asserted by ibuki.
"""
from __future__ import annotations

import json
import os
import pathlib
import tempfile

import member_submit as ms
import receipts as rc
from _t import expect_raises, run


def _queue(dr, n=2):
    q = pathlib.Path(dr) / "queue.ndjson"
    lines = []
    for i in range(n):
        lines.append(json.dumps({
            "v": 1, "ts": 1000 + i, "actorDid": f"did:web:etzhayyim.com:actor:c{i}",
            "code": f"c{i}", "title": f"Organism {i}", "mood": "calm",
            "contentSourceKind": "recordAnalysis", "text": f"観測 {i}",
            "lexicon": "app.bsky.feed.post", "createdAt": "2026-06-10T00:00:00.000Z"}))
    q.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return q


def _member_env(on: bool):
    for k, v in ((ms.ENV_HANDLE, "member.example.com"),
                 (ms.ENV_APP_PASSWORD, "xxxx-test-app-password"),
                 (ms.ENV_PDS, "https://pds.example.com")):
        if on:
            os.environ[k] = v
        else:
            os.environ.pop(k, None)


def _fake_transport(calls):
    # NOTE: the fake PDS answers with literals only — it must not echo request-body
    # fields back (a body echo would route the createSession password into the
    # session→receipt flow and trip clear-text-storage taint analysis; a real PDS
    # never returns the password either).
    def t(url, body=None, headers=None):
        calls.append({"url": url, "body": body, "headers": headers or {}})
        if url.endswith("com.atproto.server.createSession"):
            return {"did": "did:plc:member123", "handle": "member.example.com",
                    "accessJwt": "jwt-test"}
        if url.endswith("com.atproto.repo.createRecord"):
            return {"uri": f"at://did:plc:member123/post/{len(calls)}",
                    "cid": f"bafyfake{len(calls)}"}
        raise AssertionError(f"unexpected url {url}")
    return t


def test_no_env_means_no_session():
    _member_env(False)
    os.environ.pop(ms.ENV_CRON, None)
    expect_raises(lambda: ms.create_member_session(), contains="no member credentials")


def test_cron_context_refused_even_with_env():
    _member_env(True)
    os.environ[ms.ENV_CRON] = "1"
    try:
        expect_raises(lambda: ms.create_member_session(), contains="cron")
        expect_raises(lambda: ms.submit_queue(pathlib.Path("/nonexistent")),
                      contains="cron")
    finally:
        os.environ.pop(ms.ENV_CRON, None)
        _member_env(False)


def test_http_pds_refused():
    expect_raises(lambda: ms._http_json("http://pds.example.com/xrpc/x", {}),
                  contains="https")


def test_submit_without_ack_refused():
    _member_env(True)
    try:
        with tempfile.TemporaryDirectory() as dr:
            q = _queue(dr)
            expect_raises(lambda: ms.submit_queue(q, transport=_fake_transport([])),
                          contains="operator_ack")
    finally:
        _member_env(False)


def test_member_submits_and_receipts_attribute_the_member():
    _member_env(True)
    try:
        calls = []
        with tempfile.TemporaryDirectory() as dr:
            q = _queue(dr, n=2)
            res = ms.submit_queue(q, operator_ack=True, transport=_fake_transport(calls))
            assert len(res["receipts"]) == 2 and res["next_line"] == 2
            # session created with the MEMBER's creds, records on the MEMBER's repo
            assert calls[0]["url"].startswith("https://pds.example.com/")
            assert calls[1]["body"]["repo"] == "did:plc:member123"
            assert calls[1]["headers"]["Authorization"] == "Bearer jwt-test"
            r = res["receipts"][0]
            assert r["status"] == "submitted-by-member"
            assert r["submittedBy"] == "did:plc:member123"
            assert r["of"].startswith("did:web:etzhayyim.com:actor:")
    finally:
        _member_env(False)


def test_resume_from_line_skips_submitted():
    _member_env(True)
    try:
        with tempfile.TemporaryDirectory() as dr:
            q = _queue(dr, n=3)
            res = ms.submit_queue(q, from_line=2, operator_ack=True,
                                  transport=_fake_transport([]))
            assert len(res["receipts"]) == 1 and res["next_line"] == 3
    finally:
        _member_env(False)


def test_receipts_roundtrip_to_datoms():
    _member_env(True)
    try:
        with tempfile.TemporaryDirectory() as dr:
            q = _queue(dr, n=2)
            res = ms.submit_queue(q, operator_ack=True, transport=_fake_transport([]))
            rpath = pathlib.Path(dr) / "receipts.ndjson"
            assert ms.write_receipts(res["receipts"], rpath) == 2
            back = rc.read_receipts(rpath)
            assert len(back) == 2
            ds = rc.receipt_datoms(back, beat=1, as_of=2606100001)
            statuses = {d[3] for d in ds if d[2] == ":receipt/status"}
            assert statuses == {":submitted-by-member"}     # ibuki never says :published
            by = {d[3] for d in ds if d[2] == ":receipt/submitted-by"}
            assert by == {"did:plc:member123"}
    finally:
        _member_env(False)


def test_receipts_ingest_appends_verified_tx():
    import datoms
    _member_env(True)
    try:
        with tempfile.TemporaryDirectory() as dr:
            q = _queue(dr, n=1)
            res = ms.submit_queue(q, operator_ack=True, transport=_fake_transport([]))
            rpath = pathlib.Path(dr) / "receipts.ndjson"
            ms.write_receipts(res["receipts"], rpath)
            log = pathlib.Path(dr) / "log.edn"
            out = rc.ingest(rpath, log)
            assert out["count"] == 1
            assert datoms.verify_chain(log)["ok"] is True
    finally:
        _member_env(False)


def test_unknown_receipt_status_raises():
    with tempfile.TemporaryDirectory() as dr:
        p = pathlib.Path(dr) / "r.ndjson"
        p.write_text(json.dumps({"uri": "at://x", "of": "did:web:x", "queueTs": 1,
                                 "collection": "app.bsky.feed.post",
                                 "submittedBy": "did:plc:m",
                                 "status": "published"}) + "\n", encoding="utf-8")
        expect_raises(lambda: rc.read_receipts(p), contains="closed vocab")


def test_no_committed_credentials_in_source():
    src = pathlib.Path(ms.__file__).read_text(encoding="utf-8")
    # credentials may only ARRIVE via the member's env at runtime — never literals
    for needle in ("Bearer ey", "did:plc:", "xxxx-"):
        assert needle not in src, f"member_submit must not embed a credential: {needle}"
    assert "IBUKI_MEMBER_" in src       # the only credential source is the member's env


if __name__ == "__main__":
    run("member_submit", [(n, f) for n, f in sorted(globals().items())
                          if n.startswith("test_") and callable(f)])
