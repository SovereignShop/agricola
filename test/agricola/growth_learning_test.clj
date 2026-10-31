(ns agricola.growth-learning-test
  (:require [clojure.test :refer :all] [agricola.growth-learning :as growth]
            [agricola.simulation :as sim] [agricola.bot :as bot]
            [agricola.engine :as a] [agricola.recording :as rec]))

(deftest growth-features-use-only-public-and-own-information
  (let [s (sim/initial-state {:seed 9501991 :players 2})
        hidden (-> s (assoc-in [:players 1 :hand] {:minor [] :occupation []})
                   (update :round-order #(vec (concat (take (:round s) %) (reverse (drop (:round s) %)))))
                   (assoc :minor-deck [:secret] :minor-order [:secret]))]
    (is (= (growth/features s 0) (growth/features hidden 0)))
    (is (not= (growth/features s 0) (growth/features (assoc-in s [:players 0 :family] 3) 0)))
    (is (not= (growth/features s 0) (growth/features (assoc-in s [:players 0 :goods :food] 10) 0)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"two players"
                          (growth/features (sim/initial-state {:seed 1 :players 3}) 0)))))

(deftest room-gap-ignores-inapplicable-construction-replacements
  (doseq [card [:revised-base/a123 :catalog/original-base-k-272-frame-builder]]
    (let [player (-> (first (:players (a/new-game ["Ada" "Ben"])))
                     (assoc :occupations [card]) (a/gain {:wood 5 :reed 2}))]
      (is (= 0 (growth/room-gap player))))))

(deftest zero-correction-preserves-current-strong-policy
  (let [records (atom [])]
    (with-redefs [sim/choose
                  (fn [state pid _ _]
                    (let [expected (growth/parent-choice state pid)
                          actual (growth/choose state pid nil 1.0 0.0)]
                      (is (= (:command expected) (:command actual)))
                      actual))]
      (sim/run-game {:seed 9501992 :players 2 :mode :random} #(swap! records conj %)))
    (is (= :complete (:status (rec/timeline @records))))))

(deftest room-readiness-reflects-native-costs
  (let [s (sim/initial-state {:seed 9501991 :players 2}) p (get-in s [:players 0])]
    (is (= 7 (growth/room-gap (assoc p :goods {})))))
  (let [s (sim/initial-state {:seed 9501991 :players 2}) p (get-in s [:players 0])]
    (is (zero? (growth/room-gap (assoc p :goods {:wood 5 :reed 2}))))))
