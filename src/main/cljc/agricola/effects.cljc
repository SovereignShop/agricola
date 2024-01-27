(ns agricola.effects
  "All active effects executed on every event and return zero or more datoms."
  (:require
   [datascript.core :as d]
   [eurozone.effects :refer [handle-effect]]
   [agricola.utils :as u]))

(defmethod handle-effect :agricola.card/grain-elevator
  [_ event]
  (let [card (u/get-game-bit event :agricola.card/grain-elevator)
        player (u/get-owner card)
        player-id (:db/id player)]
    (cond
      (u/new-round? event)
      (let [grain-pieces (:agricola.card/pieces card)
            n-grain (count grain-pieces)]
        (when (< n-grain 3)
          (u/add-grain card 1)))

      (u/player-take-grain? event player-id)
      (let [grain-pieces (u/get-bits card)
            n-grain (count grain-pieces)]
        (when (pos? n-grain)
          (u/insert-optional
           :title "Take grain from grain elevator?"
           :tx (into
                (u/add-grain player n-grain)
                (u/remove-grain card n-grain))))))))

(defmethod handle-effect :agricola.card/field-watchman
  [_ event]
  (let [card (u/get-game-bit event :agricola.card/field-watchman)
        player (u/get-owner card)
        player-id (:db/id player)]
    (when (u/player-take-grain? event player-id)
      (u/insert-optional
       :title "Add field to board"
       :tx (u/add-fields player 1)))))

(defmethod handle-effect :agricola.card/family-counseler
  [_ event]
  (let [card (u/get-game-bit event :agricola.card/family-counseler)
        player (u/get-owner card)]
    (when (u/end-of-round? event)
      (let [workers (u/get-workers player)
            n-workers (count workers)
            squares (map u/get-square workers)
            tiles (into #{} (map u/get-title) squares)]
        (when (= (count tiles) 1)
          (case n-workers
            2 (u/add-food player 1)
            3 (u/add-grain player 1)
            4 (u/add-vegetables player 1)))))))
