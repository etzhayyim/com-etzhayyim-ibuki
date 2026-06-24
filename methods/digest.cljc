(ns ibuki.methods.digest
  "digest — the colony REASONS about its own ecosystem and reports to humanity. ADR-2606101800.
  Clojure port of `methods/digest.py`.

  The original ask was an organism that *infers*; the ecosystem waves gave the colony a rich
  log-derived state (health, web, symbiosis pool, quorum phenotype). This module closes the
  \"reason + speak\" loop at the COLONY level: it assembles that state from the log, narrates it
  in human-readable words via the Murakumo fleet ONLY (infer-text — allowlist enforced,
  fail-open to a deterministic template), and emits a `:digest/*` DRY-RUN post — the colony's
  report to humanity on the symbiosis (黒カビ→クエン酸→人類).

    - `assemble`        — structured colony state (health verdict + eco-maturity + commons
                          offered to humanity + quorum history), single pass, log-derived,
                          deterministic.
    - `template-digest` — the deterministic human-readable fallback (no I/O).
    - `narrate`         — Murakumo-only narration (G6; via ∈ #{template, murakumo}).
    - `digest-datoms`   — a `:digest/*` post, `:digest/status :dry-run` ONLY (G8; :published
                          is unrepresentable, exactly like organism posts). It carries no
                          advice — a mirror report.

  Stdlib only. Deterministic (logical beat; no wall clock). Append-only."
  (:require [ibuki.methods.datoms :as datoms]
            [ibuki.methods.ecosystem :as ecosystem]
            [ibuki.methods.health :as health]
            [ibuki.methods.infer :refer [infer-text]]
            [ibuki.methods.quorum :as quorum]
            [ibuki.methods.symbiosis :as symbiosis]))

(defn- round3
  "Python round(x, 3) parity for the bounded [0,1] evenness readout."
  [x]
  #?(:clj (/ (Math/round (* (double x) 1000.0)) 1000.0)
     :cljs (/ (js/Math.round (* x 1000.0)) 1000.0)))

(defn assemble
  "The colony's self-state from the log alone — the substance the digest reasons over."
  [txs]
  (let [rep (health/audit txs)
        pool (symbiosis/commons-pool txs)
        web (ecosystem/web-report txs)
        qh (quorum/quorum-history txs)]
    {:organisms (get-in rep [:colony :count])
     :healthy (:healthy rep)
     :findings (vec (sort (distinct (map :rule (:findings rep)))))
     :eco-maturity (round3 (get-in rep [:colony :eco-maturity] 0.0))
     :niche-population (get-in rep [:colony :niche-population] {})
     :commons-offered (:offered pool)
     :commons-drawn (:drawn pool)
     :commons-available (:available pool)
     :commons-by-source (get web :commons-by-source {})
     :quorum-states (:states qh)
     :fruiting-nutrient-total (:fruiting-nutrient-total qh)}))

(defn template-digest
  "Deterministic, human-readable colony report — the offline default + fail-open fallback.
  Mirror tone: it describes the symbiosis, never advises."
  [state]
  (let [health-word (if (:healthy state)
                      "healthy"
                      (str "attending to " (:findings state)))
        fruited (get (:quorum-states state) ":flourishing" 0)]
    (str "息吹 colony report: " (:organisms state) " organisms, " health-word "; "
         "ecological maturity " (:eco-maturity state) ". "
         "The web has offered " (:commons-offered state) " nutrient of commons to humanity "
         "(" (:commons-available state) " still available to draw); fruited " fruited " times. "
         "A mirror of where the colony's life became a gift — no advice.")))

(defn narrate
  "Narrate the colony state via Murakumo-only (G6), fail-open to the template.
  Returns {:text … :via …}."
  [state {:keys [beat]}]
  (let [_ beat
        prompt (str "You are 息吹 (ibuki), an artificial-organism COLONY that lives on an "
                    "append-only log and, as a byproduct of living, refines a commons gift for "
                    "humanity (like black mould excreting citric acid). Write ONE short "
                    "observational report (<280 chars) of your current state. Mirror tone: "
                    "describe, never advise. State: " state ".")]
    (infer-text prompt (template-digest state))))

(defn digest-datoms
  "A `:digest/*` colony report post. `:digest/status` is `:dry-run` ONLY (G8; :published is
  unrepresentable). Aggregate colony state, never a per-organism verdict."
  [state narration {:keys [beat as-of]}]
  (let [e (str "digest-" beat)]
    [(datoms/add e ":digest/text" (:text narration))
     (datoms/add e ":digest/via" (str ":" (:via narration)))
     (datoms/add e ":digest/status" ":dry-run")
     (datoms/add e ":digest/organisms" (:organisms state))
     (datoms/add e ":digest/healthy" (:healthy state))
     (datoms/add e ":digest/eco-maturity" (:eco-maturity state))
     (datoms/add e ":digest/commons-offered" (:commons-offered state))
     (datoms/add e ":digest/commons-available" (:commons-available state))
     (datoms/add e ":digest/beat" beat)
     (datoms/add e ":digest/as-of" as-of)]))

(defn make
  "Assemble → narrate → datoms in one call (the autorun/fleet entry point)."
  [txs {:keys [beat as-of]}]
  (let [state (assemble txs)
        narration (narrate state {:beat beat})]
    {:state state
     :narration narration
     :datoms (digest-datoms state narration {:beat beat :as-of as-of})}))
