(ns agricola.squares
  (:require
   [agricola.effects :as effects]
   [agricola.idents :as ids]
   [datascript.core :as d]))

(defn make-square [ident title description is-active]
  (conj {:agricola.bit/effects (get effects/effects ident)
         :agricola.bit/title title
         :agricola.bit/description description
         :agricola.bit/is-active is-active}
        ident))

(def take-one-grain
  (make-square ids/take-one-grain "Take 1 Grain" "and place it in your personal supply" true))

(def plow-one-field
  (make-square ids/plow-one-field "Plow 1 Field" "" true))

(def play-occupation
  (make-square ids/play-one-occupation "1 Occupation"
               "A player's first occupation is free, each additional one costs 1 food" true))

(def day-laborer
  (make-square ids/day-laborer "Day Layborer" "Take 2 Food and place them in your personal supply." true))
