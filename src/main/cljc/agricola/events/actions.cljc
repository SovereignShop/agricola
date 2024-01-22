(ns agricola.events.actions
  (:require
   [agricola.bits :as bits]
   [agricola.utils :as u]
   [agricola.tx :as tx]
   [datascript.core :as d]))

(defmulti handle-action :eurozone.event/name)

(defmethod handle-action bits/take-three-wood
  [action]
  (let [game (u/get-game action)
        player (u/get-current-player game)
        player-resources (u/get-resources player)
        
        square (u/get-square action)
        square-resources (u/get-resources square)]
    (tx/move-resources square-resources player-resources)))

(defmethod handle-action bits/take-one-grain
  [action]
  (let [game (u/get-game action)
        player (u/get-current-player game)
        resources (u/get-resources player)]
    (tx/add-grain resources 1)))

(defmethod handle-action bits/plow-one-field
  [action]
  (let [game (u/get-current-player action)
        player (u/get-current-player game)
        resources (u/get-resources player)]
    (tx/add-fields resources 1)))

(defmethod handle-action bits/play-one-occupation-expensive
  [action]
  (let [occupation (u/get-chosen-occupation action)]
    (concat
     (tx/add-food (u/get-current-player action) -1)
     (tx/assoc-entity occupation :agricola.occupation/played true))))

(defmethod handle-action bits/fishing
  [action]
  (let [player (u/get-current-player action)]
    (tx/move-resources action player)))

(defmethod handle-action bits/take-two-wood
  [action]
  (let [player (u/get-current-player action)]
    (tx/move-resources action player)))

(defmethod handle-action bits/take-one-clay-board-one
  [action]
  (let [player (u/get-current-player action)]
    (tx/move-resources action player)))

(defmethod handle-action bits/take-one-clay-board-two
  [action]
  (let [player (u/get-current-player action)]
    (tx/move-resources action player)))

(defmethod handle-action bits/take-one-reed
  [action]
  (let [player (u/get-current-player action)]
    (tx/move-resources action player)))

(defmethod handle-action bits/build-rooms
  [action]
  (let [player (u/get-current-player action)]
    ))

(defmethod handle-action bits/day-laborer
  [action])

(defmethod handle-action bits/take-sheep
  [action])

(defmethod handle-action bits/take-stone-round-2
  [action])

(defmethod handle-action bits/renovate
  [action])

(defmethod handle-action bits/family-growth
  [action])

(defmethod handle-action bits/starting-player
  [action])

(defmethod handle-action bits/build-fences
  [action])

(defmethod handle-action bits/major-or-minor
  [action])

(defmethod handle-action bits/sow-bake
  [action])

(defmethod handle-action bits/take-one-vegetable
  [action])
