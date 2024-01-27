(ns eurozone.methods)

(defmulti ui-event :eurozone.event/name)
(defmulti handle-event :eurozone.event/name)
(defmulti handle-effect (fn [effect _] (:eurozone.effect/bit effect)))
