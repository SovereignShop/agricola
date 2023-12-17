(ns agricola.utils
  (:require
   [agricola.idents :as ids]
   [datascript.core :as d])
  #?(:clj
     (:import [java.util UUID])))

(defn get-owner [entity]
  (:agricola.bit/owner entity))

(defn get-workers [entity]
  (:agricola.player/workers entity))

(defn get-square [entity]
  (:agricola.bit/square entity))

(defn get-title [entity]
  (map :agricola.bit/title entity))

(defn get-bits [entity]
  (:agricola.bit/children entity))

(defn get-game-bit [event game-bit-name]
  (let [db (d/entity-db event)
        game-id (:db/id (:agricola.event/game event))]
    (:v (d/find-datom db :eavt game-id game-bit-name))))

(defn make-square [ident position title description]
  (conj
   {:agricola.square/position position
    :agricola.square/title title
    :agricola.square/description description}
   ident))

(defn make-stage-one-rounds [first-round-id]
  (let [[[r1 p1] [r2 p2] [r3 p3] [r4 p4]] (shuffle [[1 9] [2 12] [3 13] [4 14]])]
    [{:db/id first-round-id
      :agricola.round/number r1
      :agricola.round/square (make-square ids/take-sheep p1 "1 Sheep" "")}
     {:agricola.round/number r2
      :agricola.round/square (make-square ids/build-fences p2 "Fences" "")}
     {:agricola.round/number r3
      :agricola.round/square (make-square ids/sow-bake p3 "Sow And/Or Bake" "")}
     {:agricola.round/number r4
      :agricola.round/square (make-square ids/major-or-minor p4 "Major or Minor Improvment" "")}]))

(defn make-stage-two-rounds []
  (let [[[r1 p1] [r2 p2] [r3 p3]] (shuffle [[5 15] [6 16] [7 17]])]
    [{:agricola.round/number r1
      :agricola.round/square (make-square ids/take-stone-round-2 p1 "1 Stone" "")}
     {:agricola.round/number r2
      :agricola.round/square (make-square ids/renovate p2 "1 Stone" "")}
     {:agricola.round/number r3
      :agricola.round/square (make-square ids/family-growth p3 "Family Growth" "")}]))

(defn make-squares [n-players first-round-id]
  (into (case n-players
          3 [(make-square ids/take-one-building-resource 0 "Take 1 Building Resource" "")
             (make-square ids/play-one-occupation-expensive 1 "1 Occupation" "")
             (make-square ids/take-two-wood 4 "2 Wood" "")
             (make-square ids/take-one-clay-board-one 5 "1 Clay" "")]
          4 []
          5 [])
        cat
        [[(make-square ids/build-rooms 6 "Build room(s)" "")
          (make-square ids/starting-player 13/2 "Starting Player" "")
          (make-square ids/take-one-grain 7 "Starting Player" "")
          (make-square ids/plow-one-field 15/2 "Plow one field" "")
          (make-square ids/play-one-occupation 8 "1 Occupation" "")
          (make-square ids/day-laborer 17/2 "Day Laborer" "")
          (make-square ids/take-three-wood 10 "3 Wood" "")
          (make-square ids/take-one-clay-board-two 21/2 "1 Clay" "")
          (make-square ids/take-one-reed 11 "1 Reed" "")
          (make-square ids/fishing 11 "Fishing" "")]
         (make-stage-one-rounds first-round-id)
         (make-stage-two-rounds)]))

(defn make-game [players]
  (let [game-id -1
        first-round-id -2
        n-players (count players)
        squares (make-squares n-players first-round-id)]
    {:db/id game-id
     :agricola.game/current-round -2
     :agricola.game/name ""
     :agricola.game/id (UUID/randomUUID)
     :agricola.game/rounds squares
     :agricola.game/players (map :db/id players)}))
