(ns ibuki.methods.member-submit
  "member-submit — 息吹 (ibuki) R2: the member-principal live-posting runtime.
  ADR-2606101200 §R2. Clojure port of `methods/member_submit.py`.

  The Wave-3 drainer (drainer.cljc) prepares member-sign-ready envelopes and structurally
  cannot post. THIS namespace is the other half: the runtime a MEMBER runs, with the
  member's OWN credentials, to sign and submit those envelopes to the member's OWN PDS.
  The no-server-key invariant (ADR-2605231525) is kept structural, not waived, by
  construction:

    - credentials come ONLY from the member's runtime env (IBUKI_MEMBER_HANDLE /
      IBUKI_MEMBER_APP_PASSWORD / IBUKI_MEMBER_PDS) at invocation time — nothing is
      committed, cached, or platform-held; missing env → member-signature-required;
    - it REFUSES to run in a scheduled/cron context (IBUKI_CRON=1 → refusal): a platform
      CronJob may never hold member credentials. This is the member at their own keyboard
      (the karakuri/okaimono member-principal model), not the platform acting;
    - submission still flows through `drainer/submit` — the same injected-signer +
      explicit-operator-ack gate the structural tests pin down;
    - ibuki itself never asserts :published. Submission produces RECEIPTS (receipts.cljc)
      attributed to the member — `:receipt/status :submitted-by-member` — which is what
      actually happened.

  Per the founder direction of 2026-06-10 the Council gate is exercised as PR review+merge:
  this code path is complete to the end; *running* it remains a member's explicit act.

  HTTP is an explicit INJECTABLE transport (`*transport*` dynamic var or the `:transport`
  option key); the portable namespace has no ambient network authority. Environment reads go
  through the injectable `*env*` edge (nil → the real process env; a map → the test env),
  so the refusal paths are exercisable exactly as the Python tests exercise os.environ."
  (:require #?(:clj [cheshire.core :as json])
            [clojure.string :as str]
            [ibuki.methods.drainer :as drainer]
            #?(:clj [clojure.java.io :as io])))

(def env-handle "IBUKI_MEMBER_HANDLE")
(def env-app-password "IBUKI_MEMBER_APP_PASSWORD")
(def env-pds "IBUKI_MEMBER_PDS")
(def env-cron "IBUKI_CRON")

(def default-pds "https://bsky.social")

;; ── injectable environment edge ───────────────────────────────────────────

(def ^:dynamic *env*
  "Injectable environment edge: nil → the real process env; a map → the injected env.
  The ONLY credential source is the member's own IBUKI_MEMBER_* env at runtime —
  nothing platform-held, nothing committed (ADR-2605231525)."
  nil)

(defn- env-get [k]
  (if (some? *env*)
    (get *env* k)
    #?(:clj (System/getenv ^String k) :default nil)))

;; ── explicit https transport ──────────────────────────────────────────────
;;
;; Contract: (transport url body headers) → the PDS's JSON response parsed to a
;; Clojure map with STRING keys (wire shape). body nil → GET, else POST.

(defn- assert-pds-url [url]
  (when-not (str/starts-with? (str url) "https://")
    (throw (drainer/member-signature-required
            (str "member PDS must be https, got " (pr-str url))))))

(defn http-json
  "Compatibility guard retained for callers: validates the PDS boundary, then
  fails closed because host I/O must be supplied as an explicit transport."
  ([url] (http-json url nil nil))
  ([url body] (http-json url body nil))
  ([url _body _headers]
   (assert-pds-url url)
   (throw (drainer/member-signature-required
           "member PDS access requires an explicit transport capability"))))

(defn- require-transport [transport]
  (when-not (fn? transport)
    (throw (drainer/member-signature-required
            "member PDS access requires an explicit transport capability")))
  transport)

(def ^:dynamic *transport*
  "The injectable PDS transport (see contract above). Rebind, or pass `:transport`
  in the options map, to stub the member's PDS in tests / other hosts."
  nil)

;; ── the G7 structural refusals ────────────────────────────────────────────

(defn refuse-if-cron
  "A scheduled platform context may never hold member credentials (G7)."
  []
  (when (= "1" (env-get env-cron))
    (throw (drainer/member-signature-required
            (str "refusing to submit from a cron/scheduled context (IBUKI_CRON=1) — member "
                 "credentials belong to the member's own interactive runtime, never a platform "
                 "job (ADR-2605231525)")))))

;; ── the member's own session ──────────────────────────────────────────────

(defn create-member-session
  "com.atproto.server.createSession with the MEMBER's own env credentials. Returns
  {\"pds\" \"did\" \"handle\" \"accessJwt\"} (wire-shaped string keys). Missing env →
  member-signature-required (the platform holds no key to fall back to).
  Options: :transport."
  ([] (create-member-session {}))
  ([{:keys [transport]}]
   (refuse-if-cron)
   (let [handle (or (env-get env-handle) "")
         password (or (env-get env-app-password) "")]
     (when (or (= "" handle) (= "" password))
       (throw (drainer/member-signature-required
               (str "no member credentials in env (" env-handle " / " env-app-password ") — the "
                    "platform holds no key; the member supplies their own session at runtime"))))
     (let [pds (str/replace (or (env-get env-pds) default-pds) #"/+$" "")
           _ (assert-pds-url pds)
           t (require-transport (or transport *transport*))
           out (t (str pds "/xrpc/com.atproto.server.createSession")
                  {"identifier" handle "password" password}
                  nil)]
       {"pds" pds
        "did" (get out "did")
        "handle" (get out "handle" handle)
        "accessJwt" (get out "accessJwt")}))))

(defn member-signer
  "The injectable signer drainer/submit requires: one envelope → one member-signed
  com.atproto.repo.createRecord on the MEMBER's PDS → one receipt.
  Options: :transport."
  ([session] (member-signer session {}))
  ([session {:keys [transport]}]
   (let [t (require-transport (or transport *transport*))]
     (fn sign [envelope]
       (let [out (t (str (get session "pds") "/xrpc/com.atproto.repo.createRecord")
                    {"repo" (get session "did")
                     "collection" (get envelope "collection")
                     "record" (get envelope "record")}
                    {"Authorization" (str "Bearer " (get session "accessJwt"))})]
         {"uri" (get out "uri" "")
          "cid" (get out "cid" "")
          "of" (get envelope "repo")
          "queueTs" (get envelope "queueTs")
          "collection" (get envelope "collection")
          "submittedBy" (get session "did")
          "status" "submitted-by-member"})))))

;; ── submit the queue AS THE MEMBER ────────────────────────────────────────

#?(:clj
   (defn submit-queue
     "Submit the valid queue posts with index ≥ :from-line AS THE MEMBER. Returns
     {:receipts :errors :next-line} where :next-line is the post index to resume from.
     Flows through drainer/submit's structural gate (injected signer + operator ack).
     Options: :from-line :operator-ack :transport :session."
     ([queue-path] (submit-queue queue-path {}))
     ([queue-path {:keys [from-line operator-ack transport session]
                   :or {from-line 0 operator-ack false}}]
      (refuse-if-cron)
      (let [[posts errors] (drainer/parse-queue queue-path)
            pending (vec (drop from-line posts))
            envelopes (mapv drainer/envelope pending)
            sess (or session (create-member-session {:transport transport}))
            receipts (drainer/submit envelopes
                                     {:member-signer (member-signer sess {:transport transport})
                                      :operator-ack operator-ack})]
        {:receipts receipts
         :errors errors
         :next-line (+ from-line (count pending))}))))

;; the ONLY keys a receipt file may carry — a structural whitelist so no credential-shaped
;; field (password / accessJwt / Authorization) can ever reach disk, whatever a future
;; receipt map accidentally contains
(def receipt-file-keys ["uri" "cid" "of" "queueTs" "collection" "submittedBy" "status"])

#?(:clj
   (defn write-receipts
     "Append receipts as NDJSON (receipts.cljc folds them onto the Datom log). Each line is
     rebuilt from the receipt-file-keys whitelist — credentials are unrepresentable in this
     file by construction. Returns the receipt count."
     [receipts path]
     (let [generate json/generate-string
           f (io/file (str path))]
       (when-let [parent (.getParentFile f)]
         (.mkdirs parent))
       (with-open [w (io/writer f :append true)]
         (doseq [r receipts]
           (.write w (str (generate (into (sorted-map) (select-keys r receipt-file-keys)))
                          "\n"))))
       (count receipts))))
