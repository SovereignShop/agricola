(ns agricola.ui
  (:require
   [datascript.core :as d]
   [eurozone.methods :refer [ui-event signal! view!]]
   [eurozone.db :as db]
   [agricola.utils :as u]
   [clojure.string :as str]
   [io.github.humbleui.font :as font]
   [io.github.humbleui.paint :as paint]
   [io.github.humbleui.ui :as ui])
  (:import
   [io.github.humbleui.skija Font Typeface FontStyle Paint PaintMode Color4f]
   [io.github.humbleui.types IPoint]))

(defn ui! [tx-data]
  (d/transact! db/conn tx-data {:view-event true}))

(defn create-bold-font [size]
  (font/make-with-size (Typeface/makeFromName "Arial" FontStyle/BOLD)
                       size))

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
     (ui/button #(view! :agricola.event/find-or-create-game
                        {:eurozone.event/username username})
                (ui/label "Agricola")))))

(defmethod ui-event :agricola.event/draft-view [event]
  (let [game (u/get-game event)
        draft (:agricola.game/draft game)
        username (:eurozone.event/username event)]
    (ui/center
     (ui/row
      (interpose
       (ui/gap 20 20)
       (for [draw (:agricola.draft/draws draft)
             :when  (= (-> draw :agricola.draw/start-player :agricola.player/name) username)]
         (ui/vscroll
          (ui/row
           (interpose
            (ui/gap 10 10)
            (for [card-type-key [:agricola.draw/minor-improvements
                                 :agricola.draw/occupations]]
              (ui/column
               (interpose
                (ui/gap 5 5)
                (cons
                 (if (= card-type-key :agricola.draw/minor-improvements)
                   (ui/label {:font (create-bold-font 32)} "Minor Improvements")
                   (ui/label {:font (create-bold-font 32)} "Occupations"))
                 (for [card (sort-by :db/id (card-type-key draw))]
                   (let [showing (:local/showing card false)
                         showing-toggle (ui/button #(ui! [{:db/id (:db/id card)
                                                           :local/showing (not showing)}])
                                                   (ui/label (:agricola.card/name card)))]
                     (if showing
                       (ui/column showing-toggle
                                  (ui/gap 5 5)
                                  (ui/width 130 (ui/paragraph (:agricola.card/text card)))
                                  (ui/gap 5 5)
                                  (ui/height 30
                                             (ui/rect
                                              (paint/fill 0xFFF5C3C1)
                                              (ui/clickable
                                               {:on-click (fn [_] (signal! :agricola.event/draft-card))}
                                               (ui/center (ui/label "draft card"))))))
                       showing-toggle))))))))))))))))

(defmethod ui-event :agricola.event/start-pre-game [event]
  (let [game (u/get-game event)
        players (:agricola.game/players game)]
    (ui/center
     (apply ui/column
            (ui/button #(signal! :agricola.event/draft-view)
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
      (ui/button #(signal! :agricola.event/draft-view) (ui/label "Create A Game"))))))
