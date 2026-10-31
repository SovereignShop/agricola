(ns eurozone.round-markers
  "Public presentation of pieces promised by the engine's saved schedules.
   Kept outside the rules fingerprint so a display fix can open existing saves."
  (:require [agricola.card-rules :as cards]))

(def fence-deliveryman :catalog/original-base-i-265-fence-deliveryman)
(def guest :catalog/original-base-i-73-guest)

(defn scheduled-pieces [{:keys [goods effect stable person]}]
  ;; Arrival effects take precedence in begin-round. In particular, :goods {}
  ;; must not hide Plowman's field or Animal Handler's optional animals.
  (cond
    effect (case (:op effect) :plow {:field 1} (:goods effect))
    stable {:stable 1}
    person {:person 1}
    :else goods))

(defn deliveries [player round]
  (let [owned (set (cards/owned player))
        well-food (+ (if (contains? (:well-rounds player) round) 1 0)
                     (get-in player [:well-deliveries round] 0))
        fences (get-in player [:reserved-fences round] 0)
        guests (get-in player [:guest-schedule round] 0)]
    (filterv #(seq (:goods %))
      (concat
        (for [entry (get-in player [:schedule round]) :when (owned (:card entry))]
          {:card (:card entry)
           :goods (into {} (filter (fn [[_ n]] (and (number? n) (pos? n))) (scheduled-pieces entry)))
           :optional? (boolean (or (:arrival-cost entry) (get-in entry [:effect :optional?])
                                   (:stable entry) (:person entry) (:field (:goods entry))))
           :cost (or (:arrival-cost entry) (get-in entry [:effect :cost]))})
        (when (pos? well-food) [{:card :well :goods {:food well-food}}])
        (when (and (owned fence-deliveryman) (pos? fences))
          [{:card fence-deliveryman :goods {:fence fences} :optional? true :cost {:food 2}}])
        ;; Guest passes on immediately, but the promised worker stays with the
        ;; player who played it; ownership is intentionally not checked here.
        (when (pos? guests) [{:card guest :goods {:guest guests}}])))))

(defn by-round [g]
  (into (sorted-map)
    (for [round (range (inc (:round g)) 15)
          :let [owners (vec (for [player (:players g)
                                 :let [entries (deliveries player round)] :when (seq entries)]
                             {:player (:id player)
                              :goods (apply merge-with + {} (map :goods entries))
                              :cards (vec (distinct (map :card entries)))
                              :deliveries entries}))]
          :when (seq owners)]
      [round owners])))
