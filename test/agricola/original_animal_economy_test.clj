(ns agricola.original-animal-economy-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [agricola.bot :as bot]
            [agricola.original-animals-test :as animals]))
(def brush :catalog/original-base-e-156-brush-maker)
(def tenant :catalog/original-base-e-215-tenant-farmer)
(def yeoman :catalog/original-base-e-165-yeoman-farmer)
(defn state [ids goods]
  (assoc-in (animals/fresh) [:players 0]
            (-> (apply animals/own (animals/player) ids) (animals/animal-goods goods))))
(defn resolve! [state choice]
  (g/command state (g/acting-player state) (merge {:type :resolve :decision (:decision-id state)} choice)))
(defn cook [state goods] (g/command state 0 {:type :convert :goods goods}))
(defn kitchen [state] (update-in state [:players 0 :improvements] conj :hearth-4))
(defn final-pending [state] (g/drain (g/finish-game (assoc state :round 14 :phase :finish-round))))

(deftest tenant-farmer-loans-real-animals-and-allows-immediate-cooking
  (let [before (-> (state [] {}) kitchen (assoc-in [:players 0 :hand :occupation] [tenant]))
        pending (g/drain (g/play-card before 0 tenant 0 {}))
        done (resolve! pending {:keep {:sheep 0 :boar 0 :cattle 0} :cook? true})]
    (is (= :housing (get-in pending [:effect-queue 0 :effect :op])))
    (is (= {:sheep 1 :boar 1 :cattle 1} (select-keys (:goods (g/p pending 0)) a/animals)))
    (is (= 9 (- (a/amount (g/p done 0) :food) (a/amount (g/p before 0) :food))))
    (is (= -3 (g/card-bonus done 0 tenant)))
    (is (= 0 (reduce + (map #(a/amount (g/p done 0) %) a/animals))))))

(deftest tenant-final-settlement-is-an-explicit-saveable-choice
  (let [before (state [tenant animals/yard] {:sheep 1 :boar 1 :cattle 1})
        pending (final-pending before)
        saved (edn/read-string (pr-str pending))
        kept (resolve! saved {:return-animals {}})
        returned (resolve! saved {:return-animals {:sheep 1 :boar 1 :cattle 1}})]
    (is (= :scoring (:phase pending)))
    (is (= :return-animal-loan (get-in pending [:effect-queue 0 :effect :op])))
    (is (= :finished (:phase kept)))
    (is (= -3 (g/card-bonus kept 0 tenant)))
    (is (= {:sheep 1 :boar 1 :cattle 1} (select-keys (:goods (g/p kept 0)) a/animals)))
    (is (= 0 (g/card-bonus returned 0 tenant)))
    (is (every? zero? (map #(a/amount (g/p returned 0) %) a/animals)))
    (is (= :finished (:phase returned)))
    (is (= (:scores kept) (:scores (edn/read-string (pr-str kept))))))
  (let [pending (final-pending (state [tenant] {})) done (resolve! pending {})]
    (is (= -3 (g/card-bonus done 0 tenant)))
    (is (= :finished (:phase done)))))

(deftest early-partial-settlement-is-final-and-never-cooks-returned-animals
  (let [before (state [tenant brush animals/yard] {:sheep 1 :boar 1 :cattle 1})
        done (g/command before 0 {:type :ability :card tenant :return-animals {:boar 1}})]
    (is (= -2 (g/card-bonus done 0 tenant)))
    (is (= 0 (a/amount (g/p done 0) :boar)))
    (is (empty? (:effect-queue done)))
    (is (= 0 (g/card-bonus done 0 brush)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/command (edn/read-string (pr-str done)) 0 {:type :ability :card tenant :return-animals {:sheep 1}})))
    (is (= :finished (:phase (final-pending done)))))
  (doseq [returned [{:sheep 2} {:boar 1} {:wood 1} {:cattle -1}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/command (state [tenant] {:sheep 1}) 0 {:type :ability :card tenant :return-animals returned}))))
  (let [pending (final-pending (state [tenant] {}))]
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:return-animals {:sheep 1}})))))

(deftest yeoman-farmer-cancels-unreturned-loan-penalties
  (let [with-loan (resolve! (final-pending (state [tenant yeoman] {})) {})
        without-loan (state [yeoman] {})]
    (is (= -3 (g/card-bonus with-loan 0 tenant)))
    (is (= 3 (- (g/card-bonus with-loan 0 yeoman) (g/card-bonus without-loan 0 yeoman))))
    (is (= (:total (g/score without-loan 0)) (:total (g/score with-loan 0))))))

(deftest brush-maker-collects-only-actually-cooked-boars
  (let [before (kitchen (state [brush] {:boar 2}))
        pending (cook before {:boar 2})
        saved (edn/read-string (pr-str pending))
        collected (resolve! saved {:amount 2})
        partial (resolve! saved {:amount 1})
        skipped (resolve! saved {:skip? true})]
    (is (= :collect-cooked-animal (get-in pending [:effect-queue 0 :effect :op])))
    (is (= {:boar 2} (get-in pending [:effect-queue 0 :context :converted-goods])))
    (is (= 6 (- (a/amount (g/p pending 0) :food) (a/amount (g/p before 0) :food))))
    (is (= 2 (get-in collected [:players 0 :card-state brush :stored-goods :boar])))
    (is (= 1 (g/card-bonus collected 0 brush)))
    (is (= 0 (a/amount (g/p collected 0) :boar)))
    (is (= 1 (get-in partial [:players 0 :card-state brush :stored-goods :boar])))
    (is (= 0 (g/card-bonus skipped 0 brush)))
    (is (empty? (:effect-queue skipped)))
    (doseq [n [-1 0 3 1.5]]
      (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:amount n}))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/command collected 0 {:type :resolve :decision (:decision-id pending) :amount 2}))))
  (doseq [[boars points] [[0 0] [1 0] [2 1] [3 2] [4 3] [9 3]]]
    (is (= points (g/card-bonus (assoc-in (state [brush] {}) [:players 0 :card-state brush :stored-goods :boar] boars) 0 brush)))))

(deftest brush-maker-receives-feeding-and-overflow-cooking-but-not-release
  (let [before (-> (state [brush] {:boar 2}) kitchen (assoc :phase :feeding :fed #{}))
        pending (g/command before 0 {:type :feed :convert {:boar 2} :craft []})]
    (is (= 0 (g/acting-player pending)))
    (is (= :collect-cooked-animal (get-in pending [:effect-queue 0 :effect :op])))
    (is (= 1 (g/card-bonus (resolve! pending {}) 0 brush))))
  (let [before (-> (state [brush] {:boar 3}) kitchen g/with-housing g/drain)
        choice {:keep {:sheep 0 :boar 1 :cattle 0}}
        cooked (resolve! before (assoc choice :cook? true))
        released (resolve! before (assoc choice :cook? false))]
    (is (= :collect-cooked-animal (get-in cooked [:effect-queue 0 :effect :op])))
    (is (= 1 (g/card-bonus (resolve! cooked {}) 0 brush)))
    (is (empty? (:effect-queue released)))
    (is (= 0 (g/card-bonus released 0 brush)))))

(deftest brush-maker-does-not-reward-old-cooking-or-resource-payment
  (let [before (-> (state [] {:boar 1}) kitchen (cook {:boar 1}))
        played (-> before (assoc-in [:players 0 :hand :occupation] [brush]) (g/play-card 0 brush 0 {}) g/drain)]
    (is (empty? (:effect-queue played)))
    (is (= 0 (g/card-bonus played 0 brush))))
  (let [p (g/p (kitchen (state [brush] {:boar 1})) 0)
        paid (assoc-in (animals/fresh) [:players 0] (a/pay p {:boar 1}))]
    (is (empty? (:effect-queue (g/drain paid)))))
  (is (thrown? clojure.lang.ExceptionInfo (cook (state [brush] {:boar 1}) {:boar 1}))))

(deftest bots-can-settle-final-loans-and-collect-cooked-markers
  (doseq [pending [(final-pending (state [tenant animals/yard] {:sheep 1 :boar 1 :cattle 1}))
                   (cook (kitchen (state [brush] {:boar 2})) {:boar 2})]]
    (let [outcomes (keep #(bot/preview pending 0 %) (bot/decision-candidates pending 0))]
      (is (seq outcomes))
      (is (some #(empty? (:effect-queue %)) outcomes))))
  (let [pending (final-pending (state [tenant animals/yard] {:sheep 1 :boar 1 :cattle 1}))
        choices (map :return-animals (bot/decision-candidates pending 0))]
    (is (some #{{:sheep 1 :boar 1 :cattle 1}} choices))
    (is (some #{{:sheep 0 :boar 0 :cattle 0}} choices))))
