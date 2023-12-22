(ns agicola.ui
  (:require
   [datascript.core :as d]
   [agricola.db :as db]
   [agricola.utils :as u]
   [io.github.humbleui.ui :as ui])
  (:import
   [io.github.humbleui.types IPoint]))

(defn render [event]
  (let [game (u/get-game event)
        board (u/get-board game)
        actions (u/get-actions board)]
    (println "actions:" event game board actions)
    (let [gap-size 40]
      (ui/default-theme
       {}
       (ui/center
        (apply
         ui/column
         (for [action actions]
           (do (println "action")
               (ui/label (str "Action: " (:agricola.action/name action)))))))))))

(def ui (atom (render (d/entity @db/conn db/event-id))))

(ui/start-app!
 (ui/window
  {:title "Humble 🐝 UI"}
  ui))

(do
  (d/listen! db/conn :ui (fn [{:keys [db-after tx-meta]}]
                           (when (:ui-update tx-meta)
                             (println "Updating UI")
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

  (d/enttiy @db/conn db/event-id)

  )
