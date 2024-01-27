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
  (try
    (vec (concat-with-meta
          (handle-event event)
          (do-effects event)))
    (catch Exception e
      (println (:eurozone.event/name event) ":" (.getMessage e)))))

(defn listen [{:keys [db-after tx-meta]}]
  (when (:signal tx-meta)
    (let [event (d/entity db-after db/event-id)
          new-tx-data (process-event event)]
      (try (d/transact! db/conn
                        new-tx-data
                        (merge {:ui-update true} (meta new-tx-data)))
           (catch Exception e
             (println "Error transacting backend tx: " (:eurozone.event/name event) "\n" (.getMessage e)))))))

(defonce event-listener
  (d/listen! db/conn :game #'listen))
