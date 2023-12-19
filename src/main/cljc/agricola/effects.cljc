(ns agricola.effects
  (:require
   [datascript.core :as d]
   [agricola.tx :as tx]
   [agricola.utils :as u]
   [agricola.bits :as bits]))

(defn- resource-acc
  ([resource entity-id]
   (resource-acc resource 1 entity-id))
  ([resource n entity-id]
   (fn [db event]
     (when (u/new-round? event)
       (tx/add-resource (d/entity db entity-id) resource n)))))

(def wood-acc (partial resource-acc :agricola.bit/wood))
(def grain-acc (partial resource-acc :agricola.bit/grain))
(def food-acc (partial resource-acc :agricola.bit/food))

(defrecord Occupation [title description]
  ICC)

(defprotocol ICard
  :extend-via-metadata true
  (playable? [this game])
  (on-event [this event])
  (on-play [this game]))

(def field-watchman
  ^{`playable? (fn [this game] true)
    `on-event (fn [this event]
                (let [card (u/get-game-bit event bits/field-watchman)
                      player (u/get-owner card)
                      player-id (:db/id player)]
                  (when (u/player-take-grain? event player-id)
                    (tx/insert-optional
                     :title "Add field to board"
                     :tx (tx/add-fields player 1)))))
    `on-play (fn [this game] nil)}
  #:agricola.card
  {:name :field-watchman
   :title "Field Watchman"
   :description "Whenever you take the Take One Grain action space, you may also receive a field."})

(def grain-elevator
  ^{`playable? (fn [this game] this)
    `on-event (fn [this event]
                (let [card (u/get-game-bit event bits/grain-elevator)
                      player (u/get-owner card)
                      player-id (:db/id player)]
                  (cond
                    (u/new-round? event)
                    (let [grain-pieces (:agricola.card/pieces card)
                          n-grain (count grain-pieces)]
                      (when (< n-grain 3)
                        (tx/add-grain card 1)))

                    (u/player-take-grain? event player-id)
                    (let [grain-pieces (u/get-bits card)
                          n-grain (count grain-pieces)]
                      (when (pos? n-grain)
                        (tx/insert-optional
                         :title "Take grain from grain elevator?"
                         :tx (into
                              (tx/add-grain player n-grain)
                              (tx/remove-grain card n-grain))))))))
    `on-play (fn [this game] this)}
  #:agricola.card
  {:name bits/grain-elevator
   :title "Grain Elevator"})

(playable? field-watchman 123)

(on-event field-watchman )

(defn field-watchman [event]
  (let [card (u/get-game-bit event bits/field-watchman)
        player (u/get-owner card)
        player-id (:db/id player)]
    (when (u/player-take-grain? event player-id)
      (tx/insert-optional
       :title "Add field to board"
       :tx (tx/add-fields player 1)))))

(defn family-counseler [event]
  (let [card (u/get-game-bit event bits/family-counseler)
        player (u/get-owner card)]
    (when (u/end-of-round? event)
      (let [workers (u/get-workers player)
            n-workers (count workers)
            squares (map u/get-square workers)
            tiles (into #{} (map u/get-title) squares)]
        (when (= (count tiles) 1)
          (case n-workers
            2 (tx/add-food player 1)
            3 (tx/add-grain player 1)
            4 (tx/add-vegetables player 1)))))))

(defn grain-elevator [event]
  (let [card (u/get-game-bit event bits/grain-elevator)
        player (u/get-owner card)
        player-id (:db/id player)]
    (cond
      (u/new-round? event)
      (let [grain-pieces (:agricola.card/pieces card)
            n-grain (count grain-pieces)]
        (when (< n-grain 3)
          (tx/add-grain card 1)))

      (u/player-take-grain? event player-id)
      (let [grain-pieces (u/get-bits card)
            n-grain (count grain-pieces)]
        (when (pos? n-grain)
          (tx/insert-optional
           :title "Take grain from grain elevator?"
           :tx (into
                (tx/add-grain player n-grain)
                (tx/remove-grain card n-grain))))))))

(defn return-workers [players]
  (for [player players
        worker (u/get-workers player)]
    (let [home-square (u/get-home-room worker)]
      [(:db/id worker) :agricola.worker/square home-square])))

(defn increment-accumulator [accumulator]
  (d/datom (:db/id accumulator)
           :agricola.accumulator/quantity
           (+ (u/get-acc-quantity accumulator) (u/get-acc-increment accumulator))))

(defn increment-board [board]
  (for [action (u/get-actions board)
        acc (u/get-accumulators action)]
    (increment-accumulator acc)))

(defn new-round [event]
  (let [game (u/get-game event)
        players (u/get-players game)
        return-workers-tx-data (return-workers players)
        inc-board-tx-data (increment-board (u/get-board game))]
    (concat return-workers-tx-data inc-board-tx-data)))

(defn next-turn [event]
  (let [game (u/get-game event)
        round (u/get-current-game-round game)
        player (u/get-current-player round)
        next-player (u/get-next-player player)]
    (when next-player
      [(d/datom (:db/id round) :agricola.round/current-player (:db/id next-player))])))

(defn step-game [game])

(def effects
  {bits/grain-elevator   [grain-elevator]
   bits/field-watchman   [field-watchman]
   bits/family-counseler [family-counseler]
   bits/day-laborer      [(partial food-acc 2)]})
