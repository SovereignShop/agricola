(ns agricola.gameplay
  (:require
   [agricola.utils :as u]
   [agricola.bits :as bits]
   [clojure.pprint :refer [pprint]]
   [datascript.core :as d]))

(defn play-turn [turn])

(defn play-round [round])

(defn play-stage [stage])

(defn play-game [game])

(defn play-draw [deck]
  (loop [let ]))

(defn print-game-state [db]
  (let [game (d/entity db bits/test-game)
        players (:agricola.game/players game)
        current-round (:agricola.game/current-round game)
        current-player (:agricola.game/current-player current-round)
        board          (:agricola.game/board game)]
    (pprint
     {:current-player (:agricola.player/alias current-player)
      :current-round {:round-number (:agricola.round/number current-round)
                      :round-card (:agricola.round/square current-round)}
      :revealed-squares (->> board
                             (map :agricola.game-board/squares)
                             (filter :agricola.square/is-revealed)
                             (map (juxt :agricola.square/title :agricola.square/bits)))})))
