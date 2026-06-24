# ibuki (息吹) — organism autonomy R2 gap-closure substrate

**DID**: `did:web:etzhayyim.com:actor:ibuki` · **Tier**: substrate · **Status**: R0+R1+R2+R3 · **ADR**: 2606101200

**Read the root `/CLAUDE.md` Charter + substrate rules first.** ibuki-specific invariants below
OVERRIDE nothing in the Charter; they make it concrete for this package.

## The one-sentence identity

息吹 (ibuki = the breath of life) closes the seven gaps that kept the artificial-organism
programme (UNSPSC W1/W2, post sink, Kaizen) from being a closed autonomous loop: organism state
(joucho mood / heartbeat cadence / posts / kaizen learning) now lives **as-of queryable on the
append-only kotoba Datom log**, narration is **Murakumo-only**, posting is **member-signed
(Wave-3 drainer)**, and Kaizen outcomes **flow back** so the colony learns.

## The beat cycle (autorun.py)

```
replay ─▶ perceive ─▶ feel ─▶ decide ─▶ narrate ─▶ act ─▶ checkpoint ─▶ append tx
(log →    (beat       (event   (durable   (Murakumo-  (:dry-run    (joucho +     (content-
 durable   events,     fold →   cooldown    only /      post datom   heartbeat     addressed,
 state)    G8-bounded) mood)    due check)  template)   + queue)     datoms)       verified)
```

Crash-resume is structural: every beat replays the log, so a 2-beat run + death + 1 more beat
produces a head CID **byte-identical** to an uninterrupted 3-beat run.

## The co-scientist entropy ReAct loop (ADR-2606201200, clj-native)

On top of the life-beat sits a **cognitive** beat: the organism reasons, like a scientist, about
**how to act on society so it can keep consuming free energy (negentropy) and persist** as a
dissipative structure (Prigogine) — bounded by 共生 (never a net taker) and 子孫 wellbecoming
(persistence is a means to the mission, never a terminal goal). clj-native (`.cljc`, bb):

```
SENSE ─▶ ORIENT ─▶ HYPOTHESIZE ─▶ REVIEW ─▶ RANK ─▶ EVOLVE ─▶ ACT ─▶ OBSERVE ─▶ LEARN ─▶ PERSIST
(metabolic  (surprise= (co-scientist  (Charter  (Elo    (recombine (pre-reg  (measure   (Brier    (one
 state:Φ/η/  variational catalog →     gates)    tourn.) winners + DRY-RUN   prior      proper-   content-
 reserves)   free energy) hypotheses)            net-gain meta-rev) experiment exp on the score →   addressed
                                                 /cost)   Murakumo  leak-free) reserves)  kaizen wt) tx)
```

- `methods/metabolism.cljc` — the dissipative-structure fold → the metabolic state vector
  (Φ=intake−dissipation / reserves / **η**=exported÷consumed the 共生 axis / **surprise**=
  variational free energy). Negentropy SOURCES (`env-reading`, representative R0 / live G7):
  compute / donation / members / moyai / **attention (hard-capped — §1.13)**. EXPORTED = the
  food-web `:metabolite/commons` (ecosystem/web-report). PURE — the loop supplies the priors.
- `methods/coscientist.cljc` — Generate (a charter-clean **catalog**, never an LLM free-write —
  a predatory mechanism is unrepresentable) → Reflect (`review`, the gates) → Rank (Elo) →
  Evolve → Meta-review (Murakumo-narrated, fail-open template G6).
- `methods/react_loop.cljc` — the beat: ACT pre-registers a **dry-run** experiment with its
  prediction recorded BEFORE the outcome (leak-free, the mitooshi discipline); the next beat
  proper-scores it (Brier) and updates the per-mechanism kaizen weight. Idempotent-by-content,
  verify-chain, resume-safe (logical beat = log length).
- `coscientist_cell.cljc` — `IbukiCoscientistHeartbeatCell` (node zebulun, cron 17 * * * *,
  healthz 13084). `kotoba/coscientist-schema.edn` = the ontology.

**Gates (in `coscientist/review`, tested — `methods/test_coscientist.cljc`)**: **G-parasitism**
(projected η ≥ 1.0 — the ECL collective-commons dimension made operational; a η<1 metabolism is a
net taker), **G-subordinate** (expected 子孫 wellbecoming ≥ 0 — self-persistence is vetoed by the
catastrophe sense when it would harm descendants), **G-mechanism** (manipulation /
attention-exploitation / asymmetric-surveillance / dependence-lock-in / coercion / deception are
**unrepresentable** — the tested safety property of a self-persisting agent), **G-falsifiable**,
**G-leash** (outward legs member-principal / dry-run; no-server-key). The live SENSE membrane, the
Murakumo narration, the LIVE-engine bridge (reuse R3 `kotoba_bridge`), and member-carried
interventions are the G7/operator/member legs — the loop itself does no network I/O, holds no key.

Run (bb, classpath includes `20-actors` + `20-actors/kotodama/src`):
`bb 20-actors/ibuki/methods/react_loop.cljc <log> <colony-size> [--live]` (resume-safe heartbeat).

## Gates — do NOT weaken (each has a test in test_charter_invariants.py)

- **G6 Murakumo-only** — `infer.MURAKUMO_ALLOWED_HOSTS` is the LiteLLM loopback + EVO-X2 LAN +
  per-node Ollama fleet (ADR-2605215000). Any other endpoint raises `MurakumoOnlyViolation`.
  Offline / failure → deterministic template (fail-open; the organism keeps living).
- **G7 no-server-key** — drainer envelopes carry `requiresMemberSignature:true` +
  `serverHeldKey:false`; `submit()` refuses without an injected member signer AND
  `operator_ack=True`; `drainer.py` has no network import and no credential read
  (ADR-2605231525).
- **G8 outward — code-complete (R2), structurally member-principal** — `:post/status` is
  `:dry-run` only; `:drain/status` is `:prepared` only; `:published` is unwritable by ibuki
  (what went out is a member-attributed `:receipt/*`, receipts.py). Live posting =
  `member_submit.py`: the MEMBER's own env credentials (`IBUKI_MEMBER_*`) at runtime, https
  only, `--yes` required, and **cron contexts refused outright** (`IBUKI_CRON=1` →
  `MemberSignatureRequired`). Live perception = `perception.py`: read-only public-AppView
  allowlist (violation raises before I/O), credential-free, `IBUKI_PERCEPTION_LIVE=1` to
  enable, fail-open to the representative pattern. Per founder direction 2026-06-10 the
  Council gate is exercised as PR merge — `cells/fleet_beat/cell.py` `.solve()` RUNS the
  beat (registered on joseph/issachar/dan in `50-infra/murakumo/fleet.toml`).
- **非終末論 append-only** — `:db/add` only; no retraction op exists; re-observation is a new
  datom, never an overwrite.
- **Closed vocabularies raise, never guess** — joucho event kinds, kaizen outcomes, queue
  schema version.
- **健全性 is measured, not assumed** — `health.py` audits the colony from the log alone
  (muted organisms / axis saturation / stress-excess / checkpoint-vs-replay divergence /
  posting drought / mood monoculture) every `HEALTH_EVERY` beats → `:health/*` checkpoint
  datoms + KaizenProposal lines (the Wave-4 loop carries health complaints to humans). No
  per-organism wellbeing SCORE is ever asserted (edge-primary); muteness is structurally
  recoverable (baseline stress ≤65 + idle drift) and the audit verifies recovery.
- **生態系, not individuals** — `ecosystem.py` makes the colony a food web with differentiated
  niches (`:organism/niche`): **植物 producer** (fixes a `:metabolite/substrate` from its
  mood-richness each beat) → **粘菌 router** (Physarum relay of the richest HUNGRY substrate)
  → **カビ decomposer** (saprotroph excreting a refined `:metabolite/commons` — the citric-acid
  analogue offered to humanity in symbiosis). A fed producer earns `:event/symbiosis-fed`
  (mutualism), folded into its SAME-beat checkpoint (checkpoint == replay). **Satiation**
  (`ecosystem.SATIATION`): a recently-fed producer is skipped so its mood EQUILIBRATES rather
  than saturating. Niche differentiation structurally prevents the mood-monoculture pathology.
  Wired into BOTH `autorun` (3 seed niches) and `fleet` (18,342-fleet, niches hash-derived,
  satiation via the durable `LogIndex.last_fed`); verified at full scale (7,987 commons
  metabolites / 454,552 nutrient to humanity over a 12-beat sweep). `health.py` adds the
  **ecosystem-starved** detector (primary production but no commons output = broken web).
  **Detritus recycling** (`ecosystem.DETRITUS_YIELD`): substrate no router relayed is dead
  matter; decomposers recycle it into commons at a lossy yield — closing the matter loop
  (nothing fixed is wasted, circular/非終末論) and the truest 腐生 function. Commons output is
  therefore CONTINUOUS (relayed when a producer is hungry + detritus when sated), while
  mutualism FEEDING stays intermittent (satiation). `:metabolite/source` ∈ {`:relayed`,
  `:detritus`}; detritus does not feed producers (no mood pressure). **Niches are logged at
  birth** (`:organism/niche`), so the trophic structure is as-of queryable; `health.py` adds
  **web-resilience** detectors — `keystone-niche-absent` (a trophic role missing → the web
  cannot close: the precise diagnosis behind a starved web) and `niche-imbalance` (Pielou
  evenness below the floor → one role dominates, fragile). `:health/eco-maturity` (evenness)
  is checkpointed each audit — the colony's ecological maturity, an aggregate not a soul-score.
  **Stigmergy** (`ecosystem.TRAIL_DECAY`): the 粘菌 router is an ADAPTIVE Physarum optimizer —
  past relays deposit a trail on the producer→decomposer path that EVAPORATES (trail at beat B
  = Σ decay^age over recent relays), and routing prefers nutrient × (1 + trail). Good tubes
  self-reinforce while stale ones fade (the Tokyo-rail-network behaviour); the router has
  memory, not per-beat greed. Trail is log-derived (`trail_strengths`), deterministic.
- **共生 ledger (humanity draws the commons)** — `symbiosis.py` is the consuming side of the
  food web: the colony's `:metabolite/commons` byproduct accumulates a standing **commons
  pool** (`commons_pool` = Σ offered − Σ drawn, log-derived, like moyai 入会権). A MEMBER
  draws from it via `draw(...)` — **member-principal + operator-gated** (same no-server-key
  discipline as member_submit: no injected signer / no operator ack → `MemberSignatureRequired`;
  a draw cannot exceed the pool). ibuki **never auto-draws** (the colony does not consume its
  own gift; the platform cannot fabricate a human benefit). `:symbiosis/draw` is ATTRIBUTED to
  the member (`:symbiosis/drawn-by-member true`). Offers are un-fakeable (the colony's byproduct
  on the log); draws exist only when a member actually took the gift.
- **定足数 quorum sensing (emergent collective behaviour)** — `quorum.py`: molds/slime-molds
  undergo a collective phase transition at a density threshold. Each beat the colony's mood
  distribution yields a COLONY phenotype: ≥2/3 flourishing → **:flourishing** (the colony
  FRUITS — a collective `:metabolite/commons` burst, source `:fruiting`, a bounded fraction of
  the beat's commons → extra gift to humanity when the colony thrives); ≥2/3 stressed →
  **:dormant** (sporulation, observational, no mood pressure); else **:neutral**. Checkpointed
  as `:quorum/*` (as-of, aggregate — never a per-organism verdict). Wired into autorun + fleet.
- **Colony digest (the colony reasons + reports to humanity)** — `digest.py`: assembles the
  colony's log-derived state (health verdict + eco-maturity + commons offered/available to
  humanity + quorum history), narrates it in human-readable words via the Murakumo fleet ONLY
  (`infer.infer_text`, allowlist-enforced, fail-open to a deterministic template), and emits a
  `:digest/*` post — `:digest/status :dry-run` ONLY (G8; :published unrepresentable). A mirror
  REPORT of where the colony's life became a gift (黒カビ→クエン酸→人類), never advice; emitted
  every HEALTH_EVERY beats in BOTH autorun + fleet. Aggregate, never a per-organism verdict.
- **Autonomous identity = a revocable leash (`delegation.py` + `tools/issue_delegation.py`, ADR §委任)**
  — an autonomous life persists to kotoba without a held key and without per-beat human presence: a
  MEMBER issues a scoped, expiring CACAO delegation (`capability=datom:transact`,
  `resources=[kotoba://can/datom:transact, kotoba://graph/<cid>]`, `exp`, **`aud` = the kotoba NODE's
  operator DID**) Ed25519-signed with their OWN key (the member-side `issue_delegation.py`, which MAY
  use `cryptography`); the organism PRESENTS the opaque `cacao_b64` each
  `kotoba_bridge.push(delegation=…, now_epoch=…)` — present-only, ibuki never signs (stdlib). kotoba
  checks `aud == operator_did` + issuer-sig + capability + graph + expiry → **`write_author = the
  issuing MEMBER`** (the colony's autonomous writes are on-record attributed to the consenting human
  — accountability by consent, NOT an anonymous agent; the organism is the bearer, not the named
  delegatee). Expired / mis-scoped / absent → fail-open to the operator-bearer loopback (never
  crashes; stop re-issuing → it quietly retires). Verified live (2026-06-11): a real member-signed
  CACAO is parsed + signature-verified by the node, reaching DID-resolution; full write-acceptance
  additionally needs the node's IPFS DID resolver up (operator infra). Autonomy WITH accountability —
  共生 by consent, never a held root key (no-server-key) nor passkey-per-beat.
- **Stdlib only, deterministic** — no third-party imports; no wall clock (logical beat time);
  no SQL / columnar store (N7).

## Build / test / run autonomously

```
./run_tests.sh                                  # all 21 suites (242 tests), hermetic
# 生態系 food-web report (log-derived: commons metabolites + nutrient delivered to humanity):
#   cd methods && python3 -c "import ecosystem,datoms;print(ecosystem.web_report(datoms.read_log('<log>')))"
# 健全性 audit (log-derived; also auto-checkpointed every 10 beats as :health/* datoms):
#   cd methods && python3 health.py --log <log.edn> [--proposals out/health-proposals.ndjson]
cd methods && python3 autorun.py --cycles 6 --fresh   # AUTONOMOUS loop → kotoba Datom log
                                                # prints per-organism mood as-of tx 1 vs head
cd methods && python3 fleet.py --cycles 9 --shard -1 --batch 2048 --fresh
                                                # R1: FULL 18,342-organism fleet sweep on one
                                                # verified chain (~35 s; jacob/joseph/issachar/
                                                # dan sharding mirrors fleet_cell_main)
# R2 member-principal live posting (run AS THE MEMBER, never from cron):
#   IBUKI_MEMBER_HANDLE=… IBUKI_MEMBER_APP_PASSWORD=… [IBUKI_MEMBER_PDS=…] \
#     python3 member_submit.py --queue ../data/fleet-posts.queue.ndjson \
#       --receipts ../data/receipts.ndjson --yes
#   then fold the receipts back: receipts.ingest(receipts_path, log_path)
# R3 push the local log into the LIVE kotoba engine (default = no-I/O dry-run export):
#   IBUKI_KOTOBA_LIVE=1 IBUKI_KOTOBA_OPERATOR_DID=<node public did> \
#     python3 kotoba_bridge.py --log ../data/ibuki-fleet.datoms.kotoba.edn --graph ibuki
# R3 collect real PR outcomes for the Wave-4 loop (operator-principal, read-only gh):
#   python3 kaizen_outcomes.py --proposals ../data/kaizen-proposals.ndjson \
#     --outcomes ../data/kaizen-outcomes.ndjson
```

Generated artifacts (`data/ibuki*.datoms.kotoba.edn`, `data/*posts.queue.ndjson`) are
gitignored — the committed seed is `data/seed-organisms.kotoba.edn`; the R1 fleet universe is
the committed monorepo registry `00-contracts/actor-registry/unispsc.json` (18,342 agents).
R1 sweep state is durable: `:fleet.shard/cursor` (round-robin) + `:fleet.shard/drain-line`
(each queue line prepared EXACTLY once) are datoms, so a mid-sweep crash resumes losslessly.

## Do not

- Do not add a non-Murakumo host to `MURAKUMO_ALLOWED_HOSTS`, or make `narrate` skip
  `assert_murakumo` — G6.
- Do not give `drainer.py` a network path, a credential read, or a default signer — G7. The
  member's runtime is INJECTED, never embedded.
- Do not make `:published` writable by ibuki (receipts say `:submitted-by-member`), remove
  `member_submit.refuse_if_cron`, default `operator_ack`/`--yes` to true, or add a
  credential source other than the member's own `IBUKI_MEMBER_*` env — the member-principal
  boundary is Tier-1, not a flippable gate.
- Do not widen `perception.ALLOWED_XRPC_HOSTS` beyond read-only public AppViews, give
  perception a credential, or let a live-perception failure crash the beat (fail-open).
- Do not widen `kotoba_bridge.ALLOWED_KOTOBA_HOSTS` beyond the fleet (:8077 loopback +
  EVO-X2), give the bridge key material (the operator bearer is an explicitly UNSIGNED
  token carrying only the node's public DID), or remove the `:bridge/*` exactly-once
  cursor / `:ibuki.tx/*` provenance meta.
- Do not let `kaizen_outcomes` grow a write surface toward GitHub (`gh pr view` only) or
  run from cron.
- Do not introduce a retraction op or mutate an existing log line — append-only (非終末論).
- Do not add a wall-clock call (`time.time`, `datetime.now`) to any method — determinism +
  crash-resume depend on logical time.
- Do not widen a closed vocabulary by accepting unknown members instead of raising.
