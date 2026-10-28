(ns eurozone.browser.subs
  (:require [re-frame.core :as rf] [eurozone.model :as model]))
(rf/reg-sub :ui (fn [db _] (dissoc db :facts)))
(rf/reg-sub :games (fn [db _] (model/games (:facts db))))
(rf/reg-sub :game (fn [db _] (model/pull-game (:facts db) (:game-id db))))
(rf/reg-sub :permission (fn [db _] (get-in db [:permissions (:game-id db)])))
