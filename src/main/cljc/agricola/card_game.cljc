(ns agricola.card-game
  "Card-game orchestration around the shared farm rules. Effects and decisions
   are data, so interrupted choices survive saves and reconnects."
  (:require [agricola.engine :as a] [agricola.card-rules :as c] [agricola.five-player :as five] [agricola.card-expressions :as expr] [agricola.card-reactive :as reactive] [agricola.card-random :as random] [clojure.set :as set]))
(declare drain run-effect play-card begin-round advance finish-game take-action take-action-complete staged-sowing? action-events take-action-with-sowing)
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
      :accommodated-taken (let [taken (select-keys (:taken context) a/animals)]
                            (and (not (get-in p [:card-state (c/id :a017) :declined-pile?])) (some pos? (vals taken)) (some? (a/animal-housing p))
                                 (every? (fn [[animal n]] (>= (a/amount p animal) n)) taken)))
      :childless (and (>= (count (a/tiles p :room)) 3) (= 2 (:family p)))
      :only-two-rooms (and (= 2 (count (a/tiles p :room))) (every? #(or (= pid (:id %)) (> (count (a/tiles % :room)) 2)) (:players g)))
      :building-resources (>= (reduce + (for [r [:wood :clay :reed :stone]] (- (get-in p [:received r] 0) (get-in p [:work-baseline r] 0)))) x)
      :three-fields (and (pos? (crop-fields p :grain)) (pos? (crop-fields p :vegetable)) (some #(zero? (or (:count %) 0)) (fields p)))
      false)))
(defn prerequisites? [g pid card]
  (let [requirement (if (contains? card :requires) (:requires card) (expr/requirement (:prerequisites card)))]
    (and (some? requirement) (boolean (expr/evaluate g pid {} requirement)))))
(defn can-pay? [p cost] (every? (fn [[r n]] (>= (a/amount p r) n)) cost))
(defn animal-accommodation-tasks [g pid context]
  (when (some pos? (vals (select-keys (:taken context) a/animals)))
    (concat
     (when (and (owned? g pid :a017) (not (get-in g [:players pid :card-state (c/id :a017) :triggered?])))
       [(task pid (c/id :a017) {:op :housing} (assoc context :reclamation? true))])
     [(event :animals-accommodated pid context)])))
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
                         :double-turn-plow (cond-> {:grain 1} (> (:round g) 3) (assoc :food 1))
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
          after (cond-> [(event (if (= kind :occupation) :occupation :build-improvement) pid {:card cid :major-or-minor? (boolean (:major-or-minor? timing))})
                          (event :check-state pid)]
                  (and (= :round-start (:event timing)) (:scholar-round-start? card))
                  (conj (event :round-start pid {:only-card cid :existing-window (:window timing)}))
                  (:passing? card) (conj {:kind :pass-card :player pid :card cid}))]
      (-> g (queue-front (concat (map #(task pid cid % {}) (:play card)) after))
          (a/log-event (str (:name player) " played " (:name card))))))))
(defn granted-action-cost [g pid effect choice]
  (if (:card choice)
    (card-cost g pid (c/card (:card choice)) (get effect :occupation-cost 0) choice)
    (a/improvement-cost (p g pid) (:improvement choice) (:upgrade choice) {:cost (:major-cost effect)})))
(defn granted-card-action [g pid effect choice]
  (let [cid (:card choice) major (:improvement choice) kinds (:kinds effect)
        kind (if cid (:kind (c/card cid)) :major)
        _ (a/require! (and (not (and cid major)) (or cid major) (contains? kinds kind)) "Choose a card allowed by this action.")
        _ (when (and major (:major-ids effect))
            (a/require! (contains? (:major-ids effect) major) "That major improvement is not allowed by this card."))
        cost (granted-action-cost g pid effect choice)
        _ (a/require! (every? (fn [[resource n]] (>= (get cost resource 0) n)) (:minimum-paid effect))
                      "This improvement action requires the specified resources to actually be paid.")
        both? (= #{:major :minor} kinds)]
    (if cid
      (play-card g pid cid (get effect :occupation-cost 0) choice {:major-action? (contains? kinds :major) :major-or-minor? both?})
      (let [context {:improvement major :paid cost :upgraded-fireplace? (boolean (:upgrade choice))}
            after (a/build-improvement g pid choice {:cost (:major-cost effect)})]
        (-> (cond-> after (= 5 (count (:players g))) (update-in [:major-builds pid] (fnil inc 0)))
            (queue-front (concat [(event :build-improvement pid context)]
                                 (when (= 5 (count (:players g))) [(event :major-built pid context)])
                                 (when (pos? (get choice :bake-grain 0)) [(event :bake pid context)])
                                 [(event :check-state pid)])))))))
(def building-resources #{:wood :clay :reed :stone})
(defn pile-good-options [g effect]
  (vec (for [space (sort (:active g))
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
    (if (= :cultivation (:space command))
      (a/plow-action-player g pid (:space command) (:cell command)) player)))
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
             (update (dissoc g :plow-plan) :cancelled-sowing (fnil conj #{}) [pid (:space command) (:round g)])))
      (queue-front g [(event :sow-declined pid {:unconditional? (:unconditional? effect)})]))))
(defn optional-effect? [g {:keys [player effect]}]
  (and (:optional? effect)
       (or (nil? (:mandatory-at effect))
           (< (a/amount (p g player) (:resource effect)) (:mandatory-at effect)))))
(defn needs-input? [g {:keys [player effect] :as head}]
  (or (optional-effect? g head)
      (and (= :pay-or-beg (:op effect)) (< (a/amount (p g player) :food) (:food effect)))
      (#{:paper-knife :ability-window :choose :plow :plow-many :wheel-plow :mini-pasture :stable :choose-good :bake :bake-one :take-field-crop :sow-offer :sow :housing :cottage :scholar} (:op effect))
      (and (= :seasonal-crop (:op effect)) (>= (:round g) 6))))
(defn run-effect* [g {:keys [player card effect context window] :as head} choice]
  (let [pid player current (p g pid) op (:op effect) cost (expr/quantities g pid context (or (:cost effect) {}))]
    (if (:skip? choice)
      (do (a/require! (optional-effect? g head) "This effect is mandatory.")
          (case op
            :sow-offer (finish-without-sowing g pid effect)
             :wheel-plow (try (take-action (assoc g :plow-plan {:player pid :space (get-in effect [:source-command :space]) :cells []}) pid (:source-command effect))
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _
                               ;; An anytime construction may invalidate the plan.
                               ;; Declining then cancels the uncommitted placement.
                               (update (dissoc g :plow-plan) :cancelled-sowing (fnil conj #{})
                                       [pid (get-in effect [:source-command :space]) (:round g)])))
            g))
      (let [deferred-sidekick? (and (= op :sidekick) (staged-sowing? g pid (five/stage-left g (:space context))))
            g (if deferred-sidekick? g (update-in g [:players pid] a/pay cost))]
        (case op
          :ability-window g
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
          :card-action (granted-card-action g pid effect choice)
          :grow (-> g (update-in [:players pid] a/grow (:room-required? effect))
                    (queue-front [(event :grow pid) (event :check-state pid)]))
          :build-rooms
          (let [rooms (:rooms choice)]
            (a/require! (and (sequential? rooms) (seq rooms)) "Choose at least one room to build.")
            (update-in g [:players pid] a/build-rooms rooms choice {:room-discount (:room-discount effect)}))
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
            (a/sow (sowing-player g pid effect) plantings)
            (let [g (dissoc g :sow-in-progress)]
              (if-let [command (:source-command effect)]
                (complete-sow-placement g pid effect (cond-> (assoc command :plantings plantings)
                                             (and (= :sow-bake (:space command)) (contains? choice :bake-grain)) (assoc :bake-grain (:bake-grain choice))))
                (-> g (update-in [:players pid] a/sow plantings)
                    (queue-front [(event :sow pid {:unconditional? (:unconditional? effect) :vegetables-sown (count (filter #(= :vegetable (:crop %)) plantings))})
                                  (event :check-state pid)])))))
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
              (update-in g [:players pid] #(-> % (a/pay {(:resource effect) 1}) (a/gain {:food (:food effect)}))))
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
          :plow-many
          (let [cells (:cells choice)]
            (a/require! (and (sequential? cells) (<= 1 (count cells) (:max-fields effect))) "Choose one or two fields to plow.")
            (update-in g [:players pid] #(reduce (fn [player cell] (a/place-tile player :field cell)) % cells)))
          :wheel-plow
          (let [cells (:cells choice) command (:source-command effect)]
            (a/require! (and (sequential? cells) (<= 1 (count cells) 2)) "Choose one or two additional fields, or skip Wheel Plow.")
            (a/require! (pos? (get-in g [:players pid :card-state card :uses] 0)) "Wheel Plow has already been used.")
            (let [planned (assoc g :plow-plan {:player pid :space (:space command) :cells (vec cells)})]
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
          :mini-pasture (let [cell (:cell choice)]
                          (a/require! (a/cell? cell) "Choose a farmyard space.")
                          (let [edges (vec (remove (:fences current) (a/edges cell)))]
                            (update-in g [:players pid] a/build-fences edges {:free-fences (count edges)})))
          :bake (do (a/require! (and (integer? (:grain choice)) (pos? (:grain choice))) "Bake at least one grain, or skip this effect.")
                    (-> g (update-in [:players pid] a/bake (:grain choice)) (queue-front [(event :bake pid)])))
          :bake-one
          (let [improvement (:improvement choice) rate (get-in a/improvements [improvement :bake])]
            (a/require! (and (contains? (:improvements current) improvement) (pos? (or rate 0))) "Choose one of your baking improvements.")
            (a/require! (= 1 (get choice :grain 1)) "This effect converts exactly one grain.")
            ;; Winnowing Fan uses a baking rate, but grants no Bake Bread action.
            (update-in g [:players pid] #(-> % (a/pay {:grain 1}) (a/gain {:food rate}))))
          :take-field-crop
          (let [cell (:cell choice) path (a/field-path cell) field (get-in current path) crop (:crop effect)
                exchange (:exchange effect)]
            (a/require! (and (= :field (:type field)) (= crop (:crop field)) (pos? (get field :count 0))) "Choose a planted field with the required crop.")
            (a/require! (or (not (:exchange? choice)) exchange) "This card does not offer an exchange.")
            ;; Taking or moving a crop is not harvesting it, even when the field empties.
            (cond-> (-> g (update-in (into [:players pid] (conj path :count)) dec)
                         (update-in [:players pid] a/gain {crop 1}))
              (:exchange? choice) (update-in [:players pid] #(-> % (a/pay {crop 1}) (a/gain {:food (:food exchange)})))
              (:exchange? choice) (update-in [:players pid :bonus-points] (fnil + 0) (:points exchange))))
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
          :silage
          (let [source (:grain-source choice) animal (:animal choice)]
            (a/require! (some #{source} (silage-grain-sources current)) "Choose one grain from your supply or a grain field.")
            (a/require! (and ((set a/animals) animal) (>= (a/amount current animal) 2)) "Choose one species with at least two animals.")
            (let [paid (if (= source :supply) (a/pay current {:grain 1})
                           (update-in current (conj (a/field-path source) :count) dec))
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
                after (take-action (assoc g :turn pid :effect-queue []) pid cmd)
                after (cond-> after deferred-sidekick?
                        (update-in [:effect-queue 0 :effect] assoc :skip-advance? true :placement-cost cost))]
            ;; Preserve the parent's one turn-advance continuation, after this action's effects.
            (assoc after :turn turn :effect-queue
                   (vec (concat (remove #(and (= :continue (:kind %)) (= :advance (:step %))) (:effect-queue after)) queue))))
          (five/automatic g pid card op context))))))

(defn run-effect [g {:keys [player effect context] :as head} choice]
  (a/require! (or (:skip? choice) (five/eligible? g player (:op effect) context)) "This card effect is not available now.")
  (let [before (count (a/tiles (p g player) :field)) after (run-effect* g head choice)
        plowed (- (count (a/tiles (p after player) :field)) before)
        fenced? (not= (:fences (p g player)) (:fences (p after player)))
        stables (- (count (filter :stable (:farm (p after player)))) (count (filter :stable (:farm (p g player)))))
        rooms (- (count (a/tiles (p after player) :room)) (count (a/tiles (p g player) :room)))
        room-context {:rooms-built rooms :rooms-before (count (a/tiles (p g player) :room))
                      :family-before (:family (p g player)) :previous-house (:house (p g player))}]
    (cond-> after
      (and (not (:source-command effect)) (not= :sidekick (:op effect)) (pos? rooms))
      (queue-front (concat (when (>= rooms 2) [(event :use-farm-spaces player {:used-spaces rooms :action :room})])
                           [(event :build-rooms player room-context) (event :check-state player)]))
      (and (not (:source-command effect)) (not= :sidekick (:op effect)) (pos? stables) (= :work (:phase g)))
      (queue-front [(event :build-stables player {:stables-built stables})])
      (and (= :plow-many (:op effect)) (>= plowed 2))
      (queue-front [(event :use-farm-spaces player {:used-spaces plowed :action :field})])
      (and (not (:source-command effect)) (pos? plowed) (not (#{:plowsmith :corral-builder :amateur-fencer :sidekick} (:op effect))))
      (queue-front [(event :plow player {:fields-plowed plowed})])
      (and (not (:source-command effect)) fenced? (= :mini-pasture (:op effect)))
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
                  (= :loppers op) [{}]
                  (= :silage op) (silage-choices (p g player))
                  (= :bake op) [{:grain 1}]
                  (= :grow op) [{}]
                  (= :build-rooms op) (for [rooms (room-plans (p g player)) frame? [false true] brushwood? [false true]]
                                       {:rooms rooms :frame? frame? :brushwood? brushwood?})
                  (= :take-pile-good op) (pile-good-options g effect)
                  (= :renovate op) [{} {:renovate-to :stone}]
                  (= :bake-one op) (map #(hash-map :improvement %) (:improvements (p g player)))
                  (= :take-field-crop op) (map #(hash-map :cell %) (concat (a/tiles (p g player) :field) (keys (:card-fields (p g player)))))
                  :else nil)]
    (and (five/eligible? g player op (:context head))
         (or (not= :adopt op) (and (= :work (:phase g)) (pos? (:newborn (p g player) 0))))
         (or (not (optional-effect? g head)) (some? conversion))
         (or (not= :threshold-exchange op) (>= (a/amount (p g player) (:resource effect)) (:minimum effect)))
         (or (not= :sow-offer op) (:source-command effect)
             (sowing-potential? g player effect))
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
    :free-placement-fences (and (pos? (a/fences-available player)) (pos? (a/amount player :wood)))
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
    :work (-> g (assoc :phase :work) (update :players #(mapv (fn [p] (assoc p :work-baseline (:received p) :used-accumulations {:round (:round g) :by-good {}})) %))
              (queue-front (map #(event :work-start (:id %)) (:players g))))
    :return-home (queue-front (assoc g :phase :return-home) (concat (map #(event :return-home (:id %)) (:players g)) [(step :harvest-or-round)]))
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
(defn drain [g]
  (loop [g g steps 0]
   (let [g (-> g reactive/sync-farm-cards reactive/sync-shaving reactive/flush-shaving)]
    (a/require! (< steps 1000) "Card effects did not settle.")
    (if-let [head (first (:effect-queue g))]
      (let [rest-state (update g :effect-queue #(vec (rest %)))]
        (case (:kind head)
          :event (let [g (record-construction g head)
                       rest-state (record-construction rest-state head)
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
(defn advance [g]
  (let [n (count (:players g)) pid (:turn g) lasso (:lasso g)
        next (first (filter #(pos? (get-in g [:players % :remaining])) (map #(mod (+ pid %) n) (range 1 (inc n)))))]
    (cond
      (and lasso (not (:second? lasso)) (pos? (get-in g [:players pid :remaining]))) (assoc-in g [:lasso :second?] true)
      (some? next) (-> g (assoc :turn next) (dissoc :lasso :turn-construction))
      :else (-> g (dissoc :lasso :turn-construction) (queue-front [(step :return-home)])))))
(defn begin-round [g]
  (let [replenished? (and (pos? (get-in g [:piles :reed :reed] 0)) (= 1 (get-in (a/action-definition g :reed) [:gain :reed])))
        g (assoc (a/begin-round (dissoc g :turn-construction)) :phase :preparation) r (:round g)
        arrivals (mapcat (fn [p]
                           (for [{:keys [card goods]} (get-in p [:schedule r]) :when (some #{card} (c/owned p))]
                             (task (:id p) card (if (:field goods) {:op :plow :optional? true} {:op :gain :goods goods}) {}))) (:players g))]
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
      :expression (expr/evaluate g pid {} (get-in (c/card cid) [:score :value]))
      :pasture-spaces (count (filter #(<= % (count (a/pasture-cells p))) thresholds))
      :house (get values (:house p) 0)
      :unfenced-stables (count (filter #(and (get-in p [:farm % :stable]) (not ((a/pasture-cells p) %))) (range 15)))
      :improvements (let [n (+ (count (:improvements p)) (count (:minors p))) qualifying (count (filter #(<= % n) thresholds))]
                      (if (zero? qualifying) 0 (nth values (dec qualifying))))
      :animals (quot (a/amount p resource) divisor)
      :tutor (- (count (:occupations p)) (get-in p [:card-state cid :occupations-before] (count (:occupations p))))
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
(defn round-space-number
  "Revealed round-card position, independent of the current round and without
   needing the hidden future round order. Card-created action spaces have none."
  [g space]
  (first (keep-indexed (fn [index id] (when (= space id) (inc index)))
                      (drop (count (a/base-actions (count (:players g)) (:variant g))) (:active g)))))
(defn before-use-player [g pid space]
  (let [round-space (round-space-number g space)]
    (reduce (fn [player cid]
              (if-let [goods (get-in (c/card cid) [:passive :before-use-round-goods round-space])]
                (a/gain player goods) player))
            (p g pid) (c/active-owned (p g pid)))))
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
(defn action-events [before after pid cmd]
  (let [p0 (p before pid) p1 (p after pid) space (:space cmd)
        context {:space space :repeated-accumulation? (repeated-accumulation? before pid space) :previous-house (:house p0) :old-pastures (a/pastures p0)
                 :before-goods (:goods p0)
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
(defn take-action-complete [g pid {:keys [space card lasso?] :as cmd}]
  (validate-space g pid space)
  (a/require! (not (and card (:improvement cmd))) "Choose one major or minor improvement, not both.")
  (when lasso?
    (a/require! (and (owned? g pid :b024) (not (:lasso g))) "Lasso is unavailable.")
    (a/require! (>= (:remaining (p g pid)) 2) "Lasso requires two people available to place.")
    (a/require! (or (#{:sheep :boar :cattle :animal-market} space)
                    (some #(and (some #{%} (:active g)) (not (contains? (:occupied g) %))) [:sheep :boar :cattle :animal-market]))
                "There is no free animal market for the Lasso's second action."))
  (let [occupied (get-in g [:occupied space])
        base (five/refresh g)
        base (cond-> (assoc-in base [:players pid] (before-use-player base pid space)) occupied (update :occupied dissoc space))
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
        after (queue-front (record-accumulation after pid space) (action-events g after pid cmd))
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
    (with-housing (cond-> (dissoc after :cancelled-sowing :plow-plan)
                    (and (= pid (get-in g [:plow-plan :player])) (= space (get-in g [:plow-plan :space]))
                         (#{:plow :cultivation} space) (seq (get-in g [:plow-plan :cells])))
                    (update-in [:players pid :card-state (c/id :a018) :uses] dec)))))
(defn staged-sowing? [g pid space]
  (and (#{:sow-bake :cultivation} space)
       (or (seq (get-in g [:plow-plan :cells])) (owned? g pid :a065) (owned? g pid :a079)
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
      (let [source (select-keys command (cond-> [:space :cell :lasso?] (= space :sow-bake) (conj :bake-grain)))
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
(defn take-action [g pid {:keys [space] :as command}]
  (if (and (wheel-plow-available? g pid space) (nil? (:plow-plan g)))
    (do
      (validate-space g pid space)
      (a/require! (not-any? #(get command %) [:card :improvement :master-hora? :animal-brander? :part-time?]) "That option is unavailable on this plowing action.")
      (when (some? (:cell command)) (a/place-tile (p g pid) :field (:cell command)))
      ;; Probe a legal completion before opening a saved choice. A Cultivation
      ;; source with no base field can still be made legal by the extra fields.
      (a/require! (or (try (take-action-with-sowing g pid command) true
                          (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false))
                      (some (fn [cell]
                              (try (take-action-with-sowing
                                    (assoc g :plow-plan {:player pid :space space :cells [cell]}) pid command) true
                                   (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false)))
                            (range 15)))
                  "Choose a legal action before using Wheel Plow.")
      (queue-front g [(task pid (c/id :a018)
                           {:op :wheel-plow :optional? true
                            :source-command (select-keys command [:space :cell :plantings :lasso?])} {})]))
    (take-action-with-sowing g pid command)))
(defn card-spaces [g pid]
  (vec (for [owner (:players g) cid (c/active-owned owner)
             :let [kind (:action-space (c/card cid))]
             :when (and kind (or (= pid (:id owner)) (#{:fold-builder :chapel} kind)))]
         {:card cid :owner (:id owner) :kind kind})))
(defn card-space-error [g pid cid]
  (let [{:keys [kind owner]} (first (filter #(= cid (:card %)) (card-spaces g pid)))
        rounds (vec (drop (count (a/base-actions (count (:players g)) (:variant g))) (:active g)))]
    (cond
      (nil? kind) "You cannot use this card action space."
      (contains? (:occupied g) cid) "This card action space is occupied."
      (and (= kind :chapel) (not= pid owner) (zero? (a/amount (p g pid) :grain))) "Pay the Chapel owner one grain to use this space."
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
          :forest-tallyman (-> base (update-in [:players pid] a/gain {:clay 2 :wood 3}) (mark-worker pid card-space)))
        after (cond-> after (seq (set/difference (:improvements (p after pid)) (:improvements (p g pid)))) (update-in [:major-builds pid] (fnil inc 0)))]
    (-> after (dissoc :cancelled-sowing) (queue-front (action-events g after pid (assoc cmd :space card-space))) with-housing)))
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
(defn prepare-placement [g]
  (if (get-in g [:lasso :second?]) g (dissoc g :turn-construction)))
(defn command [g pid {:keys [type] :as cmd}]
  (a/require! (not= :finished (:phase g)) "This game has finished.")
  (a/require! (not= :draw (:phase g)) "Complete the draw first.")
  (a/require! (not (and (= pid (:sow-in-progress g)) (#{:ability :convert} type))) "Finish the committed Sow action before converting goods or using another ability.")
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
    (drain (cond-> result (#{:ability :convert} type) (dissoc :cancelled-sowing)))))
