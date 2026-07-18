#!/usr/bin/env bb
;; ibuki — bb-native test runner (Clojure / babashka; no shell). Auto-generated
;; (repo-wide rule, root CLAUDE.md §"Operational code = clj/bb"): first-party tooling is
;; clj/bb, not shell. Discovers every test_*.{cljc,clj} namespace already authored under
;; this actor's tree and runs it via clojure.test — this actor had test suites but no
;; runner wired up (vitals reflex was reading as :absent).
;;
;;   bb run_tests.clj      ; run from repository root (run_tests.sh wires classpath)
(require '[babashka.classpath :as cp]
         '[babashka.fs :as fs]
         '[clojure.test :as t])

;; run_tests.sh creates a transient ./ibuki symlink, so the repository itself is the
;; classpath root while existing ibuki.* namespaces remain stable after extraction.
(cp/add-classpath (str (fs/parent (fs/absolutize *file*))))

(def suites
  '[ibuki.methods.test-autorun
    ibuki.methods.test-charter-invariants
    ibuki.methods.test-coscientist
    ibuki.methods.test-datoms
    ibuki.methods.test-delegation
    ibuki.methods.test-digest
    ibuki.methods.test-drainer
    ibuki.methods.test-ecosystem
    ibuki.methods.test-fleet
    ibuki.methods.test-health
    ibuki.methods.test-heartbeat
    ibuki.methods.test-infer
    ibuki.methods.test-integration
    ibuki.methods.test-joucho
    ibuki.methods.test-kaizen-feedback
    ibuki.methods.test-kaizen-outcomes
    ibuki.methods.test-kotoba-bridge
    ibuki.methods.test-member-submit
    ibuki.methods.test-metabolism
    ibuki.methods.test-perception
    ibuki.methods.test-quorum
    ibuki.methods.test-react-loop
    ibuki.methods.test-sick-colony
    ibuki.methods.test-symbiosis
    ibuki.methods.test-wellbecoming])

(apply require suites)

(let [{:keys [fail error]} (apply t/run-tests suites)]
  (if (zero? (+ fail error))
    (println "-- ibuki: ALL suites green --")
    (do (println "-- ibuki: FAILURES above --")
        (System/exit 1))))
