(ns agricola.artifex-construction-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(defn rich [state] (update-in state [:players 0] a/gain {:wood 30 :clay 30 :reed 20 :stone 30 :grain 5 :food 10}))
(defn reload [state] (edn/read-string (pr-str state)))
(defn op [state] (get-in state [:effect-queue 0 :effect :op]))

(deftest family-friend-home-checks-room-count-before-building
  (let [state (-> (fresh) rich (hand :a021))]
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 (c/id :a021) 0 {})))
    (is (c/has? (g/p (g/play-card (own state 0 :a103) 0 (c/id :a021) 0 {}) 0) :a021)))
  (let [state (-> (fresh) rich (own 0 :a021))
        no-growth (g/command state 0 {:type :action :space :build :rooms [0 1]})
        room-ready (update-in state [:players 0] a/place-tile :room 0)
        pending (g/command room-ready 0 {:type :action :space :build :rooms [1 2]})
        done (resolve! (reload pending) {})]
    (is (= 2 (get-in no-growth [:players 0 :family])))
    (is (= 0 (delta state no-growth :food)))
    (is (= :grow (op pending)))
    (is (= 1 (delta room-ready pending :food)))
    (is (= 3 (get-in done [:players 0 :family])))
    (is (= 1 (get-in done [:players 0 :newborn])))
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (= 1 (:turn done)))
    (is (= 2 (get-in (resolve! pending {:skip? true}) [:players 0 :family])))))

(deftest bed-maker-is-one-paid-growth-per-room-action
  (let [state (-> (fresh) rich (own 0 :a093))
        pending (g/command state 0 {:type :action :space :build :rooms [0 1]})
        done (resolve! (reload pending) {})]
    (is (= :grow (op pending)))
    (is (= -1 (delta pending done :wood)))
    (is (= -1 (delta pending done :grain)))
    (is (= 3 (get-in done [:players 0 :family])))
    (is (empty? (:effect-queue done)))
    (is (= 0 (delta pending (resolve! pending {:skip? true}) :grain)))
    (is (some #(and (= :resolve (:type %)) (not (:skip? %))) (bot/decision-candidates pending 0))))
  (let [state (-> (fresh) rich (own 0 :a093) (assoc-in [:players 0 :family] 5))
        done (g/command state 0 {:type :action :space :build :rooms [0]})]
    (is (= 5 (get-in done [:players 0 :family])))
    (is (= 0 (delta state done :grain)))
    (is (empty? (:effect-queue done)))))

(deftest bed-maker-and-family-home-share-a-reorderable-window
  (let [state (-> (fresh) rich (own 0 :a021) (own 0 :a093)
                  (update-in [:players 0] a/place-tile :room 0))
        pending (g/command state 0 {:type :action :space :build :rooms [1]})
        ordered (g/command pending 0 {:type :order-effect :card (c/id :a093)})
        first-growth (resolve! ordered {}) done (resolve! first-growth {})]
    (is (= #{(c/id :a021) (c/id :a093)} (set (g/order-options pending))))
    (is (= 4 (get-in done [:players 0 :family])))
    (is (= 2 (get-in done [:players 0 :newborn])))
    (is (= 1 (:turn done)))))

(deftest house-artist-builds-multiple-discounted-rooms-and-chains-growth
  (let [state (-> (fresh) rich (own 0 :a149) (own 0 :a093))
        pending (g/command state 0 {:type :action :space :traveling})
        rooms (resolve! (reload pending) {:rooms [0 1]})
        done (resolve! rooms {})]
    (is (= :build-rooms (op pending)))
    (is (= -2 (delta pending rooms :reed)))
    (is (= -10 (delta pending rooms :wood)))
    (is (= 4 (count (a/tiles (g/p rooms 0) :room))))
    (is (= :grow (op rooms)))
    (is (= 3 (get-in done [:players 0 :family])))
    (is (= {:traveling 0} (:occupied done)))
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (some #(seq (:rooms %)) (bot/decision-candidates pending 0)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:rooms []})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:rooms [4]}))))
  (let [state (-> (fresh) (own 0 :a149) (assoc-in [:players 0 :goods :wood] 5) (assoc-in [:players 0 :goods :reed] 1))
        pending (g/command state 0 {:type :action :space :traveling})
        done (resolve! pending {:rooms [0] :room-discount {:wood 5}})]
    (is (= 0 (a/amount (g/p done 0) :wood)) "Clients cannot change the trusted discount")
    (is (= 0 (a/amount (g/p done 0) :reed))))
  (let [state (-> (fresh) rich (own 0 :a149))]
    (is (= -2 (delta state (g/command state 0 {:type :action :space :build :rooms [0]}) :reed)))
    (is (empty? (:effect-queue (g/command state 0 {:type :action :space :fishing}))))))

(deftest breeder-buyer-selects-animal-from-actual-construction-material
  (doseq [[house animal] [[:wood :sheep] [:clay :boar] [:stone :cattle]]]
    (let [state (-> (fresh) rich (own 0 :a167) (assoc-in [:players 0 :house] house))
          done (g/command state 0 {:type :action :space :build :rooms [0 1] :stables [2 3]})]
      (is (= 1 (delta state done animal)) (str house))
      (is (= 1 (get-in done [:players 0 :remaining])))
      (is (= 1 (:turn done)))))
  (doseq [selection [{:rooms [0]} {:stables [0]}]]
    (let [state (-> (fresh) rich (own 0 :a167))
          done (g/command state 0 (merge {:type :action :space :build} selection))]
      (is (every? zero? (map #(delta state done %) a/animals))))))

(deftest breeder-buyer-counts-nested-actions-but-never-previous-turns
  (let [state (-> (fresh) rich (own 0 :a167) (own 0 :a149))
        pending (g/command state 0 {:type :action :space :traveling})
        ;; A native free stable effect nested in this turn, before House Artist.
        stable (g/drain (g/queue-front pending [(g/task 0 (c/id :a001) {:op :stable :optional? true} {})]))
        with-stable (resolve! stable {:cell 2})
        done (resolve! (reload with-stable) {:rooms [0]})]
    (is (= 1 (delta state done :sheep)))
    (is (nil? (:turn-construction done)) "Construction clears when the turn advances")
    (let [again (g/drain (g/queue-front done [(g/task 0 nil {:op :stable :optional? true} {})]))
          after (resolve! again {:cell 3})]
      (is (= 0 (delta done after :sheep)))))
  (let [state (-> (fresh) rich (own 0 :a167))
        stable (g/command state 0 {:type :action :space :build :stables [2]})
        next-turn (-> stable (assoc :turn 0) (update :occupied dissoc :build))
        room (g/command (reload next-turn) 0 {:type :action :space :build :rooms [0]})]
    (is (= 0 (delta stable room :sheep))))
  (let [state (-> (fresh) rich (own 0 :a167) (assoc-in [:players 0 :goods :sheep] 1))
        pending (g/command state 0 {:type :action :space :build :rooms [0] :stables [2]})]
    (is (= 2 (a/amount (g/p pending 0) :sheep)))
    (is (empty? (:effect-queue pending)) "Pet plus stable houses both sheep")))

(deftest house-artist-multiple-rooms-trigger-fertilizers-as-one-action
  (let [state (-> (fresh) rich (own 0 :a149) (own 0 :a073)
                  (update-in [:players 0] a/place-tile :field 4))
        pending (g/command state 0 {:type :action :space :traveling})
        sow-offer (resolve! pending {:rooms [0 1]})
        sow (resolve! sow-offer {})
        done (resolve! sow {:plantings [{:cell 4 :crop :grain}]})]
    (is (= :sow-offer (op sow-offer)))
    (is (= :sow (op sow)))
    (is (= 3 (get-in done [:players 0 :farm 4 :count])))
    (is (= 1 (:turn done)))))

(deftest house-artist-with-hammer-may-afford-two-rooms-but-not-one
  (let [state (-> (fresh) (own 0 :a149) (own 0 :a014)
                  (update-in [:players 0] a/gain {:wood 8}))
        pending (g/command state 0 {:type :action :space :traveling})
        done (resolve! pending {:rooms [0 1]})]
    (is (= :build-rooms (op pending)) "Do not discard a legal batch construction offer")
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:rooms [0]})))
    (is (= 4 (count (a/tiles (g/p done 0) :room))))
    (is (= 0 (a/amount (g/p done 0) :wood)))
    (is (= 0 (a/amount (g/p done 0) :reed)))
    (is (some #(= [0 1] (:rooms %)) (bot/decision-candidates pending 0)))))

(deftest breeder-buyer-retains-construction-across-lassos-two-placements
  (let [state (-> (fresh) rich (own 0 :a167) (own 0 :b024)
                  (update :active #(vec (distinct (conj % :sheep))))
                  (assoc-in [:piles :sheep] {:sheep 1}))
        first-placement (g/take-action state 0 {:type :action :space :sheep :lasso? true})
        stable-offer (g/drain (g/queue-front first-placement [(g/task 0 nil {:op :stable :optional? true} {})]))
        second-placement (resolve! stable-offer {:cell 2})
        done (g/command (reload second-placement) 0 {:type :action :space :build :rooms [0]})]
    (is (true? (get-in second-placement [:lasso :second?])))
    (is (= 1 (get-in second-placement [:turn-construction 0 :stables])))
    (is (= 2 (a/amount (g/p done 0) :sheep)))
    (is (nil? (:turn-construction done)))
    (is (= 0 (get-in done [:players 0 :remaining])))))

(deftest breeder-buyer-does-not-combine-preparation-with-the-previous-turn
  (let [state (-> (fresh) rich (own 0 :a167) (assoc :phase :preparation)
                  (assoc :turn-construction {0 {:rooms [:wood]}}))
        pending (g/drain (g/queue-front state [(g/task 0 nil {:op :stable :optional? true} {})]))
        done (resolve! pending {:cell 2})]
    (is (= 0 (a/amount (g/p done 0) :sheep)))
    (is (nil? (:turn-construction (g/begin-round done))))))
