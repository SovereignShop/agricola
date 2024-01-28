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

(defonce conn
  (d/create-conn schema {:storage (file-storage "db")}))

(defonce history-logs (atom {:added [()] :removed [()] :meta-data [{}] :next-index 0}))

(def event-id [:eurozone.event/id :global-event])

(defn init-history! []
  (reset! history-logs {:added [()] :removed [()] :meta-data [{}] :next-index 0}) )

(defn undo! []
  (let [{:keys [added removed meta-data]} @history-logs]
    (println (map count ((juxt :added :removed :meta-data) @history-logs)))
    (if (empty? (first added))
      (println "no more undos")
      (let [last-added (peek (pop added))
            last-removed (peek (pop removed))
            meta-data (peek (pop meta-data))]
        (swap! history-logs (fn [history]
                              (-> history
                                  (update :added (comp pop pop))
                                  (update :removed (comp pop pop))
                                  (update :meta-data (comp pop pop)))))
        (d/transact! conn
                     (concat
                      (map #(assoc % :added false) last-added)
                      (map #(assoc % :added true) last-removed))
                     (assoc meta-data :no-history true))))))

(d/listen!
 conn
 :history
 (fn [{:keys [tx-data tx-meta]}]
   (when-not (:no-history tx-meta)
     (let [{new-added true new-removed false} (group-by :added tx-data)]
       (if (zero? (count tx-data))
         (do (println "Fixed point")
             (swap! history-logs
                    (fn [history]
                      (-> history
                          (update :added conj (list))
                          (update :removed conj (list))
                          (update :meta-data conj {})))))
         (do (println "Merging datoms")
             (swap! history-logs
                    (fn [{:keys [added removed meta-data] :as history}]
                      (-> history
                          (assoc :meta-data (conj (pop meta-data) tx-meta))
                          (assoc :removed (conj (pop removed) (into (peek removed) new-removed)))
                          (assoc :added (conj (pop added) (into (peek added) new-added))))))))))))


(comment
  ^:chord/b (do (undo!) nil))
