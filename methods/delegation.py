"""delegation.py — the organism lives under a revocable LEASH, not a held key. ADR-2606101200 §委任.

For an autonomous life the right credential model is neither a held root key (prohibited — the
platform/actor must hold no member key, ADR-2605231525) nor a per-beat human presence (a passkey
can't be touched every beat). It is a **scoped, expiring, revocable capability delegated to the
organism's OWN runtime** — the kotoba CACAO / DelegationChain model (`kotoba-auth`: capability
`datom:transact`, a graph CID resource, `exp`, `aud`, `nonce`).

How kotoba's CACAO actually binds (verified against `kotoba-auth::delegation`, 2026-06-11):
  - `aud` is the kotoba NODE's DID (the audience the capability is presented TO — the server checks
    `cacao.p.aud == operator_did`). It is NOT the organism's DID. The organism is the BEARER that
    holds + presents the bytes; the capability is scoped to (node, graph, capability, expiry), not
    bound to a named delegatee.
  - `write_author = the ISSUING MEMBER` (the server returns the issuer DID as the principal). So the
    colony's autonomous writes are ATTRIBUTED to the member who leashed it — accountability flows to
    the consenting human, time-bounded + revocable. That is the point: autonomy WITH a named, on-the-
    record human principal (相互監視 / 共生 by consent), never an anonymous self-acting agent.

Division of trust (this is the whole point):
  - ISSUANCE is a human act: a member, present with their passkey/wallet, SIGNS a CACAO granting
    `datom:transact @ graph:ibuki, exp:+Nd`, audience = the node DID. ibuki holds NO member key and
    does NOT sign — issuance happens in the member's own runtime (the `tools/issue_delegation.py`
    member tool; see OPERATIONS.md). The bundle the member hands ibuki is
    `{cacao_b64, aud, capability, graph, exp, nonce}` — `cacao_b64` is the member-signed CBOR
    (opaque to ibuki), the rest is sidecar metadata ibuki reads to gate itself.
  - INVOCATION is the organism's autonomous act: each push it PRESENTS the opaque `cacao_b64`.
    The kotoba server verifies the issuer's Ed25519 signature + capability + graph + aud + expiry
    and sets write_author = issuer; no invoker signature is required, so ibuki presenting bytes is
    enough (and stays stdlib-only — ibuki never does crypto).
  - REVOCATION is consent withdrawn: when `exp` passes, ibuki self-disables the delegated path and
    falls back (local log only) until a member re-issues. Stop re-issuing → the organism quietly
    retires. Autonomy is LEASED, never owned.

Stdlib only. Deterministic: expiry is checked against a caller-supplied `now_epoch` (no wall clock
inside any method — the live push boundary supplies it).
"""

from __future__ import annotations

import json
import pathlib

CAPABILITY = "datom:transact"          # the only capability ibuki's autonomous loop ever needs
REQUIRED_KEYS = ("cacao_b64", "aud", "capability", "graph", "exp", "nonce")


class DelegationError(ValueError):
    """Raised when a delegation bundle is malformed or used outside its scope."""


def load(path: pathlib.Path) -> dict | None:
    """Load a member-issued delegation bundle (JSON sidecar). Returns None if absent — the
    organism then falls back to local-log-only (fail-open, never crashes)."""
    p = pathlib.Path(path)
    if not p.exists():
        return None
    bundle = json.loads(p.read_text(encoding="utf-8"))
    missing = [k for k in REQUIRED_KEYS if k not in bundle]
    if missing:
        raise DelegationError(f"delegation bundle missing keys {missing}")
    if bundle["capability"] != CAPABILITY:
        raise DelegationError(f"delegation capability {bundle['capability']!r} != {CAPABILITY!r} "
                              "(ibuki's autonomous loop only ever needs datom:transact)")
    if not bundle.get("aud", "").startswith("did:"):
        raise DelegationError("delegation audience must be a DID (the kotoba node it is presented "
                              "to — kotoba checks cacao.aud == operator_did)")
    if not bundle.get("nonce"):
        raise DelegationError("delegation must carry a nonce (replay protection)")
    return bundle


def is_usable(bundle: dict | None, *, now_epoch: int, graph: str) -> tuple[bool, str]:
    """May the organism present this delegation to write `graph` at `now_epoch`? Pure function
    of the bundle metadata — the same answer before and after a restart (the leash, checked).
    `now_epoch` is supplied by the caller (the live push boundary); no wall clock here."""
    if bundle is None:
        return False, "no delegation (local-log-only until a member issues one)"
    if bundle["graph"] != graph:
        return False, (f"delegation scoped to graph {bundle['graph']!r}, not {graph!r} — "
                       "a capability is never used outside its resource")
    if now_epoch >= int(bundle["exp"]):
        return False, (f"delegation expired (exp {bundle['exp']} <= now {now_epoch}) — "
                       "consent must be renewed; the organism falls back to local log")
    return True, f"usable (expires {bundle['exp']}, aud {bundle['aud']})"


def audience(bundle: dict) -> str:
    """The DID this capability is presented TO — the kotoba node's operator DID (kotoba checks
    `cacao.p.aud == operator_did`). NOT the organism's DID; the organism is the bearer."""
    return bundle["aud"]


def issuance_template(*, member_did: str, node_did: str, graph_cid: str, exp_iso: str,
                      nonce_hex: str) -> dict:
    """The CACAO PAYLOAD a member must sign to issue the delegation — emitted for the member's
    OWN signing runtime (ibuki does NOT sign; this is just the shape). The member's tool
    (`tools/issue_delegation.py`) turns this into the Ed25519-signed `cacao_b64` and writes the
    bundle. Mirrors kotoba_auth::CacaoPayload EXACTLY (verified 2026-06-11):
      - `aud` is the NODE DID (audience), not the organism — kotoba checks aud == operator_did;
      - `resources` is the SIWE form: TWO entries, `kotoba://can/<cap>` + `kotoba://graph/<cid>`
        (NOT a `?capability=` query string — that form is never parsed);
      - `iat`/`exp` are UTC ISO-8601 strings (`2026-07-11T00:00:00Z`);
      - write_author resolves to `iss` (the member) — the on-the-record human principal."""
    return {
        "iss": member_did,                 # the member (signer) — the on-record write principal
        "aud": node_did,                   # the kotoba node (audience the capability is used at)
        "exp": exp_iso,                    # consent has a horizon; renewal = re-consent
        "nonce": nonce_hex,                # replay protection (kotoba requires non-empty)
        "version": "1",
        "resources": [f"kotoba://can/{CAPABILITY}", f"kotoba://graph/{graph_cid}"],
        "_note": ("member signs this with their OWN key (passkey/wallet) in their own runtime; "
                  "ibuki holds no key and never signs — present-only (ADR-2605231525). "
                  "kotoba attributes the write to iss (the member) — accountability by consent."),
    }
