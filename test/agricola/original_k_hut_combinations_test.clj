(ns agricola.original-k-hut-combinations-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.original-k-workers-draw-test :as hut]
            [agricola.five-player-test :as five] [agricola.sowing-cards-test :as sow]
            [agricola.expansion-cards-test :refer [fresh own hand]]))
(def lover :catalog/original-base-k-291-lover)
(def parents :catalog/original-base-k-267-adoptive-parents)
(def magician :catalog/original-base-k-311-magician)
(defn hut-game [] (hut/play (update-in (fresh) [:players 0] a/gain {:wood 20 :reed 20 :food 20}) hut/hut))
(defn resolve! [s choices]
  (g/command (edn/read-string (pr-str s)) (g/acting-player s) (merge {:type :resolve :decision (:decision-id s)} choices)))

(deftest lover-can-adopt-hut-worker-then-adoptive-parents-releases-it
  (let [s (-> (hut-game) (own 0 parents) (hand lover))
        pending (g/command s 0 {:type :action :space :lessons :card lover})
        grown (resolve! pending {:adopt-reed-hut? true})
        adopted (resolve! grown {})]
    (is (= :grow (get-in pending [:effect-queue 0 :effect :op])))
    (is (= :adopt-original (get-in grown [:effect-queue 0 :effect :op])))
    (is (= [3 0 2 0] ((juxt :family :newborn :remaining a/reed-hut-count) (g/p adopted 0))))
    (is (empty? (get-in adopted [:newborn-spaces :lessons])))
    (is (= (- (a/amount (g/p s 0) :food) 6) (a/amount (g/p adopted 0) :food)))))

(deftest hut-worker-last-placement-counts-for-magician-but-guest-precedes-it
  (let [s (-> (hut-game) (own 0 magician) (assoc-in [:players 0 :guests] 1)
              (assoc-in [:players 0 :remaining] 2))
        ordinary (g/command s 0 {:type :action :space :traveling})]
    (is (false? (boolean (get-in ordinary [:players 0 :reed-hut-placed?]))))
    (is (= 0 (a/amount (g/p ordinary 0) :grain))))
  (let [s (-> (hut-game) (own 0 magician) (assoc-in [:players 0 :remaining] 1))
        last (g/command s 0 {:type :action :space :traveling})]
    (is (true? (get-in last [:players 0 :reed-hut-placed?])))
    (is (= 1 (a/amount (g/p last 0) :grain)))))

(deftest own-hut-worker-cannot-adopt-itself-through-lover
  (let [s (-> (hut-game) (hand lover) (assoc-in [:players 0 :family] 4)
              (assoc-in [:players 0 :remaining] 1))]
    (is (thrown? clojure.lang.ExceptionInfo (g/command s 0 {:type :action :space :lessons :card lover})))))

(deftest tag-along-places-the-hut-worker-on-the-other-players-turn
  (let [state (-> (five/game) (five/own 3 :b178)
                  (update-in [:players 3 :minors] conj hut/hut)
                  (assoc-in [:players 3 :reed-hut?] true) (assoc-in [:players 3 :remaining] 1))
        pending (five/move state :market) done (five/resolve-head pending {})]
    (is (true? (get-in done [:players 3 :reed-hut-placed?])))
    (is (= 0 (get-in done [:players 3 :remaining])))
    (is (= 3 (g/placed-people done 3)))
    (is (= [0 3] (get-in done [:occupied :market])))
    (is (= 1 (:turn done)))))

(deftest deferred-sidekick-only-marks-the-hut-worker-when-its-action-commits
  (doseq [decline? [false true]]
    (let [state (-> (five/game) (own 0 :a171) (sow/field 0)
                    (update-in [:players 0 :minors] conj hut/hut)
                    (assoc-in [:players 0 :reed-hut?] true)
                    (assoc :round 4 :active (into (a/base-actions 5 :cards) [:sheep :improvement :sow-bake :fences]))
                    (update-in [:players 0] a/gain {:wood 4}))
          state (if decline? (-> state (own 1 :a132) (update-in [:players 1] a/gain {:grain 1})) (own state 0 :a065))
          pending (g/command state 0 {:type :action :space :fences :fences (sow/boundary [4])})
          offer (resolve! pending {:action-command {}})
          choose (resolve! offer {})
          done (if decline? (sow/resolve-player choose 1 {:skip? true}) (resolve! choose {:plantings [{:cell 0 :crop :grain}]}))]
      (is (not (get-in offer [:players 0 :reed-hut-placed?])))
      (is (= (not decline?) (boolean (get-in done [:players 0 :reed-hut-placed?]))))
      (is (= (if decline? 1 0) (get-in done [:players 0 :remaining])))
      (is (= 1 (:turn done))))))
