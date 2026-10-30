(ns agricola.artifex-harvest-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn] [clojure.set :as set]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(defn boundary [cells] (set (map key (filter #(= 1 (val %)) (frequencies (mapcat a/edges cells))))))
(defn pasture [state cells]
  (update-in state [:players 0] #(-> % (a/gain {:wood 15}) (a/build-fences (vec (boundary cells))))))
(defn home [state]
  (g/drain (g/queue-front (assoc state :phase :return-home) [(g/event :return-home 0)])))
(defn keep-animals [state keep & [options]]
  (resolve! state (merge {:keep (merge (zipmap a/animals (repeat 0)) keep) :cook? false} options)))

(deftest reclamation-waits-for-actual-accommodation-and-can-be-saved
  (let [state (-> (fresh) (own 0 :a017) (pasture [0 1]) (update :active conj :sheep)
                  (assoc-in [:piles :sheep] {:sheep 3}))
        housing (g/command state 0 {:type :action :space :sheep})
        plow (keep-animals (edn/read-string (pr-str housing)) {:sheep 3})
        done (resolve! plow {:cell 2})]
    (is (= :housing (get-in housing [:effect-queue 0 :effect :op])))
    (is (= :plow (get-in plow [:effect-queue 0 :effect :op])))
    (is (= true (get-in plow [:players 0 :card-state (c/id :a017) :triggered?])))
    (is (= [2] (vec (a/tiles (g/p done 0) :field))))
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (= 1 (:turn done)))))

(deftest reclamation-incomplete-accommodation-does-not-consume-next-chance
  (let [state (-> (fresh) (own 0 :a017) (update :active conj :sheep)
                  (assoc-in [:piles :sheep] {:sheep 3}))
        done (keep-animals (g/command state 0 {:type :action :space :sheep}) {:sheep 1})]
    (is (nil? (get-in done [:players 0 :card-state (c/id :a017) :triggered?])))
    (is (empty? (:effect-queue done)))
    (let [again (-> done (assoc :turn 0 :occupied {}) (assoc-in [:piles :sheep] {:sheep 1}))
          plow (keep-animals (g/command again 0 {:type :action :space :sheep}) {:sheep 1})]
      (is (= :plow (get-in plow [:effect-queue 0 :effect :op])))
      (is (true? (get-in (resolve! plow {:skip? true}) [:players 0 :card-state (c/id :a017) :triggered?]))))))

(deftest reclamation-can-decline-even-when-all-animals-fit
  (let [state (-> (fresh) (own 0 :a017) (pasture [0 1]) (update :active conj :sheep)
                  (update-in [:players 0] a/gain {:sheep 2})
                  (assoc-in [:piles :sheep] {:sheep 1}))
        housing (g/command state 0 {:type :action :space :sheep})
        declined (keep-animals housing {:sheep 2} {:accommodate-taken? false})
        old-released (keep-animals housing {:sheep 2} {:accommodate-taken? true})]
    (is (empty? (:effect-queue declined)))
    (is (nil? (get-in declined [:players 0 :card-state (c/id :a017) :triggered?])))
    (is (= :plow (get-in old-released [:effect-queue 0 :effect :op])))
    (is (thrown? clojure.lang.ExceptionInfo (keep-animals housing {:sheep 3} {:accommodate-taken? false})))))

(deftest silage-supply-or-field-payment-breeds-one-species-with-room
  (doseq [source [:supply 2]]
    (let [state (-> (fresh) (own 0 :a084) (pasture [0 1])
                    (assoc-in [:players 0 :farm 2] {:type :field :crop :grain :count 1})
                    (update-in [:players 0] a/gain {:grain 1 :sheep 2}))
          pending (home state) done (resolve! (edn/read-string (pr-str pending)) {:grain-source source :animal :sheep})]
      (is (= 3 (a/amount (g/p done 0) :sheep)))
      (is (= (if (= source :supply) -1 0) (delta state done :grain)))
      (is (= (if (= source :supply) 1 0) (get-in done [:players 0 :farm 2 :count])))
      (is (= 2 (get-in done [:players 0 :remaining])))
      (is (= 0 (delta state (resolve! pending {:skip? true}) :sheep)))
      (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:grain-source source :animal :boar}))))))

(deftest silage-harvest-rounds-and-capacity-are-enforced
  (doseq [round [4 7 9 11 13 14]]
    (let [state (-> (fresh) (own 0 :a084) (pasture [0 1]) (assoc :round round)
                    (update-in [:players 0] a/gain {:grain 1 :sheep 2}))]
      (is (empty? (:effect-queue (home state))))))
  (let [state (-> (fresh) (own 0 :a084) (pasture [0])
                  (update-in [:players 0] a/gain {:grain 1 :sheep 2 :boar 1}))]
    (is (empty? (:effect-queue (home state)))))
  (let [state (-> (fresh) (hand :a084))]
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 (c/id :a084) 0 {})))))

(deftest full-farmer-combines-with-organic-farmer-in-one-optimal-arrangement
  (let [state (-> (fresh) (own 0 :a134) (own 0 :b098)
                  (assoc-in [:players 0 :fences] (set/union (boundary [0]) (boundary [1 2])))
                  (update-in [:players 0] a/gain {:sheep 2 :boar 1}))
        housing (a/animal-housing (g/p state 0))]
    (is (= 1 (g/card-bonus state 0 (c/id :a134))))
    (is (= 1 (g/card-bonus state 0 (c/id :b098))))
    (is (= #{[2 2] [4 1]} (set (map (juxt :capacity :count) (filter #(= :pasture (:kind %)) housing))))))
  (let [state (-> (fresh) (hand :a134)) done (g/drain (g/play-card state 0 (c/id :a134) 0 {}))]
    (is (= 1 (delta state done :wood))) (is (= 1 (delta state done :clay)))))

(deftest reclamation-also-covers-animals-taken-from-a-pile-by-another-card
  (let [state (-> (fresh) (own 0 :a017) (own 0 :a137) (update :active conj :sheep)
                  (assoc-in [:piles :sheep] {:sheep 1}))
        riverine (g/command state 0 {:type :action :space :reed})
        housing (resolve! riverine {})
        plow (keep-animals housing {:sheep 1})]
    (is (= :housing (get-in housing [:effect-queue 0 :effect :op])))
    (is (= :plow (get-in plow [:effect-queue 0 :effect :op])))
    (is (= {:reed 0} (:occupied (resolve! plow {:cell 0}))))))

(deftest oriental-fireplace-exchanges-and-returns-the-physical-cooking-improvement
  (doseq [returned [:fireplace-2 :fireplace-3 :hearth-4 :hearth-5]]
    (let [state (-> (fresh) (hand :a060) (assoc-in [:players 0 :improvements] #{returned})
                    (update :available-improvements disj returned))
          done (g/drain (g/play-card state 0 (c/id :a060) 0 {:return-improvement returned}))]
      (is (c/has? (g/p done 0) :a060))
      (is (empty? (get-in done [:players 0 :improvements])))
      (is (contains? (:available-improvements done) returned))
      (is (= {:grain 1 :vegetable 4 :sheep 3 :cattle 5} (a/cook-rates (g/p done 0))))
      (is (= 12 (:food (:goods (a/convert-food (assoc (g/p done 0) :goods {:vegetable 1 :sheep 1 :cattle 1}) {:vegetable 1 :sheep 1 :cattle 1})))))))
  (is (thrown? clojure.lang.ExceptionInfo (g/play-card (hand (fresh) :a060) 0 (c/id :a060) 0 {})))
  (let [state (-> (fresh) (own 0 :a060) (update-in [:players 0] a/gain {:grain 2}))]
    (is (= 4 (- (a/amount (a/bake (g/p state 0) 2) :food) (a/amount (g/p state 0) :food))))
    (is (thrown? clojure.lang.ExceptionInfo (a/convert-food (a/gain (g/p state 0) {:boar 1}) {:boar 1})))
    (let [done (a/build-improvement state 0 {:improvement :hearth-4 :upgrade (c/id :a060)})]
      (is (not (c/has? (g/p done 0) :a060)))
      (is (contains? (get-in done [:players 0 :improvements]) :hearth-4))
      (is (not (contains? (:available-improvements done) (c/id :a060)))))))

(deftest oriental-counts-once-and-can-use-major-only-grants
  (let [state (-> (fresh) (own 0 :a060) (own 0 :a101))
        p (g/p state 0)]
    (is (= 1 (g/card-bonus state 0 (c/id :a101))))
    (is (= 1 (count (a/baking-improvement-ids p))))
    (is (= 1 (get-in (g/score state 0) [:breakdown :improvements]))))
  (let [state (-> (fresh) (hand :a060) (assoc-in [:players 0 :improvements] #{:hearth-5}))
        head (g/task 0 nil {:op :card-action :kinds #{:major} :optional? true} {})
        pending (g/drain (g/queue-front state [head]))
        done (resolve! pending {:card (c/id :a060)})]
    (is (c/has? (g/p done 0) :a060))
    (is (some #(= (c/id :a060) (:card %)) (bot/decision-candidates pending 0)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/granted-card-action state 0 {:kinds #{:major} :major-ids #{:clay-oven :stone-oven}} {:card (c/id :a060)})))))

(deftest oriental-is-a-baking-improvement-for-winnowing-fan
  (let [state (-> (fresh) (own 0 :a060) (own 0 :a061) (update-in [:players 0] a/gain {:grain 1}))
        pending (g/drain (g/queue-front state [(g/event :after-field-phase 0)]))
        done (resolve! pending {:improvement (c/id :a060)})]
    (is (= -1 (delta state done :grain)))
    (is (= 2 (delta state done :food)))))
