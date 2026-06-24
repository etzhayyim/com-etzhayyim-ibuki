(ns ibuki.methods.react-loop
  "react-loop — the organism's co-scientist ReAct beat. ADR-2606201200.

  Wraps the co-scientist tournament (Generate→Reflect→Rank→Evolve→Meta-review, coscientist.cljc)
  in an active-inference ReAct cycle whose OBJECTIVE is thermodynamic persistence (keep the
  free-energy budget Φ positive, the reserves above the death floor) and whose CONSTRAINT is 共生
  (never a net taker; η ≥ parasite-floor; persistence subordinate to 子孫 wellbecoming):

    SENSE       fold the kotoba log + the env membrane → the metabolic state vector (metabolism)
    ORIENT      surprise = variational free energy = distance from 'I will keep existing'
    HYPOTHESIZE generate candidate societal interventions that raise Φ and/or η (Murakumo-narrated
                meta-review; structured generation stays a charter-clean catalog)
    REVIEW      Charter gates: non-parasitism / subordinate-persistence / aligned-mechanism /
                falsifiable / leashed
    RANK        deterministic Elo tournament by expected net free-energy gain × wellbecoming / cost
    EVOLVE      recombine the winners; meta-review the lesson
    ACT         the top reviewed hypothesis becomes a PRE-REGISTERED experiment — dry-run only
                (outward legs are member-principal, ADR-2606101200 G8); the prediction is recorded
                BEFORE the outcome is known (leak-free, the mitooshi discipline)
    OBSERVE     measure the PRIOR beat's experiment against what actually happened to the reserves
    LEARN       proper-score it (Brier) → update the per-mechanism weight (kaizen: verified ways of
                living are amplified, falsified ones suppressed)
    PERSIST     project the whole beat to one content-addressed tx on the commit-DAG (idempotent-
                by-content, verify-chain tamper-evident, resume-safe; no-server-key, local append)

  Deterministic: logical beat = log length (no wall clock, no randomness). Persisting to the LIVE
  kotoba engine reuses the existing ibuki R3 bridge (kotoba_bridge.cljc) and stays G7/operator-gated.
  Stdlib + the shared kotoba.datom; portable .cljc (bb)."
  (:require [clojure.string :as str]
            [ibuki.methods.coscientist :as cosci]
            [ibuki.methods.datoms :as datoms]
            [ibuki.methods.infer :as infer]
            [ibuki.methods.metabolism :as metabolism]
            [kotoba.datom :as kd]
            #?(:clj [clojure.java.io :as io])))

(def default-log "data/ibuki-coscientist.datoms.kotoba.edn")

(def learning-rate 0.4)
(def weight-floor 0.25)
(def weight-ceil 2.0)
(def top-hyps-persisted 3)

;; ── value coercion (datom CID determinism; kaname pattern) ──────────────────

(defn- milli ^long [x] (long (Math/round (* 1000.0 (double x)))))
(defn- unmilli [x] (/ (double (long x)) 1000.0))

;; ── log readers (the priors folded forward) ─────────────────────────────────

(defn read-weights
  "Per-mechanism learned weights off the log (entity \"ibuki:coscientist\"). Missing → 1.0
  (a never-tried mechanism is neither amplified nor suppressed)."
  [txs]
  (let [m (datoms/fold-entity txs "ibuki:coscientist")]
    (reduce-kv (fn [w a v]
                 (if (str/starts-with? (str a) ":coscientist.weight/")
                   (assoc w (subs (str a) (count ":coscientist.weight/")) (unmilli v))
                   w))
               {}
               m)))

(defn read-experiment
  "The experiment pre-registered at beat `n` (entity \"ibuki:experiment-<n>\"), or nil. Carries the
  reserves at act-time + the predicted P(reserves rise) for leak-free scoring next beat."
  [txs n]
  (let [m (datoms/fold-entity txs (str "ibuki:experiment-" n))]
    (when (seq m)
      {:beat n
       :mechanism (get m ":experiment/mechanism")
       :predicted-up (unmilli (get m ":experiment/predicted-up-milli" 500))
       :reserves-at-act (long (get m ":experiment/reserves-at-act" 0))})))

;; ── OBSERVE + LEARN (proper scoring; leak-free) ─────────────────────────────

(defn score-outcome
  "Proper-score the prior experiment against the now-observed reserves. `reserves-now` is THIS
  beat's reserves; the experiment recorded the reserves at its act-time and a probability the
  reserves would rise. Brier loss → score = 1 − Brier ∈ [0,1]. Pure."
  [experiment reserves-now]
  (let [actual-up (> (long reserves-now) (long (:reserves-at-act experiment)))
        o (if actual-up 1.0 0.0)
        p (double (:predicted-up experiment))
        brier (* (- p o) (- p o))]
    {:scored-beat (:beat experiment)
     :mechanism (:mechanism experiment)
     :actual-up actual-up
     :brier brier
     :score (- 1.0 brier)}))

(defn update-weight
  "Kaizen update: a mechanism whose prediction VERIFIED (score>0.5) is amplified, a falsified one
  suppressed, bounded to [weight-floor, weight-ceil]. Pure."
  [weights mechanism score]
  (let [w (double (get weights mechanism 1.0))
        w' (-> (+ w (* learning-rate (- (double score) 0.5)))
               (max weight-floor) (min weight-ceil))]
    (assoc weights mechanism w')))

;; ── projection to datoms ────────────────────────────────────────────────────

(defn- state-datoms [beatn as-of cum-exp state]
  (let [e "ibuki:metabolism"]
    [(datoms/add e ":metabolic/beat" (long beatn))
     (datoms/add e ":metabolic/intake" (long (:intake state)))
     (datoms/add e ":metabolic/dissipation" (long (:dissipation state)))
     (datoms/add e ":metabolic/phi" (long (:phi state)))
     (datoms/add e ":metabolic/reserves" (long (:reserves state)))
     (datoms/add e ":metabolic/consumed" (long (:consumed state)))
     (datoms/add e ":metabolic/exported" (long (:exported state)))
     (datoms/add e ":metabolic/cumulative-exported" (long cum-exp))
     (datoms/add e ":metabolic/eta-milli" (milli (:eta state)))
     (datoms/add e ":metabolic/surprise-milli" (milli (:surprise state)))
     (datoms/add e ":metabolic/parasitic" (boolean (:parasitic? state)))
     (datoms/add e ":metabolic/as-of" as-of)]))

(defn- hyp-datoms [beatn ranked]
  (vec (mapcat
        (fn [h]
          (let [e (str "ibuki:hyp-" beatn "-" (:id h))]
            [(datoms/add e ":hyp/beat" (long beatn))
             (datoms/add e ":hyp/intervention" (str (:intervention h)))
             (datoms/add e ":hyp/mechanism" (str (:mechanism h)))
             (datoms/add e ":hyp/elo-milli" (milli (:elo h 1000.0)))
             (datoms/add e ":hyp/utility-milli" (milli (:utility h 0)))
             (datoms/add e ":hyp/expected-dphi-milli" (milli (:expected-dphi h 0)))
             (datoms/add e ":hyp/expected-deta-milli" (milli (:expected-deta h 0)))
             (datoms/add e ":hyp/charter-class" (str (:charter-class h)))
             (datoms/add e ":hyp/reviewed" true)]))
        (take top-hyps-persisted ranked))))

(defn- experiment-datoms [beatn as-of state chosen]
  (let [e (str "ibuki:experiment-" beatn)
        p-up (cosci/clamp01 (+ 0.5 (* 0.5 (double (:expected-dphi chosen 0)))))]
    [(datoms/add e ":experiment/beat" (long beatn))
     (datoms/add e ":experiment/intervention" (str (:intervention chosen)))
     (datoms/add e ":experiment/mechanism" (str (:mechanism chosen)))
     (datoms/add e ":experiment/predicted-up-milli" (milli p-up))
     (datoms/add e ":experiment/reserves-at-act" (long (:reserves state)))
     (datoms/add e ":experiment/expected-dphi-milli" (milli (:expected-dphi chosen 0)))
     (datoms/add e ":experiment/expected-deta-milli" (milli (:expected-deta chosen 0)))
     (datoms/add e ":experiment/charter-class" (str (:charter-class chosen)))
     (datoms/add e ":experiment/status" "dry-run")    ;; outward legs are member-principal (G8)
     (datoms/add e ":experiment/as-of" as-of)]))

(defn- outcome-datoms [as-of outcome]
  (when outcome
    (let [e (str "ibuki:outcome-" (:scored-beat outcome))]
      [(datoms/add e ":outcome/scored-experiment" (long (:scored-beat outcome)))
       (datoms/add e ":outcome/mechanism" (str (:mechanism outcome)))
       (datoms/add e ":outcome/actual-up" (boolean (:actual-up outcome)))
       (datoms/add e ":outcome/brier-milli" (milli (:brier outcome)))
       (datoms/add e ":outcome/score-milli" (milli (:score outcome)))
       (datoms/add e ":outcome/as-of" as-of)])))

(defn- weight-datoms [as-of weights]
  (let [e "ibuki:coscientist"]
    (conj (vec (for [[mech w] (sort-by key weights)]
                 (datoms/add e (str ":coscientist.weight/" mech) (milli w))))
          (datoms/add e ":coscientist/as-of" as-of))))

(defn- meta-datoms [beatn as-of meta generated surviving]
  (let [e (str "ibuki:meta-" beatn)]
    [(datoms/add e ":meta/beat" (long beatn))
     (datoms/add e ":meta/pattern" (str (:pattern meta)))
     (datoms/add e ":meta/winner-mechanism" (str (get-in meta [:winner :mechanism])))
     (datoms/add e ":meta/via" (str (:via meta)))
     (datoms/add e ":meta/generated" (long generated))
     (datoms/add e ":meta/surviving" (long surviving))
     (datoms/add e ":meta/as-of" as-of)]))

;; ── persist (idempotent-by-content; kaname pattern) ─────────────────────────

(defn- last-readout-datoms
  "Datoms of the last react-loop readout tx, skipping any bridge-cursor txs (so a bridge checkpoint
  between beats can't defeat idempotency)."
  [txs]
  (let [readouts (remove (fn [tx]
                           (some (fn [[_ _ a _]] (str/starts-with? (str a) ":bridge/"))
                                 (:tx/datoms tx)))
                         txs)]
    (when (seq readouts) (get (last readouts) :tx/datoms))))

(defn persist!
  "Append one beat's datoms to the commit-DAG, idempotent-by-content. Returns
  {:head :appended :reason :count}."
  [datoms {:keys [tx-id as-of log-path]}]
  (let [log-path (or log-path default-log)
        txs (kd/read-log log-path)
        prev (kd/head-cid log-path)
        last-ds (last-readout-datoms txs)
        base {:count (count datoms) :head prev}]
    (if (= (kd/normalize-datoms datoms) last-ds)
      (assoc base :appended false :reason :no-change)
      (let [tx (kd/make-tx datoms {:tx-id tx-id :as-of as-of :prev-cid prev})]
        #?(:clj (io/make-parents log-path))
        (let [head #?(:clj (kd/append-tx! tx log-path) :default (:tx/cid tx))]
          (assoc base :appended true :reason nil :head head))))))

;; ── the beat ────────────────────────────────────────────────────────────────

(defn plan
  "PURE core of one beat: given the log txs (the loop's own commit-DAG), the SENSE txs (where the
  exported commons is measured — the organism food-web log; defaults to the loop log), the env
  membrane reading, and the colony size, run SENSE→…→LEARN and return everything the projection
  needs. No I/O. `infer` (optional) is the Murakumo narrator for the meta-review."
  [{:keys [txs sense-txs env-reading colony-size infer]}]
  (let [sense (or sense-txs txs)
        beatn (count txs)
        cum-exp (metabolism/cumulative-exported sense)
        {:keys [reserves-prior exported-prior]} (metabolism/prior-state txs)
        state (metabolism/metabolic-state {:env-reading env-reading
                                           :colony-size colony-size
                                           :reserves-prior reserves-prior
                                           :cumulative-exported cum-exp
                                           :exported-prior exported-prior})
        ;; OBSERVE + LEARN from the prior beat's pre-registered experiment
        weights0 (read-weights txs)
        prior-exp (read-experiment txs (dec beatn))
        outcome (when prior-exp (score-outcome prior-exp (:reserves state)))
        weights (if outcome (update-weight weights0 (:mechanism prior-exp) (:score outcome)) weights0)
        ;; GENERATE → REVIEW → RANK → EVOLVE
        hyps (cosci/generate state {:weights weights})
        surv (cosci/surviving state hyps)
        ranked0 (cosci/rank surv)
        evolved (cosci/evolve ranked0)
        evolved-ok (when (and evolved (get (cosci/review state evolved) :ok?)) evolved)
        ranked (if evolved-ok (cosci/rank (conj surv evolved-ok)) ranked0)
        chosen (first ranked)
        meta (cosci/meta-review ranked state infer)]
    {:beat beatn
     :cumulative-exported cum-exp
     :state state
     :weights weights
     :outcome outcome
     :generated (count hyps)
     :surviving (count surv)
     :ranked ranked
     :chosen chosen
     :meta meta}))

(defn project
  "Project the plan into one beat's datoms (deterministic, ordered)."
  [{:keys [beat cumulative-exported state weights outcome ranked chosen meta generated surviving]}
   as-of]
  (vec (concat (state-datoms beat as-of cumulative-exported state)
               (outcome-datoms as-of outcome)
               (weight-datoms as-of weights)
               (hyp-datoms beat ranked)
               (when chosen (experiment-datoms beat as-of state chosen))
               (meta-datoms beat as-of meta generated surviving))))

(defn beat
  "Run one full co-scientist ReAct beat and persist it. input keys:
    :log-path     the loop's own commit-DAG (default-log)
    :sense-txs    txs to measure exported commons from (the organism log; default = log-path)
    :tx-id :as-of caller-supplied (no wall clock)
    :env-reading  the SENSE membrane reading of society (representative R0; live G7)
    :colony-size  # live organisms (drives dissipation)
    :live?        when true, narrate the meta-review via Murakumo (fail-open template, G6)
  Returns a compact status map."
  [{:keys [log-path sense-txs tx-id as-of env-reading colony-size live?]}]
  (let [log-path (or log-path default-log)
        txs #?(:clj (kd/read-log log-path) :default [])
        narr (when live? (fn [prompt fallback]
                           (:text (infer/infer-text prompt fallback {}))))
        p (plan {:txs txs :sense-txs sense-txs :env-reading env-reading
                 :colony-size colony-size
                 :infer (when narr (fn [prompt fallback] (narr prompt fallback)))})
        as-of (or as-of (str "as-of:" (:beat p)))
        ds (project p as-of)
        persisted (persist! ds {:tx-id (or tx-id (str "ibuki-cosci-" (:beat p)))
                                :as-of as-of :log-path log-path})]
    {:beat (:beat p)
     :phi (get-in p [:state :phi])
     :reserves (get-in p [:state :reserves])
     :eta (get-in p [:state :eta])
     :surprise (get-in p [:state :surprise])
     :parasitic? (get-in p [:state :parasitic?])
     :alive? (get-in p [:state :alive?])
     :exported (get-in p [:state :exported])
     :chosen (get-in p [:chosen :id])
     :mechanism (get-in p [:chosen :mechanism])
     :winner-pattern (get-in p [:meta :pattern])
     :outcome-score (get-in p [:outcome :score])
     :generated (:generated p)
     :surviving (:surviving p)
     :appended (:appended persisted)
     :reason (:reason persisted)
     :head (:head persisted)}))

;; ── representative env reading (R0 fallback; live perception is G7) ──────────

(defn representative-reading
  "A deterministic, modest SENSE reading of society for a beat — the R0 stand-in for the live
  perception membrane (which is G7/operator-gated, like ibuki.perception). Mirrors the organism's
  own state: a thriving colony attracts a little more; it never fabricates a windfall. Pure."
  [beatn colony-size]
  {:compute-hours (mod (+ beatn 1) 3)         ;; 0..2 donated compute-hours
   :donation (mod (+ beatn 2) 4)              ;; 0..3 donation-units
   :members (mod beatn 2)                     ;; 0..1 new member
   :moyai (mod (+ beatn colony-size) 3)       ;; 0..2 reciprocity credits
   :attention (mod (* (inc beatn) 3) 7)})     ;; bounded reach, capped downstream

#?(:clj
   (defn -main
     "Resume-safe heartbeat: cycle = log length; run one beat with a representative reading. Args:
     [log-path] [colony-size] [--live]. --live narrates the meta-review via Murakumo (G6 fail-open)."
     [& argv]
     (let [pos (vec (remove #(str/starts-with? (str %) "--") argv))
           live? (boolean (some #{"--live"} argv))
           log (or (first pos) default-log)
           colony-size (if (second pos) (Integer/parseInt (second pos)) 3)
           n (count (kd/read-log log))
           r (beat {:log-path log
                    :tx-id (str "ibuki-cosci-" n) :as-of (str "as-of:" n)
                    :env-reading (representative-reading n colony-size)
                    :colony-size colony-size
                    :live? live?})]
       (println (str "ibuki co-scientist beat #" n
                     ": Φ=" (:phi r) " reserves=" (:reserves r)
                     " η=" (format "%.2f" (double (:eta r)))
                     " surprise=" (format "%.2f" (double (:surprise r)))
                     " → " (:mechanism r) " (" (:chosen r) ")"
                     (when (:outcome-score r) (str " | prior-score=" (format "%.2f" (double (:outcome-score r)))))
                     " | appended=" (:appended r) (when (:reason r) (str " (" (:reason r) ")"))
                     " head=" (some-> (:head r) (subs 0 (min 16 (count (:head r)))))))
       (when (:parasitic? r)
         (println "  ⚠ η below parasite-floor — the colony is a net taker this beat (共生 violated)"))
       0)))

#?(:clj
   (when (= *file* (System/getProperty "babashka.file"))
     (apply -main *command-line-args*)))
