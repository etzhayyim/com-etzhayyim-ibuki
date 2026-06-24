(ns ibuki.coscientist-cell
  "ibuki 息吹 co-scientist cell entry — kotodama-cell-runner contract (ADR-2605192415 §7.1).

  Registered in 50-infra/cluster/murakumo/cell-runner/cells.edn as IbukiCoscientistHeartbeatCell
  (node zebulun, cron 17 * * * *, healthz 13084). `fire` runs ONE deterministic co-scientist
  ReAct beat (ADR-2606201200 / pattern 2606091000):

      SENSE the metabolic state (free-energy budget Φ, symbiosis efficiency η, surprise) → run the
      co-scientist tournament (Generate→Reflect→Rank→Evolve→Meta-review) over charter-clean
      societal-intervention hypotheses → pre-register the top reviewed one as a DRY-RUN experiment
      → score the PRIOR beat's experiment + update the per-mechanism weights (kaizen) → append ONE
      content-addressed tx to the actor-local kotoba commit-DAG → chain verified.

  NO external I/O in the cell: the live SENSE membrane (live perception), the Murakumo meta-review
  narration (--live), and the LIVE-engine bridge stay G7/operator-gated. The returned summary is
  aggregate metabolic state (never a per-person score); outward intervention legs are
  member-principal (ADR-2606101200 G8)."
  (:require [ibuki.methods.react-loop :as rl]
            [kotoba.datom :as kd]
            #?(:clj [clojure.java.io :as io])))

#?(:clj
   (defn- actor-dir
     "20-actors/ibuki, resolved from this namespace's classpath location (runs from any cwd)."
     []
     (-> (io/resource "ibuki/coscientist_cell.cljc") io/file .getParentFile)))

#?(:clj
   (def log-default
     (delay (str (io/file (actor-dir) "data" "ibuki-coscientist.datoms.kotoba.edn")))))

#?(:clj
   (defn fire
     "One co-scientist ReAct heartbeat. Idempotent per log state (cycle = log length). The SENSE
     reading is the representative R0 membrane (live perception is G7); colony-size defaults to 3."
     ([] (fire nil))
     ([log-path]
      (let [target (str (or log-path @log-default))
            n (count (kd/read-log target))
            colony 3
            r (rl/beat {:log-path target
                        :tx-id (str "ibuki-cosci-" n) :as-of (str "as-of:" n)
                        :env-reading (rl/representative-reading n colony)
                        :colony-size colony
                        :live? false})]
        (println (str "IbukiCoscientistHeartbeatCell cycle " n
                      ": Φ=" (:phi r) " reserves=" (:reserves r)
                      " η=" (format "%.2f" (double (:eta r)))
                      " surprise=" (format "%.2f" (double (:surprise r)))
                      " → " (:mechanism r)
                      (when (:outcome-score r) (str " prior-score=" (format "%.2f" (double (:outcome-score r)))))
                      " appended=" (:appended r)
                      " head=" (some-> (:head r) (subs 0 (min 16 (count (:head r)))))))
        r))))
