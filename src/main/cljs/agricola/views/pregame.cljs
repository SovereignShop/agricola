(ns agricola.views.pregame
  (:require
   [agricola.constants.ui-tabs :as tabs]
   [agricola.subs.pregame :as pregame-subs]
   [agricola.subs.users :as user-subs]
   [agricola.subs.session :as session-subs]
   [agricola.events.pregame :as pregame-events]
   [cljs-time.core :as time]
   [swig.dispatch :as swig-view]
   [re-com.core :refer [h-box v-box button line box scroller gap]]
   [re-posh.core :as re-posh]
   [goog.string :as gstr]
   [taoensso.timbre :refer-macros [debug info warn error]]))


(defn show-player [player]
  (let [player-type (:agricola.player/type player)
        player-user @(re-posh/subscribe
                      [::user-subs/get-user
                       (:db/id (:agricola.player/user player))])
        username    (:user/name player-user)
        last-seen   (:user/last-seen player-user)]
    [h-box
     :gap "20px"
     :children
     [[box :style {:color "black"} :child (str username)]
      [box :style {:color "black"} :child (str player-type)]
      [box :style {:color "black"} :child (str "Online "
                                               (time/in-minutes (time/interval (or last-seen (time/now)) (time/now)))
                                               " minutes ago")]]]))


(defn show-players [color game-id]
  (let [players     @(re-posh/subscribe [::pregame-subs/players color game-id])
        uid         @(re-posh/subscribe [::session-subs/user])
        uid->player (into {} (map (juxt (comp :db/id :agricola.player/user) identity)) players)]
    [v-box
     :class "agricola-player-box"
     :children
     [[h-box
       :gap "5px"
       :children
       [[button
         :style {:color (case color :blue "blue" "red")}
         :label (gstr/format "Join %s Team" (case color :red "Red" "Blue"))
         :on-click #(re-posh/dispatch [::pregame-events/join-team color game-id])]
        (when-let [player (uid->player uid)]
          (if (= (:agricola.player/type player) :codemaster)
            [button
             :label "Guesser"
             :on-click #(re-posh/dispatch [::pregame-events/choose-player-type game-id :guesser])]
            [button
             :label (gstr/format "Code Master")
             :on-click #(re-posh/dispatch [::pregame-events/choose-player-type game-id :codemaster])]))]]
      [gap :size "10px"]
      [v-box
       :children
       (map show-player players)]]]))

(defmethod swig-view/dispatch tabs/player-board
  [_]
  (when-let [game-id @(re-posh/subscribe [::session-subs/game])]
    [v-box
     :children
     [[show-players :blue game-id]
      [show-players :red game-id]]]))

(defmethod swig-view/dispatch tabs/pregame
  [{tab-id :db/id}]
  (let [games @(re-posh/subscribe [::pregame-subs/open-games])]
    [scroller
     :style {:flex "1 1 0%"}
     :child
     [h-box
      :width "100%"
      :children
      [[v-box
        :class "agricola-player-board"
        :width "100%"
        :gap "20px"
        :children
        (interpose
         [line]
         (cons [button
                :label "Create Game"
                :on-click #(re-posh/dispatch [::pregame-events/new-game])]
               (for [{game-id :db/id} games]
                 [v-box
                  :gap "20px"
                  :children
                  [[button
                    :label "Enter Game"
                    :on-click #(re-posh/dispatch [::pregame-events/enter-game tab-id game-id])]
                   [show-players :blue game-id]
                   [show-players :red game-id]]])))]]]]))
