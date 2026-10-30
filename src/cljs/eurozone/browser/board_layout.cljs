(ns eurozone.browser.board-layout)

;; The three unmodified English board scans share one physical pixel scale.
;; Small gaps expose the cloth between the two portrait and one landscape boards.
(def width 4225)
(def height 1725)
(def segments
  [{:id :actions :file "board-original-base.jpg" :label "Basic actions and player-count cards"
    :bounds [0 0 1234 1725]}
   {:id :early-rounds :file "board-original-rounds-1.jpg" :label "Rounds 1 to 7"
    :bounds [1250 0 1234 1725]}
   {:id :late-rounds :file "board-original-rounds-2.jpg" :label "Rounds 8 to 14"
    :bounds [2500 0 1725 1234]}])
(defn position [[x y w h]]
  {:left (str (* 100 (/ x width)) "%") :top (str (* 100 (/ y height)) "%")
   :width (str (* 100 (/ w width)) "%") :height (str (* 100 (/ h height)) "%")})
(def actions
  {:build [790 20 420 260] :meeting [790 290 420 275] :grain [790 580 420 265]
   :plow [790 865 420 270] :lessons [790 1150 420 275] :side-job [790 1150 420 275]
   :labor [790 1440 420 270]
   :wood [1275 585 410 260] :clay [1275 860 410 265]
   :reed [1275 1145 410 265] :fishing [1275 1430 410 280]})
(def rounds
  [[1295 45] [1700 45] [1700 600] [1700 1155] [2100 45] [2100 600] [2100 1155]
   [2540 50] [2540 610] [2940 50] [2940 610] [3340 50] [3340 610] [3740 50]])
(defn round-position [round]
  (position (into (nth rounds (dec round)) [340 510])))
(def player-count-area [30 35 720 1655])
(defn player-count-groups [n]
  ;; These are Eurozone's existing Revised actions, placed in the original
  ;; board's player-count card area. A linked pair remains one placement group.
  (if (= n 5)
    [[:lessons-copse :copse] [:riverbank] [:grove] [:lessons-growth :modest-growth]
     [:animal-market] [:market] [:hollow] [:house-building :traveling]]
    [[:grove] [:hollow] [:market] [:copse] [:lessons-extra] [:traveling]]))
