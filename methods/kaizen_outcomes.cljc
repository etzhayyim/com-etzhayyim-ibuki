(ns ibuki.methods.kaizen-outcomes
  "kaizen-outcomes — 息吹 (ibuki) R3: collect real PR outcomes for the Wave-4 feedback loop.
  ADR-2606101200 §R3. Clojure port of `methods/kaizen_outcomes.py`.

  kaizen-feedback learns from an outcomes NDJSON that was, until now, operator-hand-written.
  This namespace fills it from the real source of truth: the GitHub PR state of each
  proposal's PR, read via the operator's own `gh` CLI session.

  Operator-principal, same boundary as member-submit:
    - runs with the OPERATOR's own `gh` auth (the platform holds no token — `gh` resolves
      the operator's keychain credential at invocation time);
    - REFUSES cron contexts (IBUKI_CRON=1): a platform job may never wield an operator
      credential (ADR-2605231525 discipline);
    - read-only: only `gh pr view --json state` — this namespace touches nothing on the PR
      and writes nothing toward GitHub. The humans stay the decision-makers
      (ADR-2605240200); this just tells the colony what the humans decided.

  Mapping (closed): MERGED → merged · CLOSED → rejected · OPEN → pending.

  The gh runner is INJECTABLE (`*gh*` dynamic var or the `:gh` option key) for hermetic
  tests, defaulting to shelling out via `babashka.process` (loaded lazily via
  requiring-resolve — the infer.cljc pattern). Environment reads go through the injectable
  `*env*` edge (nil → the real process env; a map → the test env)."
  (:require [clojure.string :as str]
            [ibuki.methods.kaizen-feedback :as kf]
            #?(:clj [clojure.java.io :as io])))

(def env-cron "IBUKI_CRON")

(def state->outcome
  {"MERGED" "merged" "CLOSED" "rejected" "OPEN" "pending"})

;; ── injectable environment edge ───────────────────────────────────────────

(def ^:dynamic *env*
  "Injectable environment edge: nil → the real process env; a map → the injected env."
  nil)

(defn- env-get [k]
  (if (some? *env*)
    (get *env* k)
    #?(:clj (System/getenv ^String k) :default nil)))

;; ── OperatorContextRequired (marker ex-info + predicate) ──────────────────

(defn operator-context-required
  "Outcome collection was attempted from a scheduled platform context."
  [msg]
  (ex-info msg {:ibuki/operator-context-required true}))

(defn operator-context-required? [e]
  (boolean (:ibuki/operator-context-required (ex-data e))))

(defn refuse-if-cron []
  (when (= "1" (env-get env-cron))
    (throw (operator-context-required
            (str "refusing to query gh from a cron/scheduled context (IBUKI_CRON=1) — the "
                 "operator's gh credential belongs to the operator's own runtime, never a "
                 "platform job (ADR-2605231525)")))))

;; ── injectable gh runner (read-only) ──────────────────────────────────────
;;
;; Contract: (gh pr-number) → the PR state string ("MERGED" | "CLOSED" | "OPEN").

#?(:clj
   (defn default-gh
     "`gh pr view <n> --json state` with the OPERATOR's own gh auth. Read-only —
     the ONLY gh subcommand this namespace can run."
     [pr]
     (let [process (requiring-resolve 'babashka.process/process)
           parse (requiring-resolve 'cheshire.core/parse-string)
           p (process ["gh" "pr" "view" (str pr) "--json" "state"]
                      {:out :string :err :string})
           res (deref p 30000 ::timeout)]
       (when (= ::timeout res)
         (throw (ex-info (str "gh pr view " pr " timed out after 30s") {:pr pr})))
       (when-not (zero? (:exit res))
         (throw (ex-info (str "gh pr view " pr " failed: " (str/trim (str (:err res))))
                         {:pr pr :exit (:exit res)})))
       (get (parse (:out res)) "state"))))

(def ^:dynamic *gh*
  "The injectable gh runner (see contract above). Rebind, or pass `:gh` in the
  options map, to stub gh in tests — no network/gh in tests."
  #?(:clj default-gh :default nil))

;; ── collect outcomes (closed vocab, never guessed) ────────────────────────

#?(:clj
   (defn collect
     "Resolve every proposal that names a PR (:pr, set by the PR agent) to an outcome
     record {:proposal-id :rule :pr :outcome}. Proposals without a PR yet are skipped
     (nothing to learn from). An unknown gh state throws — closed vocab, never guessed.
     Options: :gh."
     ([proposals-path] (collect proposals-path {}))
     ([proposals-path {:keys [gh]}]
      (refuse-if-cron)
      (let [run (or gh *gh*)]
        (into []
              (keep (fn [p]
                      (let [pr (:pr p)]
                        (when (and pr (not= 0 pr))
                          (let [state (run (long pr))
                                outcome (get state->outcome state)]
                            (when (nil? outcome)
                              (throw (ex-info
                                      (str "unknown PR state " (pr-str state) " for #" pr
                                           " (closed vocab: "
                                           (vec (sort (keys state->outcome))) ")")
                                      {:state state :pr pr})))
                            (when-not (some #{outcome} kf/outcomes)
                              (throw (ex-info "outcome outside the kaizen vocab"
                                              {:outcome outcome})))
                            {:proposal-id (:proposal-id p)
                             :rule (:rule p)
                             :pr (long pr)
                             :outcome outcome})))))
              (kf/read-proposals proposals-path))))))

;; ── write the outcomes snapshot ───────────────────────────────────────────

#?(:clj
   (defn write-outcomes
     "Write the outcomes NDJSON kaizen-feedback/read-outcomes consumes (OVERWRITE — the
     file is a SNAPSHOT of current PR states; the as-of history lives on the Datom log
     via kaizen-feedback/feedback-datoms, not in this file). Returns the record count."
     [outcome-records path]
     (let [generate (requiring-resolve 'cheshire.core/generate-string)
           f (io/file (str path))]
       (when-let [parent (.getParentFile f)]
         (.mkdirs parent))
       (spit f (apply str
                      (map #(str (generate (into (sorted-map)
                                                 {"outcome" (:outcome %)
                                                  "pr" (:pr %)
                                                  "proposalId" (:proposal-id %)
                                                  "rule" (:rule %)}))
                                 "\n")
                           outcome-records)))
       (count outcome-records))))
