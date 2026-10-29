(ns agricola.artifex-interactions-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot] [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(defn rich [state] (update-in state [:players 0] a/gain {:food 20 :wood 30 :clay 30 :reed 20 :stone 30}))
(defn field [state cell crop n]
  (assoc-in state [:players 0 :farm cell] {:type :field :crop crop :count n}))
(defn fence [state cells]
  (let [edges (->> cells (mapcat a/edges) frequencies (filter #(= 1 (val %))) (mapv key))]
    (update-in state [:players 0] a/build-fences edges {:free-fences (count edges)})))
(defn emit [state event actor context]
  (g/drain (g/queue-front state [(g/event event actor context)])))

(deftest wooden-shed-needs-an-authenticated-major-action
  (let [state (-> (fresh) rich (hand :a010) (assoc :active (conj (:active (fresh)) :improvement)))
        shed (g/command state 0 {:type :action :space :improvement :card (c/id :a010)})]
    (is (= 3 (a/family-capacity (g/p shed 0))))
    (is (= 2 (count (a/tiles (g/p shed 0) :room))))
    (is (= 3 (:family (a/grow (g/p shed 0) true))))
    (is (thrown? clojure.lang.ExceptionInfo (a/renovate (g/p shed 0))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/command state 0 {:type :action :space :meeting :card (c/id :a010) :major-action? true})))
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 (c/id :a010) 0 {:major-action? true})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/play-card (assoc-in state [:players 0 :house] :clay) 0 (c/id :a010) 0 {} {:major-action? true})))
    (let [scholar (-> state (own 0 :b097) (assoc-in [:players 0 :house] :stone) g/begin-round g/drain)]
      (is (thrown? clojure.lang.ExceptionInfo (resolve! scholar {:card (c/id :a010) :major-action? true}))))))

(deftest homekeeper-tests-one-rooms-actual-adjacency
  (let [state (-> (fresh) (own 0 :a085) (field 0 nil 0) (fence [6]))
        wood (g/p state 0) clay (assoc wood :house :clay)]
    (is (= 2 (a/family-capacity wood)))
    (is (= 3 (a/family-capacity clay)))
    (is (= 3 (:family (a/grow clay true))))
    (is (thrown? clojure.lang.ExceptionInfo (a/grow (assoc clay :family 3) true))))
  (let [state (-> (fresh) (own 0 :a085) (field 0 nil 0) (fence [11]) (assoc-in [:players 0 :house] :stone))]
    (is (= 2 (a/family-capacity (g/p state 0))) "Different rooms adjacent to field/pasture do not qualify")
    (is (= 2 (a/family-capacity (-> (g/p state 0) (assoc-in [:farm 0] {:type :empty})
                                    (assoc :card-fields {(c/id :b068) {:type :field :count 0}})))))))

(deftest mud-patch-provides-species-restricted-field-tile-housing
  (doseq [organic? [false true]]
    (let [state (cond-> (-> (fresh) (own 0 :a011) (field 0 nil 0) (field 1 :grain 1)) organic? (own 0 :b098))
          p (g/p state 0)
          two-boars (a/gain p {:boar 2})
          two-sheep (a/gain p {:sheep 2})]
      (is (some? (a/animal-housing two-boars)))
      (is (nil? (a/animal-housing two-sheep)))
      (is (nil? (a/animal-housing (a/gain p {:boar 3}))))
      (is (nil? (a/animal-housing (-> two-boars (a/gain {:grain 1}) (a/sow [{:cell 0 :crop :grain}])))))
      (is (some? (a/animal-housing (a/field-harvest (a/gain p {:boar 3})))))))
  (let [state (-> (fresh) (hand :a011)) played (g/drain (g/play-card state 0 (c/id :a011) 0 {}))]
    (is (= 1 (a/amount (g/p played 0) :boar)))))

(deftest carpenters-hammer-discounts-the-batch-once-and-combines-with-other-discounts
  (doseq [[house material reed] [[:wood 8 2] [:clay 7 2] [:stone 6 2]]]
    (let [state (-> (fresh) (own 0 :a014) (assoc-in [:players 0 :house] house))
          p (-> (g/p state 0) (a/gain {house material :reed reed}))
          built (a/build-rooms p [0 1])]
      (is (= 4 (count (a/tiles built :room))))
      (is (= 0 (a/amount built house))) (is (= 0 (a/amount built :reed)))
      (is (thrown? clojure.lang.ExceptionInfo (a/build-rooms (a/pay p {house 1}) [0 1])))))
  (let [p (-> (fresh) (own 0 :a014) (g/p 0) (a/gain {:wood 13 :reed 4}))]
    (is (= 8 (a/amount (a/build-rooms p [0]) :wood)) "A single room gets no batch discount")
    (is (= 0 (a/amount (a/build-rooms p [0 1 2]) :wood)))
    (is (= 0 (a/amount (a/build-rooms p [0 1 2]) :reed))))
  (let [p (-> (fresh) (own 0 :a014) (own 0 :a143) (own 0 :b126) (g/p 0)
              (assoc :house :stone) (a/gain {:reed 2}))
        built (a/build-rooms p [0 1])]
    (is (= 0 (a/amount built :stone))) (is (= 4 (count (a/tiles built :room))))))

(deftest shelter-and-carpenters-axe-require-legal-stable-sites
  (let [state (-> (fresh) (fence [4]) (hand :a001))
        pending (g/drain (g/play-card state 0 (c/id :a001) 0 {}))]
    (is (= :stable (get-in pending [:effect-queue 0 :effect :op])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:cell 3})))
    (is (true? (get-in (resolve! (edn/read-string (pr-str pending)) {:cell 4}) [:players 0 :farm 4 :stable]))))
  (let [state (-> (fresh) (fence [3 4]) (hand :a001))]
    (is (empty? (:effect-queue (g/drain (g/play-card state 0 (c/id :a001) 0 {}))))))
  (doseq [[wood offered?] [[3 false] [4 true]]]
    (let [state (-> (fresh) (own 0 :a015) (assoc-in [:players 0 :goods :wood] wood))
          pending (g/command state 0 {:type :action :space :wood})]
      (is (= offered? (boolean (seq (:effect-queue pending)))))
      (when offered?
        (let [done (resolve! pending {:cell 4})]
          (is (= 6 (a/amount (g/p done 0) :wood)))
          (is (true? (get-in done [:players 0 :farm 4 :stable]))))))))

(deftest free-renovation-is-immediate-optional-and-cannot-be-forged
  (let [state (-> (fresh) (hand :a013) (assoc-in [:players 0 :goods :wood] 4))
        pending (g/drain (g/play-card state 0 (c/id :a013) 0 {}))
        done (resolve! pending {}) declined (resolve! pending {:skip? true})]
    (is (= :wood (:house (g/p declined 0))))
    (is (= :clay (:house (g/p done 0))))
    (is (= 3 (a/amount (g/p done 0) :clay)))
    (is (= 0 (a/amount (g/p done 0) :wood)))
    (is (thrown? clojure.lang.ExceptionInfo (a/renovate (g/p (fresh) 0) {:free? true})))
    (is (empty? (:effect-queue done)))
    (is (some #(= :resolve (:type %)) (bot/decision-candidates pending 0))))
  (let [state (-> (fresh) (own 0 :a087) (hand :a013) (assoc-in [:players 0 :goods :wood] 4))
        pending (g/drain (g/play-card state 0 (c/id :a013) 0 {}))]
    (is (= :stone (:house (g/p (resolve! pending {:renovate-to :stone}) 0)))))
  (let [state (-> (fresh) (own 0 :a010) (hand :a013) (assoc-in [:players 0 :goods :wood] 4))
        done (g/drain (g/play-card state 0 (c/id :a013) 0 {}))]
    (is (= :wood (:house (g/p done 0)))))
  (is (thrown? clojure.lang.ExceptionInfo (g/play-card (-> (fresh) rich (hand :a013) (assoc-in [:players 0 :house] :clay)) 0 (c/id :a013) 0 {}))))

(deftest payments-to-the-acting-player-are-atomic-and-cannot-be-redirected
  (doseq [[code space cost owner-gain payment]
          [[:a154 :fishing {:grain 1} {} {:grain 1}]
           [:a156 :reed {:food 1} {:reed 1} {:food 1}]
           [:a159 :fishing {:wood 1} {:food 2} {:wood 1}]
           [:a159 :reed {:wood 1} {:food 3} {:wood 1}]]]
    (let [state (-> (fresh) (own 0 code) (update-in [:players 0] a/gain cost) (assoc :turn 1))
          pending (g/command state 1 {:type :action :space space})
          skipped (resolve! pending {:skip? true})
          done (resolve! (edn/read-string (pr-str pending)) {:actor 2 :player 2})]
      (is (= 0 (g/acting-player pending)))
      (doseq [r a/resources]
        (is (= (- (get owner-gain r 0) (get cost r 0)) (delta pending done r)) (str code " " r))
        (is (= (get payment r 0) (- (a/amount (g/p done 1) r) (a/amount (g/p pending 1) r))))
        (is (= (a/amount (g/p pending 2) r) (a/amount (g/p done 2) r))))
      (is (= (:goods (g/p pending 0)) (:goods (g/p skipped 0))))
      (when (= code :a154) (is (= 1 (get-in done [:players 0 :bonus-points])))))))

(deftest buyer-pays-before-receiving-the-new-animal
  (let [state (-> (fresh) (own 0 :a156) (assoc :turn 1 :active (conj (:active (fresh)) :sheep))
                  (assoc-in [:players 0 :goods :food] 0) (assoc-in [:piles :sheep] {:sheep 1}))
        no-money (g/command state 1 {:type :action :space :sheep})]
    (is (empty? (:effect-queue no-money)))
    (is (= 0 (a/amount (g/p no-money 0) :sheep))))
  (let [state (-> (fresh) (own 0 :a156) (assoc :turn 1 :active (conj (:active (fresh)) :sheep))
                  (assoc-in [:piles :sheep] {:sheep 1}))
        pending (g/command state 1 {:type :action :space :sheep})
        paid (resolve! pending {})]
    (is (= 1 (a/amount (g/p paid 0) :sheep)))
    (is (= 1 (a/amount (g/p paid 1) :sheep)))
    (is (= 0 (get-in paid [:piles :sheep :sheep])))))

(deftest wood-worker-can-fund-shifting-cultivator-with-cooked-sheep
  (let [state (-> (fresh) (own 0 :a091) (own 0 :a164)
                  (assoc-in [:players 0 :goods :food] 1) (assoc-in [:players 0 :improvements] #{:hearth-4}))
        pending (g/command state 0 {:type :action :space :wood})
        sheep (resolve! pending {})
        funded (g/command sheep 0 {:type :convert :goods {:sheep 1}})
        plowed (resolve! funded {:cell 4})]
    (is (= (c/id :a164) (get-in pending [:effect-queue 0 :card])))
    (is (= (c/id :a091) (get-in sheep [:effect-queue 0 :card])))
    (is (= 0 (a/amount (g/p plowed 0) :food)))
    (is (= :field (get-in plowed [:players 0 :farm 4 :type])))))

(deftest harvest-effects-include-last-crops-and-optional-extra-harvests
  (let [state (-> (fresh) (own 0 :a106) (own 0 :a112)
                  (field 0 :grain 2) (field 1 :vegetable 1)
                  (assoc-in [:players 0 :card-fields (c/id :b068)] {:type :field :crop :vegetable :count 1}))
        pending (g/drain (g/continue-step state :field-harvest))
        after (resolve! pending {:fields [0]})]
    (is (= :extra-grain-harvest (get-in pending [:effect-queue 0 :effect :op])))
    (is (= 2 (delta state pending :food)))
    (is (= 4 (delta state after :food)))
    (is (= 0 (get-in after [:players 0 :farm 0 :count])))
    (is (= :feeding (:phase after)))))

(deftest beer-keg-runs-in-feeding-and-pays-exactly-once
  (doseq [[option grain points] [[0 1 0] [1 2 1] [2 3 2]]]
    (let [state (-> (fresh) (own 0 :a062) (assoc-in [:players 0 :goods :grain] grain))
          pending (g/drain (g/continue-step state :feeding))
          after (resolve! pending {:option option})]
      (is (= :feeding (:phase pending)))
      (is (= 3 (delta pending after :food)))
      (is (= 0 (a/amount (g/p after 0) :grain)))
      (is (= points (get-in after [:players 0 :bonus-points])))
      (is (empty? (:effect-queue after)))))
  (let [pending (g/drain (g/continue-step (own (fresh) 0 :a062) :feeding))]
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:option 2})))
    (is (empty? (:effect-queue (resolve! pending {:skip? true}))))))

(deftest reed-bank-income-requires-nonempty-pile-before-replenishing
  (doseq [[reed expected] [[0 0] [1 1] [5 1]]]
    (let [state (-> (fresh) (own 0 :a049) (assoc-in [:piles :reed :reed] reed))
          after (g/drain (g/begin-round state))]
      (is (= expected (delta state after :food)))
      (is (= (inc reed) (get-in after [:piles :reed :reed]))))))

(deftest vegetable-slicer-only-rewards-an-actual-fireplace-upgrade
  (let [state (-> (fresh) rich (own 0 :a041) (assoc :active (conj (:active (fresh)) :improvement))
                  (assoc-in [:players 0 :improvements] #{:fireplace-2})
                  (update :available-improvements disj :fireplace-2))
        upgraded (g/command state 0 {:type :action :space :improvement :improvement :hearth-4 :upgrade :fireplace-2})
        bought (g/command state 0 {:type :action :space :improvement :improvement :hearth-4})]
    (is (= 2 (delta state upgraded :wood))) (is (= 1 (delta state upgraded :vegetable)))
    (is (= 0 (delta state bought :wood))) (is (= 0 (delta state bought :vegetable)))
    (is (contains? (:available-improvements upgraded) :fireplace-2)))
  (let [state (-> (fresh) rich (own 0 :a041) (hand :a044) (own 0 :a116) (own 0 :a112))
        after (g/command state 0 {:type :action :space :meeting :card (c/id :a044) :upgrade :fireplace-2})]
    (is (= -1 (delta state after :wood)))
    (is (= 0 (delta state after :vegetable)))))

(deftest facade-carving-is-limited-by-completed-harvests
  (let [state (-> (fresh) rich (hand :a036) (assoc :round 7))
        pending (g/drain (g/play-card state 0 (c/id :a036) 0 {}))]
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:option 1})))
    (is (= 1 (get-in (resolve! pending {:option 0}) [:players 0 :bonus-points]))))
  (let [state (-> (fresh) rich (hand :a036) (assoc :round 8))
        pending (g/drain (g/play-card state 0 (c/id :a036) 0 {}))]
    (is (= 2 (get-in (resolve! pending {:option 1}) [:players 0 :bonus-points])))
    (is (= -2 (delta pending (resolve! pending {:option 1}) :food)))))

(deftest returning-home-bonuses-use-owners-and-newborns
  (doseq [[owner newborn points] [[0 1 2] [1 1 0] [0 0 0]]]
    (let [state (-> (fresh) (own 0 :a035) (assoc :occupied {:fishing owner})
                    (assoc-in [:players 0 :newborn] newborn))
          after (emit state :return-home 0 {})]
      (is (= points (get-in after [:players 0 :bonus-points])))))
  (let [state (-> (fresh) (own 0 :a100) (assoc :occupied {:wood 0 :clay 0 :reed 0}))
        pending (emit state :return-home 0 {})]
    (is (= 1 (get-in (resolve! pending {}) [:players 0 :bonus-points])))
    (is (empty? (:effect-queue (emit (assoc state :occupied {:wood 0 :clay 0 :labor 0}) :return-home 0 {})))))
  (let [state (-> (fresh) (own 0 :a157))
        all-used (assoc state :occupied {:lessons 0 :lessons-extra 1})]
    (is (= 1 (delta state (emit state :return-home 0 {}) :food)))
    (is (= 0 (delta all-used (emit all-used :return-home 0 {}) :food)))))

(deftest resource-takers-use-the-pile-and-actual-person-number
  (doseq [[food resource] [[1 :vegetable] [2 :grain] [3 :reed] [7 :reed]]]
    (let [state (-> (fresh) (own 0 :a103) (assoc-in [:piles :fishing] {:food food}))
          after (g/command state 0 {:type :action :space :fishing})]
      (is (= 1 (delta state after resource)))))
  (doseq [[person wood food] [[1 5 1] [2 4 1] [3 3 1] [1 4 0] [4 2 0]]]
    (let [state (-> (fresh) (own 0 :a107) (assoc-in [:players 0 :family] 5)
                    (assoc-in [:players 0 :remaining] (- 6 person)) (assoc-in [:piles :wood] {:wood wood}))
          after (g/command state 0 {:type :action :space :wood})]
      (is (= food (delta state after :food)))))
  (doseq [[person resource] [[1 :wood] [2 :clay] [3 :reed] [4 :stone] [5 :stone]]]
    (let [state (-> (fresh) (own 0 :a163) (assoc-in [:players 0 :family] 5)
                    (assoc-in [:players 0 :remaining] (- 6 person)))
          plain (g/command (assoc-in state [:players 0 :occupations] []) 0 {:type :action :space :market :choice :reed})
          after (g/command state 0 {:type :action :space :market :choice :reed})]
      (is (= 1 (delta plain after resource))))))

(deftest other-player-choice-effects-validate-resources-and-scope
  (doseq [[actor reed offered?] [[0 2 true] [1 2 true] [1 1 false]]]
    (let [state (-> (fresh) (own 0 :a142) (assoc :turn actor) (assoc-in [:piles :reed] {:reed reed}))
          pending (g/command state actor {:type :action :space :reed})]
      (is (= offered? (boolean (seq (:effect-queue pending)))))
      (when offered?
        (is (= 1 (delta pending (resolve! pending {:option 0}) :grain)))
        (is (= 1 (delta pending (resolve! pending {:option 1}) :vegetable))))))
  (doseq [[option resource food] [[0 :grain 4] [1 :sheep 5] [2 :vegetable 7]]]
    (let [state (-> (fresh) (own 0 :a158) (assoc :turn 1) (update-in [:players 0] a/gain {resource 1}))
          pending (g/command state 1 {:type :action :space :traveling})
          after (resolve! pending {:option option})]
      (is (= food (delta pending after :food)))
      (is (= -1 (delta pending after resource))))))

(deftest catcher-tests-the-original-pile-when-part-time-worker-takes-half
  (let [state (-> (g/initialize (a/new-game ["A" "B" "C" "D" "E"] (vec (mapcat identity a/stages)) 0))
                  (own 0 :a107) (own 0 :d180) (assoc-in [:players 0 :remaining] 1)
                  (assoc-in [:piles :wood] {:wood 4}))
        after (g/command state 0 {:type :action :space :wood :part-time? true})]
    (is (= 2 (delta state after :wood)))
    (is (= 1 (delta state after :food)))
    (is (= 2 (get-in after [:piles :wood :wood])))))

(deftest generic-wood-accumulation-effects-include-five-player-riverbank
  (doseq [[code choice wood food sheep returned]
          [[:a015 {:cell 4} 0 0 0 0]
           [:a052 nil 1 2 0 0]
           [:a091 {:cell 4} 1 -3 0 0]
           [:a164 {} 0 0 1 1]
           [:a056 {} -1 3 0 2]
           [:a108 {} 0 2 0 1]
           [:a116 nil 2 0 0 0]
           [:b108 {:grain 1} 1 2 0 0]]]
    (let [state (-> (g/initialize (a/new-game ["A" "B" "C" "D" "E"] (vec (mapcat identity a/stages)) 0))
                    (own 0 code) (update-in [:players 0] a/gain {:wood 6 :food 5 :grain 1})
                    (assoc-in [:players 0 :improvements] #{:fireplace-2})
                    (assoc-in [:piles :boar] {:boar 1}))
          pending (g/command state 0 {:type :action :space :riverbank})
          done (if (some? choice) (resolve! pending choice) pending)]
      (testing (str code " on Riverbank Forest")
        (when (some? choice) (is (= (c/id code) (get-in pending [:effect-queue 0 :card]))))
        (is (= wood (delta state done :wood)))
        (is (= food (delta state done :food)))
        (is (= sheep (delta state done :sheep)))
        (is (= 1 (delta state done :reed)) "Riverbank's reed bonus is preserved")
        (is (= returned (get-in done [:piles :riverbank :wood])))
        (when (= code :a015) (is (true? (get-in done [:players 0 :farm 4 :stable]))))
        (when (= code :a091) (is (= :field (get-in done [:players 0 :farm 4 :type])))))))
  (let [state (-> (g/initialize (a/new-game ["A" "B" "C" "D" "E"] (vec (mapcat identity a/stages)) 0))
                  (own 0 :a042))
        done (g/command state 0 {:type :action :space :riverbank})]
    (is (= 0 (delta state done :food)) "Forest Lake Hut names Forest specifically; Riverbank is a different space")))
