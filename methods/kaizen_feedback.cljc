(ns ibuki.methods.kaizen-feedback
  "ibuki 息吹 — Wave-4 feedback that closes the Kaizen loop.
  Clojure port of `methods/kaizen_feedback.py` (ADR-2606101200).

  Closes Gap 7 of the organism autonomy survey (\"Kaizen is fire-and-forget:
  proposals flow out, nothing flows back\"). The kotodama Kaizen observer emits
  KaizenProposal NDJSON and the (already existing) PR agent drafts
  human-reviewed PRs; what was missing is the RETURN edge — did the proposal
  merge? was it rejected? — folded back into durable state so the observer
  LEARNS.

    - read-proposals    KaizenProposal NDJSON lines ({:proposal-id :rule …})
    - read-outcomes     outcome records ({:proposal-id :rule :outcome ∈
                        merged|rejected|pending}) — written by the operator /
                        PR-agent runner from `gh pr view` results (never by
                        ibuki reaching out to GitHub)
    - fold              per-rule stats incl. CONSECUTIVE rejections
    - suppression       rules with ≥ suppress-after consecutive rejections are
                        suppressed for suppress-beats beats (the observer stops
                        re-proposing what humans keep declining — that is the
                        learning)
    - should-emit       the observer-side gate
    - feedback-datoms   `:kaizen.rule/*` checkpoints on the kotoba log (as-of
                        history of what the colony learned about its own
                        proposals)

  Outcomes also feed the organism mood: `mood-events` maps merged →
  :event/kaizen-merged, rejected → :event/kaizen-rejected (joucho) —
  acceptance literally calms the colony. Deterministic. Human review stays the
  decision-maker (ADR-2605240200: auto-apply is rejected; the loop closes
  through people, the LEARNING closes through the log)."
  (:require #?(:clj [cheshire.core :as json])
            [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])))

(def suppress-after 3)   ;; consecutive rejections before a rule is suppressed
(def suppress-beats 12)  ;; how many beats a suppressed rule stays quiet

(def outcomes ["merged" "rejected" "pending"])
(def ^:private outcome-set (set outcomes))

;; ── NDJSON edge (JSON keys → kebab-case keywords) ─────────────────────────

(defn- kebab-key
  "JSON key → kebab-case keyword (proposalId → :proposal-id)."
  [s]
  (keyword (-> (str s)
               (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
               (str/replace "_" "-")
               str/lower-case)))

#?(:clj
   (defn- read-ndjson [path]
     (let [f (io/file (str path))]
       (if-not (.exists f)
         []
         (let [parse json/parse-string]
           (->> (str/split-lines (slurp f))
                (map str/trim)
                (remove str/blank?)
                (mapv #(parse % kebab-key))))))))

#?(:clj
   (defn read-proposals
     "KaizenProposal NDJSON (kotodama kaizen_cell_main schema; needs
     :proposal-id + :rule). Malformed lines are filtered; a missing file is []."
     [path]
     (vec (filter #(and (contains? % :proposal-id) (contains? % :rule))
                  (read-ndjson path)))))

#?(:clj
   (defn read-outcomes
     "Outcome NDJSON. An unknown outcome value throws — closed vocab, never
     guessed. A missing file is []."
     [path]
     (vec (keep (fn [o]
                  (when (and (contains? o :proposal-id) (contains? o :rule))
                    (when-not (outcome-set (:outcome o))
                      (throw (ex-info (str "unknown kaizen outcome (closed vocab "
                                           outcomes "): " (pr-str (:outcome o)))
                                      {:outcome (:outcome o)})))
                    o))
                (read-ndjson path)))))

;; ── pure folds (portable) ─────────────────────────────────────────────────

(def ^:private empty-rule-stats
  {:proposed 0 :merged 0 :rejected 0 :pending 0 :consecutive-rejected 0})

(defn fold
  "Per-rule stats. Outcomes are folded in file order (the operator appends as
  PRs resolve), so :consecutive-rejected reflects the latest run of rejections."
  [proposals outcome-records]
  (let [stats (reduce (fn [st p]
                        (update st (:rule p)
                                #(update (or % empty-rule-stats) :proposed inc)))
                      {} proposals)]
    (reduce (fn [st o]
              (let [s (-> (or (get st (:rule o)) empty-rule-stats)
                          (update (keyword (:outcome o)) inc))
                    s (case (:outcome o)
                        "rejected" (update s :consecutive-rejected inc)
                        "merged" (assoc s :consecutive-rejected 0)
                        s)]
                (assoc st (:rule o) s)))
            stats outcome-records)))

(defn suppression
  "rule → suppressed-until-beat for every rule whose consecutive rejections
  reached the threshold. This is the colony declining to repeat what its
  humans keep declining."
  [stats now-beat]
  (into {} (keep (fn [[rule s]]
                   (when (>= (:consecutive-rejected s) suppress-after)
                     [rule (+ now-beat suppress-beats)]))
                 stats)))

(defn should-emit
  "Observer-side gate: may this rule emit a proposal at this beat?"
  [rule suppress-table beat]
  (>= beat (get suppress-table rule -1)))

(defn mood-events
  "Map PR outcomes to joucho events — acceptance calms, rejection stresses
  (and sharpens focus). Pending moves nothing."
  [outcome-records]
  (vec (keep #(case (:outcome %)
                "merged" ":event/kaizen-merged"
                "rejected" ":event/kaizen-rejected"
                nil)
             outcome-records)))

;; ── :kaizen.rule/* checkpoint datoms ──────────────────────────────────────

(defn- add
  "One append-only EAVT assertion: [\":db/add\" e a v] (datoms.add parity)."
  [e a v]
  [":db/add" e a v])

(defn feedback-datoms
  "Checkpoint what the colony learned as `:kaizen.rule/*` datoms (new entity
  per beat — the learning history is as-of queryable like everything else)."
  [stats suppress-table beat as-of]
  (vec (mapcat
        (fn [[rule s]]
          (let [e (str "kzr-" rule "-" beat)]
            [(add e ":kaizen.rule/id" rule)
             (add e ":kaizen.rule/beat" beat)
             (add e ":kaizen.rule/as-of" as-of)
             (add e ":kaizen.rule/proposed" (:proposed s))
             (add e ":kaizen.rule/merged" (:merged s))
             (add e ":kaizen.rule/rejected" (:rejected s))
             (add e ":kaizen.rule/consecutive-rejected" (:consecutive-rejected s))
             (add e ":kaizen.rule/suppressed-until-beat" (get suppress-table rule -1))]))
        (sort-by key stats))))
