(ns agricola.events
  (:require
   [datascript.core :as d]))

(defn take-action? [event]
  (:agricola.event/action event))

(defn player-take-action? [event player-id]
  (when-let [action (take-action? event)]
    (and (= (:db/id (:agricola.action/player action)) player-id) action)))

(defn player-take-grain? [event player-id]
  (when-let [action (player-take-action? event player-id)]
    (= (:agricola.action/name action) :action/take-grain)))

(defn end-of-round? [event]
  (= (:agricola.event/name event) :events/end-of-round))

(defn new-round? [event]
  (= (:agricola.event/name event) :events/new-round))
