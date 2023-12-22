(ns agricola.ui
  (:require
   [datascript.core :as d]
   [agricola.db :as db]
   [agricola.utils :as u]
   [agricola.game :as g]
   [agricola.bits :as bits]
   [io.github.humbleui.ui :as ui])
  (:import
   [io.github.humbleui.types IPoint]))

(defn signal! [event]
  (let [tx-data [(conj event db/event-id)]]
    (d/transact! db/conn tx-data {:signal true})))

(defn render-board [board]
  (let [actions (u/get-actions board)
        gap-size 15]
    (ui/center
     (ui/column
      (interpose
       (ui/gap gap-size gap-size)
       (for [action actions]
         (ui/button
          #(signal! {:agricola.event/name (:agricola.action/name action)
                     :agricola.event/type :aciton})
          (ui/label (:agricola.bit/title action)))))))))

(defn render-player-farm [farm])

(defn render-players [players]
  (ui/center
   (ui/column
    (for [player players]
      (ui/column
       (ui/label (:agricola.player/name player))
       (render-player-farm (:agricola.player/farm player)))))))

(defn render [event]
  (let [game (u/get-game event)
        board (u/get-board game)
        players (u/get-players game)]
    (ui/default-theme
     {}
     (ui/row
      (render-players players)
      (render-board board)))))

(defonce ui (atom (render (d/entity @db/conn db/event-id))))

(defonce app
  (ui/start-app!
   (ui/window
    {:title "Humble 🐝 UI"}
    ui)))

(comment


  (d/listen! db/conn :ui (fn [{:keys [db-after tx-meta]}]
                           (when (:ui-update tx-meta)
                             (reset! ui (render (d/entity @db/conn db/event-id))))))



  (d/transact! db/conn
               [{:agricola.event/name :init
                 :agricola.event/id :global-event}]
               {:ui-update true})

  (do (d/transact! db/conn
                   [{:agricola.event/name :init
                     :agricola.event/id :global-event
                     :agricola.event/game
                     {:agricola.game/board
                      {:agricola.board/actions
                       [{:agricola.action/name bits/take-one-grain
                         :agricola.bit/title "Take One Grain"
                         :agricola.bit/description ""}
                        {:agricola.action/name bits/take-three-wood
                         :agricola.bit/title "Take Three Wood"
                         :agricola.bit/description ""}
                        {:agricola.action/name bits/take-two-wood
                         :agricola.bit/title "Take Two Wood"}]}
                      :agricola.game/players
                      [{:agricola.player/name "Lori"
                        :agricola.player/farm {:agricola.farm/house {:agricola.house/type :wood
                                                                     :agricola.house/n-rooms 2}
                                               :agricola.farm/animals []
                                               :agricola.farm/fields []
                                               :agricola.farm/pastures []}}
                       {:agricola.player/name "Cleo"
                        :agricola.player/occupations []
                        :agricola.player/improvements []
                        :agricola.player/farm {:agricola.player-board/squares []}}]}}]
                   {:ui-update true})
      nil)

  )
