(ns ibuki.methods.test-delegation
  "test-delegation — 息吹 (ibuki) the revocable leash: scoped, expiring capability.
  ADR-2606111400 + ADR-2606101200 §委任. Clojure port of `methods/test_delegation.py`."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.delegation :as dg]))

(def actor "did:web:etzhayyim.com:actor:ibuki")  ;; the organism (bearer) — NOT the CACAO audience
(def node "did:key:zNodeOperatorExample")        ;; the kotoba node (the CACAO audience)
(def member "did:key:zMemberExample")            ;; the signer == write_author

(defn- bundle
  ([] (bundle {}))
  ([over]
   (merge {"cacao_b64" "bWVtYmVyLXNpZ25lZC1jYm9y" "aud" node
           "capability" "datom:transact" "graph" "ibuki"
           "exp" 2000 "nonce" "deadbeef"}
          over)))

(defn- tmpdir []
  (str (java.nio.file.Files/createTempDirectory
        "ibuki-delegation" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-bundle [dir b]
  (let [p (io/file dir "deleg.json")]
    (spit p (json/generate-string b))
    p))

(defn- thrown-msg
  "Run f, return the ex message it throws (nil if it does not throw)."
  [f]
  (try (f) nil
       (catch Exception e (ex-message e))))

(deftest load-absent-is-nil-not-error
  (is (nil? (dg/load "/nonexistent/deleg.json"))))        ;; fail-open

(deftest load-rejects-missing-keys
  (is (str/includes?
       (str (thrown-msg #(dg/load (write-bundle (tmpdir) (dissoc (bundle) "exp")))))
       "missing keys")))

(deftest load-rejects-wrong-capability
  (is (str/includes?
       (str (thrown-msg #(dg/load (write-bundle (tmpdir)
                                                (bundle {"capability" "datom:read"})))))
       "datom:transact")))

(deftest load-requires-did-audience-and-nonce
  (is (str/includes?
       (str (thrown-msg #(dg/load (write-bundle (tmpdir) (bundle {"aud" "ibuki"})))))
       "DID"))
  (is (str/includes?
       (str (thrown-msg #(dg/load (write-bundle (tmpdir) (bundle {"nonce" ""})))))
       "nonce")))

(deftest usable-within-scope-and-window
  (let [[ok why] (dg/usable? (bundle {"exp" 2000}) {:now-epoch 1999 :graph "ibuki"})]
    (is ok)
    (is (str/includes? why "usable"))))

(deftest unusable-when-expired
  (let [[ok why] (dg/usable? (bundle {"exp" 2000}) {:now-epoch 2000 :graph "ibuki"})]
    (is (not ok))
    (is (str/includes? why "expired"))))                  ;; consent must be renewed

(deftest unusable-off-scope-graph
  (let [[ok why] (dg/usable? (bundle {"graph" "ibuki"}) {:now-epoch 1 :graph "ibuki-prod"})]
    (is (not ok))
    (is (str/includes? why "scoped to graph"))))          ;; never used outside its resource

(deftest none-bundle-unusable-failopen
  (let [[ok why] (dg/usable? nil {:now-epoch 1 :graph "ibuki"})]
    (is (not ok))
    (is (str/includes? why "no delegation"))))

(deftest audience-is-the-node-not-the-organism
  (is (= node (dg/audience (bundle)))))                   ;; kotoba checks aud == operator_did

(deftest issuance-template-is-member-signed-shape-only
  ;; The template is the payload the MEMBER signs in their own runtime — ibuki never signs.
  ;; aud = the NODE; resources = the SIWE two-entry form; write_author = the member (iss).
  (let [t (dg/issuance-template {:member-did member :node-did node :graph-cid "bafyibuki"
                                 :exp-iso "2026-07-11T00:00:00Z" :nonce-hex "deadbeef"})]
    (is (= member (get t "iss")))
    (is (= node (get t "aud")))
    (is (= ["kotoba://can/datom:transact" "kotoba://graph/bafyibuki"]
           (get t "resources")))
    (is (str/includes? (get t "_note") "never signs"))
    (is (str/includes? (get t "_note") "accountability"))))

(deftest module-does-no-crypto-present-only
  (let [src (slurp (io/resource "ibuki/methods/delegation.cljc"))]
    (doseq [needle ["ed25519" "nacl" "cryptography" "hashlib" "MessageDigest"
                    "sha256" "sign(" "private"]]
      (is (not (str/includes? src needle))
          (str "delegation must be present-only, no crypto: " needle)))))
