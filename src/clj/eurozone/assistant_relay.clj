(ns eurozone.assistant-relay
  "A one-use game/local-companion pairing. Credentials never enter this relay."
  (:require [clojure.data.json :as json] [eurozone.peer :as peer]))

(defn service [authorize] {:clients (atom {}) :lock (Object.) :authorize authorize})
(defn reply [send channel value] (send channel (json/write-str value)))
(defn leave! [service send channel]
  (locking (:lock service)
    (when-let [self (get @(:clients service) channel)]
      (doseq [[other client] @(:clients service) :when (= (:pair self) (:pair client))]
        (swap! (:clients service) dissoc other)
        (when (not= other channel)
          (reply send other {:op "closed" :message "ChatGPT pairing disconnected. Connect again from the game."}))))))
(defn short-string? [x limit] (and (string? x) (<= 1 (count x) limit)))
(defn receive! [service send channel raw]
  (locking (:lock service)
    (try
      (peer/require! (and (string? raw) (<= (count raw) 64000)) "Assistant message is too large.")
      (let [{:keys [op pair token auth id version context text result model]} (json/read-str raw :key-fn keyword)
            clients @(:clients service) self (get clients channel) now (System/currentTimeMillis)]
        (case op
          "listen"
          (do
            (peer/require! (and (not self) (string? pair) (re-matches #"[a-f0-9]{64}" pair)
                                (peer/valid-token? token)) "Invalid assistant pairing.")
            ((:authorize service) token auth)
            (peer/require! (not-any? #(= pair (:pair %)) (vals clients)) "Use a fresh assistant link.")
            (peer/require! (< (count (filter #(= token (:token %)) (vals clients))) 4) "Too many assistant sessions.")
            (swap! (:clients service) assoc channel {:pair pair :token token :role :game :expires (+ now 3600000) :seen []})
            (reply send channel {:op "ready"}))
          "pair"
          (let [[game owner] (first (filter #(and (= pair (:pair (val %))) (= :game (:role (val %)))) clients))]
            (peer/require! (and (not self) game (< now (:expires owner)) (not (:paired? owner))
                                (short-string? model 120)) "Pairing expired or already used. Create a fresh link in the game.")
            (swap! (:clients service) assoc-in [game :paired?] true)
            (swap! (:clients service) assoc channel {:pair pair :role :assistant :expires (:expires owner)})
            (reply send channel {:op "paired"})
            (reply send game {:op "paired" :model model}))
          (let [[other _] (first (filter #(and (= (:pair self) (:pair (val %))) (not= channel (key %))) clients))]
            (peer/require! (and self other (< now (:expires self))) "Assistant pairing expired or disconnected.")
            (case op
              "interpret"
              (do
                (peer/require! (and (= :game (:role self)) (short-string? id 80) (short-string? version 1000)
                                    (short-string? text 300) (map? context)) "Invalid interpretation request.")
                (peer/require! (not (some #{id} (:seen self))) "This request was already sent.")
                (peer/require! (or (nil? (:pending self)) (> (- now (:started self)) 120000)) "Wait for the current answer or cancel it.")
                (swap! (:clients service) update channel assoc :pending [id version] :started now
                       :seen (vec (take-last 100 (conj (:seen self) id))))
                (reply send other {:op "interpret" :id id :version version :text text :context context}))
              "result"
              (do
                (peer/require! (and (= :assistant (:role self)) (map? result)
                                    (<= (count (json/write-str result)) 4000)) "Invalid assistant response.")
                (when (= [id version] (:pending (get @(:clients service) other)))
                  (swap! (:clients service) assoc-in [other :pending] nil)
                  (reply send other {:op "result" :id id :version version :result result})))
              "cancel"
              (do
                (peer/require! (= :game (:role self)) "Only the game can cancel a request.")
                (swap! (:clients service) assoc-in [channel :pending] nil)
                (reply send other {:op "cancel"}))
              (throw (ex-info "Unknown assistant message." {}))))))
      (catch Exception _ (reply send channel {:op "error" :message "Assistant request rejected. Reconnect or wait for the current request."})))))
