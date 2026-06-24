(ns ibuki.methods.fleet
  "fleet — R1: bind the 18,342-organism UNSPSC fleet to ibuki's durable checkpoints.
  ADR-2606101200 §R1. Clojure port of `methods/fleet.py`.

  The kotodama fleet cell (ADR-2605240000) ticks organisms from process RAM — an LRU cache
  of 4,096 live organisms whose cooldowns/state die with the pod. ibuki R1 replaces that
  fragility with the Datom log: the WHOLE fleet's state (joucho events, heartbeat
  checkpoints, sweep cursor) lives as-of on the append-only log, so

    - no LRU is needed for *correctness* (an organism's state is never \"evicted\" — it is
      replayed from the log when its slice comes around);
    - a beat covers a bounded BATCH of organisms (round-robin over the shard via a DURABLE
      `:fleet.shard/*` cursor) — tx sizes stay bounded at any fleet size;
    - killing the process mid-sweep loses nothing: the next beat resumes from the cursor the
      log remembers, and the resulting chain is byte-identical to an uninterrupted run.

  Sharding mirrors the kotodama fleet cell's segment ranges (the registry partition is
  asserted complete + disjoint in tests; per-shard counts live in the registry, not
  hard-coded here):

    shard -1  jacob     segments  0-99   (whole fleet)
    shard  0  joseph    segments 10-29
    shard  1  issachar  segments 30-44
    shard  2  dan       segments 45-60

  The code universe is the committed monorepo registry `00-contracts/actor-registry/
  unispsc.json` (18,342 agents: code / did / title / segment). Resolution order for the
  path: `IBUKI_UNSPSC_REGISTRY_PATH` env → walk up from the working directory to the
  monorepo root. Shard index resolution mirrors fleet_cell_main:
  `UNISPSC_ORGANISM_SHARD_ALL=1` → -1, else `UNISPSC_ORGANISM_SHARD_INDEX`, else
  `ETZHAYYIM_NODE` (joseph/issachar/dan), else 0.

  Deterministic (logical beat time; no wall clock). Live deploy of the cron cell stays
  G8-gated."
  (:require [clojure.string :as str]
            [ibuki.methods.datoms :as datoms]
            [ibuki.methods.digest :as digest]
            [ibuki.methods.drainer :as drainer]
            [ibuki.methods.ecosystem :as ecosystem]
            [ibuki.methods.health :as health]
            [ibuki.methods.heartbeat :as heartbeat]
            [ibuki.methods.infer :refer [narrate]]
            [ibuki.methods.joucho :as joucho]
            [ibuki.methods.perception :as perception]
            [ibuki.methods.quorum :as quorum]
            #?(:clj [clojure.java.io :as io])))

(def registry-rel "00-contracts/actor-registry/unispsc.json")

;; shard index → [name segment-lo segment-hi]; ranges mirror kotodama fleet_cell_main
(def shards
  {-1 ["jacob" 0 99]
   0 ["joseph" 10 29]
   1 ["issachar" 30 44]
   2 ["dan" 45 60]})

(def ^:private node->shard {"joseph" 0 "issachar" 1 "dan" 2})

(def beat-ms (* 45 60000))
(def as-of-base 2606100000)
(def default-batch 256)
(def health-every
  "Colony self-audit cadence (ibuki.methods.health → :health/* checkpoints)."
  10)

#?(:clj
   (defn resolve-registry-path
     "Locate the monorepo UNSPSC actor registry (env override → walk up from the working
     directory to the repo root)."
     []
     (or (System/getenv "IBUKI_UNSPSC_REGISTRY_PATH")
         (loop [dir (.getCanonicalFile (io/file (System/getProperty "user.dir")))]
           (if (nil? dir)
             (throw (ex-info (str "UNSPSC registry not found above "
                                  (System/getProperty "user.dir")
                                  " (set IBUKI_UNSPSC_REGISTRY_PATH)")
                             {:registry-rel registry-rel}))
             (let [cand (io/file dir registry-rel)]
               (if (.exists cand)
                 (.getPath cand)
                 (recur (.getParentFile dir)))))))))

#?(:clj
   (defn load-registry
     "The fleet's code universe: [{:code :did :title :segment} …] in registry order."
     ([] (load-registry nil))
     ([path]
      (let [parse (requiring-resolve 'cheshire.core/parse-string)
            p (or path (resolve-registry-path))
            doc (parse (slurp (io/file (str p))))]
        (mapv (fn [a]
                {:code (get a "code") :did (get a "did") :title (get a "title")
                 :segment (Long/parseLong (str (get a "segment")))})
              (get doc "agents"))))))

(defn resolve-shard
  "Shard index from the environment — same resolution order as fleet_cell_main.
  `:env` (a string→string map) overrides the real process env for hermetic tests."
  ([] (resolve-shard {}))
  ([{:keys [env]}]
   (let [getenv (if (some? env)
                  #(get env %)
                  #?(:clj #(System/getenv %) :default (constantly nil)))]
     (cond
       (= "1" (getenv "UNISPSC_ORGANISM_SHARD_ALL")) -1
       (some? (getenv "UNISPSC_ORGANISM_SHARD_INDEX"))
       #?(:clj (Long/parseLong (getenv "UNISPSC_ORGANISM_SHARD_INDEX"))
          :default (js/parseInt (getenv "UNISPSC_ORGANISM_SHARD_INDEX")))
       :else (get node->shard (or (getenv "ETZHAYYIM_NODE") "") 0)))))

(defn shard-agents
  "The shard's slice of the fleet (registry order preserved — the sweep order)."
  [agents shard-index]
  (when-not (contains? shards shard-index)
    (throw (ex-info (str "unknown shard index " shard-index
                         " (known: " (vec (sort (keys shards))) ")")
                    {:shard-index shard-index})))
  (let [[_ lo hi] (get shards shard-index)]
    (filterv (fn [a] (<= lo (:segment a) hi)) agents)))

;; ── single-pass log index (fleet-scale replay) ────────────────────────────
;;
;; fold-entity/events-for are O(log) per entity — fine for 3 organisms, quadratic poison
;; for 18,342. This index recovers the same facts in a single scan. (The Python LogIndex
;; class → a plain map: :events code→[kinds], :hb code→heartbeat state, :cursor
;; shard-name→next sweep offset, :drain-line shard-name→queue lines already drained,
;; :followers code→last live :perception/followers, :last-fed code→last
;; :event/symbiosis-fed beat.)

(defn index-log
  "Build the fleet index in one scan (datom order = tx order, so 'latest wins' and event
  streams come out in lived order, exactly like the per-entity folds)."
  [txs]
  (let [{:keys [ev-entity ev-order hb-entity hb-order perc-entity perc-order cursor drain-line]}
        (reduce
         (fn [acc [_op e a v]]
           (cond
             (str/starts-with? a ":joucho.event/")
             (-> acc
                 (update :ev-order #(if (contains? (:ev-entity acc) e) % (conj % e)))
                 (assoc-in [:ev-entity e a] v))
             (str/starts-with? a ":heartbeat/")
             (-> acc
                 (update :hb-order #(if (contains? (:hb-entity acc) e) % (conj % e)))
                 (assoc-in [:hb-entity e a] v))
             (str/starts-with? a ":perception/")
             (-> acc
                 (update :perc-order #(if (contains? (:perc-entity acc) e) % (conj % e)))
                 (assoc-in [:perc-entity e a] v))
             (= a ":fleet.shard/cursor")
             (assoc-in acc [:cursor (str/replace-first e "fleet-" "")] v)
             (= a ":fleet.shard/drain-line")
             (assoc-in acc [:drain-line (str/replace-first e "fleet-" "")] v)
             :else acc))
         {:ev-entity {} :ev-order [] :hb-entity {} :hb-order []
          :perc-entity {} :perc-order [] :cursor {} :drain-line {}}
         (mapcat :tx/datoms txs))

        [events last-fed]
        (reduce (fn [[events last-fed :as acc] e]
                  (let [ent (get ev-entity e)
                        of (get ent ":joucho.event/of")
                        kind (get ent ":joucho.event/kind")]
                    (if (and of kind)
                      [(update events of (fnil conj []) kind)
                       (if (= kind ecosystem/symbiosis-event)
                         (let [b (get ent ":joucho.event/beat" -1)]
                           (update last-fed of (fnil max -1) b))
                         last-fed)]
                      acc)))
                [{} {}]
                ev-order)

        [hb _hb-beat]
        (reduce (fn [[hb hb-beat :as acc] e]
                  (let [ent (get hb-entity e)
                        of (get ent ":heartbeat/of")
                        beat (get ent ":heartbeat/beat" -1)]
                    (if (or (nil? of)
                            (and (contains? hb of) (<= beat (get hb-beat of))))
                      acc
                      [(assoc hb of
                              {:last-post-at-ms (get ent ":heartbeat/last-post-at-ms" -1)
                               :beats (get ent ":heartbeat/beats" 0)
                               :posts (get ent ":heartbeat/posts" 0)})
                       (assoc hb-beat of beat)])))
                [{} {}]
                hb-order)

        [followers _perc-beat]
        (reduce (fn [[followers perc-beat :as acc] e]
                  (let [ent (get perc-entity e)
                        of (get ent ":perception/of")
                        beat (get ent ":perception/beat" -1)]
                    (if (or (nil? of)
                            (not (contains? ent ":perception/followers"))
                            (and (contains? followers of) (<= beat (get perc-beat of -1))))
                      acc
                      [(assoc followers of (get ent ":perception/followers"))
                       (assoc perc-beat of beat)])))
                [{} {}]
                perc-order)]
    {:events events :hb hb :cursor cursor :drain-line drain-line
     :followers followers :last-fed last-fed}))

;; ── queue + incremental drain ─────────────────────────────────────────────

#?(:clj
   (defn queue-line!
     "One ADR-2605240100 v=1 queue line (deterministic logical ts)."
     [queue-path did code title mood text ts-ms]
     (let [generate (requiring-resolve 'cheshire.core/generate-string)
           line (into (sorted-map)
                      {"v" drainer/schema-version "ts" ts-ms "actorDid" did "code" code
                       "title" title "mood" mood "contentSourceKind" "recordAnalysis"
                       "text" text "lexicon" "app.bsky.feed.post"
                       "createdAt" (format "2026-06-10T00:00:00.%03dZ" (mod ts-ms 1000))})
           f (io/file (str queue-path))]
       (when-let [p (.getParentFile f)] (.mkdirs p))
       (spit f (str (generate line) "\n") :append true))))

#?(:clj
   (defn drain-incremental
     "Drain only queue lines ≥ from-line into `:drain/*` `:prepared` datoms. Returns
     [datoms next-line]. The line cursor is durable (`:fleet.shard/drain-line`) so a
     restart never re-prepares what an earlier beat already prepared."
     [queue-path from-line {:keys [as-of beat]}]
     (let [f (io/file (str queue-path))]
       (if-not (.exists f)
         [[] from-line]
         (let [parse (requiring-resolve 'cheshire.core/parse-string)
               lines (str/split-lines (slurp f))
               [out _prepared]
               (reduce
                (fn [[out prepared :as acc] i]
                  (let [raw (str/trim (nth lines i))]
                    (if (str/blank? raw)
                      acc
                      (let [obj (try (parse raw)
                                     (catch #?(:clj Exception :default :default) _ ::bad))]
                        (if (or (= obj ::bad)
                                (not= (get obj "v") drainer/schema-version)
                                (some #(not (contains? obj %)) drainer/required-keys))
                          acc
                          (let [env (drainer/envelope obj)
                                e (str "drain-" beat "-" prepared)]
                            [(into out
                                   [(datoms/add e ":drain/of" (get obj "actorDid"))
                                    (datoms/add e ":drain/lexicon" (get env "collection"))
                                    (datoms/add e ":drain/queue-ts" (get obj "ts"))
                                    (datoms/add e ":drain/status" ":prepared")
                                    (datoms/add e ":drain/requires-member-sig" true)
                                    (datoms/add e ":drain/server-held-key" false)
                                    (datoms/add e ":drain/beat" beat)
                                    (datoms/add e ":drain/as-of" as-of)])
                             (inc prepared)]))))))
                [[] 0]
                (range from-line (count lines)))]
           [out (count lines)])))))

;; ── the fleet beat ────────────────────────────────────────────────────────

#?(:clj
   (defn fleet-beat
     "One beat = the next `batch-size` organisms of the shard (durable round-robin cursor)
     + an incremental drain of queue lines this shard has not yet prepared. `:txs` is the
     log read this beat (for the periodic health audit). Returns the beat's datoms.
     Opts: :shard-name :beat :batch-size :queue-path :txs."
     [shard-slice idx {:keys [shard-name beat batch-size queue-path txs]}]
     (let [now-ms (* beat beat-ms)
           as-of (+ as-of-base beat)
           n (count shard-slice)
           start (if (pos? n) (mod (get-in idx [:cursor shard-name] 0) n) 0)

           ;; ── Phase 1: feel + decide + act for the batch; defer checkpoints for the
           ;; eco cascade ──
           batch (mapv (fn [k] (nth shard-slice (mod (+ start k) n)))
                       (range (min batch-size n)))
           [out pending beat-moods followers]
           (reduce
            (fn [[out pending beat-moods followers] org]
              (let [{:keys [code did title]} org
                    state (get-in idx [:hb code] heartbeat/default-state)
                    out (if (zero? (:beats state))   ;; first tick: birth assertion
                          (let [e (str "org-" code)]
                            (into out
                                  [(datoms/add e ":organism/code" code)
                                   (datoms/add e ":organism/title" title)
                                   (datoms/add e ":organism/did" did)
                                   (datoms/add e ":organism/niche" (ecosystem/niche-of code))
                                   (datoms/add e ":organism/born-beat" beat)]))
                          out)
                    baseline (joucho/personality-baseline code)
                    [events snap] (perception/events-for-beat
                                   beat {:actor did
                                         :prev-followers (get followers code)})
                    ;; live observation: persist the durable follower snapshot
                    [out followers]
                    (if (some? snap)
                      [(into out (perception/perception-datoms
                                  code (:followers snap) {:beat beat :as-of as-of}))
                       (assoc followers code (:followers snap))]
                      [out followers])
                    scores (joucho/replay-events
                            baseline (into (get-in idx [:events code] []) events))
                    mood (joucho/determine-mood scores)
                    [due _reason] (heartbeat/due-to-post state mood now-ms)
                    [out events scores state]
                    (if due
                      (let [nar (narrate title code mood "recordAnalysis")
                            pid (str "post-" code "-" beat)
                            out (into out
                                      [(datoms/add pid ":post/of" code)
                                       (datoms/add pid ":post/text" (:text nar))
                                       (datoms/add pid ":post/via" (str ":" (:via nar)))
                                       (datoms/add pid ":post/mood" (str ":" mood))
                                       (datoms/add pid ":post/beat" beat)
                                       (datoms/add pid ":post/as-of" as-of)
                                       (datoms/add pid ":post/status" ":dry-run")])]
                        (queue-line! queue-path did code title mood (:text nar) now-ms)
                        [out
                         (conj events ":event/post-emitted")
                         (joucho/fold-event scores ":event/post-emitted" baseline)
                         (-> state (assoc :last-post-at-ms now-ms) (update :posts inc))])
                      [out events scores state])
                    state (update state :beats inc)]
                [out
                 (conj pending {:code code :baseline baseline :events events
                                :scores scores :state state})
                 (assoc beat-moods code scores)
                 followers]))
            [[] [] {} (:followers idx)]
            batch)

           ;; ── Phase 2: 生態系 cascade over the co-active batch (niches hash-derived at
           ;; fleet scale; satiation from the durable last-fed index) ──
           sated (into #{}
                       (filter (fn [c] (<= (- beat (get-in idx [:last-fed c]
                                                            (- (- ecosystem/satiation) 1)))
                                           ecosystem/satiation)))
                       (keys beat-moods))
           eco (ecosystem/cycle (mapv (fn [o] {:code (:code o)}) batch)
                                beat-moods
                                {:beat beat :as-of as-of :satiated sated
                                 :trails (ecosystem/trail-strengths txs beat)})
           out (into out (:datoms eco))
           fed-count (frequencies (:fed eco))

           ;; ── Phase 3: fold symbiosis into the SAME-beat checkpoint; ONE event-datoms
           ;; per organism ──
           [out final-moods]
           (reduce
            (fn [[out final-moods] p]
              (let [{:keys [code baseline state]} p
                    n-fed (get fed-count code 0)
                    [events scores]
                    (reduce (fn [[events scores] _]
                              [(conj events ecosystem/symbiosis-event)
                               (joucho/fold-event scores ecosystem/symbiosis-event baseline)])
                            [(:events p) (:scores p)]
                            (range n-fed))
                    mood (joucho/determine-mood scores)]
                [(-> out
                     (into (joucho/event-datoms code events {:beat beat :as-of as-of}))
                     (into (joucho/joucho-datoms code scores mood {:beat beat :as-of as-of}))
                     (into (heartbeat/checkpoint-datoms code state mood
                                                        {:beat beat :as-of as-of})))
                 (assoc final-moods code mood)]))
            [out {}]
            pending)

           ;; 定足数 quorum sensing over the co-active batch — collective fruiting / dormancy
           beat-commons (reduce (fn [acc [_op e a v]]
                                  (if (and (= a ":metabolite/nutrient")
                                           (or (str/starts-with? e "eco-refined-")
                                               (str/starts-with? e "eco-detritus-")))
                                    (+ acc v)
                                    acc))
                                0
                                (:datoms eco))
           out (into out (:datoms (quorum/sense final-moods beat-commons
                                                {:beat beat :as-of as-of})))

           ;; incremental drain: only lines this shard has not yet prepared (durable line
           ;; cursor)
           from-line (get-in idx [:drain-line shard-name] 0)
           [drained next-line] (drain-incremental queue-path from-line
                                                  {:as-of as-of :beat beat})
           out (into out drained)

           ;; 健全化: periodic colony self-audit over this shard's log (health) + the
           ;; colony's human-readable report (digest) — the deployed fleet path reports to
           ;; humanity too, not just the seed demo. Both read `txs` (the log BEFORE this
           ;; beat), so no self-read.
           out (if (zero? (mod beat health-every))
                 (-> out
                     (into (health/health-datoms (health/audit txs)
                                                 {:beat beat :as-of as-of}))
                     (into (:datoms (digest/make txs {:beat beat :as-of as-of}))))
                 out)

           ;; durable sweep cursor checkpoint — the crash-resume point of the round-robin
           new-cursor (if (pos? n) (mod (+ start (min batch-size n)) n) 0)
           e (str "fleet-" shard-name)]
       (into out
             [(datoms/add e ":fleet.shard/cursor" new-cursor)
              (datoms/add e ":fleet.shard/drain-line" next-line)
              (datoms/add e ":fleet.shard/beat" beat)
              (datoms/add e ":fleet.shard/size" n)
              (datoms/add e ":fleet.shard/as-of" as-of)]))))

(defn beat-events
  "The offline stimulus pattern (kept for back-compat; the membrane itself lives in
  ibuki.methods.perception — live mode via IBUKI_PERCEPTION_LIVE=1)."
  [beat]
  (perception/representative-events beat))

;; ── the fleet runner ──────────────────────────────────────────────────────

(def root "20-actors/ibuki")
(def default-log-path (str root "/data/ibuki-fleet.datoms.kotoba.edn"))
(def default-queue-path (str root "/data/fleet-posts.queue.ndjson"))

#?(:clj
   (defn fleet-autorun
     "Run `cycles` fleet beats over one shard, one content-addressed tx per beat. Resumes
     sweep cursor + drain cursor + every organism's state from the log.
     Opts: :shard-index :batch-size :fresh :log-path :queue-path :registry-path.
     Returns {:beats :head :chain :shard :shard-size :organisms-alive :cursor}."
     ([cycles] (fleet-autorun cycles {}))
     ([cycles {:keys [shard-index batch-size fresh log-path queue-path registry-path]
               :or {batch-size default-batch fresh false}}]
      (let [log (or log-path default-log-path)
            queue (or queue-path default-queue-path)]
        (when fresh
          (.delete (io/file (str log)))
          (.delete (io/file (str queue))))
        (let [shard (if (some? shard-index) shard-index (resolve-shard))
              shard-name (first (get shards shard))
              slice (shard-agents (load-registry registry-path) shard)]
          (dotimes [_ cycles]
            (let [txs (datoms/read-log log)
                  idx (index-log txs)
                  beat (inc (count txs))
                  body (fleet-beat slice idx {:shard-name shard-name :beat beat
                                              :batch-size batch-size :queue-path queue
                                              :txs txs})
                  tx (datoms/make-tx body {:tx-id beat :as-of (+ as-of-base beat)
                                           :prev-cid (datoms/head-cid log)})]
              (datoms/append-tx! tx log)))
          (let [chain (datoms/verify-chain log)]
            (when-not (:ok chain)
              (throw (ex-info (str "kotoba Datom chain broken: " chain) chain)))
            (let [final (index-log (datoms/read-log log))]
              {:beats (:length chain) :head (datoms/head-cid log) :chain chain
               :shard shard-name :shard-size (count slice)
               :organisms-alive (count (:hb final))
               :cursor (get-in final [:cursor shard-name] 0)})))))))
