(ns agricola.game
  (:require
   [agricola.utils :as u]
   [agricola.db :as db]
   [agricola.actions :refer [handle-action]]
   [agricola.effects :refer [handle-effect]]
   [agricola.events :refer [handle-event]]
   [datascript.core :as d]))

(defn do-effects [event]
  (let [game (u/get-game event)
        effects (u/get-active-effects game)]
    (for [effect effects
          datom (handle-effect effect event)]
      datom)))

(defn process-event [event]
  ;; Event handlers can emit more events.
  (concat
   (case (:agricola.event/type event)
     :action (handle-action event)
     :event (handle-event event))
   (do-effects event)))

(defn listen [{:keys [db-after tx-meta tx-data]}]
  (when (:signal tx-meta)
    (d/transact! db/conn
                 (process-event (d/entity db-after db/event-id))
                 {:ui-update true})))

(defonce game-listener
  (d/listen! db/conn :game #'listen))
