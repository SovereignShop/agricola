(ns agricola.utils
  (:require
   [agricola.bits :as bits]
   [datascript.core :as d])
  #?(:clj
     (:import [java.util UUID])))

(defn take-action? [event]
  (:agricola.event/action event))

(defn player-take-action? [event player-id]
  (when-let [action (take-action? event)]
    (let [player (:agricola.action/player action)]
      (and (= (:db/id player) player-id) action))))

(defn player-take-grain? [event player-id]
  (when-let [action (player-take-action? event player-id)]
    (= (:agricola.action/name action) :action/take-grain)))

(defn end-of-round? [event]
  (= (:agricola.event/name event) :events/end-of-round))

(defn new-round? [event]
  (= (:agricola.event/name event) :events/new-round))

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

(defn get-game [event]
  (:agricola.event/game event))

(defn get-board [game]
  (:agricola.game/board game))

(defn get-players [game]
  (:agricola.game/players game))

(defn get-actions [board]
  (:agricola.board/actions board))

(defn is-acc-resource? [])

(defn get-active-effects [game])

(defn get-acc-type [accumulator]
  (:agricola.accumulator/type accumulator))

(defn get-acc-increment [accumulator]
  (:agricola.accumulator/increment accumulator))

(defn get-acc-quantity [accumulator]
  (:agricola.accumulator/quantity accumulator))

(defn get-accumulators [bit]
  (:agricola.bit/accumulators bit))

(defn has-acc-resources? [action]
  (:agricola.action/acc-resources action))

(defn get-resource-name [resource]
  (:agricola.resource/name resource))

(defn get-resource-quantity [resource]
  (:agricola.resource/quantity resource))

(defn get-game-bit [event game-bit-name]
  (let [game (:agricola.event/game event)
        bits (:agricola.game/bits game)]
    (get bits game-bit-name)))

(defn get-current-player [game]
  (:agricola.game/current-player game))

(defn get-next-round [round]
  (:agricola.round/next-round round))

(defn get-current-game-round [game]
  (-> game
      (:agricola.game/current-stage)
      (:agricola.stage/current-round)))

(defn get-current-player [round]
  (:agricola.round/current-player round))

(defn get-resources [entity]
  (:agricola.entity/resources entity))

(defn get-next-player [player]
  (:agricola.player/next-player player))

(defn get-home-room [worker]
  (:agricola.worker/room worker))

(defn action-taken? [action]
  (:agricola.action/is-taken action))

(defn get-available-actions [game]
  (let [board (get-board game)
        actions (get-actions board)
        untaken-actions (remove action-taken? actions)]
    untaken-actions))

(defn get-occupations [player]
  (:agricola.player/occupations player))

(defn get-chosen-occupation [action]
  (:agricola.action/occupation action))

(defonce tempids (atom -1000000000))

(defn next-tempid! []
  (swap! tempids dec))

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
      :agricola.round/square (make-square bits/take-sheep p1 "1 Sheep" "")}
     {:agricola.round/number r2
      :agricola.round/square (make-square bits/build-fences p2 "Fences" "")}
     {:agricola.round/number r3
      :agricola.round/square (make-square bits/sow-bake p3 "Sow And/Or Bake" "")}
     {:agricola.round/number r4
      :agricola.round/square (make-square bits/major-or-minor p4 "Major or Minor Improvment" "")}]))

(defn make-stage-two-rounds []
  (let [[[r1 p1] [r2 p2] [r3 p3]] (shuffle [[5 15] [6 16] [7 17]])]
    [{:agricola.round/number r1
      :agricola.round/square (make-square bits/take-stone-round-2 p1 "1 Stone" "")}
     {:agricola.round/number r2
      :agricola.round/square (make-square bits/renovate p2 "1 Stone" "")}
     {:agricola.round/number r3
      :agricola.round/square (make-square bits/family-growth p3 "Family Growth" "")}]))

(defn make-squares [n-players first-round-id]
  (into (case n-players
          3 [(make-square bits/take-one-building-resource 0 "Take 1 Building Resource" "")
             (make-square bits/play-one-occupation-expensive 1 "1 Occupation" "")
             (make-square bits/take-two-wood 4 "2 Wood" "")
             (make-square bits/take-one-clay-board-one 5 "1 Clay" "")]
          4 []
          5 [])
        cat
        [[(make-square bits/build-rooms 6 "Build room(s)" "")
          (make-square bits/starting-player 13/2 "Starting Player" "")
          (make-square bits/take-one-grain 7 "Starting Player" "")
          (make-square bits/plow-one-field 15/2 "Plow one field" "")
          (make-square bits/play-one-occupation 8 "1 Occupation" "")
          (make-square bits/day-laborer 17/2 "Day Laborer" "")
          (make-square bits/take-three-wood 10 "3 Wood" "")
          (make-square bits/take-one-clay-board-two 21/2 "1 Clay" "")
          (make-square bits/take-one-reed 11 "1 Reed" "")
          (make-square bits/fishing 11 "Fishing" "")]
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
