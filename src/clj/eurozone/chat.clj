(ns eurozone.chat
  "Ephemeral, seat-authorized WebRTC signaling. Chat messages never enter this server."
  (:require [agricola.engine :as a]))

(defn roster! [app send! room]
  (let [members (filter #(= room (:room (val %))) @(:peers app))
        peers (mapv #(select-keys (val %) [:id :name :seat]) members)]
    (doseq [[channel self] members]
      (send! channel {:event :chat/roster :room room :self (:id self) :peers peers}))))
(defn leave! [app send! channel]
  (locking (:peers app)
    (when-let [room (:room (get @(:peers app) channel))]
      (swap! (:peers app) dissoc channel)
      (roster! app send! room))))
(defn receive! [app send! channel token {:keys [event room to kind payload]}]
  (locking (:peers app)
    (try
      (case event
        :chat/join
        (let [table (get @(:rooms (:store app)) room)
              seat (first (keep-indexed #(when (= token (:owner %2)) %1) (:seats table)))]
          (a/require! (some? seat) "Take a seat at this table to use chat.")
          (when-not (= room (:room (get @(:peers app) channel)))
            (leave! app send! channel)
            (swap! (:peers app) assoc channel {:id (str (random-uuid)) :room room :seat seat :name (get-in table [:seats seat :name])}))
          (roster! app send! room))
        :chat/leave (leave! app send! channel)
        :chat/signal
        (let [self (get @(:peers app) channel)
              [target peer] (first (filter #(= to (:id (val %))) @(:peers app)))]
          (a/require! (and self target (= token (get-in @(:rooms (:store app)) [room :seats (:seat self) :owner])) (= room (:room self) (:room peer))) "Peer is not at your table.")
          (a/require! (and (#{:offer :answer :ice} kind) (map? payload)) "Invalid connection signal.")
          (send! target {:event :chat/signal :room room :from (:id self) :kind kind :payload payload}))
        (a/require! false "Unknown chat event."))
      (catch clojure.lang.ExceptionInfo e
        (send! channel {:event :chat/error :message (.getMessage e)})))))
