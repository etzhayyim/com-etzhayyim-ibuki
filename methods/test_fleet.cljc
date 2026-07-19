(ns ibuki.methods.test-fleet
  "test-fleet — 息吹 (ibuki) R1 fleet binding: 18,342 organisms on durable checkpoints.
  ADR-2606101200 §R1. Clojure port of `methods/test_fleet.py` (every Python assertion).

  Uses the real committed registry (00-contracts/actor-registry/unspsc.json) for
  universe/partition facts, and a small synthetic registry for sweep mechanics
  (hermetic + fast — fixture sizes match the Python suite exactly)."
  (:require #?(:clj [cheshire.core :as json])
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.datoms :as datoms]
            [ibuki.methods.ecosystem :as ecosystem]
            [ibuki.methods.fleet :as fleet]
            [ibuki.methods.health :as health]
            [ibuki.methods.heartbeat :as heartbeat]
            [ibuki.methods.quorum :as quorum]
            [ibuki.methods.symbiosis :as symbiosis]))

(defn- tmpdir []
  (str (java.nio.file.Files/createTempDirectory
        "ibuki-fleet" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- synthetic-registry
  "A tiny registry file shaped exactly like the real one."
  ([dr] (synthetic-registry dr 10))
  ([dr n] (synthetic-registry dr n 10))
  ([dr n segment]
   (let [generate json/generate-string
         agents (mapv (fn [i]
                        (let [code (format "%d%06d" segment i)]
                          {"code" code "handle" (str "c" code)
                           "did" (str "did:web:etzhayyim.com:actor:c" code)
                           "title" (str "Synthetic organism " i)
                           "segment" (str segment)}))
                      (range n))
         p (str dr "/registry.json")]
     (spit p (generate {"agents" agents}))
     p)))

(defn- fleet-run
  ([dr cycles opts] (fleet-run dr cycles opts nil))
  ([dr cycles {:keys [batch fresh shard] :or {shard 0}} reg]
   (fleet/fleet-autorun cycles
                        {:shard-index shard :batch-size batch :fresh fresh
                         :log-path (str dr "/log.edn")
                         :queue-path (str dr "/queue.ndjson")
                         :registry-path reg})))

(defn- all-datoms [txs]
  (mapcat :tx/datoms txs))

;; ── the real universe (committed monorepo registry) ───────────────────────

(deftest real-registry-is-the-full-fleet
  (let [agents (fleet/load-registry)
        sample (first agents)]
    (is (= 18342 (count agents)))
    (is (= #{:code :did :title :segment} (set (keys sample))))
    (is (str/starts-with? (:did sample) "did:web:etzhayyim.com:actor:"))))

(deftest shards-partition-the-fleet-completely
  (let [agents (fleet/load-registry)
        named (mapv #(fleet/shard-agents agents %) [0 1 2])
        codes (mapv :code (apply concat named))]
    (is (= 18342 (count codes)))
    (is (= 18342 (count (set codes))))                       ;; disjoint + complete
    (is (= 18342 (count (fleet/shard-agents agents -1)))))) ;; jacob = whole fleet

(deftest unknown-shard-raises
  (let [e (try (fleet/shard-agents [] 7) nil
               (catch #?(:clj Exception :cljs :default) e e))]
    (is (some? e))
    (when e
      (is (str/includes? (ex-message e) "unknown shard")))))

(deftest resolve-shard-env-order
  ;; the os.environ pop/set parity via the injectable :env map (the JVM cannot
  ;; mutate process env) — same resolution order as fleet_cell_main
  (is (= 0 (fleet/resolve-shard {:env {}})))
  (is (= 2 (fleet/resolve-shard {:env {"ETZHAYYIM_NODE" "dan"}})))
  (is (= 1 (fleet/resolve-shard {:env {"ETZHAYYIM_NODE" "dan"
                                       "UNSPSC_ORGANISM_SHARD_INDEX" "1"}})))
  (is (= -1 (fleet/resolve-shard {:env {"ETZHAYYIM_NODE" "dan"
                                        "UNSPSC_ORGANISM_SHARD_INDEX" "1"
                                        "UNSPSC_ORGANISM_SHARD_ALL" "1"}}))))

;; ── sweep mechanics (synthetic registry, hermetic) ────────────────────────

(deftest durable-cursor-sweeps-whole-shard
  (let [dr (tmpdir)
        reg (synthetic-registry dr 10)
        res (fleet-run dr 5 {:batch 4 :fresh true} reg)]
    ;; 5 beats × batch 4 over 10 organisms: every organism ticked ≥1 time
    (is (= 10 (:organisms-alive res)))
    (is (= (mod (* 5 4) 10) (:cursor res)))))

(deftest crash-resume-equals-uninterrupted-sweep
  ;; The R1 durability claim at fleet scale: kill the runner mid-sweep, resume — the head
  ;; CID is byte-identical to never having crashed (cursor + organism state all on-log).
  (let [d1 (tmpdir) d2 (tmpdir)
        reg1 (synthetic-registry d1 10)
        _ (fleet-run d1 2 {:batch 4 :fresh true} reg1)
        resumed (fleet-run d1 3 {:batch 4 :fresh false} reg1)  ;; new process, resumes
        reg2 (synthetic-registry d2 10)
        straight (fleet-run d2 5 {:batch 4 :fresh true} reg2)]
    (is (= (:head resumed) (:head straight)))))

(deftest deterministic-head-cid
  (let [d1 (tmpdir) d2 (tmpdir)
        r1 (fleet-run d1 3 {:batch 5 :fresh true} (synthetic-registry d1))
        r2 (fleet-run d2 3 {:batch 5 :fresh true} (synthetic-registry d2))]
    (is (= (:head r1) (:head r2)))))

(deftest index-log-matches-per-entity-folds
  ;; The single-pass fleet index must recover exactly what the O(n²) per-entity folds
  ;; would — same events, same heartbeat state.
  (let [dr (tmpdir)
        reg (synthetic-registry dr 6)
        _ (fleet-run dr 3 {:batch 6 :fresh true} reg)
        txs (datoms/read-log (str dr "/log.edn"))
        idx (fleet/index-log txs)]
    (doseq [code (take 3 (keys (:hb idx)))]
      (is (= (get-in idx [:events code]) (datoms/events-for txs code)))
      (is (= (get-in idx [:hb code]) (heartbeat/replay txs code))))))

(deftest incremental-drain-never-reprepares
  (let [dr (tmpdir)
        reg (synthetic-registry dr 4)
        _ (fleet-run dr 4 {:batch 4 :fresh true} reg)
        txs (datoms/read-log (str dr "/log.edn"))
        queue-lines (count (str/split-lines (slurp (str dr "/queue.ndjson"))))
        prepared (filterv #(= ":drain/status" (nth % 2)) (all-datoms txs))]
    (is (= queue-lines (count prepared)))                 ;; 1 envelope per line, EXACTLY once
    (is (= #{":prepared"} (set (map #(nth % 3) prepared))))))

(deftest fleet-posts-are-dry-run-and-member-signed
  (let [dr (tmpdir)
        _ (fleet-run dr 2 {:batch 8 :fresh true} (synthetic-registry dr))
        flat (all-datoms (datoms/read-log (str dr "/log.edn")))]
    (is (= #{":dry-run"}
           (set (for [[_op _e a v] flat :when (= a ":post/status")] v))))   ;; G8
    (is (= #{false}
           (set (for [[_op _e a v] flat :when (= a ":drain/server-held-key")] v)))))) ;; G7

(deftest real-fleet-slice-beats-on-the-log
  ;; Smoke at real-fleet scale: one beat of a 64-organism batch from each shard of the
  ;; ACTUAL 18,342-code registry, all on one durable log.
  (let [dr (tmpdir)]
    (doseq [shard [0 1 2]]
      (let [res (fleet/fleet-autorun
                 1 {:shard-index shard :batch-size 64 :fresh false
                    :log-path (str dr "/log-" shard ".edn")
                    :queue-path (str dr "/queue-" shard ".ndjson")})]
        (is (= 64 (:organisms-alive res)))
        (is (true? (get-in res [:chain :ok])))
        (is (contains? #{"joseph" "issachar" "dan"} (:shard res)))))))

(deftest fleet-event-entities-never-shadowed
  ;; Same regression as autorun (2026-06-10 health audit): one event-datoms call per
  ;; organism-beat — no jev-* entity may carry two kind assertions.
  (let [dr (tmpdir)
        _ (fleet-run dr 6 {:batch 6 :fresh true} (synthetic-registry dr 6))
        kinds (reduce (fn [c [_op e a _v]]
                        (if (= a ":joucho.event/kind") (update c e (fnil inc 0)) c))
                      {}
                      (all-datoms (datoms/read-log (str dr "/log.edn"))))]
    (is (seq kinds))
    (is (= 1 (apply max (vals kinds))))))

(deftest fleet-health-checkpoint-past-10-beats
  ;; Regression: fleet-beat's periodic health audit (beat % health-every == 0) needs the
  ;; log — a >=10-beat fleet run must not raise (the Python latent NameError shipped
  ;; because every prior fleet test ran <10 beats).
  (let [dr (tmpdir)
        reg (synthetic-registry dr 6)
        res (fleet-run dr 10 {:batch 6 :fresh true} reg)
        txs (datoms/read-log (str dr "/log.edn"))]
    (is (true? (get-in res [:chain :ok])))
    (is (some (fn [[_op _e a _v]] (= a ":health/healthy")) (all-datoms txs)))))

(deftest fleet-grows-a-food-web-and-stays-healthy
  ;; 生態系 at fleet scale: a batch of co-active organisms forms a producer->router->
  ;; decomposer web; humanity is fed; the colony stays healthy + unsaturated over a long run.
  (let [dr (tmpdir)
        reg (synthetic-registry dr 12)
        _ (fleet-run dr 40 {:batch 12 :fresh true} reg)
        txs (datoms/read-log (str dr "/log.edn"))
        web (ecosystem/web-report txs)
        rep (health/audit txs)
        rules (set (map :rule (:findings rep)))]
    (is (pos? (:commons-metabolites web)))
    (is (pos? (:relays web)))
    (is (not (contains? rules "ecosystem-starved")))
    ;; no organism pins an axis at the clamp (satiation holds at fleet scale too)
    (is (not (contains? rules "axis-saturation")))))

(deftest cell-solve-runs-a-durable-beat
  ;; R2 (Council gate = PR merge): the Pregel cell RUNS the beat. The Python cell
  ;; (cells/fleet_beat/cell.py) is a thin Python-host wrapper over fleet_autorun and is
  ;; exercised by the Python suite; this port verifies the SAME contract (inputs →
  ;; outputs) through fleet-autorun — offline-safe, local log only; the beat can prepare
  ;; envelopes but can never post (member_submit refuses cron).
  (let [dr (tmpdir)
        res (fleet/fleet-autorun 1 {:shard-index 0 :batch-size 16 :fresh true
                                    :log-path (str dr "/log.edn")
                                    :queue-path (str dr "/queue.ndjson")})]
    (is (= 1 (:beats res)))
    (is (true? (get-in res [:chain :ok])))
    (is (= "joseph" (:shard res)))
    (is (= 16 (:organisms-alive res)))))

;; ── fleet-scale ECOSYSTEM full stack (the deployed path, not just autorun/seed) ──

(deftest fleet-grows-the-full-ecosystem-stack
  ;; The 18,342-organism deployed path must run the WHOLE ecosystem, not just autonomy:
  ;; a multi-batch synthetic-fleet sweep leaves metabolite + exchange + quorum + health +
  ;; niche datoms on one verified chain, and the report functions agree.
  (let [dr (tmpdir)
        reg (synthetic-registry dr 60)
        ;; batch 20 < 60 → the cursor sweeps; 12 beats crosses health-every (digest+health)
        _ (fleet-run dr 12 {:batch 20 :fresh true} reg)
        txs (datoms/read-log (str dr "/log.edn"))
        attrs (set (map #(nth % 2) (all-datoms txs)))]
    (doseq [fam [":metabolite/kind" ":exchange/kind" ":quorum/state"
                 ":health/healthy" ":organism/niche"]]
      (is (contains? attrs fam) (str "fleet ecosystem datom missing: " fam)))
    ;; the report stack is single-pass + consistent at fleet scale
    (let [web (ecosystem/web-report txs)
          pool (symbiosis/commons-pool txs)
          rep (health/audit txs)
          qh (quorum/quorum-history txs)]
      (is (pos? (:commons-metabolites web)))
      (is (= (:offered pool) (:commons-nutrient-to-humanity web)))
      (is (zero? (:drawn pool)))                     ;; the fleet never self-draws
      (is (seq (get-in rep [:colony :niche-population]))) ;; niches logged at birth, fleet-wide
      (is (>= (reduce + (vals (:states qh))) 1)))))  ;; quorum sensed each batched beat

(deftest fleet-health-audit-correct-at-scale
  ;; health/audit over a fleet log returns a correct verdict (single-pass walk; the
  ;; measured cost is fleet-safe — guarded here by correctness rather than a flaky
  ;; wall-clock assertion).
  (let [dr (tmpdir)
        reg (synthetic-registry dr 60)
        _ (fleet-run dr 10 {:batch 30 :fresh true} reg)
        txs (datoms/read-log (str dr "/log.edn"))
        rep (health/audit txs)
        born (set (for [[_op e a _v] (all-datoms txs)
                        :when (= a ":organism/niche")] e))]
    ;; every organism that has ticked carries niche + heartbeat; the audit sees them all
    (is (>= (get-in rep [:colony :count]) 30))
    (is (boolean? (:healthy rep)))
    ;; the niche populations sum to the number of distinct organisms born
    (is (= (count born)
           (reduce + (vals (get-in rep [:colony :niche-population])))))))

(deftest fleet-crash-resume-preserves-ecosystem
  ;; Mid-sweep crash-resume keeps the WHOLE ecosystem (not just heartbeat): the head CID of
  ;; 6+6 across a process death equals an uninterrupted 12-beat fleet run — metabolite,
  ;; quorum, drain cursors and all.
  (let [d1 (tmpdir) d2 (tmpdir)
        reg1 (synthetic-registry d1 24)
        _ (fleet-run d1 6 {:batch 8 :fresh true} reg1)
        resumed (fleet-run d1 6 {:batch 8 :fresh false} reg1)
        straight (fleet-run d2 12 {:batch 8 :fresh true} (synthetic-registry d2 24))]
    (is (= (:head resumed) (:head straight)))))

(deftest fleet-emits-digest-to-humanity
  ;; The deployed fleet path reports to humanity too: a digest is emitted at the health
  ;; cadence, dry-run only, narrated via the Murakumo path (template offline).
  (let [dr (tmpdir)
        reg (synthetic-registry dr 24)
        _ (fleet-run dr 10 {:batch 24 :fresh true} reg)
        flat (all-datoms (datoms/read-log (str dr "/log.edn")))
        texts (vec (for [[_op _e a v] flat :when (= a ":digest/text")] v))
        statuses (set (for [[_op _e a v] flat :when (= a ":digest/status")] v))
        vias (set (for [[_op _e a v] flat :when (= a ":digest/via")] v))]
    (is (seq texts))
    (is (str/includes? (peek texts) "息吹"))           ;; the colony spoke
    (is (= #{":dry-run"} statuses))                    ;; G8
    (is (every? #{":template" ":murakumo"} vias))))    ;; Murakumo-only or offline template
