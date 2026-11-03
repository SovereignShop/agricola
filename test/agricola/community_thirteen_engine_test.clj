(ns agricola.community-thirteen-engine-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-rules :as c]
            [agricola.card-game :as g] [agricola.card-expressions :as expr]))
(defn cid [deck n]
  (:id (first (filter #(and (= "Original" (:edition %)) (= "Expansion" (:pack %))
                            (= deck (:deck %)) (= (str n) (:number %))) (vals c/metadata)))))
(defn own [p id] (update p (if (= :occupation (:kind (c/card id))) :occupations :minors) (fnil conj []) id))
(defn player [& ids] (reduce own (assoc (a/player 0 "Ada" 20) :goods (zipmap a/resources (repeat 20))) ids))
(defn animals [p goods] (assoc p :goods (merge (zipmap a/animals (repeat 0)) goods)))
(def farmer (cid "G" 2))
(def bell (cid "G" 71))
(def dealer (cid "WM" 21))
(def straw (cid "FR" 46))
(def beehive (cid "G" 65))
(def doctor (cid "FR" 74))
(def artisan (cid "G" 6))
(def wheel (cid "G" 94))
(defn pasture [p cells] (assoc p :fences (set (mapcat a/edges cells))))
(defn readback [x] (edn/read-string (pr-str x)))

(deftest cow-bell-capacity-is-four-and-unused-spaces-stay-unused
  (let [p (animals (player bell) {:cattle 5})]
    (is (some? (a/animal-housing p))) "Four cattle on unused cells and one house pet."
    (is (nil? (a/animal-housing (a/gain p {:cattle 1}))))
    (is (= 13 (count (filter #(a/empty-cell? p %) (range 15)))))
    (is (= [2 2] (mapv :capacity (filter #(= :unused (:kind %)) (a/accommodations p)))))
    (is (nil? (a/animal-housing (animals p {:sheep 2})))))
  (let [p (assoc (animals (player bell) {:cattle 3}) :farm (mapv #(hash-map :type (if (= % 0) :empty :field)) (range 15)))]
    (is (some? (a/animal-housing p)))
    (is (nil? (a/animal-housing (a/gain p {:cattle 1}))))))

(deftest fence-dealer-costs-and-built-limit
  (let [p (player dealer)
        four (a/build-fences p (a/edges 0))
        seven (a/build-fences p (vec (distinct (concat (a/edges 0) (a/edges 1)))))]
    (is (= 12 (a/fences-available p)))
    (is (= 18 (a/amount four :wood)))
    (is (= 16 (a/amount seven :wood)))
    (is (= 8 (a/fences-available four)))
    (is (= 12 (a/fences-available (assoc p :reserved-fences {:card 3}))))
    (is (= 11 (a/fences-available (assoc p :reserved-fences {:card 4}))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (a/build-fences p (vec (distinct (mapcat a/edges [0 1 2 3 4 6]))))))
    (is (= 19 (a/amount (a/build-fences (own p :catalog/original-base-e-175-hedge-keeper) (a/edges 0)) :wood)))
    (is (= 20 (a/amount (a/build-mini-pasture p 0 []) :wood)))))

(deftest straw-hut-is-optional-per-room-and-combines-with-bricklayer
  (let [p (assoc (player straw) :house :clay)
        chosen {:straw-thatched-hut? true}
        cost (a/room-cost p chosen)
        built (a/build-rooms p [0 1] {:room-options {0 chosen 1 {}}})]
    (is (= {:clay 2 :grain 1 :food 1 :stone 0 :reed 0} cost))
    (is (= 13 (a/amount built :clay)))
    (is (= 18 (a/amount built :reed)))
    (is (= 19 (a/amount built :grain)))
    (is (= 19 (a/amount built :food)))
    (is (some #(= chosen %) (a/room-payment-choices p)))
    (is (= 0 (:clay (a/room-cost (own p :catalog/original-base-i-243-bricklayer) chosen))))
    (is (thrown? clojure.lang.ExceptionInfo (a/room-cost (assoc p :house :wood) chosen)))
    (is (thrown? clojure.lang.ExceptionInfo (a/room-cost p (assoc chosen :clay-supports? true))))
    (is (thrown? clojure.lang.ExceptionInfo (a/build-rooms (assoc-in p [:goods :food] 0) [0] chosen)))
    (is (= p (readback p)))))

(deftest beehive-baking-choice-and-once-per-bake-limits
  (let [p (-> (player beehive) (assoc :improvements #{:clay-oven}))
        food #(a/amount (a/bake p %1 {} %2) :food)]
    (is (= 26 (food 1 {}))) "Auto uses Clay Oven for five plus Beehive's unused bonus."
    (is (= 24 (food 1 {:use-beehive? true})))
    (is (= 26 (food 1 {:use-beehive? false})))
    (is (= 29 (food 2 {})))
    (is (= 29 (food 2 {:use-beehive? true})))
    (is (thrown? clojure.lang.ExceptionInfo (food 2 {:use-beehive? false})))
    (is (thrown? clojure.lang.ExceptionInfo (food 3 {})))
    (is (= p (a/bake p 0 {} {:use-beehive? false}))))
  (let [p (player beehive)]
    (is (= 24 (a/amount (a/bake p 1) :food)))
    (is (thrown? clojure.lang.ExceptionInfo (a/bake p 2)))
    (is (thrown? clojure.lang.ExceptionInfo (a/bake p 1 {} {:use-beehive? false}))))
  (let [p (assoc (player beehive) :improvements #{:hearth-4})]
    (is (= 27 (a/amount (a/bake p 2) :food)))
    (is (= 27 (a/amount (a/bake p 2 {} {:use-beehive? false}) :food)))))

(deftest doctor-needs-three-planted-fields-and-a-growth-action
  (let [p (-> (player doctor)
              (assoc-in [:farm 0] {:type :field :crop :grain :count 1})
              (assoc-in [:farm 1] {:type :field :crop :vegetable :count 1})
              (assoc-in [:farm 2] {:type :field :crop :grain :count 1}))
        grown (a/grow p true {:space :growth})]
    (is (a/country-doctor-available? p))
    (is (= 3 (:family grown)))
    (is (true? (get-in grown [:card-state doctor :growth-used?])))
    (is (thrown? clojure.lang.ExceptionInfo (a/grow grown true {:space :growth})))
    (is (thrown? clojure.lang.ExceptionInfo (a/grow p true)))
    (is (thrown? clojure.lang.ExceptionInfo (a/grow (assoc-in p [:farm 2 :count] 0) true {:space :growth})))
    (is (not (get-in (a/grow p false {:space :urgent-growth}) [:card-state doctor :growth-used?])))
    (is (not (get-in (a/grow (assoc-in p [:farm 6] {:type :room}) true {:space :growth}) [:card-state doctor :growth-used?])))
    (is (= grown (readback grown)))))

(deftest animal-farmer-includes-full-capacity-and-all-full-bonus
  (let [p (pasture (animals (player farmer) {:sheep 2}) [0])]
    (is (= 2 (a/animal-farmer-points p)))
    (is (= 0 (a/animal-farmer-points (assoc-in p [:goods :sheep] 1))))
    (is (= 0 (a/animal-farmer-points (assoc-in p [:farm 0 :stable] true))))
    (is (= 2 (a/animal-farmer-points (-> p (assoc-in [:farm 0 :stable] true) (assoc-in [:goods :sheep] 4)))))
    (is (= 0 (a/animal-farmer-points (own p :catalog/original-base-e-29-shepherd-s-pipe))))
    (is (= 2 (a/animal-farmer-points (assoc-in (own p :catalog/original-base-e-29-shepherd-s-pipe) [:goods :sheep] 4))))
    (is (= 1 (a/animal-farmer-points (own p :catalog/original-base-k-145-forest-pasture))))
    (is (= 3 (a/animal-farmer-points (pasture (assoc-in p [:goods :sheep] 4) [0 1])))))
  (is (= 0 (a/animal-farmer-points (animals (player farmer) {}))))
  (is (= 2 (a/animal-farmer-points (animals (player farmer :catalog/original-base-e-58-animal-yard) {:sheep 1 :boar 1}))))
  (is (= 2 (a/animal-farmer-points (animals (player farmer :catalog/original-base-i-102-wildlife-reserve) {:sheep 1 :boar 1 :cattle 1}))))
  (is (= 0 (a/animal-farmer-points (animals (player farmer :catalog/original-base-i-102-wildlife-reserve) {:sheep 1 :boar 1}))))
  (is (= 2 (a/animal-farmer-points (animals (player farmer :catalog/original-base-i-102-wildlife-reserve :catalog/original-base-e-29-shepherd-s-pipe) {:sheep 3 :boar 1 :cattle 1})))))

(deftest apple-tree-field-is-neither-sowable-removable-nor-empty-boar-housing
  (let [p (-> (player) (assoc-in [:farm 0] {:type :field :blocked-card :apple-tree :marker-goods {:wood 1}}))]
    (is (empty? (a/removable-field-cells p)))
    (is (thrown? clojure.lang.ExceptionInfo (a/remove-empty-field p 0)))
    (is (thrown? clojure.lang.ExceptionInfo (a/sow p [{:cell 0 :crop :grain}])))
    (is (= p (a/field-harvest p)))))

(deftest artisan-only-once-per-harvest-and-improvement-not-occupation
  (let [p (assoc (player artisan wheel) :improvements #{:pottery :joinery})
        context {:phase :feeding :round 4}
        crafted (a/craft-food p [:pottery :joinery] false context)]
    (is (= 27 (a/amount crafted :food))) "Pottery2+Wheel1+Joinery2+Artisan2."
    (is (= 4 (:artisan-used-round crafted)))
    (is (= 30 (a/amount (a/craft-food crafted [:pottery] false context) :food)))
    (is (= 32 (a/amount (a/craft-food crafted [:pottery] false (assoc context :round 7)) :food)))
    (is (= 23 (a/amount (a/craft-food p [:pottery] false {:phase :work :round 4}) :food)))
    (is (= 23 (a/amount (a/convert-recipe p {:clay 1} 2 (assoc context :recipe-card :catalog/original-base-e-214-potter)) :food)))
    (is (= 24 (a/amount (a/convert-recipe p {:clay 1} 2 (assoc context :recipe-card (cid "G" 114))) :food)))
    (is (nil? (:artisan-used-round (a/convert-recipe p {:clay 1} 2 (assoc context :recipe-card :catalog/original-base-e-214-potter)))))))

(deftest native-baking-and-construction-forward-explicit-card-options
  (let [base (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0)
        p (assoc (player beehive) :improvements #{:clay-oven})
        baking (-> base (assoc-in [:players 0] p) (update :active conj :sow-bake))
        done (a/command (readback baking) 0 {:type :action :space :sow-bake :bake-grain 1 :use-beehive? false})]
    (is (= 26 (a/amount (get-in done [:players 0]) :food)))
    (is (= 19 (a/amount (get-in done [:players 0]) :grain))))
  (let [base (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0)
        p (assoc (player straw) :house :clay)
        done (a/command (assoc-in base [:players 0] p) 0
                        {:type :action :space :build :rooms [0] :straw-thatched-hut? true})]
    (is (= :room (get-in done [:players 0 :farm 0 :type])))
    (is (= 18 (a/amount (get-in done [:players 0]) :clay)))
    (is (= 19 (a/amount (get-in done [:players 0]) :grain)))))

(deftest native-feeding-artisan-pays-before-feeding-and-stores-once-marker
  (let [base (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0)
        p (assoc (player artisan wheel) :improvements #{:pottery :joinery})
        s (-> base (assoc :round 4 :phase :feeding :fed #{}) (assoc-in [:players 0] p))
        done (a/feed (readback s) 0 {:craft [:pottery :joinery]})]
    (is (= 23 (a/amount (get-in done [:players 0]) :food)))
    (is (= 4 (get-in done [:players 0 :artisan-used-round])))
    (is (= 0 (get-in done [:players 0 :begging])))))

(deftest paid-extra-breeding-is-available-once-and-uses-actual-housing
  (let [p (-> (player) (animals {:sheep 2}) (pasture [0]) (assoc :extra-breeding {:sheep 1})
              (assoc-in [:farm 0 :stable] true))
        base (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0)
        s (-> base (assoc :phase :breeding :bred #{}) (assoc-in [:players 0] p))
        done (a/breed s 0 {:offspring {:sheep 2}})]
    (is (= {:sheep 2} (a/breeding-options p)))
    (is (= 4 (get-in done [:players 0 :goods :sheep])))
    (is (nil? (get-in done [:players 0 :extra-breeding])))
    (is (= {:sheep 1} (a/breeding-options (get-in done [:players 0]))))
    (is (thrown? clojure.lang.ExceptionInfo (a/breed s 0 {:offspring {:sheep 1}})))))

(defn card-game-with [p]
  (assoc-in (g/initialize (a/new-game ["Ada" "Ben" "Cy" "Dee"] (vec (mapcat identity a/stages)) 0)) [:players 0] p))

(deftest backyard-upgrade-preserves-only-the-returned-wells-deliveries
  (let [backyard (cid "WM" 62) village :catalog/original-base-i-66-village-well
        p (-> (player backyard) (assoc :improvements #{:well} :well-rounds #{2 3 4})
              (assoc-in [:hand :minor] [village])
              (assoc-in [:farm 0] {:type :feature :card backyard :marker-goods {:stone 1}})
              (assoc :schedule {2 [{:card backyard :goods {:food 1}}]
                                3 [{:card backyard :goods {:food 1}}]}))
        s (card-game-with p)
        returned-backyard (g/paid-card-player s 0 village 0 {:return-improvement backyard})
        returned-major (g/paid-card-player s 0 village 0 {:return-improvement :well})]
    (is (= #{:well} (:improvements returned-backyard)))
    (is (= #{2 3 4} (:well-rounds returned-backyard)))
    (is (= {2 1 3 1} (:well-deliveries returned-backyard)) "Keeping the actual Well must not duplicate its food.")
    (is (a/empty-cell? returned-backyard 0))
    (is (empty? (:schedule returned-backyard)))
    (is (= {2 1 3 1 4 1} (:well-deliveries returned-major)))
    (is (empty? (:well-rounds returned-major)))
    (is (= (:schedule p) (:schedule returned-major)))
    (is (= :feature (get-in returned-major [:farm 0 :type])))))

(deftest chocolat-stored-food-covers-only-regular-occupation-fee
  (let [chocolate (cid "FR" 14) chief :catalog/original-base-e-172-chief
        p (-> (player chocolate) (assoc-in [:hand :occupation] [chief])
              (assoc-in [:card-state chocolate :stored-goods :food] 4)
              (assoc-in [:goods :food] 2))
        s (card-game-with p)
        paid (g/paid-card-player s 0 chief 2 {})]
    (is (= 0 (a/amount paid :food)))
    (is (= 2 (get-in paid [:card-state chocolate :stored-goods :food])))
    (is (thrown? clojure.lang.ExceptionInfo (g/paid-card-player (assoc-in s [:players 0 :goods :food] 1) 0 chief 2 {})))
    (is (thrown? clojure.lang.ExceptionInfo (g/paid-card-player s 0 chief 2 {:chocolate-food 3})))
    (is (= 4 (a/amount (assoc-in p [:goods :food] 4) :food)))
    (is (= 4 (get-in (g/paid-card-player (assoc-in s [:players 0 :goods :food] 4) 0 chief 2 {:chocolate-food 0}) [:card-state chocolate :stored-goods :food])))))

(deftest apple-tree-rejects-crop-transfer-and-is-not-an-empty-field-prerequisite
  (let [p (-> (player (c/id :a071))
              (assoc-in [:farm 0] {:type :field :crop :grain :count 2})
              (assoc-in [:farm 1] {:type :field :blocked-card (cid "WM" 58) :marker-goods {:wood 1}}))
        s (card-game-with p)]
    (is (= 0 (expr/evaluate s 0 {} [:empty-fields])))
    (is (thrown? clojure.lang.ExceptionInfo (g/ability s 0 {:card (c/id :a071) :from 0 :to 1})))
    (is (empty? (g/apple-tree-cells s 0)))
    (is (= #{0} (set (g/harvestable-field-cells s 0))))))

(deftest parquet-compares-net-unused-spaces-at-work-start
  (let [parquet (cid "FR" 97)
        p (assoc-in (player parquet) [:farm 0] {:type :field})
        s (g/work-baselines (card-game-with p))
        reduced (-> s (update-in [:players 0] a/place-tile :field 1) (update-in [:players 0] a/build-stables [2] 0))
        undone (update-in reduced [:players 0] a/remove-empty-field 0)]
    (is (= 0 (expr/evaluate s 0 {} [:work-used-spaces])))
    (is (= 2 (expr/evaluate reduced 0 {} [:work-used-spaces])))
    (is (= 1 (expr/evaluate undone 0 {} [:work-used-spaces])))
    (is (= 0 (expr/evaluate (update-in s [:players 0] a/remove-empty-field 0) 0 {} [:work-used-spaces])))))

(deftest welfarist-requires-native-stables-and-fences-not-renovation-or-card-gifts
  (let [welfarist (cid "FR" 62) s (card-game-with (player welfarist))
        stable (update-in s [:players 0] a/build-stables [0] 0)
        recorded (g/record-placement s stable 0 :build {})
        fenced (update-in recorded [:players 0] a/build-fences (a/edges 0))
        native (g/record-placement recorded fenced 0 :fences {})
        renovation (g/record-placement recorded fenced 0 :redevelop {})]
    (is (false? (expr/evaluate recorded 0 {} [:welfarist-qualified])))
    (is (true? (expr/evaluate native 0 {} [:welfarist-qualified])))
    (is (false? (expr/evaluate renovation 0 {} [:welfarist-qualified])))))

(deftest sunrise-discard-removes-occupation-as-well-as-deliveries
  (let [sunrise (cid "WM" 49)
        p (-> (player sunrise) (assoc :schedule {2 [{:card sunrise :effect {:op :plow}}]}))
        s (card-game-with p)
        head (g/task 0 sunrise {:op :plow :only-cells [4 9 14] :cost {:food 2} :optional? true :discard-on-skip? true} {})
        skipped (g/run-effect s head {:skip? true})
        blocked (reduce #(assoc-in %1 [:players 0 :farm %2] {:type :field}) s [4 9 14])
        unavailable (g/drain (assoc blocked :effect-queue [head]))]
    (doseq [done [skipped unavailable]]
      (is (not-any? #{sunrise} (get-in done [:players 0 :occupations])))
      (is (= 0 (c/occupation-count (g/p done 0) nil)))
      (is (empty? (get-in done [:players 0 :schedule])))
      (is (some #{sunrise} (:removed-cards done))))))

(deftest beehive-choice-survives-card-effect-and-granted-major-baking
  (let [p (assoc (player beehive) :improvements #{:clay-oven})
        s (card-game-with p)
        effect (g/task 0 beehive {:op :bake :optional? true} {})
        use (g/run-effect s effect {:grain 1 :use-beehive? true})
        spare (g/run-effect s effect {:grain 1 :use-beehive? false})]
    (is (= 24 (a/amount (g/p use 0) :food)))
    (is (= 26 (a/amount (g/p spare 0) :food))))
  (let [s (card-game-with (player beehive))
        done (g/granted-card-action s 0 {:kinds #{:major}} {:improvement :clay-oven :bake-grain 1 :use-beehive? false})]
    (is (= 26 (a/amount (g/p done 0) :food)))
    (is (= 19 (a/amount (g/p done 0) :grain)))))

(deftest livestock-keeper-and-shepherd-replace-one-offspring-rather-than-add
  (let [p (-> (player :catalog/original-base-e-203-shepherd)
              (animals {:sheep 4 :boar 2 :cattle 2}))]
    (is (= {:sheep 2 :boar 1 :cattle 1} (a/breeding-options p)))
    (is (= {:sheep 2 :boar 1 :cattle 1} (a/breeding-options (assoc p :extra-breeding {:sheep 1}))))
    (is (= {:sheep 2 :boar 2 :cattle 1} (a/breeding-options (assoc p :extra-breeding {:boar 1}))))
    (is (= {:sheep 2 :boar 1 :cattle 2} (a/breeding-options (assoc p :extra-breeding {:cattle 1}))))
    (is (= {:sheep 2 :boar 1 :cattle 1} (a/breeding-options (-> p (assoc-in [:goods :sheep] 2) (assoc :extra-breeding {:sheep 1})))))))
