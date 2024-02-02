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


(defn view
  ([event-name]
   (view event-name {}))
  ([event-name event-data]
   (with-meta [(conj (assoc event-data :eurozone.event/view event-name) db/event-id)]
     {:view-event true})))

(defn signal
  ([event-name]
   (signal event-name {}))
  ([event-name event-data]
   (with-meta
     [(conj (assoc event-data :eurozone.event/name event-name) db/event-id)]
     {:view-event true})))
