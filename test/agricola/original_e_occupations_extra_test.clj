(ns agricola.original-e-occupations-extra-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot]))
(defn fresh [] (g/initialize (a/new-game ["A" "B" "C" "D"] (vec (mapcat identity a/stages)) 0)))
(defn cid [n]
  (:id (first (filter #(and (= "Original" (:edition %)) (= "E" (:deck %)) (= (str n) (:number %))) (vals c/metadata)))))
(defn own [state n] (update-in state [:players 0 :occupations] conj (cid n)))
(defn hand [state n] (update-in state [:players 0 :hand :occupation] (fnil conj []) (cid n)))
(defn resolve! [state choice] (g/command state 0 (merge {:type :resolve :decision (:decision-id state)} choice)))
(defn resource-delta [before after r] (- (a/amount (g/p after 0) r) (a/amount (g/p before 0) r)))
(defn emit [state event context] (g/drain (g/queue-front state [(g/event event 0 context)])))

(deftest every-original-exchange-pays-exactly-the-printed-cost
  (doseq [n [158 171 185 186 209]]
    (is (seq (get-in (c/card (cid n)) [:ability :options])) (str "Original E" n " is executable")))
  (doseq [n [158 171 185 186 209]
          [i {:keys [cost goods]}] (map-indexed vector (get-in (c/card (cid n)) [:ability :options]))]
    (let [before (-> (fresh) (own n) (update-in [:players 0] a/gain (into {} (map (fn [[r amount]] [r (* 3 amount)]) cost))))
          cmd {:type :ability :card (cid n) :option i :times 3}
          after (g/ability before 0 cmd)]
      (doseq [r a/resources]
        (is (= (* 3 (- (get goods r 0) (get cost r 0))) (resource-delta before after r)) (str n " option " i " " r)))
      (is (g/anytime-available? (g/p before 0) (cid n)))
      (is (some #{cmd} (bot/ability-candidates before 0)))
      (is (thrown? clojure.lang.ExceptionInfo (g/ability before 0 (assoc cmd :times 4))))
      (doseq [times [0 -1 1.5]]
        (is (thrown? clojure.lang.ExceptionInfo (g/ability before 0 (assoc cmd :times times)))))
      (doseq [index [-1 99 nil]]
        (is (thrown? clojure.lang.ExceptionInfo (g/ability before 0 (assoc cmd :option index)))))))
  (let [before (-> (fresh) (own 186) (assoc-in [:players 0 :goods :clay] 6))
        pending (g/command before 0 {:type :ability :card (cid 186) :option 0 :times 3})]
    (is (= 3 (a/amount (g/p pending 0) :sheep)))
    (is (= :housing (get-in pending [:effect-queue 0 :effect :op])))
    (is (= pending (edn/read-string (pr-str pending))))))

(deftest original-discounts-apply-at-all-native-purchase-routes
  (let [base (-> (fresh) (own 211) (g/p 0) (assoc :house :stone))]
    (is (= {:stone 4 :reed 2} (a/room-cost base {})))
    (is (= {:wood 1 :stone 2} (a/improvement-cost base :well nil)))
    (is (= {:wood 3 :stone 0} (g/card-cost (assoc-in (fresh) [:players 0] base) 0 (c/card :catalog/original-base-e-17-windmill) 0)))
    (let [before (assoc base :goods {:stone 8 :reed 4}) after (a/build-rooms before [0 6])]
      (is (= 4 (count (a/tiles after :room))))
      (is (zero? (a/amount after :stone)))))
  (doseq [[house target material] [[:wood :clay :clay] [:clay :stone :stone]]]
    (let [before (-> (fresh) (own 199) (g/p 0) (assoc :house house :goods {:reed 1}))
          after (a/renovate before)]
      (is (= target (:house after)))
      (is (zero? (a/amount after material)))
      (is (zero? (a/amount after :reed)))))
  (let [before (-> (fresh) (own 200) (g/p 0) (assoc :goods {:stone 2 :reed 1}))
        after (a/renovate before {:renovate-to :stone})]
    (is (= :stone (:house after)))
    (is (some #{ {:renovate-to :stone} } (bot/payment-options before)))
    (is (zero? (a/amount after :stone))))
  (let [before (-> (fresh) (own 200) (own 199) (own 211) (g/p 0) (assoc :goods {:reed 1}))]
    (is (= :stone (:house (a/renovate before {:renovate-to :stone}))))))

(deftest chief-surcharge-and-patron-income-are-before-occupation-payment
  (let [before (-> (fresh) (hand 172) (assoc-in [:players 0 :goods :food] 2))]
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card before 0 (cid 172) 1 {})))
    (let [after (g/drain (g/play-card before 0 (cid 172) 0 {}))]
      (is (zero? (a/amount (g/p after 0) :food)))
      (is (= 0 (g/card-bonus after 0 (cid 172))))
      (is (= 2 (g/card-bonus (assoc-in after [:players 0 :house] :stone) 0 (cid 172))))))
  (let [before (-> (fresh) (hand 192) (assoc-in [:players 0 :goods :food] 0))
        played (g/drain (g/play-card before 0 (cid 192) 0 {}))
        funded (g/drain (g/play-card (hand played 172) 0 (cid 172) 0 {}))]
    (is (zero? (a/amount (g/p played 0) :food)) "Patron never rewards playing itself")
    (is (zero? (a/amount (g/p funded 0) :food)) "Patron's 2 food can pay Chief's printed surcharge")
    (let [after (g/drain (g/play-card (hand played 176) 0 (cid 176) 0 {}))]
      (is (= 2 (a/amount (g/p after 0) :food)) "Even a subsequent free occupation receives income"))))

(deftest maid-schedules-future-rounds-only-once
  (let [before (-> (fresh) (assoc :round 7) (hand 190) (assoc-in [:players 0 :house] :clay))
        after (g/drain (g/play-card before 0 (cid 190) 0 {}))
        later (emit (assoc-in after [:players 0 :house] :stone) :check-state {})]
    (is (= (set (range 8 15)) (set (keys (get-in after [:players 0 :schedule])))))
    (is (= (get-in after [:players 0 :schedule]) (get-in later [:players 0 :schedule])))
    (is (every? #(= [{:card (cid 190) :goods {:food 1}}] %) (vals (get-in after [:players 0 :schedule])))))
  (let [before (own (fresh) 190)]
    (is (empty? (get-in (emit before :check-state {}) [:players 0 :schedule])))
    (is (= 13 (count (get-in (emit (assoc-in before [:players 0 :house] :clay) :check-state {}) [:players 0 :schedule]))))))

(deftest cook-reduces-only-adult-feeding-beyond-two-people
  (doseq [family (range 2 6) newborn (range (inc family))]
    (let [state (-> (fresh) (own 181) (assoc-in [:players 0 :family] family) (assoc-in [:players 0 :newborn] newborn))]
      (is (= (+ family (min 2 (- family newborn))) (a/food-needed state (g/p state 0)))))))

(deftest stockman-rewards-crossed-thresholds-including-preparation-stables
  (let [before (-> (fresh) (own 213) (assoc-in [:players 0 :goods :wood] 8))
        after (g/command before 0 {:type :action :space :build :stables [0 1 2 3]})]
    (is (= {:cattle 1 :boar 1 :sheep 1} (select-keys (get-in after [:players 0 :goods]) a/animals))))
  (let [before (-> (fresh) (own 213) (assoc :phase :preparation) (assoc-in [:players 0 :farm 0 :stable] true))
        after (g/drain (g/queue-front before [(g/task 0 (cid 213) {:op :stable} {})]))
        after (resolve! after {:cell 1})]
    (is (= 1 (a/amount (g/p after 0) :cattle)))
    (is (zero? (a/amount (g/p after 0) :boar)))))

(deftest source-income-and-baking-use-real-event-context
  (let [before (own (fresh) 176)
        after (g/command before 0 {:type :action :space :wood})]
    (is (= 4 (resource-delta before after :wood))))
  (let [before (own (fresh) 188)
        after (g/command before 0 {:type :action :space :clay})]
    (is (= 3 (resource-delta before after :clay))))
  (let [before (-> (fresh) (own 188))]
    (is (= 2 (resource-delta before (emit before :use-space {:source-goods {:clay 1}}) :clay)))
    (is (zero? (resource-delta before (emit before :use-space {:source-goods {:clay 1 :stone 1}}) :clay)))
    (is (zero? (resource-delta before (emit before :use-space {:source-goods {} :taken {:clay 2}}) :clay))))
  (let [before (-> (fresh) (own 176) (update-in [:players 0 :hand :minor] (fnil conj []) :catalog/original-base-e-16-building-material))
        pending (g/drain (g/play-card before 0 :catalog/original-base-e-16-building-material 0 {}))
        after (resolve! pending {:resource :wood})]
    (is (= 1 (resource-delta before after :wood)) "Unlike Berry Picker, Woodcutter does not reward Building Material"))
  (let [before (-> (fresh) (own 155) (assoc-in [:players 0 :improvements] #{:clay-oven})
                   (assoc-in [:players 0 :goods :grain] 1) (update :active conj :sow-bake))
        after (g/command before 0 {:type :action :space :sow-bake :bake-grain 1})]
    (is (= 6 (resource-delta before after :food))))
  (let [before (-> (fresh) (own 155) (assoc-in [:players 1 :improvements] #{:stone-oven})
                   (assoc-in [:players 1 :goods :grain] 2))
        pending (g/drain (g/queue-front before [(g/task 1 (cid 155) {:op :bake :optional? true} {})]))
        after (g/command pending 1 {:type :resolve :decision (:decision-id pending) :grain 2})]
    (is (= 2 (resource-delta before after :food)))))

(deftest farmer-first-fence-and-later-fences-do-not-double-trigger
  (let [before (own (fresh) 160)
        first-fence (emit before :fence {:fences-built 4})
        same-turn (emit first-fence :fence {:fences-built 4})
        second-fence (emit (update first-fence :turn-serial (fnil inc 0)) :fence {:fences-built 4})]
    (is (= 1 (a/amount (g/p first-fence 0) :boar)))
    (is (zero? (a/amount (g/p first-fence 0) :cattle)))
    (is (zero? (a/amount (g/p same-turn 0) :cattle)))
    (is (= 1 (a/amount (g/p second-fence 0) :cattle)))))

(deftest master-builder-and-mason-grant-one-free-room-at-anytime
  (doseq [[n minimum house] [[151 5 :wood] [151 5 :clay] [151 5 :stone] [191 4 :stone]]]
    (let [before (-> (fresh) (own n) (assoc-in [:players 0 :house] house)
                     (update-in [:players 0 :farm] #(reduce (fn [farm cell] (assoc farm cell {:type :room})) % (take (- minimum 2) [0 6 11]))))
          too-small (assoc-in before [:players 0 :farm 0] {:type :empty})
          cmd {:type :ability :card (cid n) :rooms [1]}
          after (g/command before 0 cmd)]
      (is (g/anytime-available? (g/p before 0) (cid n)))
      (is (not (g/anytime-available? (g/p too-small 0) (cid n))))
      (is (= (inc minimum) (count (a/tiles (g/p after 0) :room))))
      (is (= (:goods (g/p before 0)) (:goods (g/p after 0))))
      (is (= (:remaining (g/p before 0)) (:remaining (g/p after 0))))
      (is (some #{cmd} (bot/ability-candidates before 0)))
      (is (not (g/anytime-available? (g/p after 0) (cid n))))
      (is (thrown? clojure.lang.ExceptionInfo (g/ability after 0 (assoc cmd :rooms [2]))))
      (is (thrown? clojure.lang.ExceptionInfo (g/ability before 0 (assoc cmd :rooms [1 2]))))
      (is (thrown? clojure.lang.ExceptionInfo (g/ability before 0 (assoc cmd :rooms [14]))))
      (is (thrown? clojure.lang.ExceptionInfo (g/ability too-small 0 cmd)))))
  (let [wrong-house (-> (fresh) (own 191)
                        (assoc-in [:players 0 :farm 0] {:type :room})
                        (assoc-in [:players 0 :farm 6] {:type :room}))]
    (is (not (g/anytime-available? (g/p wrong-house 0) (cid 191))))
    (is (thrown? clojure.lang.ExceptionInfo (g/ability wrong-house 0 {:card (cid 191) :rooms [1]})))))

(deftest original-endgame-bonuses-use-printed-categories-and-global-ties
  (let [before (-> (fresh) (own 189) (assoc-in [:players 0 :goods] {:grain 8 :vegetable 4 :sheep 8 :boar 7 :cattle 6})
                   (assoc-in [:players 0 :farm 0] {:type :room}) (assoc-in [:players 0 :farm 6] {:type :room})
                   (assoc-in [:players 0 :house] :clay))
        after (assoc-in before [:players 0 :farm 1] {:type :field :crop :grain :count 3})]
    (is (= 5 (g/card-bonus before 0 (cid 189))) "Four room points are not a maximum-four category")
    (is (= 5 (g/card-bonus after 0 (cid 189))) "Crop counts include supply and fields without awarding multiple bonuses")
    (let [fenced (-> before
                     (assoc-in [:players 0 :fences] (set (mapcat a/edges [1 2 3 4])))
                     (update-in [:players 0 :farm] #(reduce (fn [farm cell] (assoc-in farm [cell :stable] true)) % [1 2 3 4])))]
      (is (= 7 (g/card-bonus fenced 0 (cid 189))) "Four pastures and four fenced stables each earn one point")))
  (doseq [n [3 4 5]]
    (let [state (-> (g/initialize (a/new-game (mapv str (range n)) (vec (mapcat identity a/stages)) 0)) (own 170))]
      (is (= (dec n) (g/card-bonus state 0 (cid 170))) "Ties in all three species qualify")
      (is (= 0 (g/card-bonus (assoc-in state [:players 1 :goods :boar] 1) 0 (cid 170))))))
  (doseq [[round wood] [[1 4] [5 4] [6 3] [8 3] [9 2] [11 2] [12 1] [13 1] [14 0]]]
    (let [before (-> (fresh) (hand 217) (assoc :round round))
          after (g/drain (g/play-card before 0 (cid 217) 0 {}))]
      (is (= wood (resource-delta before after :wood)))))
  (let [state (-> (fresh) (own 217) (assoc-in [:players 1 :occupations] [(cid 176)]))]
    (is (= 3 (get-in (g/score state 0) [:breakdown :bonus])))
    (is (= 3 (get-in (g/score state 1) [:breakdown :bonus])))
    (is (= 0 (get-in (g/score state 2) [:breakdown :bonus])))
    (let [academic (assoc-in state [:players 1 :occupations] [(cid 148)])]
      (is (= 0 (g/card-bonus academic 0 (cid 217))))
      (is (= 3 (g/card-bonus academic 1 (cid 217)))))))

(deftest forest-school-can-replace-chiefs-additional-food-cost-too
  (let [before (-> (fresh) (hand 172)
                   (update-in [:players 0 :minors] conj (c/id :a028))
                   (assoc-in [:players 0 :goods :wood] 4)
                   (assoc-in [:players 0 :goods :food] 0))
        after (g/drain (g/play-card before 0 (cid 172) 2 {:occupation-wood 4}))]
    (is (zero? (a/amount (g/p after 0) :wood)))
    (is (zero? (a/amount (g/p after 0) :food)))
    (is (some #(and (= (cid 172) (:card %)) (= 4 (:occupation-wood %)))
              (bot/hand-options (g/p before 0) :occupation)))
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card before 0 (cid 172) 2 {:occupation-wood 5})))))
