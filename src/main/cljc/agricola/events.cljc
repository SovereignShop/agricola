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
    (println "Hello World!")
    (for [action actions
          :let [acc (:agricola.action/accumulator action)
                resources (:agricola.action/resources action)
                increments (:agricola.accumulator/increment acc)]
          [resource-key inc] increments
          :when (= (namespace resource-key) "agricola.resource")]
      (let [x (get resources resource-key 0)]
        (d/entity (:db/id resources) resource-key (+ x inc))))))
