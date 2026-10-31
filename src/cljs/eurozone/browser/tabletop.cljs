(ns eurozone.browser.tabletop
  "A single, continuous table. Arrangements are private presentation preferences;
  the server remains the only authority for moves, hands and card decisions."
  (:require [reagent.core :as r] [re-frame.core :as rf] [cljs.reader :as reader]
            [clojure.string :as str] [agricola.engine :as a]
            [agricola.card-game :as game] [agricola.card-rules :as cards]
            [eurozone.browser.art :as art] [eurozone.browser.card-art :as card-art]
            [eurozone.browser.readability :as readability]
            [eurozone.browser.table-controls :as controls]))

(def materials [[:white "White linen"] [:tan "Natural linen"] [:checkered "Sage check"] [:leather "Cognac leather"]])
(def colors ["#b96942" "#47779b" "#b28e32" "#826891" "#4a9dba"])
(defn clamp [v lo hi] (max lo (min hi v)))
(defn px [v] (str v "px"))
(defn dispatch [& args] (rf/dispatch (vec args)))
(defn button [label click & [attrs]] [:button (merge {:type "button" :on-click click} attrs) label])
(defn storage-key []
  (str "eurozone-tabletop-v1:" (or (when-let [id (.-EUROZONE_DISCORD_USER js/window)] (str "discord:" id)) (.getItem js/sessionStorage "eurozone-session")
                                         (.getItem js/localStorage "eurozone-session") "guest")))
(defn load-arrangement []
  (try (let [v (some-> (.getItem js/localStorage (storage-key)) reader/read-string)]
         (if-not (map? v) {:arrangement-version 2}
           (if (= 2 (:arrangement-version v)) v
             (assoc (reduce (fn [prefs pid]
                              (reduce (fn [prefs deck]
                                        (let [path [pid deck :position] xy (get-in prefs path)]
                                          (if (and (vector? xy) (= 2 (count xy)) (every? number? xy))
                                            (update-in prefs path (fn [[x y]] [x (min 2400 (+ y 80))])) prefs)))
                                      prefs [:hand-occupation :hand-minor :occupations :improvements])) v (range 5))
                    :arrangement-version 2)))) (catch :default _ {:arrangement-version 2})))
(defn save-arrangement! [v] (try (.setItem js/localStorage (storage-key) (pr-str v)) (catch :default _ nil)))
(defn position [prefs path default]
  (let [v (get-in prefs (conj path :position))]
    (if (and (vector? v) (= 2 (count v)) (every? #(and (number? %) (js/isFinite %) (<= 0 % 2400)) v)) v default)))
(defn snap [value anchors]
  (let [nearest (first (sort-by #(js/Math.abs (- value %)) anchors))]
    (clamp (if (and nearest (< (js/Math.abs (- value nearest)) 14)) nearest (* 16 (js/Math.round (/ value 16)))) 0 2400)))
(defn native-ref [events]
  (fn [el] (when el (doseq [[event f] events] (aset el event f)))))

(defn move-handle [label path xy size prefs gesture zoom]
  (let [finish (fn [cancel?]
                 (when-let [drag (when (= path (:path @gesture)) @gesture)]
                   (when-not cancel?
                     (let [[x y] (:position drag) [w h] size
                           moved [(snap x [24 704 (- 704 w) 24]) (snap y [80 160 644 (- 644 h) 704])]]
                       (swap! prefs assoc-in (conj path :position) moved)
                       (save-arrangement! @prefs)))
                   (reset! gesture nil)))]
    [:button.tt-handle {:type "button" :aria-label (str "Move " label)
                        :title "Drag to arrange · arrow keys to nudge · Escape to cancel"
                        :on-click #(.stopPropagation %)
                        :on-key-down (fn [e]
                                       (if (= "Escape" (.-key e)) (finish true)
                                         (when-let [[dx dy] ({"ArrowLeft" [-16 0] "ArrowRight" [16 0] "ArrowUp" [0 -16] "ArrowDown" [0 16]} (.-key e))]
                                           (.preventDefault e) (.stopPropagation e)
                                           (swap! prefs assoc-in (conj path :position) (mapv #(clamp % 0 2400) (mapv + xy [dx dy])))
                                           (save-arrangement! @prefs))))
                        :ref (native-ref
                              {"onpointerdown" (fn [e] (when (= 0 (.-button e))
                                                         (.preventDefault e) (.stopPropagation e)
                                                         (.setPointerCapture (.-currentTarget e) (.-pointerId e))
                                                         (reset! gesture {:path path :start [(.-clientX e) (.-clientY e)] :origin xy :position xy})))
                               "onpointermove" (fn [e] (when (= path (:path @gesture))
                                                         (let [{:keys [start origin]} @gesture]
                                                           (swap! gesture assoc :position
                                                                  (mapv #(clamp % 0 2400) (mapv + origin (mapv #(/ % @zoom) (mapv - [(.-clientX e) (.-clientY e)] start))))))))
                               "onpointerup" (fn [_] (finish false))
                               "onpointercancel" (fn [_] (finish true))
                               "onlostpointercapture" (fn [_] (finish true))})} "⠿"]))

(defn card-info [cid]
  (if-let [card (a/improvements cid)]
    (assoc card :id cid :kind :major)
    (assoc (cards/card cid) :id cid :image (card-art/url cid))))
(defn card-face [cid hidden? p]
  (let [{:keys [name image text kind]} (when-not hidden? (card-info cid))
        used? (get-in p [:card-state cid :face-down?])]
    [:div.tt-card-face {:class (str (when (or hidden? used?) "tt-card-back ") (when kind (cljs.core/name kind)))}
     (cond hidden? [:span "Agricola"]
           used? [:div [:span "Agricola"] [:small (str name " · used")]]
           (= kind :major) [art/major-card cid]
           (card-art/available? cid) [card-art/face cid name]
           :else [:div.tt-printed-card [:strong name] [:small (some-> kind cljs.core/name str/capitalize)] [:p text]])
     ]))
(defn card-markers [cid p]
  (let [field (get-in p [:card-fields cid]) state (get-in p [:card-state cid])
        animals (reduce (fn [m slot] (update m (:animal slot) (fnil + 0) (:count slot))) {}
                        (filter #(and (= :card (:kind %)) (= cid (:card %))) (when p (a/animal-housing p))))
        goods (merge-with + (:stored-goods state) (when (:food state) {:food (:food state)})
                          (when (pos? (:count field 0)) (a/field-crops field)) animals)]
    (when (seq goods)
      [:div.tt-card-markers {:aria-label "Goods on card"}
       (for [[resource n] goods :when (pos? n)] ^{:key resource} [:span [art/piece resource] [:b n]])])))
(defn deck-size [n spread?]
  [(if spread? 288 128) (if spread? (+ 72 (* 154 (max 1 (js/Math.ceil (/ n 3))))) (+ 250 (* 24 (max 0 (dec n)))))])
(defn deck [descriptor p prefs gesture zoom open-deck!]
  (let [{:keys [path label ids hidden? spread? xy size]} descriptor
        [x y] (if (= path (:path @gesture)) (:position @gesture) xy)
        n (count ids)]
    [:section.tt-deck {:aria-label label :data-deck (name (last path)) :data-owner (str (first path))
                       :data-mode (if spread? "spread" "stack") :data-x (first xy) :data-y (second xy)
                       :on-click #(when-not (.closest (.-target %) "button") (open-deck! path))
                       :class (when (= path (:path @gesture)) "is-moving")
                       :style {:left (px x) :top (px y) :width (px (first size)) :height (px (second size))}}
     [:div.tt-deck-heading
      [move-handle label path xy size prefs gesture zoom]
      [button label #(open-deck! path) {:class "tt-deck-title" :aria-label (str "Open " label " spread") :aria-haspopup "dialog"}]
      [:span.tt-deck-count n]
      (when (pos? n)
        [button (if spread? "Stack" "Spread")
         #(do (swap! prefs assoc-in (conj path :mode) (if spread? :stack :spread)) (save-arrangement! @prefs))
         {:aria-label (str (if spread? "Stack " "Spread ") label)}])]
     (if (zero? n) [:div.tt-empty-deck "—"]
       [:div.tt-deck-cards {:class (if spread? "spread" "stack")}
        (for [[i cid] (map-indexed vector ids)] ^{:key (str cid "-" i)}
          [:button.tt-card {:type "button" :aria-label (if hidden? (str "Private card " (inc i)) (:name (card-info cid)))
                            :aria-haspopup "dialog"
                            :style (if spread?
                                     {:left (px (* (mod i 3) 96)) :top (px (* (quot i 3) 154))}
                                     {:left "0px" :top (px (* i 24)) :z-index i :--stack-index i})
                            :on-click #(open-deck! path)}
           [card-face cid hidden? p]
           (when-not hidden? [card-markers cid p])])])]))

(def major-board-order [:fireplace-2 :fireplace-3 :hearth-4 :hearth-5 :well
                        :clay-oven :stone-oven :joinery :pottery :basketry])
(def major-board-size [864 660])
(defn major-board [g prefs gesture zoom inspect! open-deck!]
  (let [path [:shared :major-board] xy (position @prefs path [0 0])
        [x y] (if (= path (:path @gesture)) (:position @gesture) xy)]
    [:section.tt-major-board {:aria-label "Major improvements board" :data-x (first xy) :data-y (second xy)
                              :class (when (= path (:path @gesture)) "is-moving")
                              :style {:left (px x) :top (px y) :width (px (first major-board-size))}}
     [:div.tt-major-heading
      [move-handle "Major improvements board" path xy major-board-size prefs gesture zoom]
      [button "Major improvements" #(open-deck! path) {:aria-label "Open Major improvements spread" :aria-haspopup "dialog"}]]
     [:div.tt-major-supply
      [:img {:src (art/url "major-improvements-board.jpg") :alt "Original English major improvements supply board" :draggable false}]
      (for [[i cid] (map-indexed vector major-board-order)
            :let [available? (contains? (:available-improvements g) cid)
                  owner (first (filter #(contains? (:improvements %) cid) (:players g)))]] ^{:key cid}
        [:button.tt-major-slot {:data-major-slot (name cid) :data-available available?
                                 :aria-label (str (:name (card-info cid)) (when-not available? (str " · built by " (:name owner))))
                                 :aria-haspopup "dialog" :on-click #(inspect! cid owner)
                                 :style {:left (str (* 100 (/ (nth [16 363 710 1057 1403] (mod i 5)) 1725)) "%")
                                         :top (str (* 100 (/ (if (< i 5) 77 660) 1234)) "%")
                                         :width (str (* 100 (/ 310 1725)) "%") :height (str (* 100 (/ 500 1234)) "%")}}
         (if available? [card-face cid false nil]
           [:span.tt-major-owner {:style {:border-color (get colors (:id owner))}} (str "Built by " (:name owner))])])]]))

(defn deck-spread [descriptor p inspect!]
  (let [{:keys [ids hidden? label]} descriptor]
    [:div.tt-deck-popup
     (when hidden? [:p.small "This player's hand is private."])
     (if (empty? ids) [:p.small "No cards here yet."]
       [:div.card-choice-spread {:role "group" :aria-label label}
        (for [[i cid] (map-indexed vector ids)] ^{:key (str cid "-" i)}
          [:button.card-choice {:type "button" :aria-label (if hidden? (str "Private card " (inc i)) (:name (card-info cid)))
                                :disabled hidden? :aria-haspopup (when-not hidden? "dialog")
                                :on-click #(when-not hidden? (inspect! cid p))}
           [card-face cid hidden? p]
           (when-not hidden? [card-markers cid p])])])]))

(defn player-objects [p hand prefs focus]
  (let [pid (:id p) names [[:hand-occupation "Hand · occupations" :occupation] [:hand-minor "Hand · minors" :minor]
                         [:occupations "Played · occupations" nil] [:improvements "Played · improvements" nil]]
        played-width (first (deck-size (count (:occupations p)) (= :spread (get-in prefs [pid :occupations :mode] :stack))))
        decks (for [[i [id label kind]] (map-indexed vector names)
                    :let [hidden? (and kind (nil? hand))
                          ids (vec (if kind (if hidden? (range (get-in p [:hand-counts kind] 0)) (get hand kind))
                                     (if (= id :occupations) (:occupations p) (concat (sort (:improvements p)) (:minors p)))))
                          path [pid id] spread? (= :spread (get-in prefs (conj path :mode) (if (and kind (= pid focus)) :spread :stack)))]]
                {:path path :label label :ids ids :hidden? hidden? :spread? spread?
                 :xy (position prefs path (nth [[24 704] [376 704] [752 160] [(+ 792 played-width) 160]] i)) :size (deck-size (count ids) spread?)})
        inventory {:path [pid :inventory] :xy (position prefs [pid :inventory] [24 80]) :size [680 64]}
        objects (conj (vec decks) inventory)]
    {:decks decks :inventory inventory :w (max 1080 (+ 28 (apply max (map #(+ (first (:xy %)) (first (:size %))) objects))))
     :h (max 1000 (+ 40 (apply max (map #(+ (second (:xy %)) (second (:size %))) objects))))}))

(defn table-geometry [players center-height]
  (let [sides (take 4 players) left-w (apply max 1080 (map :w (take-nth 2 sides)))
        right-w (if (next sides) (apply max 1080 (map :w (take-nth 2 (rest sides)))) 0)
        row-heights (mapv #(apply max (map :h %)) (partition-all 2 sides))
        upper-h (+ 120 (reduce + row-heights) (* 64 (max 0 (dec (count row-heights)))))
        center-x (+ left-w 104) center-w 2100
        lower (nth players 4 nil)
        placements (mapv (fn [i p] (assoc p :x (if (even? i) 48 (+ center-x center-w 64))
                                        :y (+ 64 (reduce + (take (quot i 2) row-heights)) (* 64 (quot i 2))))) (range) sides)
        bottom-y (max (+ 128 center-height) upper-h)
        placements (cond-> placements lower (conj (assoc lower :x (+ center-x (/ (- center-w (:w lower)) 2)) :y bottom-y)))]
    {:players placements :board {:x center-x :y 64 :w center-w :h center-height}
     :w (+ center-x center-w 64 right-w 48) :h (+ (if lower (+ bottom-y (:h lower)) (max (+ 64 center-height) upper-h)) 64)}))

(defn apply-camera! [viewport canvas world zoom x y]
  (when (and viewport canvas world)
    (set! (.. world -style -left) (px (/ (.-clientWidth viewport) 2)))
    (set! (.. world -style -top) (px (/ (.-clientHeight viewport) 2)))
    (set! (.. world -style -transform) (str "scale(" zoom ")"))
    (set! (.. canvas -style -width) (px (+ (.-clientWidth viewport) (* zoom (js/parseFloat (.getAttribute world "data-width"))))) )
    (set! (.. canvas -style -height) (px (+ (.-clientHeight viewport) (* zoom (js/parseFloat (.getAttribute world "data-height"))))) )
    (set! (.-scrollLeft viewport) x) (set! (.-scrollTop viewport) y)))
(defn camera! [nodes zoom bounds animate? animation]
  (when-let [v (:viewport @nodes)]
    (when @animation (js/cancelAnimationFrame @animation))
    (let [{:keys [x y w h]} bounds z (clamp (min (/ (- (.-clientWidth v) 56) w) (/ (- (.-clientHeight v) 48) h)) 0.12 1.5)
          tx (* (+ x (/ w 2)) z)
          ty (* (+ y (/ h 2)) z)
          start [(.-scrollLeft v) (.-scrollTop v) @zoom] began (.now js/performance)
          duration (if (and animate? (not (.-matches (.matchMedia js/window "(prefers-reduced-motion: reduce)")))) 340 0)
          step (fn step [time]
                 (let [t (if (zero? duration) 1 (min 1 (/ (- time began) duration))) e (- 1 (js/Math.pow (- 1 t) 3))
                       [nx ny nz] (mapv (fn [a b] (+ a (* e (- b a)))) start [tx ty z])]
                   (apply-camera! v (:canvas @nodes) (:world @nodes) nz nx ny)
                   (if (< t 1) (reset! animation (js/requestAnimationFrame step))
                       (do (reset! animation nil) (reset! zoom z)))))]
      (reset! animation (js/requestAnimationFrame step)))))
(defn zoom-at! [nodes zoom factor cx cy animation]
  (when-let [v (:viewport @nodes)]
    (when @animation (js/cancelAnimationFrame @animation) (reset! animation nil))
    (let [old @zoom z (clamp (* old factor) 0.12 2) rect (.getBoundingClientRect v)
          x (if cx (- cx (.-left rect)) (/ (.-clientWidth v) 2))
          y (if cy (- cy (.-top rect)) (/ (.-clientHeight v) 2))]
      (apply-camera! v (:canvas @nodes) (:world @nodes) z (+ (/ (.-clientWidth v) 2) (- (* (/ (- (+ (.-scrollLeft v) x) (/ (.-clientWidth v) 2)) old) z) x)) (+ (/ (.-clientHeight v) 2) (- (* (/ (- (+ (.-scrollTop v) y) (/ (.-clientHeight v) 2)) old) z) y)))
      (reset! zoom z))))

(defn table [room renderers]
  (r/with-let [prefs (r/atom (load-arrangement)) gesture (r/atom nil) zoom (r/atom 0.3)
               view (r/atom :board) nodes (atom {}) geometry (atom nil) animation (atom nil)
               popup (r/atom nil) dismissed (r/atom nil) pan (atom nil) auto-fit? (atom true) observer (atom nil) shared-height (r/atom 2100) shared-observer (atom nil)
               fit! (fn [animate?] (let [geo @geometry target (if (number? @view) (some #(when (= @view (get-in % [:player :id])) %) (:players geo)) nil)]
                                     (camera! nodes zoom (or target (assoc geo :x 0 :y 0)) animate? animation)))
               focus! (fn [id] (reset! auto-fit? true) (reset! view id) (dispatch :inspect-player (when (number? id) id))
                        (js/requestAnimationFrame #(js/requestAnimationFrame (fn [] (fit! true)))))
               zoom! (fn [factor cx cy] (reset! auto-fit? false) (zoom-at! nodes zoom factor cx cy animation))
               inspect! (fn [cid p] (reset! popup {:kind :card :id cid :player p}))
               open-deck! (fn [path] (reset! popup {:kind :deck :path path}))]
    (let [ui @(rf/subscribe [:ui]) permission @(rf/subscribe [:permission]) g (:state room)
          actor (game/acting-player g) owned (set (:seats permission))
          can-play? (and (:connected? ui) (owned actor) (not= :finished (:phase g)) (not (seq (:takeback room))))
          objects (mapv (fn [p] (assoc (player-objects p (get-in ui [:private-hands (:id room) (:id p)]) @prefs @view) :player p)) (:players g))
          geo (table-geometry objects @shared-height) _ (reset! geometry geo)
          decision? (and can-play? (or (seq (:effect-queue g)) (#{:feeding :breeding :housing} (:phase g)) (:space (:draft ui))))
          decision-key [(:id room) (:revision room) actor (:phase g) (:space (:draft ui)) (get-in g [:effect-queue 0 :id])]
          simple-action? (and (:space (:draft ui)) (empty? (:effect-queue g))
                              (not (#{:build :house-building :side-job :plow :cultivation :sow-bake :fences :redevelop} (:space (:draft ui)))))
          decision-title (cond (seq (:effect-queue g)) (str "Resolve " (or (:name (cards/card (get-in g [:effect-queue 0 :card]))) "card effect"))
                               (:space (:draft ui)) (:name (a/action-definition g (:space (:draft ui))))
                               :else "Harvest")
          vote-result (:takeback-result room)
          vote-visible? (and (seq vote-result) (seq owned)
                             (not= (:id vote-result) (.getItem js/sessionStorage (str "eurozone-vote-dismissed:" (:id room))))
                             (< (- (+ (if (seq vote-result) @controls/wall-time (.now js/Date)) (:server-offset ui 0)) (:at vote-result 0)) 120000))
          active-popup (or @popup (when (and decision? (not= decision-key @dismissed)) {:kind :decision}))
          close! #(do (reset! popup nil) (reset! dismissed decision-key))
          open-decision! (fn [pid] (focus! pid) (reset! dismissed nil) (when-not decision? (reset! popup {:kind :farm :pid pid})))
          board (:board geo)
          major-xy (position @prefs [:shared :major-board] [0 0])
          deck-path (or (:path active-popup) (:deck-path active-popup))
          popup-deck (when deck-path
                       (if (= deck-path [:shared :major-board])
                         {:path deck-path :label "Major improvements" :ids (vec (filter (:available-improvements g) major-board-order))}
                         (some #(when (= deck-path (:path %)) %) (mapcat :decks objects))))
          inspect-from-deck! (fn [cid p] (reset! popup {:kind :card :id cid :player p :deck-path deck-path}))]

      [:main.game.tt-game {:data-revision (:revision room) :data-motion-table (:id room) :data-material (name (if (some #{(:material @prefs)} (map first materials)) (:material @prefs) :tan)) :data-view (if (number? @view) (str "player-" @view) "board")}
       [:div.tt-toolbar
        [button "← Tables" #(dispatch :navigate nil) {:class "tt-back"}]
        [:nav.tt-views {:aria-label "Table views"}
         [button "Board view" #(focus! :board) {:aria-pressed (= :board @view)}]
         (for [p (:players g)] ^{:key (:id p)}
           [button [:<> [:i {:style {:background (get colors (:id p))}}] (:name p)] #(focus! (:id p))
            {:aria-label (str (:name p) " player view") :aria-pressed (= @view (:id p)) :class (when (= actor (:id p)) "tt-active-player")}])]
        [:div.tt-camera-tools
         [button "−" #(zoom! 0.8 nil nil) {:aria-label "Zoom out"}]
         [button (str (js/Math.round (* @zoom 100)) "%") #(do (reset! auto-fit? true) (fit! true)) {:aria-label "Fit view" :title "Fit current view"}]
         [button "+" #(zoom! 1.25 nil nil) {:aria-label "Zoom in"}]]
        [readability/controls]
        [button "Tablecloth" #(do (reset! dismissed decision-key) (reset! popup {:kind :cloth}))]
        [button "Table menu" #(do (reset! dismissed decision-key) (reset! popup {:kind :menu}))]]
       [:div.tt-turn-line [:span.tt-round (str "Round " (:round g) " / 14")]
        [controls/turn-banner room open-decision!]
        (when (number? @view) [button "Farm actions" #(do (reset! dismissed decision-key) (reset! popup {:kind :farm :pid @view}))])]
       [:div.tt-viewport {:tab-index 0 :aria-label "Tabletop. Drag the cloth to pan; Control or Command wheel to zoom."
                          :data-zoom @zoom
                          :on-key-down (fn [e] (when (and (not (or (.-ctrlKey e) (.-metaKey e))) (= (.-target e) (.-currentTarget e)))
                                                (case (.-key e)
                                                  "Home" (do (.preventDefault e) (fit! true))
                                                  "+" (zoom! 1.25 nil nil)
                                                  "-" (zoom! 0.8 nil nil) nil)))
                          :ref (fn [el]
                                 (when (and el (not= el (:viewport @nodes)))
                                   (swap! nodes assoc :viewport el)
                                   (.addEventListener el "wheel" (fn [e] (when (or (.-ctrlKey e) (.-metaKey e))
                                                                         (.preventDefault e) (zoom! (js/Math.exp (* -0.003 (.-deltaY e))) (.-clientX e) (.-clientY e)))) #js {:passive false})
                                   (set! (.-onpointerdown el)
                                         (fn [e] (when (and (or (= 1 (.-button e)) (and (= 0 (.-button e)) (not (.closest (.-target e) "button,a,input,select,textarea,[role=button]")))))
                                                   (.preventDefault e) (.setPointerCapture el (.-pointerId e))
                                                   (when @animation (js/cancelAnimationFrame @animation) (reset! animation nil))
                                                   (reset! auto-fit? false)
                                                   (reset! pan [(.-clientX e) (.-clientY e) (.-scrollLeft el) (.-scrollTop el)])
                                                   (.add (.-classList el) "is-panning"))))
                                   (set! (.-onpointermove el) (fn [e] (when-let [[x y sx sy] @pan]
                                                                                     (set! (.-scrollLeft el) (- sx (- (.-clientX e) x)))
                                                                                     (set! (.-scrollTop el) (- sy (- (.-clientY e) y))))))
                                   (doseq [event ["onpointerup" "onpointercancel" "onlostpointercapture"]]
                                     (aset el event (fn [_] (reset! pan nil) (.remove (.-classList el) "is-panning"))))
                                   (reset! observer (js/ResizeObserver. (fn [_] (when @auto-fit? (fit! false)))))
                                   (.observe @observer el)
                                   (js/requestAnimationFrame #(when @auto-fit? (fit! false)))))}
        [:div.tt-canvas {:ref #(when % (swap! nodes assoc :canvas %)) :style {:width (px (+ (or (some-> (:viewport @nodes) .-clientWidth) 0) (* @zoom (:w geo)))) :height (px (+ (or (some-> (:viewport @nodes) .-clientHeight) 0) (* @zoom (:h geo))))}}
         [:div.tt-world {:ref #(when % (swap! nodes assoc :world %)) :data-width (:w geo) :data-height (:h geo)
                         :style {:left (px (/ (or (some-> (:viewport @nodes) .-clientWidth) 0) 2)) :top (px (/ (or (some-> (:viewport @nodes) .-clientHeight) 0) 2)) :width (px (:w geo)) :height (px (:h geo)) :transform (str "scale(" @zoom ")")}}
          [:section.tt-shared.tabletop-main {:aria-label "Shared board"
                                            :ref (fn [el] (when (and el (nil? @shared-observer))
                                                            (reset! shared-observer (js/ResizeObserver. (fn [_] (let [h (.-offsetHeight el)] (when (not= h @shared-height) (reset! shared-height h) (js/requestAnimationFrame #(when @auto-fit? (fit! false))))))))
                                                            (.observe @shared-observer el))) :style {:left (px (:x board)) :top (px (:y board)) :width (px (:w board))}}
           [:header.tt-board-title [:h1 "Agricola"] [:span (:title room)]]
           [(:action-board renderers) g (and can-play? (= :work (:phase g)) (empty? (:effect-queue g))) (:draft ui) (:busy? ui)]
           [:div.tt-major-area {:style {:min-height (px (+ (second major-xy) (second major-board-size) 32))}}
            [major-board g prefs gesture zoom inspect! open-deck!]
            [:div.tt-round-note [:strong (str "Round " (:round g))] [:span "Harvests: 4 · 7 · 9 · 11 · 13 · 14"]
             [:p "Drag the cloth to move around the table. Select a player above to bring their farm and cards closer."]]]]
          (for [{:keys [player decks inventory x y w h]} (:players geo) :let [pid (:id player)]] ^{:key pid}
            [:section.tt-player {:aria-label (str (:name player) " table area") :data-player pid :class (when (= pid (first (:path @gesture))) "tt-arranging")
                                 :style {:left (px x) :top (px y) :width (px w) :height (px h) :--player-color (get colors pid)}}
             [:header.tt-player-title
              [button (:name player) #(focus! pid) {:aria-label (str "Focus " (:name player) " farm")}]
              [:span (str (:remaining player) " / " (:family player) " workers ready")]
              (when (= pid (:starting-player g)) [:span "First player"])
              (when (= actor pid) [:strong.tt-turn-token (if (owned pid) "Your turn" "Playing")])]
             [:div.tt-farm {:style {:left "24px" :top "160px" :width "680px"}}
              [(:farm renderers) player {} false]
              [:div.tt-farm-caption [:span (str (count (:fences player)) " / 15 fences · " (count (filter :stable (:farm player))) " / 4 stables")]
               (when (pos? (:begging player)) [:span (str (:begging player) " begging marks")])]]
             (let [{:keys [path xy size]} inventory
                   [bx by] (if (= path (:path @gesture)) (:position @gesture) xy)]
               [:section.tt-inventory {:aria-label (str (:name player) " resources")
                                      :data-inventory pid :data-x (first xy) :data-y (second xy)
                                      :class (when (= path (:path @gesture)) "is-moving")
                                      :style {:left (px bx) :top (px by) :width "680px"}}
                [move-handle (str (:name player) " resources") path xy size prefs gesture zoom]
                (for [resource a/resources :let [amount (a/amount player resource)] :when (pos? amount)] ^{:key resource}
                  [:span.tt-resource {:data-goods (name resource) :title (str amount " " (name resource))
                                      :aria-label (str amount " " (name resource))
                                      :data-piece-key (str "goods:" pid ":" resource) :data-piece-type (name resource) :data-piece-count amount}
                   [art/piece resource] [:b.pile-count amount]])])
             (for [d decks] ^{:key (:path d)} [deck d player prefs gesture zoom open-deck!])])]]]
       [:div.tt-table-edge [:span "Drag cloth to pan · Ctrl / ⌘ + wheel to zoom · drag ⠿ to arrange"]
        [button "Chat" #(do (reset! dismissed decision-key) (reset! popup {:kind :chat}))]
        (when (and decision? (= decision-key @dismissed)) [button decision-title #(reset! dismissed nil)])]
       [:div.tt-modal
        (when (and active-popup (not vote-visible?) (not (seq (:takeback room))))
          [controls/modal
           (case (:kind active-popup) :decision decision-title :deck (or (:label popup-deck) "Cards") :card (if (:hidden? popup-deck) "Private card" (:name (card-info (:id active-popup))))
             :farm (str (get-in g [:players (:pid active-popup) :name]) "’s farm") :chat "Table chat" :scores "Scoring" :rules "Rules" :cloth "Tablecloth" "Table menu")
           (case (:kind active-popup)
             :decision [:div {:class (when simple-action? "tt-simple-action")}
                        (when (and (:space (:draft ui)) (empty? (:effect-queue g)))
                          [:div.tt-placement-confirmation
                           [:div.tt-action-preview [(:board-action renderers) g (:space (:draft ui)) false (:draft ui) false nil true]]
                           [:div [:h3 "Place a family member here?"]
                            [:p "Review the action and any choices below, then confirm your placement."]
                            [button "Choose another space" #(do (dispatch :select-action nil) (close!))]]])
                        (if simple-action?
                          [(:action-editor renderers) g (get-in g [:players actor]) (:draft ui) (:busy? ui)]
                          [(:player-panel renderers) g (get-in g [:players actor]) (:draft ui) can-play? (:busy? ui)])]
             :farm [:div [(:player-panel renderers) g (get-in g [:players (:pid active-popup)]) (:draft ui) can-play? (:busy? ui)]
                    [controls/harvest-planner room (:pid active-popup)]]
             :deck [deck-spread popup-deck (get-in g [:players (first deck-path)]) inspect-from-deck!]
             :card (if (:hidden? popup-deck) [:p.small "This player's hand is private."]
                     (let [cid (:id active-popup) p (:player active-popup) card (card-info cid)]
                     [:div.tt-inspection [card-face cid false p]
                      [:div
                       (when deck-path [button "Back to spread" #(open-deck! deck-path) {:class "tt-back-to-spread"}])
                       [:h3 (:name card)]
                       (if (= :major (:kind card)) [(:improvement-effects renderers) card]
                           [:div [:p (:text card)] (when (seq (:cost-text card)) [:p (str "Cost: " (:cost-text card))])
                            (when (seq (:prerequisites card)) [:p (str "Requires: " (:prerequisites card))])])
                       [card-markers cid p]]]))
             :cloth [:div.tt-material-picker
                     (for [[id label] materials] ^{:key id}
                       [button [:<> [:span.tt-material-swatch {:style {:background-image (str "url(/art/tablecloths/" (name id) ".webp)")}}] [:strong label]]
                        #(do (swap! prefs assoc :material id) (save-arrangement! @prefs))
                        {:aria-label label :aria-pressed (= id (:material @prefs :tan))}])]
             :chat [(:chat renderers) room]
             :scores [(:scores renderers) g]
             :rules [(:rules renderers) g]
             :menu [:div.tt-menu
                    [button "Scoring" #(reset! popup {:kind :scores})]
                    [button "Rules" #(reset! popup {:kind :rules})]
                    [button "Reset my arrangement" #(do (swap! prefs select-keys [:material :arrangement-version]) (save-arrangement! @prefs) (reset! popup nil) (js/requestAnimationFrame (fn [] (fit! true))))]
                    [(:recording-picker renderers)]
                    (when (and (:host? permission) (or (= :local (:mode room)) (= :finished (:phase g))))
                      [button "Save recording" #(dispatch :send {:event :recording/request}) {:disabled (:busy? ui)}])
                    [:p.small "Deck and goods positions are saved for your user in this browser. They do not change anyone else’s view."]
                    [:p.small "Move handles also accept arrow keys. Text controls change reading size; the zoom controls change the table’s scale."]]) close!])
        ;; Kept outside the menu so votes and results can arrive while the table is visible.
        [:div.tt-takeback {:on-click-capture #(when (.closest (.-target %) "button") (close!))} [controls/takeback room]]]] )
    (finally (when @observer (.disconnect @observer))
             (when @shared-observer (.disconnect @shared-observer))
             (when @animation (js/cancelAnimationFrame @animation)))))
