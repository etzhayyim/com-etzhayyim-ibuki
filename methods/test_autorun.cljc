(ns ibuki.methods.test-autorun
  "test-autorun — 息吹 (ibuki) autonomous heartbeat loop. ADR-2606101200.
  Clojure port of `methods/test_autorun.py`."
  (:require #?(:clj [cheshire.core :as json])
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.autorun :as autorun]
            [ibuki.methods.datoms :as datoms]
            [ibuki.methods.heartbeat :as heartbeat]
            [ibuki.methods.joucho :as joucho]))

(defn- tmpdir []
  (str (java.nio.file.Files/createTempDirectory
        "ibuki-autorun" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- run-life
  "Returns [result log-path queue-path]."
  [dir cycles fresh]
  (let [log (str dir "/log.edn")
        q (str dir "/queue.ndjson")]
    [(autorun/autorun cycles {:fresh fresh :log-path log :queue-path q}) log q]))

(deftest three-beats-chain-verifies
  (let [[res _log _q] (run-life (tmpdir) 3 true)]
    (is (= 3 (:beats res)))
    (is (true? (get-in res [:chain :ok])))
    (is (str/starts-with? (:head res) "b"))))

(deftest deterministic-same-head-cid
  (let [[r1 _ _] (run-life (tmpdir) 3 true)
        [r2 _ _] (run-life (tmpdir) 3 true)]
    (is (= (:head r1) (:head r2)))))          ;; same seed + cycles → same head

(deftest crash-resume-equals-uninterrupted-run
  ;; The Gap-1/2 closure, end-to-end: 2 beats, process 'dies', 1 more beat — the chain is
  ;; byte-identical to an uninterrupted 3-beat life (nothing lived only in RAM).
  (let [d1 (tmpdir)
        d2 (tmpdir)]
    (run-life d1 2 true)
    (let [[resumed _ _] (run-life d1 1 false)  ;; a brand-new process picks up the log
          [straight _ _] (run-life d2 3 true)]
      (is (= (:head resumed) (:head straight))))))

(deftest every-post-is-dry-run
  (let [[_ log _] (run-life (tmpdir) 4 true)
        txs (datoms/read-log log)
        statuses (for [tx txs
                       [_op _e a v] (:tx/datoms tx)
                       :when (= a ":post/status")]
                   v)]
    (is (seq statuses))
    (is (= #{":dry-run"} (set statuses)))))   ;; G8

(deftest queue-lines-match-adr-2605240100-schema
  (let [[_ _ q] (run-life (tmpdir) 2 true)
        parse json/parse-string
        lines (->> (str/split-lines (slurp q))
                   (remove str/blank?)
                   (mapv parse))]
    (is (seq lines))
    (doseq [ln lines]
      (is (= 1 (get ln "v")))
      (doseq [k ["ts" "actorDid" "code" "title" "mood" "contentSourceKind"
                 "text" "lexicon" "createdAt"]]
        (is (contains? ln k) (str "missing queue key " k))))))

(deftest drain-prepares-member-sign-envelopes
  (let [[_ log _] (run-life (tmpdir) 2 true)
        txs (datoms/read-log log)
        held (for [tx txs [_op _e a v] (:tx/datoms tx)
                   :when (= a ":drain/server-held-key")] v)
        sigs (for [tx txs [_op _e a v] (:tx/datoms tx)
                   :when (= a ":drain/requires-member-sig")] v)]
    (is (seq held))
    (is (= #{false} (set held)))
    (is (= #{true} (set sigs)))))             ;; G7

(deftest mood-is-as-of-queryable-from-log
  ;; The Gap-3/4 closure, end-to-end: 'what was the organism's mood at tx N' is answered
  ;; purely from the log — and the lived history actually moves it off the stub default.
  (let [[res log _] (run-life (tmpdir) 6 true)
        txs (datoms/read-log log)
        code "10101500"
        base (joucho/personality-baseline code)
        early (joucho/replay-events base (datoms/events-for txs code {:up-to-tx 1}))
        late (joucho/replay-events base (datoms/events-for txs code {:up-to-tx (:beats res)}))]
    (is (not= (datoms/events-for txs code {:up-to-tx 1}) (datoms/events-for txs code)))
    (is (some? [early (joucho/determine-mood early)]))
    (is (not= late base))))                   ;; the organism grew (縁起)

(deftest heartbeat-state-durable-across-beats
  (let [[_ log _] (run-life (tmpdir) 5 true)
        txs (datoms/read-log log)
        st (heartbeat/replay txs "10101500")]
    (is (= 5 (:beats st)))
    (is (>= (:posts st) 1))
    (is (>= (:last-post-at-ms st) 0))))

(deftest narration-offline-via-template
  (let [[_ log _] (run-life (tmpdir) 2 true)
        txs (datoms/read-log log)
        vias (for [tx txs [_op _e a v] (:tx/datoms tx)
                   :when (= a ":post/via")] v)]
    (is (seq vias))
    (is (set/subset? (set vias) #{":template" ":murakumo"}))))

(deftest event-entities-never-shadowed
  ;; Regression (2026-06-10 health audit): the post-emitted event used to reuse
  ;; jev-{code}-{beat}-0 and SHADOW that beat's idle event in the as-of fold — losing
  ;; homeostasis drift on every posting beat (stress crept up until an organism went
  ;; permanently mute). Every event entity must carry exactly ONE kind assertion.
  (let [[_ log _] (run-life (tmpdir) 10 true)
        kinds (reduce (fn [c tx]
                        (reduce (fn [c [_op e a _v]]
                                  (if (= a ":joucho.event/kind") (update c e (fnil inc 0)) c))
                                c (:tx/datoms tx)))
                      {} (datoms/read-log log))]
    (is (seq kinds))
    (is (= 1 (apply max (vals kinds))))))

(deftest checkpointed-joucho-equals-as-of-replay
  ;; The :joucho/* checkpoint written by the beat and the pure as-of replay of the event
  ;; log must agree — two views of one history, never two histories.
  (let [[res log _] (run-life (tmpdir) 7 true)
        txs (datoms/read-log log)]
    (doseq [code ["10101500" "14111500" "50221000"]]
      (let [base (joucho/personality-baseline code)
            replayed (joucho/replay-events base (datoms/events-for txs code))
            ck (datoms/fold-entity txs (str "joucho-" code "-" (:beats res)))]
        (doseq [[axis v] replayed]
          (is (= v (get ck (str ":joucho/" (name axis))))
              (str [code axis ck replayed])))))))

(deftest homeostasis-holds-over-a-long-life
  ;; 健全な成長: with drift intact, stress stays inside the designed equilibrium band
  ;; (baseline .. baseline+4 under the representative pattern) — no organism drifts into
  ;; permanent stressed muteness from ordinary life.
  (let [[_ log _] (run-life (tmpdir) 60 true)
        txs (datoms/read-log log)]
    (doseq [code ["10101500" "14111500" "50221000"]]
      (let [base (joucho/personality-baseline code)
            head (joucho/replay-events base (datoms/events-for txs code))]
        (is (<= (:stress head) (+ (:stress base) 4))
            (str [code (:stress base) (:stress head)]))
        (is (not= "stressed" (joucho/determine-mood head)))))))
