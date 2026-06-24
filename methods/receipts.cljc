(ns ibuki.methods.receipts
  "receipts — R2: fold member-submission receipts back onto the Datom log.
  ADR-2606101200 §R2. Clojure port of `methods/receipts.py`.

  member-submit produces receipts for records the MEMBER signed and submitted. This module
  is the return edge: receipts become `:receipt/*` datoms, so the log carries the honest,
  attributed history of what actually went out.

  ibuki itself still never asserts `:published` — `:receipt/status` is `:submitted-by-member`,
  which states exactly who acted (the member, with their own key) and what happened. The
  organism's posts stay `:dry-run` (its own beat truth); the receipt is a separate fact about
  the member's act. 縁起: two events, two attributions, one log.

  Closed vocab: a receipt status other than \"submitted-by-member\" throws (this module
  records member submissions, nothing else). Stdlib only. Deterministic. Portable .cljc."
  (:require [clojure.string :as str]
            [ibuki.methods.datoms :as datoms]
            #?(:clj [clojure.java.io :as io])))

(def receipt-status "submitted-by-member")

(def required-keys ["uri" "of" "queueTs" "collection" "submittedBy" "status"])

;; ── read the receipts NDJSON (edge I/O; closed vocab raises, never guesses) ─

#?(:clj
   (defn read-receipts
     "Read a receipts NDJSON file (member-submit write-receipts output). Closed vocab:
     an unknown status throws; a malformed line throws (receipts are evidence, never
     guessed). A missing file is []."
     [path]
     (let [f (io/file (str path))]
       (if-not (.exists f)
         []
         (let [parse (requiring-resolve 'cheshire.core/parse-string)]
           (into []
                 (keep (fn [[i raw]]
                         (let [line (str/trim raw)]
                           (when-not (str/blank? line)
                             (let [r (parse line)
                                   missing (vec (remove #(contains? r %) required-keys))]
                               (when (seq missing)
                                 (throw (ex-info (str "receipt line " i ": missing keys " missing)
                                                 {:ibuki/receipt-error true
                                                  :line i :missing missing})))
                               (when (not= (get r "status") receipt-status)
                                 (throw (ex-info (str "receipt line " i ": unknown status "
                                                      (pr-str (get r "status"))
                                                      " (closed vocab: " (pr-str receipt-status) ")")
                                                 {:ibuki/receipt-error true
                                                  :line i :status (get r "status")})))
                               r)))))
                 (map-indexed vector (str/split-lines (slurp f)))))))))

;; ── :receipt/* datoms (a NEW entity per receipt — append-only attribution) ──

(defn receipt-datoms
  "`:receipt/*` assertions for member-submitted records (a NEW entity per receipt —
  append-only attribution history)."
  [receipts {:keys [beat as-of]}]
  (vec (mapcat
        (fn [i r]
          (let [e (str "receipt-" beat "-" i)]
            [(datoms/add e ":receipt/of" (get r "of"))
             (datoms/add e ":receipt/uri" (get r "uri"))
             (datoms/add e ":receipt/collection" (get r "collection"))
             (datoms/add e ":receipt/queue-ts" (get r "queueTs"))
             (datoms/add e ":receipt/submitted-by" (get r "submittedBy"))
             (datoms/add e ":receipt/status" ":submitted-by-member")
             (datoms/add e ":receipt/beat" beat)
             (datoms/add e ":receipt/as-of" as-of)]))
        (range) receipts)))

;; ── ingest (append one content-addressed tx; verify the chain) ──────────────

#?(:clj
   (defn ingest
     "Append one content-addressed tx of receipt datoms to the log (beat = next tx id).
     Returns {:count :head}."
     [receipts-path log-path]
     (let [receipts (read-receipts receipts-path)
           txs (datoms/read-log log-path)
           beat (inc (count txs))
           as-of (+ 2606100000 beat)
           body (receipt-datoms receipts {:beat beat :as-of as-of})
           tx (datoms/make-tx body {:tx-id beat :as-of as-of
                                    :prev-cid (datoms/head-cid log-path)})]
       (datoms/append-tx! tx log-path)
       (let [chain (datoms/verify-chain log-path)]
         (when-not (:ok chain)
           (throw (ex-info (str "kotoba Datom chain broken: " chain)
                           {:ibuki/chain-broken true :chain chain}))))
       {:count (count receipts) :head (datoms/head-cid log-path)})))
