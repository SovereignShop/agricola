(ns eurozone.effects
  "All effects executed on every event and return zero or more datoms.")

(defmulti handle-effect (fn [effect _] (:eurozone.effect/bit effect)))
