(ns agricola.community-batch-five-test
  (:require [clojure.test :refer :all] [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [agricola.bot :as bot] [eurozone.card-scenarios :as scenarios]
            [agricola.community-decks-test :refer [cid fresh rich own play reload resolve! emit op stock field]]))
(defn scenario [id] (scenarios/initial-state id (:names (scenarios/options id "Ada"))))

(deftest sheep-market-exchanges-once-before-fields-without-cooking-the-sheep
  (let [s (scenario "sheep-market")]
    (is (= :return-home (:phase s)))
    (doseq [[option cost gain] [[0 :grain :sheep] [1 :vegetable :sheep] [2 :sheep :grain] [3 :sheep :vegetable]]]
      ;; A fenced pasture accommodates an incoming sheep without obscuring the exchange.
      (let [s (update-in s [:players 0] a/build-fences (vec (a/edges 0)))
            done (resolve! (reload s) {:option option})]
        (is (= (dec (stock s 0 cost)) (stock done 0 cost)))
        (is (= (inc (stock s 0 gain)) (stock done 0 gain)))
        (is (= :feeding (:phase done)))
        (is (= (stock s 0 :food) (stock done 0 :food)))
        (is (thrown? clojure.lang.ExceptionInfo (resolve! (assoc-in s [:players 0 :goods cost] 0) {:option option})))))
    (is (= (:goods (g/p s 0)) (:goods (g/p (resolve! s {:skip? true}) 0))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:option 4}))))
  (let [s (-> (fresh) rich (own 0 (cid "G" 107)) (field 0 :grain 1)
              (assoc-in [:players 0 :goods :grain] 0))
        pending (emit s :before-field-phase 0)]
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:option 0})))
    (is (nil? (op (emit s :before-field-phase 1))))
    (is (nil? (op (emit s :before-field-phase 0 {:manure? true}))))
    (is (nil? (op (emit (assoc-in s [:players 0 :goods] {}) :before-field-phase 0)))))
  (is (thrown? clojure.lang.ExceptionInfo (play (rich (fresh)) (cid "G" 107)))))

(deftest sheep-market-received-animals-can-be-cooked-when-the-farm-is-full
  (let [s (-> (scenario "sheep-market") (update-in [:players 0 :improvements] conj :fireplace-2))
        received (resolve! s {:option 0})]
    (is (= :housing (op received)))
    (let [done (resolve! received {:keep {:sheep 1 :boar 0 :cattle 0} :cook? true})]
      (is (= 1 (stock done 0 :sheep)))
      (is (= (+ 2 (stock s 0 :food)) (stock done 0 :food))))))

(deftest wooden-bridge-is-specific-to-owner-and-action-space
  (let [s (scenario "wooden-bridge")]
    (doseq [[space food reed] [[:reed 1 1] [:labor 2 1]]]
      (let [done (g/command (reload s) 0 {:type :action :space space})]
        (is (= (+ food (stock s 0 :food)) (stock done 0 :food)))
        (is (= (+ reed (stock s 0 :reed)) (stock done 0 :reed)))
        (is (nil? (op done)))))
    (doseq [[actor space] [[1 :reed] [1 :labor] [0 :resource-market] [0 :fishing]]]
      (is (= (:goods (g/p s 0)) (:goods (g/p (emit s :use-space actor {:space space}) 0)))))
    (is (= (:goods (g/p s 0)) (:goods (g/p (emit s :receive-goods 0 {:goods {:reed 1}}) 0))))))

(deftest edible-roots-rewards-emptying-the-entire-field-including-card-fields
  (let [s (scenario "edible-roots") done (g/command (reload s) 0 {:type :action :space :grain})]
    (is (= (+ 2 (stock s 0 :food)) (stock done 0 :food)))
    (is (= [0 0 2] (mapv #(get-in done [:players 0 :farm % :count]) [0 1 2]))))
  (doseq [[crop amount under preserve expected]
          [[:grain 1 nil false 1] [:vegetable 1 nil false 1] [:wood 1 nil false 1]
           [:grain 2 nil false 0] [:grain 1 {:vegetable 2} false 0]
           [:vegetable 1 nil true 0]]]
    (let [s (-> (fresh) rich (own 0 (cid "WM" 77))
                (assoc-in [:players 0 :card-fields :test-field] (cond-> {:type :field :crop crop :count amount}
                                                               under (assoc :under-crops under)))
                (cond-> preserve (own 0 :catalog/original-base-i-226-gardener)))
          ;; Also exercise vegetable preservation on an actual farm field.
          s (if preserve (-> s (update-in [:players 0] dissoc :card-fields) (field 0 :vegetable 1)) s)
          done (g/drain (g/crop-harvest s [0] {}))]
      (is (= expected (- (stock done 0 :food) (stock s 0 :food))) (str [crop amount under preserve]))))
  (let [s (-> (fresh) rich (own 0 (cid "WM" 77)) (field 0 :grain 1))
        manure (g/drain (g/manure-harvest s 0))
        moved (g/drain (g/run-effect s (g/task 0 (c/id :a058) {:op :take-field-crop :crop :grain} {}) {:cell 0}))]
    (is (= (inc (stock s 0 :food)) (stock manure 0 :food)))
    (is (= (stock s 0 :food) (stock moved 0 :food))))
  (is (thrown? clojure.lang.ExceptionInfo (play (rich (fresh)) (cid "WM" 77)))))

(deftest festival-hall-uses-harvested-grain-once-for-any-building-resource
  (let [s (scenario "festival-hall")]
    (is (= :field-harvest (:phase s)))
    (doseq [[index resource] (map-indexed vector [:wood :clay :reed :stone])]
      (let [done (resolve! (reload s) {:option index})]
        (is (= (dec (stock s 0 :grain)) (stock done 0 :grain)))
        (is (= (inc (stock s 0 resource)) (stock done 0 resource)))
        (is (= (+ 2 (stock s 0 :food)) (stock done 0 :food)))
        (is (= :feeding (:phase done)))))
    (is (= (:goods (g/p s 0)) (:goods (g/p (resolve! s {:skip? true}) 0)))))
  (let [s (-> (fresh) rich (own 0 (cid "WM" 79)))]
    (is (nil? (op (emit s :after-field-phase 0 {:manure? true}))))
    (is (nil? (op (emit (assoc-in s [:players 0 :goods :grain] 0) :after-field-phase 0))))
    (is (nil? (op (emit s :after-field-phase 1)))))
  (is (thrown? clojure.lang.ExceptionInfo (play (rich (fresh)) (cid "WM" 79)))))

(deftest abandoned-willow-removes-only-an-empty-farm-field-preserving-connectivity
  (let [s (scenario "abandoned-willow")]
    (is (= [0 2] (a/removable-field-cells (g/p s 0))))
    (doseq [cell [0 2]]
      (let [done (resolve! (reload s) {:cell cell})]
        (is (= {:type :empty} (get-in done [:players 0 :farm cell])))
        (is (= (+ 4 (stock s 0 :wood)) (stock done 0 :wood)))
        (is (some #{(cid "FR" 1)} (get-in done [:players 1 :hand :minor])))
        (is (not-any? #{(cid "FR" 1)} (c/owned (g/p done 0))))))
    (doseq [cell [1 5 14 :test-field nil]]
      (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:cell cell}))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:skip? true})))
    (is (some #(= 0 (:cell %)) (bot/decision-candidates s 0)))
    (is (not-any? #(= 1 (:cell %)) (bot/decision-candidates s 0))))
  (let [s (-> (fresh) rich (field 0 :grain 1) (field 1 nil 0) (field 2 :vegetable 1))]
    (is (thrown? clojure.lang.ExceptionInfo (play s (cid "FR" 1))))
    (is (thrown? clojure.lang.ExceptionInfo (play (assoc-in s [:players 0 :farm 1 :under-crops] {:grain 1}) (cid "FR" 1)))))
  (let [s (-> (fresh) rich (assoc-in [:players 0 :card-fields :test-field] {:type :field :count 0}))]
    (is (thrown? clojure.lang.ExceptionInfo (play s (cid "FR" 1)))))
  (let [s (-> (fresh) rich (field 0 nil 0) (play (cid "FR" 1))) done (resolve! s {:cell 0})]
    (is (empty? (a/tiles (g/p done 0) :field)))
    (is (= :field (get-in (g/command done 0 {:type :action :space :plow :cell 0}) [:players 0 :farm 0 :type])))))

(deftest powerhouse-checks-supply-at-work-start-after-scheduled-income
  (doseq [[stone expected] [[0 0] [2 0] [3 1] [4 1] [5 2] [8 2]]]
    (let [s (assoc-in (scenario "powerhouse") [:players 0 :goods :stone] stone)
          done (g/command (reload s) 0 {:type :action :space :grain})]
      (is (= 2 (:round done)))
      (is (= expected (- (stock done 0 :food) (stock s 0 :food))))))
  (let [s (-> (scenario "powerhouse") (assoc-in [:players 0 :goods :stone] 4)
              (own 0 (cid "G" 51))
              (g/schedule 0 (cid "G" 51) {:rounds [2] :goods {:stone 1}}))
        done (g/command s 0 {:type :action :space :grain})]
    (is (= 5 (stock done 0 :stone)))
    (is (= (+ 2 (stock s 0 :food)) (stock done 0 :food)))
    (is (= (:goods (g/p s 0)) (:goods (g/p (emit s :work-start 1) 0))))
    (is (= (:goods (g/p s 0)) (:goods (g/p (emit s :receive-goods 0 {:goods {:stone 1}}) 0))))))

(deftest powerhouse-allows-politician-before-the-work-bonus
  (let [s (scenario "powerhouse-politician")
        politician (cid "WM" 41) cmd {:type :ability :card politician :option 0}
        traded (g/command (reload s) 0 cmd) done (resolve! traded {})
        continued (resolve! (reload s) {})]
    (is (= 2 (:round s)))
    (is (= :preparation (:phase s)))
    (is (= :ability-window (op s)))
    (is (= 5 (stock traded 0 :stone)))
    (is (= (dec (stock s 0 :reed)) (stock traded 0 :reed)))
    (is (= (stock s 0 :food) (stock traded 0 :food)))
    (is (= (+ 2 (stock s 0 :food)) (stock done 0 :food)))
    (is (= (inc (stock s 0 :food)) (stock continued 0 :food)))
    (is (= :work (:phase done)))
    (is (nil? (op done)))
    (is (= 1 (g/exchange-remaining done (g/p done 0) politician)))
    (let [twice (g/command traded 0 cmd)]
      (is (thrown? clojure.lang.ExceptionInfo (g/command twice 0 cmd)))))
  (let [s (-> (scenario "powerhouse") (own 0 (cid "WM" 41))
              (assoc-in [:players 0 :goods :stone] 2)
              (assoc-in [:players 0 :card-state (cid "WM" 41)] {:exchange-round 1 :exchange-used 2}))
        pending (g/command s 0 {:type :action :space :grain})
        traded (g/command pending 0 {:type :ability :card (cid "WM" 41) :option 0})]
    (is (= :ability-window (op pending)))
    (is (= (inc (stock s 0 :food)) (stock (resolve! traded {}) 0 :food)))))
