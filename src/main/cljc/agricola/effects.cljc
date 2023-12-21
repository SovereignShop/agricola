(ns agricola.effects
  (:require
   [datascript.core :as d]
   [agricola.tx :as tx]
   [agricola.utils :as u]
   [agricola.bits :as bits]))

(defmulti handle-effect (fn [effect _] (:agricola.effect/bit effect)))

(defmethod handle-effect bits/grain-elevator
  [_ event]
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

(defmethod handle-effect bits/field-watchman
  [_ event]
  (let [card (u/get-game-bit event bits/field-watchman)
        player (u/get-owner card)
        player-id (:db/id player)]
    (when (u/player-take-grain? event player-id)
      (tx/insert-optional
       :title "Add field to board"
       :tx (tx/add-fields player 1)))))

(defmethod handle-effect bits/family-counseler
  [_ event]
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
