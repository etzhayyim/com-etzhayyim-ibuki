(ns ibuki.methods.test-kaizen-feedback
  "ibuki 息吹 — Wave-4 kaizen feedback loop. ADR-2606101200.
  Port of methods/test_kaizen_feedback.py (every Python assertion, 1:1)."
  (:require #?(:clj [cheshire.core :as json])
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.kaizen-feedback :as kf]))

#?(:clj
   (defn- ndjson-file
     "Write `rows` as one temp NDJSON file; returns its path (tempdir parity
     with the Python tests' tempfile.TemporaryDirectory)."
     [rows]
     (let [gen json/generate-string
           f (java.io.File/createTempFile "ibuki-kf" ".ndjson")]
       (.deleteOnExit f)
       (spit f (str (str/join "\n" (map gen rows)) "\n"))
       (.getPath f))))

(deftest read-proposals-filters-malformed
  (let [p (ndjson-file [{:proposalId "p1" :rule "error-rate"}
                        {:noise true}])]
    (is (= 1 (count (kf/read-proposals p))))))

(deftest read-outcomes-closed-vocab
  (let [p (ndjson-file [{:proposalId "p1" :rule "r" :outcome "shipped"}])
        e (try (kf/read-outcomes p) nil
               (catch #?(:clj Exception :cljs :default) e e))]
    (is (some? e) "expected a closed-vocab violation, none raised")
    (when e
      (is (str/includes? (ex-message e) "closed vocab")))))

(deftest missing-files-are-empty
  (is (= [] (kf/read-proposals "/nonexistent.ndjson")))
  (is (= [] (kf/read-outcomes "/nonexistent.ndjson"))))

(deftest fold-counts-and-consecutive-rejections
  (let [proposals (for [i (range 4)] {:proposal-id (str "p" i) :rule "lru-saturation"})
        outcomes [{:proposal-id "p0" :rule "lru-saturation" :outcome "rejected"}
                  {:proposal-id "p1" :rule "lru-saturation" :outcome "merged"}
                  {:proposal-id "p2" :rule "lru-saturation" :outcome "rejected"}
                  {:proposal-id "p3" :rule "lru-saturation" :outcome "rejected"}]
        s (get (kf/fold proposals outcomes) "lru-saturation")]
    (is (= 4 (:proposed s)))
    (is (= 1 (:merged s)))
    (is (= 3 (:rejected s)))
    (is (= 2 (:consecutive-rejected s)))))     ;; merge reset the run

(deftest suppression-threshold
  (let [stats {"a" {:proposed 3 :merged 0 :rejected 3 :pending 0
                    :consecutive-rejected 3}
               "b" {:proposed 3 :merged 1 :rejected 2 :pending 0
                    :consecutive-rejected 2}}
        table (kf/suppression stats 10)]
    (is (= {"a" (+ 10 kf/suppress-beats)} table)))) ;; b stays free to propose

(deftest should-emit-gate-lifts-after-window
  (let [table {"a" 22}]
    (is (false? (kf/should-emit "a" table 21)))  ;; the observer LEARNED to stop
    (is (true? (kf/should-emit "a" table 22)))   ;; ...for a season, not forever
    (is (true? (kf/should-emit "never-suppressed" table 0)))))

(deftest mood-events-mapping
  (let [outcomes [{:proposal-id "p" :rule "r" :outcome "merged"}
                  {:proposal-id "q" :rule "r" :outcome "rejected"}
                  {:proposal-id "s" :rule "r" :outcome "pending"}]]
    (is (= [":event/kaizen-merged" ":event/kaizen-rejected"]
           (kf/mood-events outcomes)))))

(deftest feedback-datoms-checkpoint-learning
  (let [stats (kf/fold [{:proposal-id "p" :rule "error-rate"}]
                       [{:proposal-id "p" :rule "error-rate" :outcome "merged"}])
        ds (kf/feedback-datoms stats {} 2 2606100002)
        attrs (set (map #(nth % 2) ds))]
    (is (contains? attrs ":kaizen.rule/merged"))
    (is (contains? attrs ":kaizen.rule/suppressed-until-beat"))
    (is (every? #(= ":db/add" (first %)) ds))))

(deftest loop-closes-deterministically
  ;; The Gap-7 closure: same proposals + same outcomes → same learned state,
  ;; replayable from the log at any later time.
  (let [proposals (for [i (range 3)] {:proposal-id (str "p" i) :rule "flap"})
        outcomes (for [i (range 3)] {:proposal-id (str "p" i) :rule "flap"
                                     :outcome "rejected"})
        t1 (kf/suppression (kf/fold proposals outcomes) 5)
        t2 (kf/suppression (kf/fold proposals outcomes) 5)]
    (is (= t1 t2 {"flap" (+ 5 kf/suppress-beats)}))))
