(ns agricola.gameplay
  (:require
   [agricola.utils :as u]
   [agricola.bits :as bits]
   [agricola.db :as db]
   [agricola.signals :as signals]
   [clojure.pprint :refer [pprint]]
   [datascript.core :as d]))

(defmulti take-action (fn [db sig]))

(defmethod take-action actions/take-one-grain
  [db sig])

(defmethod ac)

(defmulti step-game (fn [db sig] (:agricola.event/name sig)))

(defmethod step-game signals/take-action
  [db sig])

(defmethod step-game bits/take-three-wood
  [db sig])

(defmethod step-game bits/take-two-wood)



(defn step-turn [turn])

(defn step-round [round])

(defn step-stage [stage])

(defn step-game [event])

(let [game-id -1
      player-1 -2
      player-2 -3
      player-3 -4

      sigs
      [(signals/take-action bits/take-one-occupation player-1)
       (signals/take-action bits/take-three-wood player-2)
       (signals/take-action bits/take-two-wood player-3)
       (signals/take-action bits/day-laborer player-1)]])

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
