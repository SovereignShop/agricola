(ns agricola.community-batch-fourteen-test
  (:require [clojure.test :refer :all] [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [agricola.card-expressions :as expr]
            [eurozone.card-scenarios :as scenarios]
            [agricola.community-decks-test :refer [cid fresh rich own play reload resolve! emit op stock field]]))
(defn scenario [id] (scenarios/initial-state id (:names (scenarios/options id "Ada"))))
(defn effect [s card operation context] (g/drain (g/queue-front s [(g/task 0 card operation context)])))
(defn at-round [s n] (g/drain (g/begin-round (assoc s :round (dec n)))))
(deftest fish-seller-cannot-spend-card-bonus-food-or-more-than-two-items
  (let [s (-> (fresh) rich (own 0 (cid "G" 17))) pending (emit s :use-space 0 {:space :fishing :source-goods {:food 2}})
        done (resolve! pending {:goods [:wood :reed]})]
    (is (= :fish-seller (op pending)))
    (is (= (- (stock s 0 :food) 2) (stock done 0 :food)))
    (is (= (inc (stock s 0 :reed)) (stock done 0 :reed)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:goods [:vegetable]})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:goods [:wood :wood :wood]})))
    (is (= (:goods (g/p s 0)) (:goods (g/p (resolve! pending {:skip? true}) 0))))))
(deftest bartering-hut-allows-mixed-resource-payments-and-passes-after-resolution
  (let [s (-> (fresh) (assoc-in [:players 0 :goods :wood] 1) (assoc-in [:players 0 :goods :clay] 1))
        pending (play s (cid "G" 64)) done (resolve! (reload pending) {:animals [:sheep] :payment {:wood 1 :clay 1}})]
    (is (= :bartering-hut (op pending)))
    (is (= 1 (stock done 0 :sheep)))
    (is (= 0 (stock done 0 :wood)))
    (is (some #{(cid "G" 64)} (get-in done [:players 1 :hand :minor])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:animals [:sheep] :payment {:wood 1}})))))
(deftest paddocks-requires-printed-prerequisites-and-subdivides-only-existing-pastures
  (is (thrown? clojure.lang.ExceptionInfo (play (rich (fresh)) (cid "G" 90))))
  (let [s (scenario "paddocks") player (g/p s 0) plan (first (g/paddocks-plans player)) done (resolve! s {:fences plan})]
    (is (= :build-fences (op s))) (is (seq plan))
    (is (> (count (a/pastures (g/p done 0))) (count (a/pastures player))))
    (is (= (stock s 0 :wood) (stock done 0 :wood)))
    (is (some #{(cid "G" 90)} (get-in done [:players 1 :hand :minor])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:fences (vec (a/edges 14))})))))
(deftest cowboy-reuses-occupied-animal-action-for-one-animal-only
  (let [s (scenario "cowboy") s (assoc-in s [:piles :cattle] {:cattle 3})
        before (stock s 0 :cattle) owners (g/space-owners s :cattle) done (g/command s 0 {:type :action :space :cattle})]
    (is (= (inc before) (stock done 0 :cattle)))
    (is (= {:cattle 3} (get-in done [:piles :cattle])) "Cowboy takes its animal from supply, not any newly accumulated pile.")
    (is (= (inc (count owners)) (count (g/space-owners done :cattle))))))
(deftest hunter-can-cook-new-animals-before-housing-and-never-old-stock
  (let [s (-> (rich (fresh)) (own 0 (cid "WM" 32)) (assoc-in [:players 0 :goods :sheep] 1))
        pending (effect s nil {:op :gain :goods {:boar 2}} {}) done (resolve! pending {:goods {:boar 2}})]
    (is (= :hunter (op pending)))
    (is (= (+ (stock s 0 :food) 6) (stock done 0 :food)))
    (is (= 1 (stock done 0 :sheep))) (is (= 0 (stock done 0 :boar)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:goods {:sheep 1}})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:goods {:boar 3}})))))
(deftest pawned-improvements-retain-effects-but-lose-points-and-cannot-be-returned
  (let [s (-> (rich (fresh)) (own 0 (cid "WM" 39)) (a/build-improvement 0 {:improvement :fireplace-2})) done (g/command s 0 {:type :ability :card (cid "WM" 39) :improvement :fireplace-2})]
    (is (= (+ 3 (stock s 0 :food)) (stock done 0 :food)))
    (is (= (dec (get-in (g/score s 0) [:breakdown :improvements])) (get-in (g/score done 0) [:breakdown :improvements])))
    (is (= 2 (:sheep (a/cook-rates (g/p done 0)))))
    (is (thrown? clojure.lang.ExceptionInfo (g/command done 0 {:type :ability :card (cid "WM" 39) :improvement :fireplace-2})))
    (is (thrown? clojure.lang.ExceptionInfo (g/remove-owned-improvement (g/p done 0) :fireplace-2)))))
(deftest village-fool-keeps-hidden-identities-out-of-public-state-and-scores-skipped-tiers
  (let [s (scenario "village-fool") picked (first (get-in s [:players 0 :hand :occupation])) done (resolve! s {:card picked}) card (cid "WM" 53)]
    (is (= 1 (get-in done [:players 0 :card-state card :hidden-count])))
    (is (not-any? #{picked} (get-in done [:players 0 :hand :occupation])))
    (is (not-any? #{picked} (vals (get-in done [:players 0 :card-state card]))))
    (doseq [[n points] [[1 1] [2 2] [3 2] [4 3] [10 6]]]
      (is (= points (g/card-bonus (assoc-in done [:players 0 :card-state card :hidden-count] n) 0 card))))))
(deftest bonfire-marks-used-space-and-conversion-expires-next-round
  (let [s (scenario "bonfire") done (resolve! s {:cells [0]}) card (cid "WM" 64)
        lit (g/command done 0 {:type :ability :card card})]
    (is (not (a/empty-cell? (g/p done 0) 0)))
    (is (a/empty-cell? (g/p lit 0) 0))
    (is (= 3 (:boar (a/cook-rates (g/p lit 0)))))
    (is (nil? (:temporary-animal-cooking (g/p (at-round lit (inc (:round lit))) 0))))))
(deftest raspberries-rewards-used-spaces-once-and-never-makes-marked-space-used
  (let [s (scenario "raspberries") done (resolve! s {:cells [0 1]})
        plowed (emit (assoc-in done [:players 0 :farm 0] {:type :field :count 0}) :check-state 0) again (emit plowed :check-state 0)]
    (is (a/empty-cell? (g/p done 0) 0))
    (is (= (inc (stock done 0 :food)) (stock plowed 0 :food)))
    (is (= (stock plowed 0 :food) (stock again 0 :food)))
    (is (= #{1} (get-in plowed [:players 0 :card-state (cid "WM" 103) :marked-cells])))))
(deftest storage-shed-grants-building-resource-before-minor-payment-and-reserves-stable
  (let [s (scenario "storage-shed") done (resolve! s {:cell 0})
        zero (assoc-in done [:players 0 :goods :reed] 0)
        after (play zero (cid "WM" 104) {:storage-shed-resource :reed})]
    (is (= 3 (a/stables-available (g/p done 0))))
    (is (= 1 (expr/evaluate done 0 {} [:stables])))
    (is (= 0 (stock after 0 :reed)))
    (is (some #{(cid "WM" 104)} (c/owned (g/p after 0))))))
(deftest wine-closet-uses-real-future-round-markers-and-preserves-bonus-points
  (let [s (scenario "wine-closet") card (cid "WM" 114)
        sent (g/command s 0 {:type :ability :card card :round (+ 5 (:round s))})
        pending (at-round sent (+ 5 (:round s))) done (resolve! pending {:points 1})]
    (is (= {:vegetable 2} (get-in (g/future-round-resources sent) [(+ 5 (:round s)) 0 :goods])))
    (is (thrown? clojure.lang.ExceptionInfo (g/command sent 0 {:type :ability :card card :round 14})))
    (is (= 1 (get-in done [:players 0 :bonus-points])))
    (is (= (+ 2 (stock pending 0 :food)) (stock done 0 :food)))
    (is (= (stock sent 0 :vegetable) (stock done 0 :vegetable)))))
(deftest boatswain-is-not-sowing-and-only-plants-physical-empty-fields
  (let [s (scenario "boatswain") done (resolve! s {:cells [0 1]})]
    (is (= 1 (get-in done [:players 0 :farm 0 :count])))
    (is (= :grain (get-in done [:players 0 :farm 1 :crop])))
    (is (= (stock s 0 :grain) (stock done 0 :grain)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:cells [5]})))))
(deftest round-resource-receipts-include-deliveries-and-harvests
  (let [s (-> (rich (fresh)) (own 0 (cid "FR" 81)) (own 0 (cid "FR" 91)))
        next (at-round s 2) no-receipt (resolve! (emit next :round-end 0) {})]
    (is (= (:received (g/p s 0)) (get-in next [:players 0 :round-received-baseline])))
    (is (= (+ (stock next 0 :wood) 2) (stock no-receipt 0 :wood)))
    (is (= (inc (stock next 0 :food)) (stock no-receipt 0 :food)))
    (let [received (update-in next [:players 0] a/gain {:clay 3}) done (resolve! (emit received :round-end 0) {})]
      (is (= (inc (stock received 0 :clay)) (stock done 0 :clay)))
      (is (= (stock received 0 :wood) (stock done 0 :wood))))))
(deftest mastermind-counts-each-play-not-unique-owned-cards
  (let [s (-> (rich (fresh)) (own 0 (cid "FR" 93)))
        once (emit s :build-improvement 0 {:improvement :joinery}) twice (emit once :build-improvement 0 {:improvement :joinery})]
    (is (= 2 (g/card-bonus twice 0 (cid "FR" 93))))
    (is (= 0 (g/card-bonus (emit s :occupation 0 {:card (cid "FR" 93)}) 0 (cid "FR" 93))))
    (is (c/bonus-symbol? :catalog/original-base-i-100-tavern))
    (is (not (c/bonus-symbol? :clay-oven)))))
(deftest sailboat-prices-rise-across-harvests-and-stacks-cannot-be-rebought
  (let [s (scenario "sailboat-constructor") card (cid "FR" 106) done (resolve! s {:stacks [:wood :stone]})
        later (emit done :field-phase 0) bought (resolve! later {:stacks [:crops]})]
    (is (= (- (stock s 0 :food) 5) (stock done 0 :food)))
    (is (= (- (stock done 0 :food) 4) (stock bought 0 :food)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! later {:stacks [:wood]})))
    (is (= [:clay] (g/sailboat-available bought 0 card)))))
(deftest stroller-keeps-six-item-order-and-needs-all-four-types
  (let [s (scenario "stroller") card (cid "FR" 110) done (resolve! s {:resources [:stone :stone :reed :clay :wood :wood]})
        one (emit done :field-phase 0) two (emit one :field-phase 0)]
    (is (= (+ (stock done 0 :stone) 2) (stock two 0 :stone)))
    (is (= [:reed :clay :wood :wood] (get-in two [:players 0 :card-state card :resource-stack])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:resources [:wood :wood :wood :clay :reed :reed]})))))

(deftest paddocks-allows-hedge-keepers-additional-fences-within-the-existing-pasture
  (let [region [1 2 3 6 7 8]
        border (vec (map key (filter #(= 1 (val %)) (frequencies (mapcat a/edges region)))))
        s (-> (rich (fresh)) (update-in [:players 0] a/build-fences border)
              (own 0 :catalog/original-base-e-175-hedge-keeper))
        pending (play s (cid "G" 90)) edges ["v:0:2" "v:1:2" "v:0:3" "v:1:3" "h:1:1"]
        done (resolve! pending {:fences edges})]
    (is (= 5 (g/paddocks-limit (g/p pending 0))))
    (is (= 15 (count (:fences (g/p done 0)))))
    (is (= (stock pending 0 :wood) (stock done 0 :wood)))
    (is (= 4 (count (a/pastures (g/p done 0)))))))
