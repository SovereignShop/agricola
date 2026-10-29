(ns agricola.artifex-nested-actions-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.expansion-cards-test :refer [own hand resolve!]]))

(deftest nested-card-actions-finish-before-the-next-free-occupation
  (let [state (-> (g/initialize (a/new-game ["A" "B" "C" "D" "E"] (vec (mapcat identity a/stages)) 0))
                  (own 0 :a131) (hand :a096) (hand :a104) (hand :a027)
                  (assoc :round 2) (update :active conj :improvement)
                  (assoc-in [:players 0 :improvements] #{:fireplace-2 :hearth-4})
                  (update :available-improvements disj :fireplace-2 :hearth-4)
                  (update-in [:players 0] a/gain {:wood 2 :stone 3 :clay 1 :grain 1}))
        teacher (g/command state 0 {:type :action :space :improvement :improvement :joinery})
        artisan (resolve! teacher {:card (c/id :a096)})
        oven (resolve! (edn/read-string (pr-str artisan)) {:card (c/id :a027)})
        second-occupation (resolve! (edn/read-string (pr-str oven)) {:improvement :clay-oven :bake-grain 1})
        finished (resolve! second-occupation {:card (c/id :a104)})
        player (g/p finished 0)]
    (is (= (c/id :a131) (get-in teacher [:effect-queue 0 :card])))
    (is (= (c/id :a096) (get-in artisan [:effect-queue 0 :card])))
    (is (= (c/id :a027) (get-in oven [:effect-queue 0 :card])))
    (is (= (c/id :a131) (get-in second-occupation [:effect-queue 0 :card])))
    (is (empty? (:effect-queue finished)))
    (is (= #{(c/id :a131) (c/id :a096) (c/id :a104)} (set (:occupations player))))
    (is (= #{:fireplace-2 :hearth-4 :joinery :clay-oven} (:improvements player)))
    (is (= 3 (a/amount player :wood)))
    (is (= 0 (a/amount player :stone)))
    (is (= 0 (a/amount player :clay)))
    (is (= 0 (a/amount player :grain)))
    (is (= 7 (a/amount player :food)))
    (is (= 1 (:remaining player)))
    (is (= {:improvement 0} (:occupied finished)))
    (is (= 2 (get-in finished [:major-builds 0])))))
