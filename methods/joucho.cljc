(ns ibuki.methods.joucho
  "ibuki 息吹 — 情緒 5-axis mood that EVOLVES from observed events. ADR-2606101200.
  Clojure port of `methods/joucho.py`.

  Closes Gap 4 of the organism autonomy survey (\"joucho provider is a constant stub →
  personality never emerges\"). Three pieces:

    1. `personality-baseline` — deterministic per-organism 5-axis baseline (hash → [25,75]
       per axis): every organism gets a distinct, repeatable temperament with no network
       round-trip.
    2. `fold-event` / `replay-events` — a CLOSED event vocabulary folds observed history
       into the scores. Because events are persisted as `:joucho.event/*` datoms on the
       append-only kotoba log (ibuki.methods.datoms), mood is **as-of replayable**: replay
       the event stream up to tx N and you have the organism's mood at tx N. The mood is
       the fold of the life lived — 縁起.
    3. `determine-mood` + `post-cooldown-ms` — mood thresholds identical to kotodama joucho
       (stress ≥70 trumps; dominant axis ≥60 wins; else neutral) + the ibuki R0 post-cadence
       table.

  Scores are plain maps {:joy :calm :stress :gratitude :focus} (each 0-100); the Python
  JouchoScores dataclass defaults are `default-scores` / `(scores …)`. Deterministic. No
  wall clock."
  (:require [ibuki.methods.datoms :as d]))

(def moods ["joyful" "calm" "stressed" "grateful" "focused" "neutral"])

(def ^:private axes [:joy :calm :stress :gratitude :focus])

(def ^:private min-ms 60000)

(def default-scores
  "5-axis 情緒 scores (each 0-100). Defaults match the kotodama constant fallback —
  the very stub this module replaces (joy=50, calm=50, stress=30, gratitude=50, focus=50)."
  {:joy 50 :calm 50 :stress 30 :gratitude 50 :focus 50})

(defn scores
  "A scores map with the kotodama-stub defaults, optionally overridden per axis."
  ([] default-scores)
  ([overrides] (merge default-scores overrides)))

;; ── sha-256 (host seam, kotoba.datom pattern) ─────────────────────────────

(def ^:dynamic *sha256-bytes*
  "String → vector of unsigned digest bytes (0-255). Rebind on hosts without MessageDigest."
  #?(:clj (fn [^String s]
            (mapv #(bit-and % 0xff)
                  (.digest (java.security.MessageDigest/getInstance "SHA-256")
                           (.getBytes s "UTF-8"))))
     :default (fn [_] (throw (ex-info "bind ibuki.methods.joucho/*sha256-bytes* on this host" {})))))

(defn personality-baseline
  "Deterministic per-organism baseline: sha256(code) → 5 evenly-distributed ints in
  [25, 75]. Distinct, repeatable temperament per organism — no I/O. The stress axis is
  bounded to [25, 65]: stress comes from LIVED events, never from temperament (a baseline
  ≥70 would mean born-permanently-stressed — homeostasis would hold the organism above the
  stressed threshold forever, muting it for life)."
  [code]
  (let [h (*sha256-bytes* code)
        vals (mapv (fn [i] (+ 25 (mod (+ (* 256 (h (* 2 i))) (h (inc (* 2 i)))) 51)))
                   (range 5))
        vals (update vals 2 (fn [v] (+ 25 (quot (* (- v 25) 41) 51))))] ;; stress → [25, 65]
    (zipmap axes vals)))

(defn determine-mood
  "High stress trumps; otherwise the dominant axis ≥60 wins; else neutral.
  Thresholds identical to kotodama joucho/determine-mood."
  [j]
  (if (>= (:stress j) 70)
    "stressed"
    (let [[mood v] (first (sort-by second (fn [a b] (compare b a))
                                   [["joyful" (:joy j)] ["calm" (:calm j)]
                                    ["grateful" (:gratitude j)] ["focused" (:focus j)]]))]
      (if (< v 60) "neutral" mood))))

;; ── mood → post cadence (ibuki R0 table) ──────────────────────────────────

(def post-cooldown-ms
  {"joyful"   (* 30 min-ms)
   "grateful" (* 60 min-ms)
   "calm"     (* 120 min-ms)
   "neutral"  (* 120 min-ms)
   "focused"  (* 180 min-ms)
   "stressed" (* 240 min-ms)})

(def post-enabled
  (into {} (map (fn [m] [m (not= m "stressed")])) moods))

;; ── event fold (the growth loop) ──────────────────────────────────────────

(def event-deltas
  "CLOSED vocabulary — an unknown event kind throws (charter discipline: an unrepresentable
  stimulus cannot move a mood). Deltas are [joy calm stress gratitude focus].

  `:event/dialogue-reciprocated` (ADR-2606171500) is the charter-clean social-reward signal:
  it fires when a member-attributed post draws a RECIPROCAL reply — a 縁 returned, a dialogue
  closed — NOT a like/love/comment COUNT. Engagement tallies are deliberately unrepresentable
  here: §1.13 Wellbecoming forbids addictive/engagement-maximizing design, and shiori classes
  `:engagement-maximizing-design` as a DETRACTOR. So the reward is relational (being heard
  soothes isolation + warms gratitude), never a popularity score. Edge-primary: the event is
  the reciprocated bond, attributed to the exchange, never a per-soul tally."
  {":event/post-emitted"          [0 0 0 0 1]
   ":event/follower-gained"       [2 0 0 2 0]
   ":event/dialogue-reciprocated" [2 1 -1 3 0]   ;; a reply returned: heard → calmer, grateful
   ":event/inbox-pressure"        [0 -1 4 0 0]
   ":event/kaizen-merged"         [0 3 -3 1 0]
   ":event/kaizen-rejected"       [0 0 2 0 1]
   ":event/idle"                  [0 0 0 0 0]}) ;; handled as drift-toward-baseline below

(defn- clamp [v]
  (max 0 (min 100 v)))

(defn- drift
  "One step of homeostasis: idle beats pull each axis 1 toward its baseline."
  [v base]
  (cond
    (> v base) (dec v)
    (< v base) (inc v)
    :else v))

(defn fold-event
  "Fold ONE observed event into the scores (pure — returns a new scores map)."
  [sc event baseline]
  (when-not (contains? event-deltas event)
    (throw (ex-info (str "unknown joucho event kind (closed vocab): " event)
                    {:event event})))
  (if (= event ":event/idle")
    (into {} (map (fn [a] [a (drift (sc a) (baseline a))])) axes)
    (let [delta (event-deltas event)]
      (into {} (map-indexed (fn [i a] [a (clamp (+ (sc a) (delta i)))])) axes))))

(defn replay-events
  "Replay a full event stream from the baseline — THE as-of mood query: feed it
  `(datoms/events-for txs of {:up-to-tx N})` and you get the mood at tx N."
  [baseline events]
  (reduce (fn [sc ev] (fold-event sc ev baseline)) baseline events))

(defn joucho-datoms
  "Checkpoint the folded scores + mood as `:joucho/*` datoms (a NEW entity per beat —
  re-observation is a new datom, never an overwrite; 非終末論)."
  [of sc mood {:keys [beat as-of]}]
  (let [e (str "joucho-" of "-" beat)]
    (-> [(d/add e ":joucho/of" of)
         (d/add e ":joucho/beat" beat)
         (d/add e ":joucho/as-of" as-of)]
        (into (map (fn [a] (d/add e (str ":joucho/" (name a)) (sc a))) axes))
        (conj (d/add e ":joucho/mood" (str ":" mood))))))

(defn event-datoms
  "Persist this beat's observed events as `:joucho.event/*` datoms — the replayable
  history that makes the mood as-of queryable."
  [of kinds {:keys [beat as-of]}]
  (into []
        (mapcat (fn [[i kind]]
                  (when-not (contains? event-deltas kind)
                    (throw (ex-info (str "unknown joucho event kind (closed vocab): " kind)
                                    {:event kind})))
                  (let [e (str "jev-" of "-" beat "-" i)]
                    [(d/add e ":joucho.event/of" of)
                     (d/add e ":joucho.event/kind" kind)
                     (d/add e ":joucho.event/beat" beat)
                     (d/add e ":joucho.event/as-of" as-of)])))
        (map-indexed vector kinds)))
