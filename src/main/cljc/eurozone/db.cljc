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

(def history-logs (atom {:added [] :removed [] :meta-data []}))

(:added @history-logs)

(def event-id [:eurozone.event/id :global-event])

(d/listen!
 conn
 :history
 (fn [{:keys [tx-data tx-meta]}]
   (when-not (:no-history tx-meta)
     (let [{added true removed false} (group-by :added tx-data)]
       (swap! history-logs
              (fn [history]
                (cond-> history
                  added (update :added conj added)
                  removed (update :removed conj removed)
                  tx-meta (update :meta-data conj tx-meta))))))))

(defn undo! [{:keys [added removed meta-data]}]
  (let [last-added (peek added)
        last-removed (peek removed)
        meta-data (peek meta-data)]
    (swap! history-logs (fn [log]
                          (-> log
                              (update :added pop)
                              (update :removed pop)
                              (update :meta-data pop))))
    (d/transact! conn
                 (concat
                  (map #(assoc % :added false) last-added)
                  (map #(assoc % :added true) last-removed))
                 (assoc meta-data :no-history true))))

(comment
  ^:chord/b (do (undo! @history-logs) nil) )
