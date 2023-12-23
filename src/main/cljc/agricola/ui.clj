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

(defn draw-board [board]
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

(defn draw-farm [farm]
  (let [house (:agricola.farm/house farm)
        animals (:agricola.farm/animals farm)
        fields (:agricola.farm/fields farm)
        pastures (:agricola.farm/pastures farm)]
    (ui/column
     (ui/label (str (map (juxt :agricola.animal/type :agricola.animal/quantity) animals)))
     (ui/label (str (map (juxt :agricola.field/type :agricola.field/quantity) fields)))
     (ui/label (str "Pastures" pastures))
     (ui/label (str "House: " (:agricola.house/type house) " " (:agricola.house/n-rooms house) " rooms.")))))

(defn draw-players [players]
  (ui/center
   (ui/column
    (for [player players]
      (ui/column
       (ui/label (:agricola.player/name player))
       (draw-farm (:agricola.player/farm player)))))))

(defn render [event]
  (println "rendering")
  (let [game (u/get-game event)
        board (u/get-board game)
        players (u/get-players game)]
    (ui/default-theme
     {}
     (ui/row
      (draw-players players)
      (draw-board board)))))

(defonce ui (atom (render (d/entity @db/conn db/event-id))))

(defonce app
  (ui/start-app!
   (ui/window
    {:title "Humble 🐝 UI"}
    ui)))

(defn listen [{:keys [db-after tx-meta]}]
  (when (:ui-update tx-meta)
    (println "update")
    (reset! ui (render (d/entity @db/conn db/event-id)))))

(defonce ui-listener (d/listen! db/conn :ui #'listen))

(comment


  (d/transact! db/conn
               [{:agricola.event/name :init
                 :agricola.event/id :global-event}]
               {:ui-update true})

  (do (d/transact!
       db/conn
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
            :agricola.player/farm
            {:agricola.farm/house
             {:agricola.house/type :wood
              :agricola.house/n-rooms 2}
             :agricola.farm/animals []
             :agricola.farm/fields [{:agricola.field/resources
                                     [{:agricola.resource/type :grain
                                       :agricola.resource/quantity 3}
                                      {:agricola.resource/type :vetetable
                                       :agricola.resource/quantity 2}]}]
             :agricola.farm/pastures []}}
           {:agricola.player/name "Cleo"
            :agricola.player/occupations
            [{:agricola.card/name bits/field-watchman
              :agricola.card/type :occupation
              :agricola.card/title "Field Watchman"
              :agricola.card/description ""}
             {:agricola.card/name bits/family-counseler
              :agricola.card/type :occupation
              :agricola.card/title "Family Counseler"}]
            :agricola.player/improvements []
            :agricola.player/farm
            {:agricola.farm/house
             {:agricola.house/type :clay
              :agricola.house/n-rooms 2}
             :agricola.house/animals [{:agricola.animal/type :sheep
                                       :agricola.animal/quantity 2}
                                      {:agricola.animal/type :boar
                                       :agricola.animal/quantity 3}]
             :agricola.house/fields []
             :agricola.farm/pastures [{:agricola.pasture/squares [-2]}]
             :agricola.farm/stables [{:agricola.stable/square -2}]
             :agricola.farm/squares [{:agricola.square/pos [0 0] :db/id -2}
                                     {:agricola.square/pos [0 1] :db/id -3}
                                     {:agricola.square/pos [0 2] :db/id -4}]}}]}}]
       {:ui-update true})
      nil)

  )
