(ns agricola.fr-fifteen
  "FR-specific predicates and automatic effects; native choices stay in the shared flows."
  (:require [agricola.engine :as a] [agricola.card-rules :as cards]))

(def wood-gatherers :catalog/original-expansion-fr-118-wood-gatherers)
(def good-friend :catalog/original-expansion-fr-83-good-friend)
(def convict :catalog/original-expansion-fr-73-convict-number-24601)
(def cowboy-mother :catalog/original-expansion-fr-75-cowboy-and-mother)
(defn wood-saw-available? [g pid]
  (let [family (:family (get-in g [:players pid]))]
    (and (> (count (:players g)) 1)
         (every? #(or (= pid (:id %)) (> (:family %) family)) (:players g)))))
(defn workaholic-options [player cid]
  (let [animals (reduce + 0 (map #(a/amount player %) a/animals)) housed? (some? (a/animal-housing player))]
    (vec (for [[resource threshold] [[:wood 5] [:clay 7] [:stone 9]]
               :let [n (get-in player [:card-state cid :stored-goods resource] 0)]
               :when (and housed? (>= animals threshold) (pos? n))]
           {:label (str "Collect " n " " (name resource))
            :effects [{:op :discard-stored :goods {resource n}} {:op :gain :goods {resource n}}]}))))
(defn apply-convict [g]
  (update g :players
          #(mapv (fn [player]
                   (if (and (cards/has? player convict)
                            (> (:round g) (get-in player [:card-state convict :played-round] 14))
                            (not= (:round g) (:convict-blocked-round player)))
                     (-> player (update :remaining (fn [n] (max 0 (dec n)))) (assoc :convict-blocked-round (:round g))) player)) %)))
(defn exact-plow-available? [player cells]
  (or (nil? cells)
      (some (fn [order]
              (try (reduce #(a/place-tile %1 :field %2) player order) true
                   (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false)))
            (for [a cells b cells c cells :when (= 3 (count (set [a b c])))] [a b c]))))
(defn turkey-debt [g pid]
  (let [wood (min 4 (a/amount (get-in g [:players pid]) :wood))]
    (update-in g [:players pid] #(-> % (a/pay {:wood wood}) (update :begging (fnil + 0) (- 4 wood))))))
