(ns agricola.card-rank-experiment-test
  (:require [clojure.test :refer :all] [agricola.card-rank-experiment :as rank]
            [agricola.simulation :as sim] [agricola.draw :as draw]))

(deftest conditional-rank-uses-own-selections-and-only-legal-picks
  (let [state (sim/initial-state {:seed 9041 :players 2 :mode :draw :extra 2})
        [a b c] (draw/legal-cards (:draw state) 0)
        model {"main" {(str a) 2.0 (str b) 1.0}
               "pairs" {(str b) {(str c) 8.0}}}
        choose #(get-in (rank/pick % 0 model) [:command :card])]
    (is (= a (choose state)))
    (is (= a (choose (-> state (assoc-in [:draw :selected 1 :minor] [c])
                         (assoc-in [:players 1 :hand] {:minor [b]}) (update :round-order reverse)))))
    (is (= b (choose (assoc-in state [:draw :selected 0 :occupation] [c]))))
    (let [chosen (rank/pick state 0 model)]
      (is (= (get-in state [:draw :wave]) (get-in chosen [:command :wave])))
      (is (map? (draw/pick (:draw state) 0 (get-in chosen [:command :wave]) (get-in chosen [:command :card])))))))
