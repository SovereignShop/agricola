(ns agricola.events.transitions
  (:require
   [agricola.db :as db]
   [agricola.tx :as tx]
   [datascript.core :as d]
   [agricola.utils :as u]))

(defmulti handle-transition :eurozone.event/name)

(defmethod handle-transition :agricola.event/end-round [event]
  (tx/signal :agricola.event/start-round :transition))

(defmethod handle-transition :agricola.event/start-round [event]
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

(defmethod handle-transition :agricola.event/start-game [event]
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

(defmethod handle-transition :agricola.event/create-game [event]
  (let [game-id (u/next-tempid!)
        user (:eurozone.event/user event)
        username (:eurozone.user/name user)
        alias (:eurozone.user/alias user)]
    (conj
     (tx/signal :agricola.event/start-pre-game :transition)
     (d/datom game-id :agricola.game/players {:agricola.player/name (if (empty? alias) username alias)}))))

(defmethod handle-transition :agricola.event/join-game [event]
  (let [user (:eurozone.event/user event)
        game (u/get-game event)
        alias (:agricola.user/alias user)]
    [(d/datom (:db/id game) :agricola.game/players {:agricola.player/name alias})]))

(defmethod handle-transition :eurozone.event/login [event]
  (let [username (:eurozone.event/username event)
        password (:durozone.event/password event)

        db (d/entity-db event)
        id (d/q '[:find ?user-id .
                  :in $ ?uname ?pass
                  :where
                  [?user-id :eurozone.user/name ?uname]
                  [?user-id :eurozone.user/password ?pass]]
                db
                username
                password)]
    (if id
      (conj
       (tx/signal :eurozone.event/login-complete :transition true)
       (d/datom (:db/id event) :eurozone.event/user id))
      (tx/signal :eurozone.event/login-failed :transition true))))

(defmethod handle-transition :eurozone.event/create-user [event]
  (let [name (:eurozone.event/name event)
        alias (:eurozone.event/alias event)
        pass (:eurozone.event/password event)
        pass-confirm (:eurozone.event/confirm-password event)]
    ))
