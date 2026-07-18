(ns ibuki.methods.test-symbiosis
  "test-symbiosis — 息吹 (ibuki) the 共生 ledger: humanity draws the commons. ADR-2606101200.
  Clojure port of `methods/test_symbiosis.py`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.autorun :as autorun]
            [ibuki.methods.datoms :as datoms]
            [ibuki.methods.symbiosis :as sym]))

(def MEMBER "did:web:etzhayyim.com:member:alice")

(defn- commons-log
  "A log offering one commons metabolite per given nutrient value."
  [& nutrients]
  (loop [txs [] prev "" i 1 ns nutrients]
    (if (empty? ns)
      txs
      (let [n (first ns)
            e (str "eco-refined-d-" i)
            body [(datoms/add e ":metabolite/kind" ":refined")
                  (datoms/add e ":metabolite/commons" true)
                  (datoms/add e ":metabolite/nutrient" n)
                  (datoms/add e ":metabolite/beat" i)]
            tx (datoms/make-tx body {:tx-id i :as-of (+ 2606100000 i) :prev-cid prev})]
        (recur (conj txs tx) (:tx/cid tx) (inc i) (rest ns))))))

(defn- signer [calls]
  (fn [claim]
    (swap! calls conj claim)
    {"signedBy" "member" "claim" (get claim "kind")}))

(defn- tmpdir []
  (str (java.nio.file.Files/createTempDirectory
        "ibuki-symbiosis" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest pool-offered-drawn-available
  (let [txs (commons-log 10 20 30)
        pool (sym/commons-pool txs)]
    (is (= {:offered 60 :drawn 0 :available 60} pool))))

(deftest only-commons-refined-counts-offered
  ;; a non-commons refined + a substrate must NOT count toward the offer
  (let [tx (datoms/make-tx
            [(datoms/add "eco-refined-x" ":metabolite/kind" ":refined")
             (datoms/add "eco-refined-x" ":metabolite/commons" true)
             (datoms/add "eco-refined-x" ":metabolite/nutrient" 40)
             (datoms/add "eco-sub-p" ":metabolite/kind" ":substrate")
             (datoms/add "eco-sub-p" ":metabolite/nutrient" 99)]
            {:tx-id 1 :as-of 1 :prev-cid ""})]
    (is (= 40 (:offered (sym/commons-pool [tx]))))))

(deftest draw-without-signer-refused
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no member signer"
                        (sym/draw (commons-log 50) 10 {:member MEMBER :beat 2 :as-of 2}))))

(deftest draw-without-operator-ack-refused
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"operator_ack"
                        (sym/draw (commons-log 50) 10
                                  {:member MEMBER :beat 2 :as-of 2
                                   :member-signer (signer (atom []))}))))

(deftest draw-cannot-exceed-pool
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds available"
                        (sym/draw (commons-log 30) 31
                                  {:member MEMBER :beat 2 :as-of 2
                                   :member-signer (signer (atom [])) :operator-ack true}))))

(deftest draw-must-be-positive
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive"
                        (sym/draw (commons-log 30) 0
                                  {:member MEMBER :beat 2 :as-of 2
                                   :member-signer (signer (atom [])) :operator-ack true}))))

(deftest member-draw-is-attributed-and-signed
  (let [calls (atom [])
        out (sym/draw (commons-log 50) 20
                      {:member MEMBER :beat 3 :as-of 2606100003
                       :member-signer (signer calls) :operator-ack true})]
    (is (= 30 (:available-after out)))
    (is (and (seq @calls) (= MEMBER (get (first @calls) "member"))))  ;; the MEMBER signed the draw
    (is (= [MEMBER]
           (->> (:datoms out) (filter #(= ":symbiosis/by" (nth % 2))) (mapv #(nth % 3)))))
    (is (= [true]                                                     ;; never platform-drawn
           (->> (:datoms out) (filter #(= ":symbiosis/drawn-by-member" (nth % 2)))
                (mapv #(nth % 3)))))
    (is (every? #(= ":db/add" (nth % 0)) (:datoms out)))))

(deftest draw-depletes-pool-on-the-log
  (let [txs (commons-log 50)
        out (sym/draw txs 20 {:member MEMBER :beat 2 :as-of 2
                              :member-signer (signer (atom [])) :operator-ack true})
        ;; append the draw datoms as a new tx, re-read pool
        tx (datoms/make-tx (:datoms out) {:tx-id 99 :as-of 99 :prev-cid (:tx/cid (peek txs))})]
    (is (= {:offered 50 :drawn 20 :available 30} (sym/commons-pool (conj txs tx))))))

(deftest autonomous-loop-offers-but-never-self-draws
  ;; The colony PRODUCES commons every beat (offer grows) but ibuki never draws — the
  ;; pool stands available to humanity, undrawn, until a member takes it.
  (let [dir (tmpdir)
        log (str dir "/log.edn")]
    (autorun/autorun 20 {:fresh true :log-path log :queue-path (str dir "/q.ndjson")})
    (let [pool (sym/commons-pool (datoms/read-log log))]
      (is (pos? (:offered pool)))
      (is (= 0 (:drawn pool)))                            ;; never self-drawn
      (is (= (:available pool) (:offered pool))))))

(deftest module-holds-no-key
  (let [src (slurp (or (io/resource "ibuki/methods/symbiosis.cljc")
                       (io/file "methods/symbiosis.cljc")))]
    (doseq [needle ["password" "accessJwt" "Authorization" "urllib" "PRIVATE_KEY"]]
      (is (not (str/includes? src needle))
          (str "symbiosis must stay key-free + offline: " needle)))))
