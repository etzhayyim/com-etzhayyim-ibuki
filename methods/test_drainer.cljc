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
  (let [src (slurp (io/resource "ibuki/methods/drainer.cljc"))]
    (doseq [needle ["token" "secret" "PRIVATE_KEY" "urllib" "requests" "http.client"]]
      (is (not (str/includes? src needle))
          (str "drainer must stay credential-free + offline: " needle)))))
