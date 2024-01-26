(ns eurozone.ui
  (:require
   [datascript.core :as d]
   [eurozone.db :as db]
   [eurozone.core]
   [eurozone.utils :as u]
   [io.github.humbleui.ui :as ui])
  (:import
   [io.github.humbleui.types IPoint]))

(defmulti ui-event :eurozone.event/name)

(defmethod ui-event :default [event]
  (print "unhandled UI event: " (:eurozone.event/name event)))

(defn signal! [event]
  (let [tx-data [(conj event db/event-id)]]
    (d/transact! db/conn tx-data {:signal true :ui-update false})))

(derive :eurozone.event/login-failed :eurozone.event/login-screen)
(derive :eurozone.event/username-already-exists :eurozone.event/login-screen)

(defmethod ui-event :eurozone.event/login-screen [event]
  (let [name-state (atom {:text (or (:eurozone.event/username event) "") :placeholder "Username..."})
        password-state (atom {:text (or (:eurozone.event/password event) "")
                              :placeholder "Password..."})

        width 130
        login-signal #(signal! {:eurozone.event/name :eurozone.event/login
                                :eurozone.event/type :transition
                                :eurozone.event/username (:text @name-state)
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
                                             :eurozone.event/username (:text @name-state)
                                             :eurozone.user/password (:text @password-state)})
                                  (ui/center (ui/label "Create User"))))
       (when (= (:eurozone.event/name event) :eurozone.event/username-already-exists)
         (ui/column
          (ui/gap 5 5)
          (ui/center (ui/label "User already exists")))))))))


(defmethod ui-event :eurozone.event/login-complete [event]
  (ui/column
   (ui/center (ui/label "Home Screen"))
   (ui/button #(signal! {:eurozone.event/name :eurozone.event/choose-game})
              (ui/label "Start a game!"))))

(defn render [event]
  (ui/default-theme {} (ui-event event)))

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
