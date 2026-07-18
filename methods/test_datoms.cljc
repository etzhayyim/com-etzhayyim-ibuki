(ns ibuki.methods.test-datoms
  "ibuki 息吹 Datom log tests: chain + as-of fold. ADR-2606101200.
  Clojure-native datoms tests (ADR-2606261200; the Python↔Clojure CID-parity test retired with the py source)
  (the ibuki invariant: crash-resume = byte-identical head CID)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.datoms :as d]))

(defn- tx
  ([body tx-id] (tx body tx-id ""))
  ([body tx-id prev]
   (d/make-tx body {:tx-id tx-id :as-of (+ 2606100000 tx-id) :prev-cid prev})))

(defn- tmplog []
  (str (java.nio.file.Files/createTempDirectory
        "ibuki-clj" (make-array java.nio.file.attribute.FileAttribute 0))
       "/log.edn"))

(deftest datoms-are-append-only-adds
  (let [datom (d/add "org-1" ":organism/code" "1")]
    (is (= ":db/add" (first datom)))         ;; no :db/retract exists (非終末論)
    (is (nil? (ns-resolve (find-ns 'ibuki.methods.datoms) 'retract)))))

(deftest tx-cid-deterministic-and-prev-linked
  (let [body [(d/add "e" ":a/b" 1)]]
    (is (= (d/tx-cid body "") (d/tx-cid body "")))
    (is (not= (d/tx-cid body "") (d/tx-cid body "bdeadbeef")))))

(deftest append-read-verify-roundtrip
  (let [log (tmplog)
        prev (reduce (fn [prev i]
                       (d/append-tx! (tx [(d/add (str "e" i) ":a/n" i)] i prev) log))
                     "" [1 2 3])]
    (is (= 3 (count (d/read-log log))))
    (is (= prev (d/head-cid log)))
    (let [v (d/verify-chain log)]
      (is (true? (:ok v)))
      (is (= 3 (:length v))))))

(deftest tamper-breaks-chain
  (let [log (tmplog)]
    (reduce (fn [prev i]
              (d/append-tx! (tx [(d/add (str "e" i) ":a/n" i)] i prev) log))
            "" [1 2])
    (spit log (str/replace-first (slurp log) ":a/n 1" ":a/n 9"))
    (is (false? (:ok (d/verify-chain log))))))

(deftest fold-entity-latest-wins
  (let [txs [(tx [(d/add "e" ":a/x" 1)] 1)
             (tx [(d/add "e" ":a/x" 2) (d/add "e" ":a/y" "s")] 2)]]
    (is (= {":a/x" 2 ":a/y" "s"} (d/fold-entity txs "e")))))

(deftest fold-entity-as-of-cut
  (let [txs [(tx [(d/add "e" ":a/x" 1)] 1)
             (tx [(d/add "e" ":a/x" 2)] 2)]]
    (is (= 1 (get (d/fold-entity txs "e" {:up-to-tx 1}) ":a/x")))))  ;; history preserved

(deftest entities-by-attr
  (let [txs [(tx [(d/add "a" ":k/of" "x") (d/add "b" ":k/of" "y")] 1)]]
    (is (= ["a" "b"] (d/entities txs ":k/of")))))

(deftest events-for-ordered-and-as-of
  (let [txs [(tx [(d/add "jev-c-1-0" ":joucho.event/of" "c")
                  (d/add "jev-c-1-0" ":joucho.event/kind" ":event/idle")] 1)
             (tx [(d/add "jev-c-2-0" ":joucho.event/of" "c")
                  (d/add "jev-c-2-0" ":joucho.event/kind" ":event/follower-gained")
                  (d/add "jev-d-2-0" ":joucho.event/of" "d")
                  (d/add "jev-d-2-0" ":joucho.event/kind" ":event/idle")] 2)]]
    (is (= [":event/idle" ":event/follower-gained"] (d/events-for txs "c")))
    (is (= [":event/idle"] (d/events-for txs "c" {:up-to-tx 1})))
    (is (= [":event/idle"] (d/events-for txs "d")))))

(deftest unicode-text-roundtrips
  (let [log (tmplog)
        body [(d/add "p" ":post/text" "観測ノート\n\n日本語 with \"quotes\"")]]
    (d/append-tx! (tx body 1) log)
    (is (true? (:ok (d/verify-chain log))))
    (is (str/starts-with? (get (d/fold-entity (d/read-log log) "p") ":post/text")
                          "観測"))))
