(ns agricola.gameplay
  (:require
   [agricola.idents :as ids]
   [clojure.pprint :refer [pprint]]
   [datascript.core :as d]))

(defn print-game-state [db]
  (let [game (d/entity db ids/test-game)
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
