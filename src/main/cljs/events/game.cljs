(ns agricola.events.game
  "Events that occure during a game"
  (:require
   [agricola.constants.ui-idents :as idents]
   [datascript.core :as d]
   [swig.macros :refer [def-event-ds]]
   [taoensso.timbre :as timbre :refer-macros [debug info warn]]))

(defn users-turn? [db game-id]
  (let [game    (d/entity db game-id)
        round   (:game/current-round game)
        turn    (:agricola.round/current-turn round)
        team    (:agricola.round/current-team round)
        players (:agricola.team/players team)
        users   (into {} (map (juxt (comp :db/id :agricola.player/user) :agricola.player/type)) players)
        session (d/entity db [:swig/ident idents/session])
        user    (-> session :session/user :db/id)]
    (and (= (users user) :guesser)
         (:agricola.turn/submitted? turn))))

(def-event-ds ::end-turn [db [_ game-id]]
  (when (users-turn? db game-id)
    (let [game          (d/entity db game-id)
          round         (:game/current-round game)
          round-id      (:db/id round)
          team          (:agricola.round/current-team round)
          teams         (:game/teams game)
          other-team-id (->> teams (remove #(= (:db/id %) (:db/id team))) first :db/id)]
      [[:db/add round-id :agricola.round/current-team other-team-id]
       {:db/id -1
        :agricola.turn/word ""
        :agricola.turn/submitted? false
        :agricola.turn/team other-team-id}
       [:db/add round-id :agricola.round/turns -1]
       [:db/add round-id :agricola.round/current-turn -1]])))

(def-event-ds ::card-click [db [_ game-id character-card]]
  (when (users-turn? db game-id)
    (let [game       (d/entity db game-id)
          round      (:game/current-round game)
          round-id   (:db/id round)
          turn       (:agricola.round/current-turn round)
          turn-id    (:db/id turn)
          {:keys [:agricola.round/blue-cards-count
                  :agricola.round/red-cards-count]
           }         (d/entity db round-id)
          team       (:agricola.round/current-team round)
          team-color (:agricola.team/color team)
          session    (d/entity db [:swig/ident idents/session])
          user       (:session/user session)
          card       (d/entity db character-card)
          color      (:agricola.character-card/role card)]
      (when-not (:agricola.character-card/played? card)
        (concat
         [{:db/id                            (:db/id card)
           :agricola.character-card/played? true}
          [:db/add round-id :agricola.round/turns turn-id]]
         (case color
           :blue [[:db/add round-id :agricola.round/blue-cards-count (dec blue-cards-count)]]
           :red  [[:db/add round-id :agricola.round/red-cards-count (dec red-cards-count)]]
           [])
         (when (not= team-color color)
           (end-turn db [nil game-id]))
         [{:db/id                  turn-id
           :agricola.turn/guesses (:db/id card)}])))))

(def-event-ds ::set-browser-src [db [_ id src]]
  [[:db/add id :html.iframe/src src]])

(def-event-ds ::set-word [db [_ turn-id word]]
  [[:db/add turn-id :agricola.turn/word word]])

(def-event-ds ::set-number [db [_ turn-id number]]
  [[:db/add turn-id :agricola.turn/number (int number)]])

(def-event-ds ::submit-clue [db [_ turn-id]]
  [[:db/add turn-id :agricola.turn/submitted? true]])

(def-event-ds ::set-winning-team
  [db[_ game-id team-id]]
  (let [game  (d/entity db game-id)
        round (:game/current-round game)]
    [[:db/add (:db/id round) :agricola.round/winning-team team-id]]))
