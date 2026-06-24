(ns ibuki.methods.test-ecosystem
  "test-ecosystem — 息吹 (ibuki) the colony as an ECOSYSTEM. ADR-2606101200 §生態系.
  Clojure port of `methods/test_ecosystem.py`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.autorun :as autorun]
            [ibuki.methods.datoms :as datoms]
            [ibuki.methods.ecosystem :as eco]
            [ibuki.methods.health :as health]
            [ibuki.methods.joucho :as joucho]))

(def SEED [{:code "10101500" :niche ":niche/producer"}
           {:code "14111500" :niche ":niche/decomposer"}
           {:code "50221000" :niche ":niche/router"}])

(defn- moods [& {:as over}]
  (merge (into {} (map (fn [o] [(:code o) (joucho/personality-baseline (:code o))])) SEED)
         over))

(defn- tmpdir []
  (str (java.nio.file.Files/createTempDirectory
        "ibuki-eco" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- a2 [d] (nth d 2))
(defn- a3 [d] (nth d 3))

(deftest niche-closed-vocab
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed vocab"
                        (eco/niche-of "x" ":niche/parasite"))))

(deftest niche-declared-then-hashed
  (is (= ":niche/router" (eco/niche-of "10101500" ":niche/router")))
  (let [h (eco/niche-of "10101500")]
    (is (and (some #{h} eco/niches) (= h (eco/niche-of "10101500"))))))   ;; deterministic

(deftest hash-niches-spread-across-the-fleet
  (let [counts (reduce (fn [c i] (update c (eco/niche-of (str (+ 10000000 i))) (fnil inc 0)))
                       (zipmap eco/niches (repeat 0))
                       (range 900))]
    (is (every? #(> % 150) (vals counts)))))                ;; every niche populated

(deftest nutrient-floors-at-zero
  (is (= 0 (eco/nutrient (joucho/scores {:joy 0 :gratitude 0 :stress 100}))))
  (is (= 80 (eco/nutrient (joucho/scores {:joy 60 :gratitude 50 :stress 30})))))

(deftest trophic-cascade-producer-to-commons
  (let [out (eco/cycle SEED (moods) {:beat 1 :as-of 2606100001})
        ds (:datoms out)]
    (is (= ["10101500"] (get-in out [:roles ":niche/producer"])))
    (is (= ["50221000"] (get-in out [:roles ":niche/router"])))
    (is (= ["14111500"] (get-in out [:roles ":niche/decomposer"])))
    ;; a substrate was fixed, relayed, and refined into a commons metabolite
    (is (some #(and (= (a2 %) ":metabolite/kind") (= (a3 %) ":substrate")) ds))
    (is (some #(and (= (a2 %) ":exchange/kind") (= (a3 %) ":relay")) ds))
    (is (and (seq (:refined out))
             (some #(and (= (a2 %) ":metabolite/commons") (true? (a3 %))) ds)))))

(deftest fed-producer-returned-not-self-emitted
  (let [out (eco/cycle SEED (moods) {:beat 1 :as-of 2606100001})]
    (is (some #{"10101500"} (:fed out)))
    ;; ecosystem emits ONLY metabolic datoms — no :joucho.event/* (no divergence risk)
    (is (not (some #(str/starts-with? (a2 %) ":joucho.event/") (:datoms out))))
    ;; the symbiosis event is a registered joucho event (mutualism: calms + gratifies)
    (is (contains? joucho/event-deltas eco/symbiosis-event))))

(deftest satiation-skips-feeding-but-recycles-detritus
  (let [out (eco/cycle SEED (moods) {:beat 2 :as-of 2606100002 :satiated #{"10101500"}})]
    (is (= [] (:fed out)))                                  ;; satiation → no mutualism feeding
    (is (not (some #(= (a2 %) ":exchange/kind") (:datoms out))))   ;; no relay edge
    (is (seq (:refined out)))                               ;; detritus still → commons output
    (is (= [":detritus"] (->> (:datoms out)
                              (filter #(= (a2 %) ":metabolite/source"))
                              (mapv a3))))))

(deftest detritus-yield-is-lossy
  (let [out (eco/cycle SEED (moods "10101500" (joucho/scores {:joy 80 :gratitude 80 :stress 20}))
                       {:beat 2 :as-of 2 :satiated #{"10101500"}})
        sub-n (->> (:datoms out)
                   (filter #(and (= (a2 %) ":metabolite/nutrient")
                                 (str/starts-with? (nth % 1) "eco-sub-")))
                   first a3)
        det-n (->> (:datoms out)
                   (filter #(and (= (a2 %) ":metabolite/nutrient")
                                 (str/starts-with? (nth % 1) "eco-detritus-")))
                   first a3)]
    (is (= det-n (quot (* sub-n eco/detritus-yield-num) eco/detritus-yield-den)))
    (is (< det-n sub-n))))

(deftest no-decomposer-means-no-commons-output
  (let [producers-only [{:code "10101500" :niche ":niche/producer"}
                        {:code "50221000" :niche ":niche/router"}]
        out (eco/cycle producers-only (moods) {:beat 1 :as-of 2606100001})]
    (is (= [] (:refined out)))
    (is (= [] (:fed out)))))                                ;; web incomplete → no citric acid

(deftest stressed-producer-fixes-less-nutrient
  (let [rich (eco/cycle SEED (moods "10101500" (joucho/scores {:joy 80 :gratitude 80 :stress 20}))
                        {:beat 1 :as-of 1})
        poor (eco/cycle SEED (moods "10101500" (joucho/scores {:joy 30 :gratitude 20 :stress 90}))
                        {:beat 1 :as-of 1})
        rn (->> (:datoms rich) (filter #(= (a2 %) ":metabolite/nutrient")) first a3)
        pn (->> (:datoms poor) (filter #(= (a2 %) ":metabolite/nutrient")) first a3)]
    (is (> rn pn))))                                        ;; flourishing → richer substrate

(deftest cycle-deterministic
  (let [a (eco/cycle SEED (moods) {:beat 2 :as-of 2606100002})
        b (eco/cycle SEED (moods) {:beat 2 :as-of 2606100002})]
    (is (= (:datoms a) (:datoms b)))))

;; ── stigmergy: the 粘菌 router reinforces established trails (Physarum) ──────

(def MULTI [{:code "p1" :niche ":niche/producer"}
            {:code "r1" :niche ":niche/router"}
            {:code "d1" :niche ":niche/decomposer"}
            {:code "d2" :niche ":niche/decomposer"}])

(defn- mmoods [] {"p1" (joucho/scores {:joy 70 :gratitude 70 :stress 20})})

(deftest trail-strength-decays-with-age
  (let [txs [(datoms/make-tx
              [(datoms/add "eco-relay-r1-1" ":exchange/kind" ":relay")
               (datoms/add "eco-relay-r1-1" ":exchange/from" "p1")
               (datoms/add "eco-relay-r1-1" ":exchange/to" "d1")
               (datoms/add "eco-relay-r1-1" ":exchange/beat" 1)]
              {:tx-id 1 :as-of 1 :prev-cid ""})]]
    (is (< (Math/abs (- (get (eco/trail-strengths txs 2) ["p1" "d1"]) eco/trail-decay)) 1e-9))
    (is (< (Math/abs (- (get (eco/trail-strengths txs 3) ["p1" "d1"])
                        (* eco/trail-decay eco/trail-decay))) 1e-9))
    (is (= {} (eco/trail-strengths txs (+ 2 eco/trail-horizon))))))

(deftest routing-prefers-the-reinforced-path
  (let [no-trail (eco/cycle MULTI (mmoods) {:beat 5 :as-of 5})
        default-to (->> (:datoms no-trail) (filter #(= (a2 %) ":exchange/to")) first a3)]
    (is (= "d1" default-to))                                ;; absent trail → round-robin default
    (let [biased (eco/cycle MULTI (mmoods) {:beat 5 :as-of 5 :trails {["p1" "d2"] 4.0}})
          to (->> (:datoms biased) (filter #(= (a2 %) ":exchange/to")) first a3)]
      (is (= "d2" to)))))                                   ;; the established trail captures flux

(deftest trail-self-reinforces-over-a-run
  ;; over a multi-path run the router CONVERGES — most relays land on a single decomposer
  (let [[chosen]
        (loop [txs [] prev "" beat 1 chosen {}]
          (if (> beat 20)
            [chosen]
            (let [tr (eco/trail-strengths txs beat)
                  out (eco/cycle MULTI (mmoods) {:beat beat :as-of beat :trails tr})
                  to (->> (:datoms out) (filter #(= (a2 %) ":exchange/to")) (mapv a3))
                  chosen (if (seq to) (update chosen (first to) (fnil inc 0)) chosen)
                  body (if (seq (:datoms out))
                         (:datoms out)
                         [(datoms/add (str "nop-" beat) ":x/y" beat)])
                  tx (datoms/make-tx body {:tx-id beat :as-of beat :prev-cid prev})]
              (recur (conj txs tx) (:tx/cid tx) (inc beat) chosen))))]
    (is (and (seq chosen)
             (>= (reduce max (vals chosen)) (* 0.9 (reduce + (vals chosen))))))))

(deftest append-only
  (let [out (eco/cycle SEED (moods) {:beat 1 :as-of 1})]
    (is (every? #(= (nth % 0) ":db/add") (:datoms out)))))

;; ── end-to-end: the ecosystem lives across a real autorun life ──────────────

(defn- run-life [dir cycles]
  (let [log (str dir "/log.edn")]
    (autorun/autorun cycles {:fresh true :log-path log :queue-path (str dir "/q.ndjson")})
    (datoms/read-log log)))

(deftest autorun-grows-a-food-web
  (let [txs (run-life (tmpdir) 12)
        rep (eco/web-report txs)]
    (is (> (:commons-metabolites rep) 0))                   ;; humanity is being fed
    (is (> (:relays rep) 0))
    (is (> (:commons-nutrient-to-humanity rep) 0))))

(deftest symbiosis-lifts-mood-diversity-no-monoculture
  (let [txs (run-life (tmpdir) 30)
        rep (health/audit txs)]
    (is (>= (count (get-in rep [:colony :mood-diversity])) 2))
    (is (not (contains? (set (map :rule (:findings rep))) "mood-monoculture")))))

(deftest ecosystem-chain-verifies-and-stays-healthy
  (let [dir (tmpdir)
        log (str dir "/log.edn")
        res (autorun/autorun 40 {:fresh true :log-path log :queue-path (str dir "/q.ndjson")})]
    (is (true? (get-in res [:chain :ok])))
    (is (true? (:healthy (health/audit (datoms/read-log log)))))))

(deftest satiation-keeps-a-fed-producer-unsaturated-long-run
  (let [txs (run-life (tmpdir) 100)]
    (doseq [code ["10101500" "14111500" "50221000"]]
      (let [base (joucho/personality-baseline code)
            s (joucho/replay-events base (datoms/events-for txs code))]
        (is (not (some #(#{0 100} %) (vals s))) [code s])))))

(deftest humanity-is-fed-continuously-via-detritus-recycling
  (let [txs (run-life (tmpdir) 60)
        rep (eco/web-report txs)]
    (is (>= (:commons-metabolites rep) 60))                 ;; continuous: ≥1 per beat
    (is (> (:commons-nutrient-to-humanity rep) 0))))
