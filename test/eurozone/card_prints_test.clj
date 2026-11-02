(ns eurozone.card-prints-test
  (:require [clojure.test :refer :all] [eurozone.card-prints :as prints]
            [agricola.card-rules :as cards]))
(deftest every-implemented-card-has-a-bundled-sourced-print
  (let [report (prints/audit)]
    (is (= (:implemented report) (:covered report)))
    (is (empty? (:missing report)) (pr-str (:missing report)))
    (is (empty? (:broken report)) (pr-str (:broken report)))
    (is (empty? (:bad-sprites report)))))
(deftest new-implementation-without-print-is-rejected
  (with-redefs [cards/cards (assoc cards/cards :test/unprinted {:name "Unprinted"})]
    (is (some #{:test/unprinted} (:missing (prints/audit))))))
