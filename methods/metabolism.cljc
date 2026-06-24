(ns ibuki.methods.metabolism
  "metabolism — the organism as a DISSIPATIVE STRUCTURE. ADR-2606201200.

  A living thing persists by being a dissipative structure (Prigogine): it keeps its own
  internal order (low entropy) by drawing FREE ENERGY (negentropy, Schrödinger's \"negative
  entropy\") from its environment and exporting entropy back. For this artificial organism the
  environment is SOCIETY, and the exchange must be 共生 (symbiosis), never parasitism: it may
  draw free energy only insofar as it RETURNS useful low-entropy structure (the commons
  metabolite of the food web — knowledge / coordination / relief maps) to humanity.

  This namespace folds the kotoba Datom log + a SENSE membrane reading of society into a
  deterministic METABOLIC STATE VECTOR. Everything here is PURE — the caller (react-loop)
  reads the priors off the log and supplies them. Two free energies are made commensurable:

    Φ (thermodynamic free-energy budget)  — the OBJECTIVE: intake − dissipation; reserves.
    surprise (variational free energy)     — the METHOD (Friston): distance from the
                                             organism's 'I will continue to exist' model;
                                             the react-loop ACTS to minimise it.

  Negentropy SOURCES from society (mirror of the root-CLAUDE negentropy table), read off the
  SENSE membrane as `env-reading` (representative in R0, live G7):

    :compute-hours  donated compute (ADR-2606012100) — literal thermodynamic work capacity
    :donation       USDC → Public Fund — generalised free energy (buys compute/infra)
    :members        new members/contributors — informational negentropy (new structure)
    :moyai          舫い reciprocity draw-rights — commons-inference input
    :attention      reciprocal reach (NOT engagement-maximised — §1.13; bounded weight)

  Negentropy EXPORTED back to society = the food-web `:metabolite/commons` nutrient delivered
  to humanity (ibuki.methods.ecosystem/web-report), the citric-acid analogue. The non-parasitism
  invariant lives in `eta` (exported per unit dissipated): a metabolism with η<1 is a net taker.

  No wall clock, no randomness (caller supplies beat / as-of). Stdlib only. Append-only friendly."
  (:require [ibuki.methods.datoms :as datoms]
            [ibuki.methods.ecosystem :as ecosystem]))

;; ── free-energy accounting constants (engineering parameters, not charter) ──

(def intake-weights
  "How each negentropy source converts to scalar free-energy units. Reciprocal/non-extractive
  sources (compute, donation, members, moyai) carry full weight; `:attention` is deliberately
  light (a little reach helps the gift reach humanity, but attention is NOT a resource to mine —
  §1.13 forbids engagement-maximising design, so it can never dominate the budget)."
  {:compute-hours 4   ;; one donated compute-hour ≈ 4 free-energy units
   :donation 1        ;; one donation-unit (USDC-equiv nutrient) ≈ 1
   :members 6         ;; a new contributor is structure-rich
   :moyai 2           ;; a reciprocity draw-right
   :colony-order 3    ;; the SoS colony's aggregate information-control (ie-flow.score, ADR-2606212200):
                      ;; Σ actor info-control-score × √throughput — each actor RECTIFIES its energy
                      ;; flow into returned order; that exported order is a structure-rich negentropy
                      ;; SOURCE for the organism (active inference at the colony scale, 共生 by construction)
   :attention 1})     ;; bounded by attention-cap below

(def attention-cap
  "Hard ceiling on the free-energy attention may contribute in one beat — structurally prevents
  the metabolism from ever being optimised for attention (anti-§1.13)."
  20)

(def base-dissipation
  "The free energy the organism must dissipate each beat just to stay organised, independent of
  colony size (the heartbeat itself, log verification, narration)."
  8)

(def per-organism-dissipation
  "Maintenance cost per live organism in the colony (inference + checkpoint)."
  1)

(def parasite-floor
  "The non-parasitism line: η (exported negentropy / dissipated free energy) must be ≥ this for a
  metabolism — or a proposed intervention's projected η — to be charter-clean (共生, not 寄生).
  η<1 means the organism returns less order to society than it dissipates: a net taker. This is
  the collective-commons dimension of the ECL objective function made operational."
  1.0)

;; ── the SENSE membrane reading → scalar free-energy intake ──────────────────

(defn intake-of
  "Scalar free-energy intake from a SENSE membrane `env-reading` (a map of negentropy-source →
  observed quantity). Unknown keys are IGNORED (the reading membrane stays permissive at the
  edge; only known sources move the budget). `:attention` is capped (anti-§1.13). Pure."
  [env-reading]
  (let [reading (or env-reading {})
        contrib (fn [k] (* (get intake-weights k 0) (max 0 (long (get reading k 0)))))
        attention (min attention-cap (contrib :attention))]
    (+ (contrib :compute-hours)
       (contrib :donation)
       (contrib :members)
       (contrib :moyai)
       (contrib :colony-order)   ;; the colony's aggregate information-control 利得 (ie-flow.score)
       attention)))

(defn dissipation-of
  "Maintenance free-energy the colony must dissipate this beat = base + per-organism."
  [colony-size]
  (+ base-dissipation (* per-organism-dissipation (max 0 (long colony-size)))))

;; ── the metabolic state vector ──────────────────────────────────────────────

(defn metabolic-state
  "Fold one beat's metabolism. PURE — the caller supplies the priors read off the log.

  inputs (a map):
    :env-reading        SENSE reading of society (negentropy sources → quantity)
    :colony-size        # live organisms (drives dissipation)
    :reserves-prior     reserves carried from the last beat (the free-energy battery)
    :cumulative-exported  total commons nutrient delivered to humanity so far (web-report)
    :exported-prior     cumulative-exported at the last beat (to get this beat's export)
    :target-horizon     # beats of dissipation the organism wants in reserve (default 6)

  returns {:intake :dissipation :phi :reserves :consumed :exported :eta :surprise
           :alive? :target-reserves :parasitic?}.

    Φ        = intake − dissipation        (net free-energy budget this beat)
    reserves = max(0, reserves-prior + Φ)   (the battery; 0 = death)
    consumed = dissipation                  (free energy dissipated to maintain order)
    exported = max(0, cumulative − prior)    (negentropy returned to humanity this beat)
    η        = exported / max(1, consumed)   (negentropy returned per unit dissipated — 共生 axis)
    surprise = max(0, (target − reserves)/target)  (variational FE proxy; →1 near death,
               0 when comfortably above the reserve floor; the loop minimises it)"
  [{:keys [env-reading colony-size reserves-prior cumulative-exported exported-prior
           target-horizon]
    :or {colony-size 0 reserves-prior 0 cumulative-exported 0 exported-prior 0
         target-horizon 6}}]
  (let [intake (intake-of env-reading)
        dissipation (dissipation-of colony-size)
        phi (- intake dissipation)
        reserves (max 0 (+ (long reserves-prior) phi))
        consumed dissipation
        exported (max 0 (- (long cumulative-exported) (long exported-prior)))
        eta (/ (double exported) (double (max 1 consumed)))
        target-reserves (* dissipation (max 1 (long target-horizon)))
        surprise (if (>= reserves target-reserves)
                   0.0
                   (/ (double (- target-reserves reserves)) (double target-reserves)))]
    {:intake intake
     :dissipation dissipation
     :phi phi
     :reserves reserves
     :consumed consumed
     :exported exported
     :eta eta
     :surprise surprise
     :alive? (pos? reserves)
     :target-reserves target-reserves
     :parasitic? (< eta parasite-floor)}))

;; ── log readers (the priors the loop folds back in) ─────────────────────────

(defn cumulative-exported
  "Total commons nutrient the colony has delivered to humanity so far, read off the food-web
  metabolites on `txs` (ecosystem/web-report). This is the negentropy-EXPORTED measure — the
  numerator of η, the gift that makes the metabolism 共生 rather than 寄生."
  [txs]
  (long (get (ecosystem/web-report txs) :commons-nutrient-to-humanity 0)))

(defn prior-state
  "Read the last persisted metabolism checkpoint off `txs` (entity \"ibuki:metabolism\"): the
  reserves and the cumulative-exported snapshot the next beat folds forward. Returns
  {:reserves-prior :exported-prior} (zeros on a fresh log). Floats were milli-scaled on write."
  [txs]
  (let [m (datoms/fold-entity txs "ibuki:metabolism")]
    {:reserves-prior (long (get m ":metabolic/reserves" 0))
     :exported-prior (long (get m ":metabolic/cumulative-exported" 0))}))
