(ns agricola.woolgrower-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.expansion-cards-test :refer [fresh own hand]]))

(defn feed-all [state]
  (reduce #(g/command %1 %2 {:type :feed})
          (assoc state :phase :feeding :turn 0 :fed #{} :bred #{} :effect-queue [])
          (range (count (:players state)))))
(defn capacity [player]
  (reduce + 0 (map :capacity (filter #(= (c/id :a148) (:card %)) (a/accommodations player)))))

(deftest woolgrower-counts-completed-shared-feeding-phases
  (let [state (-> (fresh) (own 0 :a148) (assoc :phase :feeding :fed #{} :bred #{}))
        fed-one (g/command state 0 {:type :feed})
        fed-all (reduce #(g/command %1 %2 {:type :feed}) fed-one [1 2 3])]
    (is (zero? (capacity (g/p state 0))))
    (is (zero? (capacity (g/p fed-one 0))) "A player's own feeding does not finish the shared phase")
    (is (= :breeding (:phase fed-all)))
    (is (= [1 1 1 1] (mapv :completed-feeding-phases (:players fed-all))))
    (is (= 1 (capacity (g/p fed-all 0))))
    (is (thrown? clojure.lang.ExceptionInfo (g/command fed-all 0 {:type :feed}))))
  (let [earlier (nth (iterate feed-all (fresh)) 3)
        state (-> earlier (assoc :phase :work :effect-queue []) (hand :a148))
        played (g/drain (g/play-card state 0 (c/id :a148) 0 {}))]
    (is (= 3 (capacity (g/p played 0))) "Feeding phases before playing the card count too")
    (is (= 4 (capacity (g/p (feed-all (edn/read-string (pr-str played))) 0))))))

(deftest woolgrower-houses-sheep-and-preserves-other-card-compartments
  (doseq [organic? [false true]]
    (let [state (cond-> (-> (fresh) (own 0 :a148) (own 0 :b169)) organic? (own 0 :b098))
          player (-> (g/p state 0)
                     (assoc :completed-feeding-phases 3 :card-animal-capacity 1)
                     (a/gain {:sheep 3 :boar 1 :cattle 1}))
          housed (a/animal-housing player)
          wool (first (filter #(= (c/id :a148) (:card %)) housed))]
      (is (some? housed))
      (is (= [:sheep 3] [(:animal wool) (:count wool)]))
      (is (= 5 (reduce + (map :count housed))))
      (is (nil? (a/animal-housing (a/gain player {:boar 1}))))
      (is (nil? (a/animal-housing (a/gain player {:sheep 1})))))))

(deftest woolgrower-new-capacity-is-usable-during-the-same-harvest
  (let [state (-> (fresh) (own 0 :a148)
                  (assoc-in [:players 0 :completed-feeding-phases] 1)
                  (assoc-in [:players 0 :goods :sheep] 2))
        fed (feed-all state)
        bred (g/command fed 0 {:type :breed :offspring {:sheep 1}})]
    (is (= 2 (capacity (g/p fed 0))))
    (is (= 3 (a/amount (g/p bred 0) :sheep)))
    (is (some? (a/animal-housing (g/p bred 0))))
    (is (thrown? clojure.lang.ExceptionInfo (g/command fed 0 {:type :breed :offspring {}}))
        "Available Woolgrower space makes the sheep birth mandatory")))
