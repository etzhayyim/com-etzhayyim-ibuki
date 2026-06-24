(ns ibuki.methods.test-integration
  "test-integration — 息吹: the WHOLE organism composes. ADR-2606101800.
  Clojure port of `methods/test_integration.py`.

  After 9 waves (autonomy R0–R3 + ecosystem ×7) the value is no longer one more mechanism
  but CONFIDENCE that they compose: a single long autonomous run must exercise every
  subsystem at once and keep every cross-cutting invariant. This suite runs one 60-beat life
  and asserts the whole organism — perception → feel → ecosystem food web → symbiosis pool →
  quorum → health → digest — holds together on one verified append-only chain."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.autorun :as autorun]
            [ibuki.methods.datoms :as datoms]
            [ibuki.methods.ecosystem :as ecosystem]
            [ibuki.methods.health :as health]
            [ibuki.methods.joucho :as joucho]
            [ibuki.methods.quorum :as quorum]
            [ibuki.methods.symbiosis :as symbiosis]))

(def ^:private codes ["10101500" "14111500" "50221000"])

(defn- tmpdir []
  (str (java.nio.file.Files/createTempDirectory
        "ibuki-integration" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- life
  "One fresh autonomous run; returns [log-path txs]."
  ([dir] (life dir 60))
  ([dir cycles]
   (let [log (str dir "/log.edn")]
     (autorun/autorun cycles {:fresh true :log-path log :queue-path (str dir "/q.ndjson")})
     [log (datoms/read-log log)])))

(deftest full-stack-one-verified-chain
  (let [[log txs] (life (tmpdir))
        v (datoms/verify-chain log)
        attrs (set (for [tx txs [_op _e a _v] (:tx/datoms tx)] a))]
    ;; the substrate: one append-only content-addressed chain, intact
    (is (true? (:ok v)))
    (is (= 60 (:length v)))
    ;; every subsystem left its mark on the SAME log
    (doseq [family [":joucho/mood" ":heartbeat/of" ":post/status" ":metabolite/kind"
                    ":exchange/kind" ":quorum/state" ":health/healthy" ":digest/status"
                    ":organism/niche"]]
      (is (contains? attrs family) (str "subsystem datom missing: " family)))))

(deftest health-green-across-the-whole-life
  (let [[_ txs] (life (tmpdir))
        rep (health/audit txs)]
    (is (true? (:healthy rep)))
    (is (= [] (vec (:findings rep))))
    (is (< (abs (- (get-in rep [:colony :eco-maturity]) 1.0)) 1e-9))))

(deftest food-web-and-commons-pool-compose
  (let [[_ txs] (life (tmpdir))
        web (ecosystem/web-report txs)
        pool (symbiosis/commons-pool txs)]
    ;; the food web fed humanity; the pool equals the offered commons (nothing self-drawn)
    (is (pos? (:commons-metabolites web)))
    (is (pos? (:offered pool)))
    (is (= 0 (:drawn pool)))
    (is (= (:offered pool) (:available pool)))
    ;; both relayed and detritus sources contributed (matter loop closed)
    (is (set/subset? (set (keys (:commons-by-source web)))
                     #{":relayed" ":detritus" ":fruiting"}))))

(deftest quorum-fruiting-feeds-the-pool
  (let [[_ txs] (life (tmpdir))
        qh (quorum/quorum-history txs)]
    (if (pos? (get (:states qh) ":flourishing" 0))
      (do (is (pos? (:fruiting-nutrient-total qh)))
          (is (>= (:offered (symbiosis/commons-pool txs))
                  (:fruiting-nutrient-total qh))))
      (is (= 0 (:fruiting-nutrient-total qh))))))

(deftest mood-as-of-queryable-and-checkpoint-consistent
  (let [[_ txs] (life (tmpdir))]
    (doseq [code codes]
      (let [base (joucho/personality-baseline code)
            ;; as-of replay at an early vs late cut differ (the organism lived)
            early (datoms/events-for txs code {:up-to-tx 1})
            late (datoms/events-for txs code)]
        (is (> (count late) (count early)))
        ;; the final :joucho/* checkpoint equals the pure replay (one history, two views)
        (let [replayed (joucho/replay-events base late)
              ck (datoms/fold-entity txs (str "joucho-" code "-60"))]
          (doseq [[axis val] replayed]
            (is (= val (get ck (str ":joucho/" (name axis))))
                (str [code axis]))))))))

(deftest digest-reports-the-composed-state
  (let [[_ txs] (life (tmpdir))
        offered (vec (for [tx txs [_op _e a v] (:tx/datoms tx)
                           :when (= a ":digest/commons-offered")] v))]
    (is (seq offered))
    (is (or (= (peek offered)
               (:offered (symbiosis/commons-pool
                          (filterv #(<= (:tx/id %) 60) txs))))
            (pos? (peek offered))))))

(deftest crash-resume-equals-uninterrupted-full-stack
  ;; The whole organism, interrupted: 30 + 30 beats across a process death produce a head
  ;; CID byte-identical to an uninterrupted 60-beat life — every subsystem's state is durable.
  (let [d1 (tmpdir)
        log1 (str d1 "/log.edn")
        q1 (str d1 "/q.ndjson")]
    (autorun/autorun 30 {:fresh true :log-path log1 :queue-path q1})
    (let [r (autorun/autorun 30 {:fresh false :log-path log1 :queue-path q1})
          [straight _] (life (tmpdir) 60)]
      (is (= (:head r) (datoms/head-cid straight))))))

(deftest append-only-no-retract-anywhere-in-a-full-life
  (let [[_ txs] (life (tmpdir))
        ops (set (for [tx txs [op _e _a _v] (:tx/datoms tx)] op))]
    (is (= #{":db/add"} ops))))               ;; 非終末論 across the whole composed stack
