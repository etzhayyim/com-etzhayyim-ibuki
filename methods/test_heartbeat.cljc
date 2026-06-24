(ns ibuki.methods.test-heartbeat
  "ibuki 息吹 durable heartbeat cadence tests. ADR-2606101200.
  Port of methods/test_heartbeat.py."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.datoms :as d]
            [ibuki.methods.heartbeat :as heartbeat]))

(defn- tx
  ([body tx-id] (tx body tx-id ""))
  ([body tx-id prev]
   (d/make-tx body {:tx-id tx-id :as-of (+ 2606100000 tx-id) :prev-cid prev})))

(deftest fresh-state-never-posted
  (let [s (heartbeat/state)]
    (is (= -1 (:last-post-at-ms s)))
    (is (= 0 (:beats s)))
    (is (= 0 (:posts s)))))

(deftest first-post-is-due
  (let [[due reason] (heartbeat/due-to-post (heartbeat/state) "neutral" 0)]
    (is due)
    (is (= "first post" reason))))

(deftest cooldown-blocks-then-elapses
  (let [s (heartbeat/state {:last-post-at-ms 0})]
    (is (not (first (heartbeat/due-to-post s "neutral" (* 60 60000)))))   ;; 1h < 2h
    (is (first (heartbeat/due-to-post s "neutral" (* 121 60000))))))      ;; >2h

(deftest mood-changes-cadence
  (let [s (heartbeat/state {:last-post-at-ms 0})
        now (* 45 60000)]                                                 ;; 45 min
    (is (true? (first (heartbeat/due-to-post s "joyful" now))))           ;; 30m cooldown
    (is (false? (first (heartbeat/due-to-post s "neutral" now))))))       ;; 2h cooldown

(deftest stressed-never-posts
  (let [[due reason] (heartbeat/due-to-post (heartbeat/state) "stressed" 1000000000)]
    (is (not due))
    (is (str/includes? reason "disabled"))))

(deftest checkpoint-replay-roundtrip
  (let [s (heartbeat/state {:last-post-at-ms 90000 :beats 3 :posts 2})
        txs [(tx (heartbeat/checkpoint-datoms "c1" s "calm" {:beat 3 :as-of 2606100003}) 1)]
        back (heartbeat/replay txs "c1")]
    (is (= s back))))

(deftest replay-latest-checkpoint-wins
  (let [s1 (heartbeat/state {:last-post-at-ms 10 :beats 1 :posts 1})
        s2 (heartbeat/state {:last-post-at-ms 99 :beats 2 :posts 1})
        txs [(tx (heartbeat/checkpoint-datoms "c1" s1 "calm" {:beat 1 :as-of 1}) 1)
             (tx (heartbeat/checkpoint-datoms "c1" s2 "calm" {:beat 2 :as-of 2}) 2)]]
    (is (= 99 (:last-post-at-ms (heartbeat/replay txs "c1"))))))

(deftest replay-isolated-per-organism
  (let [s1 (heartbeat/state {:last-post-at-ms 10 :beats 1 :posts 1})
        txs [(tx (heartbeat/checkpoint-datoms "c1" s1 "calm" {:beat 1 :as-of 1}) 1)]]
    (is (= (heartbeat/state) (heartbeat/replay txs "c2")))))  ;; untouched organism

(deftest crash-resume-same-answer
  ;; The Gap-1/2 closure: the due decision is a pure function of (log, mood, now) — a
  ;; process restart (re-replay) cannot change it.
  (let [s (heartbeat/state {:last-post-at-ms 0 :beats 5 :posts 3})
        txs [(tx (heartbeat/checkpoint-datoms "c1" s "focused" {:beat 5 :as-of 5}) 1)]
        before (heartbeat/due-to-post (heartbeat/replay txs "c1") "focused" (* 100 60000))
        after (heartbeat/due-to-post (heartbeat/replay txs "c1") "focused" (* 100 60000))]
    (is (= before after))))
