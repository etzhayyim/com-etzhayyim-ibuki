(ns ibuki.methods.test-react-loop
  "test-react-loop — the co-scientist ReAct beat end-to-end: sense→…→act→learn→persist. ADR-2606201200."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.coscientist :as cosci]
            [ibuki.methods.datoms :as datoms]
            [ibuki.methods.react-loop :as rl]
            [kotoba.datom :as kd]))

(defn- tmp-log []
  (str (io/file (System/getProperty "java.io.tmpdir")
                (str "ibuki-cosci-test-" (System/nanoTime) ".kotoba.edn"))))

(defn- commons-sense
  "A SENSE log carrying commons metabolites (so exported>0, η>0, non-parasitic)."
  [nutrient]
  [(datoms/make-tx [(datoms/add "eco-refined-d-1" ":metabolite/kind" ":refined")
                    (datoms/add "eco-refined-d-1" ":metabolite/commons" true)
                    (datoms/add "eco-refined-d-1" ":metabolite/nutrient" nutrient)]
                   {:tx-id 1 :as-of 1 :prev-cid ""})])

(deftest a-beat-persists-and-chooses-an-aligned-mechanism
  (let [log (tmp-log)
        r (rl/beat {:log-path log :sense-txs (commons-sense 100)
                    :env-reading {:compute-hours 6 :donation 2} :colony-size 3})]
    (is (= 0 (:beat r)))
    (is (:appended r))
    (is (:alive? r))
    (is (pos? (:reserves r)))
    (is (string? (:mechanism r)))
    (is (contains? cosci/aligned-mechanisms (:mechanism r)))
    (is (some? (:head r)))
    ;; the chain is intact
    (is (:ok (kd/verify-chain log)))))

(deftest experiment-is-dry-run-and-preregistered
  (let [log (tmp-log)]
    (rl/beat {:log-path log :sense-txs (commons-sense 100)
              :env-reading {:compute-hours 6} :colony-size 2})
    (let [txs (kd/read-log log)
          exp (datoms/fold-entity txs "ibuki:experiment-0")]
      (is (= "dry-run" (get exp ":experiment/status")))   ;; outward leg is member-principal (G8)
      (is (contains? exp ":experiment/predicted-up-milli"))
      (is (contains? exp ":experiment/reserves-at-act")))))

(deftest the-loop-learns-from-the-prior-experiment
  (let [log (tmp-log)]
    ;; beat 0: pre-register
    (rl/beat {:log-path log :sense-txs (commons-sense 100)
              :env-reading {:compute-hours 8} :colony-size 2})
    ;; beat 1: reserves rose → the prior experiment is scored (OBSERVE+LEARN)
    (let [r1 (rl/beat {:log-path log :sense-txs (commons-sense 100)
                       :env-reading {:compute-hours 8} :colony-size 2})]
      (is (= 1 (:beat r1)))
      (is (number? (:outcome-score r1)))                  ;; a prior experiment was scored
      ;; a learned weight is now on the log
      (let [w (rl/read-weights (kd/read-log log))]
        (is (seq w))
        (is (every? #(<= rl/weight-floor % rl/weight-ceil) (vals w)))))))

(deftest non-parasitism-flag-on-an-isolated-loop
  ;; with NO commons in the sense log, η=0 → the loop honestly flags itself a net taker
  (let [log (tmp-log)
        r (rl/beat {:log-path log :env-reading {:compute-hours 6} :colony-size 2})]
    (is (:parasitic? r))
    (is (= 0 (:exported r)))))

(deftest resume-safe-and-idempotent
  (let [log (tmp-log)]
    (dotimes [_ 3]
      (rl/beat {:log-path log :sense-txs (commons-sense 100)
                :env-reading {:compute-hours 5} :colony-size 2}))
    (let [n (count (kd/read-log log))]
      (is (= 3 n))
      (is (:ok (kd/verify-chain log)))
      ;; logical beat derives from log length (resume-safe)
      (let [r (rl/beat {:log-path log :sense-txs (commons-sense 100)
                        :env-reading {:compute-hours 5} :colony-size 2})]
        (is (= 3 (:beat r)))))))

(deftest representative-reading-is-deterministic-and-bounded
  (is (= (rl/representative-reading 4 3) (rl/representative-reading 4 3)))
  (let [reading (rl/representative-reading 7 3)]
    (is (every? (fn [[_ v]] (>= v 0)) reading))))
