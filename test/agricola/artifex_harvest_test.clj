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
