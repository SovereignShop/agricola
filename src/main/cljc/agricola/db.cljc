(ns agricola.db
  (:require
   [datahike.api :as d]))

(def cfg {:store {:backend :file :path "/tmp/agricola"}})

(d/create-database cfg)

(def conn (d/connect cfg))

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

(d/transact conn card-schema)

(def resource-schema
  [{:db/ident       :agricola.resource/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.resource/quantity
    :db/valueType   :db.type/number
    :db/cardinality  :db.cardinality/one}])

(d/transact conn resource-schema)

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

(d/transact conn player-schema)

(def board-schema
  [{:db/ident       :agricola.board/tiles
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many}])

(d/transact conn board-schema)

(def tile-schema
  [{:db/ident       :agricola.tile/type
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.tile/pieces
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many}])

(d/transact conn tile-schema)

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

(d/transact conn piece-schema)

(def sprite-schema
  [{:db/ident       :agricola.sprite/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.sprite/uri
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

(d/transact conn sprite-schema)

(def game-board-schema
  [{:db/ident       :agricola.game-board/squares
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many}])

(d/transact conn game-board-schema)

(def square-schema
  [{:db/ident       :agricola.square/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.square/name
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.square/pieces
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many}
   {:db/ident       :agricola.square/position
    :db/valueType   :db.type/number
    :db/cardinality :db.cardinality/one}
   {:db/ident       :agricola.square/is-revealed
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

(d/transact conn square-schema)
