(ns eurozone.peer
  "Persistent rendezvous metadata and ephemeral peer routing. Never executes game commands."
  (:require [clojure.edn :as edn] [clojure.java.io :as io] [clojure.string :as str]
            [clojure.data.json :as json] [eurozone.store :as store])
  (:import [java.security MessageDigest SecureRandom]))
(defn digest [value]
  (apply str (map #(format "%02x" (bit-and 255 %)) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (str value) "UTF-8")))))
(defn secret [] (let [bytes (byte-array 32)] (.nextBytes (SecureRandom.) bytes) (apply str (map #(format "%02x" (bit-and 255 %)) bytes))))
(defn peer-id [room token] (digest (str "eurozone-peer:" room ":" token)))
(defn valid-token? [value] (and (string? value) (boolean (re-matches #"[a-f0-9-]{36,128}" value))))
(defn require! [condition message] (when-not condition (throw (ex-info message {}))))
(defn service [path]
  {:path path :tables (atom (if (and path (.exists (io/file path))) (edn/read-string (slurp path)) {}))
   :connections (atom {}) :lock (Object.)})
(defn save-tables! [service tables]
  (store/save! {:path (:path service)} tables)
  (reset! (:tables service) tables))
(defn create-table! [service {:keys [room token invite options source instance]}]
  (locking (:lock service)
    (require! (and (valid-token? room) (valid-token? token) (valid-token? invite)) "Invalid table credentials.")
    (require! (not (contains? @(:tables service) room)) "This peer table already exists.")
    (let [table {:room room :host (peer-id room token) :invite-hash (digest invite)
                 :options options :source source :instance instance :initialized false :created (System/currentTimeMillis)}]
      (save-tables! service (assoc @(:tables service) room table)) table)))
(defn send-json! [send channel message] (send channel (json/write-str message)))
(defn members [service room] (filter #(= room (:room (val %))) @(:connections service)))
(defn roster! [service send room]
  (let [table (get @(:tables service) room)
        peers (mapv #(select-keys (val %) [:id :relay]) (members service room))]
    (doseq [[channel peer] (members service room)]
      (send-json! send channel {:op "roster" :room room :self (:id peer) :host (:host table) :peers peers}))))
(defn leave! [service send channel]
  (locking (:lock service)
    (when-let [peer (get @(:connections service) channel)]
      (swap! (:connections service) dissoc channel)
      (roster! service send (:room peer)))))
(defn route! [service send channel raw]
  (locking (:lock service)
    (try
      (require! (and (string? raw) (<= (count raw) 1200000)) "Peer message is too large.")
      (let [{:keys [op room token invite to payload kind options relay] :as message} (json/read-str raw :key-fn keyword)]
        (case op
          ("join" "create")
          (do
            (require! (and (valid-token? room) (valid-token? token) (valid-token? invite)) "Invalid table invitation.")
            (when-let [authorize (:authorize service)] (authorize token (:auth message)))
            (when (= op "create")
              (require! (and (map? options) (< (count raw) 8192)) "Choose table settings.")
              (store/valid-name (:name options))
              (store/valid-setup (update (:setup options) :mode #(keyword (or % "beginner"))))
              (require! (and (integer? (:expected-players options)) (<= 1 (:expected-players options) 5)) "Choose 1–5 players.")
              (require! (let [n (get options :bots 0)] (and (integer? n) (<= 0 n 4) (< n (:expected-players options)))) "Leave at least one human seat when choosing bots.")
              (if-let [existing (get @(:tables service) room)]
                (require! (and (= (:host existing) (peer-id room token)) (= (:invite-hash existing) (digest invite))) "This peer table already exists.")
                (create-table! service {:room room :token token :invite invite :options options :source :browser})))
            (let [table (get @(:tables service) room) id (peer-id room token)]
              (require! (and table (= (:invite-hash table) (digest invite))) "This peer invitation is invalid.")
              (require! (< (count (members service room)) 24) "This table has too many connections.")
              (require! (not-any? #(and (not= channel (key %)) (= id (:id (val %)))) (members service room))
                        "This seat is already connected in another tab. Close it or open a new player tab.")
              (leave! service send channel)
              (swap! (:connections service) assoc channel {:room room :id id :relay (boolean relay) :window (System/currentTimeMillis) :messages 0 :bytes 0})
              (send-json! send channel {:op "welcome" :room room :self id :host (:host table)
                                       :initialized (:initialized table) :options (:options table)})
              (roster! service send room)))
          (let [self (get @(:connections service) channel) room (:room self)
                table (get @(:tables service) room) now (System/currentTimeMillis)
                recent? (< (- now (:window self 0)) 10000)
                n (inc (if recent? (:messages self) 0)) bytes (+ (count raw) (if recent? (:bytes self) 0))]
            (require! self "Join a peer table first.")
            (require! (and (<= n 500) (<= bytes 12000000)) "Peer routing rate limit exceeded.")
            (swap! (:connections service) update channel merge {:messages n :bytes bytes :window (if recent? (:window self) now)})
            (case op
              "initialized" (do (require! (= (:id self) (:host table)) "Only the host can initialize this table.")
                                (when-not (:initialized table) (save-tables! service (assoc-in @(:tables service) [room :initialized] true))))
              ("signal" "relay")
              (let [[target peer] (first (filter #(= to (:id (val %))) (members service room)))]
                (require! (and target (not= to (:id self)) (or (= to (:host table)) (= (:id self) (:host table)))) "Peer is unavailable or not on this table's host connection.")
                (if (= op "signal")
                  (require! (and (#{"offer" "answer" "ice"} kind) (map? payload) (< (count raw) 65536)) "Invalid WebRTC signal.")
                  (require! (and (string? payload) (< (count payload) 1000000)) "Invalid relay packet."))
                (send-json! send target {:op op :from (:id self) :kind kind :payload payload}))
              (throw (ex-info "Unknown peer operation." {}))))))
      (catch Exception e (send-json! send channel {:op "error" :message (.getMessage e)})))))
