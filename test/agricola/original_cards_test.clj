(ns agricola.original-cards-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot] [eurozone.store :as store]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(defn e [number]
  (:id (first (filter #(and (= "Original" (:edition %)) (= "E" (:deck %))
                           (= "Base" (:pack %)) (= (str number) (:number %))) (vals c/metadata)))))
(defn trigger [state number event context]
  (g/drain (g/queue-front (own state 0 (e number)) [(g/event event 0 context)])))
(defn rich [] (-> (fresh) (update-in [:players 0] a/gain {:wood 20 :clay 20 :reed 10 :stone 10 :food 20 :grain 10 :vegetable 5})))
(defn play [state number] (g/drain (g/play-card (hand state (e number)) 0 (e number) 0 {})))
(defn reload [state] (edn/read-string (pr-str state)))
(defn ready [] (-> (rich) (assoc-in [:players 0 :improvements] #{:well :joinery}) (own 0 :a116) (own 0 :a088) (own 0 :b118) (own 0 :b126)
                    (update-in [:players 0] a/place-tile :field 0)
                    (update-in [:players 0] a/place-tile :field 1)))

(deftest original-edition-identities-and-dealable-pool
  (is (= 126 (count (filter #(and (= "Original" (:edition %)) (= "Base" (:pack %)) (= "E" (:deck %))) (vals c/metadata)))))
  (is (not= (e 13) (c/id :b013)))
  (is (= {:food 1} (:printed-cost (c/card (e 11)))))
  (is (= {:wood 2} (:printed-cost (c/card (e 24)))))
  (let [pack "original · base · e" pool (store/hand-pool 4)]
    (is (some #(= (e 184) (:id %)) pool))
    (is (not-any? #(= pack (:pack-id %)) (store/hand-pool 4 [pack])))))

(deftest printed-prerequisites-and-costs-are-enforced-before-play
  (let [state (-> (fresh) (hand (e 24)) (assoc-in [:players 0 :goods :wood] 2))]
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 (e 24) 0 {})))
    (let [qualified (reduce #(own %1 0 %2) state [:a116 :a088 :b118 :b126])
          after (g/drain (g/play-card qualified 0 (e 24) 0 {}))]
      (is (= 0 (get-in after [:players 0 :goods :wood])))
      (is (= 13 (count (get-in after [:players 0 :schedule]))))))
  (is (thrown? clojure.lang.ExceptionInfo (play (assoc-in (fresh) [:players 0 :goods :food] 0) 11)))
  (is (thrown? clojure.lang.ExceptionInfo (play (fresh) 18))))

(deftest exact-original-income-windows
  (doseq [[number round event context expected]
          [[12 7 :use-space {:space :fishing} {:food 1}]
           [12 8 :use-space {:space :fishing} {:food 2}]
           [12 8 :use-space {:space :labor} {}]
           [30 1 :use-space {:space :fishing} {:food 1 :reed 1}]
           [35 1 :use-space {:space :grain} {:grain 1}]
           [41 1 :bake {:grain-baked 3} {:food 2}]
           [54 1 :use-space {:space :labor} {:stone 3}]
           [56 1 :use-space {:space :stone-west} {:stone 1}]
           [56 1 :use-space {:space :stone-east} {:stone 1}]
           [56 1 :use-space {:space :market} {}]
           [147 1 :use-space {:space :vegetable} {:grain 1}]
           [166 1 :use-space {:space :labor} {:vegetable 1}]
           [167 1 :use-space {:space :traveling} {:grain 1}]
           [168 1 :use-space {:space :grain} {:vegetable 1}]
           [206 1 :use-space {:space :boar} {:boar 1}]
           [212 1 :use-space {:space :traveling :taken {:food 2}} {:food 2}]
           [212 1 :use-space {:space :traveling :taken {:food 5}} {}]]]
    (let [state (assoc (fresh) :round round) after (trigger state number event context)]
      (doseq [resource a/resources]
        (is (= (get expected resource 0) (delta state after resource)) (str number " " resource)))))
  (let [state (own (fresh) 0 (e 12))]
    (is (= 0 (delta state (g/drain (g/queue-front state [(g/event :use-space 1 {:space :fishing})])) :food)))))

(deftest original-basket-and-berry-picker-recognize-the-source-not-card-bonuses
  (let [state (-> (fresh) (own 0 (e 152)))
        after (g/command state 0 {:type :action :space :wood})]
    (is (= 1 (delta state after :food))))
  (let [state (-> (fresh) (own 0 (e 152)) (hand (e 16)))
        pending (g/drain (g/play-card state 0 (e 16) 0 {}))
        wood (resolve! (reload pending) {:resource :wood})
        clay (resolve! pending {:resource :clay})]
    (is (= 1 (delta state wood :food)))
    (is (= 0 (delta state clay :food)))
    (is (= [(e 16)] (get-in wood [:players 1 :hand :minor])))
    (is (empty? (get-in pending [:players 1 :hand :minor]))))
  (let [state (own (fresh) 0 (e 152))
        after (g/drain (g/queue-front state [(g/task 0 nil {:op :gain :goods {:wood 2}} {})]))]
    (is (= 0 (delta state after :food)))))

(deftest printed-schedules-only-deliver-in-future-rounds
  (doseq [[number resource n rounds]
          [[24 :food 2 [8 9 10 11 12 13 14]] [31 :food 1 [9 11 13]]
           [43 :food 1 [8 9 10 11 12 13 14]] [45 :wood 1 [8 10 12 14]]
           [46 :grain 1 [8 11 14]] [48 :reed 1 [8 9 10]] [57 :food 1 [10 11 12 13 14]]
           [187 :clay 1 [8 9 10 11 12 13 14]] [201 :cattle 1 [12]]
           [204 :sheep 1 [8 9 10]] [205 :reed 1 [8 9 10 11]]]]
    (let [state (assoc (ready) :round 7) after (play state number)
          schedule (get-in after [:players 0 :schedule])]
      (is (= (set rounds) (set (keys schedule))) (str number))
      (doseq [round rounds] (is (= {resource n} (:goods (first (get schedule round)))) (str number)))
      (let [late (play (assoc (ready) :round 14) number)]
        (is (empty? (get-in late [:players 0 :schedule])) (str number))))))

(deftest optional-harvest-exchanges-survive-save-and-never-auto-pay
  (doseq [[number resource food] [[154 :grain 3] [183 :reed 3] [214 :clay 2]]]
    (let [state (assoc-in (fresh) [:players 0 :goods resource] 1)
          pending (trigger state number :feeding-start {})]
      (is (= 1 (get-in pending [:players 0 :goods resource])))
      (is (= :exchange (get-in pending [:effect-queue 0 :effect :op])))
      (let [after (resolve! (reload pending) {})]
        (is (= 0 (get-in after [:players 0 :goods resource])))
        (is (= food (delta state after :food))))
      (is (= 1 (get-in (resolve! pending {:skip? true}) [:players 0 :goods resource])))
      (is (seq (filter #(bot/preview pending 0 %) (bot/decision-candidates pending 0))))))
  (doseq [[number sheep cattle food] [[51 2 0 0] [51 3 0 1] [51 5 0 2]
                                     [53 2 1 0] [53 3 2 2] [53 8 5 4]]]
    (let [state (-> (fresh) (assoc-in [:players 0 :goods :sheep] sheep) (assoc-in [:players 0 :goods :cattle] cattle))
          after (trigger state number :field-phase {})]
      (is (= food (delta state after :food))))))

(deftest choice-cards-validate-options-and-passing-waits-for-resolution
  (let [state (assoc-in (fresh) [:players 0 :goods :clay] 1)
        pending (play state 42)]
    (is (= 0 (get-in pending [:players 0 :goods :clay])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:resource :wood})))
    (is (= 1 (delta state (resolve! (reload pending) {:resource :stone}) :stone)))
    (is (= [(e 42)] (get-in (resolve! pending {:resource :reed}) [:players 1 :hand :minor]))))
  (let [pending (trigger (fresh) 22 :use-space {:space :fishing})]
    (is (= 1 (delta pending (resolve! pending {:resource :reed}) :reed)))
    (is (= 1 (delta pending (resolve! pending {:resource :food}) :food))))
  (let [before (trigger (assoc (fresh) :round 5) 202 :use-space {:space :labor})
        after (trigger (assoc (fresh) :round 6) 202 :use-space {:space :labor})]
    (is (thrown? clojure.lang.ExceptionInfo (resolve! before {:option 1})))
    (is (= 1 (get-in (resolve! after {:option 1}) [:players 0 :goods :vegetable]))))
  (let [market (play (assoc-in (fresh) [:players 0 :goods :grain] 1) 39)]
    (is (= 0 (get-in market [:players 0 :goods :grain])))
    (is (= 1 (get-in market [:players 0 :goods :vegetable])))
    (is (= [(e 39)] (get-in market [:players 1 :hand :minor]))))
  (let [market (play (assoc-in (fresh) [:players 0 :goods :sheep] 1) 60)]
    (is (= 0 (get-in market [:players 0 :goods :sheep])))
    (is (= 1 (get-in market [:players 0 :goods :cattle])))))

(deftest spatial-effects-and-original-construction-passives
  (let [pending (play (fresh) 11) done (resolve! (reload pending) {:cell 0})]
    (is (= 1 (get-in pending [:players 0 :goods :food])))
    (is (= :field (get-in done [:players 0 :farm 0 :type])))
    (is (= [(e 11)] (get-in done [:players 1 :hand :minor]))))
  (let [pending (play (assoc-in (fresh) [:players 0 :goods :wood] 1) 52) done (resolve! pending {:cell 0})]
    (is (true? (get-in done [:players 0 :farm 0 :stable])))
    (is (= 0 (get-in done [:players 0 :goods :wood]))))
  (doseq [[number material expected] [[13 :wood {:wood 2 :reed 2 :stone 0}] [13 :clay {:clay 5 :reed 2 :stone 0}]
                                     [218 :wood {:wood 3 :reed 2 :stone 0}] [218 :stone {:stone 3 :reed 2}]]]
    (is (= expected (a/room-cost (assoc (g/p (own (fresh) 0 (e number)) 0) :house material) {}))))
  (let [player (g/p (own (rich) 0 (e 59)) 0)
        fenced (a/build-fences player (a/edges 0))
        stable (a/build-stables fenced [0] 2)]
    (is (= 4 (:capacity (first (filter #(= :pasture (:kind %)) (a/accommodations fenced))))))
    (is (= 6 (:capacity (first (filter #(= :pasture (:kind %)) (a/accommodations stable))))))))

(deftest grocer-uses-original-stock-and-no-free-goods
  (let [state (play (rich) 184)
        goods [:reed :grain :stone :vegetable :wood :clay :reed :vegetable]
        done (reduce (fn [before resource]
                       (let [after (g/command (reload before) 0 {:type :ability :card (e 184)})]
                         (is (= 1 (delta before after resource)))
                         (is (= -1 (delta before after :food))) after)) state goods)]
    (is (thrown? clojure.lang.ExceptionInfo (g/command done 0 {:type :ability :card (e 184)})))
    (is (empty? (get-in done [:players 0 :card-state (e 184) :stock])))))

(deftest cooking-baking-and-vegetable-field-have-distinct-effects
  (let [windmill (g/p (own (rich) 0 (e 17)) 0)]
    (is (= 4 (- (a/amount (a/convert-food windmill {:grain 2}) :food) (a/amount windmill :food))))
    (is (thrown? clojure.lang.ExceptionInfo (a/bake windmill 1))))
  (let [fireplace (g/p (own (rich) 0 (e 20)) 0)]
    (is (= {:grain 1 :vegetable 2 :sheep 1 :boar 2 :cattle 3} (a/cook-rates fireplace)))
    (is (= 4 (- (a/amount (a/bake fireplace 2) :food) (a/amount fireplace :food))))
    (is (some #{(e 20)} (a/fireplace-upgrades fireplace))))
  (let [state (own (rich) 0 (e 41)) pending (play state 27)
        done (resolve! (reload pending) {:grain 3})]
    (is (= 11 (delta pending done :food)))
    (is (= 0 (delta pending (resolve! pending {:skip? true}) :food))))
  (let [state (-> (fresh) (own 0 (e 20)) (assoc-in [:players 0 :goods :grain] 2))
        played (play state 150) done (resolve! played {:grain 1})
        harvest (g/drain (g/queue-front done [(g/event :feeding-start 0)]))]
    (is (= :bake (get-in harvest [:effect-queue 0 :effect :op])))
    (is (= 2 (delta harvest (resolve! harvest {:grain 1}) :food))))
  (let [state (play (ready) 18) cid (e 18) player (g/p state 0)
        sown (a/sow player [{:cell cid :crop :vegetable}])]
    (is (= 2 (get-in sown [:card-fields cid :count])))
    (is (thrown? clojure.lang.ExceptionInfo (a/sow player [{:cell cid :crop :grain}])))
    (is (= (get-in (a/score player) [:breakdown :fields]) (get-in (a/score sown) [:breakdown :fields])))))

(deftest original-scoring-thresholds
  (doseq [[n expected] [[0 0] [1 3] [2 6] [3 6]]]
    (is (= expected (g/card-bonus (assoc-in (own (fresh) 0 (e 153)) [:players 0 :begging] n) 0 (e 153)))))
  (doseq [[house clogs builder] [[:wood 0 2] [:clay 1 0] [:stone 2 0]]]
    (let [state (assoc-in (fresh) [:players 0 :house] house)]
      (is (= clogs (g/card-bonus state 0 (e 28))))
      (is (= builder (g/card-bonus state 0 (e 177))))))
  (let [state (play (fresh) 174) later (play (play state 218) 175)]
    (is (= 0 (g/card-bonus state 0 (e 174))))
    (is (= 2 (g/card-bonus later 0 (e 174)))))
  (doseq [[n expected] [[4 0] [5 1] [6 3] [7 5] [8 7] [9 9] [10 9]]]
    (let [ids (take n (keys a/improvements)) state (assoc-in (fresh) [:players 0 :improvements] (set ids))]
      (is (= expected (g/card-bonus state 0 (e 197))))))
  (doseq [[n expected] [[5 0] [6 1] [7 2] [8 3] [9 4] [10 4]]]
    (with-redefs [a/pasture-cells (constantly (set (range n)))]
      (is (= expected (g/card-bonus (fresh) 0 (e 23)))))))

(deftest stone-house-round-start-plow-is-optional-paid-and-persistent
  (let [state (-> (fresh) (own 0 (e 194)) (assoc-in [:players 0 :house] :stone)
                  (assoc-in [:players 0 :goods :food] 1))
        pending (g/drain (g/queue-front state [(g/event :round-start 0)]))
        done (resolve! (reload pending) {:cell 0})]
    (is (= :plow (get-in pending [:effect-queue 0 :effect :op])))
    (is (= 1 (get-in pending [:players 0 :goods :food])))
    (is (= 0 (get-in done [:players 0 :goods :food])))
    (is (= :field (get-in done [:players 0 :farm 0 :type])))
    (is (= :empty (get-in (resolve! pending {:skip? true}) [:players 0 :farm 0 :type])))
    (is (seq (filter #(bot/preview pending 0 %) (bot/decision-candidates pending 0)))))
  (is (empty? (:effect-queue (trigger (fresh) 194 :round-start {})))))

(deftest stablehand-builds-once-per-turn-including-after-mini-pasture
  (let [state (own (rich) 0 (e 207))
        pasture (play state 40)
        stable (resolve! pasture {:cell 0})
        done (resolve! (reload stable) {:cell 1})
        same-turn (g/drain (g/queue-front done [(g/event :fence 0 {:fences-built 3})]))
        next-turn (g/drain (g/queue-front (update done :turn-serial (fnil inc 0)) [(g/event :fence 0 {:fences-built 3})]))]
    (is (= :stable (get-in stable [:effect-queue 0 :effect :op])))
    (is (true? (get-in done [:players 0 :farm 1 :stable])))
    (is (= 0 (delta state done :wood)))
    (is (= 1 (count (filter :stable (get-in same-turn [:players 0 :farm])))))
    (is (empty? (:effect-queue same-turn)))
    (is (= :stable (get-in next-turn [:effect-queue 0 :effect :op])))
    (is (true? (get-in (resolve! next-turn {:cell 2}) [:players 0 :farm 2 :stable])))))

(deftest complete-original-e-deck-is-dealable-with-native-rules
  (let [printed (set (for [[cid card] c/metadata
                          :when (and (= "Original" (:edition card)) (= "Base" (:pack card)) (= "E" (:deck card)))] cid))
        enabled (set (for [[cid card] c/cards
                          :when (and (= "Original" (:edition card)) (= "Base" (:pack card)) (= "E" (:deck card)))] cid))
        pool (filter #(= "original · base · e" (:pack-id %)) (store/hand-pool 5))]
    (is (= printed enabled))
    (is (= 126 (count enabled)))
    (is (= {:minor 53 :occupation 73} (frequencies (map :kind pool))))
    (doseq [card pool]
      (is (seq (c/original-definitions (:id card))) (str "Native rules for " (:name card))))))
