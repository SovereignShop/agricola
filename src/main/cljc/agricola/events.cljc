(ns agricola.events
  (:require
   [datahike.api :as d]))


(defn take-action? [event]
  (:agricola.event/action event))

(defn player-take-action? [player-id event]
  (when-let [action (take-action? event)]
    (and (= (:db/id (:agricola.action/player action)) player-id)
         action)))

(defn player-take-grain? [player-id event]
  (when-let [action (player-take-action? event)]
    (= (:agricola.action/name action) :action/take-grain)))

(defn end-of-round? [event]
  (= (:agricola.event/name event) :events/end-of-round))

(defn new-round? [event]
  (= (:agricola.event/name event) :events/new-round))
