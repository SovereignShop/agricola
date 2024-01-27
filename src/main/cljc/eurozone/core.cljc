(ns eurozone.core
  (:require
   [agricola.utils :as u]
   [eurozone.db :as db]
   [eurozone.methods :refer [handle-event handle-effect]]
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
   (handle-event event)
   (do-effects event)))

(defn listen [{:keys [db-after tx-meta]}]
  (when (:signal tx-meta)
    (let [new-tx-data (process-event (d/entity db-after db/event-id))]
      (d/transact! db/conn
                   new-tx-data
                   (merge {:ui-update true} (meta new-tx-data))))))

(defonce event-listener
  (d/listen! db/conn :game #'listen))
