"""drainer.py — Wave-3 post-queue drainer (member-signed, never server-signed). ADR-2606101200.

Closes Gap 6 of the organism autonomy survey ("posts stay in the NDJSON queue — the Wave-3
drainer was never built"). Consumes the exact line schema the kotodama NdjsonQueuePostSink
emits (ADR-2605240100 §Line schema, v=1: v / ts / actorDid / code / title / mood /
contentSourceKind / text / lexicon / createdAt) and turns each line into a
`com.atproto.repo.createRecord`-shaped ENVELOPE that is ready for a MEMBER to sign.

The no-server-key invariant (ADR-2605231525) is structural here, not configurational:

  - an envelope always carries `requiresMemberSignature: true` + `serverHeldKey: false`;
  - this module holds NO credential, reads NO key material, and has NO network path;
  - `submit()` only forwards envelopes to an externally-INJECTED `member_signer` callable
    (the member's own runtime — e.g. their passkey-derived session in ameno) and refuses
    without one AND an explicit `operator_ack=True`. Absent both, posting is impossible,
    not merely disabled.

Drained envelopes are checkpointed to the kotoba log as `:drain/*` datoms with status
`:prepared` — `:published` is NOT writable by ibuki (only a member-signed submission receipt,
ingested separately, may ever assert it). Stdlib only. Deterministic.
"""

from __future__ import annotations

import json
import pathlib

SCHEMA_VERSION = 1

REQUIRED_KEYS = ("v", "ts", "actorDid", "mood", "contentSourceKind", "text",
                 "lexicon", "createdAt")


class MemberSignatureRequired(RuntimeError):
    """Raised when submission is attempted without an injected member signer + operator ack
    (ADR-2605231525 no-server-key)."""


def parse_queue(queue_path: pathlib.Path) -> tuple[list[dict], list[str]]:
    """Read the NDJSON post queue. Returns (valid posts, rejection reasons). Unknown schema
    versions and missing keys are rejected, never guessed."""
    posts: list[dict] = []
    errors: list[str] = []
    if not pathlib.Path(queue_path).exists():
        return posts, errors
    for i, line in enumerate(pathlib.Path(queue_path).read_text(encoding="utf-8").splitlines()):
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except ValueError:
            errors.append(f"line {i}: not JSON")
            continue
        if obj.get("v") != SCHEMA_VERSION:
            errors.append(f"line {i}: unknown schema version {obj.get('v')!r}")
            continue
        missing = [k for k in REQUIRED_KEYS if k not in obj]
        if missing:
            errors.append(f"line {i}: missing keys {missing}")
            continue
        posts.append(obj)
    return posts, errors


def envelope(post: dict) -> dict:
    """One member-sign-ready createRecord envelope for one queue line. The platform never
    signs: requiresMemberSignature/serverHeldKey are structural constants."""
    return {
        "xrpc": "com.atproto.repo.createRecord",
        "repo": post["actorDid"],
        "collection": post["lexicon"],
        "record": {
            "$type": post["lexicon"],
            "text": post["text"],
            "createdAt": post["createdAt"],
        },
        "queueTs": post["ts"],
        "requiresMemberSignature": True,
        "serverHeldKey": False,
    }


def drain(queue_path: pathlib.Path, *, as_of: int, beat: int) -> dict:
    """Drain the queue into envelopes + `:drain/*` datoms (status `:prepared` — the ONLY
    status this module can write). Returns {envelopes, datoms, errors}."""
    from datoms import add
    posts, errors = parse_queue(queue_path)
    envelopes = [envelope(p) for p in posts]
    out: list[list] = []
    for i, (p, env) in enumerate(zip(posts, envelopes)):
        e = f"drain-{beat}-{i}"
        out += [
            add(e, ":drain/of", p["actorDid"]),
            add(e, ":drain/lexicon", env["collection"]),
            add(e, ":drain/queue-ts", p["ts"]),
            add(e, ":drain/status", ":prepared"),
            add(e, ":drain/requires-member-sig", True),
            add(e, ":drain/server-held-key", False),
            add(e, ":drain/beat", beat),
            add(e, ":drain/as-of", as_of),
        ]
    return {"envelopes": envelopes, "datoms": out, "errors": errors}


def submit(envelopes: list[dict], *, member_signer=None, operator_ack: bool = False) -> list:
    """Forward envelopes to the MEMBER's own signing runtime. Refuses without an injected
    signer + an explicit operator ack — ibuki holds no key, so absent injection, live posting
    is structurally impossible (G7/G8)."""
    if member_signer is None:
        raise MemberSignatureRequired(
            "no member signer injected — the platform holds no key (ADR-2605231525); "
            "posting requires the member's own signing runtime")
    if not operator_ack:
        raise MemberSignatureRequired(
            "operator_ack=True required — outward posting is operator-gated (G8)")
    return [member_signer(env) for env in envelopes]
