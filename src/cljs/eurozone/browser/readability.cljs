(ns eurozone.browser.readability
  (:require [re-frame.core :as rf]))

(def storage-key "eurozone-text-size-v1")
(defn normalize [value]
  (if (and (number? value) (js/isFinite value) (<= 80 value 200))
    (* 10 (js/Math.round (/ value 10))) 100))
(defn load-size []
  (try (normalize (js/Number (.getItem js/localStorage storage-key)))
       (catch :default _ 100)))
(defn apply-size! [size]
  (.setProperty (.-style (.-documentElement js/document)) "--text-scale" (str (/ size 100)))
  (try (.setItem js/localStorage storage-key (str size)) (catch :default _ nil)))
(rf/reg-fx :text-size/apply apply-size!)
(rf/reg-event-fx :text-size/change
  (fn [{:keys [db]} [_ operation]]
    (let [current (get db :text-size 100)
          size (case operation :larger (min 200 (+ current 10)) :smaller (max 80 (- current 10)) 100)]
      {:db (assoc db :text-size size) :text-size/apply size})))
(defn shortcut! [event]
  (when (and (or (.-ctrlKey event) (.-metaKey event)) (not (.-altKey event)))
    (when-let [operation (case (.-key event) ("+" "=") :larger "-" :smaller "0" :reset nil)]
      (.preventDefault event)
      (rf/dispatch [:text-size/change operation]))))
(defonce listening? (atom false))
(defn install! []
  (apply-size! (load-size))
  (when (compare-and-set! listening? false true)
    (.addEventListener js/window "keydown" shortcut!)))
(defn controls []
  (let [size (:text-size @(rf/subscribe [:ui]) 100)]
    [:div.text-size-controls {:role "group" :aria-label "Text size"}
     [:span "Text"]
     [:button {:type "button" :aria-label "Decrease text size" :title "Smaller text (Ctrl− / ⌘−)"
               :disabled (<= size 80) :on-click #(rf/dispatch [:text-size/change :smaller])} "−"]
     [:button.text-size-reset {:type "button" :aria-label "Reset text size" :title "Reset text size (Ctrl0 / ⌘0)"
                               :on-click #(rf/dispatch [:text-size/change :reset])}
      [:span {:aria-live "polite" :aria-atomic true} (str size "%")]]
     [:button {:type "button" :aria-label "Increase text size" :title "Larger text (Ctrl+ / ⌘+)"
               :disabled (>= size 200) :on-click #(rf/dispatch [:text-size/change :larger])} "+"]]))
