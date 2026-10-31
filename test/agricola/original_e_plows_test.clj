(ns agricola.original-e-plows-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-rules :as c] [agricola.card-game :as g]
            [agricola.bot :as bot]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(def riding :catalog/original-base-e-61-riding-plow)
(def turnwrest :catalog/original-base-e-62-turnwrest-plow)
(def maker :catalog/original-base-e-195-plow-maker)
(defn fields [state] (set (a/tiles (g/p state 0) :field)))
(defn equip [state cid uses] (-> state (own 0 cid) (assoc-in [:players 0 :card-state cid :uses] uses)))
(deftest printed-costs-prerequisites-and-use-counters
  (doseq [[cid wood occupations uses] [[riding 4 3 2] [turnwrest 3 2 1]]]
    (let [state (-> (fresh) (hand cid) (update-in [:players 0] a/gain {:wood wood}))
          ready (reduce #(own %1 0 %2) state (take occupations [:a103 :a104 :a105]))]
      (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 cid 0 {})))
      (let [done (g/drain (g/play-card ready 0 cid 0 {}))]
        (is (= (- wood) (delta ready done :wood)))
        (is (= uses (get-in done [:players 0 :card-state cid :uses])))))))
(deftest original-plows-work-on-later-person-and-save-the-chosen-exclusive-plow
  (let [state (-> (fresh) (equip riding 2) (equip turnwrest 1) (equip (c/id :a018) 1)
                  (assoc-in [:players 0 :remaining] 1))
        pending (g/command state 0 {:type :action :space :plow :cell 0})
        saved (edn/read-string (pr-str pending))
        done (resolve! saved {:plow-card turnwrest :cells [1 2]})]
    (is (= [riding turnwrest] (get-in pending [:effect-queue 0 :effect :plows])))
    (is (empty? (fields pending)))
    (is (= #{0 1 2} (fields done)))
    (is (= [2 0 1] (mapv #(get-in done [:players 0 :card-state % :uses]) [riding turnwrest (c/id :a018)])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! saved {:plow-card (c/id :a018) :cells [1]})))
    (is (= #{0} (fields (resolve! saved {:skip? true}))))))
(deftest plow-maker-combines-with-one-plow-and-pays-only-on-commit
  (let [state (-> (fresh) (equip riding 2) (own 0 maker) (update-in [:players 0] a/gain {:food 1}))
        pending (g/command state 0 {:type :action :space :plow :cell 0})
        done (resolve! pending {:plow-card riding :plow-maker? true :cells [1 2 3]})]
    (is (= #{0 1 2 3} (fields done)))
    (is (= -1 (delta state done :food)))
    (is (= 0 (delta state pending :food)))
    (is (= 1 (get-in done [:players 0 :card-state riding :uses])))
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (= 1 (:turn done)))
    (is (some #(and (= riding (:plow-card %)) (:plow-maker? %) (= 3 (count (:cells %)))
                    (bot/preview pending 0 %)) (bot/decision-candidates pending 0)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:plow-card riding :cells [1 2 3]})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:plow-card nil :plow-maker? true :cells [1 2]})))
    (let [alone (resolve! pending {:plow-card nil :plow-maker? true :cells [1]})]
      (is (= #{0 1} (fields alone)))
      (is (= 2 (get-in alone [:players 0 :card-state riding :uses]))))))
(deftest cultivation-can-sow-all-four-fields-in-the-same-action
  (let [state (-> (fresh) (equip riding 2) (own 0 maker) (update :active conj :cultivation)
                  (update-in [:players 0] a/gain {:food 1 :grain 4}))
        pending (g/command state 0 {:type :action :space :cultivation :cell 0})
        offer (resolve! pending {:plow-card riding :plow-maker? true :cells [1 2 3]})
        sow (resolve! (edn/read-string (pr-str offer)) {})
        done (resolve! sow {:plantings (mapv #(hash-map :cell % :crop :grain) [0 1 2 3])})]
    (is (= #{0 1 2 3} (set (a/tiles (g/sowing-player sow 0 (get-in sow [:effect-queue 0 :effect])) :field))))
    (is (= [3 3 3 3] (mapv #(get-in done [:players 0 :farm % :count]) [0 1 2 3])))
    (is (= -1 (delta state done :food)))
    (is (= 1 (get-in done [:players 0 :card-state riding :uses])))
    (is (= {:cultivation 0} (:occupied done)))))
(deftest exhausted-plows-and-unaffordable-makers-do-not-offer-an-effect
  (let [state (-> (fresh) (equip riding 0) (own 0 maker) (assoc-in [:players 0 :goods :food] 0))
        done (g/command state 0 {:type :action :space :plow :cell 0})]
    (is (= #{0} (fields done)))
    (is (empty? (:effect-queue done)))))
(deftest original-plow-without-base-field-must-still-plow-at-least-two
  (let [state (-> (fresh) (equip riding 2) (update :active conj :cultivation))
        pending (g/command state 0 {:type :action :space :cultivation})]
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:cells [0]})))
    (is (= #{0 1} (fields (resolve! (resolve! pending {:cells [0 1]}) {:skip? true}))))))

(deftest legacy-wheel-plow-decisions-and-plans-remain-reloadable
  (let [wheel (c/id :a018) state (equip (fresh) wheel 1)
        pending (g/command state 0 {:type :action :space :plow :cell 0})
        legacy (update-in pending [:effect-queue 0 :effect] dissoc :plows :plow-maker?)
        done (resolve! (edn/read-string (pr-str legacy)) {:cells [1 2]})]
    (is (= #{0 1 2} (fields done)))
    (is (= 0 (get-in done [:players 0 :card-state wheel :uses])))
    (is (some #(and (= wheel (:plow-card %)) (bot/preview legacy 0 %)) (bot/decision-candidates legacy 0))))
  (let [wheel (c/id :a018)
        state (-> (fresh) (equip wheel 1) (update :active conj :cultivation))
        pending (g/command state 0 {:type :action :space :cultivation :cell 0})
        offer (resolve! pending {:cells [1]})
        legacy (update offer :plow-plan dissoc :card :plow-maker?)
        done (resolve! (edn/read-string (pr-str legacy)) {:skip? true})]
    (is (= #{0 1} (fields done)))
    (is (= 0 (get-in done [:players 0 :card-state wheel :uses])))))
