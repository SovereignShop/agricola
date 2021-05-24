(ns ^:figwheel-hooks agricola.core
  (:require
   [agricola.events.app-state :as app-events]
   [agricola.constants.ui-idents]
   [agricola.constants.ui-tabs]
   [agricola.constants.ui-splits]
   [agricola.constants.ui-views]
   [agricola.subs.users]
   [agricola.subs.session]
   [agricola.subs.game]
   [agricola.subs.pregame]
   [agricola.subs.app-state]
   [agricola.subs.popover]
   [agricola.subs.clock]
   [agricola.events.facts]
   [agricola.events.game]
   [agricola.events.pregame]
   [agricola.events.server]
   [agricola.events.popover]
   [agricola.events.clock]
   [agricola.events.chat :as chat-events]
   [agricola.views.users]
   [agricola.views.login]
   [agricola.views.game]
   [agricola.views.pregame]
   [agricola.views.db]
   [agricola.views.popover]
   [agricola.views.chat :as chat]
   [agricola.subs.db]
   [agricola.utils]
   [agricola.clock :as clock]
   [agricola.db :as db]
   [agricola.comms :as sente]
   [agricola.config :as config]
   [datascript.core :as d]
   [swig.core :as swig]
   [swig.views :as swig-view]
   [re-posh.core :as re-posh]))

(defonce _ (re-posh/connect! db/conn))

(defn tx-log-listener [{:keys [db-after tx-data tx-meta]}]
  (when (:db.transaction/log-message? tx-meta)
    (let [log-data (into {} (map (juxt :a :v)) tx-data)
          user-id  (:chat/user log-data)
          user     (d/entity db-after user-id)]
      (chat-events/update-scroller!)
      (chat/add-log! (:chat/user log-data) (:user/name user) (:chat/message log-data))))
  (when-not (:db.transaction/no-save tx-meta)
    (let [facts (into [] (filter (comp db/schema-keys :a)) tx-data)
          gid   (d/q '[:find ?groupname .
                       :in $
                       :where
                       [?id :group/name ?groupname]]
                     db-after)]
      (when-not (empty? facts)
        (cond (:tx/group-update? tx-meta) (sente/send-event! [:agricola.comms/group-facts {:gid    gid
                                                                                            :datoms facts}])
              :else                       (do (js/console.log "sending!") 
                                              (sente/send-event! [:agricola.comms/facts
                                                                  {:gid gid :datoms facts :tx-meta tx-meta}])))))))

(defonce init-db
  (do (swig/init db/login-layout)
      (re-posh/dispatch-sync [::app-events/initialize-db])))

(defmethod swig-view/dispatch :swig.type/cell
  ([{:keys [:swig.cell/element
            :swig.dispatch/handler]
     :as   props}]
   (if handler
     [(get-method swig-view/dispatch handler) props]
     element)))

(defn dev-setup []
  (when config/debug?
    (enable-console-print!)
    (println "dev mode")))

(defn ^:export init []
  (dev-setup)
  (d/listen! db/conn ::tx-log-listener #'tx-log-listener)
  (clock/start-clock!) 
  (swig/render [:swig/ident :swig/root-view]))

(defonce initialization-block (init))

(defn ^:after-load re-render []
  (swig/render [:swig/ident :swig/root-view]))
