(ns agricola.original-i-crops-rounds-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.card-expressions :as expr] [agricola.bot :as bot] [agricola.simulation :as sim]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(def copse :catalog/original-base-i-78-copse)
(def house :catalog/original-base-i-71-holiday-house)
(def guest :catalog/original-base-i-73-guest)
(def manure :catalog/original-base-i-92-manure)
(def layabout :catalog/original-base-i-244-layabout)
(def fieldsman :catalog/original-base-i-219-fieldsman)
(def gardener :catalog/original-base-i-226-gardener)
(def planter :catalog/original-base-i-90-planter-box)
(defn reload [s] (edn/read-string (pr-str s)))
(defn rich [] (update-in (fresh) [:players 0] a/gain {:wood 12 :reed 8 :clay 12 :grain 8 :vegetable 4 :food 20}))
(defn play [state cid] (g/drain (g/play-card (hand state cid) 0 cid 0 {})))
(defn copse-state [] (play (own (rich) 0 fieldsman) copse))
(defn wood-plan [state] (mapv #(hash-map :cell % :crop :wood) (sort (keys (get-in state [:players 0 :card-fields])))))

(defn house-five-sheep [state]
  (let [fences (vec (keep #(when (= 1 (val %)) (key %)) (frequencies (mapcat a/edges [1 2 3]))))]
    (-> state (update-in [:players 0] a/gain {:wood 8})
        (update-in [:players 0] a/build-fences fences)
        (assoc-in [:players 0 :goods :sheep] 5))))
(deftest copse-has-two-independent-wood-stacks-and-is-not-a-field-prerequisite
  (let [state (copse-state) before (g/p state 0) plan (wood-plan state)
        after (a/sow before plan)]
    (is (= 2 (count plan)))
    (is (= [4 4] (mapv :count (vals (:card-fields after)))))
    (is (= 0 (expr/evaluate (assoc-in state [:players 0] after) 0 {} [:fields])))
    (is (= 0 (expr/evaluate (assoc-in state [:players 0] after) 0 {} [:grain-fields])))
    (is (= -2 (- (a/amount after :wood) (a/amount before :wood))))
    (is (= (+ 6 (a/amount before :wood)) (a/craft-stock (a/field-harvest after) :wood)))
    (is (= [3 3] (mapv :count (vals (:card-fields (a/field-harvest after))))))
    (is (thrown? clojure.lang.ExceptionInfo (a/sow before [(assoc (first plan) :crop :grain)])))
    (is (thrown? clojure.lang.ExceptionInfo (a/sow before [(first plan) (first plan)])))
    (is (thrown? clojure.lang.ExceptionInfo (a/sow before [{:cell :forged-copse :crop :wood}])))))

(deftest fieldsman-planter-and-potato-bonuses-respect-stack-count-and-physical-adjacency
  (let [state (-> (copse-state) (own 0 planter) (own 0 :catalog/original-base-e-32-potato-dibber)
                  (update-in [:players 0] a/place-tile :field 0))
        player (g/p state 0)
        one (a/sow player [{:cell 0 :crop :vegetable}])
        two (a/sow player (wood-plan state))
        three (a/sow player (conj (wood-plan state) {:cell 0 :crop :grain}))]
    (is (= 6 (get-in one [:farm 0 :count])))
    (is (= [4 4] (mapv :count (vals (:card-fields two)))))
    (is (= [3 3] (mapv :count (vals (:card-fields three)))))
    (is (= 5 (get-in three [:farm 0 :count])))
    (is (= 6 (get-in (a/sow player [{:cell 0 :crop :grain}] {:stack-adjust {:grain -1}}) [:farm 0 :count])))))

(deftest wood-only-sowing-is-legal-on-board-and-in-saved-sow-popup
  (let [state (-> (copse-state) (update :active conj :sow-bake) (assoc-in [:players 0 :goods :grain] 0) (assoc-in [:players 0 :goods :vegetable] 0))
        plan (wood-plan state)
        direct (g/command (reload state) 0 {:type :action :space :sow-bake :plantings plan})
        pending (g/drain (g/queue-front state [(g/task 0 copse {:op :sow-offer :optional? true :unconditional? true} {})]))
        sow (resolve! pending {})
        done (resolve! (reload sow) {:plantings plan})]
    (is (= [4 4] (mapv :count (vals (get-in direct [:players 0 :card-fields])))))
    (is (= [4 4] (mapv :count (vals (get-in done [:players 0 :card-fields])))))
    (is (some #(and (= (set plan) (set (:plantings %))) (bot/preview sow 0 %)) (bot/decision-candidates sow 0)))
    (is (not (g/sowing-potential? state 0 {:allowed-crops #{:grain}})))))

(deftest manure-harvests-all-crops-including-wood-without-animal-food-or-consuming-layabout
  (let [state (-> (copse-state) (own 0 manure) (own 0 gardener) (own 0 :catalog/original-base-e-51-spindle)
                  (assoc-in [:players 0 :skip-next-harvest?] true)
                  (update-in [:players 0] a/place-tile :field 0)
                  (update-in [:players 0] a/sow (conj (wood-plan (copse-state)) {:cell 0 :crop :vegetable}))
                  house-five-sheep)
        pending (g/drain (g/queue-front state [(g/event :round-end 0)]))
        done (resolve! (reload pending) {})]
    (is (= :manure-harvest (get-in pending [:effect-queue 0 :effect :op])))
    (is (= 2 (delta pending done :wood)))
    (is (= 1 (delta pending done :vegetable)))
    (is (= 0 (delta pending done :food)))
    (is (= 2 (get-in done [:players 0 :farm 0 :count])))
    (is (true? (get-in done [:players 0 :skip-next-harvest?])))
    (is (= [2 2] (mapv :count (vals (get-in done [:players 0 :card-fields])))))
    (is (= 0 (delta pending (resolve! pending {:skip? true}) :wood)))
    (is (empty? (:effect-queue (g/drain (g/queue-front (assoc state :round 4) [(g/event :round-end 0)])))))))

(deftest guest-survives-passing-without-changing-family-feeding-or-score
  (let [played (play (fresh) guest)
        arrived (g/drain (g/begin-round (reload played)))
        player (g/p arrived 0)
        following (g/drain (g/begin-round arrived))]
    (is (not (c/has? (g/p played 0) guest)))
    (is (some #{guest} (get-in played [:players 1 :hand :minor])))
    (is (= [2 3 1] ((juxt :family :remaining :guests) player)))
    (is (= 4 (a/food-needed arrived player)))
    (is (= 6 (get-in (g/score arrived 0) [:breakdown :family])))
    (is (= [2 2 0] ((juxt :family :remaining :guests) (g/p following 0))))
    (is (nil? (sim/assert-invariants! arrived))))
  (let [state (-> (fresh) (assoc-in [:players 0 :family] 5) (assoc-in [:players 0 :guest-schedule 2] 1))
        next (g/drain (g/begin-round state))]
    (is (= 6 (get-in next [:players 0 :remaining])))
    (is (= 5 (get-in next [:players 0 :family])))))

(deftest holiday-house-allows-preparation-and-harvest-but-no-family-or-guest-placement
  (let [state (-> (rich) (assoc :round 13) (play house)
                  (assoc-in [:players 0 :guest-schedule 14] 1)
                  (assoc-in [:players 0 :well-rounds] #{14}))
        next (g/drain (g/begin-round (reload state)))]
    (is (= :work (:phase next)))
    (is (= 1 (:turn next)))
    (is (= 0 (get-in next [:players 0 :remaining])))
    (is (= 1 (delta state next :food)))
    (is (= 1 (get-in next [:players 0 :guests])))
    (is (thrown? clojure.lang.ExceptionInfo (g/command next 0 {:type :action :space :wood}))))
  (is (thrown? clojure.lang.ExceptionInfo (play (assoc (rich) :round 14) house))))

(deftest layabout-skips-exactly-one-whole-harvest-and-its-optional-cards
  (let [state (-> (fresh) (play layabout) (own 0 :catalog/original-base-e-51-spindle)
                  (update-in [:players 0] a/place-tile :field 0)
                  (assoc-in [:players 0 :farm 0] {:type :field :crop :grain :count 1})
                  house-five-sheep (assoc :round 4 :phase :return-home))
        pending (g/drain (g/continue-step state :harvest-or-round))
        fed (reduce #(g/command %1 %2 {:type :feed}) pending [1 2 3])
        next (reduce #(g/command %1 %2 {:type :breed :offspring {}}) fed [1 2 3])]
    (is (= [:feeding 1 #{0}] ((juxt :phase :turn :fed) pending)))
    (is (= [:breeding 1 #{0}] ((juxt :phase :turn :bred) fed)))
    (is (= 0 (delta state pending :grain)))
    (is (= 0 (delta state pending :food)))
    (is (= 1 (get-in next [:players 0 :farm 0 :count])))
    (is (= 0 (get-in next [:players 0 :begging])))
    (is (= 5 (get-in next [:players 0 :goods :sheep])))
    (is (nil? (:harvest-skipped next)))
    (is (nil? (get-in next [:players 0 :skip-next-harvest?])))))

(deftest solo-layabout-completes-the-skipped-harvest-without-prompting-or-feeding
  (let [state (-> (g/initialize (a/new-game ["Solo"] (vec (mapcat identity a/stages)) 0))
                  (play layabout) (assoc :round 4 :phase :return-home)
                  (assoc-in [:players 0 :goods :food] 0))
        next (g/drain (g/continue-step state :harvest-or-round))]
    (is (= [:work 5 0] ((juxt :phase :round :turn) next)))
    (is (= 0 (get-in next [:players 0 :begging])))
    (is (empty? (:effect-queue next)))
    (is (nil? (:harvest-skipped next)))
    (is (nil? (get-in next [:players 0 :skip-next-harvest?])))))

(deftest solo-holiday-house-automatically-reaches-final-harvest-but-still-feeds
  (let [state (-> (g/initialize (a/new-game ["Solo"] (vec (mapcat identity a/stages)) 0))
                  (update-in [:players 0] a/gain {:wood 10 :clay 10 :reed 10 :stone 10 :food 20})
                  (assoc :round 13) (play house))
        feeding (g/drain (g/begin-round state))
        bred (g/command feeding 0 {:type :feed})
        done (g/command bred 0 {:type :breed :offspring {}})]
    (is (= :feeding (:phase feeding)))
    (is (zero? (get-in feeding [:players 0 :remaining])))
    (is (= -6 (delta feeding bred :food)))
    (is (= :finished (:phase done)))
    (is (zero? (get-in done [:players 0 :begging])))))

(deftest guest-places-a-real-fifth-person-without-increasing-family
  (let [state (-> (fresh) (assoc-in [:players 0 :family] 4)
                  (assoc-in [:players 0 :guest-schedule 2] 1))
        ready (g/drain (g/begin-round state))
        done (reduce (fn [state space] (g/command (assoc state :turn 0) 0 {:type :action :space space}))
                     ready [:grain :wood :clay :reed :fishing])]
    (is (= 5 (get-in ready [:players 0 :remaining])))
    (is (= 0 (get-in done [:players 0 :remaining])))
    (is (= 4 (get-in done [:players 0 :family])))
    (is (= 5 (get-in done [:players 0 :people-placed-by-round 2])))
    (is (= 8 (a/food-needed done (g/p done 0))))))

(deftest layabout-blocks-conversions-but-still-receives-slaughterhouse-income
  (let [slaughter :catalog/original-base-i-97-slaughterhouse
        state (-> (fresh) (own 0 layabout) (own 0 slaughter)
                  (assoc :round 4 :phase :feeding :harvest-skipped #{0} :turn 1 :fed #{0} :bred #{0})
                  (update-in [:players 1 :improvements] conj :hearth-4)
                  (assoc-in [:players 1 :goods :boar] 2))
        cooked (g/command state 1 {:type :convert :goods {:boar 2}})]
    (is (= 1 (delta state cooked :food)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/command state 0 {:type :convert :goods {:grain 1}})))))
