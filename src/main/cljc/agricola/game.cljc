(ns agricola.game
  (:require
   [agricola.utils :as u]
   [agricola.db :as db]
   [agricola.actions :refer [handle-action]]
   [agricola.effects :refer [handle-effect]]
   [datascript.core :as d]))

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

(defonce game-listener
  (d/listen! db/conn :game (fn [{:keys [db-after tx-meta tx-data]}]
                             (when (:signal tx-meta)
                               (println "signal: " tx-data)
                               (process-event (d/entity db-after db/event-id))))))
