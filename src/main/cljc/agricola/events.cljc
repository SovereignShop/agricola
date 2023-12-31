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
    (vec
     (for [action actions
           :let [resources (:agricola.action/resources action)
                 increments (:agricola.action/increments action)]
           [resource-key inc] increments
           :when (= (namespace resource-key) "agricola.resource")]
       (let [x (get resources resource-key 0)]
         (d/datom (or (:db/id resources) (u/next-tempid!)) resource-key (+ x inc)))))))
