(ns ibuki.methods.test-wellbecoming
  "Tests for the charter-clean social-reward event + Wellbecoming-as-trajectory (ADR-2606171500)."
  (:require [clojure.test :refer [deftest is]]
            [ibuki.methods.joucho :as j]
            [ibuki.methods.wellbecoming :as wb]))

(deftest dialogue-reciprocated-is-relational-not-engagement
  (let [base (j/scores)
        after (j/fold-event base ":event/dialogue-reciprocated" base)]
    ;; a reply returned: heard → gladder + calmer + grateful, NOT a like tally
    (is (= 52 (:joy after)))
    (is (= 51 (:calm after)))
    (is (= 29 (:stress after)))          ; isolation soothed
    (is (= 53 (:gratitude after)))
    ;; engagement counts stay unrepresentable (closed vocab raises, §1.13)
    (is (thrown? clojure.lang.ExceptionInfo (j/fold-event base ":event/like-counted" base)))
    (is (thrown? clojure.lang.ExceptionInfo (j/fold-event base ":event/comment-count" base)))))

(deftest wellbecoming-is-movement-never-a-score
  (let [base (j/scores)
        stream [":event/idle" ":event/dialogue-reciprocated" ":event/kaizen-merged"
                ":event/dialogue-reciprocated"]
        beats (reductions (fn [s e] (j/fold-event s e base)) base stream)
        ro (wb/readout "ibuki" beats)
        dms (wb/wellbecoming-datoms "ibuki" beats {:beat 4 :as-of 4})]
    (is (= :improving (:direction ro)))
    (is (pos? (:net ro)))
    ;; EDGE-PRIMARY GUARD: no per-soul level/score attribute is ever emitted
    (is (not-any? #(re-find #"score|level" (str (nth % 2))) dms))
    (is (some #(= ":wellbecoming/direction" (nth % 2)) dms))
    (is (some #(= ":wellbecoming/net" (nth % 2)) dms))))

(deftest declining-and-steady-trajectories
  (let [base (j/scores)
        down (reductions (fn [s e] (j/fold-event s e base)) base
                         [":event/inbox-pressure" ":event/inbox-pressure" ":event/kaizen-rejected"])
        flat [base base]]
    (is (= :declining (:direction (wb/readout "x" down))))
    (is (= :steady (:direction (wb/readout "x" flat))))))
