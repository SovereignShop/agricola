(ns agricola.events
  (:require
   [eurozone.events :refer [handle-event]]
   [eurozone.utils :as eu]
   [agricola.bits :as bits]
   [agricola.utils :as u]
   [agricola.tx :as tx]
   [agricola.db :as db]
   [datascript.core :as d]))

(defmethod handle-event :agricola.square/take-three-wood
  [action]
  (let [game (u/get-game action)
        player (u/get-current-player game)
        player-resources (u/get-resources player)
        
        square (u/get-square action)
        square-resources (u/get-resources square)]
    (u/move-resources square-resources player-resources)))

(defmethod handle-event :agricola.square/take-one-grain
  [action]
  (let [game (u/get-game action)
        player (u/get-current-player game)
        resources (u/get-resources player)]
    (u/add-grain resources 1)))

(defmethod handle-event :agricola.square/plow-one-field
  [action]
  (let [game (u/get-current-player action)
        player (u/get-current-player game)
        resources (u/get-resources player)]
    (u/add-fields resources 1)))

(defmethod handle-event :agricola.square/play-one-occupation-expensive
  [action]
  (let [occupation (u/get-chosen-occupation action)]
    (concat
     (u/add-food (u/get-current-player action) -1)
     (u/assoc-entity occupation :agricola.occupation/played true))))

(defmethod handle-event :agricola.square/fishing
  [action]
  (let [player (u/get-current-player action)]
    (u/move-resources action player)))

(defmethod handle-event :agricola.square/take-two-wood
  [action]
  (let [player (u/get-current-player action)]
    (u/move-resources action player)))

(defmethod handle-event :agricola.square/take-one-clay-board-one
  [action]
  (let [player (u/get-current-player action)]
    (u/move-resources action player)))

(defmethod handle-event :agricola.square/take-one-clay-board-two
  [action]
  (let [player (u/get-current-player action)]
    (u/move-resources action player)))

(defmethod handle-event :agricola.square/take-one-reed
  [action]
  (let [player (u/get-current-player action)]
    (u/move-resources action player)))

(defmethod handle-event :agricola.square/build-rooms
  [action]
  (let [player (u/get-current-player action)]
    ))

(defmethod handle-event :agricola.square/day-laborer
  [action])

(defmethod handle-event :agricola.square/take-sheep
  [action])

(defmethod handle-event :agricola.square/take-stone-round-2
  [action])

(defmethod handle-event :agricola.square/renovate
  [action])

(defmethod handle-event :agricola.square/family-growth
  [action])

(defmethod handle-event :agricola.square/starting-player
  [action])

(defmethod handle-event :agricola.square/build-fences
  [action])

(defmethod handle-event :agricola.square/major-or-minor
  [action])

(defmethod handle-event :agricola.square/sow-bake
  [action])

(defmethod handle-event :agricola.square/take-one-vegetable
  [action])

(defmethod handle-event :agricola.event/end-round [event]
  (eu/signal :agricola.event/start-round :transition))

(defmethod handle-event :agricola.event/start-round [event]
  (let [game (u/get-game event)
        board (u/get-board game)
        actions (u/get-actions board)
        round (:agricola.game/current-round game)
        action (:agricola.round/action round)]
    (vec
     (list*
      (d/datom (:db/id game) :agricola.game/round (:db/id event))
      (d/datom (:db/id board) :agricola.board/actions (:db/id action))
      (for [action actions
            :let [resources (:agricola.entity/resources action)
                  increments (:agricola.action/increments action)]
            [resource-key inc] increments
            :when (= (namespace resource-key) "agricola.resource")]
        (let [x (get resources resource-key 0)]
          (d/datom (or (:db/id resources) (u/next-tempid!)) resource-key (+ x inc))))))))

(defmethod handle-event :agricola.event/start-game [event]
  (let [game (u/get-game event)
        players (u/get-players game)

        randomized-players (shuffle players)
        first-player (:db/id (first randomized-players))

        game-steps (for [[id step]
                         (map vector
                              (repeatedly u/next-tempid!)
                              (concat (shuffle db/phase-one-steps)
                                      db/harvest-steps
                                      (shuffle db/phase-two-steps)
                                      db/harvest-steps
                                      (shuffle db/phase-three-steps)
                                      db/harvest-steps
                                      (shuffle db/phase-four-steps)
                                      db/harvest-steps
                                      (shuffle db/phase-five-steps)
                                      db/harvest-steps
                                      db/phase-six-steps
                                      db/harvest-steps))]
                     (cond-> (assoc step :db/id (inc id))
                       (not= (:agricola.round/phase step) 6) (assoc :agricola.round/next-round id)))

        player-sequence (for [[left right] (partition 2 1 randomized-players)]
                          (d/datom (:db/id left) :agricola.player/next-player (:db/id right)))]
    (list*
     (d/datom (:db/id game) :agricola.game/current-round (:db/id (first game-steps)))
     (d/datom (:db/id game) :agricola.game/starting-player (:db/id first-player))
     (concat player-sequence game-steps))))

(defmethod handle-event :agricola.event/start-draft [event]
  )

(defmethod handle-event :agricola.event/create-game [event]
  (let [game-id (u/next-tempid!)
        user (:eurozone.event/user event)
        username (:eurozone.user/name user)
        alias (:eurozone.user/alias user)]
    (conj
     (eu/signal :agricola.event/start-pre-game :transition true)
     {:db/id (:db/id event) :eurozone.event/game game-id}
     {:agricola.game/players [{:agricola.player/name (if (empty? alias) username alias)}]
      :eurozone.game/name "agricola"})))

(defmethod handle-event :agricola.event/join-game [event]
  (let [user (:eurozone.event/user event)
        game (u/get-game event)
        alias (:agricola.user/alias user)]
    [(d/datom (:db/id game) :agricola.game/players {:agricola.player/name alias})]))
