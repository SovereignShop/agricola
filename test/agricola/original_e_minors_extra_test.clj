(ns agricola.original-e-minors-extra-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))

(def half-house :catalog/original-base-e-21-half-timbered-house)
(def bakers-oven :catalog/original-base-e-14-baker-s-oven)
(def wood-oven :catalog/original-base-e-27-wood-fired-oven)
(def spices :catalog/original-base-e-25-spices)
(def potato :catalog/original-base-e-32-potato-dibber)
(def basket :catalog/original-base-e-34-basket)
(def mushroom :catalog/original-base-e-196-mushroom-collector)
(def outhouse :catalog/original-base-e-44-outhouse)
(def lettuce :catalog/original-base-e-47-lettuce-patch)
(def desk :catalog/original-base-e-49-writing-desk)
(def extension :catalog/original-base-e-55-stone-house-extension)
(def pellets :catalog/original-base-e-338-feed-pellets)
(defn reload [state] (edn/read-string (pr-str state)))
(defn op [state] (get-in state [:effect-queue 0 :effect :op]))
(defn event! [state event context] (g/drain (g/queue-front state [(g/event event 0 context)])))

(deftest bakers-oven-requires-an-oven-not-just-a-baking-improvement
  (let [state (-> (fresh) (hand bakers-oven)
                  (assoc-in [:players 0 :improvements] #{:fireplace-2}))]
    (is (not (g/prerequisites? state 0 (c/card bakers-oven))))
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 bakers-oven 0 {})))
    (let [with-oven (update-in state [:players 0 :improvements] conj :clay-oven)]
      (is (g/prerequisites? with-oven 0 (c/card bakers-oven)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (g/play-card with-oven 0 bakers-oven 0 {:return-improvement :fireplace-2}))))))

(deftest bakers-oven-returns-majors-to-supply-and-removes-minor-ovens
  (let [state (-> (fresh) (hand bakers-oven) (own 0 wood-oven)
                  (assoc-in [:players 0 :improvements] #{:clay-oven :stone-oven})
                  (update :available-improvements disj :clay-oven :stone-oven))]
    (doseq [returned [:clay-oven :stone-oven wood-oven]]
      (let [done (g/drain (g/play-card state 0 bakers-oven 0 {:return-improvement returned}))]
        (is (c/has? (g/p done 0) bakers-oven))
        (is (not (some #{returned} (a/owned-improvement-ids (g/p done 0)))))
        (if (a/improvements returned)
          (is (contains? (:available-improvements done) returned))
          (do (is (not (contains? (:available-improvements done) returned)))
              (is (= [wood-oven] (:removed-cards done)))))))
    (is (= #{:clay-oven :stone-oven wood-oven}
           (set (keep :return-improvement (bot/hand-options (g/p state 0) :minor)))))))

(deftest bakers-oven-immediate-bake-uses-the-new-oven-and-other-retained-ovens
  (let [state (-> (fresh) (hand bakers-oven) (own 0 wood-oven)
                  (assoc-in [:players 0 :improvements] #{:clay-oven})
                  (assoc-in [:players 0 :goods :grain] 3))
        pending (g/drain (g/play-card state 0 bakers-oven 0 {:return-improvement :clay-oven}))
        done (resolve! (reload pending) {:grain 3})]
    (is (= :bake (op pending)))
    (is (= 13 (delta state done :food)))
    (is (= 0 (get-in done [:players 0 :goods :grain])))
    (is (= 3 (get-in (resolve! pending {:skip? true}) [:players 0 :goods :grain]))))
  (let [player (-> (fresh) (own 0 bakers-oven) (assoc-in [:players 0 :goods :grain] 3) (g/p 0))]
    (is (= 10 (- (a/amount (a/bake player 2) :food) (a/amount player :food))))
    (is (thrown? clojure.lang.ExceptionInfo (a/bake player 3)))))

(deftest half-timbered-house-does-not-stack-with-mansion
  (let [state (own (fresh) 0 half-house)]
    (doseq [house [:wood :clay]]
      (is (zero? (g/card-bonus (assoc-in state [:players 0 :house] house) 0 half-house))))
    (let [stone (assoc-in state [:players 0 :house] :stone)]
      (is (= 2 (g/card-bonus stone 0 half-house)))
      ;; Ownership matters even before the separate Mansion definition is enabled.
      (is (zero? (g/card-bonus (update-in stone [:players 0 :minors] conj :catalog/original-base-k-144-mansion)
                              0 half-house))))))

(deftest potato-dibber-adds-one-to-each-vegetable-stack-including-field-cards
  (let [state (-> (fresh) (own 0 potato)
                  (assoc-in [:players 0 :goods :vegetable] 3)
                  (assoc-in [:players 0 :goods :grain] 1)
                  (update-in [:players 0] a/place-tile :field 0)
                  (update-in [:players 0] a/place-tile :field 1)
                  (assoc-in [:players 0 :card-fields lettuce] {:type :field :allowed-crop :vegetable :count 0}))
        player (a/sow (g/p state 0) [{:cell 0 :crop :vegetable} {:cell 1 :crop :grain}
                                    {:cell lettuce :crop :vegetable}])]
    (is (= [3 3 3] [(get-in player [:farm 0 :count]) (get-in player [:farm 1 :count])
                     (get-in player [:card-fields lettuce :count])]))
    (is (= 1 (a/amount player :vegetable)))
    (is (= 0 (a/amount player :grain)))
    (is (thrown? clojure.lang.ExceptionInfo (a/sow player [{:cell 0 :crop :vegetable}])))))

(deftest basket-returns-only-taken-wood-and-waits-for-a-saved-choice
  (let [state (-> (fresh) (own 0 basket) (assoc-in [:players 0 :goods :wood] 10)
                  (assoc-in [:piles :wood] {:wood 2}))
        pending (g/command state 0 {:type :action :space :wood})
        done (resolve! (reload pending) {})]
    (is (= :return-goods (op pending)))
    (is (= 12 (get-in pending [:players 0 :goods :wood])))
    (is (= 10 (get-in done [:players 0 :goods :wood])))
    (is (= 3 (delta state done :food)))
    (is (= 2 (get-in done [:piles :wood :wood])))
    (is (= 12 (get-in (resolve! pending {:skip? true}) [:players 0 :goods :wood])))
    (is (seq (filter #(and (= :resolve (:type %)) (not (:skip? %))) (bot/decision-candidates pending 0)))))
  (let [state (-> (fresh) (own 0 basket) (own 0 :a116)
                  (assoc-in [:players 0 :goods :wood] 10) (assoc-in [:piles :wood] {:wood 1}))
        done (g/command state 0 {:type :action :space :wood})]
    (is (empty? (:effect-queue done)))
    (is (= 12 (get-in done [:players 0 :goods :wood])))
    (is (= 0 (delta state done :food)))))

(deftest basket-and-mushroom-collector-cannot-return-the-same-source-wood
  (let [state (-> (fresh) (own 0 basket) (own 0 mushroom)
                  (assoc-in [:players 0 :goods :wood] 20) (assoc-in [:piles :wood] {:wood 2}))
        pending (g/command state 0 {:type :action :space :wood})
        basket-first (g/command pending 0 {:type :order-effect :card basket})
        done (resolve! (reload basket-first) {})]
    (is (empty? (:effect-queue done)))
    (is (= 3 (delta state done :food)))
    (is (= 2 (get-in done [:piles :wood :wood]))))
  (let [state (-> (fresh) (own 0 basket) (own 0 mushroom)
                  (assoc-in [:players 0 :goods :wood] 20) (assoc-in [:piles :wood] {:wood 3}))
        pending (g/command state 0 {:type :action :space :wood})
        basket-first (g/command pending 0 {:type :order-effect :card basket})
        done (resolve! (resolve! basket-first {}) {})]
    (is (= 5 (delta state done :food)))
    (is (= 3 (get-in done [:piles :wood :wood])))))

(deftest outhouse-tests-other-players-and-solo-self
  (let [state (-> (fresh) (own 0 :a116) (own 0 :a088)) card (c/card outhouse)]
    (is (g/prerequisites? state 0 card))
    (is (g/prerequisites? (-> state (own 1 :a116) (own 1 :a088)) 0 card)
        "Only one other player needs fewer than two occupations.")
    (let [full (reduce #(-> %1 (own %2 :a116) (own %2 :a088)) state [1 2 3])]
      (is (not (g/prerequisites? full 0 card)))
      (is (not (g/prerequisites? (assoc-in full [:players 0 :occupations] []) 0 card))))
    (is (not (g/prerequisites? (update state :players #(subvec % 0 1)) 0 card)))
    (is (g/prerequisites? (-> state (update :players #(subvec % 0 1))
                             (assoc-in [:players 0 :occupations] [(c/id :a116)])) 0 card))))

(deftest lettuce-patch-converts-only-its-just-harvested-vegetable
  (let [state (-> (fresh) (own 0 lettuce)
                  (assoc-in [:players 0 :goods :vegetable] 0)
                  (assoc-in [:players 0 :card-fields lettuce] {:type :field :allowed-crop :vegetable :crop :vegetable :count 1}))
        pending (g/drain (g/continue-step state :field-harvest))
        done (resolve! (reload pending) {})]
    (is (= :exchange (op pending)))
    (is (= 1 (get-in pending [:players 0 :goods :vegetable])))
    (is (= 0 (get-in done [:players 0 :goods :vegetable])))
    (is (= 4 (delta state done :food)))
    (is (= 0 (get-in done [:players 0 :card-fields lettuce :count])))
    (is (= 1 (get-in (resolve! pending {:skip? true}) [:players 0 :goods :vegetable]))))
  (let [state (-> (fresh) (own 0 lettuce) (assoc-in [:players 0 :goods :vegetable] 2))]
    (is (empty? (:effect-queue (event! state :harvest-crop {:cell 0 :crop :vegetable}))))
    (is (empty? (:effect-queue (event! state :harvest-crop {:cell lettuce :crop :grain}))))))

(deftest writing-desk-plays-second-occupation-once-after-the-first
  (let [state (-> (fresh) (own 0 desk) (hand :a116) (hand :a088)
                  (assoc-in [:players 0 :goods :food] 2))
        pending (g/command state 0 {:type :action :space :lessons :card (c/id :a116)})
        done (resolve! (reload pending) {:card (c/id :a088)})]
    (is (= :card-action (op pending)))
    (is (= [(c/id :a116)] (get-in pending [:players 0 :occupations])))
    (is (= 2 (get-in pending [:players 0 :goods :food])))
    (is (= [(c/id :a116) (c/id :a088)] (get-in done [:players 0 :occupations])))
    (is (= 0 (get-in done [:players 0 :goods :food])))
    (is (empty? (:effect-queue done)))
    (is (= 1 (:turn done)))
    (is (= [(c/id :a116)] (get-in (resolve! pending {:skip? true}) [:players 0 :occupations])))
    (is (some #(= (c/id :a088) (:card %)) (bot/decision-candidates pending 0))))
  (let [state (-> (fresh) (own 0 desk) (hand :a116) (hand :a088))
        played (g/drain (g/play-card state 0 (c/id :a116) 0 {}))]
    (is (empty? (:effect-queue played)) "A granted occupation does not trigger the desk.")))

(deftest stone-house-extension-is-a-single-free-room-after-printed-card-cost
  (let [state (-> (fresh) (hand extension)
                  (assoc-in [:players 0 :goods :stone] 3) (assoc-in [:players 0 :goods :reed] 1))]
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 extension 0 {})))
    (let [stone (assoc-in state [:players 0 :house] :stone)
          pending (g/drain (g/play-card stone 0 extension 0 {}))
          done (resolve! (reload pending) {:rooms [0]})]
      (is (= :build-rooms (op pending)))
      (is (= 0 (get-in pending [:players 0 :goods :stone])))
      (is (= 0 (get-in pending [:players 0 :goods :reed])))
      (is (empty? (get-in pending [:players 1 :hand :minor])))
      (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:rooms [0 1]})))
      (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:skip? true})))
      (is (= 3 (count (a/tiles (g/p done 0) :room))))
      (is (= [extension] (get-in done [:players 1 :hand :minor])))
      (is (some #(= [0] (:rooms %)) (bot/decision-candidates pending 0))))))

(deftest feed-pellets-has-a-saveable-optional-choice-restricted-to-owned-species
  (let [state (-> (fresh) (own 0 pellets)
                  (assoc-in [:players 0 :goods :wood] 4)
                  (update-in [:players 0] a/build-fences (a/edges 0))
                  (assoc-in [:players 0 :goods :sheep] 1)
                  (assoc-in [:players 0 :goods :vegetable] 1))
        pending (event! state :feeding-start {})
        done (resolve! (reload pending) {:option 0})]
    (is (= :choose (op pending)))
    (is (= 1 (get-in pending [:players 0 :goods :vegetable])))
    (is (= 2 (get-in done [:players 0 :goods :sheep])))
    (is (= 0 (get-in done [:players 0 :goods :vegetable])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:option 1})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:option 2})))
    (is (= 1 (get-in (resolve! pending {:skip? true}) [:players 0 :goods :vegetable])))
    (is (empty? (:effect-queue done)))
    (is (= #{0} (set (keep :option (filter #(bot/preview pending 0 %)
                                           (bot/decision-candidates pending 0))))))))

(deftest spices-bonuses-only-fireplace-family-vegetable-cooking
  (let [state (-> (fresh) (own 0 spices)
                  (assoc-in [:players 0 :goods :vegetable] 3)
                  (assoc-in [:players 0 :goods :food] 0))]
    (is (= 2 (a/amount (a/convert-food (g/p state 0) {:vegetable 2}) :food)))
    (is (= 6 (a/amount (a/convert-food (g/p (own state 0 :catalog/original-base-e-20-simple-fireplace) 0)
                                      {:vegetable 2}) :food)))
    (is (= 8 (a/amount (a/convert-food (assoc (g/p state 0) :improvements #{:hearth-4})
                                      {:vegetable 2}) :food)))
    (with-redefs [c/cards (assoc c/cards :test/cookhouse {:kind :minor :passive {:cook {:vegetable 4}}})]
      (is (= 8 (a/amount (a/convert-food (update (g/p state 0) :minors conj :test/cookhouse)
                                        {:vegetable 2}) :food)))
      (is (= 8 (a/amount (a/convert-food (-> (g/p state 0)
                                           (update :minors conj :test/cookhouse)
                                           (assoc :improvements #{:fireplace-2}))
                                        {:vegetable 2}) :food))
          "A different cooking improvement's better rate must not receive the bonus."))))

(deftest mandatory-construction-cards-require-a-legal-immediate-effect
  (doseq [[cid goods house]
          [[:catalog/original-base-e-11-field {:food 1} :wood]
           [:catalog/original-base-e-52-stable {:wood 1} :wood]
           [extension {:stone 3 :reed 1} :stone]]]
    (let [state (-> (fresh) (hand cid)
                    (assoc-in [:players 0 :house] house)
                    (assoc-in [:players 0 :farm] (vec (repeat 15 {:type :room})))
                    (update-in [:players 0] a/gain goods))]
      (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 cid 0 {}))
          (str cid " cannot be spent merely to pass it on when no construction is possible.")))))
