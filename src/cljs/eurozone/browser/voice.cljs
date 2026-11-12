(ns eurozone.browser.voice
  (:require [reagent.core :as r] [re-frame.core :as rf] [re-frame.db :as rfdb] [eurozone.voice :as voice]
            [eurozone.model :as model] [agricola.card-game :as cards] [eurozone.browser.comms :as comms]
            [eurozone.browser.assistant :as assistant] [clojure.string :as str]))

(defonce relay (atom nil))
(defonce feedback (atom nil))
(defn speech [method & args]
  (when-let [engine (aget js/window "EurozoneSpeech")]
    (.apply (aget engine method) engine (to-array args))))
(defn surface []
  (if-let [engine (aget js/window "EurozoneActions")]
    (js->clj (.call (aget engine "snapshot") engine) :keywordize-keys true)
    {:version "none" :scope "No game" :actions []}))
(defn stamp [room ui]
  (pr-str [(:id room) (:revision room) (cards/acting-player (:state room))
           (get-in ui [:permissions (:id room) :seats]) (:navigation-version ui 0)
           (:action-selection ui 0) (hash (:draft ui)) (:version (surface))
           (get-in ui [:replica :tables (:id room) :authority-epoch])]))
(defn available? [room ui]
  (and (voice/eligible? room (get-in ui [:permissions (:id room) :seats]) (:connected? ui) (:busy? ui))
       (not (:space (:draft ui)))))
(defn send-status! [value]
  (reset! feedback value)
  (when (and @relay (= 1 (.-readyState @relay)))
    (.send @relay (js/JSON.stringify (clj->js (assoc value :op "status"))))))
(defn close-relay! []
  (when-let [socket @relay] (reset! relay nil) (.close socket)))
(rf/reg-fx :voice/status send-status!)
(rf/reg-event-db :voice/message (fn [db [_ text]] (assoc-in db [:voice :message] text)))
(rf/reg-event-db :voice/minimize (fn [db [_ value]] (assoc-in db [:voice :minimized?] value)))
(rf/reg-event-db :voice/context-changed
  (fn [db [_ version]] (if (get-in db [:voice :open?]) (assoc-in db [:voice :context-version] version) db)))
(defonce context-listener
  (.addEventListener js/window "eurozone-controls-changed" #(rf/dispatch [:voice/context-changed (.-detail %)])))
(rf/reg-event-db :voice/open (fn [db [_ open?]] (assoc db :voice {:open? open? :message "Say what you want to do. Clear commands run immediately."})))

(defn resolve-heard [db captured text result]
  (let [room (model/pull-game (:facts db) (:game-id db)) version (stamp room db)
        pending (get-in db [:voice :pending])
        action (if (= :confirm (:kind result)) pending result)
        ui? (= :control (:kind action))
        attempt? (or (= :confirm (:kind result))
                     (and (#{:action :control} (:kind result)) (not= "low" (:confidence result))))
        error (cond
                (not (get-in db [:voice :open?])) "Open Voice commands in the game first."
                (not= captured version) "The game or open controls changed. Say the command again."
                (#{:cancel :answer} (:kind result)) nil
                (= :error (:kind result)) (:message result)
                (and (= :confirm (:kind result)) (not= version (:version pending))) "No current choice to confirm. Describe what you want to do."
                (or (not (:connected? db)) (:busy? db)) "Wait for the current move to finish."
                (and ui? (not= (:surface action) (:version (surface)))) "The open controls changed. Say that again."
                (and (not ui?) (not (available? room db))) "Use the current decision's controls, or wait for your turn.")
        submit? (and (nil? error) attempt?)
        uncertain? (and (nil? error) (not attempt?) (#{:action :control} (:kind result)))
        message (or error
                    (when uncertain? (str (:message result) " Did you mean “" (:name result) "”? Confirm or clarify."))
                    (case (:kind result)
                      :answer (:message result) :cancel "Cancelled. No further action will run."
                      (or (:message action) (str "Using " (:name action) "."))))
        chain (when (and submit? ui?) {:id (str (random-uuid)) :text (or (:intent action) text)
                                      :steps (inc (:steps action 0)) :progress (:progress action [])
                                      :table (:id room) :revision (:revision room)})
        next (-> db (assoc-in [:voice :heard] text) (assoc-in [:voice :message] message)
                 (assoc-in [:voice :request] nil) (assoc-in [:voice :chain] chain)
                 (assoc-in [:voice :pending] (when uncertain? (assoc result :version version))))]
    (cond-> {:db next :voice/status {:version version :message message}}
      (and submit? ui?) (assoc :voice/control {:action action :chain chain})
      (and submit? (not ui?)) (assoc :dispatch (if (= :open (:mode action)) [:select-action (:space action)]
                                                 [:command {:type :action :space (:space action)}])))))

(rf/reg-fx :voice/control
  (fn [{:keys [action chain]}]
    (let [engine (aget js/window "EurozoneActions")
          result (when engine (js->clj (.call (aget engine "execute") engine (:space action) (:value action) (:surface action)) :keywordize-keys true))]
      (if (:ok result)
        (js/requestAnimationFrame
          (fn [] (js/requestAnimationFrame
                    (fn [] (js/setTimeout #(rf/dispatch [:voice/control-done chain action]) 140)))))
        (rf/dispatch [:voice/control-failed (:id chain) (or (:error result) "The control is unavailable.")])))))
(rf/reg-event-db :voice/control-failed
  (fn [db [_ id message]]
    (if (= id (get-in db [:voice :chain :id]))
      (-> db (assoc-in [:voice :chain] nil) (assoc-in [:voice :message] message)) db)))
(rf/reg-event-fx :voice/control-done
  (fn [{:keys [db]} [_ chain action]]
    (let [room (model/pull-game (:facts db) (:game-id db))]
      (if-not (= (:id chain) (get-in db [:voice :chain :id])) {}
        (if (and (:continue action) (= (:table chain) (:id room)) (= (:revision chain) (:revision room))
                 (get-in db [:voice :assistant :connected?]) (not (:busy? db)) (< (:steps chain) 24))
          {:db (assoc-in db [:voice :chain] nil)
           :dispatch [:voice/heard (stamp room db) (:text chain)
                      {:steps (:steps chain) :progress (conj (:progress chain) (select-keys action [:name :value]))}]}
          {:db (cond-> (assoc-in db [:voice :chain] nil)
                 (>= (:steps chain) 24) (assoc-in [:voice :message] "Stopped after 24 control steps. Check the board before continuing."))})))))

(rf/reg-event-db :voice/assistant-state
  (fn [db [_ state]]
    (-> db (assoc-in [:voice :assistant] state) (assoc-in [:voice :request] nil)
        (assoc-in [:voice :pending] nil) (assoc-in [:voice :chain] nil) (assoc-in [:voice :history] []))))
(defn request-context [db room shown progress]
  (-> (voice/assistant-context room (get-in db [:permissions (:id room) :seats])
                               (get-in db [:voice :history]) (get-in db [:voice :pending]))
      (assoc :schema "agricola-assistant-v2" :actions (:actions shown)
             :board (voice/assistant-actions (:state room))
             :view (select-keys shown [:scope :text]) :progress progress
             :limits "Native enabled UI controls for the current view/decision. Visible own cards may be included. Engine validates game moves; unrevealed state is excluded.")))
(defn named-control [shown text]
  (let [matches (filter #(= (voice/normalize text)
                           (voice/normalize (clojure.string/replace (first (clojure.string/split (:name %) #" · ")) #"[→↗]" ""))) (:actions shown))]
    (when (= 1 (count matches))
      (assoc (first matches) :kind :control :surface (:version shown) :confidence "high"))))
(rf/reg-event-fx :voice/heard
  (fn [{:keys [db]} [_ captured text continuation]]
    (let [room (model/pull-game (:facts db) (:game-id db)) shown (surface)
          parsed (voice/interpret (:state room) text)
          control (named-control shown text)
          local? (or (and (#{:cancel :confirm} (:kind parsed))
                          (or (get-in db [:voice :pending]) (get-in db [:voice :request]) (nil? control)))
                     (and (nil? continuation) (= :action (:kind parsed)) (available? room db) (= "Game board" (:scope shown))))
          result (if local? parsed (or control parsed))
          smart? (and (get-in db [:voice :assistant :connected?]) (not local?)
                      (get-in db [:voice :open?]) (= captured (stamp room db)))]
      (if smart?
        (if (get-in db [:voice :request])
          {:db (assoc-in db [:voice :message] "Wait for ChatGPT or cancel the request first.")}
          (let [request {:id (str (random-uuid)) :version captured :text text
                         :context (request-context db room shown (:progress continuation []))}
                saved (merge (select-keys request [:id :version :text])
                             {:surface (:version shown) :actions (:actions shown) :steps (:steps continuation 0)
                              :progress (:progress continuation [])})]
            {:db (-> db (assoc-in [:voice :request] saved) (assoc-in [:voice :chain] nil)
                     (assoc-in [:voice :heard] text) (assoc-in [:voice :pending] nil)
                     (assoc-in [:voice :message] "Using your ChatGPT account…"))
             :assistant/request request}))
        (cond-> (resolve-heard db captured text result)
          (and (get-in db [:voice :request]) (#{:cancel :confirm} (:kind result))) (assoc :assistant/cancel true))))))
(rf/reg-event-fx :voice/assistant-result
  (fn [{:keys [db]} [_ packet]]
    (let [request (get-in db [:voice :request]) room (model/pull-game (:facts db) (:game-id db))
          value (:result packet)
          action (some #(when (= (:space %) (:space value)) %) (:actions request))
          message (when (string? (:message value)) (subs (:message value) 0 (min 1000 (count (:message value)))))
          result (cond
                   (and (= "action" (:kind value)) action message)
                   (merge action {:kind :control :surface (:surface request) :message message
                                  :confidence (if (= "high" (:confidence value)) "high" "low")
                                  :continue (true? (:continue value)) :value (:value value) :intent (:text request)
                                  :steps (:steps request) :progress (:progress request)})
                   (and (= "answer" (:kind value)) message) {:kind :answer :message message}
                   :else {:kind :answer :message "ChatGPT returned an unavailable action. Please try again."})]
      (if (and request (= (:id packet) (:id request)) (= (:version packet) (:version request)))
        (let [next (resolve-heard db (:version request) (:text request) result)]
          (if (= (:version request) (stamp room db))
            (update-in next [:db :voice :history] #(vec (take-last 6 (concat % [{:role "user" :text (:text request)}
                                                                            {:role "assistant" :text (:message result)}]))))
            next)) {}))))

(defn pair! [version]
  (close-relay!)
  (let [pair (comms/fresh-token)
        socket (js/WebSocket. (str (if (= "https:" (.-protocol js/location)) "wss://" "ws://") (.-host js/location) "/voice"))]
    (reset! relay socket)
    (set! (.-onopen socket)
          #(.send socket (js/JSON.stringify (clj->js {:op "listen" :pair pair :token (comms/session-token)
                                                     :auth (.-EUROZONE_DISCORD_AUTH js/window)}))))
    (set! (.-onmessage socket)
          (fn [event]
            (when (identical? socket @relay)
              (let [message (js->clj (js/JSON.parse (.-data event)) :keywordize-keys true)]
                (case (:op message)
                  "ready" (let [url (js/URL. "/voice.html" (or (.-EUROZONE_PUBLIC_URL js/window) (.-origin js/location)))]
                            (set! (.-hash url) pair)
                            (if-let [open (aget js/window "EUROZONE_OPEN_VOICE")]
                              (-> (open (.toString url)) (.catch #(rf/dispatch [:voice/message "Could not open the browser. Try again."])))
                              (rf/dispatch [:voice/message "Use Speak in this browser."])))
                  "paired" (send-status! (or @feedback {:version version :message "Voice paired. Clear commands run immediately."}))
                  "heard" (rf/dispatch [:voice/heard (:version message) (:text message)])
                  ("closed" "error") (rf/dispatch [:voice/message (:message message)])
                  nil)))))
    (set! (.-onclose socket) #(when (identical? socket @relay) (reset! relay nil) (rf/dispatch [:voice/message "Voice companion disconnected. Open a fresh link."])))
    (set! (.-onerror socket) #(rf/dispatch [:voice/message "Voice relay unavailable. Check the server connection."]))))

(defn controls [room]
  (r/with-let [listening? (r/atom false) continuous? (r/atom false) mic-status (r/atom "Microphone off.") typed (r/atom "") last-status (atom nil)]
    (let [ui @(rf/subscribe [:ui]) state (:voice ui) version (stamp room ui)
          permitted? (seq (get-in ui [:permissions (:id room) :seats]))
          pending (:pending state) fresh? (= version (:version pending))
          message (if (and pending (not fresh?)) "The table changed. Say the command again." (:message state))
          status {:version version :message (or message "Voice ready.")}
          continuous! (fn []
                        (reset! continuous? true)
                        (speech "startContinuous"
                          (fn [text captured] (rf/dispatch [:voice/heard captured text]))
                          #(rf/dispatch [:voice/message %])
                          #(do (reset! continuous? false) (reset! mic-status "Microphone off."))
                          #(reset! mic-status %)
                          (fn [] (let [current @rfdb/app-db]
                                   (stamp (model/pull-game (:facts current) (:game-id current)) current)))))
          start! (fn []
                   (reset! listening? true)
                   (rf/dispatch [:voice/message "Listening…"])
                   (let [captured (stamp room ui)]
                     (speech "start" #(rf/dispatch [:voice/heard captured %])
                           #(rf/dispatch [:voice/message %]) #(reset! listening? false)
                           #(rf/dispatch [:voice/message %]))))]
      (when (and @continuous? (or (not (:connected? ui)) (not permitted?)))
        (r/after-render #(speech "stop")))
      (when (and (:open? state) (not= status @last-status))
        (reset! last-status status)
        (r/after-render #(send-status! (assoc status :version (stamp room ui)))))
      (when permitted?
        [:div.voice-controls
         [:button {:type "button" :aria-expanded (and (boolean (:open? state)) (not (:minimized? state)))
                   :on-click #(if (:open? state) (rf/dispatch [:voice/minimize (not (:minimized? state))])
                                  (rf/dispatch [:voice/open true]))} "Voice commands"]
         (when @continuous?
           [:button {:type "button" :class "voice-stop" :on-click #(speech "stop")} "Stop listening"])
         (when (and (:open? state) (not (:minimized? state)))
           [:section.voice-panel {:aria-label "Voice commands"}
            [:div.voice-heading [:strong "Voice commands"]
             [:button {:type "button" :aria-label "Minimize voice commands" :on-click #(rf/dispatch [:voice/minimize true])} "−"]
             [:button {:type "button" :aria-label "Close voice commands"
                       :on-click #(do (speech "stop") (close-relay!) (assistant/close!) (rf/dispatch [:voice/open false]))} "×"]]
            [:div.voice-assistant
             [:p.small (or (get-in state [:assistant :message]) "Connect your own ChatGPT plan for natural-language commands and questions.")]
             (if (get-in state [:assistant :connected?])
               [:button {:type "button" :on-click #(do (assistant/close!)
                                                      (rf/dispatch [:voice/assistant-state {:message "ChatGPT disconnected."}]))} "Disconnect ChatGPT"]
               [:button {:type "button" :on-click assistant/connect!} "Connect my ChatGPT"])
             (when-let [link (get-in state [:assistant :link])]
               [:div
                [:p.small "Run the local companion on your computer, then open it below and choose your account and model."]
                (let [url (str "http://127.0.0.1:1455/#" (js/encodeURIComponent link))]
                  (if (.-EUROZONE_ACTIVITY js/window)
                    [:button {:type "button" :on-click #(when-let [open (aget js/window "EUROZONE_OPEN_VOICE")]
                                                         (-> (open url) (.catch (fn [_] (rf/dispatch [:voice/message "Open the local companion manually and paste the pairing link."])))))}
                     "Open local ChatGPT companion"]
                    [:a {:href url :target "_blank" :rel "noopener noreferrer"} "Open local ChatGPT companion"]))
                [:label {:for "assistant-pair"} "ChatGPT pairing link"]
                [:input {:id "assistant-pair" :read-only true :value link :on-focus #(.. % -target select)}]
                [:p.small "Keep this link private. You can paste it into a companion using a different port."]])]
            [:p {:role "status"} message]
            (when (:request state)
              [:button {:type "button" :on-click #(rf/dispatch [:voice/heard (stamp room ui) "cancel"])} "Cancel ChatGPT request"])
            (when (:heard state) [:p.small (str "Heard: “" (:heard state) "”")])
            (if (.-EUROZONE_ACTIVITY js/window)
              [:button {:type "button" :on-click #(pair! version)} "Open voice companion"]
              [:div
               [:button {:type "button" :disabled (or @listening? @continuous? (not (speech "supported"))) :on-click start!} "Speak"]
               (when-not @continuous?
                 [:button {:type "button" :disabled (or @listening? (not (speech "supported"))) :on-click continuous!} "Start continuous listening"])
               [:p.small {:aria-live "polite" :data-microphone (cond @continuous? "on" @listening? "single" :else "off")}
                (cond @continuous? (str "Microphone on · " @mic-status)
                      @listening? "Single command in progress."
                      :else "Microphone off.")]
               (when @continuous? [:p.small "Speak one command, then pause. Say “stop listening” to turn off the microphone. All speech heard in this mode is treated as input."])
               (when @listening? [:div
                                  [:button {:type "button" :on-click #(speech "finish")} "Finish recording"]
                                  [:button {:type "button" :on-click #(do (speech "stop")
                                                                                         (rf/dispatch [:voice/message "Recording cancelled."]))} "Cancel recording"]])
               (when-not (speech "supported") [:p.small "Speech unavailable here. Try Chrome or Edge, or type below."])])
            [:form {:on-submit #(do (.preventDefault %) (rf/dispatch [:voice/heard (stamp room ui) @typed]) (reset! typed ""))}
             [:label {:for "voice-transcript"} "Or type a command"]
             [:input {:id "voice-transcript" :value @typed :max-length 300 :auto-complete "off" :placeholder "take wood"
                      :on-change #(reset! typed (.. % -target -value))}]
             [:button {:type "submit" :disabled (empty? @typed)} "Use command"]]
            (when (and pending fresh?)
              [:div [:button {:type "button" :disabled (or (:busy? ui) (not (:connected? ui))) :on-click #(rf/dispatch [:voice/heard (stamp room ui) "confirm"])}
                     (str "Confirm " (:name pending))]
               [:button {:type "button" :on-click #(rf/dispatch [:voice/heard (stamp room ui) "cancel"])} "Cancel"]])
            [:p.small (if (get-in state [:assistant :connected?])
                        "Clear commands run immediately. Ask for a farm, played cards, or choices in the open dialog. Uncertain requests need clarification."
                        "Say “take wood” or “fishing” to act immediately. Connect ChatGPT for other game controls and view commands.")]
            [:p.small "ChatGPT receives the current controls, visible dialog/card text and recent conversation. Multi-step requests can use several model calls. Minimize this panel to keep more of the board visible."]])]))
    (finally (speech "stop") (close-relay!) (assistant/close!) (rf/dispatch [:voice/open false]))))
