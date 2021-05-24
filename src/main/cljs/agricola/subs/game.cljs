(ns agricola.subs.game
  (:require
   [swig.macros :refer-macros [def-sub def-pull-sub]]))

(def-sub ::cards
  [:find (pull ?card [:agricola.character-card/color
                      :agricola.character-card/position
                      :agricola.character-card/word])
   :in $
   :where
   [?card :agricola.character-card/color]])

(def-sub ::word-cards
  [:find (pull ?card [:agricola.word-card/position
                      :agricola.word-card/word
                      :agricola.word-card/character-card])
   :in $ ?game-id
   :where
   [?card :agricola.word-card/word]
   [?game-id :game/current-round ?round-id]
   [?card :agricola.piece/round ?round-id]])

(def-sub ::player-type
  [:find ?player-type .
   :in $ ?game-id
   :where
   [?sid :session/user ?uid]
   [?game-id :game/teams ?tid]
   [?tid :agricola.team/players ?pid]
   [?pid :agricola.player/user ?uid]
   [?pid :agricola.player/type ?player-type]])

(def-sub ::red-cards-remaining
  [:find ?rem .
   :in $ ?game-id
   :where
   [?game-id :game/current-round ?round-id]
   [?round-id :agricola.round/red-cards-count ?rem]])

(def-sub ::blue-cards-remaining
  [:find ?rem .
   :in $ ?game-id
   :where
   [?game-id :game/current-round ?round-id]
   [?round-id :agricola.round/blue-cards-count ?rem]])

(def-sub ::current-team
  [:find (pull ?tid [:agricola.team/color
                     :agricola.team/name]) .
   :in $ ?game-id
   :where
   [?game-id :game/current-round ?round-id]
   [?round-id :agricola.round/current-team ?tid]])

(def-pull-sub ::character-card
  [:agricola.character-card/role
   :agricola.character-card/played?])

(def-sub ::game-over
  [:find [?team-id ?color]
   :in $ ?game-id
   :where
   [?game-id :game/teams]
   [?id :agricola.character-card/role]
   [?team-id :agricola.team/color]
   [?id :agricola.character-card/played?]
   (or (and [?game-id :game/teams ?team-id]
            [?id :agricola.character-card/role]
            [?game-id :game/current-round ?round-id]
            [?team-id :agricola.team/color ?color]
            (or (and [?round-id :agricola.round/blue-cards-count 0]
                     [?team-id :agricola.team/color :blue])
                (and [?round-id :agricola.round/red-cards-count 0]
                     [?team-id :agricola.team/color :red])))
       (and [?game-id :game/teams ?team-id]
            [?game-id :game/current-round ?round-id]
            [?team-id :agricola.team/color ?color]
            [?id :agricola.character-card/played? true]
            [?id :agricola.character-card/role :assassin]
            [?id :agricola.piece/round ?round-id]
            [?round-id :agricola.round/current-team ?team-id]))])

(def-sub ::get-browser-src
  [:find ?src .
   :in $ ?id
   :where
   [?id :html.iframe/src ?src]])

(def-sub ::current-turn
  [:find (pull ?turn-id [:agricola.turn/number
                         :agricola.turn/word
                         :agricola.turn/submitted?]) .
   :in $ ?game-id
   :where
   [?game-id :game/current-round ?round-id]
   [?round-id :agricola.round/current-turn ?turn-id]])
