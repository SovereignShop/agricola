(ns agricola.learning-rewards
  "Opt-in learning signals from public state. Does not alter game rules or scores."
  (:require [clojure.java.io :as io] [agricola.engine :as a] [agricola.card-rules :as cards]
            [agricola.recording :as recording] [agricola.data :refer [read-edn]]))
(def definition (read-edn "agricola/rewards.edn"))
(def fingerprint (recording/sha (str (pr-str definition) (slurp (io/resource "agricola/learning_rewards.clj")))))
(defn fields [p] (filter #(= :field (:type %)) (concat (:farm p) (vals (:card-fields p)))))
(defn grain-yield-food [p grain]
  ;; Baking requires a worker action. Discount its output by two food of
  ;; alternative work, and obey the actual oven batch limits.
  (reduce max grain
    (for [n (range 1 (inc grain))]
      (try
        (+ (- grain n) (- (a/amount (a/bake (assoc-in (assoc-in p [:goods :grain] n) [:goods :food] 0) n) :food) 2))
        (catch clojure.lang.ExceptionInfo _ grain)))))
(defn metrics [state pid]
  (let [p (get-in state [:players pid]) round (:round state) rates (a/cook-rates p)
        harvests (count (filter #(>= % (max 1 round)) a/harvest-rounds))
        needed (a/food-needed state p) full-meal (* (if (= 1 (count (:players state))) 3 2) (:family p))
        fed? (and (#{:feeding :breeding :finish-round} (:phase state)) (contains? (:fed state) pid))
        meal-credit (if fed? needed 0)
        safe-food (+ (a/amount p :food)
                     (reduce + (for [resource (concat a/animals [:grain :vegetable])]
                                 (* (get rates resource 0) (max 0 (- (a/amount p resource) (if ((set a/animals) resource) 2 1)))))))
        crops (frequencies (keep #(when (pos? (:count % 0)) (:crop %)) (fields p)))
        crop-food (+ (grain-yield-food p (get crops :grain 0)) (* (get crops :vegetable 0) (get rates :vegetable 1)))
        breeding-food (if (a/animal-housing p)
                        (reduce + (for [animal a/animals :when (>= (a/amount p animal) 2)] (get rates animal 0))) 0)
        scheduled (+ (count (filter #(>= % round) (:well-rounds p)))
                     (reduce + (for [[r arrivals] (:schedule p) :when (> r round)
                                     {:keys [card goods]} arrivals :when (some #{card} (cards/owned p))] (:food goods 0))))
        productive (+ crop-food breeding-food (/ scheduled (double (max 1 harvests))))
        cooking (min (get-in definition [:limits :cooking-food-per-action])
                     (max 0 (- (* 2 (apply max 0 (map #(get rates % 0) a/animals))) 2)))
        coverage (min 1.0 (/ (+ safe-food meal-credit) (double (max 1 needed))))
        support (min 1.0 (/ (+ safe-food meal-credit (* harvests (+ productive cooking)))
                           (double (max 1 (* harvests full-meal)))))]
    {:family (:family p) :remaining (:remaining p) :rooms (count (a/tiles p :room)) :begging (:begging p)
     :food (a/amount p :food) :required needed :fed fed? :safe-food safe-food :coverage coverage
     :productive-food productive :cooking-access cooking :growth-support support
     :remaining-rounds (max 0 (- 14 round)) :remaining-harvests harvests}))
(defn potential [state pid]
  (if (#{:finished :draw} (:phase state)) 0.0
    (let [{:keys [family rooms begging coverage productive-food cooking-access growth-support remaining-rounds]} (metrics state pid)
          weights (:weights definition) horizon (/ remaining-rounds 13.0)]
      (+ (* (:meal-coverage weights) coverage)
         (* (:productive-food weights) horizon (min (get-in definition [:limits :productive-food]) productive-food))
         (* (:cooking-access weights) horizon cooking-access)
         (* (:supported-family-round weights) remaining-rounds (max 0 (- family 2)) growth-support)
         (* (:supported-spare-room-round weights) remaining-rounds (if (< family 5) (min 1 (max 0 (- rooms family))) 0) growth-support)
         (* (- (:begging weights)) begging)))))
(defn observation [state]
  {:version (:version definition) :fingerprint fingerprint
   :potentials (mapv #(potential state %) (range (count (:players state))))
   :metrics (mapv #(metrics state %) (range (count (:players state))))})
