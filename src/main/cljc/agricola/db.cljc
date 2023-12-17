(ns agricola.db
  (:require
   [datascript.core :as d]
   [datascript.storage :refer [file-storage]]))

(def cfg {:store {:backend :file :path "/tmp/agricola"}})

d/create-conn

(def card-schema
  [{:db/ident       :agricola.card/type
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.card/edition
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.card/publisher
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.card/deck
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.card/base
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.card/min-players
    :db/valueType   :db.type/number
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.card/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.card/cost
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many}
   {:db/ident       :agricola.card/victory-points
    :db/valueType   :db.type/number
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.card/prerequisites
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many}
   {:db/ident       :agricola.card/text
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.card/effects
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many}
   {:db/ident       :agicola.card/is-passing
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

(def resource-schema
  [{:db/ident       :agricola.resource/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.resource/quantity
    :db/valueType   :db.type/number
    :db/cardinality  :db.cardinality/one}])

(def player-schema
  [{:db/ident       :agricola.player/alias
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.player/cards
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.player/board
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.player/resources
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}])

(def board-schema
  [{:db/ident       :agricola.board/tiles
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many}])

(def tile-schema
  [{:db/ident       :agricola.tile/type
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.tile/pieces
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many}])

(def piece-schema
  [{:db/ident       :agricola.piece/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.piece/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.piece/sprite
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}])

(def sprite-schema
  [{:db/ident       :agricola.sprite/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.sprite/uri
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

(def game-board-schema
  [{:db/ident       :agricola.game-board/squares
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many}])

(def square-schema
  [{:db/ident       :agricola.square/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.square/title
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.square/description
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.square/bits
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many}
   {:db/ident       :agricola.square/position
    :db/valueType   :db.type/number
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.square/is-revealed
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

(def gameplay-schema
  [{:db/ident       :agricola.game/id
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.game/bits
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.game/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.game/board
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.game/current-round
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.game/rounds
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many}
   {:db/ident       :agricola.game/players
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many}
   {:db/ident       :agricola.game/finished?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.game/current-stage
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :agricola.round/current-player
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.round/number
    :db/valueType   :db.type/number
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.round/square
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :agricola.stage/number
    :db/valueType   :db.type/number
    :db/cardinality :db.cardinality/one}])

(def schema
  (into {:agricola.element/field-watchman {:db/valueType :db.type/ref}
         :agricola.element/grain-elevator {:db/valueType :db.type/ref}}
        (comp cat
              (map (fn [{:keys [db/ident db/cardinality db/valueType]}]
                     [ident (cond-> {:db/cardinality cardinality}
                              (#{:db.type/ref :db.type/tuple} valueType) (assoc :db/valueType valueType))])))
        (vector card-schema player-schema resource-schema board-schema
                tile-schema piece-schema sprite-schema game-board-schema
                gameplay-schema square-schema)))

(def conn
  (d/create-conn schema {:storage (file-storage "db")}))

(d/transact! conn [{:db/id 1
                    :agricola.element/field-watchman {:agricola.bit/is-active false}
                    :agricola.element/grain-elevator {:db/id 3
                                                      :agricola.bit/title "Grain Elevator"
                                                      :agricola.bit/description ""}}])

(:agricola.bit/is-active (:agricola.bit/field-watchman (d/entity @conn 1)))


(d/entity @conn (:v (d/find-datom @conn :eavt 1 :agricola.bit/grain-elevator)))
