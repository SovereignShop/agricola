(ns agricola.game
  (:require
   [agricola.utils :as u]
   [agricola.db :as db]
   [agricola.events.actions :refer [handle-action]]
   [agricola.events.transitions :refer [handle-transition]]
   [agricola.effects :refer [handle-effect]]
   [datascript.core :as d]))

(defn do-effects [event]
  (let [game (u/get-game event)
        effects (u/get-active-effects game)]
    (for [effect effects
          datom (handle-effect effect event)]
      datom)))

(defn- concat-with-meta [& xs]
  (with-meta (apply concat xs) (transduce (map meta) merge xs)))

(defn process-event [event]
  (concat-with-meta
   (case (:agricola.event/type event)
     :action (handle-action event)
     :transition (handle-transition event))
   (do-effects event)))

(defn listen [{:keys [db-after tx-meta]}]
  (when (:signal tx-meta)
    (let [new-tx-data (process-event (d/entity db-after db/event-id))]
      (d/transact! db/conn
                   new-tx-data
                   (merge {:ui-update true} (meta new-tx-data))))))

(defonce game-listener
  (d/listen! db/conn :game #'listen))
