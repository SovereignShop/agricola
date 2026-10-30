(ns agricola.card-reactive
  "State changes that trigger cards even outside a named board action."
  (:require [agricola.engine :as a] [agricola.card-rules :as c] [agricola.card-expressions :as expr]))

(def shaving-id (c/id :a048))
(defn watch-wood [g pid]
  (assoc-in g [:players pid :card-state shaving-id :received-seen] (get-in g [:players pid :received] {})))
(defn sync-shaving [g]
  (reduce (fn [g pid]
            (let [player (get-in g [:players pid]) seen (get-in player [:card-state shaving-id :received-seen] (:received player))
                  wood (- (get-in player [:received :wood] 0) (get seen :wood 0))]
              (if-not (c/has? player :a048) g
                (cond-> (watch-wood g pid)
                  (pos? wood) (update :shaving-pending (fnil conj #{}) pid)))))
          g (range (count (:players g)))))
(defn wood-gain? [g head]
  (when (#{:gain :all-gain} (get-in head [:effect :op]))
    (let [goods (expr/quantities g (:player head) (:context head) (get-in head [:effect :goods]))]
      (= #{:wood} (set (keys (into {} (filter (comp pos? val) goods))))))))
(defn flush-shaving
  "Consecutive wood deliveries in one timing window are one receipt. Flush
   before another effect/decision so its food can fund that effect."
  [g]
  (let [head (first (:effect-queue g))
        boundary? (or (nil? head) (= :continue (:kind head))
                      (and (= :event (:kind head)) (#{:after-use-space :turn-end :round-end :harvest-end} (:event head)))
                      (and (= :effect (:kind head)) (not (wood-gain? g head))))
        pids (sort (:shaving-pending g))]
    (if-not (and boundary? (seq pids)) g
      (-> g (dissoc :shaving-pending)
          (update :effect-queue #(vec (concat
                                      (for [pid pids :when (>= (a/amount (get-in g [:players pid]) :wood) 5)]
                                        {:kind :effect :player pid :card shaving-id :context {}
                                         :effect {:op :threshold-exchange :optional? true :resource :wood
                                                  :minimum 5 :mandatory-at 7 :food 3}}) %)))))))

(defn task [pid cid effect] {:kind :effect :player pid :card cid :effect effect :context {}})
(defn prepend [g tasks] (update g :effect-queue #(vec (concat tasks %))))
(defn empty-cells [player] (set (filter #(a/empty-cell? player %) (range 15))))
(defn watch-fences [g pid]
  (assoc-in g [:players pid :card-state (c/id :a034) :fences-seen]
            (count (get-in g [:players pid :fences]))))
(defn potters-yard [g pid]
  (assoc-in g [:players pid :card-state (c/id :a040) :clay-cells]
            (empty-cells (get-in g [:players pid]))))
(def milestones {:reed {:target 3 :goods 3 :measure #(count (a/pastures %))}
                 :clay {:target 5 :goods 4 :measure #(count (a/tiles % :field))}})
(defn sequestrator [g pid]
  (assoc-in g [:players pid :card-state (c/id :a144)]
            {:stored-goods {:reed 3 :clay 4}
             :eligible (into {} (for [[r {:keys [target measure]}] milestones]
                                  [r (set (keep #(when (< (measure %) target) (:id %)) (:players g)))]))}))
(defn sync-farm-cards [g]
  (reduce
   (fn [g pid]
     (let [player (get-in g [:players pid]) loppers (c/id :a034) yard (c/id :a040) seques (c/id :a144)
           fences (count (:fences player)) seen (get-in player [:card-state loppers :fences-seen] fences)
           clay-cells (get-in player [:card-state yard :clay-cells] #{})
           used (sort (remove #(a/empty-cell? player %) clay-cells))
           g (cond-> g
               (c/has? player :a034) (assoc-in [:players pid :card-state loppers :fences-seen] fences)
               (and (c/has? player :a034) (> fences seen))
               (prepend [(task pid loppers {:op :loppers :optional? true :cost {:wood 1}})])
               (and (c/has? player :a040) (seq used))
               (assoc-in [:players pid :card-state yard :clay-cells] (apply disj clay-cells used))
               (and (c/has? player :a040) (seq used))
               (update-in [:players pid] a/gain {:clay (count used)})
               (and (c/has? player :a040) (seq used))
               (prepend (map #(task pid yard {:op :exchange :optional? true :cost {:clay 1} :goods {:food 2} :cell %}) used)))]
       (if-not (c/has? player :a144) g
         (reduce
          (fn [g resource]
            (let [{:keys [target goods measure]} (milestones resource)
                  state (get-in g [:players pid :card-state seques])
                  winner (first (filter #(and (contains? (get-in state [:eligible resource] #{}) (:id %))
                                              (>= (measure %) target)) (:players g)))]
              (if (and (pos? (get-in state [:stored-goods resource] 0)) winner)
                (-> g (assoc-in [:players pid :card-state seques :stored-goods resource] 0)
                    (assoc-in [:players pid :card-state seques :winners resource] (:id winner))
                    (update-in [:players (:id winner)] a/gain {resource goods})
                    (a/log-event (str (:name winner) " claimed Sequestrator's " goods " " (name resource)))) g)))
          g [:reed :clay]))))
   g (range (count (:players g)))))
