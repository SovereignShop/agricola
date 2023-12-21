(ns agricola.game
  (:require
   [agricola.utils :as u]
   [agricola.db :as db]
   [agricola.actions :refer [handle-action]]
   [agricola.effects :refer [handle-effect]]
   [datascript.core :as d]
   [clojure.core.async :refer [<! chan go go-loop]]))

(def event-id 1)

(defn do-effects [event]
  (let [game (u/get-game event)
        effects (u/get-active-effects game)]
    (for [effect effects
          datom (handle-effect effect)]
      datom)))

(defn process-event [event]
  (concat
   (when (= (:agricola.event/type event) :action)
     (handle-action event))
   (do-effects event)))

(defn process-signal [conn sig]
  (d/transact! conn [sig])
  (d/transact! conn (process-event (d/entity @conn event-id))))

(defn game-loop [conn ch]
  (go-loop []
    (let [sig (<! ch)]
      (process-signal conn sig)
      (recur))))
