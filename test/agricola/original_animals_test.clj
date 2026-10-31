(ns agricola.original-animals-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [agricola.bot :as bot]))
(def pipe :catalog/original-base-e-29-shepherd-s-pipe)
(def yard :catalog/original-base-e-58-animal-yard)
(def trough :catalog/original-base-e-59-drinking-trough)
(def master :catalog/original-base-e-208-stablemaster)
(def keeper :catalog/original-base-e-216-animal-keeper)
(def shepherd :catalog/original-base-e-203-shepherd)
(def meat :catalog/original-base-e-162-meat-seller)
(def oven :catalog/original-base-e-27-wood-fired-oven)
(defn fresh [] (g/initialize (a/new-game ["A" "B" "C" "D"] (vec (mapcat identity a/stages)) 0)))
(defn player [] (-> (g/p (fresh) 0) (a/gain {:wood 30 :food 20})))
(defn own [p & ids]
  (reduce (fn [p id] (update p (if (= :occupation (:kind (c/card id))) :occupations :minors) conj id)) p ids))
(defn animal-goods [p goods] (assoc p :goods (merge (:goods p) (zipmap a/animals (repeat 0)) goods)))
(defn slots [p kind] (filter #(= kind (:kind %)) (a/accommodations p)))
(defn housed [p] (reduce #(merge-with + %1 (a/housing-animals %2)) {} (a/animal-housing p)))
(defn breeding [p] (-> (fresh) (assoc :phase :breeding :turn 0 :bred #{}) (assoc-in [:players 0] p)))

(deftest animal-card-prerequisites-costs-and-scoring
  (let [base (assoc-in (fresh) [:players 0 :hand :minor] [pipe yard])]
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card base 0 pipe 0 {})))
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card base 0 yard 0 {})))
    (let [ready (-> base (update-in [:players 0] own master) (update-in [:players 0] a/gain {:wood 2 :sheep 1}))
          played (g/drain (g/play-card ready 0 yard 0 {}))
          both (g/drain (g/play-card played 0 pipe 0 {}))]
      (is (= 0 (a/amount (g/p played 0) :wood)))
      (is (= 1 (a/amount (g/p played 0) :sheep)))
      (is (= 1 (- (:total (g/score played 0)) (:total (g/score ready 0)))))
      (is (= 0 (count (a/pastures (g/p both 0)))))
      (is (= 4 (:capacity (first (slots (g/p both 0) :card)))))
      (is (= 1 (a/amount (g/p both 0) :sheep))))))

(deftest animal-yard-holds-mixed-animals-without-becoming-a-pasture
  (let [p (own (player) yard)
        mixed (animal-goods p {:sheep 1 :boar 1 :cattle 1})
        arrangement (a/animal-housing mixed)]
    (is (some? arrangement))
    (is (= {:sheep 1 :boar 1 :cattle 1} (housed mixed)))
    (is (some #(and (= :card (:kind %)) (= yard (:card %)) (= 2 (count (a/housing-animals %)))) arrangement))
    (is (empty? (a/pastures mixed)))
    (is (nil? (a/animal-housing (animal-goods p {:sheep 2 :boar 2}))))))

(deftest animal-yard-pipe-and-trough-preserve-a-normal-slot-for-sheep
  ;; The printed ruling allows3 sheep+1 other with Pipe, not2+2. An additional
  ;; cattle occupies the house pet slot so the whole-farm check cannot hide this.
  (doseq [[goods fits?] [[{:sheep 3 :boar 1 :cattle 1} true]
                        [{:sheep 4 :boar 1} true]
                        [{:sheep 2 :boar 2 :cattle 1} false]
                        [{:boar 3 :cattle 1} false]]]
    (let [p (animal-goods (own (player) yard pipe) goods)]
      (is (= fits? (some? (a/animal-housing p))) (str goods))
      (when fits? (is (= goods (housed (edn/read-string (pr-str p))))))))
  (let [p (own (player) yard trough pipe)]
    (is (= 6 (:capacity (first (slots p :card)))))
    (is (some? (a/animal-housing (animal-goods p {:sheep 3 :boar 3 :cattle 1}))))
    (is (nil? (a/animal-housing (animal-goods p {:sheep 2 :boar 4 :cattle 1}))))
    (is (nil? (a/animal-housing (animal-goods p {:sheep 6 :boar 2}))))
    (is (= 4 (:capacity (first (slots (own (player) yard trough) :card)))))))

(deftest animal-keeper-mixes-farm-pastures-but-not-ordinary-stables
  (let [p (a/build-fences (player) (a/edges 0))
        goods {:sheep 1 :boar 1 :cattle 1}]
    (is (nil? (a/animal-housing (animal-goods p goods))))
    (is (= goods (housed (animal-goods (own p keeper) goods))))
    (is (nil? (a/animal-housing (animal-goods (own p keeper) {:sheep 2 :boar 2}))))
    (let [stable (-> (player) (own keeper master) (a/build-stables [0] 0))]
      (is (nil? (a/animal-housing (animal-goods stable goods)))))
    (let [mixed (own p keeper pipe trough)
          goods {:sheep 3 :boar 3 :cattle 1}]
      (is (= goods (housed (animal-goods mixed goods))))
      (is (nil? (a/animal-housing (animal-goods mixed {:sheep 2 :boar 4 :cattle 1})))))))

(deftest trough-and-pipe-are-added-after-fenced-stable-multipliers
  (let [p (-> (player) (a/build-fences (a/edges 0)) (a/build-stables [0] 0) (own trough pipe))]
    (is (= 8 (:capacity (first (slots p :pasture)))))
    (is (some? (a/animal-housing (animal-goods p {:sheep 8 :boar 1}))))
    (is (nil? (a/animal-housing (animal-goods p {:sheep 9 :boar 1}))))
    (is (some? (a/animal-housing (animal-goods p {:boar 6 :cattle 1}))))
    (is (nil? (a/animal-housing (animal-goods p {:boar 7 :cattle 1}))))))

(deftest stablemaster-upgrades-exactly-one-unfenced-stable
  (let [p (-> (player) (a/build-stables [0 1] 0) (own master))]
    (is (= [1 3] (sort (map :capacity (slots p :stable)))))
    (is (some? (a/animal-housing (animal-goods p {:boar 3 :sheep 1 :cattle 1}))))
    (is (nil? (a/animal-housing (animal-goods p {:boar 3 :sheep 3}))))
    (let [pipe-p (own p pipe trough)]
      (is (= [2 3] (sort (map :capacity (slots pipe-p :stable)))))
      (is (some? (a/animal-housing (animal-goods pipe-p {:boar 3 :sheep 2 :cattle 1}))))
      (is (nil? (a/animal-housing (animal-goods pipe-p {:boar 3 :sheep 3 :cattle 1}))))
      (is (nil? (a/animal-housing (animal-goods pipe-p {:boar 5 :cattle 1}))))
      (let [fenced (a/build-fences pipe-p (a/edges 0))]
        (is (= [3] (map :capacity (slots fenced :stable))))
        (is (= [1] (:cells (first (slots fenced :stable)))))
        (is (= 8 (:capacity (first (slots fenced :pasture)))))))))

(deftest mixed-housing-preserves-automatic-pasture-bonus-optimization
  (let [p (-> (player) (a/build-fences (distinct (concat (a/edges 0) (a/edges 1))))
              (own keeper pipe trough (c/id :b098))
              (animal-goods {:sheep 2 :boar 1}))
        state (assoc-in (fresh) [:players 0] p)]
    (is (= 2 (g/card-bonus state 0 (c/id :b098))))
    (is (= {:sheep 2 :boar 1} (housed p)))
    (is (every? #(or (:animal %) (seq (:animals %))) (a/animal-housing p))))
  (let [p (-> (player) (a/build-fences (a/edges 0)) (own keeper (c/id :a134))
              (animal-goods {:sheep 1 :boar 1 :cattle 1}))
        state (assoc-in (fresh) [:players 0] p)]
    (is (= 1 (g/card-bonus state 0 (c/id :a134))))
    (is (= 1 (count (filter #(= :pasture (:kind %)) (a/animal-housing p)))))))

(deftest shepherd-breeds-up-to-two-only-from-four-parent-sheep
  (let [p (-> (player) (own shepherd) (a/build-fences (a/edges 0)) (a/build-stables [0] 0))]
    (is (= {:sheep 1} (a/breeding-options (animal-goods p {:sheep 3}))))
    (is (= {:sheep 2} (a/breeding-options (animal-goods p {:sheep 4}))))
    ;; Four spaces in the pasture plus one house pet: exactly one lamb fits.
    (let [limited (breeding (animal-goods p {:sheep 4}))]
      (is (= {:sheep 1} (a/default-breeding (g/p limited 0))))
      (is (= 5 (a/amount (g/p (g/command limited 0 {:type :breed :offspring {:sheep 1}}) 0) :sheep)))
      (is (thrown? clojure.lang.ExceptionInfo (g/command limited 0 {:type :breed :offspring {:sheep 2}})))
      (is (thrown? clojure.lang.ExceptionInfo (g/command limited 0 {:type :breed :offspring {}}))))
    (let [roomy (breeding (animal-goods (own p pipe) {:sheep 4}))
          command {:type :breed :offspring {:sheep 2}}
          saved (edn/read-string (pr-str roomy))]
      (is (= {:sheep 2} (a/default-breeding (g/p roomy 0))))
      (is (some #{command} (bot/candidates saved 0)))
      (is (some? (bot/preview saved 0 command)))
      (is (= 6 (a/amount (g/p (g/command saved 0 command) 0) :sheep)))
      (is (thrown? clojure.lang.ExceptionInfo (g/command saved 0 {:type :breed :offspring {:sheep 1}}))))))

(deftest meat-seller-requires-currently-owned-oven-and-never-cooks-during-breeding
  (let [p (-> (player) (own meat) (animal-goods {:sheep 2 :boar 1 :cattle 1}))]
    (is (not (contains? (a/cook-rates p) :sheep)))
    (is (thrown? clojure.lang.ExceptionInfo (a/convert-food p {:sheep 1})))
    (doseq [oven-player [(update p :improvements conj :clay-oven) (own p oven)]]
      (is (= {:sheep 2 :boar 3 :cattle 4} (select-keys (a/cook-rates oven-player) a/animals)))
      (is (= 11 (- (a/amount (a/convert-food oven-player {:sheep 2 :boar 1 :cattle 1}) :food)
                    (a/amount oven-player :food)))))
    (let [lost (-> p (own oven) (update :minors #(vec (remove #{oven} %))))]
      (is (not (contains? (a/cook-rates lost) :sheep))))
    ;; A Fireplace bakes bread, but does not satisfy Meat Seller's oven condition.
    (is (= 2 (:boar (a/cook-rates (update p :improvements conj :fireplace-2)))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/command (breeding (own p oven)) 0 {:type :convert :goods {:sheep 1}})))))

(deftest bots-resolve-overflow-with-mixed-card-housing-after-reload
  (let [p (animal-goods (own (player) yard pipe) {:sheep 2 :boar 2 :cattle 1})
        pending (-> (fresh) (assoc-in [:players 0] p) g/with-housing g/drain)
        saved (edn/read-string (pr-str pending))
        outcomes (keep #(bot/preview saved 0 %) (bot/decision-candidates saved 0))]
    (is (= :housing (get-in saved [:effect-queue 0 :effect :op])))
    (is (seq outcomes))
    (is (some #(= 4 (reduce + 0 (vals (select-keys (:goods (g/p % 0)) a/animals)))) outcomes))
    (is (every? #(some? (a/animal-housing (g/p % 0))) outcomes))))
