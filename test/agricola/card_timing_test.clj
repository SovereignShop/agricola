(ns agricola.card-timing-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.card-game-test :as fixture]))
(defn resolve! [state player choice]
  (g/command state player (merge {:type :resolve :decision (:decision-id state)} choice)))
(deftest unaffordable-effect-survives-another-card-in-its-window
  (doseq [order [[:a090 :b097] [:b097 :a090]]]
    (let [state (-> (fixture/game) (fixture/own 0 order) (fixture/hand 0 [:b016])
                    (assoc-in [:players 0 :house] :stone)
                    (assoc-in [:players 0 :goods :food] 0)
                    (assoc-in [:players 0 :goods :wood] 1))
          pending (g/drain (g/begin-round state))
          after (resolve! pending 0 {:card (c/id :b016)})
          restored (edn/read-string (pr-str after))
          plowed (resolve! restored 0 {:cell 4})]
      (is (= (c/id :b097) (get-in pending [:effect-queue 0 :card])))
      (is (= (c/id :a090) (get-in after [:effect-queue 0 :card])))
      (is (= 1 (a/amount (g/p after 0) :food)))
      (is (= 0 (a/amount (g/p plowed 0) :food)))
      (is (= :field (get-in plowed [:players 0 :farm 4 :type])))
      (is (empty? (:effect-queue plowed)))
      (is (thrown? clojure.lang.ExceptionInfo (resolve! plowed 0 {:cell 3}))))))
(deftest newly-played-scholar-effects-use-the-current-round-window
  (doseq [[code op] [[:a090 :plow] [:b089 :stable]]]
    (let [state (-> (fixture/game) (fixture/own 0 [:b097]) (fixture/hand 0 [code])
                    (assoc-in [:players 0 :house] :stone)
                    (assoc-in [:players 0 :goods :food] 2))
          pending (g/drain (g/begin-round state))
          after (resolve! pending 0 {:card (c/id code)})
          done (resolve! after 0 {:cell 4})]
      (is (= op (get-in after [:effect-queue 0 :effect :op])))
      (is (= (get-in pending [:effect-queue 0 :window]) (get-in after [:effect-queue 0 :window])))
      (is (if (= :plow op) (= :field (get-in done [:players 0 :farm 4 :type])) (true? (get-in done [:players 0 :farm 4 :stable]))))
      (is (empty? (:effect-queue done)))))
  (let [state (-> (fixture/game) (fixture/hand 0 [:a090])
                  (assoc-in [:players 0 :house] :stone) (assoc-in [:players 0 :goods :food] 3))
        played (g/command state 0 {:type :action :space :lessons :card (c/id :a090) :round-start-play? true})]
    (is (empty? (:effect-queue played)) "A client cannot claim Scholar timing on an ordinary occupation action")))
(deftest post-breeding-abilities-persist-and-precede-final-scoring
  (doseq [round [4 14]]
    (let [state (-> (fixture/game) (fixture/own 0 [:b104])
                    (assoc :phase :breeding :round round :fed #{0 1} :bred #{} :turn 0)
                    (assoc-in [:players 0 :goods :sheep] 1))
          first-bred (g/command state 0 {:type :breed :offspring {}})
          pending (g/command first-bred 1 {:type :breed :offspring {}})
          exchanged (g/command (edn/read-string (pr-str pending)) 0 {:type :ability :card (c/id :b104) :resource :vegetable})
          done (resolve! exchanged 0 {})]
      (is (= :finish-round (:phase pending)))
      (is (= :ability-window (get-in pending [:effect-queue 0 :effect :op])))
      (is (nil? (:scores pending)))
      (is (thrown? clojure.lang.ExceptionInfo (resolve! pending 1 {})))
      (is (= 0 (a/amount (g/p exchanged 0) :sheep)))
      (is (= 1 (a/amount (g/p exchanged 0) :vegetable)))
      (is (= (if (= round 14) :finished :work) (:phase done)))
      (when (= round 14) (is (= 1 (get-in done [:scores 0 :breakdown :vegetables])))))))
(deftest no-extra-prompt-without-an-available-ability
  (let [state (assoc (fixture/game) :phase :breeding :round 14 :fed #{0 1} :bred #{0} :turn 1)
        done (g/command state 1 {:type :breed :offspring {}})]
    (is (= :finished (:phase done)))))

(deftest adoptive-parents-can-be-used-after-the-growth-turn
  (let [state (-> (fixture/game) (fixture/hand 0 [:a092])
                  (assoc-in [:players 0 :family] 3) (assoc-in [:players 0 :newborn] 1)
                  (assoc-in [:players 0 :remaining] 1) (assoc-in [:players 0 :goods :food] 3))
        played (g/command state 0 {:type :action :space :lessons :card (c/id :a092)})
        adopted (g/command played 0 {:type :ability :card (c/id :a092)})]
    (is (= 0 (get-in adopted [:players 0 :newborn])))
    (is (= 1 (get-in adopted [:players 0 :remaining])))
    (is (= 2 (a/amount (g/p adopted 0) :food)))
    (is (= 6 (a/food-needed adopted (g/p adopted 0))))
    (is (thrown? clojure.lang.ExceptionInfo (g/command adopted 0 {:type :ability :card (c/id :a092)})))
    (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc played :phase :return-home) 0 {:type :ability :card (c/id :a092)})))))

(deftest food-conversion-can-fund-a-pending-optional-effect
  (let [state (-> (fixture/game) (fixture/own 0 [:a090])
                  (assoc-in [:players 0 :house] :stone)
                  (assoc-in [:players 0 :goods :food] 0)
                  (assoc-in [:players 0 :goods :grain] 1))
        pending (g/drain (g/begin-round state))
        funded (g/command pending 0 {:type :convert :goods {:grain 1}})
        plowed (resolve! funded 0 {:cell 4})]
    (is (= :preparation (:phase pending)))
    (is (= :plow (get-in pending [:effect-queue 0 :effect :op])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending 0 {:cell 4})))
    (is (= 1 (a/amount (g/p funded 0) :food)))
    (is (= :field (get-in plowed [:players 0 :farm 4 :type])))
    (is (= :work (:phase plowed)))))

(deftest firewood-collector-pays-after-baking-decisions
  (let [state (-> (fixture/game) (fixture/own 0 [:a119 :a024])
                  (assoc-in [:players 0 :goods :grain] 1)
                  (assoc-in [:players 0 :improvements] #{:clay-oven}))
        pending (g/command state 0 {:type :action :space :plow :cell 4})
        done (resolve! pending 0 {:grain 1})]
    (is (= :bake (get-in pending [:effect-queue 0 :effect :op])))
    (is (= 0 (a/amount (g/p pending 0) :wood)))
    (is (= 1 (a/amount (g/p done 0) :wood)))))

(deftest adoptive-parents-played-on-final-turn-can-still-activate
  (let [state (-> (fixture/game) (fixture/hand 0 [:a092])
                  (assoc-in [:players 0 :family] 3) (assoc-in [:players 0 :newborn] 1)
                  (assoc-in [:players 0 :remaining] 1) (assoc-in [:players 1 :remaining] 0)
                  (assoc-in [:players 0 :goods :food] 3))
        pending (g/command state 0 {:type :action :space :lessons :card (c/id :a092)})
        adopted (resolve! pending 0 {})]
    (is (= :work (:phase pending)))
    (is (= :adopt (get-in pending [:effect-queue 0 :effect :op])))
    (is (= :work (:phase adopted)))
    (is (= 1 (get-in adopted [:players 0 :remaining])))
    (is (= 0 (get-in adopted [:players 0 :newborn])))))

(deftest reordering-keeps-each-cards-effect-sequence-together
  (let [a (c/id :a090) b (c/id :b114)
        tasks [(assoc (g/task 0 a {:op :plow :optional? true} {}) :window 7)
               (assoc (g/task 0 b {:op :choose-good :choices [:grain :vegetable]} {}) :window 7)
               (assoc (g/task 0 b {:op :gain :goods {:food 1}} {}) :window 7)
               (assoc (g/task 0 a {:op :plow :optional? true} {}) :window 8)]
        reordered (g/reorder-effect (assoc (fixture/game) :effect-queue tasks) 0 b)]
    (is (= [b b a a] (mapv :card (:effect-queue reordered))))
    (is (= [7 7 7 8] (mapv :window (:effect-queue reordered))))))

(deftest grocer-can-be-funded-by-conversion-after-the-final-harvest
  (let [state (-> (fixture/game) (fixture/own 0 [:a102])
                  (assoc-in [:players 0 :goods :food] 0) (assoc-in [:players 0 :goods :grain] 1)
                  (assoc-in [:players 0 :card-state (c/id :a102) :stock] [:vegetable])
                  (assoc :round 14 :phase :breeding :fed #{0 1} :bred #{0} :turn 1))
        pending (g/command state 1 {:type :breed :offspring {}})
        bought (-> pending (g/command 0 {:type :convert :goods {:grain 1}})
                   (g/command 0 {:type :ability :card (c/id :a102)}))
        done (resolve! bought 0 {})]
    (is (= :ability-window (get-in pending [:effect-queue 0 :effect :op])))
    (is (= 1 (a/amount (g/p done 0) :vegetable)))
    (is (= :finished (:phase done)))))
