(ns agricola.db
  (:require
   [datascript.core :as d]
   [datascript.storage :refer [file-storage]]))

(def schema
  {:agricola.event/id        {:db/unique :db.unique/identity}
   :agricola.space/resources {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.event/game {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.game/board {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.board/actions {:db/cardinality :db.cardinality/many :db/valueType :db.type/ref}
   :agricola.action/name {:db/cardinality :db.cardinality/one}
   :agricola.game/players {:db/valueType :db.type/ref :db/cardinaltiy :db.cardinality/many}
   :agricola.player/farm {:db/valueType :db.type/ref}
   :agricola.player/occupations {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.player/improvements {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.farm/house {:db/valueType :db.type/ref}
   :agricola.farm/animals {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.farm/fields {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.farm/pastures {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.farm/squares {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.field/resources {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.pasture/squares {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.action/accumulator {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.action/resources {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.action/increments {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}})

(def conn
  (d/create-conn schema {:storage (file-storage "db")}))

(def history-logs (atom {}))

(def event-id [:agricola.event/id :global-event])

(d/listen! conn :history
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

(comment

  (doseq [i (range 10)]
    (d/transact! conn
                 [{:db/id 1
                   :agricola.bit/number i
                   ;; :agricola.element/field-watchman {:agricola.bit/is-active true
                   ;;                                   :db/id 2}
                   ;; :agricola.element/grain-elevator {:db/id 3
                   ;;                                   :agricola.bit/title "Grain Elevator"
                   ;;                                   :agricola.bit/description ""}
                   }]
                 {:tx-log-id :game-1}))

  (-> @history-logs :game-1 :removed)

  )
