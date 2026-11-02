(ns agricola.community-batch-three-test
  (:require [clojure.test :refer :all] [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [agricola.bot :as bot]
            [agricola.community-decks-test :refer [cid fresh rich own play reload resolve! emit op stock field]]
            [eurozone.card-scenarios :as scenarios]))

(defn scenario [id] (scenarios/initial-state id (:names (scenarios/options id "Ada"))))

(deftest businesswoman-keeps-costs-and-allows-two-independent-improvement-actions
  (let [s (scenario "businesswoman")
        first (resolve! s {:improvement :fireplace-2})
        both (resolve! (reload first) {:improvement :hearth-4 :upgrade :fireplace-2})]
    (is (= :card-action (op first)))
    (is (= (- (stock s 0 :clay) 2) (stock both 0 :clay)))
    (is (= #{:hearth-4} (get-in both [:players 0 :improvements])))
    (is (nil? (op both)))
    (is (= 1 (g/acting-player both)))
    (is (nil? (op (-> s (resolve! {:skip? true}) (resolve! {:skip? true}))))))
  (let [s (scenario "businesswoman") minor (cid "G" 110)
        one (resolve! s {:card minor}) two (resolve! one {:card (cid "FR" 7)})]
    (is (= (- (stock s 0 :wood) 1) (stock two 0 :wood)))
    (is (some #{minor} (get-in two [:players 0 :minors])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! one {:card minor})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! (assoc-in s [:players 0 :goods :wood] 0) {:card minor}))))
  (let [s (scenario "businesswoman")]
    (is (nil? (op (-> s (resolve! {:improvement :fireplace-2}) (resolve! {:card (cid "FR" 7)})))))))

(deftest singer-choices-may-match-and-resume-after-save
  (doseq [resources [[:stone :stone] [:wood :reed]]]
    (let [s (scenario "singer") after (reduce #(resolve! (reload %1) {:resource %2}) s resources)]
      (doseq [[resource n] (frequencies resources)] (is (= n (- (stock after 0 resource) (stock s 0 resource)))))
      (is (nil? (op after)))
      (is (= 1 (g/acting-player after)))))
  (is (thrown? clojure.lang.ExceptionInfo (resolve! (scenario "singer") {:resource :grain})))
  (is (thrown? clojure.lang.ExceptionInfo (resolve! (scenario "singer") {:skip? true}))))

(deftest paid-and-free-sowing-reactions-complete-with-one-charge-and-no-extra-worker
  (doseq [[id cost] [["gardening-tools" 1] ["shovel" 0]]]
    (let [s (scenario id) accepted (resolve! (reload s) {})
          done (resolve! (reload accepted) {:plantings [{:cell 0 :crop :grain}]})
          skipped (resolve! s {:skip? true})]
      (is (= :sow (op accepted)))
      (is (= (- (stock s 0 :food) cost) (stock accepted 0 :food)))
      (is (= (- (stock s 0 :food) cost) (stock done 0 :food)))
      (is (= 3 (get-in done [:players 0 :farm 0 :count])))
      (is (= 0 (get-in skipped [:players 0 :farm 0 :count] 0)))
      (is (= (stock s 0 :food) (stock skipped 0 :food)))
      (is (= (get-in s [:players 0 :remaining]) (get-in done [:players 0 :remaining])))
      (is (nil? (op done)))
      (is (some #(bot/preview s 0 %) (bot/decision-candidates s 0)))))
  (let [s (-> (fresh) rich (own 0 (cid "G" 76)) (field 0 nil 0))]
    (is (nil? (op (emit s :sow 0))))
    (is (= :sow-offer (op (emit s :sow 1))))
    (is (nil? (op (emit (field s 0 :grain 3) :sow 1))))
    (is (nil? (op (emit (update-in s [:players 0 :goods] merge {:food 0 :grain 0 :vegetable 0}) :sow 1))))))

(deftest debris-dealer-uses-previous-house-and-owner-house
  (doseq [[owner-house actor previous expected]
          [[:wood 0 :wood {}] [:clay 0 :clay {:clay 2}]
           [:wood 1 :wood {}] [:wood 1 :clay {}]
           [:clay 1 :wood {:wood 2}] [:clay 1 :clay {:clay 2}]
           [:stone 1 :wood {:wood 2}] [:stone 1 :clay {:clay 2}]]]
    (let [s (-> (fresh) (own 0 (cid "WM" 15)) (assoc-in [:players 0 :house] owner-house))
          done (emit s :renovate actor {:previous-house previous})]
      (doseq [r [:wood :clay]] (is (= (get expected r 0) (- (stock done 0 r) (stock s 0 r)))))))
  (let [s (scenario "debris-dealer") done (g/command s 0 {:type :action :space :renovate})]
    (is (= :stone (get-in done [:players 0 :house])))
    (is (= (+ 2 (stock s 0 :clay)) (stock done 0 :clay)))))

(deftest herdsman-changes-choice-at-round-six
  (doseq [round [1 5 6 14]]
    (let [s (-> (fresh) (own 0 (cid "WM" 28)) (assoc :round round))
          after (g/command s 0 {:type :action :space :labor})]
      (if (< round 6)
        (do (is (= 1 (stock after 0 :sheep))) (is (nil? (op after))))
        (do (is (= :choose-good (op after)))
            (doseq [resource [:sheep :boar]]
              (is (= 1 (stock (resolve! (reload after) {:resource resource}) 0 resource))))))))
  (is (thrown? clojure.lang.ExceptionInfo (resolve! (scenario "herdsman") {:resource :cattle}))))

(deftest ruins-stock-is-limited-optional-and-not-triggered-by-manure
  (let [s (scenario "ruins") id (cid "WM" 106)
        taken (resolve! (reload s) {}) skipped (resolve! s {:skip? true})]
    (is (= [:stone :stone :stone :stone] (get-in s [:players 0 :card-state id :stock])))
    (is (= 3 (count (get-in taken [:players 0 :card-state id :stock]))))
    (is (= (inc (stock s 0 :stone)) (stock taken 0 :stone)))
    (is (= (stock s 0 :stone) (stock skipped 0 :stone)))
    (is (= 4 (count (get-in skipped [:players 0 :card-state id :stock]))))
    (is (nil? (op (emit taken :field-phase 0 {:manure? true}))))
    (let [drained (reduce (fn [g _] (resolve! (emit (reload g) :field-phase 0) {})) taken (range 3))]
      (is (empty? (get-in drained [:players 0 :card-state id :stock])))
      (is (nil? (op (emit drained :field-phase 0))))
      (is (= (+ 4 (stock s 0 :stone)) (stock drained 0 :stone))))))

(deftest steamer-pays-pairs-and-counts-as-cooking-without-baking-or-fireplace
  (doseq [[option resource food] [[0 :vegetable 5] [1 :sheep 4] [2 :boar 5] [3 :cattle 6]]]
    (let [id (cid "WM" 108) s (-> (fresh) (own 0 id) (update-in [:players 0] a/gain {:grain 2 resource 2}))
          after (g/command s 0 {:type :ability :card id :option option :times 2})]
      (is (= (* 2 food) (- (stock after 0 :food) (stock s 0 :food))))
      (is (= 0 (stock after 0 :grain))) (is (= 0 (stock after 0 resource)))
      (is (nil? (op after)))
      (is (thrown? clojure.lang.ExceptionInfo (g/command after 0 {:type :ability :card id :option option})))
      (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc s :phase :breeding) 0 {:type :ability :card id :option option})))
      (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc s :phase :finish-round :round 14) 0 {:type :ability :card id :option option})))))
  (let [s (scenario "steamer") id (cid "WM" 108)
        pending (g/command s 0 {:type :ability :card id :option 1}) done (resolve! (reload pending) {:amount 1})]
    (is (= :pelts (op pending)))
    (is (= 1 (get-in done [:players 0 :card-state :catalog/original-base-k-339-pelts :pelts]))))
  (let [id (cid "WM" 108) s (-> (fresh) (own 0 id) (own 0 :catalog/original-base-k-109-spit-roast)
                                                 (assoc :phase :feeding) (update-in [:players 0] a/gain {:grain 2 :sheep 2}))
        first (g/command s 0 {:type :ability :card id :option 1}) second (g/command first 0 {:type :ability :card id :option 1})]
    (is (= 5 (- (stock first 0 :food) (stock s 0 :food))))
    (is (= 4 (- (stock second 0 :food) (stock first 0 :food))))))

(deftest baguette-requires-baking-equipment-grain-and-wood
  (let [s (scenario "baguette") after (resolve! (reload s) {:grain 1}) skipped (resolve! s {:skip? true})]
    (is (= (dec (stock s 0 :wood)) (stock after 0 :wood)))
    (is (= (+ 5 (stock s 0 :food)) (stock after 0 :food)))
    (is (= (stock s 0 :wood) (stock skipped 0 :wood))))
  (let [s (-> (fresh) rich (own 0 (cid "FR" 7)))]
    (is (nil? (op (emit s :feeding-start 0))))
    (let [oven (assoc-in s [:players 0 :improvements] #{:clay-oven})]
      (is (= :bake (op (emit oven :feeding-start 0))))
      (is (nil? (op (emit (assoc-in oven [:players 0 :goods :wood] 0) :feeding-start 0))))
      (is (nil? (op (emit (assoc-in oven [:players 0 :goods :grain] 0) :feeding-start 0)))))))

(deftest barber-shop-counts-only-future-harvests-and-awards-points-once
  (doseq [[round phase started? points] [[1 :work false 6] [4 :work false 6] [4 :return-home false 6]
                                        [4 :return-home true 5] [4 :field-harvest true 5] [4 :feeding true 5]
                                        [8 :work false 4] [14 :work false 1] [14 :feeding true 0]]]
    (let [s (cond-> (assoc (rich (fresh)) :round round :phase phase) started? (g/continue-step :harvest-or-round))
          done (play s (cid "FR" 8))]
      (is (= points (get-in done [:players 0 :bonus-points])) (str [round phase]))))
  (let [s (scenario "barber-shop") done (g/command s 0 {:type :action :space :meeting :card (cid "FR" 8)})]
    (is (= 4 (get-in done [:players 0 :bonus-points])))))

(deftest homework-resolves-nested-choices-before-the-second-occupation
  (let [s (scenario "homework") singer (cid "G" 50) seed (cid "G" 49)
        one (resolve! s {:card singer})
        choices (-> one (resolve! {:resource :wood}) reload (resolve! {:resource :stone}))
        done (resolve! choices {:card seed})]
    (is (= :choose-good (op one)))
    (is (= :card-action (op choices)))
    (is (= [singer seed] (get-in done [:players 0 :occupations])))
    (is (= (stock s 0 :food) (stock done 0 :food)))
    (is (= 1 (g/acting-player done)))
    (is (nil? (op (-> s (resolve! {:skip? true}) (resolve! {:skip? true})))))))

(deftest trailblazer-tests-farm-fields-and-allows-first-field
  (let [s (scenario "trailblazer") done (resolve! (reload s) {:cell 1}) skipped (resolve! s {:skip? true})]
    (is (= [0 1] (a/tiles (g/p done 0) :field)))
    (is (= (dec (stock s 0 :food)) (stock done 0 :food)))
    (is (= [0] (a/tiles (g/p skipped 0) :field)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:cell 14}))))
  (let [s (-> (fresh) rich (own 0 (cid "FR" 113)))]
    (is (= :plow (op (emit s :round-start 0))))
    (is (nil? (op (emit (field s 0 nil 0) :round-start 0))))
    (is (= :plow (op (emit (field s 0 :grain 2) :round-start 0))))
    (is (nil? (op (emit (update-in s [:players 0 :goods] merge {:food 0 :grain 0 :vegetable 0}) :round-start 0))))))

(deftest homework-still-charges-a-cards-additional-cost
  (let [chief :catalog/original-base-e-172-chief
        s (scenarios/put-in-hand (scenario "homework") 0 chief)
        done (resolve! s {:card chief})]
    (is (= (- (stock s 0 :food) 2) (stock done 0 :food)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! (assoc-in s [:players 0 :goods :food] 1) {:card chief}))))
  (let [s (assoc-in (scenario "businesswoman") [:players 0 :hand :minor] [:catalog/original-base-e-18-bean-field])]
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:card :catalog/original-base-e-18-bean-field})))))

(deftest steamer-rewards-other-players-cooking-listeners
  (let [id (cid "WM" 108)
        s (-> (fresh) (own 0 id) (own 1 :catalog/original-base-k-299-slaughterman)
              (update-in [:players 0] a/gain {:grain 2 :sheep 2}))
        done (g/command s 0 {:type :ability :card id :option 1 :times 2})]
    (is (= 1 (- (stock done 1 :food) (stock s 1 :food))))))
