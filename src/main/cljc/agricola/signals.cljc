(ns agricola.signals
  (:require
   [datascript.core :as d]))

(defn take-action [name player-id action-id]
  {:agricola.event/name name
   :agricola.event/player player-id
   :agricola.event/action action-id})

(defn use-effect [name player-id effect-id]
  {:agricola.event/name name
   :agricola.event/player player-id
   :agricola.event/effect effect-id})
