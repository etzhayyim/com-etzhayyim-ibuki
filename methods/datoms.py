"""datoms.py — 息吹 (ibuki) kotoba Datom-log writer + as-of reader. ADR-2606101200 + ADR-2605312345.

The substrate boundary (root CLAUDE.md): canonical state is the **kotoba Datom log** —
content-addressed EAVT assertions, append-only (非終末論). This module is ibuki's write AND
read path onto that log: organism state (joucho mood / heartbeat cadence / lifecycle / posts /
drain envelopes / kaizen feedback) lives HERE, not in ephemeral Python dicts — that was Gap 3
of the organism autonomy survey ("state not in kotoba EAVT as-of history").

At R0 the log materializes as an append-only EDN transaction file
(`data/ibuki.datoms.kotoba.edn`); each transaction is content-addressed (sha256 over its
canonical datoms + the previous tx's CID → a commit-DAG, mirroring shionome ADR-2606072200).
The kotoba engine ingests this exact body shape; nothing here is an SQL / columnar store (N7).

  - make_tx / append_tx / read_log / head_cid / verify_chain — the content-addressed chain
  - fold_entity(txs, e)        → latest attr→value map for one entity (optionally as-of a tx)
  - entities(txs, attr)        → every entity carrying attr (optionally as-of a tx)
  - events_for(txs, of)        → ordered :joucho.event/* stream for one organism (the
                                 replayable mood history — "what was the mood at tx N")

EAVT = [op entity attribute value]; op is :db/add only (append-only — no retraction op exists,
non-eschatological). Stdlib only. Deterministic (caller supplies tx_id + as-of; no wall clock).
"""

from __future__ import annotations

import hashlib
import json
import pathlib
from typing import Any

LOG_DEFAULT = pathlib.Path(__file__).resolve().parents[1] / "data" / "ibuki.datoms.kotoba.edn"


def add(entity: str, attr: str, value: Any) -> list:
    """One append-only EAVT assertion: [:db/add <entity> <attr> <value>]."""
    return [":db/add", entity, attr, value]


# ── content-addressed transaction chain (shionome-isomorphic) ─────────────


def _canonical(datoms: list[list], prev_cid: str) -> bytes:
    """Canonical bytes for content addressing: stable JSON of (prev_cid, datoms)."""
    return json.dumps({"prev": prev_cid, "datoms": datoms},
                      ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def tx_cid(datoms: list[list], prev_cid: str = "") -> str:
    """Content address of a transaction = sha256 over (prev_cid, datoms). Linking prev_cid in
    makes the log a commit-DAG (a tamper of any earlier tx breaks every later CID)."""
    return "b" + hashlib.sha256(_canonical(datoms, prev_cid)).hexdigest()


def make_tx(datoms: list[list], *, tx_id: int, as_of: int, prev_cid: str = "") -> dict:
    """Build a content-addressed transaction. tx_id + as_of are supplied by the caller (no wall
    clock — keeps the log deterministic + resume-safe)."""
    return {
        ":tx/id": tx_id,
        ":tx/as-of": as_of,
        ":tx/prev": prev_cid,
        ":tx/cid": tx_cid(datoms, prev_cid),
        ":tx/count": len(datoms),
        ":tx/datoms": datoms,
    }


def _edn_val(v: Any) -> str:
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return repr(v)
    if isinstance(v, str):
        if v.startswith(":"):
            return v                       # keyword
        return json.dumps(v, ensure_ascii=False)
    if isinstance(v, list):
        return "[" + " ".join(_edn_val(x) for x in v) + "]"
    return json.dumps(str(v), ensure_ascii=False)


def _tx_to_edn(tx: dict) -> str:
    """Serialize one transaction as a single-line EDN map (the kotoba ingest body shape)."""
    datoms = " ".join("[" + " ".join(_edn_val(x) for x in d) + "]" for d in tx[":tx/datoms"])
    return (f'{{:tx/id {tx[":tx/id"]} :tx/as-of {tx[":tx/as-of"]} '
            f':tx/prev {json.dumps(tx[":tx/prev"])} :tx/cid {json.dumps(tx[":tx/cid"])} '
            f':tx/count {tx[":tx/count"]} :tx/datoms [{datoms}]}}')


def append_tx(tx: dict, log_path: pathlib.Path = LOG_DEFAULT) -> str:
    """Append ONE transaction to the append-only log (never rewrites existing lines). Returns
    the tx CID. This is the only mutation: the log only ever grows (非終末論)."""
    log_path.parent.mkdir(parents=True, exist_ok=True)
    if not log_path.exists():
        log_path.write_text(";; ibuki kotoba Datom log — append-only EAVT transactions "
                            "(content-addressed DAG). DO NOT hand-edit. ADR-2606101200.\n",
                            encoding="utf-8")
    with log_path.open("a", encoding="utf-8") as fh:
        fh.write(_tx_to_edn(tx) + "\n")
    return tx[":tx/cid"]


def read_log(log_path: pathlib.Path = LOG_DEFAULT) -> list[dict]:
    """Read the log back as a list of transaction dicts (uses the shared _edn reader)."""
    from _edn import _parse, _tokens
    if not log_path.exists():
        return []
    txs = []
    for line in log_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith(";"):
            continue
        txs.append(_parse(_tokens(line)))
    return txs


def head_cid(log_path: pathlib.Path = LOG_DEFAULT) -> str:
    """The content-addressed HEAD = the last transaction's CID."""
    txs = read_log(log_path)
    return txs[-1][":tx/cid"] if txs else ""


def verify_chain(log_path: pathlib.Path = LOG_DEFAULT) -> dict:
    """Recompute every CID from its datoms + prev and verify the DAG is intact (no tampering).
    Returns {ok, length, broken_at}. The integrity proof of the append-only log."""
    txs = read_log(log_path)
    prev = ""
    for i, tx in enumerate(txs):
        expect = tx_cid(tx.get(":tx/datoms", []), prev)
        if tx.get(":tx/cid") != expect or tx.get(":tx/prev") != prev:
            return {"ok": False, "length": len(txs), "broken_at": i}
        prev = tx[":tx/cid"]
    return {"ok": True, "length": len(txs), "broken_at": -1}


# ── as-of readers (the EAVT fold — Gap 3 closure) ─────────────────────────


def _iter_datoms(txs: list[dict], up_to_tx: int | None = None):
    """Yield (tx_id, [op e a v]) over the log in transaction order, optionally bounded by an
    inclusive tx id — the as-of cut."""
    for tx in txs:
        if up_to_tx is not None and tx[":tx/id"] > up_to_tx:
            continue
        for d in tx.get(":tx/datoms", []):
            yield tx[":tx/id"], d


def fold_entity(txs: list[dict], entity: str, *, up_to_tx: int | None = None) -> dict:
    """Latest attr→value map for one entity as of tx `up_to_tx` (append-only fold: a later
    assertion of the same attr shadows the earlier one; history stays in the log)."""
    out: dict[str, Any] = {}
    for _, (_op, e, a, v) in _iter_datoms(txs, up_to_tx):
        if e == entity:
            out[a] = v
    return out


def entities(txs: list[dict], attr: str, *, up_to_tx: int | None = None) -> list[str]:
    """Every entity (first-assertion order, deduped) carrying `attr` as of `up_to_tx`."""
    seen: dict[str, None] = {}
    for _, (_op, e, a, _v) in _iter_datoms(txs, up_to_tx):
        if a == attr:
            seen.setdefault(e, None)
    return list(seen)


def events_for(txs: list[dict], of: str, *, up_to_tx: int | None = None) -> list[str]:
    """Ordered :joucho.event/kind stream for one organism — the replayable mood history.
    `joucho.replay_events(baseline, events_for(...))` answers "what was the mood at tx N"."""
    out: list[str] = []
    by_entity: dict[str, dict] = {}
    order: list[str] = []
    for _, (_op, e, a, v) in _iter_datoms(txs, up_to_tx):
        if a in (":joucho.event/of", ":joucho.event/kind"):
            if e not in by_entity:
                by_entity[e] = {}
                order.append(e)
            by_entity[e][a] = v
    for e in order:
        ev = by_entity[e]
        if ev.get(":joucho.event/of") == of and ":joucho.event/kind" in ev:
            out.append(ev[":joucho.event/kind"])
    return out
