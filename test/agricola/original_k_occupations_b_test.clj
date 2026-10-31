(ns agricola.original-k-occupations-b-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn] [clojure.java.io :as io]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot] [agricola.expansion-cards-test :refer [fresh own hand delta]]))
(defn cid [n] (first (filter #(and (= "K" (:deck (c/card %))) (= (str n) (:number (c/card %)))) (keys c/cards))))
(defn reload [s] (edn/read-string (pr-str s)))
(defn reply [s choice] (g/command s (g/acting-player s) (merge {:type :resolve :decision (:decision-id s)} choice)))
(defn op [s] (get-in s [:effect-queue 0 :effect :op]))
(defn fire [s event pid & [context]] (g/drain (g/queue-front s [(g/event event pid context)])))
(defn goods [s pid amounts] (assoc-in s [:players pid :goods] (merge (zipmap a/resources (repeat 0)) amounts)))
(defn play [s n] (g/drain (g/play-card (hand s (cid n)) 0 (cid n) 0 {})))
(defn rich [] (goods (fresh) 0 {:wood 30 :clay 30 :stone 30 :reed 30 :food 20}))
(defn boundary [cells] (set (keep #(when (= 1 (val %)) (key %)) (frequencies (mapcat a/edges cells)))))

(deftest second-half-has-all-twenty-four-native-definitions
  (let [defs (edn/read-string (slurp (io/resource "agricola/original-k-occupations-b.edn")))]
    (is (= 24 (count defs)))
    (doseq [id (keys defs)] (is (= :occupation (:kind (c/card id))) (str id)))))

(deftest clay-worker-uses-source-even-when-pig-catcher-leaves-all-wood
  (let [s (-> (fresh) (own 0 (cid 290)) (own 0 :catalog/original-base-i-253-pig-catcher) (assoc-in [:piles :wood :wood] 2))
        next (g/command s 0 {:type :action :space :wood :pig-catcher? true})]
    (is (= 0 (delta s next :wood))) (is (= 1 (delta s next :clay)))
    (is (= 1 (delta s next :boar)))
    (is (= 2 (get-in next [:piles :wood :wood])))))

(deftest lover-pays-four-food-and-grows-without-room
  (let [s (goods (fresh) 0 {:food 4}) next (play s 291)]
    (is (= 3 (get-in next [:players 0 :family])))
    (is (= 1 (get-in next [:players 0 :newborn])))
    (is (= 0 (a/amount (g/p next 0) :food))))
  (is (thrown? Exception (play (goods (fresh) 0 {:food 3}) 291)))
  (is (thrown? Exception (play (assoc-in (rich) [:players 0 :family] 5) 291))))

(deftest seed-seller-and-magician-respect-specific-action-and-last-person
  (let [s (play (fresh) 296) next (g/command s 0 {:type :action :space :grain})]
    (is (= 1 (a/amount (g/p s 0) :grain))) (is (= 2 (delta s next :grain))))
  (let [s (own (fresh) 0 (cid 311)) first (g/command s 0 {:type :action :space :traveling})]
    (is (= 0 (delta s first :grain))))
  (let [s (-> (fresh) (own 0 (cid 311)) (assoc-in [:players 0 :remaining] 1))
        next (g/command s 0 {:type :action :space :traveling})]
    (is (= 1 (delta s next :grain))) (is (= 2 (delta s next :food)))))

(deftest scheduled-cards-deliver-only-future-rounds-and-use-food-arrivals
  (let [s (play (assoc (fresh) :round 5) 293)]
    (is (= #{9 12} (set (keys (get-in s [:players 0 :schedule])))))
    (is (= {:op :plow :cost {:food 1} :optional? true} (get-in s [:players 0 :schedule 9 0 :effect]))))
  (let [s (play (assoc (fresh) :round 7) 305)]
    (is (= #{10 14} (set (keys (get-in s [:players 0 :schedule]))))))
  (let [s (play (assoc (rich) :round 6) 305) next (g/drain (g/begin-round s))]
    (is (= :gain (op next)))
    (is (= 1 (delta next (reply (reload next) {}) :sheep)))))

(deftest shepherd-boy-schedules-once-after-stone-renovation
  (let [s (play (assoc (fresh) :round 3) 298)]
    (is (empty? (get-in s [:players 0 :schedule])))
    (let [next (fire (assoc-in s [:players 0 :house] :stone) :renovate 0)
          again (fire next :renovate 0)]
      (is (= (set (range 4 15)) (set (keys (get-in next [:players 0 :schedule])))))
      (is (= (:players next) (:players again))))))

(deftest cattle-breeder-needs-room-for-round-twelve-offspring
  (let [s (-> (rich) (own 0 (cid 295)) (assoc :round 12)
              (update-in [:players 0] a/build-fences (vec (boundary [0 1])))
              (assoc-in [:players 0 :goods :cattle] 2))
        next (fire s :round-end 0)]
    (is (= 3 (a/amount (g/p next 0) :cattle))))
  (let [s (-> (rich) (own 0 (cid 295)) (assoc :round 12)
              (assoc-in [:players 0 :goods :cattle] 2))]
    (is (= 2 (a/amount (g/p (fire s :round-end 0) 0) :cattle)))))

(deftest sheep-farmer-exchanges-before-housing-but-not-during-breeding
  (let [s (-> (fresh) (own 0 (cid 297)) (assoc-in [:players 0 :goods :sheep] 3))
        command {:type :ability :card (cid 297) :option 0 :times 1}
        next (g/command s 0 command)]
    (is (= 0 (a/amount (g/p next 0) :sheep)))
    (is (= 1 (a/amount (g/p next 0) :boar))) (is (= 1 (a/amount (g/p next 0) :cattle)))
    (is (thrown? Exception (g/command (assoc s :phase :breeding) 0 command)))))

(deftest stone-breaker-renovates-only-clay-and-pays-normal-cost
  (let [s (-> (rich) (own 0 (cid 303)) (assoc-in [:players 0 :house] :clay))
        next (g/command s 0 {:type :ability :card (cid 303)})]
    (is (= :stone (get-in next [:players 0 :house])))
    (is (= -2 (delta s next :stone))) (is (= -1 (delta s next :reed))))
  (is (thrown? Exception (g/command (own (rich) 0 (cid 303)) 0 {:type :ability :card (cid 303)}))))

(deftest veterinarian-random-bag-is-persisted-and-removes-only-kept-animal
  (let [s (play (fresh) 304) result (fire s :round-start 0)
        bag (get-in result [:players 0 :card-state (cid 304) :bag])
        draw (get-in result [:players 0 :card-state (cid 304) :last-draw])]
    (is (= result (fire (reload s) :round-start 0)))
    (is (= 2 (count draw)))
    (is (= (if (apply = draw) 8 9) (count bag)))
    (is (= (if (apply = draw) 1 0) (reduce + (map #(delta s result %) a/animals))))))

(deftest tamer-smallholder-goat-and-forest-preserve-legal-housing
  (let [s (-> (rich) (own 0 (cid 306)) (goods 0 {:sheep 1 :boar 1}))]
    (is (= 2 (count (a/animal-housing (g/p s 0)))))
    (is (nil? (a/animal-housing (g/p (own s 0 (cid 120)) 0)))))
  (let [p (g/p (-> (rich) (own 0 (cid 286))
                   (own 0 :catalog/original-base-e-59-drinking-trough)
                   (own 0 :catalog/original-base-e-29-shepherd-s-pipe)
                   (update-in [:players 0] a/build-fences (vec (a/edges 0)))) 0)]
    (is (= 7 (:capacity (first (filter #(= :pasture (:kind %)) (a/accommodations p)))))))
  (let [s (-> (fresh) (own 0 (cid 145)) (goods 0 {:boar 30}))]
    (is (= 30 (reduce + (map :count (a/animal-housing (g/p s 0))))))))

(deftest animal-breeder-only-new-unused-land-and-once-per-turn
  (let [s (-> (rich) (own 0 (cid 307)) (update :active conj :fences))
        pending (g/command s 0 {:type :action :space :fences :fences (vec (a/edges 0))})
        next (reply pending {:animal :boar})]
    (is (= :animal-breeder (op pending)))
    (is (= 2 (delta pending next :boar))) (is (= -2 (delta pending next :food)))
    (is (some #(= :boar (:animal %)) (bot/decision-candidates pending 0))))
  (let [s (-> (rich) (own 0 (cid 307)) (update-in [:players 0] a/build-stables [0] 0)
              (update-in [:players 0] a/build-fences (vec (a/edges 0))))]
    (is (not (g/animal-breeder-available? s 0 {:old-pastures []})))
    (is (not (g/animal-breeder-available? s 0 {:old-pastures [] :fence-overseer? true})))))

(deftest foreman-food-is-saved-displayed-as-bonus-and-collected-on-fixed-space
  (let [s (g/drain (g/begin-round (own (fresh) 0 (cid 308))))
        placed (reply (reload s) {:space :grain})
        taken (g/command placed 0 {:type :action :space :grain})]
    (is (= 1 (get-in placed [:space-bonuses :grain :food])))
    (is (= 1 (delta placed taken :food)))
    (is (= 1 (delta placed taken :grain)))
    (is (nil? (get-in taken [:space-bonuses :grain])))))

(deftest trainer-cannot-spend-bonus-food-or-recycle-cooking-proceeds
  (let [s (-> (rich) (own 0 (cid 342)) (assoc-in [:piles :traveling :food] 5))
        pending (g/command s 0 {:type :action :space :traveling})]
    (is (= :animal-trainer (op pending)))
    (is (thrown? Exception (reply pending {:animals {:cattle 2}})))
    (let [next (reply (reload pending) {:animals {:sheep 1 :cattle 1}})]
      (is (= -5 (delta pending next :food)))
      (is (= 1 (delta pending next :sheep))) (is (= 1 (delta pending next :cattle))))))

(deftest fence-overseer-fences-just-built-stable-once-a-round
  (let [s (-> (rich) (own 0 (cid 312)))
        pending (g/command s 0 {:type :action :space :build :stables [0]})
        next (reply (reload pending) {:cell 0})]
    (is (= :fence-overseer (op pending)))
    (is (= 4 (count (get-in next [:players 0 :fences]))))
    (is (= -1 (delta pending next :food))) (is (= 0 (delta pending next :wood)))
    (is (empty? (g/fence-overseer-cells next 0 {:new-stables [1]})))
    (is (thrown? Exception (reply pending {:cell 1})))))

(deftest resource-seller-orders-source-before-card-receipts-and-excludes-returns
  (let [s (-> (play (fresh) 310) (own 0 (cid 290)))
        next (g/command s 0 {:type :action :space :wood})]
    (is (= 4 (delta s next :wood)))
    (is (= 2 (delta s next :clay)))
    (is (= :reed (first (get-in next [:players 0 :card-state (cid 310) :resource-stack]))))
    (is (empty? (:receipt-action-stack next))))
  (let [s (-> (play (fresh) 310) (own 0 :catalog/original-base-i-253-pig-catcher) (assoc-in [:piles :wood :wood] 2))
        next (g/command s 0 {:type :action :space :wood :pig-catcher? true})]
    (is (= 0 (delta s next :wood)))
    (is (= :wood (first (get-in next [:players 0 :card-state (cid 310) :resource-stack]))))))

(deftest market-woman-distinguishes-minor-occupation-and-action-vegetables
  (let [s (-> (fresh) (own 0 (cid 292)) (update :active conj :vegetable))
        next (g/command s 0 {:type :action :space :vegetable})]
    (is (= 1 (delta s next :vegetable))) (is (= 2 (delta s next :grain))))
  (let [s (own (fresh) 0 (cid 292))
        minor (g/run-effect s (g/task 0 :catalog/original-base-e-39-market-stall {:op :gain :goods {:vegetable 1}} {}) {})
        occupation (g/run-effect s (g/task 0 :catalog/original-base-i-245-market-crier {:op :gain :goods {:vegetable 1}} {}) {})]
    (is (= 2 (delta s minor :grain))) (is (= 0 (delta s occupation :grain)))))

(deftest distiller-slaughterman-and-weaver-timing
  (let [s (-> (fresh) (own 0 (cid 300)) (goods 0 {:vegetable 1}) (assoc :phase :feeding))
        pending (fire s :feeding-start 0) next (reply pending {})]
    (is (= 5 (delta s next :food))) (is (= -1 (delta s next :vegetable))))
  (let [s (-> (fresh) (own 0 (cid 299)))
        next (fire s :convert-food 1 {:cooked-animals 3})]
    (is (= 1 (delta s next :food)))
    (is (= [1 2 3 0] (g/feeding-order s))))
  (let [s (-> (rich) (own 0 (cid 309)) (update-in [:players 0] a/build-fences (vec (a/edges 0))) (assoc-in [:players 0 :goods :sheep] 2))]
    (is (= 1 (delta s (fire s :work-start 0) :food)))
    (is (= 0 (delta s (fire s :round-start 0) :food)))))

(deftest taster-can-pay-its-last-food-and-preparation-foreman-is-once-per-round
  (let [s (-> (fresh) (own 1 :catalog/original-base-i-260-taster)
              (assoc-in [:players 1 :goods :food] 1) g/begin-round g/drain)
        next (reply s {})]
    (is (= 0 (a/amount (g/p next 1) :food)))
    (is (= 1 (:turn next)))
    (is (= :work (:phase next)))))

(deftest sheep-farmer-cannot-exchange-final-harvest-offspring
  (let [s (-> (fresh) (own 0 (cid 297)) (goods 0 {:sheep 3}) (assoc :round 14 :phase :finish-round))]
    (is (thrown? Exception (g/command s 0 {:type :ability :card (cid 297) :option 0 :times 1})))))

(deftest breeder-can-cook-owned-animals-to-fund-pair-before-gaining-new-ones
  (let [s (-> (rich) (own 0 (cid 307)) (goods 0 {:wood 10 :sheep 1})
              (update-in [:players 0 :improvements] conj :hearth-4) (update :active conj :fences))
        pending (g/command s 0 {:type :action :space :fences :fences (vec (a/edges 0))})]
    (is (= :animal-breeder (op pending)))
    (is (thrown? Exception (reply pending {:animal :cattle})))
    (let [funded (g/command pending 0 {:type :convert :goods {:sheep 1}})
          next (reply funded {:animal :boar})]
      (is (= 2 (a/amount (g/p next 0) :boar)))
      (is (= 0 (a/amount (g/p next 0) :food))))))

(deftest resource-seller-does-not-reward-wood-bought-away-and-round-income-is-immediate
  (let [s (-> (play (fresh) 310) (own 1 :catalog/original-base-i-234-wood-buyer)
              (assoc-in [:piles :wood :wood] 1))
        pending (g/command s 0 {:type :action :space :wood})
        next (reply (reload pending) {})]
    (is (= 0 (a/amount (g/p next 0) :wood)))
    (is (= :wood (first (get-in next [:players 0 :card-state (cid 310) :resource-stack])))))
  (let [s (play (fresh) 310)
        next (g/drain (g/run-effect s (g/task 0 :catalog/original-base-k-283-wood-deliveryman {:op :gain :goods {:wood 1}} {}) {}))]
    (is (= 2 (delta s next :wood)))
    (is (= :clay (first (get-in next [:players 0 :card-state (cid 310) :resource-stack]))))))

(deftest seller-card-income-funds-same-action-followups-without-a-resource-source
  (let [s (play (fresh) 310)
        pending (g/begin-action-receipt s s 0 {:space :meeting})
        next (g/drain (g/run-effect pending (g/task 0 :catalog/original-base-e-16-building-material {:op :gain :goods {:wood 1}} {}) {}))]
    (is (= 2 (delta s next :wood)))
    (is (= :clay (first (get-in next [:players 0 :card-state (cid 310) :resource-stack]))))))

(deftest seller-simultaneous-order-is-a-saved-native-choice
  (let [s (play (fresh) 310)
        pending (g/drain (g/run-effect s (g/task 0 :catalog/original-base-k-284-wood-distributor {:op :gain :goods {:wood 1 :clay 1}} {}) {}))
        best (reply (reload pending) {:option 0}) alternative (reply pending {:option 1})]
    (is (= :resource-seller-receipt (op pending)))
    (is (= 2 (count (g/resource-seller-options pending 0 (cid 310) {:wood 1 :clay 1}))))
    (is (= 1 (delta pending best :wood))) (is (= 1 (delta pending best :clay)))
    (is (= 1 (delta pending alternative :wood))) (is (= 0 (delta pending alternative :clay)))
    (is (= :clay (first (get-in alternative [:players 0 :card-state (cid 310) :resource-stack]))))))
