(ns eurozone.browser.peer
  (:require [cljs.reader :as reader] [re-frame.core :as rf] [datascript.core :as d]
            [eurozone.store :as store] [eurozone.model :as model] [eurozone.browser.comms :as comms]
            [eurozone.bot-player :as bot] [eurozone.browser.bots :as bots] [eurozone.table-flow :as flow]))
(defonce runtime (atom nil))
(defonce session (atom {}))
(defonce serial (atom (js/Promise.resolve nil)))
(defonce bot-job (atom nil))
(defonce connection-generation (atom 0))
(defn error! [e] (rf/dispatch [:error (or (.-message e) (str e))]))
(defn enqueue! [f] (swap! serial #(.catch (.then % (fn [_] (f))) error!)))
(defn host? [] (and (:self @session) (= (:self @session) (:host @session))))
(defn peer-mode? [] (some? @runtime))
(declare deliver! schedule-bot! publish! persist!)
(defn view-store [room]
  {:rooms (atom {(:id room) room}) :conn (atom (d/db-with (d/empty-db model/schema) (model/game-tx room)))})
(defn presence [room]
  (let [ids (set (map :id (:peers @session)))]
    {(:id room) (vec (keep-indexed #(when (or (:bot? %2) (ids (:owner %2))) %1) (:seats room)))}))
(defn chat-roster [room id]
  {:event :chat/roster :room (:id room) :self id
   :peers (vec (for [[i seat] (map-indexed vector (:seats room))
                    :when (some #(= (:owner seat) (:id %)) (:peers @session))]
                {:id (:owner seat) :name (:name seat) :seat i}))})
(defn snapshot! [id]
  (when-let [room (:table @session)]
    (deliver! id (assoc (store/snapshot (view-store room) id) :presence (presence room)))
    (deliver! id (chat-roster room id))))
(defn publish! [] (doseq [peer (:peers @session)] (snapshot! (:id peer))) (schedule-bot!))
(defn commit-job! [job message]
  (enqueue!
   (fn []
     (when (= job @bot-job)
       (let [current (:table @session)]
         (if-not (and (host?) (= (:room job) (:id current)) (= (:revision job) (:revision current)))
           (do (reset! bot-job nil) (schedule-bot!))
           (try
             (let [[_ changed] (store/transition {(:id current) current} (:self @session)
                                 (merge {:id (:id current) :revision (:revision current)} message))]
               (-> (persist! changed)
                   (.then (fn [_] (reset! bot-job nil) (publish!)))
                   (.catch (fn [e] (reset! bot-job nil) (error! e)))))
             (catch :default e (reset! bot-job nil) (error! e)))))))))
(defn schedule-bot! []
  (when (and @runtime (host?) (nil? @bot-job))
    (let [room (:table @session) expired? (flow/expired-vote? room)
          timed (flow/due room) automated (when-not (:takeback room) (bot/actor room))
          player (or automated (:player timed))
          job {:room (:id room) :revision (:revision room) :player player :nonce (str (random-uuid))}]
      (cond
        expired? (do (reset! bot-job job)
                     (commit-job! job {:event :expire-takeback :vote-id (get-in room [:takeback :id])}))
        (some? player)
        (do (reset! bot-job job)
            (js/setTimeout
             #(when (= job @bot-job)
                (bots/request! (bot/observation (:state room) player) player
                  (fn [result]
                    (if (:error result)
                      (error! (js/Error. (str "Bot paused: " (:error result) " Reload the host to retry.")))
                      (commit-job! job {:event (if (some? automated) :bot-command :timeout-command)
                                        :clock-id (:clock-id timed) :player player :command (:command result)})))))
             (if (some? automated) 350 0)))))))
(defn persist! [room]
  (-> (.save js/EurozonePeer (:room @session) (pr-str {:rules @store/engine-fingerprint :table room :seen (:seen @session)}))
      (.then (fn [_] (swap! session assoc :table room)))))
(defn create-table [options]
  (let [options (-> options (update :mode #(if % (keyword %) :online))
                    (update-in [:setup :mode] #(if % (keyword %) :random)))
        [_ room] (store/transition {} (:self @session) (merge {:event :create :name "Host" :title "Agricola" :mode :online :expected-players 2} (dissoc options :event)))]
    (assoc room :id (:room @session))))
(defn receive-command! [from message]
  (let [{:keys [event request-id]} message room (:table @session) id (:room @session)
        seated? (some #(= from (:owner %)) (:seats room))]
    (try
      (when-not room (throw (js/Error. "The host is restoring the table. Try again shortly.")))
      (case event
        :sync (snapshot! from)
        :chat/join (deliver! from (chat-roster room from))
        :chat/leave nil
        :recording/request (deliver! from (store/export-recording (view-store room) from id))
        :peer/chat
        (do
          (when-not (and seated? (string? (:text message)) (<= 1 (count (:text message)) 1000))
            (throw (js/Error. "Take a seat before sending chat.")))
          (let [name (:name (first (filter #(= from (:owner %)) (:seats room))))
                chat {:id (str (random-uuid)) :name name :text (:text message) :time (.now js/Date)}]
            (doseq [seat (:seats room) :when (some #(= (:owner seat) (:id %)) (:peers @session))]
              (deliver! (:owner seat) {:event :peer/chat :room id :message (assoc chat :own? (= from (:owner seat)))}))))
        (do
          (when-not (and (#{:configure :join :ready :leave :start :draw-pick :command :undo
                           :harvest-plan :cancel-harvest-plan :request-takeback :takeback-vote :cancel-takeback} event)
                         (string? request-id) (< (count request-id) 100))
            (throw (js/Error. "Invalid peer game command.")))
          (if (some #{[from request-id]} (:seen @session))
            (do (snapshot! from) (deliver! from {:event :accepted :id id :request-id request-id}))
            (do
              (when (and (= event :start) (not= (count (:seats room)) (count (get (presence room) id))))
                (throw (js/Error. "Wait until all seated players are connected.")))
              (let [[_ changed] (store/transition {id room} from (assoc message :id id))
                    seen (:seen @session)]
                (swap! session update :seen #(vec (take-last 128 (conj % [from request-id]))))
                (-> (persist! changed)
                    (.then (fn [_] (publish!) (deliver! from {:event :accepted :id id :request-id request-id})))
                    (.catch (fn [e] (swap! session assoc :seen seen)
                              (deliver! from {:event :error :message (str "Could not save the host's game: " (.-message e))})))))))))
      (catch :default e (deliver! from {:event :error :message (.-message e)}) (snapshot! from)))))
(defn receive! [from text]
  (enqueue!
   #(try
      (let [packet (reader/read-string text)]
        (when-not (= @store/engine-fingerprint (:rules packet))
          (throw (js/Error. "Game versions differ. Everyone must reload Eurozone before playing.")))
        (if (host?) (receive-command! from (:message packet))
            (when (= from (:host @session)) (rf/dispatch [:server-message (:message packet)]))))
      (catch :default e (error! e)))))
;; Every game packet carries the compiled rules identity, including host snapshots.
(defn packet [message] (pr-str {:rules @store/engine-fingerprint :message message}))
(defn send! [message]
  (try
    (.send @runtime (:host @session) (packet message))
    (catch :default e (error! e))))
(defn deliver! [id message]
  (when @runtime
    (if (= id (:self @session)) (rf/dispatch [:server-message message])
        (.send @runtime id (packet message)))))
(defn welcome! [message]
  (enqueue!
   #(do
      (swap! session merge (select-keys message [:room :self :host]))
      (if (host?)
        (-> (.read js/EurozonePeer (:room message))
            (.then (fn [saved]
                     (if saved
                       (let [saved (reader/read-string saved)]
                         (when-not (= @store/engine-fingerprint (:rules saved))
                           (throw (js/Error. "The saved host game uses different rules. Keep this backup and reopen it with the matching Eurozone version.")))
                         (when-not (= (:self message) (get-in saved [:table :host]))
                           (throw (js/Error. "This browser save belongs to another host.")))
                         (swap! session assoc :table (:table saved) :seen (:seen saved)))
                       (if (:initialized message)
                         (do (when (.-EUROZONE_ACTIVITY js/window)
                                 (rf/dispatch [:peer/missing-host-save (:room message)]))
                               (throw (js/Error. "This table has an existing host save. Reopen it in the original browser, or create a new Activity table.")))
                         (persist! (create-table (:options message)))))))
            (.then (fn [_] (when @runtime (.initialized @runtime) (publish!) (rf/dispatch [:navigate (:room message)])))))
        (rf/dispatch [:peer/status "Waiting for the host's table"])))))
(defn roster! [message]
  (enqueue!
   #(do (swap! session assoc :peers (:peers message))
        (if (host?) (publish!)
            (if (some (fn [p] (= (:host @session) (:id p))) (:peers message))
              (send! {:event :sync})
              (rf/dispatch [:connection false]))))))
(defn stop! []
  (swap! connection-generation inc)
  (when @runtime (.close @runtime))
  (reset! runtime nil) (bots/close!) (reset! bot-job nil)
  ;; Let accepted host commands finish their durable save before leaving.
  (enqueue! #(do (reset! session {}) (reset! comms/peer-sender nil))))
(defn start! [{:keys [room invite options relay]}]
  (let [generation (swap! connection-generation inc)
        current? #(= generation @connection-generation)]
    (when @runtime (.close @runtime))
    (bots/close!) (reset! bot-job nil)
    (comms/use-peer! send!)
    (reset! session {:room room :seen []})
    (reset! runtime
      (.start js/EurozonePeer
              (clj->js {:room room :invite invite :createOptions options :relay relay :token (comms/session-token)
                        :onPacket #(when (current?) (receive! %1 %2))
                        :onWelcome #(when (current?) (welcome! (js->clj % :keywordize-keys true)))
                        :onRoster #(when (current?) (roster! (js->clj % :keywordize-keys true)))
                        :onStatus #(when (current?) (rf/dispatch [:peer/status %]))
                        :onError #(when (current?) (error! (js/Error. %)))
                        :onDirect (fn [_] (when (and (current?) (not (host?))) (send! {:event :sync})))})))))
(defn boot! []
  (let [params (js/URLSearchParams. (.-search js/location)) room (.get params "peer") invite (.get params "key")]
    (when (and room invite)
      (start! {:room room :invite invite :relay (or (.-EUROZONE_ACTIVITY js/window) (= "relay" (.get params "transport")))}) true)))
(defn create! [message]
  (let [room (str (random-uuid)) invite (comms/fresh-token) url (js/URL. (.-href js/location))]
    (.set (.-searchParams url) "peer" room) (.set (.-searchParams url) "key" invite)
    (set! (.-hash url) room) (.replaceState js/history nil "" (.toString url))
    (rf/dispatch [:navigate room])
    (start! {:room room :invite invite :options (assoc (dissoc message :request-id :revision :id) :mode :online)
             :relay (or (boolean (.-EUROZONE_ACTIVITY js/window)) (= "relay" (.get (.-searchParams url) "transport")))})))
(rf/reg-event-db :peer/status (fn [db [_ status]] (assoc db :peer-status status)))
(rf/reg-fx :peer/create create!)
(defonce clock-tick (js/setInterval schedule-bot! 250))

(rf/reg-event-db :peer/missing-host-save (fn [db [_ room]] (assoc db :missing-host-save room)))
(rf/reg-fx :activity/load
  (fn [_]
    (-> (.EUROZONE_ACTIVITY_TABLES js/window "list")
        (.then (fn [result]
                 (js/Promise.all
                  (.map (.-tables result)
                        (fn [table]
                          (if (and (aget table "host?") (.-initialized table))
                            (-> (.read js/EurozonePeer (.-room table))
                                (.then (fn [saved] (aset table "saveAvailable?" (boolean saved)) table)))
                            (js/Promise.resolve table)))))))
        (.then #(rf/dispatch [:activity/tables (js->clj % :keywordize-keys true)]))
        (.catch error!))))
(rf/reg-event-fx :activity/load (fn [{:keys [db]} _] {:db (assoc db :activity-lobby? true :busy? true :error nil) :activity/load true}))
(rf/reg-event-fx :activity/refresh (fn [_ _] {:activity/load true}))
(rf/reg-event-db :activity/tables (fn [db [_ tables]] (cond-> (assoc db :activity-tables tables) (not (:activity-opening? db)) (assoc :busy? false))))
(rf/reg-event-fx :activity/enter
  (fn [{:keys [db]} [_ action room]]
    {:db (assoc db :busy? true :activity-opening? true :error nil) :activity/enter {:action action :room room :request-id (str (random-uuid))}}))
(rf/reg-fx :activity/enter
  (fn [{:keys [action room request-id]}]
    (-> (.EUROZONE_ACTIVITY_TABLES js/window action room request-id)
        (.then #(.EUROZONE_ENTER_ACTIVITY_TABLE js/window %))
        (.catch error!))))

(rf/reg-event-fx :activity/change
  (fn [{:keys [db]} [_ table]] {:db (assoc db :busy? true) :dispatch [:chat/disconnect] :activity/change table}))
(rf/reg-fx :activity/change
  (fn [table]
    (-> (stop!)
        (.then (fn [_]
                 (.EUROZONE_SET_ACTIVITY_URL js/window (when table (clj->js table)))
                 (rf/dispatch [:activity/selected table])))
        (.catch error!))))
(rf/reg-event-fx :activity/selected
  (fn [{:keys [db]} [_ table]]
    {:db {:facts (d/empty-db model/schema) :permissions {} :layouts (:layouts db) :connected? false
          :game-id (:room table) :draft {} :panel :board :activity-lobby? (nil? table)}
     :activity/connect table}))
(rf/reg-fx :activity/connect
  (fn [table] (if table (start! {:room (:room table) :invite (:invite table) :relay true})
                          (rf/dispatch [:activity/load]))))
