(ns agricola.artifex-crop-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.card-expressions :as expression]
            [agricola.bot :as bot]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(defn field [state cell crop n]
  (assoc-in state (into [:players 0] (a/field-path cell)) {:type :field :crop crop :count n}))
(defn emit [state kind]
  (g/drain (g/queue-front state [(g/event kind 0)])))
(defn harvest [state round]
  (-> state (assoc :round round) (g/continue-step :field-harvest) g/drain))

(deftest crop-card-prerequisites-and-costs
  (doseq [code [:a058 :a059]]
    (let [state (hand (fresh) code)]
      (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 (c/id code) 0 {})))
      (is (c/has? (g/p (g/play-card (update-in state [:players 0] a/gain {:wood 1}) 0 (c/id code) 0 {}) 0) code))))
  (let [state (-> (fresh) (hand :a061) (update-in [:players 0] a/gain {:reed 1}))]
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 (c/id :a061) 0 {})))
    (let [done (g/play-card (assoc-in state [:players 0 :improvements] #{:fireplace-2}) 0 (c/id :a061) 0 {})]
      (is (c/has? (g/p done 0) :a061)) (is (= 0 (a/amount (g/p done 0) :reed)))))
  (let [state (-> (fresh) (hand :a070) (update-in [:players 0] a/gain {:wood 1})
                  (field 0 nil 0) (field 1 nil 0))]
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 (c/id :a070) 0 {})))
    (is (c/has? (g/p (g/play-card (field state 2 nil 0) 0 (c/id :a070) 0 {}) 0) :a070))))

(deftest field-card-prerequisites-distinguish-fields-from-field-tiles
  (let [state (-> (fresh) (own 0 :b068) (field (c/id :b068) :vegetable 0)
                  (hand :a070) (field 0 nil 0) (field 1 nil 0)
                  (update-in [:players 0] a/gain {:wood 1}))]
    (is (g/prerequisites? state 0 (c/card (c/id :a070))))
    (is (c/has? (g/p (g/play-card state 0 (c/id :a070) 0 {}) 0) :a070))
    (is (= 3 (expression/evaluate state 0 {} [:fields])))
    (is (= 2 (expression/evaluate state 0 {} [:field-tiles])))
    (is (not (g/prerequisites? state 0 {:prerequisites "3 Field Tiles"}))))
  (let [state (-> (fresh) (own 0 :b068) (field (c/id :b068) :vegetable 0))]
    (is (g/prerequisites? state 0 (c/card (c/id :a072))))
    (is (not (g/prerequisites? (field state 0 nil 0) 0 (c/card (c/id :a072)))))))

(deftest asparagus-knife-obeys-rounds-and-selected-field
  (doseq [round (range 1 15)]
    (let [state (-> (fresh) (own 0 :a058) (assoc :round round :phase :return-home) (field 0 :vegetable 2))
          pending (emit state :return-home)]
      (is (= (boolean (#{8 10 12} round)) (boolean (seq (:effect-queue pending)))))))
  (let [state (-> (fresh) (own 0 :a058) (assoc :round 8 :phase :return-home)
                  (field 0 :vegetable 2) (field 1 :grain 2) (field 2 :vegetable 0))
        pending (emit state :return-home)]
    (doseq [cell [1 2 3 -1 15 :not-owned]]
      (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:cell cell}))))
    (is (= (:players state) (:players (resolve! pending {:skip? true}))))
    (let [done (resolve! (edn/read-string (pr-str pending)) {:cell 0})]
      (is (= 1 (get-in done [:players 0 :farm 0 :count])))
      (is (= 2 (get-in done [:players 0 :farm 1 :count])))
      (is (= 1 (delta state done :vegetable)))
      (is (= 0 (delta state done :food)))))
  (let [cell (c/id :b068)
        state (-> (fresh) (own 0 :a058) (assoc :round 10 :phase :return-home) (field cell :vegetable 1))
        done (resolve! (emit state :return-home) {:cell cell :exchange? true})]
    (is (= 0 (get-in done [:players 0 :card-fields cell :count])))
    (is (= 0 (delta state done :vegetable)))
    (is (= 3 (delta state done :food)))
    (is (= 1 (get-in done [:players 0 :bonus-points])))
    (is (= 1 (get-in done [:players 0 :received :vegetable])))))

(deftest taking-and-moving-crops-do-not-count-as-harvests
  (doseq [[code round kind] [[:a058 8 :return-home] [:a070 10 :round-end]]]
    (let [state (-> (fresh) (own 0 code) (own 0 :a059) (own 0 :a106)
                    (assoc :round round :phase :return-home) (field 0 :vegetable 1)
                    (update-in [:players 0] a/gain {:vegetable 3}))
          done (resolve! (emit state kind) {:cell 0})]
      (is (= 4 (a/amount (g/p done 0) :vegetable)))
      (is (= 0 (delta state done :food)) "Neither Potato Ridger nor Slurry Spreader triggers")
      (is (empty? (:effect-queue done))))))

(deftest lifting-machine-runs-only-at-end-of-nonharvest-rounds
  (doseq [round (range 1 15)]
    (let [state (-> (fresh) (own 0 :a070) (assoc :round round) (field 0 :vegetable 2))]
      (is (empty? (:effect-queue (emit state :return-home))))
      (is (= (not (boolean (a/harvest-rounds round))) (boolean (seq (:effect-queue (emit state :round-end))))))))
  (let [state (-> (fresh) (own 0 :a070) (assoc :round 6 :phase :return-home)
                  (field (c/id :b068) :vegetable 2))
        pending (emit state :round-end)
        done (resolve! pending {:cell (c/id :b068)})]
    (is (= 1 (delta state done :vegetable)))
    (is (= 1 (get-in done [:players 0 :card-fields (c/id :b068) :count])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:cell (c/id :b068) :exchange? true}))))
  (is (empty? (:effect-queue (emit (-> (fresh) (own 0 :a070) (assoc :round 6)) :round-end)))))

(deftest potato-ridger-harvests-as-one-batch-and-converts-once
  (doseq [[before fields vegetables food decision?]
          [[0 1 1 0 false] [1 1 2 0 false] [2 1 3 0 true]
           [2 2 3 6 false] [7 2 8 6 false]]]
    (let [state (reduce #(field %1 %2 :vegetable 2)
                        (-> (fresh) (own 0 :a059) (update-in [:players 0] a/gain {:vegetable before})) (range fields))
          done (harvest state 7)]
      (is (= vegetables (a/amount (g/p done 0) :vegetable)))
      (is (= food (delta state done :food)))
      (is (= decision? (boolean (seq (:effect-queue done)))))))
  (let [state (-> (fresh) (own 0 :a059) (update-in [:players 0] a/gain {:vegetable 4}) (field 0 :grain 2))
        done (harvest state 7)]
    (is (= 4 (a/amount (g/p done 0) :vegetable)))
    (is (= 0 (delta state done :food)))))

(deftest potato-ridger-optional-and-mandatory-decisions-stay-valid
  (let [state (-> (fresh) (own 0 :a059) (update-in [:players 0] a/gain {:vegetable 2}) (field 0 :vegetable 2))
        pending (harvest state 7)
        accepted (resolve! (edn/read-string (pr-str pending)) {})
        skipped (resolve! pending {:skip? true})]
    (is (= 2 (a/amount (g/p accepted 0) :vegetable)))
    (is (= 6 (delta pending accepted :food)))
    (is (= 3 (a/amount (g/p skipped 0) :vegetable)))
    (is (= :feeding (:phase accepted)))
    (let [forced (update-in pending [:players 0] a/gain {:vegetable 1})]
      (is (not (g/optional-effect? forced (first (:effect-queue forced)))))
      (is (thrown? clojure.lang.ExceptionInfo (resolve! forced {:skip? true})))
      (is (= 6 (delta forced (g/drain forced) :food))))
    (let [too-few (update-in pending [:players 0] a/pay {:vegetable 1})]
      (is (thrown? clojure.lang.ExceptionInfo (resolve! too-few {})))
      (is (empty? (:effect-queue (g/drain too-few)))))))

(deftest winnowing-fan-selects-one-owned-baking-improvement-without-a-bake-action
  (let [state (-> (fresh) (own 0 :a061) (own 0 :a063) (assoc :round 14)
                  (assoc-in [:players 0 :improvements] #{:fireplace-2 :clay-oven :well})
                  (field 0 :grain 2))
        pending (harvest state 14)]
    (doseq [[improvement food] [[:fireplace-2 2] [:clay-oven 5]]]
      (let [done (resolve! (edn/read-string (pr-str pending)) {:improvement improvement})]
        (is (= food (delta pending done :food)) "Dutch Windmill must not add its Bake Bread bonus")
        (is (= -1 (delta pending done :grain)))
        (is (= :feeding (:phase done)))))
    (doseq [choice [{:improvement :well} {:improvement :stone-oven} {:improvement :clay-oven :grain 2} {}]]
      (is (thrown? clojure.lang.ExceptionInfo (resolve! pending choice))))
    (is (= (:players pending) (:players (resolve! pending {:skip? true}))))))

(deftest winnowing-fan-waits-for-the-end-of-the-field-phase
  (let [state (-> (fresh) (own 0 :a061) (own 0 :a112)
                  (assoc-in [:players 0 :improvements] #{:clay-oven}) (field 0 :grain 2))
        pending (harvest state 4)]
    (is (= (c/id :a112) (get-in pending [:effect-queue 0 :card])))
    (is (not (some #{(c/id :a061)} (g/order-options pending))))
    (let [fan (resolve! pending {:fields [0]}) done (resolve! fan {:improvement :clay-oven})]
      (is (= (c/id :a061) (get-in fan [:effect-queue 0 :card])))
      (is (= 2 (a/amount (g/p fan 0) :grain)))
      (is (= 1 (a/amount (g/p done 0) :grain)))
      (is (= 0 (get-in done [:players 0 :farm 0 :count])))))
  (doseq [state [(-> (fresh) (own 0 :a061))
                 (-> (fresh) (own 0 :a061) (assoc-in [:players 0 :improvements] #{:clay-oven}))]]
    (is (empty? (:effect-queue (harvest state 4))))))

(deftest bots-can-resolve-the-new-crop-decisions
  (doseq [pending [(emit (-> (fresh) (own 0 :a058) (assoc :round 8) (field 0 :vegetable 1)) :return-home)
                   (emit (-> (fresh) (own 0 :a070) (assoc :round 6) (field (c/id :b068) :vegetable 1)) :round-end)
                   (harvest (-> (fresh) (own 0 :a059) (update-in [:players 0] a/gain {:vegetable 2}) (field 0 :vegetable 1)) 7)
                   (harvest (-> (fresh) (own 0 :a061) (assoc-in [:players 0 :improvements] #{:clay-oven}) (field 0 :grain 1)) 4)]]
    (let [commands (bot/decision-candidates pending 0)]
      (is (some #(and (not (:skip? %)) (bot/preview pending 0 %)) commands))
      (is (some? (g/command pending 0 (:command (bot/choose pending 0 (java.util.Random. 7)))))))))
