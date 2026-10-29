(ns agricola.artifex-sowing-board-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.expansion-cards-test :refer [fresh own]]))
(defn resolve-for [state pid choice]
  (g/command state pid (merge {:type :resolve :decision (:decision-id state)} choice)))

(deftest sowing-gifts-and-after-space-pile-choice-form-one-saved-turn
  (let [state (-> (fresh) (own 0 :a065) (own 0 :a079) (own 0 :a082) (own 1 :a132)
                  (update :active conj :sow-bake)
                  (assoc-in [:players 0 :farm 0] {:type :field :count 0})
                  (assoc-in [:players 0 :farm 1] {:type :field :count 0})
                  (assoc-in [:players 0 :farm 2] {:type :field :count 0})
                  (assoc-in [:players 1 :hand :occupation] [(c/id :a104)])
                  (update-in [:players 0] a/gain {:vegetable 1})
                  (update-in [:players 1] a/gain {:grain 1})
                  (assoc-in [:piles :wood] {:wood 4}))
        offer (g/command state 0 {:type :action :space :sow-bake :plantings [] :bake-grain 0})
        publican (resolve-for offer 0 {})
        sow (resolve-for (edn/read-string (pr-str publican)) 1 {})
        certificate (resolve-for (edn/read-string (pr-str sow)) 0 {:plantings [{:cell 0 :crop :grain} {:cell 1 :crop :vegetable}]})
        done (resolve-for certificate 0 {:space :wood :resource :wood})]
    (is (= :sow-offer (get-in offer [:effect-queue 0 :effect :op])))
    (is (= (c/id :a132) (get-in publican [:effect-queue 0 :card])))
    (is (= 2 (get-in sow [:players 0 :remaining])))
    (is (empty? (:occupied sow)))
    (is (= 2 (a/amount (g/p sow 0) :grain)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command sow 0 {:type :convert :goods {:grain 1}})))
    (is (= (c/id :a082) (get-in certificate [:effect-queue 0 :card])))
    (is (= 1 (a/amount (g/p done 0) :clay)))
    (is (= 1 (a/amount (g/p done 0) :stone)))
    (is (= 1 (a/amount (g/p done 0) :wood)))
    (is (= 3 (get-in done [:piles :wood :wood])))
    (is (= 1 (get-in done [:players 1 :bonus-points])))
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (= {:sow-bake 0} (:occupied done)))
    (is (= 1 (:turn done)))
    (is (empty? (:effect-queue done)))
    (doseq [snapshot [offer publican sow certificate]]
      (is (not-any? #(and (map? %) (or (contains? % :hand) (contains? % :round-order)))
                    (tree-seq coll? seq (a/public-state snapshot)))
          "Pending public decisions must not embed hands or unrevealed round order"))))
