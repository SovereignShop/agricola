(ns agricola.action-rank-test
  (:require [clojure.test :refer :all] [agricola.action-rank :as rank]
            [agricola.growth-learning :as growth] [agricola.simulation :as sim]
            [agricola.recording :as rec]))

(deftest rank-features-respect-hidden-information
  (let [s (sim/initial-state {:seed 10300001 :players 2})
        hidden (-> s (assoc-in [:players 1 :hand] {:minor [:secret] :occupation [:secret]})
                   (assoc :minor-deck [:secret])
                   (update :round-order #(vec (concat (take (:round s) %) (reverse (drop (:round s) %))))))]
    (is (= (rank/features s 0) (rank/features hidden 0)))
    (is (not= (rank/features s 0) (rank/features (assoc-in s [:players 0 :goods :food] 9) 0)))))

(deftest zero-network-and-late-fallback-preserve-parent
  (let [records (atom []) model {"names" [] "members" [{"linear" [] "hidden" [] "bias" [] "out" []}]}]
    (with-redefs [sim/choose
                  (fn [state pid _ _]
                    (let [expected (growth/parent-choice state pid)
                          actual (rank/choose state pid model 1.0 1.0)]
                      (is (= (:command expected) (:command actual)))
                      (when-not (growth/eligible? state)
                        (is (= (:command expected) (:command (rank/choose state pid model 3.0 1.0)))))
                      actual))]
      (sim/run-game {:seed 10300002 :players 2 :mode :random} #(swap! records conj %)))
    (is (= :complete (:status (rec/timeline @records))))))
