(ns ibuki.methods.test-joucho
  "ibuki 息吹 evolving 5-axis mood tests. ADR-2606101200.
  Port of methods/test_joucho.py."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.joucho :as joucho]))

(deftest default-scores-match-kotodama-stub
  (let [j (joucho/scores)]
    (is (= [50 50 30 50 50]
           (mapv j [:joy :calm :stress :gratitude :focus])))))

(deftest personality-baseline-deterministic-and-distinct
  (let [a (joucho/personality-baseline "10101500")
        b (joucho/personality-baseline "10101500")
        c (joucho/personality-baseline "14111500")]
    (is (= a b))
    (is (not= a c))                                  ;; distinct temperament per organism
    (doseq [v (vals a)]
      (is (<= 25 v 75)))))

(deftest determine-mood-stress-trumps
  (is (= "stressed" (joucho/determine-mood (joucho/scores {:joy 90 :stress 70})))))

(deftest determine-mood-dominant-axis
  (is (= "joyful" (joucho/determine-mood (joucho/scores {:joy 65}))))
  (is (= "grateful" (joucho/determine-mood (joucho/scores {:gratitude 80 :joy 61})))))

(deftest determine-mood-neutral-below-60
  (is (= "neutral" (joucho/determine-mood (joucho/scores {:joy 59 :calm 59 :stress 10
                                                          :gratitude 59 :focus 59})))))

(deftest fold-event-unknown-kind-raises
  (let [base (joucho/scores)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed vocab"
                          (joucho/fold-event base ":event/made-up" base)))))

(deftest fold-event-deltas-and-clamp
  (let [base (joucho/scores)
        j (joucho/fold-event (joucho/scores {:joy 99}) ":event/follower-gained" base)]
    (is (= 100 (:joy j)))                            ;; clamped at 100
    (is (= 52 (:gratitude j)))))                     ;; +2 gratitude

(deftest idle-drifts-toward-baseline
  (let [base (joucho/scores {:joy 50 :calm 50 :stress 30 :gratitude 50 :focus 50})
        j (joucho/fold-event (joucho/scores {:joy 60 :stress 20}) ":event/idle" base)]
    (is (= 59 (:joy j)))                             ;; homeostasis: 1 step toward baseline
    (is (= 21 (:stress j)))))

(deftest replay-events-is-the-as-of-query
  (let [base (joucho/personality-baseline "10101500")
        events (vec (repeat 10 ":event/follower-gained"))
        j (joucho/replay-events base events)]
    (is (>= (:joy j) (:joy base)))
    (is (>= (:gratitude j) (:gratitude base)))
    ;; replaying a PREFIX gives the earlier state — mood history is recoverable
    (let [j5 (joucho/replay-events base (subvec events 0 5))]
      (is (<= (:joy j5) (:joy j))))))

(deftest mood-emerges-from-lived-history
  ;; The Gap-4 closure: two organisms with the same baseline-shape stub would have been
  ;; permanently neutral; with event folds, history moves the mood.
  (let [base (joucho/scores)]                        ;; the old constant stub: neutral forever
    (is (= "neutral" (joucho/determine-mood base)))
    (let [lived (joucho/replay-events base (repeat 6 ":event/follower-gained"))]
      (is (contains? #{"joyful" "grateful"}
                     (joucho/determine-mood lived)))))) ;; personality emerged

(deftest kaizen-events-move-calm-and-stress
  (let [base (joucho/scores)
        merged (joucho/fold-event base ":event/kaizen-merged" base)
        rejected (joucho/fold-event base ":event/kaizen-rejected" base)]
    (is (= 53 (:calm merged)))
    (is (= 27 (:stress merged)))
    (is (= 32 (:stress rejected)))
    (is (= 51 (:focus rejected)))))

(deftest event-datoms-closed-vocab
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed vocab"
                        (joucho/event-datoms "c" [":event/nope"] {:beat 1 :as-of 1}))))

(deftest joucho-datoms-shape
  (let [j (joucho/scores)
        ds (joucho/joucho-datoms "10101500" j "neutral" {:beat 2 :as-of 2606100002})
        attrs (set (map #(nth % 2) ds))]
    (is (set/subset? #{":joucho/of" ":joucho/mood" ":joucho/joy" ":joucho/beat"} attrs))
    (is (every? #(= ":db/add" (first %)) ds))
    (is (every? #(= "joucho-10101500-2" (second %)) ds)))) ;; new entity per beat (非終末論)

(deftest post-cooldown-table-covers-all-moods
  (is (= (set (keys joucho/post-cooldown-ms)) (set joucho/moods)))
  (is (false? (get joucho/post-enabled "stressed")))
  (is (< (get joucho/post-cooldown-ms "joyful") (get joucho/post-cooldown-ms "neutral"))))
