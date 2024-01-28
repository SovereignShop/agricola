(ns eurozone.utils
  (:require
   [eurozone.db :as db]
   [datascript.core :as d])
  #?(:clj
     (:import [java.util UUID]
              [java.security MessageDigest]
              [java.math BigInteger])))

(defn get-game [event]
  (:eurozone.event/game event))

(defn get-active-effects [game])

(defn hash-username-password [username password]
  (let [input-str (str username ":" password)
        md (MessageDigest/getInstance "SHA-256")]
    (.update md (.getBytes input-str "UTF-8"))
    (let [digest (.digest md)
          big-int (BigInteger. 1 digest)]
      (format "%032x" big-int))))

(defn signal
  ([name ui-update?]
   (with-meta
     [(conj #:eurozone.event {:name name
                              :type type}
            db/event-id)]
     {:signal true :ui-update ui-update?}))
  ([name]
   (signal name false)))
