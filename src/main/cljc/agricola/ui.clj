(ns agricola.ui
  (:require
   [datascript.core :as d]
   [agricola.db :as db]
   [agricola.utils :as u]
   [agricola.game :as g]
   [io.github.humbleui.ui :as ui])
  (:import
   [io.github.humbleui.types IPoint]))

(defn signal! [event]
  (let [tx-data [(conj event db/event-id)]]
    (d/transact! db/conn tx-data {:signal true})))

(defn render [event]
  (let [game (u/get-game event)
        board (u/get-board game)
        actions (u/get-actions board)]
    (let [gap-size 40]
      (ui/default-theme
       {}
       (ui/center
        (ui/column
         (interpose (ui/gap 15 15)
                    (for [action actions]
                      (ui/button
                       #(signal! {:agricola.event/name (:agricola.action/name action)})
                       (ui/label (:agricola.action/name action)))))))))))

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
                       [{:agricola.action/name :take-one-grain}
                        {:agricola.action/name :take-two-wood}
                        {:agricola.action/name :take-clay}]}}}]
                   {:ui-update true})
      nil)

  )
