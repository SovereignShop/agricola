(ns agricola.original-i-minors-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.card-expressions :as expr] [agricola.bot :as bot]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(defn cid [n slug] (keyword "catalog" (str "original-base-i-" n "-" slug)))
(def alms (cid 64 "alms")) (def kitchen (cid 65 "baker-s-kitchen")) (def well (cid 66 "village-well"))
(def threshing (cid 67 "threshing-board")) (def harrow (cid 68 "harrow")) (def patch (cid 69 "strawberry-patch"))
(def punner (cid 70 "punner")) (def goose (cid 72 "goose-pond")) (def cart (cid 74 "grain-cart"))
(def mill (cid 75 "hand-mill")) (def rake (cid 76 "rake")) (def crook (cid 77 "shepherd-s-crook"))
(def woodcart (cid 79 "wood-cart")) (def spinney (cid 80 "spinney")) (def extension (cid 81 "wooden-hut-extension"))
(def crane (cid 82 "wooden-crane")) (def woodpath (cid 83 "wooden-path")) (def coop (cid 84 "chicken-coop"))
(def corner (cid 85 "cooking-corner")) (def corn (cid 86 "corn-storehouse")) (def flagon (cid 87 "flagon"))
(def lasso (cid 88 "lasso")) (def claypath (cid 89 "clay-path")) (def planter (cid 90 "planter-box"))
(def ladder (cid 91 "ladder")) (def shed (cid 93 "milking-shed")) (def road (cid 94 "paved-road"))
(def trap (cid 95 "fish-trap")) (def exchange (cid 96 "reed-exchange")) (def slaughter (cid 97 "slaughterhouse"))
(def schnaps (cid 98 "schnaps-distillery")) (def roof (cid 99 "straw-thatched-roof")) (def tavern (cid 100 "tavern"))
(def feed (cid 101 "animal-feed")) (def reserve (cid 102 "wildlife-reserve")) (def water (cid 103 "water-mill"))
(def market (cid 104 "weekly-market")) (def deposit (cid 337 "clay-deposit")) (def moldboard (cid 63 "moldboard-plow"))
(defn rich [state] (update-in state [:players 0] a/gain {:wood 20 :clay 20 :reed 20 :stone 20 :food 20 :grain 10 :vegetable 10}))
(defn op [state] (get-in state [:effect-queue 0 :effect :op]))
(defn resolve-player [state choice] (g/command state (g/acting-player state) (merge {:type :resolve :decision (:decision-id state)} choice)))
(defn reload [state] (edn/read-string (pr-str state)))
(defn trigger [state event pid context] (g/drain (g/queue-front state [(g/event event pid context)])))
(defn crops [state plantings]
  (reduce (fn [state [cell crop n]] (assoc-in state [:players 0 :farm cell] {:type :field :crop crop :count n})) state plantings))

(deftest complete-minor-definition-batch-has-explicit-prerequisites
  (let [definitions (edn/read-string (slurp "resources/agricola/original-i-minors.edn"))]
    (is (= 39 (count definitions)))
    (doseq [id (keys definitions)]
      (is (contains? c/cards id) (str id))
      (is (or (contains? (c/card id) :requires) (some? (expr/requirement (:prerequisites (c/card id))))) (str id)))))

(deftest printed-alternative-costs-and-return-costs-are-real
  (doseq [[id options] [[coop [{:wood 2 :reed 1} {:clay 2 :reed 1}]]
                        [corn [{:wood 2 :reed 2} {:clay 2 :reed 2}]]
                        [exchange [{:wood 2} {:clay 2}]]]]
    (doseq [[index cost] (map-indexed vector options)]
      (is (= cost (into {} (filter (comp pos? val) (g/card-cost (fresh) 0 (c/card id) 0 {:payment-option index})))))))
  (let [state (-> (fresh) rich (hand kitchen) (assoc-in [:players 0 :improvements] #{:clay-oven}))
        played (g/drain (g/play-card state 0 kitchen 0 {:return-improvement :clay-oven}))]
    (is (= -2 (delta state played :stone)))
    (is (contains? (:available-improvements played) :clay-oven))
    (is (empty? (a/returnable-ovens (g/p played 0))))
    (is (= :bake (op played)))
    (is (= 10 (delta played (resolve! played {:grain 2}) :food))))
  (let [state (-> (fresh) (hand kitchen) (assoc-in [:players 0 :improvements] #{:clay-oven}))]
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 kitchen 0 {}))))
  (let [state (-> (fresh) (hand corner) (assoc-in [:players 0 :improvements] #{:hearth-4}))
        played (g/drain (g/play-card state 0 corner 0 {}))
        spiced (own played 0 :catalog/original-base-e-25-spices)]
    (is (= 4 (:vegetable (a/cook-rates (g/p played 0)))))
    (is (= 5 (:vegetable (a/cook-rates (g/p spiced 0)))))
    (is (contains? (:available-improvements played) :hearth-4))
    (is (empty? (a/returnable-ovens (g/p played 0))))))

(deftest passing-cards-pay-and-pass-only-after-their-effect
  (let [state (-> (fresh) (assoc :round 7) (hand alms)) done (g/drain (g/play-card state 0 alms 0 {}))]
    (is (= 6 (delta state done :food)))
    (is (some #{alms} (get-in done [:players 1 :hand :minor])))
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card (own state 0 :a103) 0 alms 0 {}))))
  (doseq [[id goods gain] [[exchange {:clay 2} {:reed 2}] [market {:grain 3} {:vegetable 2}]]]
    (let [state (-> (fresh) (hand id) (update-in [:players 0] a/gain goods))
          done (g/drain (g/play-card state 0 id 0 {}))]
      (doseq [[r n] gain] (is (= n (delta state done r))))
      (is (some #{id} (get-in done [:players 1 :hand :minor])))))
  (let [state (-> (fresh) (hand extension) (update-in [:players 0] a/gain {:wood 5 :reed 1}))
        waiting (g/drain (g/play-card state 0 extension 0 {})) done (resolve! waiting {:rooms [6]})]
    (is (= :build-rooms (op waiting)))
    (is (not (some #{extension} (get-in waiting [:players 1 :hand :minor]))))
    (is (= 3 (count (a/tiles (g/p done 0) :room))))
    (is (some #{extension} (get-in done [:players 1 :hand :minor])))
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card (assoc-in state [:players 0 :house] :clay) 0 extension 0 {})))) )

(deftest schedules-and-village-well-preserve-and-stack-deliveries
  (doseq [[id rounds] [[goose 4] [coop 8] [patch 3]]]
    (let [state (-> (fresh) rich (hand id) (own 0 :a103) (own 0 :a104) (own 0 :a105)
                    (crops [[0 :vegetable 1] [1 :vegetable 1]]))
          played (g/drain (g/play-card state 0 id 0 {}))]
      (is (= rounds (count (get-in played [:players 0 :schedule]))))
      (is (= 1 (delta played (g/drain (g/begin-round played)) :food)))))
  (let [state (-> (fresh) rich (hand well) (assoc-in [:players 0 :improvements] #{:well})
                  (assoc-in [:players 0 :well-rounds] #{2 3 4 5 6}))
        upgraded (g/drain (g/play-card state 0 well 0 {}))
        rebuilt (a/build-improvement upgraded 0 {:improvement :well})
        next (g/drain (g/begin-round rebuilt))]
    (is (contains? (:available-improvements upgraded) :well))
    (is (= 3 (delta rebuilt next :food)))
    (is (= 1 (get-in upgraded [:players 0 :well-deliveries 6])))))

(deftest action-income-uses-actual-source-not-card-bonuses
  (let [state (-> (fresh) (own 0 cart)) done (g/command state 0 {:type :action :space :grain})]
    (is (= 3 (delta state done :grain))))
  (let [state (-> (fresh) (own 0 woodcart)) done (g/command state 0 {:type :action :space :wood})]
    (is (= 5 (delta state done :wood))))
  (let [state (-> (fresh) (own 0 trap))]
    (is (= 2 (delta state (g/command state 0 {:type :action :space :market}) :food)))
    (is (= 2 (delta state (g/command state 0 {:type :action :space :fishing}) :food)))
    (is (= 0 (delta state (trigger state :build-improvement 0 {:card exchange}) :food))))
  (let [state (-> (fresh) (own 0 crane) (update :active conj :stone-west) (assoc-in [:piles :stone-west] {:stone 1}))
        pending (g/command state 0 {:type :action :space :stone-west}) done (resolve! pending {})]
    (is (= 2 (delta state pending :stone)))
    (is (= 3 (delta state done :stone)))
    (is (= -1 (delta state done :food)))))

(deftest harvest-conversion-cards-have-limits-and-do-not-bake
  (let [state (-> (fresh) (own 0 mill) (update-in [:players 0] a/gain {:grain 3}))
        pending (trigger state :feeding-start 0 {}) done (resolve! pending {:option 1})]
    (is (= 4 (delta state done :food)))
    (is (= -2 (delta state done :grain)))
    (is (nil? (op done))))
  (let [state (-> (fresh) (own 0 schnaps) (own 0 :catalog/original-base-e-25-spices)
                  (update-in [:players 0] a/gain {:vegetable 6}))
        done (resolve! (trigger state :feeding-start 0 {}) {})]
    (is (= 4 (delta state done :food)))
    (is (= -1 (delta state done :vegetable)))
    (is (= 2 (g/card-bonus state 0 schnaps)))
    (is (= 1 (g/card-bonus done 0 schnaps)))))

(deftest streets-have-one-winner-and-rake-counts-physical-fields
  (let [state (-> (fresh) (own 0 woodpath) (own 0 claypath) (own 1 road))]
    (is (= [0 0 2] [(g/card-bonus state 0 woodpath) (g/card-bonus state 0 claypath) (g/card-bonus state 1 road)])))
  (let [state (-> (fresh) (own 0 rake) (crops [[0 :grain 0] [1 :grain 0] [2 :grain 0] [3 :grain 0] [4 :grain 0]]))]
    (is (= 2 (g/card-bonus state 0 rake)))
    (is (= 0 (g/card-bonus (own state 0 harrow) 0 rake)))
    (is (= 2 (g/card-bonus (crops (own state 0 punner) [[6 :grain 0]]) 0 rake)))))

(deftest construction-and-sowing-modifiers-respect-their-scope
  (let [state (-> (fresh) (own 0 ladder) (own 0 roof)) p (g/p state 0)]
    (is (= 0 (:reed (a/room-cost p {}))))
    (is (= {:wood 3 :clay 3 :reed 2} (:printed-cost (c/metadata (cid 71 "holiday-house")))))
    (is (= 1 (:reed (g/card-cost state 0 (c/card extension) 0 {}))))
    (is (= 0 (:reed (g/card-cost state 0 (c/card coop) 0 {})))))
  (let [state (-> (fresh) (own 0 planter) (update-in [:players 0] a/gain {:grain 2 :vegetable 1})
                  (crops [[0 :grain 0] [1 :grain 0] [6 :vegetable 0]]))
        planted (a/sow (g/p state 0) [{:cell 0 :crop :grain} {:cell 1 :crop :grain} {:cell 6 :crop :vegetable}])]
    (is (= [5 3 3] (mapv #(get-in planted [:farm % :count]) [0 1 6])))))

(deftest public-action-cards-respect-owner-only-points-and-payments
  (doseq [[id kind goods] [[tavern :tavern {:food 3}] [deposit :clay-deposit {:clay 5}]]]
    (let [state (-> (fresh) (own 0 id)) points (g/command state 0 {:type :card-action :card-space id :action :points})
          visitor (assoc state :turn 1) received (g/command visitor 1 {:type :card-action :card-space id})]
      (is (= 2 (get-in points [:players 0 :bonus-points])))
      (is (= {} (g/source-action-goods state {:space id :action :points})))
      (doseq [[r n] goods] (is (= n (- (a/amount (g/p received 1) r) (a/amount (g/p visitor 1) r)))))
      (is (= (if (= id deposit) 1 0) (delta visitor received :food)))
      (is (thrown? clojure.lang.ExceptionInfo (g/command visitor 1 {:type :card-action :card-space id :action :points})))
      (is (some #(and (= id (:card-space %)) (= :points (:action %))) (bot/card-action-candidates state 0))))))

(deftest moldboard-and-public-harrow-are-exclusive-paid-plow-workflows
  (let [state (-> (fresh) (hand moldboard) (own 0 :a103) (update-in [:players 0] a/gain {:wood 2}))
        equipped (g/drain (g/play-card state 0 moldboard 0 {}))
        waiting (g/command equipped 0 {:type :action :space :plow :cell 0})
        done (resolve! (reload waiting) {:plow-card moldboard :cells [1]})]
    (is (= 2 (get-in equipped [:players 0 :card-state moldboard :uses])))
    (is (= 1 (get-in done [:players 0 :card-state moldboard :uses])))
    (is (= [0 1] (a/tiles (g/p done 0) :field)))
    (is (empty? (:plows (g/plow-options equipped 0 :cultivation))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! waiting {:plow-card moldboard :cells [1 2]}))))
  (let [state (-> (fresh) (own 0 harrow) (own 2 punner) (assoc :turn 1))
        waiting (g/command state 1 {:type :action :space :plow :cell 0})
        plowed (resolve-player (reload waiting) {:plow-card harrow :cells [1]})
        done (resolve-player plowed {:cell 0})]
    (is (= 2 (delta state plowed :food)))
    (is (= #{1} (get-in plowed [:players 0 :card-state harrow :used-by])))
    (is (= 2 (g/acting-player plowed)))
    (is (= :plow (op plowed)))
    (is (= [0] (a/tiles (g/p done 2) :field)))
    (is (empty? (:plows (g/plow-options done 1 :plow))))
    (is (= [harrow] (:plows (g/plow-options done 0 :plow))))
    (is (some #(= harrow (:plow-card %)) (bot/decision-candidates waiting 1)))) )

(deftest threshing-board-adds-a-real-bake-after-plowing
  (let [state (-> (fresh) (own 0 threshing) (assoc-in [:players 0 :improvements] #{:clay-oven})
                  (update-in [:players 0] a/gain {:grain 1}))
        waiting (g/command state 0 {:type :action :space :plow :cell 0})
        done (resolve! waiting {:grain 1})]
    (is (= :bake (op waiting)))
    (is (= 5 (delta state done :food)))
    (is (= [0] (a/tiles (g/p done 0) :field)))))

(deftest spinney-takes-source-wood-before-baskets-and-buyers
  (let [state (-> (fresh) (own 1 spinney) (own 0 :catalog/original-base-e-34-basket)
                  (own 0 :catalog/original-base-e-196-mushroom-collector)
                  (assoc-in [:piles :wood] {:wood 3})
                  (update-in [:players 0] a/gain {:wood 20}))
        waiting (g/command state 0 {:type :action :space :wood})
        ordered (g/command waiting 0 {:type :order-effect :card :catalog/original-base-e-34-basket})
        done (resolve! ordered {})]
    (is (= 1 (a/amount (g/p waiting 1) :wood)))
    (is (= 2 (delta state waiting :wood)))
    (is (= 0 (delta state done :wood)))
    (is (= 3 (delta state done :food)))
    (is (nil? (op done)))
    (is (= 2 (get-in done [:piles :wood :wood])))))

(deftest flagon-pays-for-each-current-well-and-future-builds
  (let [state (-> (fresh) (hand flagon) (update-in [:players 0] a/gain {:clay 1})
                  (own 1 well) (assoc-in [:players 2 :improvements] #{:well}))
        done (g/drain (g/play-card state 0 flagon 0 {}))]
    (is (= 8 (delta state done :food)))
    (is (= 2 (- (a/amount (g/p done 1) :food) (a/amount (g/p state 1) :food)))))
  (let [state (-> (fresh) (own 0 flagon))
        done (trigger state :build-improvement 2 {:improvement :well})]
    (is (= 4 (delta state done :food)))
    (is (= 1 (- (a/amount (g/p done 1) :food) (a/amount (g/p state 1) :food))))))

(deftest water-mill-and-corn-storehouse-share-an-orderable-window
  (let [state (-> (fresh) (own 0 corn) (own 1 water) (assoc :phase :field-harvest)
                  (crops [[0 :grain 0]]) (update-in [:players 0] a/gain {:grain 2}))
        waiting (trigger state :after-field-phase 0 {:manure? true})
        sowing (resolve! waiting {})
        sowed (resolve! (reload sowing) {:plantings [{:cell 0 :crop :grain}]})
        done (resolve! sowed {})]
    (is (= :sow-offer (op waiting)))
    (is (= :sow (op sowing)))
    (is (= 2 (get-in sowed [:players 0 :farm 0 :count])))
    (is (= :water-mill (op sowed)))
    (is (= 2 (delta state done :food)))
    (is (= 1 (- (a/amount (g/p done 1) :food) (a/amount (g/p state 1) :food))))
    (is (= -2 (delta state done :grain)))
    (is (empty? (:effect-queue done))))
  (let [state (-> (fresh) (own 0 water) (update-in [:players 0] a/gain {:grain 1}))
        done (resolve! (trigger state :after-field-phase 0 {}) {})]
    (is (= 3 (delta state done :food))))
  (let [state (-> (fresh) (own 0 corn) (crops [[0 :grain 0]]) (update-in [:players 0] a/gain {:vegetable 1}))]
    (is (empty? (:effect-queue (trigger state :after-field-phase 0 {}))))))

(deftest sheep-and-wildlife-rules-preserve-species-and-capacity
  (let [state (-> (fresh) (own 0 crook) (update :active conj :fences) (update-in [:players 0] a/gain {:wood 20}))
        edges (keep (fn [[edge n]] (when (= 1 n) edge)) (frequencies (mapcat a/edges [0 1 2 3])))
        done (g/command state 0 {:type :action :space :fences :fences (vec edges)})]
    (is (= 2 (delta state done :sheep)))
    (is (= 1 (count (a/pastures (g/p done 0))))))
  (let [state (-> (fresh) (own 0 reserve)) p (g/p state 0)]
    (is (some? (a/animal-housing (a/gain p {:sheep 1 :boar 1 :cattle 1})))))
  (let [state (-> (fresh) (own 0 reserve) (own 0 :catalog/original-base-e-59-drinking-trough))
        compartments (filter #(= reserve (:card %)) (a/accommodations (g/p state 0)))]
    (is (= 5 (reduce + (map :capacity compartments))))))

(deftest milking-shed-counts-every-player-before-the-field-phase
  (let [state (-> (fresh) (own 0 shed)
                  (assoc-in [:players 0 :goods :sheep] 2)
                  (assoc-in [:players 1 :goods :sheep] 3)
                  (assoc-in [:players 2 :goods :cattle] 6))
        head (first (g/trigger-tasks state (g/event :before-field-phase 0 {})))
        done (g/run-effect state head {})]
    (is (= 3 (delta state done :food)))))

(deftest slaughterhouse-earns-on-cooking-not-costs-and-feeds-last
  (let [state (-> (fresh) (own 0 slaughter) (assoc-in [:players 1 :improvements] #{:hearth-4})
                  (update-in [:players 1] a/gain {:sheep 2 :boar 1 :grain 1}))
        cooked (g/command state 1 {:type :convert :goods {:sheep 2 :boar 1}})
        grain (g/command cooked 1 {:type :convert :goods {:grain 1}})
        feeding (g/drain (g/continue-step grain :feeding))]
    (is (= 1 (delta state cooked :food)))
    (is (= 1 (delta state grain :food)))
    (is (every? #(empty? (:cooking-receipts %)) (:players grain)))
    (is (= [1 2 3 0] (:feeding-order feeding)))
    (is (= 1 (:turn feeding)))
    (let [next (g/command feeding 1 {:type :feed})]
      (is (= 2 (:turn next))))) )

(deftest original-lasso-excludes-five-player-market-and-offers-use-when-played
  (let [state (-> (g/initialize (a/new-game ["A" "B" "C" "D" "E"] (vec (mapcat identity a/stages)) 0))
                  (own 0 lasso) (update :active #(vec (distinct (conj % :sheep)))))
        first-action (g/command state 0 {:type :action :space :wood :lasso? true})]
    (is (= 0 (:turn first-action)))
    (is (= 1 (:turn-serial first-action)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command first-action 0 {:type :action :space :animal-market :choice :sheep})))
    (is (= 1 (:turn (g/command first-action 0 {:type :action :space :sheep}))))
    (is (some :lasso? (bot/action-candidates state 0))))
  (let [state (-> (fresh) (hand lasso) (update-in [:players 0] a/gain {:reed 1})
                  (update :active #(vec (distinct (conj % :sheep)))))
        waiting (g/command state 0 {:type :action :space :meeting :card lasso})
        next (resolve! (reload waiting) {})]
    (is (= :lasso-followup (op waiting)))
    (is (= 0 (:turn next)))
    (is (= 1 (:remaining (g/p next 0))))
    (is (= 1 (:turn (g/command next 0 {:type :action :space :sheep}))))))

(deftest animal-feed-can-repay-tenant-loan-before-final-housing
  (let [tenant :catalog/original-base-e-215-tenant-farmer
        state (-> (fresh) (own 0 feed) (own 0 tenant) (assoc :round 14 :phase :finish-round)
                  (update-in [:players 0] a/gain {:sheep 1 :boar 1 :cattle 1}))
        waiting (g/drain (g/finish-game state))
        done (resolve! waiting {:return-animals {:sheep 1 :boar 1 :cattle 1}})]
    (is (= :return-animal-loan (op waiting)))
    (is (= 2 (a/amount (g/p waiting 0) :sheep)))
    (is (= :housing (op done)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (resolve! done {:keep {:sheep 1 :boar 0 :cattle 0} :cook? true})))
    (is (= :finished (:phase (resolve! done {:keep {:sheep 1 :boar 0 :cattle 0} :cook? false}))))))

(deftest wood-cart-still-pays-when-pig-catcher-leaves-all-source-wood
  (let [state (-> (fresh) (own 0 woodcart) (own 0 (cid 253 "pig-catcher"))
                  (assoc-in [:piles :wood] {:wood 2}))
        done (g/command state 0 {:type :action :space :wood :pig-catcher? true})]
    (is (= 2 (delta state done :wood)))
    (is (= 1 (delta state done :boar)))
    (is (= 2 (get-in done [:piles :wood :wood]))))
  (let [state (-> (g/initialize (a/new-game ["A" "B" "C" "D" "E"] (vec (mapcat identity a/stages)) 0))
                  (own 0 woodcart))
        done (g/command state 0 {:type :action :space :market})]
    (is (= 1 (delta state done :wood)))))

(deftest shepherds-crook-only-rewards-subdivision-when-large-pasture-count-increases
  (let [border (fn [cells] (set (keep (fn [[edge n]] (when (= 1 n) edge)) (frequencies (mapcat a/edges cells)))))
        state (-> (fresh) (own 0 crook))
        split (assoc-in state [:players 0 :fences] (into (border [1 2 6 7]) (border [3 4 8 9])))
        whole #{1 2 3 4 6 7 8 9}
        rewarded (g/run-effect split (g/task 0 crook {:op :shepherds-crook} {:old-pastures [whole]}) {})]
    (is (= 4 (delta split rewarded :sheep)))
    (let [same-count (assoc-in state [:players 0 :fences] (into (border [1 2 6 7]) (concat (border [3 4]) (border [8 9]))))
          declined (g/run-effect same-count (g/task 0 crook {:op :shepherds-crook} {:old-pastures [whole]}) {})]
      (is (= 0 (delta same-count declined :sheep))))))
