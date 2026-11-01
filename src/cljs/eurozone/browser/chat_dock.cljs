(ns eurozone.browser.chat-dock
  (:require [reagent.core :as r] [re-frame.core :as rf]
            [eurozone.browser.chat :as chat]))

(defn dock [room panel]
  (r/with-let [element (atom nil)
               current (atom nil)
               outside (fn [event]
                         (let [{:keys [room options]} @current]
                           (when (and @element (not (:pinned? options))
                                      (not= :collapsed (:mode options))
                                      (not (.contains @element (.-target event))))
                             (rf/dispatch [:chat/view room {:mode :collapsed}]))))
               _ (.addEventListener js/document "pointerdown" outside)]
    (let [ui @(rf/subscribe [:ui]) id (:id room)
          options (get-in ui [:chat-options id] chat/default-options)
          {:keys [mode pinned? corner]} options
          unread (get-in ui [:chat-unread id] 0)
          change! #(rf/dispatch [:chat/view id %])
          control (fn [label text action & [pressed]]
                    [:button {:type "button" :title label :aria-label label
                              :aria-pressed pressed :on-click action} text])]
      (reset! current {:room id :options options})
      [:section.chat-dock {:class (str (name mode) " corner-" (name corner))
                           :ref #(reset! element %) :aria-label "Table conversation"}
       (if (= :collapsed mode)
         [:button.chat-launcher {:type "button" :aria-label "Open table chat" :aria-expanded false
                                 :on-click #(change! {:mode :open})}
          "Chat" (when (pos? unread) [:span.chat-unread {:aria-label (str unread " unread messages")} unread])]
         [:div.chat-dock-surface
          [:div.chat-dock-toolbar
           [:strong "Table chat"]
           [:div.chat-dock-controls
            [control (if pinned? "Unpin chat" "Pin chat") (if pinned? "Pinned" "Pin") #(change! {:pinned? (not pinned?)}) pinned?]
            [control (if (= corner :right) "Move chat to left corner" "Move chat to right corner")
             (if (= corner :right) "←" "→") #(change! {:corner (if (= corner :right) :left :right)})]
            [control (if (= mode :expanded) "Restore chat size" "Expand chat")
             (if (= mode :expanded) "↙" "↗") #(change! {:mode (if (= mode :expanded) :open :expanded)})]
            [control "Collapse chat" "−" #(change! {:mode :collapsed})]]]
          [panel room]])])
    (finally (.removeEventListener js/document "pointerdown" outside))))
