"""receipts.py — R2: fold member-submission receipts back onto the Datom log.

ADR-2606101200 §R2. member_submit.py produces receipts for records the MEMBER signed and
submitted. This module is the return edge: receipts become `:receipt/*` datoms, so the log
carries the honest, attributed history of what actually went out.

ibuki itself still never asserts `:published` — `:receipt/status` is `:submitted-by-member`,
which states exactly who acted (the member, with their own key) and what happened. The
organism's posts stay `:dry-run` (its own beat truth); the receipt is a separate fact about
the member's act. 縁起: two events, two attributions, one log.

Closed vocab: a receipt status other than "submitted-by-member" raises (this module records
member submissions, nothing else). Stdlib only. Deterministic.
"""

from __future__ import annotations

import json
import pathlib

RECEIPT_STATUS = "submitted-by-member"

REQUIRED_KEYS = ("uri", "of", "queueTs", "collection", "submittedBy", "status")


def read_receipts(path: pathlib.Path) -> list[dict]:
    """Read a receipts NDJSON file (member_submit.write_receipts output). Closed vocab:
    an unknown status raises; a malformed line raises (receipts are evidence, never
    guessed)."""
    p = pathlib.Path(path)
    if not p.exists():
        return []
    out = []
    for i, line in enumerate(p.read_text(encoding="utf-8").splitlines()):
        line = line.strip()
        if not line:
            continue
        r = json.loads(line)
        missing = [k for k in REQUIRED_KEYS if k not in r]
        if missing:
            raise ValueError(f"receipt line {i}: missing keys {missing}")
        if r["status"] != RECEIPT_STATUS:
            raise ValueError(f"receipt line {i}: unknown status {r['status']!r} "
                             f"(closed vocab: {RECEIPT_STATUS!r})")
        out.append(r)
    return out


def receipt_datoms(receipts: list[dict], *, beat: int, as_of: int) -> list[list]:
    """`:receipt/*` assertions for member-submitted records (a NEW entity per receipt —
    append-only attribution history)."""
    from datoms import add
    out: list[list] = []
    for i, r in enumerate(receipts):
        e = f"receipt-{beat}-{i}"
        out += [
            add(e, ":receipt/of", r["of"]),
            add(e, ":receipt/uri", r["uri"]),
            add(e, ":receipt/collection", r["collection"]),
            add(e, ":receipt/queue-ts", r["queueTs"]),
            add(e, ":receipt/submitted-by", r["submittedBy"]),
            add(e, ":receipt/status", ":submitted-by-member"),
            add(e, ":receipt/beat", beat),
            add(e, ":receipt/as-of", as_of),
        ]
    return out


def ingest(receipts_path: pathlib.Path, log_path: pathlib.Path) -> dict:
    """Append one content-addressed tx of receipt datoms to the log (beat = next tx id).
    Returns {count, head}."""
    import datoms
    receipts = read_receipts(receipts_path)
    txs = datoms.read_log(log_path)
    beat = len(txs) + 1
    body = receipt_datoms(receipts, beat=beat, as_of=2606100000 + beat)
    tx = datoms.make_tx(body, tx_id=beat, as_of=2606100000 + beat,
                        prev_cid=datoms.head_cid(log_path))
    datoms.append_tx(tx, log_path)
    chain = datoms.verify_chain(log_path)
    if not chain["ok"]:
        raise RuntimeError(f"kotoba Datom chain broken: {chain}")
    return {"count": len(receipts), "head": datoms.head_cid(log_path)}
