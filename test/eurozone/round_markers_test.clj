(ns eurozone.round-markers-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [eurozone.round-markers :as markers]))

(defn fresh [] (g/initialize (a/new-game ["Ada" "Ben" "Cal" "Dee"] (vec (mapcat identity a/stages)) 0)))
(defn play [state pid cid]
  (g/drain (g/play-card (update-in state [:players pid :hand (:kind (c/card cid))] conj cid) pid cid 0 {})))
(def plowman :catalog/original-base-k-293-plowman)
(def handler :catalog/original-base-k-305-animal-handler)

(deftest plowman-and-animal-handler-use-real-saved-arrival-effects
  (let [state (-> (fresh) (play 0 plowman) (play 1 handler))
        saved (edn/read-string (pr-str state))
        rounds (markers/by-round saved)]
    (is (= {} (get-in saved [:players 0 :schedule 5 0 :goods])))
    (doseq [round [5 8 11]]
      (is (= {:field 1} (get-in rounds [round 0 :goods])))
      (is (= {:food 1} (get-in rounds [round 0 :deliveries 0 :cost])))
      (is (true? (get-in rounds [round 0 :deliveries 0 :optional?]))))
    (doseq [[round animal] [[7 :sheep] [10 :boar] [14 :cattle]]]
      (is (= {animal 1} (get-in rounds [round 0 :goods]))))
    ;; Showing a future field does not place it or pay for it now.
    (is (empty? (a/tiles (g/p saved 0) :field)))
    (let [arrival (g/drain (g/begin-round (assoc saved :round 4)))
          resolved (g/command arrival 0 {:type :resolve :decision (:decision-id arrival) :cell 4})]
      (is (= :plow (get-in arrival [:effect-queue 0 :effect :op])))
      (is (nil? (get (markers/by-round arrival) 5)))
      (is (= :field (get-in resolved [:players 0 :farm 4 :type])))
      (is (= (dec (a/amount (g/p arrival 0) :food)) (a/amount (g/p resolved 0) :food))))))

(deftest reservations-and-passing-card-promises-are-public
  (let [state (-> (fresh)
                  (play 0 markers/fence-deliveryman)
                  (update-in [:players 1] a/gain {:food 2})
                  (play 1 markers/guest))
        rounds (markers/by-round state)]
    (is (= {:fence 4} (get-in rounds [7 0 :goods])))
    (is (= {:fence 4} (get-in rounds [11 0 :goods])))
    (is (= {:food 2} (get-in rounds [7 0 :deliveries 0 :cost])))
    (is (not (some #{markers/guest} (c/owned (g/p state 1)))))
    (is (= {:guest 1} (get-in rounds [2 0 :goods])))
    (is (= 1 (get-in rounds [2 0 :player])))
    (let [next-round (g/drain (g/begin-round state))]
      (is (nil? (get (markers/by-round next-round) 2)))
      (is (= 1 (get-in next-round [:players 1 :guests]))))))

(deftest well-stables-and-telegram-do-not-double-count-or-show-past-rounds
  (let [planner (c/id :a089) telegram :catalog/revised-artifex-expansion-a-22-telegram
        ;; Each reserved stable has a matching schedule entry. Count it once.
        state (-> (fresh)
                  (assoc-in [:players 0 :minors] [telegram])
                  (assoc-in [:players 0 :occupations] [planner])
                  (assoc-in [:players 0 :schedule] {4 [{:card planner :stable true} {:card telegram :person true}]})
                  (assoc-in [:players 0 :reserved-stables] {4 1})
                  (assoc-in [:players 0 :well-rounds] #{2 3})
                  (assoc-in [:players 0 :well-deliveries] {2 1 4 1}))
        rounds (markers/by-round state)]
    (is (= {:food 2} (get-in rounds [2 0 :goods])))
    (is (= {:food 1 :stable 1 :person 1} (get-in rounds [4 0 :goods])))
    (is (= [4] (vec (keys (markers/by-round (assoc state :round 3))))))
    (is (empty? (markers/by-round (assoc state :round 14))))
    (is (= {:food 1 :stable 1} (get-in (markers/by-round (assoc-in state [:players 0 :minors] [])) [4 0 :goods])))))

(deftest every-native-declarative-schedule-has-a-visible-piece-type
  ;; Audit the complete implemented catalogue, not an enumerated deck subset.
  (doseq [[cid card] c/cards
          effect (filter #(and (map? %) (= :schedule (:op %))) (tree-seq coll? seq card))]
    (let [entry {:goods (or (:goods effect) {}) :effect (:effect effect) :marker-goods (:marker-goods effect)}
          expected (or (:marker-goods effect) (get-in effect [:effect :marker-goods]) (not-empty (:goods effect)) (get-in effect [:effect :goods])
                       (when (= :plow (get-in effect [:effect :op])) {:field 1}))]
      (is (some? expected) (str "Unclassified future piece on " cid))
      (is (= expected (markers/scheduled-pieces entry)) (str cid)))))

(deftest scheduled-room-markers-stay-visual-and-respect-ownership
  (let [cid :catalog/original-expansion-wm-27-heiress
        entries {6 [{:card cid :goods {} :effect {:op :build-rooms :optional? true :marker-goods {:wood-room 1}}}]
                 10 [{:card cid :goods {} :marker-goods {:clay-room 1} :effect {:op :build-rooms :optional? true}}]
                 13 [{:card cid :goods {} :effect {:op :build-rooms :marker-goods {:stone-room 1}}}]}
        state (-> (fresh) (assoc-in [:players 0 :occupations] [cid]) (assoc-in [:players 0 :schedule] entries))
        restored (edn/read-string (pr-str state))]
    (doseq [[round room] [[6 :wood-room] [10 :clay-room] [13 :stone-room]]]
      (is (= {room 1} (get-in (markers/by-round restored) [round 0 :goods]))))
    (is (= [10 13] (vec (keys (markers/by-round (assoc restored :round 6))))))
    (is (empty? (markers/by-round (assoc-in restored [:players 0 :occupations] []))))
    (is (= entries (get-in restored [:players 0 :schedule])))))
