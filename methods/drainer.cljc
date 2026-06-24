(ns ibuki.methods.drainer
  "drainer — 息吹 (ibuki) Wave-3 post-queue drainer (member-signed, never server-signed).
  ADR-2606101200. Clojure port of `methods/drainer.py`.

  Closes Gap 6 of the organism autonomy survey (\"posts stay in the NDJSON queue — the Wave-3
  drainer was never built\"). Consumes the exact line schema the kotodama NdjsonQueuePostSink
  emits (ADR-2605240100 §Line schema, v=1) and turns each line into a
  `com.atproto.repo.createRecord`-shaped ENVELOPE that is ready for a MEMBER to sign.

  The no-server-key invariant (ADR-2605231525) is structural, not configurational:

    - an envelope always carries requiresMemberSignature:true + serverHeldKey:false;
    - this module holds NO credential, reads NO key material, and has NO network path;
    - `submit` only forwards envelopes to an externally-INJECTED `:member-signer` callable
      (the member's own runtime) and refuses without one AND an explicit `:operator-ack true`.
      Absent both, posting is impossible, not merely disabled.

  Drained envelopes are checkpointed to the kotoba log as :drain/* datoms with status
  :prepared — :published is NOT writable by ibuki. Stdlib only. Deterministic. Portable .cljc."
  (:require [clojure.string :as str]
            [ibuki.methods.datoms :as datoms]
            #?(:clj [clojure.java.io :as io])))

(def schema-version 1)

(def required-keys
  ["v" "ts" "actorDid" "mood" "contentSourceKind" "text" "lexicon" "createdAt"])

;; ── the no-server-key marker (drainer.MemberSignatureRequired) ────────────

(defn member-signature-required
  "Submission attempted without an injected member signer + operator ack
  (ADR-2605231525 no-server-key)."
  [msg]
  (ex-info msg {:ibuki/member-signature-required true}))

(defn member-signature-required? [e]
  (boolean (:ibuki/member-signature-required (ex-data e))))

;; ── parse the NDJSON post queue (edge I/O) ────────────────────────────────

#?(:clj
   (defn parse-queue
     "Read the NDJSON post queue. Returns [valid-posts rejection-reasons]. Unknown schema
     versions and missing keys are rejected, never guessed."
     [queue-path]
     (let [f (io/file (str queue-path))]
       (if-not (.exists f)
         [[] []]
         (let [parse (requiring-resolve 'cheshire.core/parse-string)]
           (loop [lines (str/split-lines (slurp f)) i 0 posts [] errors []]
             (if (empty? lines)
               [posts errors]
               (let [line (str/trim (first lines))
                     rst (rest lines)]
                 (cond
                   (str/blank? line)
                   (recur rst (inc i) posts errors)
                   :else
                   (let [obj (try (parse line) (catch #?(:clj Exception :default :default) _ ::bad))]
                     (cond
                       (= obj ::bad)
                       (recur rst (inc i) posts
                              (conj errors (str "line " i ": not JSON")))
                       (not= (get obj "v") schema-version)
                       (recur rst (inc i) posts
                              (conj errors (str "line " i ": unknown schema version "
                                                (pr-str (get obj "v")))))
                       :else
                       (let [missing (vec (remove #(contains? obj %) required-keys))]
                         (if (seq missing)
                           (recur rst (inc i) posts
                                  (conj errors (str "line " i ": missing keys " missing)))
                           (recur rst (inc i) (conj posts obj) errors))))))))))))))

;; ── envelope (member-sign-ready createRecord) ────────────────────────────

(defn envelope
  "One member-sign-ready createRecord envelope for one queue line. The platform never signs:
  requiresMemberSignature/serverHeldKey are structural constants."
  [post]
  {"xrpc" "com.atproto.repo.createRecord"
   "repo" (get post "actorDid")
   "collection" (get post "lexicon")
   "record" {"$type" (get post "lexicon")
             "text" (get post "text")
             "createdAt" (get post "createdAt")}
   "queueTs" (get post "ts")
   "requiresMemberSignature" true
   "serverHeldKey" false})

;; ── drain → envelopes + :drain/* datoms (status :prepared only) ───────────

#?(:clj
   (defn drain
     "Drain the queue into envelopes + :drain/* datoms (status :prepared — the ONLY status this
     module can write). Returns {:envelopes :datoms :errors}."
     [queue-path {:keys [as-of beat]}]
     (let [[posts errors] (parse-queue queue-path)
           envelopes (mapv envelope posts)
           out (vec (mapcat
                     (fn [i p env]
                       (let [e (str "drain-" beat "-" i)]
                         [(datoms/add e ":drain/of" (get p "actorDid"))
                          (datoms/add e ":drain/lexicon" (get env "collection"))
                          (datoms/add e ":drain/queue-ts" (get p "ts"))
                          (datoms/add e ":drain/status" ":prepared")
                          (datoms/add e ":drain/requires-member-sig" true)
                          (datoms/add e ":drain/server-held-key" false)
                          (datoms/add e ":drain/beat" beat)
                          (datoms/add e ":drain/as-of" as-of)]))
                     (range) posts envelopes))]
       {:envelopes envelopes :datoms out :errors errors})))

;; ── submit (forward to the MEMBER's own signing runtime) ──────────────────

(defn submit
  "Forward envelopes to the MEMBER's own signing runtime. Refuses without an injected
  `:member-signer` + an explicit `:operator-ack true` — ibuki holds no key, so absent
  injection, live posting is structurally impossible (G7/G8)."
  ([envelopes] (submit envelopes {}))
  ([envelopes {:keys [member-signer operator-ack] :or {operator-ack false}}]
   (when (nil? member-signer)
     (throw (member-signature-required
             (str "no member signer injected — the platform holds no key (ADR-2605231525); "
                  "posting requires the member's own signing runtime"))))
   (when-not operator-ack
     (throw (member-signature-required
             "operator_ack=True required — outward posting is operator-gated (G8)")))
   (mapv (fn [env] (member-signer env)) envelopes)))
