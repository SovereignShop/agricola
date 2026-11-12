(ns agricola.community-final-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.community-final :as f] [agricola.recording :as recording]
            [eurozone.card-scenarios :as scenarios] [eurozone.store :as store]
            [agricola.community-decks-test :refer [cid fresh rich own resolve! stock op emit]])
  (:import [java.nio.file Files]))
(defn scenario [id] (scenarios/initial-state id (:names (scenarios/options id "Ada"))))

(deftest badger-moves-and-pays-every-player
  (let [s (scenario "badger") marked (resolve! s {:space :grain})
        before (stock marked 0 :food) after (g/command marked 0 {:type :action :space :grain})]
    (is (= (inc before) (stock after 0 :food)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! (g/drain (g/begin-round after)) {:space :build})))
    (let [next (resolve! (g/drain (g/begin-round after)) {:space :plow})
          next (assoc next :turn 1) food (stock next 1 :food)]
      (is (= (inc food) (stock (g/command next 1 {:type :action :space :plow :cell 0}) 1 :food))))))

(deftest agrarian-reclaims-only-an-unused-reservation
  (let [s (scenario "agrarian") food (stock s 0 :food)
        declined (resolve! s {:skip? true}) placed (resolve! s {})]
    (is (= food (stock declined 0 :food)))
    (is (= (dec food) (stock placed 0 :food)))
    (is (= 1 (get-in placed [:space-bonuses :plow :food])))
    (let [end (emit placed :round-end 0)]
      (is (= food (stock end 0 :food))) (is (= :plow (op end)))
      (is (= :field (get-in (resolve! end {:cell 0}) [:players 0 :farm 0 :type]))))
    (let [taken (g/command (assoc placed :turn 1) 1 {:type :action :space :plow :cell 0})]
      (is (= (dec food) (stock (emit taken :round-end 0) 0 :food)))
      (is (not= :plow (op (emit taken :round-end 0)))))))

(deftest card-player-adjusts-receipts-without-adjusting-costs
  (let [s (scenario "card-player") p (g/p s 0) after (a/gain p {:wood 3 :stone 1 :reed 1})]
    (is (= 4 (- (a/amount after :wood) (a/amount p :wood))))
    (is (= (a/amount p :stone) (a/amount after :stone)))
    (is (= (dec (a/amount p :wood)) (a/amount (a/pay p {:wood 1}) :wood)))
    (is (= [:clay :reed :stone :wood] (get-in (emit s :round-end 0) [:players 0 :card-state f/card-player :stack])))))

(deftest bathroom-stores-converted-crops-and-adds-one-per-sown-field
  (let [s (scenario "bathroom") food (stock s 0 :food)
        kept (resolve! s {:goods {:grain 1 :vegetable 1}})
        declined (resolve! s {:skip? true})]
    (is (= food (stock kept 0 :food) (stock declined 0 :food)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:goods {:grain 2}})))
    (let [sown (-> kept (assoc :active (conj (:active kept) :sow-bake))
                   (g/command 0 {:type :action :space :sow-bake :plantings [{:cell 0 :crop :grain} {:cell 1 :crop :vegetable}] :bake-grain 0}))
          after (resolve! sown {:cells [0 1]})]
      (is (= :final-bathroom-sow (op sown)))
      (is (= [4 3] (mapv #(get-in after [:players 0 :farm % :count]) [0 1])))
      (is (= {:grain 0 :vegetable 0} (get-in after [:players 0 :card-state f/bathroom :stored-goods])))
      (is (thrown? clojure.lang.ExceptionInfo (resolve! sown {:cells [0 0]}))))))

(deftest critic-scores-distinct-items-and-observes-workshops
  (let [s (scenario "food-critic") after (resolve! s {:goods {:sheep 1}})]
    (is (= 1 (get-in after [:players 0 :card-state f/critic :stored-goods :sheep])))
    (is (nil? (get-in (resolve! s {:skip? true}) [:players 0 :card-state f/critic :stored-goods :sheep])))
    (doseq [[n points] [[3 0] [4 1] [5 2] [6 4] [7 6] [8 6]]]
      (is (= points (g/card-bonus (assoc-in after [:players 0 :card-state f/critic :stored-goods]
                                          (zipmap (take n [:food :wood :clay :reed :stone :sheep :boar :cattle]) (repeat 1))) 0 f/critic))))
    (let [harvest (-> after (assoc :phase :feeding :turn 0 :fed #{} :bred #{})
                       (update-in [:players 0 :improvements] conj :joinery))
          converted (g/command harvest 0 {:type :feed :craft [:joinery]})]
      (is (= :final-store-converted (op converted)))
      (is (= {:wood 1} (f/converted-items (first (:effect-queue converted))))))))

(deftest shared-wall-encloses-pasture-with-three-pieces
  (let [s (scenario "shared-wall") p (g/p s 0) edges ["h:0:0" "v:0:0" "v:0:1"]
        after (a/build-fences p edges)]
    (is (= [[0]] (a/pastures after))) (is (= 3 (count (:fences after))))
    (is (= (- (a/amount p :wood) 3) (a/amount after :wood)))
    (is (thrown? clojure.lang.ExceptionInfo (a/build-rooms after [0])))
    (is (thrown? clojure.lang.ExceptionInfo (a/build-fences (assoc p :minors []) edges)))))

(deftest astronomer-counts-equal-rectangles-at-return-home
  (let [s (scenario "astronomer") after (g/command s 0 {:type :action :space :lessons :card :catalog/original-base-e-174-tutor})]
    (is (= 1 (get-in after [:players 0 :bonus-points] 0))))
  (let [s (scenario "astronomer")]
    (is (= 2 (f/line-length s 0)))
    (is (= 0 (f/line-length (assoc-in s [:players 0 :worker-spaces] [(cid "G" 96)]) 0)))))

(deftest late-bloomer-is-an-adult-and-can-be-postponed
  (let [s (scenario "late-bloomer") after (resolve! s {})
        later (g/command (resolve! s {:skip? true}) 0 {:type :ability :card f/late-bloomer})]
    (doseq [state [after later]]
      (is (= 3 (get-in state [:players 0 :family])))
      (is (= 0 (get-in state [:players 0 :newborn])))
      (is (= 3 (get-in state [:players 0 :remaining]))))
    (is (thrown? clojure.lang.ExceptionInfo (g/command after 0 {:type :ability :card f/late-bloomer})))))

(deftest master-cook-replaces-a-person-with-a-guest-and-yields-the-turn
  (let [s (scenario "master-cook") cid :catalog/original-expansion-wm-36-master-cook
        after (g/command s 0 {:type :ability :card cid})]
    (is (= 1 (:turn after)))
    (is (= 1 (get-in after [:players 0 :guests])))
    (is (= 2 (get-in after [:players 0 :remaining])))
    (is (= 1 (g/placed-people after 0)))
    (is (= 2 (- (stock after 0 :food) (stock s 0 :food))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/command (assoc after :turn 0) 0 {:type :ability :card cid})))
    (let [alone (-> s (assoc-in [:players 1 :remaining] 0) (assoc-in [:players 2 :remaining] 0))]
      (is (thrown? clojure.lang.ExceptionInfo (g/command alone 0 {:type :ability :card cid}))))
    (is (= 1 (:turn (g/command s 0 {:type :action :space :grain}))))))

(deftest early-riser-breeds-before-fields-and-never-twice
  (let [s (scenario "early-riser") grain (stock s 0 :grain)
        pending (g/command s 0 {:type :action :space :grain})
        bred (resolve! pending {:offspring {} :cook? true})]
    (is (= :final-personal-breed (op pending)))
    (is (= (inc grain) (stock pending 0 :grain)))
    (is (= (+ grain 2) (stock bred 0 :grain)))
    (is (= 2 (stock bred 0 :sheep)))
    (is (= 2 (- (stock bred 0 :food) (stock s 0 :food))))
    (is (contains? (:bred bred) 0))
    (let [fed (-> bred (g/command 0 {:type :feed}) (g/command 1 {:type :feed}))]
      (is (= :breeding (:phase fed))) (is (= 1 (:turn fed)))
      (is (= 5 (:round (g/command fed 1 {:type :breed})))))))

(deftest almanac-is-a-personal-harvest-without-feeding
  (let [s (scenario "almanac") cid (cid "G" 61)
        pending (g/command s 0 {:type :action :space :meeting :card cid})
        after (resolve! pending {:offspring {:sheep 1}})]
    (is (= :final-personal-breed (op pending)))
    (is (= (inc (stock s 0 :grain)) (stock after 0 :grain)))
    (is (= (stock s 0 :food) (stock after 0 :food)))
    (is (= 3 (stock after 0 :sheep)))
    (is (some #{cid} (get-in after [:players 1 :hand :minor])))
    (is (= :work (:phase after)))))

(deftest stone-crane-follows-the-triggering-action
  (let [s (scenario "stone-crane") after (resolve! s {:rooms [0] :discount-resource :reed})]
    (is (= :build-rooms (op s)))
    (is (= 1 (get-in s [:effect-queue 0 :effect :max-rooms])))
    (is (= 3 (count (a/tiles (g/p after 0) :room))))
    (is (= 1 (- (stock s 0 :reed) (stock after 0 :reed))))
    (is (= 5 (- (stock s 0 :wood) (stock after 0 :wood))))
    (is (= 2 (count (a/tiles (g/p (resolve! s {:skip? true}) 0) :room))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:rooms [0 1]})))
    (let [pending (emit (resolve! s {:skip? true}) :renovate 1)
          renovated (resolve! pending {:discount-resource :reed})]
      (is (= :renovate (op pending)))
      (is (= :clay (get-in renovated [:players 0 :house])))
      (is (= (stock pending 0 :reed) (stock renovated 0 :reed))))))

(deftest baking-field-bakes-only-the-grain-it-provides
  (let [s (scenario "baking-field") after (g/command s 0 {:type :action :space :grain})]
    (is (= (inc (stock s 0 :grain)) (stock after 0 :grain)))
    (is (= (+ 2 (stock s 0 :food)) (stock after 0 :food)))
    (is (= 2 (get-in after [:players 0 :card-fields f/baking-field :count])))
    (let [without (assoc-in s [:players 0 :improvements] #{})
          harvested (g/command without 0 {:type :action :space :grain})]
      (is (= (inc (stock s 0 :food)) (stock harvested 0 :food)))))
  (let [s (scenario "baking-field") after (g/run-effect s (g/task 0 nil {:op :take-field-crops} {})
                                                     {:plantings [{:cell f/baking-field :crop :grain}]})]
    (is (= (stock s 0 :grain) (stock after 0 :grain)))
    (is (= (+ 2 (stock s 0 :food)) (stock after 0 :food)))))

(deftest kitchen-table-replaces-baking-and-returns-food-for-a-new-bake
  (let [s (scenario "kitchen-table")
        stored (g/command s 0 {:type :action :space :sow-bake :bake-grain 0 :kitchen-table? true})
        pending (g/command stored 0 {:type :ability :card f/kitchen-table})
        baked (resolve! pending {:grain 1})]
    (is (= (dec (stock s 0 :food)) (stock stored 0 :food)))
    (is (= 1 (get-in stored [:players 0 :card-state f/kitchen-table :stored-goods :food])))
    (is (= (stock s 0 :food) (stock pending 0 :food)))
    (is (= (+ 5 (stock s 0 :food)) (stock baked 0 :food)))
    (is (= (dec (stock s 0 :grain)) (stock baked 0 :grain)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command baked 0 {:type :ability :card f/kitchen-table})))
    (let [restored (resolve! pending {:grain 0 :kitchen-table? true})]
      (is (= (stock stored 0 :food) (stock restored 0 :food)))
      (is (= 1 (get-in restored [:players 0 :card-state f/kitchen-table :stored-goods :food]))))))

(deftest downsizing-returns-only-a-new-wooden-room-and-fences-for-free
  (let [s (scenario "downsizing") pending (resolve! s {:cell 0})
        after (resolve! pending {:fences ["h:0:0" "h:1:0" "v:0:0"]})]
    (is (= :build-fences (op pending)))
    (is (= 2 (count (a/tiles (g/p after 0) :room))))
    (is (= 2 (count (a/pastures (g/p after 0)))))
    (is (= (stock s 0 :wood) (stock after 0 :wood)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:cell 5})))
    (is (= 3 (count (a/tiles (g/p (resolve! s {:skip? true}) 0) :room))))
    (is (not= :final-downsize (op (emit after :fence 0))))))

(deftest expert-builder-stores-a-paid-piece-without-a-second-charge
  (let [s (scenario "expert-builder") after (resolve! s {:resource :reed})
        player (g/p after 0)
        built (a/build-rooms (assoc-in player [:goods :reed] 1) [0])]
    (is (= 1 (g/acting-player s)))
    (is (= (stock s 1 :reed) (stock after 1 :reed)))
    (is (= 1 (get-in after [:players 0 :card-state a/expert-builder-id :stored-goods :reed])))
    (is (= (:received player) (:received built)))
    (is (= 0 (a/amount built :reed)))
    (is (= 0 (get-in built [:card-state a/expert-builder-id :stored-goods :reed])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:resource :stone})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:skip? true})))
    (is (not= :final-expert-resource (op (g/drain (g/run-effect after (g/task 0 nil {:op :build-rooms :free? true} {}) {:rooms [0]})))))))

(deftest musketeer-counts-distinct-final-worker-spaces-around-the-arrow
  (let [s (scenario "musketeer") after (resolve! s {:point [3 3]})]
    (is (= #{:grain :plow :wood :clay} (get (f/intersections s) [3 3])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:point [0 0]})))
    (doseq [[spaces resource] [[[:grain :wood] :sheep] [[:grain :wood :plow] :boar] [[:grain :plow :wood :clay] :cattle]]]
      (is (= 1 (stock (emit (assoc-in after [:players 0 :worker-spaces] spaces) :return-home 0) 0 resource))))
    (is (= 0 (stock (emit (assoc-in after [:players 0 :worker-spaces] [:grain :grain]) :return-home 0) 0 :sheep)))))

(deftest mailman-charges-before-the-action-and-packages-are-optional
  (let [s (scenario "mailman") placed (reduce #(resolve! %1 {:space %2}) s [:grain :wood :build :plow])
        poor (-> placed (assoc :turn 1) (assoc-in [:players 1 :goods :reed] 1))
        pending (g/command poor 1 {:type :action :space :build :rooms [0]})
        after (resolve! pending {})]
    (is (= :final-mailman-buy (op pending)))
    (is (= 3 (count (a/tiles (g/p after 1) :room))))
    (is (= (inc (stock poor 0 :food)) (stock after 0 :food)))
    (is (= (dec (stock poor 1 :food)) (stock after 1 :food)))
    (is (empty? (f/mailman-offers after :build)))
    (let [offer (g/command placed 0 {:type :action :space :grain}) declined (resolve! offer {:skip? true})]
      (is (= (stock placed 0 :wood) (stock declined 0 :wood)))
      (is (seq (f/mailman-offers declined :grain))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! (resolve! s {:space :wood}) {:space :wood})))))

(deftest resource-lender-schedules-the-receipt-plus-one-and-allows-decline
  (let [s (scenario "resource-lender") after (resolve! s {:resource :wood :resources [:wood :wood :wood :wood]})]
    (is (= (- (stock s 0 :wood) 3) (stock after 0 :wood)))
    (is (= (stock s 0 :wood) (stock (resolve! s {:skip? true}) 0 :wood)))
    (is (= [2 3 4 5] (sort (keys (get-in after [:players 0 :schedule])))))
    (is (= (inc (stock after 0 :wood)) (stock (g/drain (g/begin-round after)) 0 :wood)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:resource :clay :resources [:wood :wood :wood :clay]})))))

(deftest pickaxe-pays-adjacent-board-spaces-and-plows-extra-fields
  (let [s (scenario "ground-pickaxe-plow") pending (resolve! s {:spaces [:grain :clay]})
        after (resolve! pending {:cells [1 2]})]
    (is (= :plow-many (op pending)))
    (is (= 3 (count (a/tiles (g/p after 0) :field))))
    (is (= (- (stock s 0 :wood) 2) (stock after 0 :wood)))
    (is (= 1 (get-in after [:space-bonuses :grain :wood])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:spaces [:build]})))
    (is (nil? (get-in (resolve! s {:skip? true}) [:players 0 :card-state (cid "FR" 27) :used?])))
    (is (not= :final-pickaxe (op (emit after :use-space 0 {:space :plow}))))))

(deftest winter-garden-sows-one-actual-room-and-clears-for-pets
  (let [s (scenario "winter-garden") after (g/command s 0 {:type :action :space :sow-bake :plantings [{:cell 5 :crop :grain}] :bake-grain 0})
        extra (emit (assoc after :round 6) :round-end 0)]
    (is (= :room (get-in after [:players 0 :farm 5 :type])))
    (is (= 3 (get-in after [:players 0 :farm 5 :count])))
    (is (= 2 (get-in extra [:players 0 :farm 5 :count])))
    (is (= (inc (stock after 0 :grain)) (stock extra 0 :grain)))
    (is (not (g/prerequisites? after 0 {:kind :minor :requires [:>= [:fields] 1]})))
    (is (thrown? clojure.lang.ExceptionInfo (a/sow (g/p after 0) [{:cell 10 :crop :vegetable}])))
    (is (nil? (get-in (g/drain (update-in after [:players 0] a/gain {:sheep 1})) [:players 0 :farm 5 :crop])))))

(deftest permaculturist-sows-separate-unused-spaces-and-clears-before-scoring
  (let [s (scenario "permaculturist") after (g/command s 0 {:type :action :space :sow-bake :plantings [{:cell 0 :crop :grain} {:cell 14 :crop :grain}] :bake-grain 0})
        cleared (g/command after 0 {:type :ability :card f/permaculturist :cells [14]})]
    (is (= [0 14] (f/permaculture-cells (g/p after 0))))
    (is (not (g/prerequisites? after 0 {:kind :minor :requires [:>= [:fields] 1]})))
    (is (= [0] (f/permaculture-cells (g/p cleared 0))))
    (is (= :empty (get-in cleared [:players 0 :farm 14 :type])))
    (is (thrown? clojure.lang.ExceptionInfo (a/sow (g/p s 0) [{:cell 0 :crop :vegetable}])))
    (is (= :field (:type (get-in (a/place-tile (g/p after 0) :field 7) [:farm 7]))))
    (is (empty? (f/permaculture-cells (g/p (f/refresh-farm-fields (assoc after :phase :scoring)) 0))))))

(deftest irrigation-moves-existing-crops-and-sows-with-one-less
  (let [s (scenario "irrigation-canal") after (resolve! s {:irrigations [{:cell 1 :from 0 :crop :grain}]})]
    (is (= [2 2] (mapv #(get-in after [:players 0 :farm % :count]) [0 1])))
    (is (= (stock s 0 :grain) (stock after 0 :grain)))
    (is (= (get-in s [:players 0 :received]) (get-in after [:players 0 :received])))
    (is (= 0 (get-in (resolve! s {:skip? true}) [:players 0 :farm 1 :count] 0)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:irrigations [{:cell 1 :from 2 :crop :grain}]})))))

(deftest sunday-worker-doubles-an-action-without-an-extra-worker
  (let [s (scenario "sunday-worker") after (resolve! s {})]
    (is (= (+ (stock s 0 :wood) 21) (stock after 0 :wood)))
    (is (= (dec (stock s 0 :food)) (stock after 0 :food)))
    (is (= 1 (g/placed-people after 0)))
    (is (= (stock s 0 :wood) (stock (resolve! s {:skip? true}) 0 :wood))))
  (let [base (-> (scenario "sunday-worker") (resolve! {:skip? true}) (assoc :turn 0))
        pending (g/command base 0 {:type :action :space :plow :cell 0})
        after (resolve! pending {:action-command {:space :plow :cell 1}})]
    (is (= :extra-action (op pending)))
    (is (= 2 (count (a/tiles (g/p after 0) :field))))
    (is (= 2 (g/placed-people after 0)))
    (is (= [:wood :plow] (get-in after [:players 0 :worker-spaces])))))

(deftest debt-collector-places-a-guest-before-starting-player-order
  (let [s (scenario "debt-collector") scheduled (resolve! s {:round 4})
        arrived (nth (iterate #(g/drain (g/begin-round %)) (assoc scheduled :starting-player 1)) 3)
        ready (resolve! arrived {})
        placed (g/command ready 0 {:type :action :space :grain})]
    (is (= :final-debt-arrival (op arrived)))
    (is (= 0 (:turn ready)))
    (is (= 1 (:turn placed)))
    (is (= 2 (get-in placed [:players 0 :remaining])))
    (is (= (inc (stock arrived 1 :food)) (stock ready 1 :food)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:round 3})))))

(deftest remodeler-removes-a-stable-before-free-room-and-enlarged-renovation
  (let [s (scenario "remodeler") after (resolve! s {:stable 0 :cell 0}) declined (resolve! s {:skip? true})]
    (is (= :clay (get-in after [:players 0 :house])))
    (is (= 3 (count (a/tiles (g/p after 0) :room))))
    (is (= (- (stock s 0 :clay) 3) (stock after 0 :clay)))
    (is (nil? (get-in after [:players 0 :farm 0 :stable])))
    (is (= 2 (count (a/tiles (g/p declined 0) :room))))
    (is (get-in declined [:players 0 :farm 0 :stable]))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:stable 1 :cell 0})))))


(deftest auctioneer-seals-bids-and-breaks-ties-in-frozen-order
  (let [s (scenario "auctioneer") partial (resolve! s {:amount 3})
        after (reduce #(resolve! %1 {:amount %2}) partial [4 4 0])]
    (is (= :final-auction-bid (op s)))
    (is (= (mapv :goods (:players s)) (mapv :goods (:players partial))))
    (is (= {0 3} (:private-auction-bids partial)))
    (is (= 1 (get-in after [:players 0 :card-state f/auctioneer :last-auction :winner])))
    (is (= (+ 4 (stock s 0 :food)) (stock after 0 :food)))
    (is (= (- (stock s 1 :food) 4) (stock after 1 :food)))
    (is (= (+ 2 (stock s 1 :reed)) (stock after 1 :reed)))
    (is (nil? (:private-auction-bids after)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command partial 0 {:type :convert :goods {:grain 1}})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:amount 100})))
    (let [owner (reduce #(resolve! %1 {:amount %2}) s [5 0 0 0])]
      (is (= (- (stock s 0 :food) 5) (stock owner 0 :food)))
      (is (= (+ 2 (stock s 0 :reed)) (stock owner 0 :reed))))))

(def writing-cards [:catalog/original-base-e-174-tutor :catalog/original-base-e-176-woodcutter
                    :catalog/original-base-e-187-clay-deliveryman :catalog/original-expansion-g-5-architect])
(defn schedule-writing [s]
  (reduce (fn [state [round card]] (resolve! state {:round round :card card})) s (map vector [5 7 9 11] writing-cards)))
(deftest writing-maniac-schedules-private-identities-and-plays-the-assigned-card
  (let [s (scenario "writing-maniac") scheduled (schedule-writing s)
        arrived (g/drain (g/begin-round scheduled))
        played (resolve! arrived {}) declined (resolve! arrived {:decline? true})]
    (is (= (zipmap [5 7 9 11] writing-cards) (get-in scheduled [:players 0 :private-card-state f/writing-maniac])))
    (is (empty? (get-in scheduled [:players 0 :hand :occupation])))
    (is (= :final-writing-arrive (op arrived)))
    (is (c/has? (g/p played 0) (first writing-cards)))
    (is (= (- (stock arrived 0 :food) 2) (stock played 0 :food)))
    (is (some #{(first writing-cards)} (get-in declined [:players 0 :hand :occupation])))
    (is (= (stock arrived 0 :food) (stock declined 0 :food)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! arrived {:perpetual-student? true})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:round 4 :card (first writing-cards)})))))

(deftest private-decisions-stay-out-of-public-facts-and-card-history
  (doseq [[id commands secret] [["auctioneer" [{:amount 3}] ":private-auction-bids"]
                               ["writing-maniac" [{:round 5 :card (first writing-cards)}] (str (first writing-cards))]
                               ["village-school" [{:type :action :space :build :rooms [0] :school-card (second writing-cards) :school-resource :wood}] (str (second writing-cards))]]]
    (let [path (str (.resolve (Files/createTempDirectory "secret-cards" (make-array java.nio.file.attribute.FileAttribute 0)) "games.edn"))
          service (store/make-store path) table (:id (store/transact! service "reviewer" {:event :create :scenario id :name "Ada"}))]
      (doseq [choice commands]
        (let [room (get @(:rooms service) table)]
          (store/transact! service "reviewer" {:event :command :id table :revision (:revision room)
                                             :command (merge {:type :resolve :decision (get-in room [:state :decision-id])} choice)})))
      (let [public (pr-str (:datoms (store/snapshot service "spectator")))]
        (is (not (.contains public secret)) id)
        (is (empty? (get (store/private-view service "spectator") table)))
        (is (seq (get (store/private-view service "reviewer") table)))))))

(deftest unicycle-builds-disjoint-roads-and-takes-an-action-without-moving
  (let [s (scenario "unicycle-driver") road (resolve! s {:spaces [:grain :plow]})
        declined (resolve! s {:skip? true})
        work (g/command (scenarios/final-worker road) 0 {:type :action :space :grain})
        action (resolve! work {:option 0})
        after (resolve! action {:action-command {:space :plow :cell 0}})]
    (is (= (dec (stock s 0 :stone)) (stock road 0 :stone)))
    (is (= 14 (a/fences-available (g/p road 0))))
    (is (= 15 (a/fences-available (g/p declined 0))))
    (is (= :relocate (op work)))
    (is (= :extra-action (op action)))
    (is (= :field (get-in after [:players 0 :farm 0 :type])))
    (is (= [:grain] (get-in after [:players 0 :worker-spaces])))
    (is (not (some #(some #{:grain} (:spaces %)) (f/road-choices road (g/p road 0)))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:spaces [:build :plow]})))))

(deftest mooch-borrows-food-conversions-without-ownership-and-expires
  (let [s (scenario "mooch") borrowed (resolve! s {:owner 1}) p (g/p borrowed 0)
        cooked (a/convert-food (a/gain p {:sheep 1}) {:sheep 1}) baked (a/bake p 1)
        crafted (a/craft-food p [:joinery] false)
        hall (cid "G" 114)
        converted (g/command (assoc borrowed :phase :feeding) 0 {:type :ability :card f/mooch :recipe hall :option 0})
        expired (emit borrowed :harvest-end 0)]
    (is (empty? (:improvements p)))
    (is (= 2 (- (a/amount cooked :food) (a/amount p :food))))
    (is (= 5 (- (a/amount baked :food) (a/amount p :food))))
    (is (= 2 (- (a/amount crafted :food) (a/amount p :food))))
    (is (= 3 (- (stock converted 0 :food) (stock borrowed 0 :food))))
    (is (not (g/prerequisites? borrowed 0 {:kind :minor :requires [:>= [:baking-improvements] 1]})))
    (is (= #{:hearth-4 :clay-oven :joinery} (:improvements (g/p borrowed 1))))
    (is (empty? (:borrowed-food-improvements (g/p expired 0))))
    (is (thrown? clojure.lang.ExceptionInfo (a/bake (g/p expired 0) 1)))
    (let [upgraded (g/drain (a/build-improvement borrowed 1 {:improvement :stone-oven :bake-grain 0}))]
      (is (some #{:stone-oven} (:borrowed-food-improvements (g/p upgraded 0)))))))

(deftest village-school-discounts-real-costs-once-and-keeps-discard-private
  (let [s (scenario "village-school") cid (second writing-cards)
        cmd {:type :action :space :build :rooms [0] :school-card cid :school-resource :wood}
        after (g/command s 0 cmd)]
    (is (= 3 (count (a/tiles (g/p after 0) :room))))
    (is (= 0 (stock after 0 :wood)))
    (is (= (get-in s [:players 0 :received]) (get-in after [:players 0 :received])))
    (is (= [cid] (get-in after [:players 0 :private-card-state f/village-school :discarded])))
    (is (not (some #{cid} (get-in after [:players 0 :hand :occupation]))))
    (is (nil? (get-in after [:players 0 :school-voucher])))
    (is (thrown? clojure.lang.ExceptionInfo (g/command s 0 (dissoc cmd :school-card :school-resource))))
    (is (thrown? clojure.lang.ExceptionInfo (g/command s 0 (assoc cmd :space :grain :rooms []))))
    (is (thrown? clojure.lang.ExceptionInfo (f/prepare-school (assoc s :turn 1) 0 cmd)))
    (let [same (-> s (assoc-in [:players 0 :card-state f/village-school :used-turn] [(:round s) (:turn-serial s 0)]))]
      (is (thrown? clojure.lang.ExceptionInfo (g/command same 0 cmd))))))

(deftest witches-expands-all-farm-operations-or-removes-two-spaces
  (let [s (scenario "witches-dance-ground") cells (mapv a/coordinate-cells [[-1 1] [-1 2]])
        expanded (resolve! s {:placement :extend :cells cells}) p (g/p expanded 0)
        covered (resolve! s {:placement :cover :cells [0 1]})
        room (a/build-rooms p [(first cells)])
        field (a/sow (a/place-tile p :field (first cells)) [{:cell (first cells) :crop :grain}])
        fenced (a/build-fences p (a/edges (first cells)))]
    (is (= 17 (count (a/farm-cells p))))
    (is (= 13 (count (a/farm-cells (g/p covered 0)))))
    (is (= 1 (:bonus-points p)))
    (is (= -15 (get-in (a/score p) [:breakdown :unused])))
    (is (= -11 (get-in (a/score (g/p covered 0)) [:breakdown :unused])))
    (is (= 3 (count (a/tiles room :room))))
    (is (= 3 (get-in field [:farm (first cells) :count])))
    (is (= [[(first cells)]] (a/pastures fenced)))
    (is (thrown? clojure.lang.ExceptionInfo (a/place-tile (g/p covered 0) :field 0)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:placement :cover :cells [0 2]})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:placement :cover :cells [5 10]})))
    (is (= 0 (g/card-bonus expanded 0 (cid "WM" 10))))))

(def review-commands
  {"auctioneer" [{:amount 3} {:amount 4} {:amount 4} {:amount 0}]
   "writing-maniac" (mapv (fn [round card] {:round round :card card}) [5 7 9 11] writing-cards)
   "unicycle-driver" [{:spaces [:grain :plow]}]
   "mooch" [{:owner 1} {:type :action :space :sow-bake :bake-grain 1}]
   "village-school" [{:type :action :space :build :rooms [0] :school-card :catalog/original-base-e-176-woodcutter :school-resource :wood}]
   "witches-dance-ground" [{:placement :cover :cells [0 1]}]
   "remodeler" [{:stable 0 :cell 0}]
   "sunday-worker" [{}]
   "debt-collector" [{:round 4}]
   "irrigation-canal" [{:irrigations [{:cell 1 :from 0 :crop :grain}]}]
   "winter-garden" [{:type :action :space :sow-bake :plantings [{:cell 5 :crop :grain}] :bake-grain 0}]
   "permaculturist" [{:type :action :space :sow-bake :plantings [{:cell 0 :crop :grain} {:cell 14 :crop :grain}] :bake-grain 0}
                     {:type :ability :card :catalog/original-expansion-wm-40-permaculturist :cells [14]}]
   "resource-lender" [{:resource :wood :resources [:wood :wood :wood :wood]}]
   "ground-pickaxe-plow" [{:spaces [:grain :clay]} {:cells [1 2]}]
   "mailman" [{:space :grain} {:space :wood} {:space :build} {:space :plow} {:type :action :space :grain} {}]
   "musketeer" [{:point [3 3]}]
   "expert-builder" [{:resource :reed}]
   "downsizing" [{:cell 0} {:fences ["h:0:0" "h:1:0" "v:0:0"]}]
   "baking-field" [{:type :action :space :grain}]
   "kitchen-table" [{:type :action :space :sow-bake :bake-grain 0 :kitchen-table? true}
                    {:type :ability :card :catalog/original-expansion-fr-34-kitchen-table} {:grain 1}]
   "early-riser" [{:type :action :space :grain} {:offspring {} :cook? true}]
   "almanac" [{:type :action :space :meeting :card :catalog/original-expansion-g-61-almanac} {:offspring {:sheep 1}}]
   "stone-crane" [{:rooms [0] :discount-resource :reed}]
   "master-cook" [{:type :ability :card :catalog/original-expansion-wm-36-master-cook}]
   "late-bloomer" [{}]
   "badger" [{:space :grain} {:type :action :space :grain}]
   "agrarian" [{}]
   "food-critic" [{:goods {:sheep 1}}]
   "bathroom" [{:goods {:grain 1 :vegetable 1}}]
   "card-player" [{:type :action :space :wood}]
   "astronomer" [{:type :action :space :lessons :card :catalog/original-base-e-174-tutor}]
   "shared-wall" [{:type :action :space :fences :fences ["h:0:0" "v:0:0" "v:0:1"]}]})
(deftest prepared-tables-persist-and-record-native-decisions
  (doseq [[id commands] review-commands]
    (let [path (str (.resolve (Files/createTempDirectory "final-cards" (make-array java.nio.file.attribute.FileAttribute 0)) "games.edn"))
          service (store/make-store path)
          table (:id (store/transact! service "reviewer" {:event :create :scenario id :name "Ada"}))]
      (doseq [choice commands]
        (let [room (get @(:rooms service) table) s (:state room)
              command (merge {:type :resolve :decision (:decision-id s)} (cond-> choice (= :ability (:type choice)) (assoc :player 0)))]
          (is (= s (get-in @(:rooms (store/make-store path)) [table :state])) id)
          (store/transact! service "reviewer" {:event :command :id table :revision (:revision room) :command command})))
      (let [room (get @(:rooms service) table)]
        (is (= (:state room) (last (:frames (recording/timeline (:recording room)))))) id))))

(deftest village-school-does-not-discount-transfers-and-combines-with-stored-materials
  (let [base (g/p (scenario "village-school") 0)
        p (assoc base :school-voucher {:resource :wood})
        stored (a/remove-goods p {:wood 3})]
    (is (= 1 (a/amount stored :wood)))
    (is (not (get-in stored [:school-voucher :used?])))
    (is (= 2 (a/amount (a/pay p {:wood 3}) :wood))))
  (let [p (-> (g/p (scenario "village-school") 0)
              (update :occupations conj a/expert-builder-id)
              (update :minors conj a/pelt-roof-id)
              (assoc :school-voucher {:resource :wood})
              (assoc-in [:card-state a/expert-builder-id :stored-goods] {:wood 1 :reed 1})
              (assoc-in [:card-state a/pelt-roof-id :stored-goods] {:reed 1}))
        paid (a/pay-home-construction p {:wood 5 :reed 2} {})]
    (is (= 1 (a/amount paid :wood)))
    (is (= (a/amount p :reed) (a/amount paid :reed)))
    (is (= 0 (a/pelt-roof-reed paid)))
    (is (= {:wood 0 :reed 0} (a/expert-builder-stock paid)))))

(deftest mailman-allows-food-conversion-before-buying
  (let [s (-> (scenario "mailman")
              (resolve! {:space :grain}) (resolve! {:space :wood})
              (resolve! {:space :build}) (resolve! {:space :plow})
              (assoc-in [:players 0 :goods :food] 0))
        offered (g/command s 0 {:type :action :space :grain})
        converted (g/command offered 0 {:type :convert :goods {:grain 1}})
        bought (resolve! converted {})]
    (is (= :final-mailman-buy (op offered)))
    (is (= (+ 2 (stock s 0 :wood)) (stock bought 0 :wood)))
    (is (= 1 (stock bought 0 :food)))))
