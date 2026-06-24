(ns ibuki.methods.datoms
  "ibuki 息吹 — kotoba Datom-log writer + as-of reader. ADR-2606101200 + ADR-2605312345.
  Clojure port of `methods/datoms.py`.

  The substrate boundary (root CLAUDE.md): canonical state is the **kotoba Datom log** —
  content-addressed EAVT assertions, append-only (非終末論). This module is ibuki's write
  AND read path onto that log: organism state (joucho mood / heartbeat cadence / lifecycle /
  posts / drain envelopes / kaizen feedback) lives HERE, not in ephemeral host maps.

  The content-addressed chain layer (tx-cid / make-tx / append-tx! / read-log / head-cid /
  verify-chain) is the SHARED `kotoba.datom` implementation — byte-compatible with the
  Python canonical-JSON+sha256 pattern (sha256 over {\"prev\",\"datoms\"} → commit-DAG), so
  a log written by either implementation verifies under the other and crash-resume stays
  byte-identical (the ibuki invariant). This namespace re-exposes that chain layer and adds
  the ibuki-specific as-of readers:

    fold-entity  (txs e)   → latest attr→value map for one entity (optionally as-of a tx)
    entities     (txs a)   → every entity carrying attr (optionally as-of a tx)
    events-for   (txs of)  → ordered :joucho.event/* stream for one organism (the
                             replayable mood history — \"what was the mood at tx N\")

  EAVT = [op entity attribute value]; op is :db/add only (append-only — no retraction op
  exists, non-eschatological). Deterministic (caller supplies tx-id + as-of; no wall clock)."
  (:require [kotoba.datom :as kd]))

;; ── content-addressed transaction chain (REUSED: kotoba.datom) ────────────

(def add
  "One append-only EAVT assertion: [\":db/add\" <entity> <attr> <value>]."
  kd/add)

(def tx-cid
  "Content address of a transaction = sha256 over (prev-cid, datoms). Linking prev-cid in
  makes the log a commit-DAG (a tamper of any earlier tx breaks every later CID).
  (tx-cid datoms) or (tx-cid datoms prev-cid)."
  kd/tx-cid)

(defn make-tx
  "Build a content-addressed transaction. tx-id + as-of are supplied by the caller (no wall
  clock — keeps the log deterministic + resume-safe).
  (make-tx datoms {:tx-id n :as-of n :prev-cid \"\"})."
  [datoms {:keys [tx-id as-of prev-cid] :or {prev-cid ""} :as opts}]
  (kd/make-tx datoms (assoc opts :prev-cid prev-cid)))

#?(:clj
   (def append-tx!
     "Append ONE transaction to the append-only log (never rewrites existing lines).
     Returns the tx CID. The only mutation: the log only ever grows (非終末論)."
     kd/append-tx!))

#?(:clj
   (def read-log
     "Read the log back as a vector of transaction maps (datoms normalized to \":…\" strings)."
     kd/read-log))

#?(:clj
   (def head-cid
     "The content-addressed HEAD = the last transaction's CID (\"\" on an empty log)."
     kd/head-cid))

#?(:clj
   (def verify-chain
     "Recompute every CID from its datoms + prev and verify the DAG is intact (no tampering).
     Returns {:ok :length :broken-at}. The integrity proof of the append-only log."
     kd/verify-chain))

;; ── as-of readers (the EAVT fold — Gap 3 closure) ─────────────────────────

(defn- datoms-up-to
  "All [op e a v] datoms over the log in transaction order, optionally bounded by an
  inclusive tx id — the as-of cut."
  [txs up-to-tx]
  (for [tx txs
        :when (or (nil? up-to-tx) (<= (:tx/id tx) up-to-tx))
        d (:tx/datoms tx)]
    d))

(defn fold-entity
  "Latest attr→value map for one entity as of tx :up-to-tx (append-only fold: a later
  assertion of the same attr shadows the earlier one; history stays in the log)."
  ([txs entity] (fold-entity txs entity nil))
  ([txs entity {:keys [up-to-tx]}]
   (reduce (fn [out [_op e a v]]
             (if (= e entity) (assoc out a v) out))
           {}
           (datoms-up-to txs up-to-tx))))

(defn entities
  "Every entity (first-assertion order, deduped) carrying `attr` as of :up-to-tx."
  ([txs attr] (entities txs attr nil))
  ([txs attr {:keys [up-to-tx]}]
   (second
    (reduce (fn [[seen order :as acc] [_op e a _v]]
              (if (and (= a attr) (not (seen e)))
                [(conj seen e) (conj order e)]
                acc))
            [#{} []]
            (datoms-up-to txs up-to-tx)))))

(defn events-for
  "Ordered :joucho.event/kind stream for one organism — the replayable mood history.
  `(joucho/replay-events baseline (events-for …))` answers \"what was the mood at tx N\"."
  ([txs of] (events-for txs of nil))
  ([txs of {:keys [up-to-tx]}]
   (let [[by-entity order]
         (reduce (fn [[m order :as acc] [_op e a v]]
                   (if (#{":joucho.event/of" ":joucho.event/kind"} a)
                     [(assoc-in m [e a] v)
                      (if (contains? m e) order (conj order e))]
                     acc))
                 [{} []]
                 (datoms-up-to txs up-to-tx))]
     (into []
           (keep (fn [e]
                   (let [ev (by-entity e)]
                     (when (and (= of (get ev ":joucho.event/of"))
                                (contains? ev ":joucho.event/kind"))
                       (get ev ":joucho.event/kind")))))
           order))))
