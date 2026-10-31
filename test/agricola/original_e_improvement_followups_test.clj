(ns agricola.original-e-improvement-followups-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(def madonna :catalog/original-base-e-38-madonna-statue)
(def merchant :catalog/original-base-e-179-merchant)
(def pen :catalog/original-base-e-24-animal-pen)
(def yard :catalog/original-base-e-58-animal-yard)
(def fireplace :catalog/original-base-e-20-simple-fireplace)
(def spices :catalog/original-base-e-25-spices)
(def clogs :catalog/original-base-e-28-clogs)
(def ceramics :catalog/original-base-e-33-ceramics)
(def tray :catalog/original-base-e-15-baking-tray)
(def material :catalog/original-base-e-16-building-material)
(def stall :catalog/original-base-e-39-market-stall)
(def bean-field :catalog/original-base-e-18-bean-field)
(defn reload [state] (edn/read-string (pr-str state)))
(defn rich [state] (update-in state [:players 0] a/gain {:wood 20 :clay 20 :reed 20 :stone 20 :grain 4 :food 20}))
(defn op [state] (get-in state [:effect-queue 0 :effect :op]))

(deftest madonna-requires-two-different-owned-improvements
  (let [state (-> (fresh) rich (hand madonna) (own 0 spices) (own 0 clogs) (hand bean-field) (own 0 merchant))]
    (doseq [returns [[spices] [spices clogs :well] [spices spices] [spices bean-field] [spices merchant] [spices madonna]]]
      (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 madonna 0 {:return-improvements returns}))))
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card (assoc-in state [:players 0 :minors] [spices]) 0 madonna 0 {})))
    (is (g/prerequisites? state 0 (c/card madonna)))
    (is (= #{#{spices clogs}} (set (map (comp set :return-improvements) (filter #(= madonna (:card %)) (bot/hand-options (g/p state 0) :minor))))))))

(deftest madonna-returns-majors-removes-minors-and-cancels-their-future-deliveries
  (let [state (-> (fresh) (hand madonna) (own 0 pen) (own 0 spices)
                  (assoc-in [:players 0 :improvements] #{:well})
                  (update :available-improvements disj :well)
                  (assoc-in [:players 0 :well-rounds] #{2 3 4})
                  (assoc-in [:players 0 :schedule] {2 [{:card pen :goods {:food 2}} {:card spices :goods {:wood 1}}]})
                  (assoc-in [:players 0 :card-state pen] {:stored-goods {:food 9}})
                  (g/queue-front [(g/task 0 pen {:op :gain :goods {:food 99}} {})]))
        done (g/drain (g/play-card state 0 madonna 0 {:return-improvements [:well pen]}))]
    (is (contains? (:available-improvements done) :well))
    (is (not (contains? (:available-improvements done) pen)))
    (is (= [pen] (:removed-cards done)))
    (is (= #{spices madonna} (set (get-in done [:players 0 :minors]))))
    (is (empty? (get-in done [:players 0 :well-rounds])))
    (is (= {2 [{:card spices :goods {:wood 1}}]} (get-in done [:players 0 :schedule])))
    (is (nil? (get-in done [:players 0 :card-state pen])))
    (is (= 0 (delta state done :food)))
    (is (= 2 (get-in (g/score done 0) [:breakdown :improvements])))))

(deftest madonna-removes-card-fields-and-resolves-loss-of-animal-housing
  (let [state (-> (fresh) (hand madonna) (own 0 bean-field) (own 0 spices)
                  (assoc-in [:players 0 :card-fields bean-field] {:type :field :crop :vegetable :count 3}))
        done (g/drain (g/play-card state 0 madonna 0 {:return-improvements [bean-field spices]}))]
    (is (empty? (get-in done [:players 0 :card-fields])))
    (is (= 0 (get-in done [:players 0 :goods :vegetable]))))
  (let [state (-> (fresh) (hand madonna) (own 0 yard) (own 0 fireplace)
                  (assoc-in [:players 0 :goods :sheep] 3))
        waiting (g/command state 0 {:type :action :space :meeting :card madonna :return-improvements [yard fireplace]})
        done (resolve! (reload waiting) {:keep {:sheep 1 :boar 0 :cattle 0} :cook? false})]
    (is (= :housing (op waiting)))
    (is (= 3 (get-in waiting [:players 0 :goods :sheep])))
    (is (= 1 (get-in done [:players 0 :goods :sheep])))
    (is (= 0 (delta state done :food)))
    (is (= 1 (:turn done)))))

(deftest merchant-repeats-a-minor-action-once-and-keeps-the-turn-open
  (let [state (-> (fresh) rich (own 0 merchant) (hand spices) (hand clogs))
        waiting (g/command state 0 {:type :action :space :meeting :card spices})
        done (resolve! (reload waiting) {:card clogs})]
    (is (= :card-action (op waiting)))
    (is (= #{:minor} (get-in waiting [:effect-queue 0 :effect :kinds])))
    (is (= 0 (:turn waiting)))
    (is (= -1 (delta state done :food)))
    (is (= #{spices clogs} (set (get-in done [:players 0 :minors]))))
    (is (empty? (:effect-queue done))))
  (let [state (-> (fresh) rich (own 0 merchant) (hand spices))
        waiting (g/command state 0 {:type :action :space :meeting :card spices})]
    (is (thrown? clojure.lang.ExceptionInfo (resolve! waiting {:improvement :pottery})))
    (is (= 1 (:turn (resolve! waiting {:skip? true})))))
  (is (empty? (:effect-queue (g/command (own (fresh) 0 merchant) 0 {:type :action :space :meeting})))))

(deftest merchant-waits-for-the-first-improvements-income-and-pass
  (let [state (-> (fresh) (own 0 merchant) (hand ceramics) (hand material)
                  (assoc-in [:players 0 :improvements] #{:clay-oven})
                  (assoc-in [:players 0 :goods :food] 0) (assoc-in [:players 0 :goods :clay] 1))
        waiting (g/command state 0 {:type :action :space :meeting :card ceramics})
        choosing (resolve! waiting {:card material})
        done (resolve! choosing {:resource :wood})]
    (is (= 2 (get-in waiting [:players 0 :goods :food])))
    (is (= 1 (get-in done [:players 0 :goods :food])))
    (is (= 1 (get-in done [:players 0 :goods :wood])))
    (is (empty? (:effect-queue done))))
  (let [state (-> (fresh) (own 0 merchant) (hand stall) (hand material)
                  (assoc-in [:players 0 :goods :grain] 1))
        waiting (g/command state 0 {:type :action :space :meeting :card stall})]
    (is (= :card-action (op waiting)))
    (is (= [stall] (get-in waiting [:players 1 :hand :minor])))
    (is (not (c/has? (g/p waiting 0) stall)))))

(deftest merchant-can-repeat-a-major-or-minor-action-after-immediate-baking-triggers
  (let [state (-> (fresh) rich (own 0 merchant) (own 0 :catalog/original-base-e-41-millstone)
                  (assoc-in [:players 0 :goods :food] 0) (update :active conj :improvement))
        waiting (g/command state 0 {:type :action :space :improvement :improvement :clay-oven :bake-grain 1})
        done (resolve! (reload waiting) {:improvement :pottery})]
    (is (= 7 (get-in waiting [:players 0 :goods :food])))
    (is (= #{:major :minor} (get-in waiting [:effect-queue 0 :effect :kinds])))
    (is (= #{:clay-oven :pottery} (get-in done [:players 0 :improvements])))
    (is (= 6 (get-in done [:players 0 :goods :food])))
    (is (empty? (:effect-queue done)))))

(deftest merchant-inherits-granted-action-restrictions-and-triggers-through-scholar
  (let [state (-> (fresh) rich (own 0 merchant) (hand spices) (hand clogs))
        scholar (g/drain (g/queue-front state [(g/task 0 (c/id :b097) {:op :scholar :optional? true} {})]))
        waiting (resolve! scholar {:card spices})
        done (resolve! waiting {:card clogs})]
    (is (= :card-action (op waiting)))
    (is (= -1 (delta state done :food)))
    (is (empty? (:effect-queue done))))
  (let [state (-> (fresh) rich (own 0 merchant) (hand clogs) (hand spices))
        waiting (g/drain (g/granted-card-action state 0 {:kinds #{:minor} :minimum-paid {:wood 1}} {:card clogs}))]
    (is (= {:wood 1} (get-in waiting [:effect-queue 0 :effect :minimum-paid])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! waiting {:card spices}))))
  (let [state (-> (fresh) rich (own 0 merchant))
        done (g/drain (g/granted-card-action state 0 {:kinds #{:major}} {:improvement :pottery}))]
    (is (empty? (:effect-queue done)) "A major-only action does not activate Merchant.")))

(deftest merchant-can-use-just-played-baking-tray-to-repeat-a-minor-action-with-an-oven
  (let [state (-> (fresh) rich (own 0 merchant) (hand tray))
        waiting (g/command state 0 {:type :action :space :meeting :card tray})
        done (resolve! waiting {:improvement :clay-oven :discount-resource :stone})]
    (is (some #(and (= :clay-oven (:improvement %)) (bot/preview waiting 0 %)) (bot/decision-candidates waiting 0)))
    (is (contains? (get-in done [:players 0 :improvements]) :clay-oven))
    (is (= 0 (delta state done :stone)))
    (is (= -1 (delta state done :food)))
    (is (empty? (:effect-queue done)))))
