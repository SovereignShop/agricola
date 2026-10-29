(ns agricola.card-game
  "Card-game orchestration around the shared farm rules. Effects and decisions
   are data, so interrupted choices survive saves and reconnects."
  (:require [agricola.engine :as a] [agricola.card-rules :as c] [agricola.five-player :as five] [agricola.card-expressions :as expr] [clojure.set :as set]))
(declare drain run-effect play-card begin-round advance finish-game take-action action-events)
(def ^:dynamic *effect-observer*
  "Optional diagnostic sink. It observes committed effects, never legality probes."
  nil)
(defn observe-effect! [head choice]
  (when *effect-observer*
    (*effect-observer* (assoc (select-keys head [:player :card :effect :context :window]) :choice choice))))
(defn p [g pid] (get-in g [:players pid]))
(defn owned? [g pid code] (c/has? (p g pid) code))
(defn queue-front [g tasks] (update g :effect-queue #(vec (concat tasks %))))
(defn task [pid card effect context] {:kind :effect :player pid :card card :effect effect :context context})
(defn event [kind pid & [context]] {:kind :event :event kind :player pid :context (or context {})})
(defn step [name] {:kind :continue :step name})
(defn acting-player [g] (or (:player (first (:effect-queue g))) (:turn g)))
(defn fields [p] (concat (filter #(= :field (:type %)) (:farm p)) (vals (:card-fields p))))
(defn crop-fields [p crop] (count (filter #(and (= crop (:crop %)) (pos? (or (:count %) 0))) (fields p))))
(defn harvested-crop-events [player cells]
  (for [cell cells :let [field (get-in player (a/field-path cell))]
        :when (and (:crop field) (pos? (:count field 0)))]
    (event :harvest-crop (:id player) {:cell cell :crop (:crop field) :last? (= 1 (:count field))})))
(defn condition? [g pid condition context]
  (let [p (p g pid) [kind x y] condition]
    (case kind
      nil true
      :test (boolean (expr/evaluate g pid context x))
      :house (= (:house p) x)
      :not-house (not= (:house p) x)
      :previous-house (= (:previous-house context) x)
      :rooms (= (count (a/tiles p :room)) x)
      :house-rooms (and (= (:house p) x) (= (count (a/tiles p :room)) y))
      :round (= (:round g) x)
      :after-harvest (boolean (a/harvest-rounds (dec (:round g))))
      :players-at-least (>= (count (:players g)) x)
      :occupied (contains? (:occupied g) x)
      :childless (and (>= (count (a/tiles p :room)) 3) (= 2 (:family p)))
      :only-two-rooms (and (= 2 (count (a/tiles p :room))) (every? #(or (= pid (:id %)) (> (count (a/tiles % :room)) 2)) (:players g)))
      :building-resources (>= (reduce + (for [r [:wood :clay :reed :stone]] (- (get-in p [:received r] 0) (get-in p [:work-baseline r] 0)))) x)
      :three-fields (and (pos? (crop-fields p :grain)) (pos? (crop-fields p :vegetable)) (some #(zero? (or (:count %) 0)) (fields p)))
      false)))
(defn prerequisites? [g pid card]
  (let [requirement (if (contains? card :requires) (:requires card) (expr/requirement (:prerequisites card)))]
    (and (some? requirement) (boolean (expr/evaluate g pid {} requirement)))))
(defn can-pay? [p cost] (every? (fn [[r n]] (>= (a/amount p r) n)) cost))
(defn trigger-tasks [g {:keys [event player context]}]
  (vec
   (mapcat
    (fn [owner]
      (mapcat
       (fn [cid]
         (when (or (nil? (:only-card context)) (= cid (:only-card context)))
         (mapcat
          (fn [trigger]
            (let [scope (or (:scope trigger) :self)
                  spaces (if (= cid (c/id :b056)) #{:wood :clay :reed (first (:round-order g))} (:spaces trigger))]
              (when (and (= event (:event trigger))
                         (case scope :all true :others (not= owner player) (= owner player))
                         (or (nil? spaces) (spaces (:space context)))
                         (or (not (:once? trigger)) (not (get-in g [:players owner :card-state cid :triggered?])))
                         (condition? g owner (:condition trigger) context))
                (map #(assoc (task owner cid % (assoc context :trigger-event event :actor player)) :once? (:once? trigger)) (or (:effects trigger) [(:effect trigger)])))))
          (:triggers (c/card cid)))))
       (c/active-owned (p g owner))))
    (map #(mod (+ (:starting-player g) %) (count (:players g))) (range (count (:players g)))))))
(defn schedule [g pid cid effect]
  (let [rounds (or (:rounds effect) (map #(+ (:round g) %) (or (:offsets effect) (range 1 (inc (expr/evaluate g pid {} (:offset-count effect))))))) goods (:goods effect)]
    (reduce (fn [g r]
              (if (< (:round g) r 15)
                (update-in g [:players pid :schedule r] (fnil conj []) {:card cid :goods goods}) g)) g rounds)))
(defn future-round-resources
  "Public board markers, grouped by round and owner. Use the same ownership
   rule as delivery; historical schedules remain in saves but not on the board."
  [g]
  (into (sorted-map)
        (for [round (range (inc (:round g)) 15)
              :let [owners
                    (vec (for [player (:players g)
                               :let [owned (set (c/owned player))
                                     deliveries (cond-> (filter #(owned (:card %)) (get-in player [:schedule round]))
                                                  (contains? (:well-rounds player) round)
                                                  (conj {:card :well :goods {:food 1}}))
                                     goods (into {} (filter (fn [[_ n]] (pos? n))
                                                            (apply merge-with + {} (map :goods deliveries))))]
                               :when (seq goods)]
                           {:player (:id player) :goods goods :cards (vec (distinct (map :card deliveries)))}))]
              :when (seq owners)]
          [round owners])))
(defn card-cost
  ([g pid card occupation-cost] (card-cost g pid card occupation-cost {}))
  ([g pid card occupation-cost options]
   (let [p (p g pid)
         discount #(a/discounted % {:wood (if (c/has? p :a075) 1 0) :stone (if (c/has? p :a143) 1 0)})]
     (cond
       (= :occupation (:kind card)) {:food occupation-cost}
       (:cost-options card)
       (let [costs (mapv discount (:cost-options card))
             index (if (some? (:payment-option options)) (:payment-option options)
                       (or (first (keep-indexed #(when (can-pay? p %2) %1) costs)) 0))]
         (a/require! (and (integer? index) (<= 0 index) (< index (count costs))) "Choose a printed payment option.")
         (nth costs index))
       :else (discount (case (:cost card)
                         :bottles {:clay (:family p) :food (:family p)}
                         (:fishing-person :clay-supply) {}
                         (:printed-cost card)))))))
(defn paid-card-player
  "Shared validation/payment for every route that plays a hand card. Pure: a
   rejected play cannot consume a worker, a card, or any resources."
  [g pid cid occupation-cost options]
  (let [card (c/card cid) player (p g pid)]
    (a/require! card "That card has no native rule definition.")
    (a/require! (<= (:min-players card) (count (:players g))) "This card requires more players.")
    (when (= :occupation (:kind card))
      (a/require! (not (c/has? player :b176)) "Village Idiot must remain your only occupation.")
      (when (get-in card [:passive :lone-occupation])
        (a/require! (empty? (:occupations player)) "Village Idiot must be your first and only occupation.")))
    (a/require! (some #{cid} (get-in player [:hand (:kind card)])) "That card is not in your hand.")
    (a/require! (prerequisites? g pid card) (str "Requires: " (:prerequisites card) "."))
    (when (= :fishing-person (:cost card))
      (a/require! (= pid (get-in g [:occupied :fishing])) "Brook requires one of your people on Fishing."))
    (when (= :clay-supply (:cost card))
      (a/require! (>= (a/amount player :clay) 5) "Thick Forest requires five clay in your supply; it does not spend them."))
    (let [player (if (and (= :occupation (:kind card)) (:paper-maker? options))
                   (do (a/require! (c/has? player :b109) "You do not own Paper Maker.")
                       (-> player (a/pay {:wood 1}) (a/gain {:food (count (:occupations player))}))) player)]
      (a/pay player (card-cost g pid card occupation-cost options)))))
(defn play-card
  ([g pid cid occupation-cost options] (play-card g pid cid occupation-cost options nil))
  ([g pid cid occupation-cost options timing]
  (let [card (c/card cid) before (p g pid) kind (:kind card)]
    (when (:major-action-only? card)
      (a/require! (:major-action? timing) "This card requires a Major Improvement action."))
    (let [player (-> (paid-card-player g pid cid occupation-cost options)
                     (update-in [:hand kind] #(vec (remove #{cid} %)))
                     (update (if (= kind :occupation) :occupations :minors) (fnil conj []) cid))
          player (cond-> player
                   (get-in card [:passive :lone-occupation]) (assoc-in [:hand :occupation] [])
                   (get-in card [:passive :card-field]) (assoc-in [:card-fields cid] {:type :field :allowed-crop (get-in card [:passive :card-field]) :count 0}))
          g (assoc-in g [:players pid] player)
          after (cond-> [(event (if (= kind :occupation) :occupation :build-improvement) pid {:card cid})
                          (event :check-state pid)]
                  (and (= :round-start (:event timing)) (:scholar-round-start? card))
                  (conj (event :round-start pid {:only-card cid :existing-window (:window timing)}))
                  (:passing? card) (conj {:kind :pass-card :player pid :card cid}))]
      (-> g (queue-front (concat (map #(task pid cid % {}) (:play card)) after))
          (a/log-event (str (:name player) " played " (:name card))))))))
(defn needs-input? [g {:keys [player effect]}]
  (or (:optional? effect)
      (and (= :pay-or-beg (:op effect)) (< (a/amount (p g player) :food) (:food effect)))
      (#{:ability-window :choose :plow :mini-pasture :stable :choose-good :bake :housing :cottage :scholar} (:op effect))
      (and (= :seasonal-crop (:op effect)) (>= (:round g) 6))))
(defn run-effect* [g {:keys [player card effect context window]} choice]
  (let [pid player current (p g pid) op (:op effect) cost (expr/quantities g pid context (or (:cost effect) {}))]
    (if (:skip? choice)
      (do (a/require! (:optional? effect) "This effect is mandatory.") g)
      (let [g (update-in g [:players pid] a/pay cost)]
        (case op
          :ability-window g
          :gain (update-in g [:players pid] a/gain (expr/quantities g pid context (:goods effect)))
          :exchange (update-in g [:players pid] a/gain (expr/quantities g pid context (:goods effect)))
          :proportional-gain (update-in g [:players pid] a/gain {(:resource effect) (quot (a/amount current (:resource effect)) (:divisor effect))})
          :points (update-in g [:players pid :bonus-points] (fnil + 0) (expr/evaluate g pid context (:amount effect)))
          :sequence (queue-front g (map #(task pid card % context) (:effects effect)))
          :choose (let [index (:option choice)]
                    (a/require! (and (integer? index) (<= 0 index) (< index (count (:options effect)))) "Choose an offered card effect.")
                    (let [option (nth (:options effect) index)]
                      (a/require! (expr/evaluate g pid context (get option :when true)) "That option is unavailable.")
                      (queue-front (update-in g [:players pid] a/pay (expr/quantities g pid context (:cost option)))
                                   (map #(task pid card % context) (:effects option)))))
          :schedule (schedule g pid card (update effect :goods #(expr/quantities g pid context %)))
          :remaining-rounds (-> g (update-in [:players pid] a/gain (into {} (map (fn [[r n]] [r (* n (- 14 (:round g)))]) (:goods effect))))
                                 (update-in [:players pid :bonus-points] (fnil + 0) (* (or (:points effect) 0) (- 14 (:round g)))))
          :all-gain (update g :players #(mapv (fn [p] (a/gain p (if (= pid (:id p)) (:owner effect) (:others effect)))) %))
          :return-goods (-> g (update-in [:players pid] a/gain (:goods effect))
                             (update-in [:piles (:space context)] #(merge-with + % cost)))
          :choose-good (do (a/require! (some #{(:resource choice)} (:choices effect)) "Choose one of the offered resources.")
                           (update-in g [:players pid] a/gain {(:resource choice) 1}))
          :seasonal-crop (let [resource (if (>= (:round g) 6) (:resource choice) :grain)]
                           (a/require! (#{:grain :vegetable} resource) "Choose grain or vegetables.")
                           (update-in g [:players pid] a/gain {resource 1}))
          :harpoon (update-in g [:players pid] a/gain {:food (:family current) :reed 1})
          :extra-animal (update-in g [:players pid] a/gain {(:space context) 1})
          :roof-stone (update-in g [:players pid] a/gain {:stone (count (a/tiles current :room))})
          :consultant (update-in g [:players pid] a/gain (nth [{:grain 2} {:clay 3} {:reed 2} {:sheep 2}] (min 3 (dec (count (:players g))))))
          :house-steward (update-in g [:players pid] a/gain {:wood (count (filter #(<= % (- 14 (:round g))) [1 3 6 9]))})
          :tutor-start (assoc-in g [:players pid :card-state card :occupations-before] (count (:occupations current)))
          :stock (assoc-in g [:players pid :card-state card :stock] (:goods effect))
          :set-uses (assoc-in g [:players pid :card-state card :uses] (:uses effect))
          :conditional (if (condition? g pid (:condition effect) context)
                         (run-effect g (task pid card (:effect effect) context) choice) g)
          :loom (update-in g [:players pid] a/gain {:food (count (filter #(>= (a/amount current :sheep) %) [1 4 7]))})
          :dairy (update-in g [:players pid] a/gain {:food (+ (quot (a/amount current :sheep) 3) (quot (a/amount current :cattle) 2))})
          :plow (do (when (:limited? effect) (a/require! (pos? (get-in g [:players pid :card-state card :uses] 0)) "This card has no uses left."))
                    (cond-> (update-in g [:players pid] a/place-tile :field (:cell choice))
                      (:limited? effect) (update-in [:players pid :card-state card :uses] dec)))
          :stable (do (when (:single-pasture? effect)
                        (a/require! (some #(and (= 1 (count %)) (= (:cell choice) (first %))) (a/pastures current))
                                    "Choose a pasture covering exactly one farmyard space."))
                      (update-in g [:players pid] a/build-stables [(:cell choice)] 0))
          :renovate (-> g (update-in [:players pid] a/renovate choice {:free? (:free? effect)})
                        (queue-front [(event :renovate pid {:previous-house (:house current)}) (event :check-state pid)]))
          :give-to-actor (let [actor (:actor context)]
                           (a/require! (and (integer? actor) (<= 0 actor) (< actor (count (:players g))) (not= pid actor))
                                       "This effect needs another player's action.")
                           (update-in g [:players actor] a/gain (expr/quantities g pid context (:goods effect))))
          :mini-pasture (let [cell (:cell choice)]
                          (a/require! (a/cell? cell) "Choose a farmyard space.")
                          (let [edges (vec (remove (:fences current) (a/edges cell)))]
                            (update-in g [:players pid] a/build-fences edges {:free-fences (count edges)})))
          :bake (do (a/require! (and (integer? (:grain choice)) (pos? (:grain choice))) "Bake at least one grain, or skip this effect.")
                    (-> g (update-in [:players pid] a/bake (:grain choice)) (queue-front [(event :bake pid)])))
          :adopt (do (a/require! (and (= :work (:phase g)) (pos? (:newborn current 0))) "Adopt a newborn during the work phase.")
                     (-> g (update-in [:players pid :remaining] inc) (update-in [:players pid :newborn] dec)))
          :pay-or-beg (let [owed (:food effect) available (min owed (a/amount current :food))]
                        (-> g (update-in [:players pid] a/pay {:food available})
                              (update-in [:players pid :begging] + (- owed available))))
          :add-field-crops (reduce (fn [state cell]
                                    (let [path (a/field-path cell) field (get-in current path)]
                                      (if (and (:crop field) (pos? (:count field 0)))
                                        (update-in state (into [:players pid] (conj path :count)) + (:amount effect)) state)))
                                  g (concat (a/tiles current :field) (keys (:card-fields current))))
          :shepherd-crook (let [old (set (:old-pastures context)) new (filter #(and (>= (count %) 4) (not (old %))) (a/pastures current))]
                            (update-in g [:players pid] a/gain {:sheep (* 2 (count new))}))
          :extra-grain-harvest
          (let [eligible (vec (for [i (concat (range 15) (keys (:card-fields current)))
                                    :let [field (get-in current (a/field-path i))]
                                    :when (and (= :grain (:crop field)) (pos? (get field :count 0)))] i))
                chosen (get choice :fields eligible)]
            (a/require! (and (sequential? chosen) (= (count chosen) (count (set chosen)))
                             (every? (set eligible) chosen)) "Choose grain fields that still contain grain.")
            (queue-front (reduce (fn [g i] (-> g (update-in (into [:players pid] (conj (a/field-path i) :count)) dec)
                                                (update-in [:players pid] a/gain {:grain 1}))) g chosen)
                         (harvested-crop-events current chosen)))
          :breed-boar (let [next (a/gain current {:boar 1})]
                        (if (and (>= (a/amount current :boar) 2) (a/animal-housing next)) (assoc-in g [:players pid] next) g))
          :housing (let [keep (:keep choice) _ (a/quantities! keep a/animals)
                         _ (a/require! (= (set a/animals) (set (keys keep))) "Choose how many of each species to keep.")
                         surplus (into {} (for [animal a/animals] [animal (- (a/amount current animal) (keep animal))]))
                         _ (a/quantities! surplus a/animals)
                         next (if (:cook? choice) (a/convert-food current surplus) (a/pay current surplus))]
                     (a/require! (a/animal-housing next) "Those animals do not fit on your farm.")
                     (assoc-in g [:players pid] next))
          :cottage (do (a/require! (#{:room :renovate} (:action choice)) "Choose building a room or renovating.")
                    (if (= :room (:action choice))
                     (queue-front (update-in g [:players pid] a/build-rooms [(:cell choice)] choice) [(event :build-rooms pid (when (= 5 (count (:players g))) {:rooms-built 1}))])
                     (queue-front (update-in g [:players pid] a/renovate choice) [(event :renovate pid {:previous-house (:house current)}) (event :check-state pid)])))
          :scholar (play-card g pid (:card choice) 1 choice {:event (:trigger-event context) :window window})
          :boat-painter (do (a/require! (#{:grain :food} (:resource choice)) "Choose grain or food.")
                            (update-in g [:players pid] a/gain {(:resource choice) (if (= :food (:resource choice)) 2 1)}))
          :clay-thief (-> (five/collect-pile g pid :hollow :clay) (assoc-in [:players pid :card-state card :face-down?] true))
          (:corral-builder :amateur-fencer) (run-effect g (task pid card {:op :mini-pasture} context) choice)
          :wild-boar-hunter (update-in g [:players pid] a/gain {:boar 1})
          :trapper (update-in g [:players pid] a/gain {(nth [:sheep :boar :cattle] (- (five/occupied-wood g) 2)) 1})
          :plowsmith (run-effect g (task pid card {:op :plow} context) choice)
          :fast-mason (-> g (update-in [:players pid :goods :reed] inc)
                          (update-in [:players pid] a/renovate (dissoc choice :brushwood? :renovate-to))
                          (queue-front [(event :renovate pid {:previous-house (:house current)}) (event :check-state pid)]))
          :countryman (do (a/require! (= 1 (count (:plantings choice))) "Sow exactly one field.")
                           (update-in g [:players pid] a/sow (:plantings choice)))
          :young-artist
          (if (= :draw (:action choice))
            (let [drawn (vec (take 2 (:minor-deck g)))]
              (a/require! (pos? (get g :minor-deck-count (count drawn))) "No minor improvements remain in the deck.")
              (-> g (update-in [:players pid :hand :minor] into drawn)
                    (update :minor-deck #(vec (drop 2 %))) (update :minor-deck-count #(max 0 (- % 2)))))
            (do (a/require! (= :minor (:kind (c/card (:card choice)))) "Choose a minor improvement.")
                (play-card g pid (:card choice) 0 choice)))
          :tag-along
          (let [owners (get-in g [:occupied :market]) owners (if (vector? owners) owners [owners])
                after (-> g (update-in [:players pid :remaining] dec)
                          (update-in [:players pid] a/gain (:gain (a/action-definition g :market)))
                          (assoc-in [:occupied :market] (conj owners pid)))]
            (queue-front after [(event :use-space pid {:space :market}) (step :check-all)]))
          :sidekick
          (let [space (five/stage-left g (:space context)) cmd (assoc (:action-command choice) :type :action :space space)
                turn (:turn g) queue (:effect-queue g)
                after (take-action (assoc g :turn pid :effect-queue []) pid cmd)]
            ;; Preserve the parent's one turn-advance continuation, after this action's effects.
            (assoc after :turn turn :effect-queue
                   (vec (concat (remove #(and (= :continue (:kind %)) (= :advance (:step %))) (:effect-queue after)) queue))))
          (five/automatic g pid card op context))))))

(defn run-effect [g {:keys [player effect context] :as head} choice]
  (a/require! (or (:skip? choice) (five/eligible? g player (:op effect) context)) "This card effect is not available now.")
  (let [before (count (a/tiles (p g player) :field)) after (run-effect* g head choice)
        plowed (- (count (a/tiles (p after player) :field)) before)
        fenced? (not= (:fences (p g player)) (:fences (p after player)))
        stables (- (count (filter :stable (:farm (p after player)))) (count (filter :stable (:farm (p g player)))))]
    (cond-> after
      (and (pos? stables) (= :work (:phase g)) (= player (:turn g)))
      (queue-front [(event :build-stables player {:stables-built stables})])
      (and (pos? plowed) (not (#{:plowsmith :corral-builder :amateur-fencer :sidekick} (:op effect))))
      (queue-front [(event :plow player {:fields-plowed plowed})])
      (and fenced? (= :mini-pasture (:op effect)))
      (queue-front [(event :fence player {:old-pastures (a/pastures (p g player)) :fences-built (- (count (:fences (p after player))) (count (:fences (p g player))))})]))))

(defn housing-tasks [g]
  (for [player (:players g) :when (nil? (a/animal-housing player))]
    (task (:id player) nil {:op :housing} {})))
(def conversion-phases #{:preparation :work :return-home :field-harvest :feeding :finish-round})
(defn funding-conversion [g pid cost]
  (let [player (p g pid) missing (max 0 (- (:food cost 0) (a/amount player :food)))
        rates (a/cook-rates player)
        [goods deficit]
        (reduce (fn [[goods deficit] resource]
                  (let [rate (get rates resource 0) spare (max 0 (- (a/amount player resource) (get cost resource 0)))
                        units (if (and (pos? deficit) (pos? rate)) (min spare (quot (+ deficit rate -1) rate)) 0)]
                    [(cond-> goods (pos? units) (assoc resource units)) (- deficit (* units rate))]))
                [{} missing] [:grain :vegetable :sheep :boar :cattle])]
    (when (and (or (empty? goods) (conversion-phases (:phase g))) (<= deficit 0)
               (can-pay? (a/convert-food player goods) cost)) goods)))
(defn possible-effect? [g {:keys [player effect] :as head}]
  (let [op (:op effect) cost (expr/quantities g player (:context head) (:cost effect))
        conversion (when (:optional? effect) (funding-conversion g player cost))
        probe (if (seq conversion) (update-in g [:players player] a/convert-food conversion) g)
        choices (cond
                  (#{:plow :mini-pasture :stable} op) (map #(hash-map :cell %) (range 15))
                  (= :bake op) [{:grain 1}]
                  (= :renovate op) [{} {:renovate-to :stone}]
                  :else nil)]
    (and (five/eligible? g player op (:context head))
         (or (not= :adopt op) (and (= :work (:phase g)) (pos? (:newborn (p g player) 0))))
         (or (not (:optional? effect)) (some? conversion))
         (or (nil? choices)
             (some (fn [choice] (try (run-effect probe head choice) true
                                     (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false))) choices)))))
(defn with-housing [g]
  (let [g (five/refresh g) already (set (keep #(when (= :housing (get-in % [:effect :op])) (:player %)) (:effect-queue g)))]
    (queue-front g (remove #(already (:player %)) (housing-tasks g)))))
(defn anytime-available? [player cid]
  (case (get-in (c/card cid) [:ability :op])
    :sheep-exchange (pos? (a/amount player :sheep))
    :porcelain (>= (a/amount player :clay) 2)
    :buy-stock (and (seq (get-in player [:card-state cid :stock]))
                    (or (pos? (a/amount player :food))
                        (some (fn [[resource rate]] (and (pos? rate) (pos? (a/amount player resource)))) (a/cook-rates player))))
    :reed-food (pos? (a/amount player :reed))
    :cash-card (pos? (get-in player [:card-state cid :food] 0))
    :free-placement-fences (and (< (count (:fences player)) 15) (pos? (a/amount player :wood)))
    :move-crop (and (some #(>= (:count % 0) 2) (fields player)) (some #(zero? (:count % 0)) (fields player)))
    false))
(defn anytime-tasks [g]
  (for [offset (range (count (:players g)))
        :let [pid (mod (+ (:starting-player g) offset) (count (:players g))) player (p g pid)]
        :when (some #(anytime-available? player %) (c/active-owned player))]
    (task pid nil {:op :ability-window} {})))
(defn promote-available-effect [g head]
  ;; A temporary inability to pay must not consume this timing opportunity.
  ;; Run another currently available card in the same owner's window first.
  (when (:window head)
    (when-let [other (first (filter #(and (= :effect (:kind %)) (= (:window %) (:window head))
                                         (= (:player %) (:player head)) (not= (:card %) (:card head))
                                         (possible-effect? g %)) (rest (:effect-queue g))))]
      (let [chosen? #(and (= (:window %) (:window other)) (= (:player %) (:player other)) (= (:card %) (:card other)))
            queue (:effect-queue g)]
        (assoc g :effect-queue (vec (concat (filter chosen? queue) (remove chosen? queue))))))))
(defn continue-step [g name]
  (case name
    :advance (advance g)
    :after-harvest (queue-front g (concat (anytime-tasks g) [(step :finish-round)]))
    :finish-round (queue-front g (concat (map #(event :round-end (:id %)) (:players g)) [(step :next-round)]))
    :next-round (if (= 14 (:round g)) (finish-game g) (begin-round g))
    :work (-> g (assoc :phase :work) (update :players #(mapv (fn [p] (assoc p :work-baseline (:received p))) %))
              (queue-front (map #(event :work-start (:id %)) (:players g))))
    :return-home (queue-front (assoc g :phase :return-home) (concat (map #(event :return-home (:id %)) (:players g)) [(step :harvest-or-round)]))
    :harvest-or-round (if (a/harvest-rounds (:round g))
                        (queue-front g (concat (map #(event :before-field-phase (:id %)) (:players g)) [(step :field-harvest)]))
                        (queue-front g [(step :finish-round)]))
    :field-harvest (-> g (assoc :phase :field-harvest) (update :players #(mapv a/field-harvest %))
                      (queue-front (concat (mapcat #(harvested-crop-events % (concat (a/tiles % :field) (keys (:card-fields %)))) (:players g))
                                           (map #(event :field-phase (:id %) {:harvested-grain-fields (crop-fields % :grain)
                                                                              :harvested-vegetable-fields (crop-fields % :vegetable)}) (:players g)) [(step :feeding)])))
    :feeding (-> g (assoc :phase :feeding :turn (:starting-player g) :fed #{} :bred #{}) (a/log-event "Harvest · feed each family, then breed animals.")
                 (queue-front (map #(event :feeding-start (:id %)) (:players g))))
    :check-all (queue-front g (map #(event :check-state (:id %)) (:players g)))
    g))
(defn drain [g]
  (loop [g g steps 0]
    (a/require! (< steps 1000) "Card effects did not settle.")
    (if-let [head (first (:effect-queue g))]
      (let [rest-state (update g :effect-queue #(vec (rest %)))]
        (case (:kind head)
          :event (let [window (or (get-in head [:context :existing-window]) (inc (get g :effect-window 0)))
                       tasks (trigger-tasks g head)
                       ;; Taking goods happens before optional exchanges that say
                       ;; "immediately after" using the space.
                       tasks (sort-by #(if (#{:gain :all-gain} (get-in % [:effect :op])) 0 1) tasks)]
                   (recur (queue-front (assoc rest-state :effect-window (max window (:effect-window g 0))) (map #(assoc % :window window) tasks)) (inc steps)))
          :continue (recur (continue-step rest-state (:step head)) (inc steps))
          :pass-card (let [pid (:player head) cid (:card head) n (count (:players g))]
                       (recur (cond-> (update-in rest-state [:players pid :minors] #(vec (remove #{cid} %)))
                                (> n 1) (update-in [:players (mod (inc pid) n) :hand :minor] conj cid)) (inc steps)))
          :effect (cond
                    (not (possible-effect? g head))
                    (recur (or (promote-available-effect g head)
                               (a/log-event rest-state (str (or (:name (c/card (:card head))) "Card") " · no legal use of this effect."))) (inc steps))
                    (needs-input? g head) g
                    :else
                      (let [g (run-effect rest-state head {})
                            _ (observe-effect! head {})
                            g (cond-> g (:once? head) (assoc-in [:players (:player head) :card-state (:card head) :triggered?] true))]
                        (recur (with-housing g) (inc steps))))))
      g)))
(defn order-options [g]
  (let [head (first (:effect-queue g))]
    (when (:window head)
      (vec (distinct (keep #(when (and (= :effect (:kind %)) (= (:window head) (:window %))
                                      (= (:player head) (:player %))) (:card %)) (:effect-queue g)))))))
(defn reorder-effect [g pid cid]
  (let [head (first (:effect-queue g))
        index (first (keep-indexed #(when (and (= cid (:card %2)) (= (:window head) (:window %2)) (= pid (:player %2))) %1) (:effect-queue g)))]
    (a/require! (and (:window head) (= pid (:player head)) (some? index)) "Choose one of your simultaneous card effects.")
    (if (zero? index) g
        (let [queue (:effect-queue g)
              chosen? #(and (= cid (:card %)) (= (:window head) (:window %)) (= pid (:player %)))]
          (-> g (assoc :effect-queue (vec (concat (filter chosen? queue) (remove chosen? queue))))
              (update :decision-id (fnil inc 0)))))))
(defn resolve-choice [g pid choice]
  (let [head (first (:effect-queue g))]
    (a/require! (and (= :effect (:kind head)) (= pid (:player head))) "It is another player's card decision.")
    (a/require! (= (:decision choice) (:decision-id g)) "That decision has already been resolved.")
    (let [next (-> g (update :effect-queue #(vec (rest %))) (run-effect head choice)
                   (update :decision-id (fnil inc 0)))
          next (cond-> next (:once? head) (assoc-in [:players pid :card-state (:card head) :triggered?] true))]
      (observe-effect! head choice)
      (with-housing next))))
(defn advance [g]
  (let [n (count (:players g)) pid (:turn g) lasso (:lasso g)
        next (first (filter #(pos? (get-in g [:players % :remaining])) (map #(mod (+ pid %) n) (range 1 (inc n)))))]
    (cond
      (and lasso (not (:second? lasso)) (pos? (get-in g [:players pid :remaining]))) (assoc-in g [:lasso :second?] true)
      (some? next) (-> g (assoc :turn next) (dissoc :lasso))
      :else (-> g (dissoc :lasso) (queue-front [(step :return-home)])))))
(defn begin-round [g]
  (let [replenished? (and (pos? (get-in g [:piles :reed :reed] 0)) (= 1 (get-in (a/action-definition g :reed) [:gain :reed])))
        g (assoc (a/begin-round g) :phase :preparation) r (:round g)
        arrivals (mapcat (fn [p]
                           (for [{:keys [card goods]} (get-in p [:schedule r]) :when (some #{card} (c/owned p))]
                             (task (:id p) card (if (:field goods) {:op :plow :optional? true} {:op :gain :goods goods}) {}))) (:players g))]
    (queue-front g (concat arrivals (map #(event :round-start (:id %) {:reed-replenished-on-nonempty? replenished?}) (:players g)) [(step :check-all) (step :work)]))))
(defn initialize [g]
  (let [used (set (mapcat #(concat (get-in % [:hand :minor]) (:minors %)) (:players g)))
        reserve (vec (remove used (or (:minor-order g) (sort (map :id (filter #(= :minor (:kind %)) (vals c/cards)))))))]
  (-> (cond-> g (= 5 (count (:players g)))
              (assoc :minor-deck reserve :minor-deck-count (count reserve) :major-builds {}))
      (assoc :variant :cards :defer-advance? true :effect-queue [] :decision-id 0 :round 0
             :active (a/base-actions (count (:players g)) :cards) :piles {} :occupied {} :log [])
      (update :players #(mapv (fn [p] (merge {:occupations [] :minors [] :card-state {} :schedule {} :card-fields {} :bonus-points 0} p)) %))
      begin-round drain)))
(defn card-bonus [g pid cid]
  (let [p (p g pid) {:keys [type thresholds values resource divisor]} (:score (c/card cid))]
    (case type
      :expression (expr/evaluate g pid {} (get-in (c/card cid) [:score :value]))
      :pasture-spaces (count (filter #(<= % (count (a/pasture-cells p))) thresholds))
      :house (get values (:house p) 0)
      :unfenced-stables (count (filter #(and (get-in p [:farm % :stable]) (not ((a/pasture-cells p) %))) (range 15)))
      :improvements (let [n (+ (count (:improvements p)) (count (:minors p))) qualifying (count (filter #(<= % n) thresholds))]
                      (if (zero? qualifying) 0 (nth values (dec qualifying))))
      :animals (quot (a/amount p resource) divisor)
      :tutor (- (count (:occupations p)) (get-in p [:card-state cid :occupations-before] (count (:occupations p))))
      :most-rooms (if (= (count (a/tiles p :room)) (apply max (map #(count (a/tiles % :room)) (:players g)))) 3 0)
      :organic-pastures (count (filter #(and (= :pasture (:kind %)) (pos? (:count %)) (>= (- (:capacity %) (:count %)) 3)) (a/animal-housing p)))
      0)))
(defn score [g pid]
  (let [p (p g pid) score (a/score p) points (reduce + 0 (map #(or (:points (c/card %)) 0) (:minors p)))
        bonus (+ (or (:bonus-points p) 0) (reduce + 0 (map #(card-bonus g pid %) (c/owned p)))
                 (reduce + 0 (for [other (:players g) :when (not= pid (:id other))
                                   cid (c/owned other) :when (:global-score? (c/card cid))]
                               (card-bonus g pid cid)))
                 (if (and (not (c/has? p :b136)) (some #(c/has? % :b136) (:players g))) (card-bonus g pid (c/id :b136)) 0))]
    (-> score (update :total + points bonus) (update-in [:breakdown :improvements] + points) (update-in [:breakdown :bonus] + bonus))))
(defn finish-game [g]
  (let [scores (mapv #(score g (:id %)) (:players g)) best (last (sort (map (juxt :total :tiebreak) scores)))
        winners (vec (keep-indexed #(when (= best ((juxt :total :tiebreak) %2)) %1) scores))]
    (-> g (assoc :phase :finished :scores scores :winners winners) (a/log-event "The final harvest is complete."))))
(defn validate-space [g pid space]
  (a/require! (= :work (:phase g)) "Finish the current phase first.")
  (a/require! (= pid (:turn g)) "It is another player's turn.")
  (a/require! (a/available-space? g space) "That space is not available yet or its linked space is occupied.")
  (a/require! (pos? (:remaining (p g pid))) "No family members remain at home.")
  (let [owner (get-in g [:occupied space])]
    (a/require! (or (nil? owner) (and (owned? g pid :a026) (#{:growth :urgent-growth :modest-growth} space) (integer? owner) (not= pid owner))) "That action space is occupied."))
  (when (and (:second? (:lasso g)) (:must-animal? (:lasso g)))
    (a/require! (#{:sheep :boar :cattle :animal-market} space) "The second Lasso action must use an animal market.")))
(defn mark-worker [g pid space]
  (-> g (update-in [:players pid :remaining] dec) (assoc-in [:occupied space] pid)
      (a/log-event (str (:name (p g pid)) " · " (or (:name (a/action-definition g space)) (:name (c/card space)))))))
(defn action-events [before after pid cmd]
  (let [p0 (p before pid) p1 (p after pid) space (:space cmd)
        context {:space space :previous-house (:house p0) :old-pastures (a/pastures p0)
                 :before-goods (:goods p0)
                 :placed-person (- (:family p1) (:newborn p1 0) (:remaining p1))
                 :building-pile-size (reduce + (map #(get-in before [:piles space %] 0) [:wood :clay :reed :stone]))
                 :upgraded-fireplace? (boolean (:upgrade cmd))
                 :rooms-built (- (count (a/tiles p1 :room)) (count (a/tiles p0 :room)))
                 :stables-built (- (count (filter :stable (:farm p1))) (count (filter :stable (:farm p0))))
                 :fences-built (- (count (:fences p1)) (count (:fences p0)))
                 :fields-plowed (- (count (a/tiles p1 :field)) (count (a/tiles p0 :field)))
                 :taken (when (:accumulate (a/action-definition before space))
                          (let [pile (get-in before [:piles space])]
                            (if (:part-time? cmd) (into {} (map (fn [[r n]] [r (quot n 2)]) pile)) pile)))}]
    (concat
     (when (pos? (:fields-plowed context)) [(event :plow pid context)])
     (when (pos? (:stables-built context)) [(event :build-stables pid context)])
     (when (> (count (a/tiles p1 :room)) (count (a/tiles p0 :room))) [(event :build-rooms pid context)])
     (when (not= (:house p0) (:house p1)) [(event :renovate pid context)])
     (when (> (:family p1) (:family p0)) [(event :grow pid context)])
     (when (> (count (:fences p1)) (count (:fences p0))) [(event :fence pid context)])
     (when (seq (set/difference (:improvements p1) (:improvements p0))) [(event :build-improvement pid context)])
     (when (and (= 5 (count (:players before))) (seq (set/difference (:improvements p1) (:improvements p0)))) [(event :major-built pid context)])
     (when (and (pos? (or (:bake-grain cmd) 0))
                (or (= :sow-bake space)
                    (and (#{:improvement :renovate} space)
                         (:oven (a/improvements (:improvement cmd)))
                         (contains? (:improvements p1) (:improvement cmd)))))
       [(event :bake pid context)])
     [(event :use-space pid context) (event :after-use-space pid context)
      (event :turn-end pid context) (step :check-all) (step :advance)])))
(defn take-action [g pid {:keys [space card lasso?] :as cmd}]
  (validate-space g pid space)
  (a/require! (not (and card (:improvement cmd))) "Choose one major or minor improvement, not both.")
  (when lasso?
    (a/require! (and (owned? g pid :b024) (not (:lasso g))) "Lasso is unavailable.")
    (a/require! (>= (:remaining (p g pid)) 2) "Lasso requires two people available to place.")
    (a/require! (or (#{:sheep :boar :cattle :animal-market} space)
                    (some #(and (some #{%} (:active g)) (not (contains? (:occupied g) %))) [:sheep :boar :cattle :animal-market]))
                "There is no free animal market for the Lasso's second action."))
  (let [occupied (get-in g [:occupied space])
        base (cond-> (five/refresh g) occupied (update :occupied dissoc space))
        base (if (:master-hora? cmd)
               (do (a/require! (and (owned? g pid :a174) (five/linked-groups space)) "Master Hora needs a linked extension space.")
                   (update-in base [:players pid] #(-> % (a/pay {:food 1}) (a/gain {:vegetable 1})))) base)
        base (if (:animal-brander? cmd)
               (do (a/require! (and (owned? g pid :a180) (= space :animal-market)) "Animal Brander needs Animal Market.")
                   (update-in base [:players pid] a/pay {:food 1})) base)
        pile (get-in g [:piles space]) total (reduce + (vals pile))
        base (if (:part-time? cmd)
               (do (a/require! (and (owned? g pid :d180) (:accumulate (a/action-definition g space)) (#{2 4 6} total)) "Part-Time Worker needs exactly 2, 4, or 6 accumulated goods.")
                   (assoc-in base [:piles space] (into {} (map (fn [[r n]] [r (quot n 2)]) pile)))) base)
        minor? (and card (= :minor (:kind (c/card card))))
        special? (or (#{:lessons :lessons-extra :lessons-copse :lessons-growth :meeting} space) (and (= :improvement space) minor?))
        after (if special?
                (mark-worker (cond-> base (= space :meeting) (assoc :starting-player pid)) pid space)
                (a/apply-action base pid cmd))
        after (cond-> after
                (:animal-brander? cmd) (update-in [:players pid] #(-> % (a/pay (if (= :cattle (:choice cmd)) {:food 1} {}))
                                                                              (a/gain (merge {(:choice cmd) 1} (when (= :sheep (:choice cmd)) {:food 1})))))
                (:part-time? cmd) (assoc-in [:piles space] (into {} (map (fn [[r n]] [r (quot n 2)]) pile)))
                (:part-time? cmd) (update-in [:players pid] a/gain {({2 :sheep 4 :boar 6 :cattle} total) 1})
                (and (= 5 (count (:players g))) (seq (set/difference (:improvements (p after pid)) (:improvements (p g pid))))) (update-in [:major-builds pid] (fnil inc 0)))
        after (cond-> after occupied (assoc-in [:occupied space] [occupied pid])
                      lasso? (assoc :lasso {:player pid :must-animal? (not (#{:sheep :boar :cattle :animal-market} space)) :second? false}))
        after (queue-front after (action-events g after pid cmd))
        count-occ (count (:occupations (p g pid)))
        after (cond
                (#{:lessons :lessons-extra :lessons-copse :lessons-growth} space)
                (do (a/require! (= :occupation (:kind (c/card card))) "Choose an occupation from your hand.")
                    (let [cost (case space :lessons (if (zero? count-occ) 0 1)
                                     :lessons-copse 2 :lessons-growth (if (< count-occ 2) 1 2)
                                     (if (and (= 4 (count (:players g))) (< count-occ 2)) 1 2))
                          after (cond-> after (and (= 5 (count (:players g))) (pos? cost))
                                        (queue-front [(event :lessons-paid pid {:space space})]))]
                      (play-card after pid card cost cmd)))
                (and card (#{:meeting :growth :renovate :improvement} space))
                (do (a/require! minor? "This action allows a minor improvement from your hand.") (play-card after pid card 0 cmd {:major-action? (boolean (#{:improvement :renovate} space))}))
                card (throw (ex-info "That action does not allow playing a hand card." {}))
                :else after)]
    (with-housing after)))
(defn card-spaces [g pid]
  (vec (for [owner (:players g) cid (c/active-owned owner)
             :let [kind (:action-space (c/card cid))]
             :when (and kind (or (= pid (:id owner)) (= kind :fold-builder)))]
         {:card cid :owner (:id owner) :kind kind})))
(defn card-space-error [g pid cid]
  (let [{:keys [kind]} (first (filter #(= cid (:card %)) (card-spaces g pid)))
        rounds (vec (drop (count (a/base-actions (count (:players g)) (:variant g))) (:active g)))]
    (cond
      (nil? kind) "You cannot use this card action space."
      (contains? (:occupied g) cid) "This card action space is occupied."
      (and (= kind :greenhouse) (not-any? (set (:active g)) [:fences :renovate :vegetable])) "None of this card's actions have been revealed."
      (and (= kind :substitute-teacher) (not-every? #(contains? (:occupied g) %) five/lessons)) "All three Lessons spaces must be occupied, not merely blocked."
      (and (= kind :bullcatcher) (not (and (get rounds 5) (contains? (:occupied g) (get rounds 2)) (contains? (:occupied g) (get rounds 5))))) "The round 3 and 6 spaces must both be occupied."
      :else nil)))
(defn card-action [g pid {:keys [card-space action] :as cmd}]
  (a/require! (not (and (:card cmd) (:improvement cmd))) "Choose one improvement.")
  (when (:card cmd) (a/require! (= :minor (:kind (c/card (:card cmd)))) "Choose a minor improvement."))
  (a/require! (and (= :work (:phase g)) (= pid (:turn g)) (pos? (:remaining (p g pid)))) "You need a person and your work turn.")
  (a/require! (nil? (card-space-error g pid card-space)) (card-space-error g pid card-space))
  (let [{:keys [kind owner]} (first (filter #(= card-space (:card %)) (card-spaces g pid)))
        base (five/refresh g)
        after
        (case kind
          :greenhouse
          (do (a/require! (and (#{:fences :renovate :vegetable} action) (some #{action} (:active g))) "Choose an already revealed action on Greenhouse Builder.")
              (let [shadow (-> base (update :occupied dissoc action))
                    next (a/apply-action shadow pid (assoc cmd :space action))]
                (cond-> (assoc next :occupied (assoc (:occupied base) card-space pid))
                  (and (= action :renovate) (:card cmd)) (play-card pid (:card cmd) 0 cmd {:major-action? true}))))
          :fold-builder
          (let [base (if (= pid owner) base (-> base (update-in [:players pid] a/pay {:food 1}) (update-in [:players owner] a/gain {:food 1})))]
            (-> base (update-in [:players pid] a/build-fences (:fences cmd) (select-keys cmd [:fence-material]))
                     (update-in [:players pid] a/gain {:sheep 1}) (mark-worker pid card-space)))
          :substitute-teacher
          (do (a/require! (#{:resources :crops} (:bundle cmd)) "Choose all four building resources or both crops.")
              (-> base (update-in [:players pid] a/gain (if (= :resources (:bundle cmd)) {:wood 1 :clay 1 :reed 1 :stone 1} {:grain 1 :vegetable 1})) (mark-worker pid card-space)))
          :bullcatcher (-> base (update-in [:players pid] a/gain {:cattle 1 :food 2}) (mark-worker pid card-space)))
        after (cond-> after (seq (set/difference (:improvements (p after pid)) (:improvements (p g pid)))) (update-in [:major-builds pid] (fnil inc 0)))]
    (-> after (queue-front (action-events g after pid (assoc cmd :space card-space))) with-housing)))
(defn ability [g pid {:keys [card resource amount from to] :as cmd}]
  (let [player (p g pid) op (get-in (c/card card) [:ability :op])]
    (a/require! (some #{card} (c/active-owned player)) "You do not own that card.")
    (a/require! (#{:preparation :work :return-home :field-harvest :feeding :breeding :housing :finish-round} (:phase g)) "This ability is unavailable during the current phase.")
    (case op
      :adopt (do (a/require! (and (= :work (:phase g)) (pos? (:newborn player 0))) "Adopt a newborn during the work phase.")
                 (-> g (update-in [:players pid] a/pay {:food 1})
                       (update-in [:players pid :remaining] inc) (update-in [:players pid :newborn] dec)))
      :cash-card (-> g (update-in [:players pid] a/gain {:food (get-in player [:card-state card :food] 0)})
                      (assoc-in [:players pid :card-state card :food] 0)
                      (assoc-in [:players pid :card-state card :face-down?] true))
      :reed-food (update-in g [:players pid] #(-> % (a/pay {:reed 1}) (a/gain {:food 2})))
      :free-placement-fences (update-in g [:players pid] a/build-fences (:fences cmd) (select-keys cmd [:fence-material]))
      :buy-stock (let [stock (get-in player [:card-state card :stock])]
                   (a/require! (seq stock) "The Grocer's stock is empty.")
                   (-> g (update-in [:players pid] #(-> % (a/pay {:food 1}) (a/gain {(peek stock) 1})))
                       (update-in [:players pid :card-state card :stock] pop)))
      :porcelain (do (a/require! (#{2 3 4} amount) "Exchange two, three, or four clay.")
                     (update-in g [:players pid] #(-> % (a/pay {:clay amount}) (a/gain {:stone (dec amount)}))))
      :sheep-exchange (do (a/require! (#{:boar :vegetable :stone} resource) "Choose boar, vegetable, or stone.")
                          (update-in g [:players pid] #(-> % (a/pay {:sheep 1}) (a/gain {resource 1}))))
      :move-crop (let [source-path (a/field-path from) target-path (a/field-path to)
                      source (get-in player source-path) target (get-in player target-path)]
                   (a/require! (and (not= from to) (= :field (:type source) (:type target))
                                    (>= (get source :count 0) 2) (zero? (get target :count 0))
                                    (or (nil? (:allowed-crop target)) (= (:crop source) (:allowed-crop target))))
                               "Move one crop from a field with at least two to an empty field that allows that crop.")
                   (-> g (update-in (into [:players pid] (conj source-path :count)) dec)
                       (assoc-in (into [:players pid] target-path) (assoc target :crop (:crop source) :count 1))))
      (throw (ex-info "This card has no anytime ability." {})))))
(defn command [g pid {:keys [type] :as cmd}]
  (a/require! (not= :finished (:phase g)) "This game has finished.")
  (a/require! (not= :draw (:phase g)) "Complete the draw first.")
  (a/require! (or (empty? (:effect-queue g)) (#{:resolve :order-effect :ability :convert} type)) "Resolve the pending card effect first.")
  (a/require! (or (#{:ability :convert} type) (= pid (acting-player g))) "It is another player's turn or card decision.")
  (let [result
        (case type
          :order-effect (reorder-effect g pid (:card cmd))
          :resolve (resolve-choice g pid cmd)
          :action (take-action g pid cmd)
          :card-action (card-action g pid cmd)
          ;; Hayward places fences without taking a fencing action. Check state
          ;; milestones here, but do not synthesize action triggers from deltas.
          :ability (-> (ability g pid cmd) (queue-front [(step :check-all)]) with-housing)
          :feed (a/feed g pid cmd)
          :breed (let [next (a/breed g pid cmd)]
                   (if (= :finish-round (:phase next))
                     (queue-front next (concat [(event :check-state pid)]
                                               (map #(event :harvest-end (:id %)) (:players next)) [(step :after-harvest)]))
                     (queue-front next [(event :check-state pid)])))
          :convert (do (a/require! (conversion-phases (:phase g)) "Food conversion is unavailable during this phase.")
                       (a/require! (some pos? (vals (:goods cmd))) "Choose goods to convert.")
                       (update-in g [:players pid] a/convert-food (:goods cmd)))
          (throw (ex-info "Unknown card-game command." {})))]
    (drain result)))
