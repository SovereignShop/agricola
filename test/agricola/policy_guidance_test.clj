(ns agricola.policy-guidance-test
  (:require [clojure.test :refer :all] [agricola.policy-guidance :as guidance]
            [agricola.action-space :as space] [agricola.simulation :as sim]
            [agricola.recording :as recording] [agricola.card-rules :as cards]))
(deftest advice-does-not-inspect-opponent-hands-or-future-rounds
  (let [state (sim/initial-state {:seed 49 :players 2 :mode :random})
        pid (sim/actor state) actions (space/legal-actions state)
        changed (-> state
                    (assoc-in [:players (- 1 pid) :hand] {:minor [(cards/id :a044)] :occupation []})
                    (update :round-order #(into [(first %)] (reverse (rest %)))))]
    (is (= (guidance/evaluate state actions) (guidance/evaluate changed actions)))))
(deftest advice-agrees-with-existing-teacher-through-a-real-opening
  (loop [state (sim/initial-state {:seed 50 :players 2 :mode :random}) n 0]
    (when (< n 35)
      (let [actions (space/legal-actions state) advice (guidance/evaluate state actions)
            expert (space/expert-index state actions)]
        (is (= expert (:expert advice)))
        (is (= (count actions) (count (:values advice))))
        (is (= (apply max (:values advice)) (nth (:values advice) expert)))
        (recur (recording/apply-command state (sim/actor state) (:command (nth actions expert))) (inc n))))))
