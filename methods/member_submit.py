"""member_submit.py — R2: the member-principal live-posting runtime. ADR-2606101200 §R2.

The Wave-3 drainer (drainer.py) prepares member-sign-ready envelopes and structurally cannot
post. THIS module is the other half: the runtime a MEMBER runs, with the member's OWN
credentials, to sign and submit those envelopes to the member's OWN PDS. The no-server-key
invariant (ADR-2605231525) is kept structural, not waived, by construction:

  - credentials come ONLY from the member's runtime env (`IBUKI_MEMBER_HANDLE` /
    `IBUKI_MEMBER_APP_PASSWORD` / `IBUKI_MEMBER_PDS`) at invocation time — nothing is
    committed, cached, or platform-held; missing env → `MemberSignatureRequired`;
  - it REFUSES to run in a scheduled/cron context (`IBUKI_CRON=1` → refusal): a platform
    CronJob may never hold member credentials. This is the member at their own keyboard
    (the karakuri/okaimono member-principal model), not the platform acting;
  - submission still flows through `drainer.submit(...)` — the same injected-signer +
    explicit-operator-ack gate the structural tests pin down;
  - ibuki itself never asserts `:published`. Submission produces RECEIPTS (receipts.py)
    attributed to the member — `:receipt/status :submitted-by-member` — which is what
    actually happened.

Per the founder direction of 2026-06-10 the Council gate is exercised as PR review+merge:
this code path is complete to the end; *running* it remains a member's explicit act.

Stdlib only (urllib over https).
"""

from __future__ import annotations

import json
import os
import pathlib
import urllib.request

import drainer
from drainer import MemberSignatureRequired

ENV_HANDLE = "IBUKI_MEMBER_HANDLE"
ENV_APP_PASSWORD = "IBUKI_MEMBER_APP_PASSWORD"
ENV_PDS = "IBUKI_MEMBER_PDS"
ENV_CRON = "IBUKI_CRON"

DEFAULT_PDS = "https://bsky.social"


def _http_json(url: str, body: dict | None = None, headers: dict | None = None,
               timeout_s: float = 20.0) -> dict:
    """POST (or GET when body is None) JSON over https. The default transport; tests
    inject a fake."""
    if not url.startswith("https://"):
        raise MemberSignatureRequired(f"member PDS must be https, got {url!r}")
    data = json.dumps(body).encode("utf-8") if body is not None else None
    hdrs = {"Content-Type": "application/json"}
    hdrs.update(headers or {})
    req = urllib.request.Request(url, data=data, headers=hdrs)
    with urllib.request.urlopen(req, timeout=timeout_s) as resp:
        return json.loads(resp.read().decode("utf-8"))


def refuse_if_cron() -> None:
    """A scheduled platform context may never hold member credentials (G7)."""
    if os.environ.get(ENV_CRON) == "1":
        raise MemberSignatureRequired(
            "refusing to submit from a cron/scheduled context (IBUKI_CRON=1) — member "
            "credentials belong to the member's own interactive runtime, never a platform "
            "job (ADR-2605231525)")


def create_member_session(transport=None) -> dict:
    """com.atproto.server.createSession with the MEMBER's own env credentials. Returns
    {pds, did, handle, accessJwt}. Missing env → MemberSignatureRequired (the platform
    holds no key to fall back to)."""
    refuse_if_cron()
    handle = os.environ.get(ENV_HANDLE, "")
    password = os.environ.get(ENV_APP_PASSWORD, "")
    if not handle or not password:
        raise MemberSignatureRequired(
            f"no member credentials in env ({ENV_HANDLE} / {ENV_APP_PASSWORD}) — the "
            "platform holds no key; the member supplies their own session at runtime")
    pds = os.environ.get(ENV_PDS, DEFAULT_PDS).rstrip("/")
    t = transport or _http_json
    out = t(f"{pds}/xrpc/com.atproto.server.createSession",
            {"identifier": handle, "password": password})
    return {"pds": pds, "did": out["did"], "handle": out.get("handle", handle),
            "accessJwt": out["accessJwt"]}


def member_signer(session: dict, transport=None):
    """The injectable signer drainer.submit() requires: one envelope → one member-signed
    com.atproto.repo.createRecord on the MEMBER's PDS → one receipt."""
    t = transport or _http_json

    def sign(envelope: dict) -> dict:
        out = t(f"{session['pds']}/xrpc/com.atproto.repo.createRecord",
                {"repo": session["did"],
                 "collection": envelope["collection"],
                 "record": envelope["record"]},
                {"Authorization": f"Bearer {session['accessJwt']}"})
        return {"uri": out.get("uri", ""), "cid": out.get("cid", ""),
                "of": envelope["repo"], "queueTs": envelope["queueTs"],
                "collection": envelope["collection"],
                "submittedBy": session["did"], "status": "submitted-by-member"}

    return sign


def submit_queue(queue_path: pathlib.Path, *, from_line: int = 0, operator_ack: bool = False,
                 transport=None, session: dict | None = None) -> dict:
    """Submit the valid queue posts with index ≥ from_line AS THE MEMBER. Returns
    {receipts, errors, next_line} where next_line is the post index to resume from.
    Flows through drainer.submit's structural gate (injected signer + operator ack)."""
    refuse_if_cron()
    posts, errors = drainer.parse_queue(queue_path)
    pending = posts[from_line:]
    envelopes = [drainer.envelope(p) for p in pending]
    sess = session or create_member_session(transport=transport)
    receipts = drainer.submit(envelopes, member_signer=member_signer(sess, transport),
                              operator_ack=operator_ack)
    return {"receipts": receipts, "errors": errors, "next_line": from_line + len(pending)}


# the ONLY keys a receipt file may carry — a structural whitelist so no credential-shaped
# field (password / accessJwt / Authorization) can ever reach disk, whatever a future
# receipt dict accidentally contains
RECEIPT_FILE_KEYS = ("uri", "cid", "of", "queueTs", "collection", "submittedBy", "status")


def write_receipts(receipts: list[dict], path: pathlib.Path) -> int:
    """Append receipts as NDJSON (receipts.py folds them onto the Datom log). Each line is
    rebuilt from the RECEIPT_FILE_KEYS whitelist — credentials are unrepresentable in this
    file by construction."""
    p = pathlib.Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    with p.open("a", encoding="utf-8") as fh:
        for r in receipts:
            safe = {k: r[k] for k in RECEIPT_FILE_KEYS if k in r}
            fh.write(json.dumps(safe, ensure_ascii=False, sort_keys=True) + "\n")
    return len(receipts)


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser(
        description="ibuki member-principal submitter — run as the MEMBER, with the "
                    "member's own credentials in env; refuses cron contexts")
    ap.add_argument("--queue", type=pathlib.Path, required=True)
    ap.add_argument("--receipts", type=pathlib.Path, required=True)
    ap.add_argument("--from-line", type=int, default=0)
    ap.add_argument("--yes", action="store_true",
                    help="explicit operator acknowledgement (required)")
    args = ap.parse_args()
    if not args.yes:
        raise SystemExit("refusing without --yes (explicit operator acknowledgement)")
    res = submit_queue(args.queue, from_line=args.from_line, operator_ack=True)
    n = write_receipts(res["receipts"], args.receipts)
    print(f"# ibuki member-submit — {n} records submitted by the member, "
          f"{len(res['errors'])} queue errors, receipts → {args.receipts}")
