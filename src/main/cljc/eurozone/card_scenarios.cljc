(ns eurozone.card-scenarios
  "Prepared, playable positions for reviewing native card effects. Only registered
   scenarios may create test tables; callers cannot supply arbitrary game states."
  (:require [agricola.engine :as a] [agricola.card-game :as game] [agricola.card-rules :as cards]))

(def scenarios
  [{:id "clay-starter" :card :catalog/original-expansion-g-13-clay-starter :name "Clay Starter"
    :description "Use Lessons to play Clay Starter from your hand. Your two-room wooden hut automatically renovates to clay for one additional food, without paying clay or reed."}
   {:id "fence-helper" :card :catalog/original-expansion-g-16-fence-helper :name "Fence Helper" :players 3
    :description "Partner has just built fences. Select fence edges directly on your farm, paying their normal cost plus one food to Partner, or decline. Both payments are pictured."}
   {:id "amazon" :card :catalog/original-expansion-wm-1-amazon :name "Amazon" :players 3
    :description "Take Fishing in round seven to receive one sheep automatically as well as the food. The next table tests the round-thirteen animal choice."}
   {:id "amazon-choice" :card :catalog/original-expansion-wm-1-amazon :name "Amazon · animal choice" :players 3
    :description "Fishing in round thirteen has just supplied food. Click a sheep, wild boar, or cattle icon to choose the extra animal."}
   {:id "apprenticeship" :card :catalog/original-expansion-wm-59-apprenticeship :name "Apprenticeship"
    :description "You have fewer occupations than Partner, and the minor's one-food cost is paid. Click an occupation face to play it without a lesson fee, or decline. Apprenticeship then passes to Partner's hand."}
   {:id "camembert" :card :catalog/original-expansion-fr-12-camembert :name "Camembert"
    :description "Click three revealed action spaces to place one food on each. Occupied spaces, including Meeting Place, are allowed. Click again to undo a selection. The next player to use each space receives its food."}
   {:id "coffee-break" :card :catalog/original-expansion-fr-15-coffee-break :name "Coffee Break"
    :description "Round two has delivered the first food. Four food markers remain on rounds three to six. Play an occupation using Lessons to remove those future food; Flower Pots' round-eight vegetable must remain."}
   {:id "private-market" :card :catalog/original-expansion-g-95-private-market :name "Private Market"
    :description "Open the played card and choose a pictured two-food exchange for wood, clay, reed, or stone. Only one exchange is allowed per round."}
   {:id "grain-market" :card :catalog/original-expansion-g-78-grain-market :name "Grain Market"
    :description "Three grain fields have just been harvested. Exchange one grain for a vegetable using the pictured payment, or decline. This is one offer per harvest, not one per field."}
   {:id "grain-elevator" :card :catalog/original-expansion-wm-88-grain-elevator :name "Grain Elevator"
    :description "Two grain have accumulated on the card, the cap for this table. Take Grain Seeds: receive its one grain plus both stored grain. The next test shows what happens when an opponent takes it."}
   {:id "grain-elevator-opponent" :card :catalog/original-expansion-wm-88-grain-elevator :name "Grain Elevator · opponent"
    :description "Partner is about to act. Take Grain Seeds for Partner: they receive only the space's one grain; both grain on your Elevator return to the general supply."}
   {:id "farm-school" :card :catalog/original-expansion-wm-78-farm-school :name "Farm School"
    :description "Two goods have just been harvested, including wood from Copse. Click an occupation face to play it for two food, or decline. The payment is shown graphically."}
   {:id "brickyard" :card :catalog/original-expansion-fr-11-brickyard :name "Brickyard"
    :description "Three clay are stored, too few to collect. Take Grain Seeds with the last worker: round seven adds the fourth clay. Open Brickyard to collect all four; collecting does not discard it."}
   {:id "journeyman" :card :catalog/original-expansion-fr-88-journeyman :name "Journeyman" :players 3
    :description "A round is starting and your house is stone. Click the pictured one-food exchange for a sheep, wild boar, or cattle, or decline. The chosen animal fits as your house pet."}
   {:id "carrot-farmer" :card :catalog/original-expansion-g-12-carrot-farmer :name "Carrot Farmer"
    :description "Farmland has just been used. Pay the pictured one food to take one of the three vegetables on this card, or decline. The remaining stock stays on the card."}
   {:id "pulley" :card :catalog/original-expansion-g-97-pulley :name "Pulley"
    :description "Day Laborer has just supplied two food. Click one space beside your house to build a room at its normal cost, or decline. The payment is shown before you confirm."}
   {:id "food-connoisseur" :card :catalog/original-expansion-wm-22-food-connoisseur :name "Food Connoisseur" :players 4
    :description "Take Fishing, Traveling Players, or Day Laborer. You automatically receive the top item from the visible stack: reed first, then wood. Receiving food from a card does not count."}
   {:id "oak-cask" :card :catalog/original-expansion-wm-96-oak-cask :name "Oak Cask"
    :description "Take Grain Seeds with the last worker to start harvest. Oak Cask grows from one stored food to two. Open the played card to collect all the food; an emptied cask never refills."}
   {:id "child-care-worker" :card :catalog/original-expansion-fr-71-child-care-worker :name "Child Care Worker" :players 4
    :description "Partner has just grown their family. Choose the pictured wood-for-food or wood-and-reed-for-food exchange, or decline. Your own family growth does not trigger this card."}
   {:id "oceanographer" :card :catalog/original-expansion-fr-96-oceanographer :name "Oceanographer"
    :description "Exactly one field has just been plowed. Take the top resource from the visible stack, or leave it for later. Plowing multiple fields in one action gives no resource."}
   {:id "sheep-market" :card :catalog/original-expansion-g-107-sheep-market :name "Sheep Market"
    :description "Harvest is about to begin. Click a pictured grain, vegetable or sheep exchange, or decline. The exchange happens before crops are harvested."}
   {:id "wooden-bridge" :card :catalog/original-expansion-g-119-wooden-bridge :name "Wooden Bridge"
    :description "Take Reed Bank to receive one extra food, or Day Laborer to receive one extra reed. The bonus is automatic."}
   {:id "edible-roots" :card :catalog/original-expansion-wm-77-edible-roots :name "Edible Roots"
    :description "Take Grain Seeds with the last worker to start harvest. The last grain and vegetable on two fields give one food each; the third field still holds grain and gives no food."}
   {:id "festival-hall" :card :catalog/original-expansion-wm-79-festival-hall :name "Festival Hall"
    :description "Your fields have just been harvested. Exchange one grain for the pictured building resource plus two food, or decline. All four resource choices are available."}
   {:id "abandoned-willow" :card :catalog/original-expansion-fr-1-abandoned-willow :name "Abandoned Willow"
    :description "Click an empty field on your farm to return it and receive four wood. Removing the middle field would disconnect the others, so it cannot be selected. The card then belongs to Partner."}
   {:id "powerhouse" :card :catalog/original-expansion-fr-101-powerhouse :name "Powerhouse" :players 4
    :description "Take Grain Seeds with the last worker to start round two. Five stone in your supply give two food automatically at the start of work, after scheduled resources arrive."}
   {:id "powerhouse-politician" :card :catalog/original-expansion-fr-101-powerhouse :name "Powerhouse + Politician" :players 4
    :description "Round two is prepared, with four stone in supply. Open Politician and click its reed-to-stone exchange, then Begin work: Powerhouse gives two food. Continuing without the exchange gives one food. No farm selection is needed."}
   {:id "seed-spreader" :card :catalog/original-expansion-g-105-seed-spreader :name "Seed Spreader"
    :description "Farmland has just been used. Sow exactly one grain or vegetable by clicking a field, or decline. Copse is also present to check that wood cannot be sown with this effect."}
   {:id "vegetable-scoop" :card :catalog/original-expansion-g-117-vegetable-scoop :name "Vegetable Scoop"
    :description "Take Vegetable Seeds on the shared board: you receive two vegetables automatically. Other vegetable receipts do not trigger the Scoop."}
   {:id "diamond-mine" :card :catalog/original-expansion-wm-73-diamond-mine :name "Diamond Mine"
    :description "Three occupations are already played. Use Meeting Place to play Diamond Mine and see stone markers on the next two rounds."}
   {:id "encircling-wall" :card :catalog/original-expansion-fr-18-encircling-wall :name "Encircling Wall"
    :description "The four-stone cost has been paid. Click a farm space adjoining the existing pasture to fence it without paying wood, or decline."}
   {:id "stone-house-reconstruction" :card :catalog/original-expansion-fr-45-stone-house-reconstruction :name "Stone House Reconstruction"
    :description "Open the played card and renovate the clay house to stone. The normal renovation cost applies and no worker is placed."}
   {:id "swing-plow" :card :catalog/original-base-k-115-swing-plow
    :name "Swing Plow"
    :description "Paused after choosing Plow. Click your farm to choose up to two additional fields, or decline the plow. You control both players."}
   {:id "businesswoman" :card :catalog/original-expansion-g-25-businesswoman :name "Businesswoman"
    :description "Choose up to two improvements, paying each cost. Try two majors, two minors, or one of each; either offer can be declined."}
   {:id "singer" :card :catalog/original-expansion-g-50-singer :name "Singer"
    :description "Click resource icons to queue two building resources. Remove either queued choice or clear both, then take them together. The same resource may be chosen twice."}
   {:id "gardening-tools" :card :catalog/original-expansion-g-76-gardening-tools :name "Gardening Tools"
    :description "Partner has just sown. Pay one food to sow your own empty field, or decline. This interrupts Partner's turn without using your worker."}
   {:id "shovel" :card :catalog/original-expansion-g-110-shovel :name "Shovel"
    :description "Day Laborer has just supplied two food. Sow the prepared field for free, or decline."}
   {:id "debris-dealer" :card :catalog/original-expansion-wm-15-debris-dealer :name "Debris Dealer" :players 3
    :description "You live in clay. Renovate to stone to receive two clay afterward. You control all three players, so you can also test income from their renovations."}
   {:id "herdsman" :card :catalog/original-expansion-wm-28-herdsman :name "Herdsman"
    :description "Day Laborer in round six: choose a sheep or a wild boar. The animal fits as your house pet."}
   {:id "ruins" :card :catalog/original-expansion-wm-106-ruins :name "Ruins"
    :description "At the first harvest, take one of the four stone shown on Ruins, or leave it there. The remaining stock survives later harvests and reloads."}
   {:id "steamer" :card :catalog/original-expansion-wm-108-steamer :name "Steamer" :players 3
    :description "Open your played Steamer to exchange grain with vegetables or animals for food. Pelts is also in play: cooking an animal should offer its bonus."}
   {:id "baguette" :card :catalog/original-expansion-fr-7-baguette :name "Baguette"
    :description "The feeding phase is starting. See the one-wood entry cost and click grain icons to preview the food from your Clay Oven. Confirm the pictured exchange or decline. Wood is paid once per baking action."}
   {:id "barber-shop" :card :catalog/original-expansion-fr-8-barber-shop :name "Barber Shop"
    :description "Round eight: play Barber Shop from your hand via Meeting Place. Its four remaining harvests should award four bonus points immediately."}
   {:id "homework" :card :catalog/original-expansion-fr-32-homework :name "Homework"
    :description "Homework has been paid for. Play up to two occupations without an occupation-action fee. Singer's choices resolve before the second occupation offer."}
   {:id "trailblazer" :card :catalog/original-expansion-fr-113-trailblazer :name "Trailblazer"
    :description "At round start your only farm field is planted. Pay one food and click an adjacent farm space to plow, or decline. The empty Bean Field card does not block this."}
   {:id "foreman" :card :catalog/original-base-k-308-foreman :name "Foreman" :players 4
    :description "Click an action space on the shared board to leave food there, or decline. Occupied spaces can still be marked."}
   {:id "fence-builder" :card :catalog/original-base-i-263-fence-builder :name "Fence Builder" :players 4
    :description "Click a shared action space to place the fence marker. Taking that space later offers the fencing action."}
   {:id "fence-overseer" :card :catalog/original-base-k-312-fence-overseer :name "Fence Overseer" :players 4
    :description "A stable has just been built. Click that farm space to fence it for one food, or decline."}
   {:id "animal-breeder" :card :catalog/original-base-k-307-animal-breeder :name "Animal Breeder" :players 4
    :description "A pasture has just been fenced. Click the animal pair and food price you want, then confirm or decline."}
   {:id "resource-seller" :card :catalog/original-base-k-310-resource-seller :name "Resource Seller" :players 4
    :description "A combined wood and clay receipt is pending. Click the illustrated receipt order and compare its rewards."}
   {:id "silage" :card (cards/id :a084) :name "Silage" :players 2
    :description "Choose grain directly from the farm or supply, then click the animal species to breed. The prepared pasture has room."}
   {:id "stable-planner" :card (cards/id :a089) :name "Stable Planner" :players 2
    :description "Click future round cards to reserve stable pieces. Click again to remove a reservation, or decline."}
   {:id "harvest-helper" :card :catalog/original-base-i-223-harvest-helper :name "Harvest Helper" :players 4
    :description "At feeding, click a grain field on Partner’s farm. They receive two food; you receive one grain."}
   {:id "hide-farmer" :card :catalog/original-base-i-236-hide-farmer :name "Hide Farmer" :players 4
    :description "At final scoring, click unused farm spaces to cover them with food. Click again to remove a selection."}
   {:id "clearing-spade" :card (cards/id :a071) :name "Clearing Spade" :players 2
    :description "Open the played card. Click a planted field, then an empty farm field or Bean Field card, to move a crop."}
   {:id "asparagus-knife" :card (cards/id :a058) :name "Asparagus Knife" :players 2
    :description "Click the vegetable field to harvest from. You can also exchange the vegetable for food and a bonus point."}
   {:id "work-certificate" :card (cards/id :a082) :name "Work Certificate" :players 2
    :description "Click an eligible accumulation space on the shared board to take one building resource, or decline."}
   {:id "stagehand" :card (cards/id :a150) :name "Stagehand" :players 4
    :description "Partner has taken Traveling Players. Click rooms, stables, or fences, then build directly on your farm without spending a worker. Clay Roof and Clay Supports allow individual room payments."}
   {:id "cottager" :card (cards/id :b087) :name "Cottager" :players 2
    :description "After Day Laborer, click the room or renovation picture. Choose a new room directly on the farm, or renovate the house."}
   {:id "copse" :card :catalog/original-base-i-78-copse :name "Copse" :players 2
    :description "Choose Sow on the board, then click the wood stacks on the played Copse card. Each stack is individually selectable."}
])

(defn scenario [id]
  (or (some #(when (= id (:id %)) %) scenarios)
      (throw (ex-info "Choose a supported card test table." {:scenario id}))))

(defn options [id player-name]
  (let [definition (scenario id)]
    {:scenario id :name player-name :title (str "Test · " (:name definition))
     :mode :local :expected-players (:players definition 2) :bots 0 :turn-seconds 0 :setup {:mode :random}
     :names (into [player-name] (take (dec (:players definition 2)) (remove #{player-name} ["Partner" "Neighbor" "Visitor" "Guest"])))}))

(defn prepared-round [base round]
  (if (= 1 round) base
      ;; Reveal the real stage order through native round preparation.
      (nth (iterate #(game/drain (game/begin-round %)) base) (dec round))))

(defn put-in-hand [g pid card]
  (let [kind (:kind (cards/card card))
        g (update-in g [:players pid :hand kind] (fnil conj []) card)]
    (if (= kind :minor)
      (let [deck (vec (remove #{card} (:minor-deck g)))]
        (assoc g :minor-deck deck :minor-deck-count (count deck))) g)))

(defn played [g pid card]
  (game/drain (game/play-card (put-in-hand g pid card) pid card 0 {})))

(defn resolve-preparation [g choice]
  (game/command g (game/acting-player g) (merge {:type :resolve :decision (:decision-id g)} choice)))

(defn last-harvest-action [g]
  (-> g (assoc :turn 0)
      (update :players #(mapv (fn [p] (assoc p :remaining (if (zero? (:id p)) 1 0))) %))
      (game/command 0 {:type :action :space :grain})))

(defn initial-state [id names]
  (let [{:keys [card]} (scenario id)
        base (game/initialize (a/new-game names (vec (mapcat identity a/stages)) 0))
        funded (update base :players #(mapv (fn [p] (a/gain p {:food 12 :wood 8 :clay 8 :reed 6 :stone 8 :grain 3 :vegetable 2})) %))]
    (case id
      "clay-starter" (put-in-hand funded 0 card)
      "fence-helper" (-> funded (prepared-round 4) (played 0 card) (assoc :turn 1)
                          (game/command 1 {:type :action :space :fences :fences (vec (a/edges 0))}))
      "amazon" (-> funded (prepared-round 7) (played 0 card))
      "amazon-choice" (-> funded (prepared-round 13) (played 0 card)
                           (game/command 0 {:type :action :space :fishing}))
      "apprenticeship" (-> funded (played 1 (cards/id :a109))
                             (put-in-hand 0 :catalog/original-expansion-g-5-architect)
                             (put-in-hand 0 :catalog/original-expansion-g-49-seed-master)
                             (played 0 card))
      "camembert" (-> funded (played 0 (cards/id :a109)) (played 0 (cards/id :a115))
                       (put-in-hand 0 card) (game/command 0 {:type :action :space :meeting :card card}))
      "coffee-break" (-> funded (played 0 :catalog/original-expansion-wm-82-flower-pots)
                          (played 0 card) game/begin-round game/drain
                          (put-in-hand 0 :catalog/original-expansion-g-5-architect))
      "private-market" (played funded 0 card)
      "grain-market" (-> funded (prepared-round 4)
                           (update-in [:players 0] #(reduce (fn [p cell] (a/place-tile p :field cell)) % [0 1 2]))
                           (update-in [:players 0] a/sow [{:cell 0 :crop :grain} {:cell 1 :crop :grain} {:cell 2 :crop :grain}])
                           (played 0 card) last-harvest-action)
      "grain-elevator" (-> funded (played 0 card) (prepared-round 3))
      "grain-elevator-opponent" (assoc (initial-state "grain-elevator" names) :turn 1)
      "farm-school" (-> funded (prepared-round 4)
                          (played 0 (cards/id :a109)) (played 0 (cards/id :a115))
                          (played 0 :catalog/original-base-i-78-copse) (played 0 card)
                          (update-in [:players 0] a/place-tile :field 0)
                          (update-in [:players 0] a/sow [{:cell 0 :crop :grain}
                                                       {:cell :catalog/original-base-i-78-copse :crop :wood}])
                          (put-in-hand 0 :catalog/original-expansion-g-5-architect)
                          (put-in-hand 0 :catalog/original-expansion-g-49-seed-master)
                          last-harvest-action)
      "brickyard" (-> funded (played 0 (cards/id :a109)) (played 0 (cards/id :a115))
                        (played 0 card) (prepared-round 3) (assoc-in [:players 0 :goods :clay] 0)
                        game/begin-round game/drain
                        game/begin-round game/drain
                        game/begin-round game/drain
                        (assoc :turn 0)
                        (update :players #(mapv (fn [p] (assoc p :remaining (if (zero? (:id p)) 1 0))) %)))
      "journeyman" (-> funded (update-in [:players 0] a/renovate {})
                          (update-in [:players 0] a/renovate {})
                          (played 0 card) game/begin-round game/drain)
      "carrot-farmer" (-> funded (played 0 card) (game/command 0 {:type :action :space :plow :cell 0}))
      "pulley" (-> funded (played 0 card) (game/command 0 {:type :action :space :labor}))
      "food-connoisseur" (played funded 0 card)
      "oak-cask" (-> funded (prepared-round 4) (played 0 card) (assoc :turn 0)
                     (update :players #(mapv (fn [p] (assoc p :remaining (if (zero? (:id p)) 1 0))) %)))
      "child-care-worker" (-> funded (prepared-round 7) (played 0 card)
                               (update-in [:players 1] a/build-rooms [0])
                               (assoc :turn 1) (game/command 1 {:type :action :space :growth}))
      "oceanographer" (-> funded (played 0 card) (game/command 0 {:type :action :space :plow :cell 0}))
      "sheep-market" (-> funded (prepared-round 4) (played 0 (cards/id :a109)) (played 0 card)
                          (update-in [:players 0] a/gain {:sheep 1}) last-harvest-action)
      "wooden-bridge" (played funded 0 card)
      "edible-roots" (-> funded (prepared-round 4)
                           (update-in [:players 0] #(reduce (fn [p cell] (a/place-tile p :field cell)) % [0 1 2]))
                           (played 0 card)
                           (update-in [:players 0] a/sow [{:cell 0 :crop :grain} {:cell 1 :crop :vegetable} {:cell 2 :crop :grain}])
                           (assoc-in [:players 0 :farm 0 :count] 1) (assoc-in [:players 0 :farm 1 :count] 1)
                           (assoc :turn 0)
                           (update :players #(mapv (fn [p] (assoc p :remaining (if (zero? (:id p)) 1 0))) %)))
      "festival-hall" (-> funded (prepared-round 4)
                            (update-in [:players 0] #(-> % (a/place-tile :field 0) (a/place-tile :field 1)
                                                          (a/sow [{:cell 0 :crop :grain} {:cell 1 :crop :grain}])))
                            (played 0 card) last-harvest-action)
      "abandoned-willow" (-> funded
                               (update-in [:players 0] #(reduce (fn [p cell] (a/place-tile p :field cell)) % [0 1 2]))
                               (played 0 card))
      "powerhouse" (-> funded (played 0 card) (assoc-in [:players 0 :goods :stone] 5) (assoc :turn 0)
                       (update :players #(mapv (fn [p] (assoc p :remaining (if (zero? (:id p)) 1 0))) %)))
      "powerhouse-politician" (-> (initial-state "powerhouse" names)
                                 (played 0 :catalog/original-expansion-wm-41-politician)
                                 (assoc-in [:players 0 :goods :stone] 4)
                                 (game/command 0 {:type :action :space :grain}))
      "seed-spreader" (-> funded (played 0 (cards/id :a109)) (played 0 card)
                          (played 0 :catalog/original-base-i-78-copse)
                          (update-in [:players 0] a/place-tile :field 0)
                          (game/command 0 {:type :action :space :plow :cell 1}))
      "vegetable-scoop" (-> funded (prepared-round 8) (played 0 card))
      "diamond-mine" (-> funded (prepared-round 4)
                          (played 0 (cards/id :a109))
                          (played 0 (cards/id :a115))
                          (played 0 (cards/id :a117))
                          (put-in-hand 0 card))
      "encircling-wall" (-> funded (update-in [:players 0] a/build-fences (vec (a/edges 0))) (played 0 card))
      "stone-house-reconstruction" (-> funded (update-in [:players 0] a/renovate {}) (played 0 card))
      "foreman" (-> funded (played 0 card) game/begin-round game/drain)
      "fence-builder" (played funded 0 card)
      "fence-overseer" (-> funded (played 0 card) (game/command 0 {:type :action :space :build :stables [0]}))
      "animal-breeder" (-> funded (prepared-round 4) (played 0 card)
                            (game/command 0 {:type :action :space :fences :fences (vec (a/edges 0))}))
      "resource-seller"
      (-> funded (played 0 card)
          (game/run-effect (game/task 0 :catalog/original-base-k-284-wood-distributor {:op :gain :goods {:wood 1 :clay 1}} {}) {}) game/drain)
      "silage"
      (-> funded (update-in [:players 0] #(-> % (a/place-tile :field 2) (a/place-tile :field 3)
                                               (a/sow [{:cell 2 :crop :grain}])
                                               (a/build-fences (vec (distinct (mapcat a/edges [0 1])))) (a/gain {:sheep 2})))
          (played 0 card) (game/continue-step :return-home) game/drain)
      "stable-planner" (played funded 0 card)
      "harvest-helper"
      (-> funded (prepared-round 4) (played 0 card)
          (update-in [:players 1] #(-> % (a/place-tile :field 0) (a/sow [{:cell 0 :crop :grain}]))) last-harvest-action)
      "hide-farmer" (-> funded (played 0 card) (assoc :round 14 :phase :finish-round) game/finish-game game/drain)
      "clearing-spade"
      (-> funded (update-in [:players 0 :occupations] into (map cards/id [:a103 :a104]))
          (played 0 card) (played 0 :catalog/original-base-e-18-bean-field)
          (update-in [:players 0] #(-> % (a/place-tile :field 0) (a/place-tile :field 1)
                                       (a/sow [{:cell 0 :crop :vegetable}]))))
      "asparagus-knife"
      (-> funded (prepared-round 8) (played 0 card)
          (update-in [:players 0] #(-> % (a/place-tile :field 0) (a/sow [{:cell 0 :crop :vegetable}])))
          (game/continue-step :return-home) game/drain)
      "work-certificate"
      (-> funded (update-in [:players 0 :occupations] into (map cards/id [:a103 :a104 :a106]))
          (assoc-in [:piles :wood :wood] 4) (put-in-hand 0 card)
          (game/command 0 {:type :action :space :meeting :card card}))
      "stagehand"
      (-> funded (played 0 card) (played 0 :catalog/original-base-e-36-clay-roof)
          (played 0 :catalog/original-base-e-37-clay-supports) (assoc-in [:players 0 :house] :clay)
          (assoc :turn 1) (game/command 1 {:type :action :space :traveling}))
      "cottager" (-> funded (played 0 card) (game/command 0 {:type :action :space :labor}))
      "copse" (-> funded (prepared-round 4) (update-in [:players 0 :occupations] conj (cards/id :a103)) (played 0 card))
      "swing-plow"
      ;; Prepared ownership, then the real action pipeline creates the pending
      ;; choice and uncommitted base field. No synthetic decision queue.
      (-> base
          (assoc-in [:players 0 :minors] [card])
          (assoc-in [:players 0 :card-state card :uses] 2)
          (game/command 0 {:type :action :space :plow :cell 0}))
      "businesswoman"
      (-> funded (put-in-hand 0 card)
          (put-in-hand 0 :catalog/original-expansion-g-110-shovel)
          (put-in-hand 0 :catalog/original-expansion-fr-7-baguette)
          (game/command 0 {:type :action :space :lessons :card card}))
      "singer" (-> funded (put-in-hand 0 card) (game/command 0 {:type :action :space :lessons :card card}))
      "homework"
      (-> funded (put-in-hand 0 card)
          (put-in-hand 0 :catalog/original-expansion-g-50-singer)
          (put-in-hand 0 :catalog/original-expansion-g-49-seed-master)
          (game/command 0 {:type :action :space :meeting :card card}))
      "gardening-tools"
      (-> funded (prepared-round 4) (played 0 card)
          (update-in [:players 0] a/place-tile :field 0)
          (update-in [:players 1] a/place-tile :field 0)
          (assoc :turn 1)
          (game/command 1 {:type :action :space :sow-bake :plantings [{:cell 0 :crop :grain}]}))
      "shovel"
      (-> funded (played 0 card) (update-in [:players 0] a/place-tile :field 0)
          (game/command 0 {:type :action :space :labor}))
      "debris-dealer"
      (-> funded (prepared-round 7) (played 0 card) (assoc-in [:players 0 :house] :clay))
      "herdsman"
      (-> funded (prepared-round 6) (played 0 card) (game/command 0 {:type :action :space :labor}))
      "ruins"
      (-> funded (prepared-round 4) (played 0 card) last-harvest-action)
      "steamer"
      (-> funded (played 0 card)
          (played 0 :catalog/original-expansion-g-49-seed-master)
          (played 0 :catalog/original-expansion-wm-38-minimalist)
          (played 0 :catalog/original-base-e-197-braggart)
          (played 0 :catalog/original-base-k-339-pelts)
          (update-in [:players 0] a/build-fences (vec (distinct (mapcat a/edges [0 1]))))
          (update-in [:players 0] a/gain {:sheep 1 :boar 1 :cattle 1}))
      "baguette"
      (-> funded (prepared-round 4) (played 0 card)
          (a/build-improvement 0 {:improvement :clay-oven}) last-harvest-action)
      "barber-shop" (-> funded (prepared-round 8) (put-in-hand 0 card))
      "trailblazer"
      (-> funded (played 0 card) (played 0 :catalog/original-expansion-g-49-seed-master)
          (played 0 :catalog/original-base-e-18-bean-field)
          (update-in [:players 0] a/place-tile :field 0)
          (update-in [:players 0] a/sow [{:cell 0 :crop :grain}])
          game/begin-round game/drain))))
