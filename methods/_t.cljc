(ns ibuki.methods._t
  "Tiny standalone test harness — 1:1 Clojure port of 20-actors/ibuki/methods/_t.py
  (no pytest needed; shared by the keizu/ibuki test_*.py convention).

  Each test file builds a list of [name fn] and calls (run suite cases). A case passes if it
  returns without raising; failures print and (CLJ host) the process exits non-zero. Mirrors
  the ake/noroshi convention so `bb test` can aggregate every suite.

  NOTE on the Clojure side: the ibuki `.cljc` test suites use `clojure.test`, NOT this
  harness — so nothing requires this namespace. It is ported only for faithful 1:1 parity
  with `_t.py` (the Python tests still use `from _t import run, expect_raises`). The
  process-exit edge is behind #?(:clj …) to keep the module loadable everywhere."
  (:require [clojure.string :as str]))

(defn run
  "Run a suite of [name fn] cases. A case passes if `fn` returns without throwing; a throwing
  case is counted FAILED, its name + stack printed. Prints `[suite] passed/total passed`. If
  any failed, exits the process non-zero (Python `sys.exit(1)`). Mirrors `run(suite, cases)`."
  [suite cases]
  (let [[passed failed]
        (reduce (fn [[p f] [name fn*]]
                  (try
                    (fn*)
                    [(inc p) f]
                    (catch #?(:clj Exception :cljs :default) e
                      (println (str "  FAIL " name))
                      #?(:clj (.printStackTrace ^Throwable e)
                         :cljs (println (.-stack e)))
                      [p (inc f)])))
                [0 0]
                cases)
        total (+ passed failed)]
    (println (str "[" suite "] " passed "/" total " passed"))
    (when (pos? failed)
      #?(:clj (System/exit 1)
         :cljs (js/process.exit 1)))
    nil))

(defn expect-raises
  "Assert `fn` raises; optionally assert the raised message CONTAINS `contains`. Raises an
  AssertionError-shaped exception if nothing is raised, or if the message is missing the
  required substring. Mirrors `expect_raises(fn, *, contains=\"\")`."
  ([fn*] (expect-raises fn* ""))
  ([fn* contains]
   (let [raised
         (try
           (fn*)
           false
           (catch #?(:clj Exception :cljs :default) e
             (let [msg (str #?(:clj (.getMessage ^Throwable e) :cljs (.-message e)))]
               (when (and (not (str/blank? contains))
                          (not (str/includes? msg contains)))
                 (throw (ex-info (str "raised but missing " (pr-str contains) ": " msg) {}))))
             true))]
     (when-not raised
       (throw (ex-info "expected an exception, none raised" {})))
     nil)))
