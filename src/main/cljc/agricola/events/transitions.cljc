(ns agricola.events.transitions
  (:require
   [agricola.tx :as tx]
   [datascript.core :as d]
   [agricola.utils :as u]))

(defmulti handle-transition :agricola.event/name)

(defmethod handle-transition :agricola.event/end-round [event]
  (tx/signal :agricola.event/start-round :transition))

(defmethod handle-transition :agricola.event/start-round [event]
  (let [game (u/get-game event)
        board (u/get-board game)
        actions (u/get-actions board)]
    (vec
     (for [action actions
           :let [resources (:agricola.entity/resources action)
                 increments (:agricola.action/increments action)]
           [resource-key inc] increments
           :when (= (namespace resource-key) "agricola.resource")]
       (let [x (get resources resource-key 0)]
         (d/datom (or (:db/id resources) (u/next-tempid!)) resource-key (+ x inc)))))))
