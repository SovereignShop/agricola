(ns agricola.expansion-cards-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.card-expressions :as expression] [agricola.card-coverage :as coverage]
            [agricola.bot :as bot] [eurozone.store :as store]))
(defn fresh [] (g/initialize (a/new-game ["A" "B" "C" "D"] (vec (mapcat identity a/stages)) 0)))
(defn own [state pid code]
  (let [cid (c/id code)] (update-in state [:players pid (if (= :occupation (:kind (c/card cid))) :occupations :minors)] conj cid)))
(defn hand [state code]
  (let [cid (c/id code)] (update-in state [:players 0 :hand (:kind (c/card cid))] (fnil conj []) cid)))
(defn trigger [state code event context]
  (g/drain (g/queue-front (own state 0 code) [(g/event event 0 context)])))
(defn resolve! [state choice]
  (g/command state 0 (merge {:type :resolve :decision (:decision-id state)} choice)))
(defn delta [before after resource] (- (a/amount (g/p after 0) resource) (a/amount (g/p before 0) resource)))

(deftest catalogue-and-playability-are-separate
  (let [report (coverage/report)]
    (is (= 2214 (:catalogue-entries report)))
    (is (= 221 (:native-hand-cards report)))
    (is (= 10 (:native-major-cards report)))
    (is (= (set (keys a/improvements)) (set (keep coverage/engine-major (vals c/metadata)))))
    (is (= 120 (:total (first (filter #(= "revised · artifex expansion" (:id %)) store/card-pack-coverage)))))
    (is (= 77 (:implemented (first (filter #(= "revised · artifex expansion" (:id %)) store/card-pack-coverage)))))
    (is (every? #(some? (expression/requirement (:prerequisites %))) (vals c/cards)))
    (is (= 84 (count (filter #(and (= "Minor" (:type %)) (= :minor (:kind %))) (vals c/metadata)))))
    (is (not= (:id (first (filter #(= "Original" (:edition %)) (vals c/metadata)))) (c/id :a004))))
  (is (every? #(contains? c/cards (:id %)) (store/hand-pool 4)))
  (is (not-any? #(= "revised · artifex expansion" (store/card-pack %))
               (store/hand-pool 4 ["revised · artifex expansion"]))))

(deftest alternative-costs-are-explicit-and-atomic
  (let [state (-> (fresh) (hand :a004) (assoc-in [:players 0 :goods :food] 2) (assoc-in [:players 0 :goods :grain] 1))
        food (g/drain (g/play-card state 0 (c/id :a004) 0 {:payment-option 0}))
        grain (g/drain (g/play-card state 0 (c/id :a004) 0 {:payment-option 1}))]
    (is (= 0 (a/amount (g/p food 0) :food))) (is (= 1 (a/amount (g/p food 0) :grain)))
    (is (= 2 (a/amount (g/p grain 0) :food))) (is (= 0 (a/amount (g/p grain 0) :grain)))
    (is (= 2 (a/amount (g/p food 0) :wood)))
    (is (= [(c/id :a004)] (get-in food [:players 1 :hand :minor])))
    (is (empty? (get-in food [:players 0 :minors])))
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 (c/id :a004) 0 {:payment-option 9})))
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card (assoc-in state [:players 0 :goods :grain] 0) 0 (c/id :a004) 0 {:payment-option 1})))
    (is (= 1 (a/amount (g/p state 0) :grain)))))

(deftest prerequisite-boundaries-do-not-fall-through
  (doseq [[text path low high]
          [["1 Animal" [:goods :sheep] 0 1]
           ["Play in Round 7 or Later" nil 6 7]]]
    (let [put #(if path (assoc-in (fresh) (into [:players 0] path) %) (assoc (fresh) :round %))]
      (is (not (g/prerequisites? (put low) 0 {:prerequisites text})))
      (is (g/prerequisites? (put high) 0 {:prerequisites text}))))
  (is (not (g/prerequisites? (fresh) 0 {:prerequisites "unrecognized requirement"})))
  (is (not (g/prerequisites? (fresh) 0 {:prerequisites "2 Occupations and 2 Improvements"})))
  (is (g/prerequisites? (-> (fresh) (own 0 :a116) (own 0 :b118) (assoc-in [:players 0 :improvements] #{:well :pottery}))
                        0 {:prerequisites "2 Occupations and 2 Improvements"})))

(deftest straightforward-gains-and-action-scope
  (doseq [[code event context expected]
          [[:a042 :use-space {:space :fishing} {:wood 1}]
           [:a042 :use-space {:space :wood} {:food 1}]
           [:a051 :use-space {:space :fishing} {:food 2}]
           [:a077 :use-space {:space :boar} {:clay 2}]
           [:a105 :plow {:fields-plowed 2} {:clay 2 :food 2}]
           [:a121 :after-use-space {:space :lessons-extra} {:clay 1}]
           [:a122 :use-space {:space :sow-bake} {:clay 2 :wood 1}]
           [:a145 :harvest-end {} {:reed 1}]
           [:a146 :use-space {:space :fishing :taken {:food 2}} {:stone 1}]
           [:a146 :use-space {:space :traveling :taken {:food 3}} {:reed 1}]
           [:a146 :use-space {:space :fishing :taken {:food 4}} {:clay 1}]
           [:a146 :use-space {:space :fishing :taken {:food 5}} {:wood 1}]
           [:a146 :use-space {:space :fishing :taken {:food 6}} {}]]]
    (let [state (fresh) after (trigger state code event context)]
      (doseq [resource a/resources]
        (is (= (get expected resource 0) (delta state after resource)) (str code " " resource)))))
  (let [state (assoc (own (fresh) 0 :a051) :turn 1) after (g/command state 1 {:type :action :space :fishing})]
    (is (= 2 (a/amount (g/p after 0) :food))) "Other players' actions do not trigger owner-only cards"))

(deftest action-context-records-prior-animals-and-actual-fields
  (let [base (-> (fresh) (assoc :active (conj (:active (fresh)) :sheep)) (assoc-in [:piles :sheep] {:sheep 1}))
        before (own base 0 :a066) after (g/command before 0 {:type :action :space :sheep})]
    (is (= 0 (a/amount (g/p after 0) :grain)))
    (let [with-sheep (assoc-in before [:players 0 :goods :sheep] 1)
          gained (g/take-action with-sheep 0 {:type :action :space :sheep})
          tasks (filter #(= :event (:kind %)) (:effect-queue gained))]
      (is (= 1 (get-in (first tasks) [:context :before-goods :sheep])))))
  (let [state (own (fresh) 0 :a105) after (g/command state 0 {:type :action :space :plow :cell 4})]
    (is (= 1 (delta state after :clay))) (is (= 1 (delta state after :food)))))

(deftest scheduled-income-and-nonharvest-debt
  (doseq [[code resource event context rounds]
          [[:a043 :food :build-stables {:stables-built 2} #{2 3 4}]
           [:a074 :wood :build-stables {:stables-built 2} #{2 3 4}]
           [:a046 :food :use-space {:space :sheep} #{2 3}]]]
    (let [after (trigger (fresh) code event context)]
      (is (= rounds (set (keys (:schedule (g/p after 0))))))
      (doseq [round rounds] (is (= {resource 1} (:goods (first (get-in after [:players 0 :schedule round]))))))))
  (let [state (-> (fresh) (own 0 :a054) (assoc-in [:players 0 :goods :food] 0))
        pending (g/drain (g/queue-front state [(g/event :round-end 0)]))
        owed (resolve! pending {})
        harvest (g/drain (g/queue-front (assoc state :round 4) [(g/event :round-end 0)]))]
    (is (= 1 (get-in owed [:players 0 :begging]))) (is (= 0 (get-in harvest [:players 0 :begging])))))

(deftest optional-branches-have-payment-validation-and-saveable-decisions
  (let [state (-> (fresh) (own 0 :a118) (assoc-in [:players 0 :goods :food] 1))
        pending (g/drain (g/queue-front state [(g/event :field-phase 0)]))
        before (:players pending)]
    (is (= 1 (a/amount (g/p pending 0) :wood)))
    (is (= :choose (get-in pending [:effect-queue 0 :effect :op])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:option 1})))
    (is (= before (:players pending)))
    (let [paid (resolve! (edn/read-string (pr-str pending)) {:option 0})]
      (is (= 2 (a/amount (g/p paid 0) :wood))) (is (= 0 (a/amount (g/p paid 0) :food))))
    (is (= 1 (a/amount (g/p (resolve! pending {:skip? true}) 0) :food)))))

(deftest during-use-cannot-spend-later-basket-income
  (let [state (-> (fresh) (own 0 :a091) (own 0 :a056) (assoc-in [:players 0 :goods :food] 0))
        pending (g/command state 0 {:type :action :space :wood})
        basket (resolve! pending {})]
    (is (= (c/id :a056) (get-in pending [:effect-queue 0 :card])))
    (is (= 3 (a/amount (g/p basket 0) :food)))
    (is (zero? (count (a/tiles (g/p basket 0) :field))))
    (is (empty? (:effect-queue basket)))))

(deftest global-bonus-and-unaffordable-choice-cannot-create-resources
  (let [state (-> (fresh) (own 0 :a136) (update-in [:players 1] a/gain {:wood 2 :clay 2 :reed 2 :stone 2}))]
    (is (= 3 (get-in (g/score state 1) [:breakdown :bonus]))))
  (let [state (-> (fresh) (own 0 :a153) (assoc-in [:players 0 :goods :boar] 5))
        after (g/drain (g/queue-front state [(g/event :check-state 0) (g/event :check-state 0)]))]
    (is (= 3 (get-in after [:players 0 :bonus-points])))))

(deftest last-crop-harvest-still-counts-for-barley-mill
  (let [state (-> (fresh) (own 0 :a064)
                  (assoc-in [:players 0 :farm 4] {:type :field :crop :grain :count 1}))
        harvested (g/drain (g/continue-step state :field-harvest))]
    (is (= :feeding (:phase harvested)))
    (is (= 0 (get-in harvested [:players 0 :farm 4 :count])))
    (is (= 1 (delta state harvested :grain)))
    (is (= 1 (delta state harvested :food)))))

(deftest stable-income-only-triggers-on-owners-work-turn
  (let [state (-> (fresh) (own 0 :a074) (own 0 :a043) (own 0 :b089)
                  (assoc-in [:players 0 :goods :wood] 1)
                  (assoc-in [:players 0 :house] :stone))
        pending (g/drain (g/begin-round state))
        preparation (resolve! pending {:cell 4})
        building (-> preparation (assoc-in [:players 0 :goods :wood] 4)
                     (g/command 0 {:type :action :space :build :stables [3 8]}))]
    (is (empty? (get-in preparation [:players 0 :schedule])))
    (is (= #{3 4 5} (set (keys (get-in building [:players 0 :schedule])))))
    (is (= {:wood 1 :food 1} (apply merge-with + (map :goods (get-in building [:players 0 :schedule 3])))))))

(deftest bots-can-fund-optional-choice-payments
  (let [state (-> (fresh) (own 0 :a118)
                  (assoc-in [:players 0 :goods :food] 0) (assoc-in [:players 0 :goods :grain] 1))
        pending (g/drain (g/queue-front state [(g/event :field-phase 0)]))
        commands (bot/decision-candidates pending 0)]
    (is (some #{ {:type :convert :goods {:grain 1}} } commands))
    (is (some? (bot/preview pending 0 {:type :convert :goods {:grain 1}})))
    (is (= 2 (a/amount (g/p (resolve! (g/command pending 0 {:type :convert :goods {:grain 1}}) {:option 0}) 0) :wood)))))

(deftest crop-and-building-resource-card-effects
  (let [state (-> (fresh)
                  (assoc-in [:players 0 :farm 4] {:type :field :crop :grain :count 2})
                  (assoc-in [:players 0 :farm 3] {:type :field :crop :vegetable :count 1})
                  (assoc-in [:players 0 :card-fields (c/id :b068)] {:type :field :allowed-crop :vegetable :crop :vegetable :count 1}))
        grown (trigger state :a072 :use-space {:space :stone-east})]
    (is (= [3 2 2] [(get-in grown [:players 0 :farm 4 :count]) (get-in grown [:players 0 :farm 3 :count])
                    (get-in grown [:players 0 :card-fields (c/id :b068) :count])]))
    (let [knife (g/drain (g/play-card (hand (update-in state [:players 0] a/gain {:wood 1}) :a007) 0 (c/id :a007) 0 {}))]
      (is (= 1 (delta state knife :food))) (is (= 2 (delta state knife :grain)))))
  (let [state (-> (fresh) (hand :a006) (assoc-in [:players 0 :improvements] #{:well :joinery :pottery :basketry}))
        barn (g/drain (g/play-card state 0 (c/id :a006) 0 {}))]
    (doseq [resource [:wood :clay :reed :stone]] (is (= 1 (delta state barn resource)))))
  (let [state (-> (fresh) (own 0 :a008) (assoc-in [:players 0 :improvements] #{:well :joinery}) (hand :a117))
        carrier (g/drain (g/play-card state 0 (c/id :a117) 0 {}))]
    (is (= 3 (delta state carrier :wood)))))

(deftest optional-exchanges-consume-cost-and-trigger-once
  (doseq [[code event cost expected]
          [[:a029 :return-home {:grain 1} {:bonus 1 :grain -1}]
           [:a037 :renovate {:wood 1} {:bonus 1 :wood -1 :grain 1}]
           [:a076 :work-start {:grain 1 :clay 1} {:grain -1 :clay 2 :food 1}]]]
    (let [state (update-in (fresh) [:players 0] a/gain cost)
          pending (trigger state code event {})
          done (resolve! pending {})]
      (doseq [resource [:grain :wood :clay :food]] (is (= (get expected resource 0) (delta state done resource))))
      (is (= (get expected :bonus 0) (get-in done [:players 0 :bonus-points])))
      (when (= code :a029) (is (= 1 (- (a/amount (g/p done 1) :food) (a/amount (g/p state 1) :food)))))
      (is (empty? (:effect-queue done))))))

(deftest conditional-income-has-negative-boundaries
  (doseq [[code event context change goods]
          [[:a052 :use-space {:space :wood} #(assoc-in % [:piles :boar] {:boar 1}) {:food 2}]
           [:a141 :return-home {} #(assoc % :occupied {:labor 1 :grain 2}) {:vegetable 1}]
           [:a140 :use-space {:space :clay} #(assoc-in % [:piles :hollow] {:clay 4}) {:food 4}]
           [:a066 :use-space {:space :sheep :before-goods {:sheep 0}}
            identity {}]]]
    (let [state (assoc-in (fresh) [:piles :hollow] {:clay 0}) none (trigger state code event context)
          ready (change state) yes (trigger ready code event context)]
      (doseq [resource [:food :grain :vegetable]]
        (is (zero? (delta state none resource)))
        (is (= (get goods resource 0) (delta ready yes resource)))))))

(deftest wood-harvester-counts-qualifying-spaces-not-total-wood
  (let [state (-> (fresh) (assoc :piles {:wood {:wood 2} :copse {:wood 3} :grove {:wood 7}}))
        after (trigger state :a104 :field-phase {})]
    (is (= 1 (delta state after :wood))) (is (= 2 (delta state after :food)))))

(deftest accumulation-exchanges-and-animal-choices
  (let [state (-> (fresh) (own 0 :a164))
        pending (g/command state 0 {:type :action :space :wood})
        after (resolve! pending {})]
    (is (= 1 (get-in after [:piles :wood :wood])))
    (is (= 2 (delta state after :wood)))
    (is (= 1 (delta state after :sheep))))
  (doseq [[option resource cost] [[0 :sheep 0] [1 :boar 1] [2 :cattle 2]]]
    (let [state (fresh) pending (trigger state :a168 :after-use-space {:space :lessons})
          after (resolve! pending {:option option})]
      (is (= 1 (delta state after resource)))
      (is (= (- cost) (delta state after :food)))))
  (let [state (-> (fresh) (update-in [:players 0] a/build-fences (a/edges 4) {:free-fences 4})
                  (assoc-in [:players 0 :goods :food] 3))
        pending (trigger state :a166 :before-field-phase {})
        after (resolve! pending {})]
    (is (= -3 (delta state after :food))) (is (= 1 (delta state after :cattle)))))

(deftest score-thresholds-and-limited-future-schedules
  (let [state (-> (fresh) (own 0 :a031) (own 0 :a101)
                  (assoc-in [:players 0 :improvements] #{:well :hearth-4 :fireplace-2}))]
    (is (= 3 (g/card-bonus state 0 (c/id :a031))))
    (is (= 2 (g/card-bonus state 0 (c/id :a101)))))
  (let [edges (vec (distinct (mapcat a/edges [3 4 8 9])))
        state (-> (fresh) (own 0 :a099)
                  (update-in [:players 0] a/build-fences edges {:free-fences (count edges)}))]
    ;; Four single-cell pastures do not qualify as one four-cell pasture.
    (is (= 0 (g/card-bonus state 0 (c/id :a099)))))
  (doseq [[animals expected] [[1 0] [2 1] [3 3] [4 5]]]
    (let [state (-> (fresh) (own 0 :a135)
                    (update-in [:players 1] a/gain {:sheep animals :boar animals :cattle animals}))]
      (is (= expected (get-in (g/score state 1) [:breakdown :bonus])))))
  (let [state (-> (fresh) (assoc :round 12) (hand :a047)
                  (update-in [:players 0] a/build-fences (a/edges 4) {:free-fences 4})
                  (update-in [:players 0] a/gain {:wood 1}))
        played (g/drain (g/play-card state 0 (c/id :a047) 0 {}))]
    (is (= #{13 14} (set (keys (get-in played [:players 0 :schedule])))))))

(deftest milestone-and-printed-prerequisite-effects
  (let [state (-> (fresh) (own 0 :a045) (assoc-in [:players 0 :house] :clay))
        after (g/drain (g/queue-front state [(g/event :check-state 0) (g/event :check-state 0)]))]
    (is (= #{2 3 4 5 6 7} (set (keys (get-in after [:players 0 :schedule])))))
    (is (every? #(= 1 (count %)) (vals (get-in after [:players 0 :schedule])))))
  (let [state (-> (fresh) (own 0 :a068))
        no (g/drain (g/queue-front (assoc state :round 5) [(g/event :fence 0 {:fences-built 4})]))
        yes (g/drain (g/queue-front (assoc state :round 4) [(g/event :fence 0 {:fences-built 4})]))]
    (is (= 0 (delta state no :vegetable))) (is (= 1 (delta state yes :vegetable))))
  (let [state (-> (fresh) (hand :a008) (own 0 :a116) (own 0 :b118)
                  (assoc-in [:players 0 :improvements] #{:well :pottery}) (update-in [:players 0] a/gain {:reed 1}))
        played (g/drain (g/play-card state 0 (c/id :a008) 0 {}))]
    (is (= 1 (delta state played :grain))) (is (= 1 (delta state played :vegetable)))
    (is (= -1 (delta state played :reed)))))

(deftest milking-parlor-thresholds-add-both-species
  (doseq [[sheep cattle food] [[0 0 0] [1 1 4] [2 2 5] [3 2 6] [4 3 8]]]
    (let [state (-> (fresh) (hand :a057)
                    (assoc-in [:players 0 :goods :sheep] sheep) (assoc-in [:players 0 :goods :cattle] cattle)
                    (update-in [:players 0] a/gain {:wood 2}))
          ;; Inspect the play effect before mandatory animal accommodation.
          played (g/play-card state 0 (c/id :a057) 0 {})
          after (g/run-effect (update played :effect-queue #(vec (rest %))) (first (:effect-queue played)) {})]
      (is (= food (delta state after :food))))))

(deftest pig-owner-milestone-fires-for-breeding-and-anytime-exchanges
  (let [edges (->> (mapcat a/edges [3 4 8 9]) frequencies (filter #(= 1 (val %))) (mapv key))
        base (-> (fresh) (own 0 :a153)
                 (update-in [:players 0] a/build-fences edges {:free-fences (count edges)})
                 (assoc-in [:players 0 :goods :boar] 4))
        exchanged (-> base (own 0 :b104) (assoc-in [:players 0 :goods :sheep] 1)
                      (g/command 0 {:type :ability :card (c/id :b104) :resource :boar}))
        bred (-> base (assoc :phase :breeding :fed #{0 1 2 3} :bred #{} :turn 0)
                 (g/command 0 {:type :breed :offspring {:boar 1}}))]
    (doseq [state [exchanged bred]]
      (is (= 5 (a/amount (g/p state 0) :boar)))
      (is (= 3 (get-in state [:players 0 :bonus-points])))
      (is (true? (get-in state [:players 0 :card-state (c/id :a153) :triggered?]))))))

(deftest credit-permits-food-conversion-before-taking-begging
  (let [state (-> (fresh) (own 0 :a054) (assoc :phase :return-home)
                  (assoc-in [:players 0 :goods :food] 0) (assoc-in [:players 0 :goods :grain] 1))
        pending (g/drain (g/queue-front state [(g/event :round-end 0)]))
        paid (g/command pending 0 {:type :convert :goods {:grain 1}})]
    (is (= :pay-or-beg (get-in pending [:effect-queue 0 :effect :op])))
    (is (some #{{:type :convert :goods {:grain 1}}} (bot/decision-candidates pending 0)))
    (is (= 0 (get-in paid [:players 0 :begging])))
    (is (= 0 (a/amount (g/p paid 0) :food)))
    (is (= 1 (get-in (resolve! pending {}) [:players 0 :begging])))))
