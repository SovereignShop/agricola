(ns agricola.db
  (:require
   [datascript.core :as d]
   [eurozone.db :as db]))

(def harvest-steps
  [{:eurozone.event/name :harvest/begin-harvest}
   {:eurozone.event/name :harvest/sow-fields}
   {:eurozone.event/name :harvest/breed-animals}])

(def phase-one-steps
  [{:eurozone.event/name :agricola.event/start-round
    :agricola.round/phase 1
    :agricola.round/action {:eurozone.event/name :agricola.square/take-sheep
                            :agricola.action/active false
                            :agricola.action/increments {:agricola.resource/sheep 0}
                            :agricola.entity/resources {:agricola.resource/sheep 1}}}
   {:eurozone.event/name :agricola.event/start-round
    :agricola.round/phase 1
    :agricola.round/action {:eurozone.event/name :agricola.square/major-or-minor}}
   {:eurozone.event/name :agricola.event/start-round
    :agricola.round/phase 1
    :agricola.event/action {:eurozone.event/name :agricola.square/sow-bake}}
   {:eurozone.event/name :agricola.event/start-round
    :agricola.round/phase 1
    :agricola.event/action {:eurozone.event/name :agricola.square/build-fences}}
   {:eurozone.event/name :end-phase}])

(def phase-two-steps
  [{:eurozone.event/name :agricola.event/start-round
    :agricola.round/phase 2
    :agricola.event/action {:eurozone.event/name :agricola.square/family-growth}}
   {:eurozone.event/name :agricola.event/start-round
    :agricola.round/phase 2
    :agricola.event/action {:eurozone.event/name :agricola.square/take-stone-round-2
                            :agricola.action/increments {:agricola.resource/stone 0}
                            :agricola.action/resources {:agricola.resource/stone 1}}}
   {:eurozone.event/name :agricola.event/start-round
    :agricola.round/phase 2
    :agricola.round/action {:eurozone.event/name :agricola.square/renovate}}])

(def phase-three-steps
  [{:eurozone.event/name :agricola.event/start-round
    :agricola.round/phase 3
    :agricola.round/action {:eurozone.event/name :agricola.square/take-one-vegetable}}
   {:eurozone.event/name :agricola.event/start-round
    :agricola.round/phase 3
    :agricola.round/action {:eurozone.event/name :agricola.square/take-boar
                            :agricola.action/increments {:agricola.resource/boar 0}
                            :agricola.action/resources {:agricola.resource/boar 1}}}])

(def phase-four-steps
  [{:eurozone.event/name :agricola.event/start-round
    :agricola.round/phase 4
    :agricola.round/action {:eurozone.event/name :agricola.square/take-stone-round-4
                            :agricola.action/increments {:agricola.resource/stone 0}
                            :agricola.action/resources {:agricola.resource/stone 1}}}
   {:eurozone.event/name :agricola.event/start-round
    :agricola.round/phase 4
    :agricola.round/action {:agricola.event/anme :agricola.square/take-cattle
                            :agricola.action/increments {:agricola.resource/cattle 0}
                            :agricola.action/resources {:agricola.resource/cattle 1}}}])

(def phase-five-steps
  [{:eurozone.event/name :agricola.event/start-round
    :agricla.round/phase 5
    :agricola.round/action {:eurozone.event/name :agricola.square/plow-and-sow}}])

(def phase-six-steps
  [{:eurozone.event/name :agricola.event/start-round
    :agricola.round/phase 6
    :agricola.round/action {:eurozone.event/name :agricola.square/reno-fence}}])

(def schema
  {:agricola.space/resources      {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.event/action         {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.game/name            {:db/unique :db.unique/identity}
   :agricola.game/starting-player {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.game/board           {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.game/current-player  {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.board/actions        {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.action/name          {:db/unique :db.unique/identity}
   :agricola.action/increments    {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.action/accumulator   {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.game/players         {:db/valueType :db.type/ref :db/cardinaltiy :db.cardinality/many}
   :agricola.player/name          {:db/unique :db.unique/identity}
   :agricola.player/next-player   {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.player/farm          {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.player/occupations   {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.player/improvements  {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.farm/house           {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.farm/animals         {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.farm/fields          {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.farm/pastures        {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.farm/squares         {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.field/resources      {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.pasture/squares      {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.entity/resources     {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}})

(def initial-actions
  [{:agricola.action/name :agricola.square/take-one-grain
    :agricola.entity/resources {:agricola.resource/wood 2
                                :agricola.resource/grain 2
                                :agricola.resource/clay 3}
    :agricola.action/increments {:agricola.resource/wood 2}
    :agricola.bit/title "Take One Grain"
    :agricola.bit/description ""}
   {:agricola.action/name :agricola.square/take-three-wood
    :agricola.bit/title "Take Three Wood"
    :agricola.bit/description ""}
   {:agricola.action/name :agricola.square/take-two-wood
    :agricola.bit/title "Take Two Wood"}
   {:agricola.action/name :agricola.square/take-one-reed
    :agricola.bit/title "Take One Reed"
    :agricola.bit/description ""}
   {:agricola.action/name :agricola.square/fishing
    :agricola.bit/title "Fishing"
    :agricola.bit/description ""}])

(def tmp-players
  [{:agricola.player/name "Lori"
    :agricola.entity/resources {:agricola.resource/grain 2}
    :agricola.player/farm
    {:agricola.farm/house
     {:agricola.house/type :wood
      :agricola.house/n-rooms 2}
     :agricola.farm/animals []
     :agricola.farm/fields [{:agricola.field/resources
                             [{:agricola.resource/type :grain
                               :agricola.resource/quantity 3}
                              {:agricola.resource/type :vetetable
                               :agricola.resource/quantity 2}]}]
     :agricola.farm/pastures []}}

   {:agricola.player/name "Cleo"
    :agricola.entity/resources {:agricola.resource/grain 2}
    :agricola.player/occupations
    [{:agricola.card/name :agricola.square/field-watchman
      :agricola.card/type :occupation
      :agricola.card/title "Field Watchman"
      :agricola.card/description ""}
     {:agricola.card/name :agricola.square/family-counseler
      :agricola.card/type :occupation
      :agricola.card/title "Family Counseler"}]
    :agricola.player/improvements []
    :agricola.player/farm
    {:agricola.farm/house
     {:agricola.house/type :clay
      :agricola.house/n-rooms 2}
     :agricola.farm/animals [{:agricola.animal/type :sheep
                              :agricola.animal/quantity 2}
                             {:agricola.animal/type :boar
                              :agricola.animal/quantity 3}]
     :agricola.farm/fields []
     :agricola.farm/pastures [{:agricola.pasture/squares [-2]}]
     :agricola.farm/stables [{:agricola.stable/square -2}]
     :agricola.farm/squares [{:agricola.square/pos [0 0] :db/id -2}
                             {:agricola.square/pos [0 1] :db/id -3}
                             {:agricola.square/pos [0 2] :db/id -4}]}}])

(def tmp-games
  [{:agricola.game/name "Game A"
    :agricola.game/current-player [:agricola.player/name "Lori"]
    :agricola.game/board
    {:agricola.board/actions
     (for [action initial-actions]
       (find action :agricola.action/name))}
    :agricola.game/players
    (for [player tmp-players]
      (find player :agricola.player/name))}
   {:agricola.game/name "Game B"
    :agricola.game/board
    {:agricola.board/actions
     (for [action initial-actions]
       (find action :agricola.action/name))}
    :agricola.game/players
    (for [player tmp-players]
      (find player :agricola.player/name))}])

(d/reset-schema! db/conn (merge db/schema schema))

(defonce init-data
  (do (doall (for [tx-data [initial-actions
                            tmp-players
                            tmp-games
                            [{:eurozone.event/name :init
                              :eurozone.event/id :global-event
                              :eurozone.event/game [:agricola.game/name "Game A"]}]]]
               (d/transact! db/conn tx-data {:ui-update false})))
      nil) )

(comment

  (d/q '[:find ?game-id :in $ :where [?game-id :agricola.game/name]] @db/conn)


  )
