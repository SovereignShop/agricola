(ns agricola.card-game
  "Card-game orchestration around the shared farm rules. Effects and decisions
   are data, so interrupted choices survive saves and reconnects."
  (:require [agricola.engine :as a] [agricola.card-rules :as c] [agricola.five-player :as five] [agricola.card-expressions :as expr] [agricola.card-reactive :as reactive] [agricola.card-random :as random] [clojure.set :as set]))
(declare possible-effect? drain run-effect play-card begin-round advance finish-game take-action take-action-complete staged-sowing? action-events take-action-with-sowing extra-action-spaces execute-extra-action finish-action-context card-spaces card-action record-newborn adopt-newborn freshman-available? resume-freshman-source with-housing plow-options)
(def ^:dynamic *effect-observer*
  "Optional diagnostic sink. It observes committed effects, never legality probes."
  nil)
(defn observe-effect! [head choice]
  (when *effect-observer*
    (*effect-observer* (assoc (select-keys head [:player :card :effect :context :window]) :choice choice))))
(defn p [g pid] (get-in g [:players pid]))
(defn plow-effect
  "Upgrade pre-original-deck Wheel Plow decisions saved without selectable plows."
  [effect]
  (if (and (= :wheel-plow (:op effect)) (not (contains? effect :plows)))
    (assoc effect :plows [(c/id :a018)]) effect))
(defn owned? [g pid code] (c/has? (p g pid) code))
(defn queue-front [g tasks] (update g :effect-queue #(vec (concat tasks %))))
(defn task [pid card effect context] {:kind :effect :player pid :card card :effect effect :context context})
(defn event [kind pid & [context]] {:kind :event :event kind :player pid :context (or context {})})
(defn improvement-finished-event [pid action repeated?]
  (event :improvement-action-finished pid
         {:improvement-action (select-keys action [:kinds :major-ids :major-cost :minimum-paid])
          :minor-action-allowed? (contains? (:kinds action) :minor)
          :merchant-repeat? (boolean repeated?)}))
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
        :when (and (= event (:event response))
                   (case (:scope response) :others (not= owner player) :all true (= owner player))
                   (condition? g owner (:condition response) context))]
    (task owner nil {:op :play-hand-response :card cid :optional? true} (assoc context :actor player))))
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
              (when (and (= event (:event trigger))
                         (case scope :all true :others (not= owner player) (= owner player))
                         (or (nil? spaces) (spaces (:space context))
                             (and (= spaces #{:copse :grove :wood :riverbank})
                                  (= :master-forester (:action-space (c/card (:space context))))))
                         (or (not (:once? trigger)) (not (get-in g [:players owner :card-state cid :triggered?])))
                         (condition? g owner (:condition trigger) context))
                (map (fn [effect]
                       (let [effect (if (:inherit-improvement-action? effect)
                                      (merge (:improvement-action context) effect) effect)]
                         (assoc (task owner cid effect (assoc context :trigger-event event :actor player)) :once? (:once? trigger))))
                     (or (:effects trigger) [(:effect trigger)])))))
          (:triggers (c/card cid)))))
       (c/active-owned (p g owner))))
    (map #(mod (+ (:starting-player g) %) (count (:players g))) (range (count (:players g)))))
           (public-hand-response-tasks g source))))
(defn schedule [g pid cid effect]
  (let [rounds (or (:rounds effect) (map #(+ (:round g) %) (or (:offsets effect) (range 1 (inc (expr/evaluate g pid {} (:offset-count effect))))))) goods (:goods effect)]
    (reduce (fn [g r]
              (if (< (:round g) r 15)
                (update-in g [:players pid :schedule r] (fnil conj []) {:card cid :goods goods}) g)) g rounds)))
(defn remove-lodger-person [player kind]
  (a/require! (#{:adult :newborn} kind) "Choose an adult or newborn person to remove.")
  (a/require! (pos? (if (= kind :newborn) (:newborn player 0) (- (:family player) (:newborn player 0))))
              "There is no person of that kind to remove.")
  (cond-> (-> player (update :family dec) (update :removed-family (fnil inc 0)))
    (= kind :newborn) (update :newborn dec)))
(defn planner-rounds [g] (filterv #(< (:round g) % 15) (map #(+ (:round g) %) [3 6 9])))
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
                                                            (apply merge-with + {} (map #(cond (:stable %) {:stable 1} (:person %) {:person 1} :else (:goods %)) deliveries))))]
                               :when (seq goods)]
                           {:player (:id player) :goods goods :cards (vec (distinct (map :card deliveries)))}))]
              :when (seq owners)]
          [round owners])))
(defn card-cost
  ([g pid card occupation-cost] (card-cost g pid card occupation-cost {}))
  ([g pid card occupation-cost options]
   (let [p (p g pid)
         discount #(a/special-improvement-cost p (:id card)
                     (a/discounted % (a/hand-improvement-discounts p card)) options)]
     (cond
       (= :occupation (:kind card))
       (let [wood (get options :occupation-wood 0)
             total-food (+ occupation-cost (:additional-food-cost card 0))]
         (a/require! (and (a/natural? wood) (<= wood total-food)
                          (or (zero? wood) (c/has? p :a028))) "Forest School can replace each occupation-cost food with one wood.")
         {:food (- total-food wood) :wood wood})
       (:cost-options card)
       (let [costs (mapv discount (:cost-options card))
             index (if (some? (:payment-option options)) (:payment-option options)
                       (or (first (keep-indexed #(when (can-pay? p %2) %1) costs)) 0))]
         (a/require! (and (integer? index) (<= 0 index) (< index (count costs))) "Choose a printed payment option.")
         (nth costs index))
       :else (discount (case (:cost card)
                         :double-turn-plow (cond-> {:grain 1} (> (:round g) 3) (assoc :food 1))
                         :bottles {:clay (:family p) :food (:family p)}
                         (:fishing-person :clay-supply :return-cooking :return-oven :return-two-improvements) {}
                         (:printed-cost card)))))))
(defn card-return-options [player cid]
  (case (:cost (c/card cid))
    :return-cooking (a/returnable-cooking player)
    :return-oven (a/returnable-ovens player)
    :return-two-improvements (sort (a/owned-improvement-ids player))
    nil))
(defn card-return-pairs [player]
  (let [ids (vec (sort (a/owned-improvement-ids player)))]
    (for [i (range (count ids)) j (range (inc i) (count ids))] [(ids i) (ids j)])))
(defn selected-card-returns [player cid options]
  (let [eligible (card-return-options player cid)
        count-needed (case (:cost (c/card cid)) :return-two-improvements 2 (:return-cooking :return-oven) 1 0)
        chosen (case count-needed
                 2 (get options :return-improvements (vec (take 2 eligible)))
                 1 [(or (:return-improvement options) (first eligible))]
                 [])]
    (a/require! (and (sequential? chosen) (= count-needed (count chosen)) (= (count chosen) (count (set chosen)))
                     (every? (set eligible) chosen)) "Return the required number of distinct improvements you own.")
    (vec chosen)))
(defn remove-owned-improvement [player cid]
  ;; Previously earned resources/points remain. Goods stored on discarded cards,
  ;; their future deliveries and their continuing effects leave with the card.
  (cond-> (-> player
              (update :improvements disj cid)
              (update :minors #(vec (remove #{cid} %)))
              (update :card-state dissoc cid)
              (update :card-fields dissoc cid)
              (update :schedule #(into {} (for [[round entries] %
                                                 :let [kept (filterv (fn [entry] (not= cid (:card entry))) entries)]
                                                 :when (seq kept)] [round kept]))))
    (= cid :well) (assoc :well-rounds #{})))
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
    (let [player (reduce remove-owned-improvement player (selected-card-returns player cid options))
          player (if (= :occupation (:kind card))
                   (a/gain player {:food (reduce + 0 (c/passives player :occupation-food))}) player)
          player (if (and (= :occupation (:kind card)) (:paper-maker? options))
                   (do (a/require! (c/has? player :b109) "You do not own Paper Maker.")
                       (-> player (a/pay {:wood 1}) (a/gain {:food (count (:occupations player))}))) player)]
      (let [paid (a/pay player (card-cost g pid card occupation-cost options))]
        (when (:requires-effect? card)
          (let [owned (update paid (if (= :occupation (:kind card)) :occupations :minors) (fnil conj []) cid)
                probe (assoc-in g [:players pid] owned)]
            (a/require! (and (seq (:play card))
                             (every? #(possible-effect? probe (task pid cid % {})) (:play card)))
                        "This card requires an available space and pieces for its printed action.")))
        paid))))
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
          returned (selected-card-returns before cid options)
          major? (and (get-in card [:passive :major-or-minor]) (or (:as-major? options) (:major-only? timing)))
          g (cond-> (assoc-in g [:players pid] player)
                    (seq returned) (update :available-improvements into (filter a/improvements returned))
                    (some #(not (a/improvements %)) returned) (update :removed-cards (fnil into []) (remove a/improvements returned))
                    (seq returned) (update :effect-queue #(filterv (fn [queued] (not (and (= pid (:player queued)) ((set returned) (:card queued))))) %))
                    (and major? (= 5 (count (:players g)))) (update-in [:major-builds pid] (fnil inc 0)))
          after (cond-> [(event (if (= kind :occupation) :occupation :build-improvement) pid {:card cid :major-or-minor? (boolean (:major-or-minor? timing))})
                          (event :check-state pid)]
                  (and major? (= 5 (count (:players g)))) (conj (event :major-built pid {:card cid :improvement cid}))
                  (and (= :round-start (:event timing)) (:scholar-round-start? card))
                  (conj (event :round-start pid {:only-card cid :existing-window (:window timing)}))
                  (:passing? card) (conj {:kind :pass-card :player pid :card cid})
                  (= kind :minor) (conj (improvement-finished-event
                                         pid (or (:improvement-action timing)
                                                 {:kinds (cond (:major-only? timing) #{:major}
                                                               (:major-action? timing) #{:major :minor}
                                                               :else #{:minor})})
                                         (:merchant-repeat? timing))))]
      (cond-> (-> g (queue-front (concat (map #(task pid cid % {}) (:play card)) after))
                   (a/log-event (str (:name player) " played " (:name card))))
        (seq returned) with-housing)))))
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
                        (assoc (select-keys choice [:discount-resource]) :cost (:major-cost effect)))))
(defn granted-card-action [g pid effect choice]
  (let [cid (:card choice) major (:improvement choice) kinds (:kinds effect)
        kind (if cid (:kind (c/card cid)) :major)
        _ (a/require! (and (not (and cid major)) (or cid major)
                          (or (contains? kinds kind)
                              (and cid (get-in (c/card cid) [:passive :major-or-minor]) (contains? kinds :major))
                              (and major (contains? kinds :minor) (a/major-as-minor? (p g pid) major))))
                      "Choose a card allowed by this action.")
        _ (when (:major-ids effect)
            (a/require! (or (contains? (:major-ids effect) (or major cid))
                            (and cid (get-in (c/card cid) [:passive :fireplace])
                                 (seq (set/intersection (:major-ids effect) #{:fireplace-2 :fireplace-3})))) "That major improvement is not allowed by this card."))
        cost (granted-action-cost g pid effect choice)
        _ (a/require! (every? (fn [[resource n]] (>= (get cost resource 0) n)) (:minimum-paid effect))
                      "This improvement action requires the specified resources to actually be paid.")
        both? (= #{:major :minor} kinds)]
    (if cid
      (play-card g pid cid (get effect :occupation-cost 0) choice
                 {:major-action? (contains? kinds :major) :major-only? (= #{:major} kinds) :major-or-minor? both?
                  :improvement-action effect :merchant-repeat? (:merchant-repeat? effect)})
      (let [context {:improvement major :paid cost :upgraded-fireplace? (boolean (:upgrade choice))}
            deferred-bake? (and (:oven (a/improvements major)) (freshman-available? g pid))
            after (a/build-improvement g pid (cond-> choice deferred-bake? (assoc :bake-grain 0 :cook-with-bake {})) {:cost (:major-cost effect)})]
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
      (:allowed-crops effect) (update :goods #(apply dissoc % (remove (:allowed-crops effect) [:grain :vegetable]))))))
(defn sowable? [player]
  (boolean (some (fn [[cell crop]]
                   (try (a/sow player [{:cell cell :crop crop}]) true
                        (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false)))
                 (for [cell (concat (a/tiles player :field) (keys (:card-fields player))) crop [:grain :vegetable]] [cell crop]))))
(defn sowing-potential? [g pid effect]
  (let [grain? (and (:unconditional? effect)
                    (or (owned? g pid :a065)
                        (some #(and (not= pid (:id %)) (c/has? % :a132) (pos? (a/amount % :grain))) (:players g))))]
    (sowable? (cond-> (sowing-player g pid effect) grain? (a/gain {:grain 1})))))
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
(defn animal-loan-choices [player]
  (for [sheep (range (inc (min 1 (a/amount player :sheep))))
        boar (range (inc (min 1 (a/amount player :boar))))
        cattle (range (inc (min 1 (a/amount player :cattle))))]
    {:return-animals {:sheep sheep :boar boar :cattle cattle}}))
(defn return-animal-loan [g pid cid returned]
  (let [player (p g pid) returned (or returned {})]
    (a/require! (not (get-in player [:card-state cid :loan-settled?])) "This animal loan has already been settled.")
    (a/quantities! returned a/animals)
    (a/require! (every? #(<= % 1) (vals returned)) "Return at most one animal of each species.")
    (-> g (update-in [:players pid] a/pay returned)
        (assoc-in [:players pid :card-state cid :loan-settled?] true)
        (assoc-in [:players pid :card-state cid :returned-animals] returned))))
(defn flush-cooking-receipts [g]
  ;; Receipts come only from a successful engine food conversion. Paying an
  ;; animal as a card cost, repaying a loan, and releasing surplus do not cook it.
  (let [events (for [player (:players g) receipt (:cooking-receipts player)
                     cid (:listeners receipt)]
                 (event :convert-food (:id player)
                        {:only-card cid :converted-goods (:goods receipt) :cooking-rates (:rates receipt)
                         :cooked-boar (get-in receipt [:goods :boar] 0)}))]
    (if (seq events)
      (queue-front (update g :players #(mapv (fn [player] (dissoc player :cooking-receipts)) %)) events) g)))
(defn optional-effect? [g {:keys [player effect]}]
  (and (:optional? effect)
       (or (nil? (:mandatory-at effect))
           (< (a/amount (p g player) (:resource effect)) (:mandatory-at effect)))))
(defn needs-input? [g {:keys [player effect] :as head}]
  (or (optional-effect? g head)
      (and (= :pay-or-beg (:op effect)) (< (a/amount (p g player) :food) (:food effect)))
      (#{:return-animal-loan :freshman-bake :classify-fields :paper-knife :lodger-remove :ability-window :choose :plow :plow-many :wheel-plow :mini-pasture :stable :build-rooms :choose-good :bake :bake-one :take-field-crop :sow-offer :sow :housing :cottage :scholar} (:op effect))
      (and (= :seasonal-crop (:op effect)) (>= (:round g) 6))))
(defn run-effect* [g {:keys [player card effect context window] :as head} choice]
  (let [pid player current (p g pid) op (:op effect) cost (expr/quantities g pid context (or (:cost effect) {}))]
    (if (:skip? choice)
      (do (a/require! (optional-effect? g head) "This effect is mandatory.")
          (case op
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
            _ (when (:once-per-turn? effect)
                (a/require! (not= (freshman-turn-key g) (get-in current [:card-state card :last-turn])) "This card was already used this turn."))
            g (cond-> (if deferred-sidekick? g (update-in g [:players pid] a/pay cost))
                (:once-per-turn? effect) (assoc-in [:players pid :card-state card :last-turn] (freshman-turn-key g)))]
        (case op
          :return-animal-loan (return-animal-loan g pid card (:return-animals choice))
          :collect-cooked-animal
          (let [resource (:resource effect) maximum (get-in context [:converted-goods resource] 0)
                amount (get choice :amount maximum)]
            (a/require! (and (a/natural? amount) (pos? amount) (<= amount maximum)) "Collect only markers from the animals just cooked, or skip this effect.")
            (update-in g [:players pid :card-state card :stored-goods resource] (fnil + 0) amount))
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
          :card-action (granted-card-action g pid effect choice)
          :telegram
          (let [round (+ (:round g) (a/fences-available current))]
            (cond-> (assoc-in g [:players pid :card-state card :arrival-round] round)
              (< round 15) (update-in [:players pid :schedule round] (fnil conj []) {:card card :person true})))
          :temporary-person
          (do (a/require! (< (:family current) (a/family-limit current)) "No person remains in your supply.")
              (-> g (update-in [:players pid :family] inc) (update-in [:players pid :remaining] inc)
                    (update-in [:players pid :temporary-family] (fnil inc 0))))
          :lodger-remove (update-in g [:players pid] remove-lodger-person (:person-kind choice))
          :lodger-start
          (assoc-in g [:players pid :card-state card :room-active?]
                    (or (< (:round g) 9) (and (= (:round g) 9) (#{:preparation :work} (:phase g)))))
          :reserve-stables
          (let [rounds (:rounds choice) valid (set (planner-rounds g))]
            (a/require! (and (sequential? rounds) (seq rounds) (= (count rounds) (count (set rounds)))) "Choose different future rounds for your stables.")
            (a/require! (every? valid rounds) "Stable Planner allows only the printed future rounds before game end.")
            (a/require! (<= (count rounds) (a/stables-available current)) "You do not have enough unreserved stable pieces.")
            (reduce (fn [state round] (-> state
                                         (update-in [:players pid :reserved-stables round] (fnil inc 0))
                                         (update-in [:players pid :schedule round] (fnil conj []) {:card card :stable true}))) g rounds))
          :stable-arrival
          (-> g (update-in [:players pid :reserved-stables] dissoc (:round g))
              (queue-front [(task pid card {:op :stable :optional? true} context)]))
          :extra-action (execute-extra-action g pid effect context (:action-command choice))
          :grow (-> g (update-in [:players pid] a/grow (:room-required? effect)) (record-newborn pid)
                    (queue-front [(event :grow pid) (event :check-state pid)]))
          :build-rooms
          (let [rooms (:rooms choice)]
            (a/require! (and (sequential? rooms) (seq rooms)) "Choose at least one room to build.")
            (when (:max-rooms effect) (a/require! (<= (count rooms) (:max-rooms effect)) "This card allows only one room."))
            (update-in g [:players pid] a/build-rooms rooms choice (select-keys effect [:room-discount :free?])))
          :build-stables
          (do (a/require! (seq (:stables choice)) "Choose at least one stable to build.")
              (update-in g [:players pid] a/build-stables (:stables choice) 2))
          :build-fences
          (update-in g [:players pid] a/build-fences (:fences choice) (select-keys choice [:fence-material]))
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
                              (task pid card (assoc effect :op :sow-start :optional? false) context)])))
          :sow-start
          (if (sowable? (sowing-player g pid effect))
            (queue-front g [(task pid card (assoc effect :op :sow :optional? false) context)])
            (finish-without-sowing g pid effect))
          :sow
          (let [plantings (:plantings choice)]
            (a/require! (seq plantings) "Plant at least one field to take this Sow action.")
            (when (:max-fields effect) (a/require! (<= (count plantings) (:max-fields effect)) "This Sow action is limited to one field."))
            (when (:allowed-crops effect)
              (a/require! (every? #((:allowed-crops effect) (:crop %)) plantings) "This card permits sowing only the offered crop."))
            (a/sow (sowing-player g pid effect) plantings)
            (let [g (dissoc g :sow-in-progress)]
              (if-let [command (:source-command effect)]
                (complete-sow-placement g pid effect (cond-> (assoc command :plantings plantings)
                                             (and (= :sow-bake (:space command)) (contains? choice :bake-grain)) (assoc :bake-grain (:bake-grain choice) :cook-with-bake (:cook-with-bake choice))))
                (-> g (update-in [:players pid] a/sow plantings)
                    (queue-front [(event :sow pid {:unconditional? (:unconditional? effect) :vegetables-sown (count (filter #(= :vegetable (:crop %)) plantings))})
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
          :gain (update-in g [:players pid] a/gain (expr/quantities g pid context (:goods effect)))
          :store-goods (update-in g [:players pid :card-state card :stored-goods]
                                 #(merge-with + (or % {}) (expr/quantities g pid context (:goods effect))))
          :release-stored (-> g (update-in [:players pid] a/gain (get-in current [:card-state card :stored-goods] {}))
                              (assoc-in [:players pid :card-state card :stored-goods] {}))
          :take-pile-good
          (let [selection (merge (select-keys effect [:space :resource]) (select-keys choice [:space :resource]))
                {:keys [space resource]} selection]
            (a/require! (some #{selection} (pile-good-options g effect)) "Choose an eligible good from an accumulation space.")
            (-> g (update-in [:piles space resource] dec) (update-in [:players pid] a/gain {resource 1})
                (queue-front (animal-accommodation-tasks g pid {:space space :taken {resource 1}}))))
          :exchange (update-in g [:players pid] a/gain (expr/quantities g pid context (:goods effect)))
          :threshold-exchange
          (do (a/require! (>= (a/amount current (:resource effect)) (:minimum effect)) "You do not have enough crops for this exchange.")
              (cond-> (update-in g [:players pid] #(-> % (a/pay {(:resource effect) 1}) (a/gain {:food (:food effect)})))
                (:wood-group effect) (assoc-in [:players pid :card-state (c/id :a048) :group-consumed] (:wood-group effect))))
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
          :return-goods
          (let [returned (get-in g [:returned-goods window pid] {})]
            (when (:from-taken? effect)
              (a/require! (every? (fn [[r n]] (<= (+ n (get returned r 0)) (get-in context [:taken r] 0))) cost)
                          "Return only goods actually taken from this action space."))
            (-> g (update-in [:players pid] a/gain (expr/quantities g pid context (:goods effect)))
                (update-in [:piles (:space context)] #(merge-with + % cost))
                (cond-> (:from-taken? effect) (update-in [:returned-goods window pid] #(merge-with + % cost)))))
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
          :set-uses (assoc-in g [:players pid :card-state card :uses] (:uses effect))
          :conditional (if (condition? g pid (:condition effect) context)
                         (run-effect g (task pid card (:effect effect) context) choice) g)
          :loom (update-in g [:players pid] a/gain {:food (count (filter #(>= (a/amount current :sheep) %) [1 4 7]))})
          :dairy (update-in g [:players pid] a/gain {:food (+ (quot (a/amount current :sheep) 3) (quot (a/amount current :cattle) 2))})
          :plow-many
          (let [cells (:cells choice)]
            (a/require! (and (sequential? cells) (<= 1 (count cells) (:max-fields effect))) "Choose one or two fields to plow.")
            (when (:limited? effect) (a/require! (pos? (get-in g [:players pid :card-state card :uses] 0)) "This card has no uses left."))
            (cond-> (update-in g [:players pid] #(reduce (fn [player cell] (a/place-tile player :field cell)) % cells))
              (:limited? effect) (update-in [:players pid :card-state card :uses] dec)))
          :wheel-plow
          (let [effect (plow-effect effect) cells (:cells choice) command (:source-command effect)
                available (plow-options g pid (:space command))
                selected (if (contains? choice :plow-card) (:plow-card choice) (first (:plows effect)))
                maker? (true? (:plow-maker? choice))
                maximum (+ (if selected 2 0) (if maker? 1 0))]
            (a/require! (or (nil? selected) (some #{selected} (:plows available))) "Choose one available plow.")
            (a/require! (or (not maker?) (:plow-maker? available)) "Plow Maker is unavailable.")
            (a/require! (and (sequential? cells) (<= 1 (count cells) maximum)) "Choose the additional fields allowed by your plow and Plow Maker.")
            (when (and selected (not= selected (c/id :a018)))
              (a/require! (>= (+ (if (some? (:cell command)) 1 0) (count cells) (if maker? -1 0)) 2)
                          "Use this plow to plow at least two fields, before Plow Maker's extra field."))
            (let [planned (assoc g :plow-plan {:player pid :space (:space command) :cells (vec cells)
                                              :card selected :plow-maker? maker?})]
              (a/plow-action-player planned pid (:space command) (:cell command))
              (take-action planned pid command)))
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
          :mini-pasture (update-in g [:players pid] a/build-mini-pasture (:cell choice) (:fences choice))
          :bake (do (a/require! (and (integer? (:grain choice)) (pos? (:grain choice))) "Bake at least one grain, or skip this effect.")
                    (-> g (update-in [:players pid] a/bake (:grain choice) (:cook-with-bake choice))
                        (queue-front [(event :bake pid {:grain-baked (:grain choice) :bake-source-card card
                                                      :bake-responders (cond-> (set (:bake-responders context)) (:reactive-bake? effect) (conj pid))})])))
          :bake-one
          (let [improvement (:improvement choice) rate (:bake (a/improvement-definition improvement))]
            (a/require! (and (some #{improvement} (a/baking-improvement-ids current)) (pos? (or rate 0))) "Choose one of your baking improvements.")
            (a/require! (= 1 (get choice :grain 1)) "This effect converts exactly one grain.")
            ;; Winnowing Fan uses a baking rate, but grants no Bake Bread action.
            (update-in g [:players pid] #(-> % (a/pay {:grain 1}) (a/gain {:food rate}))))
          :take-field-crop
          (let [cell (:cell choice) path (a/field-path cell) field (get-in current path) crop (:crop effect)
                exchange (:exchange effect)]
            (a/require! (and (= :field (:type field)) (pos? (a/field-crop-count field crop))) "Choose a planted field with the required crop.")
            (a/require! (or (not (:exchange? choice)) exchange) "This card does not offer an exchange.")
            ;; Taking or moving a crop is not harvesting it, even when the field empties.
            (cond-> (-> g (update-in [:players pid] a/remove-field-crop cell crop)
                         (update-in [:players pid] a/gain {crop 1}))
              (:exchange? choice) (update-in [:players pid] #(-> % (a/pay {crop 1}) (a/gain {:food (:food exchange)})))
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
                         next (if (:cook? choice) (a/convert-food current surplus) (a/pay current surplus))]
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
                     (queue-front (update-in g [:players pid] a/renovate choice) [(event :renovate pid {:previous-house (:house current)}) (event :check-state pid)])))
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
            (granted-card-action g pid {:kinds #{:minor}} choice))
          :tag-along
          (let [owners (get-in g [:occupied :market]) owners (if (vector? owners) owners [owners])
                after (-> g (update-in [:players pid :remaining] dec)
                          (update-in [:players pid] a/gain (:gain (a/action-definition g :market)))
                          (assoc-in [:occupied :market] (conj owners pid)))]
            (queue-front after [(event :use-space pid {:space :market}) (step :check-all)]))
          :sidekick
          (let [space (five/stage-left g (:space context)) cmd (assoc (:action-command choice) :type :action :space space)
                turn (:turn g) queue (:effect-queue g)
                after (take-action (assoc g :turn pid :effect-queue []) pid cmd)
                after (cond-> after deferred-sidekick?
                        (update-in [:effect-queue 0 :effect] assoc :skip-advance? true :placement-cost cost))]
            ;; Preserve the parent's one turn-advance continuation, after this action's effects.
            (assoc after :turn turn :effect-queue
                   (vec (concat (remove #(and (= :continue (:kind %)) (= :advance (:step %))) (:effect-queue after)) queue))))
          (five/automatic g pid card op context))))))

(defn run-effect [g {:keys [player effect context] :as head} choice]
  (a/require! (or (:skip? choice) (five/eligible? g player (:op effect) context)) "This card effect is not available now.")
  (let [before (count (a/tiles (p g player) :field)) after (reactive/note-effect g (run-effect* g head choice) head)
        plowed (- (count (a/tiles (p after player) :field)) before)
        fenced? (not= (:fences (p g player)) (:fences (p after player)))
        stables (- (count (filter :stable (:farm (p after player)))) (count (filter :stable (:farm (p g player)))))
        rooms (- (count (a/tiles (p after player) :room)) (count (a/tiles (p g player) :room)))
        room-context {:rooms-built rooms :rooms-before (count (a/tiles (p g player) :room))
                      :family-before (:family (p g player)) :previous-house (:house (p g player))}]
    (cond-> after
      (and (not (:source-command effect)) (not (#{:sidekick :extra-action} (:op effect))) (pos? rooms))
      (queue-front (concat (when (>= rooms 2) [(event :use-farm-spaces player {:used-spaces rooms :action :room})])
                           [(event :build-rooms player room-context) (event :check-state player)]))
      (and (not (:source-command effect)) (not (#{:sidekick :extra-action} (:op effect))) (pos? stables) (= :work (:phase g)))
      (queue-front [(event :build-stables player {:stables-built stables})])
      (and (not (:source-command effect)) (not (#{:sidekick :extra-action} (:op effect))) (pos? stables))
      (queue-front [(event :stables-created player {:stables-built stables})])
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
      (and (not (:source-command effect)) fenced? (#{:mini-pasture :build-fences} (:op effect)))
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
(defn possible-effect? [g {:keys [player effect] :as head}]
  (let [op (:op effect) cost (expr/quantities g player (:context head) (:cost effect))
        conversion (when (optional-effect? g head) (funding-conversion g player cost))
        probe (if (seq conversion) (update-in g [:players player] a/convert-food conversion) g)
        choices (cond
                  (#{:plow :mini-pasture :stable} op) (map #(hash-map :cell %) (range 15))
                  (= :plow-many op) (map #(hash-map :cells [%]) (range 15))
                  (= :wheel-plow op) (cons {:skip? true} (map #(hash-map :cells [%]) (range 15)))
                  (#{:loppers :return-goods} op) [{}]
                  (= :freshman-bake op)
                  (concat (map #(hash-map :card %) (get-in g [:players player :hand :occupation]))
                          [{:grain 1}] (when (:source-command effect) [{:skip? true}]))
                  (= :silage op) (silage-choices (p g player))
                  (= :bake op) [{:grain 1}]
                  (#{:grow :temporary-person} op) [{}]
                  (= :reserve-stables op) (map #(hash-map :rounds [%]) (planner-rounds g))
                  (= :build-stables op) (map #(hash-map :stables [%]) (range 15))
                  (= :build-fences op) (for [cell (range 15) material [:wood :clay]]
                                        {:fences (vec (remove (:fences (p g player)) (a/edges cell))) :fence-material material})
                  (= :build-rooms op) (for [rooms (room-plans (p g player)) options (a/room-payment-plans (p g player) rooms)]
                                       (assoc options :rooms rooms))
                  (= :take-pile-good op) (pile-good-options g effect)
                  (= :renovate op) [{} {:renovate-to :stone}]
                  (= :bake-one op) (map #(hash-map :improvement %) (a/baking-improvement-ids (p g player)))
                  (= :take-field-crop op) (map #(hash-map :cell %) (concat (a/tiles (p g player) :field) (keys (:card-fields (p g player)))))
                  :else nil)]
    (and (five/eligible? g player op (:context head))
         (or (not= :adopt op) (and (= :work (:phase g)) (pos? (:newborn (p g player) 0))))
         (or (not (optional-effect? g head)) (some? conversion))
         (or (not= :extra-action op) (seq (extra-action-spaces g player effect (:context head))))
         (or (not= :threshold-exchange op) (>= (a/amount (p g player) (:resource effect)) (:minimum effect)))
         (or (not= :sow-offer op) (:source-command effect)
             (and (:committed-source effect) (owned? g player :a094))
             (sowing-potential? g player effect))
         (or (nil? choices)
             (some (fn [choice] (try (run-effect probe head choice) true
                                     (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false))) choices)))))
(defn with-housing [g]
  (let [g (five/refresh g) already (set (keep #(when (= :housing (get-in % [:effect :op])) (:player %)) (:effect-queue g)))]
    (queue-front g (remove #(already (:player %)) (housing-tasks g)))))
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
(defn anytime-available?
  ([player cid] (anytime-available? nil player cid))
  ([g player cid]
  (case (get-in (c/card cid) [:ability :op])
    :return-animal-loan (and (not (get-in player [:card-state cid :loan-settled?]))
                              (some #(pos? (a/amount player %)) a/animals))
    :claim-goods (claim-available? g player cid)
    :renovate-wood-clay
    (and (= :wood (:house player)) (or (nil? g) (trowel-boundary? g))
         (boolean (some (fn [options] (try (a/renovate player (assoc options :renovate-to :clay)) true
                                           (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false)))
                        (a/construction-payment-options player))))
    :free-room (free-room-available? player cid)
    :exchange-options (boolean (some #(can-pay? player (:cost %)) (get-in (c/card cid) [:ability :options])))
    :sheep-exchange (pos? (a/amount player :sheep))
    :porcelain (>= (a/amount player :clay) 2)
    :buy-stock (and (seq (get-in player [:card-state cid :stock]))
                    (or (pos? (a/amount player :food))
                        (some (fn [[resource rate]] (and (pos? rate) (pos? (a/amount player resource)))) (a/cook-rates player))))
    :reed-food (pos? (a/amount player :reed))
    :cash-card (pos? (get-in player [:card-state cid :food] 0))
    :free-placement-fences (and (pos? (a/fences-available player)) (pos? (a/amount player :wood)))
    :move-crop (and (some #(>= (:count % 0) 2) (fields player)) (some #(zero? (:count % 0)) (fields player)))
    false)))
(defn anytime-tasks [g]
  (for [offset (range (count (:players g)))
        :let [pid (mod (+ (:starting-player g) offset) (count (:players g))) player (p g pid)]
        :when (some #(anytime-available? g player %) (c/active-owned player))]
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
(defn prepare-return-home [g]
  (reduce
   (fn [state pid]
     (let [player (p state pid) temporary (:temporary-family player 0)
           player (-> player (update :family - temporary) (dissoc :temporary-family))
           expire? (and (= 9 (:round state)) (get-in player [:card-state (c/id :a127) :room-active?]))
           player (cond-> player expire? (assoc-in [:card-state (c/id :a127) :room-active?] false))
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
(defn continue-step [g name]
  (case name
    :advance (advance g)
    :after-harvest (queue-front g (concat (anytime-tasks g) [(step :finish-round)]))
    :finish-round (queue-front g (concat (map #(event :round-end (:id %)) (:players g)) [(step :next-round)]))
    :next-round (if (= 14 (:round g)) (finish-game g) (begin-round g))
    :score-final (finish-game g)
    :work (-> g (assoc :phase :work :first-nonaccumulation nil :newborn-spaces {} :newborn-only #{}) (update :players #(mapv (fn [p] (assoc (a/reset-turn-fence-bonus p) :worker-spaces [] :tracked-newborns 0 :work-baseline (:received p) :used-accumulations {:round (:round g) :by-good {}})) %))
              apply-ratcatcher (queue-front (map #(event :work-start (:id %)) (:players g))))
    :return-home (prepare-return-home (queue-front (assoc g :phase :return-home) (concat (map #(event :return-home (:id %)) (:players g)) [(step :harvest-or-round)])))
    :harvest-or-round (if (a/harvest-rounds (:round g))
                        (queue-front g (concat (map #(event :before-field-phase (:id %)) (:players g)) [(step :field-harvest)]))
                        (queue-front g [(step :finish-round)]))
    :field-harvest (-> g (assoc :phase :field-harvest) (update :players #(mapv a/field-harvest %))
                      (queue-front (concat (mapcat #(harvested-crop-events % (concat (a/tiles % :field) (keys (:card-fields %)))) (:players g))
                                           (map #(event :harvest-crops (:id %) {:harvested-grain-fields (crop-fields % :grain)
                                                                                :harvested-vegetable-fields (crop-fields % :vegetable)}) (:players g))
                                           (map #(event :field-phase (:id %) {:harvested-grain-fields (crop-fields % :grain)
                                                                              :harvested-vegetable-fields (crop-fields % :vegetable)}) (:players g))
                                           (map #(event :after-field-phase (:id %)) (:players g)) [(step :feeding)])))
    :feeding (-> g (assoc :phase :feeding :turn (:starting-player g) :fed #{} :bred #{}) (a/log-event "Harvest · feed each family, then breed animals.")
                 (queue-front (map #(event :feeding-start (:id %)) (:players g))))
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
  (if (not= :occupation event) g
    (let [played (:card context)]
      (reduce
       (fn [state cid]
         (let [owner (p state player)
               amount (if (and (= "Original" (:edition (c/card cid)))
                               (get-in (c/card played) [:passive :academic])) 2 1)
               previous (if (contains? (get-in owner [:card-state cid] {}) :occupations-after)
                          (tutor-points owner cid)
                          (max 0 (- (tutor-points owner cid) (if (some #{played} (:occupations owner)) amount 0))))]
           (assoc-in state [:players player :card-state cid :occupations-after] (+ previous amount))))
       g (filter #(and (not= played %) (= :tutor (get-in (c/card %) [:score :type])))
                 (c/active-owned (p g player)))))))
(defn drain [g]
  (loop [g g steps 0]
   (let [g (-> g flush-cooking-receipts reactive/sync-farm-cards reactive/sync-shaving reactive/flush-shaving)]
    (a/require! (< steps 1000) "Card effects did not settle.")
    (if-let [head (first (:effect-queue g))]
      (let [rest-state (update g :effect-queue #(vec (rest %)))]
        (case (:kind head)
          :event (let [g (-> g (record-construction head) (record-tutor-occupation head))
                       rest-state (-> rest-state (record-construction head) (record-tutor-occupation head))
                       window (or (get-in head [:context :existing-window]) (inc (get g :effect-window 0)))
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
                    (and (= :bake (get-in head [:effect :op])) (freshman-available? g (:player head)))
                    (recur (assoc-in g [:effect-queue 0 :effect] {:op :freshman-bake :original (:effect head) :optional? (get-in head [:effect :optional?])}) (inc steps))
                    (and (not= :classify-fields (get-in head [:effect :op]))
                         (some #(pos? (or % 0)) (vals (expr/classification-options (p g (:player head)) (:effect head)))))
                    (recur (assoc-in g [:effect-queue 0 :effect] {:op :classify-fields :original (:effect head) :optional? (get-in head [:effect :optional?])}) (inc steps))
                    (not (possible-effect? g head))
                    (recur (or (promote-available-effect g head)
                               (a/log-event rest-state (str (or (:name (c/card (:card head))) "Card") " · no legal use of this effect."))) (inc steps))
                    (needs-input? g head) g
                    :else
                      (let [g (run-effect rest-state head {})
                            _ (observe-effect! head {})
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
(defn resolve-choice [g pid choice]
  (let [head (first (:effect-queue g))]
    (a/require! (and (= :effect (:kind head)) (= pid (:player head))) "It is another player's card decision.")
    (a/require! (= (:decision choice) (:decision-id g)) "That decision has already been resolved.")
    (let [next (-> g (update :effect-queue #(vec (rest %))) (run-effect head choice)
                   (update :decision-id (fnil inc 0)))
          next (cond-> next (:once? head) (assoc-in [:players pid :card-state (:card head) :triggered?] true))]
      (observe-effect! head choice)
      (with-housing next))))
(defn reset-turn-fence-bonuses [g]
  (update g :players #(mapv a/reset-turn-fence-bonus %)))
(defn advance [g]
  (let [n (count (:players g)) pid (:turn g) lasso (:lasso g)
        next (first (filter #(pos? (get-in g [:players % :remaining])) (map #(mod (+ pid %) n) (range 1 (inc n)))))]
    (cond
      (and lasso (not (:second? lasso)) (pos? (get-in g [:players pid :remaining]))) (assoc-in g [:lasso :second?] true)
      (some? next) (-> g reset-turn-fence-bonuses (assoc :turn next) (update :turn-serial (fnil inc 0)) (dissoc :lasso :turn-construction))
      :else (-> g reset-turn-fence-bonuses (update :turn-serial (fnil inc 0)) (dissoc :lasso :turn-construction) (queue-front [(step :return-home)])))))
(defn begin-round [g]
  (let [replenished? (and (pos? (get-in g [:piles :reed :reed] 0)) (= 1 (get-in (a/action-definition g :reed) [:gain :reed])))
        g (assoc (a/begin-round (reset-turn-fence-bonuses (dissoc g :turn-construction))) :phase :preparation)
        g (reduce (fn [state cid] (update-in state [:piles cid :wood] (fnil + 0) 2)) g
                  (for [owner (:players g) cid (c/active-owned owner)
                        :when (= :master-forester (:action-space (c/card cid)))] cid))
        r (:round g)
        arrivals (mapcat (fn [p]
                           (for [{:keys [card goods stable person]} (get-in p [:schedule r]) :when (some #{card} (c/owned p))]
                             (task (:id p) card (cond stable {:op :stable-arrival} person {:op :temporary-person :optional? true}
                                                   (:field goods) {:op :plow :optional? true} :else {:op :gain :goods goods}) {}))) (:players g))]
    (queue-front g (concat arrivals (map #(event :round-start (:id %) {:reed-replenished-on-nonempty? replenished? :revealed-space (nth (:round-order g) (dec r))}) (:players g)) [(step :check-all) (step :work)]))))
(defn initialize [g]
  (let [used (set (mapcat #(concat (get-in % [:hand :minor]) (:minors %)) (:players g)))
        reserve (vec (remove used (or (:minor-order g) (sort (map :id (filter #(= :minor (:kind %)) (vals c/cards)))))))]
  (-> (cond-> g (= 5 (count (:players g)))
              (assoc :minor-deck reserve :minor-deck-count (count reserve) :major-builds {}))
      (assoc :card-rng (or (:card-rng g) (random/seed g)))
      (assoc :variant :cards :defer-advance? true :effect-queue [] :decision-id 0 :round 0
             :active (a/base-actions (count (:players g)) :cards) :piles {} :occupied {} :log [])
      (update :players #(mapv (fn [p] (merge {:occupations [] :minors [] :card-state {} :schedule {} :card-fields {} :bonus-points 0} p)) %))
      begin-round drain)))
(defn card-bonus [g pid cid]
  (let [p (p g pid) {:keys [type thresholds values resource divisor]} (:score (c/card cid))]
    (case type
      :unreturned-animals (- (reduce + 0 (vals (get-in p [:card-state cid :returned-animals] {}))) 3)
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
      :full-pastures (count (filter #(and (= :pasture (:kind %)) (= (:capacity %) (:count %))) (a/animal-housing p)))
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
  (let [loans (when-not (:final-scoring-started? g)
                (for [offset (range (count (:players g)))
                      :let [pid (mod (+ (:starting-player g) offset) (count (:players g))) player (p g pid)]
                      cid (c/active-owned player)
                      :when (and (= :return-animal-loan (get-in (c/card cid) [:ability :op]))
                                 (not (get-in player [:card-state cid :loan-settled?])))]
                  (task pid cid {:op :return-animal-loan} {})))]
    (if (seq loans)
      (queue-front (assoc g :phase :scoring :final-scoring-started? true) (concat loans [(step :score-final)]))
      (let [scores (mapv #(score g (:id %)) (:players g)) best (last (sort (map (juxt :total :tiebreak) scores)))
            winners (vec (keep-indexed #(when (= best ((juxt :total :tiebreak) %2)) %1) scores))]
        (-> g (assoc :phase :finished :scores scores :winners winners) (a/log-event "The final harvest is complete."))))))
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
(defn head-family-permission? [g pid space]
  (let [linked (head-family-linked-space g space) spaces (cond-> [space] linked (conj linked))]
    (and (some true? (c/passives (p g pid) :share-family-spaces))
         (or (family-sharing-spaces space) linked)
         (some #(contains? (:occupied g) %) spaces)
         (not-any? #{pid} (mapcat #(space-owners g %) spaces)))))
(defn occupied-permission [g pid space]
  (when (and (not= space :meeting) (or (contains? (:occupied g) space) (head-family-permission? g pid space)))
    (let [player (p g pid) workers (:worker-spaces player)]
      (cond
        (and (= pid (get-in g [:action-context :player]))
             (#{:lazy-sowman :swagman} (get-in g [:action-context :mode]))) :extra-action
        (and (owned? g pid :a028) (#{:lessons :lessons-extra :lessons-copse :lessons-growth} space)) :forest-school
        (head-family-permission? g pid space) :head-of-family
        (and (some true? (c/passives player :share-field-spaces)) (#{:vegetable :plow :cultivation} space)
             (not (some #{pid} (space-owners g space)))) :field-warden
        (and (owned? g pid :a025) (= space (:first-nonaccumulation g)) (= 1 (space-people g space))) :bassinet
        (and (owned? g pid :a130) (>= (count workers) 2) (= space (second workers))
             (some #{pid} (space-owners g space)) (not= (:round g) (:mummy-used-round player))) :mummys-boy
        (and (owned? g pid :a026) (#{:growth :urgent-growth :modest-growth} space)
             (= 1 (count (space-owners g space))) (not= pid (first (space-owners g space)))) :sleeping-corner))))
(defn record-placement [before after pid space]
  (let [mode (get-in before [:action-context :mode])
        newborns (max 0 (- (:newborn (p after pid) 0) (:newborn (p before pid) 0)))
        after (case mode
                :minstrel after
                :swagman (assoc-in after [:players pid :worker-spaces (get-in before [:action-context :worker-index])] space)
                (update-in after [:players pid :worker-spaces] (fnil conj []) space))]
    (cond-> (-> after (update :newborn-only disj space) (assoc-in [:players pid :tracked-newborns] (:newborn (p after pid) 0)))
      (and (not= mode :minstrel) (nil? (:first-nonaccumulation before)) (not (:accumulate (a/action-definition before space)))) (assoc :first-nonaccumulation space)
      (= :mummys-boy (occupied-permission before pid space)) (assoc-in [:players pid :mummy-used-round] (:round before))
      (pos? newborns) (update-in [:newborn-spaces space] (fnil into []) (repeat newborns pid)))))
(defn available-action-space? [g pid space]
  (a/available-space? (if (head-family-permission? g pid space)
                        (update g :occupied dissoc (head-family-linked-space g space)) g) space))
(defn validate-space [g pid space]
  (a/require! (= :work (:phase g)) "Finish the current phase first.")
  (a/require! (= pid (:turn g)) "It is another player's turn.")
  (a/require! (available-action-space? g pid space)
              "That space is not available yet or its linked space is occupied.")
  (a/require! (pos? (:remaining (p g pid))) "No family members remain at home.")
  (let [owner (get-in g [:occupied space])]
    (a/require! (or (nil? owner) (occupied-permission g pid space)) "That action space is occupied."))
  (when (and (= :head-of-family (occupied-permission g pid space)) (:accumulate (a/action-definition g space)))
    (a/require! (some pos? (vals (get-in g [:piles space]))) "Head of the Family cannot use an empty accumulation space."))
  (when (and (:second? (:lasso g)) (:must-animal? (:lasso g)))
    (a/require! (#{:sheep :boar :cattle :animal-market} space) "The second Lasso action must use an animal market.")))
(defn mark-worker [g pid space]
  (-> g (update-in [:players pid :remaining] dec) (assoc-in [:occupied space] pid)
      (a/log-event (str (:name (p g pid)) " · " (or (:name (a/action-definition g space)) (:name (c/card space)))))))
(defn round-space-number
  "Revealed round-card position, independent of the current round and without
   needing the hidden future round order. Card-created action spaces have none."
  [g space]
  (first (keep-indexed (fn [index id] (when (= space id) (inc index)))
                      (drop (count (a/base-actions (count (:players g)) (:variant g))) (:active g)))))
(defn before-use-gains [g pid space]
  (let [round-space (round-space-number g space)]
    (reduce (fn [player cid]
              (if-let [goods (get-in (c/card cid) [:passive :before-use-round-goods round-space])]
                (a/gain player goods) player))
            (p g pid) (c/active-owned (p g pid)))))
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
(defn source-action-goods [g cmd]
  (let [space (:space cmd) action (a/action-definition g space)]
    (cond
      (:accumulate action) (let [pile (get-in g [:piles space])]
                             (if (:part-time? cmd) (into {} (map (fn [[r n]] [r (quot n 2)]) pile)) pile))
      (:gain action) (:gain action)
      (= space :market) (if (= 4 (count (:players g))) {:reed 1 :stone 1 :food 1} {(:choice cmd) 1 :food 1})
      (= space :animal-market) {(:choice cmd) 1}
      :else {})))
(defn action-events [before after pid cmd]
  (let [p0 (p before pid) p1 (p after pid) space (:space cmd)
        context {:space space :repeated-accumulation? (repeated-accumulation? before pid space) :previous-house (:house p0) :old-pastures (a/pastures p0)
                 :before-goods (:goods p0) :before-received (:received p0) :source-goods (source-action-goods before cmd)
                 :worker-index (or (get-in before [:action-context :worker-index]) (count (:worker-spaces p0)))
                 :placed-person (- (:family p1) (:newborn p1 0) (:remaining p1))
                 :building-pile-size (reduce + (map #(get-in before [:piles space %] 0) [:wood :clay :reed :stone]))
                 :upgraded-fireplace? (boolean (:upgrade cmd))
                 :improvement (:improvement cmd)
                 :pile-food (get-in before [:piles space :food] 0)
                 :rooms-before (count (a/tiles p0 :room)) :family-before (:family p0)
                 :rooms-built (- (count (a/tiles p1 :room)) (count (a/tiles p0 :room)))
                 :stables-built (- (count (filter :stable (:farm p1))) (count (filter :stable (:farm p0))))
                 :fences-built (- (count (:fences p1)) (count (:fences p0)))
                 :fields-plowed (- (count (a/tiles p1 :field)) (count (a/tiles p0 :field)))
                 :taken (when (:accumulate (a/action-definition before space))
                          (let [pile (get-in before [:piles space])]
                            (if (:part-time? cmd) (into {} (map (fn [[r n]] [r (quot n 2)]) pile)) pile)))}]
    (concat
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
     (when (and (#{:sow-bake :cultivation} space) (seq (:plantings cmd))) [(event :sow pid {:unconditional? true :vegetables-sown (count (filter #(= :vegetable (:crop %)) (:plantings cmd)))})])
     (when (pos? (:fields-plowed context)) [(event :plow pid context)])
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
       [(improvement-finished-event pid {:kinds (if (#{:meeting :growth} space) #{:minor} #{:major :minor})} false)])
     (when-not (= :minstrel (get-in before [:action-context :mode]))
       [(event :use-space pid context) (event :after-use-space pid context) (event :turn-end pid context) (event :after-action pid context)])
     [(step :check-all)]
     (when-not (:action-context before) [(step :advance)]))))
(defn take-action-complete [g pid {:keys [space card lasso?] :as source-cmd}]
  (let [plan (:freshman-plan g)
        replacement? (and (= pid (:player plan)) (= space (:space plan)) (:card plan))
        cmd (cond-> source-cmd replacement? (assoc :bake-grain 0 :cook-with-bake {}))]
  (validate-space g pid space)
  (a/require! (not (and card (:improvement cmd))) "Choose one major or minor improvement, not both.")
  (when (:improvement cmd)
    (a/require! (or (#{:improvement :renovate} space)
                    (and (#{:meeting :growth} space) (a/major-as-minor? (p g pid) (:improvement cmd))))
                "This action does not allow that supply improvement."))
  (when lasso?
    (a/require! (and (owned? g pid :b024) (not (:lasso g))) "Lasso is unavailable.")
    (a/require! (>= (:remaining (p g pid)) 2) "Lasso requires two people available to place.")
    (a/require! (or (#{:sheep :boar :cattle :animal-market} space)
                    (some #(and (some #{%} (:active g)) (not (contains? (:occupied g) %))) [:sheep :boar :cattle :animal-market]))
                "There is no free animal market for the Lasso's second action."))
  (let [occupied (get-in g [:occupied space])
        head-linked (when (head-family-permission? g pid space) (head-family-linked-space g space))
        base (five/refresh g)
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
                      lasso? (assoc :lasso {:player pid :must-animal? (not (#{:sheep :boar :cattle :animal-market} space)) :second? false}))
        after (queue-front (record-placement g (if (= :minstrel (get-in g [:action-context :mode])) after (record-accumulation after pid space)) pid space) (action-events g after pid cmd))
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
                (do (a/require! minor? "This action allows a minor improvement from your hand.") (play-card after pid card 0 cmd {:major-action? (boolean (#{:improvement :renovate} space)) :major-or-minor? (boolean (#{:improvement :renovate} space))}))
                card (throw (ex-info "That action does not allow playing a hand card." {}))
                :else after)]
    (with-housing (finish-action-context (cond-> (dissoc (if replacement?
                                  (use-freshman
                                   (cond-> after (and (= space :sow-bake) (not (:sow-before? plan)))
                                     (queue-front [(task pid nil {:op :sow-offer :optional? true :unconditional? true
                                                                 :committed-source :sow-bake} {:space :sow-bake})]))
                                   pid (:card plan) (:options plan)) after)
                                 :cancelled-sowing :plow-plan :freshman-plan)
                    (and (= pid (get-in g [:plow-plan :player])) (= space (get-in g [:plow-plan :space]))
                         (#{:plow :cultivation} space) (seq (get-in g [:plow-plan :cells])) (get (:plow-plan g) :card (c/id :a018)))
                    (update-in [:players pid :card-state (get (:plow-plan g) :card (c/id :a018)) :uses] dec)) true)))))
(defn staged-sowing? [g pid space]
  (and (#{:sow-bake :cultivation} space)
       (or (and (= :sow-bake space) (get-in g [:freshman-plan :card]) (get-in g [:freshman-plan :sow-before?]))
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
        (a/require! (and (owned? g pid :b024) (not (:lasso g)) (>= (:remaining (p g pid)) 2)) "Lasso is unavailable.")
        (a/require! (some #(and (some #{%} (:active g)) (not (contains? (:occupied g) %))) [:sheep :boar :cattle :animal-market])
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
         (zero? (- (:family player) (:newborn player 0) (:remaining player))))))
(defn plow-options [g pid space]
  (let [player (p g pid)]
    (if-not (#{:plow :cultivation} space) {:plows [] :plow-maker? false}
      {:plows (vec (concat (when (wheel-plow-available? g pid space) [(c/id :a018)])
                           (sort (for [cid (c/active-owned player)
                                       :when (and (get-in (c/card cid) [:passive :action-plow])
                                                  (pos? (get-in player [:card-state cid :uses] 0)))] cid))))
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
        (queue-front g [(task pid (or (first (:plows options)) :catalog/original-base-e-195-plow-maker)
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
        command (assoc source :bake-grain grain :cook-with-bake (if cid {} (:cook-with-bake choice)))
        planned (assoc g :freshman-plan {:player pid :space (:space source) :card cid :sow-before? (:sow-before? choice)
                                         :options (select-keys choice [:paper-maker?])})]
    (try (if (and cid (= :sow-bake (:space source)) (not (:sow-before? choice)))
           (take-action-complete planned pid (assoc command :plantings []))
           (take-action-with-plowing planned pid command))
         (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
           (if (and (nil? cid) (zero? grain))
             (update (dissoc (finish-action-context g false) :freshman-plan) :cancelled-sowing (fnil conj #{}) [pid (:space source) (:round g)])
             (throw error))))))
(defn take-action [g pid {:keys [space] :as command}]
  (if (and (nil? (:freshman-plan g)) (freshman-source? g pid command))
    (do
      (validate-space g pid space)
      ;; Every embedded key is a public action option; never save a game copy.
      (let [source (select-keys command [:space :cell :plantings :stables :improvement :upgrade :discount-resource :renovate-to
                                         :frame? :brushwood? :clay-reed :clay-supports? :room-options :lasso? :bake-grain :cook-with-bake :shaving-horse? :mummys-boy?])]
        (a/require! (not (:card command)) "Choose either the baking substitution or an improvement from your hand.")
        (a/require! (some (fn [cid] (try (resume-freshman-source g pid {:source-command source} {:card cid}) true
                                       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false)))
                         (get-in g [:players pid :hand :occupation]))
                    "This action cannot be completed with Freshman now.")
        (queue-front g [(task pid (c/id :a097) {:op :freshman-bake :optional? true :source-command source} {})])))
    (take-action-with-plowing g pid command)))
(defn card-spaces [g pid]
  (vec (for [owner (:players g) cid (c/active-owned owner)
             :let [kind (:action-space (c/card cid))]
             :when (and kind (or (= pid (:id owner)) (#{:fold-builder :chapel :master-forester} kind) (occupied-permission g pid cid)))]
         {:card cid :owner (:id owner) :kind kind})))
(defn card-space-error [g pid cid]
  (let [{:keys [kind owner]} (first (filter #(= cid (:card %)) (card-spaces g pid)))
        rounds (vec (drop (count (a/base-actions (count (:players g)) (:variant g))) (:active g)))]
    (cond
      (nil? kind) "You cannot use this card action space."
      (and (contains? (:occupied g) cid) (not (occupied-permission g pid cid))) "This card action space is occupied."
      (and (= kind :chapel) (not= pid owner) (zero? (a/amount (p g pid) :grain))) "Pay the Chapel owner one grain to use this space."
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
        base (five/refresh g)
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
          :master-forester
          (do (a/require! (not (or (:card cmd) (:improvement cmd))) "This space only collects wood.")
              (-> (if (= pid owner) base
                      (-> base (update-in [:players pid] a/pay {:food 2}) (update-in [:players owner] a/gain {:food 2})))
                  (update-in [:players pid] a/gain (get-in base [:piles card-space] {:wood 0}))
                  (assoc-in [:piles card-space] {:wood 0}) (mark-worker pid card-space)))
          :forest-tallyman (-> base (update-in [:players pid] a/gain {:clay 2 :wood 3}) (mark-worker pid card-space)))
        after (cond-> after (seq (set/difference (:improvements (p after pid)) (:improvements (p g pid)))) (update-in [:major-builds pid] (fnil inc 0)))]
    (-> (cond-> (record-placement g (record-accumulation after pid card-space) pid card-space)
          (contains? (:occupied g) card-space) (assoc-in [:occupied card-space] (conj (space-owners g card-space) pid)))
        (dissoc :cancelled-sowing) (queue-front (action-events g after pid (assoc cmd :space card-space)))
        (finish-action-context true) with-housing)))
(defn extra-action-spaces [g pid effect context]
  (case (:mode effect)
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
  (let [mode (:mode effect) saved {:player pid :mode mode :phase (:phase g) :turn (:turn g)
                                 :remaining (:remaining (p g pid)) :occupied (:occupied g)
                                 :worker-index (:worker-index context)}
        g (assoc g :action-context saved :phase :work :turn pid)]
    (case mode
      :swagman (-> g (remove-worker pid (:space context)) (update-in [:players pid :remaining] inc)
                   (assoc-in [:players pid :swagman-used-turn] [(:round g) (:turn-serial g 0)]))
      :minstrel (update-in g [:players pid :remaining] inc)
      g)))
(defn finish-action-context [g committed?]
  (if-let [{:keys [player mode phase turn remaining occupied]} (:action-context g)]
    (cond-> (dissoc g :action-context)
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
    (let [state (extra-action-state g pid effect context)]
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
      :return-animal-loan (return-animal-loan g pid card (:return-animals cmd))
      :claim-goods
      (do (a/require! (claim-available? g player card) "Claim this card only once, while you are the last player with exactly two rooms.")
          (-> g (update-in [:players pid] a/gain (get-in (c/card card) [:ability :goods]))
              (assoc-in [:players pid :card-state card :claimed?] true)))
      :renovate-wood-clay
      (do (a/require! (and (= :wood (:house player)) (trowel-boundary? g)
                           (not= :stone (:renovate-to cmd)))
                      "Builder's Trowel renovates wood to clay between actions; finish the current action first.")
          (run-effect g (task pid card {:op :renovate} {}) (assoc cmd :renovate-to :clay)))
      :free-room
      (do (a/require! (free-room-available? player card) "This free room needs the printed house size and material, an empty adjacent space, and its unused room tile.")
          (-> (run-effect g (task pid card {:op :build-rooms :max-rooms 1 :free? true} {}) cmd)
              (assoc-in [:players pid :card-state card :room-used?] true)))
      :exchange-options
      (let [options (get-in (c/card card) [:ability :options]) index (:option cmd)
            times (get cmd :times 1)]
        (a/require! (and (integer? index) (<= 0 index) (< index (count options))) "Choose one of this card's exchanges.")
        (a/require! (and (a/natural? times) (pos? times)) "Choose a positive whole number of exchanges.")
        (let [{:keys [cost goods]} (nth options index)
              scale #(into {} (map (fn [[resource n]] [resource (* times n)]) %))]
          (update-in g [:players pid] #(-> % (a/pay (scale cost)) (a/gain (scale goods))))))
      :adopt (do (a/require! (and (= :work (:phase g)) (pos? (:newborn player 0))) "Adopt a newborn during the work phase.")
                 (-> g (update-in [:players pid] a/pay {:food 1})
                       (update-in [:players pid :remaining] inc) (update-in [:players pid :newborn] dec) (adopt-newborn pid)))
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
(defn prepare-placement [g]
  (if (get-in g [:lasso :second?]) g (dissoc g :turn-construction)))
(defn committed-action? [g pid]
  (or (= pid (:sow-in-progress g))
      (and (= pid (get-in g [:freshman-plan :player])) (some? (get-in g [:freshman-plan :card])))))
(defn command [g pid {:keys [type] :as cmd}]
  (a/require! (not= :finished (:phase g)) "This game has finished.")
  (a/require! (not= :draw (:phase g)) "Complete the draw first.")
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
    (drain (cond-> result (#{:ability :convert} type)
             (as-> state (reactive/note-effect g (dissoc state :cancelled-sowing) {:player pid :card (:card cmd) :effect {:op type}}))))))
