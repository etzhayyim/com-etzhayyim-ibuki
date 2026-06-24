"""test_kotoba_bridge.py — 息吹 (ibuki) R3 bridge to the live kotoba engine. ADR-2606101200 §R3."""
from __future__ import annotations

import os
import pathlib
import tempfile

import datoms
import kotoba_bridge as kb
from _t import expect_raises, run


def _log(dr, n=3):
    log = pathlib.Path(dr) / "log.edn"
    prev = ""
    for i in range(1, n + 1):
        tx = datoms.make_tx([datoms.add(f"e{i}", ":a/n", i),
                             datoms.add(f"e{i}", ":a/s", f"観測 {i}")],
                            tx_id=i, as_of=2606100000 + i, prev_cid=prev)
        prev = datoms.append_tx(tx, log)
    return log


def _fake_transport(calls):
    def t(url, body):
        calls.append(body)
        return {"status": "ok", "tx_cid": f"bafyremote{len(calls)}",
                "commit_cid": f"bafycommit{len(calls)}"}
    return t


def test_allowlist_is_kotoba_fleet_only():
    for host in kb.ALLOWED_KOTOBA_HOSTS:
        assert host.split(":")[0] in ("127.0.0.1", "localhost", "192.168.1.70")
        assert host.endswith(":8077")


def test_foreign_endpoint_unrepresentable():
    for bad in ("http://203.0.113.7:8077/xrpc/com.etzhayyim.apps.kotoba.datomic.transact",
                "https://127.0.0.1:8077/xrpc/x",
                "http://127.0.0.1:9999/xrpc/x"):
        expect_raises(lambda b=bad: kb.assert_kotoba(b), contains="allowlist")
    with tempfile.TemporaryDirectory() as dr:
        expect_raises(lambda: kb.push(_log(dr), endpoint="http://evil.example:8077/x"),
                      contains="allowlist")


def test_tx_edn_roundtrips_through_the_edn_reader():
    from _edn import _parse, _tokens
    with tempfile.TemporaryDirectory() as dr:
        tx = datoms.read_log(_log(dr, n=1))[0]
        forms = _parse(_tokens(kb.tx_to_edn_vec(tx)))
        assert forms[0] == [":db/add", "e1", ":a/n", 1]
        assert forms[1][3] == "観測 1"
        metas = {f[2]: f[3] for f in forms if f[2].startswith(":ibuki.tx/")}
        assert metas[":ibuki.tx/id"] == 1
        assert metas[":ibuki.tx/local-cid"] == tx[":tx/cid"]   # provenance to the local DAG
        assert metas[":ibuki.tx/local-prev"] == ""


def test_default_is_dry_run_no_io_no_checkpoint():
    os.environ.pop(kb.LIVE_ENV, None)
    with tempfile.TemporaryDirectory() as dr:
        log = _log(dr, n=3)
        res = kb.push(log)                       # no transport passed: would raise on I/O
        assert res["mode"] == "dry-run" and res["pending"] == 3
        assert all(b["graph"] == kb.graph_cid("ibuki") for b in res["bodies"])
        assert len(datoms.read_log(log)) == 3    # nothing appended


def test_dry_run_export_deterministic():
    with tempfile.TemporaryDirectory() as d1, tempfile.TemporaryDirectory() as d2:
        r1 = kb.push(_log(d1), live=False)
        r2 = kb.push(_log(d2), live=False)
        assert r1["bodies"] == r2["bodies"]


def test_live_push_advances_durable_cursor_exactly_once():
    with tempfile.TemporaryDirectory() as dr:
        log = _log(dr, n=3)
        calls = []
        res = kb.push(log, live=True, transport=_fake_transport(calls))
        assert res["pushed"] == 3 and len(calls) == 3
        # checkpoint appended → chain still verifies
        assert datoms.verify_chain(log)["ok"] is True
        assert len(datoms.read_log(log)) == 4
        # second run: only the checkpoint tx itself is pending — earlier txs NEVER resent
        calls2 = []
        res2 = kb.push(log, live=True, transport=_fake_transport(calls2))
        assert res2["pushed"] == 1
        assert calls2[0]["tx_edn"].find(":bridge/pushed-to-tx") >= 0


def test_expected_parent_chains_remote_commits():
    with tempfile.TemporaryDirectory() as dr:
        log = _log(dr, n=2)
        calls = []
        kb.push(log, live=True, transport=_fake_transport(calls))
        assert "expected_parent" not in calls[0]            # first push: no parent yet
        assert calls[1]["expected_parent"] == "bafycommit1"  # then chained
        calls2 = []
        kb.push(log, live=True, transport=_fake_transport(calls2))
        assert calls2[0]["expected_parent"] == "bafycommit2"  # durable across runs


def test_remote_refusal_raises_loudly():
    with tempfile.TemporaryDirectory() as dr:
        log = _log(dr, n=1)
        def refusing(url, body):
            return {"status": "conflict", "error": "head moved"}
        expect_raises(lambda: kb.push(log, live=True, transport=refusing),
                      contains="refused")
        assert len(datoms.read_log(log)) == 1    # no checkpoint on failure


def test_empty_log_is_a_noop():
    with tempfile.TemporaryDirectory() as dr:
        log = pathlib.Path(dr) / "log.edn"
        res = kb.push(log, live=True, transport=_fake_transport([]))
        assert res["pushed"] == 0


def test_graph_cid_matches_kotoba_core():
    """graph_cid must equal KotobaCid::from_bytes(name).to_multibase() — pinned against
    the value the live engine accepted as a valid graph CID (2026-06-10 verification)."""
    assert kb.graph_cid("ibuki-demo") == \
        "bafyreiepxtekmtjhrssrwyie2l2e63g6ms4beeuxttrib3sxdyz4z65r7q"
    assert kb.graph_cid("ibuki") == kb.graph_cid("ibuki")
    assert kb.graph_cid("ibuki").startswith("bafyrei")


def test_operator_bearer_requires_public_did_env_only():
    os.environ.pop(kb.ENV_OPERATOR_DID, None)
    expect_raises(lambda: kb.operator_bearer(), contains=kb.ENV_OPERATOR_DID)
    os.environ[kb.ENV_OPERATOR_DID] = "did:key:zexample"
    try:
        tok = kb.operator_bearer()
        assert tok.endswith(".unsigned-loopback")      # explicitly unsigned: no key held
        import base64, json as _json
        payload = _json.loads(base64.urlsafe_b64decode(tok.split(".")[1] + "=="))
        assert payload == {"sub": "did:key:zexample"}  # the public DID, nothing else
    finally:
        os.environ.pop(kb.ENV_OPERATOR_DID, None)


def _deleg(graph="ibuki", exp=9999999999):
    return {"cacao_b64": "bWVtYmVyLXNpZ25lZA", "aud": "did:web:etzhayyim.com:actor:ibuki",
            "capability": "datom:transact", "graph": graph, "exp": exp, "nonce": "beef"}


def test_delegation_requires_now_epoch():
    with tempfile.TemporaryDirectory() as dr:
        log = _log(dr, n=1)
        expect_raises(lambda: kb.push(log, graph="ibuki", live=True,
                                      transport=_fake_transport([]), delegation=_deleg()),
                      contains="now_epoch")


def test_usable_delegation_presents_cacao_and_drops_operator_bearer():
    """The leash in force: a usable member-issued delegation → the body carries cacao_b64 and
    the push principal is the delegation, not the operator."""
    with tempfile.TemporaryDirectory() as dr:
        log = _log(dr, n=2)
        calls = []
        res = kb.push(log, graph="ibuki", live=True, transport=_fake_transport(calls),
                      delegation=_deleg(), now_epoch=1000)
        assert res["delegated"] is True
        assert all(b.get("cacao_b64") == "bWVtYmVyLXNpZ25lZA" for b in calls)


def test_expired_delegation_falls_back_to_operator():
    """An unrenewed leash never crashes the organism — it reverts to the node-operator
    principal (fail-open) and carries no cacao_b64."""
    with tempfile.TemporaryDirectory() as dr:
        log = _log(dr, n=1)
        calls = []
        res = kb.push(log, graph="ibuki", live=True, transport=_fake_transport(calls),
                      delegation=_deleg(exp=500), now_epoch=1000)
        assert res["delegated"] is False and "expired" in res["principal"]
        assert all("cacao_b64" not in b for b in calls)


def test_off_scope_delegation_not_used():
    with tempfile.TemporaryDirectory() as dr:
        log = _log(dr, n=1)
        calls = []
        res = kb.push(log, graph="ibuki", live=True, transport=_fake_transport(calls),
                      delegation=_deleg(graph="other"), now_epoch=1)
        assert res["delegated"] is False and "scoped to graph" in res["principal"]


def test_dry_run_reports_delegation_principal():
    os.environ.pop(kb.LIVE_ENV, None)
    with tempfile.TemporaryDirectory() as dr:
        log = _log(dr, n=1)
        res = kb.push(log, graph="ibuki", live=False, delegation=_deleg(), now_epoch=1000)
        assert res["mode"] == "dry-run" and res["delegated"] is True
        assert all(b.get("cacao_b64") for b in res["bodies"])


if __name__ == "__main__":
    run("kotoba_bridge", [(n, f) for n, f in sorted(globals().items())
                          if n.startswith("test_") and callable(f)])
