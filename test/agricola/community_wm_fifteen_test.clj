(ns agricola.community-wm-fifteen-test
  (:require [clojure.test :refer :all] [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [eurozone.card-scenarios :as s] [agricola.bot :as bot]))
(defn table [id] (s/initial-state id (:names (s/options id "Ada"))))
(defn resolve! [state choice] (g/command state (g/acting-player state) (merge {:type :resolve :decision (:decision-id state)} choice)))
(def borrower :catalog/original-expansion-wm-6-borrower)
(def minder :catalog/original-expansion-wm-47-storehouse-minder)
(def cash :catalog/original-expansion-wm-68-cash-crop)
(deftest origin-sensitive-begging
  (let [x (table "borrower") p (g/p x 0)
        p (-> p (update :begging + 3) (assoc-in [:begging-origins :feeding] 1))
        x (assoc-in x [:players 0] p)
        y (g/ability x 0 {:card borrower}) z (g/ability y 0 {:card borrower})
        done (g/ability z 0 {:card borrower})]
    (is (= 3 (g/eligible-begging p true)))
    (is (= 1 (g/eligible-begging p false)))
    (is (= 2 (:begging (g/p done 0))))
    (is (thrown? clojure.lang.ExceptionInfo (g/ability done 0 {:card borrower}))))
  (let [x (table "almshouse") y (g/command x 0 {:type :feed}) z (resolve! y {})]
    (is (= 3 (get-in y [:players 0 :begging-origins :feeding])))
    (is (= 2 (get-in z [:players 0 :begging])))))
(deftest family-reservations-and-arrivals
  (let [x (table "family-planner") y (resolve! x {:rounds [5 8 11]})]
    (is (= 0 (a/unused-person-tokens (g/p y 0))))
    (is (thrown? clojure.lang.ExceptionInfo (a/grow (g/p y 0) false)))
    (let [ready (-> y (update-in [:players 0] a/build-rooms [0]) (s/prepared-round 5))
          grown (resolve! ready {})]
      (is (= :grow (get-in ready [:effect-queue 0 :effect :op])))
      (is (= 3 (get-in grown [:players 0 :family])))
      (is (= 1 (get-in grown [:players 0 :newborn])))
      (is (= 2 (get-in grown [:players 0 :remaining]))))
    (let [missed (s/prepared-round y 5)]
      (is (= 2 (get-in missed [:players 0 :family])))
      (is (= 1 (a/unused-person-tokens (g/p missed 0))))))
  (let [x (table "lady-in-waiting") y (resolve! x {})]
    (is (= 3 (get-in y [:players 0 :family])))
    (is (= 3 (get-in y [:players 0 :remaining])))
    (is (zero? (get-in y [:players 0 :newborn])))
    (is (= (- (get-in x [:players 0 :goods :food]) 2) (get-in y [:players 0 :goods :food])))))
(deftest storehouse-growth-and-withdrawal
  (let [x (resolve! (table "storehouse-minder") {:resources [:wood :clay :reed :stone]})
        y (-> x (s/prepared-round 4) s/last-harvest-action)
        z (resolve! y {:resources [:wood :clay]})
        withdrawn (g/ability z 0 {:card minder :resource :wood})]
    (is (= {:wood 2 :clay 2 :reed 1 :stone 1} (get-in z [:players 0 :card-state minder :stored-goods])))
    (is (zero? (get-in withdrawn [:players 0 :card-state minder :stored-goods :wood])))
    (is (thrown? clojure.lang.ExceptionInfo (g/ability withdrawn 0 {:card minder :resource :clay})))
    (is (= (+ 2 (a/amount (g/p z 0) :clay)) (a/craft-stock (g/p z 0) :clay)))))
(deftest cash-crop-harvest-only-points
  (let [x (table "cash-crop") p (g/p x 0) y (g/command x 0 {:type :action :space :grain})
        normal (a/field-harvest p {:harvest? true}) manure (a/field-harvest p {:harvest? true :manure? true})]
    (is (= (inc (:bonus-points p)) (:bonus-points normal)))
    (is (= (a/amount p :grain) (a/amount normal :grain)))
    (is (= (inc (a/amount p :grain)) (a/amount manure :grain)))
    (is (= (inc (:bonus-points p)) (get-in y [:players 0 :bonus-points])))
    (is (= (:bonus-points normal) (:bonus-points (g/remove-owned-improvement normal cash))))))
(deftest spatial-actions-and-source-caps
  (let [x (table "chemist") y (resolve! x {:space :reed})]
    (is (= 1 (get-in y [:piles :reed :clay])))
    (is (= (+ 2 (get-in x [:players 0 :goods :food])) (get-in y [:players 0 :goods :food])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! x {:space :wood}))))
  (let [x (table "rotherham-plow") y (resolve! x {:cells [7]})]
    (is (= #{0 1 6 7} (set (a/tiles (g/p y 0) :field))))
    (is (seq (:plows (g/plow-options y 0 :plow))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! x {:cells [5]}))))
  (let [x (table "slapdash-renovation") y (resolve! x {})]
    (is (= 2 (get-in y [:players 0 :remaining])))
    (is (not (contains? (:occupied y) :renovate))))
  (let [x (resolve! (table "fish-pond") {:cell 0}) y (g/command x 0 {:type :action :space :fishing}) z (resolve! y {:amount 1})]
    (is (= :feature (get-in z [:players 0 :farm 0 :type])))
    (is (= 1 (get-in z [:players 0 :card-state :catalog/original-expansion-wm-81-fish-pond :stored-goods :food])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! y {:amount 2})))))
(deftest new-decisions-have-bot-choices
  (doseq [id ["chemist" "family-planner" "lady-in-waiting" "storehouse-minder" "fish-pond" "rotherham-plow" "slapdash-renovation"]]
    (let [x (table id)]
      (is (some (fn [cmd] (try (g/command x (g/acting-player x) cmd) true (catch Exception _ false)))
                (bot/decision-candidates x (g/acting-player x))) id))))
(deftest chemist-five-player-market-and-fresh-clay
  (let [cid :catalog/original-expansion-wm-9-chemist
        x (-> (g/initialize (a/new-game ["A" "B" "C" "D" "E"] (vec (mapcat identity a/stages)) 0))
              (update-in [:players 0] a/gain {:clay 1 :food 10}) (s/played 0 cid))
        pending (g/command x 0 {:type :action :space :market})
        y (resolve! pending {:space :reed})]
    (is (= :chemist (get-in pending [:effect-queue 0 :effect :op])))
    (is (= 1 (get-in y [:piles :reed :clay]))))
  (let [x (-> (table "chemist") (assoc-in [:players 0 :goods :clay] 1))]
    (is (seq (g/chemist-spaces x 0 {:space :wood :source-goods {:wood 3 :clay 1}})))))
(deftest unplayed-deck-begging-and-historical-shape
  (let [x (assoc (g/initialize (a/new-game ["A" "B"] (vec (mapcat identity a/stages)) 0)) :phase :feeding :fed #{} :minor-deck [] :occupation-deck [])
        x (assoc-in x [:players 0 :goods :food] 0)
        plain (a/feed x 0 {}) tracked (a/feed (assoc x :minor-deck [:catalog/original-expansion-wm-56-almshouse]) 0 {})]
    (is (not (contains? (g/p plain 0) :begging-origins)))
    (is (= 4 (get-in tracked [:players 0 :begging-origins :feeding])))))
(deftest print-identity-and-scoring-symbol
  (is (= {:wood 1 :reed 1} (:printed-cost (c/card :catalog/original-expansion-wm-56-almshouse))))
  (is (= 3 (:min-players (c/card minder))))
  (is (c/bonus-symbol? cash)))
