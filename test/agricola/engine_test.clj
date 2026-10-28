(ns agricola.engine-test
  (:require [clojure.test :refer :all] [agricola.engine :as a] [clojure.string :as str]))
(def order (vec (mapcat identity a/stages)))
(defn game ([] (game ["Ada" "Ben"])) ([names] (a/new-game names order 0)))
(defn rich [g] (update g :players #(mapv (fn [p] (a/gain p {:wood 50 :clay 30 :reed 20 :stone 20 :grain 10 :vegetable 5 :food 100})) %)))
(defn take-action [g space & [params]] (a/command g (:turn g) (merge {:type :action :space space} params)))
(defn throws? [f] (try (f) false (catch clojure.lang.ExceptionInfo _ true)))
(deftest setup-and-turns
  (let [g (game)]
    (is (= [2 3] (mapv #(a/amount % :food) (:players g))))
    (is (= 3 (get-in g [:piles :wood :wood])))
    (is (= 1 (get-in g [:piles :sheep :sheep])))
    (is (throws? #(a/command g 1 {:type :action :space :wood})))
    (let [g' (take-action g :wood)]
      (is (= 1 (:turn g')))
      (is (= 3 (get-in g' [:players 0 :goods :wood])))
      (is (throws? #(take-action g' :wood)))
      (is (throws? #(take-action g' :cattle))))))
(deftest replenishment-and-starting-player
  (let [g (-> (game) (take-action :grain) (take-action :meeting) (take-action :labor) (take-action :fishing))]
    (is (= 2 (:round g))) (is (= 1 (:turn g))) (is (= 1 (:starting-player g)))
    (is (= 6 (get-in g [:piles :wood :wood])))
    (is (= 1 (get-in g [:piles :meeting :food])))
    (is (= 2 (get-in g [:piles :sheep :sheep])))))
(deftest solo-and-player-count
  (let [g (game ["Solo"])]
    (is (= 0 (get-in g [:players 0 :goods :food])))
    (is (= 2 (get-in g [:piles :wood :wood])))
    (is (= 6 (a/food-needed g (first (:players g))))))
  (is (= {:clay 2} (:gain (a/action-definition (game ["A" "B" "C" "D"]) :hollow))))
  (is (some #{:market} (:active (game ["A" "B" "C"]))))
  (is (not-any? #{:market} (:active (game)))))
(deftest spatial-building
  (let [p (first (:players (rich (game))))
        built (a/build-rooms p [0 1])]
    (is (= 4 (count (a/tiles built :room))))
    (is (= 40 (a/amount built :wood)))
    (is (= 16 (a/amount built :reed)))
    (is (throws? #(a/build-rooms p [14])))
    (is (throws? #(a/build-rooms p [0 0])))
    (is (throws? #(a/place-tile (a/place-tile p :field 4) :field 12)))
    (is (throws? #(a/build-stables p [5] 2)))
    (is (throws? #(a/build-stables p [0 1 2 3 4] 2)))
    (is (= :clay (:house (a/renovate p))))
    (is (= 28 (a/amount (a/renovate p) :clay)))))
(deftest fencing-and-capacity
  (let [p (first (:players (rich (game))))
        fenced (a/build-fences p (a/edges 4))
        stable (a/build-stables fenced [4] 2)]
    (is (= [[4]] (a/pastures fenced)))
    (is (= 46 (a/amount fenced :wood)))
    (is (some? (a/animal-housing (a/gain fenced {:sheep 2 :boar 1}))))
    (is (nil? (a/animal-housing (a/gain fenced {:sheep 2 :boar 2}))))
    (is (some? (a/animal-housing (a/gain stable {:sheep 4 :boar 1}))))
    (is (throws? #(a/build-fences p ["h:0:4"])))
    (is (throws? #(a/build-fences p (a/edges 5))))
    (is (throws? #(a/build-fences fenced (a/edges 12))))
    (is (throws? #(a/build-fences p ["h:0:4" "h:0:4"]))))
  (let [p (first (:players (rich (game))))
        perimeter ["h:0:3" "h:0:4" "h:1:3" "h:1:4" "v:0:3" "v:0:5"]
        fenced (a/build-fences p perimeter)
        divided (a/build-fences fenced ["v:0:4"])]
    (is (= [[3 4]] (a/pastures fenced)))
    (is (= [[3] [4]] (a/pastures divided)))
    (is (= 16 (:capacity (second (a/accommodations (a/build-stables fenced [3 4] 2))))))))
(deftest cultivation-and-harvest
  (let [p (-> (first (:players (rich (game)))) (a/place-tile :field 3) (a/place-tile :field 4)
              (a/sow [{:cell 3 :crop :grain} {:cell 4 :crop :vegetable}]))
        harvested (a/field-harvest p)]
    (is (= [3 2] (mapv #(get-in p [:farm % :count]) [3 4])))
    (is (= [2 1] (mapv #(get-in harvested [:farm % :count]) [3 4])))
    (is (= 10 (a/amount harvested :grain)))
    (is (throws? #(a/sow p [{:cell 3 :crop :grain}])))))
(deftest family-growth
  (let [p (first (:players (rich (game))))]
    (is (throws? #(a/grow p true)))
    (is (= 3 (:family (a/grow p false))))
    (is (= 2 (:remaining (a/grow p false))))
    (is (= 1 (:newborn (a/grow p false))))
    (is (= 5 (a/food-needed (game) (a/grow p false))))
    (is (throws? #(a/grow (assoc p :family 5) false)))))
(deftest improvements-and-food
  (let [g (rich (game))
        g (a/build-improvement g 0 {:improvement :fireplace-2})
        g (a/build-improvement g 0 {:improvement :hearth-4 :upgrade :fireplace-2})
        p (get-in g [:players 0])]
    (is ((:available-improvements g) :fireplace-2))
    (is (not ((:available-improvements g) :hearth-4)))
    (is (= 28 (a/amount p :clay)))
    (is (= 109 (a/amount (a/convert-food (a/gain p {:sheep 2 :boar 1}) {:sheep 2 :boar 1}) :food)))
    (is (= 108 (a/amount (a/bake p 2) :food)))
    (is (throws? #(a/convert-food (first (:players (game))) {:sheep 1})))
    (is (throws? #(a/convert-food p {:grain -1})))
    (is (throws? #(a/build-improvement g 1 {:improvement :hearth-4}))))
  (let [g (a/build-improvement (rich (game)) 0 {:improvement :clay-oven :bake-grain 1}) p (get-in g [:players 0])]
    (is (= 107 (a/amount p :food)))
    (is (throws? #(a/bake p 2))))
  (let [g (a/build-improvement (rich (game)) 0 {:improvement :well})]
    (is (= #{2 3 4 5 6} (get-in g [:players 0 :well-rounds])))
    (is (= 103 (get-in (a/begin-round g) [:players 0 :goods :food])))))
(deftest harvest-order-and-begging
  (let [g (assoc (game) :round 4 :phase :feeding :fed #{} :bred #{})
        g (a/command g 0 {:type :feed})]
    (is (= 2 (get-in g [:players 0 :begging])))
    (is (= :feeding (:phase g)))
    (let [g (a/command g 1 {:type :feed})]
      (is (= :breeding (:phase g))) (is (= 0 (:turn g)))
      (is (throws? #(a/command g 0 {:type :convert :goods {:grain 1}})))
      (is (= :work (:phase (-> g (a/command 0 {:type :breed}) (a/command 1 {:type :breed}))))))))
(deftest breeding-requires-space-and-is-mandatory
  (let [p (-> (first (:players (rich (game)))) (a/build-fences (a/edges 4)) (a/gain {:sheep 2}))
        g (assoc (game) :phase :breeding :bred #{} :players [p (second (:players (game)))])]
    (is (throws? #(a/command g 0 {:type :breed :offspring {}})))
    (is (= 3 (get-in (a/command g 0 {:type :breed}) [:players 0 :goods :sheep])))
    (let [full (update-in g [:players 0] a/gain {:boar 1})]
      (is (throws? #(a/command full 0 {:type :breed})))
      (is (= 2 (get-in (a/command full 0 {:type :breed :offspring {}}) [:players 0 :goods :sheep]))))))
(deftest scoring
  (is (= -14 (:total (a/score (first (:players (game)))))))
  (is (= -1 (a/threshold-score 1 [2 3 4 5])))
  (is (= 4 (a/threshold-score 20 [1 4 6 8])))
  (let [p (-> (first (:players (game))) (assoc :house :stone :begging 2) (a/gain {:wood 7}) (assoc :improvements #{:joinery}))
        score (a/score p)]
    (is (= 4 (get-in score [:breakdown :rooms])))
    (is (= -6 (get-in score [:breakdown :begging])))
    (is (= 3 (get-in score [:breakdown :bonus])))
    (is (= 7 (:tiebreak score)))))
(defn complete-game [g]
  (loop [g g n 0]
    (when (> n 400) (throw (ex-info "Game did not terminate" {})))
    (if (= :finished (:phase g)) g
      (let [cmd (case (:phase g)
                  :feeding {:type :feed}
                  :breeding {:type :breed}
                  :work (let [space (first (filter #(and (some #{%} (:active g)) (not (contains? (:occupied g) %)))
                                                   [:wood :clay :reed :grain :labor :fishing :meeting :grove :hollow :copse :traveling :stone-west :stone-east :vegetable]))]
                          {:type :action :space space}))]
        (recur (a/command g (:turn g) cmd) (inc n))))))
(deftest complete-games-all-player-counts
  (doseq [n (range 1 6)]
    (let [finished (complete-game (game (mapv #(str "Player " %) (range n))))]
      (is (= 14 (:round finished)))
      (is (= :finished (:phase finished)))
      (is (= n (count (:scores finished))))
      (is (= 6 (count (filter #(str/starts-with? (:text %) "Harvest") (:log finished))))))))

(deftest animal-overflow-and-workshops
  (let [g (assoc-in (game) [:piles :sheep :sheep] 3)
        pending (take-action g :sheep)]
    (is (= :housing (:phase pending)))
    (is (= 0 (:turn pending)))
    (is (throws? #(a/command pending 0 {:type :house-animals :keep {:sheep 2 :boar 0 :cattle 0}})))
    (is (throws? #(a/command pending 0 {:type :house-animals :keep {:sheep 1 :boar 0 :cattle 0} :cook? true})))
    (let [settled (a/command pending 0 {:type :house-animals :keep {:sheep 1 :boar 0 :cattle 0}})]
      (is (= 1 (:turn settled)))
      (is (= 1 (get-in settled [:players 0 :goods :sheep])))
      (is (= 0 (get-in settled [:piles :sheep :sheep])))))
  (let [g (-> (game) (assoc :phase :feeding :fed #{})
              (assoc-in [:players 0 :improvements] #{:joinery})
              (assoc-in [:players 0 :goods :wood] 1))
        fed (a/command g 0 {:type :feed :craft [:joinery]})]
    (is (= 0 (get-in fed [:players 0 :goods :wood])))
    (is (= 0 (get-in fed [:players 0 :begging])))
    (is (throws? #(a/command g 0 {:type :feed :craft [:joinery :joinery]})))))
(deftest renovation-and-improvement-are-atomic
  (let [g (-> (rich (game)) (update :active conj :renovate))
        g' (take-action g :renovate {:improvement :clay-oven :bake-grain 1})]
    (is (= :clay (get-in g' [:players 0 :house])))
    (is (= 25 (get-in g' [:players 0 :goods :clay])))
    (is (= 107 (get-in g' [:players 0 :goods :food])))
    (is (throws? #(take-action (game) :improvement {:improvement :fireplace-2})))
    (is (= :wood (get-in g [:players 0 :house])))))
