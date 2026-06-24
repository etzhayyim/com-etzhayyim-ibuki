"""test_charter_invariants.py — 息吹 (ibuki) charter invariants across every home. ADR-2606101200.

The gates this package must keep, each enforced structurally (unrepresentable, not configured):
  G6 Murakumo-only inference (ADR-2605215000)   — infer.py allowlist
  G7 no-server-key (ADR-2605231525)             — drainer envelopes + submit injection
  G8 outward-gated                              — :post/status :dry-run / :drain/status :prepared
  N7 no parallel substrate                      — kotoba Datom log only, no SQL/RW imports
  非終末論 append-only                           — :db/add only, no retract anywhere
"""
from __future__ import annotations

import pathlib
import tempfile

import datoms
import drainer
import infer
import joucho
import kaizen_feedback
from _t import expect_raises, run

METHODS = pathlib.Path(__file__).resolve().parent
SOURCES = [p for p in METHODS.glob("*.py") if not p.name.startswith("test_")]


def _all_source() -> dict[str, str]:
    return {p.name: p.read_text(encoding="utf-8") for p in SOURCES}


def test_g6_only_murakumo_hosts_in_allowlist():
    assert infer.MURAKUMO_ALLOWED_HOSTS <= {
        "127.0.0.1:4000", "localhost:4000", "192.168.1.70:8077",
        "192.168.1.70:11434", "127.0.0.1:11434", "localhost:11434"}


def test_g6_commercial_inference_unrepresentable():
    expect_raises(lambda: infer.assert_murakumo("https://api.openai.com/v1/chat/completions"),
                  contains="Murakumo")
    expect_raises(lambda: infer.narrate("t", "c", "calm", "x",
                                        endpoint="https://api.runpod.ai/v2/x/run"),
                  contains="Murakumo")


def test_g6_no_commercial_provider_strings_in_source():
    for name, src in _all_source().items():
        for banned in ("api.openai.com", "runpod", "bedrock", "vertexai",
                       "generativelanguage.googleapis"):
            assert banned not in src.lower(), f"{name} references {banned}"


def test_g7_server_never_signs():
    env = drainer.envelope({"v": 1, "ts": 1, "actorDid": "did:web:x", "mood": "calm",
                            "contentSourceKind": "k", "text": "t",
                            "lexicon": "app.bsky.feed.post", "createdAt": "2026-06-10"})
    assert env["serverHeldKey"] is False and env["requiresMemberSignature"] is True
    expect_raises(lambda: drainer.submit([env]), contains="no member signer")


def test_g7_drainer_has_no_network_or_credential_path():
    src = _all_source()["drainer.py"]
    for needle in ("urllib", "requests", "http.client", "socket", "getenv", "environ"):
        assert needle not in src, f"drainer.py must be offline + credential-free: {needle}"


def test_g8_published_status_unwritable():
    with tempfile.TemporaryDirectory() as dr:
        q = pathlib.Path(dr) / "q.ndjson"
        q.write_text('{"v":1,"ts":1,"actorDid":"did:web:x","mood":"calm",'
                     '"contentSourceKind":"k","text":"t","lexicon":"app.bsky.feed.post",'
                     '"createdAt":"2026-06-10"}\n', encoding="utf-8")
        out = drainer.drain(q, as_of=1, beat=1)
        statuses = {d[3] for d in out["datoms"] if d[2] == ":drain/status"}
        assert statuses == {":prepared"}
    src = _all_source()
    assert '":published"' not in src["drainer.py"] and '":published"' not in src["autorun.py"]


def test_g8_autorun_post_status_literal_is_dry_run():
    assert '":post/status", ":dry-run"' in _all_source()["autorun.py"]


def test_n7_no_parallel_substrate_imports():
    for name, src in _all_source().items():
        for banned in ("psycopg", "sqlalchemy", "risingwave", "kysely", "sqlite3",
                       "duckdb", "lancedb"):
            assert banned not in src.lower(), f"{name} imports a parallel substrate: {banned}"


def test_append_only_no_retract_representable():
    for name, src in _all_source().items():
        assert ":db/retract" not in src, f"{name} makes retraction representable (非終末論)"
    d = datoms.add("e", ":a/b", 1)
    assert d[0] == ":db/add"


def test_closed_vocabularies_raise_not_guess():
    base = joucho.JouchoScores()
    expect_raises(lambda: joucho.fold_event(base, ":event/invented", base),
                  contains="closed vocab")
    with tempfile.TemporaryDirectory() as dr:
        p = pathlib.Path(dr) / "o.ndjson"
        p.write_text('{"proposalId":"p","rule":"r","outcome":"deployed"}\n', encoding="utf-8")
        expect_raises(lambda: kaizen_feedback.read_outcomes(p), contains="closed vocab")


def test_g7_member_submission_is_member_principal_only():
    """The R2 live-posting runtime can act ONLY as the member: no env credentials →
    refusal; cron context → refusal even WITH credentials (a platform job may never
    hold a member key)."""
    import os
    import member_submit as ms
    for k in (ms.ENV_HANDLE, ms.ENV_APP_PASSWORD, ms.ENV_CRON):
        os.environ.pop(k, None)
    expect_raises(lambda: ms.create_member_session(), contains="no member credentials")
    os.environ[ms.ENV_HANDLE] = "m.example"
    os.environ[ms.ENV_APP_PASSWORD] = "pw"
    os.environ[ms.ENV_CRON] = "1"
    try:
        expect_raises(lambda: ms.create_member_session(), contains="cron")
    finally:
        for k in (ms.ENV_HANDLE, ms.ENV_APP_PASSWORD, ms.ENV_CRON):
            os.environ.pop(k, None)


def test_perception_is_readonly_allowlisted():
    """The live membrane only LOOKS: read-only public AppView, allowlisted; anything
    else raises before I/O; the module reads no credential."""
    import perception
    assert perception.ALLOWED_XRPC_HOSTS == frozenset({"public.api.bsky.app"})
    expect_raises(lambda: perception.assert_allowed("https://api.example.com/xrpc/x"),
                  contains="allowlist")
    src = _all_source()["perception.py"]
    for needle in ("password", "accessJwt", "Authorization"):
        assert needle not in src


def test_receipts_never_assert_published():
    src = _all_source()["receipts.py"]
    assert '":published"' not in src
    assert ":submitted-by-member" in src     # honest attribution, not a status upgrade


def test_kotoba_bridge_targets_the_fleet_only():
    """The R3 transact bridge can only reach the kotoba fleet (loopback + EVO-X2 :8077);
    anything else raises before I/O, and the default mode is a no-I/O dry-run export."""
    import kotoba_bridge as kb
    assert kb.ALLOWED_KOTOBA_HOSTS == frozenset({
        "127.0.0.1:8077", "localhost:8077", "192.168.1.70:8077"})
    expect_raises(lambda: kb.assert_kotoba("http://203.0.113.7:8077/xrpc/x"),
                  contains="allowlist")
    import os
    os.environ.pop(kb.LIVE_ENV, None)


def test_kaizen_outcomes_is_operator_principal_readonly():
    import os
    import kaizen_outcomes as ko
    os.environ[ko.ENV_CRON] = "1"
    try:
        expect_raises(lambda: ko.collect(pathlib.Path("/nonexistent")), contains="cron")
    finally:
        os.environ.pop(ko.ENV_CRON, None)
    src = _all_source()["kaizen_outcomes.py"]
    assert "view" in src and "--json" in src     # read-only gh surface


def test_symbiosis_draw_is_member_principal_keyfree():
    """A commons draw can only happen by a MEMBER (injected signer + operator ack); ibuki
    holds no key and never auto-draws the colony's gift on a human's behalf."""
    import symbiosis as sym
    expect_raises(lambda: sym.draw([], 1, member="did:web:x", beat=1, as_of=1),
                  contains="no member signer")
    expect_raises(lambda: sym.draw([], 1, member="did:web:x", beat=1, as_of=1,
                                   member_signer=lambda c: c),
                  contains="operator_ack")
    src = _all_source()["symbiosis.py"]
    for needle in ("urllib", "password", "accessJwt", "Authorization"):
        assert needle not in src, f"symbiosis must stay key-free + offline: {needle}"


def test_digest_is_murakumo_only_and_dry_run():
    """The colony digest reasons via the Murakumo fleet ONLY (G6) and reports DRY-RUN only
    (G8): :published is unrepresentable, exactly like organism posts."""
    src = _all_source()["digest.py"]
    assert '":published"' not in src
    assert '":dry-run"' in src
    for banned in ("api.openai.com", "runpod", "bedrock", "http://", "https://"):
        assert banned not in src.lower(), f"digest must route inference via infer.py: {banned}"


def test_infer_text_enforces_murakumo_allowlist():
    import infer
    expect_raises(lambda: infer.infer_text("p", "fallback",
                                           endpoint="https://api.openai.com/v1/chat/completions"),
                  contains="Murakumo")


def test_stdlib_only():
    import ast
    allowed_local = {"datoms", "drainer", "heartbeat", "joucho", "kaizen_feedback",
                     "infer", "autorun", "_edn", "_t", "perception", "member_submit",
                     "receipts", "fleet", "kotoba_bridge", "kaizen_outcomes",
                     "ecosystem", "health", "symbiosis", "quorum", "digest", "delegation"}
    for p in SOURCES:
        tree = ast.parse(p.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            mods = []
            if isinstance(node, ast.Import):
                mods = [a.name.split(".")[0] for a in node.names]
            elif isinstance(node, ast.ImportFrom) and node.module:
                mods = [node.module.split(".")[0]]
            for m in mods:
                if m in allowed_local:
                    continue
                import importlib.util
                spec = importlib.util.find_spec(m)
                assert spec is not None and (spec.origin in ("built-in", "frozen") or
                                             "site-packages" not in (spec.origin or "")), \
                    f"{p.name} imports third-party module {m}"


if __name__ == "__main__":
    run("charter_invariants", [(n, f) for n, f in sorted(globals().items())
                               if n.startswith("test_") and callable(f)])
