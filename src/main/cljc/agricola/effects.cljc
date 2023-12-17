(ns agricola.effects
  (:require
   [datascript.core :as d]
   [agricola.events :as events]
   [agricola.tx :as tx]
   [agricola.utils :as u]
   [agricola.idents :as ids]))

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
  (let [card (u/get-ident event ids/field-watchman)
        player (u/get-owner card)
        player-id (:db/id player)]
    (fn [db event]
      (when (events/player-take-grain? event player-id)
        (tx/insert-optional
         :title "Add field to board"
         :tx (tx/add-field player))))))

(defn family-counseler [event]
  (let [card (u/get-game-bit event ids/family-counseler)
        player (u/get-owner card)]
    (when (events/end-of-round? event)
      (let [workers (u/get-workers player)
            n-workers (count workers)
            squares (map u/get-square workers)
            tiles (into #{} (map u/get-title) squares)]
        (when (= (count tiles) 1)
          (cond (= n-workers 2) (tx/add-food player 1)
                (= n-workers 3) (tx/add-grain player 1)
                (= n-workers 4) (tx/add-vegetable player 1)))))))


(:agricola.card/name :card/)
(:agricola.deck/)

(defn grain-elevator [event]
  (let [db (d/entity-db event)
        card (u/get-ident event ids/grain-elevator)
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

(defn plow-one-field [event]
  (let [db (d/entity-db event)
        bit (d/entity db ids/plow-one-field)]
    (when (events/take-action? event ids/plow-one-field))))

(defn plow-field [player-id]
  (fn [db event]
    (when (event/player-take? event player-id))))

(def effects
  {ids/grain-elevator   [grain-elevator]
   ids/field-watchman   [field-watchman]
   ids/family-counseler [family-counseler]
   ids/day-laborer      [(partial food-acc 2)]
   ids/plow-one-field   [(partial ply)]})
