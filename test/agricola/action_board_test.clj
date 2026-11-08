(ns agricola.action-board-test
  (:require [clojure.test :refer :all] [agricola.engine :as a] [agricola.action-board :as board]))
(defn game [n round]
  (let [g (a/new-game (mapv str (range n)) (vec (mapcat identity a/stages)) 0)]
    (assoc g :variant :cards :round round :active (into (a/base-actions n :cards) (take round (:round-order g))))))
(deftest fishing-neighbours-follow-the-actual-round-four-card
  (doseq [n (range 1 6)]
    (let [g (game n 4) r4 (nth (:round-order g) 3)]
      (is (= #{:reed :labor r4} (board/adjacent-spaces g :fishing)))
      (is (board/adjacent? g r4 :fishing))
      (is (= #{:reed :labor} (board/adjacent-spaces (game n 3) :fishing))))))
(deftest printed-boards-are-not-virtual-card-actions
  (let [g (game 5 14)]
    (is (= :actions (board/space-board g :lessons-copse)))
    (is (= :actions (board/space-board g :build)))
    (is (= :early-rounds (board/space-board g :wood)))
    (is (= :early-rounds (board/space-board g (nth (:round-order g) 6))))
    (is (= :late-rounds (board/space-board g (nth (:round-order g) 7))))
    (is (nil? (board/space-board g :catalog/original-expansion-g-96-private-school)))
    (is (empty? (board/adjacent-spaces g :catalog/original-base-e-164-master-forester)))))
(deftest adjacency-is-symmetric-and-not-diagonal
  (doseq [n (range 1 6) round (range 1 15) :let [g (game n round)]
          space (:active g) neighbour (board/adjacent-spaces g space)]
    (is (board/adjacent? g neighbour space)))
  (let [g (game 4 14)]
    (is (not (board/adjacent? g :fishing :lessons)))
    (is (not (board/adjacent? g (nth (:round-order g) 1) (nth (:round-order g) 5))))))
