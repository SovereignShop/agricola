(ns eurozone.db
  (:require
   [datascript.core :as d]
   [datascript.storage :refer [file-storage]])
  (:import
   [org.sqlite SQLiteDataSource]))

(def datasource
  (doto (SQLiteDataSource.)
    (.setUrl "jdbc:sqlite:target/db.sqlite")))

(def schema
  {:eurozone/user       {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :eurozone.event/name {:db/cardinality :db.cardinality/one}
   :eurozone.event/game {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :eurozone.event/user {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :eurozone.event/id   {:db/unique :db.unique/identity}})

(def conn
  (d/create-conn schema {:storage (file-storage "db")}))

(def history-logs (atom {}))

(def event-id [:eurozone.event/id :global-event])

(d/listen!
 conn
 :history
 (fn [{:keys [tx-data tx-meta]}]
   (let [{:keys [tx-log-id]} tx-meta]
     (when (and tx-log-id (pos? (count tx-data)))
       (let [groups (group-by :added tx-data)]
         (swap! history-logs
                (fn [history]
                  (update history
                          tx-log-id
                          (fn [logs]
                            (-> logs
                                (update :added into (get groups true))
                                (update :removed into (get groups false))))))))))))
