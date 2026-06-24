(ns ibuki.methods.test-metabolism
  "test-metabolism — the dissipative-structure free-energy fold. ADR-2606201200."
  (:require [clojure.test :refer [deftest is]]
            [ibuki.methods.datoms :as datoms]
            [ibuki.methods.metabolism :as m]))

(defn- commons-log
  "A log offering one commons metabolite per given nutrient value (web-report sees them)."
  [& nutrients]
  (loop [txs [] prev "" i 1 ns nutrients]
    (if (empty? ns)
      txs
      (let [n (first ns)
            e (str "eco-refined-d-" i)
            body [(datoms/add e ":metabolite/kind" ":refined")
                  (datoms/add e ":metabolite/commons" true)
                  (datoms/add e ":metabolite/nutrient" n)
                  (datoms/add e ":metabolite/source" ":relayed")
                  (datoms/add e ":metabolite/beat" i)]
            tx (datoms/make-tx body {:tx-id i :as-of (+ 2606100000 i) :prev-cid prev})]
        (recur (conj txs tx) (:tx/cid tx) (inc i) (rest ns))))))

(deftest intake-weights-and-attention-cap
  (is (= 0 (m/intake-of nil)))
  (is (= 0 (m/intake-of {})))
  ;; 2 compute-hours (×4) + 3 donation (×1) + 1 member (×6) = 8+3+6 = 17
  (is (= 17 (m/intake-of {:compute-hours 2 :donation 3 :members 1})))
  ;; attention is capped: 100 attention ×1 = 100 → capped at attention-cap
  (is (= m/attention-cap (m/intake-of {:attention 100})))
  ;; unknown sources are ignored at the membrane
  (is (= 0 (m/intake-of {:bribe 999 :ad-revenue 999})))
  ;; negative readings are floored at 0 (no negative intake)
  (is (= 0 (m/intake-of {:donation -50}))))

(deftest colony-order-is-a-negentropy-source
  ;; the SoS integration (ADR-2606212200): the colony's aggregate information-control
  ;; (ie-flow.score) feeds the organism's metabolic intake → Φ → reserves → survival.
  (is (= 3 (get m/intake-weights :colony-order)) "colony-order is a recognised source")
  ;; 5 colony-order ×3 = 15, additive on top of the other sources
  (is (= 15 (m/intake-of {:colony-order 5})))
  (is (= 21 (m/intake-of {:members 1 :colony-order 5})) "additive: 6 + 15")
  ;; a higher colony score ⇒ strictly higher intake ⇒ higher Φ (the reward integration)
  (is (> (m/intake-of {:colony-order 10}) (m/intake-of {:colony-order 2}))
      "more colony information-control ⇒ more free-energy intake (the organism's reward rises)"))

(deftest dissipation-scales-with-colony
  (is (= m/base-dissipation (m/dissipation-of 0)))
  (is (= (+ m/base-dissipation 3) (m/dissipation-of 3))))

(deftest phi-reserves-and-death-floor
  ;; thriving beat: intake > dissipation → Φ>0, reserves grow
  (let [s (m/metabolic-state {:env-reading {:compute-hours 10} :colony-size 3
                              :reserves-prior 0})]
    (is (pos? (:phi s)))
    (is (= (:phi s) (:reserves s)))
    (is (:alive? s)))
  ;; starving beat: no intake, reserves can't go below zero (death floor)
  (let [s (m/metabolic-state {:env-reading {} :colony-size 3 :reserves-prior 2})]
    (is (neg? (:phi s)))
    (is (= 0 (:reserves s)))
    (is (not (:alive? s)))))

(deftest eta-and-non-parasitism
  ;; exported negentropy ≥ consumed → η ≥ 1 → not a net taker
  (let [s (m/metabolic-state {:env-reading {:compute-hours 1} :colony-size 0
                              :cumulative-exported 100 :exported-prior 0})]
    (is (>= (:eta s) m/parasite-floor))
    (is (not (:parasitic? s))))
  ;; no export → η = 0 → parasitic flag set (the safety signal)
  (let [s (m/metabolic-state {:env-reading {:compute-hours 1} :colony-size 0
                              :cumulative-exported 0 :exported-prior 0})]
    (is (= 0.0 (:eta s)))
    (is (:parasitic? s))))

(deftest surprise-is-variational-free-energy
  ;; reserves at/above target → no surprise; deep below → surprise → 1
  (let [hi (m/metabolic-state {:env-reading {:compute-hours 100} :colony-size 1
                               :reserves-prior 1000 :target-horizon 6})
        lo (m/metabolic-state {:env-reading {} :colony-size 1
                               :reserves-prior 0 :target-horizon 6})]
    (is (= 0.0 (:surprise hi)))
    (is (> (:surprise lo) 0.9))))

(deftest cumulative-exported-reads-the-foodweb
  (is (= 0 (m/cumulative-exported [])))
  (is (= 30 (m/cumulative-exported (commons-log 10 20)))))

(deftest prior-state-folds-the-checkpoint
  (let [tx (datoms/make-tx [(datoms/add "ibuki:metabolism" ":metabolic/reserves" 42)
                            (datoms/add "ibuki:metabolism" ":metabolic/cumulative-exported" 7)]
                           {:tx-id 0 :as-of 1 :prev-cid ""})]
    (is (= {:reserves-prior 42 :exported-prior 7} (m/prior-state [tx])))
    (is (= {:reserves-prior 0 :exported-prior 0} (m/prior-state [])))))
