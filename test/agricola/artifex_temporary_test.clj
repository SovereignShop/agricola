(ns agricola.artifex-temporary-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot]
            [agricola.expansion-cards-test :refer [fresh own hand delta]]))
(defn resolve! [state choice] (g/command state (g/acting-player state) (merge {:type :resolve :decision (:decision-id state)} choice)))
(defn op [state] (get-in state [:effect-queue 0 :effect :op]))
(defn reload [state] (edn/read-string (pr-str state)))
(defn rich [state pid] (update-in state [:players pid] a/gain {:wood 30 :clay 30 :reed 20 :stone 30 :grain 5 :food 10}))

(deftest riparian-builder-is-one-discounted-room-on-another-players-reed-bank
  (doseq [[house cost] [[:wood 5] [:clay 4] [:stone 3]]]
    (let [state (-> (fresh) (rich 0) (own 0 :a128) (assoc :turn 1) (assoc-in [:players 0 :house] house))
          pending (g/command state 1 {:type :action :space :reed})
          done (resolve! (reload pending) {:rooms [0]})]
      (is (= :build-rooms (op pending))) (is (= 0 (g/acting-player pending)))
      (is (= (- cost) (delta pending done house)))
      (is (= -2 (delta pending done :reed)))
      (is (= 2 (get-in done [:players 0 :remaining])))
      (is (= 1 (get-in done [:players 1 :remaining])))
      (is (= 2 (:turn done)))
      (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:rooms [0 1]})))))
  (let [state (-> (fresh) (rich 0) (own 0 :a128))]
    (is (empty? (:effect-queue (g/command state 0 {:type :action :space :reed}))))
    (is (empty? (:effect-queue (g/command state 0 {:type :action :space :wood}))))))

(deftest stagehand-has-three-saved-construction-workflows
  (doseq [[option choice expected] [[0 {:rooms [0]} :room] [1 {:stables [0 1]} :stable]
                                    [2 {:fences (vec (a/edges 0))} :fence]]]
    (let [state (-> (fresh) (rich 0) (own 0 :a150) (assoc :turn 1))
          pending (g/command state 1 {:type :action :space :traveling})
          selected (resolve! (reload pending) {:option option})
          done (resolve! (reload selected) choice)]
      (is (= :choose (op pending)))
      (is (= ({:room :build-rooms :stable :build-stables :fence :build-fences} expected) (op selected)))
      (is (= 2 (:turn done)))
      (is (= 2 (get-in done [:players 0 :remaining])))
      (is (= {:traveling 1} (:occupied done)))
      (case expected
        :room (is (= :room (get-in done [:players 0 :farm 0 :type])))
        :stable (do (is (true? (get-in done [:players 0 :farm 0 :stable]))) (is (= -4 (delta state done :wood))))
        :fence (is (= (set (a/edges 0)) (get-in done [:players 0 :fences])))))))

(deftest stagehand-does-not-trigger-own-turn-stable-bonuses
  (let [state (-> (fresh) (rich 0) (own 0 :a150) (own 0 :a043) (own 0 :a074) (assoc :turn 1))
        pending (g/command state 1 {:type :action :space :traveling})
        chosen (resolve! pending {:option 1}) done (resolve! chosen {:stables [0 1]})]
    (is (empty? (get-in done [:players 0 :schedule])))
    (is (some #(= [0 1] (:stables %)) (bot/decision-candidates chosen 0))))
  (let [state (-> (fresh) (rich 0) (own 0 :a150))
        done (g/command state 0 {:type :action :space :traveling})]
    (is (empty? (:effect-queue done)))))

(deftest stable-planner-reserves-physical-pieces-until-their-rounds
  (let [state (-> (fresh) (hand :a089) (update-in [:players 0] a/build-stables [0] 0))
        pending (g/drain (g/play-card state 0 (c/id :a089) 0 {}))
        saved (resolve! pending {:rounds [4 7 10]})
        player (g/p saved 0)]
    (is (= :reserve-stables (op pending)))
    (is (= {4 1 7 1 10 1} (:reserved-stables player)))
    (is (= 0 (a/stables-available player)))
    (is (thrown? clojure.lang.ExceptionInfo (a/build-stables player [1] 0)))
    (is (= {:stable 1} (get-in (g/future-round-resources saved) [4 0 :goods])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:rounds [4 4]})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:rounds [5]})))
    (let [arrival (g/drain (g/begin-round (assoc (reload saved) :round 3)))
          done (resolve! arrival {:cell 1})]
      (is (= :stable (op arrival)))
      (is (= {7 1 10 1} (get-in arrival [:players 0 :reserved-stables])))
      (is (= 1 (a/stables-available (g/p arrival 0))))
      (is (true? (get-in done [:players 0 :farm 1 :stable])))
      (is (= 0 (delta arrival done :wood)))
      (is (= 0 (a/stables-available (g/p done 0))))
      (is (= :work (:phase done))))))

(deftest stable-planner-late-play-skip-and-no-off-turn-bonuses
  (let [state (-> (fresh) (hand :a089) (own 0 :a043) (own 0 :a074) (assoc :round 8))
        pending (g/drain (g/play-card state 0 (c/id :a089) 0 {}))
        saved (resolve! pending {:rounds [11 14]})
        arrival (g/drain (g/begin-round (assoc saved :round 10)))
        done (resolve! arrival {:cell 0})]
    (is (= [11 14] (g/planner-rounds pending)))
    (is (nil? (get-in done [:players 0 :schedule 12])))
    (is (= 2 (a/stables-available (g/p done 0)))))
  (let [pending (g/drain (g/play-card (-> (fresh) (hand :a089)) 0 (c/id :a089) 0 {}))
        saved (resolve! pending {:rounds [4]})
        arrival (g/drain (g/begin-round (assoc saved :round 3)))
        done (resolve! arrival {:skip? true})]
    (is (= 4 (a/stables-available (g/p done 0))))
    (is (empty? (get-in done [:players 0 :reserved-stables]))))
  (let [done (g/drain (g/play-card (-> (fresh) (hand :a089) (assoc :round 12)) 0 (c/id :a089) 0 {}))]
    (is (empty? (:effect-queue done)))))

(deftest telegram-uses-fences-still-in-supply-and-schedules-one-person
  (let [state (-> (fresh) (hand :a022) (assoc-in [:players 0 :discarded-fences] 12))
        played (g/drain (g/play-card state 0 (c/id :a022) 0 {}))
        offer (g/drain (g/begin-round (assoc (reload played) :round 3)))
        arrival (resolve! offer {})
        returned (g/prepare-return-home arrival)]
    (is (= 4 (get-in played [:players 0 :card-state (c/id :a022) :arrival-round])))
    (is (= -2 (delta state played :food)))
    (is (= {:person 1} (get-in (g/future-round-resources played) [4 0 :goods])))
    (is (= 3 (get-in arrival [:players 0 :family])))
    (is (= 3 (get-in arrival [:players 0 :remaining])))
    (is (= 0 (get-in arrival [:players 0 :newborn])))
    (is (= 2 (get-in returned [:players 0 :family])))
    (is (= 4 (a/food-needed returned (g/p returned 0))))
    (is (= 5 (a/family-limit (g/p returned 0)))))
  (is (thrown? clojure.lang.ExceptionInfo
               (g/play-card (-> (fresh) (hand :a022) (assoc-in [:players 0 :discarded-fences] 15)) 0 (c/id :a022) 0 {}))))

(deftest telegram-never-creates-a-sixth-person-or-a-newborn
  (doseq [family [4 5]]
    (let [state (-> (fresh) (hand :a022) (assoc-in [:players 0 :discarded-fences] 14)
                    (assoc-in [:players 0 :family] family))
          played (g/drain (g/play-card state 0 (c/id :a022) 0 {}))
          offer (g/drain (g/begin-round played))
          arrival (if (= :temporary-person (op offer)) (resolve! offer {}) offer)
          returned (g/prepare-return-home arrival)]
      (is (= 5 (get-in arrival [:players 0 :family])))
      (is (= family (get-in returned [:players 0 :family])))
      (is (thrown? clojure.lang.ExceptionInfo (a/grow (g/p arrival 0) false)))))
  (let [played (g/drain (g/play-card (-> (fresh) (hand :a022)) 0 (c/id :a022) 0 {}))]
    (is (= 16 (get-in played [:players 0 :card-state (c/id :a022) :arrival-round])))
    (is (empty? (get-in played [:players 0 :schedule])))))

(deftest lodger-room-expires-at-round-nine-and-can-permanently-remove-one-person
  (let [state (-> (fresh) (hand :a127) (assoc :round 8))
        played (g/drain (g/play-card state 0 (c/id :a127) 0 {}))
        grown (-> played (update-in [:players 0] a/grow true) (assoc-in [:players 0 :newborn] 0))
        early (g/prepare-return-home grown)
        expired (g/prepare-return-home (assoc grown :round 9))]
    (is (= 3 (a/family-capacity (g/p played 0))))
    (is (= 2 (count (a/tiles (g/p played 0) :room))))
    (is (= 3 (get-in early [:players 0 :family])))
    (is (= 2 (get-in expired [:players 0 :family])))
    (is (= 4 (a/family-limit (g/p expired 0))))
    (is (= 2 (a/family-capacity (g/p expired 0))))
    (let [p (-> (g/p expired 0) (a/grow false) (a/grow false))]
      (is (= 4 (:family p)))
      (is (thrown? clojure.lang.ExceptionInfo (a/grow p false))))))

(deftest lodger-preserves-person-if-another-room-exists-and-does-nothing-after-nine
  (let [state (-> (fresh) (hand :a127) (assoc :round 9))
        played (g/drain (g/play-card state 0 (c/id :a127) 0 {}))
        ready (-> played (update-in [:players 0] a/grow true) (update-in [:players 0] a/place-tile :room 0))
        expired (g/prepare-return-home ready)]
    (is (= 3 (get-in expired [:players 0 :family])))
    (is (= 5 (a/family-limit (g/p expired 0))))
    (is (= 3 (a/family-capacity (g/p expired 0)))))
  (doseq [[round phase] [[10 :work] [9 :return-home]]]
    (let [played (g/drain (g/play-card (-> (fresh) (hand :a127) (assoc :round round :phase phase)) 0 (c/id :a127) 0 {}))]
      (is (= 2 (a/family-capacity (g/p played 0)))))))

(deftest lodger-saves-a-choice-between-an-adult-and-newborn-occupant
  (let [state (-> (fresh) (hand :a127) (assoc :round 9))
        played (g/drain (g/play-card state 0 (c/id :a127) 0 {}))
        grown (update-in played [:players 0] a/grow true)
        pending (g/prepare-return-home grown)
        newborn (resolve! (reload pending) {:person-kind :newborn})
        adult (resolve! pending {:person-kind :adult})]
    (is (= :lodger-remove (op pending)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:skip? true})))
    (is (= 0 (get-in newborn [:players 0 :newborn])))
    (is (= 1 (get-in adult [:players 0 :newborn])))
    (is (= 4 (a/food-needed newborn (g/p newborn 0))))
    (is (= 3 (a/food-needed adult (g/p adult 0))))
    (is (= 4 (a/family-limit (g/p newborn 0))))))
