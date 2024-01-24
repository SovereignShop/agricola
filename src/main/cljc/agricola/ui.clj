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
    (d/transact! db/conn tx-data {:signal true :ui-update false})))

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
               (signal! #:eurozone.event
                        {:name (:eurozone.event/name action)
                         :type :action}))
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
    #(signal! #:eurozone.event
              {:name :agricola.event/start-round
               :type :transition})
    (ui/height 15 (ui/label "Start Round")))
   (ui/button
    #(signal! #:eurozone.event
              {:name :agricola.event/end-round
               :type :transition})
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

(defn pregame-screen [event]
  (ui/button #() (ui/label "Start Draft")))

(defn choose-game [event]
  (let [user (:eurozone.event/user event)]
    (ui/column
     (ui/button #(signal! {:eurozone.event/name :agricola.event/create-game
                           :agricola.event/user (:db/id user)})
                (ui/label "Agricola")))))

(defn home-screen [event]
  (ui/column
   (ui/label "Home Screen")
   (ui/button #(signal! {:eurozone.event/name :eurozone.event/choose-game})
              (ui/label "Start a game!"))))

(.length
 (doto (StringBuilder.)
   (.append \a)))

(defn login-screen [event]
  (let [name-state (atom {:text (or (:eurozone.user/name event) "") :placeholder "Username..."})
        password-state (atom {:text (or (:eurozone.event/password event) "")
                             :placeholder "Password..."})

        width 130
        login-signal #(signal! {:eurozone.event/name :eurozone.event/login
                                :eurozone.event/type :transition
                                :eurozone.user/name (:text @name-state)
                                :eurozone.event/password (:text @password-state)})]
    (ui/center
     (ui/focus-controller
      (ui/column
       (ui/width width (ui/text-field {} name-state))
       (ui/gap 5 5)
       (ui/width width (ui/text-field {} password-state))
       (ui/gap 5 5)
       (ui/focusable
        (ui/on-key-focused
         {:enter login-signal}
         (ui/width width (ui/button login-signal (ui/center (ui/label "Login"))))))
       (ui/gap 5 5)
       (ui/width width (ui/button #(signal! {:eurozone.event/name :eurozone.event/create-user
                                             :eurozone.event/type :transition
                                             :eurozone.user/name (:text @name-state)
                                             :eurozone.user/password (:text @password-state)})
                                  (ui/center (ui/label "Create User"))))
       (when (= (:eurozone.event/name event) :eurozone.event/username-already-exists)
         (ui/column
          (ui/gap 5 5)
          (ui/center (ui/label "User already exists")))))))))

(defn render [event]
  (ui/default-theme
   {}
   (case (:eurozone.event/name event)
     :agricola.event/start-pre-game (pregame-screen event)

     (:eurozone.event/login-failed :eurozone.event/username-already-exists) (login-screen event)
     :eurozone.event/login-complete (home-screen event)
     :eurozone.event/choose-game (choose-game event)
     :eurozone.event/login-screen (login-screen false))))

(defonce _ (do (d/transact! db/conn
                            [(conj {:eurozone.event/name :eurozone.event/login-screen}
                                   db/event-id)])))

(defonce ui (atom (render (d/entity @db/conn db/event-id))))

(defonce app
  (ui/start-app!
   (ui/window
    {:title "Humble 🐝 UI"}
    ui)))

(defn listen [{:keys [tx-meta]}]
  (when (:ui-update tx-meta)
    (reset! ui (render (d/entity @db/conn db/event-id)))))

(defonce ui-listener (d/listen! db/conn :ui #'listen))

(do
  (d/transact! db/conn
               [(conj {:eurozone.event/name :eurozone.event/login-screen}
                      db/event-id)])
  (reset! ui (render (d/entity @db/conn db/event-id))))

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
           [{:eurozone.event/name bits/take-one-grain
             :agricola.entity/resources {:agricola.resource/wood 2
                                         :agricola.resource/grain 2
                                         :agricola.resource/clay 3}
             :agricola.action/increments {:agricola.resource/wood 2}
             :agricola.bit/title "Take One Grain"
             :agricola.bit/description ""}
            {:eurozone.event/name bits/take-three-wood
             :agricola.bit/title "Take Three Wood"
             :agricola.bit/description ""}
            {:eurozone.event/name bits/take-two-wood
             :agricola.bit/title "Take Two Wood"}
            {:eurozone.event/name bits/take-one-reed
             :agricola.bit/title "Take One Reed"
             :agricola.bit/description ""}
            {:eurozone.event/name bits/fishing
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
