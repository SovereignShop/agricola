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
  {
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
                     (repeat (if (#{"businesswoman" "homework"} scenario) 2 1) {:skip? true}) commands)]
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
