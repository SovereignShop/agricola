(ns agricola.action-board
  "Shared physical board layout and rules geometry. Personal table pan/zoom never
   changes adjacency. Rectangles use logical grid units, ignoring printed margins.")

(def width 4225)
(def height 1725)
(def segments
  [{:id :actions :file "board-original-base.jpg" :label "Basic actions and player-count cards"
    :bounds [0 0 1234 1725]}
   {:id :early-rounds :file "board-original-rounds-1.jpg" :label "Rounds 1 to 7"
    :bounds [1250 0 1234 1725]}
   {:id :late-rounds :file "board-original-rounds-2.jpg" :label "Rounds 8 to 14"
    :bounds [2500 0 1725 1234]}])
(def actions
  {:build [790 20 420 260] :meeting [790 290 420 275] :grain [790 580 420 265]
   :plow [790 865 420 270] :lessons [790 1150 420 275] :side-job [790 1150 420 275]
   :labor [790 1440 420 270]
   :wood [1275 585 410 260] :clay [1275 860 410 265]
   :reed [1275 1145 410 265] :fishing [1275 1430 410 280]})
(def rounds
  [[1295 45] [1700 45] [1700 600] [1700 1155] [2100 45] [2100 600] [2100 1155]
   [2540 50] [2540 610] [2940 50] [2940 610] [3340 50] [3340 610] [3740 50]])
(def player-count-area [30 35 720 1655])
(defn player-count-groups [n]
  (if (= n 5)
    [[:lessons-copse :copse] [:riverbank] [:grove] [:lessons-growth :modest-growth]
     [:animal-market] [:market] [:hollow] [:house-building :traveling]]
    [[:grove] [:hollow] [:market] [:copse] [:lessons-extra] [:traveling]]))
(def base-rects
  {:build [2 0 1 1] :meeting [2 1 1 1] :grain [2 2 1 1]
   :plow [2 3 1 1] :lessons [2 4 1 1] :side-job [2 4 1 1] :labor [2 5 1 1]
   :wood [3 2 1 1] :clay [3 3 1 1] :reed [3 4 1 1] :fishing [3 5 1 1]})
(def round-rects
  [[3 0 1 2] [4 0 1 2] [4 2 1 2] [4 4 1 2] [5 0 1 2] [5 2 1 2] [5 4 1 2]
   [6 0 1 2] [6 2 1 2] [7 0 1 2] [7 2 1 2] [8 0 1 2] [8 2 1 2] [9 0 1 2]])
(def base-ids (into (set (keys base-rects)) (mapcat identity (concat (player-count-groups 4) (player-count-groups 5)))))
(defn locations [g]
  (let [n (count (:players g)) active (set (:active g))
        groups (keep #(not-empty (filterv active %)) (player-count-groups n))
        row-height (/ 6 (if (= n 5) 4 3))
        group-locations (for [[index group] (map-indexed vector groups)
                              [subindex space] (map-indexed vector group)
                              :let [h (/ row-height (count group))]]
                          [space {:board :actions :rect [(mod index 2) (+ (* (quot index 2) row-height) (* subindex h)) 1 h]}])
        revealed (vec (remove #(or (base-ids %) (namespace %)) (:active g)))]
    (into {} (concat
              (for [[space rect] base-rects :when (active space)]
                [space {:board (if (#{:wood :clay :reed :fishing} space) :early-rounds :actions) :rect rect}])
              group-locations
              (for [[index space] (map-indexed vector (take 14 revealed))
                    ;; Card-created spaces are not printed on a game board.
                    :when (nil? (namespace space))]
                [space {:board (if (< index 7) :early-rounds :late-rounds)
                        :round (inc index) :rect (nth round-rects index)}])))))
(defn space-board [g space] (get-in (locations g) [space :board]))
(defn- overlap? [a length-a b length-b] (< (max a b) (min (+ a length-a) (+ b length-b))))
(defn- touching? [[x y w h] [xx yy ww hh]]
  (or (and (or (= (+ x w) xx) (= (+ xx ww) x)) (overlap? y h yy hh))
      (and (or (= (+ y h) yy) (= (+ yy hh) y)) (overlap? x w xx ww))))
(defn adjacent-spaces [g space]
  (let [positions (locations g) rect (get-in positions [space :rect])]
    (if rect (into #{} (for [[other data] positions :when (and (not= space other) (touching? rect (:rect data)))] other)) #{})))
(defn adjacent? [g a b] (contains? (adjacent-spaces g a) b))
