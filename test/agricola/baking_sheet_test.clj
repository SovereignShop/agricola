(ns agricola.baking-sheet-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(defn ready [grain]
  (-> (fresh) (own 0 :a030) (update :active conj :sow-bake)
      (assoc-in [:players 0 :goods :grain] grain)
      (update-in [:players 0 :improvements] conj :fireplace-2)))

(deftest baking-sheet-is-an-optional-extra-exchange-after-real-baking
  (let [before (ready 2) pending (g/command before 0 {:type :action :space :sow-bake :bake-grain 1})
        restored (edn/read-string (pr-str pending)) done (resolve! restored {}) skipped (resolve! restored {:skip? true})]
    (is (= (c/id :a030) (get-in pending [:effect-queue 0 :card])))
    (is (= 1 (get-in pending [:players 0 :goods :grain])))
    (is (= 4 (delta before done :food)))
    (is (= -2 (delta before done :grain)))
    (is (= 1 (get-in done [:players 0 :bonus-points])))
    (is (= 2 (delta before skipped :food)))
    (is (= -1 (delta before skipped :grain)))
    (is (= 1 (:turn done)))
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (empty? (:effect-queue done)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! done {})) "One exchange per real baking action"))
  (let [state (ready 1) done (g/command state 0 {:type :action :space :sow-bake :bake-grain 1})]
    (is (empty? (:effect-queue done)) "No exchange is offered without a second grain"))
  (let [state (-> (ready 2) (assoc-in [:players 0 :farm 0] {:type :field :count 0}))
        sow-only (g/command state 0 {:type :action :space :sow-bake :plantings [{:cell 0 :crop :grain}]})
        converted (g/command state 0 {:type :convert :goods {:grain 1}})]
    (is (empty? (:effect-queue sow-only)))
    (is (empty? (:effect-queue converted))))
  (is (thrown? clojure.lang.ExceptionInfo
               (g/command (assoc-in (ready 2) [:players 0 :improvements] #{}) 0
                          {:type :action :space :sow-bake :bake-grain 1}))
      "Baking Sheet cannot provide a baking action by itself"))

(deftest baking-sheet-requires-no-grain-field-including-field-cards
  (let [state (hand (fresh) :a030)]
    (is (c/has? (g/p (g/play-card state 0 (c/id :a030) 0 {}) 0) :a030))
    (doseq [path [[:players 0 :farm 0] [:players 0 :card-fields (c/id :a070)]]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (g/play-card (assoc-in state path {:type :field :crop :grain :count 1}) 0 (c/id :a030) 0 {}))))))

(deftest baking-sheet-does-not-trigger-from-bake-one
  (let [state (ready 2)
        head (g/task 0 (c/id :a061) {:op :bake-one} {})
        done (g/drain (g/run-effect state head {:improvement :fireplace-2}))]
    (is (= -1 (delta state done :grain)))
    (is (= 2 (delta state done :food)))
    (is (empty? (:effect-queue done)))))

(deftest baking-sheet-triggers-once-per-bake-and-after-an-ovens-granted-bake
  (let [state (ready 3)
        pending (g/command state 0 {:type :action :space :sow-bake :bake-grain 2})
        done (resolve! pending {})]
    (is (= 6 (delta state done :food)))
    (is (= 1 (get-in done [:players 0 :bonus-points])))
    (is (empty? (:effect-queue done))))
  (let [state (-> (ready 2) (update :active conj :improvement)
                  (update-in [:players 0] a/gain {:clay 3 :stone 1}))
        sheet (g/command state 0 {:type :action :space :improvement :improvement :clay-oven :bake-grain 1})
        done (resolve! (edn/read-string (pr-str sheet)) {})]
    (is (= (c/id :a030) (get-in sheet [:effect-queue 0 :card])))
    (is (= 7 (delta state done :food)))
    (is (= 1 (get-in done [:players 0 :bonus-points])))
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (= 1 (:turn done)))))

(deftest freshman-replacement-never-activates-baking-sheet
  (doseq [oven? [false true]]
    (let [state (cond-> (-> (ready 2) (own 0 :a097) (hand :a116))
                  (not oven?) (assoc-in [:players 0 :improvements] #{}))
          offer (g/command state 0 {:type :action :space :sow-bake :bake-grain 1})
          done (resolve! (edn/read-string (pr-str offer)) {:card (c/id :a116)})]
      (is (= :freshman-bake (get-in offer [:effect-queue 0 :effect :op])))
      (is (c/has? (g/p done 0) :a116))
      (is (= 0 (delta state done :grain)))
      (is (= 0 (delta state done :food)))
      (is (= 0 (get-in done [:players 0 :bonus-points])))
      (is (empty? (:effect-queue done)))
      (is (= 1 (get-in done [:players 0 :remaining]))))))

(deftest freshman-can-choose-real-baking-and-then-use-baking-sheet
  (let [state (-> (ready 2) (own 0 :a097) (hand :a116))
        offer (g/command state 0 {:type :action :space :sow-bake})
        sheet (resolve! offer {:grain 1})
        done (resolve! (edn/read-string (pr-str sheet)) {})]
    (is (= (c/id :a030) (get-in sheet [:effect-queue 0 :card])))
    (is (= 4 (delta state done :food)))
    (is (= -2 (delta state done :grain)))
    (is (= 1 (get-in done [:players 0 :bonus-points])))
    (is (not (c/has? (g/p done 0) :a116)))
    (is (nil? (get-in done [:players 0 :card-state (c/id :a097) :used-turn])))
    (is (empty? (:effect-queue done)))))

(deftest baking-sheet-triggers-only-for-the-real-bake-after-a-freshman-occupation
  (let [state (-> (ready 2) (own 0 :a097) (own 0 :b025) (hand :a116))
        offer (g/command state 0 {:type :action :space :sow-bake})
        paddle (resolve! offer {:card (c/id :a116)})
        sheet (resolve! (edn/read-string (pr-str paddle)) {:grain 1})
        done (resolve! sheet {})]
    (is (= :bake (get-in paddle [:effect-queue 0 :effect :op])))
    (is (= 0 (delta state paddle :food)))
    (is (= (c/id :a030) (get-in sheet [:effect-queue 0 :card])))
    (is (= 4 (delta state done :food)))
    (is (= -2 (delta state done :grain)))
    (is (= 1 (get-in done [:players 0 :bonus-points])))
    (is (= 1 (:turn done)))
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (empty? (:effect-queue done)))))

(deftest replacing-a-new-ovens-baking-does-not-activate-baking-sheet
  (let [state (-> (ready 2) (own 0 :a097) (hand :a116) (update :active conj :improvement)
                  (update-in [:players 0] a/gain {:clay 3 :stone 1}))
        offer (g/command state 0 {:type :action :space :improvement :improvement :clay-oven :bake-grain 1})
        done (resolve! offer {:card (c/id :a116)})]
    (is (contains? (:improvements (g/p done 0)) :clay-oven))
    (is (c/has? (g/p done 0) :a116))
    (is (= 0 (delta state done :grain)))
    (is (= 0 (delta state done :food)))
    (is (= 0 (get-in done [:players 0 :bonus-points])))
    (is (empty? (:effect-queue done)))))
