(ns eurozone.card-scenarios
  "Prepared, playable positions for reviewing native card effects. Only registered
   scenarios may create test tables; callers cannot supply arbitrary game states."
  (:require [agricola.engine :as a] [agricola.card-game :as game] [agricola.card-rules :as cards]))

(def scenarios
  [{:id "seed-spreader" :card :catalog/original-expansion-g-105-seed-spreader :name "Seed Spreader"
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
