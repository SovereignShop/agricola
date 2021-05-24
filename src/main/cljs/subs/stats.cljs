(ns agricola.subs.stats
  (:require
   [swig.macros :refer-macros [def-sub]]))

(def-sub ::average-words-asked-per-turn
  [:find (mean ?n) .
   :in $ ?uid
   :where
   [?turn-id :agricola.turn/player ?payer-id]
   [?player-id :agricola.player/user ?uid]
   [?turn-id :agricola.turn/number ?n]])

(def-sub ::count-answers
  [:find [(count-distinct ?correct-guess) (count-distinct ?neutral-miss)
          (count-distinct ?assassin-miss) (count-distinct ?opponent-miss)
          (count-distinct ?turn-id)]
   :in $ ?user-id
   :where
   [?turn-id :agricola.turn/player ?player-id]
   [?team-id :agricola.team/players ?player-id]
   [?team-id :agricola.team/color ?color]
   [?player-id :agricola.player/user ?user-id]
   [?match-id :agricola.character-card/played? ?turn-id]
   [?match-id :agricola.character-card/color ?color]
   [?neutral-miss :agricola.character-card/played? ?turn-id]
   [?neutral-miss :agricola.character-card/role :neutral]
   [?assassin-miss :agricola.character-card/played? ?turn-id]
   [?assassin-miss :agricola.character-card/role :assassin]])

(def-sub ::leader-board
  [:find ?name 
   :in $
   :where
   [?session-id :session/user ?user-id]
   [?user-id :user/name ?name]])
