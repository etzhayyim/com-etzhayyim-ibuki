# 息吹 (ibuki) — organism autonomy R2 gap-closure substrate

> ADR-2606101200 · Apache 2.0 + etzhayyim Charter Compliance Rider v3.0

The breath that closes the artificial-organism loop. The UNSPSC organism programme
(ADR-2605232345 / 2605240000 / 2605240100 / 2605240200) had every organ but no closed
circulation: state was ephemeral, mood was a constant, narration was unwired, posts queued
forever, and Kaizen never heard back. ibuki closes those seven gaps on the canonical
substrate — the append-only, content-addressed **kotoba Datom log** (ADR-2605312345) — in the
charter-permitted form pioneered by shionome (ADR-2606072200): autonomous logic, local
persistence, every outward edge member-signed and gated.

| gap | closure |
|---|---|
| 1–2. no durable scheduler | `:heartbeat/*` checkpoint datoms; cadence replayed from the log — crash-resume is byte-identical to never crashing |
| 3. state not as-of queryable | all organism state is EAVT datoms; "mood at tx N" = replay `events_for(txs, code, up_to_tx=N)` |
| 4. constant joucho stub | deterministic personality baseline + closed event vocabulary folded over lived history — mood **emerges** (縁起) |
| 5. inference unwired | `infer.narrate` — Murakumo fleet ONLY (allowlist; violation raises), deterministic template fail-open |
| 6. Wave-3 drainer unbuilt | queue → member-sign-ready `createRecord` envelopes; `serverHeldKey:false` structural; submission requires injected member signer + operator ack |
| 7. Kaizen one-way | outcomes fold back: rule suppression after repeated rejection + mood events (merge calms, rejection stresses) |

```bash
bb test                                         # .cljc suites, hermetic
```

**R1 (same wave): the real 18,342-organism fleet on durable checkpoints.** `methods/fleet.cljc`
loads the versioned wire snapshot (`wire/actor-registry/unspsc.json`), shards it exactly
like the kotodama fleet cell (jacob/joseph/issachar/dan), and sweeps each shard in bounded
batches behind a durable `:fleet.shard/cursor` — no LRU needed for correctness, mid-sweep
crash-resume is byte-identical, and a full 18,342-organism sweep lands on one verified chain
in ~35 s.

**R2 (same wave): code-complete outward paths (Council gate exercised as PR merge).**
`perception.cljc` = read-only allowlisted public-XRPC live membrane (follower delta → capped
joucho events, durable `:perception/*` snapshots, fail-open); `member_submit.cljc` = the
MEMBER-principal posting runtime (member's own env credentials, https only, `--yes`
required, **cron contexts structurally refused**); `receipts.cljc` folds member-attributed
`:receipt/*` back onto the log (ibuki never asserts `:published`);
`cells/fleet_beat/cell.cljc` runs the durable beat and is registered on
joseph/issachar/dan in `50-infra/murakumo/fleet.toml`. E2E verified: beat → 64 envelopes →
member-signed → 64 receipts on one verified chain.

**R3 (same wave): the local log lands on the LIVE kotoba engine.** `kotoba_bridge.cljc`
pushes each local tx as one `datomic.transact` to a running kotoba node (fleet allowlist
:8077; graph id = `KotobaCid::from_bytes(name)` pinned against the live engine; remote
commits chained via `expected_parent`; `:ibuki.tx/*` provenance meta; exactly-once
`:bridge/*` cursor; default no-I/O dry-run; operator auth = unsigned bearer carrying only
the node's PUBLIC DID — no key held). **Verified live 2026-06-10**: 2 fleet beats → 2
transacts → `status:ok`, 780 datoms confirmed by the engine, IPNS head advanced, re-push
sent nothing twice. `kaizen_outcomes.cljc` fills the Wave-4 outcomes file from real PR
states (`gh pr view`, operator-principal, read-only).

## Embeds the IE-flow system-of-systems substrate (ADR-2606211200)

ibuki's metabolism (Φ/η/surprise) IS an information-energy flow. The organism-specific
co-scientist here (ADR-2606201200) is the special case of the shared, actor-agnostic
**`etzhayyim.ie-flow`** lifecycle (`70-tools/src/etzhayyim/ie_flow/`), which every actor
embeds (`80-data/ie-flow/registry.edn`): measure a flow ledger (events/nodes/stocks/
interventions on kotoba) → order-index / net-gain / agent-efficiency → the same Google
co-scientist tournament (shared, unforkable aligned/forbidden mechanism vocabulary) →
pre-registered dry-run experiment → Brier kaizen → content-addressed commit-DAG. Adopt in
3 lines: `(ie/record! "<actor>" events {:as-of n})` / `(ie/beat! "<actor>" {:as-of n})`.
