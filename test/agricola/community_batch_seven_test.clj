(ns agricola.community-batch-seven-test
  (:require [clojure.test :refer :all] [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [agricola.bot :as bot] [eurozone.card-scenarios :as scenarios]
            [agricola.community-decks-test :refer [cid fresh rich own play reload resolve! emit op stock field]]))
(defn scenario [id] (scenarios/initial-state id (:names (scenarios/options id "Ada"))))
(defn stored [s card good] (get-in s [:players 0 :card-state card :stored-goods good] 0))
(defn harvest [s] (g/drain (g/crop-harvest s [0] {})))

(deftest private-market-charges-two-food-once-per-round-for-each-building-resource
  (let [card (cid "G" 95) s (scenario "private-market")]
    (doseq [[option resource] (map-indexed vector [:wood :clay :reed :stone])]
      (let [cmd {:type :ability :card card :option option}
            done (g/command (reload s) 0 cmd)]
        (is (= (- (stock s 0 :food) 2) (stock done 0 :food)))
        (is (= (inc (stock s 0 resource)) (stock done 0 resource)))
        (is (zero? (g/exchange-remaining done (g/p done 0) card)))
        (is (thrown? clojure.lang.ExceptionInfo (g/command done 0 cmd)))
        (is (thrown? clojure.lang.ExceptionInfo (g/command s 0 (assoc cmd :times 2))))
        (let [next-round (g/drain (g/begin-round done))]
          (is (= 1 (g/exchange-remaining next-round (g/p next-round 0) card)))
          (is (= (+ 2 (stock s 0 resource)) (stock (g/command next-round 0 cmd) 0 resource))))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/command (assoc-in s [:players 0 :goods :food] 1) 0 {:type :ability :card card :option 0})))
    (is (= {:wood 1 :clay 1} (:printed-cost (c/card card))))
    (is (= 1 (:points (c/card card))))))

(deftest grain-market-harvests-once-including-manure-and-card-fields
  (let [s (scenario "grain-market") done (resolve! (reload s) {})]
    (is (= :exchange (op s)))
    (is (= (dec (stock s 0 :grain)) (stock done 0 :grain)))
    (is (= (inc (stock s 0 :vegetable)) (stock done 0 :vegetable)))
    (is (= :feeding (:phase done)))
    (is (= (:goods (g/p s 0)) (:goods (g/p (resolve! s {:skip? true}) 0)))))
  (let [card (cid "G" 78) s (-> (fresh) rich (own 0 card))]
    (doseq [crop [:vegetable :wood]]
      (is (nil? (op (harvest (field s 0 crop 1))))))
    (is (nil? (op (harvest s))))
    (is (= :exchange (op (g/drain (g/manure-harvest (field s 0 :grain 1) 0)))))
    (is (= :exchange (op (harvest (assoc-in s [:players 0 :card-fields :grain-card] {:type :field :crop :grain :count 1})))))
    (is (nil? (op (emit s :receive-goods 0 {:goods {:grain 1}}))))
    (is (nil? (op (emit s :harvest-crops 1 {:harvested-grain-fields 1}))))
    (is (nil? (op (g/drain (g/run-effect (field s 0 :grain 1)
                                        (g/task 0 nil {:op :take-field-crop :crop :grain} {}) {:cell 0})))))
    (is (thrown? clojure.lang.ExceptionInfo (play (-> s (assoc-in [:players 0 :minors] []) (field 0 :grain 3)) card)))
    (let [ready (-> (fresh) rich (field 0 :grain 1) (field 1 :grain 1) (field 2 :grain 1))]
      (is (some #{card} (c/owned (g/p (play ready card) 0)))))))

(deftest harvest-helper-grain-is-harvested-but-does-not-activate-farm-school
  (let [s (-> (fresh) rich (own 0 (cid "G" 78)) (own 0 (cid "WM" 78))
              (assoc-in [:players 1 :farm 0] {:type :field :crop :grain :count 2})
              (assoc :phase :feeding))
        taken (g/drain (g/run-effect s (g/task 0 :catalog/original-base-i-223-harvest-helper
                                              {:op :harvest-helper} {}) {:owner 1 :cell 0}))
        done (resolve! taken {})]
    (is (= (cid "G" 78) (get-in taken [:effect-queue 0 :card])))
    (is (= (stock s 0 :grain) (stock done 0 :grain)))
    (is (= (inc (stock s 0 :vegetable)) (stock done 0 :vegetable)))
    (is (nil? (op done)))))

(deftest grain-elevator-stores-to-player-cap-and-resets-on-either-users-action
  (let [card (cid "WM" 88)]
    (doseq [n (range 1 6)]
      (let [s (-> (g/initialize (a/new-game (mapv str (range n)) (vec (mapcat identity a/stages)) 0))
                  rich (play card))
            full (nth (iterate #(g/drain (g/begin-round %)) s) (+ n 2))]
        (is (= n (stored full card :grain)))
        (is (= (stock s 0 :grain) (stock full 0 :grain)))
        (is (zero? (stored s card :grain)))))
    (let [s (scenario "grain-elevator") done (g/command (reload s) 0 {:type :action :space :grain})]
      (is (= 2 (stored s card :grain)))
      (is (= (+ 3 (stock s 0 :grain)) (stock done 0 :grain)))
      (is (zero? (stored done card :grain)))
      (is (= 1 (stored (g/drain (g/begin-round done)) card :grain)))
      (is (= 2 (stored (emit s :receive-goods 0 {:goods {:grain 1}}) card :grain))))
    (let [s (scenario "grain-elevator-opponent") done (g/command (reload s) 1 {:type :action :space :grain})]
      (is (= (stock s 0 :grain) (stock done 0 :grain)))
      (is (= (inc (stock s 1 :grain)) (stock done 1 :grain)))
      (is (zero? (stored done card :grain)))
      (is (nil? (op done))))))

(deftest farm-school-counts-harvested-goods-not-receipts-or-stack-reductions
  (let [s (scenario "farm-school") card (cid "G" 5)
        done (resolve! (reload s) {:card card})]
    (is (= :card-action (op s)))
    (is (= (- (stock s 0 :food) 2) (stock done 0 :food)))
    (is (some #{card} (get-in done [:players 0 :occupations])))
    (is (= :feeding (:phase done)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:card (cid "G" 95)})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! (assoc-in s [:players 0 :goods :food] 1) {:card card})))
    (is (= (:goods (g/p s 0)) (:goods (g/p (resolve! s {:skip? true}) 0)))))
  (let [s (-> (fresh) rich (own 0 (cid "WM" 78)) (scenarios/put-in-hand 0 (cid "G" 5)))]
    (doseq [[crop preserve] [[:grain false] [:vegetable false] [:wood false] [:vegetable true]]]
      (let [ready (-> s (field 0 crop 1)
                       (assoc-in [:players 0 :card-fields :test-field] {:type :field :crop :wood :count 1})
                       (cond-> preserve (own 0 :catalog/original-base-i-226-gardener)))
            pending (harvest ready)]
        (is (= 2 (get-in pending [:effect-queue 0 :context :harvested-goods])))
        (is (= :card-action (op pending)))
        (is (nil? (op (g/drain (g/manure-harvest ready 0)))))))
    (is (nil? (op (harvest (field s 0 :grain 8)))))
    (is (nil? (op (emit s :receive-goods 0 {:goods {:grain 2}}))))
    (is (nil? (op (emit s :harvest-crops 1 {:harvested-goods 2}))))
    (is (thrown? clojure.lang.ExceptionInfo (play (rich (fresh)) (cid "WM" 78))))
    (is (thrown? clojure.lang.ExceptionInfo (play (-> (fresh) rich (own 0 (c/id :a109))) (cid "WM" 78))))))

(deftest brickyard-accumulates-only-with-empty-supply-and-collects-at-four
  (let [card (cid "FR" 11) s (scenario "brickyard") cmd {:type :ability :card card}
        grown (g/command (reload s) 0 {:type :action :space :grain})
        done (g/command (reload grown) 0 cmd)]
    (is (= 3 (stored s card :clay)))
    (is (false? (g/anytime-available? s (g/p s 0) card)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command s 0 cmd)))
    (is (= 4 (stored grown card :clay)))
    (is (= 7 (:round grown)))
    (is (g/anytime-available? grown (g/p grown 0) card))
    (is (some #{cmd} (bot/ability-candidates grown 0)))
    (is (= 4 (stock done 0 :clay)))
    (is (zero? (stored done card :clay)))
    (is (some #{card} (c/active-owned (g/p done 0))))
    (is (zero? (stored (emit done :round-start 0) card :clay)))
    (let [spent (update-in done [:players 0] a/pay {:clay 4})
          resumed (emit spent :round-start 0)]
      (is (= 1 (stored resumed card :clay))))
    (is (= 3 (stored (emit s :round-start 1) card :clay)))
    (is (= 3 (stored (emit (assoc-in s [:players 0 :goods :clay] 1) :round-start 0) card :clay)))
    (let [saved (nth (iterate #(emit % :round-start 0) grown) 2)]
      (is (= 6 (stock (g/command saved 0 cmd) 0 :clay)))))
  (is (thrown? clojure.lang.ExceptionInfo (play (rich (fresh)) (cid "FR" 11)))))

(deftest journeyman-offers-one-animal-with-stone-house-only
  (let [s (scenario "journeyman")]
    (doseq [[option animal] (map-indexed vector [:sheep :boar :cattle])]
      (let [done (resolve! (reload s) {:option option})]
        (is (= (dec (stock s 0 :food)) (stock done 0 :food)))
        (is (= 1 (stock done 0 animal)))
        (is (= :work (:phase done)))
        (is (nil? (op done)))))
    (is (= (:goods (g/p s 0)) (:goods (g/p (resolve! s {:skip? true}) 0)))))
  (let [s (-> (fresh) rich (play (cid "FR" 88)))]
    (doseq [house [:wood :clay]]
      (is (nil? (op (emit (assoc-in s [:players 0 :house] house) :round-start 0)))))
    (is (nil? (op (emit (assoc-in s [:players 0 :house] :stone) :round-start 1))))
    (let [offer (emit (-> s (assoc-in [:players 0 :house] :stone) (assoc-in [:players 0 :goods] {})) :round-start 0)]
      (is (thrown? clojure.lang.ExceptionInfo (resolve! offer {:option 0})))
      (is (nil? (op (resolve! offer {:skip? true})))))
    (let [offer (emit (-> s (assoc-in [:players 0 :house] :stone) (assoc-in [:players 0 :goods :sheep] 1)) :round-start 0)
          overcrowded (resolve! offer {:option 2})]
      (is (= :housing (op overcrowded)))))
  (is (= 3 (:min-players (c/card (cid "FR" 88))))))
