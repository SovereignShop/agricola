(ns agricola.original-k-workers-draw-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot] [agricola.simulation :as sim] [eurozone.store :as store]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(def hut :catalog/original-base-k-138-reed-hut)
(def broom :catalog/original-base-k-125-broom)
(defn reload [s] (edn/read-string (pr-str s)))
(defn rich [] (update-in (g/initialize (a/new-game ["A" "B"] (vec (mapcat identity a/stages)) 0)) [:players 0] a/gain {:wood 20 :reed 20 :food 20}))
(defn play [state cid] (g/drain (g/play-card (hand state cid) 0 cid 0 {})))
(defn hut-game [] (play (rich) hut))

(deftest reed-hut-adds-an-immediate-fed-worker-without-family-points
  (let [state (hut-game) player (g/p state 0)
        next (g/drain (g/begin-round (reload state)))]
    (is (= [2 0 3] ((juxt :family :newborn :remaining) player)))
    (is (= 1 (a/reed-hut-count player)))
    (is (= 2 (a/unused-person-tokens player)))
    (is (= 6 (a/food-needed state player)))
    (is (= 6 (get-in (g/score state 0) [:breakdown :family])))
    (is (= 3 (get-in next [:players 0 :remaining])))
    (is (nil? (sim/assert-invariants! state))))
  (let [full (play (assoc-in (rich) [:players 0 :family] 5) hut)]
    (is (= 0 (a/reed-hut-count (g/p full 0))))
    (is (c/has? (g/p full 0) hut))))

(deftest reed-hut-is-last-after-family-and-guest-and-cannot-adopt-itself
  (let [state (-> (hut-game) (assoc-in [:players 1 :family] 3) (assoc-in [:players 1 :remaining] 3))
        first (g/command state 0 {:type :action :space :wood})
        second (-> first (g/command 1 {:type :action :space :clay}) (g/command 0 {:type :action :space :reed}))
        third (-> second (g/command 1 {:type :action :space :fishing}) (g/command 0 {:type :action :space :grain}))]
    (is (not (get-in first [:players 0 :reed-hut-placed?])))
    (is (not (get-in second [:players 0 :reed-hut-placed?])))
    (is (true? (get-in third [:players 0 :reed-hut-placed?])))
    (is (= 0 (get-in third [:players 0 :remaining])))
    (is (= 3 (g/placed-people third 0))))
  (let [state (-> (hut-game) (assoc-in [:players 0 :family] 4) (assoc-in [:players 0 :remaining] 1)
                  (update :active conj :urgent-growth))]
    (is (nil? (bot/preview state 0 {:type :action :space :urgent-growth})))
    (is (thrown? clojure.lang.ExceptionInfo (g/command state 0 {:type :action :space :urgent-growth}))))
  (let [state (-> (hut-game) (assoc-in [:players 0 :guests] 1) (assoc-in [:players 0 :remaining] 2))]
    (is (false? (get-in (g/prepare-placement state) [:players 0 :reed-hut-acting?])))
    (is (true? (get-in (g/prepare-placement (assoc-in state [:players 0 :remaining] 1)) [:players 0 :reed-hut-acting?])))))

(deftest growth-can-adopt-the-hut-worker-or-use-an-unused-token
  (let [player (g/p (hut-game) 0)
        ordinary (a/grow player false {:adopt-reed-hut? false})
        adopted (a/grow player false {:adopt-reed-hut? true})
        last-token (a/grow (assoc player :family 4) false)]
    (is (= [3 1 3 1] ((juxt :family :newborn :remaining a/reed-hut-count) ordinary)))
    (is (= [3 1 2 0] ((juxt :family :newborn :remaining a/reed-hut-count) adopted)))
    (is (= 5 (:family last-token)))
    (is (= 0 (a/reed-hut-count last-token)))
    (is (thrown? clojure.lang.ExceptionInfo (a/grow (assoc player :family 4) false {:adopt-reed-hut? false}))))
  (let [state (-> (hut-game) (update :active conj :urgent-growth))
        grown (g/command state 0 {:type :action :space :urgent-growth :adopt-reed-hut? true})]
    (is (= [3 1 1 0] ((juxt :family :newborn :remaining a/reed-hut-count) (g/p grown 0))))
    (is (= 5 (a/food-needed grown (g/p grown 0)))))
  (let [state (g/drain (g/queue-front (hut-game) [(g/task 0 hut {:op :grow :room-required? false} {})]))
        options (bot/decision-candidates state 0)]
    (is (= :grow (get-in state [:effect-queue 0 :effect :op])))
    (is (= #{false true} (set (map :adopt-reed-hut? options))))
    (is (= 0 (a/reed-hut-count (g/p (resolve! (reload state) {:adopt-reed-hut? true}) 0))))))

(deftest discarded-hut-occupant-leaves-at-the-correct-time
  (let [state (hut-game) player (g/p state 0)
        early (g/remove-owned-improvement player hut)
        placed (assoc player :reed-hut-placed? true :remaining 0)
        late (g/remove-owned-improvement placed hut)
        home (g/prepare-return-home (assoc-in state [:players 0] late))]
    (is (= [2 0] ((juxt :remaining a/reed-hut-count) early)))
    (is (= 1 (a/reed-hut-count late)))
    (is (true? (:reed-hut-discard? late)))
    (is (= 0 (a/reed-hut-count (g/p home 0))))
    (is (= 4 (a/food-needed home (g/p home 0))))))

(deftest hut-respects-physical-token-limit-and-holiday-house
  (let [state (-> (hut-game) (assoc-in [:players 0 :family] 4))]
    (is (thrown? clojure.lang.ExceptionInfo (g/run-effect state (g/task 0 hut {:op :temporary-person} {}) {}))))
  (let [state (-> (rich) (assoc :round 14) (own 0 :catalog/original-base-i-71-holiday-house)
                  (assoc-in [:players 0 :remaining] 0) (play hut))]
    (is (= 0 (get-in state [:players 0 :remaining])))
    (is (= 1 (a/reed-hut-count (g/p state 0))))))

(defn broom-game [n mode]
  (with-redefs [c/cards (into {} (filter (fn [[_ card]] (and (= "Original" (:edition card)) (= "K" (:deck card)))) c/cards))]
    (sim/initial-state {:players n :seed 612 :mode mode :extra 1})))

(deftest broom-draws-private-undealt-minors-and-preserves-a-paid-play-window
  (let [initial (broom-game 2 :random)
        available (vec (remove #{broom} (:minor-deck initial)))
        state (-> initial (assoc :minor-deck available :minor-deck-count (count available))
                  (update-in [:players 0] a/gain {:wood 10 :food 10}) (hand broom))
        old-hand (set (get-in state [:players 0 :hand :minor]))
        drawn (play (reload state) broom)]
    (is (= (take 7 available) (get-in drawn [:players 0 :hand :minor])))
    (is (not-any? old-hand (get-in drawn [:players 0 :hand :minor])))
    (is (= (vec (drop 7 available)) (:minor-deck drawn)))
    (is (= (- (count available) 7) (:minor-deck-count drawn)))
    (is (every? #(= "K" (:deck (c/card %))) (get-in drawn [:players 0 :hand :minor])))
    (is (= :card-action (get-in drawn [:effect-queue 0 :effect :op])))
    (is (some? (resolve! (reload drawn) {:skip? true})))
    (is (nil? (:minor-deck (a/public-state drawn))))
    (is (nil? (:minor-deck (bot/observation drawn 0))))))

(deftest replacement-deck-exhaustion-never-recycles-discarded-cards
  (let [initial (broom-game 2 :random) supply (vec (take 3 (remove #{broom} (:minor-deck initial))))
        state (-> initial (assoc :minor-deck supply :minor-deck-count 3) (hand broom)
                  (update-in [:players 0] a/gain {:wood 2}))
        drawn (play state broom)]
    (is (= supply (get-in drawn [:players 0 :hand :minor])))
    (is (= [] (:minor-deck drawn)))
    (is (zero? (:minor-deck-count drawn)))))

(deftest configured-deck-exclusions-apply-to-the-reserve-for-all-player-counts
  (let [keep-pack (:pack-id (c/card broom))
        excluded (vec (remove #{keep-pack} (distinct (map :pack-id (vals c/cards)))))]
    (doseq [n [1 2 3 4 5] mode [:random :draw]]
      (let [state (store/start-state (mapv #(str "P" %) (range n)) {:mode mode :extra 0 :excluded-packs excluded})]
        (is (seq (:minor-order state)))
        (is (every? #(= keep-pack (:pack-id (c/card %))) (:minor-order state))))
      (when (= mode :random)
        (let [state (store/start-state (mapv #(str "P" %) (range n)) {:mode :random :excluded-packs excluded})
              hands (set (mapcat #(get-in % [:hand :minor]) (:players state)))]
          (is (= (- 43 (* 7 n)) (:minor-deck-count state)))
          (is (not-any? hands (:minor-deck state))))))))
