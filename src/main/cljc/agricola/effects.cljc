(ns agricola.effects
  (:require
   [datascript.core :as d]
   [agricola.events :as events]
   [agricola.tx :as tx]
   [agricola.utils :as u]
   [agricola.bits :as bits]))

(defn- resource-acc
  ([resource entity-id]
   (resource-acc resource 1 entity-id))
  ([resource n entity-id]
   (fn [db event]
     (when (events/new-round? event)
       (tx/add-resource (d/entity db entity-id) resource n)))))

(def wood-acc (partial resource-acc :agricola.bit/wood))
(def grain-acc (partial resource-acc :agricola.bit/grain))
(def food-acc (partial resource-acc :agricola.bit/food))

(defn field-watchman [event]
  (let [card (u/get-game-bit event bits/field-watchman)
        player (u/get-owner card)
        player-id (:db/id player)]
    (when (events/player-take-grain? event player-id)
      (tx/insert-optional
       :title "Add field to board"
       :tx (tx/add-fields player 1)))))

(defn family-counseler [event]
  (let [card (u/get-game-bit event bits/family-counseler)
        player (u/get-owner card)]
    (when (events/end-of-round? event)
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
      (events/new-round? event)
      (let [grain-pieces (:agricola.card/pieces card)
            n-grain (count grain-pieces)]
        (when (< n-grain 3)
          (tx/add-grain card 1)))

      (events/player-take-grain? event player-id)
      (let [grain-pieces (u/get-bits card)
            n-grain (count grain-pieces)]
        (when (pos? n-grain)
          (tx/insert-optional
           :title "Take grain from grain elevator?"
           :tx (into
                (tx/add-grain player n-grain)
                (tx/remove-grain card n-grain))))))))

(def effects
  {bits/grain-elevator   [grain-elevator]
   bits/field-watchman   [field-watchman]
   bits/family-counseler [family-counseler]
   bits/day-laborer      [(partial food-acc 2)]})
