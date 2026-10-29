(ns agricola.card-game-test
  (:require [clojure.test :refer :all] [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]))
(def order (vec (mapcat identity a/stages)))
(defn game [] (g/initialize (a/new-game ["Ada" "Ben"] order 0)))
(defn hand [game pid codes]
  (reduce (fn [game code] (let [cid (c/id code) kind (:kind (c/card cid))]
                          (update-in game [:players pid :hand kind] (fnil conj []) cid))) game codes))
(defn own [game pid codes]
  (reduce (fn [game code] (let [cid (c/id code) key (if (= :occupation (:kind (c/card cid))) :occupations :minors)]
                          (update-in game [:players pid key] conj cid))) game codes))
(deftest future-round-board-markers-follow-deliveries
  (let [s (-> (game)
              (own 0 [:a044 :a019])
              (own 1 [:b084])
              (g/schedule 0 (c/id :a044) {:offsets [1 2 14] :goods {:food 1}})
              (g/schedule 0 (c/id :a044) {:offsets [1] :goods {:food 1}})
              (g/schedule 0 (c/id :a019) {:offsets [5] :goods {:field 1}})
              (g/schedule 1 (c/id :b084) {:offsets [1 2] :goods {:boar 1}})
              ;; An unowned source must not appear: it will not be delivered.
              (g/schedule 1 (c/id :a069) {:offsets [1] :goods {:vegetable 1}})
              (assoc-in [:players 0 :well-rounds] #{2 3 4}))
        markers (g/future-round-resources s)
        next-round (g/drain (g/begin-round s))]
    (is (= [2 3 4 6] (vec (keys markers))))
    (is (= [{:player 0 :goods {:food 3} :cards [:well (c/id :a044)]}
            {:player 1 :goods {:boar 1} :cards [(c/id :b084)]}]
           (get markers 2)))
    (is (= {:field 1} (:goods (first (get markers 6)))))
    (is (nil? (get (g/future-round-resources next-round) 2)))
    (is (= (+ 3 (a/amount (g/p s 0) :food)) (a/amount (g/p next-round 0) :food)))
    (is (= 1 (a/amount (g/p next-round 1) :boar)))
    (is (= (dissoc markers 2) (g/future-round-resources next-round))))
  (is (= {2 [{:player 0 :goods {:food 1} :cards [:well]}]}
         (g/future-round-resources (assoc-in (a/new-game ["Ada"] order 0) [:players 0 :well-rounds] #{2})))))
(deftest all-base-hand-cards-have-explicit-definitions
  (is (= 96 (count (filter #(= "revised-base" (namespace %)) (keys c/cards)))))
  (is (every? (set (keys c/metadata)) (keys c/cards)))
  (is (every? :name (vals c/cards))))
(deftest occupations-have-costs-and-trigger-on-the-printed-space
  (let [s (hand (game) 0 [:a116])
        played (g/command s 0 {:type :action :space :lessons :card (c/id :a116)})
        next (g/command played 1 {:type :action :space :grain})
        wood (g/command next 0 {:type :action :space :wood})]
    (is (= 2 (a/amount (get-in played [:players 0]) :food)))
    (is (= [(c/id :a116)] (get-in played [:players 0 :occupations])))
    (is (empty? (get-in played [:players 0 :hand :occupation])))
    (is (= 4 (a/amount (get-in wood [:players 0]) :wood)))
    (is (= 1 (:turn wood)))))
(deftest travelling-minor-passes-left-and-does-not-accumulate-food
  (let [s (-> (game) (hand 0 [:b008]) (update-in [:players 0] a/gain {:grain 1}))
        played (g/command s 0 {:type :action :space :meeting :card (c/id :b008)})]
    (is (= 1 (a/amount (get-in played [:players 0]) :vegetable)))
    (is (= 2 (a/amount (get-in played [:players 0]) :food)))
    (is (empty? (get-in played [:players 0 :minors])))
    (is (= [(c/id :b008)] (get-in played [:players 1 :hand :minor])))))
(deftest spatial-choice-and-schedule-survive-as-data
  (let [s (hand (game) 0 [:a002])
        pending (g/command s 0 {:type :action :space :meeting :card (c/id :a002)})
        resolved (g/command pending 0 {:type :resolve :decision 0 :cell 4})]
    (is (= :plow (get-in pending [:effect-queue 0 :effect :op])))
    (is (= 0 (g/acting-player pending)))
    (is (= :field (get-in resolved [:players 0 :farm 4 :type])))
    (is (= 1 (:turn resolved)))
    (is (empty? (:effect-queue resolved)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command pending 1 {:type :resolve :decision 0 :cell 4})))
    (is (thrown? clojure.lang.ExceptionInfo (g/command pending 0 {:type :resolve :decision 9 :cell 4}))))
  (let [s (-> (game) (own 0 [:a098 :b099]) (hand 0 [:a044]) (update-in [:players 0] a/gain {:wood 1}))
        played (g/command s 0 {:type :action :space :meeting :card (c/id :a044)})]
    (is (= #{2 3 4} (set (keys (get-in played [:players 0 :schedule])))))))
(deftest optional-exchanges-do-not-auto-spend-and-return-wood-to-the-space
  (let [s (own (game) 0 [:a056 :a108])
        pending (g/command s 0 {:type :action :space :wood})
        ordered (g/command pending 0 {:type :order-effect :card (c/id :a056)})
        exchanged (g/command ordered 0 {:type :resolve :decision 1})
        finished (g/command exchanged 0 {:type :resolve :decision 2 :skip? true})]
    (is (= 3 (a/amount (get-in pending [:players 0]) :wood)))
    (is (= 1 (a/amount (get-in finished [:players 0]) :wood)))
    (is (= 5 (a/amount (get-in finished [:players 0]) :food)))
    (is (= 2 (get-in finished [:piles :wood :wood])))))
(deftest other-players-effects-pause-the-acting-player
  (let [s (-> (g/initialize (a/new-game ["A" "B" "C" "D"] order 0)) (own 1 [:a160]))
        pending (g/command s 0 {:type :action :space :traveling})
        resolved (g/command pending 1 {:type :resolve :decision 0})]
    (is (= 1 (g/acting-player pending)))
    (is (= 4 (a/amount (get-in pending [:players 1]) :food)))
    (is (= 1 (a/amount (get-in resolved [:players 1]) :vegetable)))
    (is (= 2 (a/amount (get-in resolved [:players 1]) :food)))))
(deftest passive-costs-housing-and-card-fields
  (let [s (-> (game) (own 0 [:a088 :a016 :a012 :a086 :b126 :a143])) p (get-in s [:players 0])
        p (a/gain p {:clay 1 :wood 10 :reed 10})
        fenced (a/build-fences p (a/edges 14) {:fence-material :clay})]
    (is (= 0 (a/amount fenced :clay)))
    (is (= 4 (:capacity (first (filter #(= :pasture (:kind %)) (a/accommodations fenced))))))
    (is (= 2 (count (filter #(= :pet (:kind %)) (a/accommodations fenced)))))
    (is (= {:wood 3 :reed 2 :stone 0} (a/room-cost p {}))))
  (let [p (-> (a/player 0 "A" 0) (assoc :card-fields {(c/id :b068) {:type :field :allowed-crop :vegetable :count 0}}) (a/gain {:vegetable 1}))
        planted (a/sow p [{:cell (c/id :b068) :crop :vegetable}])]
    (is (= 2 (get-in planted [:card-fields (c/id :b068) :count])))
    (is (= 1 (a/amount (a/field-harvest planted) :vegetable)))
    (is (thrown? clojure.lang.ExceptionInfo (a/sow p [{:cell (c/id :b068) :crop :grain}])))))
(deftest generic-commands-cannot-request-free-fences
  (let [s (assoc (game) :active (conj (:active (game)) :fences))]
    (is (thrown? clojure.lang.ExceptionInfo (g/command s 0 {:type :action :space :fences :fences (a/edges 14) :free-fences 15})))))

(deftest card-game-round-and-harvest-lifecycle
  (doseq [n [1 2 3 4]]
    (loop [s (g/initialize (a/new-game (mapv #(str "Player " %) (range n)) order 0)) moves 0]
      (is (< moves 200))
      (if (= :finished (:phase s))
        (do (is (= 14 (:round s))) (is (= n (count (:scores s)))))
        (let [pid (g/acting-player s)
              cmd (case (:phase s)
                    :feeding {:type :feed :convert {} :craft []}
                    :breeding {:type :breed :offspring {}}
                    :work {:type :action :space (first (filter #(and (some #{%} (:active s)) (not (contains? (:occupied s) %)))
                                                             [:wood :clay :reed :grain :labor :fishing :meeting :grove :hollow :copse :traveling :stone-west :stone-east :vegetable]))})]
          (recur (g/command s pid cmd) (inc moves)))))))

(deftest scheduled-animals-stop-the-correct-owner-before-work
  (let [s (-> (game) (own 1 [:b084])
              (assoc :round 1)
              (assoc-in [:players 1 :schedule 2] [{:card (c/id :b084) :goods {:boar 3}}]))
        started (g/drain (g/begin-round s))]
    (is (= 2 (:round started)))
    (is (= 1 (g/acting-player started)))
    (is (= :housing (get-in started [:effect-queue 0 :effect :op])))
    (let [settled (g/command started 1 {:type :resolve :decision 0 :keep {:sheep 0 :boar 1 :cattle 0}})]
      (is (empty? (:effect-queue settled)))
      (is (= (:starting-player settled) (:turn settled)))
      (is (= 1 (a/amount (get-in settled [:players 1]) :boar))))))

(defn rich [s]
  (update-in s [:players 0] a/gain (zipmap [:wood :clay :reed :stone :food :grain :vegetable] (repeat 30))))
(defn occupation-count [s n]
  (assoc-in s [:players 0 :occupations] (mapv :id (take n (filter #(= :occupation (:kind %)) (vals c/cards))))))
(deftest every-printed-prerequisite-has-a-boundary-check
  (let [requirements (set (map :prerequisites (filter #(= "revised-base" (namespace (:id %))) (vals c/cards))))]
    (is (= #{"" "1 Occupation" "2 Occupations" "3 Occupations" "Exactly 2 Occupations"
             "At Most 3 Occupations" "2 Grain Fields" "2 Vegetable Fields" "5 Sheep"
             "Clay or Stone House" "All Farmyard Spaces Used"} requirements)))
  (doseq [[requirement n allowed?] [["1 Occupation" 0 false] ["1 Occupation" 1 true]
                                   ["2 Occupations" 1 false] ["2 Occupations" 2 true] ["2 Occupations" 3 true]
                                   ["3 Occupations" 2 false] ["3 Occupations" 3 true]
                                   ["Exactly 2 Occupations" 1 false] ["Exactly 2 Occupations" 2 true] ["Exactly 2 Occupations" 3 false]
                                   ["At Most 3 Occupations" 0 true] ["At Most 3 Occupations" 3 true] ["At Most 3 Occupations" 4 false]]]
    (is (= allowed? (boolean (g/prerequisites? (occupation-count (game) n) 0 {:prerequisites requirement}))) (str requirement " with " n)))
  (doseq [[crop requirement] [[:grain "2 Grain Fields"] [:vegetable "2 Vegetable Fields"]]]
    (let [s (-> (game) (assoc-in [:players 0 :farm 3] {:type :field :crop crop :count 0})
                (assoc-in [:players 0 :card-fields :test-field] {:type :field :crop crop :count 2}))]
      (is (not (g/prerequisites? s 0 {:prerequisites requirement})))
      (is (g/prerequisites? (assoc-in s [:players 0 :farm 3 :count] 1) 0 {:prerequisites requirement}))))
  (doseq [[sheep allowed?] [[4 false] [5 true] [6 true]]]
    (is (= allowed? (g/prerequisites? (assoc-in (game) [:players 0 :goods :sheep] sheep) 0 {:prerequisites "5 Sheep"}))))
  (doseq [[house allowed?] [[:wood false] [:clay true] [:stone true]]]
    (is (= allowed? (boolean (g/prerequisites? (assoc-in (game) [:players 0 :house] house) 0 {:prerequisites "Clay or Stone House"})))))
  (let [s (assoc-in (game) [:players 0 :farm] (vec (repeat 15 {:type :field})))]
    (is (g/prerequisites? s 0 {:prerequisites "All Farmyard Spaces Used"}))
    (is (not (g/prerequisites? (assoc-in s [:players 0 :farm 0] {:type :empty}) 0 {:prerequisites "All Farmyard Spaces Used"}))))
  (is (not (g/prerequisites? (game) 0 {:prerequisites "Unknown requirement"}))))

(deftest rejected-cards-cannot-consume-a-worker-or-pay-for-their-own-cost
  (let [s (-> (game) rich (hand 0 [:b068]))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Requires: 2 Occupations"
                         (g/command s 0 {:type :action :space :meeting :card (c/id :b068)})))
    (is (= 2 (get-in s [:players 0 :remaining])))
    (is (= [(c/id :b068)] (get-in s [:players 0 :hand :minor])))
    (let [played (g/command (occupation-count s 2) 0 {:type :action :space :meeting :card (c/id :b068)})]
      (is (= 31 (a/amount (g/p played 0) :food)))
      (is (= :vegetable (get-in played [:players 0 :card-fields (c/id :b068) :allowed-crop])))))
  ;; A printed cost is required before any immediate effect can run.
  (let [s (-> (game) (hand 0 [:a002]) (assoc-in [:players 0 :goods :food] 1))]
    (is (thrown? clojure.lang.ExceptionInfo (g/command s 0 {:type :action :space :meeting :card (c/id :a002)}))))
  (is (thrown? clojure.lang.ExceptionInfo
               (g/command (rich (game)) 0 {:type :action :space :meeting :card (c/id :b068)}))))

(deftest special-requirements-and-discounts-use-the-current-state
  (let [s (-> (game) rich (hand 0 [:b056 :b074]))]
    (doseq [owner [nil 1]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Fishing"
                           (g/command (assoc-in s [:occupied :fishing] owner) 0 {:type :action :space :meeting :card (c/id :b056)}))))
    (is (some #{(c/id :b056)} (:minors (g/p (g/command (assoc-in s [:occupied :fishing] 0) 0 {:type :action :space :meeting :card (c/id :b056)}) 0))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"five clay"
                         (g/command (assoc-in s [:players 0 :goods :clay] 4) 0 {:type :action :space :meeting :card (c/id :b074)})))
    (let [played (g/command (assoc-in s [:players 0 :goods :clay] 5) 0 {:type :action :space :meeting :card (c/id :b074)})]
      (is (= 5 (a/amount (g/p played 0) :clay)))
      (is (= #{2 4 6 8 10 12 14} (set (keys (get-in played [:players 0 :schedule])))))))
  (let [s (-> (game) (own 0 [:a075]) (hand 0 [:a071]))
        played (g/command s 0 {:type :action :space :meeting :card (c/id :a071)})]
    (is (zero? (a/amount (g/p played 0) :wood)))
    (is (some #{(c/id :a071)} (:minors (g/p played 0))))))

(deftest scholar-cannot-bypass-prerequisites-or-replay-a-resolved-effect
  (let [s (-> (game) rich (hand 0 [:b068]) (own 0 [:b097]) (assoc-in [:players 0 :house] :stone))
        pending (g/drain (g/begin-round s))]
    (is (= :scholar (get-in pending [:effect-queue 0 :effect :op])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Requires: 2 Occupations"
                         (g/command pending 0 {:type :resolve :decision 0 :card (c/id :b068)})))
    (let [resolved (g/command (own pending 0 [:a116]) 0 {:type :resolve :decision 0 :card (c/id :b068)})]
      (is (empty? (:effect-queue resolved)))
      (is (thrown? clojure.lang.ExceptionInfo (g/command resolved 0 {:type :resolve :decision 0 :card (c/id :b068)}))))))

(deftest mandatory-effects-chain-automatically-and-one-time-triggers-do-not-repeat
  (let [s (-> (game) rich (own 0 [:a120 :a055 :b118]) (hand 0 [:a071])
              (assoc :active (conj (:active (game)) :renovate)))
        renovated (g/command s 0 {:type :action :space :renovate :card (c/id :a071)})
        next-round (g/drain (g/begin-round renovated))]
    (is (empty? (:effect-queue renovated)))
    (is (= 33 (a/amount (g/p renovated 0) :food))) ; automatic improvement bonus
    (is (= 5 (count (get-in renovated [:players 0 :schedule]))))
    (is (= 1 (count (get-in next-round [:players 0 :schedule 3]))))
    (is (= 30 (a/amount (g/p next-round 0) :clay))) ; 30 - two rooms + 2 scheduled
    (is (= 30 (a/amount (g/p next-round 0) :wood))) ; card cost -1, Small-scale Farmer +1
    (is (empty? (:effect-queue next-round)))))

(deftest impossible-spatial-effects-do-not-deadlock-a-game
  (let [s (-> (game) rich (hand 0 [:a002])
              (assoc-in [:players 0 :farm] (mapv #(hash-map :type (if (#{5 10} %) :room :field)) (range 15))))
        played (g/command s 0 {:type :action :space :meeting :card (c/id :a002)})]
    (is (empty? (:effect-queue played)))
    (is (= 1 (:turn played)))
    (is (= [(c/id :a002)] (get-in played [:players 1 :hand :minor])))))

(deftest optional-harvest-and-field-card-restrictions
  (let [s (-> (game) (own 0 [:a071 :a112])
              (assoc-in [:players 0 :farm 3] {:type :field :crop :grain :count 2})
              (assoc-in [:players 0 :farm 4] {:type :field :crop :grain :count 2})
              (assoc-in [:players 0 :card-fields (c/id :b068)] {:type :field :allowed-crop :vegetable :count 0}))
        pending (g/drain (g/queue-front s [(g/event :field-phase 0)]))
        result (g/command pending 0 {:type :resolve :decision 0 :fields [3]})]
    (is (= 1 (get-in result [:players 0 :farm 3 :count])))
    (is (= 2 (get-in result [:players 0 :farm 4 :count])))
    (is (= 1 (a/amount (g/p result 0) :grain)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/command pending 0 {:type :resolve :decision 0 :fields [3 3]})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/command s 0 {:type :ability :card (c/id :a071) :from 3 :to (c/id :b068)})))
    (let [result (g/command (assoc-in s [:players 0 :farm 3 :crop] :vegetable) 0
                            {:type :ability :card (c/id :a071) :from 3 :to (c/id :b068)})]
      (is (= :vegetable (get-in result [:players 0 :card-fields (c/id :b068) :allowed-crop])))
      (is (= 1 (get-in result [:players 0 :card-fields (c/id :b068) :count]))))))

(deftest automatic-animal-placement-preserves-organic-farmer-bonuses
  (let [s (-> (game) rich (own 0 [:b098 :a012]))
        p (-> (g/p s 0) (a/build-fences (distinct (concat (a/edges 13) (a/edges 14)))) (a/gain {:sheep 2}))
        s (assoc-in s [:players 0] p)
        placement (a/animal-housing p)]
    (is (= 2 (g/card-bonus s 0 (c/id :b098))))
    (is (= [1 1] (sort (map :count placement))))
    (is (every? #(= :pasture (:kind %)) placement))
    (is (nil? (a/animal-housing (a/gain p {:sheep 8}))))))

(deftest zero-grain-cannot-trigger-baking-bonuses
  (let [s (-> (game) (own 0 [:b025 :a063]) (hand 0 [:a116])
              (assoc :round 5) (assoc-in [:players 0 :improvements] #{:fireplace-2})
              (update-in [:players 0] a/gain {:grain 1}))
        pending (g/command s 0 {:type :action :space :lessons :card (c/id :a116)})]
    (is (= :bake (get-in pending [:effect-queue 0 :effect :op])))
    (is (thrown? clojure.lang.ExceptionInfo (g/command pending 0 {:type :resolve :decision 0 :grain 0})))
    (let [baked (g/command pending 0 {:type :resolve :decision 0 :grain 1})]
      (is (= 7 (a/amount (g/p baked 0) :food)))
      (is (empty? (:effect-queue baked))))))

(deftest lasso-requires-two-available-people
  (let [s (-> (game) (own 0 [:b024]) (assoc-in [:players 0 :remaining] 1)
              (update :active conj :sheep))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"two people"
                         (g/command s 0 {:type :action :space :sheep :lasso? true})))))

(deftest ignored-command-fields-cannot-forge-card-events
  (let [s (-> (game) (own 0 [:a063]) (assoc :round 5))
        result (g/command s 0 {:type :action :space :wood :bake-grain 1})]
    (is (= 2 (a/amount (g/p result 0) :food)))
    (is (empty? (:effect-queue result)))))

(deftest scholar-uses-current-printing-round-start-timing
  ;; Revised B097: https://boardgamegeek.com/thread/2505218/b097-scholar
  ;; Older preview images say "before". Current printing says "at" round start.
  (let [s (-> (game) (own 0 [:b097]) (hand 0 [:b118])
              (assoc-in [:players 0 :house] :stone)
              (assoc-in [:players 0 :goods :food] 0)
              (assoc-in [:players 0 :well-rounds] #{2}))
        pending (g/drain (g/begin-round s))
        played (g/command pending 0 {:type :resolve :decision (:decision-id pending) :card (c/id :b118)})]
    (is (= 1 (a/amount (g/p pending 0) :food)))
    (is (= :scholar (get-in pending [:effect-queue 0 :effect :op])))
    (is (= 0 (a/amount (g/p played 0) :food)))
    (is (some #{(c/id :b118)} (:occupations (g/p played 0))))
    (is (= 0 (a/amount (g/p played 0) :wood)) "Small-scale Farmer does not get an immediate trigger; Plow Driver and Groom have separate rulings")
    (let [next-round (g/drain (g/begin-round played))]
      (is (= 1 (a/amount (g/p next-round 0) :wood)) "The trigger works starting next round"))))

(deftest scholar-manservant-schedules-only-remaining-rounds
  ;; Lumin's Revised Compendium, B097/B107 (resources/rules/sources.edn).
  (let [s (-> (game) (own 0 [:b097]) (hand 0 [:b107])
              (assoc :round 4)
              (assoc-in [:players 0 :house] :stone)
              (assoc-in [:players 0 :goods :food] 1))
        pending (g/drain (g/begin-round s))
        played (g/command pending 0 {:type :resolve :decision (:decision-id pending) :card (c/id :b107)})
        next-round (g/drain (g/begin-round played))]
    (is (= 5 (:round played)))
    (is (= 0 (a/amount (g/p played 0) :food)))
    (is (= (set (range 6 15)) (set (keys (:schedule (g/p played 0))))))
    (is (= 3 (a/amount (g/p next-round 0) :food)))))
