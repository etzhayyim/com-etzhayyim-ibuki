(ns ibuki.methods.test-health
  "test-health — 息吹 (ibuki) colony 健全性 audit. ADR-2606101200 §健全化.
  Clojure port of `methods/test_health.py`."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.autorun :as autorun]
            [ibuki.methods.datoms :as datoms]
            [ibuki.methods.health :as health]
            [ibuki.methods.joucho :as joucho]
            [ibuki.methods.kaizen-feedback :as kf]))

(defn- tmpdir []
  (str (java.nio.file.Files/createTempDirectory
        "ibuki-health" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- run-autorun [dir cycles]
  (let [log (str dir "/log.edn")]
    (autorun/autorun cycles {:fresh true :log-path log :queue-path (str dir "/q.ndjson")})
    (datoms/read-log log)))

(defn- synthetic-life
  "Hand-built log: one tx per beat carrying the given event kinds for one organism."
  [code kinds-per-beat]
  (loop [txs [] prev "" i 1 kpb kinds-per-beat]
    (if (empty? kpb)
      txs
      (let [kinds (first kpb)
            body (joucho/event-datoms code kinds {:beat i :as-of (+ 2606100000 i)})
            tx (datoms/make-tx body {:tx-id i :as-of (+ 2606100000 i) :prev-cid prev})]
        (recur (conj txs tx) (:tx/cid tx) (inc i) (rest kpb))))))

(defn- rule-set [rep] (set (map :rule (:findings rep))))

(deftest healthy-colony-audits-healthy
  (let [rep (health/audit (run-autorun (tmpdir) 30))]
    (is (and (true? (:healthy rep)) (= [] (:findings rep))))
    (is (= 3 (get-in rep [:colony :count])))
    (is (= 3 (count (get-in rep [:colony :mood-diversity]))))))   ;; no monoculture

(deftest muted-organism-detected
  (let [txs (synthetic-life "10101500" (vec (repeat 20 [":event/kaizen-rejected"])))
        rep (health/audit txs)
        org (get-in rep [:organisms "10101500"])]
    (is (and (= "stressed" (:mood org)) (true? (:muted org))))
    (is (contains? (rule-set rep) "organism-muted"))
    (is (contains? (rule-set rep) "stress-excess"))
    (is (false? (:healthy rep)))))

(deftest muteness-is-recoverable-by-drift
  (let [life (vec (concat (repeat 20 [":event/kaizen-rejected"]) (repeat 40 [":event/idle"])))
        rep (health/audit (synthetic-life "10101500" life))
        org (get-in rep [:organisms "10101500"])]
    (is (and (not= "stressed" (:mood org)) (false? (:muted org))))
    (is (= 0 (:stress-excess org)))))

(deftest checkpoint-divergence-detected
  (let [code "10101500"
        body (-> (joucho/event-datoms code [":event/idle"] {:beat 1 :as-of 2606100001})
                 (into (joucho/joucho-datoms
                        code (joucho/scores {:joy 1 :calm 1 :stress 1 :gratitude 1 :focus 1})
                        "neutral" {:beat 1 :as-of 2606100001})))
        txs [(datoms/make-tx body {:tx-id 1 :as-of 2606100001 :prev-cid ""})]
        rep (health/audit txs)]
    (is (true? (get-in rep [:organisms code :checkpoint-diverged])))
    (is (contains? (rule-set rep) "checkpoint-divergence"))))

(deftest mood-monoculture-detected
  (let [txs (loop [txs [] prev "" i 1 codes ["10101500" "14111500" "50221000"]]
              (if (empty? codes)
                txs
                (let [code (first codes)
                      body (joucho/event-datoms code (vec (repeat 25 ":event/kaizen-rejected"))
                                                {:beat 1 :as-of 2606100001})
                      tx (datoms/make-tx body {:tx-id i :as-of (+ 2606100000 i) :prev-cid prev})]
                  (recur (conj txs tx) (:tx/cid tx) (inc i) (rest codes)))))
        rep (health/audit txs)]
    (is (contains? (rule-set rep) "mood-monoculture"))))

(deftest ecosystem-starved-detected
  (let [code "10101500"
        txs (loop [txs [] prev "" i 1]
              (if (>= i (+ health/eco-grace-beats 3))
                txs
                (let [body (into (joucho/event-datoms code [":event/idle"]
                                                      {:beat i :as-of (+ 2606100000 i)})
                                 [(datoms/add (str "eco-sub-" code "-" i)
                                              ":metabolite/kind" ":substrate")
                                  (datoms/add (str "eco-sub-" code "-" i)
                                              ":metabolite/beat" i)])
                      tx (datoms/make-tx body {:tx-id i :as-of (+ 2606100000 i) :prev-cid prev})]
                  (recur (conj txs tx) (:tx/cid tx) (inc i)))))
        rep (health/audit txs)]
    (is (contains? (rule-set rep) "ecosystem-starved"))))

(deftest complete-food-web-is-not-starved
  (let [rep (health/audit (run-autorun (tmpdir) 30))]
    (is (not (contains? (rule-set rep) "ecosystem-starved")))
    (is (true? (:healthy rep)))))

(defn- niche-log
  "A minimal log asserting one organism per given niche (birth :organism/niche)."
  [& niches]
  (loop [txs [] prev "" i 0 ns niches]
    (if (empty? ns)
      txs
      (let [nn (first ns)
            body [(datoms/add (str "org-c" i) ":organism/niche" nn)
                  (datoms/add (str "org-c" i) ":organism/born-beat" 1)]
            tx (datoms/make-tx body {:tx-id (inc i) :as-of (+ 2606100000 i) :prev-cid prev})]
        (recur (conj txs tx) (:tx/cid tx) (inc i) (rest ns))))))

(deftest evenness-pielou
  (is (= 0.0 (health/evenness {})))
  (is (= 0.0 (health/evenness {":niche/producer" 5})))          ;; one niche → 0
  (is (< (Math/abs (- (health/evenness {"a" 3 "b" 3 "c" 3}) 1.0)) 1e-9))  ;; perfectly even
  (is (< 0 (health/evenness {"a" 10 "b" 1 "c" 1}) 0.7)))        ;; dominated → low

(deftest keystone-niche-absent-detected
  (let [rep (health/audit (niche-log ":niche/producer" ":niche/producer" ":niche/router"))]
    (is (contains? (rule-set rep) "keystone-niche-absent"))
    (is (false? (:healthy rep)))))

(deftest niche-imbalance-detected
  (let [rep (health/audit (apply niche-log (concat (repeat 12 ":niche/producer")
                                                   [":niche/router" ":niche/decomposer"])))]
    (is (contains? (rule-set rep) "niche-imbalance"))
    ;; a missing niche takes precedence (keystone) over imbalance — mutually exclusive
    (is (not (contains? (rule-set rep) "keystone-niche-absent")))))

(deftest balanced-colony-is-resilient
  (let [rep (health/audit (niche-log ":niche/producer" ":niche/router" ":niche/decomposer"))]
    (is (not (contains? (rule-set rep) "keystone-niche-absent")))
    (is (not (contains? (rule-set rep) "niche-imbalance")))
    (is (< (Math/abs (- (get-in rep [:colony :eco-maturity]) 1.0)) 1e-9))))  ;; perfectly even

(deftest seed-colony-eco-maturity-logged
  (let [txs (run-autorun (tmpdir) 12)
        rep (health/audit txs)
        mats (->> txs (mapcat :tx/datoms)
                  (filter #(= (nth % 2) ":health/eco-maturity")) (mapv #(nth % 3)))]
    (is (< (Math/abs (- (get-in rep [:colony :eco-maturity]) 1.0)) 1e-9))
    (is (not (contains? (rule-set rep) "keystone-niche-absent")))
    (is (and (seq mats) (= (peek mats) 1.0)))))             ;; rounded to 4dp → exactly 1.0

(deftest health-datoms-checkpoint-shape
  (let [rep (health/audit (run-autorun (tmpdir) 12))
        ds (health/health-datoms rep {:beat 12 :as-of 2606100012})
        attrs (set (map #(nth % 2) ds))]
    (is (set/subset? #{":health/healthy" ":health/findings" ":health/mood-kinds"} attrs))
    (is (every? #(= (nth % 0) ":db/add") ds))
    ;; no per-organism wellbeing score is ever asserted (edge-primary)
    (is (not (some #(str/includes? (nth % 2) "score") ds)))))

(deftest autorun-checkpoints-health-every-10-beats
  (let [txs (run-autorun (tmpdir) 20)
        healthy-flags (->> txs
                           (mapcat (fn [tx] (map (fn [d] [(:tx/id tx) d]) (:tx/datoms tx))))
                           (filter (fn [[_id d]] (= (nth d 2) ":health/healthy")))
                           (mapv (fn [[id d]] [id (nth d 3)])))]
    (is (= [10 20] (mapv first healthy-flags)))
    (is (every? (fn [[_b v]] (true? v)) healthy-flags))))   ;; the fixed loop stays healthy

(deftest findings-feed-the-kaizen-loop
  (let [txs (synthetic-life "10101500" (vec (repeat 20 [":event/kaizen-rejected"])))
        rep (health/audit txs)
        dir (tmpdir)
        p (str dir "/proposals.ndjson")
        n (health/write-proposals rep p)]
    (is (= n (count (:findings rep))))
    (is (> n 0))
    (let [proposals (kf/read-proposals p)]
      (is (set/subset? (set (map :rule proposals)) (set health/rules))))))

(deftest audit-deterministic
  (is (= (health/audit (run-autorun (tmpdir) 15))
         (health/audit (run-autorun (tmpdir) 15)))))
