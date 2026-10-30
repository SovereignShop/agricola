(ns agricola.artifex-plow-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(defn wheel [state]
  (-> state (own 0 :a018) (assoc-in [:players 0 :card-state (c/id :a018) :uses] 1)))
(defn fields [state] (set (a/tiles (g/p state 0) :field)))

(deftest double-turn-round-dependent-cost-and-prerequisite
  (doseq [round (range 1 6)]
    (let [state (-> (fresh) (assoc :round round) (hand :a020) (update-in [:players 0] a/gain {:grain 1}))
          pending (g/drain (g/play-card state 0 (c/id :a020) 0 {}))]
      (is (= -1 (delta state pending :grain)))
      (is (= (if (> round 3) -1 0) (delta state pending :food)))
      (is (= :plow-many (get-in pending [:effect-queue 0 :effect :op])))
      (is (= #{0 1} (fields (resolve! pending {:cells [0 1]}))))))
  (is (thrown? clojure.lang.ExceptionInfo
               (g/play-card (-> (fresh) (assoc :round 6) (hand :a020) (update-in [:players 0] a/gain {:grain 1})) 0 (c/id :a020) 0 {})))
  (is (thrown? clojure.lang.ExceptionInfo
               (g/play-card (-> (fresh) (assoc :round 4) (hand :a020) (assoc-in [:players 0 :goods] {:grain 1 :food 0})) 0 (c/id :a020) 0 {}))))

(deftest double-turn-saved-choice-is-bounded-and-atomic
  (let [state (-> (fresh) (hand :a020) (update-in [:players 0] a/gain {:grain 1}))
        pending (g/command state 0 {:type :action :space :meeting :card (c/id :a020)})
        saved (edn/read-string (pr-str pending))]
    (doseq [cells [[] [0 0] [0 14] [0 1 2] [5] [99] ["0"]]]
      (is (thrown? clojure.lang.ExceptionInfo (resolve! saved {:cells cells}))))
    (is (= #{0} (fields (resolve! saved {:cells [0]}))))
    (is (= #{} (fields (resolve! saved {:skip? true}))))
    (is (= 1 (get-in (resolve! saved {:cells [0 1]}) [:players 0 :remaining])))
    (is (= {:meeting 0} (:occupied (resolve! saved {:cells [0 1]}))))
    (is (= 1 (:turn (resolve! saved {:cells [0 1]}))))
    (is (= #{} (fields saved)))))

(deftest wheel-requires-two-occupations-and-costs-two-wood
  (let [state (-> (fresh) (hand :a018) (update-in [:players 0] a/gain {:wood 2}))]
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 (c/id :a018) 0 {})))
    (let [ready (-> state (own 0 :a103) (own 0 :a104))
          done (g/drain (g/play-card ready 0 (c/id :a018) 0 {}))]
      (is (= -2 (delta ready done :wood)))
      (is (= 1 (get-in done [:players 0 :card-state (c/id :a018) :uses]))))))

(deftest wheel-chooses-additional-fields-before-committing-one-worker
  (let [state (wheel (fresh)) pending (g/command state 0 {:type :action :space :plow :cell 0})
        saved (edn/read-string (pr-str pending))
        done (resolve! saved {:cells [1 2]})]
    (is (= :wheel-plow (get-in pending [:effect-queue 0 :effect :op])))
    (is (= #{} (fields pending)))
    (is (= 2 (get-in pending [:players 0 :remaining])))
    (is (= #{0 1 2} (fields done)))
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (= {:plow 0} (:occupied done)))
    (is (= 1 (:turn done)))
    (is (nil? (:plow-plan done)))
    (is (= 0 (get-in done [:players 0 :card-state (c/id :a018) :uses])))
    (doseq [cells [[0] [1 1] [14] [1 2 3] []]]
      (is (thrown? clojure.lang.ExceptionInfo (resolve! saved {:cells cells}))))
    (let [skipped (resolve! saved {:skip? true})]
      (is (= #{0} (fields skipped)))
      (is (= 1 (get-in skipped [:players 0 :card-state (c/id :a018) :uses]))))))

(deftest wheel-is-first-person-only-and-once-per-game
  (doseq [state [(assoc-in (wheel (fresh)) [:players 0 :remaining] 1)
                (assoc-in (wheel (fresh)) [:players 0 :card-state (c/id :a018) :uses] 0)]]
    (let [done (g/command state 0 {:type :action :space :plow :cell 0})]
      (is (= #{0} (fields done))) (is (empty? (:effect-queue done)))))
  (let [state (-> (wheel (fresh)) (assoc-in [:players 0 :family] 3) (assoc-in [:players 0 :newborn] 1))]
    (is (= :wheel-plow (get-in (g/command state 0 {:type :action :space :plow :cell 0}) [:effect-queue 0 :effect :op]))))
  (let [state (wheel (fresh)) done (g/command state 0 {:type :action :space :grain :cells [0 1] :plow-plan {:cells [0 1]}})]
    (is (empty? (fields done))) (is (= 1 (get-in done [:players 0 :card-state (c/id :a018) :uses])))))

(deftest wheel-cultivation-fields-exist-before-sowing-and-trigger-once
  (let [state (-> (wheel (fresh)) (own 0 :a105) (update :active conj :cultivation)
                  (update-in [:players 0] a/gain {:grain 3}))
        pending (g/command state 0 {:type :action :space :cultivation :cell 0})
        offer (resolve! pending {:cells [1 2]})
        sow (resolve! (edn/read-string (pr-str offer)) {})
        done (resolve! (edn/read-string (pr-str sow)) {:plantings [{:cell 0 :crop :grain} {:cell 1 :crop :grain} {:cell 2 :crop :grain}]})]
    (is (= :sow-offer (get-in offer [:effect-queue 0 :effect :op])))
    (is (= #{} (fields offer)))
    (is (= #{0 1 2} (set (a/tiles (g/sowing-player sow 0 (get-in sow [:effect-queue 0 :effect])) :field))))
    (is (= #{0 1 2} (fields done)))
    (is (= [3 3 3] (mapv #(get-in done [:players 0 :farm % :count]) [0 1 2])))
    (is (= 3 (delta state done :clay)))
    (is (= 3 (delta state done :food)))
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (= 0 (get-in done [:players 0 :card-state (c/id :a018) :uses])))
    (is (= 1 (:turn done)))))

(deftest multi-field-plows-trigger-fertilizers-as-one-construction-action
  (let [state (-> (fresh) (own 0 :a073) (hand :a020) (update-in [:players 0] a/gain {:grain 3}))
        pending (g/command state 0 {:type :action :space :meeting :card (c/id :a020)})
        offer (resolve! pending {:cells [0 1]})]
    (is (= (c/id :a073) (get-in offer [:effect-queue 0 :card])))
    (is (= :sow-offer (get-in offer [:effect-queue 0 :effect :op])))
    (let [done (resolve! (resolve! offer {}) {:plantings [{:cell 0 :crop :grain}]})]
      (is (= 3 (get-in done [:players 0 :farm 0 :count])))
      (is (= 1 (:turn done)))))
  (let [state (-> (wheel (fresh)) (own 0 :a073) (update-in [:players 0] a/gain {:grain 1}))
        offer (resolve! (g/command state 0 {:type :action :space :plow :cell 0}) {:cells [1]})]
    (is (= (c/id :a073) (get-in offer [:effect-queue 0 :card])))))

(deftest bots-have-legal-multi-field-plow-decisions
  (let [state (wheel (fresh)) pending (g/command state 0 {:type :action :space :plow :cell 0})
        candidates (filter #(= [1 2] (:cells %)) (bot/decision-candidates pending 0))]
    (is (seq candidates))
    (is (= #{0 1 2} (fields (g/command pending 0 (first candidates)))))))

(deftest wheel-can-plow-on-cultivation-without-a-base-field
  (let [state (-> (wheel (fresh)) (update :active conj :cultivation))
        pending (g/command state 0 {:type :action :space :cultivation})
        done (resolve! (resolve! pending {:cells [0 1]}) {:skip? true})]
    ;; No crops: decline the ordinary sow offer, then the
    ;; saved construction still commits, advances once, and spends one use.
    (is (= #{0 1} (fields done)))
    (is (= 1 (:turn done)))
    (is (= 0 (get-in done [:players 0 :card-state (c/id :a018) :uses])))
    (let [skipped (resolve! pending {:skip? true})]
      (is (empty? (fields skipped)))
      (is (= 2 (get-in skipped [:players 0 :remaining])))
      (is (nil? (:plow-plan skipped))))))

(deftest wheel-precedes-before-sow-seed-gifts-without-demanding-existing-grain
  (let [state (-> (wheel (fresh)) (own 0 :a065) (update :active conj :cultivation))
        wheel-choice (g/command state 0 {:type :action :space :cultivation :cell 0})
        offer (resolve! wheel-choice {:cells [1 2]})
        sow (resolve! offer {})
        done (resolve! sow {:plantings [{:cell 2 :crop :grain}]})]
    (is (= 1 (delta state sow :grain)))
    (is (= #{0 1 2} (fields done)))
    (is (= 3 (get-in done [:players 0 :farm 2 :count])))
    (is (= 0 (delta state done :grain)))
    (is (= 1 (:turn done)))))

(deftest wheel-still-commits-base-action-when-no-extra-field-fits
  (let [state (-> (wheel (fresh))
                  (assoc-in [:players 0 :farm] (mapv #(if (= % 0) {:type :empty} {:type :field}) (range 15))))
        pending (g/command state 0 {:type :action :space :plow :cell 0})
        done (resolve! pending {:skip? true})]
    (is (= :wheel-plow (get-in pending [:effect-queue 0 :effect :op])))
    (is (= 15 (count (fields done))))
    (is (= 1 (:turn done)))
    (is (= 1 (get-in done [:players 0 :card-state (c/id :a018) :uses])))))

(deftest invalidated-wheel-plans-cancel-without-consuming-use-or-leaking-to-other-actions
  (doseq [after-wheel-choice? [false true]]
    (let [state (-> (g/initialize (a/new-game ["A" "B" "C" "D" "E"] (vec (mapcat identity a/stages)) 0))
                    wheel (own 0 :a170) (update-in [:players 0] a/gain {:wood 4})
                    (update :active conj :cultivation))
          pending (g/command state 0 {:type :action :space :cultivation :cell 0})
          pending (if after-wheel-choice? (resolve! pending {:cells [1]}) pending)
          fenced (g/command pending 0 {:type :ability :card (c/id :a170) :fences (vec (a/edges (if after-wheel-choice? 1 0)))})
          cancelled (resolve! (edn/read-string (pr-str fenced)) {:skip? true})
          done (g/command cancelled 0 {:type :action :space :grain})]
      (is (nil? (:plow-plan cancelled)))
      (is (empty? (:effect-queue cancelled)))
      (is (= 2 (get-in cancelled [:players 0 :remaining])))
      (is (= 1 (get-in cancelled [:players 0 :card-state (c/id :a018) :uses])))
      (is (empty? (fields done)))
      (is (= 1 (get-in done [:players 0 :card-state (c/id :a018) :uses])))
      (is (= {:grain 0} (:occupied done))))))
