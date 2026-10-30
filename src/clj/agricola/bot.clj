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
(defn payment-options [p]
  (for [frame? (if (c/has? p :a123) [false true] [false])
        brushwood? (if (c/has? p :b145) [false true] [false])
        stone? (if (c/has? p :a087) [false true] [false])]
    (cond-> {} frame? (assoc :frame? true) brushwood? (assoc :brushwood? true)
            stone? (assoc :renovate-to :stone))))
(defn hand-options [p kind]
  (for [cid (get-in p [:hand kind])
        paper? (if (and (= kind :occupation) (c/has? p :b109)) [false true] [false])
        payment (if-let [costs (:cost-options (c/card cid))] (range (count costs)) [nil])]
    (cond-> {:card cid} paper? (assoc :paper-maker? true) (some? payment) (assoc :payment-option payment))))
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
(defn major-options [state p]
  (for [cid (sort (:available-improvements state))
        upgrade (if (:upgrade (a/improvements cid))
                  (cons nil (sort (set/intersection (:improvements p) #{:fireplace-2 :fireplace-3}))) [nil])
        grain (if (:oven (a/improvements cid)) (range (inc (min 3 (a/amount p :grain)))) [0])]
    (cond-> {:improvement cid} upgrade (assoc :upgrade upgrade) (pos? grain) (assoc :bake-grain grain))))
(defn action-candidates [state pid]
  (let [p (g/p state pid) payments (payment-options p)
        minors (hand-options p :minor)
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
               :house-building (for [cell cells opts payments] (assoc opts :rooms [cell]))
               :animal-market (map #(hash-map :choice %) a/animals)
               :build (concat (for [cell cells opts payments] (assoc opts :rooms [cell]))
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
         (for [option options
               hora (if (and (c/has? p :a174) (five/linked-groups space)) [false true] [false])
               double (if (and (c/has? p :a180) (= space :animal-market)) [false true] [false])
               part (if (and (c/has? p :d180) (:accumulate (a/action-definition state space))) [false true] [false])]
           (cond-> (merge {:type :action :space space} option) hora (assoc :master-hora? true) double (assoc :animal-brander? true) part (assoc :part-time? true)))))
     ;; Sleeping Corner can share Growth; validate-space decides whether it can.
     (filter #(and (not (contains? (:cancelled-sowing state) [pid % (:round state)]))
                   (or (not (contains? (:occupied state) %)) (and (#{:growth :urgent-growth} %) (c/has? p :a026)))) (:active state)))))
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
(declare ability-candidates)
(defn decision-candidates [state pid]
  (let [p (g/p state pid) head (first (:effect-queue state)) effect (:effect head)
        options (case (:op effect)
                  (:plow :stable :mini-pasture :corral-builder :amateur-fencer :plowsmith) (map #(hash-map :cell %) (range 15))
                  (:plow-many :wheel-plow) (concat (map #(hash-map :cells [%]) (range 15))
                                                 (for [x (range 15) y (range 15) :when (not= x y)] {:cells [x y]}))
                  :paper-knife (let [hand (vec (get-in p [:hand :occupation]))]
                                 (for [i (range (count hand)) j (range (inc i) (count hand)) k (range (inc j) (count hand))]
                                   {:cards [(hand i) (hand j) (hand k)]}))
                  :sow-offer [{}]
                  :build-rooms (for [rooms (g/room-plans p) opts (payment-options p)] (assoc opts :rooms rooms))
                  :sow (let [player (g/sowing-player state pid effect)
                             plans (if (:max-fields effect)
                                     (for [cell (field-ids player) crop [:grain :vegetable]] [{:cell cell :crop crop}])
                                     (remove empty? (sow-plans player)))]
                         (for [plantings plans grain (if (= :sow-bake (get-in effect [:source-command :space]))
                                                      (range (inc (min 8 (a/amount player :grain)))) [0])]
                           {:plantings plantings :bake-grain grain}))
                  :choose (map #(hash-map :option %) (range (count (:options effect))))
                  :take-pile-good (g/pile-good-options state effect)
                  :choose-good (map #(hash-map :resource %) (:choices effect))
                  :seasonal-crop [{:resource :grain} {:resource :vegetable}]
                  :bake (map #(hash-map :grain %) (range 1 (inc (min 8 (a/amount p :grain)))))
                  :bake-one (for [improvement (:improvements p) :when (get-in a/improvements [improvement :bake])] {:improvement improvement})
                  :take-field-crop (for [cell (field-ids p) exchange? (if (:exchange effect) [false true] [false])]
                                     {:cell cell :exchange? exchange?})
                  :housing (for [keep (housing-plans p) cook? [true false]] {:keep keep :cook? cook?})
                  :cottage (concat (for [cell (range 15) opts (payment-options p)] (assoc opts :action :room :cell cell))
                                   (map #(assoc % :action :renovate) (payment-options p)))
                  :boat-painter [{:resource :grain} {:resource :food}]
                  :countryman (for [cell (field-ids p) crop [:grain :vegetable]] {:plantings [{:cell cell :crop crop}]})
                  (:fast-mason :renovate) (payment-options p)
                  :young-artist (cons {:action :draw} (map #(assoc % :action :play) (hand-options p :minor)))
                  :sidekick (let [space (five/stage-left state (get-in state [:effect-queue 0 :context :space]))]
                              (map #(hash-map :action-command %) (action-candidates (assoc state :active [space] :turn pid) pid)))
                  :card-action (concat (when (contains? (:kinds effect) :minor) (hand-options p :minor))
                                       (when (contains? (:kinds effect) :occupation) (hand-options p :occupation))
                                       (when (contains? (:kinds effect) :major)
                                         (filter #(or (nil? (:major-ids effect)) (contains? (:major-ids effect) (:improvement %))) (major-options state p))))
                  :scholar (concat (hand-options p :minor) (hand-options p :occupation))
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
         (cond-> options (g/optional-effect? state head) (concat [{:skip? true}]))))))
(defn ability-candidates [state pid]
  (let [p (g/p state pid)]
    (mapcat
     (fn [cid]
       (map #(merge {:type :ability :card cid} %)
            (case (get-in (c/card cid) [:ability :op])
              (:adopt :buy-stock :cash-card :reed-food) [{}]
              :free-placement-fences (map #(hash-map :fences %) (fence-plans p))
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
          (:bullcatcher :chapel :forest-tallyman) [{:type :card-action :card-space card}]
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
     (for [craft (subsets craftable) order priorities preserve [2 1 0]]
       (let [rates (a/cook-rates p) food (+ (a/amount p :food) (reduce + (map #(:rate (a/improvements %)) craft)))
             deficit (max 0 (- (a/food-needed state p) food))
             units (concat (mapcat #(repeat (max 0 (- (a/amount p %) (if ((set a/animals) %) preserve (min preserve 1)))) %) order)
                           (mapcat #(repeat (min (a/amount p %) (if ((set a/animals) %) preserve (min preserve 1))) %) order))
             [convert _] (reduce (fn [[goods missing] r]
                                   (if (and (pos? missing) (pos? (get rates r 0)))
                                     [(update goods r (fnil inc 0)) (- missing (rates r))] [goods missing])) [{} deficit] units)]
         {:type :feed :convert convert :craft craft})))))
(defn candidates [state pid]
  (cond
    (seq (:effect-queue state)) (decision-candidates state pid)
    (= :feeding (:phase state)) (concat (feeding-candidates state pid) (ability-candidates state pid))
    (= :breeding (:phase state)) (map #(hash-map :type :breed :offspring (zipmap % (repeat 1))) (subsets (sort (keys (a/breeding-options (g/p state pid))))))
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
