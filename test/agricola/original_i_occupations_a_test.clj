(ns agricola.original-i-occupations-a-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c] [agricola.bot :as bot]))
(defn cid [n] (:id (first (filter #(and (= "Original" (:edition %)) (= "I" (:deck %)) (= (str n) (:number %))) (vals c/metadata)))))
(defn fresh ([] (fresh 4)) ([n] (g/initialize (a/new-game (mapv str (range n)) (vec (mapcat identity a/stages)) 0))))
(defn own [s pid n] (update-in s [:players pid :occupations] conj (if (number? n) (cid n) n)))
(defn play [s pid n] (let [id (cid n)] (g/drain (g/play-card (update-in s [:players pid :hand :occupation] conj id) pid id 0 {}))))
(defn resolve! [s choice] (g/command s (g/acting-player s) (merge {:type :resolve :decision (:decision-id s)} choice)))
(defn reload [s] (edn/read-string (pr-str s)))
(defn emit [s kind pid context] (g/drain (g/queue-front s [(g/event kind pid context)])))
(defn rich [s pid] (update-in s [:players pid] a/gain {:food 20 :wood 20 :clay 20 :reed 20 :stone 20 :grain 6 :vegetable 4}))
(defn goods [s pid r] (a/amount (g/p s pid) r))
(defn op [s] (get-in s [:effect-queue 0 :effect :op]))

(deftest all-first-half-cards-have-canonical-native-definitions
  (is (= 24 (count (filter c/card (map cid (range 219 243))))))
  (doseq [n (range 219 243)] (is (= "Original" (:edition (c/card (cid n)))))))

(deftest fieldsman-and-gardener-use-real-field-stacks
  (doseq [[n expected] [[1 5] [2 4] [3 3]]]
    (let [s (-> (fresh) (own 0 219) (rich 0))
          p (reduce #(a/place-tile %1 :field %2) (g/p s 0) (range n))
          sown (a/sow p (mapv #(hash-map :cell % :crop :grain) (range n)))]
      (is (= (repeat n expected) (map #(get-in sown [:farm % :count]) (range n))))))
  (let [p (-> (g/p (-> (fresh) (own 0 226) (rich 0)) 0) (a/place-tile :field 0) (a/sow [{:cell 0 :crop :vegetable}]))
        harvested (a/field-harvest p)]
    (is (= 2 (get-in harvested [:farm 0 :count])))
    (is (= 1 (- (a/amount harvested :vegetable) (a/amount p :vegetable))))))

(deftest well-builder-buys-well-as-a-minor-at-its-fixed-cost
  (let [s (-> (fresh) (own 0 220) (assoc-in [:players 0 :goods] {:wood 1 :stone 1 :food 2}))
        done (g/command s 0 {:type :action :space :meeting :improvement :well})]
    (is (contains? (get-in done [:players 0 :improvements]) :well))
    (is (= 0 (goods done 0 :stone))) (is (= 0 (goods done 0 :wood)))))

(deftest village-elder-and-warden-gain-wood-and-score-all-players
  (doseq [[r wood] [[1 4] [5 4] [6 3] [9 2] [12 1] [14 0]] n [221 227]]
    (is (= wood (goods (play (assoc (fresh) :round r) 0 n) 0 :wood))))
  (let [s (-> (fresh) (own 0 221) (assoc-in [:players 0 :improvements] #{:well})
              (assoc-in [:players 1 :improvements] #{:pottery}))]
    (is (= 3 (g/card-bonus s 0 (cid 221))))
    (is (= 3 (g/card-bonus s 1 (cid 221))))
    (is (= 0 (g/card-bonus s 2 (cid 221))))))

(deftest social-climber-income-follows-payment-and-can-buy-the-followup-major
  (let [s (-> (fresh) (own 0 222) (update :active conj :renovate)
              (assoc-in [:players 0 :goods] {:clay 4 :reed 1 :food 2}))
        done (g/command s 0 {:type :action :space :renovate :improvement :pottery})]
    (is (= :clay (get-in done [:players 0 :house])))
    (is (contains? (get-in done [:players 0 :improvements]) :pottery))
    (is (= 1 (goods done 0 :stone))))
  (let [s (-> (fresh) (rich 0) (rich 1) (own 0 222) (own 1 :catalog/original-base-e-200-conservator))
        direct (a/renovate-game s 1 {:renovate-to :stone})
        first-clay (a/renovate-game direct 0 {})]
    (is (= 3 (- (goods first-clay 0 :stone) (goods direct 0 :stone))))
    (is (= #{:stone} (get-in direct [:renovation-history 1]))))
  (let [s (-> (fresh) (rich 0) (rich 1)) earlier (a/renovate-game s 1 {})
        done (a/renovate-game (own earlier 0 222) 0 {})]
    (is (= 2 (- (goods done 0 :stone) (goods earlier 0 :stone))))))

(deftest harvest-helper-takes-an-opponents-grain-with-compensation
  (let [s (-> (fresh) (own 0 223) (assoc-in [:players 1 :farm 0] {:type :field :crop :grain :count 1}))
        pending (emit s :feeding-start 0 {}) done (resolve! (reload pending) {:owner 1 :cell 0})]
    (is (= :harvest-helper (op pending)))
    (is (= 1 (goods done 0 :grain)))
    (is (= 2 (- (goods done 1 :food) (goods s 1 :food))))
    (is (= 0 (get-in done [:players 1 :farm 0 :count])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:owner 0 :cell 0})))
    (is (some #(bot/preview pending 0 %) (bot/decision-candidates pending 0)))))

(deftest field-worker-and-watchman-react-to-the-right-actions
  (doseq [n [3 4 5]]
    (let [s (own (fresh n) 1 224) done (emit s :sow 0 {})]
      (is (= (if (= n 3) 1 0) (- (goods done 1 :grain) (goods s 1 :grain))))
      (is (= (if (= n 3) 0 1) (- (goods done 1 :food) (goods s 1 :food))))))
  (let [pending (g/command (own (fresh) 0 225) 0 {:type :action :space :grain})
        done (resolve! pending {:cell 0})]
    (is (= :plow (op pending))) (is (= #{0} (set (a/tiles (g/p done 0) :field))))))

(deftest sycophant-requires-food-before-taking-grain
  (let [s (-> (fresh) (own 1 229) (assoc-in [:players 0 :goods :food] 0))]
    (is (thrown? clojure.lang.ExceptionInfo (g/command s 0 {:type :action :space :grain})))
    (let [paid (assoc-in s [:players 0 :goods :food] 1) done (g/command paid 0 {:type :action :space :grain})]
      (is (= 0 (goods done 0 :food))) (is (= 2 (- (goods done 1 :food) (goods paid 1 :food))))))
  (let [s (-> (fresh) (own 0 229) (assoc-in [:players 0 :goods :food] 0))]
    (is (= 1 (goods (g/command s 0 {:type :action :space :grain}) 0 :food)))))

(deftest clay-digger-is-public-and-owner-does-not-pay
  (let [s (play (fresh) 0 230) id (cid 230)]
    (is (= 3 (get-in s [:piles id :clay])))
    (let [done (g/command s 0 {:type :card-action :card-space id})]
      (is (= 3 (goods done 0 :clay))) (is (= (goods s 0 :food) (goods done 0 :food))))
    (let [s (assoc s :turn 1) done (g/command s 1 {:type :card-action :card-space id})]
      (is (= 3 (goods done 1 :clay))) (is (= 3 (- (goods done 0 :food) (goods s 0 :food)))))))

(deftest scheduled-supplies-start-after-play-and-only-schedule-once
  (let [s (play (fresh) 0 235)]
    (is (= #{2 3 4 5 6} (set (keys (get-in s [:players 0 :schedule]))))))
  (doseq [[n material amount resource] [[231 :stone 3 :food] [242 :clay 2 :clay] [242 :stone 2 :clay]]]
    (let [s (play (assoc-in (fresh) [:players 0 :house] material) 0 n)
          again (emit s :check-state 0 {})]
      (is (= amount (get-in s [:players 0 :schedule 2 0 :goods resource])))
      (is (= (:schedule (g/p s 0)) (:schedule (g/p again 0)))))))

(deftest midwife-ignores-temporary-people-and-farm-steward-is-consumed-by-first-growth
  (let [s (-> (fresh) (own 0 232) (assoc-in [:players 1 :family] 5) (assoc-in [:players 1 :temporary-family] 2))
        done (emit s :grow 1 {})]
    (is (= 1 (- (goods done 0 :food) (goods s 0 :food)))))
  (let [p (g/p (-> (fresh) (own 0 233) (assoc-in [:players 0 :house] :clay)) 0)
        grown (a/grow p true)]
    (is (= 3 (:family grown)))
    (is (thrown? clojure.lang.ExceptionInfo (a/grow grown true)))
    (is (thrown? clojure.lang.ExceptionInfo (a/grow (a/grow p false) true))))
  (is (thrown? clojure.lang.ExceptionInfo (a/grow (g/p (own (fresh) 0 233) 0) true))))

(deftest hide-farmer-uses-a-saved-final-payment
  (let [s (-> (fresh) (own 0 236) (assoc :round 14 :phase :finish-round) (assoc-in [:players 0 :goods :food] 20))
        pending (g/drain (g/finish-game s)) done (resolve! (reload pending) {:amount 13})]
    (is (= :cover-unused (op pending))) (is (= :finished (:phase done)))
    (is (= 7 (goods done 0 :food))) (is (= 0 (get-in (g/score done 0) [:breakdown :unused])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:amount 14})))
    (is (= -13 (get-in (g/score (resolve! pending {:skip? true}) 0) [:breakdown :unused])))))

(deftest juggler-pays-artists-before-their-effects-and-excludes-dancer
  (let [s (-> (fresh) (own 0 237) (own 0 :catalog/original-base-e-212-dancer)
              (own 1 :catalog/original-base-e-169-storyteller) (assoc-in [:piles :traveling :food] 1))
        pending (g/command s 0 {:type :action :space :traveling}) done (resolve! pending {}) declined (resolve! pending {:skip? true})]
    (is (= :juggler (op pending)))
    (is (= 1 (- (goods done 0 :food) (goods s 0 :food))))
    (is (= 1 (- (goods done 1 :food) (goods s 1 :food))))
    (is (= 4 (- (goods declined 0 :food) (goods s 0 :food))))))

(deftest chamberlain-reveals-reorders-and-limits-access-to-the-owner
  (let [s (assoc (fresh) :round 11) pending (play s 0 238)
        done (resolve! (reload pending) {:cultivation-first? true})]
    (is (= :reveal-rounds (op pending)))
    (is (some? (g/resolve-choice (a/public-state pending) 0 {:decision (:decision-id pending) :cultivation-first? true})))
    (is (= [:cultivation :urgent-growth :redevelop] (subvec (:round-order done) 11)))
    (is (g/available-action-space? done 0 :cultivation))
    (is (not (g/available-action-space? done 1 :cultivation)))
    (is (= 12 (g/round-space-number done :cultivation)))
    (is (g/available-action-space? (assoc done :round 12) 1 :cultivation))
    (is (= 14 (count (:revealed-round-order (a/public-state done)))))
    (is (= (count (:active done)) (count (:active (a/begin-round done)))))))

(deftest corn-profiteer-saves-offers-and-refunds-unselected-buyers
  (let [s (-> (fresh) (own 0 239) (assoc-in [:players 0 :goods :grain] 1))
        pending (g/command s 0 {:type :ability :card (cid 239)})
        one (resolve! pending {}) two (resolve! (reload one) {}) choosing (resolve! two {:skip? true})
        done (resolve! (reload choosing) {:buyer 2})]
    (is (= :corn-offer (op pending))) (is (= :corn-select (op choosing)))
    (is (= 0 (goods done 0 :grain))) (is (= 1 (goods done 2 :grain)))
    (is (= 2 (- (goods done 0 :food) (goods s 0 :food))))
    (is (= (goods s 1 :food) (goods done 1 :food)))
    (is (= -2 (- (goods done 2 :food) (goods s 2 :food))))
    (is (nil? (:corn-sale done)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! choosing {:buyer 3})))
    (is (some #(bot/preview choosing 0 %) (bot/decision-candidates choosing 0))))
  (let [s (-> (fresh) (own 0 239) (assoc-in [:players 0 :goods :grain] 1))
        pending (g/command s 0 {:type :ability :card (cid 239)})
        done (reduce (fn [s _] (resolve! s {:skip? true})) pending (range 3))]
    (is (= 3 (- (goods done 0 :food) (goods s 0 :food))))))

(deftest cowherd-only-adds-on-the-cattle-space
  (let [s (-> (fresh) (own 0 240) (update :active conj :cattle) (assoc-in [:piles :cattle :cattle] 1))
        pending (g/command s 0 {:type :action :space :cattle})]
    (is (= 2 (goods pending 0 :cattle)))))

(deftest clay-plasterer-combines-with-roof-and-bricklayer
  (let [s (-> (fresh) (own 0 241) (rich 0)) p (g/p s 0)
        renovated (a/renovate p)]
    (is (= 1 (- (a/amount p :clay) (a/amount renovated :clay))))
    (is (= {:clay 3 :reed 2} (select-keys (a/room-cost renovated {}) [:clay :reed])))
    (let [both (update renovated :occupations conj (cid 243))]
      (is (= {:clay 1 :reed 2} (select-keys (a/room-cost both {}) [:clay :reed]))))))

(deftest businessman-and-merchant-repeat-the-whole-improvement-action-once
  (let [spices :catalog/original-base-e-25-spices clogs :catalog/original-base-e-28-clogs
        raft :catalog/original-base-e-22-raft plane :catalog/original-base-e-26-plane
        s (-> (fresh) (rich 0) (own 0 228) (own 0 :catalog/original-base-e-179-merchant)
              (assoc-in [:players 0 :hand :minor] [spices clogs raft plane]))
        first-extra (g/command s 0 {:type :action :space :meeting :card spices})
        repeat-action (resolve! first-extra {:card clogs})
        repeated-extra (resolve! (reload repeat-action) {:card raft})
        done (resolve! repeated-extra {:card plane})]
    (is (= (cid 228) (get-in first-extra [:effect-queue 0 :card])))
    (is (= :catalog/original-base-e-179-merchant (get-in repeat-action [:effect-queue 0 :card])))
    (is (= (cid 228) (get-in repeated-extra [:effect-queue 0 :card])))
    (is (= #{spices clogs raft plane} (set (get-in done [:players 0 :minors]))))
    (is (= -1 (- (goods done 0 :food) (goods s 0 :food))))
    (is (empty? (:effect-queue done)))
    (let [repeat-only (resolve! first-extra {:skip? true})
          extra (resolve! repeat-only {:card raft}) result (resolve! extra {:card plane})]
      (is (= #{spices raft plane} (set (get-in result [:players 0 :minors]))))
      (is (= -1 (- (goods result 0 :food) (goods s 0 :food)))))))

(deftest businessman-requires-the-first-minor-and-allows-a-major-followup
  (let [minor :catalog/original-base-e-25-spices
        s (-> (fresh) (rich 0) (own 0 228) (assoc-in [:players 0 :hand :minor] [minor]))
        pending (g/command s 0 {:type :action :space :meeting :card minor})
        done (resolve! pending {:improvement :well})]
    (is (contains? (get-in done [:players 0 :improvements]) :well))
    (is (empty? (:effect-queue done)))
    (is (empty? (:effect-queue (g/command s 0 {:type :action :space :meeting}))))))

(deftest church-warden-counts-new-placements-in-round-fourteen
  (let [s (-> (fresh) (own 0 227) (assoc :round 14))
        fifth (reduce (fn [s space] (g/record-placement s s 0 space)) s [:wood :clay :reed :grain :fishing])
        relocated (g/record-placement (assoc fifth :action-context {:mode :minstrel}) fifth 0 :grain)]
    (is (= 3 (g/card-bonus fifth 0 (cid 227))))
    (is (= 5 (get-in relocated [:players 0 :people-placed-by-round 14])))
    (is (= 0 (g/card-bonus (assoc-in fifth [:players 0 :people-placed-by-round 14] 4) 0 (cid 227))))))

(deftest wood-buyer-forced-exchange-is-saveable-and-keeps-source-bonuses
  (let [s (-> (fresh) (own 1 234) (assoc-in [:piles :wood :wood] 3))
        pending (g/command s 0 {:type :action :space :wood})
        done (resolve! (reload pending) {})]
    (is (= :buy-action-resource (op pending)))
    (is (= 2 (goods done 0 :wood))) (is (= 1 (goods done 1 :wood)))
    (is (= 1 (- (goods done 0 :food) (goods s 0 :food))))
    (is (= -1 (- (goods done 1 :food) (goods s 1 :food))))))

(deftest gardener-does-not-report-the-last-vegetable-as-removed
  (let [s (-> (fresh) (own 0 226) (own 0 (c/id :a106))
              (assoc :phase :field-harvest)
              (assoc-in [:players 0 :farm 0] {:type :field :crop :vegetable :count 1}))
        done (g/drain (g/continue-step s :field-harvest))]
    (is (= 1 (get-in done [:players 0 :farm 0 :count])))
    (is (= 1 (- (goods done 0 :vegetable) (goods s 0 :vegetable))))
    (is (= 0 (- (goods done 0 :food) (goods s 0 :food))))))

(deftest guest-does-not-disable-first-person-wheel-plow
  (let [wheel (c/id :a018)
        s (-> (fresh) (update-in [:players 0 :minors] conj wheel)
              (assoc-in [:players 0 :card-state wheel :uses] 1)
              (assoc-in [:players 0 :guests] 1) (assoc-in [:players 0 :remaining] 3))
        pending (g/command s 0 {:type :action :space :plow :cell 0})
        done (resolve! pending {:cells [1 2]})]
    (is (= :wheel-plow (op pending)))
    (is (= #{0 1 2} (set (a/tiles (g/p done 0) :field))))
    (is (= 2 (get-in done [:players 0 :remaining])))
    (is (not (g/wheel-plow-available? (-> s (assoc-in [:players 0 :remaining] 2) (assoc-in [:players 0 :worker-spaces] [:wood])) 0 :plow)))))

(deftest blocked-workers-do-not-count-as-placements-and-lazy-sowman-does
  (let [wheel (c/id :a018)
        s (-> (fresh) (assoc :round 10) (own 1 :catalog/original-base-e-198-ratcatcher)
              (assoc-in [:players 0 :family] 3) (assoc-in [:players 0 :remaining] 3)
              (update-in [:players 0 :minors] conj wheel) (assoc-in [:players 0 :card-state wheel :uses] 1)
              g/apply-ratcatcher)
        pending (g/command s 0 {:type :action :space :plow :cell 0})
        done (resolve! pending {:cells [1]})]
    (is (= 2 (get-in s [:players 0 :remaining])))
    (is (= :wheel-plow (op pending)))
    (is (= 1 (g/placed-people done 0)))
    (is (not (g/wheel-plow-available? done 0 :plow))))
  (let [s (-> (fresh) (assoc :round 14) (own 0 227)
              (assoc-in [:players 0 :people-placed-by-round 14] 4))
        lazy (g/record-placement (assoc s :action-context {:mode :lazy-sowman}) s 0 :wood)
        relocated (g/record-placement (assoc s :action-context {:mode :swagman :worker-index 0})
                                      (assoc-in s [:players 0 :worker-spaces] [:grain]) 0 :build)]
    (is (= 5 (g/placed-people lazy 0)))
    (is (= 3 (g/card-bonus lazy 0 (cid 227))))
    (is (= 4 (g/placed-people relocated 0)))))

(deftest lasso-and-adopted-newborn-actions-each-count-real-people
  (let [s (-> (fresh)
              (update-in [:players 0 :minors] conj (c/id :b024))
              (update :active into [:sheep :boar])
              (assoc-in [:piles :sheep :sheep] 1) (assoc-in [:piles :boar :boar] 1)
              (update-in [:players 0 :improvements] conj :hearth-4))
        first (g/command s 0 {:type :action :space :sheep :lasso? true})
        ;; Cooking frees the house pet slot for the second placement.
        first (g/command first 0 {:type :convert :goods {:sheep 1}})
        second (g/command first 0 {:type :action :space :boar})]
    (is (= 1 (g/placed-people first 0)))
    (is (= 2 (g/placed-people second 0))))
  (let [s (-> (fresh) (assoc :round 14)
              (assoc-in [:players 0 :family] 5) (assoc-in [:players 0 :newborn] 1)
              (assoc-in [:players 0 :remaining] 0) (assoc-in [:players 0 :people-placed-by-round 14] 4)
              (assoc-in [:players 0 :goods :food] 4) (own 0 (c/id :a092)))
        ;; The real adopt effect changes remaining/newborn; the placement itself
        ;; is counted only when that newborn actually uses an action space.
        adopted (g/command s 0 {:type :ability :card (c/id :a092)})
        done (g/command adopted 0 {:type :action :space :wood})]
    (is (= 4 (g/placed-people adopted 0)))
    (is (= 5 (g/placed-people done 0)))))
