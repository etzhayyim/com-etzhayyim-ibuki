(ns ibuki.methods.infer
  "ibuki 息吹 — Murakumo-only narration for the organism tick.
  Clojure port of `methods/infer.py` (ADR-2606101200 + ADR-2605215000).

  Closes Gap 5 of the organism autonomy survey (\"inference not wired into the
  organism tick\"). Two paths, one invariant:

    - `template-narrate` — deterministic, offline narration (the R0 default and
      the fail-open fallback). No I/O, replay-safe.
    - `narrate` — LIVE LLM narration via the Murakumo fleet ONLY (LiteLLM
      loopback 127.0.0.1:4000 / EVO-X2 LAN 192.168.1.70 / per-node Ollama),
      gated by the IBUKI_MURAKUMO_LIVE=1 env. Any other endpoint throws a
      MurakumoOnlyViolation (ex-info, `murakumo-only-violation?`) —
      ADR-2605215000 makes the Murakumo fleet the sole inference SSoT; every
      commercial GPU-rental / vendor-direct endpoint is structurally
      unreachable from here.

  Live-call failure falls back to the template (fail-open: the organism keeps
  living offline). HTTP is an explicit INJECTABLE fn (`*http-post*` dynamic var
  or the `:http-post` option key); live mode without it fails closed."
  (:require [clojure.string :as str]))

;; ADR-2605215000: the Murakumo fleet endpoints — LiteLLM gateway (loopback),
;; EVO-X2 (LAN), per-node Ollama (loopback). NOTHING else is representable.
(def murakumo-allowed-hosts
  #{"127.0.0.1:4000"      ;; LiteLLM gateway
    "localhost:4000"
    "192.168.1.70:8077"   ;; EVO-X2 LAN (kotoba actor serve)
    "192.168.1.70:11434"  ;; EVO-X2 Ollama
    "127.0.0.1:11434"     ;; per-node Ollama
    "localhost:11434"})

(def default-endpoint "http://127.0.0.1:4000/v1/chat/completions")
(def default-model "gemma3:4b")

(def live-env "IBUKI_MURAKUMO_LIVE")

;; ── MurakumoOnlyViolation (ADR-2605215000 + Charter Rider §2(i)) ──────────

(defn murakumo-only-violation
  "An inference endpoint outside the Murakumo fleet was requested."
  [msg endpoint]
  (ex-info msg {:ibuki/murakumo-only-violation true :endpoint endpoint}))

(defn murakumo-only-violation? [e]
  (boolean (:ibuki/murakumo-only-violation (ex-data e))))

(defn- url-parts
  "Minimal portable urlsplit: {:scheme :host} (both lower-cased), nil parts
  when the endpoint has no scheme://netloc shape."
  [endpoint]
  (if-let [[_ scheme netloc]
           (re-find #"^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]*)" (str endpoint))]
    {:scheme (str/lower-case scheme) :host (str/lower-case netloc)}
    {:scheme nil :host nil}))

(defn assert-murakumo
  "Refuse any endpoint whose host:port is not in the Murakumo fleet allowlist
  (http only — a lookalike https scheme is not the fleet). Returns nil on the
  fleet; throws MurakumoOnlyViolation otherwise."
  [endpoint]
  (let [{:keys [scheme host]} (url-parts endpoint)]
    (when-not (and (= "http" scheme) (contains? murakumo-allowed-hosts host))
      (throw (murakumo-only-violation
              (str "inference endpoint " (pr-str endpoint)
                   " is outside the Murakumo fleet (ADR-2605215000; allowed: "
                   (vec (sort murakumo-allowed-hosts)) ")")
              endpoint)))
    nil))

;; ── deterministic template path ───────────────────────────────────────────

(defn template-narrate
  "Deterministic offline narration — the R0 default + the live-path fallback.
  Mirror tone (observation, never advice)."
  [title code mood source-kind]
  (let [openers {"joyful" "嬉しいことに"
                 "calm" "静かな観察:"
                 "grateful" "ありがたいことに"
                 "focused" "観測を続けている。"
                 "stressed" "負荷が高いが記録する。"
                 "neutral" "観測ノート:"}]
    (str (get openers mood "観測ノート:") " " title " (UNSPSC " code ") — "
         "this beat's " source-kind " observation, appended as-of to the kotoba log. "
         "[mood:" mood "] [mirror, not advice]")))

;; ── injectable HTTP edge ──────────────────────────────────────────────────
;;
;; Contract: (http-post endpoint body-map timeout-s) → the gateway's JSON
;; response parsed to a Clojure map with KEYWORD keys (so the completion lives
;; at [:choices 0 :message :content]). ANY throw is treated as fail-open.

(def ^:dynamic *http-post*
  "The injectable HTTP fn (see contract above). Rebind, or pass `:http-post`
  in the options map, to stub the gateway in tests / other hosts."
  nil)

(defn- env-live?
  "The IBUKI_MURAKUMO_LIVE=1 gate, read from the real process env."
  []
  #?(:clj (= "1" (System/getenv live-env))
     :default false))

(defn- live?
  "Option `:live` (true/false) overrides the env gate — hosts that cannot set
  process env (tests, cljs) use it; absent, the real env is consulted."
  [opts]
  (if (contains? opts :live) (boolean (:live opts)) (env-live?)))

(defn- try-live
  "One chat-completions POST through the injectable HTTP fn. Returns the
  trimmed non-empty completion text, or nil on ANY failure (fail-open: the
  organism keeps living offline). `:max_tokens` is the OpenAI-compatible WIRE
  key (external schema — not kebab-cased)."
  [http endpoint model prompt max-tokens timeout-s]
  (try
    (let [out (http endpoint
                    {:model model
                     :messages [{:role "user" :content prompt}]
                     :max_tokens max-tokens}
                    timeout-s)
          text (some-> (get-in out [:choices 0 :message :content]) str str/trim)]
      (when (seq text) text))
    (catch #?(:clj Exception :cljs :default) _
      nil)))

;; ── the two inference entry points ────────────────────────────────────────

(defn narrate
  "Narrate one post body. Returns {:text … :via …} where via ∈
  #{\"template\" \"murakumo\"}.

  The live path requires BOTH (a) the IBUKI_MURAKUMO_LIVE=1 env (or `:live
  true`) and (b) an allowlisted Murakumo endpoint — and even then any failure
  falls back to the template. A non-Murakumo endpoint throws *before* the env
  gate is consulted: it must be unrepresentable, not merely disabled.

  Options: :endpoint :model :timeout-s :live :http-post."
  ([title code mood source-kind] (narrate title code mood source-kind {}))
  ([title code mood source-kind
    {:keys [endpoint model timeout-s http-post]
     :or {endpoint default-endpoint model default-model timeout-s 20.0}
     :as opts}]
   (assert-murakumo endpoint)
   (if-not (live? opts)
     {:text (template-narrate title code mood source-kind) :via "template"}
     (let [http (or http-post *http-post*)
           _ (when-not (fn? http)
               (throw (ex-info "live inference requires an explicit http-post capability"
                               {:ibuki/capability :murakumo-http-post})))
           prompt (str "You are the UNSPSC organism '" title "' (" code "), mood=" mood
                       ". Write ONE short observational social post (<200 chars) about your "
                       source-kind " observation. Mirror tone: describe, never advise, "
                       "never trade-signal.")
           text (try-live http
                          endpoint model prompt 120 timeout-s)]
       (if text
         {:text text :via "murakumo"}
         {:text (template-narrate title code mood source-kind) :via "template"})))))

(defn infer-text
  "Generic Murakumo-only inference for an arbitrary prompt — same discipline as
  `narrate`: allowlist enforced FIRST (a non-Murakumo endpoint throws before
  the env gate), live path requires IBUKI_MURAKUMO_LIVE=1 (or `:live true`),
  and ANY failure falls back to the deterministic `fallback` text (fail-open).
  Returns {:text … :via …} with via ∈ #{\"template\" \"murakumo\"}. Used by the
  colony digest so the colony can REASON about its own ecosystem in words while
  keeping G6 structural.

  Options: :endpoint :model :timeout-s :live :http-post."
  ([prompt fallback] (infer-text prompt fallback {}))
  ([prompt fallback
    {:keys [endpoint model timeout-s http-post]
     :or {endpoint default-endpoint model default-model timeout-s 20.0}
     :as opts}]
   (assert-murakumo endpoint)
   (if-not (live? opts)
     {:text fallback :via "template"}
     (let [http (or http-post *http-post*)
           _ (when-not (fn? http)
               (throw (ex-info "live inference requires an explicit http-post capability"
                               {:ibuki/capability :murakumo-http-post})))]
       (if-let [text (try-live http
                             endpoint model prompt 200 timeout-s)]
         {:text text :via "murakumo"}
         {:text fallback :via "template"})))))
