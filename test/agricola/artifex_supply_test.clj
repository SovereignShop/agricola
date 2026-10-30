(ns agricola.artifex-supply-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.card-reactive :as reactive] [agricola.card-random :as random]
            [agricola.bot :as bot]
            [agricola.expansion-cards-test :as fixture :refer [own hand resolve! delta]]))
(defn fresh [] (update (fixture/fresh) :active #(vec (distinct (conj % :fences)))))
(defn op [state] (get-in state [:effect-queue 0 :effect :op]))
(defn supply [state goods] (update-in state [:players 0] a/gain goods))
(defn reload [state] (edn/read-string (pr-str state)))
(defn plowed [state pid cells] (reduce #(update-in %1 [:players pid] a/place-tile :field %2) state cells))
(defn fenced [cells] (set (mapcat a/edges cells)))

(deftest loppers-removes-a-physical-piece-and-works-with-anytime-fencing
  (doseq [anytime? [false true]]
    (let [state (-> (fresh) (own 0 :a034) (own 0 :a170) (reactive/watch-fences 0) (supply {:wood 10}))
          pending (g/command state 0 (if anytime? {:type :ability :card (c/id :a170) :fences (a/edges 0)}
                                        {:type :action :space :fences :fences (a/edges 0)}))
          done (resolve! (reload pending) {})]
      (is (= :loppers (op pending)))
      (is (= -5 (delta state done :wood)))
      (is (= 2 (delta state done :food)))
      (is (= 1 (get-in done [:players 0 :bonus-points])))
      (is (= 1 (get-in done [:players 0 :discarded-fences])))
      (is (= 10 (a/fences-available (g/p done 0))))
      (is (nil? (get-in (resolve! pending {:skip? true}) [:players 0 :discarded-fences])))))
  (let [player (-> (g/p (fresh) 0) (a/gain {:wood 20}) (assoc :discarded-fences 12))]
    (is (thrown? clojure.lang.ExceptionInfo (a/build-fences player (a/edges 0))))))

(deftest potters-yard-marks-only-current-unused-spaces-and-rewards-each-once
  (let [state (-> (fresh) (plowed 0 [0 1 2 3 4 6]) (hand :a040) (supply {:wood 10 :reed 1}))
        ready (g/drain (g/play-card state 0 (c/id :a040) 0 {}))
        pending (g/command ready 0 {:type :action :space :plow :cell 7})
        clay (resolve! pending {:skip? true}) exchanged (resolve! (reload pending) {})]
    (is (= 7 (count (get-in ready [:players 0 :card-state (c/id :a040) :clay-cells]))))
    (is (= 1 (delta ready pending :clay)))
    (is (= 1 (delta ready clay :clay)))
    (is (= 0 (delta ready exchanged :clay)))
    (is (= 2 (delta ready exchanged :food)))
    (is (not (contains? (get-in clay [:players 0 :card-state (c/id :a040) :clay-cells]) 7)))
    (is (= 0 (delta clay (g/drain clay) :clay))))
  (is (thrown? clojure.lang.ExceptionInfo
               (g/play-card (-> (fresh) (hand :a040) (supply {:wood 1 :reed 1})) 0 (c/id :a040) 0 {}))))

(deftest potters-yard-pastures-pay-per-newly-used-space
  (let [state (-> (fresh) (plowed 0 [2 3 4 7 8 9]) (hand :a040) (supply {:wood 15 :reed 1}))
        ready (g/drain (g/play-card state 0 (c/id :a040) 0 {}))
        pending (g/command ready 0 {:type :action :space :fences :fences (vec (fenced [0 1]))})
        once (resolve! pending {:skip? true}) done (resolve! once {})]
    (is (= 2 (delta ready pending :clay)))
    (is (= 1 (delta ready done :clay)))
    (is (= 2 (delta ready done :food)))
    (is (empty? (:effect-queue done)))))

(deftest sequestrator-is-a-once-only-race-with-no-retroactive-awards
  (let [state (-> (fresh) (plowed 0 [0 1 2 3]) (plowed 1 [0 1 2 3 4]) (hand :a144))
        ready (g/drain (g/play-card state 0 (c/id :a144) 0 {}))
        won (g/command ready 0 {:type :action :space :plow :cell 4})]
    (is (= 0 (a/amount (g/p ready 1) :clay)))
    (is (= 4 (delta ready won :clay)))
    (is (= 0 (get-in won [:players 0 :card-state (c/id :a144) :stored-goods :clay])))
    (is (= 0 (get-in won [:players 0 :card-state (c/id :a144) :winners :clay])))
    (is (= 4 (a/amount (g/p (g/drain (reload won)) 0) :clay))))
  (let [state (-> (fresh) (hand :a144) (supply {:wood 15}))
        ready (g/drain (g/play-card state 0 (c/id :a144) 0 {}))
        won (g/command ready 0 {:type :action :space :fences :fences (vec (fenced [0 1 2]))})]
    (is (= 3 (delta ready won :reed)))
    (is (= 3 (count (a/pastures (g/p won 0)))))))

(def knife-cards (mapv c/id [:a103 :a104 :a116]))
(defn knife-ready []
  (-> (fresh) (hand :a003) (assoc-in [:players 0 :hand :occupation] knife-cards) (supply {:wood 1})))
(deftest paper-knife-is-private-deterministic-and-replay-safe
  (let [state (assoc (knife-ready) :card-rng 13)
        pending (g/command state 0 {:type :action :space :meeting :card (c/id :a003)})
        done (resolve! pending {:cards knife-cards}) restored (resolve! (reload pending) {:cards (reverse knife-cards)})]
    (is (= done restored))
    (is (= 1 (count (:occupations (g/p done 0)))))
    (is (= 2 (count (get-in done [:players 0 :hand :occupation]))))
    (is (= 0 (delta pending done :food)))
    (is (some #{(c/id :a003)} (get-in done [:players 1 :hand :minor])) "Passing card changes hands after its effect")
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (= 1 (:turn done)))
    (is (not (contains? (a/public-state pending) :card-rng)))
    (is (not (contains? (bot/observation pending 0) :card-rng)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:cards (take 2 knife-cards)})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:cards (repeat 3 (first knife-cards))}))))
  (is (thrown? clojure.lang.ExceptionInfo
               (g/play-card (assoc-in (knife-ready) [:players 0 :hand :occupation] (take 2 knife-cards)) 0 (c/id :a003) 0 {})))
  (let [counts (frequencies (for [seed (range 1 301)] (second (random/choose {:card-rng seed} knife-cards))))]
    (is (= #{100} (set (vals counts))))))

(defn horse [wood]
  (-> (fresh) (own 0 :a048) (supply {:wood wood}) (reactive/watch-wood 0)))
(deftest shaving-horse-thresholds-and-wood-delivery-batches
  (doseq [[wood mandatory? final-wood] [[2 false 4] [4 true 6] [6 true 8]]]
    (let [state (horse wood) next (g/command state 0 {:type :action :space :wood})
          done (if mandatory? next (resolve! (reload next) {}))]
      (is (= final-wood (a/amount (g/p done 0) :wood)))
      (is (= 3 (delta state done :food)))
      (is (empty? (:effect-queue done)))
      (when-not mandatory? (is (= 5 (a/amount (g/p (resolve! next {:skip? true}) 0) :wood))))))
  (let [state (-> (horse 3) (own 0 :a116))
        done (g/command state 0 {:type :action :space :wood})]
    (is (= 6 (a/amount (g/p done 0) :wood)))
    (is (= 3 (delta state done :food)) "Forest and Wood Cutter are one uninterrupted receipt batch"))
  (let [state (horse 3)
        next (g/drain (g/queue-front state [(g/task 0 nil {:op :gain :goods {:wood 2}} {})
                                           (g/task 0 nil {:op :gain :goods {:wood 2}} {})]))]
    (is (= 6 (a/amount (g/p next 0) :wood)))
    (is (= 3 (delta state next :food)))))

(deftest shaving-horse-restarts-after-intervening-nonwood-goods
  (let [state (horse 4)
        next (g/drain (g/queue-front state [(g/task 0 nil {:op :gain :goods {:wood 1}} {})
                                           (g/task 0 nil {:op :gain :goods {:food 1}} {})
                                           (g/task 0 nil {:op :gain :goods {:wood 2}} {})]))
        second (resolve! next {}) done (resolve! second {})]
    (is (= 5 (a/amount (g/p done 0) :wood)))
    (is (= 7 (delta state done :food)))
    (is (empty? (:effect-queue done)))))
