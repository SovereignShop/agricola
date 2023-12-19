(ns agricola.views.game
  (:require
   [agricola.db :as db]
   [re-posh.core :as re-posh]))

(re-posh/connect! db/conn)

(defn play-occupation [player-id]
  (let [occupations (re-posh/subscribe [:player-occupations player-id])]
    [:div "Choose occupation:"]))

(re-posh/subscribe [])
