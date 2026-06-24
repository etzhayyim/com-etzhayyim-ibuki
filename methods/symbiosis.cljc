(ns ibuki.methods.symbiosis
  "symbiosis — the 共生 ledger: humanity draws the colony's commons output.
  ADR-2606101200 §共生. Clojure port of `methods/symbiosis.py`.

  The colony LIVES and, as a byproduct (黒カビ→クエン酸), excretes a refined commons metabolite —
  and HUMANITY consumes it in symbiosis. The food web (ecosystem) builds the producing side;
  this module is the consuming side, kept honest:

    - `commons-pool` — the standing commons available to humanity = Σ offered commons metabolite
      nutrient − Σ already-drawn. Log-derived, deterministic (like moyai 入会権 commons-draw-rights).
    - `draw` — a MEMBER draws from the pool. Member-principal + operator-gated, exactly the
      no-server-key discipline of member-submit (drainer/member-signature-required): no member
      signer + operator ack → refusal. ibuki NEVER auto-draws. A draw cannot exceed the pool.
      Records a :symbiosis/draw datom ATTRIBUTED to the drawing member.

  So the symbiosis is real and measured (offer vs draw) yet un-fakeable: offers are the colony's
  byproduct on the log; draws exist only when a member actually took the gift. Stdlib only.
  Deterministic. Append-only."
  (:require [ibuki.methods.datoms :as datoms]
            [ibuki.methods.drainer :as drainer]))

(defn commons-pool
  "The standing commons pool (single pass, fleet-scale safe). Returns {:offered :drawn :available}
  in nutrient units. offered = Σ commons metabolite nutrient; drawn = Σ :symbiosis/amount;
  available = offered − drawn (≥0)."
  [txs]
  (let [all (mapcat :tx/datoms txs)
        [meta drawn]
        (reduce (fn [[meta drawn] [_op e a v]]
                  (cond
                    (= a ":metabolite/kind")     [(assoc-in meta [e :kind] v) drawn]
                    (= a ":metabolite/commons")  [(assoc-in meta [e :commons] v) drawn]
                    (= a ":metabolite/nutrient") [(assoc-in meta [e :nutrient] v) drawn]
                    (= a ":symbiosis/amount")    [meta (+ drawn v)]
                    :else                        [meta drawn]))
                [{} 0]
                all)
        offered (reduce-kv (fn [acc _e m]
                             (if (and (= ":refined" (:kind m)) (true? (:commons m)))
                               (+ acc (get m :nutrient 0))
                               acc))
                           0
                           meta)]
    {:offered offered :drawn drawn :available (max 0 (- offered drawn))}))

(defn draw
  "A MEMBER draws `amount` nutrient of commons from the standing pool. Member-principal +
  operator-gated (no-server-key, ADR-2605231525): refuses without an injected `:member-signer`
  AND an explicit `:operator-ack true` — ibuki holds no key and never auto-draws. Refuses if the
  draw exceeds the available pool. Returns {:datoms :receipt :available-after}; the
  :symbiosis/draw datom is ATTRIBUTED to the member (a human benefit is never fabricated)."
  [txs amount {:keys [member beat as-of member-signer operator-ack] :or {operator-ack false}}]
  (when (nil? member-signer)
    (throw (drainer/member-signature-required
            (str "no member signer injected — the platform holds no key and never draws the "
                 "colony's commons on a human's behalf (ADR-2605231525)"))))
  (when-not operator-ack
    (throw (drainer/member-signature-required
            (str "operator_ack=True required — a commons draw is an outward, member-principal "
                 "act (G8)"))))
  (when (<= amount 0)
    (throw (ex-info "draw amount must be positive" {:ibuki/invalid-draw true})))
  (let [pool (commons-pool txs)]
    (when (> amount (:available pool))
      (throw (ex-info (str "draw " amount " exceeds available commons pool " (:available pool))
                      {:ibuki/invalid-draw true})))
    ;; the member signs the draw with their OWN runtime (like member-submit); ibuki only
    ;; records what the member attests
    (let [receipt (member-signer {"kind" "symbiosis-draw" "member" member
                                  "amount" amount "beat" beat})
          e (str "symbiosis-draw-" member "-" beat)
          ds [(datoms/add e ":symbiosis/by" member)
              (datoms/add e ":symbiosis/amount" amount)
              (datoms/add e ":symbiosis/kind" ":draw")
              (datoms/add e ":symbiosis/drawn-by-member" true)  ;; never platform-drawn (structural)
              (datoms/add e ":symbiosis/beat" beat)
              (datoms/add e ":symbiosis/as-of" as-of)]]
      {:datoms ds :receipt receipt :available-after (- (:available pool) amount)})))
