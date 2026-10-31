(ns agricola.original-e-resource-actions-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot] [agricola.card-expressions :as expr] [agricola.five-player :as five]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(def fisherman :catalog/original-base-e-161-fisherman)
(def forester :catalog/original-base-e-164-master-forester)
(def rod :catalog/original-base-e-12-fishing-rod)
(def raft :catalog/original-base-e-22-raft)
(def canoe :catalog/original-base-e-30-canoe)
(def trap :catalog/original-base-i-95-fish-trap)
(def net :catalog/original-base-k-126-landing-net)
(def berries :catalog/original-base-e-152-berry-picker)
(def woodcutter :catalog/original-base-e-176-woodcutter)
(defn reload [state] (edn/read-string (pr-str state)))
(defn food [state pid] (a/amount (g/p state pid) :food))
(defn fishing [state] (g/command state 0 {:type :action :space :fishing}))
(defn op [state] (get-in state [:effect-queue 0 :effect :op]))

(deftest fisherman-doubles-only-the-pile-and-pays-per-other-owned-card
  (let [state (-> (fresh) (own 0 fisherman) (own 0 raft) (own 1 rod) (own 1 canoe)
                  ;; I/K prints are referenced by identity even before their own effects are implemented.
                  (update-in [:players 2 :minors] into [trap net])
                  (assoc-in [:piles :fishing] {:food 3}))
        waiting (fishing state)
        after-fisherman (resolve! (reload waiting) {})
        done (resolve! after-fisherman {:resource :food})]
    (is (= :fisherman (op waiting)))
    (is (= 3 (delta state waiting :food))) ; Fisherman waits before Raft.
    (is (= 7 (- (get-in done [:players 0 :received :food] 0) (get-in state [:players 0 :received :food] 0))))
    (is (= 3 (delta state done :food))) ; 3 pile +3 bonus -4 fees +1 own Raft.
    (is (= 2 (- (food done 1) (food state 1))))
    (is (= 2 (- (food done 2) (food state 2))))
    (is (= 1 (:turn done)))
    (is (zero? (get-in done [:piles :fishing :food])))))

(deftest fisherman-can-decline-and-ignores-hand-cards
  (let [state (-> (fresh) (own 0 fisherman) (assoc-in [:piles :fishing] {:food 2})
                  (assoc-in [:players 1 :hand :minor] [rod raft canoe trap net]))
        waiting (fishing state) accept (resolve! waiting {}) decline (resolve! waiting {:skip? true})]
    (is (= 4 (delta state accept :food)))
    (is (= 2 (delta state decline :food)))
    (is (= (food state 1) (food accept 1)))
    (is (= :work (:phase accept))))
  (let [state (-> (fresh) (own 0 fisherman))]
    (is (empty? (g/trigger-tasks state (g/event :use-space 0 {:space :reed :taken {:food 4}}))))
    (is (empty? (g/trigger-tasks state (g/event :use-space 1 {:space :fishing :taken {:food 4}}))))))

(deftest fisherman-fee-can-exceed-bonus-but-must-be-funded
  (let [state (-> (fresh) (own 0 fisherman) (assoc-in [:players 0 :goods :food] 0)
                  (assoc-in [:players 1 :minors] [rod raft canoe trap net])
                  (assoc-in [:piles :fishing] {:food 1}))
        no-funds (fishing state)
        funded (assoc-in state [:players 0 :goods :food] 3)
        waiting (fishing funded) done (resolve! waiting {})]
    (is (= 1 (:turn no-funds)))
    (is (empty? (:effect-queue no-funds)))
    (is (= 1 (food no-funds 0)))
    (is (= :fisherman (op waiting)))
    (is (zero? (food done 0)))
    (is (= 5 (- (food done 1) (food state 1))))
    (let [convertible (fishing (assoc-in state [:players 0 :goods :grain] 3))]
      (is (= :fisherman (op convertible)))
      (is (thrown? clojure.lang.ExceptionInfo (resolve! convertible {})))
      (is (seq (filter #(= :convert (:type %)) (bot/decision-candidates convertible 0))))
      (let [converted (g/command convertible 0 {:type :convert :goods {:grain 3}})]
        (is (zero? (food (resolve! converted {}) 0)))))))

(deftest master-forester-starts-empty-and-accumulates-separately-from-round-reveals
  (let [state (-> (fresh) (hand forester))
        played (g/drain (g/play-card state 0 forester 0 {}))
        next-round (g/drain (g/begin-round played))
        later (g/drain (g/begin-round next-round))]
    (is (= {:wood 0} (get-in played [:piles forester])))
    (is (= 2 (get-in next-round [:piles forester :wood])))
    (is (= 4 (get-in later [:piles forester :wood])))
    (is (= (+ (count (a/base-actions 4 :cards)) (:round later)) (count (:active later))))
    (is (not (some #{forester} (:active later))))
    (is (= {:wood 2} (:gain (a/action-definition later forester))))
    (doseq [pid (range 4)]
      (is (= {:card forester :owner 0 :kind :master-forester}
             (first (filter #(= forester (:card %)) (g/card-spaces later pid))))))))

(deftest master-forester-owner-free-other-players-pay-before-the-bonus
  (let [state (-> (fresh) (own 0 forester) (own 1 berries) (own 1 woodcutter)
                  (assoc-in [:piles forester] {:wood 6}) (assoc :turn 1)
                  (assoc-in [:players 1 :goods :food] 2))
        poor (assoc-in state [:players 1 :goods :food] 1)
        done (g/command (reload state) 1 {:type :card-action :card-space forester})]
    (is (string? (g/card-space-error poor 1 forester)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command poor 1 {:type :card-action :card-space forester})))
    (is (= 2 (delta state done :food)))
    (is (= 1 (food done 1)))
    (is (= 7 (a/amount (g/p done 1) :wood)))
    (is (= 1 (get-in done [:occupied forester])))
    (is (= {:wood 0} (get-in done [:piles forester])))
    (is (= #{forester} (get-in done [:players 1 :used-accumulations :by-good :wood])))
    (is (string? (g/card-space-error done 2 forester)))
    (is (some #(= forester (:card-space %)) (bot/card-action-candidates state 1)))
    (let [owner (-> state (assoc :turn 0) (assoc-in [:players 0 :goods :food] 0))
          collected (g/command owner 0 {:type :card-action :card-space forester})]
      (is (= 0 (food collected 0)))
      (is (= 6 (a/amount (g/p collected 0) :wood)))))
  (let [state (-> (fresh) (own 0 forester) (assoc-in [:piles forester] {:wood 2}))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/command state 0 {:type :card-action :card-space forester :improvement :well})))) )

(deftest fisherman-recognizes-the-equivalent-revised-canoe-print
  (let [state (-> (fresh) (own 0 fisherman) (own 1 :a078)
                  (assoc-in [:piles :fishing] {:food 2}))
        done (resolve! (fishing state) {})]
    (is (= 3 (delta state done :food)))
    (is (= 1 (- (food done 1) (food state 1))))))

(deftest master-forester-counts-as-an-additional-wood-accumulation
  (let [state (-> (fresh) (own 0 forester) (assoc-in [:piles forester] {:wood 2})
                  (assoc :occupied {:wood 0 :grove 1 forester 2}))
        ordinary (update-in state [:players 0 :occupations] #(vec (remove #{forester} %)))]
    (is (= 3 (five/occupied-wood state)))
    (is (= (inc (expr/evaluate ordinary 0 {} [:space-pile-count :wood := 2]))
           (expr/evaluate state 0 {} [:space-pile-count :wood := 2])))
    (is (some #{ {:space forester :resource :wood} } (g/pile-good-options state {:building-only? true})))
    (is (= (+ (count (a/base-actions 4 :cards)) 1) (count (:active state))))))
