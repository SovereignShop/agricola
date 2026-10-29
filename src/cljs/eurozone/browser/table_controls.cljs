(ns eurozone.browser.table-controls
  (:require [reagent.core :as r] [re-frame.core :as rf] [clojure.string :as str]
            [eurozone.table-flow :as flow] [agricola.card-game :as game]
            [agricola.card-rules :as cards] [agricola.engine :as a] [eurozone.browser.art :as art]
            [eurozone.browser.layout :as layout]))

(defonce wall-time (r/atom (.now js/Date)))
(defonce tick (js/setInterval #(reset! wall-time (.now js/Date)) 250))
(defn send! [message] (rf/dispatch [:send message]))
(defn seconds-left [clock ui]
  (max 0 (js/Math.ceil (/ (- (:deadline clock) (+ @wall-time (:server-offset ui 0))) 1000))))
(defn time-text [seconds]
  (str (quot seconds 60) ":" (when (< (mod seconds 60) 10) "0") (mod seconds 60)))
(defn limit-select [seconds change disabled?]
  [:label.form-field [:span "Turn time limit"]
   [:select {:value seconds :disabled disabled? :on-change #(change (js/parseInt (.. % -target -value)))}
    (for [n flow/limits] ^{:key n} [:option {:value n} (if (zero? n) "No time limit" (str n " seconds"))])]
   [:small "Each required decision has its own clock. At zero, a bot makes that decision."]])
(defn turn-banner [room]
  (let [ui @(rf/subscribe [:ui]) permission @(rf/subscribe [:permission]) s (:state room)
        actors (flow/actors room) mine (first (filter (set (:seats permission)) actors))
        pid (or mine (first actors)) clock (get-in room [:clocks pid])
        seconds (when clock (seconds-left clock ui)) paused? (seq (:takeback room))
        urgent? (and clock (not paused?) (<= seconds 10))
        name (get-in room [:seats pid :name])
        task (cond (seq (:effect-queue s)) (str "Resolve " (or (:name (cards/card (get-in s [:effect-queue 0 :card]))) "the card decision"))
                   :else (case (:phase s) :draw "Choose a card" :work "Place a family member"
                           :feeding "Feed your family" :breeding "Confirm your offspring" :housing "House your animals" ""))]
    (when (some? pid)
      [:section.turn-banner {:class (str (when (some? mine) "your-turn ") (when urgent? "clock-urgent ") (when paused? "paused"))
                             :aria-label "Turn status" :data-your-turn (boolean (some? mine))}
       [:img.turn-piece {:src (art/worker-url pid) :alt ""}]
       [:div.turn-message {:role "status" :aria-live "polite"}
        [:strong (cond paused? "Takeback vote · play paused" (some? mine) (str "Your turn · " name) :else (str name "’s turn"))]
        [:span (if paused? "The clock resumes when the vote ends." task)]]
       (when (and (some? mine) (not= :draw (:phase s)))
         [:button.text-button {:on-click #(do (rf/dispatch [:inspect-player mine]) (rf/dispatch [:panel :board])
                                              (when-not (some #{"farm"} (:order (layout/layout ui (:id room))))
                                                (rf/dispatch [:layout/change (:id room) :toggle "farm"]))
                                              (js/setTimeout (fn [] (when-let [node (.querySelector js/document "[data-tile='farm']")]
                                                                     (.scrollIntoView node #js {:block "nearest" :behavior "smooth"}))) 50))} "Go to my decision"])
       (when clock
         [:div.turn-countdown {:role "timer" :aria-label "Time remaining" :data-seconds seconds :class (when urgent? "urgent")}
          [:strong (if paused? "Paused" (time-text seconds))]
          [:small (cond paused? "Vote in progress" (zero? seconds) "Bot is choosing…" urgent? "Bot moves at zero" :else "remaining")]])])))

(defn modal [title children & [close]]
  (r/with-let [previous (.-activeElement js/document)
               handler (fn [e]
                         (when (= "Escape" (.-key e)) (when close (close)))
                         (when (= "Tab" (.-key e))
                           (when-let [dialog (.querySelector js/document ".table-dialog")]
                             (let [nodes (.querySelectorAll dialog "button:not([disabled]),select:not([disabled]),input:not([disabled]),[tabindex='0']")
                                   first (aget nodes 0) last (aget nodes (dec (.-length nodes)))]
                               (when (and first (or (and (.-shiftKey e) (= (.-activeElement js/document) first))
                                                   (and (not (.-shiftKey e)) (= (.-activeElement js/document) last))))
                                 (.preventDefault e) (.focus (if (.-shiftKey e) last first)))))))
               _ (.addEventListener js/document "keydown" handler)]
    [:div.dialog-backdrop
     [:section.table-dialog {:role "dialog" :aria-modal true :aria-label title :tab-index -1
                             :ref #(when (and % (not (.contains % (.-activeElement js/document)))) (.focus %))}
      [:div.section-heading [:h2 title] (when close [:button.text-button {:aria-label "Close dialog" :on-click close} "×"])]
      children]]
    (finally (.removeEventListener js/document "keydown" handler)
             (when (and previous (.-isConnected previous)) (.focus previous)))))

(defn takeback [room]
  (r/with-let [request? (r/atom false) dismissed (r/atom (.getItem js/sessionStorage (str "eurozone-vote-dismissed:" (:id room))))]
    (let [ui @(rf/subscribe [:ui]) permission @(rf/subscribe [:permission]) owned (set (:seats permission))
          v (:takeback room) result (:takeback-result room) pending? (seq v)
          pid (or (when (owned (:last-move-player room)) (:last-move-player room)) (first (filter (set (:takeback-players room)) (:seats permission))))
          waiting (filter #(and (owned %) (not (contains? (:yes v) %)) (not (contains? (:no v) %))) (:voters v))
          dismiss! #(do (reset! dismissed (:id result)) (.setItem js/sessionStorage (str "eurozone-vote-dismissed:" (:id room)) (:id result)))]
      [:div.takeback-controls
       (when (seq owned)
         [:button.text-button {:disabled (or (:busy? ui) (not (:can-takeback? room)) (nil? pid) pending?)
                               :on-click #(reset! request? true)} "Request takeback"])
       (cond
         @request?
         [modal "Request a takeback"
          [:div [:p (str "Rewind to " (get-in room [:seats pid :name]) "’s last turn?")]
           [:p.small "Any decisions made since your turn, including bot moves, will also be undone. A majority of human players must approve; your request counts as approval. Play and clocks pause while people vote."]
           (when (= :draw (get-in room [:state :phase])) [:p.small "Cards already seen cannot be unseen."])
           [:div.dialog-actions
            [:button.button {:on-click #(reset! request? false)} "Cancel"]
            [:button.button.primary {:disabled (:busy? ui) :on-click #(do (reset! request? false) (reset! dismissed nil)
                                                                          (send! {:event :request-takeback :player pid}))} "Ask for takeback"]]]
          #(reset! request? false)]
         (and pending? (seq owned))
         [modal "Takeback vote"
          [:div [:p (str (get-in room [:seats (:requester v) :name]) " requests a rewind to their turn in round " (:round v) ". " (:steps v 1) " decision(s) will be undone, including intervening moves.")]
           [:p.vote-count {:role "status"} (str (count (:yes v)) " / " (:needed v) " approvals needed")]
           [:ul.vote-roster (for [p (:voters v)] ^{:key p}
                              [:li [:span (get-in room [:seats p :name])]
                               [:strong (cond (contains? (:yes v) p) "Approved" (contains? (:no v) p) "Declined" :else "Waiting")]])]
           (for [p waiting] ^{:key p}
             [:div.dialog-actions [:span (get-in room [:seats p :name])]
              [:button.button {:disabled (:busy? ui) :on-click #(send! {:event :takeback-vote :vote-id (:id v) :player p :approve? false})} "Decline"]
              [:button.button.primary {:disabled (:busy? ui) :on-click #(send! {:event :takeback-vote :vote-id (:id v) :player p :approve? true})} "Approve takeback"]])
           [:p.small (str "Vote closes in " (seconds-left {:deadline (:expires-at v)} ui) " seconds. Bots do not vote.")]
           (when (owned (:requester v))
             [:button.text-button {:disabled (:busy? ui) :on-click #(send! {:event :cancel-takeback :vote-id (:id v)})} "Cancel request"])]]
         (and (seq result) (seq owned) (not= (:id result) @dismissed)
              (< (- (+ @wall-time (:server-offset ui 0)) (:at result 0)) 120000))
         [modal (case (:status result) :approved "Takeback approved" :declined "Takeback declined" :expired "Takeback vote expired" "Takeback cancelled")
          [:div [:p (if (= :approved (:status result)) "The requesting player’s turn has been restored. Later decisions and queued harvest plans have been cleared." "The game continues from the same position.")]
           [:button.button.primary {:on-click dismiss!} "Continue"]]
          dismiss!])])) )

(def resource-names {:food "Food" :grain "Grain" :vegetable "Vegetables" :sheep "Sheep" :boar "Wild boar" :cattle "Cattle"})
(defn quantity [label n max-value update]
  [:label.form-field [:span label]
   [:input {:type "number" :min 0 :max max-value :value n
            :on-change #(update (max 0 (min max-value (or (js/parseInt (.. % -target -value)) 0))))}]])
(defn harvest-planner [room pid]
  (r/with-let [convert (r/atom {}) craft (r/atom []) offspring (r/atom nil) queue-breed? (r/atom true)]
    (let [ui @(rf/subscribe [:ui]) permission @(rf/subscribe [:permission]) s (:state room) p (get-in s [:players pid])
          eligible? (and (some #{pid} (:seats permission)) (#{:feeding :breeding} (:phase s)))
          fed? (or (= :breeding (:phase s)) (contains? (set (:fed s)) pid))
          bred? (contains? (set (:bred s)) pid) queued (get-in ui [:private-plans (:id room) pid])
          feed {:type :feed :convert @convert :craft @craft}
          preview (try {:player (if (or fed? (not eligible?)) p (get-in (flow/feed-preview s pid feed) [:players pid]))}
                       (catch :default e {:error (.-message e)}))
          after (:player preview) options (when after (a/breeding-options after))
          babies (or @offspring (when after (if (a/animal-housing (a/gain after options)) options {})))
          disabled? (or (:busy? ui) (not (:connected? ui)) (seq (:takeback room)))]
      (when (and eligible? (not bred?))
        [:section.panel.harvest-planner {:aria-label (str (:name p) " harvest plan")}
         [:h3 "Harvest plan"]
         [:p.small "Queue your choices now. They will run in harvest order; card effects may require a fresh decision."]
         (when (:error queued) [:p.warning {:role "alert"} (:error queued)])
         (if (or (:feed queued) (:breed queued))
           [:div [:p.queued-label {:role "status"} (str "Queued: " (str/join " and " (keep #(when (get queued (first %)) (second %)) [[:feed "feeding"] [:breed "breeding"]])))]
            [:button.button {:disabled disabled? :on-click #(send! {:event :cancel-harvest-plan :player pid :round (:round s)})} "Cancel queued plan"]]
           [:div
            (when-not fed?
              [:div [:h4 "Feeding"] [:p (str "Food required: " (a/food-needed s p) " · stored: " (a/amount p :food))]
               [:div.conversion-grid
                (for [resource (concat [:grain :vegetable] a/animals)
                      :let [n (a/amount p resource) rate (get (a/cook-rates p) resource)] :when (and rate (pos? n))] ^{:key resource}
                  [quantity (str (resource-names resource) " to cook · " rate " food each") (get @convert resource 0) n #(swap! convert assoc resource %)])]
               (for [id (:improvements p) :when (:craft (a/improvements id))] ^{:key id}
                 [:label.checkbox [:input {:type "checkbox" :checked (boolean (some #{id} @craft))
                                           :on-change #(swap! craft (fn [xs] (if (some #{id} xs) (vec (remove #{id} xs)) (conj xs id))))}]
                  (str "Use " (:name (a/improvements id)))])
               (when after [:p.feeding-total (let [marks (- (:begging after) (:begging p))]
                                             (if (pos? marks) (str marks " begging marks (−" (* 3 marks) " points)") "Enough food for everyone"))])])
            (when-not fed? [:label.checkbox [:input {:type "checkbox" :checked @queue-breed? :on-change #(reset! queue-breed? (.. % -target -checked))}] "Also queue breeding after feeding"])
            (when (or fed? @queue-breed?)
              [:div [:h4 "Breeding"]
               (if (seq options)
                 (for [[animal n] options] ^{:key animal}
                   [quantity (str "New " (str/lower-case (resource-names animal))) (get babies animal 0) n #(reset! offspring (assoc babies animal %))])
                 [:p.small "No pairs after feeding. Breeding can be completed automatically."])])
            (when (:error preview) [:p.warning (:error preview)])
            [:button.button.primary {:disabled (or disabled? (some? (:error preview)))
                                     :on-click #(send! (cond-> {:event :harvest-plan :player pid :round (:round s)}
                                                        (not fed?) (assoc :feed feed)
                                                        (or fed? @queue-breed?) (assoc :breed {:type :breed :offspring babies})))}
             (if fed? "Queue breeding" "Queue harvest decisions")]])]))))
