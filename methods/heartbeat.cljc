(ns ibuki.methods.heartbeat
  "ibuki 息吹 — durable heartbeat cadence on the kotoba Datom log. ADR-2606101200.
  Clojure port of `methods/heartbeat.py`.

  Closes Gap 1+2 of the organism autonomy survey (\"no durable scheduler — if the pod dies
  between ticks, cooldowns reset\"). The cadence state is no longer process memory: every
  beat appends a `:heartbeat/*` checkpoint datom, and a restarted runner REPLAYS the log to
  recover exactly where it was (crash-resume = read the append-only history; nothing is
  lost because nothing was only in RAM).

    replay            (txs of)            → state map folded from the log (latest checkpoint)
    due-to-post       (state mood now-ms) → [due? reason] under the mood cooldown table
    checkpoint-datoms (…)                 → this beat's `:heartbeat/*` assertions

  Time is LOGICAL: a beat index + a caller-supplied now-ms (no wall clock) — deterministic
  and replay-safe. The cron layer (Murakumo cells / k8s) only decides *when* to call tick;
  *whether* an organism acts is decided here, from durable state.

  State is a plain map {:last-post-at-ms :beats :posts} (the Python HeartbeatState
  dataclass → `default-state` / `(state …)`)."
  (:require [ibuki.methods.datoms :as d]
            [ibuki.methods.joucho :refer [post-cooldown-ms post-enabled]]))

(def default-state
  "The durable cadence state of one organism (everything a restart must recover).
  :last-post-at-ms -1 = never posted; :beats / :posts are monotone totals."
  {:last-post-at-ms -1 :beats 0 :posts 0})

(defn state
  "A heartbeat state map with fresh defaults, optionally overridden per field."
  ([] default-state)
  ([overrides] (merge default-state overrides)))

(defn replay
  "Fold the `:heartbeat/*` checkpoints for one organism out of the log. The LAST
  checkpoint wins (append-only shadowing) — this is the crash-resume read."
  [txs of]
  (let [latest (reduce (fn [latest e]
                         (let [ent (d/fold-entity txs e)]
                           (if (= of (get ent ":heartbeat/of"))
                             (let [beat (get ent ":heartbeat/beat" -1)]
                               (if (or (nil? latest) (> beat (first latest)))
                                 [beat ent]
                                 latest))
                             latest)))
                       nil
                       (d/entities txs ":heartbeat/of"))]
    (if-let [[_beat ent] latest]
      {:last-post-at-ms (get ent ":heartbeat/last-post-at-ms" -1)
       :beats (get ent ":heartbeat/beats" 0)
       :posts (get ent ":heartbeat/posts" 0)}
      default-state)))

(defn due-to-post
  "Is this organism due to post at logical time now-ms, given its mood? Pure function of
  durable state — the same answer before and after a crash. Returns [due? reason]."
  [st mood now-ms]
  (if-not (get post-enabled mood false)
    [false (str "post disabled while " mood)]
    (let [cooldown (get post-cooldown-ms mood)]
      (if (neg? (:last-post-at-ms st))
        [true "first post"]
        (let [elapsed (- now-ms (:last-post-at-ms st))]
          (if (>= elapsed cooldown)
            [true (str "cooldown elapsed (" elapsed "ms >= " cooldown "ms while " mood ")")]
            [false (str "cooling down (" elapsed "ms < " cooldown "ms while " mood ")")]))))))

(defn checkpoint-datoms
  "This beat's `:heartbeat/*` checkpoint (a NEW entity per beat — the cadence history is
  itself as-of queryable, like every other organism fact)."
  [of st mood {:keys [beat as-of]}]
  (let [e (str "hb-" of "-" beat)]
    [(d/add e ":heartbeat/of" of)
     (d/add e ":heartbeat/beat" beat)
     (d/add e ":heartbeat/as-of" as-of)
     (d/add e ":heartbeat/mood" (str ":" mood))
     (d/add e ":heartbeat/last-post-at-ms" (:last-post-at-ms st))
     (d/add e ":heartbeat/beats" (:beats st))
     (d/add e ":heartbeat/posts" (:posts st))]))
