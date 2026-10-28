(ns agricola.learning-rewards-test
  (:require [clojure.test :refer :all] [agricola.learning-rewards :as rewards]
            [agricola.simulation :as sim] [agricola.engine :as a] [agricola.card-rules :as cards]))
(defn initial [] (assoc (sim/initial-state {:seed 991 :players 2 :mode :random}) :round 5))
(deftest private-hands-and-future-round-order-cannot-affect-rewards
  (let [state (initial) changed (-> state (update :round-order reverse)
                                 (assoc-in [:players 0 :hand] {:minor [(cards/id :a002)]})
                                 (assoc-in [:players 1 :hand] {:occupation [(cards/id :a086)]}))]
    (is (= (rewards/observation state) (rewards/observation changed)))))
(deftest terminal-and-hoarding-and-growth
  (let [state (initial) rich (assoc-in state [:players 0 :goods :food] 100)
        grow #(update-in % [:players 0] a/grow false)
        hungry (assoc-in state [:players 0 :goods :food] 0)]
    (is (= 0.0 (rewards/potential (assoc rich :phase :finished) 0)))
    (is (= 0.0 (rewards/potential (assoc rich :phase :draw) 0)))
    (is (= (rewards/potential rich 0) (rewards/potential (assoc-in rich [:players 0 :goods :food] 1000) 0)))
    (is (> (rewards/potential (grow rich) 0) (rewards/potential rich 0)))
    (is (= (rewards/potential (grow hungry) 0) (rewards/potential hungry 0)))
    (is (> (- (rewards/potential (grow rich) 0) (rewards/potential rich 0))
           (- (rewards/potential (grow (assoc rich :round 12)) 0) (rewards/potential (assoc rich :round 12) 0))))
    (is (< (rewards/potential (assoc-in rich [:players 0 :begging] 1) 0) (rewards/potential rich 0)))))
(deftest oven-output-needs-crops-and-respects-batch-limits
  (let [p (assoc (get-in (initial) [:players 0]) :improvements #{:clay-oven})]
    (is (= 0 (rewards/grain-yield-food p 0)))
    (is (= 3 (rewards/grain-yield-food p 1)))
    (is (= 4 (rewards/grain-yield-food p 2)))))
