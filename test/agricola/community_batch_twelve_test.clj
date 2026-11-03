(ns agricola.community-batch-twelve-test
  (:require [clojure.test :refer :all]
            [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [agricola.card-expressions :as expr]
            [eurozone.card-scenarios :as scenarios]
            [agricola.community-decks-test :refer [cid fresh rich own play reload resolve! emit op stock field]]))
(defn scenario [id] (scenarios/initial-state id (:names (scenarios/options id "Ada"))))
(defn bonus [s] (get-in s [:players 0 :bonus-points] 0))
(defn effect [s card operation context] (g/drain (g/queue-front s [(g/task 0 card operation context)])))

(deftest pearl-seeker-buys-only-pile-food-and-always-receives-one-stone
  (let [s (-> (rich (fresh)) (own 0 (cid "G" 44)) (assoc-in [:piles :fishing] {:food 3}))
        pending (g/command s 0 {:type :action :space :fishing})
        done (resolve! (reload pending) {:amount 1})]
    (is (= :pearl-seeker (op pending)))
    (is (= 1 (g/pearl-seeker-max pending 0 (get-in pending [:effect-queue 0 :context]))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:amount 2})))
    (is (= (+ 2 (stock s 0 :stone)) (stock done 0 :stone)))
    (is (= (inc (stock s 0 :food)) (stock done 0 :food)))
    (is (= (inc (stock s 0 :stone)) (stock (resolve! pending {:skip? true}) 0 :stone)))))

(deftest grain-lover-plows-only-in-first-seven-rounds-and-sows-new-field
  (doseq [round [7 8]]
    (let [s (-> (rich (fresh)) (assoc :round round) (field 1 nil 0))
          pending (play s (cid "G" 27))]
      (if (= round 8) (is (nil? (op pending)))
        (let [offer (resolve! pending {:cell 0}) sow (resolve! offer {})]
          (is (= :sow-offer (op offer))) (is (= :sow (op sow)))
          (is (thrown? clojure.lang.ExceptionInfo (resolve! sow {:plantings [{:cell 1 :crop :grain}]})))
          (is (thrown? clojure.lang.ExceptionInfo (resolve! sow {:plantings [{:cell 0 :crop :vegetable}]})))
          (is (= :grain (get-in (resolve! (reload sow) {:plantings [{:cell 0 :crop :grain}]}) [:players 0 :farm 0 :crop])))))))
  (let [s (-> (fresh) (own 0 (cid "G" 27)) (field 0 :grain 3)
              (assoc-in [:players 1 :goods :grain] 3))]
    (is (= [2 2 0 0] (mapv #(g/card-bonus s % (cid "G" 27)) (range 4))))))

(deftest birthstone-catches-earlier-growth-and-multiple-newborns
  (let [card (cid "G" 66) s (-> (rich (fresh)) (update-in [:players 0 :hand :minor] conj card)
                              (assoc-in [:players 0 :family] 4))
        grown (emit s :grow 0 {:family-before 2 :newborns-added 2})
        pending (play grown card)
        done (-> pending (resolve! {}) (resolve! {}))]
    (is (= 2 (get-in grown [:round-growths 0 :count])))
    (is (= 2 (bonus done))) (is (= (- (stock s 0 :stone) 2) (stock done 0 :stone)))
    (is (= 0 (expr/evaluate (update grown :round inc) 0 {} [:growths-this-round]))))
  (let [s (-> (rich (fresh)) (own 0 (cid "G" 66)))
        pending (effect s nil {:op :grow :room-required? false} {})]
    (is (= :points (op pending))) (is (= 1 (bonus (resolve! pending {}))))))

(deftest stone-trader-enforces-printed-stone-cost-even-when-discounted
  (let [s (scenario "stone-trader")]
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:improvement :fireplace-2})))
    (is (contains? (get-in (resolve! s {:improvement :well}) [:players 0 :improvements]) :well))
    (is (= (stock s 0 :food) (stock (resolve! s {:skip? true}) 0 :food))))
  (is (nil? (op (emit (-> (fresh) (own 0 (cid "G" 52))) :use-space 0 {:source-goods {:wood 3}})))))

(deftest pine-forest-delivers-fifth-wood-before-other-round-start-wood
  (let [card (cid "G" 92) s (-> (fresh) (own 0 card)
                             (assoc-in [:players 0 :card-state card :stored-goods :wood] 4)
                             (assoc-in [:players 0 :schedule 2] [{:card card :goods {:wood 2}}]))
        pending (g/drain (g/begin-round s)) done (resolve! (reload pending) {})]
    (is (= :pine-forest (op pending))) (is (= 0 (stock pending 0 :wood)))
    (is (= 7 (stock done 0 :wood)))
    (is (= 0 (get-in done [:players 0 :card-state card :stored-goods :wood])))))

(deftest covetous-farmer-requires-unanimous-opponents-and-owner-missing-resource
  (let [card (cid "WM" 12) s (scenario "covetous-farmer")
        command {:type :ability :card card :option 2} done (g/command s 0 command)]
    (is (= 1 (stock done 0 :reed)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command done 0 command)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc-in s [:players 1 :goods :reed] 0) 0 command)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc s :turn 1) 0 command)))))

(deftest gem-hunter-return-is-required-only-for-three-clay-before-round-ten
  (doseq [[round pile returned] [[9 3 1] [9 2 0] [10 3 0]]]
    (let [s (-> (rich (fresh)) (own 0 (cid "WM" 24)) (assoc :round round) (assoc-in [:piles :clay] {:clay pile}))
          pending (g/command s 0 {:type :action :space :clay}) done (resolve! pending {})]
      (is (= :gem-hunter (op pending)))
      (is (= returned (get-in done [:piles :clay :clay])))
      (is (= (+ (stock s 0 :clay) pile (- returned)) (stock done 0 :clay)))
      (is (= (inc (stock s 0 :stone)) (stock done 0 :stone)))
      (is (= 0 (get-in (resolve! pending {:skip? true}) [:piles :clay :clay]))))))

(deftest wood-worker-food-stays-for-next-visitor
  (let [s (-> (rich (fresh)) (own 0 (cid "WM" 55)))
        pending (g/command s 0 {:type :action :space :grain}) done (resolve! pending {})]
    (is (= :wood-worker (op pending)))
    (is (= 1 (get-in done [:space-bonuses :grain :food])))
    (is (= (dec (stock s 0 :food)) (stock done 0 :food)))
    (is (= (inc (stock s 0 :wood)) (stock done 0 :wood)))
    (let [collected (g/collect-space-bonus done 1 :grain)]
      (is (= (inc (stock done 1 :food)) (stock collected 1 :food)))
      (is (nil? (get-in collected [:space-bonuses :grain]))))))

(deftest award-winner-uses-actual-payment-and-is-once-per-round
  (let [s (scenario "award-winner") done (resolve! s {:resource :wood})]
    (is (= :award-winner (op s)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:resource :stone})))
    (is (= 1 (bonus done))) (is (= (dec (stock s 0 :wood)) (stock done 0 :wood)))
    (is (nil? (op (emit done :build-improvement 0 {:paid {:wood 1}})))))
  (let [s (-> (rich (fresh)) (own 0 (cid "FR" 64)) (update :active conj :improvement))
        pending (g/command s 0 {:type :action :space :improvement :improvement :clay-oven})]
    (is (= #{:clay :stone} (set (g/award-winner-resources pending 0 (cid "FR" 64) (get-in pending [:effect-queue 0 :context])))))
    (is (= 1 (bonus (resolve! pending {:resource :stone}))))))

(deftest attic-only-new-rooms-are-eligible-and-removal-loses-score
  (let [s (scenario "attic") card (cid "WM" 60) done (resolve! s {:cells [0]})]
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:cells [5]})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:cells [0 0]})))
    (is (= 1 (g/card-bonus done 0 card)))
    (is (= 0 (g/card-bonus (assoc-in done [:players 0 :farm 0] {:type :empty}) 0 card)))
    (is (= (dec (stock s 0 :wood)) (stock done 0 :wood)))))

(deftest freeloader-never-starts-at-meeting-and-diplomat-spends-only-occupied-use
  (let [s (-> (rich (fresh)) (own 0 (cid "G" 20)))]
    (is (thrown? clojure.lang.ExceptionInfo (g/command s 0 {:type :action :space :meeting})))
    (is (thrown? clojure.lang.ExceptionInfo (g/command (-> s (assoc-in [:occupied :wood] 1) (assoc-in [:piles :wood] {:wood 0})) 0 {:type :action :space :wood})))
    (is (= :field (get-in (g/command (assoc-in s [:occupied :plow] 1) 0 {:type :action :space :plow :cell 0}) [:players 0 :farm 0 :type]))))
  (let [s (scenario "diplomat") card (cid "WM" 16) uses (get-in s [:players 0 :card-state card :uses])]
    (is (= (dec uses) (get-in (g/command s 0 {:type :action :space :plow :cell 0}) [:players 0 :card-state card :uses])))
    (is (= uses (get-in (g/command s 0 {:type :action :space :grain}) [:players 0 :card-state card :uses])))
    (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc s :round 13) 0 {:type :action :space :plow :cell 0})))
    (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc-in s [:occupied :plow] 0) 0 {:type :action :space :plow :cell 0})))))

(deftest hay-rake-allows-third-acrobat-to-use-occupied-plow
  (let [s (-> (rich (fresh)) (own 0 (cid "FR" 29))
              (assoc-in [:occupied :plow] 0) (assoc-in [:occupied :traveling] 0)
              (assoc-in [:players 0 :worker-spaces] [:grain :plow :traveling]))
        context {:space :traveling :worker-index 2 :relocation-card :catalog/original-base-k-269-acrobat}
        extra (g/extra-action-state s 0 {:mode :original-relocation} context)]
    (is (some #{:plow} (g/relocation-spaces s 0 context)))
    (is (= :hay-rake (g/occupied-permission extra 0 :plow)))
    (is (not (some #{:plow} (g/relocation-spaces s 0 (assoc context :worker-index 1)))))))

(deftest gardening-loan-is-graphical-resource-return-and-partial-settlement-is-final
  (let [card (cid "FR" 82) s (play (rich (fresh)) card)
        done (g/command s 0 {:type :ability :card card :return-animals {:grain 1}})]
    (is (= -1 (g/card-bonus done 0 card)))
    (is (= (dec (stock s 0 :grain)) (stock done 0 :grain)))
    (is (= (stock s 0 :vegetable) (stock done 0 :vegetable)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command done 0 {:type :ability :card card :return-animals {:vegetable 1}})))
    (is (thrown? clojure.lang.ExceptionInfo (g/command s 0 {:type :ability :card card :return-animals {:sheep 1}})))))

(deftest winter-deadwood-passes-only-when-taking-wood
  (let [s (scenario "winter-deadwood") card (cid "FR" 58)
        kept (resolve! s {:option 0}) passed (resolve! s {:option 1})]
    (is (= 1 (bonus kept))) (is (some #{card} (:minors (g/p kept 0))))
    (is (= (dec (stock s 0 :wood)) (stock kept 0 :wood)))
    (is (= (inc (stock s 0 :wood)) (stock passed 0 :wood)))
    (is (not (some #{card} (:minors (g/p passed 0)))))
    (is (some #{card} (get-in passed [:players 1 :hand :minor])))))

(deftest sculptors-son-requires-single-source-good-and-matching-workshop
  (doseq [[resource major n] [[:wood :joinery 2] [:clay :pottery 2] [:reed :basketry 1]]]
    (let [s (-> (rich (fresh)) (own 0 (cid "FR" 107)) (assoc-in [:players 0 :improvements] #{major}))]
      (is (= n (expr/evaluate s 0 {:source-goods {resource 3}} [:workshop-source-bonus resource])))
      (is (= 0 (expr/evaluate s 0 {:source-goods {resource 3 :food 1}} [:workshop-source-bonus resource])))
      (is (= 0 (expr/evaluate (assoc-in s [:players 0 :improvements] #{}) 0 {:source-goods {resource 3}} [:workshop-source-bonus resource]))))))

(deftest wheelbarrow-rewards-one-actual-building-resource-with-player-count-recipe
  (doseq [players [3 4 5]]
    (let [s (-> (g/initialize (a/new-game (vec (take players ["A" "B" "C" "D" "E"])) (vec (mapcat identity a/stages)) 0))
                (own 0 (cid "WM" 54)) (assoc-in [:piles :wood] {:wood 1}))
          pending (g/command s 0 {:type :action :space :wood})
          done (resolve! pending {:resource :clay})]
      (is (= :wheelbarrow-pusher (op pending)))
      (is (= 1 (stock done 0 :clay)))
      (is (= (+ (stock s 0 :food) (if (= players 3) 0 1)) (stock done 0 :food)))
      (if (= players 3)
        (is (= (inc (stock s 0 :food)) (stock (resolve! pending {:resource :food}) 0 :food)))
        (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:resource :food}))))))
  (let [s (-> (fresh) (own 0 (cid "WM" 54)))
        context {:space :wood :source-goods {:wood 3} :before-received {}}]
    (is (expr/evaluate (assoc-in s [:players 0 :received :wood] 3) 0 (assoc context :source-returned {:wood 2}) [:wheelbarrow-eligible]))
    (is (not (expr/evaluate (assoc-in s [:players 0 :received :wood] 2) 0 context [:wheelbarrow-eligible])))
    (is (not (expr/evaluate (assoc-in s [:players 0 :received :wood] 1) 0 (assoc context :space :market) [:wheelbarrow-eligible])))))

(deftest meat-pie-uses-printed-grain-cost-and-one-bonus-per-bake
  (let [card (cid "G" 88) s (-> (rich (fresh)) (own 0 (cid "G" 34))
                              (own 0 card) (assoc-in [:players 0 :improvements] #{:clay-oven})
                              (assoc-in [:players 0 :goods :sheep] 1))
        pending (effect s nil {:op :bake} {})
        pie (resolve! pending {:grain 1}) done (resolve! pie {:option 0})]
    (is (= {:grain 2} (into {} (filter (comp pos? val) (g/card-cost s 0 (c/card card) 0 {:payment-option 0})))))
    (is (= :choose (op pie))) (is (= 3 (bonus done)))
    (is (= (- (stock s 0 :grain) 2) (stock done 0 :grain)))
    (is (= 0 (stock done 0 :sheep)))
    (is (nil? (op (emit s :bake 0 {:grain-baked 0}))))))

(deftest carrot-cake-baker-converts-vegetables-and-offers-baking
  (let [card (cid "G" 11) s (-> (rich (fresh)) (own 0 card) (assoc-in [:players 0 :improvements] #{:clay-oven}))
        pending (g/command s 0 {:type :ability :card card :option 0}) done (resolve! pending {:grain 1})]
    (is (= :bake (op pending)))
    (is (= (dec (stock s 0 :vegetable)) (stock done 0 :vegetable)))
    (is (= (+ 7 (stock s 0 :food)) (stock done 0 :food)))
    (is (= (dec (stock s 0 :grain)) (stock done 0 :grain))))
  (let [s (-> (rich (fresh)) (own 0 (cid "G" 11)) (assoc-in [:players 0 :improvements] #{:clay-oven}))]
    (is (nil? (op (emit s :convert-food 0 {:converted-goods {:sheep 1}}))))))

(deftest farm-school-graduate-blocks-occupation-spaces-and-manure-harvest
  (let [card (cid "WM" 20) s (-> (rich (fresh)) (own 0 card)
                              (assoc-in [:players 0 :hand :occupation] [(cid "G" 5)]))]
    (is (thrown? clojure.lang.ExceptionInfo (g/command s 0 {:type :action :space :lessons :card (cid "G" 5)})))
    (is (nil? (op (emit s :before-field-phase 0 {:manure? true}))))
    (let [pending (emit s :before-field-phase 0) done (resolve! pending {:card (cid "G" 5)})]
      (is (= :card-action (op pending)))
      (is (= (- (stock s 0 :food) 2) (stock done 0 :food)))))
  (is (thrown? clojure.lang.ExceptionInfo (play (-> (rich (fresh)) (own 0 (cid "WM" 70))) (cid "WM" 20)))))

(deftest tuileries-crop-arrives-before-sowing-and-triggers-market-woman
  (let [card (cid "FR" 54) s (-> (rich (fresh)) (own 0 card) (own 0 :catalog/original-base-k-292-market-woman) (field 0 nil 0))
        pending (emit s :use-space 0 {:space :plow}) offer (resolve! pending {:option 1})]
    (is (= (inc (stock s 0 :vegetable)) (stock offer 0 :vegetable)))
    (is (= (+ 2 (stock s 0 :grain)) (stock offer 0 :grain)))
    (is (= :sow-offer (op offer)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! offer {:skip? true})))
    (let [sow (resolve! offer {}) done (resolve! sow {:plantings [{:cell 0 :crop :vegetable}]})]
      (is (= :vegetable (get-in done [:players 0 :farm 0 :crop])))))
  (let [pending (emit (-> (rich (fresh)) (own 0 (cid "FR" 54))) :use-space 0 {:space :plow})]
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:option 0})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:option 1})))
    (is (nil? (op (resolve! pending {:skip? true}))))))
