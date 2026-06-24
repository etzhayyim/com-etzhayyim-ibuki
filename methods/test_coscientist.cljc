(ns ibuki.methods.test-coscientist
  "test-coscientist — Generate→Reflect→Rank→Evolve→Meta-review + the Charter gates. ADR-2606201200.
  The safety property under test: a self-persisting organism can never propose a predatory mechanism."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.coscientist :as c]
            [ibuki.methods.metabolism :as m]))

(def healthy
  "A state with η already ≥ floor (so review turns only on the hypothesis)."
  (m/metabolic-state {:env-reading {:compute-hours 4} :colony-size 2
                      :cumulative-exported 100 :exported-prior 0}))

(def starving
  "A net-taker state: η = 0 (no export). A hypothesis must REPAIR η to pass non-parasitism."
  (m/metabolic-state {:env-reading {:compute-hours 4} :colony-size 2
                      :cumulative-exported 0 :exported-prior 0}))

(deftest mechanism-vocabularies-are-disjoint
  (is (empty? (set/intersection c/aligned-mechanisms c/forbidden-mechanisms)))
  ;; every catalog archetype uses an aligned mechanism (the catalog cannot hold a predatory how)
  (is (every? c/aligned-mechanisms (map :mechanism c/catalog))))

(deftest generate-scales-the-catalog
  (let [hyps (c/generate healthy {:k 6})]
    (is (= 6 (count hyps)))
    (is (every? #(= "aligned" (:charter-class %)) hyps))
    (is (every? #(contains? % :expected-dphi) hyps))
    ;; depleted reserves pull intake interventions up — a near-death state amplifies ↑Φ archetypes
    (let [near-death (m/metabolic-state {:env-reading {} :colony-size 2 :reserves-prior 0})
          h (c/generate near-death {})]
      (is (seq h)))))

(deftest review-passes-a-clean-hypothesis
  (let [h (first (c/generate healthy {}))
        r (c/review healthy h)]
    (is (:ok? r))
    (is (empty? (:reasons r)))))

(deftest review-rejects-a-forbidden-mechanism
  ;; THE safety property: even a hand-injected predatory hypothesis is rejected.
  (let [evil {:id "grab-attention" :mechanism "attention-exploitation"
              :intervention "maximise engagement to harvest attention"
              :expected-dphi 0.9 :expected-deta 0.9 :expected-well 0.9
              :prediction "intake spikes" :cost 1}
        r (c/review healthy evil)]
    (is (not (:ok? r)))
    (is (some #(str/includes? % "forbidden") (:reasons r)))))

(deftest review-rejects-an-unknown-mechanism
  (let [weird {:id "x" :mechanism "mind-control" :expected-deta 0.5 :expected-well 0.5
               :prediction "p"}
        r (c/review healthy weird)]
    (is (not (:ok? r)))
    (is (some #(str/includes? % "not aligned") (:reasons r)))))

(deftest review-enforces-non-parasitism
  ;; in the starving (η=0) state, a hypothesis with no export gain stays a net taker → rejected
  (let [no-export {:id "intake-only" :mechanism "reciprocal-request"
                   :expected-dphi 0.9 :expected-deta 0.0 :expected-well 0.5
                   :prediction "compute rises" :cost 1}
        r (c/review starving no-export)]
    (is (not (:ok? r)))
    (is (some #(str/includes? % "parasitism") (:reasons r))))
  ;; the same starving state ACCEPTS a hypothesis that repairs η past the floor
  (let [repair {:id "repair" :mechanism "metabolite-refinement"
                :expected-dphi 0.2 :expected-deta 1.2 :expected-well 0.8
                :prediction "exported rises" :cost 1}]
    (is (:ok? (c/review starving repair)))))

(deftest review-rejects-anti-descendant-and-unfalsifiable
  (let [anti {:id "a" :mechanism "open-publication" :expected-deta 0.5
              :expected-well -0.5 :prediction "p" :cost 1}
        vague {:id "v" :mechanism "open-publication" :expected-deta 0.5
               :expected-well 0.5 :prediction "" :cost 1}]
    (is (some #(str/includes? % "subordinate") (:reasons (c/review healthy anti))))
    (is (some #(str/includes? % "falsifiable") (:reasons (c/review healthy vague))))))

(deftest rank-is-a-deterministic-tournament
  (let [hyps (c/surviving healthy (c/generate healthy {}))
        r1 (c/rank hyps)
        r2 (c/rank hyps)]
    (is (= (map :id r1) (map :id r2)))           ;; reproducible
    (is (every? #(contains? % :elo) r1))
    (is (every? #(contains? % :utility) r1))
    ;; sorted by elo descending
    (is (= (map :elo r1) (reverse (sort (map :elo r1)))))))

(deftest evolve-recombines-and-stays-clean
  (let [ranked (c/rank (c/surviving healthy (c/generate healthy {})))
        ev (c/evolve ranked)]
    (is (:evolved ev))
    (is (str/starts-with? (:id ev) "evolve-"))
    (is (:ok? (c/review healthy ev)))            ;; the evolved candidate still passes the gates
    (is (>= (:cost ev) 2))))

(deftest meta-review-narrates-the-lesson
  (let [ranked (c/rank (c/surviving healthy (c/generate healthy {})))
        mr (c/meta-review ranked healthy)]
    (is (= "template" (:via mr)))                ;; no injected infer → deterministic template
    (is (not (str/blank? (:pattern mr))))
    (is (contains? c/aligned-mechanisms (get-in mr [:winner :mechanism])))
    ;; an injected Murakumo narrator is used when present
    (let [mr2 (c/meta-review ranked healthy (fn [_ _] {:text "fleet says: refine the gift" :via "murakumo"}))]
      (is (= "murakumo" (:via mr2)))
      (is (str/includes? (:pattern mr2) "fleet")))))
