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

(defn process-event [event]
  ;; Event handlers can emit more events.
  (concat
   (case (:agricola.event/type event)
     :action (handle-action event)
     :transition (handle-transition event))
   (do-effects event)))

(defn listen [{:keys [db-after tx-meta tx-data]}]
  (when (:signal tx-meta)
    (d/transact! db/conn
                 (process-event (d/entity db-after db/event-id))
                 {:ui-update true})))

(defonce game-listener
  (d/listen! db/conn :game #'listen))
