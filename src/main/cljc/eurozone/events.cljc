(ns eurozone.events
  (:require
   [eurozone.utils :as u]
   [eurozone.db :as db]
   [datascript.core :as d]))

(defmulti handle-event :eurozone.event/name)

(defmethod handle-event :default [event]
  (println "no handler for event:" (:eurozone.event/name event)))

(defmethod handle-event :eurozone.event/login [event]
  (let [username (:eurozone.event/username event)
        password (:eurozone.event/password event)
        key (u/hash-username-password username password)

        db (d/entity-db event)
        id (d/q '[:find ?user-id .
                  :in $ ?uname ?key
                  :where
                  [?user-id :eurozone.user/name ?uname]
                  [?user-id :eurozone.user/key ?key]]
                db
                username
                key)]
    (if id
      (conj
       (u/signal :eurozone.event/login-complete :transition true)
       (d/datom (:db/id event) :eurozone.event/user id))
      (u/signal :eurozone.event/login-failed :transition true))))

(defmethod handle-event :eurozone.event/create-user [event]
  (let [username (:eurozone.event/username event)
        pass (:eurozone.event/password event)
        key (u/hash-username-password username pass)

        db (d/entity-db event)

        id (d/q '[:find ?user-id .
                  :in $ ?uname
                  :where
                  [?user-id :eurozone.user/name ?uname]]
                db
                username)]
    (if id
      (u/signal :eurozone.event/username-already-exists :transition true)
      (conj
       (u/signal :eurozone.event/login-complete :transition true)
       {:eurozone.user/name username
        :eurozone.user/key key
        :eurozone.user/alias ""}))))
