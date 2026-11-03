(ns eurozone.browser.events
  (:require [re-frame.core :as rf] [datascript.core :as d]
            [eurozone.model :as model] [eurozone.browser.comms :as comms] [eurozone.browser.chat] [eurozone.browser.peer] [eurozone.browser.layout :as layout] [eurozone.browser.readability :as readability]))

(defn hash-id [] (let [h (subs (.-hash js/location) 1)] (when (seq h) h)))
(rf/reg-event-db :initialize (fn [_ _] {:facts (d/empty-db model/schema) :permissions {} :layouts (layout/load-layouts) :text-size (readability/load-size) :connected? false :game-id (hash-id) :draft {} :panel :board}))
(rf/reg-event-fx :connection (fn [{:keys [db]} [_ connected?]] {:db (assoc db :connected? connected? :busy? false :recording-request nil) :dispatch (if connected? [:chat/sync] [:chat/disconnect])}))
(rf/reg-event-db :error (fn [db [_ message]] (assoc db :error message :busy? false :recording-request nil :activity-opening? false)))
(rf/reg-event-db :dismiss-error (fn [db _] (dissoc db :error)))
(rf/reg-event-db :panel (fn [db [_ panel]] (assoc db :panel panel)))
(rf/reg-event-db :draft
  (fn [db [_ key value]]
    (cond-> (assoc-in db [:draft key] value)
      (= key :improvement) (update :draft dissoc :upgrade :bake-grain :card :discount-resource :discount-resources :manufacturer-discount :wood-carver? :return-improvement :return-improvements :as-major?)
      (= key :card) (update :draft dissoc :improvement :upgrade :bake-grain :payment-option :return-improvement :return-improvements :as-major? :discount-resource :discount-resources :manufacturer-discount :wood-carver?)
      (= key :payment-option) (update :draft dissoc :discount-resource :discount-resources)
      (= key :discount-resource) (update :draft dissoc :discount-resources))))
(rf/reg-event-db :draft-discount-choice
  (fn [db [_ index amount resource]]
    (update db :draft
      (fn [draft]
        (-> draft
            (assoc :discount-resources (assoc (vec (take amount (concat (:discount-resources draft) (repeat nil)))) index resource))
            (dissoc :discount-resource))))))
;; Apply multi-control edits to the latest draft, not the render-time snapshot.
;; Several DOM changes can arrive before Reagent renders the next frame.
(rf/reg-event-db :draft-adjust-in
  (fn [db [_ path default delta maximum]]
    (update-in db (into [:draft] path) #(max 0 (min maximum (+ (or % default) delta))))))
(rf/reg-event-db :draft-adjust
  (fn [db [_ key default delta maximum]]
    (update-in db [:draft key] #(max 0 (min maximum (+ (or % default) delta))))))
(rf/reg-event-db :draft-resource-queue
  (fn [db [_ decision amount operation value]]
    (let [prior (get-in db [:draft :effect-resource-queue])
          resources (if (= decision (:decision prior)) (vec (:resources prior)) [])
          next (case operation
                 :add (cond (= amount 1) [value]
                            (< (count resources) amount) (conj resources value)
                            :else resources)
                 :remove (if (and (integer? value) (<= 0 value) (< value (count resources)))
                           (vec (concat (subvec resources 0 value) (subvec resources (inc value)))) resources)
                 :clear [])]
      (assoc-in db [:draft :effect-resource-queue] {:decision decision :resources next}))))

(rf/reg-event-db :draft-map-entry
  (fn [db [_ key entry value]]
    (if (nil? value) (update-in db [:draft key] dissoc entry)
        (assoc-in db [:draft key entry] value))))
(rf/reg-event-db :draft-planting
  (fn [db [_ cell crop]]
    (update-in db [:draft :plantings]
               (fn [plantings]
                 (let [others (vec (remove #(= cell (:cell %)) plantings))]
                   (cond-> others crop (conj {:cell cell :crop crop})))))))
(rf/reg-event-db :draft-card
  (fn [db [_ card selected?]]
    (update-in db [:draft :cards]
               (fn [cards] (cond-> (vec (remove #{card} cards)) selected? (conj card))))))
(rf/reg-event-db :draft-toggle-value
  (fn [db [_ key value]]
    (update-in db [:draft key]
               (fn [selection]
                 (if (some #{value} selection) (vec (remove #{value} selection)) (conj (vec selection) value))))))
(rf/reg-event-db :draft-selection
  (fn [db [_ key value selected?]]
    (update-in db [:draft key]
               (fn [selection]
                 (let [others (vec (remove #{value} selection))]
                   (cond-> others selected? (conj value)))))))
(rf/reg-event-db :draft-room
  (fn [db [_ cell selected?]]
    (update-in db [:draft :rooms]
               (fn [rooms]
                 (let [others (vec (remove #{cell} rooms))]
                   (cond-> others selected? (conj cell)))))))
(rf/reg-event-db :draft-plow-field
  (fn [db [_ index cell]]
    (update-in db [:draft :cells]
               (fn [selection]
                 (let [cells (vec selection)]
                   (if (some? cell)
                     (assoc (vec (take (max 2 (inc index) (count cells)) (concat cells (repeat "")))) index cell)
                     (subvec cells 0 (min index (count cells)))))))))
(rf/reg-event-db :select-action
  (fn [db [_ space]]
    (-> db (assoc :draft {:space space :rooms [] :stables [] :fences [] :plantings [] :bake-grain 0} :error nil)
        (update :action-selection (fnil inc 0)))))
(rf/reg-event-db :inspect-player (fn [db [_ pid]] (assoc db :inspect-player pid)))
(rf/reg-fx :observer/preference comms/remember-observer!)
(rf/reg-event-fx :table/enter
  (fn [{:keys [db]} [_ id observe? activity?]]
    {:db (update db :observer-choice-version (fnil inc 0))
     :fx [[:observer/preference [id observe?]]
          [:dispatch (if activity? [:activity/enter "join" id] [:navigate id])]]}))
(rf/reg-event-fx :table/observer-choice
  (fn [{:keys [db]} [_ id observe?]]
    {:db (update db :observer-choice-version (fnil inc 0)) :observer/preference [id observe?]}))
(rf/reg-fx :navigate #(cond
                         (and (nil? %) (.-EUROZONE_ACTIVITY js/window)) (.EUROZONE_ACTIVITY_HOME js/window)
                         (and (nil? %) (comms/peer?)) (set! (.-href js/location) "/")
                         :else (set! (.-hash js/location) (or % ""))))
(rf/reg-event-fx :navigate
  (fn [{:keys [db]} [_ id]]
    {:db (assoc db :game-id id :draft {} :inspect-player nil :error nil
                :panel (if (= :finished (:status (model/pull-game (:facts db) id))) :scores :board))
     :navigate id :dispatch [:chat/sync]}))
(rf/reg-fx :send comms/send!)
(rf/reg-event-fx :request-recording
  (fn [{:keys [db]} [_ action]]
    (cond
      (:recording-request db) {}
      (not (:connected? db)) {:db (assoc db :error "Reconnect to the table before opening its recording.")}
      :else {:db (assoc db :busy? true :error nil :recording-request {:id (:game-id db) :action action})
             :send {:event :recording/request :id (:game-id db) :request-id (str (random-uuid))}})))
(rf/reg-fx :download-recording
  (fn [{:keys [text filename]}]
    (let [blob (js/Blob. #js [text] #js {:type "application/edn"}) url (.createObjectURL js/URL blob)
          link (.createElement js/document "a")]
      (set! (.-href link) url) (set! (.-download link) filename)
      (.appendChild (.-body js/document) link) (.click link) (.remove link)
      (js/setTimeout #(.revokeObjectURL js/URL url) 1000))))
(rf/reg-event-fx :send
  (fn [{:keys [db]} [_ message]]
    (if-not (:connected? db) {:db (assoc db :error "Wait for the connection before making a move.")}
      (let [room (model/pull-game (:facts db) (:game-id db))]
        {:db (assoc db :busy? true :error nil)
         (if (= :peer (:mode message)) :peer/create :send) (merge {:id (:game-id db) :revision (:revision room) :request-id (str (random-uuid))} message)}))))
(rf/reg-event-fx :command (fn [_ [_ cmd]] {:dispatch [:send {:event :command :command cmd}]}))
(rf/reg-event-fx :server-message
  (fn [{:keys [db]} [_ message]]
    (case (:event message)
      :recording (if (and (:recording-request db) (:id message)
                          (not= (:id message) (get-in db [:recording-request :id]))) {}
                   {:db (assoc db :busy? false :recording-request nil)
                    (if (= :replay (get-in db [:recording-request :action])) :replay-recording :download-recording) message})
      :snapshot {:db (assoc db :facts (d/db-with (d/empty-db model/schema) (model/datoms->tx (:datoms message))) :permissions (:permissions message) :private-hands (:private-hands message) :private-draws (:private-draws message) :private-plans (:private-plans message)
                           :server-offset (- (or (:server-time message) (.now js/Date)) (.now js/Date)) :connected? true :busy? false :presence (:presence message)) :dispatch [:chat/sync]}
      :facts {:db (assoc db :presence (:presence message) :facts (d/db-with (:facts db) (model/datoms->tx (:datoms message))) :permissions (:permissions message) :private-hands (:private-hands message) :private-draws (:private-draws message) :private-plans (:private-plans message)
                        :server-offset (- (or (:server-time message) (.now js/Date)) (.now js/Date))) :dispatch [:chat/sync]}
      :error {:db (assoc db :error (:message message) :busy? false :recording-request nil)}
      :accepted {:db (cond-> (assoc db :busy? false :draft {} :inspect-player nil :game-id (:id message))
                       (= :finished (:status (model/pull-game (:facts db) (:id message)))) (assoc :panel :scores)) :navigate (:id message) :dispatch [:chat/sync]}
      :presence {:db (assoc db :presence (:presence message))}
      :peer/chat {:dispatch-n (cond-> [[:chat/message (:room message) (:message message)]]
                               (get-in message [:message :own?]) (conj [:chat/draft ""]))}
      :chat/roster {:dispatch [:chat/roster message]}
      :chat/signal {:dispatch [:chat/signal message]}
      :chat/error {:dispatch [:chat/error (:message message)]}
      {})))
