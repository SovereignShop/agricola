(ns agricola.effects
  (:require
   [datahike.api :as d]
   [agricola.events :as events]
   [agricola.tx :as tx]))

(defn play-field-watchman [player-id]
  (fn [db event]
    (when (events/player-take-grain? player-id event)
      (tx/optional-insert
       :title "Add field to board"
       :tx (u/add-field db player-id)))))

(defn play-family-counseler [player-id]
  (fn [db event]
    (when (events/end-of-round? event)
      (let [player (:agricola.action/player action)
            workers (:agricola.player/workers player)
            n-workers (count workers)
            squares (map :agricola.worker/square workers)
            tiles (into #{} (map :agricola.square/tile) squares)]
        (when (= (count tiles) 1)
          (cond (= n-workers 2) (u/add-food db player-id)
                (= n-workers 3) (u/add-grain db player-id)
                (= n-workers 4) (u/add-vegetable db player-id)))))))

(def play-grain-elevator [player-id]
  (fn [db event]
    (cond
      (events/new-round? event)
      (let [card (d/entity db cards/grain-elevator)
            grain-pieces (:agricola.card/pieces card)
            n-grain (count grain-pieces)]
        (when (< n-grain 3)
          (u/add-grain db card-id)))

      (events/player-take-grain? player-id event)
      (let [card (d/entity db cards/grain-elevator)
            grain-pieces (:agricola.card/pieces card)
            n-grain (count grain-pieces)]
        (when (pos? n-grain)
          (tx/insert-optional
           :title "Take grain from grain elevator?"
           :tx (into
                (tx/add-grain db player-id n-grain)
                (tx/remove-grain db card-id n-grain))))))))

(def effects
  {:grain-elevator (grain-elevator)
   :field-watchman (field-watchman)})
