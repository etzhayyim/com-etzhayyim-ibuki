(ns ibuki.methods.kotoba-bridge
  "ibuki 息吹 — R3: push the local tx log into the LIVE kotoba engine.
  Clojure port of `methods/kotoba_bridge.py` (ADR-2606101200 §R3).

  R0–R2 persist organism life to a LOCAL append-only EDN tx log whose body shape
  is kotoba-native by design. This namespace is the missing hop: each local
  transaction becomes one `com.etzhayyim.apps.kotoba.datomic.transact` call
  against a running kotoba node (the engine of ADR-2605262130 / ADR-2605301625,
  serving on :8077), so organism state lands on the REAL distributed Datom graph
  (IPFS-backed, IPNS-headed) — not just a file.

  Durability + honesty rules, same discipline as everything else here:

    - the push cursor is a `:bridge/*` checkpoint ON the local log (a tx like any
      other), so a crashed/re-run push NEVER double-sends a transaction
      (exactly-once per local tx);
    - every pushed tx carries `:ibuki.tx/*` provenance meta datoms (local tx id +
      local CID + local prev), so the remote graph holds the full mapping back to
      the local commit-DAG;
    - the previous push's remote `commit_cid` is sent as `expected_parent`
      (optimistic concurrency: a fork on the remote head fails loudly, never
      silently overwrites);
    - host allowlist (loopback + EVO-X2 LAN, ADR-2605215000 fleet topology) — any
      other endpoint throws BEFORE any I/O; live mode is `IBUKI_KOTOBA_LIVE=1`
      (or `:live true`), default is a DRY-RUN export that returns the exact
      request bodies without any I/O.

  HTTP is an INJECTABLE fn (`*http-post*` dynamic var or the `:http-post` option
  key, the `infer.cljc` pattern) defaulting to a babashka/JVM implementation
  (`babashka.http-client`, loaded via `requiring-resolve`). Deterministic (the
  dry-run export of the same log is byte-identical)."
  (:require [clojure.string :as str]
            [ibuki.methods.datoms :as datoms]
            [kotoba.datom :as kd]))

;; ── the fleet-only target allowlist (charter invariant) ───────────────────
;; ADR-2605215000 fleet topology: loopback + EVO-X2 LAN, kotoba serve :8077.
;; NOTHING else is representable as a bridge target.

(def allowed-kotoba-hosts
  #{"127.0.0.1:8077"
    "localhost:8077"
    "192.168.1.70:8077"})   ;; EVO-X2 (kotoba actor serve, ADR-2605301625)

(def default-endpoint "http://127.0.0.1:8077/xrpc/com.etzhayyim.apps.kotoba.datomic.transact")
(def default-graph "ibuki")

(def live-env "IBUKI_KOTOBA_LIVE")
(def env-operator-did "IBUKI_KOTOBA_OPERATOR_DID")

;; ── KotobaBoundaryViolation (ex-info marker) ──────────────────────────────

(defn kotoba-boundary-violation
  "A transact push targeted a host outside the kotoba fleet allowlist (or an
  equivalent boundary precondition failed)."
  ([msg] (kotoba-boundary-violation msg {}))
  ([msg data]
   (ex-info msg (assoc data :ibuki/kotoba-boundary-violation true))))

(defn kotoba-boundary-violation? [e]
  (boolean (:ibuki/kotoba-boundary-violation (ex-data e))))

;; ── URL parsing (minimal portable urlsplit) ───────────────────────────────

(defn- url-parts
  "{:scheme (lower-cased) :netloc (verbatim)} — nil parts when the endpoint has
  no scheme://netloc shape."
  [endpoint]
  (if-let [[_ scheme netloc]
           (re-find #"^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]*)" (str endpoint))]
    {:scheme (str/lower-case scheme) :netloc netloc}
    {:scheme nil :netloc nil}))

(defn assert-kotoba
  "Refuse any endpoint whose host:port is not in the kotoba fleet allowlist
  (http only — a lookalike https scheme is not the fleet). Returns nil on the
  fleet; throws KotobaBoundaryViolation otherwise. Enforced BEFORE any I/O."
  [endpoint]
  (let [{:keys [scheme netloc]} (url-parts endpoint)]
    (when-not (and (= "http" scheme)
                   (contains? allowed-kotoba-hosts (some-> netloc str/lower-case)))
      (throw (kotoba-boundary-violation
              (str "kotoba endpoint " (pr-str endpoint)
                   " is outside the fleet allowlist ("
                   (vec (sort allowed-kotoba-hosts)) ")")
              {:endpoint endpoint})))
    nil))

;; ── graph CID (KotobaCid::from_bytes parity, pinned vs the live engine) ───

(defn- b32-lower
  "RFC-4648 base32 (lowercase alphabet, no padding) — Python
  `base64.b32encode(raw).rstrip(b'=').lower()` parity."
  ^String [^bytes bs]
  (let [alphabet "abcdefghijklmnopqrstuvwxyz234567"
        sb #?(:clj (StringBuilder.) :cljs (atom ""))
        append! #?(:clj (fn [c] (.append ^StringBuilder sb ^char c))
                   :cljs (fn [c] (swap! sb str c)))
        n #?(:clj (alength bs) :cljs (count bs))]
    (loop [i 0 buf 0 bits 0]
      (cond
        (>= bits 5)
        (let [shift (- bits 5)]
          (append! (nth alphabet (bit-and (bit-shift-right buf shift) 0x1f)))
          (recur i (bit-and buf (dec (bit-shift-left 1 shift))) shift))

        (< i n)
        (recur (inc i)
               (bit-or (bit-shift-left buf 8)
                       (bit-and (long #?(:clj (aget bs i) :cljs (nth bs i))) 0xff))
               (+ bits 8))

        :else
        (do (when (pos? bits)
              (append! (nth alphabet (bit-and (bit-shift-left buf (- 5 bits)) 0x1f))))
            #?(:clj (str sb) :cljs @sb))))))

#?(:clj
   (defn graph-cid
     "The kotoba graph identifier for a graph NAME: KotobaCid::from_bytes(name) —
     CIDv1 + dag-cbor(0x71) + sha2-256 multihash over the raw name bytes, multibase
     base32lower ('b' prefix). Mirrors kotoba-core cid.rs exactly (verified against
     the engine's own parser: a fresh CID transacts as genesis)."
     ^String [^String name]
     (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                           (.getBytes name "UTF-8"))
           raw (byte-array (concat [0x01 0x71 0x12 0x20] (seq digest)))]
       (str "b" (b32-lower raw)))))

;; ── per-tx body: local tx → the transact lexicon's tx_edn vector ──────────

(defn- edn-val
  "Python datoms._edn_val parity: \":…\" strings stay raw (keywords), other
  strings quote, numbers/bools literal, sequences nest."
  ^String [v]
  (cond
    (true? v)  "true"
    (false? v) "false"
    (number? v) (str v)
    (string? v) (if (str/starts-with? v ":") v (pr-str v))
    (sequential? v) (str "[" (str/join " " (map edn-val v)) "]")
    :else (pr-str (str v))))

(defn tx->edn-vec
  "One local transaction → the `tx_edn` string the transact lexicon takes: an EDN
  vector of [:db/add e a v] forms + `:ibuki.tx/*` provenance meta (local id / CID /
  prev), so the remote graph can always be mapped back to the local commit-DAG."
  ^String [tx]
  (let [meta-e (str "ibuki-tx-" (:tx/id tx))
        forms (concat (:tx/datoms tx)
                      [[":db/add" meta-e ":ibuki.tx/id" (:tx/id tx)]
                       [":db/add" meta-e ":ibuki.tx/local-cid" (:tx/cid tx)]
                       [":db/add" meta-e ":ibuki.tx/local-prev" (:tx/prev tx)]
                       [":db/add" meta-e ":ibuki.tx/as-of" (:tx/as-of tx)]])]
    (str "["
         (str/join " "
                   (map (fn [[op e a v]]
                          (str "[" op " " (pr-str e) " " a " " (edn-val v) "]"))
                        forms))
         "]")))

;; ── unsigned public-DID operator bearer (no key held) ─────────────────────

#?(:clj
   (defn- b64url-nopad ^String [^String s]
     (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder))
                      (.getBytes s "UTF-8"))))

#?(:clj
   (defn operator-bearer
     "The operator-tier bearer the transact endpoint requires: a JWT whose `sub` is
     the NODE's own operator DID (`IBUKI_KOTOBA_OPERATOR_DID` env, or the
     `:operator-did` option — a PUBLIC identifier, not a secret). The kotoba server
     checks `sub == operator_did` and documents that signature verification is the
     edge/loopback trust boundary's job, so the loopback operator sends an
     explicitly unsigned token. No key material is held or read here."
     (^String [] (operator-bearer {}))
     (^String [{:keys [operator-did]}]
      (let [did (or operator-did (System/getenv env-operator-did) "")]
        (when (str/blank? did)
          (throw (kotoba-boundary-violation
                  (str "live push requires " env-operator-did
                       " (the node's public operator DID — see the node's"
                       " agent-identity log line or `security find-generic-password"
                       " -s etzhayyim.kotoba -a agent-did -w`)"))))
        (str (b64url-nopad (kd/canonical-json {"alg" "none"}))
             "."
             (b64url-nopad (kd/canonical-json {"sub" did}))
             ".unsigned-loopback")))))

;; ── injectable HTTP edge (the infer.cljc pattern) ─────────────────────────
;;
;; Contract: (http-post url body-map headers timeout-s) → the engine's JSON
;; response parsed to a Clojure map with KEYWORD keys (:status :tx_cid
;; :commit_cid :datom_count — wire keys, external schema). A non-2xx response
;; throws (the server's reason surfaced — e.g. a CACAO-verification message on
;; the delegated path — so a delegation issuer can see WHY it was rejected).

#?(:clj
   (defn default-http-post
     "babashka/JVM HTTP edge: POST `body-map` as application/json with `headers`
     and parse the JSON response (keyword keys). `babashka.http-client` +
     `cheshire` are loaded lazily via requiring-resolve (both built into bb)."
     [url body-map headers timeout-s]
     (let [post (requiring-resolve 'babashka.http-client/post)
           generate (requiring-resolve 'cheshire.core/generate-string)
           parse (requiring-resolve 'cheshire.core/parse-string)
           resp (post (str url)
                      {:headers headers
                       :body (generate body-map)
                       :timeout (long (* 1000 (double timeout-s)))
                       :throw false})
           status (:status resp)]
       (if (<= 200 status 299)
         (parse (:body resp) true)
         (let [body (str (:body resp))]
           (throw (ex-info (str "kotoba transact HTTP " status ": "
                                (subs body 0 (min 200 (count body))))
                           {:ibuki/kotoba-transact-http-error true
                            :status status})))))))

(def ^:dynamic *http-post*
  "The injectable HTTP fn (see contract above). Rebind, or pass `:http-post`
  in the options map, to stub the engine in tests / other hosts."
  #?(:clj default-http-post :default nil))

#?(:clj
   (defn default-transport
     "POST a transact. When `:operator-auth` (the loopback fallback, default true)
     → attach the unsigned operator bearer. When NOT (the delegated path) → send
     NO Authorization header: auth is the member-signed `cacao_b64` already in the
     body, and the actor is the principal, not the operator."
     ([url body] (default-transport url body {}))
     ([url body {:keys [timeout-s operator-auth http-post operator-did]
                 :or {timeout-s 60.0 operator-auth true}}]
      (assert-kotoba url)
      (let [headers (cond-> {"Content-Type" "application/json"}
                      operator-auth
                      (assoc "Authorization"
                             (str "Bearer " (operator-bearer {:operator-did operator-did}))))]
        ((or http-post *http-post*) url body headers timeout-s)))))

;; ── the durable push cursor ───────────────────────────────────────────────

(defn bridge-state
  "Replay the durable push cursor from the local log: the LAST `:bridge/*`
  checkpoint wins. Returns {:pushed-to :parent-commit}."
  [txs]
  (reduce (fn [st tx]
            (reduce (fn [st [_op _e a v]]
                      (case a
                        ":bridge/pushed-to-tx" (assoc st :pushed-to v)
                        ":bridge/parent-commit" (assoc st :parent-commit v)
                        st))
                    st
                    (:tx/datoms tx)))
          {:pushed-to 0 :parent-commit ""}
          txs))

;; ── delegation gate (the leash, ADR-2606101200 §委任 / delegation.is_usable) ──

(defn- bget
  "Bundle lookup tolerant of both representations: the kebab keyword key (this
  codebase's convention) and the JSON-sidecar string key (`delegation/load`)."
  [bundle k s]
  (if (contains? bundle k) (get bundle k) (get bundle s)))

(defn- delegation-usable
  "May the organism present this delegation to write `graph` at `now-epoch`?
  Pure function of the bundle metadata (no wall clock). Returns [ok reason]."
  [bundle now-epoch graph]
  (cond
    (nil? bundle)
    [false "no delegation (local-log-only until a member issues one)"]

    (not= (bget bundle :graph "graph") graph)
    [false (str "delegation scoped to graph " (pr-str (bget bundle :graph "graph")) ", not "
                (pr-str graph) " — a capability is never used outside its resource")]

    (>= now-epoch (long (bget bundle :exp "exp")))
    [false (str "delegation expired (exp " (bget bundle :exp "exp") " <= now " now-epoch
                ") — consent must be renewed; the organism falls back to local log")]

    :else
    [true (str "usable (expires " (bget bundle :exp "exp")
               ", aud " (bget bundle :aud "aud") ")")]))

;; ── the push ──────────────────────────────────────────────────────────────

#?(:clj
   (defn- env-live? []
     (= "1" (System/getenv live-env))))

#?(:clj
   (defn push
     "Push every local tx the cursor has not yet sent, one transact call per tx,
     oldest first. Live mode requires IBUKI_KOTOBA_LIVE=1 (or `:live true`);
     otherwise this is a DRY-RUN export. After a live push, ONE `:bridge/*`
     checkpoint tx is appended.

     Auth principal (the leash, ADR-2606101200 §委任): if a usable member-issued
     `:delegation` bundle is given (usable at `:now-epoch`, scoped to this graph),
     the push presents the member-signed `cacao_b64` and the organism writes AS
     ITS OWN actor DID — no operator bearer, no held key. If the delegation is
     absent/expired/mis-scoped, the push FALLS BACK to the operator-bearer
     loopback (the node persisting on the organism's behalf) — fail-open, so an
     unrenewed leash never crashes the organism, it just reverts to the
     node-operator principal.

     Options: :graph :endpoint :transport :live :delegation :now-epoch
              :http-post :operator-did."
     ([log-path] (push log-path {}))
     ([log-path {:keys [graph endpoint transport live delegation now-epoch
                        http-post operator-did]
                 :or {graph default-graph endpoint default-endpoint}}]
      (assert-kotoba endpoint)
      (let [graph-id (if (and (str/starts-with? graph "b") (> (count graph) 40))
                       graph
                       (graph-cid graph))
            ;; decide the auth principal for this push
            [delegated principal]
            (if (nil? delegation)
              [false "operator-bearer (no delegation supplied)"]
              (do (when (nil? now-epoch)
                    (throw (kotoba-boundary-violation
                            (str "now-epoch required to check a delegation's expiry"
                                 " (caller supplies it — no wall clock inside ibuki)"))))
                  (delegation-usable delegation now-epoch graph)))
            txs (datoms/read-log log-path)
            state (bridge-state txs)
            pending (filterv #(> (:tx/id %) (:pushed-to state)) txs)
            bodies (first
                    (reduce (fn [[bodies parent] tx]
                              ;; only the first pending tx chains off the recorded
                              ;; parent; the rest chain off the commits this very
                              ;; push creates (filled live)
                              [(conj bodies
                                     (cond-> {:graph graph-id :tx_edn (tx->edn-vec tx)}
                                       delegated
                                       (assoc :cacao_b64   ;; member-signed capability, presented
                                              (bget delegation :cacao-b64 "cacao_b64"))
                                       (seq parent) (assoc :expected_parent parent)))
                               ""])
                            [[] (:parent-commit state)]
                            pending))
            is-live (if (some? live) (boolean live) (env-live?))]
        (if-not is-live
          {:mode "dry-run" :pending (count bodies) :bodies bodies
           :delegated delegated :principal principal
           :pushed-to (:pushed-to state)}
          (loop [pairs (map vector pending bodies)
                 remote-cids []
                 last-commit (:parent-commit state)
                 datoms-confirmed 0]
            (if-let [[tx body] (first pairs)]
              (let [body (if (seq last-commit)
                           (assoc body :expected_parent last-commit)
                           body)
                    out (if transport
                          (transport endpoint body)
                          (default-transport endpoint body
                                             {:operator-auth (not delegated)
                                              :http-post http-post
                                              :operator-did operator-did}))]
                (when-not (contains? #{"ok" "committed" "success"} (:status out))
                  (throw (ex-info (str "kotoba transact refused tx " (:tx/id tx)
                                       ": " (pr-str out))
                                  {:ibuki/kotoba-transact-refused true
                                   :tx/id (:tx/id tx) :out out})))
                (recur (rest pairs)
                       (conj remote-cids (or (:tx_cid out) ""))
                       (or (:commit_cid out) "")
                       (+ datoms-confirmed (or (:datom_count out) 0))))
              (do
                (when (seq pending)   ;; ONE durable checkpoint — the exactly-once cursor
                  (let [beat (inc (count txs))
                        e (str "bridge-" beat)
                        body-datoms [(datoms/add e ":bridge/pushed-to-tx"
                                                 (:tx/id (peek pending)))
                                     (datoms/add e ":bridge/parent-commit" last-commit)
                                     (datoms/add e ":bridge/graph" graph)
                                     (datoms/add e ":bridge/endpoint-host"
                                                 (:netloc (url-parts endpoint)))
                                     (datoms/add e ":bridge/remote-tx-cids" remote-cids)
                                     (datoms/add e ":bridge/beat" beat)
                                     (datoms/add e ":bridge/as-of" (+ 2606100000 beat))]
                        ck (datoms/make-tx body-datoms
                                           {:tx-id beat
                                            :as-of (+ 2606100000 beat)
                                            :prev-cid (datoms/head-cid log-path)})]
                    (datoms/append-tx! ck log-path)))
                {:mode "live" :pushed (count pending) :remote-tx-cids remote-cids
                 :parent-commit last-commit :datoms-confirmed datoms-confirmed
                 :delegated delegated :principal principal
                 :pushed-to (if (seq pending)
                              (:tx/id (peek pending))
                              (:pushed-to state))}))))))))
