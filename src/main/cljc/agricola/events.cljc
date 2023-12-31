(ns agricola.events
  (:require
   [datascript.core :as d]
   [agricola.utils :as u]))

(defmulti handle-event :agricola.event/name)

(defmethod handle-event :agricola.event/end-round
  [event])

(defmethod handle-event :agricola.event/start-round
  [event]
  (let [game (u/get-game event)
        board (u/get-board game)
        actions (u/get-actions board)]
    (for [action actions
          acc (:agricola.action/accumulators action)
          :let [resources (:agricola.accumulator/resources acc)]
          increment (:agricola.accumulator/increment acc)])))
