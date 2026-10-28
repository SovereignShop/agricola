(ns agricola.growth-value-test
  (:require [clojure.test :refer :all] [agricola.growth-value :as value]
            [agricola.growth-learning :as growth] [eurozone.play-values :as parent]
            [agricola.simulation :as sim] [agricola.recording :as rec]))

(deftest augmented-features-preserve-privacy
  (let [state (sim/initial-state {:seed 9900991 :players 2})
        hidden (-> state (assoc-in [:players 1 :hand] {})
                   (update :round-order #(vec (concat (take (:round state) %) (reverse (drop (:round state) %))))))]
    (is (= (value/features state 0) (value/features hidden 0))))
  (let [state (assoc (sim/initial-state {:seed 9900991 :players 2}) :round 11)
        pid (sim/actor state)]
    (is (= (:command (growth/parent-choice state pid))
           (:command (value/choose state pid {"weights" {"base/own/family" -1.0e9}} 1.0))))))

(deftest unchanged-prior-preserves-complete-parent-game
  (let [events (atom [])]
    (with-redefs [sim/choose
                  (fn [state pid _ _]
                    (let [expected (growth/parent-choice state pid)
                          actual (value/choose state pid parent/model 1.0)]
                      (is (= (:command expected) (:command actual))) actual))]
      (sim/run-game {:seed 9900992 :players 2 :mode :random} #(swap! events conj %)))
    (is (= :complete (:status (rec/timeline @events))))))
