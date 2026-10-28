(ns eurozone.browser.layout
  (:require [re-frame.core :as rf] [reagent.core :as r] [cljs.reader :as reader] [clojure.string :as str]
            [eurozone.table-layout :as model]))
(def storage-key "eurozone-layouts-v1")
(defn seat-storage-key [] (str storage-key ":" (.getItem js/sessionStorage "eurozone-session")))
(defn load-layouts []
  (try (let [v (some-> (.getItem js/localStorage (seat-storage-key)) reader/read-string)] (if (map? v) v {}))
       (catch :default _ {})))
(defn layout [ui room] (model/normalize (get-in ui [:layouts room])))
(rf/reg-fx :layout/save #(try (.setItem js/localStorage (seat-storage-key) (pr-str %)) (catch :default _ nil)))
(rf/reg-event-db :layout/edit (fn [db _] (update db :arranging? not)))
(rf/reg-event-db :layout/maximize (fn [db [_ room id]] (update db :maximized-panel #(when (and id (not= % [room id])) [room id]))))
(rf/reg-event-fx :layout/change
  (fn [{:keys [db]} [_ room operation & args]]
    (let [changed (model/change (layout db room) operation args)
          layouts (assoc (:layouts db) room changed)]
      {:db (cond-> (assoc db :layouts layouts)
             (or (#{:preset :reset} operation)
                 (not (some #{(second (:maximized-panel db))} (:order changed)))) (dissoc :maximized-panel))
       :layout/save layouts})))
(defn change! [room & command] (rf/dispatch-sync (into [:layout/change room] command)))
(defn maximize! [room id]
  (rf/dispatch-sync [:layout/maximize room id])
  (js/requestAnimationFrame #(js/requestAnimationFrame
                             (fn [] (when-let [node (.querySelector js/document ".table-layout.has-maximized")]
                                      (.scrollIntoView node #js {:block "start"}))))))
(defn pointer-ref [handlers]
  ;; Native events also support touch/pen with the inherited React version.
  (fn [node] (when node (doseq [[event handler] handlers] (aset node event handler)))))
(defn capture! [e] (.preventDefault e) (.setPointerCapture (.-currentTarget e) (.-pointerId e)))
(defn edge-at [rect x y]
  (let [x (/ (- x (.-left rect)) (.-width rect)) y (/ (- y (.-top rect)) (.-height rect))]
    (cond (< y 0.22) :top (> y 0.78) :bottom (< x 0.5) :left :else :right)))
(def edge-label {:left "Place to the left" :right "Place to the right" :top "New row above" :bottom "New row below"})
(defn drag-ref [room id title gesture]
  (pointer-ref
   {"onpointerdown" (fn [e]
                       (when (= 0 (.-button e))
                         (capture! e)
                         (reset! gesture {:kind :dock :id id :title title :start-x (.-clientX e) :start-y (.-clientY e)})))
    "onpointermove" (fn [e]
                       (when-let [start (when (= :dock (:kind @gesture)) @gesture)]
                         (let [x (.-clientX e) y (.-clientY e)
                               active? (or (:active? start) (> (+ (js/Math.abs (- x (:start-x start))) (js/Math.abs (- y (:start-y start)))) 7))
                               node (.elementFromPoint js/document x y)
                               target (when node (.closest node ".table-tile"))
                               target-id (when target (.getAttribute target "data-tile"))
                               valid? (and target (not= id target-id))]
                           (reset! gesture (assoc start :active? active? :x x :y y :target (when valid? target-id)
                                                  :edge (when valid? (edge-at (.getBoundingClientRect target) x y))))
                           (when active?
                             (cond (< y 70) (.scrollBy js/window 0 -22)
                                   (> y (- (.-innerHeight js/window) 60)) (.scrollBy js/window 0 22))))))
    "onpointerup" (fn [_]
                     (let [{:keys [active? target edge]} @gesture]
                       (reset! gesture nil)
                       (when (and active? target edge) (change! room :dock id target edge))))
    "onpointercancel" (fn [_] (reset! gesture nil))}))
(defn controls [room panels player-count]
  (r/with-let [fullscreen? (r/atom (boolean (.-fullscreenElement js/document)))
               sync-fullscreen #(reset! fullscreen? (boolean (.-fullscreenElement js/document)))
               _ (.addEventListener js/document "fullscreenchange" sync-fullscreen)]
    (let [ui @(rf/subscribe [:ui]) current (layout ui room) visible (set (:order current))]
      [:div.layout-tools
       [:div.layout-toolbar
        [:div.layout-presets {:aria-label "Table layouts"}
         [:button {:on-click #(change! room :preset :table)} "Table view"]
         [:button {:on-click #(change! room :preset :farms player-count)} "Show all farms"]]
        [:span.layout-hint "Drag a title to a panel edge · drag dividers to resize"]
        [:div.layout-view-controls
         [:button {:aria-expanded (boolean (:arranging? ui)) :on-click #(rf/dispatch [:layout/edit])} "Panels"]
         [:button {:aria-pressed (boolean (:focus? current)) :on-click #(change! room :focus)} (if (:focus? current) "Exit focus" "Focus table")]
         (when (.-EUROZONE_ACTIVITY js/window)
           [:span.small "Fullscreen: use Discord’s controls"])
         (when (and (.-fullscreenEnabled js/document) (not (.-EUROZONE_ACTIVITY js/window)))
           [:button {:on-click #(let [result (if @fullscreen? (.exitFullscreen js/document) (.requestFullscreen (.-documentElement js/document)))]
                                  (.catch result (fn [_] (rf/dispatch [:error "Fullscreen is unavailable in this browser. Focus table still works."]))))}
            (if @fullscreen? "Exit fullscreen" "Fullscreen")])]]
       (when (:arranging? ui)
         [:div.layout-options
          [:p.small "Show or hide panels here. Use a panel’s controls to set its width or height. Alt + arrow keys on a title move it; Escape cancels a drag or restores a maximized panel."]
          [:div.layout-toggles
           (for [{:keys [id title]} panels] ^{:key id}
             [:label.checkbox [:input {:type "checkbox" :checked (boolean (visible id)) :on-change #(change! room :toggle id)}] title])]
          [:button.text-button {:on-click #(change! room :reset)} "Reset layout"]])])
    (finally (.removeEventListener js/document "fullscreenchange" sync-fullscreen))))
(defn divider [room left right gesture]
  (let [ui @(rf/subscribe [:ui]) current (layout ui room) w (model/weight current left)]
    [:div.tile-divider
     {:role "separator" :tab-index 0 :aria-orientation "vertical" :aria-label (str "Resize panels " left " and " right)
      :aria-valuenow (js/Math.round (* 100 (/ w (+ w (model/weight current right))))) :aria-valuemin 0 :aria-valuemax 100
      :on-key-down #(when (#{"ArrowLeft" "ArrowRight"} (.-key %))
                      (.preventDefault %) (change! room :pair left right (+ w (if (= "ArrowLeft" (.-key %)) -0.25 0.25))))
      :ref (pointer-ref
            {"onpointerdown" (fn [e]
                               (capture! e)
                               (reset! gesture {:kind :divider :left left :right right :start-x (.-clientX e) :start-width w
                                                :row-width (.-width (.getBoundingClientRect (.-parentElement (.-currentTarget e))))}))
             "onpointermove" (fn [e]
                               (when-let [start (when (= :divider (:kind @gesture)) @gesture)]
                                 (let [total (+ w (model/weight current right))
                                       width (model/clamp (+ (:start-width start) (* 12 (/ (- (.-clientX e) (:start-x start)) (:row-width start))))
                                                          (min 1 (/ total 3)) (- total (min 1 (/ total 3))))]
                                   (swap! gesture assoc :width width))))
             "onpointerup" (fn [_] (let [{:keys [width left right]} @gesture] (reset! gesture nil)
                                      (when width (change! room :pair left right width))))
             "onpointercancel" (fn [_] (reset! gesture nil))})}]))
(defn corner [room id width gesture]
  [:button.tile-resizer
   {:aria-label (str "Resize " id) :title "Drag to resize this panel; double-click to fit its height"
    :on-double-click #(change! room :auto-height id)
    :ref (pointer-ref
          {"onpointerdown" (fn [e]
                             (capture! e)
                             (let [tile (.closest (.-currentTarget e) ".table-tile") rect (.getBoundingClientRect tile)]
                               (reset! gesture {:kind :corner :id id :start-x (.-clientX e) :start-y (.-clientY e)
                                                :start-width width :start-height (.-height rect)
                                                :row-width (.-width (.getBoundingClientRect (.-parentElement tile)))})))
           "onpointermove" (fn [e]
                             (when-let [start (when (= :corner (:kind @gesture)) @gesture)]
                               (swap! gesture assoc :width (model/clamp (+ (:start-width start) (* 12 (/ (- (.-clientX e) (:start-x start)) (:row-width start)))) 1 12)
                                      :height (model/clamp (+ (:start-height start) (- (.-clientY e) (:start-y start))) 180 1800))))
           "onpointerup" (fn [_] (let [{:keys [id width height]} @gesture] (reset! gesture nil)
                                    (when width (change! room :resize id width height))))
           "onpointercancel" (fn [_] (reset! gesture nil))})} "◢"])
(defn fitted-board [content]
  [:div.board-fit content])
(defn tile [room {:keys [id title content]} index total gesture]
  (let [ui @(rf/subscribe [:ui]) current (layout ui room)
        width (model/weight current id) height (get-in current [:heights id])
        height (if (and (= :corner (:kind @gesture)) (= id (:id @gesture))) (or (:height @gesture) height) height)
        maximized? (= [room id] (:maximized-panel ui))
        target? (and (:active? @gesture) (= id (:target @gesture)))]
    [:section.table-tile {:data-tile id :aria-label (str title " panel")
                          :class (str (when target? "drop-target ") (when maximized? "maximized"))
                          :style {:height (when height (if (= :auto height) "auto" (str height "px")))}}
     [:div.tile-header
      [:button.tile-grip {:title "Drag to dock · double-click to maximize · Alt + arrows to move"
                         :aria-label (str "Move " title) :ref (drag-ref room id title gesture)
                         :on-double-click #(maximize! room id)
                         :on-key-down #(when (and (.-altKey %) (#{"ArrowLeft" "ArrowRight" "ArrowUp" "ArrowDown"} (.-key %)))
                                         (.preventDefault %) (change! room :move id (if (#{"ArrowLeft" "ArrowUp"} (.-key %)) -1 1)))}
       [:span.grip-icon {:aria-hidden true} "⠿"] [:strong title]]
      [:div.tile-actions
       [:button {:aria-label (str (if maximized? "Restore " "Maximize ") title) :title (if maximized? "Restore panel" "Maximize panel") :on-click #(maximize! room id)} (if maximized? "▣" "⛶")]
       [:button {:aria-label (str "Hide " title) :title "Hide panel" :on-click #(do (change! room :toggle id) (when maximized? (maximize! room id)))} "×"]]]
     (when (:arranging? ui)
       [:div.tile-settings
        [:button {:aria-label (str "Move " title " earlier") :disabled (zero? index) :on-click #(change! room :move id -1)} "←"]
        [:button {:aria-label (str "Move " title " later") :disabled (= index (dec total)) :on-click #(change! room :move id 1)} "→"]
        [:select {:aria-label (str title " panel width") :value (js/Math.round width) :on-change #(change! room :resize id (js/parseInt (.. % -target -value)))}
         (for [n (range 1 13)] ^{:key n} [:option {:value n} (str (js/Math.round (* n (/ 100 12)))) "%"]) ]
        [:button {:on-click #(change! room :full-row id)} "Full row"]
        [:button {:aria-label (str "Fit " title " height to content") :on-click #(change! room :auto-height id)} "Fit height"]])
     [:div.tile-content (if (= id "board") [fitted-board content] content)]
     (when-not maximized? [corner room id width gesture])
     (when target?
       [:div.dock-preview {:class (name (:edge @gesture)) :aria-hidden true} [:span (edge-label (:edge @gesture))]])]))
(defn table-layout [room panels player-count]
  (r/with-let [gesture (r/atom nil)
               cancel (fn [_] (reset! gesture nil))
               on-key (fn [e] (when (= "Escape" (.-key e))
                                (if @gesture (cancel e) (rf/dispatch-sync [:layout/maximize nil nil]))))
               _ (.addEventListener js/document "keydown" on-key)
               _blur (.addEventListener js/window "blur" cancel)]
    (let [ui @(rf/subscribe [:ui]) base (layout ui room)
          current (case (:kind @gesture)
                    :divider (if (:width @gesture) (model/resize-pair base (:left @gesture) (:right @gesture) (:width @gesture)) base)
                    :corner (if (:width @gesture) (model/resize-panel base (:id @gesture) (:width @gesture) nil) base)
                    base)
          by-id (into {} (map (juxt :id identity) panels))
          maximized (when (= room (first (:maximized-panel ui))) (second (:maximized-panel ui)))
          rows (vec (filter seq (mapv #(vec (filter by-id %)) (:rows current))))
          order (vec (mapcat identity rows))]
      [:div.table-layout {:class (str (when (:arranging? ui) "arranging ") (when maximized "has-maximized ") (when (:active? @gesture) "dragging"))}
       [controls room panels player-count]
       (when maximized [:div.maximize-notice [:span "Panel maximized"] [:button {:on-click #(maximize! room maximized)} "Restore layout · Esc"]])
       [:div.tile-grid
        (for [[ri row] (map-indexed vector rows)] ^{:key (str ri "-" (first row))}
          [:div.tile-row {:style {:grid-template-columns
                                 (str/join " " (interpose "10px" (map #(str "minmax(0," (model/weight current %) "fr)") row)))}}
           (for [[i id] (map-indexed vector row)] ^{:key id}
             [:<> [tile room (by-id id) (.indexOf (clj->js order) id) (count order) gesture]
              (when (< i (dec (count row))) [divider room id (nth row (inc i)) gesture])])])]
       (when (empty? order) [:p.panel "All panels are hidden. Open Panels or choose Table view to bring them back."])
       (when (:active? @gesture)
         [:div.tile-drag-label {:style {:left (str (+ 14 (:x @gesture)) "px") :top (str (+ 14 (:y @gesture)) "px")}}
          (:title @gesture) [:small "Drop on an edge · Esc to cancel"]])])
    (finally (.removeEventListener js/document "keydown" on-key) (.removeEventListener js/window "blur" cancel))))
