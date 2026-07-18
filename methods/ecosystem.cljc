(ns ibuki.methods.ecosystem
  "ecosystem — the colony is an ECOSYSTEM, not a bag of individuals. ADR-2606101200 §生態系.
  Clojure port of `methods/ecosystem.py`.

  The founder's image: not single organisms but an ecosystem — like Aspergillus niger (黒カビ)
  excreting citric acid as a metabolic byproduct, the colony should LIVE and, as a byproduct of
  living, produce something useful that humanity consumes in symbiosis (共生). Organisms occupy
  differentiated NICHES (生態的地位):

    - 植物 / producer   — primary production: emits a `:metabolite/substrate` each beat.
    - 粘菌 / router     — Physarum relay of the highest-nutrient available substrate.
    - カビ / decomposer — saprotroph: excretes a refined `:metabolite/commons` (the citric acid).

  A trophic cascade per cycle: producers → (router relay) → decomposers → commons. Each step is
  an append-only `:exchange/*` / `:metabolite/*` edge. A producer whose substrate is consumed
  earns a `:event/symbiosis-fed` joucho event (mutualism). Satiation, detritus recycling, and
  stigmergy (adaptive Physarum trails) round out the web. Deterministic. Append-only. Pure."
  (:refer-clojure :exclude [cycle])
  (:require [clojure.string :as str]
            [ibuki.methods.datoms :as datoms]
            [ibuki.methods.joucho :as joucho]))

;; closed vocab — an unknown niche raises (an unrepresentable role cannot enter the web)
(def niches [":niche/producer" ":niche/router" ":niche/decomposer"])

;; the mutualism reward folded into a fed producer's mood (small; idle drift + the health
;; saturation guard keep gratitude from pinning). NOT in joucho/event-deltas by default —
;; ecosystem registers it (below) so joucho stays usable without the eco layer.
(def symbiosis-event ":event/symbiosis-fed")
(def symbiosis-delta [1 1 -1 1 0])   ;; joy calm stress gratitude focus

;; satiation: a producer fed within the last SATIATION beats is sated — the 粘菌 router routes
;; to a hungrier producer instead. Lets the homeostatic idle-drift budget counter other upward
;; pressures, so a fed producer EQUILIBRATES instead of saturating.
(def satiation 2)

;; detritus recycling: substrate no router relayed this beat is DEAD MATTER. Decomposers recycle
;; the unrelayed detritus into commons at a LOSSY yield (decomposition is never 100%). Closes
;; the matter loop (非終末論). Detritus does NOT feed its producer (no mood pressure).
(def detritus-yield-num 1)
(def detritus-yield-den 2)   ;; recovered nutrient = floor(n * 1/2)

;; stigmergy (粘菌 trail): a real Physarum reinforces the tube along a path that carried flux,
;; and the chemical trail EVAPORATES. Trail at beat B = Σ TRAIL_DECAY^(B − relay_beat); routing
;; prefers nutrient × (1 + trail), so the router is an ADAPTIVE optimizer with memory.
(def trail-decay 0.5)   ;; evaporation: a relay's trail halves each beat
(def trail-horizon 8)   ;; ignore relays older than this (decayed below ~1/256 — negligible)

;; ── register :event/symbiosis-fed as a first-class joucho event (idempotent) ──
;; Called at load so replays that include eco events fold correctly; joucho stays eco-agnostic.
#?(:clj
   (defn register-symbiosis-event!
     "Make `:event/symbiosis-fed` a first-class joucho event (idempotent)."
     []
     (when-not (contains? joucho/event-deltas symbiosis-event)
       (alter-var-root #'joucho/event-deltas assoc symbiosis-event symbiosis-delta))))

#?(:clj (register-symbiosis-event!))

(defn niche-of
  "The organism's trophic niche: seed-declared if present, else deterministic by hash (the
  18,342-fleet self-differentiates with no central assignment)."
  ([code] (niche-of code nil))
  ([code declared]
   (if (some? declared)
     (do (when-not (some #{declared} niches)
           (throw (ex-info (str "unknown niche (closed vocab " niches "): " declared)
                           {:niche declared})))
         declared)
     (let [h (joucho/*sha256-bytes* (str "niche:" code))
           n (reduce (fn [acc i] (+ (* 256 acc) (h i))) 0 (range 4))]
       (nth niches (mod n (count niches)))))))

(defn nutrient
  "Primary-production richness of a substrate = how flourishing the producer is right now
  (joy + gratitude − stress, floored at 0). A stressed organism fixes less."
  [scores]
  (max 0 (+ (:joy scores) (:gratitude scores) (- (:stress scores)))))

(defn satiated-producers
  "Producers fed (a `:event/symbiosis-fed`) within the last SATIATION beats — the router will
  skip them. Log-derived, deterministic. Returns a set of producer codes."
  [txs beat]
  (reduce (fn [sated [_e ev]]
            (if (and (= (get ev ":joucho.event/kind") symbiosis-event)
                     (<= (- beat (get ev ":joucho.event/beat" (- (- satiation) 1))) satiation))
              (conj sated (get ev ":joucho.event/of"))
              sated))
          #{}
          (datoms/fold-entities txs ":joucho.event/kind")))

(defn trail-strengths
  "Per (producer, decomposer) Physarum trail at `beat`, read off past relay edges and decayed
  by age (evaporation). Single pass, deterministic. Older than TRAIL_HORIZON beats is ignored.
  Returns a map {[from to] strength}."
  [txs beat]
  (let [edges (reduce (fn [edges [_op e a v]]
                        (if (and (str/starts-with? a ":exchange/")
                                 (str/starts-with? e "eco-relay-"))
                          (assoc-in edges [e a] v)
                          edges))
                      {}
                      (mapcat :tx/datoms txs))]
    (reduce (fn [trail ed]
              (if (not= (get ed ":exchange/kind") ":relay")
                trail
                (let [age (- beat (get ed ":exchange/beat" (- (long 1e9))))]
                  (if (or (<= age 0) (> age trail-horizon))
                    trail
                    (let [pair [(get ed ":exchange/from") (get ed ":exchange/to")]]
                      (update trail pair (fnil + 0.0) (Math/pow trail-decay age)))))))
            {}
            (vals edges))))

(defn cycle
  "One trophic cascade over the colony. `organisms` = [{:code :niche?} …]; `moods` = code →
  current scores map (from the beat's fold). Returns {:datoms :refined :fed :roles}.

  `:datoms` are append-only `:metabolite/*` + `:exchange/*` ONLY — the cascade is purely
  metabolic. `:fed` is the list of producer codes whose substrate was consumed; the CALLER folds
  `:event/symbiosis-fed` into those producers' SAME-beat event stream + checkpoint. Pure +
  deterministic. Opts: :beat :as-of :satiated :trails."
  [organisms moods {:keys [beat as-of satiated trails] :or {satiated #{} trails {}}}]
  (let [roles0 (reduce (fn [r org]
                         (update r (niche-of (:code org) (:niche org)) (fnil conj []) (:code org)))
                       (zipmap niches (repeat []))
                       organisms)
        roles (into {} (map (fn [n] [n (vec (sort (get roles0 n)))])) niches)
        producers (get roles ":niche/producer")
        routers (get roles ":niche/router")
        decomposers (get roles ":niche/decomposer")

        ;; ── primary production (植物): each producer fixes a substrate this beat ──
        substrates (mapv (fn [code]
                           {:sub (str "eco-sub-" code "-" beat)
                            :prod code
                            :n (nutrient (get moods code joucho/default-scores))})
                         producers)
        prod-datoms (vec (mapcat (fn [{:keys [sub prod n]}]
                                   [(datoms/add sub ":metabolite/kind" ":substrate")
                                    (datoms/add sub ":metabolite/of" prod)
                                    (datoms/add sub ":metabolite/nutrient" n)
                                    (datoms/add sub ":metabolite/beat" beat)
                                    (datoms/add sub ":metabolite/as-of" as-of)])
                                 substrates))

        ;; ── 粘菌 routing (stigmergy): each router relays the richest HUNGRY substrate along the
        ;; path with the strongest established trail — preference = nutrient × (1 + trail). ──
        hungry (->> substrates
                    (remove #(contains? satiated (:prod %)))
                    (sort-by (juxt #(- (:n %)) :prod))
                    vec)
        relay-state
        (reduce (fn [{:keys [claimed] :as st} [ri router]]
                  (if (or (>= claimed (count hungry)) (empty? decomposers))
                    (reduced st)
                    (let [{:keys [sub prod n]} (nth hungry claimed)
                          default-d (nth decomposers (mod ri (count decomposers)))
                          target (last (sort-by (fn [d]
                                                  [(* n (+ 1.0 (get trails [prod d] 0.0)))
                                                   (if (= d default-d) 1 0)
                                                   d])
                                                decomposers))
                          edge (str "eco-relay-" router "-" beat)]
                      {:claimed (inc claimed)
                       :relayed (update (:relayed st) target (fnil conj [])
                                        {:sub sub :prod prod :n n})
                       :datoms (into (:datoms st)
                                     [(datoms/add edge ":exchange/kind" ":relay")
                                      (datoms/add edge ":exchange/by" router)
                                      (datoms/add edge ":exchange/from" prod)
                                      (datoms/add edge ":exchange/to" target)
                                      (datoms/add edge ":exchange/metabolite" sub)
                                      (datoms/add edge ":exchange/nutrient" n)
                                      (datoms/add edge ":exchange/beat" beat)
                                      (datoms/add edge ":exchange/as-of" as-of)])
                       :relayed-subs (conj (:relayed-subs st) sub)})))
                {:claimed 0 :relayed {} :datoms [] :relayed-subs #{}}
                (map-indexed vector routers))
        relayed (:relayed relay-state)
        relayed-subs (:relayed-subs relay-state)
        relay-datoms (:datoms relay-state)

        ;; ── カビ decomposition: each fed decomposer excretes ONE refined commons metabolite ──
        [refined-datoms refined-relayed fed]
        (reduce (fn [[ds rf fd] dcode]
                  (let [inbound (get relayed dcode)]
                    (if (empty? inbound)
                      [ds rf fd]
                      (let [total (reduce + (map :n inbound))
                            ref (str "eco-refined-" dcode "-" beat)]
                        [(into ds [(datoms/add ref ":metabolite/kind" ":refined")
                                   (datoms/add ref ":metabolite/of" dcode)
                                   (datoms/add ref ":metabolite/nutrient" total)
                                   (datoms/add ref ":metabolite/source" ":relayed")
                                   (datoms/add ref ":metabolite/commons" true)
                                   (datoms/add ref ":metabolite/inputs" (mapv :sub inbound))
                                   (datoms/add ref ":metabolite/beat" beat)
                                   (datoms/add ref ":metabolite/as-of" as-of)])
                         (conj rf ref)
                         (into fd (map :prod inbound))]))))
                [[] [] []]
                decomposers)

        ;; ── 腐生 detritus recycling: unrelayed substrate is dead matter; decomposers recycle it
        ;; into commons at a lossy yield (closes the matter loop). Split round-robin. ──
        detritus (vec (remove #(contains? relayed-subs (:sub %)) substrates))
        [det-datoms refined-detritus]
        (if (and (seq decomposers) (seq detritus))
          (let [buckets (reduce (fn [b [di d]]
                                  (update b (nth decomposers (mod di (count decomposers)))
                                          (fnil conj []) d))
                                {}
                                (map-indexed vector detritus))]
            (reduce (fn [[ds rf] dcode]
                      (let [bin (get buckets dcode)]
                        (if (empty? bin)
                          [ds rf]
                          (let [recovered (reduce + (map (fn [{:keys [n]}]
                                                           (quot (* n detritus-yield-num)
                                                                 detritus-yield-den))
                                                         bin))]
                            (if (<= recovered 0)
                              [ds rf]
                              (let [ref (str "eco-detritus-" dcode "-" beat)]
                                [(into ds [(datoms/add ref ":metabolite/kind" ":refined")
                                           (datoms/add ref ":metabolite/of" dcode)
                                           (datoms/add ref ":metabolite/nutrient" recovered)
                                           (datoms/add ref ":metabolite/source" ":detritus")
                                           (datoms/add ref ":metabolite/commons" true)
                                           (datoms/add ref ":metabolite/inputs" (mapv :sub bin))
                                           (datoms/add ref ":metabolite/beat" beat)
                                           (datoms/add ref ":metabolite/as-of" as-of)])
                                 (conj rf ref)]))))))
                    [[] []]
                    decomposers))
          [[] []])]
    {:datoms (vec (concat prod-datoms relay-datoms refined-datoms det-datoms))
     :refined (vec (concat refined-relayed refined-detritus))
     :fed (vec fed)
     :roles roles}))

(defn web-report
  "Read the food web back from the log: total commons metabolites excreted, the symbiosis output
  (commons nutrient delivered to humanity), and relay count. SINGLE PASS. Deterministic. Returns
  {:commons-metabolites :commons-nutrient-to-humanity :commons-by-source :relays}."
  [txs]
  (let [[meta relay-entities]
        (reduce (fn [[meta relays] [_op e a v]]
                  (cond
                    (= a ":metabolite/kind")     [(assoc-in meta [e :kind] v) relays]
                    (= a ":metabolite/commons")  [(assoc-in meta [e :commons] v) relays]
                    (= a ":metabolite/nutrient") [(assoc-in meta [e :nutrient] v) relays]
                    (= a ":metabolite/source")   [(assoc-in meta [e :source] v) relays]
                    (and (= a ":exchange/kind") (= v ":relay")) [meta (conj relays e)]
                    :else                        [meta relays]))
                [{} #{}]
                (mapcat :tx/datoms txs))
        commons (filterv (fn [m] (and (= (:kind m) ":refined") (true? (:commons m))))
                         (vals meta))
        by-source (reduce (fn [bs m]
                            (update bs (get m :source ":unknown") (fnil inc 0)))
                          {}
                          commons)]
    {:commons-metabolites (count commons)
     :commons-nutrient-to-humanity (reduce + (map #(get % :nutrient 0) commons))
     :commons-by-source by-source
     :relays (count relay-entities)}))
