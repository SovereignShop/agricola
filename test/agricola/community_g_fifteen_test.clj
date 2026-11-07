(ns agricola.community-g-fifteen-test
 (:require [clojure.test :refer :all] [clojure.edn :as edn]
           [agricola.engine :as a] [agricola.card-rules :as c] [agricola.card-game :as g]
           [agricola.community-decks-test :refer [cid fresh rich own ready play reload resolve! emit op stock field]]))
(defn run [s n effect choice & [context]]
 (g/run-effect s (g/task 0 (cid "G" n) {:op effect} (or context {})) choice))
(deftest prints-and-definitions
 (doseq [n [30 39 40 43 45 46 62 67 68 72]]
  (is (c/card (cid "G" n)))
  (is (.isFile (java.io.File. (format "resources/public/art/agricola/cards/original-g%03d.jpg" n))))))
(deftest headmaster-food-is-reserved-but-can-pay-additional-costs
 (let [s (-> (fresh) rich (play (cid "G" 30)))
       s (emit s :occupation 1) p (g/p s 0)
       s (assoc-in s [:players 0 :goods :food] 0)
       card :catalog/original-base-e-172-chief
       s (assoc-in s [:players 0 :hand :occupation] [card])]
   (is (= 1 (g/headmaster-food p)))
   (is (= 0 (stock s 0 :food)))
   (is (= {:food 1 :wood 0} (g/card-cost s 0 (c/card card) 0 {})))
   (is (= 0 (g/headmaster-food (g/spend-headmaster p 1))))))
(deftest pilgrim-food-arrives-before-paying-and-personal-stocks-count
 (let [s (-> (fresh) (assoc-in [:players 0 :goods :food] 0))
       s (g/drain (g/play-card (assoc-in s [:players 0 :hand :occupation] [(cid "G" 45)]) 0 (cid "G" 45) 2 {}))]
  (is (= 0 (stock s 0 :food)))
  (let [s (assoc s :round 2)
        result (run s 45 :g-pilgrim {:resource :wood})]
   (is (= 1 (stock result 0 :food))) (is (= 1 (stock result 0 :wood)))
   (is (thrown? clojure.lang.ExceptionInfo (run result 45 :g-pilgrim {:resource :clay}))))))
(deftest bountiful-food-is-personal-supply-spend-normal-first
 (let [s (-> (fresh) (own 0 (cid "G" 67)) (assoc-in [:players 0 :goods :food] 2)
             (assoc-in [:players 0 :card-state (cid "G" 67) :stored-goods :food] 3))
       p (g/p s 0)]
  (is (= 5 (a/amount p :food)))
  (is (= 3 (a/personal-stock-food (a/pay p {:food 2}))))
  (is (= 1 (a/personal-stock-food (a/pay p {:food 4}))))
  (is (= 0 (get-in (a/pay p {:food 4}) [:goods :food])))
  (is (thrown? clojure.lang.ExceptionInfo (a/pay p {:food 6})))
  (let [s (-> s (own 0 (cid "G" 45)) (assoc-in [:players 0 :goods :food] 0))]
   (is (= 3 (stock (run s 45 :g-pilgrim {:resource :wood}) 0 :food))))))
(deftest mathematician-stone-requires-five-players-and-equal-piles
 (let [s (-> (fresh) (assoc :round 6 :active [:wood :copse :clay :hollow :stone-west :stone-east])
             (assoc :piles {:wood {:wood 3} :copse {:wood 3} :clay {:clay 2} :hollow {:clay 2} :stone-west {:stone 2} :stone-east {:stone 2}}))]
  (is (= #{{:space :wood :resource :wood} {:space :copse :resource :wood} {:space :clay :resource :clay} {:space :hollow :resource :clay}} (set (g/g-mathematician-options s))))
  (is (= 2 (get-in (run s 39 :g-mathematician {:space :wood :resource :wood}) [:piles :wood :wood])))
  (is (empty? (g/g-mathematician-options (assoc s :round 5))))))
(deftest miner-collects-one-entire-revealed-stone-pile
 (let [s (-> (fresh) (assoc :round 7 :active [:labor :stone-west :stone-east] :piles {:stone-west {:stone 4} :stone-east {:stone 2}}))
       s (run s 40 :g-miner-mark {}) result (run s 40 :g-miner {:space :stone-west})]
  (is (= 4 (stock result 0 :stone)))
  (is (= 0 (get-in result [:piles :stone-west :stone])))
  (is (= 2 (get-in result [:piles :stone-east :stone])))
  (is (thrown? clojure.lang.ExceptionInfo (run result 40 :g-miner {:space :stone-east})))))
(deftest opportunist-direct-resource-and-last-action-only
 (let [s (assoc (fresh) :round 5)
       context {:source-goods {:wood 3 :clay 2} :g-resources-before {:round 5 :goods #{:wood}} :g-last-action? true}]
  (is (= [:clay] (g/g-opportunist-resources s context)))
  (is (= [] (g/g-opportunist-resources s (assoc-in context [:g-resources-before :goods] #{:wood :clay}))))
  (is (nil? (g/g-opportunist-resources s (dissoc context :g-last-action?))))))
(deftest dunghill-distinct-planted-farm-and-card-fields
 (let [s (-> (fresh) (assoc-in [:players 0 :goods :sheep] 1) (assoc-in [:players 0 :goods :boar] 1)
             (assoc-in [:players 0 :goods :cattle] 1) (field 0 :grain 2)
             (assoc-in [:players 0 :card-fields :copse] {:type :field :crop :wood :count 2}))
       result (run s 72 :g-dunghill {:fields [0 :copse]})]
  (is (= 3 (get-in result [:players 0 :farm 0 :count])))
  (is (= 3 (get-in result [:players 0 :card-fields :copse :count])))
  (is (thrown? clojure.lang.ExceptionInfo (run s 72 :g-dunghill {:fields [0 0]})))
  (is (thrown? clojure.lang.ExceptionInfo (run (assoc-in s [:players 0 :goods :cattle] 0) 72 :g-dunghill {:fields [0 :copse]})))))
(deftest chronicle-marks-only-remaining-majors-and-pays-once
 (let [s (-> (fresh) rich ready (update :available-improvements disj :well) (play (cid "G" 68)))
       food (stock s 0 :food)
       result (emit s :build-improvement 1 {:improvement :clay-oven})]
  (is (= (inc food) (stock result 0 :food)))
  (is (= food (stock (emit s :build-improvement 1 {:improvement :well}) 0 :food)))
  (is (= (inc food) (stock (emit result :build-improvement 1 {:improvement :clay-oven}) 0 :food)))))
(deftest animal-farm-once-per-round-removal-and-decline
 (let [s (-> (fresh) (own 0 (cid "G" 62)) (assoc :phase :work :round 1) (assoc-in [:players 0 :goods :sheep] 1))
       result (run s 62 :g-animal-farm {:resource :sheep})]
  (is (= 3 (get-in result [:players 0 :remaining])))
  (is (= 0 (stock result 0 :sheep)))
  (is (thrown? clojure.lang.ExceptionInfo (run result 62 :g-animal-farm {:resource :sheep})))
  (is (= 2 (:remaining (g/remove-owned-improvement (g/p result 0) (cid "G" 62)))))))

(deftest native-g-tables-resolve-decline-persist-and-replay
 (require 'eurozone.card-scenarios-test)
 (let [ids ["headmaster" "mathematician" "miner" "opportunist" "pilgrim" "pond-watchman" "animal-farm" "bountiful-harvest" "chronicle" "dunghill"]
       commands @(resolve 'eurozone.card-scenarios-test/review-commands)
       verify (resolve 'eurozone.card-scenarios-test/community-tables-resolve-decline-save-and-replay)]
  (with-redefs-fn {(resolve 'eurozone.card-scenarios-test/review-commands) (select-keys commands ids)} #(verify))))

(deftest bountiful-native-breeding-and-spending-in-feeding
 (require 'eurozone.card-scenarios)
 (let [initial ((resolve 'eurozone.card-scenarios/initial-state) "bountiful-harvest" ["Ada" "Ben"])
       after (reduce (fn [s cmd] (g/command s (g/acting-player s) cmd)) initial [{:type :feed} {:type :feed} {:type :breed}])
       bountiful (cid "G" 67)
       p (g/p after 0)
       s (-> after (assoc :phase :feeding :fed #{} :turn 0) (assoc-in [:players 0 :goods :food] 0)
             (assoc-in [:players 0 :card-state bountiful :stored-goods :food] 4))
       fed (a/feed s 0 {})]
  (is (= 1 (get-in p [:card-state bountiful :stored-goods :food])))
  (is (= 0 (get-in fed [:players 0 :begging])))
  (is (= 0 (stock fed 0 :food)))
  (let [s (assoc-in s [:players 0 :hand :occupation] [(cid "G" 40)])
        paid (g/paid-card-player s 0 (cid "G" 40) 2 {})]
    (is (= 2 (a/amount paid :food))))))

(deftest pond-watchman-costs-one-food-and-keeps-worker-count
 (require 'eurozone.card-scenarios)
 (let [s ((resolve 'eurozone.card-scenarios/initial-state) "pond-watchman" ["Ada" "Ben" "Cy" "Dee"])
       food (stock s 0 :food) count-before (count (get-in s [:players 0 :worker-spaces]))
       next (resolve! s {:option 0})
       result (g/resolve-choice next 0 {:decision (:decision-id next) :action-command {:type :action :space :wood}})]
  (is (= (dec food) (stock result 0 :food)))
  (is (= count-before (count (get-in result [:players 0 :worker-spaces]))))
  (is (= :wood (first (get-in result [:players 0 :worker-spaces]))))
  (is (= food (stock (resolve! s {:skip? true}) 0 :food)))))

(deftest pilgrim-may-take-benefit-before-well-and-scheduled-incomes
 (let [id (cid "G" 45) s (-> (fresh) (own 0 id) (own 0 (cid "G" 51))
                           (assoc-in [:players 0 :goods :food] 0)
                           (assoc-in [:players 0 :well-rounds] #{2})
                           (assoc-in [:players 0 :schedule 2] [{:card (cid "G" 51) :goods {:wood 2}}])
                           g/begin-round g/drain)]
  (is (= :g-pilgrim (op s)))
  (is (= 0 (stock s 0 :food)))
  (let [after (resolve! s {:resource :wood})]
   (is (= 3 (stock after 0 :wood))) (is (= 2 (stock after 0 :food))))))

(deftest headmaster-income-can-fund-earlier-played-educator
 (let [headmaster (cid "G" 30)
       s (-> (fresh) (own 0 :catalog/original-base-k-271-educator) (own 0 headmaster)
             (assoc-in [:players 0 :goods :food] 0)
             (assoc-in [:players 0 :card-state headmaster :stored-goods :food] 2)
             (assoc-in [:players 0 :hand :occupation] [(cid "G" 40)])
             (emit :occupation 1))
       after (resolve! s {:card (cid "G" 40)})]
  (is (= :educator (op s)))
  (is (= 3 (g/headmaster-food (g/p s 0))))
  (is (= 0 (g/headmaster-food (g/p after 0))))
  (is (c/has? (g/p after 0) (cid "G" 40)))))

(deftest dunghill-can-top-up-only-an-underlying-stack
 (let [s (-> (fresh) (assoc-in [:players 0 :goods :sheep] 1) (assoc-in [:players 0 :goods :boar] 1)
             (assoc-in [:players 0 :card-fields :mixed] {:type :field :under-crops {:reed 2}}))
       result (run s 72 :g-dunghill {:fields [:mixed]})]
  (is (= 3 (get-in result [:players 0 :card-fields :mixed :under-crops :reed])))))

(deftest bountiful-can-be-played-after-the-field-phase
 (require 'eurozone.card-scenarios)
 (let [bountiful (cid "G" 67)
       s ((resolve 'eurozone.card-scenarios/initial-state) "dunghill" ["Ada" "Ben"])
       s (-> (resolve! s {:skip? true})
             (assoc-in [:players 0 :hand :minor] [bountiful])
             (update-in [:players 0] a/build-stables [0] 0))
       played (g/drain (g/run-effect s (g/task 0 nil {:op :card-action :kinds #{:minor}} {}) {:card bountiful}))
       result (reduce (fn [s cmd] (g/command s (g/acting-player s) cmd)) played [{:type :feed} {:type :feed} {:type :breed}])]
  (is (= 2 (get-in s [:players 0 :g-harvest-count])))
  (is (= 1 (get-in result [:players 0 :card-state bountiful :stored-goods :food])))))

(deftest harvest-helper-counts-before-bountiful-is-played
 (let [s (-> (fresh) (assoc :phase :field-harvest)
             (assoc-in [:players 0 :g-harvest-count] 1)
             (assoc-in [:players 1 :farm 0] {:type :field :crop :grain :count 2}))
       result (g/run-effect s (g/task 0 :catalog/original-base-i-223-harvest-helper {:op :harvest-helper} {}) {:owner 1 :cell 0})]
  (is (= 2 (get-in result [:players 0 :g-harvest-count])))
  (is (= 1 (stock result 0 :grain)))))

(deftest bountiful-counts-cash-crop-before-its-good-becomes-a-point
  (let [cash (cid "WM" 68)
        state (-> (fresh) rich (own 0 (cid "G" 67)) (own 0 cash)
                  (field 0 :grain 2)
                  (assoc-in [:players 0 :card-fields cash] {:type :field :crop :grain :count 2})
                  (assoc-in [:players 0 :g-harvest-count] 0))
        after (g/crop-harvest state [0] {})]
    (is (= 2 (get-in after [:players 0 :g-harvest-count])))
    (is (= 1 (- (stock after 0 :grain) (stock state 0 :grain))))
    (is (= 1 (- (get-in after [:players 0 :bonus-points] 0)
                (get-in state [:players 0 :bonus-points] 0))))))
