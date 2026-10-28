(ns eurozone.table-layout-test
  (:require [clojure.test :refer :all] [eurozone.table-layout :as l]))
(defn valid-layout? [layout]
  (and (= (:order layout) (vec (mapcat identity (:rows layout))))
       (= (count (:order layout)) (count (set (:order layout))))
       (every? #(and (seq %) (< (abs (- 12 (reduce + (map (fn [id] (l/weight layout id)) %)))) 0.00001)) (:rows layout))))
(deftest legacy-arrangements-preserve-visibility-and-heights
  (let [old {:order ["board" "chat" "farm-1" "farm-0" "log"]
             :widths {"board" 9 "chat" 3 "farm-1" 6 "farm-0" 6 "log" 12}
             :heights {"farm-0" 450}}
        next (l/normalize old)]
    (is (= [["board" "chat"] ["farm-1" "farm-0"] ["log"]] (:rows next)))
    (is (= (:order old) (:order next)))
    (is (= (:heights old) (:heights next)))
    (is (valid-layout? next))))
(deftest docking-splits-or-creates-rows-without-losing-panels
  (doseq [edge [:left :right :top :bottom]]
    (let [s (l/normalize nil) next (l/dock s "chat" "cards" edge)
          row (get-in next [:rows (l/row-index next "chat")])]
      (is (valid-layout? next))
      (is (= (set (:order s)) (set (:order next))))
      (case edge
        :left (is (= ["hand" "chat" "cards"] row))
        :right (is (= ["hand" "cards" "chat"] row))
        (is (= ["chat"] row)))))
  (let [s (l/normalize nil)]
    (is (= s (l/dock s "board" "board" :left)))
    (is (= s (l/dock s "missing" "board" :left)))))
(deftest resize-divider-only-affects-neighbors-and-has-limits
  (let [s (l/normalize nil) next (l/resize-pair s "board" "farm" 7)
        narrow (l/resize-pair s "board" "farm" -100)]
    (is (= 7 (get-in next [:widths "board"])))
    (is (= 3 (get-in next [:widths "farm"])))
    (is (= (get-in s [:widths "chat"]) (get-in next [:widths "chat"])))
    (is (valid-layout? next)) (is (valid-layout? narrow))
    (is (= s (l/resize-pair s "board" "hand" 8)))))
(deftest presets-visibility-and-repeated-edits-preserve-invariants
  (let [s (l/change (l/normalize nil) :all-farms [4])]
    (is (every? (set (:order s)) ["farm-0" "farm-1" "farm-2" "farm-3"]))
    (is (not (some #{"farm"} (:order s))))
    (is (= s (l/change s :all-farms [4])))
    (is (valid-layout? s)))
  (loop [s (l/normalize nil) n 0]
    (when (< n 80)
      (let [order (:order s) source (nth order (mod n (count order))) target (nth order (mod (+ n 2) (count order)))
            next (-> s (l/dock source target (nth [:left :right :top :bottom] (mod n 4)))
                     (l/resize-panel source (inc (mod n 12)) (+ 200 n)))]
        (is (valid-layout? next))
        (is (= (set (:order (l/normalize nil))) (set (:order next))))
        (recur next (inc n))))))
