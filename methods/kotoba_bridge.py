"""kotoba_bridge.py — R3: push the local tx log into the LIVE kotoba engine.

ADR-2606101200 §R3. R0–R2 persist organism life to a LOCAL append-only EDN tx log whose body
shape is kotoba-native by design. This module is the missing hop: each local transaction
becomes one `com.etzhayyim.apps.kotoba.datomic.transact` call against a running kotoba node
(the engine of ADR-2605262130 / ADR-2605301625, serving on :8077), so organism state lands
on the REAL distributed Datom graph (IPFS-backed, IPNS-headed) — not just a file.

Durability + honesty rules, same discipline as everything else here:

  - the push cursor is a `:bridge/*` checkpoint ON the local log (a tx like any other), so
    a crashed/re-run push NEVER double-sends a transaction (exactly-once per local tx);
  - every pushed tx carries `:ibuki.tx/*` provenance meta datoms (local tx id + local CID +
    local prev), so the remote graph holds the full mapping back to the local commit-DAG;
  - the previous push's remote `commit_cid` is sent as `expected_parent` (optimistic
    concurrency: a fork on the remote head fails loudly, never silently overwrites);
  - host allowlist (loopback + EVO-X2 LAN, ADR-2605215000 fleet topology) — any other
    endpoint raises before I/O; live mode is `IBUKI_KOTOBA_LIVE=1`, default is a DRY-RUN
    export that returns the exact request bodies without any I/O.

Stdlib only. Deterministic (the dry-run export of the same log is byte-identical).
"""

from __future__ import annotations

import json
import os
import pathlib
import urllib.error
import urllib.request
from urllib.parse import urlsplit

import datoms

ALLOWED_KOTOBA_HOSTS: frozenset[str] = frozenset({
    "127.0.0.1:8077",
    "localhost:8077",
    "192.168.1.70:8077",   # EVO-X2 (kotoba actor serve, ADR-2605301625)
})

DEFAULT_ENDPOINT = "http://127.0.0.1:8077/xrpc/com.etzhayyim.apps.kotoba.datomic.transact"
DEFAULT_GRAPH = "ibuki"

LIVE_ENV = "IBUKI_KOTOBA_LIVE"
ENV_OPERATOR_DID = "IBUKI_KOTOBA_OPERATOR_DID"


class KotobaBoundaryViolation(ValueError):
    """Raised when a transact push targets a host outside the kotoba fleet allowlist."""


def graph_cid(name: str) -> str:
    """The kotoba graph identifier for a graph NAME: KotobaCid::from_bytes(name) —
    CIDv1 + dag-cbor(0x71) + sha2-256 multihash over the raw name bytes, multibase
    base32lower ('b' prefix). Mirrors kotoba-core cid.rs exactly (verified against the
    engine's own parser: a fresh CID transacts as genesis)."""
    import base64
    import hashlib
    raw = bytes([0x01, 0x71, 0x12, 0x20]) + hashlib.sha256(name.encode("utf-8")).digest()
    return "b" + base64.b32encode(raw).decode("ascii").rstrip("=").lower()


def assert_kotoba(endpoint: str) -> None:
    parts = urlsplit(endpoint)
    if parts.scheme != "http" or parts.netloc.lower() not in ALLOWED_KOTOBA_HOSTS:
        raise KotobaBoundaryViolation(
            f"kotoba endpoint {endpoint!r} is outside the fleet allowlist "
            f"({sorted(ALLOWED_KOTOBA_HOSTS)})")


def tx_to_edn_vec(tx: dict) -> str:
    """One local transaction → the `tx_edn` string the transact lexicon takes: an EDN
    vector of [:db/add e a v] forms + `:ibuki.tx/*` provenance meta (local id / CID /
    prev), so the remote graph can always be mapped back to the local commit-DAG."""
    from datoms import _edn_val
    meta_e = f"ibuki-tx-{tx[':tx/id']}"
    forms = list(tx[":tx/datoms"]) + [
        [":db/add", meta_e, ":ibuki.tx/id", tx[":tx/id"]],
        [":db/add", meta_e, ":ibuki.tx/local-cid", tx[":tx/cid"]],
        [":db/add", meta_e, ":ibuki.tx/local-prev", tx[":tx/prev"]],
        [":db/add", meta_e, ":ibuki.tx/as-of", tx[":tx/as-of"]],
    ]
    return "[" + " ".join(
        "[" + " ".join([d[0], json.dumps(d[1])] + [d[2], _edn_val(d[3])]) + "]"
        for d in forms) + "]"


def operator_bearer() -> str:
    """The operator-tier bearer the transact endpoint requires: a JWT whose `sub` is the
    NODE's own operator DID (`IBUKI_KOTOBA_OPERATOR_DID` env — a PUBLIC identifier, not a
    secret). The kotoba server checks `sub == operator_did` and documents that signature
    verification is the edge/loopback trust boundary's job, so the loopback operator
    sends an explicitly unsigned token. No key material is held or read here."""
    import base64
    did = os.environ.get(ENV_OPERATOR_DID, "")
    if not did:
        raise KotobaBoundaryViolation(
            f"live push requires {ENV_OPERATOR_DID} (the node's public operator DID — "
            "see the node's agent-identity log line or `security find-generic-password "
            "-s etzhayyim.kotoba -a agent-did -w`)")
    enc = lambda d: base64.urlsafe_b64encode(  # noqa: E731
        json.dumps(d, separators=(",", ":")).encode()).decode().rstrip("=")
    return f"{enc({'alg': 'none'})}.{enc({'sub': did})}.unsigned-loopback"


def _default_transport(url: str, body: dict, timeout_s: float = 60.0,
                       operator_auth: bool = True) -> dict:
    """POST a transact. When `operator_auth` (the loopback fallback) → attach the unsigned
    operator bearer. When NOT (the delegated path) → send NO Authorization header: auth is the
    member-signed `cacao_b64` already in the body, and the actor is the principal, not the
    operator."""
    assert_kotoba(url)
    headers = {"Content-Type": "application/json"}
    if operator_auth:
        headers["Authorization"] = f"Bearer {operator_bearer()}"
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout_s) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        # surface the server's reason (e.g. a CACAO-verification message on the delegated
        # path) so a delegation issuer can see WHY a capability was rejected
        raise RuntimeError(f"kotoba transact HTTP {e.code}: {e.read().decode()[:200]}") from None


def bridge_state(txs: list[dict]) -> dict:
    """Replay the durable push cursor from the local log: the LAST `:bridge/*` checkpoint
    wins. Returns {pushed_to, parent_commit}."""
    pushed_to, parent = 0, ""
    for tx in txs:
        for _op, _e, a, v in tx.get(":tx/datoms", []):
            if a == ":bridge/pushed-to-tx":
                pushed_to = v
            elif a == ":bridge/parent-commit":
                parent = v
    return {"pushed_to": pushed_to, "parent_commit": parent}


def push(log_path: pathlib.Path = datoms.LOG_DEFAULT, *, graph: str = DEFAULT_GRAPH,
         endpoint: str = DEFAULT_ENDPOINT, transport=None, live: bool | None = None,
         delegation: dict | None = None, now_epoch: int | None = None) -> dict:
    """Push every local tx the cursor has not yet sent, one transact call per tx, oldest
    first. Live mode requires IBUKI_KOTOBA_LIVE=1 (or live=True); otherwise this is a
    DRY-RUN export. After a live push, ONE `:bridge/*` checkpoint tx is appended.

    Auth principal (the leash, ADR-2606101200 §委任): if a usable member-issued `delegation`
    bundle is given (delegation.is_usable at `now_epoch`, scoped to this graph), the push
    presents the member-signed `cacao_b64` and the organism writes AS ITS OWN actor DID — no
    operator bearer, no held key. If the delegation is absent/expired/mis-scoped, the push
    FALLS BACK to the operator-bearer loopback (the node persisting on the organism's behalf)
    — fail-open, so an unrenewed leash never crashes the organism, it just reverts to the
    node-operator principal."""
    assert_kotoba(endpoint)
    graph_id = graph if graph.startswith("b") and len(graph) > 40 else graph_cid(graph)

    # decide the auth principal for this push
    delegated, deleg_reason = False, "operator-bearer (no delegation supplied)"
    if delegation is not None:
        if now_epoch is None:
            raise KotobaBoundaryViolation(
                "now_epoch required to check a delegation's expiry (caller supplies it — no "
                "wall clock inside ibuki)")
        import delegation as _deleg  # the actor-side leash module
        ok, deleg_reason = _deleg.is_usable(delegation, now_epoch=now_epoch, graph=graph)
        delegated = ok

    txs = datoms.read_log(log_path)
    state = bridge_state(txs)
    pending = [tx for tx in txs if tx[":tx/id"] > state["pushed_to"]]
    bodies = []
    parent = state["parent_commit"]
    for tx in pending:
        body = {"graph": graph_id, "tx_edn": tx_to_edn_vec(tx)}
        if delegated:
            body["cacao_b64"] = delegation["cacao_b64"]   # member-signed capability, presented
        if parent:
            body["expected_parent"] = parent
        bodies.append(body)
        parent = ""   # only the first pending tx chains off the recorded parent; the
        #               rest chain off the commits this very push creates (filled live)
    is_live = (os.environ.get(LIVE_ENV) == "1") if live is None else live
    if not is_live:
        return {"mode": "dry-run", "pending": len(bodies), "bodies": bodies,
                "delegated": delegated, "principal": deleg_reason,
                "pushed_to": state["pushed_to"]}

    t = transport or _default_transport
    remote_cids: list[str] = []
    last_commit = state["parent_commit"]
    datoms_confirmed = 0
    for tx, body in zip(pending, bodies):
        if last_commit:
            body["expected_parent"] = last_commit
        out = t(endpoint, body, operator_auth=not delegated) if transport is None \
            else t(endpoint, body)
        if out.get("status") not in ("ok", "committed", "success"):
            raise RuntimeError(f"kotoba transact refused tx {tx[':tx/id']}: {out}")
        remote_cids.append(out.get("tx_cid", ""))
        last_commit = out.get("commit_cid", "")
        datoms_confirmed += out.get("datom_count", 0)   # the engine's own echo of what landed

    if pending:   # ONE durable checkpoint — the exactly-once cursor
        beat = len(txs) + 1
        e = f"bridge-{beat}"
        body_datoms = [
            datoms.add(e, ":bridge/pushed-to-tx", pending[-1][":tx/id"]),
            datoms.add(e, ":bridge/parent-commit", last_commit),
            datoms.add(e, ":bridge/graph", graph),
            datoms.add(e, ":bridge/endpoint-host", urlsplit(endpoint).netloc),
            datoms.add(e, ":bridge/remote-tx-cids", remote_cids),
            datoms.add(e, ":bridge/beat", beat),
            datoms.add(e, ":bridge/as-of", 2606100000 + beat),
        ]
        ck = datoms.make_tx(body_datoms, tx_id=beat, as_of=2606100000 + beat,
                            prev_cid=datoms.head_cid(log_path))
        datoms.append_tx(ck, log_path)
    return {"mode": "live", "pushed": len(pending), "remote_tx_cids": remote_cids,
            "parent_commit": last_commit, "datoms_confirmed": datoms_confirmed,
            "delegated": delegated, "principal": deleg_reason,
            "pushed_to": pending[-1][":tx/id"] if pending else state["pushed_to"]}


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser(description="ibuki → kotoba engine transact bridge")
    ap.add_argument("--log", type=pathlib.Path, default=datoms.LOG_DEFAULT)
    ap.add_argument("--graph", default=DEFAULT_GRAPH)
    ap.add_argument("--endpoint", default=DEFAULT_ENDPOINT)
    args = ap.parse_args()
    res = push(args.log, graph=args.graph, endpoint=args.endpoint)
    if res["mode"] == "dry-run":
        print(f"# ibuki kotoba-bridge DRY-RUN — {res['pending']} tx pending past cursor "
              f"{res['pushed_to']} (set {LIVE_ENV}=1 to push)")
    else:
        print(f"# ibuki kotoba-bridge — pushed {res['pushed']} tx, head commit "
              f"{res['parent_commit'][:18]}…")
