(ns ibuki.methods.test-member-submit
  "test-member-submit — 息吹 (ibuki) R2 member-principal submission + receipts.
  ADR-2606101200 §R2 + ADR-2605231525 (no-server-key). Clojure port of
  `methods/test_member_submit.py`.

  The structural claims under test: the platform can NEVER post (no env → refusal;
  cron → refusal; no ack → refusal), and when the MEMBER posts with their own
  credentials, the log records an attributed receipt — never a `:published`
  asserted by ibuki."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ibuki.methods.datoms :as datoms]
            [ibuki.methods.member-submit :as ms]
            [ibuki.methods.receipts :as rc]))

(defn- tmpdir []
  (str (java.nio.file.Files/createTempDirectory
        "ibuki-member-submit" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- queue-file
  "Write an n-line valid post queue (wire-format string keys) into dir."
  ([dir] (queue-file dir 2))
  ([dir n]
   (let [q (io/file dir "queue.ndjson")]
     (spit q (str (str/join "\n"
                            (for [i (range n)]
                              (json/generate-string
                               {"v" 1 "ts" (+ 1000 i)
                                "actorDid" (str "did:web:etzhayyim.com:actor:c" i)
                                "code" (str "c" i) "title" (str "Organism " i)
                                "mood" "calm" "contentSourceKind" "recordAnalysis"
                                "text" (str "観測 " i)
                                "lexicon" "app.bsky.feed.post"
                                "createdAt" "2026-06-10T00:00:00.000Z"})))
                  "\n"))
     q)))

(def ^:private member-env
  {ms/env-handle "member.example.com"
   ms/env-app-password "xxxx-test-app-password"
   ms/env-pds "https://pds.example.com"})

(defn- fake-transport
  ;; NOTE: the fake PDS answers with literals only — it must not echo request-body
  ;; fields back (a body echo would route the createSession password into the
  ;; session→receipt flow and trip clear-text-storage taint analysis; a real PDS
  ;; never returns the password either).
  [calls]
  (fn [url body headers]
    (swap! calls conj {:url url :body body :headers (or headers {})})
    (cond
      (str/ends-with? url "com.atproto.server.createSession")
      {"did" "did:plc:member123" "handle" "member.example.com"
       "accessJwt" "jwt-test"}

      (str/ends-with? url "com.atproto.repo.createRecord")
      {"uri" (str "at://did:plc:member123/post/" (count @calls))
       "cid" (str "bafyfake" (count @calls))}

      :else
      (throw (ex-info (str "unexpected url " url) {:url url})))))

(deftest no-env-means-no-session
  (binding [ms/*env* {}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no member credentials"
                          (ms/create-member-session)))))

(deftest cron-context-refused-even-with-env
  (binding [ms/*env* (assoc member-env ms/env-cron "1")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cron"
                          (ms/create-member-session)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cron"
                          (ms/submit-queue "/nonexistent")))))

(deftest http-pds-refused
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"https"
                        (ms/http-json "http://pds.example.com/xrpc/x" {}))))

(deftest member-session-without-transport-capability-fails-closed
  (binding [ms/*env* member-env]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"explicit transport capability"
                          (ms/create-member-session)))))

(deftest submit-without-ack-refused
  (binding [ms/*env* member-env]
    (let [q (queue-file (tmpdir))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"operator_ack"
                            (ms/submit-queue q {:transport (fake-transport (atom []))}))))))

(deftest member-submits-and-receipts-attribute-the-member
  (binding [ms/*env* member-env]
    (let [calls (atom [])
          q (queue-file (tmpdir) 2)
          res (ms/submit-queue q {:operator-ack true :transport (fake-transport calls)})]
      (is (= 2 (count (:receipts res))))
      (is (= 2 (:next-line res)))
      ;; session created with the MEMBER's creds, records on the MEMBER's repo
      (is (str/starts-with? (:url (first @calls)) "https://pds.example.com/"))
      (is (= "did:plc:member123" (get-in (second @calls) [:body "repo"])))
      (is (= "Bearer jwt-test" (get-in (second @calls) [:headers "Authorization"])))
      (let [r (first (:receipts res))]
        (is (= "submitted-by-member" (get r "status")))
        (is (= "did:plc:member123" (get r "submittedBy")))
        (is (str/starts-with? (get r "of") "did:web:etzhayyim.com:actor:"))))))

(deftest resume-from-line-skips-submitted
  (binding [ms/*env* member-env]
    (let [q (queue-file (tmpdir) 3)
          res (ms/submit-queue q {:from-line 2 :operator-ack true
                                  :transport (fake-transport (atom []))})]
      (is (= 1 (count (:receipts res))))
      (is (= 3 (:next-line res))))))

(deftest receipts-roundtrip-to-datoms
  (binding [ms/*env* member-env]
    (let [dir (tmpdir)
          q (queue-file dir 2)
          res (ms/submit-queue q {:operator-ack true
                                  :transport (fake-transport (atom []))})
          rpath (io/file dir "receipts.ndjson")]
      (is (= 2 (ms/write-receipts (:receipts res) rpath)))
      (let [back (rc/read-receipts rpath)]
        (is (= 2 (count back)))
        (let [ds (rc/receipt-datoms back {:beat 1 :as-of 2606100001})]
          ;; ibuki never says :published
          (is (= #{":submitted-by-member"}
                 (set (for [d ds :when (= ":receipt/status" (nth d 2))] (nth d 3)))))
          (is (= #{"did:plc:member123"}
                 (set (for [d ds :when (= ":receipt/submitted-by" (nth d 2))] (nth d 3))))))))))

(deftest receipts-ingest-appends-verified-tx
  (binding [ms/*env* member-env]
    (let [dir (tmpdir)
          q (queue-file dir 1)
          res (ms/submit-queue q {:operator-ack true
                                  :transport (fake-transport (atom []))})
          rpath (io/file dir "receipts.ndjson")
          _ (ms/write-receipts (:receipts res) rpath)
          log (io/file dir "log.edn")
          out (rc/ingest rpath log)]
      (is (= 1 (:count out)))
      (is (true? (:ok (datoms/verify-chain log)))))))

(deftest unknown-receipt-status-raises
  (let [p (io/file (tmpdir) "r.ndjson")]
    (spit p (str (json/generate-string
                  {"uri" "at://x" "of" "did:web:x" "queueTs" 1
                   "collection" "app.bsky.feed.post"
                   "submittedBy" "did:plc:m"
                   "status" "published"})
                 "\n"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed vocab"
                          (rc/read-receipts p)))))

(deftest no-committed-credentials-in-source
  (let [src (slurp (or (io/resource "ibuki/methods/member_submit.cljc")
                       (io/file "methods/member_submit.cljc")))]
    ;; credentials may only ARRIVE via the member's env at runtime — never literals
    (doseq [needle ["Bearer ey" "did:plc:" "xxxx-"]]
      (is (not (str/includes? src needle))
          (str "member-submit must not embed a credential: " needle)))
    ;; the only credential source is the member's env
    (is (str/includes? src "IBUKI_MEMBER_"))))
