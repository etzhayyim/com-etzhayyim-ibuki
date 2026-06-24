(ns ibuki.methods.autorun
  "autorun — 息吹: the autonomous organism heartbeat over the kotoba Datom log.
  ADR-2606101200. Clojure port of `methods/autorun.py`.

  The integrating loop that closes the organism autonomy survey gaps in one beat cycle —
  shionome's autorun pattern (ADR-2606072200) applied to the organism layer:

    replay ─▶ perceive ─▶ feel ─▶ decide ─▶ narrate ─▶ act ─▶ checkpoint ─▶ append tx

  Every beat is DURABLE: state is replayed from the append-only log, so killing the process
  between beats loses nothing (Gap 1/2). Mood evolves from persisted events and is as-of
  queryable (Gap 3/4). Narration goes through ibuki.methods.infer — Murakumo-only, template
  fallback (Gap 5). Posts land as `:dry-run` datoms AND on the ADR-2605240100 NDJSON queue,
  which the Wave-3 drainer turns into member-sign-ready envelopes (Gap 6). Kaizen outcomes,
  when present, feed both rule suppression and the colony's mood (Gap 7).

  Deterministic: logical time only (beat index × beat-ms); same seed + same cycle count →
  same head CID. Live external I/O stays G8-gated — this loop's only side effects are the
  LOCAL log + the LOCAL queue file."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [ibuki.methods.datoms :as datoms]
            [ibuki.methods.digest :as digest]
            [ibuki.methods.drainer :as drainer]
            [ibuki.methods.ecosystem :as ecosystem]
            [ibuki.methods.health :as health]
            [ibuki.methods.heartbeat :as heartbeat]
            [ibuki.methods.infer :refer [narrate]]
            [ibuki.methods.joucho :as joucho]
            [ibuki.methods.kaizen-feedback :as kaizen-feedback]
            [ibuki.methods.quorum :as quorum]
            #?(:clj [clojure.java.io :as io])))

(def root "20-actors/ibuki")
(def seed-resource "ibuki/data/seed-organisms.kotoba.edn")
(def default-log-path (str root "/data/ibuki.datoms.kotoba.edn"))
(def default-queue-path (str root "/data/organism-posts.queue.ndjson"))
(def proposals-path (str root "/data/kaizen-proposals.ndjson"))
(def outcomes-path (str root "/data/kaizen-outcomes.ndjson"))

(def beat-ms
  "One beat = 45 logical minutes (crosses the joyful 30m cooldown, sits inside the
  calm/neutral 2h one — moods visibly change cadence)."
  (* 45 60000))

(def as-of-base 2606100000)

(def health-every
  "Colony self-audit cadence (ibuki.methods.health → :health/* checkpoints)."
  10)

(defn beat-events
  "This beat's perceived events — the bounded :representative stimulus pattern
  (deterministic, no live I/O): every beat passes time, every 3rd beat a follower arrives,
  every 5th the inbox surges. (Mirrors perception.representative-events; the live perception
  membrane lives in the unported Python perception.py / fleet.py.)"
  [beat]
  (cond-> [":event/idle"]
    (zero? (mod beat 3)) (conj ":event/follower-gained")
    (zero? (mod beat 5)) (conj ":event/inbox-pressure")))

#?(:clj
   (defn- load-seed
     "The committed :representative seed (3 organisms spanning all 3 niches). EDN keyword
     keys; the niche keyword is stringified at the use sites (\":…\" stays a string)."
     []
     (-> (or (io/resource seed-resource)
             (io/file (str root "/data/seed-organisms.kotoba.edn")))
         slurp
         edn/read-string
         (get :seed/organisms))))

#?(:clj
   (defn- queue-line!
     "Append one ADR-2605240100 v=1 line to the post queue (deterministic logical ts)."
     [queue-path did code title mood text ts-ms]
     (let [generate (requiring-resolve 'cheshire.core/generate-string)
           created (format "2026-06-10T00:00:00.%03dZ" (mod ts-ms 1000))
           line (into (sorted-map)
                      {"v" drainer/schema-version "ts" ts-ms "actorDid" did "code" code
                       "title" title "mood" mood "contentSourceKind" "recordAnalysis"
                       "text" text "lexicon" "app.bsky.feed.post" "createdAt" created})
           f (io/file (str queue-path))]
       (when-let [p (.getParentFile f)] (.mkdirs p))
       (spit f (str (generate line) "\n") :append true))))

#?(:clj
   (defn run-beat
     "One full heartbeat over every organism. Pure over (log so far, beat) → new datoms
     (plus the local queue append, exactly like the Python). Opts: :beat :queue-path."
     [organisms txs {:keys [beat queue-path]}]
     (let [now-ms (* beat beat-ms)
           as-of (+ as-of-base beat)

           ;; Kaizen feedback (Gap 7): outcomes fold into rule suppression + colony mood events.
           outcomes (kaizen-feedback/read-outcomes outcomes-path)
           proposals (kaizen-feedback/read-proposals proposals-path)
           kz-events (if (= beat 1) (kaizen-feedback/mood-events outcomes) [])
           out (if (or (seq outcomes) (seq proposals))
                 (let [stats (kaizen-feedback/fold proposals outcomes)
                       table (kaizen-feedback/suppression stats beat)]
                   (vec (kaizen-feedback/feedback-datoms stats table beat as-of)))
                 [])

           ;; ── Phase 1: feel + decide + act (per organism) — gather state, defer
           ;; checkpoints. Checkpoints are deferred until after the ecosystem cascade so a
           ;; fed producer's :event/symbiosis-fed lands in the SAME beat's event stream AND
           ;; checkpoint (checkpoint == as-of replay). ──
           [out pending beat-moods]
           (reduce
            (fn [[out pending beat-moods] org]
              (let [code (:organism/code org)
                    title (:organism/title org)
                    did (:organism/did org)
                    out (if (= beat 1)            ;; birth: assert the organism entity once
                          (let [e (str "org-" code)]
                            (into out
                                  [(datoms/add e ":organism/code" code)
                                   (datoms/add e ":organism/title" title)
                                   (datoms/add e ":organism/did" did)
                                   (datoms/add e ":organism/niche"
                                               (ecosystem/niche-of
                                                code (some-> (:organism/niche org) str)))
                                   (datoms/add e ":organism/born-beat" beat)]))
                          out)
                    baseline (joucho/personality-baseline code)
                    history (datoms/events-for txs code)
                    events (into (beat-events beat) kz-events)
                    scores (joucho/replay-events baseline (into history events))
                    mood (joucho/determine-mood scores)
                    state (heartbeat/replay txs code)
                    [due _reason] (heartbeat/due-to-post state mood now-ms)
                    [out events scores state]
                    (if due
                      (let [n (narrate title code mood "recordAnalysis")
                            pid (str "post-" code "-" beat)
                            out (into out
                                      [(datoms/add pid ":post/of" code)
                                       (datoms/add pid ":post/text" (:text n))
                                       (datoms/add pid ":post/via" (str ":" (:via n)))
                                       (datoms/add pid ":post/mood" (str ":" mood))
                                       (datoms/add pid ":post/beat" beat)
                                       (datoms/add pid ":post/as-of" as-of)
                                       (datoms/add pid ":post/status" ":dry-run")])]
                        (queue-line! queue-path did code title mood (:text n) now-ms)
                        [out
                         (conj events ":event/post-emitted")
                         (joucho/fold-event scores ":event/post-emitted" baseline)
                         (-> state (assoc :last-post-at-ms now-ms) (update :posts inc))])
                      [out events scores state])
                    state (update state :beats inc)]
                [out
                 (conj pending {:code code :baseline baseline :events events
                                :scores scores :mood mood :state state})
                 (assoc beat-moods code scores)]))
            [out [] {}]
            organisms)

           ;; ── Phase 2: 生態系 trophic cascade — producers fix substrate, 粘菌 routers relay
           ;; the richest to カビ decomposers, which excrete a refined commons metabolite (the
           ;; citric acid offered to humanity). Returns the producers the web fed. ──
           eco (ecosystem/cycle (mapv (fn [o] {:code (:organism/code o)
                                               :niche (some-> (:organism/niche o) str)})
                                      organisms)
                                beat-moods
                                {:beat beat :as-of as-of
                                 :satiated (ecosystem/satiated-producers txs beat)
                                 :trails (ecosystem/trail-strengths txs beat)})
           out (into out (:datoms eco))
           fed-count (frequencies (:fed eco))

           ;; ── Phase 3: fold symbiosis feeding into each fed producer's SAME-beat
           ;; checkpoint, then write exactly ONE event-datoms + joucho + heartbeat per
           ;; organism ──
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
                    mood (joucho/determine-mood scores)]   ;; final mood, post-symbiosis
                [(-> out
                     (into (joucho/event-datoms code events {:beat beat :as-of as-of}))
                     (into (joucho/joucho-datoms code scores mood {:beat beat :as-of as-of}))
                     (into (heartbeat/checkpoint-datoms code state mood
                                                        {:beat beat :as-of as-of})))
                 (assoc final-moods code mood)]))
            [out {}]
            pending)

           ;; ── 定足数 quorum sensing: a colony phenotype no cell could trigger alone. ──
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

           ;; drain (Gap 6): queue → member-sign-ready envelopes, checkpointed :prepared
           out (into out (:datoms (drainer/drain queue-path {:as-of as-of :beat beat})))]

       ;; 健全化: every health-every beats the colony audits its own log and checkpoints the
       ;; verdict (`:health/*`) — Wellbecoming as as-of history, measured not assumed; then
       ;; the colony REASONS about its ecosystem + reports to humanity (Murakumo-only,
       ;; dry-run): a mirror of where its life became a gift (digest). Uses `txs` (the log
       ;; BEFORE this beat) so it never reads its own in-flight datoms.
       (if (zero? (mod beat health-every))
         (-> out
             (into (health/health-datoms (health/audit txs) {:beat beat :as-of as-of}))
             (into (:datoms (digest/make txs {:beat beat :as-of as-of}))))
         out))))

#?(:clj
   (defn autorun
     "Run `cycles` heartbeats, each appended as one content-addressed transaction. Resumes
     from whatever the log already holds (crash-resume is just... running again).
     Opts: :fresh :log-path :queue-path. Returns {:beats :head :chain}."
     ([cycles] (autorun cycles {}))
     ([cycles {:keys [fresh log-path queue-path]
               :or {fresh false log-path default-log-path}}]
      (let [queue-path (or queue-path default-queue-path)]
        (when fresh
          (.delete (io/file (str log-path)))
          (.delete (io/file (str queue-path))))
        (let [organisms (load-seed)]
          ;; O(N) heartbeat: read the durable log ONCE, then thread (txs, head) in memory across
          ;; beats. append-tx! still persists every beat, and a *fresh* autorun call re-reads from
          ;; disk — so crash-resume stays byte-identical (verified by the integration suite). This
          ;; removes the O(N²) per-beat full-log re-parse that made the run intractable under SCI.
          (loop [n 0
                 txs (datoms/read-log log-path)
                 head (datoms/head-cid log-path)]
            (when (< n cycles)
              (let [beat (inc (count txs))
                    body (run-beat organisms txs {:beat beat :queue-path queue-path})
                    tx (datoms/make-tx body {:tx-id beat :as-of (+ as-of-base beat)
                                             :prev-cid head})
                    cid (datoms/append-tx! tx log-path)]
                (recur (inc n) (conj txs tx) cid))))
          (let [chain (datoms/verify-chain log-path)]
            (when-not (:ok chain)
              (throw (ex-info (str "kotoba Datom chain broken: " chain) chain)))
            {:beats (:length chain) :head (datoms/head-cid log-path) :chain chain}))))))
