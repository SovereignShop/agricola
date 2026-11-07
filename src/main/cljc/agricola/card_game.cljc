(ns agricola.card-game
  "Card-game orchestration around the shared farm rules. Effects and decisions
   are data, so interrupted choices survive saves and reconnects."
  (:require [agricola.engine :as a] [agricola.fr-fifteen :as fr] [agricola.house-rules :as house] [agricola.card-rules :as c] [agricola.five-player :as five] [agricola.card-expressions :as expr] [agricola.card-reactive :as reactive] [agricola.card-random :as random] [clojure.set :as set]))
(declare paid-food-conversion possible-effect? drain run-effect play-card begin-round advance finish-game take-action take-action-complete staged-sowing? action-events take-action-with-sowing extra-action-spaces execute-extra-action finish-action-context card-spaces card-action record-newborn adopt-newborn freshman-available? resume-freshman-source resume-serf with-housing plow-options source-action-goods placed-people game-has-cards? remove-worker space-owners)
(def ^:dynamic *effect-observer*
  "Optional diagnostic sink. It observes committed effects, never legality probes."
  nil)
(def ^:dynamic *impact-observer* nil)
(def ^:dynamic *perpetual-draw?* false)
(def ^:dynamic *hand-food-discount-used* 0)
(defn hand-food-discount [player] (reduce + 0 (c/passives player :hand-food-discount)))
(defn observe-effect! [head choice before after]
  (when *impact-observer* (*impact-observer* before after head choice))
  (when *effect-observer*
    (*effect-observer* (assoc (select-keys head [:player :card :effect :context :window]) :choice choice))))
(defn p [g pid] (get-in g [:players pid]))
(declare source-action-goods*
         record-placement crop-harvest manure-harvest prepare-worker-placement prepare-placement preparation-reconsider start-relocations relocation-origins relocation-spaces)
(defn harvest-participant? [g pid] (not (contains? (:harvest-skipped g #{}) pid)))
(def manure-animal-food-cards
  #{:catalog/original-base-e-51-spindle :catalog/original-base-e-53-butter-churn
    :catalog/original-base-i-93-milking-shed :catalog/original-base-i-246-milking-hand
    :catalog/original-base-k-133-milking-stool :catalog/original-base-k-146-loom
    :revised-base/a033 :revised-base/b039 :revised-base/b050})
(defn harvest-card-allowed? [g pid cid context]
  (and (or (harvest-participant? g pid) (get-in (c/card cid) [:passive :slaughter-income]))
       (not (and (:manure? context) (contains? manure-animal-food-cards cid)))))
(defn plow-effect
  "Upgrade pre-original-deck Wheel Plow decisions saved without selectable plows."
  [effect]
  (if (and (= :wheel-plow (:op effect)) (not (contains? effect :plows)))
    (assoc effect :plows [(c/id :a018)]) effect))
(def original-lasso :catalog/original-base-i-88-lasso)
(defn lasso-animal-spaces [player]
  (cond (c/has? player :b024) #{:sheep :boar :cattle :animal-market}
        (c/has? player original-lasso) #{:sheep :boar :cattle}
        :else #{}))
(defn owned? [g pid code] (c/has? (p g pid) code))
(defn queue-front [g tasks] (update g :effect-queue #(vec (concat tasks %))))
(defn task [pid card effect context] {:kind :effect :player pid :card card :effect effect :context context})
(defn event [kind pid & [context]] {:kind :event :event kind :player pid :context (or context {})})
(declare event)
(def salesman-id :catalog/original-base-k-281-traveling-salesman)
(defn salesman-turn [g] [(:round g) (or (get-in g [:action-context :phase]) (:phase g)) (:turn-serial g 0)])
(defn salesman-available? [g pid action]
  (and (owned? g pid salesman-id) (#{:meeting :growth :improvement :renovate} (:origin-space action))
       (not (:original-scholar? action)) (not (:suppress-finish? action))
       (contains? (:kinds action) :minor)
       (or (not= (salesman-turn g) (get-in g [:players pid :card-state salesman-id :used-turn]))
           (and (:businessman? action) (:merchant-repeat? action)
                (= (salesman-turn g) (get-in g [:players pid :card-state salesman-id :businessman-turn]))))))
(defn salesman-action [g pid action choice]
  (let [inherited (:salesman-mode action)
        requested? (or inherited (:salesman? choice))]
    (if-not requested? [g action]
      (let [_ (a/require! (or (and inherited (:merchant-repeat? action)) (salesman-available? g pid action))
                          "Traveling Salesman is available once per turn on a board improvement action, never Scholar.")
            mode (or inherited (if (contains? (:kinds action) :major) :two-minors :major))
            action (assoc action :salesman-mode mode :kinds (if (= mode :major) #{:major} #{:minor}))
            state (cond-> (assoc-in g [:players pid :card-state salesman-id :used-turn] (salesman-turn g))
                    (:businessman? action) (assoc-in [:players pid :card-state salesman-id :businessman-turn] (salesman-turn g)))]
        [state action]))))
(defn improvement-finished-event [pid action repeated?]
  (event (cond (:suppress-finish? action) :salesman-intermediate
               (= :two-minors (:salesman-mode action)) :salesman-finish
               :else :improvement-action-finished) pid
         {:improvement-action (select-keys action [:kinds :major-ids :major-cost :minimum-paid :origin-space :businessman? :salesman-mode])
          :businessman? (:businessman? action)
          :origin-space (:origin-space action) :minor-played? (true? (:minor-played? action))
          :minor-action-allowed? (or (:salesman-mode action) (contains? (:kinds action) :minor))
          :merchant-repeat? (boolean repeated?)}))
(defn step [name] {:kind :continue :step name})
(defn acting-player [g] (or (:player (first (:effect-queue g))) (:turn g)))
(defn fields [p] (filter #(= :field (:type %)) (concat (:farm p) (vals (:card-fields p)))))
(defn crop-fields [p crop] (count (filter #(and (= crop (:crop %)) (pos? (or (:count %) 0))) (fields p))))
(defn harvested-crop-events [player cells]
  (for [cell cells :let [field (get-in player (a/field-path cell))]
        :when (and (:crop field) (pos? (:count field 0)))]
    (let [last? (and (= 1 (:count field))
                     (not (and (= :vegetable (:crop field))
                               (some true? (c/passives player :preserve-harvest-vegetables)))))]
      (event :harvest-crop (:id player)
             (cond-> {:cell cell :crop (:crop field) :last? last?}
               ;; Snapshot before harvesting: another queued effect may sow this field.
               (some true? (c/passives player :observe-empty-harvest))
               (assoc :emptied-field? (and last? (= 1 (reduce + 0 (vals (a/field-crops field)))))))))))
(defn businessman-available? [g pid context]
  (let [cid :catalog/original-base-i-228-businessman
        key [(:round g) (or (get-in g [:action-context :phase]) (:phase g)) (:turn-serial g 0)]
        marker (if (:merchant-repeat? context) :repeat-turn :last-turn)]
    (and (= :meeting (:origin-space context)) (:minor-played? context)
         (not (:businessman? context))
         (not= key (get-in g [:players pid :card-state cid marker])))))
(defn condition? [g pid condition context]
  (let [p (p g pid) [kind x y] condition]
    (case kind
      nil true
      :businessman (businessman-available? g pid context)
      :test (boolean (expr/evaluate g pid context x))
      :house (= (:house p) x)
      :not-house (not= (:house p) x)
      :previous-house (= (:previous-house context) x)
      :rooms (= (count (a/tiles p :room)) x)
      :house-rooms (and (= (:house p) x) (= (count (a/tiles p :room)) y))
      :round (= (:round g) x)
      :non-harvest-round (not (a/harvest-rounds (:round g)))
      :own-turn (and (= :work (:phase g)) (= pid (:turn g)))
      :after-harvest (boolean (a/harvest-rounds (dec (:round g))))
      :players-at-least (>= (count (:players g)) x)
      :occupied (contains? (:occupied g) x)
      :accommodated-taken (let [taken (select-keys (:taken context) a/animals)]
                            (and (not (get-in p [:card-state (c/id :a017) :declined-pile?])) (some pos? (vals taken)) (some? (a/animal-housing p))
                                 (every? (fn [[animal n]] (>= (a/amount p animal) n)) taken)))
      :childless (and (>= (count (a/tiles p :room)) 3) (= 2 (:family p)))
      :only-two-rooms (and (= 2 (count (a/tiles p :room))) (every? #(or (= pid (:id %)) (> (count (a/tiles % :room)) 2)) (:players g)))
      :building-resources (>= (reduce + (for [r [:wood :clay :reed :stone]] (- (get-in p [:received r] 0) (get-in p [:work-baseline r] 0)))) x)
      :three-fields (expr/evaluate g pid context [:and [:> [:grain-fields] 0] [:> [:vegetable-fields] 0] [:> [:empty-fields] 0]])
      false)))
(defn prerequisites? [g pid card]
  (let [requirement (if (contains? card :requires) (:requires card) (expr/requirement (:prerequisites card)))]
    (and (some? requirement) (boolean (expr/evaluate g pid (when (= :minor (:kind card)) {:occupation-purpose :minor-prerequisite}) requirement)))))
(defn can-pay? [p cost] (every? (fn [[r n]] (>= (a/amount p r) n)) cost))
(defn animal-accommodation-tasks [g pid context]
  (when (some pos? (vals (select-keys (:taken context) a/animals)))
    (concat
     (when (and (owned? g pid :a017) (not (get-in g [:players pid :card-state (c/id :a017) :triggered?])))
       [(task pid (c/id :a017) {:op :housing} (assoc context :reclamation? true))])
     [(event :animals-accommodated pid context)])))
(defn public-hand-response-tasks [g {:keys [event player context]}]
  ;; Everyone receives the same public window, whether or not they hold the
  ;; response card. Declining must not reveal another player's private hand.
  (for [cid c/public-hand-response-cards
        :let [response (:public-hand-response (c/card cid))]
        offset (range (count (:players g)))
        :let [owner (mod (+ (:starting-player g) offset) (count (:players g)))]
        :when (and (or (not= cid :catalog/original-expansion-g-9-baker-s-daughter)
                       (game-has-cards? g #{cid}))
                   (= event (:event response))
                   (case (:scope response) :others (not= owner player) :all true (= owner player))
                   (condition? g owner (:condition response) context))]
    (task owner nil {:op :play-hand-response :card cid :optional? true} (assoc context :actor player))))
(defn water-mill-tasks [g pid context]
  (for [owner (:players g) cid (c/active-owned owner)
        :when (get-in (c/card cid) [:passive :water-mill])]
    (task pid cid {:op :water-mill :optional? true :cost {:grain 1} :owner (:id owner)} context)))
(defn trigger-tasks [g {:keys [event player context] :as source}]
  (vec
   (concat (mapcat
    (fn [owner]
      (mapcat
       (fn [cid]
         (when (or (nil? (:only-card context)) (= cid (:only-card context)))
         (mapcat
          (fn [trigger]
            (let [scope (or (:scope trigger) :self)
                  spaces (if (= cid (c/id :b056)) #{:wood :clay :reed (first (:round-order g))} (:spaces trigger))]
              (when (and (= event (:event trigger)) (harvest-card-allowed? g owner cid context)
                         (case scope :all true :others (not= owner player) (= owner player))
                         (or (nil? spaces) (spaces (:space context))
                             (and (= spaces #{:copse :grove :wood :riverbank})
                                  (= :master-forester (:action-space (c/card (:space context))))))
                         (or (not (:once? trigger)) (not (get-in g [:players owner :card-state cid :triggered?])))
                         (condition? g owner (:condition trigger) context))
                (map (fn [effect]
                       (let [effect (if (:inherit-improvement-action? effect)
                                      (merge (:improvement-action context) effect) effect)
                             effect (cond-> effect (= :reveal-rounds (:op effect)) (assoc :round-order (:round-order g)))]
                         (assoc (task owner cid effect (assoc context :trigger-event event :actor player)) :once? (:once? trigger))))
                     (remove #(and (= event :round-start) (#{:wood-distributor :foreman :taster} (:op %))
                                   (not (:preparation-reconsider? context)))
                             (or (:effects trigger) [(:effect trigger)]))))))
          (:triggers (c/card cid)))))
       (c/active-owned (p g owner))))
    (map #(mod (+ (:starting-player g) %) (count (:players g))) (range (count (:players g)))))
           (:arrival-effects context)
           (public-hand-response-tasks g source)
           (when (= event :after-field-phase) (water-mill-tasks g player context))
           (when (= event :salesman-finish)
             [(task player salesman-id {:op :card-action :kinds #{:minor} :optional? true
                                         :suppress-finish? true :origin-space (:origin-space context)} {})
              (assoc source :event :improvement-action-finished)]))))
(defn schedule [g pid cid effect]
  (let [rounds (or (:rounds effect) (map #(+ (:round g) %) (or (:offsets effect) (range 1 (inc (expr/evaluate g pid {} (:offset-count effect))))))) goods (:goods effect)]
    (reduce (fn [g r]
              (if (< (:round g) r 15)
                (update-in g [:players pid :schedule r] (fnil conj []) (cond-> {:card cid :goods goods} (:effect effect) (assoc :effect (:effect effect)) (:arrival-cost effect) (assoc :arrival-cost (:arrival-cost effect)) (:marker-goods effect) (assoc :marker-goods (:marker-goods effect)))) g)) g rounds)))
(defn remove-lodger-person [player kind]
  (a/require! (#{:adult :newborn} kind) "Choose an adult or newborn person to remove.")
  (a/require! (pos? (if (= kind :newborn) (:newborn player 0) (- (:family player) (:newborn player 0))))
              "There is no person of that kind to remove.")
  (cond-> (-> player (update :family dec) (update :removed-family (fnil inc 0)))
    (= kind :newborn) (update :newborn dec)))
(defn planner-rounds
  ([g] (planner-rounds g {}))
  ([g effect] (filterv #(< (:round g) % 15) (map #(+ (:round g) %) (:offsets effect [3 6 9])))))
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
                                                  (conj {:card :well :goods {:food (a/well-food player round)}}))
                                     goods (into {} (filter (fn [[_ n]] (pos? n))
                                                            (apply merge-with + {} (map #(cond (:stable %) {:stable 1} (:person %) {:person 1} :else (or (:marker-goods %) (get-in % [:effect :marker-goods]) (when (seq (:goods %)) (:goods %)) (get-in % [:effect :goods]) (when (= :plow (get-in % [:effect :op])) {:field 1}))) deliveries))))]
                               :when (seq goods)]
                           {:player (:id player) :goods goods :cards (vec (distinct (map :card deliveries)))}))]
              :when (seq owners)]
          [round owners])))

(def g-headmaster :catalog/original-expansion-g-30-headmaster)
(def g-animal-farm :catalog/original-expansion-g-62-animal-farm)
(def g-bountiful :catalog/original-expansion-g-67-bountiful-harvest)
(def g-pond-watchman :catalog/original-expansion-g-46-pond-watchman)
(defn headmaster-food [player] (if (c/has? player g-headmaster) (get-in player [:card-state g-headmaster :stored-goods :food] 0) 0))
(defn spend-headmaster [player n]
  (if (pos? n) (update-in player [:card-state g-headmaster :stored-goods :food] - n) player))
(defn headmaster-payment [g pid food options]
  (let [maximum (min food (headmaster-food (p g pid))) n (if (some? (:headmaster-food options)) (:headmaster-food options) maximum)]
    (a/require! (and (a/natural? n) (<= n maximum)) "Use at most the occupation's cost from Headmaster.") n))

(defn chocolate-food-available [player]
  (reduce + 0 (for [cid (c/active-owned player) :when (get-in (c/card cid) [:passive :occupation-stock-food])]
                (get-in player [:card-state cid :stored-goods :food] 0))))
(defn occupation-stock-payment [g pid occupation-cost options]
  (let [player (p g pid) regular (max 0 (- occupation-cost (max 0 (- (hand-food-discount player) *hand-food-discount-used*))))
        maximum (min regular (chocolate-food-available player)) n (get options :chocolate-food maximum)]
    (a/require! (and (a/natural? n) (<= n maximum)) "Use stored Chocolade food only for the regular occupation fee, never additional card costs.") n))
(defn chocolate-payment-limit [g pid options]
  (let [head (first (:effect-queue g)) effect (:effect head) count-occ (c/occupation-count (p g pid) nil)
        fee (if head (get effect :occupation-cost 0)
                (case (:space options) :lessons (if (zero? count-occ) 0 1) :lessons-copse 2
                      :lessons-growth (if (< count-occ 2) 1 2) :lessons-extra (if (and (= 4 (count (:players g))) (< count-occ 2)) 1 2) 0))]
    (occupation-stock-payment g pid fee (dissoc options :chocolate-food))))
(defn spend-occupation-stock [player n]
  (first (reduce (fn [[p left] cid] (let [used (min left (get-in p [:card-state cid :stored-goods :food] 0))]
                                    [(if (pos? used) (update-in p [:card-state cid :stored-goods :food] - used) p) (- left used)]))
                 [player n] (filter #(get-in (c/card %) [:passive :occupation-stock-food]) (c/active-owned player)))))
(defn storage-shed-payment [player options minor?]
  (if (and minor? (some true? (c/passives player :storage-shed)))
    (let [resource (get options :storage-shed-resource :wood)]
      (a/require! (#{:wood :clay :reed :stone} resource) "Choose the building resource received from Storage Shed before payment.")
      (a/gain player {resource 1})) player))
(defn card-cost
  ([g pid card occupation-cost] (card-cost g pid card occupation-cost {}))
  ([g pid card occupation-cost options]
   (let [p (p g pid)
         remaining-discount (max 0 (- (hand-food-discount p) *hand-food-discount-used*))
         discount (fn [cost opts] (a/special-improvement-cost p (:id card)
                     (a/discounted cost (merge-with + (a/hand-improvement-discounts p card)
                                                  (when (and (= :minor (:kind card)) (not (:as-major? options)) (pos? remaining-discount)) {:food remaining-discount}))) opts))]
     (cond
       (= :occupation (:kind card))
       (let [wood (get options :occupation-wood 0)
             total-food (+ (- (max 0 (- occupation-cost remaining-discount)) (occupation-stock-payment g pid occupation-cost options)) (:additional-food-cost card 0))]
         (a/require! (and (a/natural? wood) (<= wood total-food)
                          (or (zero? wood) (c/has? p :a028))) "Forest School can replace each occupation-cost food with one wood.")
         {:food (- total-food wood (headmaster-payment g pid (- total-food wood) options)) :wood wood})
       (:cost-options card)
       (let [costs (:cost-options card)
             explicit (:payment-option options)
             cost-at (fn [i] (discount (nth costs i) (assoc options :payment-option i)))]
         (if (some? explicit)
           (do (a/require! (and (integer? explicit) (<= 0 explicit) (< explicit (count costs))) "Choose a printed payment option.")
               (cost-at explicit))
           (let [valid (keep (fn [i] (try [i (cost-at i)] (catch #?(:clj Exception :cljs :default) _ nil))) (range (count costs)))]
             (or (second (first (filter #(can-pay? p (second %)) valid)))
                 (second (first valid)) (cost-at 0)))))
       :else (discount (case (:cost card)
                         :double-turn-plow (cond-> {:grain 1} (> (:round g) 3) (assoc :food 1))
                         :bottles {:clay (:family p) :food (:family p)}
                         (:fishing-person :clay-supply :return-two-improvements) {}
                         (:return-cooking :return-oven :return-hearth :return-well :return-fireplace :return-joinery :return-pottery :return-basketry) (:printed-cost card)
                         (:printed-cost card)) options)))))
(defn card-return-options [player cid]
  (remove #(contains? (:pawned-improvements player #{}) %)
   (case (:cost (c/card cid))
    :return-cooking (a/returnable-cooking player)
    :return-oven (a/returnable-ovens player)
    :return-hearth (a/returnable-hearths player)
    :return-well (concat (when (contains? (:improvements player) :well) [:well]) (filter #(get-in (c/card %) [:passive :is-well]) (:minors player)))
    :return-fireplace (a/fireplace-upgrades player)
    :return-basketry (when (contains? (:improvements player) :basketry) [:basketry])
    :return-pottery (when (contains? (:improvements player) :pottery) [:pottery])
    :return-joinery (when (contains? (:improvements player) :joinery) [:joinery])
    :return-two-improvements (sort (a/owned-improvement-ids player))
    nil)))
(defn card-return-pairs [player]
  (let [ids (vec (sort (remove (:pawned-improvements player #{}) (a/owned-improvement-ids player))))]
    (for [i (range (count ids)) j (range (inc i) (count ids))] [(ids i) (ids j)])))
(defn selected-card-returns [player cid options]
  (let [eligible (card-return-options player cid)
        count-needed (case (:cost (c/card cid)) :return-two-improvements 2 (:return-cooking :return-oven :return-hearth :return-well :return-fireplace :return-joinery :return-pottery :return-basketry) 1 0)
        chosen (case count-needed
                 2 (get options :return-improvements (vec (take 2 eligible)))
                 1 [(or (:return-improvement options) (first eligible))]
                 [])]
    (a/require! (and (sequential? chosen) (= count-needed (count chosen)) (= (count chosen) (count (set chosen)))
                     (every? (set eligible) chosen)) "Return the required number of distinct improvements you own.")
    (cond-> (vec chosen) (:grain-mill-oven? options)
      (conj (do (a/grain-mill-payment? player cid options) (a/grain-mill-oven-id player))))))
(defn remove-owned-improvement [player cid]
  (a/require! (not (contains? (:pawned-improvements player #{}) cid)) "Pawnbroker prevents returning or discarding a pawned improvement.")
  ;; Previously earned resources/points remain. Goods stored on discarded cards,
  ;; their future deliveries and their continuing effects leave with the card.
  (cond-> (-> player
              (update :improvements disj cid)
              (update :minors #(vec (remove #{cid} %)))
              (update :card-state dissoc cid)
              (update :farm #(mapv (fn [cell] (cond (= cid (:card cell)) {:type :empty}
                                                   (= cid (:blocked-card cell)) (dissoc cell :blocked-card :marker-goods) :else cell)) %))
              (update :card-fields #(into {} (remove (fn [[key field]] (or (= key cid) (= (:card field) cid))) %)))
              (update :schedule #(into {} (for [[round entries] %
                                                 :let [kept (filterv (fn [entry] (not= cid (:card entry))) entries)]
                                                 :when (seq kept)] [round kept]))))
    (and (= cid g-animal-farm) (get-in player [:card-state cid :unplaced?]))
    (-> (update :remaining #(max 0 (dec %))) (update :guests #(max 0 (dec (or % 0)))))
    (and (#{:catalog/original-expansion-wm-19-family-planner :catalog/original-expansion-wm-33-lady-in-waiting} cid)
         (:reserved-family player))
    (update :reserved-family (fn [reserved] (into {} (for [[round n] reserved]
        [round (max 0 (- n (count (filter #(and (= cid (:card %)) (= :reserved-family-arrival (get-in % [:effect :op]))) (get-in player [:schedule round])))))]))))
    (some #{cid} (:occupations player)) (update :occupations #(vec (remove #{cid} %)))
    (= cid :well) (assoc :well-rounds #{})
    (and (= cid :catalog/original-base-k-138-reed-hut) (:reed-hut? player))
    (as-> after (if (:reed-hut-placed? player)
                  (assoc after :reed-hut-discard? true)
                  (-> after (dissoc :reed-hut? :reed-hut-placed?) (update :remaining #(max 0 (dec %))))))))
(defn paid-card-player
  "Shared validation/payment for every route that plays a hand card. Pure: a
   rejected play cannot consume a worker, a card, or any resources."
  [g pid cid occupation-cost options]
  (let [card (c/card cid) player (p g pid)]
    (a/require! card "That card has no native rule definition.")
    (a/require! (not (house/banned? (:house-rules g) card)) "This card is banned by this table’s house rules.")
    (a/require! (<= (:min-players card) (count (:players g))) "This card requires more players.")
    (when (or (and (= cid :catalog/original-expansion-g-108-sheepdog) (c/has? player :catalog/original-base-k-120-house-goat))
              (and (= cid :catalog/original-base-k-120-house-goat) (some true? (c/passives player :sheepdog))))
      (a/require! false "Sheepdog and House Goat cannot be played together."))
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
    (let [player (if (and (:preserve-well-deliveries? card) (some #{:well} (selected-card-returns player cid options)))
                   (update player :well-deliveries #(merge-with + (or % {}) (into {} (map (fn [round] [round (a/well-food player round)]) (:well-rounds player))))) player)
          player (if (and (:preserve-well-deliveries? card) (some #{:catalog/original-expansion-wm-62-backyard-well} (selected-card-returns player cid options)))
                   (update player :well-deliveries #(merge-with + (or % {})
                       (into {} (for [[round entries] (:schedule player) :when (> round (:round g))
                                      :let [n (reduce + 0 (for [entry entries :when (= :catalog/original-expansion-wm-62-backyard-well (:card entry))] (get-in entry [:goods :food] 0)))]
                                      :when (pos? n)] [round n])))) player)
          player (if (= :occupation (:kind card))
                   (let [chocolate (occupation-stock-payment g pid occupation-cost options)
                         fee (+ (max 0 (- occupation-cost (max 0 (- (hand-food-discount player) *hand-food-discount-used*)))) (:additional-food-cost card 0))]
                     (-> player (spend-headmaster (headmaster-payment g pid (- fee chocolate (:occupation-wood options 0)) options)) (spend-occupation-stock chocolate))) player)
          player (reduce remove-owned-improvement player (selected-card-returns player cid options))
          player (if (= :minor (:kind card)) (a/improvement-food-before-payment (storage-shed-payment player options (not (:as-major? options))) cid) player)
          player (if (= :occupation (:kind card))
                   (a/gain player {:food (+ (:food-before-payment card 0) (reduce + 0 (c/passives player :occupation-food)))}) player)
          player (if (and (= :occupation (:kind card)) (:paper-maker? options))
                   (do (a/require! (c/has? player :b109) "You do not own Paper Maker.")
                       (-> player (a/pay {:wood 1}) (a/gain {:food (c/occupation-count player nil)}))) player)]
      (let [cost (card-cost g pid card occupation-cost options)
            shortfall (max 0 (- (:food cost 0) (a/amount player :food)))
            player (cond-> player (and *perpetual-draw?* (pos? shortfall))
                             (-> (a/gain {:food shortfall}) (update :begging + shortfall)))
            paid (cond-> ((if (= :minor (:kind card)) a/pay-construction a/pay) player cost)
                   (and (= :minor (:kind card)) (:wood-carver? options)) (assoc :wood-carver-used? true))]
        (when (or (:requires-effect? card)
                  (and (:requires-effect-when card) (expr/evaluate g pid {} (:requires-effect-when card))))
          (let [owned (update paid (if (= :occupation (:kind card)) :occupations :minors) (fnil conj []) cid)
                probe (assoc-in g [:players pid] owned)]
            (a/require! (and (seq (:play card))
                             (every? #(possible-effect? probe (task pid cid % {})) (:play card)))
                        "This card requires an available space and pieces for its printed action.")))
        paid))))
(defn play-card
  ([g pid cid occupation-cost options] (play-card g pid cid occupation-cost options nil))
  ([g pid cid occupation-cost options timing]
  (if (:perpetual-student? options)
    (do (a/require! (and (= :occupation (:kind (c/card cid)))
                         (some true? (c/passives (p g pid) :perpetual-student))) "Perpetual Student replaces an occupation choice.")
        (let [[state selected] (random/choose g (vec (sort (get-in g [:players pid :hand :occupation]))))]
          (-> state (update-in [:players pid] a/gain {:food 3})
              (queue-front [(task pid :catalog/original-base-k-275-perpetual-student
                                  {:op :perpetual-play :card selected :occupation-cost occupation-cost :timing timing} {})]))))
  (let [card (c/card cid) before (p g pid) kind (:kind card)]
    (when (:major-action-only? card)
      (a/require! (:major-action? timing) "This card requires a Major Improvement action."))
    (let [payment (when (and (= kind :minor) (or (c/has? before :catalog/original-expansion-fr-64-award-winner) (game-has-cards? g #{fr/good-friend})))
                    (card-cost g pid card occupation-cost options))
          player (-> (paid-card-player g pid cid occupation-cost options)
                     (update-in [:hand kind] #(vec (remove #{cid} %)))
                     (update (if (= kind :occupation) :occupations :minors) (fnil conj []) cid))
          player (if (get-in card [:passive :five-rings])
                   (let [index (or (:payment-option options)
                                   (first (keep-indexed (fn [i _] (try (when (can-pay? before (card-cost g pid card occupation-cost (assoc options :payment-option i))) i)
                                                                           (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))) (:cost-options card))) 0)
                         resource (if (= index 0) :wood :stone)]
                     (-> player (assoc-in [:card-state cid :resource] resource) (assoc-in [:card-state cid :stored-goods] {resource 1}))) player)
          player (cond-> player
                   (get-in card [:passive :vegetarian]) (assoc :vegetarian? true)
                   (get-in card [:passive :original-acrobat])
                   (assoc-in [:card-state cid :played-at] [(:round g) (if (= :work (:phase g)) (count (:worker-spaces before)) 0)])
                   (get-in card [:passive :lone-occupation]) (assoc-in [:hand :occupation] [])
                   (get-in card [:passive :card-field]) (assoc-in [:card-fields cid] {:type :field :allowed-crop (when-not (= :any (get-in card [:passive :card-field])) (get-in card [:passive :card-field])) :count 0})
                   (get-in card [:passive :card-stacks]) (update :card-fields merge (a/card-stack-fields cid (get-in card [:passive :card-stacks]))))
          returned (selected-card-returns before cid options)
          major? (and (get-in card [:passive :major-or-minor]) (or (:as-major? options) (:major-only? timing)))
          g (cond-> (assoc-in g [:players pid] player)
                    (seq returned) (update :available-improvements into (filter a/improvements returned))
                    (some #(not (a/improvements %)) returned) (update :removed-cards (fnil into []) (remove a/improvements returned))
                    (seq returned) (update :effect-queue #(filterv (fn [queued] (not (and (= pid (:player queued)) ((set returned) (:card queued))))) %))
                    (and major? (= 5 (count (:players g)))) (update-in [:major-builds pid] (fnil inc 0)))
          after (cond-> [(event (if (= kind :occupation) :occupation :build-improvement) pid (cond-> {:card cid :major-or-minor? (boolean (:major-or-minor? timing))}
                            (and (= kind :minor) (or (c/has? before :catalog/original-expansion-fr-64-award-winner) (game-has-cards? g #{fr/good-friend})))
                            (assoc :paid payment)
                            (and (= kind :minor) (game-has-cards? g #{fr/good-friend})) (assoc :minor-play? (not major?))))
                          (event :check-state pid)]
                  (and major? (= 5 (count (:players g)))) (conj (event :major-built pid {:card cid :improvement cid}))
                  (and (= :round-start (:event timing)) (or (:original-scholar? timing) (:scholar-round-start? card)))
                  (conj (event :round-start pid {:only-card cid :existing-window (:window timing)}))
                  (:passing? card) (conj {:kind :pass-card :player pid :card cid})
                  (= kind :minor) (conj (improvement-finished-event
                                         pid (assoc (or (:improvement-action timing)
                                                 {:kinds (cond (:major-only? timing) #{:major}
                                                               (:major-action? timing) #{:major :minor}
                                                               :else #{:minor})})
                                               :minor-played? (not major?)
                                               :origin-space (or (:origin-space timing) (:space options) (get-in timing [:improvement-action :origin-space])))
                                         (:merchant-repeat? timing))))]
      (cond-> (-> g (queue-front (concat (map #(task pid cid % {}) (:play card)) after))
                   (a/log-event (str (:name player) " played " (:name card))))
        (seq returned) with-housing))))))
(defn freshman-turn-key [g]
  [(:round g) (or (get-in g [:action-context :phase]) (:phase g)) (:turn-serial g 0)])
(defn freshman-available? [g pid]
  (and (owned? g pid :a097)
       (not= (freshman-turn-key g) (get-in g [:players pid :card-state (c/id :a097) :used-turn]))
       (some (fn [cid] (try (paid-card-player g pid cid 0 {}) true
                           (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false)))
             (get-in g [:players pid :hand :occupation]))))
(defn use-freshman [g pid cid options]
  (a/require! (freshman-available? g pid) "Freshman can replace baking only once per turn and needs a playable occupation.")
  (a/require! (= :occupation (:kind (c/card cid))) "Choose an occupation instead of baking bread.")
  (-> g (assoc-in [:players pid :card-state (c/id :a097) :used-turn] (freshman-turn-key g))
      (play-card pid cid 0 options)))
(defn granted-action-cost [g pid effect choice]
  (if (:card choice)
    (card-cost g pid (c/card (:card choice)) (get effect :occupation-cost 0) choice)
    (a/improvement-cost (p g pid) (:improvement choice) (:upgrade choice)
                        (assoc (select-keys choice [:discount-resource :discount-resources :manufacturer-discount :wood-carver? :grain-mill-oven?]) :cost (:major-cost effect)))))
(defn granted-card-action [g pid effect choice]
  (let [[g effect] (salesman-action g pid effect choice)
        cid (:card choice) major (:improvement choice) kinds (:kinds effect)
        choice (cond-> choice (and cid (= #{:major} kinds) (get-in (c/card cid) [:passive :major-or-minor])) (assoc :as-major? true))
        kind (if cid (:kind (c/card cid)) :major)
        _ (a/require! (and (not (and cid major)) (or cid major)
                          (or (contains? kinds kind)
                              (and cid (get-in (c/card cid) [:passive :major-or-minor]) (contains? kinds :major))
                              (and major (contains? kinds :minor) (a/major-as-minor? (p g pid) major))))
                      "Choose a card allowed by this action.")
        _ (when-let [resource (:printed-resource effect)]
            (let [definition (a/improvement-definition (or cid major))]
              (a/require! (pos? (get (or (:printed-cost definition) (:cost definition)) resource 0))
                          "Choose an improvement with the required resource printed in its cost.")))
        _ (when (:major-ids effect)
            (a/require! (or (contains? (:major-ids effect) (or major cid))
                            (and cid (get-in (c/card cid) [:passive :fireplace])
                                 (seq (set/intersection (:major-ids effect) #{:fireplace-2 :fireplace-3})))) "That major improvement is not allowed by this card."))
        _ (when (contains? (:require-upgrade-for effect) major)
            (a/require! (:upgrade choice) "Return a Fireplace to upgrade it to a Cooking Hearth."))
        major? (or major (and cid (get-in (c/card cid) [:passive :major-or-minor])
                              (or (:as-major? choice) (= #{:major} kinds))))
        g (if (and major? (:major-entry-cost effect)) (update-in g [:players pid] a/pay (:major-entry-cost effect)) g)
        fee (if (or major (and cid (get-in (c/card cid) [:passive :major-or-minor])
                                                   (or (:as-major? choice) (= #{:major} kinds)))) (:major-fee effect 0) 0)
        g (if (pos? fee) (update-in g [:players pid] a/pay {:food fee}) g)
        cost (granted-action-cost g pid effect choice)
        _ (a/require! (every? (fn [[resource n]] (>= (get cost resource 0) n)) (:minimum-paid effect))
                      "This improvement action requires the specified resources to actually be paid.")
        both? (= #{:major :minor} kinds)]
    (if cid
      (play-card g pid cid (get effect :occupation-cost 0) choice
                 {:major-action? (contains? kinds :major) :major-only? (= #{:major} kinds) :major-or-minor? both?
                  :improvement-action effect :merchant-repeat? (:merchant-repeat? effect)
                  :event (:event effect) :window (:window effect) :original-scholar? (:original-scholar? effect)})
      (let [context (cond-> {:improvement major :paid cost :upgraded-fireplace? (boolean (:upgrade choice))}
                      (game-has-cards? g #{fr/good-friend}) (assoc :minor-play? (= #{:minor} kinds)))
            deferred-bake? (and (:oven (a/improvements major)) (freshman-available? g pid))
            after (a/build-improvement (update-in g [:players pid] storage-shed-payment choice (= #{:minor} kinds)) pid (cond-> choice deferred-bake? (assoc :bake-grain 0 :cook-with-bake {})) {:cost (:major-cost effect)})]
        (-> (cond-> after (= 5 (count (:players g))) (update-in [:major-builds pid] (fnil inc 0)))
            (queue-front (concat (when deferred-bake? [(task pid nil {:op :bake :optional? true} context)]) [(event :build-improvement pid context)]
                                 (when (= 5 (count (:players g))) [(event :major-built pid context)])
                                 (when (and (not deferred-bake?) (pos? (get choice :bake-grain 0))) [(event :bake pid (assoc context :grain-baked (:bake-grain choice)))])
                                 [(event :check-state pid)
                                  (improvement-finished-event pid effect (:merchant-repeat? effect))])))))))
(def building-resources #{:wood :clay :reed :stone})
(defn pile-good-options [g effect]
  (vec (for [space (sort (a/accumulation-spaces g))
             :let [definition (a/action-definition g space) pile (get-in g [:piles space])]
             :when (and (:accumulate definition) (or (nil? (:space effect)) (= space (:space effect)))
                        (>= (reduce + (vals (select-keys pile building-resources))) (:minimum-building effect 0)))
             resource (sort (keys pile))
             :when (and (pos? (get pile resource 0))
                        (or (not (:building-only? effect)) (building-resources resource))
                        (or (nil? (:resource effect)) (= resource (:resource effect))))]
         {:space space :resource resource})))
(defn silage-grain-sources [player]
  (concat (when (pos? (a/amount player :grain)) [:supply])
          (for [cell (concat (a/tiles player :field) (keys (:card-fields player)))
                :let [field (get-in player (a/field-path cell))]
                :when (and (= :grain (:crop field)) (pos? (:count field 0)))] cell)))
(defn silage-choices [player]
  (for [source (silage-grain-sources player) animal a/animals :when (>= (a/amount player animal) 2)]
    {:grain-source source :animal animal}))
(defn sowing-player [g pid effect]
  (let [player (p g pid) command (:source-command effect)]
    (cond-> (if (= :cultivation (:space command))
              (a/plow-action-player g pid (:space command) (:cell command)) player)
      (:only-cells effect) (as-> limited
        (let [allowed (set (:only-cells effect))]
          (-> limited (update :farm #(mapv (fn [cell tile] (if (or (allowed cell) (not= :field (:type tile))) tile {:type :empty})) (range 15) %))
                      (update :card-fields #(select-keys % allowed)))))
      (:allowed-crops effect) (update :goods #(apply dissoc % (remove (:allowed-crops effect) [:grain :vegetable :wood :reed]))))))
(defn sowable?
  ([player] (sowable? player {}))
  ([player modifiers]
   (boolean (some (fn [[cell crop]]
                    (try (a/sow player [{:cell cell :crop crop}] modifiers) true
                         (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false)))
                  (for [cell (concat (a/tiles player :field) (keys (:card-fields player))) crop (a/sowable-crops player)] [cell crop])))))
(defn sowing-potential? [g pid effect]
  (let [grain? (and (:unconditional? effect)
                    (or (owned? g pid :a065)
                        (some #(and (not= pid (:id %)) (c/has? % :a132) (pos? (a/amount % :grain))) (:players g))))]
    (sowable? (cond-> (sowing-player g pid effect) grain? (a/gain {:grain 1})) (select-keys effect [:stack-adjust :empty-only?]))))
(defn complete-sow-placement [g pid effect command]
  (let [g (update-in g [:players pid] a/pay (or (:placement-cost effect) {}))]
    (if (:skip-advance? effect)
      (let [queue (:effect-queue g) after (take-action-complete (assoc g :effect-queue []) pid command)]
        (assoc after :effect-queue (vec (concat (remove #(and (= :continue (:kind %)) (= :advance (:step %))) (:effect-queue after)) queue))))
      (take-action-complete g pid command))))
(defn finish-without-sowing [g pid effect]
  (let [g (dissoc g :sow-in-progress)]
    (if-let [command (:source-command effect)]
      ;; No worker was committed while the other players considered Publican.
      ;; A legal plow/bake alternative may still complete the original placement.
      (try (queue-front (complete-sow-placement g pid effect (assoc command :plantings []))
                        [(event :sow-declined pid {:unconditional? true})])
           (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _
             (update (dissoc (finish-action-context g false) :plow-plan :freshman-plan) :cancelled-sowing (fnil conj #{}) [pid (:space command) (:round g)])))
      (queue-front g [(event :sow-declined pid (cond-> {:unconditional? (:unconditional? effect)} (:committed-source effect) (assoc :space (:committed-source effect) :completed-placement? true)))]))))
(defn loan-resources [cid] (get (c/card cid) :loan-resources a/animals))
(defn animal-loan-choices
  ([player] (animal-loan-choices player nil))
  ([player cid]
   (map #(hash-map :return-animals %)
     (reduce (fn [plans resource]
               (for [plan plans n (range (inc (min 1 (a/amount player resource))))] (assoc plan resource n))) [{}] (loan-resources cid)))))
(defn return-animal-loan [g pid cid returned]
  (let [player (p g pid) returned (or returned {})]
    (a/require! (not (get-in player [:card-state cid :loan-settled?])) "This loan has already been settled.")
    (a/quantities! returned (loan-resources cid))
    (a/require! (every? #(<= % 1) (vals returned)) "Return at most one of each borrowed resource.")
    (-> g (update-in [:players pid] a/pay returned)
        (assoc-in [:players pid :card-state cid :loan-settled?] true)
        (assoc-in [:players pid :card-state cid :returned-animals] returned))))
(defn flush-cooking-receipts [g]
  ;; Successful conversions are recorded even if only another player's card
  ;; listens. Costs, released animals and loan returns never produce receipts.
  (let [receipts (vec (for [player (:players g) receipt (:cooking-receipts player)] [(:id player) receipt]))
        serial (:cooking-serial g 0)
        events (for [[index [actor receipt]] (map-indexed vector receipts)
                     cid (distinct (concat (:listeners receipt)
                                           (for [owner (:players g) :when (not= actor (:id owner))
                                                 cid (c/active-owned owner)
                                                 :when (some #(and (= :convert-food (:event %))
                                                                   (#{:others :all} (:scope %))) (:triggers (c/card cid)))] cid)))]
                 (event :convert-food actor
                        {:only-card cid :cooking-phase (:phase receipt) :cooking-round (:round receipt) :converted-goods (:goods receipt) :cooking-rates (:rates receipt)
                         :cooked-animals (reduce + 0 (vals (select-keys (:goods receipt) a/animals)))
                         :cooking-receipt (+ serial index 1)
                         :cooked-boar (get-in receipt [:goods :boar] 0)
                         :cooked-cattle (get-in receipt [:goods :cattle] 0)}))]
    (if (seq receipts)
      (queue-front (-> g (assoc :cooking-serial (+ serial (count receipts)))
                       (update :players #(mapv (fn [player] (dissoc player :cooking-receipts)) %))) events) g)))
(defn unclaimed-cooked-boar [g context role]
  (let [claims (get-in g [:cooked-marker-claims (:cooking-receipt context)])
        blocked (if (= role :tanner) (max (:brush claims 0) (:basin claims 0)) (:tanner claims 0))]
    (max 0 (- (:cooked-boar context (get-in context [:converted-goods :boar] 0)) blocked))))
(defn tinsmith-rates [g]
  (if (or (pos? (:well-build-count g 0))
          (some #(or (contains? (:improvements %) :well) (c/has? % :catalog/original-base-i-66-village-well)) (:players g)))
    {:clay 2 :food 3} {:clay 1 :food 1}))
(defn pieceworker-resources [context]
  (filterv #(pos? (get-in context [:source-goods %] 0)) [:wood :clay :reed :stone :grain :vegetable]))
(defn pieceworker-choices [context]
  (rest (reduce (fn [out resource] (into out (map #(assoc % resource 1) out))) [{}] (pieceworker-resources context))))
(defn buyer-available? [g pid effect context]
  (let [actor (:actor context) resource (:resource effect)
        left (- (get-in context [:source-goods resource] 0)
                (get-in context [:source-returned resource] 0)
                (get-in g [:action-returned-goods (:action-id context) resource] 0))]
    (and (integer? actor) (not= pid actor) (pos? left)
         (pos? (a/amount (p g actor) resource))
         (not= (vec (take 3 (:action-id context))) (get-in g [:players pid :buyer-turns resource])))))
(defn fence-marker-spaces [g pid]
  (vec (distinct (concat (:active g) (map :card (card-spaces g pid))))))
(defn taster-available? [g pid]
  (and (= :preparation (:phase g)) (pos? (:remaining (p g pid) 0)) (not= pid (:starting-player g))
       (not= (:round g) (get-in g [:taster :round])) (pos? (a/amount (p g pid) :food))))
(defn apply-taster [g]
  (if (and (= (:round g) (get-in g [:taster :round]))
           (pos? (get-in g [:players (get-in g [:taster :player]) :remaining] 0)))
    (assoc g :turn (get-in g [:taster :player])) g))
(defn optional-effect? [g {:keys [player effect]}]
  (and (:optional? effect)
       (or (nil? (:mandatory-at effect))
           (< (a/amount (p g player) (:resource effect)) (:mandatory-at effect)))))
(defn harvest-helper-options [g pid]
  (for [owner (:players g) :when (not= pid (:id owner))
        cell (concat (a/tiles owner :field) (keys (:card-fields owner)))
        :when (pos? (a/field-crop-count (get-in owner (a/field-path cell)) :grain))]
    {:owner (:id owner) :cell cell}))
(def juggler-artists
  #{:catalog/original-base-k-311-magician :catalog/original-base-e-167-conjurer
    :catalog/original-base-i-257-street-musician :catalog/original-base-i-249-puppeteer
    :catalog/original-base-k-269-acrobat :catalog/original-base-e-212-dancer
    :catalog/original-base-k-342-animal-trainer :catalog/original-base-e-169-storyteller})
(def receipt-goods #{:wood :clay :reed :stone :vegetable})
(defn receipt-resources [player]
  (if (some #(or (get-in (c/card %) [:passive :seller-resources]) (get-in (c/card %) [:passive :hunter])) (c/active-owned player))
    (into receipt-goods a/animals) receipt-goods))
(defn receipt-listeners [player]
  (vec (filter #(or (get-in (c/card %) [:passive :resource-seller])
                   (get-in (c/card %) [:passive :market-woman]) (get-in (c/card %) [:passive :hunter])) (c/active-owned player))))
(defn receipt-orders [resources]
  (if (empty? resources) [[]]
    (for [resource resources tail (receipt-orders (remove #{resource} resources))] (into [resource] tail))))
(defn seller-reward [stack order]
  (reduce (fn [{:keys [stack] :as result} resource]
            (if (= resource (first stack))
              (-> result (update :goods update resource (fnil inc 0)) (assoc :stack (vec (rest stack)))) result))
          {:stack (vec stack) :goods {}} order))
(defn resource-seller-options [g pid cid goods]
  (let [stack (get-in g [:players pid :card-state cid :resource-stack])
        resources (sort (for [[r n] (select-keys goods (get-in (c/card cid) [:passive :seller-resources] [:wood :clay :reed :stone])) :when (pos? n)] r))]
    (->> (receipt-orders resources)
         (map #(assoc (seller-reward stack %) :order %))
         (reduce (fn [options result] (if (some #(= (:goods result) (:goods %)) options) options (conj options result))) [])
         (sort-by #(reduce + 0 (vals (:goods %))) >) vec)))
(defn gain-reaction [g {:keys [player goods source-kind listeners]}]
  (reduce (fn [state cid]
            (cond
              (and (get-in (c/card cid) [:passive :hunter]) (not= :breeding (:phase g))
                   (some pos? (vals (select-keys goods a/animals))))
              (queue-front state [(task player cid {:op :hunter :optional? true} {:goods (select-keys goods a/animals)})])
              (and (get-in (c/card cid) [:passive :market-woman])
                   (#{:action :minor} source-kind) (pos? (:vegetable goods 0)))
              (update-in state [:players player] a/gain {:grain 2})
              (and (get-in (c/card cid) [:passive :resource-seller])
                   (not (and (= :breeding (:phase g)) (get-in (c/card cid) [:passive :seller-resources]))))
              (queue-front state [(task player cid {:op :resource-seller-receipt :goods goods} {})])
              :else state)) g listeners))
(defn note-gain-receipts [before after source-kind]
  (reduce (fn [state player]
            (let [pid (:id player) listeners (receipt-listeners (p before pid))
                  goods (into {} (for [resource (receipt-resources (p before pid))
                                      :let [n (- (get-in player [:received resource] 0) (get-in before [:players pid :received resource] 0))]
                                      :when (pos? n)] [resource n]))
                  receipt {:player pid :goods goods :source-kind source-kind :listeners listeners}]
              (if (and (seq goods) (seq listeners))
                (let [action-id (peek (:receipt-action-stack state))
                      defer? (and action-id (not (get-in state [:action-gain-receipts action-id :source-settled?])))
                      seller (filterv #(get-in (c/card %) [:passive :resource-seller]) listeners)
                      immediate (if defer? (filterv #(not (some #{%} seller)) listeners) listeners)
                      state (gain-reaction state (assoc receipt :listeners immediate))]
                  (if (and defer? (seq seller))
                    (update-in state [:action-gain-receipts action-id :cards] (fnil conj []) (assoc receipt :listeners seller)) state)) state))) after (:players after)))
(defn begin-action-receipt [before after pid cmd]
  (let [action-id [(:round before) (:turn-serial before 0) pid (count (:worker-spaces (p before pid)))]
        goods (select-keys (source-action-goods before cmd) (receipt-resources (p before pid)))
        listeners (receipt-listeners (p before pid))
        seller (filterv #(get-in (c/card %) [:passive :resource-seller]) listeners)
        settled? (not-any? #(pos? (get goods % 0)) [:wood :clay :reed :stone])
        source {:player pid :goods goods :source-kind :action :listeners seller}
        after (gain-reaction after (assoc source :listeners (filterv #(not (some #{%} seller)) listeners)))]
    (-> after (update :receipt-action-stack (fnil conj []) action-id)
        (assoc-in [:action-gain-receipts action-id]
                  {:source source :source-settled? settled?
                   :source-returned (if (:pig-catcher? cmd) {:wood 2} {}) :cards []}))))
(defn finish-action-receipt [g action-id]
  (let [{:keys [source source-returned cards]} (get-in g [:action-gain-receipts action-id])
        returned (merge-with + source-returned (get-in g [:action-returned-goods action-id]))
        source (update source :goods #(into {} (for [[r n] %] [r (max 0 (- n (get returned r 0)))])))
        g (-> g (update :receipt-action-stack #(vec (remove #{action-id} %)))
                (update :action-gain-receipts dissoc action-id))]
    (reduce gain-reaction g (reverse (cons source cards)))))
(defn animal-breeder-available? [g pid context]
  (let [player (p g pid) old (set (mapcat identity (:old-pastures context)))
        cid :catalog/original-base-k-307-animal-breeder]
    (and (not (:fence-overseer? context))
         (not= (freshman-turn-key g) (get-in player [:card-state cid :last-turn]))
         (some #(and (not (old %)) (not (get-in player [:farm % :stable]))) (a/pasture-cells player)))))
(defn fence-overseer-cells [g pid context]
  (let [player (p g pid)]
    (when (not= (:round g) (get-in player [:card-state :catalog/original-base-k-312-fence-overseer :used-round]))
      (filterv #(and (get-in player [:farm % :stable]) (not ((a/pasture-cells player) %))) (:new-stables context)))))
(defn collect-space-bonus [g pid space]
  (if-let [goods (get-in g [:space-bonuses space])]
    (-> g (update-in [:players pid] a/gain goods) (update :space-bonuses dissoc space)) g))
(defn unused-count [player] (count (filter #(a/empty-cell? player %) (range 15))))
(defn start-corn-sale [g pid cid]
  (a/require! (nil? (:corn-sale g)) "Finish the current grain sale first.")
  (-> g (update-in [:players pid] a/pay {:grain 1})
      (assoc :corn-sale {:seller pid :card cid :offers []})
      (queue-front (concat (for [offset (range 1 (count (:players g)))
                                :let [other (mod (+ pid offset) (count (:players g)))]]
                            (task other cid {:op :corn-offer :optional? true} {:seller pid}))
                           [(task pid cid {:op :corn-select} {})]))))
(defn pearl-seeker-max [g pid context]
  (min (quot (a/amount (p g pid) :food) 2) (quot (get-in context [:source-goods :food] 0) 2)))
(defn award-winner-resources [g pid cid context]
  (when (not= (:round g) (get-in g [:players pid :card-state cid :used-round]))
    (filterv #(and (pos? (get-in context [:paid %] 0)) (pos? (a/amount (p g pid) %))) [:wood :clay :reed :stone])))
(defn wheelbarrow-choices [g] (cond-> [:wood :clay :reed :stone] (= 3 (count (:players g))) (conj :food)))
(defn harvestable-field-cells [g pid]
  (let [player (p g pid)] (filterv (fn [cell] (let [f (get-in player (a/field-path cell))]
                                              (and (not (:blocked-card f)) (:crop f) (pos? (:count f 0)))))
                                 (concat (a/tiles player :field) (keys (:card-fields player))))))
(defn apple-tree-cells [g pid]
  (filterv #(let [f (get-in g [:players pid :farm %])] (and (= :field (:type f)) (not (:blocked-card f)) (zero? (reduce + 0 (vals (a/field-crops f)))))) (range 15)))
(defn backyard-well-cells [g pid]
  (let [player (p g pid)] (filterv #(and (a/empty-cell? player %) (some (set (a/tiles player :room)) (a/neighbors %))) (range 15))))
(defn seasonal-plantings [g pid context]
  (filterv #(let [f (get-in (p g pid) (a/field-path (:cell %)))]
              (and (not (:blocked-card f)) (pos? (a/field-crop-count f (:crop %))))) (:plantings context)))
(defn benefactor-available? [g pid cid]
  (let [player (p g pid)] (and (= :wood (:house player)) (= 4 (count (a/pastures player)))
                              (not (get-in player [:card-state cid :room-used?]))
                              (some #(pos? (a/amount player %)) a/animals)
                              (seq (backyard-well-cells g pid)))))
(defn cabbage-lover-max [g pid context]
  (min (a/amount (p g pid) :clay) (get-in context [:converted-goods :vegetable] 0)))
(defn needs-input? [g {:keys [player effect] :as head}]
  (or (and (#{:fiddler :manual-labourer} (:op effect)) (:window head)
           (some #(and (= :effect (:kind %)) (= player (:player %)) (= (:window head) (:window %))
                       (not= (:card head) (:card %))) (:effect-queue g)))
      (and (= :resource-seller-receipt (:op effect))
           (> (count (resource-seller-options g player (:card head) (:goods effect))) 1))
      (and (= :grow (:op effect)) (:reed-hut? (p g player)))
      (optional-effect? g head)
      (and (= :build-fences (:op effect)) (or (:hammer? effect) (:paddocks? effect)))
      (and (= :pay-or-beg (:op effect)) (< (a/amount (p g player) :food) (:food effect)))
      (and (= :corn-select (:op effect)) (> (count (get-in g [:corn-sale :offers])) 1))
      (and (= :reveal-rounds (:op effect)) (= 11 (:round g)))
      (and (= :choose-good (:op effect)) (not (contains? effect :selected-resource)))
      (#{:g-mathematician :g-miner :g-opportunist :g-pilgrim :g-animal-farm :g-dunghill :chemist :reserve-family :minder-deposit :minder-increase :fish-pond-place :fish-pond-store :fish-seller :bartering-hut :bee-keeper :hunter :stash-card :farm-markers :storage-shed :wine-delivery :boatswain :sailboat-constructor :stroller :reed-nursery-points :schedule-resource-order :harvest-field :apple-tree :seasonal-plants :return-stables :take-wild-breeding :discard-minors :choose-score-category :cabbage-lover :livestock-keeper :room-resource :pearl-seeker :award-winner :wheelbarrow-pusher :room-food :take-field-crops :build-stables :place-space-goods :serf :perpetual-play :foreman :animal-breeder :animal-trainer :fence-overseer :mark-fence-space :return-animal-loan :freshman-bake :classify-fields :paper-knife :lodger-remove :ability-window :choose :remove-empty-field :plow :plow-many :wheel-plow :mini-pasture :stable :build-rooms :card-action :bake :bake-one :take-field-crop :sow-offer :sow :housing :cottage :scholar} (:op effect))
      (and (= :seasonal-crop (:op effect)) (>= (:round g) 6))))
(def fish-seller-prices {:wood 1 :clay 1 :reed 1 :stone 1 :grain 2 :sheep 2 :boar 2 :vegetable 3 :cattle 3})
(def bartering-hut-prices {:sheep 2 :boar 3 :cattle 4})
(def sailboat-stacks {:wood {:wood 3} :clay {:clay 3} :stone {:stone 2} :crops {:grain 1 :vegetable 1}})
(defn fish-seller-budget [g pid context] (min (a/amount (p g pid) :food) (get-in context [:source-goods :food] 0)))
(defn boatswain-cells [g pid]
  (let [player (p g pid)] (filterv #(let [field (get-in player [:farm %])]
                                   (and (= :field (:type field)) (not (:blocked-card field))
                                        (empty? (a/field-crops field)))) (range 15))))
(defn paddocks-edges [player]
  (set (mapcat (fn [region] (for [one region two (a/neighbors one) :when ((set region) two)
                                 :let [edge (a/shared-edge one two)] :when (not ((:fences player) edge))] edge)) (a/pastures player))))
(defn paddocks-limit [player] (min (a/fences-available player) (+ 4 (a/turn-free-fences-available player))))
(defn paddocks-plans [player]
  (let [edges (vec (sort (paddocks-edges player))) limit (paddocks-limit player)]
    (letfn [(choose [left start chosen]
              (concat (when (seq chosen) [chosen])
                      (when (pos? left) (mapcat #(choose (dec left) (inc %) (conj chosen (edges %))) (range start (count edges))))))]
      (filterv (fn [fences] (try (a/build-fences player fences {:free-fences 4}) true
                               (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false))) (choose limit 0 [])))))
(defn pawnable-improvements [player]
  (vec (filter #(and (pos? (get (a/improvement-definition %) :points 0))
                     (not (contains? (:pawned-improvements player #{}) %))) (sort (a/owned-improvement-ids player)))))
(defn sailboat-available [g pid cid]
  (filterv #(not (contains? (get-in g [:players pid :card-state cid :bought-stacks] #{}) %)) [:wood :clay :stone :crops]))
(defn sailboat-price [g pid cid selected]
  (let [bought (count (get-in g [:players pid :card-state cid :bought-stacks]))]
    (reduce + 0 (range (+ 2 bought) (+ 2 bought (count selected))))))
(defn reed-nursery-total [player]
  (+ (a/amount player :reed) (reduce + 0 (for [field (concat (:farm player) (vals (:card-fields player)))] (a/field-crop-count field :reed)))))
(defn round-building-receipts [player]
  (into {} (for [r [:wood :clay :reed :stone]
                 :let [n (- (get-in player [:received r] 0) (get-in player [:round-received-baseline r] 0))] :when (pos? n)] [r n])))
;; Original WM fifteen: explicit choices, shared between browser and bots.
(def wm-fifteen-ops #{:borrower-start :chemist :reserve-family :reserve-lady :reserved-family-arrival
                     :lady-arrival :minder-deposit :minder-increase :almshouse :fish-pond-place :fish-pond-store :slapdash-return})
(defn family-planner-rounds [g] (filterv #(< % 15) (mapv #(+ (:round g) %) [4 7 10])))
(defn chemist-spaces [g pid context]
  (let [source (:space context) action (a/action-definition g source)]
    (when (and (pos? (get-in context [:source-goods :wood] 0))
               (or (and (:accumulate action) (some (set building-resources) (keys (:gain action)))) (and (= source :market) (= 5 (count (:players g)))))
               (pos? (a/amount (p g pid) :clay)))
      (filterv #(let [action (a/action-definition g %)]
                  (and (not= % source) (:accumulate action)
                       (some (set building-resources) (keys (:gain action))))) (:active g)))))
(defn minder-resources [player cid]
  (filterv #(pos? (get-in player [:card-state cid :stored-goods %] 0)) building-resources))
(defn eligible-begging [player borrower?]
  (min (:begging player 0) (+ (get-in player [:begging-origins :feeding] 0)
                            (if borrower? (get-in player [:begging-origins :borrower] 0) 0))))
(defn discard-eligible-begging [player borrower?]
  (a/require! (pos? (eligible-begging player borrower?)) "No begging card of the permitted origin remains.")
  (let [origin (if (and borrower? (pos? (get-in player [:begging-origins :borrower] 0))) :borrower :feeding)]
    (-> player (update :begging dec) (update-in [:begging-origins origin] dec))))
(defn discard-any-begging [player]
  (a/require! (pos? (:begging player 0)) "You have no begging card to discard.")
  ;; Preserve the most restricted (feeding-only) origin when the choice is free.
  (let [origins (:begging-origins player)
        untracked (- (:begging player) (reduce + 0 (vals origins)))]
    (if (or (nil? origins) (pos? untracked)) (update player :begging dec)
        (discard-eligible-begging player true))))
(defn wm-fifteen-choices [g pid cid effect context]
  (let [player (p g pid)]
    (case (:op effect)
      :chemist (map #(hash-map :space %) (chemist-spaces g pid context))
      :reserve-family (map #(hash-map :rounds [%]) (family-planner-rounds g))
      (:minder-deposit :minder-increase) (map #(hash-map :resources [%]) building-resources)
      :fish-pond-place (map #(hash-map :cell %) (filter #(a/empty-cell? player %) (range 15)))
      :fish-pond-store (map #(hash-map :amount %) (range 1 (inc (min (a/amount player :food) (get-in context [:source-goods :food] 0)))))
      [{}])))
(defn run-wm-fifteen-effect [g {:keys [player card effect context]} choice]
  (let [pid player current (p g pid)]
    (case (:op effect)
      :borrower-start (update-in g [:players pid] #(-> % (a/gain {:food 7}) (update :begging + 2)
                                                       (update-in [:begging-origins :borrower] (fnil + 0) 2)))
      :chemist (let [space (:space choice)]
                 (a/require! (some #{space} (chemist-spaces g pid context)) "Put clay on another revealed building-resource accumulation space.")
                 (-> g (update-in [:players pid] #(-> % (a/pay {:clay 1}) (a/gain {:food 2})))
                       (update-in [:piles space :clay] (fnil inc 0))))
      :reserve-family
      (let [rounds (:rounds choice)]
        (a/require! (and (seq rounds) (= (count rounds) (count (set rounds)))
                         (every? (set (family-planner-rounds g)) rounds) (<= (count rounds) (a/unused-person-tokens current)))
                    "Reserve at most one available family piece on each printed future round.")
        (reduce (fn [state round] (-> state (update-in [:players pid :reserved-family round] (fnil inc 0))
                                      (schedule pid card {:rounds [round] :marker-goods {:person 1} :effect {:op :reserved-family-arrival}}))) g rounds))
      :reserve-lady
      (if (and (<= (:round g) 4) (pos? (a/unused-person-tokens current)))
        (-> g (update-in [:players pid :reserved-family 12] (fnil inc 0))
              (schedule pid card {:rounds [12] :marker-goods {:person 1} :effect {:op :reserved-family-arrival :adult? true}})) g)
      :reserved-family-arrival
      (-> g (update-in [:players pid :reserved-family (:round g)] dec)
            (queue-front [(task pid card (if (:adult? effect) {:op :lady-arrival :optional? true}
                                            {:op :grow :room-required? true :optional? true :family-planner? true}) {})]))
      :lady-arrival
      (do (a/require! (pos? (a/unused-person-tokens current)) "The reserved person must still be available.")
          (update-in g [:players pid] #(-> % (a/pay {:food 2}) (update :family inc) (update :remaining inc))))
      (:minder-deposit :minder-increase)
      (let [deposit? (= :minder-deposit (:op effect)) resources (:resources choice) maximum (if deposit? 4 2)]
        (a/require! (and (seq resources) (<= (count resources) maximum) (= (count resources) (count (set resources)))
                         (every? (set (if deposit? building-resources (minder-resources current card))) resources))
                    "Select different building resources still available for this card.")
        (cond-> (update-in g [:players pid :card-state card :stored-goods] #(merge-with + (or % {}) (frequencies resources)))
          deposit? (update-in [:players pid] a/pay (frequencies resources))))
      :almshouse (update-in g [:players pid] discard-eligible-begging false)
      :fish-pond-place
      (let [cell (:cell choice)]
        (a/require! (and (a/cell? cell) (a/empty-cell? current cell)) "Place the clay marker on an unused farm space.")
        (assoc-in g [:players pid :farm cell] {:type :feature :card card :marker-goods {:clay 1}}))
      :fish-pond-store
      (let [n (:amount choice) maximum (min (a/amount current :food) (get-in context [:source-goods :food] 0))]
        (a/require! (and (a/natural? n) (<= 1 n maximum)) "Store only food taken from Fishing itself.")
        (-> g (update-in [:players pid] a/pay {:food n})
              (update-in [:players pid :card-state card :stored-goods :food] (fnil + 0) n)))
      :slapdash-return
      (let [space (:space context) index (:worker-index context)]
        (a/require! (and (#{:renovate :redevelop} space) (some #{pid} (space-owners g space))
                         (integer? index) (= space (get-in current [:worker-spaces index]))) "Return the person who took this renovation action.")
        (-> g (remove-worker pid space) (update-in [:players pid :remaining] inc)
              (assoc-in [:players pid :worker-spaces index] nil))))))

(def community-fourteen-ops #{:fish-seller :bartering-hut :bee-keeper :hunter :stash-card :farm-markers :collect-raspberries :storage-shed :wine-delivery :boatswain :fiddler :manual-labourer :remaining-round-wood :revolution-start :revolution-check :mastermind :sailboat-constructor :stroller :take-stroller :reed-nursery-points})
(defn run-community-fourteen-effect [g {:keys [player card effect context]} choice]
  (let [pid player current (p g pid)]
    (case (:op effect)
      :fish-seller
      (let [goods (:goods choice) price (reduce + 0 (map #(get fish-seller-prices % 1000) goods))]
        (a/require! (and (vector? goods) (<= 1 (count goods) 2) (every? fish-seller-prices goods)
                         (<= price (fish-seller-budget g pid context))) "Buy up to two goods using only food originally collected from Fishing.")
        (update-in g [:players pid] #(-> % (a/pay {:food price}) (a/gain (frequencies goods)))))
      :bartering-hut
      (let [animals (:animals choice) payment (:payment choice) n (reduce + 0 (map #(get bartering-hut-prices % 1000) animals))]
        (a/require! (and (vector? animals) (<= 1 (count animals) 2) (every? bartering-hut-prices animals)
                         (map? payment) (every? building-resources (keys payment)) (every? a/natural? (vals payment)) (= n (reduce + 0 (vals payment))))
                    "Choose up to two animals and exactly two, three, or four building resources per sheep, boar, or cattle.")
        (update-in g [:players pid] #(-> % (a/pay payment) (a/gain (frequencies animals)))))
      :bee-keeper
      (let [cell (:cell choice)]
        (-> g (update-in [:players pid] a/build-stables [cell] 0)
              (assoc-in [:players pid :farm cell :stable-disabled?] true)
              (assoc-in [:players pid :farm cell :stable-card] card)))
      :hunter
      (let [goods (:goods choice) received (:goods context)]
        (a/require! (and (map? goods) (seq goods) (every? (set a/animals) (keys goods))
                         (every? a/natural? (vals goods)) (some pos? (vals goods))
                         (every? (fn [[r n]] (<= n (get received r 0))) goods)
                         (not= :breeding (:phase g))) "Hunter can cook only the animals just received, outside the Harvest Breeding phase.")
        (update-in g [:players pid] a/hunter-convert goods (select-keys g [:phase :round])))
      :stash-card
      (let [cid (:card choice) kind (:kind (c/card cid))]
        (a/require! (and (#{:minor :occupation} kind) (some #{cid} (get-in current [:hand kind]))) "Put one card from your hand face down under Village Fool.")
        (-> g (update-in [:players pid :hand kind] #(vec (remove #{cid} %)))
              (update-in [:players pid :card-state card :hidden-count] (fnil inc 0))))
      :farm-markers
      (let [cells (:cells choice) bonfire? (= :bonfire (:kind effect))]
        (a/require! (and (vector? cells) (<= 1 (count cells) (:count effect)) (= (count cells) (count (set cells)))
                         (or (not bonfire?) (= 1 (count cells))) (every? #(and (integer? %) (<= 0 % 14) (a/empty-cell? current %)) cells))
                    "Click distinct unused farm spaces for the card's markers.")
        (reduce (fn [state cell]
                  (-> (if bonfire? (assoc-in state [:players pid :farm cell] {:type :feature :card card :marker-goods {:wood 1}})
                          (update-in state [:players pid :farm cell] assoc :marker-goods {:food 1} :marker-card card))
                      (update-in [:players pid :card-state card :marked-cells] (fnil conj #{}) cell))) g cells))
      :collect-raspberries
      (let [used (filterv #(not (a/empty-cell? current %)) (get-in current [:card-state card :marked-cells]))]
        (if (empty? used) g
          (-> (reduce (fn [state cell] (update-in state [:players pid :farm cell] dissoc :marker-goods :marker-card)) g used)
              (update-in [:players pid :card-state card :marked-cells] #(apply disj % used))
              (update-in [:players pid] a/gain {:food (count used)}))))
      :storage-shed
      (let [cell (:cell choice)]
        (a/require! (and (integer? cell) (<= 0 cell 14) (get-in current [:farm cell :stable])) "Choose one built stable to move onto Storage Shed.")
        (-> g (update-in [:players pid :farm cell] dissoc :stable :stable-disabled? :stable-card)
              (assoc-in [:players pid :stored-stables card] 1)))
      :wine-delivery
      (let [n (:amount effect) points (:points choice 0)]
        (a/require! (and (a/natural? points) (<= points n)) "Choose food or one bonus point for each scheduled vegetable.")
        (-> (if (< points n) (paid-food-conversion g pid card {:vegetable (- n points)} (* 2 (- n points))) g) (update-in [:players pid :bonus-points] (fnil + 0) points)))
      :boatswain
      (let [cells (:cells choice)]
        (a/require! (and (vector? cells) (seq cells) (= (count cells) (count (set cells))) (every? (set (boatswain-cells g pid)) cells)) "Click empty plowed fields for one grain each.")
        (reduce #(update-in %1 [:players pid :farm %2] assoc :crop :grain :count 1) g cells))
      :fiddler (if (empty? (round-building-receipts current))
                 (update-in g [:players pid] a/gain (cond-> {:wood 1} (even? (:round g)) (assoc :food 1))) g)
      :manual-labourer (let [resources (keys (round-building-receipts current))]
                         (if (= 1 (count resources)) (update-in g [:players pid] a/gain {(first resources) 1}) g))
      :remaining-round-wood
      (let [left (- 14 (:round g)) wood (cond (>= left 9) 4 (>= left 6) 3 (>= left 3) 2 (>= left 1) 1 :else 0)]
        (update-in g [:players pid] a/gain {:wood wood}))
      :revolution-start
      (assoc-in g [:players pid :card-state card :eligible-players]
                (set (for [player (:players g) :when (some #(a/empty-cell? player %) (range 15))] (:id player))))
      :revolution-check
      (let [eligible (get-in current [:card-state card :eligible-players])
            winner (first (for [offset (range (count (:players g)))
                               :let [id (mod (+ (:starting-player g) offset) (count (:players g)))]
                               :when (and (contains? eligible id) (not-any? #(a/empty-cell? (p g id) %) (range 15)))] id))]
        (if (or (get-in current [:card-state card :awarded?]) (nil? winner)) g
          (-> g (update-in [:players winner :bonus-points] (fnil + 0) 3)
                (assoc-in [:players pid :card-state card :awarded?] true)
                (assoc-in [:players pid :card-state card :winner] winner))))
      :mastermind
      (let [cid (or (:card context) (:improvement context))]
        (if (and (not= cid card) (c/bonus-symbol? cid))
          (update-in g [:players pid :card-state card :bonus-plays] (fnil inc 0)) g))
      :sailboat-constructor
      (let [selected (:stacks choice)]
        (a/require! (and (vector? selected) (seq selected) (= (count selected) (count (set selected)))
                         (every? (set (sailboat-available g pid card)) selected)) "Choose any remaining resource stacks to buy.")
        (-> g (update-in [:players pid] #(-> % (a/pay {:food (sailboat-price g pid card selected)}) (a/gain (apply merge-with + (map sailboat-stacks selected)))))
              (update-in [:players pid :card-state card :bought-stacks] (fnil into #{}) selected)))
      :stroller
      (let [resources (:resources choice)]
        (a/require! (and (vector? resources) (= 6 (count resources)) (= building-resources (set resources))) "Arrange six building resources, including at least one of every type, in delivery order.")
        (assoc-in g [:players pid :card-state card :resource-stack] resources))
      :take-stroller
      (let [stack (get-in current [:card-state card :resource-stack])]
        (if (empty? stack) g (-> g (update-in [:players pid] a/gain {(first stack) 1})
                                  (assoc-in [:players pid :card-state card :resource-stack] (vec (rest stack))))))
      :reed-nursery-points
      (let [n (:amount choice 0) fields (:fields choice {}) total (+ n (reduce + 0 (vals fields))) points ({1 1 4 2 6 3 8 4} total)]
        (a/require! (and (a/natural? n) (map? fields) (every? a/natural? (vals fields)) points
                         (<= n (a/amount current :reed))
                         (every? (fn [[cell count]] (<= count (a/field-crop-count (get-in current (a/field-path cell)) :reed))) fields))
                    "Exchange exactly 1, 4, 6, or 8 reed from your supply and chosen fields.")
        (-> (reduce (fn [state [cell count]] (nth (iterate #(update-in % [:players pid] a/remove-field-crop cell :reed) state) count)) g fields)
            (update-in [:players pid] a/pay {:reed n}) (update-in [:players pid :bonus-points] (fnil + 0) points))))))

(def community-thirteen-ops #{:schedule-resource-order :harvest-field :apple-tree :seasonal-plants :return-stables :wild-breeding :take-wild-breeding :discard-minors :choose-score-category :cabbage-lover :livestock-keeper :absinthe-ready})
(defn run-community-thirteen-effect [g {:keys [player card effect context]} choice]
  (let [pid player current (p g pid)]
    (case (:op effect)
          :schedule-resource-order
          (let [resources (:resources choice)]
            (a/require! (and (= 4 (count resources)) (= #{:wood :clay :reed :stone} (set resources))) "Arrange each of the four building resources exactly once.")
            (reduce (fn [state [offset resource]] (let [round (+ (:round g) offset)]
                       (if (< round 15) (update-in state [:players pid :schedule round] (fnil conj []) {:card card :goods {resource 1}}) state))) g (map vector (range 1 5) resources)))
          :harvest-field
          (let [cell (:cell choice) field (get-in current (a/field-path cell)) crop (:crop field)]
            (a/require! (some #{cell} (harvestable-field-cells g pid)) "Harvest one planted field or card field.")
            (-> g (update-in [:players pid] #(cond-> (a/gain % {crop 1})
                                               (not (and (= crop :vegetable) (some true? (c/passives % :preserve-harvest-vegetables)))) (a/remove-field-crop cell crop)))
                (queue-front (harvested-crop-events current [cell]))))
          :apple-tree
          (let [cell (:cell choice)]
            (a/require! (some #{cell} (apple-tree-cells g pid)) "Place the Apple Tree on an empty field.")
            (update-in g [:players pid :farm cell] assoc :blocked-card card :marker-goods {:wood 1}))
          :seasonal-plants
          (let [planting (first (filter #(= (:cell choice) (:cell %)) (seasonal-plantings g pid context)))
                path (a/field-path (:cell planting))]
            (a/require! planting "Add one crop only to a field just sown.")
            (update-in g (into [:players pid] (conj path :count)) inc))
          :return-stables
          (let [cells (:stables choice)]
            (a/require! (and (sequential? cells) (<= (:min-stables effect 1) (count cells) (:max-stables effect 2))
                             (= (count cells) (count (set cells))) (every? #(get-in current [:farm % :stable]) cells)) "Choose the printed number of built stables to return.")
            (let [after (reduce #(update-in %1 [:players pid :farm %2] dissoc :stable :stable-disabled? :stable-card) g cells)
                  next (if (= :room (:then effect)) {:op :build-rooms :free? true :max-rooms 1}
                           {:op :plow-many :min-fields (count cells) :max-fields (count cells)})]
              (a/require! (or (not= :room (:then effect)) (= :wood (:house current))) "Remodeling extends only a wooden hut.")
              (a/require! (possible-effect? after (task pid card next {})) "You must be able to complete the construction after returning these stables.")
              (queue-front after [(task pid card next {})])))
          :wild-breeding
          (let [spaces (vec (for [space (:active g) :let [pile (get-in g [:piles space])]
                                 :when (and (:accumulate (a/action-definition g space)) (some #(>= (get pile % 0) 2) a/animals))] space))
                after (reduce (fn [state space] (let [animal (first (filter #(>= (get-in state [:piles space %] 0) 2) a/animals))]
                                                 (update-in state [:piles space animal] inc))) g spaces)]
            (queue-front after [(task pid card {:op :take-wild-breeding :optional? true} {:added-spaces spaces})]))
          :take-wild-breeding
          (let [space (:space choice) animal (first (filter #(pos? (get-in g [:piles space %] 0)) a/animals))]
            (a/require! (some #{space} (:added-spaces context)) "Take one of the animals just added to an accumulation space.")
            (let [after (update-in g [:players pid] a/gain {animal 1})]
              (a/require! (a/animal-housing (p after pid)) "You must have room to keep the added animal.")
              (update-in after [:piles space animal] dec)))
          :discard-minors
          (let [cards (:cards choice)]
            (a/require! (and (sequential? cards) (<= 1 (count cards) (:max-cards effect 2)) (= (count cards) (count (set cards)))
                             (every? (set (get-in current [:hand :minor])) cards)) "Show and discard up to two minor improvements from your hand.")
            (-> g (update-in [:players pid :hand :minor] #(vec (remove (set cards) %)))
                (update :removed-cards (fnil into []) cards)
                (update-in [:players pid] a/gain {:food (* (:food-per-card effect 2) (count cards))})
                (a/log-event (str (:name current) " discarded " (mapv #(:name (c/card %)) cards)))))
          :choose-score-category
          (let [category (:category choice)]
            (a/require! (#{:grain :vegetables :sheep :boar :cattle :fields :pastures :stables} category) "Choose one of the eight printed scoring categories.")
            (assoc-in g [:players pid :card-state card :category] category))
          :cabbage-lover
          (let [n (:amount choice)]
            (a/require! (and (a/natural? n) (<= 1 n (cabbage-lover-max g pid context))) "Store at most one clay for each vegetable converted to food.")
            (-> g (update-in [:players pid] a/pay {:clay n}) (update-in [:players pid :card-state card :stored-goods :clay] (fnil + 0) n)))
          :livestock-keeper
          (let [animal (:animal choice)]
            (a/require! (and (some #{animal} a/animals) (>= (a/amount current animal) 2) (nil? (:extra-breeding current))) "Choose exactly one animal type that can breed.")
            (-> g (update-in [:players pid] a/pay {:food 1}) (assoc-in [:players pid :extra-breeding] {animal 1})))
          :absinthe-ready (assoc-in g [:players pid :card-state card :ready-round] (:round g))
)))
(defn enhanced-food-conversion? [player]
  (or (a/vegetarian? player) (some true? (c/passives player :artisan)) (some true? (c/passives player :potters-wheel))))
(defn paid-food-conversion [g pid cid cost food]
  ;; The effect already paid its cost. Restore only its supply balance, then
  ;; use the ordinary recipe receipt path without manufacturing a resource gain.
  (update-in g [:players pid]
    #(a/convert-recipe (update % :goods (fn [goods] (merge-with + goods cost))) cost food (assoc (select-keys g [:phase :round]) :recipe-card cid))))

(defn g-animal-farm-available? [g player]
  (and (= :work (:phase g)) (c/has? player g-animal-farm)
       (not= (:round g) (get-in player [:card-state g-animal-farm :used-round]))
       (some #(pos? (a/amount player %)) a/animals)))
(defn g-mathematician-options [g]
  (when (>= (:round g) 6)
    (vec (for [resource (if (= 5 (count (:players g))) [:wood :clay :stone] [:wood :clay])
               [n spaces] (group-by #(get-in g [:piles % resource] 0)
                                   (filter #(and (:accumulate (a/action-definition g %)) (pos? (get-in (a/action-definition g %) [:gain resource] 0))) (:active g)))
               :when (and (pos? n) (> (count spaces) 1)) space spaces] {:space space :resource resource}))))
(defn g-miner-spaces [g pid cid]
  (when (= (:round g) (get-in g [:players pid :card-state cid :claim-round]))
    (filterv #(and (contains? (set (:active g)) %) (pos? (get-in g [:piles % :stone] 0))) [:stone-west :stone-east])))
(defn g-opportunist-resources [g context]
  (let [taken (if (= (:round g) (get-in context [:g-resources-before :round])) (get-in context [:g-resources-before :goods]) #{})]
    (when (:g-last-action? context) (filterv #(and (pos? (get-in context [:source-goods %] 0)) (not (contains? taken %))) [:wood :clay :reed :stone]))))
(defn g-dunghill-fields [player]
  (vec (for [cell (concat (a/tiles player :field) (keys (:card-fields player)))
             :let [field (get-in player (a/field-path cell))]
             :when (and (not (:blocked-card field)) (some pos? (vals (a/field-crops field))))] cell)))
(defn g-dunghill-limit [player] (max 0 (dec (count (filter #(pos? (a/amount player %)) a/animals)))))
(def g-fifteen-ops #{:g-headmaster :g-mathematician :g-miner-mark :g-miner :g-opportunist :g-pilgrim :g-animal-farm :g-bountiful-crops :g-chronicle-mark :g-chronicle :g-dunghill})
(defn g-fifteen-choices [g pid cid op context]
  (let [player (p g pid)]
    (case op
      :g-mathematician (g-mathematician-options g)
      :g-miner (map #(hash-map :space %) (g-miner-spaces g pid cid))
      :g-opportunist (map #(hash-map :resource %) (g-opportunist-resources g context))
      :g-pilgrim (if (every? #(zero? (a/amount player %)) [:wood :clay :reed :stone]) (map #(hash-map :resource %) [:wood :clay :reed :stone]) [{}])
      :g-animal-farm (map #(hash-map :resource %) a/animals)
      :g-dunghill (map #(hash-map :fields [%]) (g-dunghill-fields player))
      [{}])))
(defn run-g-fifteen-effect [g {:keys [player card effect context]} choice]
  (let [pid player current (p g pid)]
    (case (:op effect)
      :g-headmaster (update-in g [:players pid :card-state card :stored-goods :food] (fnil inc 0))
      :g-mathematician
      (let [selected (select-keys choice [:space :resource])]
        (a/require! (and (some #{selected} (g-mathematician-options g)) (not= (:round g) (get-in current [:card-state card :used-round]))) "Choose one of two equal building-resource accumulation piles, from round six onwards.")
        (-> g (update-in [:piles (:space choice) (:resource choice)] dec)
            (update-in [:players pid] a/gain {(:resource choice) 1}) (assoc-in [:players pid :card-state card :used-round] (:round g))))
      :g-miner-mark (assoc-in g [:players pid :card-state card :claim-round] (:round g))
      :g-miner (do (a/require! (some #{(:space choice)} (g-miner-spaces g pid card)) "Choose a marked Stone action space containing stone.")
                   (let [n (get-in g [:piles (:space choice) :stone])]
                     (-> g (assoc-in [:piles (:space choice) :stone] 0) (update-in [:players pid] a/gain {:stone n})
                         (update-in [:players pid :card-state card] dissoc :claim-round))))
      :g-opportunist (do (a/require! (some #{(:resource choice)} (g-opportunist-resources g context)) "Choose a building resource not previously taken from an action space this work phase.")
                        (update-in g [:players pid] a/gain {(:resource choice) 1}))
      :g-pilgrim
      (let [empty-building? (every? #(zero? (a/amount current %)) [:wood :clay :reed :stone]) empty-food? (zero? (a/amount current :food))]
        (a/require! (and (or empty-building? empty-food?) (not= (:round g) (get-in current [:card-state card :used-round]))
                        (if empty-building? (contains? #{:wood :clay :reed :stone} (:resource choice)) (nil? (:resource choice)))) "Claim the eligible food and building-resource gifts together, once per round.")
        (-> g (update-in [:players pid] a/gain (cond-> {} empty-building? (assoc (:resource choice) 1) empty-food? (assoc :food 1)))
            (assoc-in [:players pid :card-state card :used-round] (:round g))))
      :g-animal-farm
      (do (a/require! (and (g-animal-farm-available? g current) (contains? (set a/animals) (:resource choice))) "Exchange one animal for a guest once per round, during work.")
          (-> g (update-in [:players pid] a/pay {(:resource choice) 1}) (update-in [:players pid :remaining] inc)
              (update-in [:players pid :guests] (fnil inc 0)) (assoc-in [:players pid :card-state card :used-round] (:round g))
              (assoc-in [:players pid :card-state card :unplaced?] true)))
      :g-bountiful-crops
      (if (or (:manure? context) (not (:g-harvest-count current))) g
        (update-in g [:players pid :g-harvest-count] + (get context :g-harvested-goods (get context :harvested-goods (+ (:harvested-grain-fields context 0) (:harvested-vegetable-fields context 0))))))
      :g-chronicle-mark (assoc-in g [:players pid :card-state card :marked-majors] (set (:available-improvements g)))
      :g-chronicle
      (let [major (or (:improvement context) (:card context))]
        (if (contains? (get-in current [:card-state card :marked-majors] #{}) major)
          (-> g (update-in [:players pid :card-state card :marked-majors] disj major) (update-in [:players pid] a/gain {:food 1})) g))
      :g-dunghill
      (let [fields (:fields choice) eligible (set (g-dunghill-fields current))]
        (a/require! (and (vector? fields) (<= 1 (count fields) (g-dunghill-limit current)) (= (count fields) (count (set fields))) (every? eligible fields)) "Click one or two distinct planted fields, according to your animal types.")
        (reduce (fn [state cell] (let [field (get-in current (a/field-path cell)) crop (or (get (:crops choice) cell) (:crop field))]
                                  (a/require! (pos? (a/field-crop-count field crop)) "Add a resource matching the selected planted stack.")
                                  (update-in state (into [:players pid] (a/field-path cell))
                                    #(if (= crop (:crop %)) (update % :count inc) (update-in % [:under-crops crop] (fnil inc 0)))))) g fields)))))

(defn resolve-action-plow [g {:keys [player card effect context]} choice current]
  (let [pid player]
          (let [effect (plow-effect effect) cells (:cells choice) command (:source-command effect)
                available (plow-options g pid (:space command))
                selected (if (contains? choice :plow-card) (:plow-card choice) (first (:plows effect)))
                maker? (true? (:plow-maker? choice))
                maximum (+ (if selected (get-in (c/card selected) [:passive :action-plow :extra-fields] 2) 0) (if maker? 1 0))]
            (a/require! (or (nil? selected) (some #{selected} (:plows available))) "Choose one available plow.")
            (a/require! (or (not maker?) (:plow-maker? available)) "Plow Maker is unavailable.")
            (a/require! (and (sequential? cells) (<= 1 (count cells) maximum)) "Choose the additional fields allowed by your plow and Plow Maker.")
            (when-let [exact (get-in (c/card selected) [:passive :action-plow :exact-cells])]
              (a/require! (and (set/subset? (set exact) (set (cons (:cell command) cells)))
                               (= (+ 3 (if maker? 1 0)) (inc (count cells)))) "Plow all three middle farm spaces together."))
            (when (and selected (not= selected (c/id :a018)))
              (a/require! (>= (+ (if (some? (:cell command)) 1 0) (count cells) (if maker? -1 0)) 2)
                          "Use this plow to plow at least two fields, before Plow Maker's extra field."))
            (when (get-in (c/card selected) [:passive :action-plow :separated?])
              (let [[one two] (take 2 (concat (when (some? (:cell command)) [(:cell command)]) cells))]
                (a/require! (and (a/cell? one) (a/cell? two)
                                 (or (> (#?(:clj Math/abs :cljs js/Math.abs) (- (quot one 5) (quot two 5))) 1)
                                     (> (#?(:clj Math/abs :cljs js/Math.abs) (- (mod one 5) (mod two 5))) 1)))
                            "Rotherham Plow's two fields cannot touch, even diagonally.")))
            (let [public? (get-in (c/card selected) [:passive :action-plow :public?])
                  owner (when public? (:id (first (filter #(some #{selected} (c/active-owned %)) (:players g)))))
                  base (if (and public? (not= pid owner))
                         (-> g (update-in [:players pid] a/pay {:food 2}) (update-in [:players owner] a/gain {:food 2})) g)
                  planned (assoc base :plow-plan {:player pid :space (:space command) :cells (vec cells)
                                                 :card selected :owner owner :plow-maker? maker?})]
              (a/plow-action-player planned pid (:space command) (:cell command))
              (take-action planned pid command)))))

(defn run-effect* [g {:keys [player card effect context window] :as head} choice]
  (let [pid player current (p g pid) op (:op effect) cost (expr/quantities g pid context (or (:cost effect) {}))
        discount-used (if (and (= :card-action op)
                               (or (and (:card choice) (not (:as-major? choice))
                                        (some (:kinds effect) [:minor :occupation])
                                        (#{:minor :occupation} (:kind (c/card (:card choice)))))
                                   (and (:improvement choice) (= #{:minor} (:kinds effect))
                                        (a/major-as-minor? current (:improvement choice)))))
                        (min (:food cost 0) (hand-food-discount current)) 0)
        cost (cond-> cost (pos? discount-used) (update :food - discount-used))
        choice (if (and (= op :choose-good) (contains? effect :selected-resource))
                 (assoc choice :resource (:selected-resource effect)) choice)]
    (if (:skip? choice)
      (do (a/require! (optional-effect? g head) "This effect is mandatory.")
          (case op
            :g-animal-farm (if (:last-chance? effect) (advance (assoc-in g [:players pid :card-state card :declined-round] (:round g))) g)
            :plow (if (:discard-on-skip? effect) (-> g (update-in [:players pid] remove-owned-improvement card) (update :removed-cards (fnil conj []) card)) g)
            :build-stables (if (:source-command effect)
                             (take-action (assoc g :stableman-plan true) pid (:source-command effect)) g)
            :freshman-bake (if (:source-command effect) (resume-freshman-source g pid effect {:grain 0}) g)
            :sow-offer (finish-without-sowing g pid effect)
             :wheel-plow (try (take-action (assoc g :plow-plan {:player pid :space (get-in effect [:source-command :space]) :cells []}) pid (:source-command effect))
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _
                               ;; An anytime construction may invalidate the plan.
                               ;; Declining then cancels the uncommitted placement.
                               (update (dissoc (finish-action-context g false) :plow-plan :freshman-plan) :cancelled-sowing (fnil conj #{})
                                       [pid (get-in effect [:source-command :space]) (:round g)])))
            g))
      (let [deferred-sidekick? (and (= op :sidekick) (staged-sowing? g pid (five/stage-left g (:space context))))
            _ (when (:businessman? effect)
                (a/require! (businessman-available? g pid context) "Businessman has already been used for this action."))
            _ (when (:once-per-turn? effect)
                (a/require! (not= (freshman-turn-key g) (get-in current [:card-state card :last-turn])) "This card was already used this turn."))
            _ (when (:pay-actor? effect)
                (a/require! (and (integer? (:actor context)) (<= 0 (:actor context))
                                 (< (:actor context) (count (:players g))) (not= pid (:actor context)))
                            "This effect pays another player's action."))
            headmaster (if (and (= op :card-action) (= #{:occupation} (:kinds effect)))
                         (headmaster-payment g pid (:food cost 0) (if (contains? choice :headmaster-entry-food) {:headmaster-food (:headmaster-entry-food choice)} {})) 0)
            cost (cond-> cost (pos? headmaster) (update :food - headmaster))
            g (cond-> (if deferred-sidekick? g (update-in g [:players pid] #(a/pay (spend-headmaster % headmaster) cost)))
                (:pay-actor? effect) (update-in [:players (:actor context)] a/gain cost)
                (:businessman? effect) (assoc-in [:players pid :card-state card (if (:merchant-repeat? context) :repeat-turn :last-turn)] (freshman-turn-key g))
                (:once-per-turn? effect) (assoc-in [:players pid :card-state card :last-turn] (freshman-turn-key g)))]
        (case op
          :remove-empty-field
          (let [after (update-in g [:players pid] #(-> % (a/remove-empty-field (:cell choice))
                                                       (a/gain (expr/quantities g pid context (:goods effect)))))]
            (when (= :pasture (:then effect))
              (a/require! (some (fn [cell] (try (a/build-mini-pasture (p after pid) cell nil) true
                                                (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false))) (range 15)) "A legal single-space pasture must be buildable after removing the field."))
            (cond-> after (= :pasture (:then effect)) (queue-front [(task pid card {:op :mini-pasture} {})])))
          :relocate
          (let [origins (relocation-origins g pid) index (:option choice)]
            (a/require! (and (integer? index) (<= 0 index) (< index (count origins))) "Choose one eligible person to move.")
            (let [context (nth origins index)]
              (queue-front g [(task pid (:relocation-card context) {:op :extra-action :mode :original-relocation :optional? true} context)
                              (task pid nil {:op :relocate :optional? true} {})])))
          :serf (resume-serf g pid effect choice)
          :perpetual-play
          (binding [*perpetual-draw?* true]
            (play-card g pid (:card effect) (:occupation-cost effect)
                       (dissoc choice :perpetual-student?) (:timing effect)))
          :adopt-original
          (let [n (get choice :amount 1) maximum (min (:newborn current 0) (:newborns-added context 1))]
            (a/require! (and (#{:preparation :work} (:phase g)) (a/natural? n) (pos? n) (<= n maximum)) "Adopt only the newborns from this family growth.")
            ;; One food was paid by the effect; pay additional adopted children.
            (reduce (fn [state _] (-> state (update-in [:players pid :remaining] inc)
                                        (update-in [:players pid :newborn] dec) (adopt-newborn pid)))
                    (update-in g [:players pid] a/pay {:food (dec n)}) (range n)))
          :wet-nurse
          (let [n (get choice :amount 1)]
            (a/require! (and (a/natural? n) (pos? n) (<= n (:rooms-built context 0))) "Grow by at most the number of rooms just built.")
            (let [grown (reduce (fn [player index] (a/grow player true (cond-> {} (and (zero? index) (:adopt-reed-hut? choice)) (assoc :adopt-reed-hut? true))))
                                (a/pay current {:food n}) (range n))]
              (-> g (assoc-in [:players pid] grown) (record-newborn pid)
                  (queue-front [(event :grow pid {:family-before (:family current) :newborns-added n}) (event :check-state pid)]))))
          :pieceworker
          (let [purchase (:purchase choice) cost (reduce + 0 (map #(if (= :vegetable %) 2 1) (keys purchase)))]
            (a/require! (and (map? purchase) (seq purchase) (every? #{1} (vals purchase))
                             (every? (set (pieceworker-resources context)) (keys purchase))) "Buy at most one of each resource taken directly from this action space.")
            (update-in g [:players pid] #(-> % (a/pay {:food cost}) (a/gain purchase))))
          :educator (granted-card-action g pid {:kinds #{:occupation} :occupation-cost (if (>= (c/occupation-count current nil) 3) 2 3)} choice)
          :original-scholar
          (if (and (:card choice) (= :occupation (:kind (c/card (:card choice)))))
            (play-card g pid (:card choice) 1 choice {:event :round-start :window window :original-scholar? true})
            (granted-card-action g pid {:kinds #{:major :minor} :event :round-start :window window :original-scholar? true} choice))
          :basin-maker
          (let [n (get choice :amount 0) boars (quot (inc n) 2)]
            (a/require! (and (a/natural? n) (pos? n) (<= boars (unclaimed-cooked-boar g context :basin))) "Place up to two wood for each cooked boar not used for Tanner.")
            (-> g (update-in [:players pid] a/pay {:wood n})
                (update-in [:players pid :card-state card :stored-goods :wood] (fnil + 0) n)
                (assoc-in [:cooked-marker-claims (:cooking-receipt context) :basin] boars)))
          :tanner
          (let [goods (select-keys (:animals choice) [:boar :cattle])]
            (a/quantities! goods [:boar :cattle])
            (a/require! (and (some pos? (vals goods)) (<= (:boar goods 0) (unclaimed-cooked-boar g context :tanner))
                             (<= (:cattle goods 0) (:cooked-cattle context 0))) "Collect only newly cooked animals, and do not reuse Brush Maker or Basin Maker boar.")
            (-> g (update-in [:players pid :card-state card :stored-goods] #(merge-with + (or % {}) goods))
                (assoc-in [:cooked-marker-claims (:cooking-receipt context) :tanner] (:boar goods 0))))
          :wood-distributor
          (let [wood (get-in g [:piles :wood :wood] 0) each (quot wood 3)]
            (a/require! (and (= :preparation (:phase g)) (pos? each)
                             (not= (:round g) (get-in current [:card-state card :used-round]))) "Distribute the three-wood pile at most once before work begins.")
            (-> (reduce #(update-in %1 [:piles %2 :wood] (fnil + 0) each) g [:clay :reed :fishing])
                (update-in [:piles :wood :wood] - (* 3 each))
                (assoc-in [:players pid :card-state card :used-round] (:round g))))
          :harvest-helper
          (let [selected (select-keys choice [:owner :cell]) owner (:owner selected)]
            (a/require! (some #{selected} (harvest-helper-options g pid)) "Choose one grain on another player's field.")
            (-> g (update-in [:players owner] #(-> % (a/remove-field-crop (:cell selected) :grain) (a/gain {:food 2})))
                (update-in [:players pid] a/gain {:grain 1})
                (cond-> (some true? (c/passives current :observe-extra-grain-harvest))
                  (queue-front [(event :harvest-crops pid {:harvested-grain-fields 1
                                                         :harvested-vegetable-fields 0})]))))
          :cover-unused
          (let [n (:amount choice 0)]
            (a/require! (and (a/natural? n) (<= n (unused-count current))) "Choose a number of unused farmyard spaces.")
            (a/require! (not (:hide-farmer-settled? current)) "Hide Farmer has already been settled.")
            (-> g (update-in [:players pid] a/pay {:food n})
                (assoc-in [:players pid :paid-unused-spaces] n)
                (assoc-in [:players pid :hide-farmer-settled?] true)))
          :juggler
          (let [recipients (for [other (:players g) :when (not= pid (:id other))]
                             [(:id other) (count (filter juggler-artists (c/active-owned other)))])
                fee (reduce + 0 (map second recipients)) bonus (get-in context [:taken :food] 0)]
            (reduce (fn [state [owner n]] (update-in state [:players owner] a/gain {:food n}))
                    (-> g (update-in [:players pid] a/gain {:food bonus})
                        (update-in [:players pid] a/pay {:food fee})) recipients))
          :corn-offer
          (do (a/require! (and (:corn-sale g) (not= pid (get-in g [:corn-sale :seller]))
                               (not (some #{pid} (get-in g [:corn-sale :offers])))) "That grain sale is not awaiting your offer.")
              (-> g (update-in [:players pid] a/pay {:food 2}) (update-in [:corn-sale :offers] conj pid)))
          :corn-select
          (let [{:keys [seller offers]} (:corn-sale g) buyer (or (:buyer choice) (when (= 1 (count offers)) (first offers)))]
            (a/require! (= seller pid) "Only the grain seller chooses a buyer.")
            (a/require! (or (empty? offers) (some #{buyer} offers)) "Choose one of the players who offered to buy.")
            (-> (reduce (fn [state other] (if (= other buyer) (update-in state [:players other] a/gain {:grain 1})
                                            (update-in state [:players other] a/gain {:food 2}))) g offers)
                (update-in [:players seller] a/gain {:food (if (seq offers) 2 3)})
                (dissoc :corn-sale)))
          :reveal-rounds
          (let [order (vec (or (:round-order effect) (:round-order g) (:revealed-round-order g)))
                order (if (= 11 (:round g))
                        (let [first-space (if (:cultivation-first? choice) :cultivation :urgent-growth)
                              second-space (if (:cultivation-first? choice) :urgent-growth :cultivation)]
                          (assoc order 11 first-space 12 second-space)) order)]
            (-> g (assoc :round-order order :revealed-round-order order)
                (update :active #(vec (distinct (concat % (drop (:round g) order)))))))
          :buy-action-resource
          (do (a/require! (buyer-available? g pid effect context) "Buy only one resource actually retained from this action, paying one food first.")
              (let [actor (:actor context) resource (:resource effect)]
                (-> g (assoc-in [:players pid :buyer-turns resource] (vec (take 3 (:action-id context))))
                    (update-in [:players pid] a/gain {resource 1})
                    (update-in [:action-returned-goods (:action-id context) resource] (fnil inc 0))
                    (update-in [:players actor] #(-> % (a/pay {resource 1}) (a/gain {:food (+ 1 (:compensation effect 0))}))))))
          :net-fisherman
          (let [food (get-in g [:piles :fishing :food] 0)]
            (a/require! (pos? food) "No food remains on Fishing.")
            (-> g (assoc-in [:piles :fishing :food] 0) (update-in [:players pid] a/gain {:food food})))
          :animal-dealer (update-in g [:players pid] a/gain {(:space context) 1})
          :resource-seller-receipt
          (let [options (resource-seller-options g pid card (:goods effect))
                index (get choice :option (when (= 1 (count options)) 0))]
            (a/require! (and (integer? index) (<= 0 index) (< index (count options))) "Choose the order in which you receive these resources.")
            (let [{:keys [stack goods]} (nth options index)]
              (-> g (update-in [:players pid] a/gain goods)
                  (assoc-in [:players pid :card-state card :resource-stack] stack))))
          :flush-action-receipts (finish-action-receipt g (:action-id effect))
          :place-space-goods
          (let [spaces (:spaces choice) eligible (set (fence-marker-spaces g pid))
                goods (expr/quantities g pid context (:goods effect))]
            (a/require! (and (sequential? spaces) (= (:count effect) (count spaces) (count (set spaces)))
                             (every? eligible spaces))
                        (str "Choose " (:count effect) " different revealed action spaces."))
            (reduce (fn [state space] (update-in state [:space-bonuses space] #(merge-with + (or % {}) goods))) g spaces))
          :foreman
          (do (a/require! (some #{(:space choice)} (fence-marker-spaces g pid)) "Choose an available action space for the food.")
              (a/require! (not= (:round g) (get-in current [:card-state card :used-round])) "Foreman was already used this round.")
              (-> g (update-in [:space-bonuses (:space choice) :food] (fnil inc 0))
                  (assoc-in [:players pid :card-state card :used-round] (:round g))))
          :animal-breeder
          (let [animal (:animal choice) price ({:sheep 1 :boar 2 :cattle 3} animal)]
            (a/require! (and price (animal-breeder-available? (assoc-in g [:players pid :card-state card :last-turn] nil) pid context)) "Buy one pair after fencing previously unused land.")
            (update-in g [:players pid] #(-> % (a/pay {:food price}) (a/gain {animal 2}))))
          :animal-trainer
          (let [goods (or (:animals choice) {}) _ (a/quantities! goods a/animals)
                price (reduce + 0 (map (fn [[animal n]] (* n ({:sheep 2 :boar 2 :cattle 3} animal))) goods))]
            (a/require! (and (pos? price) (<= price (:pile-food context 0))) "Spend only the food lying on Traveling Players, without reusing animal-conversion proceeds.")
            (update-in g [:players pid] #(-> % (a/pay {:food price}) (a/gain goods))))
          :fence-overseer
          (let [cell (:cell choice)]
            (a/require! (some #{cell} (fence-overseer-cells g pid context)) "Choose an unfenced stable just built, once this round.")
            (-> g (update-in [:players pid] a/build-mini-pasture cell (:fences choice))
                (assoc-in [:players pid :card-state card :used-round] (:round g))))
          :extra-animal-birth
          (let [animal (:animal effect) next (a/gain current {animal 1})]
            (if (and (>= (a/amount current animal) 2) (a/animal-housing next))
              (assoc-in g [:players pid] next) g))
          :shepherd-boy
          (if (and (= :stone (:house current)) (not (get-in current [:card-state card :scheduled?])))
            (-> g (schedule pid card {:offset-count [:rounds-left] :goods {:sheep 1}})
                (assoc-in [:players pid :card-state card :scheduled?] true)) g)
          :veterinarian
          (let [bag (vec (get-in current [:card-state card :bag]))]
            (if (< (count bag) 2) g
              (let [[g i] (random/choose g (vec (range (count bag))))
                    [g j] (random/choose g (vec (remove #{i} (range (count bag)))))]
                (cond-> (assoc-in g [:players pid :card-state card :last-draw] [(bag i) (bag j)])
                  (= (bag i) (bag j))
                  (-> (update-in [:players pid] a/gain {(bag i) 1})
                      (assoc-in [:players pid :card-state card :bag] (vec (concat (subvec bag 0 i) (subvec bag (inc i))))))))))
          :extra-boar-birth
          (let [next (a/gain current {:boar 1})]
            (if (and (>= (a/amount current :boar) 2) (a/animal-housing next))
              (assoc-in g [:players pid] next) g))
          :taster
          (do (a/require! (taster-available? (assoc-in g [:players pid] current) pid) "Taster requires another starting player and one food before the work phase.")
              (-> g (update-in [:players (:starting-player g)] a/gain {:food 1})
                  (assoc :taster {:round (:round g) :player pid :starting-player (:starting-player g)})))
          :water-carrier-start
          (if (or (pos? (:well-build-count g 0))
                  (some #(or (contains? (:improvements %) :well)
                             (c/has? % :catalog/original-base-i-66-village-well)) (:players g)))
            (schedule g pid card {:offset-count [:rounds-left] :goods {:food 1}}) g)
          :mark-fence-space
          (let [space (:space choice)]
            (a/require! (pos? (a/fences-available current)) "No fence piece remains to mark an action space.")
            (a/require! (some #{space} (fence-marker-spaces g pid)) "Mark a currently available action space.")
            (-> g (update-in [:players pid :discarded-fences] (fnil inc 0))
                (assoc-in [:players pid :card-state card :marked-space] space)))
          :schedule-fences
          (loop [state g left (a/fences-available current) offsets [6 10]]
            (if-let [offset (first offsets)]
              (let [round (+ (:round g) offset) n (min 4 left)]
                (recur (if (and (< round 15) (pos? n))
                         (assoc-in state [:players pid :reserved-fences round] n) state)
                       (- left n) (rest offsets))) state))
          :fence-delivery
          (let [n (get-in current [:reserved-fences (:round g)] 0)]
            (cond-> (update-in g [:players pid :reserved-fences] dissoc (:round g))
              (pos? n) (queue-front [(task pid card {:op :build-fences :optional? true :cost {:food 2}
                                                    :free-fences n :max-fences (+ n (a/turn-free-fences-available current))} context)])))
          :return-animal-loan (return-animal-loan g pid card (:return-animals choice))
          :collect-cooked-animal
          (let [resource (:resource effect) maximum (if (= resource :boar) (unclaimed-cooked-boar g context :brush) (get-in context [:converted-goods resource] 0))
                amount (get choice :amount maximum)]
            (a/require! (and (a/natural? amount) (pos? amount) (<= amount maximum)) "Collect only markers from the animals just cooked, or skip this effect.")
            (cond-> (update-in g [:players pid :card-state card :stored-goods resource] (fnil + 0) amount)
              (= resource :boar) (assoc-in [:cooked-marker-claims (:cooking-receipt context) :brush] amount)))
          :ability-window g
          :play-hand-response (play-card g pid (:card effect) 0 {} {:from-hand-response? true})
          :set-card-state (update-in g [:players pid :card-state card] merge (expr/quantities g pid context (:values effect)))
          :discard-self (-> g (update-in [:players pid :occupations] #(vec (remove #{card} %)))
                            (update-in [:players pid :minors] #(vec (remove #{card} %)))
                            (update :removed-cards (fnil conj []) card))
          :paper-knife
          (let [choices (:cards choice) hand (set (get-in current [:hand :occupation]))]
            (a/require! (and (= 3 (count choices)) (= 3 (count (set choices))) (every? hand choices))
                        "Choose exactly three distinct occupations from your hand.")
            (let [[g selected] (random/choose g (vec (sort choices)))]
              (try (play-card g pid selected 0 {})
                   (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
                     (if (= :illegal-command (:type (ex-data error)))
                       (-> g (update-in [:players pid :hand :occupation] #(vec (remove #{selected} %)))
                           (update :removed-cards (fnil conj []) selected)
                           (a/log-event (str (:name current) " could not play the randomly selected " (:name (c/card selected)) "; it leaves the game.")))
                       (throw error))))))
          :watch-wood (reactive/watch-wood g pid)
          :watch-fences (reactive/watch-fences g pid)
          :potters-yard (reactive/potters-yard g pid)
          :sequestrator (reactive/sequestrator g pid)
          :loppers (do (a/require! (pos? (a/fences-available current)) "No fence piece remains to exchange.")
                       (-> g (update-in [:players pid :discarded-fences] (fnil inc 0))
                           (update-in [:players pid] a/gain {:food 2})
                           (update-in [:players pid :bonus-points] (fnil inc 0))))
          :freshman-bake
          (if (:source-command effect)
            (resume-freshman-source g pid effect choice)
            (if-let [cid (:card choice)]
              (use-freshman g pid cid choice)
              (run-effect* g (assoc head :effect (:original effect)) choice)))
          :classify-fields
          (let [original (:original effect) options (expr/classification-options current original)
                _ (doseq [[option amount] [[:mixed-as-grain (:mixed-fields options)] [:dual-as-major (:dual-improvements options)]]
                          :when (pos? (or amount 0))]
                    (a/require! (and (a/natural? (get choice option)) (<= (get choice option) amount)) "Choose how to classify each mixed field or improvement, without counting it twice."))
                context (merge context (select-keys choice [:mixed-as-grain :dual-as-major]))]
            (run-effect* g (assoc head :effect original :context context) choice))
          :heresy-teacher
          (update-in g [:players pid]
                     (fn [player]
                       (reduce (fn [player cell]
                                 (let [path (a/field-path cell) field (get-in player path)]
                                   (if (and (>= (a/field-crop-count field :grain) 3) (zero? (a/field-crop-count field :vegetable)))
                                     (assoc-in player (conj path :under-crops) {:vegetable 1}) player)))
                               player (concat (a/tiles player :field) (keys (:card-fields player))))))
          :card-action (binding [*hand-food-discount-used* discount-used] (granted-card-action g pid (cond-> effect (:businessman? effect) (assoc :merchant-repeat? (:merchant-repeat? context))) choice))
          :telegram
          (let [round (+ (:round g) (a/fences-available current))]
            (cond-> (assoc-in g [:players pid :card-state card :arrival-round] round)
              (< round 15) (update-in [:players pid :schedule round] (fnil conj []) {:card card :person true})))
          :temporary-person
          (do (a/require! (pos? (a/unused-person-tokens current)) "No person remains in your supply.")
              (-> g (update-in [:players pid :family] inc) (update-in [:players pid :remaining] inc)
                    (update-in [:players pid :temporary-family] (fnil inc 0))))
          :lodger-remove (update-in g [:players pid] remove-lodger-person (:person-kind choice))
          :lodger-start
          (assoc-in g [:players pid :card-state card :room-active?]
                    (or (< (:round g) 9) (and (= (:round g) 9) (#{:preparation :work} (:phase g)))))
          :reserve-stables
          (let [rounds (:rounds choice) valid (set (planner-rounds g effect))]
            (a/require! (and (sequential? rounds) (seq rounds) (= (count rounds) (count (set rounds)))) "Choose different future rounds for your stables.")
            (a/require! (every? valid rounds) "Stable Planner allows only the printed future rounds before game end.")
            (a/require! (<= (count rounds) (a/stables-available current)) "You do not have enough unreserved stable pieces.")
            (reduce (fn [state round] (-> state
                                         (update-in [:players pid :reserved-stables round] (fnil inc 0))
                                         (update-in [:players pid :schedule round] (fnil conj []) (cond-> {:card card :stable true} (:arrival-cost effect) (assoc :stable-cost (:arrival-cost effect)))))) g rounds))
          :stable-arrival
          (-> g (update-in [:players pid :reserved-stables] dissoc (:round g))
              (queue-front [(task pid card (cond-> {:op :stable :optional? true} (:stable-cost effect) (assoc :cost (:stable-cost effect))) context)]))
          :extra-action (execute-extra-action g pid effect context (:action-command choice))
          :grow (-> g (update-in [:players pid] a/grow (:room-required? effect) (cond-> choice (:family-planner? effect) (assoc :space :growth))) (record-newborn pid)
                    (queue-front [(event :grow pid {:family-before (:family current) :newborns-added 1}) (event :check-state pid)]))
          :build-rooms
          (let [rooms (:rooms choice)]
            (a/require! (and (sequential? rooms) (seq rooms)) "Choose at least one room to build.")
            (when (:max-rooms effect) (a/require! (<= (count rooms) (:max-rooms effect)) "This card allows only one room."))
            (update-in g [:players pid] a/build-rooms rooms choice (select-keys effect [:room-discount :free?])))
          :build-stables
          (let [cells (:stables choice)]
            (a/require! (and (seq cells) (<= (count cells) (:max-stables effect 4))) "Choose the allowed number of stables.")
            (let [after (update-in g [:players pid] a/build-stables cells (:stable-cost effect 2) choice)]
              (if (:source-command effect)
                (queue-front after [(task pid card {:op :resume-stableman :source-command (:source-command effect)} {})]) after)))
          :resume-stableman (take-action (assoc g :stableman-plan true) pid (:source-command effect))
          :discard-begging (update-in g [:players pid] discard-any-begging)
          :pearl-seeker
          (let [n (:amount choice)]
            (a/require! (and (a/natural? n) (<= 1 n (pearl-seeker-max g pid context))) "Buy stone only with food actually taken from Fishing, at two food per stone.")
            (update-in g [:players pid] #(-> % (a/pay {:food (* 2 n)}) (a/gain {:stone n}))))
          :award-winner
          (let [resource (:resource choice)]
            (a/require! (some #{resource} (award-winner-resources g pid card context)) "Pay one building resource actually paid for this improvement, once per round.")
            (-> g (update-in [:players pid] a/pay {resource 1})
                  (update-in [:players pid :bonus-points] (fnil inc 0))
                  (assoc-in [:players pid :card-state card :used-round] (:round g))))
          :room-resource
          (let [cells (:cells choice) eligible (set (expr/evaluate g pid context (:cells effect)))
                rooms (set (a/tiles current :room)) used (set (keys (get-in current [:card-state card :room-resources])))
                resource (:resource effect) maximum (expr/evaluate g pid context (:max effect))]
            (a/require! (and (sequential? cells) (<= 1 (count cells) maximum) (= (count cells) (count (set cells)))
                             (every? eligible cells) (every? rooms cells) (not-any? used cells)) "Place scoring wood only in rooms just built, once in each room.")
            (cond-> (update-in g [:players pid :card-state card :room-resources] #(merge (or % {}) (zipmap cells (repeat resource))))
              (:pay? effect) (update-in [:players pid] a/pay {resource (count cells)})))
          :gem-hunter
          (let [owed? (and (<= (:round g) 9) (>= (get-in context [:source-goods :clay] 0) 3))]
            (cond-> (update-in g [:players pid] a/gain {:stone 1})
              owed? (update-in [:players pid] a/pay {:clay 1})
              owed? (update-in [:piles (:space context) :clay] (fnil inc 0))
              owed? (update-in [:action-returned-goods (:action-id context) :clay] (fnil inc 0))))
          :wheelbarrow-pusher
          (let [resource (:resource choice)]
            (a/require! (and (expr/evaluate g pid context [:wheelbarrow-eligible]) (some #{resource} (wheelbarrow-choices g))) "Choose one resource from this single-resource accumulation action.")
            (update-in g [:players pid] a/gain (merge-with + {resource 1} (when (>= (count (:players g)) 4) {:food 1}))))
          :wood-worker (-> g (update-in [:space-bonuses (:space context) :food] (fnil inc 0))
                            (update-in [:players pid] a/gain {:wood 1}))
          :pine-forest
          (do (a/require! (and (zero? (a/amount current :wood))
                               (not= (:round g) (get-in current [:card-state card :used-round]))) "Store wood once at round start, only with no wood in your supply.")
              (let [n (inc (get-in current [:card-state card :stored-goods :wood] 0))]
                (cond-> (-> g (assoc-in [:players pid :card-state card :stored-goods :wood] (if (>= n 5) 0 n))
                              (assoc-in [:players pid :card-state card :used-round] (:round g)))
                  (>= n 5) (update-in [:players pid] a/gain {:wood n}))))
          :pass-this-card (queue-front g [{:kind :pass-card :player pid :card card}])
          :room-food
          (let [cells (:cells choice) rooms (set (a/tiles current :room))
                used (set (get-in current [:card-state card :room-food]))
                maximum (expr/evaluate g pid context (:max effect))]
            (a/require! (and (sequential? cells) (<= 1 (count cells) maximum)
                             (= (count cells) (count (set cells))) (every? rooms cells) (not-any? used cells))
                        "Choose empty scoring spaces in your rooms, once per room for this card.")
            (cond-> (update-in g [:players pid :card-state card :room-food] (fnil into #{}) cells)
              (:pay? effect) (update-in [:players pid] a/pay {:food (count cells)})))
          :take-field-crops
          (let [plantings (:plantings choice)]
            (a/require! (and (sequential? plantings) (seq plantings)
                             (= (count plantings) (count (set (map :cell plantings))))) "Choose at most one crop from each planted field.")
            (reduce (fn [state {:keys [cell crop]}]
                      (let [field (get-in (p state pid) (a/field-path cell))]
                        (a/require! (and (#{:grain :vegetable} crop) (= :field (:type field))
                                         (pos? (a/field-crop-count field crop))) "Choose grain or vegetables actually planted on this field.")
                        (update-in state [:players pid] #(-> % (a/remove-field-crop cell crop) (a/gain {crop 1}))))) g plantings))
          :build-fences
          (let [recipients (when (:hammer? effect) (filter #(and (not= pid (:id %)) (seq (:fences %))) (:players g)))
                g (if (seq recipients) (reduce (fn [state owner] (update-in state [:players (:id owner)] a/gain {:wood 1}))
                                              (update-in g [:players pid] a/pay {:wood (count recipients)}) recipients) g)]
          (do (when (:paddocks? effect)
                (a/require! (every? (paddocks-edges current) (:fences choice)) "Paddocks may only subdivide existing pastures."))
              (when (:max-fences effect) (a/require! (<= (count (:fences choice)) (if (:paddocks? effect) (paddocks-limit current) (:max-fences effect))) "Build only the delivered fences and any Hedge Keeper bonus."))
              (update-in g [:players pid] #(a/build-fences (cond-> % (:reset-fence-bonus? effect) a/reset-turn-fence-bonus) (:fences choice)
                         (merge (select-keys choice [:fence-material :wood-carver? :wood-clay :wood-stone :stonepiler-stone :alternative-first? :frame-materials]) (select-keys effect [:free-fences]))))))
          :breeder-buyer
          (let [{:keys [rooms stables rewarded?]} (get-in g [:turn-construction pid])]
            (if (and (seq rooms) (pos? (or stables 0)) (not rewarded?))
              (-> g (update-in [:players pid] a/gain {({:wood :sheep :clay :boar :stone :cattle} (last rooms)) 1})
                    (assoc-in [:turn-construction pid :rewarded?] true)) g))
          :sow-offer
          (do
            (a/require! (can-pay? current (or (:placement-cost effect) {})) "Pay the extra placement cost before starting this Sow action.")
            (a/require! (sowing-potential? g pid effect) "There is no legal sowing plan, even with the grain available before sowing.")
            (-> g (assoc :sow-in-progress pid)
                (queue-front [(event :before-sow pid {:unconditional? (:unconditional? effect)})
                              ;; The offer has already paid its cost. Internal
                              ;; sowing steps must not charge it again.
                              (task pid card (assoc (dissoc effect :cost) :op :sow-start :optional? false) context)])))
          :sow-start
          (if (sowable? (sowing-player g pid effect) (select-keys effect [:stack-adjust :empty-only?]))
            (queue-front g [(task pid card (assoc effect :op :sow :optional? false) context)])
            (finish-without-sowing g pid effect))
          :sow
          (let [plantings (:plantings choice)]
            (a/require! (seq plantings) "Plant at least one field to take this Sow action.")
            (when (:max-fields effect) (a/require! (<= (count plantings) (:max-fields effect)) "This Sow action is limited to one field."))
            (when (:only-cells effect)
              (a/require! (every? (set (:only-cells effect)) (map :cell plantings)) "Sow only the field just plowed by this card."))
            (when (:allowed-crops effect)
              (a/require! (every? #((:allowed-crops effect) (:crop %)) plantings) "This card permits sowing only the offered crop."))
            (a/sow (sowing-player g pid effect) plantings (merge (select-keys effect [:stack-adjust :empty-only?]) (select-keys choice [:smallholder?])))
            (let [g (dissoc g :sow-in-progress)]
              (if-let [command (:source-command effect)]
                (complete-sow-placement g pid effect (cond-> (merge command (select-keys choice [:smallholder? :use-beehive?]) {:plantings plantings})
                                             (and (= :sow-bake (:space command)) (contains? choice :bake-grain)) (assoc :bake-grain (:bake-grain choice) :cook-with-bake (:cook-with-bake choice))))
                (-> g (update-in [:players pid] a/sow plantings (merge (select-keys effect [:stack-adjust :empty-only?]) (select-keys choice [:smallholder?])))
                    (queue-front [(event :sow pid (cond-> {:unconditional? (:unconditional? effect) :vegetables-sown (count (filter #(= :vegetable (:crop %)) plantings))}
                                      (c/has? current :catalog/original-expansion-g-104-seasonal-plants) (assoc :plantings plantings)))
                                  (event :check-state pid)])))))
          :open-accumulation (assoc-in g [:piles card] (:goods effect))
          :fisherman
          (let [recipients (for [other (:players g) :when (not= pid (:id other))]
                             [(:id other) (count (filter (:recipients effect) (c/owned other)))])
                fee (reduce + 0 (map second recipients))
                bonus (get-in context [:taken :food] 0)]
            (reduce (fn [state [owner amount]] (update-in state [:players owner] a/gain {:food amount}))
                    (-> g (update-in [:players pid] a/gain {:food bonus})
                        (update-in [:players pid] a/pay {:food (min bonus fee)})) recipients))
          :lasso-followup
          (do (a/require! (and (= :work (:phase g)) (= pid (:turn g)) (pos? (:remaining current)) (not (:lasso g))
                               (some #(and (some #{%} (:active g)) (not (contains? (:occupied g) %))) (lasso-animal-spaces current)))
                           "A remaining person and a free animal space are needed to use Lasso now.")
              (assoc g :lasso {:player pid :must-animal? true :second? false
                              :animal-spaces (lasso-animal-spaces current) :separate-turns? true}))
          :water-mill (cond-> (update-in g [:players pid] a/gain {:food (+ (if (= pid (:owner effect)) 3 2) (a/vegetarian-food current {:grain 1}))})
                        (not= pid (:owner effect)) (update-in [:players (:owner effect)] a/gain {:food 1}))
          :gain (let [goods (expr/quantities g pid context (:goods effect))]
                  (if-let [cost (:conversion-cost effect)]
                    (-> (paid-food-conversion g pid card cost (:food goods)) (update-in [:players pid] a/gain (dissoc goods :food)))
                    (update-in g [:players pid] a/gain goods)))
          :store-goods (update-in g [:players pid :card-state card :stored-goods]
                                 #(merge-with + (or % {}) (expr/quantities g pid context (:goods effect))))
          :clear-stored (assoc-in g [:players pid :card-state card :stored-goods] {})
          :discard-stored
          (update-in g [:players pid :card-state card :stored-goods]
                     (fn [stored] (into {} (for [[resource n] stored
                                                :let [left (max 0 (- n (get (:goods effect) resource 0)))]
                                                :when (pos? left)] [resource left]))))
          :release-stored (-> g (update-in [:players pid] a/gain (get-in current [:card-state card :stored-goods] {}))
                              (assoc-in [:players pid :card-state card :stored-goods] {}))
          :take-pile-good
          (let [selection (merge (select-keys effect [:space :resource]) (select-keys choice [:space :resource]))
                {:keys [space resource]} selection]
            (a/require! (some #{selection} (pile-good-options g effect)) "Choose an eligible good from an accumulation space.")
            (-> g (update-in [:piles space resource] dec) (update-in [:players pid] a/gain {resource 1})
                (queue-front (animal-accommodation-tasks g pid {:space space :taken {resource 1}}))))
          :exchange (let [goods (expr/quantities g pid context (:goods effect))]
                      (if (and (enhanced-food-conversion? current) (pos? (:food goods 0)) (seq cost) (not (contains? cost :food)))
                        (-> (paid-food-conversion g pid card cost (:food goods)) (update-in [:players pid] a/gain (dissoc goods :food)))
                        (update-in g [:players pid] a/gain goods)))
          :threshold-exchange
          (do (a/require! (>= (a/amount current (:resource effect)) (:minimum effect)) "You do not have enough crops for this exchange.")
              (cond-> (update-in g [:players pid] #(-> % (a/pay {(:resource effect) 1}) (a/gain {:food (+ (:food effect) (a/vegetarian-food current {(:resource effect) 1}))})))
                (:wood-group effect) (assoc-in [:players pid :card-state (c/id :a048) :group-consumed] (:wood-group effect))))
          :proportional-gain (update-in g [:players pid] a/gain {(:resource effect) (quot (a/amount current (:resource effect)) (:divisor effect))})
          :points (update-in g [:players pid :bonus-points] (fnil + 0) (expr/evaluate g pid context (:amount effect)))
          :feeding-discount (assoc-in g [:players pid :round-feeding-discount]
                                      {:round (:round g) :amount (expr/evaluate g pid context (:amount effect))})
          :repeat (queue-front g (repeat (expr/evaluate g pid context (:count effect)) (task pid card (:effect effect) context)))
          :water-crops
          (update-in g [:players pid]
                     (fn [player]
                       (reduce (fn [player cell]
                                 (reduce (fn [player crop]
                                           (let [path (a/field-path cell) tile (get-in player path)]
                                             (if (pos? (a/field-crop-count tile crop))
                                               (update-in player (into path (if (= crop (:crop tile)) [:count] [:under-crops crop])) inc) player)))
                                         player [:grain :vegetable]))
                               player (concat (a/tiles player :field) (keys (:card-fields player))))))
          :sequence (queue-front g (map #(task pid card % context) (:effects effect)))
          :choose (let [index (:option choice)]
                    (a/require! (and (integer? index) (<= 0 index) (< index (count (:options effect)))) "Choose an offered card effect.")
                    (let [option (nth (:options effect) index)
                          cost (expr/quantities g pid context (:cost option))
                          conversion-index (when (and (enhanced-food-conversion? current) (seq cost) (not (contains? cost :food)))
                                             (first (keep-indexed (fn [i e] (when (and (= :gain (:op e)) (pos? (:food (expr/quantities g pid context (:goods e)) 0))) i)) (:effects option))))]
                      (a/require! (expr/evaluate g pid context (get option :when true)) "That option is unavailable.")
                      (queue-front (update-in g [:players pid] a/pay (expr/quantities g pid context (:cost option)))
                                   (map-indexed (fn [i e] (task pid card (cond-> e (= i conversion-index) (assoc :conversion-cost cost)) context)) (:effects option)))))
          :cancel-schedule
          (update-in g [:players pid :schedule]
                     #(into {} (for [[round entries] %
                                     :let [kept (if (> round (:round g)) (filterv (fn [entry] (not= card (:card entry))) entries) entries)]
                                     :when (seq kept)] [round kept])))
          :schedule (schedule g pid card (update effect :goods #(expr/quantities g pid context %)))
          :remaining-rounds (-> g (update-in [:players pid] a/gain (into {} (map (fn [[r n]] [r (* n (- 14 (:round g)))]) (:goods effect))))
                                 (update-in [:players pid :bonus-points] (fnil + 0) (* (or (:points effect) 0) (- 14 (:round g)))))
          :all-gain (update g :players #(mapv (fn [p] (a/gain p (expr/quantities g pid context (if (= pid (:id p)) (:owner effect) (:others effect))))) %))
          :pelts (let [n (:amount choice 0) available (min (:cooked-animals context 0)
                                                       (- (count (a/tiles current :room)) (get-in current [:card-state card :pelts] 0)))]
                   (a/require! (and (a/natural? n) (pos? n) (<= n available)) "Store at most one pelt per empty room and cooked animal.")
                   (-> g (update-in [:players pid] a/pay {:food n})
                       (update-in [:players pid :card-state card :pelts] (fnil + 0) n)))
          :landing-net (update-in g [:players pid] a/gain
                                 {:food (if (some #(pos? (expr/evaluate g pid context [:received-since %])) [:wood :clay :stone]) 1 2)})
          :clapper (let [spaces (cond-> #{:growth :urgent-growth :modest-growth}
                                  (and (= 5 (count (:players g))) (>= (:round g) 5)) (conj :lessons-growth))
                         used? (if (:played? effect) (some spaces (:worker-spaces current)) (spaces (:space context)))
                         add (fn [field] (cond-> field
                                           (and (pos? (:count field 0)) (#{:grain :wood :food} (:crop field))) (update :count inc)
                                           (pos? (get-in field [:under-crops :grain] 0)) (update-in [:under-crops :grain] inc)))]
                     (if used? (-> g (update-in [:players pid :farm] #(mapv add %))
                                       (update-in [:players pid :card-fields] #(into {} (map (fn [[k v]] [k (add v)]) %)))) g))
          :spinney (let [actor (:actor context)]
                     (-> g (update-in [:players actor] a/pay {:wood 1}) (update-in [:players pid] a/gain {:wood 1})
                         (update-in [:action-returned-goods (:action-id context) :wood] (fnil inc 0))))
          :shepherds-crook
          (let [old (map set (:old-pastures context)) now (map set (a/pastures current))
                large #(count (filter (fn [pasture] (>= (count pasture) 4)) %))
                qualifying (filter #(and (>= (count %) 4) (not (some #{%} old))
                                         (or (not-any? (fn [prior] (set/subset? % prior)) old)
                                             (> (large now) (large old)))) now)]
            (update-in g [:players pid] a/gain {:sheep (* 2 (count qualifying))}))
          :animal-feed (-> g (assoc :final-animal-feed? true)
                            (update-in [:players pid] a/gain
                                       (into {} (for [animal a/animals :when (pos? (a/amount current animal))] [animal 1]))))
          :return-goods
          (let [returned (if (:action-id context) (get-in g [:action-returned-goods (:action-id context)] {})
                             (get-in g [:returned-goods window pid] {}))]
            (when (:from-taken? effect)
              (a/require! (every? (fn [[r n]] (<= (+ n (get returned r 0)) (get-in context [:taken r] 0))) cost)
                          "Return only goods actually taken from this action space."))
            (-> g (update-in [:players pid] a/gain (expr/quantities g pid context (:goods effect)))
                (update-in [:piles (:space context)] #(merge-with + % cost))
                (cond-> (:from-taken? effect) (update-in [:returned-goods window pid] #(merge-with + % cost)))
                (cond-> (and (:from-taken? effect) (:action-id context)) (update-in [:action-returned-goods (:action-id context)] #(merge-with + % cost)))))
          :choose-good (do (a/require! (some #{(:resource choice)} (:choices effect)) "Choose one of the offered resources.")
                           (cond-> (update-in g [:players pid] a/gain {(:resource choice) 1})
                             (= card :catalog/original-base-e-16-building-material)
                             (queue-front [(event :building-material pid {:source-goods {(:resource choice) 1}})])))
          :seasonal-crop (let [resource (if (>= (:round g) 6) (:resource choice) :grain)]
                           (a/require! (#{:grain :vegetable} resource) "Choose grain or vegetables.")
                           (update-in g [:players pid] a/gain {resource 1}))
          :harpoon (update-in g [:players pid] a/gain {:food (:family current) :reed 1})
          :extra-animal (update-in g [:players pid] a/gain {(:space context) 1})
          :roof-stone (update-in g [:players pid] a/gain {:stone (count (a/tiles current :room))})
          :consultant (update-in g [:players pid] a/gain (nth [{:grain 2} {:clay 3} {:reed 2} {:sheep 2}] (min 3 (dec (count (:players g))))))
          :house-steward (update-in g [:players pid] a/gain {:wood (count (filter #(<= % (- 14 (:round g))) [1 3 6 9]))})
          :tutor-start (-> g
                           (assoc-in [:players pid :card-state card :occupations-before]
                                     (c/occupation-count current (when (= "Original" (:edition (c/card card))) :original-tutor)))
                           (assoc-in [:players pid :card-state card :occupations-after] 0))
          :stock (assoc-in g [:players pid :card-state card :stock] (:goods effect))
          :take-stock (let [stock (get-in current [:card-state card :stock])]
                        (a/require! (seq stock) "No goods remain on this card.")
                        (-> g (update-in [:players pid] a/gain {(peek stock) 1})
                            (update-in [:players pid :card-state card :stock] pop)))
          :reed-hut-worker (if (pos? (a/unused-person-tokens current))
                             (cond-> (-> g (assoc-in [:players pid :reed-hut?] true)
                                           (assoc-in [:players pid :reed-hut-placed?] false))
                               (not (a/holiday-house? current (:round g))) (update-in [:players pid :remaining] inc)) g)
          :replace-minor-hand (let [drawn (vec (take 7 (:minor-deck g)))
                                    available (get g :minor-deck-count (count (:minor-deck g)))]
                                (-> g (assoc-in [:players pid :hand :minor] drawn)
                                    (update :minor-deck #(vec (drop 7 %)))
                                    (assoc :minor-deck-count (max 0 (- available 7)))))
          :guest-next-round (update-in g [:players pid :guest-schedule (inc (:round g))] (fnil inc 0))
          :skip-next-harvest (assoc-in g [:players pid :skip-next-harvest?] true)
          :manure-harvest (queue-front g [(event :before-field-phase pid {:manure? true})
                                         (task pid card {:op :manure-field-harvest} {:manure? true})])
          :manure-field-harvest (manure-harvest g pid)
          :set-uses (assoc-in g [:players pid :card-state card :uses] (:uses effect))
          :conditional (if (condition? g pid (:condition effect) context)
                         (run-effect g (task pid card (:effect effect) context) choice) g)
          :loom (update-in g [:players pid] a/gain {:food (count (filter #(>= (a/amount current :sheep) %) [1 4 7]))})
          :dairy (update-in g [:players pid] a/gain {:food (+ (quot (a/amount current :sheep) 3) (quot (a/amount current :cattle) 2))})
          :plow-many
          (let [cells (:cells choice)]
            (a/require! (and (sequential? cells) (<= (:min-fields effect 1) (count cells) (:max-fields effect))) "Choose one or two fields to plow.")
            (when (:limited? effect) (a/require! (pos? (get-in g [:players pid :card-state card :uses] 0)) "This card has no uses left."))
            (cond-> (update-in g [:players pid] #(reduce (fn [player cell] (a/place-tile player :field cell)) % cells))
              (:limited? effect) (update-in [:players pid :card-state card :uses] dec)))
          :wheel-plow (resolve-action-plow g head choice current)
          :plow (do (when (:only-cells effect) (a/require! (some #{(:cell choice)} (:only-cells effect)) "Choose an unused space on the eastern edge of your farm."))
                    (when (:limited? effect) (a/require! (pos? (get-in g [:players pid :card-state card :uses] 0)) "This card has no uses left."))
                    (cond-> (update-in g [:players pid] a/place-tile :field (:cell choice))
                      (:limited? effect) (update-in [:players pid :card-state card :uses] dec)))
          :stable (do (when (:single-pasture? effect)
                        (a/require! (some #(and (= 1 (count %)) (= (:cell choice) (first %))) (a/pastures current))
                                    "Choose a pasture covering exactly one farmyard space."))
                      (update-in g [:players pid] a/build-stables [(:cell choice)] 0))
          :renovate (-> g (a/renovate-game pid choice {:free? (:free? effect)})
                        (queue-front [(event :renovate pid {:previous-house (:house current)}) (event :check-state pid)]))
          :give-to-actor (let [actor (:actor context)]
                           (a/require! (and (integer? actor) (<= 0 actor) (< actor (count (:players g))) (not= pid actor))
                                       "This effect needs another player's action.")
                           (update-in g [:players actor] a/gain (expr/quantities g pid context (:goods effect))))
          :mini-pasture (update-in g [:players pid] a/build-mini-pasture (:cell choice) (:fences choice))
          :bake (do (a/require! (and (integer? (:grain choice)) (pos? (:grain choice))) "Bake at least one grain, or skip this effect.")
                    (-> g (update-in [:players pid] a/bake (:grain choice) (:cook-with-bake choice) (merge (select-keys g [:phase :round]) (select-keys choice [:use-beehive?])))
                        (queue-front [(event :bake pid {:grain-baked (:grain choice) :bake-source-card card
                                                      :bake-responders (cond-> (set (:bake-responders context)) (:reactive-bake? effect) (conj pid))})])))
          :bake-one
          (let [improvement (:improvement choice) rate (:bake (a/improvement-definition improvement))]
            (a/require! (and (some #{improvement} (a/baking-improvement-ids current)) (pos? (or rate 0))) "Choose one of your baking improvements.")
            (a/require! (= 1 (get choice :grain 1)) "This effect converts exactly one grain.")
            ;; Winnowing Fan uses a baking rate, but grants no Bake Bread action.
            (update-in g [:players pid] #(-> % (a/pay {:grain 1}) (a/gain {:food (+ rate (a/vegetarian-food current {:grain 1}))}))))
          :take-field-crop
          (let [cell (:cell choice) path (a/field-path cell) field (get-in current path) crop (:crop effect)
                exchange (:exchange effect)]
            (a/require! (and (= :field (:type field)) (pos? (a/field-crop-count field crop))) "Choose a planted field with the required crop.")
            (a/require! (or (not (:exchange? choice)) exchange) "This card does not offer an exchange.")
            ;; Taking or moving a crop is not harvesting it, even when the field empties.
            (cond-> (-> g (update-in [:players pid] a/remove-field-crop cell crop)
                         (update-in [:players pid] a/gain {crop 1}))
              (:exchange? choice) (update-in [:players pid] #(-> % (a/pay {crop 1}) (a/gain {:food (+ (:food exchange) (a/vegetarian-food current {crop 1}))})))
              (:exchange? choice) (update-in [:players pid :bonus-points] (fnil + 0) (:points exchange))))
          :adopt (do (a/require! (and (= :work (:phase g)) (pos? (:newborn current 0))) "Adopt a newborn during the work phase.")
                     (-> g (update-in [:players pid :remaining] inc) (update-in [:players pid :newborn] dec) (adopt-newborn pid)))
          :pay-or-beg (let [owed (:food effect) available (min owed (a/amount current :food))]
                        (-> g (update-in [:players pid] a/pay {:food available})
                              (update-in [:players pid :begging] + (- owed available))))
          :add-field-crops (reduce (fn [state cell]
                                    (let [path (a/field-path cell) field (get-in current path)]
                                      (if (= 1 (count (filter (comp pos? val) (a/field-crops field))))
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
            (queue-front (reduce (fn [g i] (-> g (update-in [:players pid] a/remove-field-crop i :grain)
                                                (update-in [:players pid] a/gain {:grain 1}))) g chosen)
                         (harvested-crop-events current chosen)))
          :silage
          (let [source (:grain-source choice) animal (:animal choice)]
            (a/require! (some #{source} (silage-grain-sources current)) "Choose one grain from your supply or a grain field.")
            (a/require! (and ((set a/animals) animal) (>= (a/amount current animal) 2)) "Choose one species with at least two animals.")
            (let [paid (if (= source :supply) (a/pay current {:grain 1})
                           (a/remove-field-crop current source :grain))
                  next (a/gain paid {animal 1})]
              (a/require! (a/animal-housing next) "You must be able to accommodate this offspring.")
              (-> g (assoc-in [:players pid] next) (queue-front [(event :check-state pid)]))))
          :breed-boar (let [next (a/gain current {:boar 1})]
                        (if (and (>= (a/amount current :boar) 2) (a/animal-housing next)) (assoc-in g [:players pid] next) g))
          :housing (let [keep (:keep choice) _ (a/quantities! keep a/animals)
                         _ (a/require! (= (set a/animals) (set (keys keep))) "Choose how many of each species to keep.")
                         surplus (into {} (for [animal a/animals] [animal (- (a/amount current animal) (keep animal))]))
                         _ (a/quantities! surplus a/animals)
                         _ (a/require! (or (harvest-participant? g pid) (not (:cook? choice)) (every? zero? (vals surplus)))
                                       "Layabout may release surplus animals but cannot cook during the skipped harvest.")
                         _ (a/require! (not (and (= :scoring (:phase g)) (:cook? choice))) "Final animals cannot be converted to food.")
                         next (if (:cook? choice) (a/convert-food current surplus (select-keys g [:phase :round])) (a/pay current surplus))]
                     (a/require! (a/animal-housing next) "Those animals do not fit on your farm.")
                     (when (and (:reclamation? context) (false? (:accommodate-taken? choice)))
                       (a/require! (some (fn [[animal n]] (and (pos? n) (pos? (get surplus animal 0)))) (:taken context))
                                   "To decline accommodation, cook or release at least one animal of a newly taken species."))
                     (cond-> (assoc-in g [:players pid] next)
                       (:reclamation? context) (assoc-in [:players pid :card-state (c/id :a017) :declined-pile?]
                                                        (false? (:accommodate-taken? choice)))))
          :cottage (do (a/require! (#{:room :renovate} (:action choice)) "Choose building a room or renovating.")
                    (if (= :room (:action choice))
                     (update-in g [:players pid] a/build-rooms [(:cell choice)] choice)
                     (queue-front (a/renovate-game g pid choice) [(event :renovate pid {:previous-house (:house current)}) (event :check-state pid)])))
          :scholar (if (:improvement choice)
                     (granted-card-action g pid {:kinds #{:minor}} choice)
                     (play-card g pid (:card choice) 1 choice {:event (:trigger-event context) :window window}))
          :boat-painter (do (a/require! (#{:grain :food} (:resource choice)) "Choose grain or food.")
                            (update-in g [:players pid] a/gain {(:resource choice) (if (= :food (:resource choice)) 2 1)}))
          :clay-thief (-> (five/collect-pile g pid :hollow :clay) (assoc-in [:players pid :card-state card :face-down?] true))
          (:corral-builder :amateur-fencer) (run-effect g (task pid card {:op :mini-pasture} context) choice)
          :wild-boar-hunter (update-in g [:players pid] a/gain {:boar 1})
          :trapper (update-in g [:players pid] a/gain {(nth [:sheep :boar :cattle] (- (five/occupied-wood g) 2)) 1})
          :plowsmith (run-effect g (task pid card {:op :plow} context) choice)
          :fast-mason (-> g (update-in [:players pid :goods :reed] inc)
                          (a/renovate-game pid (dissoc choice :brushwood? :renovate-to))
                          (queue-front [(event :renovate pid {:previous-house (:house current)}) (event :check-state pid)]))
          :countryman (do (a/require! (= 1 (count (:plantings choice))) "Sow exactly one field.")
                           (update-in g [:players pid] a/sow (:plantings choice) (select-keys choice [:smallholder?])))
          :young-artist
          (if (= :draw (:action choice))
            (let [drawn (vec (take 2 (:minor-deck g)))]
              (a/require! (pos? (get g :minor-deck-count (count drawn))) "No minor improvements remain in the deck.")
              (-> g (update-in [:players pid :hand :minor] into drawn)
                    (update :minor-deck #(vec (drop 2 %))) (update :minor-deck-count #(max 0 (- % 2)))))
            (granted-card-action g pid {:kinds #{:minor}} choice))
          :tag-along
          (let [owners (get-in g [:occupied :market]) owners (if (vector? owners) owners [owners])
                prepared (prepare-worker-placement g pid)
                after (-> prepared (update-in [:players pid :remaining] dec)
                          (update-in [:players pid] a/gain (:gain (a/action-definition g :market)))
                          (assoc-in [:occupied :market] (conj owners pid)))]
            (queue-front (record-placement prepared after pid :market {:space :market})
                         [(event :use-space pid {:space :market :source-goods (source-action-goods g {:space :market})}) (step :check-all)]))
          :sidekick
          (let [space (five/stage-left g (:space context)) cmd (assoc (:action-command choice) :type :action :space space)
                turn (:turn g) queue (:effect-queue g)
                after (take-action (prepare-worker-placement (assoc g :turn pid :effect-queue []) pid) pid cmd)
                after (cond-> after deferred-sidekick?
                        (update-in [:effect-queue 0 :effect] assoc :skip-advance? true :placement-cost cost))]
            ;; Preserve the parent's one turn-advance continuation, after this action's effects.
            (assoc after :turn turn :effect-queue
                   (vec (concat (remove #(and (= :continue (:kind %)) (= :advance (:step %))) (:effect-queue after)) queue))))
          (cond (= :fr-turkey-debt op) (fr/turkey-debt g pid)
                (wm-fifteen-ops op) (run-wm-fifteen-effect g head choice)
                (g-fifteen-ops op) (run-g-fifteen-effect g head choice)
                (community-fourteen-ops op) (run-community-fourteen-effect g head choice)
                (community-thirteen-ops op) (run-community-thirteen-effect g head choice)
                :else (five/automatic g pid card op context)))))))

(defn run-effect [g {:keys [player effect context] :as head} choice]
  (a/require! (or (:skip? choice) (and (five/eligible? g player (:op effect) context) (expr/evaluate g player context (get effect :available-when true)))) "This card effect is not available now.")
  (let [before (count (a/tiles (p g player) :field)) after (reactive/note-effect g (run-effect* g head choice) head)
        after (if (or (:source-command effect) (#{:extra-action :sidekick :flush-action-receipts :resource-seller-receipt :reposition-worker} (:op effect))) after
                  (note-gain-receipts g after (:kind (c/card (:card head)))))
        plowed (- (count (a/tiles (p after player) :field)) before)
        fenced? (not= (:fences (p g player)) (:fences (p after player)))
        stables (- (count (filter :stable (:farm (p after player)))) (count (filter :stable (:farm (p g player)))))
        rooms (- (count (a/tiles (p after player) :room)) (count (a/tiles (p g player) :room)))
        new-rooms (vec (remove (set (a/tiles (p g player) :room)) (a/tiles (p after player) :room)))
        room-context (cond-> {:rooms-built rooms :rooms-before (count (a/tiles (p g player) :room))
                      :family-before (:family (p g player)) :previous-house (:house (p g player))}
                       (c/has? (p g player) :catalog/original-expansion-wm-60-attic) (assoc :new-rooms new-rooms)
                       (c/has? (p g player) :catalog/original-expansion-fr-42-rock-pyramid)
                       (assoc :paid-stone-rooms (if (= (set new-rooms) (set (get-in after [:players player :last-room-payment :cells])))
                                                 (get-in after [:players player :last-room-payment :stone-rooms] 0) 0)))]
    (cond-> after
      (and (:sow-after? effect) (pos? plowed))
      (queue-front [(task player (:card head) {:op :sow-offer :only-cells [(:cell choice)] :allowed-crops #{:grain} :max-fields 1 :optional? true} {})])
      (and (not (:source-command effect)) (not (#{:sidekick :extra-action} (:op effect))) (pos? rooms))
      (queue-front (concat (when (>= rooms 2) [(event :use-farm-spaces player {:used-spaces rooms :action :room})])
                           [(event :build-rooms player room-context) (event :check-state player)]))
      (and (or (not (:source-command effect)) (= :build-stables (:op effect))) (not (#{:sidekick :extra-action} (:op effect))) (pos? stables) (= :work (:phase g)))
      (queue-front [(event :build-stables player {:stables-built stables})])
      (and (or (not (:source-command effect)) (= :build-stables (:op effect))) (not (#{:sidekick :extra-action} (:op effect))) (pos? stables))
      (queue-front [(event :stables-created player {:stables-built stables :new-stables (vec (filter #(and (get-in (p after player) [:farm % :stable]) (not (get-in (p g player) [:farm % :stable]))) (range 15)))})])
      (and (= :build-stables (:op effect))
           (>= (count (filter #(and (:stable (get-in (p after player) [:farm %]))
                                   (a/empty-cell? (p g player) %)) (range 15))) 2))
      (queue-front [(event :use-farm-spaces player {:used-spaces stables :action :stable})])
      (and (= :build-fences (:op effect))
           (>= (count (filter #(a/empty-cell? (p g player) %) (a/pasture-cells (p after player)))) 2))
      (queue-front [(event :use-farm-spaces player {:used-spaces (count (filter #(a/empty-cell? (p g player) %) (a/pasture-cells (p after player)))) :action :pasture})])
      (and (= :plow-many (:op effect)) (>= plowed 2))
      (queue-front [(event :use-farm-spaces player {:used-spaces plowed :action :field})])
      (and (not (:source-command effect)) (pos? plowed) (not (#{:plowsmith :corral-builder :amateur-fencer :sidekick :extra-action} (:op effect))))
      (queue-front [(event :plow player {:fields-plowed plowed})])
      (and (not (:source-command effect)) fenced? (#{:mini-pasture :build-fences :fence-overseer} (:op effect)))
      (queue-front [(event :fence player {:old-pastures (a/pastures (p g player)) :fence-overseer? (= :fence-overseer (:op effect)) :fences-built (- (count (:fences (p after player))) (count (:fences (p g player))))})]))))

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
(defn room-plans [player]
  ;; Two-room plans matter for batch discounts: a player can afford two rooms
  ;; while lacking the reed required for a single room.
  (let [cells (filter #(a/empty-cell? player %) (range 15))
        attached? (fn [rooms cell] (some (set rooms) (a/neighbors cell)))
        rooms (a/tiles player :room)]
    (concat (for [cell cells :when (attached? rooms cell)] [cell])
            (for [one cells :when (attached? rooms one) two cells
                  :when (and (not= one two) (attached? (conj (vec rooms) one) two))]
              [one two]))))
(defn major-action-choices [g pid]
  ;; Legality probes must include upgrades and discounts, without baking or
  ;; committing a card. Hand improvements marked major-or-minor also qualify.
  (let [player (p g pid)]
    (concat
      (for [id (:available-improvements g)
            upgrade (if (:upgrade (a/improvements id)) (cons nil (a/fireplace-upgrades player)) [nil])
            discount (or (seq (a/improvement-discount-resources player id)) [nil])
            payment (a/improvement-payment-options player id)]
        (cond-> (assoc payment :improvement id) upgrade (assoc :upgrade upgrade)
                discount (assoc :discount-resource discount)))
      (for [id (get-in player [:hand :minor]) :when (get-in (c/card id) [:passive :major-or-minor])
            discount (or (seq (a/improvement-discount-resources player id)) [nil])
            payment (a/improvement-payment-options player id)
            option (if-let [costs (:cost-options (c/card id))] (range (count costs)) [nil])]
        (cond-> (assoc payment :card id :as-major? true) discount (assoc :discount-resource discount)
                (some? option) (assoc :payment-option option))))))
(defn possible-effect? [g {:keys [player effect] :as head}]
  (let [op (:op effect) cost (expr/quantities g player (:context head) (:cost effect))
        cost (if (and (= :card-action op) (some (:kinds effect) [:minor :occupation]))
               (a/discounted cost {:food (hand-food-discount (p g player))}) cost)
        cost (if (and (= :card-action op) (= #{:occupation} (:kinds effect))) (update cost :food #(max 0 (- (or % 0) (headmaster-food (p g player))))) cost)
        conversion (when (optional-effect? g head) (funding-conversion g player cost))
        probe (if (seq conversion) (update-in g [:players player] a/convert-food conversion) g)
        choices (cond
                  (wm-fifteen-ops op) (wm-fifteen-choices g player (:card head) effect (:context head))
                  (g-fifteen-ops op) (g-fifteen-choices g player (:card head) op (:context head))
                  (= :fish-seller op) (map #(hash-map :goods [%]) (keys fish-seller-prices))
                  (= :bartering-hut op) (for [one building-resources two building-resources] {:animals [:sheep] :payment (frequencies [one two])})
                  (#{:bee-keeper :storage-shed} op) (map #(hash-map :cell %) (range 15))
                  (= :hunter op) (for [[r n] (get-in head [:context :goods]) :when (pos? n)] {:goods {r 1}})
                  (= :stash-card op) (map #(hash-map :card %) (mapcat val (:hand (p g player))))
                  (= :farm-markers op) (map #(hash-map :cells [%]) (range 15))
                  (= :wine-delivery op) [{:points 0}]
                  (= :boatswain op) (map #(hash-map :cells [%]) (boatswain-cells g player))
                  (= :sailboat-constructor op) (map #(hash-map :stacks [%]) (sailboat-available g player (:card head)))
                  (= :stroller op) [{:resources [:wood :clay :reed :stone :wood :clay]}]
                  (= :reed-nursery-points op) (concat [{:amount 1}] (for [cell (concat (a/tiles (p g player) :field) (keys (:card-fields (p g player))))] {:fields {cell 1}}))
                  (and (= :build-fences op) (:paddocks? effect)) (map #(hash-map :fences %) (paddocks-plans (p g player)))
                  (and (= :card-action op) (= #{:major} (:kinds effect)) (not (optional-effect? g head))) (major-action-choices probe player)
                  (= :relocate op) (map #(hash-map :option %) (range (count (relocation-origins g player))))
                  (= :adopt-original op) [{:amount 1}]
                  (= :wet-nurse op) [{:amount 1} {:amount 1 :adopt-reed-hut? true}]
                  (= :pieceworker op) (map #(hash-map :purchase %) (pieceworker-choices (:context head)))
                  (= :basin-maker op) [{:amount 1}]
                  (= :tanner op) [{:animals {:boar 1}} {:animals {:cattle 1}}]
                  (#{:wood-distributor :take-stock} op) [{}]
                  (= :foreman op) (map #(hash-map :space %) (fence-marker-spaces g player))
                  (= :animal-breeder op) (when (animal-breeder-available? g player (:context head)) (map #(hash-map :animal %) a/animals))
                  (= :animal-trainer op) (map #(hash-map :animals {% 1}) a/animals)
                  (= :fence-overseer op) (map #(hash-map :cell %) (fence-overseer-cells g player (:context head)))
                  (= :mark-fence-space op) (map #(hash-map :space %) (fence-marker-spaces g player))
                  (#{:buy-action-resource :net-fisherman :taster :animal-dealer} op) [{}]
                  (= :harvest-helper op) (harvest-helper-options g player)
                  (= :corn-offer op) (when (>= (a/amount (p g player) :food) 2) [{}])
                  (= :juggler op) [{}]
                  (= :pelts op) [{:amount 1}]
                  (#{:plow :mini-pasture :stable :remove-empty-field} op) (map #(hash-map :cell %) (range 15))
                  (= :plow-many op) (if (= 2 (:min-fields effect)) (for [x (range 15) y (range 15) :when (not= x y)] {:cells [x y]}) (map #(hash-map :cells [%]) (range 15)))
                  (= :wheel-plow op) (cons {:skip? true} (map #(hash-map :cells [%]) (range 15)))
                  (#{:loppers :return-goods :lasso-followup} op) [{}]
                  (= :freshman-bake op)
                  (concat (map #(hash-map :card %) (get-in g [:players player :hand :occupation]))
                          [{:grain 1}] (when (:source-command effect) [{:skip? true}]))
                  (= :silage op) (silage-choices (p g player))
                  (= :bake op) [{:grain 1}]
                  (#{:grow :temporary-person} op) [{}]
                  (= :reserve-stables op) (map #(hash-map :rounds [%]) (planner-rounds g effect))
                  (= :build-stables op) (map #(hash-map :stables [%]) (range 15))
                  (= :build-fences op) (for [cell (range 15) material [:wood :clay]]
                                        {:fences (vec (remove (:fences (p g player)) (a/edges cell))) :fence-material material})
                  (= :build-rooms op) (for [rooms (room-plans (p g player)) options (a/room-payment-plans (p g player) rooms)]
                                       (assoc options :rooms rooms))
                  (= :take-pile-good op) (pile-good-options g effect)
                  (= :renovate op) [{} {:renovate-to :stone}]
                  (= :bake-one op) (map #(hash-map :improvement %) (a/baking-improvement-ids (p g player)))
                  (= :pearl-seeker op) [{:amount 1}]
                  (= :award-winner op) (map #(hash-map :resource %) (award-winner-resources g player (:card head) (:context head)))
                  (= :schedule-resource-order op) [{:resources [:wood :clay :reed :stone]}]
                  (= :harvest-field op) (map #(hash-map :cell %) (harvestable-field-cells g player))
                  (= :apple-tree op) (map #(hash-map :cell %) (apple-tree-cells g player))
                  (= :seasonal-plants op) (map #(select-keys % [:cell]) (seasonal-plantings g player (:context head)))
                  (= :return-stables op) (map #(hash-map :stables [%]) (keep-indexed (fn [i cell] (when (:stable cell) i)) (:farm (p g player))))
                  (= :take-wild-breeding op) (map #(hash-map :space %) (get-in head [:context :added-spaces]))
                  (= :discard-minors op) (map #(hash-map :cards [%]) (get-in g [:players player :hand :minor]))
                  (= :choose-score-category op) [{:category :grain}]
                  (= :cabbage-lover op) [{:amount 1}]
                  (= :livestock-keeper op) (map #(hash-map :animal %) a/animals)
                  (= :room-resource op) (map #(hash-map :cells [%]) (expr/evaluate g player (:context head) (:cells effect)))
                  (= :wheelbarrow-pusher op) (map #(hash-map :resource %) (wheelbarrow-choices g))
                  (#{:pine-forest :gem-hunter} op) [{}]
                  (= :room-food op) (map #(hash-map :cells [%]) (a/tiles (p g player) :room))
                  (= :take-field-crops op) (for [cell (concat (a/tiles (p g player) :field) (keys (:card-fields (p g player)))) crop [:grain :vegetable]] {:plantings [{:cell cell :crop crop}]})
                  (= :take-field-crop op) (map #(hash-map :cell %) (concat (a/tiles (p g player) :field) (keys (:card-fields (p g player)))))
                  :else nil)]
    (and (five/eligible? g player op (:context head))
         (expr/evaluate g player (:context head) (get effect :available-when true))
         (or (not= :adopt op) (and (= :work (:phase g)) (pos? (:newborn (p g player) 0))))
         (or (not (optional-effect? g head)) (some? conversion))
         (or (not= :extra-action op) (seq (extra-action-spaces g player effect (:context head))))
         (or (not= :threshold-exchange op) (>= (a/amount (p g player) (:resource effect)) (:minimum effect)))
         (or (not= :sow-offer op) (:source-command effect)
             (and (:committed-source effect) (owned? g player :a094))
             (sowing-potential? g player effect))
         (or (nil? choices)
             (some (fn [choice]
                     (let [extra-cost (case op :pieceworker {:food (reduce + 0 (map #(if (= :vegetable %) 2 1) (keys (:purchase choice))))}
                                              :wet-nurse {:food 1}
                                              :animal-breeder {:food ({:sheep 1 :boar 2 :cattle 3} (:animal choice))} {})
                           conversion (funding-conversion probe player extra-cost)
                           probe (if (seq conversion) (update-in probe [:players player] a/convert-food conversion) probe)]
                       (try (run-effect probe head choice) true
                            (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false)))) choices)))))
(defn with-housing [g]
  (if (:final-animal-feed? g) g
  (let [g (five/refresh g) already (set (keep #(when (#{:housing :hunter} (get-in % [:effect :op])) (:player %)) (:effect-queue g)))]
    (queue-front g (remove #(already (:player %)) (housing-tasks g))))))
(defn free-room-available? [player cid]
  (let [rule (:ability (c/card cid))]
    (and (not (get-in player [:card-state cid :room-used?]))
         (>= (count (a/tiles player :room)) (:minimum-rooms rule))
         (or (nil? (:house rule)) (= (:house rule) (:house player)))
         (boolean (some (fn [cell]
                          (and (a/empty-cell? player cell)
                               (some #{cell} (mapcat a/neighbors (a/tiles player :room))))) (range 15))))))
(defn claim-available? [g player cid]
  (and g (not (get-in player [:card-state cid :claimed?]))
       (condition? g (:id player) (get-in (c/card cid) [:ability :condition]) {})))
(defn trowel-boundary? [g]
  (and (nil? (:action-context g)) (nil? (:sow-in-progress g)) (nil? (:freshman-plan g))
       (every? #(or (= :ability-window (get-in % [:effect :op]))
                    (and (= :continue (:kind %)) (#{:finish-round :next-round} (:step %)))) (:effect-queue g))))
(defn exchange-remaining [g player cid]
  (when-let [limit (get-in (c/card cid) [:ability :limit-per-round])]
    (max 0 (- limit (if (= (:round g) (get-in player [:card-state cid :exchange-round]))
                      (get-in player [:card-state cid :exchange-used] 0) 0)))))
(defn stored-goods-collectable? [player cid]
  (>= (reduce + 0 (vals (get-in player [:card-state cid :stored-goods])))
      (get-in (c/card cid) [:ability :minimum-stored] 1)))
(defn exchange-phase-available? [g player cid]
  (let [ability (:ability (c/card cid)) phases (:phases ability)]
    (and (condition? g (:id player) (:condition ability) {})
         (or (nil? phases) (contains? phases (:phase g)))
         (not (contains? (:exclude-phases ability #{}) (:phase g)))
         (not (and (:exclude-final-breeding? ability) (= 14 (:round g)) (= :finish-round (:phase g)))))))
(defn anytime-available?
  ([player cid] (anytime-available? nil player cid))
  ([g player cid]
  (case (get-in (c/card cid) [:ability :op])
    :wood-saw (and g (fr/wood-saw-available? g (:id player)) (possible-effect? g (task (:id player) cid {:op :build-rooms} {})))
    :workaholic (seq (fr/workaholic-options player cid))
    :g-pilgrim-discard (some #(pos? (a/amount player %)) a/resources)
    :g-animal-farm (g-animal-farm-available? g player)
    :tinsmith (pos? (a/amount player :clay))
    :corn-profiteer (and (pos? (a/amount player :grain)) (not (:corn-sale g)))
    :return-animal-loan (and (not (get-in player [:card-state cid :loan-settled?]))
                              (some #(pos? (a/amount player %)) (loan-resources cid)))
    :claim-goods (claim-available? g player cid)
    :renovate-clay-stone
    (and (= :clay (:house player))
         (boolean (some (fn [options] (try (a/renovate player (assoc options :renovate-to :stone)) true
                                           (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false)))
                        (a/construction-payment-options player))))
    :renovate-wood-clay
    (and (= :wood (:house player)) (or (nil? g) (trowel-boundary? g))
         (boolean (some (fn [options] (try (a/renovate player (assoc options :renovate-to :clay)) true
                                           (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false)))
                        (a/construction-payment-options player))))
    :free-room (free-room-available? player cid)
    :borrower-repay (and (>= (a/amount player :food) 3) (pos? (eligible-begging player true)))
    :minder-collect (and (seq (minder-resources player cid)) (not= (:round g) (get-in player [:card-state cid :collected-round])))
    :pawn-improvement (seq (pawnable-improvements player))
    :light-bonfire (some #(= cid (:card %)) (:farm player))
    :wine-closet (and (< (:round g) 14) (pos? (a/amount player :vegetable)) (not= (:round g) (get-in player [:card-state cid :used-round])))
    :backyard-well (and (not (get-in player [:card-state cid :used?])) (seq (backyard-well-cells g (:id player))))
    :benefactor (benefactor-available? g (:id player) cid)
    :exchange-options (and (exchange-phase-available? g player cid) (pos? (or (exchange-remaining g player cid) 1))
                           (boolean (some #(and (expr/evaluate g (:id player) {} (get % :when true)) (can-pay? player (:cost %))) (get-in (c/card cid) [:ability :options]))))
    :sheep-exchange (pos? (a/amount player :sheep))
    :porcelain (>= (a/amount player :clay) 2)
    :buy-stock (and (seq (get-in player [:card-state cid :stock]))
                    (or (pos? (a/amount player :food))
                        (some (fn [[resource rate]] (and (pos? rate) (pos? (a/amount player resource)))) (a/cook-rates player))))
    :reed-food (pos? (a/amount player :reed))
    :cash-card (pos? (get-in player [:card-state cid :food] 0))
    :release-stored (stored-goods-collectable? player cid)
    :free-placement-fences (and (pos? (a/fences-available player)) (pos? (a/amount player :wood)))
    :move-crop (and (some #(>= (:count % 0) 2) (fields player)) (some #(zero? (:count % 0)) (fields player)))
    false)))
(defn anytime-tasks [g]
  (for [offset (range (count (:players g)))
        :let [pid (mod (+ (:starting-player g) offset) (count (:players g))) player (p g pid)]
        :when (some #(anytime-available? g player %) (c/active-owned player))]
    (task pid nil {:op :ability-window} {})))
(defn before-work-tasks [g]
  ;; Opt-in timing window: stone-producing anytime abilities (e.g. Politician)
  ;; may fund Powerhouse after this round's deliveries, before its bonus is checked.
  (for [offset (range (count (:players g)))
        :let [pid (mod (+ (:starting-player g) offset) (count (:players g))) player (p g pid)]
        cid (c/active-owned player)
        :let [condition (get-in (c/card cid) [:passive :before-work-abilities])]
        :when (and condition (expr/evaluate g pid {} condition)
                   (some #(anytime-available? g player %) (c/active-owned player)))]
    (task pid cid {:op :ability-window :timing :before-work} {})))
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
(defn work-baselines [g]
  (if-not (game-has-cards? g #{:catalog/original-expansion-fr-97-parquet-setter}) g
    (update g :players #(mapv (fn [p] (assoc p :work-unused-baseline (unused-count p))) %))))
(defn prepare-return-home [g]
  (reduce
   (fn [state pid]
     (let [player (p state pid) temporary (:temporary-family player 0)
           player (cond-> (-> player (update :family - temporary) (dissoc :temporary-family :reed-hut-acting?))
                    (:reed-hut-discard? player) (dissoc :reed-hut? :reed-hut-placed? :reed-hut-discard?))
           expire? (and (= 9 (:round state)) (get-in player [:card-state (c/id :a127) :room-active?]))
           player (cond-> player (c/has? player fr/cowboy-mother) (assoc :cowboy-mother-actions (count (:worker-spaces player))) expire? (assoc-in [:card-state (c/id :a127) :room-active?] false))
           state (assoc-in state [:players pid] player)]
       (if (and expire? (> (:family player) (a/family-capacity player)))
         (if (and (pos? (:newborn player 0)) (> (:family player) (:newborn player 0)))
           (queue-front state [(task pid (c/id :a127) {:op :lodger-remove} {})])
           (update-in state [:players pid] remove-lodger-person (if (pos? (:newborn player 0)) :newborn :adult))) state)))
   g (reverse (range (count (:players g))))))
(defn apply-ratcatcher [g]
  (if (and (= :work (:phase g)) (#{10 12} (:round g)))
    (let [owners (set (for [player (:players g) :when (some true? (c/passives player :ratcatcher))] (:id player)))]
      (update g :players
              #(mapv (fn [player]
                       (if (and (seq (disj owners (:id player)))
                                (not= (:round g) (:ratcatcher-blocked-round player))
                                (> (- (:family player) (:newborn player 0) (:temporary-family player 0)) 2)
                                (pos? (:remaining player)))
                         (-> player (update :remaining dec) (assoc :ratcatcher-blocked-round (:round g))) player)) %))) g))
(defn feeding-order [g]
  (vec (sort-by #(boolean (some true? (c/passives (p g %) :feed-last)))
                (map #(mod (+ (:starting-player g) %) (count (:players g))) (range (count (:players g)))))))
(defn crop-harvest [g pids context]
  (let [players (mapv #(p g %) pids)
        after (reduce #(update-in %1 [:players %2] a/field-harvest (assoc context :harvest? true)) g pids)
        after (note-gain-receipts g after :harvest)
        after (if (and (= 14 (:round g)) (not (:manure? context)) (game-has-cards? g #{:catalog/original-expansion-fr-85-harvester}))
                (reduce (fn [state player]
                          (assoc-in state [:players (:id player) :final-harvest-goods]
                                    (reduce + 0 (for [r [:wood :clay :reed :stone :grain :vegetable]]
                                                  (- (get-in after [:players (:id player) :received r] 0) (get-in player [:received r] 0)))))) after players) after)
        crop-events (mapcat #(harvested-crop-events % (concat (a/tiles % :field) (keys (:card-fields %)))) players)
        facts (fn [player]
                (cond-> (merge context (when (c/has? player g-bountiful)
                                         {:g-harvested-goods (reduce + 0 (for [resource [:grain :vegetable :reed :wood]]
                                           (- (get-in after [:players (:id player) :received resource] 0) (get-in player [:received resource] 0))))})
                               {:harvested-grain-fields (crop-fields player :grain)
                                       :harvested-vegetable-fields (crop-fields player :vegetable)})
                  ;; Count actual harvested stacks, including wood and preserved vegetables.
                  ;; Opt in so recordings of existing cards retain identical event contexts.
                  (some true? (c/passives player :observe-harvest-count))
                  (assoc :harvested-goods
                         (count (filter #(and (:crop %) (pos? (:count % 0)))
                                        (concat (:farm player) (vals (:card-fields player))))))))]
    (queue-front after (concat (map #(update % :context merge context) crop-events)
                              (map #(event :harvest-crops (:id %) (facts %)) players)
                              (map #(event :field-phase (:id %) (facts %)) players)
                              (map #(event :after-field-phase (:id %) context) players)))))
(defn manure-harvest [g pid] (crop-harvest g [pid] {:manure? true}))
(defn apply-holiday-houses [g]
  (update g :players #(mapv (fn [player]
    (cond (a/holiday-house? player (:round g)) (assoc player :remaining 0)
          (and (= 14 (:round g)) (some true? (c/passives player :honeymoon)) (not= 14 (:honeymoon-round player)))
          (-> player (update :remaining (fn [n] (max 0 (- n 2)))) (assoc :honeymoon-round 14))
          :else player)) %)))
(defn harvest-members [g] (filterv #(harvest-participant? g %) (range (count (:players g)))))
(defn start-harvest [g]
  (-> g
      (assoc :harvest-skipped (set (for [player (:players g) :when (:skip-next-harvest? player)] (:id player))))
      (update :players #(mapv (fn [player] (cond-> (dissoc player :skip-next-harvest?) (c/has? player g-bountiful) (assoc :g-harvest-count 0))) %))))
(defn continue-step [g name]
  (case name
    :resume-breeding (assoc g :phase :breeding)
    :preparation-reconsider (preparation-reconsider g)
    :finish-relocations (if (some #(pos? (:remaining %)) (:players g)) (advance (dissoc g :relocations))
                           (queue-front (dissoc g :relocations) [(step :return-home)]))
    :advance (advance g)
    :after-harvest (let [g (dissoc g :harvest-skipped)]
                     (queue-front g (concat (anytime-tasks g) [(step :finish-round)])))
    :finish-round (queue-front g (concat (map #(event :round-end (:id %)) (:players g)) [(step :next-round)]))
    :next-round (if (= 14 (:round g)) (finish-game g) (begin-round g))
    :score-final (finish-game g)
    :work (-> g (assoc :phase :work :first-nonaccumulation nil :newborn-spaces {} :newborn-only #{})
              (update :players #(mapv (fn [p] (assoc (a/reset-turn-fence-bonus p) :worker-spaces [] :tracked-newborns 0 :work-baseline (:received p) :used-accumulations {:round (:round g) :by-good {}})) %))
              work-baselines apply-holiday-houses apply-ratcatcher fr/apply-convict apply-taster
              (queue-front (concat (map #(event :work-start (:id %)) (:players g)) [(step :work-ready)])))
    :work-ready (let [g (fr/apply-convict (apply-holiday-houses g)) n (count (:players g))
                      ready (first (filter #(pos? (:remaining (p g %)))
                                           (map #(mod (+ (:turn g) %) n) (range n))))]
                  (if (some? ready) (assoc g :turn ready) (queue-front g [(step :return-home)])))
    :return-home (prepare-return-home (queue-front (assoc g :phase :return-home) (concat (map #(event :return-home (:id %)) (:players g)) [(step :harvest-or-round)])))
    :harvest-or-round (if (a/harvest-rounds (:round g))
                        (let [g (start-harvest g)]
                          (queue-front g (concat (map #(event :before-field-phase %) (harvest-members g)) [(step :field-harvest)])))
                        (queue-front g [(step :finish-round)]))
    :field-harvest (crop-harvest (queue-front (assoc g :phase :field-harvest) [(step :feeding)]) (harvest-members g) {})
    :feeding (let [order (feeding-order g) skipped (:harvest-skipped g #{})
                   active (vec (remove skipped order))
                   g (assoc g :phase :feeding :turn (or (first active) (:starting-player g))
                              :feeding-order order :fed skipped :bred skipped)]
               (if (seq active)
                 (-> g (a/log-event "Harvest · feed each family, then breed animals.")
                     (queue-front (map #(event :feeding-start %) active)))
                 (-> g (assoc :phase :finish-round)
                     (update :players #(mapv (fn [player] (update player :completed-feeding-phases (fnil inc 0))) %))
                     (queue-front (concat (map #(event :harvest-end (:id %)) (:players g)) [(step :after-harvest)])))))
    :check-all (queue-front g (map #(event :check-state (:id %)) (:players g)))
    g))
(defn record-construction [g {:keys [event player context]}]
  ;; Record committed construction events, including nested card actions. This
  ;; survives reloads and both Lasso placements, but never crosses a turn or phase.
  (if (not= :work (:phase g)) g
   (case event
    :build-rooms (update-in g [:turn-construction player :rooms] (fnil conj []) (:previous-house context))
    :build-stables (update-in g [:turn-construction player :stables] (fnil + 0) (:stables-built context 0))
    g)))
(defn tutor-points [player cid]
  (let [n (c/occupation-count player (when (= "Original" (:edition (c/card cid))) :original-tutor))]
    (get-in player [:card-state cid :occupations-after]
            (max 0 (- n (get-in player [:card-state cid :occupations-before] n))))))
(defn record-tutor-occupation [g {:keys [event player context]}]
  ;; Count plays, not cards remaining in front of the player: late Ratcatcher
  ;; leaves play immediately but still earns Tutor's point. Migrate old saves
  ;; from their existing baseline before adding this event's new occupation.
  (if (not (or (= :occupation event) (and (= :build-improvement event) (pos? (get-in (c/card (:card context)) [:passive :counts-as-occupation] 0))))) g
    (let [played (:card context)]
      (reduce
       (fn [state cid]
         (let [owner (p state player)
               amount (if (and (= "Original" (:edition (c/card cid)))
                               (get-in (c/card played) [:passive :academic])) 2 1)
               previous (if (contains? (get-in owner [:card-state cid] {}) :occupations-after)
                          (tutor-points owner cid)
                          (max 0 (- (tutor-points owner cid) (if (some #{played} (c/occupation-cards owner)) amount 0))))]
           (assoc-in state [:players player :card-state cid :occupations-after] (+ previous amount))))
       g (filter #(and (not= played %) (= :tutor (get-in (c/card %) [:score :type])))
                 (c/active-owned (p g player)))))))
(def round-fact-cards #{:catalog/original-expansion-wm-72-crib :catalog/original-expansion-wm-89-home-garden :catalog/original-expansion-g-66-birthstone})
(defn game-has-cards? [g ids]
  (or (some ids (:minor-deck g)) (some ids (:occupation-deck g))
      (some (fn [owner] (some ids (concat (c/owned owner) (mapcat val (:hand owner))))) (:players g))))
(defn record-round-facts [g {:keys [event player context]}]
  (cond-> g
    (and (#{:grow :renovate} event) (game-has-cards? g round-fact-cards))
    (assoc-in [:round-facts player event] (:round g))
    (and (= :grow event) (game-has-cards? g #{:catalog/original-expansion-g-66-birthstone}))
    (update-in [:round-growths player] (fn [old] {:round (:round g) :count (+ (if (= (:round old) (:round g)) (:count old 0) 0)
                                                                                  (:newborns-added context (max 0 (- (:family (p g player)) (:family-before context (:family (p g player)))))))}))
    (and (= :build-improvement event)
         (:oven (a/improvement-definition (or (:improvement context) (:card context))))
         (game-has-cards? g #{:catalog/original-expansion-wm-8-bread-carrier}))
    (assoc :oven-built? true)))
(defn sync-room-food [g]
  (update g :players (fn [players]
    (mapv (fn [player]
      (reduce (fn [p cid]
        (if-let [marks (get-in p [:card-state cid :room-food])]
          (assoc-in p [:card-state cid :room-food] (set (filter (set (a/tiles p :room)) marks))) p))
        (reduce (fn [p cid] (if-let [marks (get-in p [:card-state cid :room-resources])]
                             (assoc-in p [:card-state cid :room-resources] (select-keys marks (a/tiles p :room))) p)) player (c/active-owned player))
        (c/active-owned player))) players))))
(defn drain [g]
  (loop [g g steps 0]
   (let [g (-> g flush-cooking-receipts sync-room-food reactive/sync-farm-cards reactive/sync-shaving reactive/flush-shaving)]
    (a/require! (< steps 1000) "Card effects did not settle.")
    (if-let [head (first (:effect-queue g))]
      (let [rest-state (update g :effect-queue #(vec (rest %)))]
        (case (:kind head)
          :event (let [g (cond-> (-> g (record-construction head) (record-tutor-occupation head) (record-round-facts head))
                           (and (= :build-improvement (:event head)) (= :well (or (get-in head [:context :improvement]) (get-in head [:context :card])))) (update :well-build-count (fnil inc 0)))
                       rest-state (cond-> (-> rest-state (record-construction head) (record-tutor-occupation head) (record-round-facts head))
                                    (and (= :build-improvement (:event head)) (= :well (or (get-in head [:context :improvement]) (get-in head [:context :card])))) (update :well-build-count (fnil inc 0)))
                       window (or (get-in head [:context :existing-window]) (inc (get g :effect-window 0)))
                       tasks (trigger-tasks g head)
                       ;; Taking goods happens before optional exchanges that say
                       ;; "immediately after" using the space.
                       tasks (sort-by #(cond (and (= :round-end (:event head)) (#{:fiddler :manual-labourer} (get-in % [:effect :op]))) -1
                                             (#{:gain :all-gain} (get-in % [:effect :op])) 0
                                             (get-in % [:effect :businessman?]) 1 :else 2) tasks)]
                   (recur (queue-front (assoc rest-state :effect-window (max window (:effect-window g 0))) (map #(assoc % :window window) tasks)) (inc steps)))
          :continue (recur (continue-step rest-state (:step head)) (inc steps))
          :pass-card (let [pid (:player head) cid (:card head) n (count (:players g))]
                       (recur (cond-> (update-in rest-state [:players pid :minors] #(vec (remove #{cid} %)))
                                (> n 1) (update-in [:players (mod (inc pid) n) :hand :minor] conj cid)) (inc steps)))
          :effect (cond
                    (vector? (get-in head [:effect :max-fields]))
                    (recur (assoc-in g [:effect-queue 0 :effect :max-fields]
                                     (expr/evaluate g (:player head) (:context head) (get-in head [:effect :max-fields]))) (inc steps))
                    (and (= :bake (get-in head [:effect :op])) (freshman-available? g (:player head)))
                    (recur (assoc-in g [:effect-queue 0 :effect] {:op :freshman-bake :original (:effect head) :optional? (get-in head [:effect :optional?])}) (inc steps))
                    (and (not= :classify-fields (get-in head [:effect :op]))
                         (some #(pos? (or % 0)) (vals (expr/classification-options (p g (:player head)) (:effect head)))))
                    (recur (assoc-in g [:effect-queue 0 :effect] {:op :classify-fields :original (:effect head) :optional? (get-in head [:effect :optional?])}) (inc steps))
                    (and (get-in head [:effect :discard-on-skip?]) (not (possible-effect? g head)))
                    (recur (-> rest-state (update-in [:players (:player head)] remove-owned-improvement (:card head))
                               (update :removed-cards (fnil conj []) (:card head))) (inc steps))
                    (not (possible-effect? g head))
                    (recur (or (promote-available-effect g head)
                               (a/log-event rest-state (str (or (:name (c/card (:card head))) "Card") " · no legal use of this effect."))) (inc steps))
                    (needs-input? g head) g
                    :else
                      (let [g (run-effect rest-state head {})
                            _ (observe-effect! head {} rest-state g)
                            g (cond-> g (:once? head) (assoc-in [:players (:player head) :card-state (:card head) :triggered?] true))]
                        (recur (with-housing g) (inc steps))))))
      g))))
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
(defn resource-choice-count
  "Consecutive identical mandatory resource choices from one card can be
   selected together. Their effects still execute separately, in native order."
  [g]
  (let [head (first (:effect-queue g)) effect (:effect head)]
    (if (not= :choose-good (:op effect)) 0
      (if (or (optional-effect? g head) (seq (:cost effect))) 1
        (count (take-while #(= head %) (:effect-queue g)))))))

(defn prepare-resource-choices [g choice]
  (if-not (and (contains? choice :resources) (= :choose-good (get-in g [:effect-queue 0 :effect :op]))) [g choice]
    (let [head (first (:effect-queue g)) resources (:resources choice)
          n (resource-choice-count g)]
      (a/require! (and (pos? n) (vector? resources) (= n (count resources)) (not (:skip? choice))
                       (every? (set (get-in head [:effect :choices])) resources))
                  (str "Choose exactly " n " of the offered resources."))
      ;; Preselect only the remaining choices, rather than executing past any
      ;; reactions caused by the first gain. Interruptions persist normally.
      [(update g :effect-queue
               #(vec (concat [(first %)]
                             (map (fn [task resource] (assoc-in task [:effect :selected-resource] resource))
                                  (take (dec n) (rest %)) (rest resources))
                             (drop n %))))
       (assoc choice :resource (first resources))])))

(defn resolve-choice [g pid choice]
  (let [head (first (:effect-queue g))]
    (a/require! (and (= :effect (:kind head)) (= pid (:player head))) "It is another player's card decision.")
    (a/require! (= (:decision choice) (:decision-id g)) "That decision has already been resolved.")
    (let [[g choice] (prepare-resource-choices g choice)
          next (-> g (update :effect-queue #(vec (rest %))) (run-effect head choice)
                   (update :decision-id (fnil inc 0)))
          next (cond-> next (:once? head) (assoc-in [:players pid :card-state (:card head) :triggered?] true))]
      (observe-effect! head choice g next)
      (with-housing next))))
(defn reset-turn-fence-bonuses [g]
  (update g :players #(mapv a/reset-turn-fence-bonus %)))
(defn advance [g]
  (let [g (update g :players #(mapv (fn [player] (dissoc player :reed-hut-acting?)) %))
        n (count (:players g)) pid (:turn g) lasso (:lasso g)
        taster? (and (= pid (get-in g [:taster :player])) (= (:round g) (get-in g [:taster :round])) (not (get-in g [:taster :used?])))
        next (first (filter #(or (pos? (get-in g [:players % :remaining])) (and (g-animal-farm-available? g (p g %)) (not= (:round g) (get-in g [:players % :card-state g-animal-farm :declined-round]))))
                            (if taster? (map #(mod (+ (:starting-player g) %) n) (range n))
                                (map #(mod (+ pid %) n) (range 1 (inc n))))))
        g (cond-> g (and taster? (not (and lasso (not (:second? lasso))))) (assoc-in [:taster :used?] true))]
    (cond
      (and lasso (not (:second? lasso)) (pos? (get-in g [:players pid :remaining])))
      (cond-> (assoc-in g [:lasso :second?] true)
        (:separate-turns? lasso) (-> reset-turn-fence-bonuses (update :turn-serial (fnil inc 0)) (dissoc :turn-construction)))
      (some? next) (-> g
                        (cond-> (zero? (:remaining (p g next))) (queue-front [(task next g-animal-farm {:op :g-animal-farm :optional? true :last-chance? true} {})]))
                        reset-turn-fence-bonuses (assoc :turn next) (update :turn-serial (fnil inc 0)) (dissoc :lasso :turn-construction))
      :else (-> g reset-turn-fence-bonuses (update :turn-serial (fnil inc 0)) (dissoc :lasso :turn-construction) start-relocations))))
(defn preparation-fingerprint [g]
  [(:taster g) (vec (for [player (:players g) cid (c/active-owned player)
                        :when (some #(#{:wood-distributor :foreman} (get-in % [:effect :op])) (:triggers (c/card cid)))]
                    [(:id player) cid (get-in player [:card-state cid :used-round])]))])
(defn preparation-reconsider [g]
  (let [fingerprint (preparation-fingerprint g)]
    (if (= fingerprint (:preparation-cycle g))
      (queue-front (dissoc g :preparation-cycle) (concat (before-work-tasks g) [(step :work)]))
      (let [candidates (for [offset (range (count (:players g)))
                             :let [pid (mod (+ (:starting-player g) offset) (count (:players g)))]
                             head (trigger-tasks g (event :round-start pid {:preparation-reconsider? true}))
                             :when (#{:wood-distributor :foreman :taster} (get-in head [:effect :op]))]
                         head)]
        (queue-front (assoc g :preparation-cycle fingerprint)
                     (concat candidates [(step :preparation-reconsider)]))))))
(defn begin-round [g]
  (let [g (if (game-has-cards? g #{fr/wood-gatherers}) (update g :players #(mapv (fn [p] (assoc p :construction-wood-spent 0)) %)) g)
        replenished? (and (pos? (get-in g [:piles :reed :reed] 0)) (= 1 (get-in (a/action-definition g :reed) [:gain :reed])))
        g (if (game-has-cards? g #{:catalog/original-expansion-fr-81-fiddler :catalog/original-expansion-fr-91-manual-labourer})
            (update g :players #(mapv (fn [player] (assoc player :round-received-baseline (:received player))) %)) g)
        g (assoc (a/begin-round (reset-turn-fence-bonuses (dissoc g :turn-construction))) :phase :preparation)
        g (update g :players #(mapv (fn [player]
                                     (let [guests (get-in player [:guest-schedule (:round g)] 0)]
                                       (cond-> (assoc player :guests guests)
                                         (not (a/holiday-house? player (:round g))) (update :remaining + guests)))) %))
        g (reduce (fn [state cid] (update-in state [:piles cid] #(merge-with + (or % {}) (:gain (a/action-definition state cid))))) g
                  (for [owner (:players g) cid (c/active-owned owner)
                        :when (#{:master-forester :clay-digger} (:action-space (c/card cid)))] cid))
        r (:round g)
        used (mapv #(count (remove (partial a/empty-cell? %) (range 15))) (:players g))
        arrivals (mapcat (fn [p]
                           (concat (when (and (c/has? p :catalog/original-expansion-g-45-pilgrim) (pos? (+ (a/well-food p r) (get-in p [:well-deliveries r] 0))))
                                     [(task (:id p) :well {:op :gain :goods {:food (+ (a/well-food p r) (get-in p [:well-deliveries r] 0))}} {})])
                           (for [{:keys [card goods stable person arrival-cost effect stable-cost]} (get-in p [:schedule r]) :when (some #{card} (c/owned p))]
                             (task (:id p) card (or effect (cond arrival-cost {:op :exchange :optional? true :cost arrival-cost :goods goods}
                                                   stable (cond-> {:op :stable-arrival} stable-cost (assoc :stable-cost stable-cost)) person {:op :temporary-person :optional? true}
                                                   (:field goods) {:op :plow :optional? true} :else {:op :gain :goods goods})) {})))) (:players g))
        automatic? #(and (= :gain (get-in % [:effect :op])) (not (get-in % [:effect :optional?])))]
    (queue-front g (concat (for [player (:players g) :when (or (c/has? player :catalog/original-expansion-g-92-pine-forest) (c/has? player :catalog/original-expansion-g-45-pilgrim))]
                            (event :before-round-deliveries (:id player)))
                          (filter automatic? arrivals)
                          (map #(event :round-start (:id %) {:reed-replenished-on-nonempty? replenished? :revealed-space (nth (:round-order g) (dec r))
                                                            :arrival-effects (vec (filter (fn [head] (and (= (:id %) (:player head)) (not (automatic? head)))) arrivals))
                                                            :rancher-leads? (every? (fn [other] (> (nth used (:id %)) (nth used other))) (remove #{(:id %)} (range (count used))))}) (:players g))
                          [(step :check-all) (step :preparation-reconsider)]))))
(defn initialize [g]
  (let [used (set (mapcat #(concat (get-in % [:hand :minor]) (:minors %)) (:players g)))
        reserve (vec (remove #(or (used %) (house/banned? (:house-rules g) (c/card %))) (or (:minor-order g) (sort (map :id (filter #(= :minor (:kind %)) (vals c/cards)))))))]
  (-> (assoc g :minor-deck reserve :minor-deck-count (count reserve) :major-builds {})
      (assoc :card-rng (or (:card-rng g) (random/seed g)))
      (assoc :variant :cards :defer-advance? true :effect-queue [] :decision-id 0 :round 0
             :active (a/base-actions (count (:players g)) :cards) :piles {} :occupied {} :log [])
      (update :players #(mapv (fn [p] (merge {:occupations [] :minors [] :card-state {} :schedule {} :card-fields {} :bonus-points 0} p)) %))
      begin-round drain)))
(defn card-bonus [g pid cid]
  (let [p (p g pid) {:keys [type thresholds values resource divisor]} (:score (c/card cid))
        thresholds (if (house/braggart? (:house-rules g) (c/card cid)) (mapv inc thresholds) thresholds)]
    (case type
      :basin-maker (let [n (get-in p [:card-state cid :stored-goods :wood] 0)] (- n (count (filter #(<= % n) [1 4 7 10]))))
      :tanner (+ (count (filter #(<= % (get-in p [:card-state cid :stored-goods :boar] 0)) [2 4 6]))
                 (count (filter #(<= % (get-in p [:card-state cid :stored-goods :cattle] 0)) [2 3 4])))
      :original-organic-pastures
      (count (filter #(and (pos? (:count %)) (>= (- (:capacity %) (:count %)) 3)
                           (or (= :pasture (:kind %)) (get-in (c/card (:card %)) [:passive :forest-boars])
                               (get-in (c/card (:card %)) [:passive :animal-yard]))) (a/animal-housing p)))
      :constable
      (let [yeoman? (some #(= :no-negative-points (get-in (c/card %) [:score :type])) (c/owned p))
            categories (:breakdown (a/score p))
            negative-category? (some (fn [[category n]] (and (neg? n) (or (not yeoman?) (#{:unused :begging} category)))) categories)
            negative-card? (and (not yeoman?)
                                (or (neg? (:bonus-points p 0))
                                    (some #(neg? (:points (c/card %) 0)) (:minors p))
                                    (some #(and (not= :constable (get-in (c/card %) [:score :type]))
                                                (neg? (card-bonus g pid %))) (c/owned p))))]
        (if (or negative-category? negative-card?) 0 5))
      :sawmill (count (filter #(<= % (a/craft-stock p :wood)) [2 4 5]))
      :best-road (if (= (get-in (c/card cid) [:score :rank])
                         (apply max 0 (mapcat #(c/passives % :road-value) (:players g)))) 2 0)
      :rake (let [tools (some #(or (get-in (c/card %) [:passive :action-plow])
                                   (#{(c/id :a018) :catalog/original-base-i-68-harrow
                                      :catalog/original-base-i-70-punner :catalog/original-base-k-124-yoke} %)) (c/owned p))]
              (if (>= (count (a/tiles p :field)) (if tools 6 5)) 2 0))
      :corral (a/corral-points p)
      :village-fool (let [n (get-in p [:card-state cid :hidden-count] 0)] (- n (count (filter #(<= % n) [3 5 7 9]))))
      :harvester (if (>= (:final-harvest-goods p 0) 5) 3 0)
      :animal-farmer (a/animal-farmer-points p)
      :gold-medal (let [category (some #(get-in % [:card-state cid :category]) (:players g))
                        values (map #(get-in (a/score %) [:breakdown category] 0) (:players g))]
                    (if (and category (= (nth (vec values) pid) (apply max values))) 2 0))
      :unreturned-animals (- (reduce + 0 (vals (get-in p [:card-state cid :returned-animals] {}))) (count (loan-resources cid)))
      :collected-animals (count (filter #(<= % (get-in p [:card-state cid :stored-goods resource] 0)) thresholds))
      :expression (expr/evaluate g pid {} (get-in (c/card cid) [:score :value]))
      :pasture-spaces (count (filter #(<= % (count (a/pasture-cells p))) thresholds))
      :house (get values (:house p) 0)
      :unfenced-stables (count (filter #(and (get-in p [:farm % :stable]) (not ((a/pasture-cells p) %))) (range 15)))
      :improvements (let [n (+ (count (:improvements p)) (count (:minors p))) qualifying (count (filter #(<= % n) thresholds))]
                      (if (zero? qualifying) 0 (nth values (dec qualifying))))
      :animals (quot (a/amount p resource) divisor)
      :tutor (tutor-points p cid)
      :no-negative-points
      (+ (reduce + 0 (map #(max 0 (- %)) (vals (dissoc (:breakdown (a/score p)) :unused :begging))))
         (reduce + 0 (for [other (c/owned p) :when (not= :no-negative-points (get-in (c/card other) [:score :type]))]
                       (max 0 (- (card-bonus g pid other)))))
         (reduce + 0 (map #(max 0 (- (:points (c/card %) 0))) (:minors p)))
         (max 0 (- (:bonus-points p 0))))
      :maximum-basic-categories
      (count (filter #(= 4 %) (vals (select-keys (:breakdown (a/score p))
                                                  [:fields :pastures :grain :vegetables :sheep :boar :cattle :stables]))))
      :most-of-each-animal
      (if (every? (fn [animal] (every? #(>= (a/amount p animal) (a/amount % animal)) (:players g))) a/animals)
        (dec (count (:players g))) 0)
      :most-original-occupations
      (let [occupation-count #(c/occupation-count % :original-reeve)]
        (if (= (occupation-count p) (apply max (map occupation-count (:players g)))) 3 0))
      :most-rooms (if (= (count (a/tiles p :room)) (apply max (map #(count (a/tiles % :room)) (:players g)))) 3 0)
      :most-improvements (let [n #(+ (count (:improvements %)) (count (:minors %)))]
                           (if (= (n p) (apply max (map n (:players g)))) 3 0))
      :five-people-worked (if (>= (get-in p [:people-placed-by-round 14] 0) 5) 3 0)
      :full-pastures (count (filter #(and (= :pasture (:kind %)) (= (:capacity %) (:count %))) (a/animal-housing p)))
      :organic-pastures (count (filter #(and (= :pasture (:kind %)) (pos? (:count %)) (>= (- (:capacity %) (:count %)) 3)) (a/animal-housing p)))
      0)))
(defn score [g pid]
  (let [p (p g pid) score (a/score p) points (reduce + 0 (map #(if (contains? (:pawned-improvements p #{}) %) 0 (or (:points (c/card %)) 0)) (:minors p)))
        bonus (+ (or (:bonus-points p) 0) (reduce + 0 (map #(card-bonus g pid %) (c/owned p)))
                 (reduce + 0 (for [other (:players g) :when (not= pid (:id other))
                                   cid (c/owned other) :when (:global-score? (c/card cid))]
                               (card-bonus g pid cid)))
                 (if (and (not (c/has? p :b136)) (some #(c/has? % :b136) (:players g))) (card-bonus g pid (c/id :b136)) 0))]
    (-> score (update :total + points bonus) (update-in [:breakdown :improvements] + points) (update-in [:breakdown :bonus] + bonus))))
(defn finish-game [g]
  (if-not (:before-final-score-started? g)
    (queue-front (assoc g :phase :scoring :before-final-score-started? true)
                 (concat (map #(event :before-final-score (:id %)) (:players g)) [(step :score-final)]))
    (let [loans (when-not (:final-scoring-started? g)
                  (for [offset (range (count (:players g)))
                        :let [pid (mod (+ (:starting-player g) offset) (count (:players g))) player (p g pid)]
                        cid (c/active-owned player)
                        :when (and (= :return-animal-loan (get-in (c/card cid) [:ability :op]))
                                   (not (get-in player [:card-state cid :loan-settled?])))]
                    (task pid cid {:op :return-animal-loan} {})))
          choices (when-not (:final-scoring-started? g)
                    (for [player (:players g) cid (c/active-owned player)
                          :let [effect (:final-choice (c/card cid))] :when effect]
                      (task (:id player) cid effect {})))]
      (if (seq (concat loans choices))
        (queue-front (assoc g :phase :scoring :final-scoring-started? true) (concat loans choices [(step :score-final)]))
        (if (:final-animal-feed? g)
          (update (with-housing (dissoc g :final-animal-feed?)) :effect-queue #(conj (vec %) (step :score-final)))
        (let [scores (mapv #(score g (:id %)) (:players g)) best (last (sort (map (juxt :total :tiebreak) scores)))
              winners (vec (keep-indexed #(when (= best ((juxt :total :tiebreak) %2)) %1) scores))]
          (-> g (assoc :phase :finished :scores scores :winners winners) (a/log-event "The final harvest is complete."))))))))
(defn space-owners [g space]
  (let [owner (when-not (contains? (:newborn-only g) space) (get-in g [:occupied space]))] (if (sequential? owner) (vec owner) (if (some? owner) [owner] []))))
(defn space-people [g space]
  (+ (count (space-owners g space))
     (count (get-in g [:newborn-spaces space]))))
(def family-sharing-spaces #{:build :house-building :growth :urgent-growth :modest-growth})
(defn head-family-linked-space [g space]
  (when (and (= 5 (count (:players g)))
             (or (family-sharing-spaces space) (family-sharing-spaces (a/linked-spaces space))))
    (a/linked-spaces space)))
(def construction-sharing-spaces #{:build :house-building :renovate :redevelop :improvement})
(defn head-family-permission? [g pid space]
  (let [linked (head-family-linked-space g space) spaces (cond-> [space] linked (conj linked))]
    (and (or (and (some true? (c/passives (p g pid) :share-construction-spaces))
                       (or (construction-sharing-spaces space) (and linked (construction-sharing-spaces linked))))
                  (and (some true? (c/passives (p g pid) :share-family-spaces))
                       (or (family-sharing-spaces space) linked))
                  (and (some true? (c/passives (p g pid) :share-growth-spaces))
                       (or (#{:growth :urgent-growth :modest-growth} space)
                           (and (= 5 (count (:players g))) (>= (:round g) 5) (= space :lessons-growth)))))
         (some #(contains? (:occupied g) %) spaces)
         (not-any? #{pid} (mapcat #(space-owners g %) spaces)))))
(defn community-shared-permission [g pid space]
  (let [player (p g pid) linked (when (= 5 (count (:players g))) (a/linked-spaces space))
        owners (concat (space-owners g space) (when linked (space-owners g linked)))]
    (when (seq owners)
      (cond
        (and (some true? (c/passives player :cowboy)) (#{:sheep :boar :cattle} space)) :cowboy
        (and (some true? (c/passives player :understudy)) (= [pid] (vec owners))
             (empty? (get-in g [:newborn-spaces space])) (not (contains? (:newborn-only g #{}) space))) :understudy
        (and (some true? (c/passives player :freeloader)) (zero? (placed-people g pid)) (not= space :meeting)) :freeloader
        (and (some true? (c/passives player :diplomat)) (= 14 (:round g)) (not-any? #{pid} owners)
             (pos? (get-in player [:card-state :catalog/original-expansion-wm-16-diplomat :uses] 0))) :diplomat
        (and (some true? (c/passives player :third-person-plow)) (= space :plow)
             (= 2 (if (= :original-relocation (get-in g [:action-context :mode]))
                    (get-in g [:action-context :worker-index]) (placed-people g pid)))) :hay-rake))))
(defn occupied-permission [g pid space]
  (when (or (contains? (:occupied g) space) (head-family-permission? g pid space) (community-shared-permission g pid space))
    (let [player (p g pid) workers (:worker-spaces player)]
      (cond
        (community-shared-permission g pid space) (community-shared-permission g pid space)
        (= space :meeting) nil
        (and (= pid (get-in g [:action-context :player]))
             (#{:lazy-sowman :swagman} (get-in g [:action-context :mode]))) :extra-action
        (and (owned? g pid :a028) (#{:lessons :lessons-extra :lessons-copse :lessons-growth} space)) :forest-school
        (head-family-permission? g pid space) :head-of-family
        (and (some #(contains? % space) (c/passives player :share-action-spaces))
             (not (some #{pid} (space-owners g space)))) :shared-action
        (and (some true? (c/passives player :share-field-spaces)) (#{:vegetable :plow :cultivation} space)
             (not (some #{pid} (space-owners g space)))) :field-warden
        (and (owned? g pid :a025) (= space (:first-nonaccumulation g)) (= 1 (space-people g space))) :bassinet
        (and (owned? g pid :a130) (>= (count workers) 2) (= space (second workers))
             (some #{pid} (space-owners g space)) (not= (:round g) (:mummy-used-round player))) :mummys-boy
        (and (owned? g pid :a026) (#{:growth :urgent-growth :modest-growth} space)
             (= 1 (count (space-owners g space))) (not= pid (first (space-owners g space)))) :sleeping-corner))))
(defn placed-people [g pid]
  (let [player (p g pid)
        blocked (+ (if (= (:round g) (:convict-blocked-round player)) 1 0) (if (= (:round g) (:ratcatcher-blocked-round player)) 1 0) (if (= (:round g) (:honeymoon-round player)) 2 0))
        legacy (max (count (:worker-spaces player))
                    (- (a/workers-in-play player) (:newborn player 0) (:remaining player) blocked))]
    (get-in player [:people-placed-by-round (:round g)] legacy)))
(defn new-person-placement? [g]
  (not (#{:swagman :minstrel :original-relocation} (get-in g [:action-context :mode]))))
(defn record-placement [before after pid space & [cmd]]
  (let [mode (get-in before [:action-context :mode])
        newborns (max 0 (- (:newborn (p after pid) 0) (:newborn (p before pid) 0)))
        after (if (game-has-cards? before #{:catalog/original-expansion-g-43-opportunist})
                (assoc after :g-work-resources {:round (:round before) :goods (into (if (= (:round before) (get-in before [:g-work-resources :round])) (get-in before [:g-work-resources :goods]) #{})
                    (for [[r n] (source-action-goods* before (assoc cmd :space space)) :when (and (contains? #{:wood :clay :reed :stone} r) (pos? n))] r))}) after)
        after (case mode
                :minstrel after
                (:swagman :original-relocation) (assoc-in after [:players pid :worker-spaces (get-in before [:action-context :worker-index])] space)
                (update-in after [:players pid :worker-spaces] (fnil conj []) space))]
    (cond-> (-> (reduce (fn [state resource]
                           (if (and (pos? (get (source-action-goods before (assoc cmd :space space)) resource 0))
                                    (not= (:round before) (get-in before [:first-action-goods resource :round])))
                             (assoc-in state [:first-action-goods resource] {:round (:round before) :player pid}) state))
                         after [:reed :stone]) (update :newborn-only disj space) (assoc-in [:players pid :tracked-newborns] (:newborn (p after pid) 0)))
      (and (not= mode :minstrel) (nil? (:first-nonaccumulation before)) (not (:accumulate (a/action-definition before space)))) (assoc :first-nonaccumulation space)
      (and (new-person-placement? before) (get-in before [:players pid :card-state g-animal-farm :unplaced?])) (update-in [:players pid :card-state g-animal-farm] dissoc :unplaced?)
      (new-person-placement? before) (assoc-in [:players pid :people-placed-by-round (:round before)] (inc (placed-people before pid)))
      (and (new-person-placement? before) (:reed-hut-acting? (p before pid))) (assoc-in [:players pid :reed-hut-placed?] true)
      (and (game-has-cards? before #{:catalog/original-expansion-fr-62-animal-welfarist})
           (#{:build :side-job} space) (> (count (filter :stable (:farm (p after pid)))) (count (filter :stable (:farm (p before pid))))))
      (update-in [:players pid :work-actions (:round before)] (fnil conj #{}) :stables)
      (and (game-has-cards? before #{:catalog/original-expansion-fr-62-animal-welfarist})
           (= :fences space) (> (count (:fences (p after pid))) (count (:fences (p before pid)))))
      (update-in [:players pid :work-actions (:round before)] (fnil conj #{}) :fences)
      (= :diplomat (community-shared-permission before pid space)) (update-in [:players pid :card-state :catalog/original-expansion-wm-16-diplomat :uses] dec)
      (= :mummys-boy (occupied-permission before pid space)) (assoc-in [:players pid :mummy-used-round] (:round before))
      (pos? newborns) (update-in [:newborn-spaces space] (fnil into []) (repeat newborns pid)))))
(defn available-action-space? [g pid space]
  (let [index (first (keep-indexed #(when (= space %2) %1) (:revealed-round-order g)))
        future? (and index (>= index (:round g)))]
    (and (or (not future?) (some true? (c/passives (p g pid) :early-rounds)))
         (a/available-space? (cond (head-family-permission? g pid space) (update g :occupied dissoc (head-family-linked-space g space))
                                  (community-shared-permission g pid space) (update g :occupied dissoc (a/linked-spaces space))
                                  :else g) space))))
(defn validate-space [g pid space]
  (a/require! (= :work (:phase g)) "Finish the current phase first.")
  (a/require! (= pid (:turn g)) "It is another player's turn.")
  (when (and (= space :meeting) (some true? (c/passives (p g pid) :freeloader)))
    (a/require! (pos? (placed-people g pid)) "Freeloader cannot take Starting Player with the first person of a round."))
  (when (and (#{:lessons :lessons-extra :lessons-copse :lessons-growth} space) (some true? (c/passives (p g pid) :no-occupation-spaces)))
    (a/require! false "Farm School Graduate cannot use an occupation action on an action space."))
  (a/require! (available-action-space? g pid space)
              "That space is not available yet or its linked space is occupied.")
  (a/require! (pos? (:remaining (p g pid))) "No family members remain at home.")
  (let [owner (get-in g [:occupied space])]
    (a/require! (or (nil? owner) (occupied-permission g pid space)) "That action space is occupied."))
  (when (and (#{:head-of-family :freeloader :diplomat :understudy} (occupied-permission g pid space)) (:accumulate (a/action-definition g space)))
    (a/require! (some pos? (vals (get-in g [:piles space]))) "Head of the Family cannot use an empty accumulation space."))
  (when (and (:second? (:lasso g)) (:must-animal? (:lasso g)))
    (a/require! ((get-in g [:lasso :animal-spaces] #{:sheep :boar :cattle :animal-market}) space) "The second Lasso action must use an eligible animal space.")))
(defn mark-worker [g pid space]
  (-> g (update-in [:players pid :remaining] dec) (assoc-in [:occupied space] pid)
      (a/log-event (str (:name (p g pid)) " · " (or (:name (a/action-definition g space)) (:name (c/card space)))))))
(defn round-space-number
  "Revealed round-card position, independent of the current round and without
   needing the hidden future round order. Card-created action spaces have none."
  [g space]
  (first (keep-indexed (fn [index id] (when (= space id) (inc index)))
                      (or (:revealed-round-order g)
                          (drop (count (a/base-actions (count (:players g)) (:variant g))) (:active g))))))
(defn before-use-gains [g pid space]
  (let [round-space (round-space-number g space)]
    (reduce (fn [player cid]
              (if-let [goods (get-in (c/card cid) [:passive :before-use-round-goods round-space])]
                (a/gain player goods) player))
            (cond-> (p g pid)
              (and (= round-space (:round g)) (some true? (c/passives (p g pid) :newest-round-grain)))
              (a/gain {:grain 1})) (c/active-owned (p g pid)))))
(defn pre-use-shaving? [g pid space]
  (let [before (p g pid) after (before-use-gains g pid space)]
    (and (not= :minstrel (get-in g [:action-context :mode])) (c/has? before :a048)
         (> (a/amount after :wood) (a/amount before :wood))
         (<= 5 (a/amount after :wood) 6))))
(defn before-use-player
  ([g pid space] (before-use-player g pid space {}))
  ([g pid space options]
   (let [before (p g pid) gained (before-use-gains g pid space)
         receipt? (and (c/has? before :a048) (> (a/amount gained :wood) (a/amount before :wood)))
         selected? (:shaving-horse? options)]
     (when selected? (a/require! (and receipt? (>= (a/amount gained :wood) 5)) "Shaving Horse needs a qualifying wood bonus before this action."))
     (let [after (if (and receipt? (or selected? (>= (a/amount gained :wood) 7)))
                   (-> gained (a/pay {:wood 1}) (a/gain {:food 3})) gained)]
       (cond-> after receipt? (assoc-in [:card-state (c/id :a048) :received-seen] (:received after)))))))
(defn accumulation-goods [g space]
  (let [definition (a/action-definition g space)]
    (when (:accumulate definition) (set (keys (:gain definition))))))
(defn accumulation-history [g pid]
  (let [history (:used-accumulations (p g pid))]
    (if (= (:round g) (:round history)) (:by-good history) {})))
(defn repeated-accumulation? [g pid space]
  (boolean (some #(seq (disj (get (accumulation-history g pid) % #{}) space))
                 (accumulation-goods g space))))
(defn record-accumulation [g pid space]
  (if-let [goods (seq (accumulation-goods g space))]
    (assoc-in g [:players pid :used-accumulations]
              {:round (:round g)
               :by-good (reduce #(update %1 %2 (fnil conj #{}) space)
                                (accumulation-history g pid) goods)}) g))
(defn source-action-goods* [g cmd]
  (let [space (:space cmd) action (a/action-definition g space)]
    (cond
      (and (= :points (:action cmd)) (#{:tavern :clay-deposit} (:action-space (c/card space)))) {}
      (= :cowboy (occupied-permission g (or (get-in g [:action-context :player]) (:turn g)) space)) {space 1}
      (:accumulate action) (let [pile (get-in g [:piles space])]
                             (if (:part-time? cmd) (into {} (map (fn [[r n]] [r (quot n 2)]) pile)) pile))
      (:gain action) (:gain action)
      (= space :market) (if (= 4 (count (:players g))) {:reed 1 :stone 1 :food 1} {(:choice cmd) 1 :food 1})
      (= space :animal-market) {(:choice cmd) 1}
      (= :forest-tallyman (:action-space (c/card space))) {:wood 3 :clay 2}
      (= :substitute-teacher (:action-space (c/card space)))
      (if (= :crops (:bundle cmd)) {:grain 1 :vegetable 1} {:wood 1 :clay 1 :reed 1 :stone 1})
      :else {})))
(defn source-action-goods [g cmd]
  (merge-with + (source-action-goods* g cmd) (get-in g [:space-bonuses (:space cmd)])))
(defn pig-catcher-available? [g pid space]
  (and (some true? (c/passives (p g pid) :pig-catcher))
       (:accumulate (a/action-definition g space))
       (>= (get-in g [:piles space :wood] 0)
           (if (and (= space :wood) (= 3 (get-in (a/action-definition g space) [:gain :wood]))
                    (some #(and (not= pid (:id %)) (some #{:catalog/original-base-i-80-spinney} (c/active-owned %))) (:players g))) 3 2))))
(defn apply-pig-catcher [before after pid cmd]
  (if-not (:pig-catcher? cmd) after
    (do (a/require! (and (pig-catcher-available? before pid (:space cmd)) (not (:part-time? cmd)))
                    "Pig Catcher needs two wood from this accumulation space.")
        (-> after (update-in [:players pid] #(-> % (a/pay {:wood 2}) (a/gain {:boar 1})))
            (update-in [:piles (:space cmd) :wood] (fnil + 0) 2)))))
(defn action-events [before after pid cmd]
  (let [p0 (p before pid) p1 (p after pid) space (:space cmd)
        context (cond-> {:space space :action-id [(:round before) (:turn-serial before 0) pid (count (:worker-spaces p0))]
                 :source-returned (if (:pig-catcher? cmd) {:wood 2} {})
                 :first-reed? (and (pos? (get (source-action-goods before cmd) :reed 0)) (not= (:round before) (get-in before [:first-action-goods :reed :round])))
                 :first-stone? (and (pos? (get (source-action-goods before cmd) :stone 0)) (not= (:round before) (get-in before [:first-action-goods :stone :round])))
                 :repeated-accumulation? (repeated-accumulation? before pid space) :previous-house (:house p0) :old-pastures (a/pastures p0)
                 :before-goods (:goods p0) :before-received (:received p0) :source-goods (source-action-goods before cmd)
                 :worker-index (or (get-in before [:action-context :worker-index]) (count (:worker-spaces p0)))
                 :last-person? (and (new-person-placement? before) (= 1 (:remaining p0)))
                 :new-stables (vec (filter #(and (get-in p1 [:farm % :stable]) (not (get-in p0 [:farm % :stable]))) (range 15)))
                 :placed-person (+ (placed-people before pid) (if (new-person-placement? before) 1 0))
                 :building-pile-size (reduce + (map #(get-in before [:piles space %] 0) [:wood :clay :reed :stone]))
                 :upgraded-fireplace? (boolean (:upgrade cmd))
                 :improvement (:improvement cmd)
                 :pile-food (+ (get-in before [:piles space :food] 0) (get-in before [:space-bonuses space :food] 0))
                 :rooms-before (count (a/tiles p0 :room)) :family-before (:family p0)
                 :rooms-built (- (count (a/tiles p1 :room)) (count (a/tiles p0 :room)))
                 :stables-built (- (count (filter :stable (:farm p1))) (count (filter :stable (:farm p0))))
                 :fences-built (- (count (:fences p1)) (count (:fences p0)))
                 :fields-plowed (- (count (a/tiles p1 :field)) (count (a/tiles p0 :field)))
                 :taken (when (:accumulate (a/action-definition before space))
                          (let [pile (get-in before [:piles space])]
                            (cond-> (if (:part-time? cmd) (into {} (map (fn [[r n]] [r (quot n 2)]) pile)) pile)
                              (:pig-catcher? cmd) (update :wood - 2))))}
                  (and (:improvement cmd) (or (c/has? p0 :catalog/original-expansion-fr-64-award-winner) (game-has-cards? before #{fr/good-friend})))
                  (assoc :paid (get-in p1 [:last-improvement-payment :paid]))
                  (and (:improvement cmd) (game-has-cards? before #{fr/good-friend}))
                  (assoc :minor-play? (boolean (#{:meeting :growth :urgent-growth :modest-growth} space)))
                  (game-has-cards? before #{:catalog/original-expansion-g-66-birthstone})
                  (assoc :newborns-added (max 0 (- (:family p1) (:family p0))))
                  (c/has? p0 :catalog/original-expansion-wm-60-attic)
                  (assoc :new-rooms (vec (remove (set (a/tiles p0 :room)) (a/tiles p1 :room))))
                  (c/has? p0 :catalog/original-expansion-fr-42-rock-pyramid)
                  (assoc :paid-stone-rooms (if (and (pos? (- (count (a/tiles p1 :room)) (count (a/tiles p0 :room)))))
                                            (get-in p1 [:last-room-payment :stone-rooms] 0) 0)))]
    (concat
     [(event :before-action-rewards pid context)]
     ;; Resolve housing before card triggers can add more animals. Existing
     ;; animals may be cooked instead: retaining at least the newly taken pile
     ;; is enough to accommodate every animal from that pile.
     (animal-accommodation-tasks before pid context)
     (mapcat (fn [type]
               (let [before-cells (set (case type :room (a/tiles p0 :room) :field (a/tiles p0 :field)
                                             :stable (keep-indexed #(when (:stable %2) %1) (:farm p0))
                                             :pasture (a/pasture-cells p0)))
                     after-cells (case type :room (a/tiles p1 :room) :field (a/tiles p1 :field)
                                           :stable (keep-indexed #(when (:stable %2) %1) (:farm p1))
                                           :pasture (a/pasture-cells p1))
                     used (count (filter #(and (not (before-cells %)) (a/empty-cell? p0 %)) after-cells))]
                 (when (>= used 2) [(event :use-farm-spaces pid {:used-spaces used :action type})])))
             [:room :field :stable :pasture])
     (when (and (#{:sow-bake :cultivation} space) (seq (:plantings cmd))) [(event :sow pid (cond-> {:unconditional? true :vegetables-sown (count (filter #(= :vegetable (:crop %)) (:plantings cmd)))}
                                (c/has? p0 :catalog/original-expansion-g-104-seasonal-plants) (assoc :plantings (:plantings cmd))))])
     (when (pos? (:fields-plowed context)) [(event :plow pid context)])
     (when (and (> (:fields-plowed context) 1) (get (:plow-plan before) :card))
       [(event :plow-tool-used pid (assoc context :plow-card (get-in before [:plow-plan :card])))])
     (when (pos? (:stables-built context)) [(event :stables-created pid context) (event :build-stables pid context)])
     (when (> (count (a/tiles p1 :room)) (count (a/tiles p0 :room))) [(event :build-rooms pid context)])
     (when (not= (:house p0) (:house p1)) [(event :renovate pid context)])
     (when (> (:family p1) (:family p0)) [(event :grow pid context)])
     (when (> (count (:fences p1)) (count (:fences p0))) [(event :fence pid context)])
     (when (seq (set/difference (:improvements p1) (:improvements p0))) [(event :build-improvement pid context)])
     (when (and (= 5 (count (:players before))) (seq (set/difference (:improvements p1) (:improvements p0)))) [(event :major-built pid context)])
     (when (and (pos? (or (:bake-grain cmd) 0))
                (or (#{:sow-bake :side-job} space)
                    (and (#{:improvement :renovate :meeting :growth} space)
                         (:oven (a/improvements (:improvement cmd)))
                         (contains? (:improvements p1) (:improvement cmd)))))
       [(event :bake pid (assoc context :grain-baked (:bake-grain cmd)))])
     (when (seq (set/difference (:improvements p1) (:improvements p0)))
       [(improvement-finished-event pid (or (:improvement-action cmd) {:kinds (if (#{:meeting :growth} space) #{:minor} #{:major :minor}) :origin-space space}) false)])
     (when-let [effect (get-in (c/card (get-in before [:plow-plan :card])) [:passive :after-plow])]
       [(task pid (get-in before [:plow-plan :card]) effect context)])
     (when-not (= :minstrel (get-in before [:action-context :mode]))
       [(event :before-action-benefits pid context) (event :use-space pid context) (event :after-use-space pid context) (event :turn-end pid context) (event :after-action pid context) (event :after-resource-returns pid context)])
     [(task pid nil {:op :flush-action-receipts :action-id (:action-id context)} context)]
     (when-let [cid (first (filter #(= space (get-in p0 [:card-state % :marked-space])) (c/active-owned p0)))]
       [(task pid cid {:op :build-fences :optional? true :reset-fence-bonus? true} context)])
     [(step :check-all)]
     (when (and (c/has? p1 :catalog/original-expansion-g-43-opportunist) (zero? (:remaining p1)))
       [(task pid :catalog/original-expansion-g-43-opportunist {:op :g-opportunist :optional? true}
              {:g-last-action? true :g-resources-before (:g-work-resources before) :source-goods (source-action-goods* before cmd)})])
     (when-not (:action-context before) [(step :advance)]))))
(defn commit-plow-use [after before pid space]
  (let [plan (:plow-plan before) cid (get plan :card (c/id :a018))]
    (cond (get-in (c/card cid) [:passive :action-plow :unlimited?]) after
      (get-in (c/card cid) [:passive :action-plow :public?])
      (update-in after [:players (:owner plan) :card-state cid :used-by] (fnil conj #{}) pid)
       :else (update-in after [:players pid :card-state cid :uses] dec))))
(defn take-action-complete [g pid {:keys [space card lasso?] :as source-cmd}]
  (let [g (dissoc g :stableman-plan)
        [g improvement-action] (salesman-action g pid
                                  {:kinds (if (#{:meeting :growth} space) #{:minor} #{:major :minor}) :origin-space space} source-cmd)
        _ (when (:salesman? source-cmd)
            (a/require! (or card (:improvement source-cmd)) "Choose an improvement when using Traveling Salesman."))
        _ (when (= :two-minors (:salesman-mode improvement-action))
            (a/require! (or card (a/major-as-minor? (p g pid) (:improvement source-cmd))) "Traveling Salesman's double action plays minors only."))
        _ (when (= :major (:salesman-mode improvement-action))
            (a/require! (or (:improvement source-cmd) (get-in (c/card card) [:passive :major-or-minor])) "Choose a major improvement for Traveling Salesman."))
        source-cmd (assoc source-cmd :improvement-action improvement-action)
        plan (:freshman-plan g)
        replacement? (and (= pid (:player plan)) (= space (:space plan)) (:card plan))
        cmd (cond-> source-cmd replacement? (assoc :bake-grain 0 :cook-with-bake {}))]
  (validate-space g pid space)
  (a/require! (not (and card (:improvement cmd))) "Choose one major or minor improvement, not both.")
  (when (:improvement cmd)
    (a/require! (or (#{:improvement :renovate} space)
                    (and (#{:meeting :growth} space) (or (= :major (:salesman-mode improvement-action)) (a/major-as-minor? (p g pid) (:improvement cmd)))))
                "This action does not allow that supply improvement."))
  (when lasso?
    (a/require! (and (seq (lasso-animal-spaces (p g pid))) (not (:lasso g))) "Lasso is unavailable.")
    (a/require! (>= (:remaining (p g pid)) 2) "Lasso requires two people available to place.")
    (a/require! (or ((lasso-animal-spaces (p g pid)) space)
                    (some #(and (some #{%} (:active g)) (not (contains? (:occupied g) %))) (lasso-animal-spaces (p g pid))))
                "There is no free animal market for the Lasso's second action."))
  (let [occupied (get-in g [:occupied space])
        cowboy? (= :cowboy (occupied-permission g pid space))
        head-linked (or (when (head-family-permission? g pid space) (head-family-linked-space g space))
                        (when (and (= 5 (count (:players g))) (community-shared-permission g pid space)) (a/linked-spaces space)))
        base (cond-> (collect-space-bonus (five/refresh g) pid space) cowboy? (assoc-in [:piles space] {space 1}))
        base (cond-> base head-linked (update :occupied dissoc head-linked))
        base (cond-> (assoc-in base [:players pid] (if (= :minstrel (get-in g [:action-context :mode])) (p base pid) (before-use-player base pid space cmd))) occupied (update :occupied dissoc space))
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
        after (apply-pig-catcher g after pid cmd)
        after (if (and (:improvement cmd) (#{:meeting :growth} space))
                (a/build-improvement after pid cmd) after)
        after (cond-> after
                (:animal-brander? cmd) (update-in [:players pid] #(-> % (a/pay (if (= :cattle (:choice cmd)) {:food 1} {}))
                                                                              (a/gain (merge {(:choice cmd) 1} (when (= :sheep (:choice cmd)) {:food 1})))))
                (:part-time? cmd) (assoc-in [:piles space] (into {} (map (fn [[r n]] [r (quot n 2)]) pile)))
                (:part-time? cmd) (update-in [:players pid] a/gain {({2 :sheep 4 :boar 6 :cattle} total) 1})
                (and (= 5 (count (:players g))) (seq (set/difference (:improvements (p after pid)) (:improvements (p g pid))))) (update-in [:major-builds pid] (fnil inc 0)))
        after (cond-> after occupied (assoc-in [:occupied space] (conj (space-owners g space) pid))
                      (and head-linked (contains? (:occupied g) head-linked))
                      (assoc-in [:occupied head-linked] (get-in g [:occupied head-linked]))
                      lasso? (assoc :lasso {:player pid :must-animal? (not ((lasso-animal-spaces (p g pid)) space)) :second? false
                                           :animal-spaces (lasso-animal-spaces (p g pid))
                                           :separate-turns? (owned? g pid original-lasso)}))
        after (cond-> after cowboy? (assoc-in [:piles space] (get-in g [:piles space])))
        after (begin-action-receipt g after pid cmd)
        after (queue-front (record-placement g (if (= :minstrel (get-in g [:action-context :mode])) after (record-accumulation after pid space)) pid space cmd) (action-events g after pid cmd))
        count-occ (c/occupation-count (p g pid) nil)
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
                (do (a/require! minor? "This action allows a minor improvement from your hand.") (play-card after pid card 0 cmd {:major-action? (contains? (:kinds improvement-action) :major)
                                                                        :major-only? (= #{:major} (:kinds improvement-action))
                                                                        :major-or-minor? (= #{:major :minor} (:kinds improvement-action))
                                                                        :improvement-action improvement-action}))
                card (throw (ex-info "That action does not allow playing a hand card." {}))
                :else after)]
    (with-housing (finish-action-context (cond-> (dissoc (if replacement?
                                  (use-freshman
                                   (cond-> after (and (= space :sow-bake) (not (:sow-before? plan)))
                                     (queue-front [(task pid nil {:op :sow-offer :optional? true :unconditional? true
                                                                 :committed-source :sow-bake} {:space :sow-bake})]))
                                   pid (:card plan) (:options plan)) after)
                                 :cancelled-sowing :plow-plan :freshman-plan :serf-plan)
                    (and (= pid (get-in g [:plow-plan :player])) (= space (get-in g [:plow-plan :space]))
                         (#{:plow :cultivation} space) (seq (get-in g [:plow-plan :cells])) (get (:plow-plan g) :card (c/id :a018)))
                    (commit-plow-use g pid space)) true)))))
(defn staged-sowing? [g pid space]
  (and (#{:sow-bake :cultivation} space)
       (or (and (= :sow-bake space) (:serf-plan g))
           (and (= :sow-bake space) (get-in g [:freshman-plan :card]) (get-in g [:freshman-plan :sow-before?]))
           (seq (get-in g [:plow-plan :cells])) (owned? g pid :a094) (owned? g pid :a065) (owned? g pid :a079)
           (some #(and (not= pid (:id %)) (c/has? % :a132)) (:players g)))))
(defn take-action-with-sowing [g pid {:keys [space] :as command}]
  (if-not (staged-sowing? g pid space) (take-action-complete g pid command)
    (do
      (validate-space g pid space)
      (a/require! (not-any? #(get command %) [:card :improvement :master-hora? :animal-brander? :part-time?]) "That option is unavailable on a Sow action space.")
      (when (some? (:bake-grain command))
        (a/require! (a/natural? (:bake-grain command)) "Choose a nonnegative number of grain to bake."))
      (when (:lasso? command)
        (a/require! (and (seq (lasso-animal-spaces (p g pid))) (not (:lasso g)) (>= (:remaining (p g pid)) 2)) "Lasso is unavailable.")
        (a/require! (some #(and (some #{%} (:active g)) (not (contains? (:occupied g) %))) (lasso-animal-spaces (p g pid)))
                    "There is no free animal market for the Lasso's second action."))
      (let [source (select-keys command (cond-> [:space :cell :lasso? :shaving-horse?] (= space :sow-bake) (conj :bake-grain :cook-with-bake)))
            effect {:op :sow-offer :optional? true :unconditional? true :source-command source}]
        ;; Validate a planned plow now, before any other player commits a gift.
        (sowing-player g pid effect)
        (a/require! (or (sowing-potential? g pid effect)
                        (try (take-action-complete g pid (assoc source :plantings [])) true
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false)))
                    "You cannot sow, plow, or bake on this space now.")
        (queue-front g [(task pid nil effect {})])))))
(defn wheel-plow-available? [g pid space]
  (let [player (p g pid)]
    (and (#{:plow :cultivation} space) (owned? g pid :a018)
         (pos? (get-in player [:card-state (c/id :a018) :uses] 0))
         (zero? (placed-people g pid)))))
(defn plow-options [g pid space]
  (let [player (p g pid)]
    (if-not (#{:plow :cultivation} space) {:plows [] :plow-maker? false}
      {:plows (vec (concat (when (wheel-plow-available? g pid space) [(c/id :a018)])
                          (sort (for [owner (:players g) cid (c/active-owned owner)
                                      :let [rule (get-in (c/card cid) [:passive :action-plow])]
                                      :when (and rule (fr/exact-plow-available? player (:exact-cells rule)) (or (nil? (:spaces rule)) ((:spaces rule) space))
                                                 (if (:public? rule)
                                                   (and (not (contains? (get-in owner [:card-state cid :used-by] #{}) pid))
                                                        (or (= pid (:id owner)) (>= (a/amount player :food) 2)))
                                                   (and (= pid (:id owner)) (or (:unlimited? rule) (pos? (get-in owner [:card-state cid :uses] 0))))))] cid))))
       :plow-maker? (and (some #(get-in (c/card %) [:passive :plow-maker]) (c/active-owned player))
                        (pos? (a/amount player :food)))})))
(defn take-action-with-plowing [g pid {:keys [space] :as command}]
  (let [options (plow-options g pid space)]
    (if (and (or (seq (:plows options)) (:plow-maker? options)) (nil? (:plow-plan g)))
      (do
        (validate-space g pid space)
        (a/require! (not-any? #(get command %) [:card :improvement :master-hora? :animal-brander? :part-time?]) "That option is unavailable on this plowing action.")
        (when (some? (:cell command)) (a/place-tile (p g pid) :field (:cell command)))
        ;; Choices remain uncommitted until the complete plow/sow placement is legal.
        (a/require! (or (try (take-action-with-sowing g pid command) true
                            (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false))
                        (some (fn [cell]
                                (try (take-action-with-sowing
                                      (assoc g :plow-plan {:player pid :space space :cells [cell] :card nil}) pid command) true
                                     (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false)))
                              (range 15)))
                    "Choose a legal action before using a plow.")
        (queue-front g [(task pid (or (first (:plows options))
                                     (first (filter #(get-in (c/card %) [:passive :plow-maker]) (c/active-owned (p g pid)))))
                             (merge options {:op :wheel-plow :optional? true
                                             :source-command (select-keys command [:space :cell :plantings :lasso? :shaving-horse?])}) {})]))
      (take-action-with-sowing g pid command))))
(defn freshman-source? [g pid command]
  (and (freshman-available? g pid)
       (or (#{:sow-bake :side-job} (:space command))
           (and (#{:improvement :renovate :meeting :growth} (:space command)) (:oven (a/improvements (:improvement command)))))))
(defn resume-freshman-source [g pid effect choice]
  (let [source (:source-command effect) cid (:card choice)
        _ (when cid (a/require! (freshman-available? g pid) "Freshman has already been used this turn.")
                    (a/require! (= :occupation (:kind (c/card cid))) "Choose an occupation instead of baking.")
                    (paid-card-player g pid cid 0 choice))
        grain (if cid 0 (:grain choice 0))
        _ (a/require! (a/natural? grain) "Choose a nonnegative number of grain to bake.")
        command (merge (assoc source :bake-grain grain :cook-with-bake (if cid {} (:cook-with-bake choice))) (select-keys choice [:use-beehive?]))
        planned (assoc g :freshman-plan {:player pid :space (:space source) :card cid :sow-before? (:sow-before? choice)
                                         :options (select-keys choice [:paper-maker?])})]
    (try (if (and cid (= :sow-bake (:space source)) (not (:sow-before? choice)))
           (take-action-complete planned pid (assoc command :plantings []))
           (take-action-with-plowing planned pid command))
         (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
           (if (and (nil? cid) (zero? grain))
             (update (dissoc (finish-action-context g false) :freshman-plan) :cancelled-sowing (fnil conj #{}) [pid (:space source) (:round g)])
             (throw error))))))
(defn resume-serf [g pid effect choice]
  (let [resource (:resource choice :grain)
        _ (a/require! (#{:grain :vegetable} resource) "Serf either takes one grain or exchanges one grain for a vegetable.")
        state (-> g (assoc :serf-plan {:player pid})
                  (update-in [:players pid] #(if (= :grain resource) (a/gain % {:grain 1})
                                                 (-> % (a/pay {:grain 1}) (a/gain {:vegetable 1})))))
        source (:source-command effect)
        source (if (and (zero? (:bake-grain source 0)) (not (sowable? (p state pid)))
                        (try (a/bake (p state pid) 1) true (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false)))
                 (assoc source :bake-grain 1) source)]
    (take-action state pid source)))
(defn take-action [g pid {:keys [space] :as command}]
  (cond
    (and (not (:stableman-plan g)) (some true? (c/passives (p g pid) :stables-before-animals))
         (:accumulate (a/action-definition g space))
         (>= (reduce + 0 (vals (select-keys (source-action-goods g command) a/animals))) 2)
         (possible-effect? g (task pid :catalog/original-expansion-wm-37-master-stableman
                                   {:op :build-stables :stable-cost 1 :max-stables 2 :optional? true} {})))
    (do (validate-space g pid space)
        (queue-front g [(task pid :catalog/original-expansion-wm-37-master-stableman
                             {:op :build-stables :stable-cost 1 :max-stables 2 :optional? true :source-command command} {})]))
    (and (= :sow-bake space) (some true? (c/passives (p g pid) :original-serf)) (nil? (:serf-plan g)))
    (let [effect {:op :serf :source-command (select-keys command [:space :lasso? :bake-grain :cook-with-bake :use-beehive? :shaving-horse?])}]
      (validate-space g pid space)
      (a/require! (some (fn [resource] (try (resume-serf g pid effect {:resource resource}) true
                                           (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false))) [:grain :vegetable])
                  "Serf still requires an available Sow or Bake Bread action.")
      (queue-front g [(task pid :catalog/original-base-k-266-serf effect {})]))
    (and (nil? (:freshman-plan g)) (freshman-source? g pid command))
    (do
      (validate-space g pid space)
      ;; Every embedded key is a public action option; never save a game copy.
      (let [source (select-keys command [:space :cell :plantings :stables :improvement :upgrade :discount-resource :renovate-to
                                         :frame? :brushwood? :wood-reed :wood-carver? :clay-reed :clay-supports? :room-options :wood-clay :wood-stone :stonepiler-stone :alternative-first? :frame-materials :grain-mill-oven? :storage-shed-resource :straw-thatched-hut? :headmaster-food :headmaster-entry-food :chocolate-food :lasso? :bake-grain :cook-with-bake :use-beehive? :use-beehive? :shaving-horse? :mummys-boy?])]
        (a/require! (not (:card command)) "Choose either the baking substitution or an improvement from your hand.")
        (a/require! (some (fn [cid] (try (resume-freshman-source g pid {:source-command source} {:card cid}) true
                                       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false)))
                         (get-in g [:players pid :hand :occupation]))
                    "This action cannot be completed with Freshman now.")
        (queue-front g [(task pid (c/id :a097) {:op :freshman-bake :optional? true :source-command source} {})])))
    :else (take-action-with-plowing g pid command)))
(defn card-spaces [g pid]
  (vec (for [owner (:players g) cid (c/active-owned owner)
             :let [kind (:action-space (c/card cid))]
             :when (and kind (or (= pid (:id owner)) (#{:fold-builder :chapel :master-forester :clay-digger :clay-deposit :tavern} kind) (occupied-permission g pid cid)))]
         {:card cid :owner (:id owner) :kind kind})))
(defn card-space-error [g pid cid]
  (let [{:keys [kind owner]} (first (filter #(= cid (:card %)) (card-spaces g pid)))
        rounds (vec (drop (count (a/base-actions (count (:players g)) (:variant g))) (:active g)))]
    (cond
      (nil? kind) "You cannot use this card action space."
      (and (contains? (:occupied g) cid) (not (occupied-permission g pid cid))) "This card action space is occupied."
      (and (= kind :chapel) (not= pid owner) (zero? (a/amount (p g pid) :grain))) "Pay the Chapel owner one grain to use this space."
      (and (= kind :clay-digger) (not= pid owner) (< (a/amount (p g pid) :food) 3)) "Pay the Clay Digger owner three food before taking clay."
      (and (= kind :clay-deposit) (not= pid owner) (< (a/amount (p g pid) :food) 1)) "Pay the Clay Deposit owner one food."
      (and (= kind :master-forester) (not= pid owner) (< (a/amount (p g pid) :food) 2)) "Pay the Master Forester owner two food before taking wood."
      (and (= kind :forest-tallyman) (not-every? #(contains? (:occupied g) %) [:wood :clay])) "Forest and Clay Pit must both be occupied."
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
        base (collect-space-bonus (five/refresh g) pid card-space)
        after
        (case kind
          :greenhouse
          (do (a/require! (and (#{:fences :renovate :vegetable} action) (some #{action} (:active g))) "Choose an already revealed action on Greenhouse Builder.")
              (let [shadow (-> base (update :occupied dissoc action))
                    next (a/apply-action shadow pid (assoc cmd :space action))]
                (cond-> (assoc next :occupied (assoc (:occupied base) card-space pid))
                  (and (= action :renovate) (:card cmd)) (play-card pid (:card cmd) 0 cmd {:major-action? true :major-or-minor? true}))))
          :fold-builder
          (let [base (if (= pid owner) base (-> base (update-in [:players pid] a/pay {:food 1}) (update-in [:players owner] a/gain {:food 1})))]
            (-> base (update-in [:players pid] a/build-fences (:fences cmd) (select-keys cmd [:fence-material]))
                     (update-in [:players pid] a/gain {:sheep 1}) (mark-worker pid card-space)))
          :substitute-teacher
          (do (a/require! (#{:resources :crops} (:bundle cmd)) "Choose all four building resources or both crops.")
              (-> base (update-in [:players pid] a/gain (if (= :resources (:bundle cmd)) {:wood 1 :clay 1 :reed 1 :stone 1} {:grain 1 :vegetable 1})) (mark-worker pid card-space)))
          :bullcatcher (-> base (update-in [:players pid] a/gain {:cattle 1 :food 2}) (mark-worker pid card-space))
          :chapel (-> (if (= pid owner) base
                        (-> base (update-in [:players pid] a/pay {:grain 1}) (update-in [:players owner] a/gain {:grain 1})))
                      (update-in [:players pid :bonus-points] (fnil + 0) 3) (mark-worker pid card-space))
          (:master-forester :clay-digger)
          (do (a/require! (not (or (:card cmd) (:improvement cmd))) "This space only collects its accumulated resources.")
              (let [fee (if (= kind :master-forester) 2 3) resource (if (= kind :master-forester) :wood :clay)]
                (-> (if (= pid owner) base
                        (-> base (update-in [:players pid] a/pay {:food fee}) (update-in [:players owner] a/gain {:food fee})))
                    (update-in [:players pid] a/gain (get-in base [:piles card-space] {resource 0}))
                    (assoc-in [:piles card-space] {resource 0}) (mark-worker pid card-space))))
          (:tavern :clay-deposit)
          (let [points? (= action :points)
                _ (a/require! (and (not (or (:card cmd) (:improvement cmd)))
                                  (or (not points?) (= pid owner))
                                  (or (nil? action) (#{:points :labor :clay} action)))
                              "Only this card's owner may choose its bonus points.")
                state (if (and (= kind :clay-deposit) (not= pid owner))
                        (-> base (update-in [:players pid] a/pay {:food 1}) (update-in [:players owner] a/gain {:food 1})) base)]
            (-> (if points? (update-in state [:players pid :bonus-points] (fnil + 0) 2)
                    (update-in state [:players pid] a/gain (if (= kind :tavern) {:food 3} {:clay 5})))
                (mark-worker pid card-space)))
          :forest-tallyman (-> base (update-in [:players pid] a/gain {:clay 2 :wood 3}) (mark-worker pid card-space)))
        after (apply-pig-catcher g after pid (assoc cmd :space card-space))
        after (begin-action-receipt g after pid (assoc cmd :space card-space))
        after (cond-> after (seq (set/difference (:improvements (p after pid)) (:improvements (p g pid)))) (update-in [:major-builds pid] (fnil inc 0)))]
    (-> (cond-> (record-placement g (record-accumulation after pid card-space) pid card-space cmd)
          (contains? (:occupied g) card-space) (assoc-in [:occupied card-space] (conj (space-owners g card-space) pid)))
        (dissoc :cancelled-sowing) (queue-front (action-events g after pid (assoc cmd :space card-space)))
        (finish-action-context true) with-housing)))
(defn relocation-spaces [g pid context]
  (let [acrobat? (= :catalog/original-base-k-269-acrobat (:relocation-card context))
        pond? (= g-pond-watchman (:relocation-card context))
        targets (cond pond? (filter #(or (and (:accumulate (a/action-definition g %)) (pos? (get-in (a/action-definition g %) [:gain :wood] 0))) (= :master-forester (:action-space (c/card %)))) (concat (:active g) (map :card (card-spaces g pid))))
                      acrobat? [:grain :plow :cultivation] :else [:sow-bake :cultivation])
        frozen (get-in g [:relocations :occupied] #{})]
    (filterv #(and (available-action-space? g pid %)
                   (or (and (not (frozen %)) (not (contains? (:occupied g) %)))
                       (and acrobat? (= % :plow) (= 2 (:worker-index context))
                            (some true? (c/passives (p g pid) :third-person-plow)))
                       (and (not acrobat?) (= % :cultivation) (= :field-warden (occupied-permission g pid %))))) targets)))
(defn relocation-origins [g pid]
  (vec (for [cid [:catalog/original-base-k-269-acrobat :catalog/original-base-k-289-countryman g-pond-watchman]
             :let [player (p g pid) acrobat? (= cid :catalog/original-base-k-269-acrobat)
                   [played-round threshold] (get-in player [:card-state cid :played-at] [0 0])]
             :when (and (some #{cid} (c/active-owned player))
                        (not= (:round g) (get-in player [:card-state cid :used-round])))
             [index source] (map-indexed vector (get-in g [:relocations :workers pid]))
             :when (and (not (contains? (:relocated-workers g #{}) [(:round g) pid index]))
                        (cond (= cid g-pond-watchman) (and (= source :fishing) (pos? (a/amount player :food)))
                              acrobat? (and (= source :traveling) (or (not= played-round (:round g)) (>= index threshold)))
                            :else (#{:grain :vegetable} source)))
             :let [context {:space source :worker-index index :relocation-card cid}]
             :when (seq (relocation-spaces g pid context))]
         context)))
(defn start-relocations [g]
  (let [n (count (:players g))
        g (assoc g :relocations {:occupied (set (keys (:occupied g))) :workers (mapv :worker-spaces (:players g))})]
    (queue-front g (concat (for [offset (range 1 (inc n))
                                :let [pid (mod (+ (:turn g) offset) n)]
                                :when (seq (relocation-origins g pid))]
                            (task pid nil {:op :relocate :optional? true} {}))
                           [(step :finish-relocations)]))))
(defn extra-action-spaces [g pid effect context]
  (case (:mode effect)
    :original-relocation (relocation-spaces g pid context)
    :lazy-sowman
    (when (and (= :work (:phase g)) (= pid (:turn g)) (pos? (:remaining (p g pid))))
      (vec (concat (filter #(and (a/available-space? g %) (not (and (= % :meeting) (contains? (:occupied g) %)))) (:active g))
                   (map :card (card-spaces (assoc g :action-context {:player pid :mode :lazy-sowman}) pid)))))
    :swagman
    (let [target ({:grain :build :build :grain} (:space context))]
      (when (and target (= :work (:phase g)) (= pid (:turn g))
                 (not= [(:round g) (:turn-serial g 0)] (:swagman-used-turn (p g pid)))
                 (a/available-space? g target) (some #{pid} (space-owners g (:space context)))) [target]))
    :minstrel
    (let [rounds (take 4 (drop (count (a/base-actions (count (:players g)) (:variant g))) (:active g)))
          free (vec (remove #(contains? (:occupied g) %) rounds))]
      (when (and (= :return-home (:phase g)) (= 1 (count free))) free))
    []))
(defn remove-worker [g pid space]
  (let [owners (space-owners g space) index (first (keep-indexed #(when (= pid %2) %1) owners))
        remaining (vec (concat (take index owners) (drop (inc index) owners)))]
    (cond (seq remaining) (assoc-in g [:occupied space] remaining)
          (seq (get-in g [:newborn-spaces space])) (-> g (assoc-in [:occupied space] (first (get-in g [:newborn-spaces space])))
                                                        (update :newborn-only (fnil conj #{}) space))
          :else (update g :occupied dissoc space))))
(defn extra-action-state [g pid effect context]
  (let [mode (:mode effect) saved (cond-> {:player pid :mode mode :phase (:phase g) :turn (:turn g)
                                 :remaining (:remaining (p g pid)) :occupied (:occupied g)
                                 :worker-index (:worker-index context)}
                                  (:relocation-card context) (assoc :relocation-card (:relocation-card context)))
        g (assoc g :action-context saved :phase :work :turn pid)]
    (case mode
      :original-relocation (-> g (remove-worker pid (:space context)) (update-in [:players pid :remaining] inc))
      :swagman (-> g (remove-worker pid (:space context)) (update-in [:players pid :remaining] inc)
                   (assoc-in [:players pid :swagman-used-turn] [(:round g) (:turn-serial g 0)]))
      :minstrel (update-in g [:players pid :remaining] inc)
      g)))
(defn finish-action-context [g committed?]
  (if-let [{:keys [player mode phase turn remaining occupied worker-index relocation-card]} (:action-context g)]
    (cond-> (dissoc g :action-context)
      (= mode :original-relocation) (assoc :phase phase :turn turn)
      (and (= mode :original-relocation) committed? (= relocation-card g-pond-watchman)) (update-in [:players player] a/pay {:food 1})
      (and (= mode :original-relocation) committed?)
      (-> (assoc-in [:players player :card-state relocation-card :used-round] (:round g))
          (update :relocated-workers (fnil conj #{}) [(:round g) player worker-index]))
      (and (= mode :original-relocation) (not committed?)) (assoc :occupied occupied)
      (= mode :original-relocation) (assoc-in [:players player :remaining] remaining)
      (or (= mode :minstrel) (and (not committed?) (= mode :swagman)))
      (assoc :phase phase :turn turn :occupied occupied)
      (or (= mode :minstrel) (and (not committed?) (= mode :swagman)))
      (assoc-in [:players player :remaining] remaining)) g))
(defn execute-extra-action [g pid effect context command]
  (let [space (or (:card-space command) (:space command))
        allowed (set (extra-action-spaces g pid effect context))]
    (a/require! (and (map? command) (contains? allowed space)) "Choose an action allowed by this card.")
    (a/require! (or (= :lazy-sowman (:mode effect)) (not (:lasso? command))) "This card effect does not place a new person for Lasso.")
    (when (= :minstrel (:mode effect))
      (a/require! (not-any? #(get command %) [:part-time? :master-hora? :animal-brander? :shaving-horse?])
                  "Minstrel takes only the action effect, without placement bonuses."))
    (let [state (prepare-placement (extra-action-state g pid effect context))]
      (if (:card-space command) (card-action state pid command) (take-action state pid command)))))
(defn record-newborn [g pid]
  (let [player (p g pid) added (- (:newborn player 0) (:tracked-newborns player 0))
        space (peek (:worker-spaces player))]
    (cond-> (assoc-in g [:players pid :tracked-newborns] (:newborn player 0))
      (and (pos? added) space (= :work (:phase g)) (= pid (:turn g)))
      (update-in [:newborn-spaces space] (fnil into []) (repeat added pid)))))
(defn adopt-newborn [g pid]
  (let [space (first (sort (for [[space children] (:newborn-spaces g) :when (some #{pid} children)] space)))
        children (get-in g [:newborn-spaces space])
        index (first (keep-indexed #(when (= pid %2) %1) children))
        g (apply-ratcatcher (assoc-in g [:players pid :tracked-newborns] (:newborn (p g pid) 0)))]
    (if (some? index)
      (let [left (vec (concat (take index children) (drop (inc index) children)))
            g (assoc-in g [:newborn-spaces space] left)]
        (if (and (empty? left) (contains? (:newborn-only g) space))
          (-> g (update :newborn-only disj space) (update :occupied dissoc space)) g)) g)))
(defn ability [g pid {:keys [card resource amount from to] :as cmd}]
  (let [player (p g pid) op (get-in (c/card card) [:ability :op])]
    (a/require! (some #{card} (c/active-owned player)) "You do not own that card.")
    (a/require! (#{:preparation :work :return-home :field-harvest :feeding :breeding :housing :finish-round} (:phase g)) "This ability is unavailable during the current phase.")
    (case op
      :wood-saw (do (a/require! (fr/wood-saw-available? g pid) "Every other player must have a larger family.")
                    (queue-front g [(task pid card {:op :build-rooms :optional? true} {})]))
      :workaholic (let [options (fr/workaholic-options player card)]
                    (a/require! (seq options) "House enough animals to collect a remaining resource pile.")
                    (queue-front g [(task pid card {:op :choose :optional? true :options options} {})]))
      :g-pilgrim-discard (do (a/quantities! (:goods cmd) a/resources) (a/require! (some pos? (vals (:goods cmd))) "Choose resources to return to the supply.") (update-in g [:players pid] a/pay (:goods cmd)))
      :g-animal-farm (run-g-fifteen-effect g {:player pid :card card :effect {:op op}} cmd)
      :tinsmith (let [rate (tinsmith-rates g) n (get cmd :amount 1)]
                  (a/require! (and (a/natural? n) (pos? n)) "Choose a positive amount of clay to exchange.")
                  (update-in g [:players pid] #(-> % (a/pay {:clay n})
                                                   (a/gain {:food (+ n (if (= 2 (:clay rate)) (quot n 2) 0))}))))
      :corn-profiteer (start-corn-sale g pid card)
      :return-animal-loan (return-animal-loan g pid card (:return-animals cmd))
      :claim-goods
      (do (a/require! (claim-available? g player card) "Claim this card only once, while you are the last player with exactly two rooms.")
          (-> g (update-in [:players pid] a/gain (get-in (c/card card) [:ability :goods]))
              (assoc-in [:players pid :card-state card :claimed?] true)))
      :renovate-clay-stone
      (do (a/require! (= :clay (:house player))
                      "This ability renovates a clay hut to stone.")
          (run-effect g (task pid card {:op :renovate} {}) (assoc cmd :renovate-to :stone)))
      :renovate-wood-clay
      (do (a/require! (and (= :wood (:house player)) (trowel-boundary? g)
                           (not= :stone (:renovate-to cmd)))
                      "Builder's Trowel renovates wood to clay between actions; finish the current action first.")
          (run-effect g (task pid card {:op :renovate} {}) (assoc cmd :renovate-to :clay)))
      :borrower-repay (update-in g [:players pid] #(-> % (a/pay {:food 3}) (discard-eligible-begging true)))
      :minder-collect
      (do (a/require! (and (some #{resource} (minder-resources player card))
                           (not= (:round g) (get-in player [:card-state card :collected-round]))) "Take one remaining resource type, once per round.")
          (-> g (update-in [:players pid] a/gain {resource (get-in player [:card-state card :stored-goods resource])})
                (assoc-in [:players pid :card-state card :stored-goods resource] 0)
                (assoc-in [:players pid :card-state card :collected-round] (:round g))))
      :pawn-improvement
      (let [cid (:improvement cmd) points (:points (a/improvement-definition cid))]
        (a/require! (some #{cid} (pawnable-improvements player)) "Choose an unpawned improvement with printed victory points.")
        (-> g (update-in [:players pid :pawned-improvements] (fnil conj #{}) cid) (update-in [:players pid] a/gain {:food (* 3 points)})))
      :light-bonfire
      (let [cell (first (keep-indexed #(when (= card (:card %2)) %1) (:farm player)))]
        (a/require! (some? cell) "The Bonfire marker has already been used.")
        (-> g (assoc-in [:players pid :farm cell] {:type :empty})
              (assoc-in [:players pid :temporary-animal-cooking] {:round (:round g) :rates {:sheep 2 :boar 3 :cattle 4}})
              (assoc-in [:players pid :card-state card :marked-cells] #{})))
      :wine-closet
      (let [round (:round cmd) n (if (and (integer? round) (>= (- round (:round g)) 5)) 2 1)]
        (a/require! (and (integer? round) (< (:round g) round 15) (not= (:round g) (get-in player [:card-state card :used-round]))) "Choose a future round, once this round.")
        (-> g (update-in [:players pid] a/pay {:vegetable 1}) (assoc-in [:players pid :card-state card :used-round] (:round g))
              (schedule pid card {:rounds [round] :marker-goods {:vegetable n} :effect {:op :wine-delivery :amount n}})))
      :backyard-well
      (let [cell (:cell cmd)]
        (a/require! (and (not (get-in player [:card-state card :used?])) (some #{cell} (backyard-well-cells g pid))) "Place the well once on an unused space next to your home.")
        (-> g (assoc-in [:players pid :farm cell] {:type :feature :card card :marker-goods {:stone 1}})
              (assoc-in [:players pid :card-state card :used?] true)
              (schedule pid card {:offsets [1 2 3] :goods {:food 1}})))
      :benefactor
      (let [animal (:animal cmd)]
        (a/require! (and (benefactor-available? g pid card) (some #{animal} a/animals) (pos? (a/amount player animal))) "Return one animal with four pastures to build your one free wooden room.")
        (-> (run-effect (update-in g [:players pid] a/pay {animal 1}) (task pid card {:op :build-rooms :free? true :max-rooms 1} {}) cmd)
            (assoc-in [:players pid :card-state card :room-used?] true)))
      :free-room
      (do (a/require! (free-room-available? player card) "This free room needs the printed house size and material, an empty adjacent space, and its unused room tile.")
          (-> (run-effect g (task pid card {:op :build-rooms :max-rooms 1 :free? true} {}) cmd)
              (assoc-in [:players pid :card-state card :room-used?] true)))
      :exchange-options
      (let [_ (a/require! (exchange-phase-available? g player card) "This exchange is unavailable during this phase.")
            options (get-in (c/card card) [:ability :options]) index (:option cmd)
            times (get cmd :times 1) remaining (exchange-remaining g player card)]
        (a/require! (and (integer? index) (<= 0 index) (< index (count options))) "Choose one of this card's exchanges.")
        (a/require! (and (a/natural? times) (pos? times)) "Choose a positive whole number of exchanges.")
        (when remaining (a/require! (<= times remaining) "This card has no more exchanges available this round."))
        (let [{:keys [cost goods] :as option} (nth options index)
              _ (a/require! (expr/evaluate g pid {} (get option :when true)) "That resource is not available from this card now.")
              scale #(into {} (map (fn [[resource n]] [resource (* times n)]) %))]
          (cond-> (update-in g [:players pid]
                            #(if (or (get-in (c/card card) [:ability :food-conversion?])
                                     (and (enhanced-food-conversion? player) (pos? (:food goods 0)) (seq cost) (not (contains? cost :food))))
                               (-> (a/convert-recipe % (scale cost) (:food (scale goods)) (cond-> (select-keys g [:phase :round]) (enhanced-food-conversion? player) (assoc :recipe-card card)))
                                   (a/gain (dissoc (scale goods) :food)))
                               (-> % (a/pay (scale cost)) (a/gain (scale goods)))))
            remaining (assoc-in [:players pid :card-state card :exchange-round] (:round g))
            remaining (assoc-in [:players pid :card-state card :exchange-used]
                                (+ times (- (get-in (c/card card) [:ability :limit-per-round]) remaining))))))
      :adopt (do (a/require! (and (= :work (:phase g)) (pos? (:newborn player 0))) "Adopt a newborn during the work phase.")
                 (-> g (update-in [:players pid] a/pay {:food 1})
                       (update-in [:players pid :remaining] inc) (update-in [:players pid :newborn] dec) (adopt-newborn pid)))
      :release-stored
      (do (a/require! (stored-goods-collectable? player card)
                      (str "Collect when this card holds at least " (get-in (c/card card) [:ability :minimum-stored] 1) " goods."))
          (if-let [resource (get-in (c/card card) [:ability :partial-resource])]
            (let [available (get-in player [:card-state card :stored-goods resource] 0)
                  n (get cmd :amount available)]
              (a/require! (and (a/natural? n) (pos? n) (<= n available)) "Choose how much stored food to collect.")
              (-> g (update-in [:players pid :card-state card :stored-goods resource] - n)
                    (update-in [:players pid] a/gain {resource n})))
            (run-effect* g (task pid card {:op :release-stored} {}) {})))
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
                                    (>= (get source :count 0) 2) (zero? (get target :count 0)) (not (:blocked-card target))
                                    (or (nil? (:allowed-crop target)) (= (:crop source) (:allowed-crop target))))
                               "Move one crop from a field with at least two to an empty field that allows that crop.")
                   (-> g (update-in (into [:players pid] (conj source-path :count)) dec)
                       (assoc-in (into [:players pid] target-path) (assoc target :crop (:crop source) :count 1))))
      (throw (ex-info "This card has no anytime ability." {})))))
(defn prepare-worker-placement [g pid]
  (let [player (p g pid)
        hut? (and (new-person-placement? g) (:reed-hut? player) (not (:reed-hut-placed? player)) (= 1 (:remaining player)))]
    (assoc-in g [:players pid :reed-hut-acting?] (boolean hut?))))
(defn prepare-placement [g]
  (cond-> (prepare-worker-placement g (:turn g))
    (not (get-in g [:lasso :second?])) (dissoc :turn-construction)))
(defn committed-action? [g pid]
  (or (= pid (:sow-in-progress g))
      (and (= pid (get-in g [:freshman-plan :player])) (some? (get-in g [:freshman-plan :card])))))
(defn command [g pid {:keys [type] :as cmd}]
  (a/require! (not= :finished (:phase g)) "This game has finished.")
  (a/require! (not= :draw (:phase g)) "Complete the draw first.")
  (a/require! (or (harvest-participant? g pid) (not (#{:ability :convert} type))) "Layabout skips this harvest, including conversions and card abilities.")
  (a/require! (not (and (committed-action? g pid) (#{:ability :convert} type))) "Finish the committed action before converting goods or using another ability.")
  (a/require! (or (empty? (:effect-queue g)) (#{:resolve :order-effect :ability :convert} type)) "Resolve the pending card effect first.")
  (a/require! (or (#{:ability :convert} type) (= pid (acting-player g))) "It is another player's turn or card decision.")
  (let [result
        (case type
          :order-effect (reorder-effect g pid (:card cmd))
          :resolve (resolve-choice g pid cmd)
          :action (take-action (prepare-placement g) pid cmd)
          :card-action (card-action (prepare-placement g) pid cmd)
          ;; Hayward places fences without taking a fencing action. Check state
          ;; milestones here, but do not synthesize action triggers from deltas.
          :ability (let [after (ability g pid cmd)]
                     (when *impact-observer*
                       (*impact-observer* g after {:player pid :card (:card cmd) :effect {:op :ability}} cmd))
                     (-> (note-gain-receipts g after (:kind (c/card (:card cmd)))) (queue-front [(step :check-all)]) with-housing))
          :feed (let [next (a/feed g pid cmd)
                      after-feed? (some #(some (fn [t] (= :after-feed (:event t))) (:triggers (c/card %))) (c/active-owned (p next pid)))
                      breeding? (= :breeding (:phase next))
                      next (cond-> next (and after-feed? breeding?) (assoc :phase :feeding))]
                  (queue-front next
                    (concat (when after-feed? [(event :after-feed pid)])
                            (when (and after-feed? breeding?) [(step :resume-breeding)])
                            (when breeding?
                              (for [player (:players next)
                                    :when (and (not (contains? (:harvest-skipped next #{}) (:id player)))
                                               (some #(some (fn [t] (= :breeding-start (:event t))) (:triggers (c/card %)))
                                                     (c/active-owned player)))]
                                (event :breeding-start (:id player)))))))
          :breed (let [next (a/breed g pid cmd)
                       next (if (and (c/has? (p g pid) g-bountiful) (>= (:g-harvest-count (p g pid) 0) 2)
                                     (some #(> (a/amount (p next pid) %) (a/amount (p g pid) %)) a/animals))
                              (update-in next [:players pid :card-state g-bountiful :stored-goods :food] (fnil inc 0)) next)]
                   (if (= :finish-round (:phase next))
                     (queue-front next (concat [(event :check-state pid)]
                                               (map #(event :harvest-end (:id %)) (:players next)) [(step :after-harvest)]))
                     (queue-front next [(event :check-state pid)])))
          :convert (do (a/require! (conversion-phases (:phase g)) "Food conversion is unavailable during this phase.")
                       (a/require! (some pos? (vals (:goods cmd))) "Choose goods to convert.")
                       (update-in g [:players pid] a/convert-food (:goods cmd) (select-keys g [:phase :round])))
          (throw (ex-info "Unknown card-game command." {})))]
    (drain (cond-> result (#{:ability :convert} type)
             (as-> state (reactive/note-effect g (dissoc state :cancelled-sowing) {:player pid :card (:card cmd) :effect {:op type}}))))))
