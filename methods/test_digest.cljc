(ns ibuki.methods.test-digest
  "test-digest — 息吹 (ibuki) colony digest: the colony reasons + reports. ADR-2606101800.
  Clojure port of `methods/test_digest.py`."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.autorun :as autorun]
            [ibuki.methods.datoms :as datoms]
            [ibuki.methods.digest :as digest]))

(defn- tmpdir []
  (str (java.nio.file.Files/createTempDirectory
        "ibuki-digest" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- life
  "Run a fresh autonomous life and return the parsed log."
  [dir cycles]
  (let [log (str dir "/log.edn")]
    (autorun/autorun cycles {:fresh true :log-path log :queue-path (str dir "/q.ndjson")})
    (datoms/read-log log)))

(deftest assemble-is-log-derived-and-complete
  (let [st (digest/assemble (life (tmpdir) 12))]
    (doseq [k [:organisms :healthy :eco-maturity :commons-offered
               :commons-available :quorum-states :findings]]
      (is (contains? st k) (str "missing digest state key " k)))
    (is (= 3 (:organisms st)))
    (is (pos? (:commons-offered st)))))

(deftest template-digest-is-mirror-no-advice
  (let [st (digest/assemble [])
        text (digest/template-digest st)]
    (is (str/includes? text "息吹"))
    (is (str/includes? text "no advice"))))

(deftest narrate-offline-uses-template
  ;; the test environment never sets IBUKI_MURAKUMO_LIVE (the Python pops it) — offline,
  ;; the narration is the deterministic template
  (let [st (digest/assemble (life (tmpdir) 12))
        n (digest/narrate st {:beat 12})]
    (is (= "template" (:via n)))
    (is (str/includes? (:text n) "息吹"))))

(deftest digest-datoms-dry-run-only
  (let [st (digest/assemble [])
        ds (digest/digest-datoms st {:text "x" :via "template"} {:beat 1 :as-of 1})
        statuses (for [[_op _e a v] ds :when (= a ":digest/status")] v)]
    (is (= [":dry-run"] (vec statuses)))      ;; G8: :published unrepresentable
    (is (every? #(= ":db/add" (nth % 0)) ds))
    ;; aggregate colony entity only — no per-organism verdict
    (is (= #{"digest-1"} (set (map #(nth % 1) ds))))))

(deftest make-deterministic-offline
  (let [a (digest/make (life (tmpdir) 12) {:beat 12 :as-of 2606100012})
        b (digest/make (life (tmpdir) 12) {:beat 12 :as-of 2606100012})]
    (is (= (:datoms a) (:datoms b)))))

(deftest autorun-emits-digest-at-health-cadence
  (let [txs (life (tmpdir) 20)
        digests (for [tx txs [_op _e a v] (:tx/datoms tx)
                      :when (= a ":digest/text")] v)
        beats (sort (for [tx txs [_op _e a v] (:tx/datoms tx)
                          :when (= a ":digest/beat")] v))]
    (is (= [10 20] (vec beats)))              ;; every health-every beats
    (is (seq digests))
    (is (every? string? digests))))

(deftest digest-reports-commons-offered-to-humanity
  (let [txs (life (tmpdir) 20)
        offered (vec (for [tx txs [_op _e a v] (:tx/datoms tx)
                           :when (= a ":digest/commons-offered")] v))]
    (is (seq offered))
    (is (pos? (peek offered)))))              ;; the gift is reported, growing

(deftest digest-via-is-murakumo-or-template-only
  (let [txs (life (tmpdir) 10)
        vias (set (for [tx txs [_op _e a v] (:tx/datoms tx)
                        :when (= a ":digest/via")] v))]
    (is (set/subset? vias #{":template" ":murakumo"}))))
