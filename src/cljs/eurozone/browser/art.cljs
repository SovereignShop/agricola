(ns eurozone.browser.art)
(def root "/art/agricola/")
(def pieces {:wood "tree" :clay "clay" :reed "reed" :stone "rock" :grain "grain2" :vegetable "vegetable" :food "food" :sheep "sheep" :boar "pig" :cattle "cow"})
(def majors {:fireplace-2 1 :fireplace-3 2 :hearth-4 3 :hearth-5 4 :well 5 :clay-oven 6 :stone-oven 7 :joinery 8 :pottery 9 :basketry 10})
(def workers ["farmer_red" "farmer_blue" "farmer_yellow" "farmer_green" "farmer_green"])
(defn url [name] (str root name))
(defn piece-url [resource] (url (str (pieces resource) ".png")))
(defn worker-url [pid] (url (str (workers pid) ".png")))
(defn card-url [id] (url (str "major-" (majors id) ".png")))
(def major-indices {:pottery 0 :hearth-4 1 :fireplace-3 2 :hearth-5 3 :stone-oven 4
                    :basketry 5 :joinery 6 :clay-oven 7 :well 8 :fireplace-2 9})
(defn major-card [id & [attrs]]
  [:span (merge {:class "major-card-art" :role "img" :aria-label "Original English major improvement card"
                 :data-major id :style {:background-image (str "url(" (url "majors-en.jpg") ")")
                                       :background-position (str (* (/ (major-indices id) 9) 100) "% 0%")}} attrs)])
(def round-card-indices
  {:fences 0 :improvement 1 :sheep 2 :sow-bake 3 :growth 4 :renovate 5
   :stone-west 6 :boar 7 :vegetable 8 :cattle 9 :stone-east 10
   :urgent-growth 11 :cultivation 12 :redevelop 13})
(defn round-card-art [side index]
  ;; Original, unmodified 10-column, 7-row sheets. A back's index comes only
  ;; from the public round number, never from the hidden shuffled action order.
  [:span.round-card-art {:aria-hidden true
                         :style {:background-image (str "url(" (url (str "round-" (name side) ".jpg")) ")")
                                 :background-position (str (* (/ (mod index 10) 9) 100) "% "
                                                           (* (/ (quot index 10) 6) 100) "%")}}])
(defn piece [resource] [:img.piece {:src (piece-url resource) :alt "" :aria-hidden true :draggable false}])
(defn pile [resource n]
  [:span.piece-pile {:aria-label (str n " " (name resource)) :title (str n " " (name resource))}
   (for [i (range (min 7 n))] ^{:key i}
     [:img.piece {:src (piece-url resource) :alt "" :draggable false
                  :style {:left (str (* 12 (mod i 4)) "px") :top (str (* 10 (quot i 4)) "px") :transform (str "rotate(" (- (* i 11) 16) "deg)")}}])
   [:b.pile-count n]])
