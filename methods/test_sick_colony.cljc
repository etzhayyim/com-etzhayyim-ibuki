(ns ibuki.methods.test-sick-colony
  "test-sick-colony — adversarial: a BROKEN colony detects + reports its own ill-health.
  ADR-2606101800. Clojure port of `methods/test_sick_colony.py`.

  The self-monitoring claims (health audit, digest report, kaizen feedback) are only worth
  anything if the loop CLOSES under failure. This suite induces a pathology — a colony with
  NO decomposer niche, so the food web cannot close — runs it end-to-end through autorun,
  and asserts the whole self-monitoring stack catches and reports it:

    health/audit flags keystone-niche-absent (+ ecosystem-starved past the grace window) →
    the colony is NOT healthy → the digest reports the unhealthy state → health proposals
    carry the complaint to the Wave-4 kaizen loop.

  Contrast with the healthy seed (producer+router+decomposer), which stays green."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.autorun :as autorun]
            [ibuki.methods.datoms :as datoms]
            [ibuki.methods.digest :as digest]
            [ibuki.methods.health :as health]
            [ibuki.methods.kaizen-feedback :as kf]
            [ibuki.methods.symbiosis :as symbiosis]))

(def ^:private sick-seed
  ";; deliberately broken colony — NO decomposer niche, the web cannot close.
{:seed/kind :representative
 :seed/organisms
 [{:organism/code \"10101500\" :organism/title \"Producer A\"
   :organism/did \"did:web:etzhayyim.com:actor:sick-a\" :organism/niche :niche/producer}
  {:organism/code \"14111500\" :organism/title \"Producer B\"
   :organism/did \"did:web:etzhayyim.com:actor:sick-b\" :organism/niche :niche/producer}
  {:organism/code \"50221000\" :organism/title \"Router C\"
   :organism/did \"did:web:etzhayyim.com:actor:sick-c\" :organism/niche :niche/router}]}
")

(defn- tmpdir []
  (str (java.nio.file.Files/createTempDirectory
        "ibuki-sick" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- run-sick
  "Run a fresh autonomous life on the deliberately broken seed (the Python patches
  autorun.SEED; here the private seed loader is rebound to read the sick seed file).
  Returns [log-path txs]."
  ([dir] (run-sick dir 12))
  ([dir cycles]
   (let [seed (str dir "/sick-seed.edn")]
     (spit seed sick-seed)
     (with-redefs [autorun/load-seed
                   (fn [] (-> (slurp seed) edn/read-string (get :seed/organisms)))]
       (let [log (str dir "/log.edn")]
         (autorun/autorun cycles {:fresh true :log-path log
                                  :queue-path (str dir "/q.ndjson")})
         [log (datoms/read-log log)])))))

(defn- rule-set [findings] (set (map :rule findings)))

(deftest sick-colony-chain-still-verifies
  ;; Even broken, the substrate is sound — a pathology is DATA, not corruption.
  (let [[log _] (run-sick (tmpdir))]
    (is (true? (:ok (datoms/verify-chain log))))))

(deftest health-flags-keystone-absent
  (let [[_ txs] (run-sick (tmpdir))
        rep (health/audit txs)]
    (is (contains? (rule-set (:findings rep)) "keystone-niche-absent"))  ;; no decomposer → web cannot close
    (is (false? (:healthy rep)))))

(deftest no-commons-reaches-humanity
  ;; No カビ → no refining → the symbiosis offer is zero (the gift never forms).
  (let [[_ txs] (run-sick (tmpdir))]
    (is (= 0 (:offered (symbiosis/commons-pool txs))))
    ;; past the grace window this is also surfaced as ecosystem-starved
    (is (contains? (rule-set (:findings (health/audit txs))) "ecosystem-starved"))))

(deftest digest-reports-the-illness
  (let [[_ txs] (run-sick (tmpdir))
        state (digest/assemble txs)]
    (is (false? (:healthy state)))
    (is (some #{"keystone-niche-absent"} (:findings state)))
    (let [text (digest/template-digest state)]
      (is (str/includes? text "attending to")))       ;; the report names the illness honestly
    ;; the on-log digest checkpoint also carries the unhealthy verdict
    (let [healthy-flags (vec (for [tx txs
                                   [_op _e a v] (:tx/datoms tx)
                                   :when (= a ":digest/healthy")]
                               v))]
      (is (seq healthy-flags))
      (is (false? (peek healthy-flags))))))

(deftest health-proposals-carry-the-complaint-to-kaizen
  (let [dir (tmpdir)
        [_ txs] (run-sick dir)
        rep (health/audit txs)
        p (str dir "/health-proposals.ndjson")
        n (health/write-proposals rep p)]
    (is (pos? n))
    (let [proposals (kf/read-proposals p)]
      (is (contains? (set (map :rule proposals))
                     "keystone-niche-absent")))))      ;; the Wave-4 loop will carry it to humans

(deftest healthy-seed-stays-green-by-contrast
  ;; Control: the real seed (producer+router+decomposer) shows none of the above.
  (let [dir (tmpdir)
        log (str dir "/log.edn")]
    (autorun/autorun 12 {:fresh true :log-path log :queue-path (str dir "/q.ndjson")})
    (let [rep (health/audit (datoms/read-log log))]
      (is (true? (:healthy rep)))
      (is (not (contains? (rule-set (:findings rep)) "keystone-niche-absent"))))))
