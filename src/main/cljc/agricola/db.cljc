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
   :agricola.game/starting-player {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.game/board           {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.game/current-player  {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.board/actions        {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :agricola.action/increments    {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.action/accumulator   {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :agricola.game/players         {:db/valueType :db.type/ref :db/cardinaltiy :db.cardinality/many}
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

(d/reset-schema! db/conn (merge db/schema schema))

(comment


  )
