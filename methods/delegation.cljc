(ns ibuki.methods.delegation
  "delegation — the organism lives under a revocable LEASH, not a held key.
  ADR-2606111400 + ADR-2606101200 §委任. Clojure port of `methods/delegation.py`.

  For an autonomous life the right credential model is neither a held root key (prohibited —
  the platform/actor must hold no member key, ADR-2605231525) nor a per-beat human presence
  (a passkey can't be touched every beat). It is a **scoped, expiring, revocable capability
  delegated to the organism's OWN runtime** — the kotoba CACAO / DelegationChain model
  (`kotoba-auth`: capability `datom:transact`, a graph CID resource, `exp`, `aud`, `nonce`).

  How kotoba's CACAO actually binds (verified against `kotoba-auth::delegation`, 2026-06-11):
    - `aud` is the kotoba NODE's DID (the audience the capability is presented TO — the
      server checks `cacao.p.aud == operator_did`). It is NOT the organism's DID. The
      organism is the BEARER that holds + presents the bytes; the capability is scoped to
      (node, graph, capability, expiry), not bound to a named delegatee.
    - `write_author = the ISSUING MEMBER` (the server returns the issuer DID as the
      principal). So the colony's autonomous writes are ATTRIBUTED to the member who leashed
      it — accountability flows to the consenting human, time-bounded + revocable. That is
      the point: autonomy WITH a named, on-the-record human principal (相互監視 / 共生 by
      consent), never an anonymous self-acting agent.

  Division of trust (this is the whole point):
    - ISSUANCE is a human act: a member, present with their passkey/wallet, SIGNS a CACAO
      granting `datom:transact @ graph:ibuki, exp:+Nd`, audience = the node DID. ibuki holds
      NO member key and does NOT sign — issuance happens in the member's own runtime (the
      `tools/issue_delegation.py` member tool; see OPERATIONS.md). The bundle the member
      hands ibuki is `{cacao_b64, aud, capability, graph, exp, nonce}` — `cacao_b64` is the
      member-signed CBOR (opaque to ibuki), the rest is sidecar metadata ibuki reads to gate
      itself.
    - INVOCATION is the organism's autonomous act: each push it PRESENTS the opaque
      `cacao_b64`. The kotoba server verifies the issuer's Ed25519-style signature +
      capability + graph + aud + expiry and sets write_author = issuer; no invoker signature
      is required, so ibuki presenting bytes is enough (and stays stdlib-only — ibuki never
      does crypto).
    - REVOCATION is consent withdrawn: when `exp` passes, ibuki self-disables the delegated
      path and falls back (local log only) until a member re-issues. Stop re-issuing → the
      organism quietly retires. Autonomy is LEASED, never owned.

  Stdlib only. Deterministic: expiry is checked against a caller-supplied `:now-epoch` (no
  wall clock inside any method — the live push boundary supplies it). Present-only: this
  namespace contains NO signature primitive of any kind. Portable .cljc."
  (:refer-clojure :exclude [load])
  (:require [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])))

(def capability
  "The only capability ibuki's autonomous loop ever needs."
  "datom:transact")

(def required-keys ["cacao_b64" "aud" "capability" "graph" "exp" "nonce"])

;; ── the refusal marker (delegation.DelegationError) ─────────────────────────

(defn delegation-error
  "A delegation bundle is malformed or used outside its scope."
  [msg]
  (ex-info msg {:ibuki/delegation-error true}))

(defn delegation-error? [e]
  (boolean (:ibuki/delegation-error (ex-data e))))

;; ── load (member-issued bundle sidecar; fail-open when absent) ──────────────

#?(:clj
   (defn load
     "Load a member-issued delegation bundle (JSON sidecar). Returns nil if absent — the
     organism then falls back to local-log-only (fail-open, never crashes)."
     [path]
     (let [f (io/file (str path))]
       (when (.exists f)
         (let [parse (requiring-resolve 'cheshire.core/parse-string)
               bundle (parse (slurp f))
               missing (vec (remove #(contains? bundle %) required-keys))]
           (when (seq missing)
             (throw (delegation-error
                     (str "delegation bundle missing keys " missing))))
           (when (not= (get bundle "capability") capability)
             (throw (delegation-error
                     (str "delegation capability " (pr-str (get bundle "capability"))
                          " != " (pr-str capability)
                          " (ibuki's autonomous loop only ever needs datom:transact)"))))
           (when-not (str/starts-with? (str (get bundle "aud" "")) "did:")
             (throw (delegation-error
                     (str "delegation audience must be a DID (the kotoba node it is "
                          "presented to — kotoba checks cacao.aud == operator_did)"))))
           (when (contains? #{nil ""} (get bundle "nonce"))
             (throw (delegation-error
                     "delegation must carry a nonce (replay protection)")))
           bundle)))))

;; ── usable? (the leash, checked — pure fn of bundle metadata) ───────────────

(defn- ->long [x]
  (if (number? x)
    (long x)
    #?(:clj (Long/parseLong (str x))
       :default (js/parseInt (str x) 10))))

(defn usable?
  "May the organism present this delegation to write `:graph` at `:now-epoch`? Pure
  function of the bundle metadata — the same answer before and after a restart (the leash,
  checked). `:now-epoch` is supplied by the caller (the live push boundary); no wall clock
  here. Returns [ok? why]."
  [bundle {:keys [now-epoch graph]}]
  (cond
    (nil? bundle)
    [false "no delegation (local-log-only until a member issues one)"]

    (not= (get bundle "graph") graph)
    [false (str "delegation scoped to graph " (pr-str (get bundle "graph"))
                ", not " (pr-str graph)
                " — a capability is never used outside its resource")]

    (>= now-epoch (->long (get bundle "exp")))
    [false (str "delegation expired (exp " (get bundle "exp") " <= now " now-epoch ") — "
                "consent must be renewed; the organism falls back to local log")]

    :else
    [true (str "usable (expires " (get bundle "exp") ", aud " (get bundle "aud") ")")]))

;; ── audience (the node, not the organism) ───────────────────────────────────

(defn audience
  "The DID this capability is presented TO — the kotoba node's operator DID (kotoba checks
  `cacao.p.aud == operator_did`). NOT the organism's DID; the organism is the bearer."
  [bundle]
  (get bundle "aud"))

;; ── issuance template (the shape the MEMBER signs — ibuki never signs) ──────

(defn issuance-template
  "The CACAO PAYLOAD a member must sign to issue the delegation — emitted for the member's
  OWN signing runtime (ibuki does NOT sign; this is just the shape). The member's tool
  (`tools/issue_delegation.py`) turns this into the member-signed `cacao_b64` and writes
  the bundle. Mirrors kotoba_auth::CacaoPayload EXACTLY (verified 2026-06-11):
    - `aud` is the NODE DID (audience), not the organism — kotoba checks aud == operator_did;
    - `resources` is the SIWE form: TWO entries, `kotoba://can/<cap>` + `kotoba://graph/<cid>`
      (NOT a `?capability=` query string — that form is never parsed);
    - `iat`/`exp` are UTC ISO-8601 strings (`2026-07-11T00:00:00Z`);
    - write_author resolves to `iss` (the member) — the on-the-record human principal."
  [{:keys [member-did node-did graph-cid exp-iso nonce-hex]}]
  {"iss" member-did                  ;; the member (signer) — the on-record write principal
   "aud" node-did                    ;; the kotoba node (audience the capability is used at)
   "exp" exp-iso                     ;; consent has a horizon; renewal = re-consent
   "nonce" nonce-hex                 ;; replay protection (kotoba requires non-empty)
   "version" "1"
   "resources" [(str "kotoba://can/" capability)
                (str "kotoba://graph/" graph-cid)]
   "_note" (str "member signs this with their OWN key (passkey/wallet) in their own runtime; "
                "ibuki holds no key and never signs — present-only (ADR-2605231525). "
                "kotoba attributes the write to iss (the member) — accountability by consent.")})
