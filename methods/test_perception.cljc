(ns ibuki.methods.test-perception
  "test-perception — 息吹 (ibuki) R2 live perception membrane. ADR-2606101200 §R2.
  Clojure port of `methods/test_perception.py` (every Python assertion, 1:1).
  Hermetic: the live path is exercised through the injectable :fetch fn only —
  no network is ever touched."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.joucho :as joucho]
            [ibuki.methods.perception :as perception]))

(deftest representative-pattern-deterministic
  (is (= [":event/idle"] (perception/representative-events 1)))
  (is (= [":event/idle" ":event/follower-gained"] (perception/representative-events 3)))
  (is (= [":event/idle" ":event/follower-gained" ":event/inbox-pressure"]
         (perception/representative-events 15)))
  (is (= (perception/representative-events 7) (perception/representative-events 7))))

(deftest allowlist-is-public-appview-only
  (doseq [host perception/allowed-xrpc-hosts]
    (is (= "public.api.bsky.app" host))))

(deftest non-allowlisted-host-unrepresentable
  (doseq [bad ["https://evil.example.com/xrpc/app.bsky.actor.getProfile?actor=x"
               "http://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=x"
               "https://api.openai.example/v1"]]
    (let [e (try (perception/assert-allowed bad) nil
                 (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? e) (str "expected boundary violation for " bad))
      (when e
        (is (perception/perception-boundary-violation? e))
        (is (str/includes? (ex-message e) "allowlist"))))))

(deftest offline-default-is-representative-no-snapshot
  ;; the env-pop parity: :live false ≡ IBUKI_PERCEPTION_LIVE unset
  (let [[events snap] (perception/events-for-beat 4 {:actor "did:web:x" :live false})]
    (is (= (perception/representative-events 4) events))
    (is (nil? snap)))
  ;; and the real env default path (the suite never sets IBUKI_PERCEPTION_LIVE)
  (when-not (= "1" #?(:clj (System/getenv perception/live-env) :default nil))
    (let [[events snap] (perception/events-for-beat 4 {:actor "did:web:x"})]
      (is (= (perception/representative-events 4) events))
      (is (nil? snap)))))

(deftest live-observation-maps-follower-delta
  (let [fake (fn [_url] {"followersCount" 12})
        [events snap] (perception/events-for-beat
                       1 {:actor "did:web:x" :prev-followers 10 :live true :fetch fake})]
    (is (= {:followers 12} snap))
    (is (= [":event/idle" ":event/follower-gained" ":event/follower-gained"] events))))

(deftest live-follower-spike-is-capped
  (let [fake (fn [_url] {"followersCount" 10000})
        [events _snap] (perception/events-for-beat
                        1 {:actor "did:web:x" :prev-followers 0 :live true :fetch fake})]
    (is (= perception/follower-event-cap
           (count (filter #{":event/follower-gained"} events))))))

(deftest live-first-observation-emits-no-gain
  (let [fake (fn [_url] {"followersCount" 99})
        [events snap] (perception/events-for-beat
                       1 {:actor "did:web:x" :prev-followers nil :live true :fetch fake})]
    (is (= [":event/idle"] events))          ;; baseline, not a spike
    (is (= {:followers 99} snap))))

(deftest live-failure-fails-open-to-representative
  (let [boom (fn [_url] (throw (ex-info "network down" {})))
        [events snap] (perception/events-for-beat
                       6 {:actor "did:web:x" :prev-followers 5 :live true :fetch boom})]
    (is (= (perception/representative-events 6) events))
    (is (nil? snap))))

(deftest events-are-closed-vocab
  (let [fake (fn [_url] {"followersCount" 3})
        [events _] (perception/events-for-beat
                    1 {:actor "did:web:x" :prev-followers 1 :live true :fetch fake})]
    (doseq [ev events]
      (is (contains? joucho/event-deltas ev)))))

(deftest perception-datoms-shape
  (let [ds (perception/perception-datoms "10101500" 42 {:beat 3 :as-of 2606100003})
        attrs (set (map #(nth % 2) ds))]
    (is (= #{":perception/of" ":perception/followers" ":perception/beat"
             ":perception/as-of"}
           attrs))
    (is (every? (fn [d] (and (= ":db/add" (nth d 0)) (= "perc-10101500-3" (nth d 1))))
                ds))))

(deftest module-reads-no-credentials
  (let [src (slurp (or (io/resource "ibuki/methods/perception.cljc")
                       (io/file "methods/perception.cljc")))]
    (doseq [needle ["password" "accessJwt" (str "Authoriza" "tion") "PRIVATE_KEY"]]
      (is (not (str/includes? src needle))
          (str "perception must stay unauthenticated read-only: " needle)))))
