(ns eurozone.voice-relay
  "Ephemeral, explicitly paired speech relay. Never stores audio or executes commands."
  (:require [clojure.data.json :as json] [eurozone.peer :as peer]))

(defn service [authorize] {:clients (atom {}) :lock (Object.) :authorize authorize})
(defn reply [send channel value] (send channel (json/write-str value)))
(defn leave! [service send channel]
  (locking (:lock service)
    (when-let [self (get @(:clients service) channel)]
      (swap! (:clients service) dissoc channel)
      (doseq [[other client] @(:clients service) :when (= (:pair self) (:pair client))]
        (reply send other {:op "closed" :message "Voice pairing closed. Open a new link from the game."})
        (swap! (:clients service) dissoc other)))))
(defn receive! [service send channel raw]
  (locking (:lock service)
    (try
      (peer/require! (and (string? raw) (<= (count raw) 8192)) "Voice message is too large.")
      (let [{:keys [op pair token auth text version id message] :as packet} (json/read-str raw :key-fn keyword)
            now (System/currentTimeMillis) clients @(:clients service) self (get clients channel)]
        (case op
          "listen"
          (do
            (peer/require! (and (not self) (string? pair) (re-matches #"[a-f0-9]{64}" pair)
                                (peer/valid-token? token)) "Invalid voice pairing.")
            ((:authorize service) token auth)
            (peer/require! (not-any? #(= pair (:pair %)) (vals clients)) "Start a new voice pairing.")
            (peer/require! (< (count (filter #(= token (:token %)) (vals clients))) 4) "Too many voice sessions.")
            (swap! (:clients service) assoc channel {:pair pair :role :game :token token :expires (+ now 3600000)})
            (reply send channel {:op "ready"}))
          "pair"
          (let [[game owner] (first (filter #(and (= pair (:pair (val %))) (= :game (:role (val %)))) clients))]
            (peer/require! (and (not self) game (< now (:expires owner)) (not (:paired? owner))) "This voice link expired or was already used. Open a new one from the game.")
            (swap! (:clients service) assoc-in [game :paired?] true)
            (swap! (:clients service) assoc channel {:pair pair :role :mic :expires (:expires owner) :seen [] :last 0})
            (reply send channel {:op "ready"})
            (reply send game {:op "paired"}))
          (let [[other _] (first (filter #(and (= (:pair self) (:pair (val %))) (not= channel (key %))) clients))]
            (peer/require! (and self (< now (:expires self))) "Voice session expired. Open a new link from the game.")
            (case op
              "heard"
              (do
                (peer/require! (and (= :mic (:role self)) other (string? text) (<= 1 (count text) 300)
                                    (string? id) (<= 1 (count id) 80) (string? version) (<= (count version) 1000)) "Invalid speech request.")
                (when-not (some #{id} (:seen self))
                  (peer/require! (> (- now (:last self)) 250) "Please speak one command at a time.")
                  (swap! (:clients service) update channel assoc :last now :seen (vec (take-last 100 (conj (:seen self) id))))
                  (reply send other {:op "heard" :text text :version version})))
              "status"
              (do (peer/require! (and (= :game (:role self)) (string? version) (<= (count version) 1000)
                                     (string? message) (<= (count message) 1000)) "Invalid voice status.")
                  (when other (reply send other {:op "status" :version version :message message})))
              (throw (ex-info "Unknown voice message." {}))))))
      (catch Exception e (reply send channel {:op "error" :message (.getMessage e)})))))
