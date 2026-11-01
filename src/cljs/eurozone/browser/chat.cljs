(ns eurozone.browser.chat
  (:require [re-frame.core :as rf] [clojure.string :as str] [cljs.reader :as reader]
            [eurozone.model :as model] [eurozone.browser.comms :as comms]))

(defonce session (atom {:room nil :self nil :peers {}}))
(defonce connections (atom {}))
(def default-options {:mode :collapsed :pinned? true :corner :right})
(defn storage-key [room]
  (str "eurozone-chat-v1:" (or (.-EUROZONE_DISCORD_USER js/window)
                                 (.getItem js/sessionStorage "eurozone-session")
                                 (.getItem js/localStorage "eurozone-session") "guest") ":" room))
(defn load-room [room]
  (try
    (let [saved (some-> (.getItem js/localStorage (storage-key room)) reader/read-string)
          options (:options saved)]
      {:messages (vec (take-last 100 (filter #(and (string? (:id %)) (string? (:name %))
                                                   (string? (:text %)) (<= (count (:text %)) 1000)
                                                   (number? (:time %))) (:messages saved))))
       :options {:mode (if (#{:collapsed :open :expanded} (:mode options)) (:mode options) :collapsed)
                 :pinned? (not (false? (:pinned? options)))
                 :corner (if (= :left (:corner options)) :left :right)}
       :unread (max 0 (min 100 (if (number? (:unread saved)) (:unread saved) 0)))})
    (catch :default _ {:messages [] :options default-options :unread 0})))
(defn saved-room [db room]
  {:messages (get-in db [:chat-messages room] []) :options (get-in db [:chat-options room] default-options)
   :unread (get-in db [:chat-unread room] 0)})
(rf/reg-fx :chat/save
  (fn [[room saved]]
    (try (.setItem js/localStorage (storage-key room) (pr-str saved))
         (rf/dispatch [:chat/storage-error false])
         (catch :default _ (rf/dispatch [:chat/storage-error true])))))
(rf/reg-event-db :chat/storage-error (fn [db [_ failed?]] (assoc db :chat-storage-error failed?)))
(rf/reg-event-fx :chat/view
  (fn [{:keys [db]} [_ room changes]]
    (let [options (merge (get-in db [:chat-options room] default-options) changes)
          db (cond-> (assoc-in db [:chat-options room] options)
               (not= :collapsed (:mode options)) (assoc-in [:chat-unread room] 0))]
      {:db db :chat/save [room (saved-room db room)]})))
(rf/reg-event-fx :chat/clear
  (fn [{:keys [db]} [_ room]]
    (let [db (-> db (assoc-in [:chat-messages room] []) (assoc-in [:chat-unread room] 0))]
      {:db db :chat/save [room (saved-room db room)]})))
(defn status! [peer status] (rf/dispatch [:chat/status peer status]))
(defn error! [message] (rf/dispatch [:chat/error message]))
(defn close-peer! [id]
  (when-let [{:keys [pc dc]} (get @connections id)]
    (swap! connections dissoc id)
    (when dc (.close dc))
    (.close pc)))
(defn reset-connections! []
  (doseq [id (keys @connections)] (close-peer! id))
  (reset! session {:room nil :self nil :peers {}}))
(defn signal! [id kind payload]
  (comms/send! {:event :chat/signal :room (:room @session) :to id :kind kind :payload payload}))
(defn attach-channel! [id dc]
  (swap! connections assoc-in [id :dc] dc)
  (set! (.-onopen dc) #(status! id :open))
  (set! (.-onclose dc) #(status! id :closed))
  (set! (.-onerror dc) #(status! id :failed))
  (set! (.-onmessage dc)
        (fn [event]
          (try
            (let [raw (.-data event)]
              (when (and (string? raw) (<= (count raw) 4096))
                (let [peer (get-in @session [:peers id])
                      {:keys [text id room]} (js->clj (js/JSON.parse raw) :keywordize-keys true)]
                  (when (and peer (= room (:room @session)) (string? id) (< (count id) 80)
                             (string? text) (<= 1 (count (str/trim text)) 1000))
                    (rf/dispatch [:chat/message room {:id id :name (:name peer) :text text :time (.now js/Date)}])))))
            (catch :default _ nil)))))
(defn peer! [id]
  (or (:pc (get @connections id))
      (let [config (or (.-EUROZONE_RTC_CONFIG js/window)
                       #js {:iceServers #js [#js {:urls "stun:stun.l.google.com:19302"}]})
            pc (js/RTCPeerConnection. config)]
        (swap! connections assoc id {:pc pc :ice []})
        (status! id :connecting)
        (set! (.-onicecandidate pc)
              #(when-let [candidate (.-candidate %)]
                 (signal! id :ice (js->clj (.toJSON candidate) :keywordize-keys true))))
        (set! (.-ondatachannel pc) #(attach-channel! id (.-channel %)))
        (set! (.-onconnectionstatechange pc)
              #(let [state (.-connectionState pc) dc (get-in @connections [id :dc])]
                 (cond
                   (and (= "connected" state) dc (= "open" (.-readyState dc))) (status! id :open)
                   (#{"failed" "disconnected" "closed"} state) (status! id (keyword state)))))
        pc)))
(defn flush-ice! [id pc]
  (let [candidates (:ice (get @connections id))]
    (swap! connections assoc-in [id :ice] [])
    (js/Promise.all (clj->js (map #(.addIceCandidate pc (clj->js %)) candidates)))))
(defn offer! [id]
  (let [pc (peer! id)]
    (attach-channel! id (.createDataChannel pc "eurozone-chat" #js {:ordered true}))
    (-> (.createOffer pc)
        (.then #(.setLocalDescription pc %))
        (.then #(signal! id :offer (js->clj (.toJSON (.-localDescription pc)) :keywordize-keys true)))
        (.catch #(status! id :failed)))))
(defn roster! [{:keys [room self peers]}]
  (when (= room (:room @session))
    (let [others (into {} (map (juxt :id identity) (remove #(= self (:id %)) peers)))]
      (swap! session assoc :self self :peers others)
      (doseq [id (keys @connections) :when (not (contains? others id))] (close-peer! id))
      (doseq [id (keys others) :when (not (contains? @connections id))]
        (if (comms/peer?) (status! id :open)
          (if (neg? (compare self id)) (offer! id) (peer! id)))))))
(defn receive-signal! [{:keys [room from kind payload]}]
  (when (and (= room (:room @session)) (get-in @session [:peers from]))
    (let [pc (peer! from)]
      (-> (case kind
            :ice (if (.-remoteDescription pc)
                   (.addIceCandidate pc (clj->js payload))
                   (do (swap! connections update-in [from :ice] conj payload) (js/Promise.resolve nil)))
            :offer (-> (.setRemoteDescription pc (clj->js payload))
                       (.then #(flush-ice! from pc))
                       (.then #(.createAnswer pc))
                       (.then #(.setLocalDescription pc %))
                       (.then #(signal! from :answer (js->clj (.toJSON (.-localDescription pc)) :keywordize-keys true))))
            :answer (-> (.setRemoteDescription pc (clj->js payload)) (.then #(flush-ice! from pc)))
            (js/Promise.resolve nil))
          (.catch #(status! from :failed))))))

(rf/reg-fx :chat/join
  (fn [room]
    (when-not (= room (:room @session))
      (reset-connections!)
      (swap! session assoc :room room)
      (if (or (comms/peer?) (nil? room) (exists? js/RTCPeerConnection))
        (comms/send! (if room {:event :chat/join :room room} {:event :chat/leave}))
        (error! "This browser does not support direct chat. Use a current browser with WebRTC.")))))
(rf/reg-fx :chat/reconnect (fn [_] (comms/send! {:event :chat/leave}) (reset-connections!)))
(rf/reg-fx :chat/reset (fn [_] (reset-connections!)))
(rf/reg-fx :chat/roster roster!)
(rf/reg-fx :chat/signal receive-signal!)
(rf/reg-event-fx :chat/sync
  (fn [{:keys [db]} _]
    (let [room (:game-id db) seated? (seq (get-in db [:permissions room :seats]))
          desired (when (and (:connected? db) seated?) room)]
      (if (= desired (:room @session)) {}
          (let [saved (when desired (load-room desired))
                restore? (and desired (not (contains? (:chat-messages db) desired)))]
            {:db (cond-> (assoc db :chat-room desired :chat-self nil :chat-peers [] :chat-status {} :chat-error nil :chat-draft "")
                   restore? (assoc-in [:chat-messages desired] (:messages saved))
                   restore? (assoc-in [:chat-options desired] (:options saved))
                   restore? (assoc-in [:chat-unread desired] (:unread saved)))
             :chat/join desired})))))
(rf/reg-event-fx :chat/roster
  (fn [{:keys [db]} [_ message]]
    (if (= (:room message) (:chat-room db))
      {:db (assoc db :chat-self (:self message) :chat-peers (vec (remove #(= (:self message) (:id %)) (:peers message))))
       :chat/roster message} {})))
(rf/reg-event-fx :chat/signal (fn [_ [_ message]] {:chat/signal message}))
(rf/reg-event-db :chat/error (fn [db [_ message]] (assoc db :chat-error message)))
(rf/reg-event-db :chat/status (fn [db [_ id status]] (assoc-in db [:chat-status id] status)))
(rf/reg-event-fx :chat/message
  (fn [{:keys [db]} [_ room message]]
    (let [messages (get-in db [:chat-messages room] [])]
      (if (some #(= (:id %) (:id message)) messages) {}
          (let [db (cond-> (assoc-in db [:chat-messages room] (vec (take-last 100 (conj messages message))))
                     (and (not (:own? message)) (= :collapsed (get-in db [:chat-options room :mode] :collapsed)))
                     (update-in [:chat-unread room] #(min 100 (inc (or % 0)))))]
            {:db db :chat/save [room (saved-room db room)]})))))
(rf/reg-event-db :chat/draft (fn [db [_ text]] (assoc db :chat-draft text)))
(rf/reg-event-fx :chat/disconnect
  (fn [{:keys [db]} _]
    {:db (assoc db :chat-room nil :chat-self nil :chat-peers [] :chat-status {}) :chat/reset true}))
(rf/reg-event-fx :chat/retry
  (fn [{:keys [db]} _]
    ;; Leave/rejoin assigns a fresh peer id and recreates both ends of each channel.
    {:db (assoc db :chat-room nil :chat-self nil :chat-peers [] :chat-status {} :chat-error nil)
     :chat/reconnect true :dispatch [:chat/sync]}))
(rf/reg-fx :chat/send
  (fn [{:keys [room text name]}]
    (let [ids (keys (:peers @session))
          channels (map #(get-in @connections [% :dc]) ids)
          ready? (and (= room (:room @session)) (seq ids) (every? #(and % (= "open" (.-readyState %))) channels))]
      (if (comms/peer?) (comms/send! {:event :peer/chat :text text})
       (if-not ready? (error! "Wait for a direct connection to everyone at the table.")
        (try
          (let [message {:id (str (random-uuid)) :room room :text text}]
            (doseq [dc channels] (.send dc (js/JSON.stringify (clj->js message))))
            (rf/dispatch [:chat/message room (assoc message :name name :own? true :time (.now js/Date))])
            (rf/dispatch [:chat/draft ""]))
          (catch :default _ (error! "A peer disconnected during send. Some peers may have received the message."))))))))
(rf/reg-event-fx :chat/send
  (fn [{:keys [db]} _]
    (let [text (str/trim (or (:chat-draft db) "")) room (:game-id db)
          seat (first (get-in db [:permissions room :seats]))
          name (get-in (model/pull-game (:facts db) room) [:seats seat :name])]
      (when (and (some? seat) (<= 1 (count text) 1000))
        {:db (assoc db :chat-error nil) :chat/send {:room room :text text :name name}}))))
