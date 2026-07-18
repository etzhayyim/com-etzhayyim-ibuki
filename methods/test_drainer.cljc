(ns ibuki.methods.test-drainer
  "test-drainer — 息吹 (ibuki) Wave-3 member-signed drainer. ADR-2606101200 + ADR-2605240100.
  Clojure port of `methods/test_drainer.py`."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.drainer :as drainer]))

(defn- line
  "One valid queue line (wire-format string keys), with optional overrides."
  ([] (line {}))
  ([over]
   (merge {"v" 1 "ts" 1000 "actorDid" "did:web:etzhayyim.com:actor:unspsc-10101500"
           "code" "10101500" "title" "Live cattle stewardship" "mood" "calm"
           "contentSourceKind" "recordAnalysis" "text" "観測ノート"
           "lexicon" "app.bsky.feed.post" "createdAt" "2026-06-10T00:00:00.000Z"}
          over)))

(defn- tmpdir []
  (str (java.nio.file.Files/createTempDirectory
        "ibuki-drainer" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-queue [dir lines]
  (let [q (io/file dir "queue.ndjson")]
    (spit q (str (str/join "\n" (map #(if (map? %) (json/generate-string %) %) lines)) "\n"))
    q))

(deftest parse-valid-queue
  (let [q (write-queue (tmpdir) [(line) (line {"ts" 2000})])
        [posts errors] (drainer/parse-queue q)]
    (is (= 2 (count posts)))
    (is (= [] errors))))

(deftest parse-rejects-bad-lines-never-guesses
  (let [q (write-queue (tmpdir)
                       [(line)
                        (line {"v" 99})
                        (dissoc (line) "createdAt")
                        "not-json{"])
        [posts errors] (drainer/parse-queue q)]
    (is (= 1 (count posts)))
    (is (= 3 (count errors)))))

(deftest missing-queue-is-empty-not-error
  (let [[posts errors] (drainer/parse-queue "/nonexistent/queue.ndjson")]
    (is (= [] posts))
    (is (= [] errors))))

(deftest envelope-shape-member-signed
  (let [env (drainer/envelope (line))]
    (is (= "com.atproto.repo.createRecord" (get env "xrpc")))
    (is (str/starts-with? (get env "repo") "did:web:etzhayyim.com:actor:"))
    (is (= "app.bsky.feed.post" (get-in env ["record" "$type"])))
    (is (= true (get env "requiresMemberSignature")))
    (is (= false (get env "serverHeldKey")))))           ;; G7 structural constant

(deftest drain-emits-prepared-only
  (let [q (write-queue (tmpdir) [(line) (line {"ts" 2000})])
        out (drainer/drain q {:as-of 2606100001 :beat 1})]
    (is (= 2 (count (:envelopes out))))
    (is (= [":prepared" ":prepared"]                     ;; :published unwritable by ibuki
           (->> (:datoms out) (filter #(= ":drain/status" (nth % 2))) (mapv #(nth % 3)))))
    (is (= [false false]
           (->> (:datoms out) (filter #(= ":drain/server-held-key" (nth % 2)))
                (mapv #(nth % 3)))))))

(deftest submit-without-signer-is-impossible
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no member signer"
                        (drainer/submit [(drainer/envelope (line))]))))

(deftest submit-without-operator-ack-refused
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"operator_ack"
                        (drainer/submit [(drainer/envelope (line))]
                                        {:member-signer identity}))))

(deftest submit-forwards-to-injected-member-runtime
  (let [seen (atom [])
        member-signer (fn [env]                          ;; the member's OWN runtime, injected
                        (swap! seen conj env)
                        {"signedBy" "member"
                         "uri" "at://did:.../app.bsky.feed.post/1"})
        res (drainer/submit [(drainer/envelope (line))]
                            {:member-signer member-signer :operator-ack true})]
    (is (= 1 (count @seen)))
    (is (= "member" (get (first res) "signedBy")))))

(deftest module-holds-no-credentials
  (let [src (slurp (or (io/resource "ibuki/methods/drainer.cljc")
                       (io/file "methods/drainer.cljc")))]
    (doseq [needle ["token" "secret" "PRIVATE_KEY" "urllib" "requests" "http.client"]]
      (is (not (str/includes? src needle))
          (str "drainer must stay credential-free + offline: " needle)))))

;; ── slice 4: unify the outward path onto the OWN Path B PDS ───────────────

(deftest pds-request-builds-own-pds-path-b-createRecord
  (let [env (drainer/envelope (line))
        req (drainer/pds-request env {:pds-base "https://atproto.etzhayyim.com"
                                      :leash "cacao_b64_opaque_member_capability"})]
    ;; the request targets the OWN PDS + carries the envelope's repo/collection/record
    (is (= "https://atproto.etzhayyim.com" (:base req)))
    (is (= (get env "repo") (get-in req [:spec :repo])))
    (is (= "app.bsky.feed.post" (get-in req [:spec :collection])))
    (is (= (get env "record") (get-in req [:spec :record])))
    ;; the member CACAO leash is PRESENTED (attribution by consent), never a held key
    (is (= "cacao_b64_opaque_member_capability" (get-in req [:spec :leash])))
    ;; the PDS signs with the actor's sealed key — ibuki holds none (no-server-key)
    (is (= false (:server-held-key req)))))

(deftest pds-request-omits-leash-when-absent
  (let [req (drainer/pds-request (drainer/envelope (line)) {:pds-base "https://atproto.etzhayyim.com"})]
    (is (not (contains? (:spec req) :leash)) "no leash → unattributed (back-compat), never fabricated")
    (is (= false (:server-held-key req)))))

(deftest pds-request-requires-pds-base
  (is (thrown? Exception (drainer/pds-request (drainer/envelope (line)) {})))
  (is (thrown? Exception (drainer/pds-request (drainer/envelope (line)) {:pds-base "   "}))))

(deftest pds-request-rejects-non-createRecord
  ;; closed-vocabulary discipline: only a createRecord envelope routes to the PDS
  (is (thrown? Exception (drainer/pds-request {"xrpc" "com.atproto.repo.deleteRecord"}
                                              {:pds-base "https://atproto.etzhayyim.com"}))))

(deftest drain-without-pds-base-is-unchanged
  ;; back-compat: no :pds-base → no :pds-reqs, no :drain/pds-target (today's behaviour)
  (let [q (write-queue (tmpdir) [(line)])
        out (drainer/drain q {:as-of 2606100001 :beat 1})]
    (is (not (contains? out :pds-reqs)))
    (is (empty? (->> (:datoms out) (filter #(= ":drain/pds-target" (nth % 2))))))))

(deftest drain-routes-to-own-pds-when-base-given
  (let [q (write-queue (tmpdir) [(line) (line {"ts" 2000})])
        out (drainer/drain q {:as-of 2606100001 :beat 1
                              :pds-base "https://atproto.etzhayyim.com"
                              :leash "cacao_b64_opaque"})]
    ;; one Path B createRecord request per envelope, each targeting the OWN PDS + carrying the leash
    (is (= 2 (count (:pds-reqs out))))
    (is (every? #(= "https://atproto.etzhayyim.com" (:base %)) (:pds-reqs out)))
    (is (every? #(= "cacao_b64_opaque" (get-in % [:spec :leash])) (:pds-reqs out)))
    (is (every? #(= false (:server-held-key %)) (:pds-reqs out)))
    ;; the routing intent is on the log; status stays :prepared (still dry-run, never sent)
    (is (= ["https://atproto.etzhayyim.com" "https://atproto.etzhayyim.com"]
           (->> (:datoms out) (filter #(= ":drain/pds-target" (nth % 2))) (mapv #(nth % 3)))))
    (is (every? #(= ":prepared" %)
                (->> (:datoms out) (filter #(= ":drain/status" (nth % 2))) (mapv #(nth % 3)))))))
