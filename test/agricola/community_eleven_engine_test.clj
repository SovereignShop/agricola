(ns agricola.community-eleven-engine-test
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [agricola.engine :as a]
            [agricola.card-rules :as cards]
            [agricola.card-game :as g]
            [agricola.community-decks-test :refer [cid fresh rich own play]]))

(defn player-with [& ids]
  (g/p (reduce #(own %1 0 %2) (rich (fresh)) ids) 0))
(defn no-pets [p]
  (update p :minors conj (cid "WM" 63)))
(defn pasture [p cells stable]
  (cond-> (assoc p :fences (set (map key (filter #(= 1 (val %)) (frequencies (mapcat a/edges cells))))))
    stable (assoc-in [:farm stable :stable] true)))
(defn animals [p counts]
  (assoc p :goods (merge (zipmap a/animals (repeat 0)) counts)))

(deftest friendly-pastures-holds-two-species-only-in-stabled-pastures
  (let [p (-> (player-with (cid "WM" 84)) no-pets (pasture [0 1] 0))
        space (first (filter #(= :pasture (:kind %)) (a/accommodations p)))]
    (is (= 8 (:capacity space)))
    (is (= 2 (:max-animal-types space)))
    (doseq [counts [{:sheep 4 :boar 4} {:boar 2 :cattle 6} {:sheep 5 :cattle 3}]]
      (let [housing (a/animal-housing (animals p counts))]
        (is (= counts (reduce #(merge-with + %1 (a/housing-animals %2)) {} housing)))))
    (is (nil? (a/animal-housing (animals p {:sheep 1 :boar 1 :cattle 1}))))
    (is (some? (a/animal-housing (animals (update p :occupations conj :catalog/original-base-e-216-animal-keeper)
                                        {:sheep 1 :boar 1 :cattle 1}))))
    (is (nil? (a/animal-housing (animals p {:sheep 5 :cattle 4}))))
    (is (nil? (a/animal-housing (animals (assoc-in p [:farm 0 :stable] false) {:sheep 1 :boar 1}))))
    (is (= (a/animal-housing p) (a/animal-housing (edn/read-string (pr-str p)))))))

(deftest kids-corner-adds-an-independent-pet-and-obeys-house-exclusions
  (let [p (player-with (cid "FR" 33))
        pets #(filter (fn [space] (= :pet (:kind space))) (a/accommodations %))]
    (is (= 2 (count (pets p))))
    (is (some? (a/animal-housing (animals p {:sheep 1 :cattle 1}))))
    (is (some? (a/animal-housing (animals p {:boar 2}))))
    (is (nil? (a/animal-housing (animals p {:sheep 1 :boar 1 :cattle 1}))))
    (is (empty? (pets (no-pets p))))
    (is (nil? (a/animal-housing (animals (no-pets p) {:sheep 1}))))
    (is (= 3 (count (pets (update p :occupations conj (cards/id :a086))))))))

(deftest reformer-houses-one-per-actual-occupation-including-itself
  (let [reformer (cid "FR" 105) academic :catalog/original-base-e-148-academic
        p (-> (player-with reformer academic (cid "G" 21)) no-pets)
        spaces (filter #(= :reformer (:slot %)) (a/accommodations p))]
    (is (= #{reformer academic (cid "G" 21)} (set (map :card spaces))))
    (is (= 3 (count spaces)))
    (is (every? #(= 1 (:capacity %)) spaces))
    (is (some? (a/animal-housing (animals p {:sheep 1 :boar 1 :cattle 1}))))
    (is (nil? (a/animal-housing (animals p {:sheep 4}))))
    (is (nil? (a/animal-housing (animals (update p :occupations #(vec (remove #{reformer} %))) {:sheep 1}))))))

(deftest horse-barn-adds-up-to-two-neighboring-stables-to-real-fields
  (doseq [crop [:grain :vegetable] n [0 1 2 3]]
    (let [p (-> (player-with (cid "G" 84))
                (assoc-in [:farm 6] {:type :field :count 0}))
          p (reduce #(assoc-in %1 [:farm %2 :stable] true) p (take n [1 7 11]))
          done (a/sow p [{:cell 6 :crop crop}])]
      (is (= (+ (if (= crop :grain) 3 2) (min 2 n)) (get-in done [:farm 6 :count])))
      (is (= (dec (a/amount p crop)) (a/amount done crop)))))
  (let [p (-> (player-with (cid "G" 84))
              (assoc-in [:card-fields :extra] {:type :field :count 0})
              (assoc-in [:farm 1 :stable] true)
              (assoc-in [:farm 5 :count] 0)
              (assoc-in [:farm 5 :type] :field))
        done (a/sow p [{:cell :extra :crop :grain}])]
    (is (= 3 (get-in done [:card-fields :extra :count])))))

(deftest engineer-grants-food-before-cost-and-uses-printed-stone-cost
  (let [engineer (cid "WM" 17)
        p (player-with engineer)]
    (doseq [[id food] [[:fireplace-2 1] [:stone-oven 2] [(cid "FR" 31) 2]
                       [(cid "G" 93) 1] [(cid "WM" 17) 0]]]
      (let [done (a/improvement-food-before-payment p id)]
        (is (= (+ food (a/amount p :food)) (a/amount done :food)))
        (when (zero? food) (is (= p done)))))
    (let [s (-> (fresh) rich (own 0 engineer))
          done (a/build-improvement s 0 {:improvement :fireplace-2} {:cost {}})]
      (is (= (inc (a/amount (g/p s 0) :food)) (a/amount (g/p done 0) :food))))
    (let [s (-> (fresh) rich (own 0 engineer) (assoc-in [:players 0 :goods :food] 0))
          ;; Engineer's immediate food pays Mountain Cave even from an empty supply.
          hand-card (cid "WM" 95)]
      (is (some? hand-card))
      (is (some #{hand-card} (:minors (g/p (play s hand-card) 0)))))))

(deftest trees-for-citizens-waives-wood-only-with-owned-joinery-or-sawmill
  (let [id (cid "FR" 52) p (player-with)]
    (is (= {:wood 3 :food 1} (a/special-improvement-cost p id {:wood 3 :food 1} {})))
    (is (= {:food 1} (a/special-improvement-cost (update p :improvements conj :joinery) id {:wood 3 :food 1} {})))
    (is (= {:food 1} (a/special-improvement-cost (update p :minors conj :catalog/original-base-k-122-sawmill) id {:wood 3 :food 1} {})))
    (is (= {:wood 3} (a/special-improvement-cost (update p :improvements conj :pottery) id {:wood 3} {})))
    (is (= {:wood 2} (a/special-improvement-cost (update p :improvements conj :joinery) (cid "WM" 84) {:wood 2} {})))))
