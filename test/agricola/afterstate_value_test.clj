(ns agricola.afterstate-value-test
  (:require [clojure.test :refer :all] [agricola.afterstate-value :as value]
            [agricola.bot :as bot] [agricola.simulation :as sim]
            [agricola.engine :as engine] [agricola.card-rules :as cards]))

(deftest public-information-only
  (let [state (sim/initial-state {:seed 90412 :players 2})
        altered (-> state (assoc-in [:players 1 :hand] {:occupation [] :minor []})
                    (update :round-order #(vec (concat (take (:round state) %) (reverse (drop (:round state) %)))))
                    (assoc :minor-deck [:secret] :minor-order [:secret]))]
    (is (= (value/features state 0) (value/features altered 0)))
    (is (not= (value/features state 0) (value/features (assoc-in state [:players 0 :hand] {}) 0)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"two players"
                          (value/features (sim/initial-state {:seed 1 :players 3}) 0)))))

(deftest zero-blend-reproduces-heuristic-and-guard-limits-regret
  (let [state (sim/initial-state {:seed 90231 :players 2}) pid (sim/actor state)
        original (bot/choose state pid (java.util.Random. 17))
        unchanged (value/choose state pid (java.util.Random. 17) {"weights" {}} 1.0 0.0)
        adversarial (value/choose state pid (java.util.Random. 17)
                                 {"weights" {"base/own/food" -1000000.0 "base/own/family" -1000000.0}} 0.5 1.0)]
    (is (= (:command original) (:command unchanged)))
    (is (= (:value original) (:value unchanged)))
    (is (>= (:value adversarial) (- (:value original) 0.525)))
    (is (map? (sim/apply-command state pid (:command adversarial))))))

(deftest pairing-changes-when-a-card-becomes-active
  (let [state (sim/initial-state {:seed 90412 :players 2})
        [a b] (get-in state [:players 0 :hand :occupation])
        key (str "pair/" (first (sort [a b])) "/" (second (sort [a b])))
        played (-> state (update-in [:players 0 :hand :occupation] #(vec (remove #{a} %)))
                   (update-in [:players 0 :occupations] conj a))]
    (is (= (* 4 (get (value/features state 0) key)) (get (value/features played 0) key)))))
