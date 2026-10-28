(ns eurozone.table-layout
  "Personal table arrangements. All changes are independent of game commands.")

(def default-layout
  {:rows [["board" "farm" "chat"] ["hand" "cards"] ["log"]]
   :widths {"board" 6 "farm" 4 "chat" 2 "hand" 8 "cards" 4 "log" 12}
   :heights {} :focus? false})
(defn clamp [n low high] (max low (min high n)))
(defn weight [layout id] (max 0.25 (get-in layout [:widths id] 6)))
(defn tidy [layout]
  (let [rows (vec (filter seq (mapv vec (:rows layout))))]
    (assoc layout :rows rows :order (vec (mapcat identity rows))
           :widths (reduce (fn [widths row]
                             (let [total (reduce + (map #(weight layout %) row))]
                               (into widths (map #(vector % (* 12 (/ (weight layout %) total))) row))))
                           (:widths layout) rows))))
(defn normalize [saved]
  (tidy
   (if-not saved default-layout
     (if (:rows saved) saved
       ;; Preserve old wrapping order, visibility, widths, and explicit heights.
       (assoc saved :rows
              (:rows (reduce (fn [{:keys [used rows]} id]
                               (let [w (get-in saved [:widths id] 6)]
                                 (if (or (empty? rows) (> (+ used w) 12))
                                   {:used w :rows (conj rows [id])}
                                   {:used (+ used w) :rows (update rows (dec (count rows)) conj id)})))
                             {:used 0 :rows []} (:order saved))))))))
(defn remove-panel [layout id]
  (tidy (update layout :rows #(mapv (fn [row] (vec (remove #{id} row))) %))))
(defn row-index [layout id]
  (first (keep-indexed #(when (some #{id} %2) %1) (:rows layout))))
(defn insert-at [xs i value] (vec (concat (take i xs) [value] (drop i xs))))
(defn dock [layout source target edge]
  (if (or (= source target) (nil? (row-index layout source)) (nil? (row-index layout target))
          (not (#{:left :right :top :bottom} edge))) layout
    (let [layout (remove-panel layout source) r (row-index layout target)
          row (get-in layout [:rows r]) i (first (keep-indexed #(when (= target %2) %1) row))]
      (tidy
       (if (#{:top :bottom} edge)
         (assoc layout :rows (insert-at (:rows layout) (+ r (if (= edge :bottom) 1 0)) [source]))
         (let [half (/ (weight layout target) 2)]
           (-> layout
               (assoc-in [:rows r] (insert-at row (+ i (if (= edge :right) 1 0)) source))
               (assoc-in [:widths source] half) (assoc-in [:widths target] half))))))))
(defn resize-panel [layout id width height]
  (if-let [r (row-index layout id)]
    (let [row (get-in layout [:rows r]) others (remove #{id} row)
          w (if (seq others) (clamp width 1 (- 12 (count others))) 12)
          total (reduce + (map #(weight layout %) others))]
      (cond-> (reduce #(assoc-in %1 [:widths %2] (* (- 12 w) (/ (weight layout %2) total)))
                      (assoc-in layout [:widths id] w) others)
        height (assoc-in [:heights id] (clamp height 180 1800)))) layout))
(defn resize-pair [layout left right left-width]
  (if (and (some? (row-index layout left)) (= (row-index layout left) (row-index layout right)))
    (let [total (+ (weight layout left) (weight layout right))
          w (clamp left-width (min 1 (/ total 3)) (- total (min 1 (/ total 3))))]
      (-> layout (assoc-in [:widths left] w) (assoc-in [:widths right] (- total w)))) layout))
(defn all-farms [layout n]
  (let [farm? #(or (= % "farm") (re-matches #"farm-\d+" %))
        rows (vec (filter seq (mapv #(vec (remove farm? %)) (:rows layout))))
        farms (mapv #(str "farm-" %) (range n))
        index (min 1 (count rows))]
    (tidy (-> layout
              (assoc :rows (vec (concat (take index rows) (mapv vec (partition-all 2 farms)) (drop index rows))))
              (update :widths merge (zipmap farms (repeat 6)))))))
(defn change [layout operation args]
  (let [[id value extra] args]
    (case operation
      :dock (dock layout id value extra)
      :toggle (if (some #{id} (:order layout)) (remove-panel layout id)
                  (tidy (update layout :rows conj [id])))
      :resize (resize-panel layout id value extra)
      :pair (resize-pair layout id value extra)
      :auto-height (assoc-in layout [:heights id] :auto)
      :move (let [order (:order layout) i (first (keep-indexed #(when (= id %2) %1) order))
                  to (when i (+ i value))]
              (if (and to (<= 0 to) (< to (count order)))
                (dock layout id (nth order to) (if (neg? value) :left :right)) layout))
      :full-row (if-let [r (row-index layout id)]
                  (tidy (assoc (remove-panel layout id) :rows
                               (insert-at (:rows (remove-panel layout id)) (min r (count (:rows (remove-panel layout id)))) [id]))) layout)
      :all-farms (all-farms layout id)
      :focus (update layout :focus? not)
      :preset (case id
                :farms (-> (all-farms (normalize nil) value)
                           (assoc :focus? (:focus? layout))
                           (assoc-in [:heights "board"] 420) (assoc-in [:heights "chat"] 420))
                (assoc (normalize nil) :focus? (:focus? layout)))
      :reset (normalize nil)
      layout)))
