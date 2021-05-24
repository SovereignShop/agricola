(ns agricola.subs.pregame
  "Subs associdate with pre-game"
  (:require
   [swig.macros :refer [def-sub]]))

(def-sub ::players
  [:find (pull ?pid [:agricola.player/type
                     :agricola.player/name
                     :agricola.player/user])
   :in $ ?color ?gid
   :where
   [?gid :game/teams ?tid]
   [?tid :agricola.team/color ?color]
   [?tid :agricola.team/players ?pid]])

(def-sub ::games
  [:find (pull ?pid [:game])])

(def-sub ::open-games
  [:find (pull ?id [:game/finished?
                    :game/id
                    :game/name
                    :game/teams])
   :in $
   :where
   [?id :game/finished? false]])
