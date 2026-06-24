#!/usr/bin/env python3
"""issue_delegation.py — the MEMBER-side tool that mints a revocable CACAO leash for ibuki.

THIS IS NOT PART OF THE ibuki ACTOR. The actor (`methods/*.py`) is stdlib-only and never signs.
This is the human's OWN signing runtime (ADR-2605231525 §委任): a member runs it on their own
machine, with their own key, to issue a scoped + expiring capability the organism then PRESENTS.
It therefore MAY use a crypto library (`cryptography`) — the no-crypto rule binds the actor, not
the member's tool.

What it produces, verified byte-correct against `kotoba-auth::{cacao,delegation}` on 2026-06-11:
  - a CAIP-122/SIWE CACAO {h:{t:eip4361}, p:{iss,aud,iat,exp,nonce,domain,version,resources}, s}
    CBOR-encoded, base64 → `cacao_b64`;
  - signed with the member's Ed25519 key over the exact `siwe_message()` plaintext the server
    reconstructs (did:key issuer ⇒ Chain ID 1, address = the z6Mk… segment);
  - `aud` = the NODE's operator DID (kotoba checks `cacao.p.aud == operator_did`);
  - `resources` = ["kotoba://can/datom:transact", "kotoba://graph/<graph-cid>"];
  - write_author resolves to the member (`iss`) — the on-the-record human principal.

Output: the `{cacao_b64, aud, capability, graph, exp, nonce}` JSON sidecar bundle that
`methods/delegation.py` loads and `methods/kotoba_bridge.push(delegation=…)` presents.

  Generate a throwaway member key + issue a 30-day leash on graph 'ibuki':
    python3 issue_delegation.py --node-did did:key:zABC… --graph ibuki \
        --exp 2026-07-11T00:00:00Z --out ../data/ibuki-delegation.json --gen-key

  Issue from an existing member key (32-byte Ed25519 seed, hex):
    python3 issue_delegation.py --node-did did:key:zABC… --graph ibuki \
        --exp 2026-07-11T00:00:00Z --member-seed-hex <64hex> --out …

The seed is the MEMBER's secret — never commit it, never hand it to ibuki. ibuki only ever sees
`cacao_b64` (opaque) + the sidecar metadata. Revoke by letting `exp` pass (stop re-issuing).
"""
from __future__ import annotations

import argparse
import base64
import calendar
import datetime
import hashlib
import json
import sys

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
except ImportError:
    sys.exit("issue_delegation requires `cryptography` (member-side tool; ibuki the actor stays "
             "stdlib-only). Install: pip install cryptography")

CAPABILITY = "datom:transact"
_B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"


def _b58(b: bytes) -> str:
    n = int.from_bytes(b, "big")
    out = ""
    while n:
        n, r = divmod(n, 58)
        out = _B58[r] + out
    return "1" * (len(b) - len(b.lstrip(b"\0"))) + out


def did_key_from_pubkey(pub: bytes) -> str:
    """Raw 32-byte Ed25519 pubkey → did:key:z6Mk… (multicodec 0xed01 + base58btc)."""
    return "did:key:z" + _b58(bytes([0xed, 0x01]) + pub)


def graph_cid(name: str) -> str:
    """KotobaCid::from_bytes(name) — CIDv1 dag-cbor sha2-256, base32lower. Matches the engine."""
    raw = bytes([0x01, 0x71, 0x12, 0x20]) + hashlib.sha256(name.encode("utf-8")).digest()
    return "b" + base64.b32encode(raw).decode("ascii").rstrip("=").lower()


def siwe_message(p: dict) -> str:
    """Reconstruct the EIP-4361 plaintext kotoba_auth::Cacao::siwe_message() signs/verifies.
    did:key issuer ⇒ address = last colon segment, Chain ID = 1 (CAIP-122 default)."""
    addr = p["iss"].split(":")[-1]
    lines = [f"{p['domain']} wants you to sign in with your Ethereum account:", addr, ""]
    if p.get("statement"):
        lines += [p["statement"], ""]
    lines += [f"URI: {p['aud']}", f"Version: {p['version']}", "Chain ID: 1",
              f"Nonce: {p['nonce']}", f"Issued At: {p['iat']}"]
    if p.get("exp"):
        lines.append(f"Expiration Time: {p['exp']}")
    if p.get("resources"):
        lines.append("Resources:")
        lines += [f"- {r}" for r in p["resources"]]
    return "\n".join(lines)


# ---- minimal definite-length CBOR (small maps/arrays of text strings) ----
def _cbor_str(s: str) -> bytes:
    b = s.encode("utf-8")
    n = len(b)
    if n < 24:
        head = bytes([0x60 | n])
    elif n < 256:
        head = bytes([0x78, n])
    else:
        head = bytes([0x79, n >> 8, n & 0xFF])
    return head + b


def _cbor_arr(items: list[bytes]) -> bytes:
    return bytes([0x80 | len(items)]) + b"".join(items)


def _cbor_map(d: dict) -> bytes:
    out = bytes([0xA0 | len(d)])
    for k, v in d.items():
        out += _cbor_str(k) + v
    return out


def build_cacao(payload: dict, sig_b64: str) -> bytes:
    p = {"iss": _cbor_str(payload["iss"]), "aud": _cbor_str(payload["aud"]),
         "iat": _cbor_str(payload["iat"]), "exp": _cbor_str(payload["exp"]),
         "nonce": _cbor_str(payload["nonce"]), "domain": _cbor_str(payload["domain"]),
         "version": _cbor_str(payload["version"]),
         "resources": _cbor_arr([_cbor_str(r) for r in payload["resources"]])}
    return _cbor_map({"h": _cbor_map({"t": _cbor_str("eip4361")}),
                      "p": _cbor_map(p),
                      "s": _cbor_map({"t": _cbor_str("EdDSA"), "s": _cbor_str(sig_b64)})})


def issue(*, node_did: str, graph: str, iat: str, exp: str, nonce: str,
          member_seed: bytes, domain: str = "kotoba.etzhayyim.com") -> dict:
    sk = Ed25519PrivateKey.from_private_bytes(member_seed)
    pub = sk.public_key().public_bytes(serialization.Encoding.Raw,
                                        serialization.PublicFormat.Raw)
    member_did = did_key_from_pubkey(pub)
    gcid = graph_cid(graph)
    payload = {"iss": member_did, "aud": node_did, "iat": iat, "exp": exp, "nonce": nonce,
               "domain": domain, "version": "1",
               "resources": [f"kotoba://can/{CAPABILITY}", f"kotoba://graph/{gcid}"]}
    sig = sk.sign(siwe_message(payload).encode("utf-8"))
    cacao = build_cacao(payload, base64.b64encode(sig).decode("ascii"))
    # the sidecar `exp` is the EPOCH form `delegation.is_usable` self-gates on (the actor checks a
    # caller-supplied now_epoch); the CACAO inside carries the ISO form kotoba verifies — same
    # instant, two representations. They MUST agree (this is the single conversion point).
    dt = datetime.datetime.strptime(exp, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=datetime.timezone.utc)
    exp_epoch = calendar.timegm(dt.timetuple())
    return {"cacao_b64": base64.b64encode(cacao).decode("ascii"),
            "aud": node_did, "capability": CAPABILITY, "graph": graph,
            "exp": exp_epoch, "exp_iso": exp, "nonce": nonce, "_issuer": member_did,
            "_note": "member-signed; ibuki presents this, never signs. Revoke by letting exp pass."}


def main() -> None:
    ap = argparse.ArgumentParser(description="member-side CACAO leash issuer for ibuki")
    ap.add_argument("--node-did", required=True, help="kotoba node operator DID (the audience)")
    ap.add_argument("--graph", default="ibuki", help="graph name to scope the capability to")
    ap.add_argument("--iat", default="2026-06-11T00:00:00Z", help="Issued At (UTC ISO-8601)")
    ap.add_argument("--exp", required=True, help="Expiration Time (UTC ISO-8601) — the leash horizon")
    ap.add_argument("--nonce", default="ibuki0001", help="replay nonce (non-empty)")
    grp = ap.add_mutually_exclusive_group(required=True)
    grp.add_argument("--member-seed-hex", help="32-byte Ed25519 seed (hex) — the member's secret")
    grp.add_argument("--gen-key", action="store_true", help="generate a throwaway member key")
    ap.add_argument("--out", required=True, help="write the delegation bundle JSON here")
    a = ap.parse_args()

    if a.gen_key:
        seed = Ed25519PrivateKey.generate().private_bytes(
            serialization.Encoding.Raw, serialization.PrivateFormat.Raw,
            serialization.NoEncryption())
        print(f"# generated member seed (SECRET — store safely, never commit): {seed.hex()}",
              file=sys.stderr)
    else:
        seed = bytes.fromhex(a.member_seed_hex)
        if len(seed) != 32:
            sys.exit("member seed must be 32 bytes (64 hex chars)")

    bundle = issue(node_did=a.node_did, graph=a.graph, iat=a.iat, exp=a.exp,
                   nonce=a.nonce, member_seed=seed)
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(bundle, f, indent=2)
        f.write("\n")
    print(f"wrote leash → {a.out}  (issuer {bundle['_issuer'][:30]}…, graph {a.graph}, exp {a.exp})")


if __name__ == "__main__":
    main()
