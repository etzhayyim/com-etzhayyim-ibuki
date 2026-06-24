(ns ibuki.methods.quorum
  "quorum — quorum sensing: emergent COLLECTIVE behaviour the colony shows, not the cell.
  ADR-2606101200 §定足数. Clojure port of `methods/quorum.py`.

  Molds and slime molds do not act only as individuals — at a density threshold of a shared
  signal they undergo a collective phase transition (粘菌 aggregation / fungal fruiting). This
  module reads the colony's mood distribution this beat and derives a COLONY phenotype:

    :flourishing — ≥ QUORUM_FRACTION of the colony is flourishing (joyful/grateful): the colony
                   FRUITS — a collective :metabolite/commons burst (source :fruiting).
    :dormant     — ≥ QUORUM_FRACTION is stressed: the colony SPORULATES (observational only).
    :neutral     — no quorum; the colony acts as a loose collection of individuals.

  The phenotype is a COLONY-level emergent state, checkpointed as :quorum/* datoms (as-of
  queryable). It is an aggregate, never a per-organism verdict (edge-primary). The fruiting
  bonus is bounded (a fraction of the beat's realised production), so it cannot run away.
  Stdlib only. Deterministic. Append-only."
  (:require [ibuki.methods.datoms :as datoms]))

(def quorum-fraction-num 2)
(def quorum-fraction-den 3)            ;; ≥ 2/3 sharing a condition = quorum
(def quorum-min 3)                     ;; need ≥3 organisms for a colony phenotype
(def fruit-bonus-num 1)
(def fruit-bonus-den 4)                ;; fruiting burst = 1/4 of the beat's commons

(def flourish-moods #{"joyful" "grateful"})
(def stress-moods #{"stressed"})

(def states [":flourishing" ":dormant" ":neutral"])

(defn phenotype
  "The colony phenotype from this beat's per-organism moods (code → mood string).
  Returns {:state :flourish :stressed :n}. Deterministic."
  [moods]
  (let [n (count moods)
        flourish (count (filter flourish-moods (vals moods)))
        stressed (count (filter stress-moods (vals moods)))
        state (if (>= n quorum-min)
                (let [need (quot (+ (* n quorum-fraction-num) (dec quorum-fraction-den))
                                 quorum-fraction-den)]
                  (cond
                    (>= flourish need) ":flourishing"
                    (>= stressed need) ":dormant"
                    :else ":neutral"))
                ":neutral")]
    {:state state :flourish flourish :stressed stressed :n n}))

(defn sense
  "Sense the quorum and emit :quorum/* datoms. On :flourishing, the colony fruits: a collective
  commons metabolite worth FRUIT_BONUS of this beat's realised commons nutrient (a gift that
  grows when the colony thrives). Returns {:datoms :state :fruiting-nutrient}."
  [moods beat-commons-nutrient {:keys [beat as-of]}]
  (let [ph (phenotype moods)
        e (str "quorum-" beat)
        base [(datoms/add e ":quorum/state" (:state ph))
              (datoms/add e ":quorum/flourish" (:flourish ph))
              (datoms/add e ":quorum/stressed" (:stressed ph))
              (datoms/add e ":quorum/n" (:n ph))
              (datoms/add e ":quorum/beat" beat)
              (datoms/add e ":quorum/as-of" as-of)]
        fruiting (if (and (= ":flourishing" (:state ph)) (> beat-commons-nutrient 0))
                   (quot (* beat-commons-nutrient fruit-bonus-num) fruit-bonus-den)
                   0)
        out (if (> fruiting 0)
              (let [fe (str "quorum-fruit-" beat)]
                (into base
                      [(datoms/add fe ":metabolite/kind" ":refined")
                       (datoms/add fe ":metabolite/of" e)
                       (datoms/add fe ":metabolite/nutrient" fruiting)
                       (datoms/add fe ":metabolite/source" ":fruiting")  ;; collective, not a single カビ
                       (datoms/add fe ":metabolite/commons" true)
                       (datoms/add fe ":metabolite/beat" beat)
                       (datoms/add fe ":metabolite/as-of" as-of)]))
              base)]
    {:datoms out :state (:state ph) :fruiting-nutrient fruiting}))

(defn quorum-history
  "Single-pass tally of colony phenotypes over the log — the colony's collective-behaviour
  history (how often it fruited / went dormant). Aggregate, deterministic.
  Returns {:states <state→count> :fruiting-nutrient-total <int>}."
  [txs]
  (let [all (mapcat :tx/datoms txs)
        counts (reduce (fn [c [_op _e a v]]
                         (if (= a ":quorum/state") (update c v (fnil inc 0)) c))
                       (zipmap states (repeat 0))
                       all)
        meta (reduce (fn [m [_op e a v]]
                       (cond
                         (= a ":metabolite/source") (assoc-in m [e :source] v)
                         (= a ":metabolite/nutrient") (assoc-in m [e :nutrient] v)
                         :else m))
                     {}
                     all)
        fruiting-total (reduce-kv (fn [acc _e mm]
                                    (if (= ":fruiting" (:source mm))
                                      (+ acc (get mm :nutrient 0))
                                      acc))
                                  0
                                  meta)]
    {:states counts :fruiting-nutrient-total fruiting-total}))
