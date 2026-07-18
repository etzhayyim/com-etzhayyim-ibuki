# ibuki (息吹) — operations runbook + live-run verification

ADR-2606101200 / 2606101800. This is the honest operational picture: what runs **live** today,
what is **member/operator-gated** (Tier-1, never the platform's act), and what is a **physical
operator deploy** outside any agent's reach.

## Standalone runtime status

The former Python operator entrypoints have been retired. Canonical actor logic is CLJC and
configuration/state is EDN. Invoke the hermetic suite with `./run_tests.sh`; live operator I/O
must be wired by an external member/operator host that calls the CLJC boundaries below.

## Operator-authorised boundaries

These flags do real I/O against the operator's own loopback/fleet + read-only public endpoints.
None of them holds a member credential or a platform signing key.

| flag | effect | substrate |
|---|---|---|
| `IBUKI_PERCEPTION_LIVE=1` | read-only public-AppView observation | `methods/perception.cljc` |
| `IBUKI_MURAKUMO_LIVE=1` | Murakumo narration, fail-open to template | `methods/infer.cljc` |
| `IBUKI_KOTOBA_LIVE=1` + operator DID | persist Datom transactions | `methods/kotoba_bridge.cljc` |

The actor repository deliberately contains no member key or autonomous live-I/O launcher.

### Live-run verification — 2026-06-10 (this session)

A real production cycle was executed and confirmed:

- **perception LIVE**: a read-only fetch of `bsky.app` returned a real follower count
  (33,623,191) — the membrane works against the live public AppView.
- **murakumo LIVE → fail-open**: the LiteLLM gateway (:4000) was down; narration fell back to
  the deterministic template — fail-open verified **in production**, the colony kept living.
- **12-beat life**: chain verified, `healthy=True`, eco-maturity 1.0, commons offered 500
  (all available — never self-drawn), fruited 3×; the colony's own digest:
  > 息吹 colony report: 3 organisms, healthy; ecological maturity 1.0. The web has offered
  > 374 nutrient of commons to humanity (374 still available to draw); fruited 2 times.
  > A mirror of where the colony's life became a gift — no advice.
- **kotoba engine LIVE**: 12 tx pushed → **2,386 datoms confirmed by the node**, IPNS head
  advanced; exactly-once re-push (only the bridge checkpoint) confirmed.

## What is member/operator-gated (NEVER the platform's act — Tier-1, ADR-2605231525)

These exist as complete code paths but require a credential the platform does not hold and must
not fabricate. They are run BY the member/operator, not by ibuki or any agent:

- **member-principal posting** (`member_submit.py`) — the MEMBER's own `IBUKI_MEMBER_*` env
  credentials, https only, `--yes` required, **cron contexts refused**. ibuki only PREPARES
  member-sign-ready envelopes (`:drain/status :prepared`); it never asserts `:published`.
- **commons draw** (`symbiosis.draw`) — a MEMBER draws the colony's commons gift with their own
  signer + operator ack. ibuki **never auto-draws** (the colony does not consume its own gift).
- **kaizen outcome collection** (`kaizen_outcomes.py`) — the OPERATOR's own `gh` auth,
  read-only, cron-refusing.

## What is a physical operator deploy (outside any agent — ADR-2606071000)

- **continuous fleet operation**: `cells/fleet_beat/cell.py` `.solve()` runs the durable beat
  and is registered on joseph/issachar/dan in `50-infra/murakumo/fleet.edn` (cron 3/33/43).
  Turning it into a running k3s DaemonSet via the Ansible playbook on the physical Mac-mini
  fleet is the operator's hardware step — one human action away.

## The boundary in one line

The colony **lives, refines a commons gift, measures its own health, and reasons about itself**
fully autonomously on the real substrate today. Every step where it would **act on a human**
(post as a member, draw the gift, deploy onto hardware) is held by a member/operator key the
platform structurally does not possess — 共生 by consent, not by fabrication.

## Autonomous identity — the revocable leash (ADR-2606101200 §委任, a改 2026-06-10)

How an autonomous life persists to kotoba without a held key and without per-beat human
presence: a member ISSUES a scoped, expiring CACAO delegation; the organism PRESENTS it each
push (`delegation.py` + `kotoba_bridge` delegated mode). The member-side minting tool is
`tools/issue_delegation.py` (member's own runtime — MAY use `cryptography`; the actor stays
stdlib-only).

How kotoba's CACAO actually binds (verified against `kotoba-auth::{cacao,delegation}` 2026-06-11):
- `aud` = the kotoba **NODE's** operator DID (the server checks `cacao.p.aud == operator_did`) —
  **NOT** the organism's DID. The organism is the *bearer* that holds + presents the bytes; the
  capability is scoped to (node, graph, capability, expiry), not bound to a named delegatee.
- `write_author` = the **issuing MEMBER** (the server returns `iss` as the write principal). The
  colony's autonomous writes are therefore ATTRIBUTED to the member who leashed it — accountability
  flows to a named, on-the-record human, time-bounded + revocable. That is the model: autonomy WITH
  a consenting human principal (相互監視 / 共生 by consent), never an anonymous self-acting agent.

| step | who | what | key custody |
|---|---|---|---|
| ISSUE | the MEMBER (present, with key) | `python3 tools/issue_delegation.py --node-did <node DID> --graph ibuki --exp <UTC ISO> [--gen-key\|--member-seed-hex …] --out <bundle.json>` → an Ed25519-signed CACAO (`capability=datom:transact`, `resources=[kotoba://can/datom:transact, kotoba://graph/<cid>]`, `aud=<node DID>`) + the sidecar bundle `{cacao_b64, aud, capability, graph, exp, nonce}` | member's own key, member's runtime — **ibuki holds no key, never signs** |
| INVOKE | the ORGANISM (autonomous, every beat) | `kotoba_bridge.push(..., delegation=bundle, now_epoch=<unix now>)` presents the opaque `cacao_b64` (no operator bearer); kotoba verifies issuer-sig + capability + graph + aud + expiry → `write_author = the member` | no invoker signature needed → **present-only, stays stdlib** |
| REVOKE | consent withdrawn | `exp` passes → the organism self-disables the delegated path and falls back to local-log/operator-bearer (fail-open); **stop re-issuing → the organism quietly retires** | — |

Issuance payload the member signs (`delegation.issuance_template(...)` — the SIWE/CAIP-122 shape):
```
{ "iss": "<member did:key>", "aud": "<kotoba node DID>",
  "exp": "<UTC ISO-8601>", "nonce": "<hex>", "version": "1",
  "resources": ["kotoba://can/datom:transact", "kotoba://graph/<graph-cid>"] }
```
(The sidecar bundle's `exp` is the **epoch** form `delegation.is_usable` self-gates on; the CACAO
inside carries the **ISO** form kotoba verifies — same instant, two representations, reconciled by
the issuer.)

**Live-wiring status (2026-06-11).** A real member-signed CACAO from `issue_delegation.py`, presented
on the delegated path with NO operator bearer, is parsed + signature-verified by the live node and
reaches the server's **DID-resolution** stage — i.e. the CACAO format, Ed25519 signature, SIWE
plaintext, capability/graph/aud scoping are all accepted. Full write-acceptance additionally needs
the node's DID resolver (IPFS/kubo daemon) to be up to fetch the issuer DID document; with kubo down
the verifier returns `did resolver error: … kubo block/get`. That is an **operator infra** prerequisite,
not a delegation-design or actor gap. The actor-side path (issuer → `delegation.load` → `is_usable` →
`kotoba_bridge` presents) is verified end-to-end.

**Why not a held key or a passkey-per-beat?** A held root key is constitutionally prohibited
(no-server-key); a passkey requires human presence the autonomous loop cannot supply each beat.
The leash is the third way: the organism acts autonomously, but only under a scope-limited,
time-bounded, revocable grant a member mints with their own key, and every write it makes is
on-record attributed to that member — **autonomy with accountability, 共生 by consent.**
Human-presence acts (publishing to AT Protocol as the member, drawing commons) stay separate and
require the member's fresh signature each time; they are never delegated.
