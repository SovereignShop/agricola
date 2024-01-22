(ns agricola.db
  (:require
   [agricola.bits :as bits]
   [agricola.utils :as u]
   [datascript.core :as d]
   [datascript.storage :refer [file-storage]])
  (:import
   [org.sqlite SQLiteDataSource]))

(def datasource
  (doto (SQLiteDataSource.)
    (.setUrl "jdbc:sqlite:target/db.sqlite")))

(def harvest-steps
  [{:eurozone.event/name :harvest/begin-harvest}
   {:eurozone.event/name :harvest/sow-fields}
   {:eurozone.event/name :harvest/breed-animals}])

(def phase-one-steps
  [{:eurozone.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 1
    :agricola.round/action {:eurozone.event/name bits/take-sheep
                            :agricola.action/active false
                            :agricola.action/increments {:agricola.resource/sheep 0}
                            :agricola.entity/resources {:agricola.resource/sheep 1}}}
   {:eurozone.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 1
    :agricola.round/action {:eurozone.event/name bits/major-or-minor}}
   {:eurozone.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 1
    :agricola.event/action {:eurozone.event/name bits/sow-bake}}
   {:eurozone.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 1
    :agricola.event/action {:eurozone.event/name bits/build-fences}}
   {:eurozone.event/name :end-phase}])

(def phase-two-steps
  [{:eurozone.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 2
    :agricola.event/action {:eurozone.event/name bits/family-growth}}
   {:eurozone.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 2
    :agricola.event/action {:eurozone.event/name bits/take-stone-round-2
                            :agricola.action/increments {:agricola.resource/stone 0}
                            :agricola.action/resources {:agricola.resource/stone 1}}}
   {:eurozone.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 2
    :agricola.round/action {:eurozone.event/name bits/renovate}}])

(def phase-three-steps
  [{:eurozone.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 3
    :agricola.round/action {:eurozone.event/name bits/take-one-vegetable}}
   {:eurozone.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 3
    :agricola.round/action {:eurozone.event/name bits/take-boar
                            :agricola.action/increments {:agricola.resource/boar 0}
                            :agricola.action/resources {:agricola.resource/boar 1}}}])

(def phase-four-steps
  [{:eurozone.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 4
    :agricola.round/action {:eurozone.event/name bits/take-stone-round-4
                            :agricola.action/increments {:agricola.resource/stone 0}
                            :agricola.action/resources {:agricola.resource/stone 1}}}
   {:eurozone.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 4
    :agricola.round/action {:agricola.event/anme bits/take-cattle
                            :agricola.action/increments {:agricola.resource/cattle 0}
                            :agricola.action/resources {:agricola.resource/cattle 1}}}])

(def phase-five-steps
  [{:eurozone.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricla.round/phase 5
    :agricola.round/action {:eurozone.event/name bits/plow-and-sow}}])

(def phase-six-steps
  [{:eurozone.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 6
    :agricola.round/action {:eurozone.event/name bits/reno-fence}}])

(def schema
  {:eurozone/user {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :eurozone.event/user {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.event/id {:db/unique :db.unique/identity}
   :agricola.space/resources {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.event/game {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.event/action {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.game/starting-player {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.game/board {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.game/current-player {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.board/actions {:db/cardinality :db.cardinality/many :db/valueType :db.type/ref}
   :eurozone.event/name {:db/cardinality :db.cardinality/one}
   :agricola.action/increments {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.action/accumulator {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.game/players {:db/valueType :db.type/ref :db/cardinaltiy :db.cardinality/many}
   :agricola.player/next-player {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
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
   :agricola.entity/resources {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}})

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
