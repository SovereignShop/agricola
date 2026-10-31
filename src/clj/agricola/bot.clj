(ns agricola.bot
  "A deterministic, bounded one-ply player. Candidate commands are checked by
   the same rules as human commands. The policy sees public information and its
   own hand only; previews stop before advancing the turn or revealing a round."
  (:require [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [agricola.five-player :as five] [agricola.card-expressions :as expression] [clojure.set :as set]))

(def version 1)
(defn attempt [f]
  (try (f) (catch clojure.lang.ExceptionInfo e
             (if (= :illegal-command (:type (ex-data e))) nil (throw e)))))
(defn observation [state pid]
  (-> state
      (update :players #(mapv (fn [p] (if (= pid (:id p)) p (dissoc p :hand))) %))
      (dissoc :minor-order :minor-deck :card-rng)
      (update :round-order #(vec (take (:round state) %)))
      (dissoc :draw :log)))
(defn without-continuations [state]
  (update state :effect-queue #(vec (remove (fn [t] (= :continue (:kind t))) %))))
(defn preview [state pid cmd]
  (binding [g/*effect-observer* nil]
    (attempt
     #(case (:type cmd)
      :action (g/drain (without-continuations (g/take-action (g/prepare-placement state) pid cmd)))
      :resolve (g/drain (without-continuations (g/resolve-choice state pid cmd)))
      :ability (g/drain (g/with-housing (g/ability state pid cmd)))
      :convert (g/command (without-continuations state) pid cmd)
      :feed (a/feed state pid cmd)
      :breed (a/breed state pid cmd)))))
(defn subsets [xs]
  (reduce (fn [out x] (into out (map #(conj % x) out))) [[]] xs))
(defn field-ids [p] (concat (a/tiles p :field) (sort (keys (:card-fields p)))))
(defn payment-options [p] (a/construction-payment-options p))
(defn hand-options [p kind]
  (for [cid (get-in p [:hand kind])
        paper? (if (and (= kind :occupation) (c/has? p :b109)) [false true] [false])
        payment (if-let [costs (:cost-options (c/card cid))] (range (count costs)) [nil])
        wood (if (and (= kind :occupation) (c/has? p :a028)) (range (+ 4 (:additional-food-cost (c/card cid) 0))) [0])
        returned (if (#{:return-cooking :return-oven} (:cost (c/card cid))) (g/card-return-options p cid) [nil])
        returned-pair (if (= :return-two-improvements (:cost (c/card cid))) (g/card-return-pairs p) [nil])
        discount-resource (or (seq (a/improvement-discount-resources p cid)) [nil])
        major? (if (get-in (c/card cid) [:passive :major-or-minor]) [false true] [false])]
    (cond-> {:card cid} paper? (assoc :paper-maker? true) (some? payment) (assoc :payment-option payment)
            returned (assoc :return-improvement returned) returned-pair (assoc :return-improvements returned-pair)
            discount-resource (assoc :discount-resource discount-resource)
            major? (assoc :as-major? true) (pos? wood) (assoc :occupation-wood wood))))
(defn sow-plans [p]
  (distinct
   (cons []
         (for [priority [[:grain :vegetable] [:vegetable :grain]]]
           (:plan
            (reduce (fn [{:keys [player] :as out} cell]
                      (if-let [crop (first (filter #(and (pos? (a/amount player %))
                                                                       (attempt (fn [] (a/sow player [{:cell cell :crop %}])))) priority))]
                        (-> out (update :player a/sow [{:cell cell :crop crop}])
                            (update :plan conj {:cell cell :crop crop})) out))
                    {:player p :plan []} (field-ids p)))))))
(defn fence-plans [p]
  ;; Rectangles cover useful small/large pastures
  ;; without searching the power set of every fence edge.
  (let [covered (a/pasture-cells p)]
   (distinct
   (for [height [1 2] width [1 2 3] row (range (- 4 height)) col (range (- 6 width))
         :let [cells (for [r (range row (+ row height)) c (range col (+ col width))] (+ (* 5 r) c))]
         :when (every? #(and (= :empty (get-in p [:farm % :type])) (not (covered %))) cells)
         :let [boundary (->> cells (mapcat a/edges) frequencies (keep (fn [[e n]] (when (= n 1) e))) set)
               additions (vec (sort (set/difference boundary (:fences p))))]
         :when (seq additions)] additions))))
(defn mini-pasture-choices [p]
  (mapcat (fn [cell]
            (cons {:cell cell}
                  (when (pos? (a/turn-free-fences-available p))
                    (let [base (set (a/edges cell)) staged (update p :fences into base)]
                      (for [extra (fence-plans staged)
                            :when (<= (count extra) (a/turn-free-fences-available p))]
                        {:cell cell :fences extra})))))
          (filter #(and (= :empty (get-in p [:farm % :type])) (not ((a/pasture-cells p) %))) (range 15))))
(defn bake-cooking-options [p grain]
  (cons {} (when (and (a/crock? p) (pos? grain))
             (for [resource (concat a/animals [:vegetable :grain])
                   :when (pos? (get (a/cook-rates p) resource 0))
                   n (range 1 (inc (min 3 (- (a/amount p resource) (if (= resource :grain) grain 0)))))]
               {resource n}))))
(defn with-bake-cooking [p option grain-key]
  (for [goods (bake-cooking-options p (get option grain-key 0))]
    (cond-> option (seq goods) (assoc :cook-with-bake goods))))
(defn major-options [state p]
  (for [cid (sort (:available-improvements state))
        upgrade (if (:upgrade (a/improvements cid))
                  (cons nil (a/fireplace-upgrades p)) [nil])
        discount-resource (or (seq (a/improvement-discount-resources p cid)) [nil])
        grain (if (:oven (a/improvements cid)) (range (inc (min 3 (a/amount p :grain)))) [0])]
    (cond-> {:improvement cid} upgrade (assoc :upgrade upgrade) (pos? grain) (assoc :bake-grain grain)
            discount-resource (assoc :discount-resource discount-resource))))
(defn supply-minor-options [state p]
  (filter #(a/major-as-minor? p (:improvement %)) (major-options state p)))
(defn action-candidates [state pid]
  (let [p (g/p state pid) payments (payment-options p)
        minors (concat (hand-options p :minor) (supply-minor-options state p))
        majors (major-options state p)
        cells (filter #(a/empty-cell? p %) (range 15))
        fences (delay (for [f (fence-plans p) material (if (c/has? p :a016) [:wood :clay] [:wood])]
                       {:fences f :fence-material material}))]
    (mapcat
     (fn [space]
       (let [options
             (case space
               (:lessons :lessons-extra :lessons-copse :lessons-growth) (hand-options p :occupation)
               :meeting (cons {} minors)
               :market [{:choice :reed} {:choice :stone}]
               :plow (map #(hash-map :cell %) cells)
               :house-building (for [cell cells opts (a/room-payment-plans p [cell])] (assoc opts :rooms [cell]))
               :animal-market (map #(hash-map :choice %) a/animals)
               :build (concat (for [cell cells opts (a/room-payment-plans p [cell])] (assoc opts :rooms [cell]))
                              (for [cell (range 15)] {:stables [cell]}))
               :fences @fences
               :sow-bake (for [plantings (if (g/staged-sowing? state pid space) [[]] (sow-plans p)) grain (range (inc (min 8 (a/amount p :grain))))]
                           {:plantings plantings :bake-grain grain})
               :cultivation (for [cell (cons nil cells)
                                  :let [plowed (if cell (attempt #(a/place-tile p :field cell)) p)]
                                  :when plowed plantings (if (g/staged-sowing? state pid space) [[]] (sow-plans plowed))]
                              (cond-> {:plantings plantings} cell (assoc :cell cell)))
               :growth (cons {} minors)
               :improvement (concat majors minors)
               :renovate (for [opts payments improvement (concat [{}] majors minors)] (merge opts improvement))
               :redevelop (for [opts payments fence (cons {} @fences)] (merge opts fence))
               [{}])]
         (for [base options option (with-bake-cooking p base :bake-grain)
               hora (if (and (c/has? p :a174) (five/linked-groups space)) [false true] [false])
               double (if (and (c/has? p :a180) (= space :animal-market)) [false true] [false])
               shave? (if (g/pre-use-shaving? state pid space) [false true] [false])
               part (if (and (c/has? p :d180) (:accumulate (a/action-definition state space))) [false true] [false])]
           (cond-> (merge {:type :action :space space} option) hora (assoc :master-hora? true) double (assoc :animal-brander? true) part (assoc :part-time? true) shave? (assoc :shaving-horse? true)))))
     ;; Sleeping Corner can share Growth; validate-space decides whether it can.
     (filter #(and (not (contains? (:cancelled-sowing state) [pid % (:round state)]))
                   (or (not (contains? (:occupied state) %)) (g/occupied-permission state pid %))) (:active state)))))
(def animal-orders [[:sheep :boar :cattle] [:sheep :cattle :boar] [:boar :sheep :cattle]
                    [:boar :cattle :sheep] [:cattle :sheep :boar] [:cattle :boar :sheep]])
(defn housing-plans [p]
  (distinct
   (for [order animal-orders pairs-first? [false true]]
     (let [empty (assoc p :goods (merge (:goods p) (zipmap a/animals (repeat 0))))
           units (if pairs-first?
                   (concat (mapcat #(repeat (min 2 (a/amount p %)) %) order)
                           (mapcat #(repeat (max 0 (- (a/amount p %) 2)) %) order))
                   (mapcat #(repeat (a/amount p %) %) order))
           kept (reduce (fn [p animal] (let [next (a/gain p {animal 1})] (if (a/animal-housing next) next p))) empty units)]
       (select-keys (:goods kept) a/animals)))))
(defn plow-decision-candidates [p effect]
  (let [base (get-in effect [:source-command :cell])
        initial (if (some? base) (a/place-tile p :field base) p)
        extend (fn [paths]
                 (for [[player cells] paths cell (range 15)
                       :let [next (attempt #(a/place-tile player :field cell))]
                       :when next] [next (conj cells cell)]))
        one (vec (extend [[initial []]])) two (vec (extend one))
        three (delay (take 64 (extend two)))]
    (for [cid (cons nil (:plows effect)) maker? (if (:plow-maker? effect) [false true] [false])
          :let [maximum (+ (if cid 2 0) (if maker? 1 0))]
          [_ cells] (concat (when (pos? maximum) one) (when (>= maximum 2) two) (when (= maximum 3) @three))]
      {:cells cells :plow-card cid :plow-maker? maker?})))
(declare ability-candidates card-action-candidates)
(defn decision-candidates [state pid]
  (let [p (g/p state pid) head (first (:effect-queue state)) effect (g/plow-effect (:effect head))
        options (case (:op effect)
                  :return-animal-loan (g/animal-loan-choices p)
                  :collect-cooked-animal (map #(hash-map :amount %) (range 1 (inc (get-in head [:context :converted-goods (:resource effect)] 0))))
                  (:plow :stable :plowsmith) (map #(hash-map :cell %) (range 15))
                  (:mini-pasture :corral-builder :amateur-fencer) (mini-pasture-choices p)
                  :wheel-plow (plow-decision-candidates p effect)
                  :plow-many (concat (map #(hash-map :cells [%]) (range 15))
                                                 (for [x (range 15) y (range 15) :when (not= x y)] {:cells [x y]}))
                  :paper-knife (let [hand (vec (get-in p [:hand :occupation]))]
                                 (for [i (range (count hand)) j (range (inc i) (count hand)) k (range (inc j) (count hand))]
                                   {:cards [(hand i) (hand j) (hand k)]}))
                  :freshman-bake (concat (if (= :sow-bake (get-in effect [:source-command :space]))
                                           (mapcat #(vector % (assoc % :sow-before? true)) (hand-options p :occupation))
                                           (hand-options p :occupation))
                                         (for [grain (range 1 (inc (min 8 (a/amount p :grain))))] {:grain grain}))
                  :classify-fields (let [{:keys [mixed-fields dual-improvements]} (expression/classification-options p (:original effect))]
                                     (for [grain (range (inc (or mixed-fields 0))) major (range (inc (or dual-improvements 0)))]
                                       {:mixed-as-grain grain :dual-as-major major}))
                  :silage (g/silage-choices p)
                  :sow-offer [{}]
                  :lodger-remove [{:person-kind :adult} {:person-kind :newborn}]
                  :reserve-stables (map #(hash-map :rounds %) (remove empty? (subsets (g/planner-rounds state))))
                  :build-stables (let [cells (filter #(and (= :empty (get-in p [:farm % :type])) (not (get-in p [:farm % :stable]))) (range 15))]
                                   (concat (map #(hash-map :stables [%]) cells)
                                           (for [one cells two cells :when (< one two)] {:stables [one two]})
                                           (for [n [3 4]] {:stables (vec (take n cells))})))
                  :build-fences (for [fences (fence-plans p) material (if (c/has? p :a016) [:wood :clay] [:wood])]
                                  {:fences fences :fence-material material})
                  :build-rooms (for [rooms (g/room-plans p) opts (a/room-payment-plans p rooms)] (assoc opts :rooms rooms))
                  :sow (let [player (g/sowing-player state pid effect)
                             plans (if (:max-fields effect)
                                     (for [cell (field-ids player) crop (or (:allowed-crops effect) [:grain :vegetable])] [{:cell cell :crop crop}])
                                     (remove empty? (sow-plans player)))]
                         (for [plantings plans grain (if (= :sow-bake (get-in effect [:source-command :space]))
                                                      (range (inc (min 8 (a/amount player :grain)))) [0])]
                           {:plantings plantings :bake-grain grain}))
                  :choose (map #(hash-map :option %) (range (count (:options effect))))
                  :take-pile-good (g/pile-good-options state effect)
                  :choose-good (map #(hash-map :resource %) (:choices effect))
                  :seasonal-crop [{:resource :grain} {:resource :vegetable}]
                  :bake (map #(hash-map :grain %) (range 1 (inc (min 8 (a/amount p :grain)))))
                  :bake-one (for [improvement (a/baking-improvement-ids p)] {:improvement improvement})
                  :take-field-crop (for [cell (field-ids p) exchange? (if (:exchange effect) [false true] [false])]
                                     {:cell cell :exchange? exchange?})
                  :housing (for [keep (housing-plans p) cook? [true false]
                                 accommodate? (if (get-in head [:context :reclamation?]) [true false] [true])]
                             {:keep keep :cook? cook? :accommodate-taken? accommodate?})
                  :cottage (concat (for [cell (range 15) opts (payment-options p)] (assoc opts :action :room :cell cell))
                                   (map #(assoc % :action :renovate) (payment-options p)))
                  :boat-painter [{:resource :grain} {:resource :food}]
                  :countryman (for [cell (field-ids p) crop [:grain :vegetable]] {:plantings [{:cell cell :crop crop}]})
                  (:fast-mason :renovate) (payment-options p)
                  :young-artist (cons {:action :draw} (map #(assoc % :action :play)
                                                        (concat (hand-options p :minor) (supply-minor-options state p))))
                  :extra-action (let [context (:context head)
                                      allowed (set (g/extra-action-spaces state pid effect context))
                                      ready (g/extra-action-state state pid effect context)]
                                  (map #(hash-map :action-command %)
                                       (filter #(allowed (or (:card-space %) (:space %)))
                                               (concat (action-candidates ready pid) (card-action-candidates ready pid)))))
                  :sidekick (let [space (five/stage-left state (get-in state [:effect-queue 0 :context :space]))]
                              (map #(hash-map :action-command %) (action-candidates (assoc state :active [space] :turn pid) pid)))
                  :card-action (concat (when (contains? (:kinds effect) :minor) (hand-options p :minor))
                                       (when (and (contains? (:kinds effect) :minor) (not (contains? (:kinds effect) :major)))
                                         (filter #(or (nil? (:major-ids effect)) (contains? (:major-ids effect) (:improvement %)))
                                                 (supply-minor-options state p)))
                                       (when (and (contains? (:kinds effect) :major) (not (contains? (:kinds effect) :minor)))
                                         (filter #(get-in (c/card (:card %)) [:passive :major-or-minor]) (hand-options p :minor)))
                                       (when (contains? (:kinds effect) :occupation) (hand-options p :occupation))
                                       (when (contains? (:kinds effect) :major)
                                         (filter #(or (nil? (:major-ids effect)) (contains? (:major-ids effect) (:improvement %))) (major-options state p))))
                  :scholar (concat (hand-options p :minor) (hand-options p :occupation) (supply-minor-options state p))
                  :extra-grain-harvest (map #(hash-map :fields %) (reductions conj [] (filter #(let [f (get-in p (a/field-path %))]
                                                                                                   (and (= :grain (:crop f)) (pos? (get f :count 0)))) (field-ids p))))
                  [{}])]
    (concat (when (= :ability-window (:op effect)) (ability-candidates state pid))
     (when (= :ability-window (:op effect))
       (for [cid (c/active-owned p)
             :when (and (= :buy-stock (get-in (c/card cid) [:ability :op])) (seq (get-in p [:card-state cid :stock])))
             :let [goods (g/funding-conversion state pid {:food 1})] :when (seq goods)]
         {:type :convert :goods goods}))
     (when (or (g/optional-effect? state head) (= :pay-or-beg (:op effect)))
       (let [context (get-in state [:effect-queue 0 :context])
             base (if (= :pay-or-beg (:op effect)) {:food (:food effect)}
                      (expression/quantities state pid context (:cost effect)))
             costs (cond
                     (= :card-action (:op effect)) (keep #(attempt (fn [] (merge-with + base (g/granted-action-cost state pid effect %)))) options)
                     (= :choose (:op effect))
                     (for [option (:options effect)
                           :when (expression/evaluate state pid context (get option :when true))]
                       (merge-with + base (expression/quantities state pid context (:cost option)))) :else [base])]
         (for [cost costs :let [goods (g/funding-conversion state pid cost)] :when (seq goods)]
           {:type :convert :goods goods})))
     (map #(merge {:type :resolve :decision (:decision-id state)} %)
         (cond-> (mapcat #(with-bake-cooking p % (if (#{:bake :freshman-bake} (:op effect)) :grain :bake-grain)) options) (g/optional-effect? state head) (concat [{:skip? true}]))))))
(defn ability-candidates [state pid]
  (let [p (g/p state pid)]
    (mapcat
     (fn [cid]
       (map #(merge {:type :ability :card cid} %)
            (case (get-in (c/card cid) [:ability :op])
              :return-animal-loan (when-not (get-in p [:card-state cid :loan-settled?]) (g/animal-loan-choices p))
              (:adopt :buy-stock :cash-card :reed-food :claim-goods) [{}]
              :renovate-wood-clay (remove #(= :stone (:renovate-to %)) (payment-options p))
              :free-placement-fences (map #(hash-map :fences %) (fence-plans p))
              :free-room (map #(hash-map :rooms [%]) (range 15))
              :exchange-options
              (for [[index option] (map-indexed vector (get-in (c/card cid) [:ability :options]))
                    :let [maximum (apply min (map (fn [[resource n]] (quot (a/amount p resource) n)) (:cost option)))]
                    times (distinct (filter pos? [1 maximum]))]
                {:option index :times times})
              :porcelain (map #(hash-map :amount %) [2 3 4])
              :sheep-exchange (map #(hash-map :resource %) [:boar :vegetable :stone])
              :move-crop (for [from (field-ids p) to (field-ids p)] {:from from :to to})
              []))) (c/owned p))))
(defn card-action-candidates [state pid]
  (mapcat
    (fn [{:keys [card kind]}]
      (when-not (g/card-space-error state pid card)
        (case kind
          :substitute-teacher (for [bundle [:resources :crops]] {:type :card-action :card-space card :bundle bundle})
          (:bullcatcher :chapel :forest-tallyman :master-forester) [{:type :card-action :card-space card}]
          (:greenhouse :fold-builder)
          (let [spaces (if (= kind :fold-builder) [:fences] (filter (set (:active state)) [:fences :renovate :vegetable]))]
            (map #(assoc % :type :card-action :card-space card :action (:space %))
                 (action-candidates (assoc state :active spaces :occupied {}) pid))))))
    (g/card-spaces state pid)))
(defn feeding-candidates [state pid]
  (let [p (g/p state pid) craftable (filter #(and (:craft (a/improvements %))
                                                               (pos? (a/amount p (:craft (a/improvements %))))) (sort (:improvements p)))
        priorities [[:sheep :boar :cattle :grain :vegetable] [:grain :sheep :boar :cattle :vegetable]
                    [:vegetable :sheep :boar :cattle :grain] [:cattle :boar :sheep :vegetable :grain]]]
    (distinct
     (for [craft (subsets craftable)
           extra? (if (and (a/plane? p) (some #{:joinery} craft) (>= (a/amount p :wood) 2)) [false true] [false])
           order priorities preserve [2 1 0]
           :let [crafted (a/craft-food p craft extra?) rates (a/cook-rates p)
                 needed (a/food-needed state p)
                 units (concat (mapcat #(repeat (max 0 (- (a/amount p %) (if ((set a/animals) %) preserve (min preserve 1)))) %) order)
                               (mapcat #(repeat (min (a/amount p %) (if ((set a/animals) %) preserve (min preserve 1))) %) order))
                 convert (reduce (fn [goods resource]
                                   (if (and (< (a/amount (a/convert-food crafted goods) :food) needed)
                                            (pos? (get rates resource 0)))
                                     (update goods resource (fnil inc 0)) goods)) {} units)]]
       (cond-> {:type :feed :convert convert :craft craft} extra? (assoc :plane-extra? true))))))
(defn candidates [state pid]
  (cond
    (seq (:effect-queue state)) (decision-candidates state pid)
    (= :feeding (:phase state)) (concat (feeding-candidates state pid) (ability-candidates state pid))
    (= :breeding (:phase state)) (map #(hash-map :type :breed :offspring %) (a/breeding-choices (g/p state pid)))
    (= :work (:phase state)) (concat (action-candidates state pid) (card-action-candidates state pid) (ability-candidates state pid))
    :else []))

(defn diminishing [n weights tail]
  (+ (reduce + (take n weights)) (* (max 0 (- n (count weights))) tail)))
(defn future-card-value [cid rounds]
  (let [card (c/card cid)]
    (+ (* rounds (min 1.8 (* 0.14 (count (:triggers card)))))
       (if (seq (:passive card)) (* 0.10 rounds) 0)
       (if (:ability card) (* 0.08 rounds) 0))))
(defn raw-utility [state pid]
  (let [p (g/p state pid) r (:round state) future (- 14 r)
        rooms (count (a/tiles p :room)) family (:family p)
        fields (g/fields p) rates (a/cook-rates p)
        kitchen? (contains? rates :sheep)
        crop-food (reduce + (for [f fields :when (pos? (get f :count 0))] (get rates (:crop f) 0)))
        cookable (reduce + (for [animal a/animals] (* (get rates animal 0) (max 0 (- (a/amount p animal) 2)))))
        food (+ (a/amount p :food) cookable (* 0.75 crop-food))
        target (* (if (= 1 (count (:players state))) 3 2) family)
        harvest (first (sort (filter #(>= % r) a/harvest-rounds)))
        urgency (if (= r harvest) 1.6 (if (= 1 (- harvest r)) 1.1 0.65))
        fed-now? (and (#{:feeding :breeding :finish-round} (:phase state)) (contains? (:fed state) pid))
        hunger (if fed-now? 0 (* urgency (max 0 (- target food))))
        growth-room? (and (< family 5) (> (a/family-capacity p) family))
        wood-target (if (and (< family 5) (not growth-room?) (= :wood (:house p))) 5 0)
        reed-target (if (and (< family 5) (not growth-room?)) 2 1)
        material-values (+ (diminishing (a/amount p :wood) (repeat (max 5 wood-target) 0.72) 0.16)
                           (diminishing (a/amount p :reed) (repeat reed-target 1.25) 0.17)
                           (diminishing (a/amount p :clay) (repeat (if kitchen? rooms 3) 0.85) 0.12)
                           (diminishing (a/amount p :stone) [1.1 1.1 1.1 0.65 0.5] 0.15))
        animals-fit? (a/animal-housing p)
        surplus (if animals-fit? 0
                  ;; Do not overvalue animals that the next mandatory decision
                  ;; must release. Kitchens turn their food value into a benefit.
                  (max 0 (- (reduce + (map #(a/amount p %) a/animals))
                            (reduce + (map :capacity (a/accommodations p))))))]
    (+ (:total (g/score state pid))
       (* 1.35 future (- family 2))
       (if growth-room? (+ 2.0 (* 0.75 future)) 0)
       (* (min 1.0 (/ future 5.0)) material-values)
       (diminishing (a/amount p :food) (repeat (+ target 2) 0.38) 0.06)
       (- hunger) (* -1.7 (:begging p))
       (if kitchen? (* 0.38 future) 0)
       (if (some #(:oven (a/improvements %)) (:improvements p)) (* 0.14 future) 0)
       (* 0.3 future (min 2 crop-food))
       (* 0.22 future (min 2 (count (filter #(zero? (get % :count 0)) fields))))
       (if (and (pos? (a/amount p :grain)) (< (count fields) 3)) (* 0.12 future) 0)
       (reduce + (map #(future-card-value % future) (c/owned p)))
       (if (= pid (:starting-player state)) 0.45 0)
       (if kitchen? (* 0.35 (reduce + (map #(* (a/amount p %) (get rates % 0)) a/animals))) (* -2 surplus)))))
(defn utility [state pid]
  (let [p (g/p state pid)]
    (if (a/animal-housing p) (raw-utility state pid)
        (apply max
               (for [keep (housing-plans p) cook? [true false]
                     :let [surplus (merge-with - (select-keys (:goods p) a/animals) keep)
                           settled (attempt #(if cook? (a/convert-food p surplus) (a/pay p surplus)))]
                     :when settled]
                 (raw-utility (assoc-in state [:players pid] settled) pid))))))
(defn choose
  "Returns an executable command and diagnostic estimate. Policy :random chooses
   an action space uniformly, then a legal candidate on it; harvests still use
   the feeding policy. rng controls tie breaking/exploration reproducibly."
  ([state pid rng] (choose state pid rng :heuristic))
  ([state pid ^java.util.Random rng policy]
   (let [state (observation state pid) base (utility state pid)
         legal (vec (keep (fn [cmd]
                            (when-let [after (preview state pid cmd)]
                              (let [value (- (utility after pid) base)
                                    ability? (= :ability (:type cmd))]
                                ;; Anytime actions must improve the position; this
                                ;; prevents cost-free crop moves from cycling.
                                (when (or (not ability?) (> value 0.15))
                                  {:command cmd :value (- value (if ability? 0.15 0))}))))
                          (distinct (candidates state pid))))]
     (when (empty? legal)
       (throw (ex-info "Bot has no legal candidate." {:player pid :round (:round state) :phase (:phase state)
                                                      :pending (first (:effect-queue state))})))
     (if (and (= policy :random) (= :work (:phase state)) (empty? (:effect-queue state)))
       (let [groups (->> legal (group-by #(or (get-in % [:command :space]) :ability)) (sort-by key) (mapv val))
             group (nth groups (.nextInt rng (count groups)))]
         (nth group (.nextInt rng (count group))))
       (reduce (fn [best candidate]
                 (let [candidate (assoc candidate :rank (+ (:value candidate) (* 0.025 (.nextDouble rng))))]
                   (if (or (nil? best) (> (:rank candidate) (:rank best))) candidate best))) nil legal)))))
