(ns eurozone.card-values-test
  (:require [clojure.test :refer :all] [eurozone.card-values :as values]
            [eurozone.bot-player :as bot] [agricola.card-rank-experiment :as experiment]
            [agricola.simulation :as sim] [agricola.card-rules :as cards]))

(deftest exported-policy-covers-current-two-player-cards
  (is (= (sim/rules-hash) (:rules-hash values/model)))
  (is (= (set (map :id (filter #(<= (:min-players %) 2) (vals cards/cards))))
         (set (keys (:values values/model)))))
  (is (= 82 (count (:values values/model))))
  (is (nil? (values/expected-value 5 :revised-base/a055))))

(deftest application-draft-policy-matches-tested-formula
  (let [model {"main" (into {} (for [[cid value] (:values values/model)] [(str cid) value]))}]
    (doseq [seed [9521 9522 9523]]
      (loop [state (sim/initial-state {:seed seed :players 2 :mode :draw :extra 2})]
        (when (= :draw (:phase state))
          (let [pid (sim/actor state) observed (bot/observation state pid)
                command (bot/choose observed pid)
                reference (:command (experiment/pick state pid model))]
            (is (= reference command))
            (recur (sim/apply-command state pid command))))))))

(deftest larger-tables-retain-the-existing-draft-policy
  (doseq [players [3 4 5]]
    (let [state (sim/initial-state {:seed 9530 :players players :mode :draw :extra 2}) pid (sim/actor state)]
      (is (= (:command (sim/choose state pid (java.util.Random. 1) :heuristic))
             (bot/choose (bot/observation state pid) pid))))))
