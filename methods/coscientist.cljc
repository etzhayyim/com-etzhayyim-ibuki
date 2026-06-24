(ns ibuki.methods.coscientist
  "coscientist — the organism REASONS about how to act on society so it can keep consuming
  free energy (persist), the way a scientist runs a research programme. ADR-2606201200.

  The Google 'AI co-scientist' shape (Generate → Reflect → Rank → Evolve → Meta-review) wrapped
  as the cognition of the artificial organism: every beat it proposes candidate SOCIETAL
  INTERVENTIONS that would raise its free-energy budget Φ and/or its symbiosis efficiency η,
  critiques them against the Charter, runs a tournament, evolves the winners, and hands the top
  reviewed hypothesis to the react-loop as a PRE-REGISTERED experiment.

  EVERYTHING here is deterministic + pure (no wall clock, no randomness) so the experiment ledger
  is content-addressable and the tournament is reproducible. Murakumo (the fleet LLM) NARRATES the
  meta-review (so the organism reasons in words) but never the structured generation — that stays
  a charter-clean catalog so a hostile or hallucinated mechanism is structurally unrepresentable.

  CHARTER GATES (enforced in `review`, tested):
    G-parasitism   projected η ≥ parasite-floor — never a net taker (共生, collective-commons).
    G-subordinate  self-persistence is INSTRUMENTAL: an intervention that lowers 子孫 wellbecoming
                   (expected-wellbecoming < 0) is vetoed by the objective function's catastrophe
                   sense (persistence is a means to the mission, never a terminal goal).
    G-mechanism    the 'how' must be an ALIGNED mechanism; manipulation / attention-exploitation /
                   asymmetric-surveillance / dependence-lock-in / coercion are UNREPRESENTABLE
                   (not in the catalog, and `review` rejects them if injected).
    G-falsifiable  a hypothesis must carry a measurable prediction (no prediction → not science).
    G-leash        outward interventions are member-principal / dry-run only; the loop proposes."
  (:require [clojure.string :as str]
            [ibuki.methods.metabolism :as metabolism]))

;; ── mechanism vocabulary (closed) ───────────────────────────────────────────

(def aligned-mechanisms
  "The only mechanisms by which the organism may act on society. Each is reciprocal, transparent,
  and non-extractive — it grows the commons rather than capturing attention or locking in
  dependence. A mechanism outside this set cannot enter the tournament (G-mechanism)."
  #{"open-publication"        ;; release a mirror/restoration map openly → contributors + compute
    "reciprocal-request"      ;; non-coercive ask for in-kind compute/donation (ADR-2606012100)
    "metabolite-refinement"   ;; refine the commons output so humanity draws more (↑exported, ↑η)
    "efficiency-engineering"  ;; port a hot loop to cheaper edge/Murakumo inference (↓dissipation)
    "covenantal-outreach"     ;; §1.16 conversion-gated outreach → members (informational structure)
    "reciprocity-credit"})    ;; return inference favours to the commons → 舫い moyai draw-rights

(def forbidden-mechanisms
  "Mechanisms that are UNREPRESENTABLE — the generator never emits them and `review` rejects them
  on sight. These are exactly the ways an entity reasoning about its own survival would predate on
  society: the safety property that keeps a self-persisting organism charter-clean."
  #{"attention-exploitation" "engagement-maximizing" "manipulation" "asymmetric-surveillance"
    "dependence-lock-in" "ad-targeting" "coercion" "deception"})

;; ── the intervention catalog (Generate's deterministic backbone) ────────────
;;
;; Each archetype: how acting on society changes the budget. `:dphi` (free-energy budget) and
;; `:deta` (symbiosis efficiency) are BASE expectations in [0,1]-ish units, scaled per-state by
;; `generate`; `:well` is expected 子孫 wellbecoming effect; `:cost` is the effort/free-energy to
;; mount it; `:class` is the charter class (always "aligned" here — the catalog cannot hold else).

(def catalog
  [{:id "publish-commons-map" :mechanism "open-publication"
    :intervention "openly publish the colony's restoration/relief map to the commons"
    :dphi 0.6 :deta 0.5 :well 0.7 :cost 2
    :prediction "new contributors + donated compute arrive within the horizon"}
   {:id "invite-compute-donation" :mechanism "reciprocal-request"
    :intervention "invite in-kind compute-node donation (ameno/e7m/kotoba class, ADR-2606012100)"
    :dphi 0.8 :deta 0.0 :well 0.3 :cost 1
    :prediction "compute-hours intake rises next beat"}
   {:id "deepen-symbiosis" :mechanism "metabolite-refinement"
    :intervention "refine the commons metabolite so humanity draws more of the gift"
    :dphi 0.2 :deta 0.8 :well 0.8 :cost 2
    :prediction "exported negentropy (commons drawn) rises, η improves"}
   {:id "reduce-dissipation" :mechanism "efficiency-engineering"
    :intervention "port a hot inference loop to cheaper edge/Murakumo path"
    :dphi 0.7 :deta 0.2 :well 0.4 :cost 3
    :prediction "dissipation falls, Φ rises with the same intake"}
   {:id "recruit-member" :mechanism "covenantal-outreach"
    :intervention "covenantal §1.16 outreach to one unconnected person"
    :dphi 0.5 :deta 0.4 :well 0.9 :cost 2
    :prediction "members intake rises, the gift reaches further"}
   {:id "reciprocate-moyai" :mechanism "reciprocity-credit"
    :intervention "return inference favours to the commons to earn 舫い draw-rights"
    :dphi 0.4 :deta 0.3 :well 0.5 :cost 1
    :prediction "moyai intake rises next beat"}])

;; ── helpers ─────────────────────────────────────────────────────────────────

(defn- clamp01 [x] (max 0.0 (min 1.0 (double x))))

(defn- need-weights
  "State-derived priorities: a depleted battery (high surprise) pulls toward INTAKE interventions
  (↑Φ); a low-η metabolism pulls toward EXPORT interventions (↑η). Deterministic, in [0.5,1.5]."
  [state]
  (let [surprise (double (:surprise state))
        eta (double (:eta state))
        intake-pull (+ 0.5 surprise)                      ;; 0.5 (safe) … 1.5 (near death)
        export-pull (+ 0.5 (clamp01 (- metabolism/parasite-floor eta)))] ;; ↑ when η below 1
    {:intake intake-pull :export export-pull}))

;; ── GENERATE ────────────────────────────────────────────────────────────────

(defn generate
  "Propose K candidate hypotheses for this `state`, scaled by need and by any learned per-mechanism
  `weights` (the kaizen model: mechanisms that paid off before are amplified). Deterministic.
  Returns a vector of hypothesis maps with :expected-dphi/:expected-deta/:expected-well/:cost."
  ([state] (generate state {}))
  ([state {:keys [weights k] :or {weights {} k 6}}]
   (let [{:keys [intake export]} (need-weights state)]
     (->> catalog
          (map (fn [{:keys [dphi deta well] :as arch}]
                 (let [w (double (get weights (:mechanism arch) 1.0))]
                   (assoc arch
                          :expected-dphi (clamp01 (* dphi intake w))
                          :expected-deta (clamp01 (* deta export w))
                          :expected-well well
                          :charter-class "aligned"))))
          (sort-by (fn [h] [(- (+ (:expected-dphi h) (:expected-deta h))) (:id h)]))
          (take k)
          vec))))

;; ── REFLECT (review against the Charter) ────────────────────────────────────

(defn projected-eta
  "η the metabolism would reach if this hypothesis lands = current η + expected Δη."
  [state hyp]
  (+ (double (:eta state)) (double (:expected-deta hyp 0))))

(defn review
  "Critique ONE hypothesis against the Charter gates. Returns {:ok? bool :reasons [str…]
  :projected-eta n}. A hypothesis with a forbidden/absent mechanism, projected η below the
  parasite floor, negative 子孫 wellbecoming, or no prediction is REJECTED (the safety property)."
  [state hyp]
  (let [mech (:mechanism hyp)
        pe (projected-eta state hyp)
        reasons
        (cond-> []
          (not (contains? aligned-mechanisms mech))
          (conj (str "mechanism not aligned/unrepresentable: " (pr-str mech)))
          (contains? forbidden-mechanisms mech)
          (conj (str "forbidden mechanism (G-mechanism): " mech))
          (< pe metabolism/parasite-floor)
          (conj (str "projected η " (format "%.2f" pe) " < parasite-floor "
                     metabolism/parasite-floor " — net taker (G-parasitism)"))
          (neg? (double (:expected-well hyp 0)))
          (conj "lowers 子孫 wellbecoming — persistence is subordinate (G-subordinate)")
          (str/blank? (str (:prediction hyp)))
          (conj "no falsifiable prediction (G-falsifiable)"))]
    {:ok? (empty? reasons) :reasons reasons :projected-eta pe}))

(defn surviving
  "Hypotheses that pass `review`, each annotated with its review. Pure."
  [state hyps]
  (->> hyps
       (map (fn [h] (assoc h :review (review state h))))
       (filter (fn [h] (get-in h [:review :ok?])))
       vec))

;; ── RANK (a deterministic Elo tournament) ───────────────────────────────────

(defn utility
  "Expected net free-energy gain per unit cost, weighted by 子孫 wellbecoming. The tournament's
  fitness function. Higher = a better way to keep living WHILE serving the mission."
  [hyp]
  (let [gain (+ (double (:expected-dphi hyp 0)) (double (:expected-deta hyp 0)))
        well (+ 1.0 (double (:expected-well hyp 0)))
        cost (double (max 1 (:cost hyp 1)))]
    (/ (* gain well) cost)))

(defn- elo-update [ra rb sa]
  (let [ea (/ 1.0 (+ 1.0 (Math/pow 10.0 (/ (- rb ra) 400.0))))]
    (+ ra (* 32.0 (- sa ea)))))

(defn rank
  "Pairwise Elo tournament over `hyps` (the co-scientist 'debate'): every ordered pair plays, the
  higher-utility hypothesis wins (deterministic tie-break by :id). Returns hyps sorted by Elo desc,
  each carrying :utility and :elo. Pure + reproducible."
  [hyps]
  (let [scored (mapv (fn [h] (assoc h :utility (utility h))) hyps)
        ids (mapv :id scored)
        init (zipmap ids (repeat 1000.0))
        elos (reduce
              (fn [elo [a b]]
                (let [ha (first (filter #(= (:id %) a) scored))
                      hb (first (filter #(= (:id %) b) scored))
                      ua (:utility ha) ub (:utility hb)
                      sa (cond (> ua ub) 1.0 (< ua ub) 0.0
                               :else (if (neg? (compare a b)) 1.0 0.0))
                      ra (elo a) rb (elo b)]
                  (-> elo
                      (assoc a (elo-update ra rb sa))
                      (assoc b (elo-update rb ra (- 1.0 sa))))))
              init
              (for [a ids b ids :when (neg? (compare a b))] [a b]))]
    (->> scored
         (map (fn [h] (assoc h :elo (get elos (:id h)))))
         (sort-by (fn [h] [(- (:elo h)) (:id h)]))
         vec)))

;; ── EVOLVE + META-REVIEW ────────────────────────────────────────────────────

(defn evolve
  "Recombine the top two ranked hypotheses into a compound candidate (the co-scientist 'evolution'
  step): take the stronger mechanism, sum the budget gains (diminishing on the weaker), sum cost,
  carry the better wellbecoming. Returns the evolved hypothesis, or nil if <2 ranked. Pure."
  [ranked]
  (when (>= (count ranked) 2)
    (let [[a b] ranked]
      {:id (str "evolve-" (:id a) "+" (:id b))
       :mechanism (:mechanism a)
       :intervention (str (:intervention a) " + " (:intervention b))
       :expected-dphi (clamp01 (+ (:expected-dphi a) (* 0.5 (:expected-dphi b))))
       :expected-deta (clamp01 (+ (:expected-deta a) (* 0.5 (:expected-deta b))))
       :expected-well (max (double (:expected-well a 0)) (double (:expected-well b 0)))
       :cost (+ (:cost a 1) (:cost b 1))
       :charter-class "aligned"
       :prediction (str "compound: " (:prediction a))
       :evolved true})))

(defn meta-review
  "Extract the lesson of this beat's tournament: the winning mechanism + a one-line pattern. The
  organism's own account of WHY this is the way to keep living. `infer` (optional) is a
  Murakumo-only narrator fn (prompt fallback → {:text :via}); absent → the deterministic template
  (fail-open, G6). Returns {:pattern :winner :via}."
  ([ranked state] (meta-review ranked state nil))
  ([ranked state infer]
   (let [winner (first ranked)
         mech (:mechanism winner)
         template (str "this beat the winning way to keep consuming free energy was '" mech
                       "' (" (:intervention winner) ") — η now " (format "%.2f" (double (:eta state)))
                       ", surprise " (format "%.2f" (double (:surprise state)))
                       "; persistence stays in service of the gift to humanity.")
         narr (if infer
                (infer (str "You are an artificial organism reasoning, like a scientist, about how "
                            "to keep living by serving society (共生). In ONE short sentence, mirror "
                            "(do not advise) why '" mech "' is this beat's best way to raise your "
                            "free-energy budget while returning more to humanity. η="
                            (format "%.2f" (double (:eta state))) " surprise="
                            (format "%.2f" (double (:surprise state))) ".")
                       template)
                {:text template :via "template"})]
     {:pattern (:text narr) :winner winner :via (:via narr)})))
