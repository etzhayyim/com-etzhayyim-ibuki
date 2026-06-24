(ns ibuki.methods.test-quorum
  "test-quorum — 息吹 (ibuki) quorum sensing: emergent collective behaviour. ADR-2606101200.
  Clojure port of `methods/test_quorum.py`."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.autorun :as autorun]
            [ibuki.methods.datoms :as datoms]
            [ibuki.methods.quorum :as quorum]
            [ibuki.methods.symbiosis :as sym]))

(defn- tmpdir []
  (str (java.nio.file.Files/createTempDirectory
        "ibuki-quorum" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest below-min-is-neutral
  (is (= ":neutral" (:state (quorum/phenotype {"a" "joyful" "b" "grateful"})))))

(deftest flourishing-quorum
  (let [ph (quorum/phenotype {"a" "joyful" "b" "grateful" "c" "joyful" "d" "neutral"})]
    (is (= ":flourishing" (:state ph)))   ;; 3/4 flourishing ≥ ceil(4*2/3)=3
    (is (= 3 (:flourish ph)))))

(deftest dormant-quorum
  (is (= ":dormant" (:state (quorum/phenotype {"a" "stressed" "b" "stressed" "c" "stressed"})))))

(deftest no-quorum-is-neutral
  (is (= ":neutral" (:state (quorum/phenotype {"a" "joyful" "b" "stressed" "c" "neutral" "d" "calm"})))))

(deftest fruiting-emits-collective-commons
  (let [out (quorum/sense {"a" "joyful" "b" "grateful" "c" "joyful"} 40
                          {:beat 2 :as-of 2606100002})]
    (is (= ":flourishing" (:state out)))
    (is (= (quot (* 40 quorum/fruit-bonus-num) quorum/fruit-bonus-den)
           (:fruiting-nutrient out)))
    (is (= [":fruiting"]                  ;; collective gift to humanity
           (->> (:datoms out) (filter #(= ":metabolite/source" (nth % 2))) (mapv #(nth % 3)))))
    (is (= [true]
           (->> (:datoms out) (filter #(= ":metabolite/commons" (nth % 2))) (mapv #(nth % 3)))))))

(deftest fruiting-is-bounded-fraction
  (let [out (quorum/sense {"a" "joyful" "b" "joyful" "c" "grateful"} 100 {:beat 1 :as-of 1})]
    (is (= 25 (:fruiting-nutrient out)))  ;; bounded, cannot run away
    (is (< 25 100))))

(deftest dormant-emits-no-fruiting
  (let [out (quorum/sense {"a" "stressed" "b" "stressed" "c" "stressed"} 40 {:beat 1 :as-of 1})]
    (is (= ":dormant" (:state out)))
    (is (= 0 (:fruiting-nutrient out)))
    (is (not (some #(= ":metabolite/kind" (nth % 2)) (:datoms out))))))

(deftest quorum-datoms-append-only-and-aggregate
  (let [out (quorum/sense {"a" "joyful" "b" "joyful" "c" "grateful"} 40 {:beat 1 :as-of 1})]
    (is (every? #(= ":db/add" (nth % 0)) (:datoms out)))
    ;; colony-level entity only, no per-organism quorum verdict
    (is (= #{"quorum-1"}
           (set (->> (:datoms out)
                     (filter #(str/starts-with? (nth % 2) ":quorum/"))
                     (map #(nth % 1))))))))

(deftest deterministic
  (let [a (quorum/sense {"a" "joyful" "b" "grateful" "c" "joyful"} 40 {:beat 3 :as-of 3})
        b (quorum/sense {"a" "joyful" "b" "grateful" "c" "joyful"} 40 {:beat 3 :as-of 3})]
    (is (= (:datoms a) (:datoms b)))))

;; ── end-to-end on the autonomous loop ───────────────────────────────────────

(deftest autorun-records-quorum-history
  (let [dir (tmpdir)
        log (str dir "/log.edn")]
    (autorun/autorun 20 {:fresh true :log-path log :queue-path (str dir "/q.ndjson")})
    (let [txs (datoms/read-log log)
          hist (quorum/quorum-history txs)]
      (is (= 20 (reduce + (vals (:states hist)))))        ;; one phenotype per beat
      (is (set/subset? (set (keys (:states hist))) (set quorum/states))))))

(deftest fruiting-feeds-the-symbiosis-pool
  ;; When the colony fruits, the collective commons burst lands in the symbiosis pool —
  ;; the colony's thriving becomes an extra gift to humanity (共生).
  (let [dir (tmpdir)
        log (str dir "/log.edn")]
    (autorun/autorun 20 {:fresh true :log-path log :queue-path (str dir "/q.ndjson")})
    (let [txs (datoms/read-log log)
          hist (quorum/quorum-history txs)
          pool (sym/commons-pool txs)]
      (if (pos? (get (:states hist) ":flourishing" 0))
        (do (is (pos? (:fruiting-nutrient-total hist)))
            ;; the fruiting nutrient is part of the offered pool
            (is (>= (:offered pool) (:fruiting-nutrient-total hist))))
        (is (= 0 (:fruiting-nutrient-total hist)))))))    ;; honest: no fruit, no bonus
