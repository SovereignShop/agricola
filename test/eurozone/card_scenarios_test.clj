(ns eurozone.card-scenarios-test
  (:require [clojure.test :refer :all] [eurozone.card-scenarios :as scenarios]
            [eurozone.store :as store] [agricola.engine :as a] [agricola.card-rules :as cards]
            [agricola.card-game :as game] [agricola.recording :as recording])
  (:import [java.nio.file Files]))

(deftest swing-plow-table-resolves-declines-persists-and-replays
  (doseq [skip? [false true]]
    (let [path (str (.resolve (Files/createTempDirectory "card-scenario" (make-array java.nio.file.attribute.FileAttribute 0)) "games.edn"))
          service (store/make-store path)
          original (:id (store/transact! service "owner" {:event :create :name "Ada"}))
          before @(:rooms service)
          id (:id (store/transact! service "tester" {:event :create :scenario "swing-plow" :name "Partner"
                                                    :bots 4 :turn-seconds 30 :mode :online}))
          room (get @(:rooms service) id) pending (:state room)
          head (first (:effect-queue pending))
          command (merge {:type :resolve :decision (:decision-id pending)}
                         (if skip? {:skip? true} {:plow-card (:card head) :cells [1 2]}))]
      (is (= (get before original) (get @(:rooms service) original)))
      (is (= :local (:mode room)))
      (is (= ["Partner" "Neighbor"] (mapv :name (:seats room))))
      (is (= ["tester" "tester"] (mapv :owner (:seats room))))
      (is (= 0 (:turn-seconds room)))
      (is (= :catalog/original-base-k-115-swing-plow (:card head)))
      (is (= pending (get-in @(:rooms (store/make-store path)) [id :state])))
      (is (true? (get-in room [:recording 0 :partial-start?])))
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/transact! service "intruder" {:event :command :id id :revision 0 :command command})))
      (store/transact! service "tester" {:event :command :id id :revision 0 :command command})
      (let [after (get @(:rooms service) id)]
        (is (= (if skip? #{0} #{0 1 2}) (set (a/tiles (get-in after [:state :players 0]) :field))))
        (is (= (if skip? 2 1) (get-in after [:state :players 0 :card-state (:card head) :uses])))
        (is (= (:state after) (last (:frames (recording/timeline (:recording after))))))
        (is (= 1 (game/acting-player (:state after))))))))

(deftest only-registered-prepared-positions-are-accepted
  (is (thrown? clojure.lang.ExceptionInfo (store/transition {} "tester" {:event :create :name "Ada" :scenario "unknown"})))
  (doseq [{:keys [id card]} scenarios/scenarios]
    (let [state (scenarios/initial-state id (:names (scenarios/options id "Ada")))
          player (game/p state 0)]
      (is (some #{card} (concat (cards/owned player) (mapcat val (:hand player)))) id)
      (is (= (:players (scenarios/scenario id) 2) (count (:players state)))))))

(def review-commands
  {"grotto" [{:option 0}]
   "threshing-machine-plow" [{:plow-card :catalog/original-expansion-fr-50-threshing-machine-plow :cells [7 8]}]
   "wood-saw" [{:rooms [0]}]
   "convict-number-24601" [{:type :action :space :grain}]
   "cowboy-and-mother" [{:option 4} {:cell 0}]
   "good-friend" [{:option 0}]
   "miser" [{:type :action :space :build :rooms [0]}]
   "turkey-breeder" [{:fences ["h:0:0" "h:1:0" "v:0:0" "v:0:1"]} {:stables [0]}]
   "wood-gatherers" [{:type :action :space :grain}]
   "workaholic" [{:option 0}]

   "borrower" [{:type :ability :card :catalog/original-expansion-wm-6-borrower}]
   "chemist" [{:space :reed}]
   "family-planner" [{:rounds [5 8]}]
   "lady-in-waiting" [{}]
   "storehouse-minder" [{:resources [:wood :clay]} {:type :ability :card :catalog/original-expansion-wm-47-storehouse-minder :resource :clay}]
   "almshouse" [{:type :feed} {}]
   "cash-crop" [{:type :action :space :grain}]
   "fish-pond" [{:cell 0} {:type :action :space :fishing} {:amount 1}]
   "rotherham-plow" [{:cells [7]}]
   "slapdash-renovation" [{}]
   "alternative-designer" [{:type :action :space :build :rooms [0] :room-options {0 {:wood-clay 2 :wood-stone 2}}}]
   "fish-seller" [{:goods [:vegetable :wood]}]
   "stonepiler" [{:type :action :space :fences :fences ["h:0:0" "h:1:0" "v:0:0" "v:0:1"] :stonepiler-stone 2}]
   "vegetarian" [{:type :convert :goods {:grain 1 :vegetable 1}}]
   "bartering-hut" [{:animals [:sheep :sheep] :payment {:wood 2 :clay 2}}]
   "corral" [{:type :action :space :sheep}]
   "gimlet" [{:type :action :space :build :rooms [0] :stables [1 2]}]
   "paddocks" [{:fences ["h:1:1" "h:1:2"]}]
   "scythe" [{:type :action :space :sow-bake :plantings [{:cell 0 :crop :grain}]}]
   "sheepdog" [{:type :feed}]
   "bee-keeper" [{:cell 0}]
   "cowboy" [{:type :action :space :cattle}]
   "hunter" [{:goods {:sheep 2}}]
   "pawnbroker" [{:type :ability :card :catalog/original-expansion-wm-39-pawnbroker :improvement :well}]
   "village-fool" [{:card :catalog/original-expansion-g-110-shovel}]
   "bonfire" [{:cells [0]} {:type :ability :card :catalog/original-expansion-wm-64-bonfire} {:type :convert :goods {:boar 1}}]
   "raspberries" [{:cells [0 1 6]} {:type :action :space :plow :cell 0}]
   "reed-nursery" [{:type :action :space :sow-bake :plantings [{:cell 0 :crop :reed} {:cell 1 :crop :reed}]}]
   "storage-shed" [{:cell 0} {:type :action :space :meeting :card :catalog/original-expansion-g-110-shovel :storage-shed-resource :wood}]
   "wine-closet" [{:points 1}]
   "five-rings" [{:type :action :space :meeting :card :catalog/original-expansion-fr-20-five-rings :payment-option 1}]
   "grain-mill-oven" [{:type :action :space :meeting :improvement :clay-oven :grain-mill-oven? true :bake-grain 1}]
   "boatswain" [{:cells [0 1]}]
   "fiddler" [{:type :action :space :grain}]
   "harvester" [{:type :action :space :grain}]
   "head-of-the-revolution" [{:type :action :space :plow :cell 0}]
   "manual-labourer" [{:type :action :space :grain}]
   "mastermind" [{:type :action :space :improvement :improvement :joinery}]
   "sailboat-constructor" [{:stacks [:wood :crops]}]
   "stroller" [{:resources [:wood :clay :reed :stone :wood :clay]}]
   "reed-nursery-scoring" [{:amount 1 :fields {0 3}}]
   "workshop-combinations" [{:type :feed :craft [:pottery :joinery]}]
   "animal-farmer" [{:type :action :space :sheep}]
   "artisan" [{:type :feed :craft [:pottery]}]
   "resource-collector" [{:resources [:wood :clay :reed :stone]}]
   "understudy" [{:type :action :space :plow :cell 1}]
   "beehive-oven" [{:grain 1 :use-beehive? false}]
   "cow-bell" [{:type :action :space :cattle}]
   "hammer" [{:fences ["h:0:0" "h:1:0" "v:0:0" "v:0:1"]}]
   "potters-wheel" [{:type :feed :craft [:pottery]}]
   "remodeling" [{:stables [0]} {:rooms [0]}]
   "seasonal-plants" [{:cell 1}]
   "fence-dealer" [{:type :action :space :fences :fences ["h:0:0" "h:1:0" "v:0:0" "v:0:1"]}]
   "livestock-keeper" [{:animal :sheep} {:type :breed :offspring {:sheep 2}}]
   "stable-deliveryman" [{:rounds [4 6 8 10]}]
   "sunrise-admirer" [{:cell 4}]
   "apple-tree" [{:cell 0}]
   "backyard-well" [{:type :ability :card :catalog/original-expansion-wm-62-backyard-well :cell 0}]
   "changing-plans" [{:stables [0 1]} {:cells [0 1]}]
   "gold-medal" [{:category :grain}]
   "pig-wallow" [{:cell 0} {:cell 0}]
   "wild-breeding" [{:space :sheep}]
   "absinthe" [{:spaces [:grain]}]
   "artists-garden" [{:cell 0}]
   "chocolade" [{:type :action :space :lessons :card :catalog/original-expansion-g-5-architect}]
   "straw-thatched-hut" [{:type :action :space :build :rooms [0] :straw-thatched-hut? true}]
   "animal-welfarist" [{:type :action :space :grain}]
   "benefactor" [{:type :ability :card :catalog/original-expansion-fr-65-benefactor :animal :sheep :rooms [0]}]
   "cabbage-lover" [{:amount 1}]
   "country-doctor" [{:type :action :space :growth}]
   "martial-artist" [{:cards [:catalog/original-expansion-g-110-shovel :catalog/original-expansion-wm-70-clockwork-man]}]
   "parquet-setter" [{:type :action :space :grain}]
   "bakers-daughter" [{} {:grain 1} {:skip? true}]
   "carrot-cake-baker" [{:grain 1}]
   "freeloader" [{:type :action :space :plow :cell 0}]
   "grain-lover" [{:cell 0} {} {:plantings [{:cell 0 :crop :grain}]}]
   "inventor" [{:type :action :space :improvement :improvement :clay-oven :discount-resource :clay}]
   "pearl-seeker" [{:amount 1}]
   "stone-trader" [{:improvement :well}]
   "birthstone" [{}]
   "meat-pie" [{:option 0}]
   "pine-forest" [{}]
   "bargaining-baker" [{:type :action :space :improvement :improvement :clay-oven :discount-resource :clay}]
   "covetous-farmer" [{:type :ability :card :catalog/original-expansion-wm-12-covetous-farmer :option 2}]
   "diplomat" [{:type :action :space :plow :cell 0}]
   "farm-school-graduate" [{:card :catalog/original-expansion-g-5-architect}]
   "gem-hunter" [{}]
   "wheelbarrow-pusher" [{:resource :wood}]
   "wood-worker" [{}]
   "attic" [{:cells [0]}]
   "clockwork-man" [{:type :action :space :meeting :card :catalog/original-expansion-wm-70-clockwork-man}]
   "honeymoon" [{:type :action :space :grain}]
   "chameleon" [{:type :action :space :meeting :card :catalog/original-expansion-fr-13-chameleon}]
   "goblet" [{:type :action :space :meeting :improvement :well}]
   "hay-rake" [{:type :action :space :plow :cell 1}]
   "rock-pyramid" [{:cells [1 2]}]
   "tuileries-garden" [{:option 0} {} {:plantings [{:cell 0 :crop :grain}]}]
   "winter-deadwood" [{:option 1}]
   "award-winner" [{:resource :wood}]
   "cattle-dealer" [{}]
   "gardening-enthusiast" [{:return-animals {:grain 1 :vegetable 1}}]
   "sculptors-son" [{:type :action :space :wood}]
   "animal-holder" [{:type :action :space :lessons :card :catalog/original-expansion-g-3-animal-holder}]
   "apprentice" [{:type :action :space :grain}]
   "glutton" [{:option 1}]
   "hobbyist" [{:improvement :fireplace-2}]
   "ringmaster" [{:improvement :fireplace-2}]
   "wintercrafter" [{:improvement :fireplace-2}]
   "holiday-present" [{:option 1}]
   "horse-barn" [{:type :action :space :sow-bake :plantings [{:cell 1 :crop :grain} {:cell 2 :crop :grain}]}]
   "pumpkin-pie" [{}]
   "fish-farm" [{:plantings [{:cell 0 :crop :grain} {:cell 1 :crop :vegetable}]}]
   "bread-carrier" [{:type :action :space :improvement :improvement :clay-oven}]
   "engineer" [{:type :action :space :meeting :card :catalog/original-expansion-g-86-itinerant-laborers}]
   "heiress" [{:rooms [0]}]
   "hoarder" [{:option 0}]
   "master-stableman" [{:stables [0]}]
   "tapestry-weaver" [{:cells [5]}]
   "cookies" [{:cells [5]}]
   "crib" [{:option 1}]
   "friendly-pastures" [{:type :action :space :boar}]
   "home-garden" [{:type :action :space :renovate}]
   "evening-prayer" [{:card :catalog/original-expansion-g-49-seed-master} {:card :catalog/original-expansion-g-5-architect}]
   "golden-rose" [{:type :action :space :lessons :card :catalog/original-expansion-g-5-architect}]
   "heatwave" [{:improvement :fireplace-2}]
   "kids-corner" [{:type :action :space :boar}]
   "march" [{:option 0} {:cells [0 1]}]
   "trees-for-the-citizens" [{:type :action :space :meeting :card :catalog/original-expansion-fr-52-trees-for-the-citizens}]
   "drawing-genius" [{:card :catalog/original-expansion-g-110-shovel}]
   "lemon-trader" [{:type :ability :card :catalog/original-expansion-fr-90-lemon-trader :option 4}]
   "pasteurization-expert" [{:type :action :space :sheep}]
   "reformer" [{:type :action :space :cattle}]
   "fishermans-apprentice" [{:card :catalog/original-expansion-g-5-architect}]
   "glass-blower" [{:type :ability :card :catalog/original-expansion-g-22-glass-blower :option 1}]
   "grafter" [{:type :feed}]
   "grain-farmer" [{:type :action :space :grain}]
   "grump" [{:type :action :space :lessons :card :catalog/original-expansion-g-28-grump}]
   "helper" [{:type :action :space :build :rooms [0]}]
   "truffle-digger" [{:type :action :space :wood}]
   "village-farmer" [{:type :action :space :lessons :card :catalog/original-expansion-g-58-village-farmer}]
   "wheedler" [{:type :action :space :labor}]
   "sausage-grinder" [{:type :feed :convert {:sheep 1}}]
   "animal-buyer" [{:option 3}]
   "cloister-dweller" [{:type :action :space :lessons :card :catalog/original-expansion-wm-10-cloister-dweller}]
   "humble-farmboy" [{:type :action :space :lessons :card :catalog/original-expansion-wm-31-humble-farmboy}]
   "silo-girl" [{:type :action :space :renovate}]
   "stone-curator" [{:type :action :space :stone-west}]
   "tile-maker" [{:type :action :space :lessons :card :catalog/original-expansion-wm-52-tile-maker}]
   "birdcage" [{}]
   "free-range-chickens" [{:type :ability :card :catalog/original-expansion-wm-83-free-range-chickens :amount 2}]
   "fruitcake" [{:type :action :space :grain}]
   "iron-plow" [{:plow-card :catalog/original-expansion-wm-91-iron-plow :cells [1]} {} {:plantings [{:cell 0 :crop :grain} {:cell 1 :crop :vegetable}]}]
   "apple-garden" [{:type :action :space :meeting :card :catalog/original-expansion-fr-4-apple-orchard}]
   "peasants-boutique" [{:type :ability :card :catalog/original-expansion-fr-41-peasants-boutique :option 0}]
   "the-port-le-havre" [{:option 1}]
   "watering-can" [{:type :action :space :meeting :card :catalog/original-expansion-fr-56-watering-can}]
   "art-director" [{:resource :stone}]
   "cocotte" [{:improvement :fireplace-2}]
   "dove-hunter" [{:type :ability :card :catalog/original-expansion-fr-77-dove-hunter :option 0}]
   "landscape-gardener" [{} {:plantings [{:cell :catalog/original-expansion-fr-89-landscape-gardener :crop :grain} {:cell :catalog/original-expansion-fr-89-landscape-gardener-2 :crop :vegetable}]}]
   "stage-star" [{:type :action :space :traveling}]
   "wealthiest-european" [{:resources [:wood :clay :stone]}]
   "reed-sale" [{:type :action :space :meeting :card :catalog/original-expansion-g-100-reed-sale}]
   "sheepskin-rug" [{:type :action :space :meeting :card :catalog/original-expansion-g-109-sheepskin-rug}]
   "sickle" [{:type :action :space :plow :cell 4}]
   "stonecutters-hall" [{:type :ability :card :catalog/original-expansion-g-114-stonecutter-s-hall :option 0}]
   "tree-house" [{:type :action :space :growth}]
   "boundary-stones" [{:type :action :space :plow :cell 6}]
   "brazier" [{:type :ability :card :catalog/original-expansion-wm-66-brazier :option 0}]
   "glassblowers-shop" [{:type :ability :card :catalog/original-expansion-wm-86-glassblowers-shop :option 0}]
   "lamp-oil" [{:type :action :space :lessons :card :catalog/original-expansion-g-5-architect}]
   "worm-bait" [{:type :action :space :grain}]
   "flat-hill" [{:type :action :space :meeting :card :catalog/original-expansion-fr-21-flat-hill}]
   "lighthouse" [{:type :action :space :growth}]
   "drinker-of-absinthe" [{:type :action :space :lessons :card :catalog/original-expansion-fr-79-drinker-of-absinthe}]
   "immigrants-son" [{:cell 0}]
   "village-druid" [{:type :action :space :lessons :card :catalog/original-expansion-g-5-architect}]
   "clay-starter" [{:type :action :space :lessons :card :catalog/original-expansion-g-13-clay-starter}]
   "fence-helper" [{:fences (vec (a/edges 0))}]
   "amazon" [{:type :action :space :fishing}]
   "amazon-choice" [{:resource :boar}]
   "apprenticeship" [{:card :catalog/original-expansion-g-5-architect}]
   "camembert" [{:spaces [:meeting :grain :wood]} {:type :action :space :grain}]
   "coffee-break" [{:type :action :space :lessons :card :catalog/original-expansion-g-5-architect}]
   "private-market" [{:type :ability :card :catalog/original-expansion-g-95-private-market :option 3}]
   "grain-market" [{}]
   "grain-elevator" [{:type :action :space :grain}]
   "grain-elevator-opponent" [{:type :action :space :grain}]
   "farm-school" [{:card :catalog/original-expansion-g-5-architect}]
   "brickyard" [{:type :action :space :grain} {:type :ability :card :catalog/original-expansion-fr-11-brickyard}]
   "journeyman" [{:option 2}]
   "carrot-farmer" [{}]
   "pulley" [{:rooms [0]}]
   "food-connoisseur" [{:type :action :space :fishing}]
   "oak-cask" [{:type :action :space :grain} {:type :ability :card :catalog/original-expansion-wm-96-oak-cask}]
   "child-care-worker" [{:option 1}]
   "oceanographer" [{}]
   "sheep-market" [{:option 2}]
   "wooden-bridge" [{:type :action :space :reed}]
   "edible-roots" [{:type :action :space :grain}]
   "festival-hall" [{:option 3}]
   "abandoned-willow" [{:cell 0}]
   "powerhouse" [{:type :action :space :grain}]
   "powerhouse-politician" [{:type :ability :card :catalog/original-expansion-wm-41-politician :option 0} {}]
   "seed-spreader" [{} {:plantings [{:cell 1 :crop :vegetable}]}]
   "vegetable-scoop" [{:type :action :space :vegetable}]
   "diamond-mine" [{:type :action :space :meeting :card :catalog/original-expansion-wm-73-diamond-mine}]
   "encircling-wall" [{:cell 1}]
   "stone-house-reconstruction" [{:type :ability :card :catalog/original-expansion-fr-45-stone-house-reconstruction}]
   "businesswoman" [{:improvement :fireplace-2} {:skip? true}]
   "singer" [{:resources [:reed :reed]}]
   "gardening-tools" [{} {:plantings [{:cell 0 :crop :grain}]}]
   "shovel" [{} {:plantings [{:cell 0 :crop :vegetable}]}]
   "debris-dealer" [{:type :action :space :renovate}]
   "herdsman" [{:resource :boar}]
   "ruins" [{}]
   "steamer" [{:type :ability :card :catalog/original-expansion-wm-108-steamer :option 1} {:amount 1}]
   "baguette" [{:grain 1}]
   "barber-shop" [{:type :action :space :meeting :card :catalog/original-expansion-fr-8-barber-shop}]
   "homework" [{:card :catalog/original-expansion-g-49-seed-master} {:skip? true}]
   "trailblazer" [{:cell 1}]
   "foreman" [{:space :grain}]
   "fence-builder" [{:space :wood}]
   "fence-overseer" [{:cell 0}]
   "animal-breeder" [{:animal :boar}]
   "resource-seller" [{:option 0}]
   "silage" [{:grain-source 2 :animal :sheep}]
   "stable-planner" [{:rounds [4 7]}]
   "harvest-helper" [{:owner 1 :cell 0}]
   "hide-farmer" [{:amount 2}]
   "clearing-spade" [{:type :ability :card :revised-base/a071 :from 0 :to :catalog/original-base-e-18-bean-field}]
   "asparagus-knife" [{:cell 0}]
   "work-certificate" [{:space :wood :resource :wood}]
   "stagehand" [{:option 1} {:stables [1 2]}]
   "cottager" [{:action :room :cell 0}]
   "copse" [{:type :action :space :sow-bake :plantings [{:cell :catalog/original-base-i-78-copse :crop :wood}
                                                       {:cell :catalog/original-base-i-78-copse-2 :crop :wood}]}]})

(deftest community-tables-resolve-decline-save-and-replay
  (doseq [[scenario commands] review-commands
          decline? [false true]]
    (let [path (str (.resolve (Files/createTempDirectory "community-scenario" (make-array java.nio.file.attribute.FileAttribute 0)) "games.edn"))
          service (store/make-store path)
          id (:id (store/transact! service "tester" {:event :create :name "Ada" :scenario scenario}))
          initial (get @(:rooms service) id)
          optional? (get-in initial [:state :effect-queue 0 :effect :optional?])
          commands (if (and decline? optional?)
                     (repeat (if (#{"businesswoman" "homework" "turkey-breeder"} scenario) 2 1) {:skip? true}) commands)]
      (is (= scenario (:scenario initial)))
      (is (= scenario (get-in @(:rooms (store/make-store path)) [id :scenario])))
      (is (= (:state initial) (get-in @(:rooms (store/make-store path)) [id :state])) scenario)
      (doseq [choice commands]
        (let [room (get @(:rooms service) id) state (:state room)
              command (if (:type choice) choice (merge {:type :resolve :decision (:decision-id state)} choice))]
          (store/transact! service "tester" {:event :command :id id :revision (:revision room) :command command})))
      (let [room (get @(:rooms service) id)]
        (is (= (:state room) (get-in @(:rooms (store/make-store path)) [id :state])) scenario)
        (is (= (:state room) (last (:frames (recording/timeline (:recording room))))))
        (is (not= (:state initial) (:state room)) scenario)))))

(deftest community-thirteen-prepared-tables-demonstrate-their-rewards
  ;; These passive tables must visibly exercise their card, not merely accept a
  ;; legal unrelated action. In particular Parquet Setter needs a native work
  ;; baseline before the farm changes made during preparation.
  (doseq [[id expected] [["artisan" {:food 0 :clay -1}]
                         ["potters-wheel" {:food -1 :clay -1}]
                         ["beehive-oven" {:food 6 :grain -1}]
                         ["cow-bell" {:cattle 4}]
                         ["animal-welfarist" {:sheep 1 :boar 1 :cattle 1}]
                         ["parquet-setter" {:wood 1 :food 1}]]]
    (let [before (scenarios/initial-state id (:names (scenarios/options id "Ada")))
          after (reduce (fn [state choice]
                          (game/command state (game/acting-player state)
                                        (if (:type choice) choice
                                            (merge {:type :resolve :decision (:decision-id state)} choice))))
                        before (get review-commands id))]
      (doseq [[resource delta] expected]
        (is (= delta (- (a/amount (game/p after 0) resource)
                        (a/amount (game/p before 0) resource))) [id resource])))))

(deftest community-fourteen-review-tables-show-native-card-results
  (doseq [[id goods points card-score]
          [["vegetarian" {:food 5 :grain -1 :vegetable -1} nil nil]
           ["sheepdog" {:food -5} nil nil]
           ["fiddler" {:wood 1 :food 1} nil nil]
           ["manual-labourer" {:wood 1} nil nil]
           ["wine-closet" {:food 2} 1 nil]
           ["head-of-the-revolution" {} 3 nil]
           ["mastermind" {} nil 1]
           ["harvester" {:grain 6} nil 3]
           ["grain-mill-oven" {:food 5 :grain -1 :clay 0 :stone 0} nil nil]]]
    (let [before (scenarios/initial-state id (:names (scenarios/options id "Ada")))
          after (reduce (fn [state choice]
                          (game/command state (game/acting-player state)
                                        (if (:type choice) choice
                                            (merge {:type :resolve :decision (:decision-id state)} choice))))
                        before (get review-commands id))]
      (doseq [[resource delta] goods]
        (is (= delta (- (a/amount (game/p after 0) resource)
                        (a/amount (game/p before 0) resource))) [id resource]))
      (when points
        (is (= points (- (get-in after [:players 0 :bonus-points] 0)
                         (get-in before [:players 0 :bonus-points] 0))) id))
      (when card-score
        (is (= card-score (game/card-bonus after 0 (:card (scenarios/scenario id)))) id)))))

(deftest reed-nursery-final-table-allocates-actual-supply-and-field-reed
  (doseq [decline? [false true]]
    (let [state (scenarios/initial-state "reed-nursery-scoring" ["Ada" "Partner"])
          choice (if decline? {:skip? true} {:amount 1 :fields {0 3}})
          after (game/command state 0 (merge {:type :resolve :decision (:decision-id state)} choice))
          player (game/p after 0)]
      (is (= :reed-nursery-points (get-in state [:effect-queue 0 :effect :op])))
      (is (= :finished (:phase after)))
      (is (= (if decline? 0 2) (:bonus-points player)))
      (is (= (if decline? 3 2) (a/amount player :reed)))
      (is (= (if decline? 3 0) (a/field-crop-count (get-in player [:farm 0]) :reed)))
      (is (= 3 (a/field-crop-count (get-in player [:farm 1]) :reed))))))

(deftest workshop-combination-orders-feed-save-and-replay
  (doseq [[order first-food second-food] [[[:pottery :joinery] 5 2]
                                          [[:joinery :pottery] 4 3]]]
    (let [path (str (.resolve (Files/createTempDirectory "workshop-scenario" (make-array java.nio.file.attribute.FileAttribute 0)) "games.edn"))
          service (store/make-store path)
          id (:id (store/transact! service "tester" {:event :create :name "Ada" :scenario "workshop-combinations"}))
          before (get-in @(:rooms service) [id :state])
          player (game/p before 0)
          context (select-keys before [:phase :round])
          first-workshop (a/craft-food player [(first order)] false context)
          both-workshops (a/craft-food first-workshop [(second order)] false context)]
      (is (= :feeding (:phase before)))
      (is (= first-food (- (a/amount first-workshop :food) (a/amount player :food))) order)
      (is (= second-food (- (a/amount both-workshops :food) (a/amount first-workshop :food))) order)
      (is (= before (get-in @(:rooms (store/make-store path)) [id :state])))
      (store/transact! service "tester" {:event :command :id id :revision 0 :command {:type :feed :craft order}})
      (let [room (get @(:rooms service) id) after (:state room) fed (game/p after 0)]
        (is (= (+ 3 (a/amount player :food)) (a/amount fed :food)) "Seven workshop food minus four family food.")
        (is (= (dec (a/amount player :wood)) (a/amount fed :wood)))
        (is (= (dec (a/amount player :clay)) (a/amount fed :clay)))
        (is (= (:round before) (:artisan-used-round fed)))
        (is (= after (get-in @(:rooms (store/make-store path)) [id :state])))
        (is (= after (last (:frames (recording/timeline (:recording room))))))))))
