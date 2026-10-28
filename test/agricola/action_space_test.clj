(ns agricola.action-space-test
  (:require [clojure.test :refer :all] [agricola.action-space :as space]
            [agricola.simulation :as sim] [agricola.recording :as recording]
            [agricola.card-rules :as cards]))
(deftest learning-actions-are-deterministic-and-executable
  (doseq [mode [:beginner :random :draw] n [1 2 4]]
    (let [state (sim/initial-state {:seed 43 :players n :mode mode})
          actions (space/legal-actions state)]
      (is (seq actions))
      (is (= actions (space/legal-actions state)))
      (is (= (count actions) (count (set (map :id actions)))))
      (doseq [action actions]
        (is (map? (recording/apply-command state (sim/actor state) (:command action))))))))
(deftest observation-and-action-mask-hide-unrevealed-information
  (let [state (sim/initial-state {:seed 4 :players 2 :mode :random}) pid (sim/actor state)
        changed (-> state (assoc-in [:players (- 1 pid) :hand] {:minor [(cards/id :a044)] :occupation []})
                    (update :round-order #(into [(first %)] (reverse (rest %)))))]
    (is (= (space/features (space/observation state pid)) (space/features (space/observation changed pid))))
    (is (= (space/legal-actions state) (space/legal-actions changed))))
  (let [state (sim/initial-state {:seed 4 :players 2 :mode :draw}) pid (sim/actor state)
        changed (update-in state [:draw :packets (- 1 pid)] #(vec (reverse %)))]
    (is (= (space/observation state pid) (space/observation changed pid)))))
(deftest action-identities-ignore-transient-decision-numbers
  (is (= (space/action-id {:type :resolve :decision 1 :cell 5})
         (space/action-id {:type :resolve :decision 42 :cell 5})))
  (is (not= (space/action-id {:type :action :space :grain})
            (space/action-id {:type :action :space :wood}))))
