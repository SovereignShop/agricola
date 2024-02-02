(ns eurozone.methods
  (:require
   [datascript.core :as d]
   [eurozone.db :as db]))

(defmulti ui-event :eurozone.event/view)
(defmulti handle-event :eurozone.event/signal)
(defmulti handle-effect (fn [effect _] (:eurozone.effect/bit effect)))

(defn view!
  ([event-name]
   (view! event-name {}))
  ([event-name event-data]
   (let [event (conj (assoc event-data :eurozone.event/view event-name) db/event-id)]
     (try (d/transact! db/conn [event] {:view-event true})
          (catch Exception e
            (println "Error transacting view: " event "\n" (.getMessage e)))))))

(defn signal!
  ([event-name]
   (signal! event-name {}))
  ([event-name event-data]
   (let [event (assoc event-data :eurozone.event/signal event-name)
         tx-data [(conj event db/event-id)]]
     (try (d/transact! db/conn tx-data {:signal-event true})
          (catch Exception e
            (println "Error transacting signal:" event "\n" (.getMessage e)))))))
