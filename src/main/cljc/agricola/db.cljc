(ns agricola.db
  (:require
   [agricola.bits :as bits]
   [agricola.utils :as u]
   [datascript.core :as d]
   [datascript.storage :refer [file-storage]]))


(def harvest-steps
  [{:agricola.event/name :harvest/begin-harvest}
   {:agricola.event/name :harvest/sow-fields}
   {:agricola.event/name :harvest/breed-animals}])

(def phase-one-steps
  [{:agricola.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 1
    :agricola.round/action {:agricola.event/name bits/take-sheep
                            :agricola.action/active false
                            :agricola.action/increments {:agricola.resource/sheep 0}
                            :agricola.entity/resources {:agricola.resource/sheep 1}}}
   {:agricola.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 1
    :agricola.round/action {:agricola.event/name bits/major-or-minor}}
   {:agricola.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 1
    :agricola.event/action {:agricola.event/name bits/sow-bake}}
   {:agricola.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 1
    :agricola.event/action {:agricola.event/name bits/build-fences}}
   {:agricola.event/name :end-phase}])

(def phase-two-steps
  [{:agricola.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 2
    :agricola.event/action {:agricola.event/name bits/family-growth}}
   {:agricola.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 2
    :agricola.event/action {:agricola.event/name bits/take-stone-round-2
                            :agricola.action/increments {:agricola.resource/stone 0}
                            :agricola.action/resources {:agricola.resource/stone 1}}}
   {:agricola.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 2
    :agricola.round/action {:agricola.event/name bits/renovate}}])

(def phase-three-steps
  [{:agricola.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 3
    :agricola.round/action {:agricola.event/name bits/take-one-vegetable}}
   {:agricola.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 3
    :agricola.round/action {:agricola.event/name bits/take-boar
                            :agricola.action/increments {:agricola.resource/boar 0}
                            :agricola.action/resources {:agricola.resource/boar 1}}}])

(def phase-four-steps
  [{:agricola.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 4
    :agricola.round/action {:agricola.event/name bits/take-stone-round-4
                            :agricola.action/increments {:agricola.resource/stone 0}
                            :agricola.action/resources {:agricola.resource/stone 1}}}
   {:agricola.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 4
    :agricola.round/action {:agricola.event/anme bits/take-cattle
                            :agricola.action/increments {:agricola.resource/cattle 0}
                            :agricola.action/resources {:agricola.resource/cattle 1}}}])

(def phase-five-steps
  [{:agricola.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricla.round/phase 5
    :agricola.round/action {:agricola.event/name bits/plow-and-sow}}])

(def phase-six-steps
  [{:agricola.event/name :agricola.event/start-round
    :agricola.event/type :transition
    :agricola.round/phase 6
    :agricola.round/action {:agricola.event/name bits/reno-fence}}])

(def all-steps
  (concat
   phase-one-steps
   harvest-steps
   phase-two-steps
   harvest-steps
   phase-three-steps
   harvest-steps
   phase-four-steps
   harvest-steps
   phase-five-steps
   harvest-steps
   phase-six-steps
   harvest-steps))

(def game-steps
  [{:agricola.game/steps
    (vec (for [[id step] (map vector (repeatedly u/next-tempid!) all-steps)]
           (cond-> (assoc step :db/id (inc id))
             (not= (:agricola.round/phase step) 6) (assoc :agricola.round/next-round id))))}])

(def schema
  {:agricola.event/id        {:db/unique :db.unique/identity}
   :agricola.space/resources {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.event/game {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.event/action {:db/valueType :db.type/ref :db/cardinality :db.cardinatliy/one}
   :agricola.game/board {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.game/current-player {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.board/actions {:db/cardinality :db.cardinality/many :db/valueType :db.type/ref}
   :agricola.event/name {:db/cardinality :db.cardinality/one}
   :agricola.action/increments {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.action/accumulator {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
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
