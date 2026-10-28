(ns eurozone.browser.events
  (:require [re-frame.core :as rf] [datascript.core :as d]
            [eurozone.model :as model] [eurozone.browser.comms :as comms] [eurozone.browser.chat] [eurozone.browser.peer] [eurozone.browser.layout :as layout]))

(defn hash-id [] (let [h (subs (.-hash js/location) 1)] (when (seq h) h)))
(rf/reg-event-db :initialize (fn [_ _] {:facts (d/empty-db model/schema) :permissions {} :layouts (layout/load-layouts) :connected? false :game-id (hash-id) :draft {} :panel :board}))
(rf/reg-event-fx :connection (fn [{:keys [db]} [_ connected?]] {:db (assoc db :connected? connected? :busy? false) :dispatch (if connected? [:chat/sync] [:chat/disconnect])}))
(rf/reg-event-db :error (fn [db [_ message]] (assoc db :error message :busy? false)))
(rf/reg-event-db :dismiss-error (fn [db _] (dissoc db :error)))
(rf/reg-event-db :panel (fn [db [_ panel]] (assoc db :panel panel)))
(rf/reg-event-db :draft
  (fn [db [_ key value]]
    (cond-> (assoc-in db [:draft key] value)
      (= key :improvement) (update :draft dissoc :upgrade :bake-grain :card)
      (= key :card) (update :draft dissoc :improvement :upgrade :bake-grain))))
(rf/reg-event-db :select-action (fn [db [_ space]] (assoc db :draft {:space space :rooms [] :stables [] :fences [] :plantings [] :bake-grain 0} :error nil)))
(rf/reg-event-db :inspect-player (fn [db [_ pid]] (assoc db :inspect-player pid)))
(rf/reg-fx :navigate #(if (and (nil? %) (comms/peer?)) (set! (.-href js/location) "/") (set! (.-hash js/location) (or % ""))))
(rf/reg-event-fx :navigate
  (fn [{:keys [db]} [_ id]]
    {:db (assoc db :game-id id :draft {} :inspect-player nil :error nil
                :panel (if (= :finished (:status (model/pull-game (:facts db) id))) :scores :board))
     :navigate id :dispatch [:chat/sync]}))
(rf/reg-fx :send comms/send!)
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
      :recording {:db (assoc db :busy? false) :download-recording message}
      :snapshot {:db (assoc db :facts (d/db-with (d/empty-db model/schema) (model/datoms->tx (:datoms message))) :permissions (:permissions message) :private-hands (:private-hands message) :private-draws (:private-draws message) :private-plans (:private-plans message)
                           :server-offset (- (or (:server-time message) (.now js/Date)) (.now js/Date)) :connected? true :busy? false :presence (:presence message)) :dispatch [:chat/sync]}
      :facts {:db (assoc db :presence (:presence message) :facts (d/db-with (:facts db) (model/datoms->tx (:datoms message))) :permissions (:permissions message) :private-hands (:private-hands message) :private-draws (:private-draws message) :private-plans (:private-plans message)
                        :server-offset (- (or (:server-time message) (.now js/Date)) (.now js/Date))) :dispatch [:chat/sync]}
      :error {:db (assoc db :error (:message message) :busy? false)}
      :accepted {:db (cond-> (assoc db :busy? false :draft {} :inspect-player nil :game-id (:id message))
                       (= :finished (:status (model/pull-game (:facts db) (:id message)))) (assoc :panel :scores)) :navigate (:id message) :dispatch [:chat/sync]}
      :presence {:db (assoc db :presence (:presence message))}
      :peer/chat {:dispatch-n (cond-> [[:chat/message (:room message) (:message message)]]
                               (get-in message [:message :own?]) (conj [:chat/draft ""]))}
      :chat/roster {:dispatch [:chat/roster message]}
      :chat/signal {:dispatch [:chat/signal message]}
      :chat/error {:dispatch [:chat/error (:message message)]}
      {})))
