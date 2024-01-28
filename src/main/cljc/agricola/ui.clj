(ns agricola.ui
  (:require
   [datascript.core :as d]
   [eurozone.methods :refer [ui-event]]
   [eurozone.db :as db]
   [agricola.utils :as u]
   [clojure.string :as str]
   [io.github.humbleui.ui :as ui])
  (:import
   [io.github.humbleui.types IPoint]))

(defn signal!
  ([event-name]
   (signal! event-name {}))
  ([event-name event-data]
   (let [event (assoc event-data :eurozone.event/name event-name)
         tx-data [(conj event db/event-id)]]
     (try (d/transact! db/conn tx-data {:signal true})
          (catch Exception e
            (println "Error transacting UI signal:" event "\n" (.getMessage e)))))))

(defn with-gap [size & args]
  (interpose (ui/gap size size) args))

(defn draw-action [action]
  (ui/column
   (ui/label (str (:agricola.bit/title action)))
   (ui/gap 10 10)
   (ui/row
    (apply with-gap 5
           (map ui/label
                (for [[resource-key quantity] (:agricola.entity/resources action)]
                  (str (name resource-key) ":" quantity)))))))

(defn draw-board [board]
  (let [actions (u/get-actions board)
        gap-size 15]
    (ui/center
     (ui/column
      (interpose
       (ui/gap gap-size gap-size)
       (for [action (sort-by :db/id actions)]
         (ui/button
          #(do (println "hello world" (:eurozone.event/name action))
               (signal! (:eurozone.event/name action)))
          (draw-action action))))))))

(defn draw-farm [farm]
  (let [house (:agricola.farm/house farm)
        animals (:agricola.farm/animals farm)
        fields (:agricola.farm/fields farm)
        pastures (:agricola.farm/pastures farm)]
    (ui/column
     (with-gap 15
       (ui/label (str (mapv (juxt :agricola.animal/type :agricola.animal/quantity) animals)))
       (ui/label (str (mapv (juxt :agricola.field/type :agricola.field/quantity) fields)))
       (ui/label (str "Pastures" pastures))
       (ui/label (str "House: " (:agricola.house/type house) " " (:agricola.house/n-rooms house) " rooms."))
       (ui/label (str "Played Occupations:"))
       (ui/label (str "Unplayed Occupations:"))
       (ui/label (str "Played Improvements:"))
       (ui/label (str "Unplayed Improvements:"))))))

(defn draw-players [players]
  (ui/center
   (ui/column
    (interpose
     (ui/gap 40 40)
     (for [player (sort-by :db/id players)]
       (ui/column
        (ui/label (:agricola.player/name player))
        (ui/gap 20 20)
        (draw-farm (:agricola.player/farm player))))))))

(defn draw-event-buttons []
  (ui/column
   (ui/button
    #(signal! :agricola.event/start-round)
    (ui/height 15 (ui/label "Start Round")))
   (ui/button
    #(signal! :agricola.event/end-round)
    (ui/height 15 (ui/label "End Round")))))

(defn render-game [event]
  (let [game (u/get-game event)
        board (u/get-board game)
        players (u/get-players game)]
    (ui/center
     (ui/row
      (draw-players players)
      (ui/gap 20 20)
      (draw-board board)
      (ui/gap 20 20)
      (draw-event-buttons)))))

(defmethod ui-event :eurozone.event/choose-game [event]
  (let [username (:eurozone.event/username event)]
    (ui/column
     (ui/button #(signal! :agricola.event/find-or-create-game
                          {:eurozone.event/username username})
                (ui/label "Agricola")))))

(defmethod ui-event :eurozone.event/draft-view [event]
  (let [game (u/get-game event)]
    (ui/center (ui/label "Draft View"))))

(defmethod ui-event :agricola.event/start-pre-game [event]
  (let [game (u/get-game event)
        players (:agricola.game/players game)]
    (ui/center
     (apply ui/column
            (ui/button #(signal! :eurozone.event/draft-view)
                       (ui/label "Start Draft"))
            (ui/gap 20 20)
            (ui/label "Pre Game View")
            (ui/gap 20 20)
            (interpose
             (ui/gap 5 5)
             (map (comp ui/label :agricola.player/name) players))))))

(defmethod ui-event :agricola.event/find-or-create-game [event]
  (let [games (u/get-all-games event)]
    (ui/center
     (ui/row
      (ui/center
       (apply ui/column
              (interpose
               (ui/gap 5 5)
               (for [game games]
                 (ui/row
                  (ui/button #(signal! :agricola.event/join-game
                                       {:eurozone.event/game (:db/id game)})
                             (ui/label "Join Game"))
                  (ui/gap 10 10)
                  (ui/center (ui/label (str (or (:agricola.game/name game) (:db/id game)) ":"
                                            (str/join "," (map :agricola.player/name
                                                               (:agricola.game/players game)))))))))))
      (ui/gap 50 50)
      (ui/button #(signal! :eurozone.event/draft-view) (ui/label "Create A Game"))))))

(comment


  (d/transact! db/conn
               [#:eurozone.event
                {:name :init
                 :id :global-event}]
               {:ui-update true})

  (do (d/transact!
       db/conn
       [{:eurozone.event/name :init
         :eurozone.event/id :global-event
         :eurozone.event/game
         {:agricola.game/current-player -20
          :agricola.game/board
          {:agricola.board/actions
           [{:eurozone.event/name :agricola.square/take-one-grain
             :agricola.entity/resources {:agricola.resource/wood 2
                                         :agricola.resource/grain 2
                                         :agricola.resource/clay 3}
             :agricola.action/increments {:agricola.resource/wood 2}
             :agricola.bit/title "Take One Grain"
             :agricola.bit/description ""}
            {:eurozone.event/name :agricola.square/take-three-wood
             :agricola.bit/title "Take Three Wood"
             :agricola.bit/description ""}
            {:eurozone.event/name :agricola.square/take-two-wood
             :agricola.bit/title "Take Two Wood"}
            {:eurozone.event/name :agricola.square/take-one-reed
             :agricola.bit/title "Take One Reed"
             :agricola.bit/description ""}
            {:eurozone.event/name :agricola.square/fishing
             :agricola.bit/title "Fishing"
             :agricola.bit/description ""}]}
          :agricola.game/players
          [{:db/id -20
            :agricola.player/name "Lori"
            :agricola.entity/resources {:agricola.resource/grain 2}
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
            :agricola.entity/resources {:agricola.resource/grain 2}
            :agricola.player/occupations
            [{:agricola.card/name :agricola.square/field-watchman
              :agricola.card/type :occupation
              :agricola.card/title "Field Watchman"
              :agricola.card/description ""}
             {:agricola.card/name :agricola.square/family-counseler
              :agricola.card/type :occupation
              :agricola.card/title "Family Counseler"}]
            :agricola.player/improvements []
            :agricola.player/farm
            {:agricola.farm/house
             {:agricola.house/type :clay
              :agricola.house/n-rooms 2}
             :agricola.farm/animals [{:agricola.animal/type :sheep
                                      :agricola.animal/quantity 2}
                                     {:agricola.animal/type :boar
                                      :agricola.animal/quantity 3}]
             :agricola.farm/fields []
             :agricola.farm/pastures [{:agricola.pasture/squares [-2]}]
             :agricola.farm/stables [{:agricola.stable/square -2}]
             :agricola.farm/squares [{:agricola.square/pos [0 0] :db/id -2}
                                     {:agricola.square/pos [0 1] :db/id -3}
                                     {:agricola.square/pos [0 2] :db/id -4}]}}]}}]
       {:ui-update true})
      nil)

  )
