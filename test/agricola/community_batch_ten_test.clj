(ns agricola.community-batch-ten-test
  (:require [clojure.test :refer :all] [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [agricola.card-expressions :as expr] [agricola.bot :as bot]
            [eurozone.card-scenarios :as scenarios]
            [agricola.community-decks-test :refer [cid fresh rich own play reload resolve! emit op stock field]]))
(defn scenario [id] (scenarios/initial-state id (:names (scenarios/options id "Ada"))))
(defn exchange [s card option] (g/command s 0 {:type :ability :card card :option option}))
(defn stored [s card] (get-in s [:players 0 :card-state card :stored-goods :food] 0))

(deftest fisherman-pays-the-fisher-not-the-supply-and-declines-cleanly
  (let [s (scenario "fishermans-apprentice") card (cid "G" 5) done (resolve! (reload s) {:card card})]
    (is (= :card-action (op s)))
    (is (= (dec (stock s 0 :food)) (stock done 0 :food)))
    (is (= (inc (stock s 1 :food)) (stock done 1 :food)))
    (is (some #{card} (:occupations (g/p done 0))))
    (is (= (:goods (g/p s 0)) (:goods (g/p (resolve! s {:skip? true}) 0))))
    (is (nil? (op (emit (-> (fresh) rich (own 0 (cid "G" 18))) :use-space 0 {:space :fishing}))))))

(deftest harvest-recipes-enforce-oven-timing-one-use-and-mutually-exclusive-choices
  (doseq [[id deck number options] [["glass-blower" "G" 22 [[0 :clay 1 3] [1 :clay 2 5]]]
                                  ["dove-hunter" "FR" 77 [[0 :sheep 1 4] [1 :cattle 1 6]]]
                                  ["peasants-boutique" "FR" 41 [[0 :reed 1 4]]]]]
    (doseq [[option resource n food] options]
      (let [s (-> (scenario id) (assoc-in [:players 0 :goods resource] n)) card (cid deck number)
            done (exchange (reload s) card option)]
        (is (= 0 (stock done 0 resource)))
        (is (= (+ food (stock s 0 :food)) (stock done 0 :food)))
        (is (thrown? clojure.lang.ExceptionInfo (exchange done card option)))
        (is (thrown? clojure.lang.ExceptionInfo (g/command s 0 {:type :ability :card card :option option :times 2})))
        (is (thrown? clojure.lang.ExceptionInfo (exchange (assoc-in s [:players 0 :goods resource] 0) card option)))
        (doseq [phase [:work :field-harvest :breeding :finish-round]]
          (is (not (g/anytime-available? (assoc s :phase phase) (g/p s 0) card))))
        (is (= n (stock (g/command s 0 {:type :feed}) 0 resource)))
        (let [refilled (-> done (assoc :round 7) (assoc-in [:players 0 :goods resource] n))]
          (is (g/anytime-available? refilled (g/p refilled 0) card))))))
  (let [s (assoc-in (scenario "glass-blower") [:players 0 :improvements] #{})]
    (is (not (g/anytime-available? s (g/p s 0) (cid "G" 22))))
    (is (thrown? clojure.lang.ExceptionInfo (exchange s (cid "G" 22) 0)))))

(deftest grafter-checks-personal-supply-after-feeding-at-real-breeding-boundary
  (let [s (scenario "grafter") done (g/command (reload s) 1 {:type :feed})]
    (is (= :breeding (:phase done)))
    (doseq [r [:grain :vegetable]] (is (= (inc (stock s 0 r)) (stock done 0 r))))
    (let [next (g/command done 0 {:type :breed})]
      (is (= (:goods (g/p done 0)) (:goods (g/p next 0))))))
  (doseq [n [0 1 2 4]]
    (let [s (-> (fresh) rich (own 0 (cid "G" 24)) (field 0 :grain 5) (field 1 :vegetable 5)
                (assoc-in [:players 0 :goods :grain] n) (assoc-in [:players 0 :goods :vegetable] n))
          done (emit s :breeding-start 0)]
      (doseq [r [:grain :vegetable]] (is (= (+ n (if (>= n 2) 1 0)) (stock done 0 r))))))
  (let [s (-> (scenario "grafter") (assoc-in [:players 0 :goods :grain] 2)
              (assoc-in [:players 0 :goods :food] 2) (assoc :fed #{} :turn 0))
        fed (g/command s 0 {:type :feed :convert {:grain 2}})
        done (g/command fed 1 {:type :feed})]
    (is (zero? (stock done 0 :grain)))))

(deftest grain-farmer-counts-planted-units-before-harvest-not-supply-or-manure
  (doseq [[n expected] [[0 0] [1 1] [3 1] [4 2] [6 2] [7 3] [9 3] [10 4] [20 4]]]
    (let [s (-> (fresh) rich (own 0 (cid "G" 26)) (field 0 :grain n))]
      (is (= (+ expected (stock s 0 :food)) (stock (emit s :before-field-phase 0) 0 :food)))
      (is (= (stock s 0 :food) (stock (emit s :before-field-phase 0 {:manure? true}) 0 :food)))))
  (let [s (scenario "grain-farmer") done (g/command s 0 {:type :action :space :grain})]
    (is (= (+ 3 (stock s 0 :food)) (stock done 0 :food)))
    (is (= 0 (get-in done [:players 0 :farm 2 :count] 0)))))

(deftest global-scorers-award-every-tied-player-and-use-remaining-round-thresholds
  (doseq [[deck n resource] [["G" 28 :wood] ["G" 58 :wood] ["WM" 31 :wood] ["WM" 52 :clay]]
          [round amount] [[1 4] [5 4] [6 3] [8 3] [9 2] [11 2] [12 1] [13 1] [14 0]]]
    (let [s (assoc (rich (fresh)) :round round) done (play s (cid deck n))]
      (is (= (+ amount (stock s 0 resource)) (stock done 0 resource)))))
  (doseq [[deck n prepared expected]
          [["G" 28 #(-> % (assoc-in [:players 2 :family] 3)) [2 2 0 2]]
           ["G" 58 #(-> % (field 0 nil 0) (assoc-in [:players 1 :farm 0] {:type :field :count 0})) [3 3 0 0]]
           ["WM" 31 identity [0 3 3 3]]
           ["WM" 52 #(-> % (assoc-in [:players 0 :house] :clay) (assoc-in [:players 1 :house] :clay)
                           (assoc-in [:players 2 :house] :stone)) [2 2 0 0]]]]
    (let [card (cid deck n) s (prepared (own (fresh) 0 card))]
      (doseq [pid (range 4)]
        (is (= (expected pid) (g/card-bonus s pid card)))
        (is (= (expected pid) (- (:total (g/score s pid)) (:total (a/score (g/p s pid))))))))))

(deftest helper-shares-construction-but-never-own-worker-or-unrelated-spaces
  (let [s (scenario "helper") done (g/command (reload s) 0 {:type :action :space :build :rooms [0]})]
    (is (= 3 (count (a/tiles (g/p done 0) :room))))
    (is (= #{0 1} (set (g/space-owners done :build)))))
  (doseq [space [:build :renovate :redevelop :improvement]]
    (let [s (-> (fresh) rich (own 0 (cid "G" 31)) (assoc-in [:occupied space] 1))]
      (is (g/head-family-permission? s 0 space))
      (is (not (g/head-family-permission? (assoc-in s [:occupied space] 0) 0 space)))))
  (doseq [space [:meeting :growth :wood :lessons :fishing]]
    (is (not (g/head-family-permission? (-> (fresh) (own 0 (cid "G" 31)) (assoc-in [:occupied space] 1)) 0 space))))
  (let [s (-> (g/initialize (a/new-game ["A" "B" "C" "D" "E"] (vec (mapcat identity a/stages)) 0))
              rich (own 0 (cid "G" 31)) (assoc :turn 1)
              (update-in [:players 1] a/gain {:wood 5 :reed 2})
              (g/command 1 {:type :action :space :house-building :rooms [0]}) (assoc :turn 0))
        done (g/command s 0 {:type :action :space :traveling})]
    (is (= (inc (stock s 0 :food)) (stock done 0 :food)))
    (is (= #{0 1} (set (concat (g/space-owners done :house-building) (g/space-owners done :traveling))))))
  (let [s (-> (g/initialize (a/new-game ["A" "B" "C" "D" "E"] (vec (mapcat identity a/stages)) 0))
              rich (own 0 (cid "G" 31)) (assoc :turn 1) (g/command 1 {:type :action :space :traveling}) (assoc :turn 0))]
    (is (thrown? clojure.lang.ExceptionInfo (g/command s 0 {:type :action :space :traveling})))
    (is (= 3 (count (a/tiles (g/p (g/command s 0 {:type :action :space :house-building :rooms [0]}) 0) :room))))))

(deftest source-bonuses-ignore-cards-and-apply-on-native-actions
  (let [s (scenario "truffle-digger") done (g/command s 0 {:type :action :space :wood})]
    (is (= (+ 2 (stock s 0 :food)) (stock done 0 :food))))
  (doseq [[n bonus] [[0 0] [1 1] [2 2] [3 3] [5 3]]]
    (let [s (-> (fresh) rich (own 0 (cid "G" 55)) (assoc-in [:players 0 :goods :boar] n))]
      (is (= (+ bonus (stock s 0 :food)) (stock (emit s :use-space 0 {:space :wood :source-goods {:wood 3}}) 0 :food)))
      (is (= (stock s 0 :food) (stock (emit s :use-space 0 {:space :labor :source-goods {:wood 1}}) 0 :food)))))
  (let [s (scenario "wheedler") done (g/command s 0 {:type :action :space :labor})]
    (is (= (+ 3 (stock s 0 :food)) (stock done 0 :food)))
    (is (= (stock s 0 :food) (stock (emit s :gain 0 {:goods {:food 2}}) 0 :food)))))

(deftest sausage-grinder-adds-per-animal-without-enabling-cooking-or-doubling
  (let [s (scenario "sausage-grinder") p (g/p s 0)]
    (is (= 3 (:sheep (a/cook-rates p))))
    (is (= (+ 3 (a/amount p :food)) (a/amount (a/convert-food p {:sheep 1}) :food)))
    (is (= (+ 5 (a/amount p :food)) (a/amount (a/convert-recipe p {:sheep 1} 4 {}) :food)))
    (is (thrown? clojure.lang.ExceptionInfo (a/convert-food (assoc p :improvements #{}) {:sheep 1})))
    (is (= 1 (:grain (a/cook-rates p))))
    (is (= 2 (:vegetable (a/cook-rates p))))))

(deftest animal-buyer-offers-all-nine-purchases-once-and-pairs-can-breed
  (doseq [[index cost animals] [[0 2 {:sheep 1}] [1 3 {:boar 1}] [2 4 {:cattle 1}]
                               [3 4 {:sheep 2}] [4 6 {:boar 2}] [5 8 {:cattle 2}]
                               [6 5 {:sheep 1 :boar 1}] [7 6 {:sheep 1 :cattle 1}] [8 7 {:boar 1 :cattle 1}]]]
    (let [s (scenario "animal-buyer") done (resolve! (reload s) {:option index})]
      (is (= (- (stock s 0 :food) cost) (stock done 0 :food)))
      (doseq [[r n] animals] (is (= n (stock done 0 r))))
      (is (= :feeding (:phase done)))
      (is (not= :choose (op done)))))
  (let [s (scenario "animal-buyer") done (resolve! s {:skip? true})]
    (is (= (stock s 0 :food) (stock done 0 :food))))
  (let [s (resolve! (scenario "animal-buyer") {:option 3})
        fed (reduce #(g/command %1 %2 {:type :feed}) s (range 3))
        bred (g/command fed 0 {:type :breed})]
    (is (= 3 (stock bred 0 :sheep)))))

(deftest cloister-dweller-needs-whole-columns-in-one-category
  (let [card (cid "WM" 10) s (play (scenario "cloister-dweller") card)]
    (is (= 1 (g/card-bonus s 0 card)))
    (is (= 2 (g/card-bonus (assoc-in s [:players 0 :farm 0] {:type :room}) 0 card)))
    (is (= 0 (g/card-bonus (assoc-in s [:players 0 :farm 9] {:type :empty}) 0 card)))
    (is (= 2 (g/card-bonus (update-in (rich s) [:players 0] a/build-fences (vec (map key (filter #(= 1 (val %)) (frequencies (mapcat a/edges [3 8 13])))))) 0 card)))))

(deftest silo-girl-schedules-only-future-parity-rounds-once
  (let [s (scenario "silo-girl") done (g/command s 0 {:type :action :space :renovate})
        schedule (get-in done [:players 0 :schedule])]
    (is (= (set (range 7 15)) (set (keys schedule))))
    (doseq [round (range 7 15)]
      (is (= {(if (even? round) :grain :vegetable) 1} (:goods (first (schedule round))))))
    (is (= schedule (get-in (emit done :check-state 0) [:players 0 :schedule])))
    (let [next (g/drain (g/begin-round done))]
      (is (= (inc (stock done 0 :vegetable)) (stock next 0 :vegetable)))))
  (is (empty? (:schedule (g/p (play (rich (fresh)) (cid "WM" 44)) 0)))))

(deftest stone-curator-counts-original-costs-and-separate-pairs
  (let [s (scenario "stone-curator") card (cid "WM" 46)]
    (is (= 2 (g/card-bonus s 0 card)))
    (is (= 1 (g/card-bonus (assoc-in s [:players 0 :house] :clay) 0 card)))
    (is (= 1 (g/card-bonus (assoc-in s [:players 0 :improvements] #{:well}) 0 card)))
    (let [done (g/command s 0 {:type :action :space :stone-west})]
      (is (= (inc (stock s 0 :food)) (stock done 0 :food))))))

(deftest birdcage-uses-stored-food-for-points-and-forbids-all-house-pets
  (let [s (scenario "birdcage") card (cid "WM" 63) done (resolve! (reload s) {})]
    (is (= (dec (stock s 0 :food)) (stock done 0 :food)))
    (is (= 1 (stored done card) (g/card-bonus done 0 card)))
    (is (= 0 (stored (resolve! s {:skip? true}) card)))
    (is (empty? (filter #(= :pet (:kind %)) (a/accommodations (g/p (own done 0 (c/id :a086)) 0)))))
    (is (= 0 (get-in done [:players 0 :bonus-points] 0)))))

(deftest free-range-food-can-be-collected-partially-and-preserved-for-scoring
  (let [s (scenario "free-range-chickens") card (cid "WM" 83)
        done (g/command (reload s) 0 {:type :ability :card card :amount 2})]
    (is (= 6 (stored s card))) (is (= 2 (g/card-bonus s 0 card)))
    (is (= 4 (stored done card))) (is (= 1 (g/card-bonus done 0 card)))
    (is (= (+ 2 (stock s 0 :food)) (stock done 0 :food)))
    (doseq [n [0 -1 7 1.5]]
      (is (thrown? clojure.lang.ExceptionInfo (g/command s 0 {:type :ability :card card :amount n}))))
    (is (= 0 (stored (g/command done 0 {:type :ability :card card}) card)))
    (is (= 7 (stored (g/drain (g/begin-round s)) card)))
    (is (= 6 (stored (g/drain (g/begin-round (field s 0 nil 0))) card)))
    (is (= #{1 2 3 4 5 6} (set (map :amount (filter #(= card (:card %)) (bot/ability-candidates s 0)))))))
  (is (thrown? clojure.lang.ExceptionInfo (play (rich (fresh)) (cid "WM" 83)))))

(deftest fruitcake-discounts-each-person-including-newborns-only-next-harvest
  (let [s (scenario "fruitcake") card (cid "WM" 85)
        s (-> s (assoc-in [:players 0 :family] 3) (assoc-in [:players 0 :newborn] 1))
        done (g/command s 0 {:type :action :space :grain})]
    (is (= 2 (a/food-needed done (g/p done 0)))))
  (let [s (g/command (scenario "fruitcake") 0 {:type :action :space :grain})]
    (is (= 2 (a/food-needed s (g/p s 0))))
    (let [next (emit (assoc s :round 7) :feeding-start 0)]
      (is (= 4 (a/food-needed next (g/p next 0)))))))

(deftest iron-plow-uses-native-cells-once-then-offers-sowing-only-on-that-use
  (let [s (scenario "iron-plow") card (cid "WM" 91)
        plowed (resolve! (reload s) {:plow-card card :cells [1]})
        done (-> plowed (resolve! {}) (resolve! {:plantings [{:cell 0 :crop :grain} {:cell 1 :crop :vegetable}]}))]
    (is (= :sow-offer (op plowed)))
    (is (= #{0 1} (set (a/tiles (g/p plowed 0) :field))))
    (is (= 0 (get-in done [:players 0 :card-state card :uses])))
    (is (= 3 (get-in done [:players 0 :farm 0 :count])))
    (is (= 2 (get-in done [:players 0 :farm 1 :count])))
    (is (nil? (op (resolve! plowed {:skip? true}))))
    (let [next (g/drain (g/begin-round done)) action (g/command next 0 {:type :action :space :plow :cell 2})]
      (is (nil? (op action)))))
  (let [s (scenario "iron-plow") skipped (resolve! s {:skip? true})]
    (is (= #{0} (set (a/tiles (g/p skipped 0) :field))))
    (is (= 1 (get-in skipped [:players 0 :card-state (cid "WM" 91) :uses])))
    (is (nil? (op skipped)))))

(deftest apple-garden-awards-two-total-for-either-missing-crop
  (let [card (cid "FR" 4)]
    (doseq [[grain veg expected] [[0 0 2] [0 1 2] [1 0 2] [1 1 0]]]
      (let [s (-> (scenario "apple-garden") (play card) (assoc-in [:players 0 :goods :grain] grain)
                  (assoc-in [:players 0 :goods :vegetable] veg))]
        (is (= expected (g/card-bonus s 0 card)))))
    (is (= 0 (g/card-bonus (-> (scenario "apple-garden") (play card) (field 0 :grain 1) (field 1 :vegetable 1)) 0 card)))))

(deftest peasants-boutique-returns-workshop-and-prevents-duplicate-reed-bonuses
  (let [card (cid "FR" 41) s (scenario "peasants-boutique") repurchased (a/build-improvement s 0 {:improvement :basketry})]
    (is (contains? (:available-improvements s) :basketry))
    (is (not (contains? (:improvements (g/p s 0)) :basketry)))
    (doseq [[n expected] [[0 0] [1 1] [2 1] [3 2] [4 3] [9 3]]]
      (let [state (assoc-in repurchased [:players 0 :goods :reed] n)]
        (is (= expected (g/card-bonus state 0 card)))
        (is (= 0 (get-in (a/score (g/p state 0)) [:breakdown :bonus])))))
    (is (thrown? clojure.lang.ExceptionInfo (play (rich (fresh)) card)))
    (is (= 1 (expr/evaluate s 0 {} [:majors]) (expr/evaluate s 0 {} [:minors])))))

(deftest port-le-havre-offers-one-or-two-clay-per-bake-and-needs-an-oven-to-play
  (let [s (scenario "the-port-le-havre")]
    (doseq [index [0 1]]
      (let [done (resolve! (reload s) {:option index}) n (inc index)]
        (is (= (- (stock s 0 :clay) n) (stock done 0 :clay)))
        (is (= (+ (stock s 0 :stone) n) (stock done 0 :stone)))
        (is (nil? (op done)))))
    (is (= (:goods (g/p s 0)) (:goods (g/p (resolve! s {:skip? true}) 0))))
    (is (thrown? clojure.lang.ExceptionInfo (play (rich (fresh)) (cid "FR" 49))))))

(deftest watering-can-tops-up-actual-grain-and-vegetables-including-card-fields-and-passes
  (let [s (scenario "watering-can") card (cid "FR" 56) done (g/command (reload s) 0 {:type :action :space :meeting :card card})]
    (is (= 4 (get-in done [:players 0 :farm 0 :count])))
    (is (= 3 (get-in done [:players 0 :farm 1 :count])))
    (is (= 0 (get-in done [:players 0 :farm 2 :count] 0)))
    (is (= 3 (get-in done [:players 0 :card-fields :catalog/original-base-e-18-bean-field :count])))
    (is (= (dec (stock s 0 :food)) (stock done 0 :food)))
    (is (some #{card} (get-in done [:players 1 :hand :minor])))
    (is (not-any? #{card} (c/owned (g/p done 0))))
    (let [mixed (-> s (assoc-in [:players 0 :farm 0 :under-crops] {:vegetable 2})
                    (assoc-in [:players 0 :card-fields :copse] {:type :wood-stack :crop :wood :count 2}))
          done (g/command mixed 0 {:type :action :space :meeting :card card})]
      (is (= 3 (get-in done [:players 0 :farm 0 :under-crops :vegetable])))
      (is (= 2 (get-in done [:players 0 :card-fields :copse :count]))))))

(deftest art-director-keeps-building-resource-choice-after-extra-food
  (let [s (scenario "art-director")]
    (doseq [resource [:wood :clay :reed :stone]]
      (let [done (resolve! (reload s) {:resource resource})]
        (is (= (inc (stock s 0 resource)) (stock done 0 resource))))))
  (let [s (-> (fresh) rich (play (cid "FR" 63))) next (g/command s 0 {:type :action :space :traveling})]
    (is (= (+ 2 (stock s 0 :food)) (stock next 0 :food)))))

(deftest cocotte-major-fee-is-atomic-and-minors-have-no-extra-charge
  (let [s (scenario "cocotte") done (resolve! (reload s) {:improvement :fireplace-2})]
    (is (= (dec (stock s 0 :food)) (stock done 0 :food)))
    (is (= (- (stock s 0 :clay) 2) (stock done 0 :clay)))
    (is (contains? (:improvements (g/p done 0)) :fireplace-2))
    (is (= (:goods (g/p s 0)) (:goods (g/p (resolve! s {:skip? true}) 0))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! (assoc-in s [:players 0 :goods :food] 0) {:improvement :fireplace-2})))
    (let [done (resolve! (assoc-in s [:players 0 :goods :food] 0) {:card (cid "G" 110)})]
      (is (zero? (stock done 0 :food)))
      (is (some #{(cid "G" 110)} (:minors (g/p done 0)))))))

(deftest landscape-gardener-sows-two-card-fields-but-does-not-score-them-as-fields
  (let [s (scenario "landscape-gardener") card (cid "FR" 89) second-card (keyword (namespace card) (str (name card) "-2"))
        done (-> s reload (resolve! {}) (resolve! {:plantings [{:cell card :crop :grain} {:cell second-card :crop :vegetable}]}))]
    (is (= 3 (get-in done [:players 0 :card-fields card :count])))
    (is (= 2 (get-in done [:players 0 :card-fields second-card :count])))
    (is (= 0 (expr/evaluate done 0 {} [:field-tiles])))
    (is (= 2 (expr/evaluate done 0 {} [:fields])))
    (is (= -1 (get-in (a/score (g/p done 0)) [:breakdown :fields])))
    (let [harvest (a/field-harvest (g/p done 0))]
      (is (= (inc (stock done 0 :grain)) (a/amount harvest :grain)))
      (is (= (inc (stock done 0 :vegetable)) (a/amount harvest :vegetable))))
    (is (= 2 (count (:card-fields (g/p (resolve! s {:skip? true}) 0)))))))

(deftest stage-star-grants-wood-in-stage-one-and-pays-other-traveling-players
  (doseq [round [1 4 5 14]]
    (let [s (assoc (rich (fresh)) :round round) done (play s (cid "FR" 109))]
      (is (= (+ (stock s 0 :wood) (if (<= round 4) 6 0)) (stock done 0 :wood)))))
  (let [s (scenario "stage-star") done (g/command s 1 {:type :action :space :traveling})]
    (is (= (dec (stock s 0 :wood)) (stock done 0 :wood)))
    (is (= (inc (stock s 1 :wood)) (stock done 1 :wood)))
    (is (= 0 (stock (emit (assoc-in s [:players 0 :goods :wood] 0) :use-space 1 {:space :traveling}) 0 :wood)))
    (is (= (stock s 0 :wood) (stock (emit s :use-space 0 {:space :traveling}) 0 :wood)))))

(deftest wealthiest-european-queues-one-building-resource-per-completed-round-only-as-first
  (let [s (scenario "wealthiest-european") done (resolve! (reload s) {:resources [:wood :clay :stone]})]
    (doseq [resource [:wood :clay :stone]] (is (= (inc (stock s 0 resource)) (stock done 0 resource))))
    (is (= (stock s 0 :reed) (stock done 0 :reed)))
    (is (nil? (op done)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:resources [:food :wood :wood]}))))
  (is (nil? (op (play (rich (fresh)) (cid "FR" 117)))))
  (is (nil? (op (play (-> (fresh) rich (assoc :round 14) (own 0 (cid "G" 5))) (cid "FR" 117))))))
