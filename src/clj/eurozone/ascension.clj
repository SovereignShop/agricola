(ns eurozone.ascension
  "Server-authoritative Ascension tables. The shared JavaScript engine runs in a
   serialized worker; only validated commands reach it. Hidden information never
   leaves the service except to its owning seat."
  (:require [clojure.data.json :as json] [clojure.edn :as edn]
            [clojure.java.io :as io] [clojure.string :as str]
            [eurozone.store :as store] [eurozone.peer :as peer])
  (:import [java.util UUID] [java.io BufferedReader InputStreamReader BufferedWriter OutputStreamWriter]
           [java.lang ProcessBuilder$Redirect]))
(defn node-command []
  (or (System/getenv "EUROZONE_NODE")
      (some #(when (.canExecute (io/file %)) %) ["/usr/bin/node" "/usr/local/bin/node"
        (str (System/getProperty "user.home") "/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node")])
      "node"))
(defn stop-worker! [service]
  (when-let [worker @(:worker service)] (.destroyForcibly ^Process (:process worker)))
  (reset! (:worker service) nil))
(defn start-worker! [service]
  (let [builder (ProcessBuilder. ^java.util.List [(node-command) "scripts/ascension_worker.cjs"])
        _ (.redirectError builder ProcessBuilder$Redirect/INHERIT)
        process (.start builder)
        worker {:process process :in (BufferedWriter. (OutputStreamWriter. (.getOutputStream process) "UTF-8"))
                :out (BufferedReader. (InputStreamReader. (.getInputStream process) "UTF-8"))}]
    (reset! (:worker service) worker) worker))
(defn rules! [service message]
  (locking (:worker-lock service)
    (let [worker (or (when-let [w @(:worker service)] (when (.isAlive ^Process (:process w)) w)) (start-worker! service))]
      (try
        (.write ^BufferedWriter (:in worker) (str (json/write-str message) "\n"))
        (.flush ^BufferedWriter (:in worker))
        (let [read (future (.readLine ^BufferedReader (:out worker))) result (deref read 5000 ::timeout)]
          (when (= ::timeout result) (future-cancel read) (stop-worker! service) (throw (ex-info "The rules engine timed out; reconnect and retry." {})))
          (when-not result (stop-worker! service) (throw (ex-info "The rules engine restarted; please retry." {})))
          (let [reply (json/read-str result :key-fn keyword)]
            (peer/require! (:ok reply) (:error reply)) (:value reply)))
        (catch java.io.IOException _ (stop-worker! service) (throw (ex-info "The rules engine is unavailable; please retry." {})))))))
(defn service [path]
  {:path path :rooms (atom (if (and path (.exists (io/file path))) (edn/read-string (slurp path)) {}))
   :clients (atom {}) :lock (Object.) :worker (atom nil) :worker-lock (Object.)})
(defn seat [room token] (first (keep-indexed #(when (= token (:owner %2)) %1) (:seats room))))
(defn public-state [state own]
  (when state
    (-> state (dissoc :rng :deck :discard :eventDeck :eventDiscard :serial)
        (update :players #(mapv (fn [i p] (assoc p :handCount (count (filter (fn [c] (and (= "hand" (:zone c)) (= i (:owner c)))) (:cards state))))) (range) %))
        (update :cards #(filterv (fn [c] (or (not= "hand" (:zone c)) (= own (:owner c)))) %)))))
(defn summary [room token]
  {:id (:id room) :game "ascension" :title (:title room) :status (:status room) :revision (:revision room)
   :players (:players room) :bots (:bots room) :created (:created room) :host (= token (:host room)) :seat (seat room token)
   :seats (mapv #(select-keys % [:name :bot]) (:seats room))})
(defn view [room token] (assoc (summary room token) :state (public-state (:state room) (seat room token))))
(defn persist! [service rooms]
  (store/save! {:path (:path service)} rooms) (reset! (:rooms service) rooms))
(defn require-room [service id]
  (let [room (get @(:rooms service) id)] (peer/require! room "This Ascension table was not found.") room))
(defn transact! [service token message]
  (locking (:lock service)
    (let [{:keys [op id revision]} message
          old (when-not (= op "create") (require-room service id))
          _ (when old (peer/require! (= revision (:revision old)) "The table changed. Review the refreshed position and try again."))
          room
          (case op
            "create"
            (let [n (:players message) bots (get message :bots 0) name (store/valid-name (:name message))]
              (peer/require! (and (integer? n) (<= 2 n 4) (integer? bots) (<= 0 bots) (< bots n)) "Choose 2–4 claimants, leaving at least one human seat.")
              {:id (str (UUID/randomUUID)) :host token :title (str name "’s Ascension table") :created (System/currentTimeMillis)
               :players n :bots bots :status "lobby" :revision 0
               :seats (vec (concat [{:owner token :name name :bot false}]
                                  (repeat (- n bots 1) nil)
                                  (for [i (range bots)] {:name (str "Bot " (inc i)) :bot true})))})
            "join"
            (if (some? (seat old token)) old
              (let [slot (first (keep-indexed #(when (nil? %2) %1) (:seats old)))]
                (peer/require! (= "lobby" (:status old)) "This game has already started. You can observe it.")
                (peer/require! (some? slot) "All seats are filled. You can observe this table.")
                (assoc-in old [:seats slot] {:owner token :name (store/valid-name (:name message)) :bot false})))
            "leave-seat"
            (let [own (seat old token)]
              (peer/require! (and (= "lobby" (:status old)) (some? own) (not= token (:host old))) "Only guests can release an unstarted seat.")
              (assoc-in old [:seats own] nil))
            "start"
            (do (peer/require! (and (= token (:host old)) (= "lobby" (:status old))) "Only the creator can start this table once.")
                (peer/require! (every? some? (:seats old)) "Wait for all human seats to join.")
                (let [state (rules! service {:op "init" :players (:players old) :bots (:bots old) :seed (bit-and (System/nanoTime) 4294967295)})]
                  (assoc old :status "playing" :state (update state :players #(mapv (fn [p s] (assoc p :name (:name s))) % (:seats old))))))
            "command"
            (let [own (seat old token) state (:state old) action (:action message)]
              (peer/require! (and (= "playing" (:status old)) (some? own)) "Join a playing seat to take an action.")
              (peer/require! (and (map? action) (#{"advance" "draft" "play" "pass"} (:type action))) "Choose a valid game action.")
              (peer/require! (or (and (= "advance" (:type action)) (not (#{3 4} (:phase state)))) (= own (:active state))) "It is another claimant’s turn.")
              (let [result (rules! service {:op "step" :state state :action (assoc action :actor own)})]
                (assoc old :state result :status (if (:winner result) "finished" "playing"))))
            (throw (ex-info "Unknown Ascension operation." {})))
          room (cond-> room old (assoc :revision (inc (:revision old))))]
      (persist! service (assoc @(:rooms service) (:id room) room)) room)))
(defn send! [send channel value] (send channel (json/write-str value)))
(defn directory [service token] (mapv #(summary % token) (sort-by :created > (vals @(:rooms service)))))
(defn broadcast! [service send]
  (doseq [[channel {:keys [token room]}] @(:clients service)]
    (send! send channel {:op "tables" :tables (directory service token)})
    (when-let [table (get @(:rooms service) room)] (send! send channel {:op "table" :table (view table token)}))))
(defn route! [service send channel raw]
  (locking (:lock service)
    (try
      (peer/require! (and (string? raw) (<= (count raw) 16384)) "Message is too large.")
      (let [{:keys [op token auth id requestId] :as message} (json/read-str raw :key-fn keyword)]
        (if (= op "hello")
          (do (peer/require! (peer/valid-token? token) "Invalid player identity.")
              (when-let [authorize (:authorize service)] (authorize token auth))
              (swap! (:clients service) assoc channel {:token token :window (System/currentTimeMillis) :messages 0})
              (send! send channel {:op "welcome"})
              (send! send channel {:op "tables" :tables (directory service token)}))
          (let [client (get @(:clients service) channel) token (:token client)
                now (System/currentTimeMillis) n (if (< (- now (:window client 0)) 10000) (inc (:messages client 0)) 1)]
            (peer/require! client "Connect before sending game commands.")
            (peer/require! (<= n 100) "Please slow down and retry.")
            (swap! (:clients service) update channel merge {:messages n :window (if (= n 1) now (:window client))})
            (case op
              "watch" (let [room (require-room service id)] (swap! (:clients service) assoc-in [channel :room] id) (send! send channel {:op "table" :table (view room token)}))
              "list" (do (swap! (:clients service) update channel dissoc :room) (send! send channel {:op "tables" :tables (directory service token)}))
              (let [room (transact! service token message)]
                (swap! (:clients service) assoc-in [channel :room] (:id room))
                (broadcast! service send)))
            (send! send channel {:op "ack" :requestId requestId}))))
      (catch Exception e
        (send! send channel {:op "error" :message (.getMessage e)})
        (when-let [{:keys [token room]} (get @(:clients service) channel)]
          (when-let [table (get @(:rooms service) room)] (send! send channel {:op "table" :table (view table token)})))))))
(defn tick! [service send]
  (locking (:lock service)
    (doseq [[id room] @(:rooms service)
            :let [state (:state room)]
            :when (and (= "playing" (:status room)) (#{3 4} (:phase state))
                       (get-in state [:players (:active state) :bot])
                       (some #(= id (:room %)) (vals @(:clients service))))]
      (let [result (rules! service {:op "bot" :state state})]
        (persist! service (assoc @(:rooms service) id (-> room (assoc :state result :status (if (:winner result) "finished" "playing")) (update :revision inc))))
        (broadcast! service send)))))
