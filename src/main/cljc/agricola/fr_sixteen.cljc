(ns agricola.fr-sixteen
  "Original FR card bookkeeping; choices use the existing graphical flows."
  (:require [agricola.engine :as a] [agricola.card-rules :as c] [agricola.action-board :as board]))
(def prefect :catalog/original-expansion-fr-102-prefect)
(def prosecutor :catalog/original-expansion-fr-103-prosecutor)
(def school :catalog/original-expansion-fr-47-sunday-school)
(def park :catalog/original-expansion-fr-40-park-cemetery)
(def fencing :catalog/original-expansion-fr-80-fencing-master)
(defn improvement-count [p] (count (a/owned-improvement-ids p)))
(defn refresh [g]
  (update g :players #(mapv (fn [p] (if (c/has? p prosecutor)
    (assoc p :prosecutor-discount (max 0 (dec (count (filter (fn [other] (> (improvement-count other) (improvement-count p))) (:players g)))))) p)) %)))
(defn necklace? [g pid]
  (let [p (get-in g [:players pid]) spaces (take (- (:family p) (:newborn p 0)) (:worker-spaces p))]
    (boolean (some (fn [x] (some #(board/adjacent? g x %) spaces)) spaces))))
(defn tower? [g pid context]
  (and (= 2 (:rooms-before context)) (#{:build :house-building} (:space context))
       (every? #(or (= pid (:id %)) (not= 2 (count (a/tiles % :room)))) (:players g))))
(defn park-stone [g] (+ (get-in g [:park-stone] 0) (get-in g [:piles :plow :stone] 0) (get-in g [:space-bonuses :plow :stone] 0)))
(defn apply-school [g]
  (update g :players #(mapv (fn [p]
    (if (and (pos? (get-in p [:school-rounds (:round g)] 0)) (not= (:round g) (:school-blocked-round p)))
      (-> p (update :remaining (fn [n] (max 0 (- n (get-in p [:school-rounds (:round g)] 0))))) (assoc :school-blocked-round (:round g))) p)) %)))
(defn prefect-receipts [g receipts]
  (let [owned? (some #(c/has? % prefect) (:players g))
        sources (when owned? (set (for [[pid r] receipts
                    :when (and (#{:field-harvest :feeding :breeding} (:phase r))
                               (some #(pos? (get-in r [:goods %] 0)) [:wood :clay :reed :stone])
                               (:recipe-card r))]
                    [(:round r (:round g)) pid (:recipe-card r)])))
        prior (:prefect-conversions g #{}) fresh (remove prior sources)]
    [(cond-> g (seq sources) (update :prefect-conversions (fnil into #{}) sources)) (count fresh)]))

(defn craft-bonus [g pid selected]
  (if (c/has? (get-in g [:players pid]) prefect)
    (count (remove #(contains? (:prefect-conversions g #{}) [(:round g) pid %]) (distinct selected))) 0))
