(ns agricola.community-fourteen-engine-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-rules :as c]
            [agricola.card-game :as g] [agricola.card-expressions :as expr]))
(defn cid [deck n]
  (:id (first (filter #(and (= "Original" (:edition %)) (= "Expansion" (:pack %))
                            (= deck (:deck %)) (= (str n) (:number %))) (vals c/metadata)))))
(defn own [p id] (update p (if (= :occupation (:kind (c/card id))) :occupations :minors) (fnil conj []) id))
(defn player [& ids]
  (reduce own (assoc (a/player 0 "Ada" 20) :goods (merge (zipmap a/resources (repeat 20)) (zipmap a/animals (repeat 0)))) ids))
(defn animal-player [p goods] (update p :goods merge (zipmap a/animals (repeat 0)) goods))
(def designer (cid "G" 1))
(def piler (cid "G" 53))
(def vegetarian (cid "G" 57))
(def corral (cid "G" 70))
(def gimlet (cid "G" 77))
(def scythe (cid "G" 103))
(def dog (cid "G" 108))
(def cowboy (cid "WM" 13))
(def nursery (cid "WM" 104))
(def rings (cid "FR" 20))
(def mill (cid "FR" 25))
(defn stock [p r] (a/amount p r))
(defn field [p i crop n] (assoc-in p [:farm i] (cond-> {:type :field :count n} crop (assoc :crop crop))))

(deftest alternative-designer-supports-independent-per-room-limits
  (let [p (player designer)
        opts {:wood-clay 2 :wood-stone 2}
        built (a/build-rooms p [0 1] opts)]
    (is (= 18 (stock built :wood)))
    (is (= 16 (stock built :clay)))
    (is (= 16 (stock built :stone)))
    (is (= 16 (stock built :reed)))
    (is (thrown? clojure.lang.ExceptionInfo (a/room-cost p {:wood-clay 3})))
    (is (thrown? clojure.lang.ExceptionInfo (a/room-cost p {:wood-clay -1})))
    (is (some #(= opts %) (a/room-payment-choices p)))
    (let [supports (-> p (own :catalog/original-base-e-37-clay-supports) (assoc :house :clay))
          room (a/room-cost supports {:clay-supports? true :wood-stone 1})]
      (is (= 0 (:wood room)))
      (is (= 1 (:stone room)))
      (is (= 2 (:clay room))))))

(deftest designer-frame-builder-ordered-combinations-match-explicit-rulings
  (let [p (player designer :catalog/original-base-k-272-frame-builder)
        opts {:wood-clay 2 :wood-stone 2 :frame? true :alternative-first? true :frame-materials [:clay :stone]}
        wooden (a/room-cost p opts)
        clay (a/room-cost (assoc p :house :clay) {:frame? true :wood-clay 1})]
    (is (= 3 (:wood wooden)))
    (is (= 0 (:clay wooden)))
    (is (= 0 (:stone wooden)))
    (is (= 4 (:clay clay)))
    (is (= 0 (:wood clay)))
    (is (some #(= 3 (:wood (a/room-cost p %))) (a/room-payment-choices p)))
    (is (thrown? clojure.lang.ExceptionInfo (a/room-cost (player designer (c/id :a123)) opts)))))

(deftest stonepiler-rounds-up-and-substitutions-follow-total-stable-discounts
  (let [p (player designer piler gimlet)
        two (a/build-stables p [0 1] 2 {:stonepiler-stone 1})
        third (a/build-stables two [2] 2 {:wood-clay 2})]
    (is (= 20 (stock two :wood)))
    (is (= 19 (stock two :stone)))
    (is (= 2 (:gimlet-stables-used two)))
    (is (= 18 (stock third :clay)))
    (is (= 20 (stock third :wood)))
    (is (= {:wood 0 :stone 1} (a/stable-construction-cost p [0 1] 2 {:stonepiler-stone 1})))
    (is (thrown? clojure.lang.ExceptionInfo (a/build-stables p [0 1] 2 {:stonepiler-stone 2}))))
  (let [p (player piler)
        cost (a/construction-substitutions p {:wood 3} {:stonepiler-stone 2} false)]
    (is (= {:wood 0 :stone 2} cost))
    (is (thrown? clojure.lang.ExceptionInfo (a/construction-substitutions p {:wood 3} {:stonepiler-stone 3} false)))
    (is (thrown? clojure.lang.ExceptionInfo (a/room-cost p {:stonepiler-stone 1})))))

(deftest fence-cost-preview-and-native-payment-agree-with-combined-discounts
  (let [p (player designer piler (cid "WM" 21)) edges (a/edges 0)
        opts {:wood-clay 1 :stonepiler-stone 1}
        cost (a/fence-construction-cost p edges opts)
        built (a/build-fences p edges opts)]
    (is (= {:wood 0 :clay 1 :stone 1} cost))
    (is (= 19 (stock built :clay)))
    (is (= 19 (stock built :stone)))
    (is (= 20 (stock built :wood)))
    (is (= cost (a/fence-construction-cost (assoc p :goods {}) edges opts)))
    (is (thrown? clojure.lang.ExceptionInfo (a/build-fences (assoc p :goods {:clay 1}) edges opts)))))

(deftest gimlet-room-discount-applies-to-alternative-costs-and-resets-stables
  (let [p (player gimlet (cid "FR" 46))
        room (a/room-cost (assoc p :house :clay) {:straw-thatched-hut? true})
        g (-> (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0)
              (assoc-in [:players 0] (a/build-stables p [0 1] 2)))
        next (a/begin-round g)]
    (is (= 1 (:clay room)))
    (is (= 1 (:grain room)))
    (is (= 1 (:food room)))
    (is (= 2 (get-in g [:players 0 :gimlet-stables-used])))
    (is (nil? (get-in next [:players 0 :gimlet-stables-used])))
    (is (= 17 (stock (a/build-stables (get-in next [:players 0]) [2] 2) :wood))))
  (let [p (player gimlet :catalog/original-base-k-121-sawhorse)
        built (a/build-stables p [0 1 2] 2)]
    (is (= 2 (:gimlet-stables-used built)))
    (is (= 18 (stock built :wood)))))

(deftest vegetarian-adds-one-per-crop-for-every-engine-conversion-and-bans-animals
  (let [p (assoc (player vegetarian) :improvements #{:hearth-4 :clay-oven})]
    (is (= 26 (stock (a/convert-food p {:grain 1 :vegetable 1}) :food)))
    (is (= 26 (stock (a/bake p 1) :food)))
    (is (= 26 (stock (a/convert-recipe p {:grain 2} 4 {}) :food)))
    (is (not-any? #(contains? (a/cook-rates p) %) a/animals))
    (doseq [f [#(a/convert-food % {:sheep 1}) #(a/convert-recipe % {:boar 1} 3 {}) #(a/hunter-convert % {:cattle 1} {:phase :work})]]
      (is (thrown? clojure.lang.ExceptionInfo (f (a/gain p {:sheep 1 :boar 1 :cattle 1})))))
    (is (thrown? clojure.lang.ExceptionInfo (a/convert-food (assoc (player) :vegetarian? true :improvements #{:hearth-4}) {:sheep 1})))))

(deftest hunter-and-bonfire-preserve-receipts-and-temporary-availability
  (let [p (animal-player (player) {:sheep 1 :boar 1 :cattle 1})
        cooked (a/hunter-convert p {:sheep 1 :boar 1 :cattle 1} {:phase :work :round 4})]
    (is (= 29 (stock cooked :food)))
    (is (= {:sheep 1 :boar 1 :cattle 1} (:goods (last (:cooking-receipts cooked)))))
    (is (thrown? clojure.lang.ExceptionInfo (a/hunter-convert p {:sheep 1} {:phase :breeding})))
    (is (thrown? clojure.lang.ExceptionInfo (a/hunter-convert p {:sheep 2} {:phase :work}))))
  (let [p (assoc (player) :temporary-animal-cooking {:round 1 :rates {:sheep 2 :boar 3 :cattle 4}})
        g (assoc-in (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0) [:players 0] p)]
    (is (= 4 (:cattle (a/cook-rates p))))
    (is (nil? (:cattle (a/cook-rates (get-in (a/begin-round g) [:players 0])))))))

(deftest scythe-keeps-same-crop-adds-a-stack-and-never-sows-a-cell-twice
  (let [p (field (player scythe) 0 :grain 2)
        sown (a/sow p [{:cell 0 :crop :grain}])]
    (is (= 5 (get-in sown [:farm 0 :count])))
    (is (= 19 (stock sown :grain)))
    (is (a/can-sow-field? p 0 :grain))
    (is (not (a/can-sow-field? p 0 :vegetable)))
    (is (not (a/can-sow-field? p 0 :grain {:empty-only? true})))
    (is (thrown? clojure.lang.ExceptionInfo (a/sow p [{:cell 0 :crop :grain}] {:empty-only? true})))
    (is (thrown? clojure.lang.ExceptionInfo (a/sow p [{:cell 0 :crop :grain} {:cell 0 :crop :grain}]))))
  (let [copse :catalog/original-base-i-78-copse
        p (assoc-in (player scythe) [:card-fields copse] {:type :wood-stack :allowed-crop :wood :crop :wood :count 1})]
    (is (= 4 (get-in (a/sow p [{:cell copse :crop :wood}]) [:card-fields copse :count])))))

(deftest reed-nursery-uses-generic-stack-modifiers-and-harvests-reed
  (let [p (field (player nursery :catalog/original-base-i-219-fieldsman) 0 nil 0)
        sown (a/sow p [{:cell 0 :crop :reed}])]
    (is (some #{:reed} (a/sowable-crops p)))
    (is (= 5 (get-in sown [:farm 0 :count])))
    (is (= 19 (stock sown :reed)))
    (is (= 20 (stock (a/field-harvest sown) :reed)))
    (is (= 4 (get-in (a/field-harvest sown) [:farm 0 :count])))
    (is (not (a/can-sow-field? (field (player) 0 nil 0) 0 :reed))))
  (let [p (field (player nursery :catalog/original-base-i-90-planter-box) 0 nil 0)]
    (is (= 3 (get-in (a/sow p [{:cell 0 :crop :reed}]) [:farm 0 :count])))))

(deftest sheepdog-cowboy-and-cowbell-share-physical-unused-cells
  (let [p (animal-player (player dog cowboy (cid "G" 71)) {:sheep 20 :cattle 4 :boar 2})]
    (is (some? (a/animal-housing p)))
    (is (nil? (a/animal-housing (a/gain p {:boar 1}))))
    (is (= 13 (count (filter #(a/empty-cell? p %) (range 15)))))
    (is (= 5 (a/food-needed {:players [p p] :round 4} p)))
    (is (every? #(or (not= :pet (:kind %)) (= :sheep (:animal %))) (a/animal-housing p))))
  (let [p (animal-player (player cowboy) {:boar 3})]
    (is (some? (a/animal-housing p)))
    (is (nil? (a/animal-housing (a/gain p {:boar 1}))))))

(deftest corral-optimizes-occupied-fields-without-claiming-pasture-points
  (let [p (-> (player corral) (field 0 nil 0) (field 6 nil 0) (animal-player {:sheep 2}))]
    (is (= 2 (a/corral-points p)))
    (is (= 0 (count (a/pastures p))))
    (is (= 1 (a/corral-points (assoc-in p [:farm 0 :blocked-card] :tree))))
    (is (= 1 (a/corral-points (field p 0 :grain 1))))
    (is (= 2 (a/corral-points (animal-player p {:sheep 1 :boar 1}))))))

(deftest bee-stable-has-no-animal-functions-but-retains-piece-and-fenced-point
  (let [p (-> (player) (assoc-in [:farm 0] {:type :empty :stable true :stable-disabled? true})
              (assoc :fences (set (a/edges 0))) (animal-player {:sheep 2 :boar 1}))]
    (is (= 2 (:capacity (first (filter #(= :pasture (:kind %)) (a/accommodations p))))))
    (is (= 3 (a/stables-available p)))
    (is (= 1 (get-in (a/score p) [:breakdown :stables])))
    (is (nil? (a/animal-housing (a/gain p {:sheep 1})))))
  (let [p (-> (player) (assoc-in [:farm 0] {:type :empty :stable true :stable-disabled? true}) (animal-player {:sheep 1 :boar 1}))]
    (is (nil? (a/animal-housing p)))))

(deftest five-rings-native-cost-discounts-use-the-paid-marker
  (let [p (assoc-in (player rings) [:card-state rings :resource] :stone)]
    (is (= 0 (:stone (a/improvement-cost p :clay-oven nil))))
    (is (= 3 (:clay (a/improvement-cost p :clay-oven nil))))
    (is (= 1 (:stone (a/hand-improvement-discounts p (c/card (cid "G" 65))))))
    (is (= 0 (:wood (a/hand-improvement-discounts p (c/card (cid "G" 65))))))))

(deftest grain-mill-payment-removes-card-and-does-not-make-it-a-baking-oven
  (let [p (player mill)
        g (assoc-in (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0) [:players 0] p)
        done (a/build-improvement g 0 {:improvement :clay-oven :grain-mill-oven? true :bake-grain 1})]
    (is (not (a/baking-symbol? mill)))
    (is (empty? (a/returnable-ovens p)))
    (is (= {} (a/improvement-cost p :clay-oven nil {:grain-mill-oven? true})))
    (is (= 20 (stock (get-in done [:players 0]) :clay)))
    (is (= 20 (stock (get-in done [:players 0]) :stone)))
    (is (= 25 (stock (get-in done [:players 0]) :food)))
    (is (not-any? #{mill} (get-in done [:players 0 :minors])))
    (is (some #{mill} (:removed-cards done)))
    (is (thrown? clojure.lang.ExceptionInfo (a/improvement-cost p :well nil {:grain-mill-oven? true})))
    (is (thrown? clojure.lang.ExceptionInfo (a/improvement-cost (assoc p :pawned-improvements #{mill}) :clay-oven nil {:grain-mill-oven? true})))))

(deftest pawned-major-loses-only-printed-points-and-cannot-be-returned
  (let [p (-> (player) (assoc :improvements #{:fireplace-2 :clay-oven :joinery} :pawned-improvements #{:fireplace-2 :clay-oven}))
        g (assoc-in (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0) [:players 0] p)]
    (is (= 2 (get-in (a/score p) [:breakdown :improvements])))
    (is (empty? (a/fireplace-upgrades p)))
    (is (empty? (a/returnable-ovens p)))
    (is (= 25 (stock (a/bake p 1) :food)))
    (is (thrown? clojure.lang.ExceptionInfo (a/build-improvement g 0 {:improvement :hearth-4 :upgrade :fireplace-2}))))
  (is (= 2 (a/stables-available (assoc (player) :stored-stables {:shed 2})))))

(deftest native-build-and-fence-commands-preserve-payment-options
  (let [p (player designer piler)
        base (assoc-in (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0) [:players 0] p)
        rooms (a/command base 0 {:type :action :space :build :rooms [0] :wood-clay 2 :wood-stone 2})
        stables (a/command base 0 {:type :action :space :build :stables [0 1] :stonepiler-stone 2})
        fences (a/command (update base :active conj :fences) 0 {:type :action :space :fences :fences (a/edges 0) :wood-clay 2 :stonepiler-stone 1})]
    (is (= 19 (get-in rooms [:players 0 :goods :wood])))
    (is (= 18 (get-in rooms [:players 0 :goods :clay])))
    (is (= 18 (get-in stables [:players 0 :goods :stone])))
    (is (= 20 (get-in stables [:players 0 :goods :wood])))
    (is (= 18 (get-in fences [:players 0 :goods :clay])))
    (is (= 19 (get-in fences [:players 0 :goods :stone])))))

(defn card-game [p]
  (assoc-in (g/initialize (a/new-game ["Ada" "Ben" "Cy" "Dee"] (vec (mapcat identity a/stages)) 0)) [:players 0] p))
(defn effect [p id op choice]
  (g/run-effect (assoc (card-game p) :phase :feeding :round 4) (g/task 0 id op {}) choice))

(deftest scythe-does-not-override-corn-storehouses-empty-only-sowing
  (let [p (field (player scythe) 0 :grain 2)
        corn :catalog/original-base-i-86-corn-storehouse
        op (assoc (get-in (c/card corn) [:triggers 0 :effect]) :op :sow)]
    (is (thrown? clojure.lang.ExceptionInfo (effect p corn op {:plantings [{:cell 0 :crop :grain}]})))
    (is (= 2 (get-in (effect (field p 0 nil 0) corn op {:plantings [{:cell 0 :crop :grain}]}) [:players 0 :farm 0 :count])))))

(deftest reed-only-sowing-is-offered-and-restricted-crop-effects-stay-restricted
  (let [p (-> (player nursery) (field 0 nil 0) (assoc-in [:goods :grain] 0) (assoc-in [:goods :vegetable] 0))
        s (card-game p)]
    (is (g/sowable? p))
    (is (g/sowing-potential? s 0 {}))
    (is (not (g/sowing-potential? s 0 {:allowed-crops #{:grain}})))
    (is (not (a/can-sow-field? (assoc-in p [:card-fields :copse] {:type :wood-stack :allowed-crop :wood}) 0 :wood)))))

(deftest vegetarian-native-exchanges-and-water-mill-include-crop-bonus
  (let [p (player vegetarian)
        brewer :catalog/original-base-e-154-master-brewer
        brew (effect p brewer (get-in (c/card brewer) [:triggers 0 :effect]) {})
        threshold (effect p (c/id :a059) {:op :threshold-exchange :resource :vegetable :minimum 3 :food 6} {})
        water (effect p :catalog/original-base-i-103-water-mill {:op :water-mill :cost {:grain 1} :owner 0} {})]
    (is (= 24 (get-in brew [:players 0 :goods :food])))
    (is (= 27 (get-in threshold [:players 0 :goods :food])))
    (is (= 24 (get-in water [:players 0 :goods :food])))))

(deftest actual-potter-and-sawmill-triggers-use-conversion-passives
  (let [potter :catalog/original-base-e-214-potter
        sawmill :catalog/original-base-k-122-sawmill
        wheel (effect (player (cid "G" 94)) potter (get-in (c/card potter) [:triggers 0 :effect]) {})
        artisan (effect (player (cid "G" 6) sawmill) sawmill (get-in (c/card sawmill) [:triggers 0 :effect]) {})]
    (is (= 23 (get-in wheel [:players 0 :goods :food])))
    (is (= 25 (get-in artisan [:players 0 :goods :food])))
    (is (= 4 (get-in artisan [:players 0 :artisan-used-round])))))

(deftest vegetable-delivery-and-winnowing-use-vegetarian-without-spurious-crop-gain
  (let [p (assoc (player vegetarian) :improvements #{:clay-oven})
        delivery (effect p (cid "WM" 114) {:op :wine-delivery :amount 2} {:points 1})
        winnow (effect p (c/id :a058) {:op :bake-one} {:improvement :clay-oven :grain 1})]
    (is (= 23 (get-in delivery [:players 0 :goods :food])))
    (is (= 1 (get-in delivery [:players 0 :bonus-points])))
    (is (= 20 (get-in delivery [:players 0 :goods :vegetable])))
    (is (= 26 (get-in winnow [:players 0 :goods :food])))))

(deftest grain-mill-hand-oven-payment-removes-only-the-grain-mill
  (let [oven (cid "G" 65) p (assoc-in (player mill) [:hand :minor] [oven])
        s (card-game p)
        done (g/play-card s 0 oven 0 {:grain-mill-oven? true})]
    (is (= 20 (get-in done [:players 0 :goods :stone])))
    (is (not-any? #{mill} (get-in done [:players 0 :minors])))
    (is (some #{oven} (get-in done [:players 0 :minors])))
    (is (some #{mill} (:removed-cards done)))))

(deftest bee-stable-stored-on-shed-no-longer-produces-food-or-blocks-new-stable-functions
  (let [bee (cid "WM" 5) shed (cid "WM" 110)
        built (effect (player bee shed) bee {:op :bee-keeper} {:cell 0})
        stored (g/run-effect built (g/task 0 shed {:op :storage-shed} {}) {:cell 0})
        p (get-in stored [:players 0])
        replaced (a/build-stables p [0] 2)]
    (is (= 3 (a/stables-available p)))
    (is (not (get-in p [:farm 0 :stable-disabled?])))
    (is (not (get-in p [:farm 0 :stable-card])))
    (is (some #(and (= :stable (:kind %)) (= [0] (:cells %))) (a/accommodations replaced)))))

(deftest unmarked-food-recipes-and-mixed-food-rewards-use-conversion-bonuses
  (let [hall (cid "G" 114)
        s (assoc (card-game (player (cid "G" 6) hall)) :phase :feeding :round 4)
        done (g/ability s 0 {:card hall :option 0})]
    (is (= 25 (get-in done [:players 0 :goods :food])))
    (is (= 4 (get-in done [:players 0 :artisan-used-round]))))
  (let [festival (cid "WM" 79)
        done (g/drain (effect (player vegetarian festival) festival (get-in (c/card festival) [:triggers 0 :effect]) {:option 0}))]
    (is (= 23 (get-in done [:players 0 :goods :food])))
    (is (= 21 (get-in done [:players 0 :goods :wood])))
    (is (= 19 (get-in done [:players 0 :goods :grain])))))

(deftest returning-bee-stable-removes-the-bee-keeper-marker-too
  (let [bee (cid "WM" 5) changing (cid "WM" 69)
        built (effect (player bee) bee {:op :bee-keeper} {:cell 0})
        returned (g/run-effect built (g/task 0 changing {:op :return-stables :min-stables 1 :max-stables 2 :then :plow} {}) {:stables [0]})]
    (is (not (get-in returned [:players 0 :farm 0 :stable])))
    (is (not (get-in returned [:players 0 :farm 0 :stable-disabled?])))
    (is (not (get-in returned [:players 0 :farm 0 :stable-card])))
    (is (= 4 (a/stables-available (get-in returned [:players 0]))))))

(deftest pawned-ovens-and-fireplaces-still-satisfy-ownership-benefits
  (let [glass (cid "G" 22) rug (cid "G" 109)
        p (assoc (player glass rug) :improvements #{:clay-oven :fireplace-2} :pawned-improvements #{:clay-oven :fireplace-2})
        s (assoc (card-game p) :phase :feeding)]
    (is (= 1 (expr/evaluate s 0 {} [:ovens])))
    (is (= 2 (expr/evaluate s 0 {} (get-in (c/card rug) [:score :value]))))
    (is (= 23 (get-in (g/ability s 0 {:card glass :option 0}) [:players 0 :goods :food])))
    (is (empty? (a/returnable-ovens p)))
    (is (empty? (a/fireplace-upgrades p)))))
