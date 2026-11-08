(ns agricola.community-sixteen-combinations-test
  (:require [clojure.test :refer :all]
            [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [eurozone.card-scenarios :as s]
            [agricola.community-decks-test :refer [fresh own cid resolve! reload op stock]]))

(defn table [id] (s/initial-state id (:names (s/options id "Ada"))))
(def cat-lover :catalog/original-expansion-fr-69-cat-lover)
(def contractor :catalog/original-expansion-wm-11-contractor)
(def pelt :catalog/original-expansion-g-91-pelt-roof)
(def branch :catalog/original-expansion-wm-7-branch-collector)
(def timetable :catalog/original-expansion-fr-51-timetable)
(def rotator :catalog/original-expansion-wm-14-crop-rotator)

(deftest three-card-room-payment-combines-discounts-substitution-and-reserved-reed
  (let [state (-> (fresh) (own 0 cat-lover) (own 0 contractor) (own 0 pelt)
                  (assoc-in [:players 0 :goods] {:wood 4 :food 1 :sheep 1})
                  (assoc-in [:players 0 :card-state pelt :stored-goods] {:reed 1}))
        options {:cat-lover-resources {:wood 1} :contractor-resource :reed}
        p (g/p state 0)
        result (g/command (reload state) 0 (merge {:type :action :space :build :rooms [0]} options))]
    (is (= {:wood 4 :reed 1 :food 1}
           (into {} (filter (comp pos? val) (:cost (a/room-construction-payment p [0] options {:action-space? true}))))))
    (is (= 3 (count (a/tiles (g/p result 0) :room))))
    (is (every? zero? (map #(stock result 0 %) [:wood :reed :food])))
    (is (= 1 (stock result 0 :sheep)))
    (is (zero? (a/pelt-roof-reed (g/p result 0))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/command state 0 (merge {:type :action :space :build :rooms [0] :pelt-roof-reed 0} options))))))

(deftest timetable-payment-is-real-clay-payment-but-a-discount-is-not
  (let [state (-> (table "timetable") (own 0 branch))
        command {:type :action :space :meeting :card :catalog/original-expansion-g-110-shovel}
        result (g/command (reload state) 0 command)
        discounted (own state 0 :catalog/original-base-i-243-bricklayer)
        discounted-result (g/command discounted 0 command)]
    (is (= 1 (stock result 0 :wood)) "Clay paid from Timetable still triggers Branch Collector.")
    (is (zero? (get-in result [:players 0 :card-state timetable :stored-goods :clay])))
    (is (zero? (stock discounted-result 0 :wood)) "The only clay was discounted away, so no Branch Collector income.")
    (is (= 1 (get-in discounted-result [:players 0 :card-state timetable :stored-goods :clay])))))

(deftest harvest-feast-empties-fields-and-offers-rotation-without-harvest-income
  (let [state (-> (table "harvest-feast") (own 0 rotator)
                  (assoc-in [:players 0 :g-harvest-count] 0) g/drain)
        grain (get-in state [:players 0 :farm 0 :count])
        food (stock state 0 :food)
        vegetable (stock state 0 :vegetable)
        removed (resolve! (reload state) {:takes {0 {:grain grain}}})
        rotated (resolve! removed {})
        declined (resolve! (reload removed) {:skip? true})]
    (is (= :wm-rotate (op removed)))
    (is (= 0 (g/acting-player removed)) "Rotation interrupts before the next player's Feast.")
    (is (= :vegetable (get-in rotated [:players 0 :farm 0 :crop])))
    (is (= 2 (get-in rotated [:players 0 :farm 0 :count])))
    (is (= (dec vegetable) (stock rotated 0 :vegetable)))
    (is (= food (stock rotated 0 :food)))
    (is (zero? (get-in rotated [:players 0 :g-harvest-count])))
    (is (zero? (get-in declined [:players 0 :farm 0 :count])))
    (is (= vegetable (stock declined 0 :vegetable)))
    (is (not= :wm-rotate (op (g/drain declined))))))
