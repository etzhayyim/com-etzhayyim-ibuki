(ns ibuki.methods.wellbecoming
  "ibuki 息吹 — Wellbecoming as the DYNAMIC TRAJECTORY of 情緒 (joucho), ADR-2606171500.

  The Charter defines Wellbecoming as a 動的軌跡 (a *becoming*), explicitly NOT a static
  wellbeing LEVEL (§1.13). ibuki's own gate forbids asserting any per-organism wellbeing
  SCORE (edge-primary). This module honours both: it is a pure READ-DERIVATION (like
  health) over the `:joucho/*` beat history already on the append-only log, and it emits
  only MOVEMENT — the direction and magnitude the mood is travelling over a window — never
  a standing level. There is, by construction, no `:wellbecoming/score` / `:wellbecoming/level`
  attribute: a soul is never reduced to a number, only its 軌跡 is witnessed.

  Pipeline (ADR-2606171500): the charter-clean social-reward `:event/dialogue-reciprocated`
  (joucho) and the kaizen/symbiosis events move the mood; this module reads the resulting
  beat trajectory and surfaces whether the organism is becoming-well (improving), suffering
  (declining), or steady — the signal a future moyai heir-decay (子孫 priority) or a relief
  route (shiori) would consume. Aggregate/movement only; no per-post engagement, no per-soul
  tally. Deterministic, stdlib, append-only.

  `beats` is an oldest→newest seq of joucho score maps {:joy :calm :stress :gratitude :focus}."
  (:require [ibuki.methods.datoms :as d]))

(def ^:private axes [:joy :calm :stress :gratitude :focus])

;; flourishing direction = the four nourishing axes RISE while stress FALLS.
(def ^:private axis-weight {:joy 1 :calm 1 :gratitude 1 :focus 1 :stress -1})

(def ^:private steady-band
  "Net movement within ±this magnitude is :steady (homeostatic drift, not a real trajectory)."
  2)

(defn axis-trajectory
  "Per-axis MOVEMENT over the window: newest − oldest. A movement, never a level.
  Empty / single-beat windows have no trajectory (all zero)."
  [beats]
  (if (< (count beats) 2)
    (zipmap axes (repeat 0))
    (let [a (first beats) z (last beats)]
      (zipmap axes (map (fn [ax] (- (get z ax 0) (get a ax 0))) axes)))))

(defn net-movement
  "Signed scalar of the weighted axis movement (stress counts against). The MAGNITUDE +
  SIGN of the becoming — not a wellbeing level (the level is never asserted)."
  [traj]
  (reduce + (map (fn [ax] (* (axis-weight ax) (get traj ax 0))) axes)))

(defn direction
  "The 軌跡 as a word: :improving (becoming-well) / :declining (suffering) / :steady.
  A direction, never a score."
  [traj]
  (let [m (net-movement traj)]
    (cond (> m steady-band) :improving
          (< m (- steady-band)) :declining
          :else :steady)))

(defn readout
  "Edge-primary wellbecoming readout for an organism over its beat window — movement only.
  {:of :beats :trajectory {axis Δ…} :net :direction}. No level/score field exists."
  [of beats]
  (let [traj (axis-trajectory beats)]
    {:of of :beats (count beats) :trajectory traj
     :net (net-movement traj) :direction (direction traj)}))

(defn wellbecoming-datoms
  "Checkpoint the wellbecoming MOVEMENT as `:wellbecoming/*` datoms (new entity per beat;
  append-only). Emits the per-axis movement + the net + the direction word — and pointedly
  NO level/score attribute (edge-primary, §1.13)."
  [of beats {:keys [beat as-of]}]
  (let [traj (axis-trajectory beats)
        e (str "wb-" of "-" beat)]
    (-> [(d/add e ":wellbecoming/of" of)
         (d/add e ":wellbecoming/beat" beat)
         (d/add e ":wellbecoming/as-of" as-of)
         (d/add e ":wellbecoming/window" (count beats))
         (d/add e ":wellbecoming/net" (net-movement traj))
         (d/add e ":wellbecoming/direction" (str (direction traj)))]
        (into (map (fn [ax] (d/add e (str ":wellbecoming/d-" (name ax)) (get traj ax 0))) axes)))))
