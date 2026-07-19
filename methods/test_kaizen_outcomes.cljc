(ns ibuki.methods.test-kaizen-outcomes
  "test-kaizen-outcomes — 息吹 (ibuki) R3 operator-principal outcome collector.
  Clojure port of `methods/test_kaizen_outcomes.py`. No network/gh: the runner is
  always a stub."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.kaizen-feedback :as kf]
            [ibuki.methods.kaizen-outcomes :as ko]))

(defn- tmpdir []
  (str (java.nio.file.Files/createTempDirectory
        "ibuki-kaizen-outcomes" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- proposals-file [dir rows]
  (let [p (io/file dir "proposals.ndjson")]
    (spit p (str (str/join "\n" (map json/generate-string rows)) "\n"))
    p))

(deftest cron-context-refused
  (binding [ko/*env* {ko/env-cron "1"}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cron"
                          (ko/collect "/nonexistent")))
    (is (try (ko/collect "/nonexistent") false
             (catch clojure.lang.ExceptionInfo e
               (ko/operator-context-required? e))))))

(deftest states-map-to-closed-outcomes
  (let [states {101 "MERGED" 102 "CLOSED" 103 "OPEN"}
        p (proposals-file (tmpdir)
                          (for [n [101 102 103]]
                            {"proposalId" (str "p" n) "rule" "error-rate" "pr" n}))
        out (ko/collect p {:gh #(get states %)})]
    (is (= ["merged" "rejected" "pending"] (mapv :outcome out)))))

(deftest proposals-without-pr-are-skipped
  (let [p (proposals-file (tmpdir)
                          [{"proposalId" "p1" "rule" "r"}
                           {"proposalId" "p2" "rule" "r" "pr" 7}])
        out (ko/collect p {:gh (constantly "MERGED")})]
    (is (= 1 (count out)))
    (is (= 7 (:pr (first out))))))

(deftest unknown-state-raises
  (let [p (proposals-file (tmpdir) [{"proposalId" "p1" "rule" "r" "pr" 1}])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed vocab"
                          (ko/collect p {:gh (constantly "DRAFT")})))))

(deftest collected-outcomes-feed-kaizen-feedback-end-to-end
  ;; The full Wave-4 loop on real plumbing: proposals + gh states → outcomes file →
  ;; kaizen-feedback fold → suppression of the thrice-rejected rule.
  (let [dir (tmpdir)
        rows (for [i (range 3)]
               {"proposalId" (str "p" i) "rule" "flappy-rule" "pr" (+ 200 i)})
        p (proposals-file dir rows)
        out (ko/collect p {:gh (constantly "CLOSED")})
        opath (io/file dir "outcomes.ndjson")]
    (is (= 3 (ko/write-outcomes out opath)))
    (let [outcomes (kf/read-outcomes opath)
          table (kf/suppression (kf/fold (kf/read-proposals p) outcomes) 4)]
      (is (= {"flappy-rule" (+ 4 kf/suppress-beats)} table))
      (is (false? (kf/should-emit "flappy-rule" table 4))))))

(deftest write-is-a-snapshot-not-append
  (let [opath (io/file (tmpdir) "outcomes.ndjson")]
    (ko/write-outcomes [{:proposal-id "a" :rule "r" :pr 1 :outcome "pending"}] opath)
    (ko/write-outcomes [{:proposal-id "a" :rule "r" :pr 1 :outcome "merged"}] opath)
    (let [lines (str/split-lines (slurp opath))]
      (is (= 1 (count lines)))
      ;; current state, not history
      (is (str/includes? (first lines) "\"merged\"")))))

(deftest module-is-read-only-toward-github
  (let [portable (slurp (or (io/resource "ibuki/methods/kaizen_outcomes.cljc")
                            (io/file "methods/kaizen_outcomes.cljc")))
        host (slurp (or (io/resource "ibuki/methods/host_capabilities.clj")
                        (io/file "methods/host_capabilities.clj")))
        src (-> (str portable "\n" host)
                (str/replace "'" "\""))]
    (is (str/includes? src "\"view\""))
    (doseq [verb ["merge" "close" "comment" "review" "edit"]]
      (is (not (str/includes? src (str "\"" verb "\"")))
          (str "kaizen-outcomes must stay read-only: gh pr " verb)))))

(deftest missing-gh-capability-fails-closed
  (let [p (proposals-file (tmpdir)
                          [{"proposalId" "p1" "rule" "r" "pr" 1}])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"explicit read-only gh capability"
                          (ko/collect p)))))
