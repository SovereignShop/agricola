(ns agricola.effects
  (:require
   [datahike.api :as d]
   [agricola.events :as events]
   [agricola.tx :as tx]
   [agricola.cards :as cards]))

(defn play-field-watchman [player-id]
  (fn [db event]
    (when (events/player-take-grain? player-id event)
      (tx/insert-optional
       :title "Add field to board"
       :tx (tx/add-field (d/entity db player-id))))))

(defn play-family-counseler [player-id]
  (fn [db event]
    (when (events/end-of-round? event)
      (let [player (:agricola.event/player event)
            workers (:agricola.player/workers player)
            n-workers (count workers)
            squares (map :agricola.worker/square workers)
            tiles (into #{} (map :agricola.square/tile) squares)]
        (when (= (count tiles) 1)
          (cond (= n-workers 2) (tx/add-food (d/entity db player-id) 1)
                (= n-workers 3) (tx/add-grain (d/entity db player-id) 1)
                (= n-workers 4) (tx/add-vegetable (d/entity db player-id) 1)))))))

(defn play-grain-elevator [player-id]
  (fn [db event]
    (cond
      (events/new-round? event)
      (let [card (d/entity db cards/grain-elevator)
            grain-pieces (:agricola.card/pieces card)
            n-grain (count grain-pieces)]
        (when (< n-grain 3)
          (tx/add-grain card)))

      (events/player-take-grain? player-id event)
      (let [card (d/entity db cards/grain-elevator)
            grain-pieces (:agricola.card/pieces card)
            n-grain (count grain-pieces)]
        (when (pos? n-grain)
          (tx/insert-optional
           :title "Take grain from grain elevator?"
           :tx (into
                (tx/add-grain (d/entity db player-id) n-grain)
                (tx/remove-grain card n-grain))))))))

(def effects
  {cards/grain-elevator   play-grain-elevator
   cards/field-watchman   play-field-watchman
   cards/family-counseler play-family-counseler})
