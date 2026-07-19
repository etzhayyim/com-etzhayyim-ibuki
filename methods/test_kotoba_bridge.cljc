(ns ibuki.methods.test-kotoba-bridge
  "ibuki 息吹 — R3 bridge to the live kotoba engine. ADR-2606101200 §R3.
  Port of methods/test_kotoba_bridge.py (every Python assertion, 1:1)."
  (:require #?(:clj [cheshire.core :as json])
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.datoms :as d]
            [ibuki.methods.kotoba-bridge :as kb]
            [kotoba.datom :as kd]))

;; ── helpers (test_kotoba_bridge.py _log / _fake_transport / expect_raises) ─

(defn- tmpdir []
  (str (java.nio.file.Files/createTempDirectory
        "ibuki-kb-clj" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- make-log
  ([dir] (make-log dir 3))
  ([dir n]
   (let [log (str dir "/log.edn")]
     (reduce (fn [prev i]
               (d/append-tx!
                (d/make-tx [(d/add (str "e" i) ":a/n" i)
                            (d/add (str "e" i) ":a/s" (str "観測 " i))]
                           {:tx-id i :as-of (+ 2606100000 i) :prev-cid prev})
                log))
             "" (range 1 (inc n)))
     log)))

(defn- fake-transport [calls]
  (fn [_url body]
    (swap! calls conj body)
    {:status "ok"
     :tx_cid (str "bafyremote" (count @calls))
     :commit_cid (str "bafycommit" (count @calls))}))

(defmacro raises-containing?
  "expect_raises(fn, contains=substr) parity: BODY must throw, and the message
  must contain SUBSTR."
  [substr & body]
  `(let [e# (try ~@body nil
                 (catch #?(:clj Exception :cljs :default) e# e#))]
     (is (some? e#) (str "expected an exception containing " (pr-str ~substr)
                         ", none raised"))
     (when e#
       (is (str/includes? (ex-message e#) ~substr)
           (str "expected " (pr-str ~substr) " in " (pr-str (ex-message e#)))))))

;; ── the charter-invariant allowlist ───────────────────────────────────────

(deftest allowlist-is-kotoba-fleet-only
  (doseq [host kb/allowed-kotoba-hosts]
    (is (contains? #{"127.0.0.1" "localhost" "192.168.1.70"}
                   (first (str/split host #":")))
        (str host " is not loopback/EVO-X2"))
    (is (str/ends-with? host ":8077") (str host " is not the kotoba serve port"))))

(deftest foreign-endpoint-unrepresentable
  (doseq [bad ["http://203.0.113.7:8077/xrpc/com.etzhayyim.apps.kotoba.datomic.transact"
               "https://127.0.0.1:8077/xrpc/x"
               "http://127.0.0.1:9999/xrpc/x"]]
    (raises-containing? "allowlist" (kb/assert-kotoba bad)))
  (let [dr (tmpdir)]
    (raises-containing? "allowlist"
                        (kb/push (make-log dr)
                                 {:endpoint "http://evil.example:8077/x"}))))

;; ── tx_edn body shape ─────────────────────────────────────────────────────

(deftest tx-edn-roundtrips-through-the-edn-reader
  (let [dr (tmpdir)
        tx (first (d/read-log (make-log dr 1)))
        forms (kd/normalize-datoms (edn/read-string (kb/tx->edn-vec tx)))
        metas (into {} (for [[_op _e a v] forms
                             :when (str/starts-with? a ":ibuki.tx/")]
                         [a v]))]
    (is (= [":db/add" "e1" ":a/n" 1] (first forms)))
    (is (= "観測 1" (nth (second forms) 3)))
    (is (= 1 (get metas ":ibuki.tx/id")))
    (is (= (:tx/cid tx) (get metas ":ibuki.tx/local-cid")))   ;; provenance to the local DAG
    (is (= "" (get metas ":ibuki.tx/local-prev")))))

;; ── dry-run default (no I/O, no checkpoint, deterministic) ────────────────

(deftest default-is-dry-run-no-io-no-checkpoint
  ;; IBUKI_KOTOBA_LIVE unset (the env-pop parity): no transport passed —
  ;; a live attempt would raise on I/O.
  (let [dr (tmpdir)
        log (make-log dr 3)
        res (kb/push log)]
    (is (= "dry-run" (:mode res)))
    (is (= 3 (:pending res)))
    (is (every? #(= (kb/graph-cid "ibuki") (:graph %)) (:bodies res)))
    (is (= 3 (count (d/read-log log))))))   ;; nothing appended

(deftest dry-run-export-deterministic
  (let [r1 (kb/push (make-log (tmpdir)) {:live false})
        r2 (kb/push (make-log (tmpdir)) {:live false})]
    (is (= (:bodies r1) (:bodies r2)))))

(deftest live-default-transport-without-http-capability-fails-closed
  (let [log (make-log (tmpdir) 1)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"explicit http-post capability"
                          (kb/push log {:live true})))))

;; ── exactly-once durable cursor ───────────────────────────────────────────

(deftest live-push-advances-durable-cursor-exactly-once
  (let [dr (tmpdir)
        log (make-log dr 3)
        calls (atom [])
        res (kb/push log {:live true :transport (fake-transport calls)})]
    (is (= 3 (:pushed res)))
    (is (= 3 (count @calls)))
    ;; checkpoint appended → chain still verifies
    (is (true? (:ok (d/verify-chain log))))
    (is (= 4 (count (d/read-log log))))
    ;; second run: only the checkpoint tx itself is pending — earlier txs NEVER resent
    (let [calls2 (atom [])
          res2 (kb/push log {:live true :transport (fake-transport calls2)})]
      (is (= 1 (:pushed res2)))
      (is (str/includes? (:tx_edn (first @calls2)) ":bridge/pushed-to-tx")))))

(deftest expected-parent-chains-remote-commits
  (let [dr (tmpdir)
        log (make-log dr 2)
        calls (atom [])]
    (kb/push log {:live true :transport (fake-transport calls)})
    (is (not (contains? (first @calls) :expected_parent)))       ;; first push: no parent yet
    (is (= "bafycommit1" (:expected_parent (second @calls))))    ;; then chained
    (let [calls2 (atom [])]
      (kb/push log {:live true :transport (fake-transport calls2)})
      (is (= "bafycommit2" (:expected_parent (first @calls2))))))) ;; durable across runs

(deftest remote-refusal-raises-loudly
  (let [dr (tmpdir)
        log (make-log dr 1)
        refusing (fn [_url _body] {:status "conflict" :error "head moved"})]
    (raises-containing? "refused" (kb/push log {:live true :transport refusing}))
    (is (= 1 (count (d/read-log log))))))   ;; no checkpoint on failure

(deftest empty-log-is-a-noop
  (let [dr (tmpdir)
        log (str dr "/log.edn")
        res (kb/push log {:live true :transport (fake-transport (atom []))})]
    (is (= 0 (:pushed res)))))

;; ── graph CID pinned vs the engine ────────────────────────────────────────

(deftest graph-cid-matches-kotoba-core
  ;; graph-cid must equal KotobaCid::from_bytes(name).to_multibase() — pinned
  ;; against the value the live engine accepted as a valid graph CID
  ;; (2026-06-10 verification).
  (is (= "bafyreiepxtekmtjhrssrwyie2l2e63g6ms4beeuxttrib3sxdyz4z65r7q"
         (kb/graph-cid "ibuki-demo")))
  (is (= (kb/graph-cid "ibuki") (kb/graph-cid "ibuki")))
  (is (str/starts-with? (kb/graph-cid "ibuki") "bafyrei")))

;; ── unsigned public-DID operator bearer (no key held) ─────────────────────

(deftest operator-bearer-requires-public-did-env-only
  ;; env unset (the env-pop parity) + no :operator-did override → refuses,
  ;; naming the env var
  (raises-containing? kb/env-operator-did (kb/operator-bearer))
  (let [tok (kb/operator-bearer {:operator-did "did:key:zexample"})]
    (is (str/ends-with? tok ".unsigned-loopback"))   ;; explicitly unsigned: no key held
    (let [payload-b64 (second (str/split tok #"\."))
          padded (str payload-b64
                      (apply str (repeat (mod (- 4 (mod (count payload-b64) 4)) 4) "=")))
          payload (json/parse-string
                   (String. (.decode (java.util.Base64/getUrlDecoder) ^String padded)
                            "UTF-8"))]
      (is (= {"sub" "did:key:zexample"} payload)))))   ;; the public DID, nothing else

;; ── the leash: member-issued delegation as the auth principal ─────────────

(defn- deleg
  ([] (deleg {}))
  ([{:keys [graph exp] :or {graph "ibuki" exp 9999999999}}]
   {:cacao-b64 "bWVtYmVyLXNpZ25lZA"
    :aud "did:web:etzhayyim.com:actor:ibuki"
    :capability "datom:transact"
    :graph graph
    :exp exp
    :nonce "beef"}))

(deftest delegation-requires-now-epoch
  (let [dr (tmpdir)
        log (make-log dr 1)]
    (raises-containing? "now-epoch"
                        (kb/push log {:graph "ibuki" :live true
                                      :transport (fake-transport (atom []))
                                      :delegation (deleg)}))))

(deftest usable-delegation-presents-cacao-and-drops-operator-bearer
  ;; The leash in force: a usable member-issued delegation → the body carries
  ;; cacao_b64 and the push principal is the delegation, not the operator.
  (let [dr (tmpdir)
        log (make-log dr 2)
        calls (atom [])
        res (kb/push log {:graph "ibuki" :live true
                          :transport (fake-transport calls)
                          :delegation (deleg) :now-epoch 1000})]
    (is (true? (:delegated res)))
    (is (every? #(= "bWVtYmVyLXNpZ25lZA" (:cacao_b64 %)) @calls))))

(deftest expired-delegation-falls-back-to-operator
  ;; An unrenewed leash never crashes the organism — it reverts to the
  ;; node-operator principal (fail-open) and carries no cacao_b64.
  (let [dr (tmpdir)
        log (make-log dr 1)
        calls (atom [])
        res (kb/push log {:graph "ibuki" :live true
                          :transport (fake-transport calls)
                          :delegation (deleg {:exp 500}) :now-epoch 1000})]
    (is (false? (:delegated res)))
    (is (str/includes? (:principal res) "expired"))
    (is (every? #(not (contains? % :cacao_b64)) @calls))))

(deftest off-scope-delegation-not-used
  (let [dr (tmpdir)
        log (make-log dr 1)
        calls (atom [])
        res (kb/push log {:graph "ibuki" :live true
                          :transport (fake-transport calls)
                          :delegation (deleg {:graph "other"}) :now-epoch 1})]
    (is (false? (:delegated res)))
    (is (str/includes? (:principal res) "scoped to graph"))))

(deftest dry-run-reports-delegation-principal
  ;; IBUKI_KOTOBA_LIVE unset (the env-pop parity); :live false makes it explicit
  (let [dr (tmpdir)
        log (make-log dr 1)
        res (kb/push log {:graph "ibuki" :live false
                          :delegation (deleg) :now-epoch 1000})]
    (is (= "dry-run" (:mode res)))
    (is (true? (:delegated res)))
    (is (every? #(seq (:cacao_b64 %)) (:bodies res)))))
