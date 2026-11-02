(ns eurozone.card-scenarios
  "Prepared, playable positions for reviewing native card effects. Only registered
   scenarios may create test tables; callers cannot supply arbitrary game states."
  (:require [agricola.engine :as a] [agricola.card-game :as game] [agricola.card-rules :as cards]))

(def scenarios
  [{:id "swing-plow" :card :catalog/original-base-k-115-swing-plow
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
    :description "The feeding phase is starting. Pay one wood to bake with your Clay Oven, or decline. Wood is paid once, regardless of the amount baked."}
   {:id "barber-shop" :card :catalog/original-expansion-fr-8-barber-shop :name "Barber Shop"
    :description "Round eight: play Barber Shop from your hand via Meeting Place. Its four remaining harvests should award four bonus points immediately."}
   {:id "homework" :card :catalog/original-expansion-fr-32-homework :name "Homework"
    :description "Homework has been paid for. Play up to two occupations without an occupation-action fee. Singer's choices resolve before the second occupation offer."}
   {:id "trailblazer" :card :catalog/original-expansion-fr-113-trailblazer :name "Trailblazer"
    :description "At round start your only farm field is planted. Pay one food and click an adjacent farm space to plow, or decline. The empty Bean Field card does not block this."}])

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
