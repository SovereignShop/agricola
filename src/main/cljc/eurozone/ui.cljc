(ns eurozone.ui
  (:require
   [datascript.core :as d]
   [eurozone.methods :refer [ui-event view! signal!]]
   [agricola.effects]
   [agricola.events]
   [agricola.ui]
   [eurozone.events]
   [eurozone.db :as db]
   [eurozone.core]
   [io.github.humbleui.ui :as ui])
  (:import
   [io.github.humbleui.types IPoint]))

(defmethod ui-event :default [event]
  (println "unhandled UI event: " (:eurozone.event/view event) event))

(defmethod ui-event :eurozone.event/login-screen [event]
  (let [name-state (atom {:text (or (:eurozone.event/username event) "") :placeholder "Username..."})
        password-state (atom {:text (or (:eurozone.event/password event) "")
                              :placeholder "Password..."})

        width 130
        login-signal #(signal! :eurozone.event/login
                               {:eurozone.event/username (:text @name-state)
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
       (ui/width width (ui/button #(signal! :eurozone.event/create-user
                                            {:eurozone.event/username (:text @name-state)
                                             :eurozone.user/password (:text @password-state)})
                                  (ui/center (ui/label "Create User"))))
       (when (= (:eurozone.event/view event) :eurozone.event/username-already-exists)
         (ui/column
          (ui/gap 5 5)
          (ui/center (ui/label "User already exists")))))))))


(defmethod ui-event :eurozone.event/login-complete [event]
  (let [usernames (d/q '[:find [?name ...]  :in $ :where [?id :eurozone.user/name ?name]]
                       (d/entity-db event))]
    (ui/column
     (ui/gap 10 10)
     (ui/center (ui/label "Home Screen"))
     (ui/gap 10 10)
     (ui/center (apply ui/row (interpose (ui/gap 15 15) (mapv ui/label usernames))))
     (ui/gap 10 10)
     (ui/button #(view! :eurozone.event/choose-game)
                (ui/label "Start a game!")))))

(defn render [event]
  (println "rendering:" (:eurozone.event/view event))
  (try
    (when-let [view (ui-event event)]
      (ui/default-theme {} view))
    (catch Exception e
      (println (:eurozone.event/view event) ":" (.getMessage e)))))

(defonce _ (do (d/transact! db/conn
                            [(conj {:eurozone.event/view :eurozone.event/login-screen}
                                   db/event-id)])))

(defonce ui (atom (render (d/entity @db/conn db/event-id))))

(defonce app
  (ui/start-app!
   (ui/window
    {:title "Humble 🐝 UI"}
    ui)))


(defn listen [{:keys [tx-meta]}]
  (when (:view-event tx-meta)
    (when-let [new-state (render (d/entity @db/conn db/event-id))]
      (reset! ui new-state))))

(defonce ui-listener (d/listen! db/conn :ui #'listen))

^:chord/o (do
            (d/transact! db/conn
                         [(conj {:eurozone.event/view :eurozone.event/login-screen}
                                db/event-id)])

            ^:chord/x (when-let [new-state (render (d/entity @db/conn db/event-id))]
                        (reset! ui new-state)))

(db/init-history!)
