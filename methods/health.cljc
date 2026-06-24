(ns ibuki.methods.health
  "health — colony 健全性 (Wellbecoming) audit, derived purely from the Datom log.
  ADR-2606101200 §健全化. Clojure port of `methods/health.py`.

  `audit` reads NOTHING but the log (single pass, fleet-scale safe) and reports, per organism
  and for the colony:

    - muted        — mood `stressed` for ≥ MUTED_STREAK consecutive recent beats.
    - saturation   — any axis pinned at the 0/100 clamp (emotion ceiling = no headroom).
    - stress_excess — head stress above the designed equilibrium band (baseline + BAND).
    - divergence   — the `:joucho/*` checkpoint disagrees with the pure as-of replay.
    - posting drought — beats since last post far beyond the slowest mood cooldown.
    - colony       — mood diversity, niche population, eco-maturity, verdict.

  Plus ecosystem completeness + resilience detectors (ecosystem-starved / keystone-niche-absent
  / niche-imbalance). Pathologies become KaizenProposal lines; `:health/*` checkpoint datoms put
  the verdict on the log (as-of queryable). No per-organism wellbeing SCORE (edge-primary).
  Deterministic. Append-only."
  (:require [clojure.string :as str]
            [ibuki.methods.datoms :as datoms]
            [ibuki.methods.joucho :as joucho]
            #?(:clj [clojure.java.io :as io])))

(def stress-band 4)          ;; designed equilibrium: stress ≤ baseline + BAND under idle drift
(def muted-streak 12)        ;; consecutive stressed beats before an organism counts as muted
(def drought-factor 3)       ;; beats-without-post > FACTOR × slowest cooldown (in beats) = drought
(def beat-ms (* 45 60000))   ;; autorun/fleet logical beat

(def rules ["organism-muted" "axis-saturation" "stress-excess"
            "checkpoint-divergence" "posting-drought" "mood-monoculture"
            "ecosystem-starved" "keystone-niche-absent" "niche-imbalance"])

(def eco-grace-beats 6)      ;; don't flag a broken web before the colony has had time to feed
(def niche-evenness-floor 0.5)  ;; Pielou evenness below this on a 3-niche colony = imbalance
(def all-niches [":niche/producer" ":niche/router" ":niche/decomposer"])

(def ^:private axes [:joy :calm :stress :gratitude :focus])

(defn evenness
  "Pielou's evenness J = H / ln(S) over the niche populations — 1.0 = perfectly even, →0 = one
  niche dominates. 0.0 for an empty/degenerate colony. An aggregate, never a per-organism score."
  [pop]
  (let [total (reduce + (vals pop))
        present (filter pos? (vals pop))]
    (if (or (zero? total) (<= (count present) 1))
      0.0
      (let [h (- (reduce + (map (fn [c] (* (/ c (double total))
                                           (Math/log (/ c (double total)))))
                                present)))]
        (/ h (Math/log (count present)))))))

(defn- walk
  "ONE pass over the log → per-organism {:events [[beat kind]…] :joucho-ck latest-ck-attrs
  :hb latest-hb-attrs}. Fleet-scale safe (no per-entity refolds)."
  [txs]
  (let [{:keys [ev-entity ev-order joucho-ck hb]}
        (reduce (fn [acc [_op e a v]]
                  (cond
                    (str/starts-with? a ":joucho.event/")
                    (-> acc
                        (update :ev-order #(if (contains? (:ev-entity acc) e) % (conj % e)))
                        (assoc-in [:ev-entity e a] v))
                    (str/starts-with? a ":joucho/")
                    (assoc-in acc [:joucho-ck e a] v)
                    (str/starts-with? a ":heartbeat/")
                    (assoc-in acc [:hb e a] v)
                    :else acc))
                {:ev-entity {} :ev-order [] :joucho-ck {} :hb {}}
                (mapcat :tx/datoms txs))
        per0 (reduce (fn [per e]
                       (let [ent (get ev-entity e)
                             of (get ent ":joucho.event/of")
                             kind (get ent ":joucho.event/kind")]
                         (if (and of kind)
                           (-> per
                               (update of #(or % {:events [] :joucho-ck nil :hb nil}))
                               (update-in [of :events] conj
                                          [(get ent ":joucho.event/beat" 0) kind]))
                           per)))
                     {}
                     ev-order)
        per1 (reduce (fn [per ent]
                       (let [of (get ent ":joucho/of")
                             beat (get ent ":joucho/beat" -1)]
                         (if (and (contains? per of)
                                  (> beat (get-in per [of ::ck-beat] -1)))
                           (-> per (assoc-in [of :joucho-ck] ent) (assoc-in [of ::ck-beat] beat))
                           per)))
                     per0
                     (vals joucho-ck))
        per2 (reduce (fn [per ent]
                       (let [of (get ent ":heartbeat/of")
                             beat (get ent ":heartbeat/beat" -1)]
                         (if (and (contains? per of)
                                  (> beat (get-in per [of ::hb-beat] -1)))
                           (-> per (assoc-in [of :hb] ent) (assoc-in [of ::hb-beat] beat))
                           per)))
                     per1
                     (vals hb))]
    per2))

(defn audit
  "The colony health report, from the log alone. Deterministic."
  [txs]
  (let [per (walk txs)
        slowest-beats (+ (quot (reduce max (vals joucho/post-cooldown-ms)) beat-ms) 1)
        ;; per-organism pass (sorted by code)
        [organisms head-moods findings0]
        (reduce
         (fn [[organisms head-moods findings] [code rec]]
           (let [base (joucho/personality-baseline code)
                 ;; incremental fold, one pass — collect (beat, mood) trail
                 [scores mood-by-beat]
                 (reduce (fn [[scores trail] [beat kind]]
                           (let [s (joucho/fold-event scores kind base)]
                             [s (conj trail [beat (joucho/determine-mood s)])]))
                         [base []]
                         (:events rec))
                 mood (joucho/determine-mood scores)
                 head-moods (update head-moods mood (fnil inc 0))
                 streak (reduce (fn [streak [_b m]]
                                  (if (= m "stressed") (inc streak) (reduced streak)))
                                0
                                (reverse mood-by-beat))
                 muted (>= streak muted-streak)
                 saturated (vec (keep (fn [a] (when (#{0 100} (get scores a)) (name a))) axes))
                 stress-excess (max 0 (- (:stress scores) (+ (:stress base) stress-band)))
                 ck (:joucho-ck rec)
                 diverged (boolean
                           (and (some? ck)
                                (some (fn [a] (not= (get ck (str ":joucho/" (name a)))
                                                    (get scores a)))
                                      axes)))
                 hb (:hb rec)
                 drought (boolean
                          (when (and (some? hb) (> (get hb ":heartbeat/beats" 0) 0))
                            (let [beats (get hb ":heartbeat/beats")
                                  last-post-ms (get hb ":heartbeat/last-post-at-ms" -1)
                                  since (- beats (if (>= last-post-ms 0)
                                                   (quot last-post-ms beat-ms) 0))]
                              (> since (* drought-factor slowest-beats)))))
                 org {:mood mood :axes scores :stressed-streak streak :muted muted
                      :saturated-axes saturated :stress-excess stress-excess
                      :checkpoint-diverged diverged :posting-drought drought}
                 new-findings
                 (keep (fn [[cond rule detail]]
                         (when cond
                           {:proposal-id (str "health-" rule "-" code)
                            :rule rule :organism code :detail detail}))
                       [[muted "organism-muted" (str "stressed for " streak " consecutive beats")]
                        [(boolean (seq saturated)) "axis-saturation"
                         (str "axes at clamp: " saturated)]
                        [(> stress-excess 0) "stress-excess"
                         (str "stress " (:stress scores) " > baseline " (:stress base)
                              " + " stress-band)]
                        [diverged "checkpoint-divergence"
                         "joucho checkpoint != as-of replay (two histories)"]
                        [drought "posting-drought"
                         "no post far beyond slowest cooldown"]])]
             [(assoc organisms code org)
              head-moods
              (into findings new-findings)]))
         [{} {} []]
         (sort-by key per))

        ;; mood monoculture (colony-level)
        findings1 (if (and (>= (count per) 3) (= (count head-moods) 1))
                    (conj findings0
                          {:proposal-id "health-mood-monoculture-colony"
                           :rule "mood-monoculture" :organism "*"
                           :detail (str "all " (count per) " organisms share mood "
                                        (pr-str (first (keys head-moods)))
                                        " — personality collapsed")})
                    findings0)

        ;; ecosystem completeness + resilience (read the food web off the log)
        [substrate-beats commons-seen niche-pop]
        (reduce (fn [[sb cs np] tx]
                  (reduce (fn [[sb cs np] [_op _e a v]]
                            (cond
                              (and (= a ":metabolite/kind") (= v ":substrate"))
                              [(conj sb (:tx/id tx)) cs np]
                              (and (= a ":metabolite/commons") (true? v))
                              [sb true np]
                              (= a ":organism/niche")
                              [sb cs (update np v (fnil inc 0))]
                              :else [sb cs np]))
                          [sb cs np]
                          (:tx/datoms tx)))
                [#{} false {}]
                txs)
        eco-maturity (evenness niche-pop)
        findings2 (if (and (> (count substrate-beats) eco-grace-beats) (not commons-seen))
                    (conj findings1
                          {:proposal-id "health-ecosystem-starved-colony"
                           :rule "ecosystem-starved" :organism "*"
                           :detail (str "primary production occurs but no commons metabolite "
                                        "reaches humanity — the food web is broken")})
                    findings1)
        findings3 (if (seq niche-pop)
                    (let [absent (filterv #(zero? (get niche-pop % 0)) all-niches)]
                      (cond
                        (seq absent)
                        (conj findings2
                              {:proposal-id "health-keystone-niche-absent-colony"
                               :rule "keystone-niche-absent" :organism "*"
                               :detail (str "trophic niche(s) absent: " absent
                                            " — the web cannot close")})
                        (and (>= (reduce + (vals niche-pop)) 3)
                             (< eco-maturity niche-evenness-floor))
                        (conj findings2
                              {:proposal-id "health-niche-imbalance-colony"
                               :rule "niche-imbalance" :organism "*"
                               :detail (str "niche evenness " eco-maturity " < "
                                            niche-evenness-floor
                                            " — one trophic role dominates: " niche-pop)})
                        :else findings2))
                    findings2)]
    {:organisms organisms
     :colony {:count (count per)
              :mood-diversity head-moods
              :niche-population niche-pop
              :eco-maturity eco-maturity
              :findings (count findings3)}
     :findings findings3
     :healthy (empty? findings3)}))

(defn- round4 [x]
  (/ (Math/round (* (double x) 10000.0)) 10000.0))

(defn health-datoms
  "Checkpoint the verdict on the log (`:health/*`) — colony aggregate + one flag per finding.
  No per-organism wellbeing score is ever asserted (edge-primary). Opts: :beat :as-of."
  [report {:keys [beat as-of]}]
  (let [e (str "health-" beat)
        base [(datoms/add e ":health/beat" beat)
              (datoms/add e ":health/as-of" as-of)
              (datoms/add e ":health/organisms" (get-in report [:colony :count]))
              (datoms/add e ":health/mood-kinds" (count (get-in report [:colony :mood-diversity])))
              (datoms/add e ":health/eco-maturity"
                          (round4 (get-in report [:colony :eco-maturity] 0.0)))
              (datoms/add e ":health/findings" (get-in report [:colony :findings]))
              (datoms/add e ":health/healthy" (:healthy report))]]
    (into base
          (mapcat (fn [i f]
                    (let [fe (str "health-" beat "-f" i)]
                      [(datoms/add fe ":health.finding/of" (:organism f))
                       (datoms/add fe ":health.finding/rule" (:rule f))
                       (datoms/add fe ":health.finding/beat" beat)
                       (datoms/add fe ":health.finding/as-of" as-of)]))
                  (range)
                  (:findings report)))))

#?(:clj
   (defn write-proposals
     "Pathologies → KaizenProposal NDJSON (proposalId + rule — exactly the shape
     kaizen_feedback/read-proposals consumes). Snapshot semantics (overwrite): current
     pathologies only; the as-of history lives on the log via health-datoms. Returns the count."
     [report path]
     (let [generate (requiring-resolve 'cheshire.core/generate-string)
           f (io/file (str path))]
       (when-let [p (.getParentFile f)] (.mkdirs p))
       (spit f (str/join (map (fn [fnd]
                                (str (generate (into (sorted-map)
                                                     {"proposalId" (:proposal-id fnd)
                                                      "rule" (:rule fnd)
                                                      "organism" (:organism fnd)
                                                      "detail" (:detail fnd)}))
                                     "\n"))
                              (:findings report))))
       (count (:findings report)))))
