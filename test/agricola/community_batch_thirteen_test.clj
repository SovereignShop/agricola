(ns agricola.community-batch-thirteen-test
  (:require [clojure.test :refer :all] [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [agricola.card-expressions :as expr]
            [eurozone.card-scenarios :as scenarios]
            [agricola.community-decks-test :refer [cid fresh rich own play reload resolve! emit op stock field]]))
(defn scenario [id] (scenarios/initial-state id (:names (scenarios/options id "Ada"))))
(defn effect [s card operation context] (g/drain (g/queue-front s [(g/task 0 card operation context)])))
(defn at-round [s n] (g/drain (g/begin-round (assoc s :round (dec n)))))

(deftest resource-collector-preserves-order-including-late-round-choice
  (let [s (assoc (rich (fresh)) :round 12) card (cid "G" 47) pending (play s card)
        done (resolve! (reload pending) {:resources [:stone :reed :wood :clay]})]
    (is (= :schedule-resource-order (op pending)))
    (is (= {:stone 1} (get-in done [:players 0 :schedule 13 0 :goods])))
    (is (= {:reed 1} (get-in done [:players 0 :schedule 14 0 :goods])))
    (is (nil? (get-in done [:players 0 :schedule 15])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:resources [:wood :wood :reed :stone]})))
    (is (= (inc (stock s 0 :stone)) (stock (at-round done 13) 0 :stone)))))

(deftest hammer-pays-each-fenced-opponent-and-passes-only-after-fencing
  (let [s (scenario "hammer") card (cid "G" 79) before (mapv #(a/amount % :wood) (:players s))
        recipients (set (for [p (:players s) :when (and (not= 0 (:id p)) (seq (:fences p)))] (:id p)))
        done (resolve! s {:fences (vec (a/edges 0))})]
    (is (= :build-fences (op s)))
    (is (= (- (before 0) 4 (count recipients)) (stock done 0 :wood)))
    (doseq [pid (range 1 (count (:players s)))] (is (= (+ (before pid) (if (recipients pid) 1 0)) (stock done pid :wood))))
    (is (some #{card} (get-in done [:players 1 :hand :minor])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! (assoc-in s [:players 0 :goods :wood] 4) {:fences (vec (a/edges 0))}))))
  (is (thrown? clojure.lang.ExceptionInfo (play (assoc-in (fresh) [:players 0 :goods :wood] 0) (cid "G" 79)))))

(deftest understudy-shares-once-but-not-with-newborn-or-an-empty-pile
  (let [s (scenario "understudy") used (g/command s 0 {:type :action :space :plow :cell 1})]
    (is (= [0 0] (get-in used [:occupied :plow])))
    (is (nil? (g/occupied-permission used 0 :plow)))
    (is (nil? (g/occupied-permission (assoc-in s [:newborn-spaces :plow] [0]) 0 :plow)))
    (is (nil? (g/occupied-permission (assoc-in s [:occupied :plow] 1) 0 :plow))))
  (let [s (-> (rich (fresh)) (own 0 (cid "G" 56)) (assoc-in [:occupied :wood] 0) (assoc-in [:piles :wood] {:wood 0}))]
    (is (thrown? clojure.lang.ExceptionInfo (g/command s 0 {:type :action :space :wood})))))

(deftest stable-conversions-require-real-stables-and-completable-construction
  (let [s (scenario "remodeling") pending (resolve! s {:stables [0]}) done (resolve! pending {:rooms [0]})]
    (is (= :build-rooms (op pending)))
    (is (= :room (get-in done [:players 0 :farm 0 :type])))
    (is (not (get-in done [:players 0 :farm 0 :stable])))
    (is (= (:goods (g/p s 0)) (:goods (g/p done 0))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:stables [2]}))))
  (let [s (scenario "changing-plans") pending (resolve! s {:stables [0 1]}) done (resolve! pending {:cells [0 1]})]
    (is (= :plow-many (op pending)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:cells [0]})))
    (is (= #{0 1} (set (a/tiles (g/p done 0) :field))))
    (is (some #{(cid "WM" 69)} (get-in done [:players 1 :hand :minor])))))

(deftest seasonal-plants-adds-only-one-crop-to-one-just-sown-field
  (let [s (scenario "seasonal-plants") done (resolve! s {:cell 1})]
    (is (= (inc (get-in s [:players 0 :farm 1 :count])) (get-in done [:players 0 :farm 1 :count])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:cell 5})))
    (is (nil? (op (emit (assoc s :round 3 :effect-queue []) :sow 0 {:plantings [{:cell 1 :crop :grain}]}))))))

(deftest stable-deliveryman-reserves-pieces-and-charges-only-if-arrival-built
  (let [s (scenario "stable-deliveryman") selected (resolve! s {:rounds [4 6]})]
    (is (= 2 (a/stables-available (g/p selected 0))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:rounds [3]})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:rounds [4 4]})))
    (let [pending (at-round selected 4) done (resolve! pending {:cell 0}) skipped (resolve! pending {:skip? true})]
      (is (= :stable (op pending)))
      (is (= (dec (stock pending 0 :wood)) (stock done 0 :wood)))
      (is (= (stock pending 0 :wood) (stock skipped 0 :wood)))
      (is (get-in done [:players 0 :farm 0 :stable]))
      (is (= 3 (a/stables-available (g/p skipped 0)))))))

(deftest sunrise-admirer-constrains-east-and-discard-cancels-later-offers
  (let [s (scenario "sunrise-admirer") card (cid "WM" 49) done (resolve! s {:cell 4}) skipped (resolve! s {:skip? true})]
    (is (= :field (get-in done [:players 0 :farm 4 :type])))
    (is (= (- (stock s 0 :food) 2) (stock done 0 :food)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:cell 0})))
    (is (not (some #{card} (c/owned (g/p skipped 0)))))
    (is (every? empty? (map second (:schedule (g/p skipped 0)))))
    (is (not= card (get-in (at-round skipped 3) [:effect-queue 0 :card])))))

(deftest apple-tree-blocks-sowing-removal-and-empty-field-prerequisites
  (let [s (scenario "apple-tree") done (resolve! s {:cell 0}) player (g/p done 0)]
    (is (= {:wood 1} (get-in player [:farm 0 :marker-goods])))
    (is (= (dec (expr/evaluate s 0 {} [:empty-fields])) (expr/evaluate done 0 {} [:empty-fields])))
    (is (thrown? clojure.lang.ExceptionInfo (a/sow player [{:cell 0 :crop :grain}])))
    (is (thrown? clojure.lang.ExceptionInfo (a/remove-empty-field player 0)))
    (is (= (inc (stock done 0 :food)) (stock (emit done :field-phase 0) 0 :food)))
    (is (= (stock done 0 :wood) (a/amount (a/field-harvest player) :wood)))))

(deftest backyard-well-reserves-a-real-plot-and-village-well-removes-it
  (let [card (cid "WM" 62) s (scenario "backyard-well")
        done (g/command s 0 {:type :ability :card card :cell 0})
        upgraded (play done :catalog/original-base-i-66-village-well {:return-improvement card})]
    (is (= :feature (get-in done [:players 0 :farm 0 :type])))
    (is (not (a/empty-cell? (g/p done 0) 0)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command done 0 {:type :ability :card card :cell 1})))
    (is (thrown? clojure.lang.ExceptionInfo (g/command s 0 {:type :ability :card card :cell 4})))
    (is (a/empty-cell? (g/p upgraded 0) 0))
    (is (= 1 (get-in upgraded [:players 0 :well-deliveries 2])))
    (is (= (+ (stock upgraded 0 :food) 2) (stock (at-round upgraded 2) 0 :food)))))

(deftest gold-medal-compares-scoring-points-not-raw-amounts
  (let [s (resolve! (scenario "gold-medal") {:category :grain}) card (cid "WM" 87)
        s (-> s (assoc-in [:players 0 :goods :grain] 8) (assoc-in [:players 1 :goods :grain] 20))]
    (is (= 2 (g/card-bonus s 0 card))) (is (= 2 (g/card-bonus s 1 card))))
  (is (thrown? clojure.lang.ExceptionInfo (resolve! (scenario "gold-medal") {:category :wood}))))

(deftest wild-breeding-increases-only-eligible-piles-and-animal-must-fit
  (let [s (scenario "wild-breeding") done (resolve! s {:space :sheep})]
    (is (= :take-wild-breeding (op s)))
    (is (= (dec (get-in s [:piles :sheep :sheep])) (get-in done [:piles :sheep :sheep])))
    (is (= (inc (stock s 0 :sheep)) (stock done 0 :sheep)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:space :wood})))
    (is (= (:piles s) (:piles (resolve! s {:skip? true}))))))

(deftest artists-garden-harvests-one-field-including-card-fields
  (let [s (scenario "artists-garden") done (resolve! s {:cell 0})]
    (is (= (inc (stock s 0 :grain)) (stock done 0 :grain)))
    (is (= (dec (get-in s [:players 0 :farm 0 :count])) (get-in done [:players 0 :farm 0 :count])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:cell 5}))))
  (let [card (cid "FR" 5) s (-> (rich (fresh)) (own 0 card)
                               (assoc-in [:players 0 :card-fields :test] {:type :field :crop :wood :count 2}))
        pending (emit s :return-home 0) done (resolve! pending {:cell :test})]
    (is (= (inc (stock s 0 :wood)) (stock done 0 :wood)))
    (is (= 1 (get-in done [:players 0 :card-fields :test :count])))))

(deftest chocolate-can-pay-normal-occupation-fees-but-not-added-costs
  (let [card (cid "FR" 14) s (-> (rich (fresh)) (own 0 card) (assoc-in [:players 0 :card-state card :stored-goods :food] 4))
        chief :catalog/original-base-e-172-chief
        pending (play s chief)
        paid (g/drain (g/play-card (update-in s [:players 0 :hand :occupation] conj chief) 0 chief 2 {}))]
    (is (= 4 (g/chocolate-food-available (g/p pending 0))))
    (is (= (- (stock s 0 :food) 2) (stock pending 0 :food)))
    (is (= 2 (g/chocolate-food-available (g/p paid 0))))
    (is (= (- (stock s 0 :food) 2) (stock paid 0 :food)))
    (let [personal (g/drain (g/play-card (update-in s [:players 0 :hand :occupation] conj chief) 0 chief 2 {:chocolate-food 0}))]
      (is (= 4 (g/chocolate-food-available (g/p personal 0))))
      (is (= (- (stock s 0 :food) 4) (stock personal 0 :food))))
    (is (thrown? clojure.lang.ExceptionInfo (g/card-cost s 0 (c/card chief) 0 {:chocolate-food 1})))))

(deftest cabbage-lover-stores-up-to-converted-vegetables-and-caps-points-only
  (let [s (scenario "cabbage-lover") card (cid "FR" 67) done (resolve! s {:amount 1})]
    (is (= 1 (get-in done [:players 0 :card-state card :stored-goods :clay])))
    (is (= (dec (stock s 0 :clay)) (stock done 0 :clay)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:amount 2})))
    (is (= 5 (g/card-bonus (assoc-in done [:players 0 :card-state card :stored-goods :clay] 8) 0 card)))))

(deftest martial-artist-reveals-selected-minors-and-does-not-discard-occupations
  (let [s (scenario "martial-artist") cards (get-in s [:players 0 :hand :minor]) done (resolve! s {:cards (vec (take 2 cards))})]
    (is (= (+ 4 (stock s 0 :food)) (stock done 0 :food)))
    (is (every? (set (:removed-cards done)) (take 2 cards)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:cards [(first cards) (first cards)]})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:cards [(cid "G" 47)]})))))
