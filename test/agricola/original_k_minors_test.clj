(ns agricola.original-k-minors-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.card-expressions :as expr] [agricola.bot :as bot]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(defn cid [n] (:id (first (filter #(and (= "Original" (:edition %)) (= "K" (:deck %)) (= (str n) (:number %))) (vals c/metadata)))))
(defn rich [s] (update-in s [:players 0] a/gain {:wood 30 :clay 30 :reed 30 :stone 30 :food 30 :grain 10 :vegetable 10}))
(defn ready [s] (assoc-in s [:players 0 :occupations] [:catalog/original-base-e-176-woodcutter :catalog/original-base-e-205-reed-collector :catalog/original-base-e-187-clay-deliveryman :catalog/original-base-e-201-cattle-whisperer]))
(defn play [s n] (g/drain (g/play-card (hand s (cid n)) 0 (cid n) 0 {})))
(defn trigger [s event context] (g/drain (g/queue-front s [(g/event event 0 context)])))
(defn op [s] (get-in s [:effect-queue 0 :effect :op]))
(defn reload [s] (edn/read-string (pr-str s)))
(defn crop [s cell kind n] (assoc-in s [:players 0 :farm cell] {:type :field :crop kind :count n}))
(defn positive [m] (into {} (filter (comp pos? val) m)))

(deftest all-forty-one-minors-have-native-definitions-and-exact-prerequisites
  (let [defs (edn/read-string (slurp "resources/agricola/original-k-minors.edn"))]
    (is (= 41 (count defs)))
    (doseq [id (keys defs)]
      (is (c/card id) (str id))
      (is (or (:requires (c/card id)) (some? (expr/requirement (:prerequisites (c/card id))))) (str id)))
    (is (= {:wood 2} (:printed-cost (c/card (cid 117)))))
    (is (not (g/prerequisites? (fresh) 0 (c/card (cid 136)))))
    (is (g/prerequisites? (assoc-in (fresh) [:players 0 :occupations] [(cid 266) (cid 267)]) 0 (c/card (cid 136))))))

(deftest passing-improvements-pay-print-and-leave-the-owners-table
  (doseq [[n goods cost] [[107 {:wood 3} {:stone 1}] [129 {:grain 1} {}] [141 {:boar 1} {:food 1}]]]
    (let [before (-> (fresh) rich ready) after (play before n)]
      (doseq [[r amount] goods] (is (= amount (delta before after r))))
      (doseq [[r amount] cost] (is (= (- amount) (delta before after r))))
      ;; Boar housing is resolved before passing the physical card.
      (let [after (if (= :housing (op after)) (resolve! after {:keep {:sheep 0 :boar 1 :cattle 0}}) after)]
        (is (not (some #{(cid n)} (:minors (g/p after 0)))))
        (is (some #{(cid n)} (get-in after [:players 1 :hand :minor])))))))

(deftest alternative-costs-remain-whole-payments
  (doseq [[n choices] [[116 [{:wood 3} {:clay 3}]] [143 [{:wood 2} {:clay 2}]]]]
    (doseq [[index price] (map-indexed vector choices)]
      (is (= price (positive (g/card-cost (fresh) 0 (c/card (cid n)) 0 {:payment-option index}))))))
  (let [s (-> (fresh) (hand (cid 143)) (assoc-in [:players 0 :goods :wood] 1) (assoc-in [:players 0 :goods :clay] 1))]
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card s 0 (cid 143) 0 {})))))

(deftest bakery-and-hearth-upgrades-return-the-correct-physical-card
  (let [s (-> (fresh) rich (assoc-in [:players 0 :improvements] #{:clay-oven}))
        done (play s 106)]
    (is (= -3 (delta s done :stone)))
    (is (contains? (:available-improvements done) :clay-oven))
    (is (empty? (a/returnable-ovens (g/p done 0))))
    (is (= :bake (op done)))
    (is (= 10 (delta done (resolve! (reload done) {:grain 2}) :food))))
  (let [fireplace :catalog/original-base-e-20-simple-fireplace
        s (-> (fresh) (own 0 fireplace)) done (play s 128)]
    (is (not (some #{fireplace} (:minors (g/p done 0)))))
    (is (not (contains? (:available-improvements done) fireplace)))
    (is (= {:grain 1 :vegetable 3 :sheep 2 :boar 3 :cattle 4} (a/cook-rates (g/p done 0))))
    (is (= [(cid 128)] (vec (a/returnable-hearths (g/p done 0)))))
    (is (= 3 (- (a/amount (a/bake (a/gain (g/p done 0) {:grain 1}) 1) :food) (a/amount (g/p done 0) :food))))))

(deftest beehive-and-fixed-round-deliveries-exclude-current-and-past-rounds
  (doseq [[n rounds resource quantity] [[108 [6 8 10 12 14] :food 2] [116 [8 10 12] :grain 1] [142 [6 8 10 12 14] :stone 1]]]
    (let [s (-> (fresh) rich ready (own 0 (cid 120)) (own 0 (cid 135)) (assoc :round 4)) done (play s n)]
      (is (= rounds (sort (keys (:schedule (g/p done 0))))))
      (doseq [r rounds] (is (= quantity (get-in done [:players 0 :schedule r 0 :goods resource])))))))

(deftest ponds-and-garden-deliver-only-remaining-rounds
  (doseq [[n offsets] [[114 [1 2 3]] [130 [1 2 3 4 5]] [140 [1 2 3 4 5]]]]
    (let [s (-> (fresh) rich ready (crop 0 :vegetable 1) (assoc :round 12)) done (play s n)]
      (is (= [13 14] (sort (keys (:schedule (g/p done 0)))))))))

(deftest greenhouse-requires-payment-at-arrival-and-can-use-well-income
  (let [s (-> (fresh) rich ready (assoc :round 1)) planted (play s 117)
        before (-> planted (assoc :round 4) (assoc-in [:players 0 :goods :food] 0)
                   (assoc-in [:players 0 :well-rounds] #{5}))
        arrived (g/drain (g/begin-round before))]
    (is (= [5 8] (sort (keys (:schedule (g/p planted 0))))))
    (is (= :exchange (op arrived)))
    (is (= 1 (a/amount (g/p arrived 0) :food)))
    (let [done (resolve! (reload arrived) {})]
      (is (= 0 (a/amount (g/p done 0) :food)))
      (is (= 11 (a/amount (g/p done 0) :vegetable))))
    (is (= 10 (a/amount (g/p (resolve! arrived {:skip? true}) 0) :vegetable)))))

(deftest acreage-turnip-and-liquid-manure-share-real-sowing
  (let [s (-> (fresh) rich ready (play 105) (assoc-in [:players 0 :goods :sheep] 4) (play 118))
        fields (keys (:card-fields (g/p s 0)))
        planted (a/sow (g/p s 0) (mapv #(hash-map :cell % :crop :grain) fields))]
    (is (= 2 (count fields)))
    (is (= 2 (expr/evaluate s 0 {} [:fields])))
    (is (every? #(= 4 (:count %)) (vals (:card-fields planted))))
    (is (= -1 (get-in (a/score planted) [:breakdown :fields]))))
  (let [s (-> (fresh) rich ready (crop 0 :grain 0) (play 137))]
    (is (= :sow-offer (op s)))
    (let [done (resolve! (resolve! s {}) {:plantings [{:cell 0 :crop :grain} {:cell (cid 137) :crop :vegetable}]})]
      (is (= 3 (get-in done [:players 0 :farm 0 :count])))
      (is (= 2 (get-in done [:players 0 :card-fields (cid 137) :count]))))))

(deftest bookshelf-pays-before-occupation-and-paddle-bakes-after
  (let [s (-> (fresh) (own 0 (cid 112)) (own 0 (cid 111))
              (assoc-in [:players 0 :goods :food] 0) (assoc-in [:players 0 :goods :grain] 1)
              (assoc-in [:players 0 :improvements] #{:clay-oven}) (hand :catalog/original-base-e-176-woodcutter))
        paid (g/drain (g/play-card s 0 :catalog/original-base-e-176-woodcutter 3 {}))]
    (is (= 0 (a/amount (g/p paid 0) :food)))
    (is (= :bake (op paid)))
    (is (= 5 (a/amount (g/p (resolve! paid {:grain 1}) 0) :food))))
  (let [s (-> (fresh) (own 0 (cid 111)) (assoc-in [:players 0 :goods :food] 0) (hand :catalog/original-base-e-176-woodcutter))]
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card s 0 :catalog/original-base-e-176-woodcutter 1 {})))))

(deftest cooking-pelts-and-spit-roast-distinguish-harvest-phase
  (let [s (-> (fresh) (own 0 (cid 109)) (own 0 (cid 339))
              (assoc-in [:players 0 :improvements] #{:hearth-4}) (assoc-in [:players 0 :goods :sheep] 2)
              (assoc-in [:players 0 :goods :food] 0))
        cooked (g/command s 0 {:type :convert :goods {:sheep 2}})]
    (is (= 4 (a/amount (g/p cooked 0) :food)))
    (is (= :pelts (op cooked)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! cooked {:amount 3})))
    (let [done (resolve! (reload cooked) {:amount 2})]
      (is (= 2 (a/amount (g/p done 0) :food)))
      (is (= 2 (g/card-bonus done 0 (cid 339))))))
  (let [s (-> (fresh) (own 0 (cid 109)) (assoc :phase :feeding :round 4 :fed #{} :bred #{})
              (assoc-in [:players 0 :improvements] #{:hearth-4}) (assoc-in [:players 0 :goods :sheep] 2)
              (assoc-in [:players 0 :goods :food] 0))
        one (g/command s 0 {:type :convert :goods {:sheep 1}})
        two (g/command one 0 {:type :feed :convert {:sheep 1}})]
    (is (= 3 (a/amount (g/p one 0) :food)))
    (is (= 1 (a/amount (g/p two 0) :food)))
    (is (= 0 (:begging (g/p two 0))))))

(deftest sawmill-returns-joinery-and-suppresses-only-its-bonus
  (let [s (-> (fresh) (assoc-in [:players 0 :improvements] #{:joinery})) done (play s 122)]
    (is (contains? (:available-improvements done) :joinery))
    (is (not (contains? (:improvements (g/p done 0)) :joinery)))
    (let [s (-> done (update-in [:players 0 :improvements] conj :joinery)
                (assoc-in [:players 0 :goods :wood] 1)
                (assoc-in [:players 0 :card-fields :copse] {:type :wood-stack :crop :wood :count 4}))]
      (is (= 3 (g/card-bonus s 0 (cid 122))))
      (is (= 0 (get-in (a/score (g/p s 0)) [:breakdown :bonus])))
      (is (= 5 (get-in (g/score s 0) [:breakdown :improvements]))))))

(deftest clay-extension-is-card-cost-not-room-cost
  (let [s (-> (fresh) rich (assoc-in [:players 0 :house] :clay)) offered (play s 132)
        done (resolve! offered {:rooms [0]})]
    (is (= -4 (delta s done :clay)))
    (is (= -1 (delta s done :reed)))
    (is (= 3 (count (a/tiles (g/p done 0) :room))))
    (is (some #{(cid 132)} (get-in done [:players 1 :hand :minor]))))
  (is (thrown? clojure.lang.ExceptionInfo (play (-> (fresh) rich) 132))))

(deftest original-roof-supports-mixed-and-per-room-payment
  (let [p (g/p (-> (fresh) rich (own 0 (cid 136)) (own 0 :catalog/original-base-e-36-clay-roof)) 0)]
    (is (= {:wood 6 :clay 1} (positive (a/room-cost p {:wood-reed 1 :clay-reed 1}))))
    (is (= {:wood 7} (positive (a/room-cost p {:wood-reed 2}))))
    (is (thrown? clojure.lang.ExceptionInfo (a/room-cost p {:wood-reed 2 :clay-reed 1})))
    (let [built (a/build-rooms p [0 1] {:room-options {0 {:wood-reed 1} 1 {:wood-reed 2}}})]
      (is (= 17 (a/amount built :wood)))
      (is (= 29 (a/amount built :reed))))))

(deftest frame-builder-and-collector-use-original-exchange-ratios
  (let [p (-> (g/p (-> (fresh) rich (own 0 (cid 272)) (own 0 (cid 294))) 0) (assoc :house :clay))]
    (is (= {:clay 3 :wood 2} (positive (a/room-cost p {:frame? true :brushwood? true}))))
    (let [after (a/renovate p {:frame? true :brushwood? true})]
      (is (= 29 (a/amount after :stone)))
      (is (= 28 (a/amount after :wood)))
      (is (= 30 (a/amount after :reed))))))

(deftest sawhorse-consumes-next-stable-and-fence-ordinals
  (let [p (g/p (-> (fresh) rich (own 0 (cid 121))) 0)
        one (a/build-stables p [0] 0) two (a/build-stables one [1] 2)]
    (is (= 28 (a/amount two :wood)))
    (is (get-in one [:card-state (cid 121) :stable-used?]))
    (let [fenced (a/build-fences p (a/edges 0))]
      (is (= 27 (a/amount fenced :wood)))
      (let [next (a/build-fences fenced (remove (:fences fenced) (a/edges 1)))]
        (is (= 25 (a/amount next :wood)))))))

(deftest field-tools-allow-the-printed-number-and-exclude-cultivation
  (doseq [[n uses] [[115 2] [119 1]]]
    (let [s (play (-> (fresh) rich ready) n)]
      (is (= uses (get-in s [:players 0 :card-state (cid n) :uses])))
      (is (some #{(cid n)} (:plows (g/plow-options s 0 :plow))))
      (is (not (some #{(cid n)} (:plows (g/plow-options s 0 :cultivation)))))))
  (let [s (-> (fresh) rich (assoc-in [:players 0 :goods :cattle] 2) (assoc :round 11)) offered (play s 134)]
    (is (= 3 (get-in offered [:effect-queue 0 :effect :max-fields])))
    (let [done (resolve! offered {:cells [0 1 2]})]
      (is (= 3 (count (a/tiles (g/p done 0) :field))))))
  (let [s (-> (fresh) rich (assoc-in [:players 0 :goods :cattle] 1)
              (own 1 (cid 115)) (own 2 :catalog/original-base-i-68-harrow)) offered (play s 124)]
    (is (= 2 (get-in offered [:effect-queue 0 :effect :max-fields])))))

(deftest clapper-adds-to-every-planted-grain-and-wood-stack
  (let [s (-> (fresh) rich (crop 0 :grain 2) (crop 1 :vegetable 2)
              (assoc-in [:players 0 :card-fields :wood] {:type :wood-stack :crop :wood :count 3})
              (assoc-in [:players 0 :worker-spaces] [:growth]) (play 127))]
    (is (= 3 (get-in s [:players 0 :farm 0 :count])))
    (is (= 2 (get-in s [:players 0 :farm 1 :count])))
    (is (= 4 (get-in s [:players 0 :card-fields :wood :count])))
    (let [again (trigger s :use-space {:space :urgent-growth})]
      (is (= 4 (get-in again [:players 0 :farm 0 :count]))))))

(deftest landing-net-considers-building-resources-from-card-bonuses
  (let [s (own (fresh) 0 (cid 126)) two (g/command s 0 {:type :action :space :reed})]
    (is (= 2 (delta s two :food))))
  (let [s (-> (fresh) (own 0 (cid 126)) (own 0 :catalog/original-base-e-205-reed-collector))
        net (trigger s :after-action {:source-goods {:reed 1} :before-received {}})]
    (is (= 2 (delta s net :food))))
  (let [s (-> (fresh) (own 0 (cid 126)) (update-in [:players 0] a/gain {:stone 1}))
        net (trigger s :after-action {:source-goods {:reed 1} :before-received {}})]
    (is (= 1 (delta s net :food)))))

(deftest bonus-cards-do-not-rewrite-base-scoring
  (let [s (-> (fresh) (own 0 (cid 135)))]
    (is (= 2 (g/card-bonus s 0 (cid 135))))
    (is (= -1 (get-in (g/score s 0) [:breakdown :cattle]))))
  (let [s (-> (fresh) (own 0 (cid 144)) (own 0 :catalog/original-base-e-21-half-timbered-house)
              (assoc-in [:players 0 :house] :stone))]
    (is (= 4 (g/card-bonus s 0 (cid 144))))
    (is (= 0 (g/card-bonus s 0 :catalog/original-base-e-21-half-timbered-house))))
  (doseq [[n resource amount points] [[133 :cattle 5 2] [146 :sheep 7 2]]]
    (let [s (-> (fresh) (own 0 (cid n)) (assoc-in [:players 0 :goods resource] amount))
          harvest (trigger s :before-field-phase {})]
      (is (= 3 (delta s harvest :food)))
      (is (= points (g/card-bonus s 0 (cid n)))))))

(deftest feeding-brewing-field-baking-and-labor-are-separate-events
  (let [s (-> (fresh) (own 0 (cid 110)) (assoc-in [:players 0 :goods :grain] 9))
        offered (trigger s :feeding-start {}) done (resolve! offered {})]
    (is (= 3 (delta s done :food)))
    (is (= -1 (delta s done :grain)))
    (is (= 1 (g/card-bonus s 0 (cid 110))))
    (is (= 0 (g/card-bonus done 0 (cid 110)))))
  (let [s (-> (fresh) (own 0 (cid 113)) (assoc-in [:players 0 :goods :grain] 1)
              (assoc-in [:players 0 :improvements] #{:clay-oven}))
        offered (g/command s 0 {:type :action :space :plow :cell 0})]
    (is (= :bake (op offered)))
    (is (= 5 (delta offered (resolve! offered {:grain 1}) :food))))
  (let [s (own (fresh) 0 (cid 131)) done (g/command s 0 {:type :action :space :labor})]
    (is (= 3 (delta s done :clay))))
  (doseq [[rooms points] [[4 0] [5 2] [6 4] [7 4]]]
    (let [s (assoc-in (own (fresh) 0 (cid 123)) [:players 0 :farm]
                      (vec (concat (repeat rooms {:type :room}) (repeat (- 15 rooms) {:type :empty}))))]
      (is (= points (g/card-bonus s 0 (cid 123)))))))

(deftest house-goat-and-forest-pasture-use-real-housing
  (let [s (own (fresh) 0 (cid 120)) p (g/p s 0)]
    (is (nil? (a/animal-housing (a/gain p {:sheep 1}))))
    (is (= 1 (delta s (trigger s :feeding-start {}) :food)))
    (is (nil? (a/animal-housing (a/gain (g/p (own s 0 (cid 306)) 0) {:sheep 1})))) )
  (let [s (own (fresh) 0 (cid 145)) p (a/gain (g/p s 0) {:boar 20})]
    (is (some #(and (= :card (:kind %)) (= 20 (:count %))) (a/animal-housing p)))
    (is (empty? (a/pastures p)))
    (is (= 4 (get-in (a/score p) [:breakdown :boar])))))

(deftest sleeping-corner-reuses-others-growth-but-never-its-own-worker
  (let [s (-> (fresh) (own 0 (cid 139)) (assoc :occupied {:urgent-growth [1 2]})
              (update :active conj :urgent-growth))]
    (is (some? (g/occupied-permission s 0 :urgent-growth)))
    (let [done (g/command s 0 {:type :action :space :urgent-growth})]
      (is (= 3 (:family (g/p done 0))))
      (is (= [1 2 0] (get-in done [:occupied :urgent-growth]))))
    (is (nil? (g/occupied-permission (assoc-in s [:occupied :urgent-growth] [1 0]) 0 :urgent-growth))))
  (let [s (-> (g/initialize (a/new-game ["A" "B" "C" "D" "E"] (vec (mapcat identity a/stages)) 0))
              (own 0 (cid 139)) (own 0 (cid 127)) (crop 0 :grain 2)
              (assoc :round 5 :occupied {:modest-growth 1})
              (update :active into [:modest-growth :lessons-growth])
              (hand :catalog/original-base-e-176-woodcutter))
        done (g/command s 0 {:type :action :space :lessons-growth :card :catalog/original-base-e-176-woodcutter})]
    (is (= 1 (get-in done [:occupied :modest-growth])))
    (is (= 0 (get-in done [:occupied :lessons-growth])))
    (is (= 3 (get-in done [:players 0 :farm 0 :count])))))

(deftest wood-carver-is-optional-once-per-round-and-only-eligible-construction
  (let [p (g/p (-> (fresh) rich (own 0 (cid 301))) 0)
        room (a/build-rooms p [0] {:wood-carver? true})
        stable (a/build-stables p [0] 2 {:wood-carver? true})
        fenced (a/build-fences p (a/edges 0) {:wood-carver? true})]
    (is (= 26 (a/amount room :wood)))
    (is (= 29 (a/amount stable :wood)))
    (is (= 27 (a/amount fenced :wood)))
    (doseq [built [room stable fenced]] (is (:wood-carver-used? built)))
    (is (thrown? clojure.lang.ExceptionInfo (a/build-stables room [1] 2 {:wood-carver? true})))
    (is (thrown? clojure.lang.ExceptionInfo (a/renovate p {:wood-carver? true})))
    (is (thrown? clojure.lang.ExceptionInfo (a/build-rooms (assoc p :house :clay) [0] {:wood-carver? true})))
    (let [saw (g/p (own (assoc-in (fresh) [:players 0] p) 0 (cid 121)) 0)
          built (a/build-stables saw [0 1] 2 {:wood-carver? true})]
      (is (= 29 (a/amount built :wood)))
      (is (:wood-carver-used? built)))))

(deftest spit-roast-food-is-credited-before-last-player-feeds
  (let [s (-> (fresh) (own 3 (cid 109)) (assoc :phase :feeding :round 4 :turn 3 :fed #{0 1 2} :bred #{})
              (assoc-in [:players 3 :improvements] #{:hearth-4}) (assoc-in [:players 3 :goods :boar] 1)
              (assoc-in [:players 3 :goods :food] 0))
        done (g/command s 3 {:type :feed :convert {:boar 1}})]
    (is (= :breeding (:phase done)))
    (is (= 0 (:begging (g/p done 3))))
    (is (= 4 (:spit-roast-round (g/p done 3))))))

(deftest spit-roast-includes-animal-cooking-combined-with-harvest-baking
  (let [s (-> (fresh) (own 0 (cid 109)) (assoc :phase :feeding :round 4)
              (assoc-in [:players 0 :improvements] #{:hearth-4})
              (assoc-in [:players 0 :goods :grain] 1) (assoc-in [:players 0 :goods :sheep] 1)
              (g/queue-front [(g/task 0 :catalog/original-base-e-150-baker {:op :bake :optional? true} {})]) g/drain)
        done (resolve! s {:grain 1 :cook-with-bake {:sheep 1}})]
    (is (= 6 (delta s done :food)))
    (is (= 4 (:spit-roast-round (g/p done 0))))))
