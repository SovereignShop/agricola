(ns agricola.engine
  "Pure Revised Edition beginner-game rules. Commands either return a new state
   or throw ex-info; no UI, transport, storage, clocks, or global mutable state."
  (:require [agricola.card-rules :as cards] [clojure.set :as set] [clojure.string :as str]
            #?(:clj [agricola.data :refer [read-edn]]))
  #?(:cljs (:require-macros [agricola.data :refer [read-edn]])))

(def board-data (read-edn "agricola/board.edn"))
(def animals (:animals board-data))
(def resources (:resources board-data))
(def harvest-rounds (:harvest-rounds board-data))
(def stages (:stages board-data))
(def actions (:actions board-data))
(def improvements (:improvements board-data))

(defn require! [condition message] (when-not condition (throw (ex-info message {:type :illegal-command}))))
(defn amount [p r] (get-in p [:goods r] 0))
(defn natural? [n] (and (integer? n) (<= 0 n)))
(defn quantities! [m allowed]
  (require! (and (map? m) (every? (set allowed) (keys m)) (every? natural? (vals m))) "Invalid resource quantities."))
(defn gain [p goods]
  (-> p (update :goods #(merge-with + % goods))
      (update :received #(merge-with + (or % {}) (into {} (filter (comp pos? val) goods))))))
(defn pay [p goods]
  (quantities! goods resources)
  (require! (every? (fn [[r n]] (>= (amount p r) n)) goods) "You do not have enough resources.")
  (gain p (into {} (map (fn [[r n]] [r (- n)]) goods))))
(defn cell? [i] (and (integer? i) (<= 0 i 14)))
(defn neighbors [i]
  (let [r (quot i 5) c (mod i 5)]
    (cond-> [] (> r 0) (conj (- i 5)) (< r 2) (conj (+ i 5)) (> c 0) (conj (dec i)) (< c 4) (conj (inc i)))))
(defn edges [i]
  (let [r (quot i 5) c (mod i 5)]
    [(str "h:" r ":" c) (str "h:" (inc r) ":" c)
     (str "v:" r ":" c) (str "v:" r ":" (inc c))]))
(def all-edges (set (mapcat edges (range 15))))
(defn shared-edge [a b] (first (set/intersection (set (edges a)) (set (edges b)))))
(defn connected? [cells]
  (let [cells (set cells)]
    (loop [seen #{} todo (take 1 cells)]
      (if-let [i (first todo)]
        (if (seen i) (recur seen (rest todo))
            (recur (conj seen i) (concat (rest todo) (remove seen (filter cells (neighbors i))))))
        (= seen cells)))))
(defn pastures [p]
  (let [fences (set (:fences p))]
    (loop [left (set (range 15)) result []]
      (if-let [start (first (sort left))]
        (let [region (loop [seen #{} todo [start]]
                       (if-let [i (first todo)]
                         (if (seen i) (recur seen (rest todo))
                             (recur (conj seen i) (concat (rest todo) (remove seen (filter #(not (fences (shared-edge i %))) (neighbors i))))))
                         seen))
              boundary (->> region (mapcat edges) frequencies (filter #(= 1 (val %))) (map key))]
          (recur (set/difference left region)
                 (cond-> result (every? fences boundary) (conj (vec (sort region))))))
        result))))
(defn pasture-cells [p] (set (mapcat identity (pastures p))))
(defn empty-cell? [p i]
  (and (cell? i) (= :empty (:type (get (:farm p) i)))
       (not (:stable (get (:farm p) i))) (not ((pasture-cells p) i))))
(defn tiles [p t] (keep-indexed #(when (= t (:type %2)) %1) (:farm p)))
(defn place-tile [p t i]
  (require! (empty-cell? p i) "Choose an unused farm space.")
  (require! (or (empty? (tiles p t)) (some (set (tiles p t)) (neighbors i))) "Rooms and fields must touch another tile of their own kind.")
  (assoc-in p [:farm i] {:type t}))
(defn discounted [cost reductions]
  (reduce-kv (fn [m resource n] (update m resource #(max 0 (- (or % 0) n)))) cost reductions))
(defn clay-roof-cost [p cost options]
  (let [n (:clay-reed options 0)]
    (require! (and (natural? n) (<= n 2) (<= n (:reed cost 0))
                   (or (zero? n) (some true? (cards/passives p :clay-roof))))
              "Clay Roof can replace up to two required reed with the same amount of clay.")
    (cond-> cost (pos? n) (-> (update :reed - n) (update :clay (fnil + 0) n)))))
(defn wood-carver-available? [p]
  (and (some true? (cards/passives p :wood-carver)) (not (:wood-carver-used? p))))
(defn carve-cost [p cost options]
  (if-not (:wood-carver? options) cost
    (do (require! (and (wood-carver-available? p) (pos? (:wood cost 0))) "Wood Carver needs an unused discount and a wood cost.")
        (update cost :wood dec))))
(defn use-wood-carver [p options]
  (cond-> p (:wood-carver? options) (assoc :wood-carver-used? true)))
(defn collector? [p] (or (cards/has? p :b145) (some true? (cards/passives p :brushwood-collector))))
(defn construction-payment-options [p]
  (for [frame? (if (or (cards/has? p :a123) (some true? (cards/passives p :original-frame-builder))) [false true] [false])
        brushwood? (if (collector? p) [false true] [false])
        wood-reed (if (some true? (cards/passives p :brushwood-roof)) [0 1 2] [0])
        carver? (if (wood-carver-available? p) [false true] [false])
        stone? (if (some true? (cards/passives p :direct-stone)) [false true] [false])
        roof (if (some true? (cards/passives p :clay-roof)) [0 1 2] [0])
        supports? (if (and (= :clay (:house p)) (some true? (cards/passives p :clay-supports))) [false true] [false])]
    (cond-> {} frame? (assoc :frame? true) brushwood? (assoc :brushwood? true)
              carver? (assoc :wood-carver? true) (pos? wood-reed) (assoc :wood-reed wood-reed)
              stone? (assoc :renovate-to :stone) (pos? roof) (assoc :clay-reed roof)
              supports? (assoc :clay-supports? true))))
(defn construction-replacements [p cost options material renovation?]
  (let [cost (clay-roof-cost p cost options)
        n (:wood-reed options 0)
        _ (require! (and (natural? n) (<= n 2) (<= n (:reed cost 0))
                         (or (zero? n) (some true? (cards/passives p :brushwood-roof))))
                    "Brushwood Roof replaces up to two required reed with the same amount of wood.")
        cost (cond-> cost (pos? n) (-> (update :reed - n) (update :wood (fnil + 0) n)))
        original? (some true? (cards/passives p :original-frame-builder))
        frame (if (and original? renovation?) 1 2)
        cost (if (:frame? options)
               (do (require! (and (or original? (cards/has? p :a123)) (#{:clay :stone} material)
                                  (>= (get cost material 0) frame)) "Frame Builder must replace the required clay or stone.")
                   (-> cost (update material - frame) (update :wood (fnil inc 0)))) cost)]
    (if (:brushwood? options)
      (do (require! (and (collector? p) (pos? (:reed cost 0))) "Brushwood Collector needs reed remaining in this cost.")
          (-> cost (assoc :reed 0) (update :wood (fnil inc 0)))) cost)))
(defn room-cost [p options]
  (let [material (:house p)
        base (apply min 5 (concat (cards/passives p :room-material-cost)
                                  (when (= material :clay) (cards/passives p :clay-room-cost))
                                  (when (= material :wood) (cards/passives p :wood-room-cost))))
        _ (when (:clay-supports? options)
            (require! (and (= material :clay) (some true? (cards/passives p :clay-supports))) "Clay Supports requires that card and a clay house."))
        cost (if (:clay-supports? options) {:clay 2 :wood 1 :reed 1} {material base :reed 2})
        cost (cond-> cost (some true? (cards/passives p :no-construction-reed)) (assoc :reed 0))
        cost (discounted cost (cond-> {:stone (reduce + 0 (cards/passives p :stone-discount))
                                       :reed (reduce + 0 (cards/passives p :reed-construction-discount))}
                                 (seq (cards/passives p :room-clay-discount))
                                 (assoc :clay (reduce + 0 (cards/passives p :room-clay-discount)))))]
    (construction-replacements p cost options material false)))
(defn room-payment-choices [p]
  ;; Equivalent payments need only one representative; this keeps bot search
  ;; bounded even when several different construction cards are owned.
  (vals (reduce (fn [out options]
                  (try (let [cost (room-cost p options)] (if (contains? out cost) out (assoc out cost options)))
                       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ out)))
                (array-map) (construction-payment-options p))))
(defn room-payment-plans [p rooms]
  (if (or (some true? (cards/passives p :clay-roof)) (some true? (cards/passives p :clay-supports)) (some true? (cards/passives p :brushwood-roof)))
    (for [plan (reduce (fn [plans cell] (for [plan plans options (room-payment-choices p)] (assoc-in plan [:room-options cell] (dissoc options :wood-carver?))))
                       [{}] rooms)
          carve? (if (wood-carver-available? p) [false true] [false])]
      (cond-> plan carve? (assoc :wood-carver? true)))
    (construction-payment-options p)))
(defn build-rooms
  ([p cells] (build-rooms p cells {}))
  ([p cells options] (build-rooms p cells options {}))
  ([p cells options {:keys [room-discount free?]}]
   (let [cost (apply merge-with + {} (map #(discounted (room-cost p (get-in options [:room-options %] options)) room-discount) cells))
         reductions (when (and (>= (count cells) 2) (some true? (cards/passives p :batch-room-discount)))
                      {:reed 2 (:house p) ({:wood 2 :clay 3 :stone 4} (:house p))})]
     (require! (or (not (:wood-carver? options)) (and (not free?) (= :wood (:house p)))) "Wood Carver discounts only paid wooden rooms.")
     (reduce #(place-tile %1 :room %2)
             (use-wood-carver (pay p (if free? {} (carve-cost p (discounted cost reductions) options))) options) cells))))
(defn fences-available [p] (- 15 (count (:fences p)) (:discarded-fences p 0) (reduce + 0 (vals (:reserved-fences p)))))
(defn stables-available [p]
  (- 4 (count (filter :stable (:farm p))) (reduce + 0 (vals (:reserved-stables p)))))
(defn family-limit [p] (- 5 (:removed-family p 0)))
(defn reed-hut-count [p] (if (:reed-hut? p) 1 0))
(defn unused-person-tokens [p] (max 0 (- (family-limit p) (:family p) (reed-hut-count p))))
(defn workers-in-play [p] (+ (:family p) (:guests p 0) (reed-hut-count p)))

(defn build-stables
  ([p cells cost] (build-stables p cells cost {}))
  ([p cells cost options]
   (let [requested? (:wood-carver? options)
         _ (when requested? (require! (wood-carver-available? p) "Wood Carver is already used or unavailable."))
         built (reduce (fn [p i]
                         (require! (and (cell? i) (= :empty (get-in p [:farm i :type])) (not (get-in p [:farm i :stable]))) "A stable needs an empty space or pasture without another stable.")
                         (require! (pos? (stables-available p)) "No unreserved stable remains in your supply.")
                         (let [sawhorse (first (filter #(and (get-in (cards/card %) [:passive :sawhorse])
                                                            (not (get-in p [:card-state % :stable-used?]))) (cards/active-owned p)))
                               price (if sawhorse 0 cost)
                               use? (and requested? (wood-carver-available? p) (pos? price))
                               opts {:wood-carver? use?}]
                           (cond-> (assoc-in (use-wood-carver (pay p (carve-cost p {:wood price} opts)) opts) [:farm i :stable] true)
                             sawhorse (assoc-in [:card-state sawhorse :stable-used?] true)))) p cells)]
     (when requested? (require! (:wood-carver-used? built) "Wood Carver needs a stable with a wood cost."))
     built)))
(defn turn-free-fences-available [p]
  (if (:turn-fence-bonus-used? p) 0 (reduce + 0 (cards/passives p :turn-free-fences))))
(defn reset-turn-fence-bonus [p] (dissoc p :turn-fence-bonus-used?))
(defn build-fences
  ([p additions] (build-fences p additions {}))
  ([p additions options]
  (let [new (set additions) old (set (:fences p))]
    (require! (and (seq new) (= (count new) (count additions)) (set/subset? new all-edges) (empty? (set/intersection new old))) "Select new fence edges.")
    (require! (<= (count new) (fences-available p)) "Not enough fence pieces remain in your supply.")
    (let [material (if (and (= :clay (:fence-material options)) (cards/has? p :a016)) :clay :wood)
          sawhorse (if (some true? (cards/passives p :sawhorse))
                     (count (filter #(zero? (mod % 3)) (range (inc (count old)) (inc (+ (count old) (count new)))))) 0)
          provided (+ sawhorse (reduce + 0 (cards/passives p :free-fences)) (or (:free-fences options) 0))
          ;; Original Hedge Keeper adds fences after a first fence supplied by
          ;; the player or another card. It cannot make the first fence free.
          bonus (min (turn-free-fences-available p) (max 0 (- (count new) (max 1 provided))))
          cost (max 0 (- (count new) provided bonus))
          p' (cond-> (assoc (use-wood-carver (pay p (carve-cost p {material cost} options)) options) :fences (set/union old new))
               (pos? bonus) (assoc :turn-fence-bonus-used? true))
          regions (pastures p') covered (set (mapcat identity regions))
          borders (set (mapcat (fn [region] (->> region (mapcat edges) frequencies (filter #(= 1 (val %))) (map key))) regions))]
      (require! (and (seq covered) (connected? covered)) "Pastures must be adjacent and fully enclosed.")
      (require! (every? #(= :empty (get-in p [:farm % :type])) covered) "Pastures cannot contain rooms or fields.")
      (require! (set/subset? (:fences p') borders) "Every fence must border a fully enclosed pasture.")
      p'))))
(defn build-mini-pasture [p cell extra-fences]
  (require! (and (cell? cell) (not ((pasture-cells p) cell))) "Choose an unfenced farmyard space.")
  (let [base (vec (remove (:fences p) (edges cell))) extras (or extra-fences [])]
    (require! (and (sequential? extras) (= (count extras) (count (set extras)))
                  (empty? (set/intersection (set base) (set extras)))) "Select distinct extra fence edges outside the chosen space.")
    (require! (<= (count extras) (turn-free-fences-available p)) "Hedge Keeper can provide at most three extra fences, once per turn.")
    ;; Validate only the finished construction: the additional fences may join
    ;; the chosen cell to an existing pasture. Neither half need stand alone.
    (build-fences p (into base extras) {:free-fences (count base)})))
(defn sheep-accommodation [space bonus]
  (if (pos? bonus) (assoc space :base-capacity (:capacity space) :capacity (+ (:capacity space) bonus) :sheep-bonus bonus) space))
(defn accommodations [p]
  (let [ps (pastures p) fenced (set (mapcat identity ps))
        trough (reduce + 0 (cards/passives p :pasture-capacity))
        pipe (reduce + 0 (cards/passives p :sheep-pasture-capacity))
        stable-sheep (apply max 1 (cards/passives p :unfenced-sheep-capacity))
        stablemaster (apply max 1 (cards/passives p :one-unfenced-stable-capacity))
        mixed? (some true? (cards/passives p :mixed-pastures))
        stables (filter #(and (get-in p [:farm % :stable]) (not (fenced %))) (range 15))]
    (into (cond (some true? (cards/passives p :no-house-animals)) []
                (or (cards/has? p :a086) (some true? (cards/passives p :room-pets)))
                (mapv #(hash-map :cells [%] :capacity 1 :kind :pet) (tiles p :room))
                :else [{:cells [5] :capacity 1 :kind :pet}])
          (concat
           (for [region ps :let [stables (count (filter #(get-in p [:farm % :stable]) region))]]
             (sheep-accommodation
              (cond-> {:cells region :capacity (+ (* 2 (count region) (reduce * 1 (repeat stables 2)))
                                                 (if (and (= 1 (count region)) (zero? stables) (some true? (cards/passives p :smallholder))) 1 0) trough) :kind :pasture}
                mixed? (assoc :mixed? true)) pipe))
           (for [i stables]
             ;; Unfenced stables are interchangeable for housing. Designate one
             ;; physical stable for Stablemaster; Pipe still applies to the rest.
             (if (and (> stablemaster 1) (= i (first stables)))
               {:cells [i] :capacity stablemaster :kind :stable}
               (sheep-accommodation {:cells [i] :capacity 1 :kind :stable} (dec stable-sheep))))
           (when (some true? (cards/passives p :boar-on-empty-fields))
             (for [i (tiles p :field) :when (zero? (get-in p [:farm i :count] 0))]
               {:cells [i] :capacity 1 :kind :field :allowed-animals #{:boar}}))
           (for [cid (cards/owned p) :when (get-in (cards/card cid) [:passive :forest-boars])]
             {:cells [] :capacity 1000000 :kind :card :card cid :allowed-animals #{:boar}})
           (for [_ (range (get p :card-animal-capacity 0))]
             {:cells [] :capacity 1 :kind :card :card (cards/id :b169)})
           (mapcat (fn [cid]
                     (concat (for [animal animals]
                               {:cells [] :capacity (+ 1 (if (= animal :sheep) pipe 0))
                                :kind :card :card cid :slot animal :allowed-animals #{animal}})
                             (when (pos? trough)
                               [{:cells [] :capacity trough :kind :card :card cid :slot :trough :mixed? true}])))
                   (filter #(get-in (cards/card %) [:passive :wildlife-reserve]) (cards/owned p)))
           (for [cid (cards/owned p) :when (get-in (cards/card cid) [:passive :animal-yard])]
             (sheep-accommodation {:cells [] :capacity (+ 2 trough) :kind :card :card cid :mixed? true} pipe))
           (when (and (cards/has? p :a148) (pos? (:completed-feeding-phases p 0)))
             [{:cells [] :capacity (:completed-feeding-phases p) :kind :card
               :card (cards/id :a148) :allowed-animals #{:sheep}}])))))
(defn housing-animals
  "Species counts in a compartment, including cards/pastures with mixed species."
  [space]
  (or (:animals space) (when (pos? (:count space 0)) {(:animal space) (:count space)}) {}))
(defn occupied-accommodation [space counts]
  (let [goods (into {} (filter (comp pos? second) (map vector animals counts)))
        total (reduce + counts)
        capacity (if (pos? (first counts)) (:capacity space) (get space :base-capacity (:capacity space)))]
    (cond-> (assoc space :count total :capacity capacity)
      (= 1 (count goods)) (assoc :animal (ffirst goods))
      (> (count goods) 1) (assoc :animals goods))))
(defn accommodation-allocations [space remaining optimize?]
  (let [allowed (get space :allowed-animals (set animals))
        limits (mapv #(if (allowed %1) %2 0) animals remaining)
        base (get space :base-capacity (:capacity space)) bonus (:sheep-bonus space 0)]
    (if (:mixed? space)
      ;; At least one sheep must occupy ordinary capacity before Pipe can add
      ;; sheep. Thus Animal Yard + Pipe holds3 sheep+1 other, never2+2.
      (for [sheep (range (min (limits 0) (:capacity space)) -1 -1)
            :let [left (- (+ base (min bonus (max 0 (dec sheep)))) sheep)]
            :when (>= left 0)
            boar (range (min (limits 1) left) -1 -1)
            cattle (range (min (limits 2) (- left boar)) -1 -1)
            :when (pos? (+ sheep boar cattle))]
        [sheep boar cattle])
      (for [index (range 3)
            :let [max-n (min (limits index) (if (= index 0) (:capacity space) base))]
            n (if optimize? (range max-n 0 -1) (when (pos? max-n) [max-n]))]
        (assoc [0 0 0] index n)))))
(defn housing-solution [p optimize?]
  (let [spaces (vec (sort-by :capacity > (accommodations p))) cache (atom {})
        capacities (mapv #(reduce + 0 (map :capacity (subvec spaces %))) (range (inc (count spaces))))]
    (letfn [(fit [index remaining]
              (let [key [index remaining]]
                (if-let [cached (find @cache key)] (val cached)
                  (let [result
                        (cond
                          (every? zero? remaining) {:points 0 :placement []}
                          (>= index (count spaces)) nil
                          (> (reduce + remaining) (capacities index)) nil
                          :else
                          (let [space (spaces index)
                                choices (concat (accommodation-allocations space remaining optimize?) [[0 0 0]])
                                candidates
                                (keep (fn [counts]
                                        (when-let [tail (fit (inc index) (mapv - remaining counts))]
                                          (if (every? zero? counts) tail
                                            (let [occupied (occupied-accommodation space counts)
                                                  n (:count occupied) cap (:capacity occupied)]
                                              (-> tail
                                                  (update :points + (+ (if (and (or (and (cards/has? p :b098) (= :pasture (:kind space)))
                                                                                         (and (some true? (cards/passives p :organic-pastures))
                                                                                              (or (= :pasture (:kind space))
                                                                                                  (get-in (cards/card (:card space)) [:passive :forest-boars])
                                                                                                  (get-in (cards/card (:card space)) [:passive :animal-yard]))))
                                                                                    (>= (- cap n) 3)) 1 0)
                                                                       (if (and (cards/has? p :a134) (= :pasture (:kind space)) (= cap n)) 1 0)))
                                                  (update :placement conj occupied)))))) choices)]
                            (if optimize?
                              (reduce (fn [best candidate] (if (or (nil? best) (> (:points candidate) (:points best))) candidate best)) nil candidates)
                              (first candidates))))]
                    (swap! cache assoc key result)
                    result))))]
      (:placement (fit 0 (mapv #(amount p %) animals))))))
(defn organic-housing
  "Choose a legal arrangement maximizing the combined pasture card bonuses."
  [p] (housing-solution p true))
(defn animal-housing
  "Find legal animal compartments, respecting mixed/species-specific capacity."
  [p]
  (let [spaces (accommodations p)]
    (letfn [(fit [spaces remaining result]
              (if (every? zero? (vals remaining)) result
                  (when-let [space (first spaces)]
                    (or (some (fn [animal]
                                (when (and (pos? (get remaining animal 0)) ((get space :allowed-animals (set animals)) animal))
                                  (let [n (min (:capacity space) (remaining animal))]
                                    (fit (rest spaces) (update remaining animal - n)
                                         (conj result (assoc space :animal animal :count n)))))) animals)
                        (fit (rest spaces) remaining result)))))]
      (cond
        (or (or (cards/has? p :b098) (some true? (cards/passives p :organic-pastures))) (cards/has? p :a134)) (organic-housing p)
        (some #(or (:mixed? %) (:sheep-bonus %)) spaces) (housing-solution p false)
        :else (fit (sort-by :capacity > spaces) (select-keys (:goods p) animals) [])))))
(defn improvement-definition [id]
  (or (improvements id) (when-let [card (cards/card id)] (merge card (:passive card)))))
(defn owned-improvement-ids [p] (concat (:improvements p) (:minors p)))
(defn baking-improvement-ids [p] (filter #(pos? (:bake (improvement-definition %) 0)) (owned-improvement-ids p)))
(defn fireplace-upgrades [p]
  (concat (sort (set/intersection (:improvements p) #{:fireplace-2 :fireplace-3}))
          (filter #(get-in (cards/card %) [:passive :fireplace]) (:minors p))))
(defn returnable-cooking [p] (sort (set/intersection (:improvements p) #{:fireplace-2 :fireplace-3 :hearth-4 :hearth-5})))
(defn returnable-hearths [p]
  (sort (filter #(or (#{:hearth-4 :hearth-5 :catalog/original-base-k-128-cooking-hearth} %)
                     (:cooking-hearth (improvement-definition %))) (owned-improvement-ids p))))
(defn returnable-ovens [p]
  (sort (filter #(:oven (improvement-definition %)) (owned-improvement-ids p))))
(defn cook-rates [p]
  (let [bonus (reduce + 0 (cards/passives p :vegetable-cooking-bonus))]
    (apply merge-with max {:grain 1 :vegetable 1}
           (concat (cards/passives p :animal-cooking)
                   (when (some #(:oven (improvement-definition %)) (owned-improvement-ids p))
                     (cards/passives p :oven-animal-cooking))
            (for [id (owned-improvement-ids p) :let [definition (improvement-definition id) rates (:cook definition {})]]
             (cond-> rates
               (and (:vegetable rates) (or (#{:fireplace-2 :fireplace-3 :hearth-4 :hearth-5} id) (:fireplace definition) (:cooking-corner definition) (:cooking-hearth definition)))
               (update :vegetable + bonus)))))))
(defn fireplace-family? [id]
  (let [definition (improvement-definition id)]
    (or (contains? #{:fireplace-2 :fireplace-3 :hearth-4 :hearth-5} id)
        (:fireplace definition) (:cooking-corner definition) (:cooking-hearth definition))))
(defn crock? [p] (some true? (cards/passives p :cooking-pair-food)))
(defn qualifying-cooked-units [p goods]
  (let [rates (cook-rates p)
        spice (reduce + 0 (cards/passives p :vegetable-cooking-bonus))
        qualifying (apply merge-with max {}
                          (for [id (owned-improvement-ids p) :when (fireplace-family? id)
                                :let [rates (:cook (improvement-definition id) {})]]
                            (cond-> rates (:vegetable rates) (update :vegetable + spice))))]
    (reduce + 0 (for [[resource n] goods
                     :when (and (contains? qualifying resource) (= (qualifying resource) (rates resource)))] n))))
(defn convert-food
  ([p goods] (convert-food p goods {}))
  ([p goods context]
  (quantities! goods (concat animals [:grain :vegetable]))
  (let [rates (cook-rates p)]
    (require! (every? #(or (zero? (val %)) (contains? rates (key %))) goods) "You need a Fireplace or Cooking Hearth to cook animals.")
    (let [listeners (vec (filter #(some (fn [trigger] (= :convert-food (:event trigger))) (:triggers (cards/card %)))
                                 (cards/active-owned p)))
          converted (into {} (filter (comp pos? val) goods))
          bonus (if (crock? p) (quot (qualifying-cooked-units p goods) 2) 0)
          spit? (and (= :feeding (:phase context)) (some true? (cards/passives p :spit-roast))
                     (not= (:round context) (:spit-roast-round p)) (some #(pos? (get goods % 0)) animals))]
      (cond-> (gain (pay p goods) {:food (+ bonus (if spit? 1 0) (reduce + 0 (map (fn [[r n]] (* n (get rates r 0))) goods)))})
        spit? (assoc :spit-roast-round (:round context))
        (seq converted)
        (update :cooking-receipts (fnil conj []) (merge context {:goods converted :rates rates :listeners listeners})))))))
(defn bake
  ([p n] (bake p n {}))
  ([p n cook-with-bake] (bake p n cook-with-bake {}))
  ([p n cook-with-bake context]
   (require! (natural? n) "Choose a whole number of grain to bake.")
   (let [cook-with-bake (or cook-with-bake {})]
     (quantities! cook-with-bake (concat animals [:grain :vegetable]))
     (require! (or (pos? n) (every? zero? (vals cook-with-bake))) "Cooking with baking requires at least one grain to bake.")
     (if (zero? n) p
       (let [ovens (sort-by (fn [id] [(- (:bake (improvement-definition id))) (if (fireplace-family? id) 0 1)])
                            (baking-improvement-ids p))
             [food qualifying] (loop [left n ovens ovens food 0 qualifying 0]
                                 (if (zero? left) [food qualifying]
                                   (let [id (first ovens) oven (improvement-definition id)]
                                     (require! oven "Your improvements cannot bake that much grain in one action.")
                                     (let [used (min left (get oven :limit left))]
                                       (recur (- left used) (rest ovens) (+ food (* used (:bake oven)))
                                              (+ qualifying (if (fireplace-family? id) used 0)))))))
             cooked (qualifying-cooked-units p cook-with-bake)
             ;; convert-food pays the cooking bonus; add the remainder of this
             ;; single simultaneous batch here, including a baked/cooked pair.
             bonus (if (crock? p) (- (quot (+ qualifying cooked) 2) (quot cooked 2)) 0)]
         (-> p (pay {:grain n}) (convert-food cook-with-bake context) (gain {:food (+ food bonus)})))))))
(defn field-path [cell] (if (integer? cell) [:farm cell] [:card-fields cell]))
(defn card-stack-fields [cid {:keys [crop count field?]}]
  (into {} (for [index (range count)
                 :let [key (if (zero? index) cid (keyword (namespace cid) (str (name cid) "-" (inc index))))]]
             [key {:type (if field? :field :wood-stack) :allowed-crop crop :card cid :slot (inc index) :count 0}])))
(defn field-label [p cell]
  (if (integer? cell) (str "field " (inc cell))
    (let [field (get-in p (field-path cell)) cid (or (:card field) cell)]
      (str (:name (cards/card cid)) (when (:slot field) (str " · stack " (:slot field)))))))
(defn card-field-goods [p cid]
  (reduce (fn [goods [key field]]
            (if (= cid (or (:card field) key))
              (merge-with + goods (if (and (:crop field) (pos? (:count field 0))) {(:crop field) (:count field)} {})) goods))
          {} (:card-fields p)))
(defn field-crops [field]
  (merge-with + (or (:under-crops field) {})
              (if (and (:crop field) (pos? (:count field 0))) {(:crop field) (:count field)} {})))
(defn field-crop-count [field crop] (get (field-crops field) crop 0))
(defn normalize-field [field]
  (if (pos? (:count field 0)) field
    (if-let [[crop amount] (first (filter (comp pos? val) (:under-crops field)))]
      (cond-> (assoc field :crop crop :count amount)
        true (update :under-crops dissoc crop)
        (= 1 (count (:under-crops field))) (dissoc :under-crops))
      (assoc (dissoc field :crop :under-crops) :count 0))))
(defn remove-field-crop [player cell crop]
  (let [path (field-path cell) field (get-in player path)]
    (require! (pos? (field-crop-count field crop)) "That field does not contain this crop.")
    (assoc-in player path (normalize-field
                          (if (= crop (:crop field)) (update field :count dec)
                              (update-in field [:under-crops crop] dec))))))
(defn sow
  ([p plantings] (sow p plantings {}))
  ([p plantings modifiers]
   (let [fieldsman (if (some true? (cards/passives p :fieldsman)) ({1 2 2 1} (count plantings) 0) 0)
         smallholder? (and (get modifiers :smallholder? true) (some true? (cards/passives p :smallholder))
                           (<= (+ (count (tiles p :field)) (count (filter #(= :field (:type %)) (vals (:card-fields p))))) 2))]
  (reduce (fn [p {:keys [cell crop]}]
            (let [path (field-path cell) field (get-in p path)]
              (require! (and (or (cell? cell) (contains? (:card-fields p) cell))
                             (if (= :wood-stack (:type field)) (= :wood crop) (#{:grain :vegetable} crop))
                             (#{:field :wood-stack} (:type field)) (zero? (or (:count field) 0))
                             (or (nil? (:allowed-crop field)) (= crop (:allowed-crop field))))
                        "Sow an allowed crop in an empty field.")
              (assoc-in (pay p {crop 1}) path
                        (assoc field :crop crop :count
                               (max 1 (+ (if (= crop :vegetable) 2 3) fieldsman (if (and smallholder? (not= crop :wood)) 1 0) (reduce + 0 (cards/passives p :extra-sown-all))
                                         (reduce + 0 (cards/passives p (if (= crop :vegetable) :extra-sown-vegetable :extra-sown-grain)))
                                         (get-in modifiers [:stack-adjust crop] 0)
                                         (if (and (integer? cell) (some (set (tiles p :room)) (neighbors cell)))
                                           (reduce + 0 (map #(get % crop 0) (cards/passives p :adjacent-room-sow-bonus))) 0))))))) p plantings))))
(defn renovate
  ([p] (renovate p {}))
  ([p options] (renovate p options {}))
  ([p options modifiers]
   (let [material (if (and (= :wood (:house p)) (some true? (cards/passives p :direct-stone)) (= :stone (:renovate-to options))) :stone ({:wood :clay :clay :stone} (:house p)))
         _ (require! (not (some true? (cards/passives p :no-renovation))) "An improvement prevents further renovation.")
         _ (require! material "Your house is already stone.")
         base (apply min (count (tiles p :room))
                     (when (and (= :wood (:house p)) (= material :clay))
                       (cards/passives p :wood-clay-renovation-cost)))
         cost {material base :reed (if (some true? (cards/passives p :no-construction-reed)) 0 1)}
         cost (discounted cost {material (reduce + 0 (cards/passives p :renovation-discount))})
         cost (discounted cost (cond-> {:stone (reduce + 0 (cards/passives p :stone-discount))
                                       :reed (reduce + 0 (cards/passives p :reed-construction-discount))}
                                 (seq (cards/passives p :renovation-clay-discount))
                                 (assoc :clay (reduce + 0 (cards/passives p :renovation-clay-discount)))))
         cost (construction-replacements p cost options material true)
         _ (require! (not (:wood-carver? options)) "Wood Carver cannot discount renovation.")]
     (assoc (pay p (if (:free? modifiers) {} cost)) :house material))))
(defn family-capacity [p]
  (let [field-cells (set (tiles p :field)) pasture-cells (pasture-cells p)
        homekeeper? (and (some true? (cards/passives p :homekeeper)) (#{:clay :stone} (:house p))
                         (some #(and (some field-cells (neighbors %)) (some pasture-cells (neighbors %))) (tiles p :room)))]
    (+ (count (tiles p :room)) (reduce + 0 (cards/passives p :extra-room))
       (if (:off-site-room p) 1 0) (if homekeeper? 1 0)
       (if (and (cards/has? p :a127) (get-in p [:card-state (cards/id :a127) :room-active?])) 1 0))))
(defn grow
  ([p room-required?] (grow p room-required? {}))
  ([p room-required? options]
   (let [stewards (when (#{:clay :stone} (:house p))
                    (filter #(and (get-in (cards/card %) [:passive :next-growth-without-room])
                                  (not (get-in p [:card-state % :growth-used?]))) (cards/active-owned p)))
         adopt? (if (contains? options :adopt-reed-hut?) (:adopt-reed-hut? options)
                    (and (:reed-hut? p) (zero? (unused-person-tokens p))))]
     (require! (if adopt? (and (:reed-hut? p) (not (:reed-hut-acting? p))) (pos? (unused-person-tokens p)))
               (if adopt? "Another person must bring the Reed Hut occupant into the family." "No person remains in your supply."))
     (require! (or (seq stewards) (not room-required?) (> (family-capacity p) (:family p))) "Family growth needs an empty room.")
     (reduce #(assoc-in %1 [:card-state %2 :growth-used?] true)
             (cond-> (-> p (update :family inc) (update :newborn inc))
               (and adopt? (not (:reed-hut-placed? p))) (update :remaining #(max 0 (dec %)))
               adopt? (dissoc :reed-hut? :reed-hut-placed? :reed-hut-discard?)) stewards))))
(defn manufacturer? [p id]
  (and (#{:joinery :pottery :basketry} id) (#{:clay :stone} (:house p))
       (some true? (cards/passives p :manufacturer))))
(defn major-as-minor? [p id]
  (boolean (and (improvements id) (or (manufacturer? p id)
                                     (some #(contains? % id) (cards/passives p :major-as-minor))))))
(defn manufacturer-discounts [p id cost]
  (when (manufacturer? p id)
    (let [resources [:wood :clay :reed :stone]
          choices (for [one resources :when (pos? (get cost one 0))
                        two resources :when (pos? (- (get cost two 0) (if (= one two) 1 0)))]
                    (frequencies [one two]))]
      (vec (distinct (if (seq choices) choices
                        [(select-keys cost resources)]))))))
(defn improvement-payment-options [p id]
  (for [discount (or (seq (manufacturer-discounts p id (:cost (improvements id)))) [nil])
        carve? (if (and (some true? (cards/passives p :wood-carver)) (not (:wood-carver-used? p))) [false true] [false])]
    (cond-> {} discount (assoc :manufacturer-discount discount) carve? (assoc :wood-carver? true))))
(defn hand-improvement-discounts [p card]
  (let [reed (count (filter #(contains? % (:id card)) (cards/passives p :reed-discount-for-improvements)))]
    (cond-> {:wood (if (cards/has? p :a075) 1 0)
             :stone (reduce + 0 (cards/passives p :stone-discount))}
      (seq (cards/passives p :improvement-clay-discount))
      (assoc :clay (reduce + 0 (cards/passives p :improvement-clay-discount)))
      (pos? reed) (assoc :reed reed))))
(defn improvement-discount-resources
  "The printed building materials eligible for this card's chosen discount."
  [p id]
  (when (some #(contains? % id) (cards/passives p :choice-improvement-discount))
    (let [cost (if-let [major (improvements id)] (:cost major) (:printed-cost (cards/card id)))]
      (filterv #(pos? (get cost % 0)) [:wood :clay :reed :stone]))))
(defn special-improvement-cost [p id cost options]
  (let [manufactured (manufacturer-discounts p id cost)
        selected (when (seq manufactured)
                   (or (:manufacturer-discount options)
                       (first (filter #(every? (fn [[r n]] (>= (amount p r) n)) (discounted cost %)) manufactured))
                       (first manufactured)))
        _ (when (:manufacturer-discount options)
            (require! (some #{selected} manufactured) "Manufacturer removes two available building materials of your choice."))
        cost (if selected (discounted cost selected) cost)
        _ (when (:wood-carver? options)
            (require! (and (some true? (cards/passives p :wood-carver)) (not (:wood-carver-used? p)) (pos? (:wood cost 0)))
                      "Wood Carver can reduce a positive wood cost once per round."))
        cost (cond-> cost (:wood-carver? options) (update :wood dec))
        allowed (improvement-discount-resources p id)
        chosen (:discount-resource options)
        _ (when chosen (require! (some #{chosen} allowed) "Choose a printed building resource eligible for this discount."))
        candidates (or (seq (filter #(pos? (get cost % 0)) allowed)) allowed)
        chosen (or chosen
                   (first (filter (fn [resource]
                                    (every? (fn [[r n]] (>= (amount p r) n)) (discounted cost {resource 1}))) candidates))
                   (first candidates))]
    (if (some #(contains? % id) (cards/passives p :free-improvements)) {}
      (if chosen (discounted cost {chosen 1}) cost))))
(defn improvement-cost
  ([p improvement upgrade] (improvement-cost p improvement upgrade {}))
  ([p improvement upgrade modifiers]
   (if upgrade {}
     (special-improvement-cost p improvement
       (discounted (or (:cost modifiers) (some #(get % improvement) (cards/passives p :fixed-improvement-costs)) (:cost (improvements improvement)))
                   (update (hand-improvement-discounts p (assoc (improvements improvement) :id improvement))
                           :stone + (if (cards/has? p :b095) (max 0 (- (count (tiles p :room)) 2)) 0))) modifiers))))
(defn build-improvement
  ([g pid options] (build-improvement g pid options {}))
  ([g pid {:keys [improvement upgrade bake-grain cook-with-bake] :as options} modifiers]
   (let [card (improvements improvement) p (get-in g [:players pid])]
     (require! (and card ((:available-improvements g) improvement)) "That improvement is not available.")
     (when upgrade (require! (and (:upgrade card) (some #{upgrade} (fireplace-upgrades p))) "Only your Fireplace can be exchanged for a Cooking Hearth."))
     (when (and (pos? (or bake-grain 0)) (not (:oven card))) (require! false "Only a new oven grants an immediate Bake Bread action."))
     (let [p' (-> (pay p (improvement-cost p improvement upgrade (merge (select-keys options [:discount-resource :manufacturer-discount :wood-carver?]) modifiers)))
                  (cond-> (and (:wood-carver? options) (not upgrade)) (assoc :wood-carver-used? true))
                  (update :improvements #(cond-> (conj % improvement) upgrade (disj upgrade)))
                  (cond-> (and upgrade (not (improvements upgrade)))
                    (update :minors #(vec (remove #{upgrade} %))))
                  (cond-> (= improvement :well) (assoc :well-rounds (set (range (inc (:round g)) (min 15 (+ 6 (:round g))))))))
           p' (if (:oven card) (bake p' (or bake-grain 0) cook-with-bake) p')]
       (-> g (assoc-in [:players pid] p')
           (update :available-improvements #(cond-> (disj % improvement) (and upgrade (improvements upgrade)) (conj upgrade))))))))

(defn player [id name food]
  {:id id :name name :family 2 :newborn 0 :remaining 2 :house :wood
   :goods (assoc (zipmap resources (repeat 0)) :food food) :begging 0
   :farm (mapv #(hash-map :type (if (#{5 10} %) :room :empty)) (range 15))
   :fences #{} :improvements #{} :well-rounds #{}})
(defn base-actions
  ([n] (base-actions n :beginner))
  ([n variant]
  (cond-> [:wood :clay :reed :fishing :grain :labor :plow :build :meeting :side-job]
    (>= n 3) (into [:grove :hollow :market])
    (>= n 4) (into [:copse :traveling])
    (= n 5) (into [:riverbank :house-building :modest-growth :animal-market])
    (= variant :cards) (#(mapv (fn [space] (if (= space :side-job) :lessons space)) %))
    (and (= variant :cards) (<= 3 n 4)) (conj :lessons-extra)
    (and (= variant :cards) (= n 5)) (into [:lessons-copse :lessons-growth]))))
(def linked-spaces {:lessons-copse :copse :copse :lessons-copse
                    :lessons-growth :modest-growth :modest-growth :lessons-growth
                    :house-building :traveling :traveling :house-building})
(defn blocked? [g space]
  (and (= 5 (count (:players g))) (contains? (:occupied g) (linked-spaces space))))
(defn available-space? [g space]
  (and (some #{space} (:active g)) (not (blocked? g space))
       (or (not= space :modest-growth) (>= (:round g) 5))))
(defn action-definition [g id]
  (cond-> (or (actions id)
              (case (:action-space (cards/card id))
                :master-forester {:name "Master Forester" :accumulate true :gain {:wood 2} :group :resources}
                :clay-digger {:name "Clay Digger" :accumulate true :gain {:clay 1} :group :resources}
                :clay-deposit {:name "Clay Deposit" :gain {:clay 5} :group :resources}
                :tavern {:name "Tavern" :gain {:food 3} :group :resources}
                nil))
    (and (= id :wood) (= 1 (count (:players g)))) (assoc :gain {:wood 2})
    (and (= id :hollow) (= 4 (count (:players g)))) (assoc :gain {:clay 2})
    (and (= id :hollow) (= 5 (count (:players g)))) (assoc :gain {:clay 3})
    (and (= id :market) (= 5 (count (:players g)))) (assoc :gain {:reed 1 :wood 1 :stone 1} :description "Take 1 reed, 1 wood, and 1 stone.")
    (and (= :cards (:variant g)) (= id :meeting)) (assoc :gain nil :accumulate false :description "Become starting player, then optionally play a minor improvement.")
    (and (= :cards (:variant g)) (= id :improvement)) (assoc :name "Major or Minor Improvement" :description "Build a major improvement or play a minor improvement from your hand.")))
(defn accumulation-spaces [g]
  (filterv #(:accumulate (action-definition g %))
           (distinct (concat (:active g)
                             (for [owner (:players g) cid (cards/active-owned owner)
                                   :when (#{:master-forester :clay-digger} (:action-space (cards/card cid)))] cid)))))
(defn log-event [g message] (update g :log conj {:round (:round g) :text message}))
(defn holiday-house? [p round]
  (and (= round 14) (some true? (cards/passives p :holiday-house))))
(defn begin-round [g]
  (let [r (inc (:round g)) ids (vec (distinct (conj (:active g) (nth (:round-order g) (dec r)))))
        g (assoc g :round r :phase :work :active ids :occupied {} :turn (:starting-player g))]
    (-> (reduce (fn [g id]
                  (let [a (action-definition g id)]
                    (if (:accumulate a) (update-in g [:piles id] #(merge-with + (or % {}) (:gain a))) g))) g ids)
        (update :players #(mapv (fn [p]
                                 (let [food (+ (if (contains? (:well-rounds p) r) 1 0) (get-in p [:well-deliveries r] 0))]
                                   (cond-> (assoc p :remaining (if (holiday-house? p r) 0 (+ (:family p) (reed-hut-count p))) :newborn 0 :guests 0
                                                   :reed-hut-placed? false :reed-hut-acting? false :wood-carver-used? false)
                                     (pos? food) (gain {:food food})))) %))
        (log-event (str "Round " r " begins · " (:name (actions (last ids))))))))
(defn new-game
  ([names] (new-game names (vec (mapcat shuffle stages)) 0))
  ([names round-order starting-player]
   (require! (and (<= 1 (count names) 5) (every? #(and (string? %) (not (str/blank? %))) names) (= (count names) (count (set names)))) "Choose one to five distinct player names.")
   (require! (and (integer? starting-player) (< -1 starting-player (count names))) "Invalid starting player.")
   (require! (and (= 14 (count round-order)) (= (map set stages) (map set (map #(subvec (vec round-order) %1 %2) [0 4 7 9 11 13] [4 7 9 11 13 14])))) "Round cards must stay within their stages.")
   (begin-round
    {:version 1 :round 0 :phase :work :turn starting-player :starting-player starting-player
     :players (mapv (fn [i name] (player i name (cond (= 1 (count names)) 0 (= i starting-player) 2 :else 3))) (range) names)
     :active (base-actions (count names)) :round-order (vec round-order) :piles {} :occupied {}
     :available-improvements (set (keys improvements)) :log []})))

(defn food-needed [g p]
  (let [adults (- (:family p) (:newborn p)) caps (cards/passives p :adult-feeding-cap)
        rate (if (= 1 (count (:players g))) 3 2)
        family-food (if (seq caps) (+ (:family p) (min adults (apply min caps))) (+ (* rate adults) (:newborn p)))]
    (max 0 (- (+ family-food (* rate (reed-hut-count p)))
              (reduce + 0 (cards/passives p :harvest-food-discount))))))
(defn field-harvest [p]
  (reduce (fn [p cell]
            (let [path (field-path cell) {:keys [crop count]} (get-in p path)]
              (if (and crop (pos? (or count 0)))
                (cond-> (gain p {crop 1})
                  (not (and (= crop :vegetable) (some true? (cards/passives p :preserve-harvest-vegetables))))
                  (remove-field-crop cell crop)) p)))
          p (concat (range 15) (keys (:card-fields p)))))
(defn craft-stock [p resource]
  (+ (amount p resource)
     (if (= resource :wood) (reduce + 0 (map #(field-crop-count % :wood) (vals (:card-fields p)))) 0)))
(defn threshold-score [n thresholds] (if (< n (first thresholds)) -1 (count (filter #(>= n %) thresholds))))
(defn score [p]
  (let [crops (merge-with + (select-keys (:goods p) [:grain :vegetable])
                          (reduce #(merge-with + %1 (field-crops %2)) {} (concat (:farm p) (vals (:card-fields p)))))
        fenced (pasture-cells p)
        breakdown {:fields (threshold-score (count (tiles p :field)) [2 3 4 5])
                   :pastures (threshold-score (count (pastures p)) [1 2 3 4])
                   :grain (threshold-score (:grain crops) [1 4 6 8])
                   :vegetables (threshold-score (:vegetable crops) [1 2 3 4])
                   :sheep (threshold-score (amount p :sheep) [1 4 6 8])
                   :boar (threshold-score (amount p :boar) [1 3 5 7])
                   :cattle (threshold-score (amount p :cattle) [1 2 4 6])
                   :unused (- (max 0 (- (count (filter #(empty-cell? p %) (range 15))) (:paid-unused-spaces p 0))))
                   :stables (count (filter #(get-in p [:farm % :stable]) fenced))
                   :rooms (* ({:wood 0 :clay 1 :stone 2} (:house p)) (count (tiles p :room)))
                   :family (* 3 (:family p))
                   :improvements (reduce + 0 (map #(:points (improvements %)) (:improvements p)))
                   :bonus (reduce + 0 (for [id (:improvements p) :let [{:keys [craft bonus]} (improvements id)] :when (and craft (not (and (= id :joinery) (some true? (cards/passives p :sawmill)))))]
                                       (count (filter #(>= (craft-stock p craft) %) bonus))))
                   :begging (* -3 (:begging p))}]
    {:total (reduce + (vals breakdown)) :breakdown breakdown :tiebreak (reduce + (map #(amount p %) [:wood :clay :reed :stone]))}))
(defn finish [g]
  (let [scores (mapv score (:players g)) best (last (sort (map (juxt :total :tiebreak) scores)))
        winners (vec (keep-indexed #(when (= best ((juxt :total :tiebreak) %2)) %1) scores))]
    (-> g (assoc :phase :finished :scores scores :winners winners) (log-event "The final harvest is complete."))))
(defn finish-round [g] (if (= 14 (:round g)) (finish g) (begin-round g)))
(defn round-end [g]
  (if (harvest-rounds (:round g))
    (-> g (update :players #(mapv field-harvest %))
        (assoc :phase :feeding :turn (:starting-player g) :fed #{} :bred #{})
        (log-event "Harvest · fields harvested. Feed each family, then breed animals."))
    (finish-round g)))
(defn next-turn [g]
  (let [n (count (:players g)) current (:turn g)
        next (first (filter #(pos? (get-in g [:players % :remaining])) (map #(mod (+ current %) n) (range 1 (inc n)))))]
    (if (some? next) (assoc g :turn next) (round-end g))))
(defn plow-action-player
  "Apply the base field and a trusted, saved card plow plan before sowing."
  [g pid space cell]
  (let [plan (:plow-plan g)
        extra (when (and (= pid (:player plan)) (= space (:space plan))) (:cells plan))]
    (reduce #(place-tile %1 :field %2)
            (cond-> (get-in g [:players pid]) (and (seq extra) (:plow-maker? plan)) (pay {:food 1}))
            (concat (when (some? cell) [cell]) extra))))
(defn replaced-baking? [g pid space]
  (let [plan (:freshman-plan g)]
    (and (= pid (:player plan)) (= space (:space plan)) (some? (:card plan)))))
(defn renovation-history [g]
  ;; New games record every step, distinguishing Conservator's direct stone
  ;; renovation. Older saves lack that history, so retain their visible houses.
  (or (:renovation-history g)
      (into {} (map (fn [p] [(:id p) (case (:house p) :stone #{:clay :stone} :clay #{:clay} #{})]) (:players g)))))
(defn renovate-game
  ([g pid options] (renovate-game g pid options {}))
  ([g pid options modifiers]
   (let [history (renovation-history g) player (get-in g [:players pid])
         renovated (renovate player options modifiers) material (:house renovated)
         predecessors (count (filter #(contains? % material) (vals history)))
         bonus (if (some true? (cards/passives player :renovation-rank-stone)) (max 0 (- 3 predecessors)) 0)]
     (-> g (assoc-in [:players pid] (gain renovated {:stone bonus}))
         (assoc :renovation-history (update history pid (fnil conj #{}) material))))))
(defn grain-action-tolls [g pid space]
  (if (not= space :grain) g
    (let [owners (for [player (:players g) :when (some true? (cards/passives player :grain-action-toll))] (:id player))
          others (remove #{pid} owners)]
      (reduce (fn [state owner] (update-in state [:players owner] gain {:food (if (= owner pid) 1 2)}))
              (update-in g [:players pid] pay {:food (count others)}) owners))))
(defn apply-action [g pid {:keys [space cell rooms stables fences plantings bake-grain choice] :as command}]
  (let [a (action-definition g space) p (get-in g [:players pid])]
    (require! (= :work (:phase g)) "Finish the harvest or animal housing first.")
    (require! (available-space? g space) "That action is not available yet or its linked space is occupied.")
    (require! (not (contains? (:occupied g) space)) "That action space is occupied.")
    (require! (pos? (:remaining p)) "You have no workers left this round.")
    (let [g (grain-action-tolls g pid space)
          g (cond
              (:gain a) (let [goods (if (:accumulate a) (get-in g [:piles space]) (:gain a))]
                          (cond-> (update-in g [:players pid] gain (merge-with + goods (:bonus a)))
                            (:accumulate a) (assoc-in [:piles space] (zipmap (keys goods) (repeat 0)))
                            (= space :meeting) (assoc :starting-player pid)))
              :else
              (case space
                :market (do (require! (or (= 4 (count (:players g))) (#{:reed :stone} choice)) "Choose reed or stone.")
                            (update-in g [:players pid] gain (if (= 4 (count (:players g))) {:reed 1 :stone 1 :food 1} {choice 1 :food 1})))
                :plow (do (require! (some? cell) "Choose a field to plow.")
                          (assoc-in g [:players pid] (plow-action-player g pid space cell)))
                :build (do (require! (or (seq rooms) (seq stables)) "Select at least one room or stable.")
                           (update-in g [:players pid] #(-> % (build-rooms rooms (cond-> command (or (empty? rooms) (not= :wood (:house %))) (dissoc :wood-carver?))) (build-stables stables 2 (cond-> command (and (seq rooms) (= :wood (:house %))) (dissoc :wood-carver?))))))
                :house-building (do (require! (and (seq rooms) (empty? stables)) "Build at least one room; this space does not build stables.")
                                    (update-in g [:players pid] build-rooms rooms command))
                :animal-market (do (require! (some #{choice} animals) "Choose sheep, boar, or cattle.")
                                   (update-in g [:players pid] #(-> % (pay (if (= choice :cattle) {:food 1} {}))
                                                                    (gain (merge {choice 1} (when (= choice :sheep) {:food 1}))))))
                :fences (update-in g [:players pid] build-fences fences (select-keys command [:fence-material :wood-carver?]))
                :side-job (do (require! (and (<= (count stables) 1) (or (seq stables) (pos? (or bake-grain 0)) (replaced-baking? g pid space))) "Build one stable and/or bake bread.")
                              (update-in g [:players pid] #(-> % (build-stables stables 1 command) (bake (or bake-grain 0) (:cook-with-bake command)))))
                :sow-bake (do (require! (or (seq plantings) (pos? (or bake-grain 0)) (replaced-baking? g pid space)) "Sow at least one field or bake bread.")
                             (update-in g [:players pid] #(-> % (sow plantings (select-keys command [:smallholder?])) (bake (or bake-grain 0) (:cook-with-bake command)))))
                :cultivation (do (require! (or (some? cell) (seq plantings) (and (= pid (get-in g [:plow-plan :player])) (= space (get-in g [:plow-plan :space])) (seq (get-in g [:plow-plan :cells])))) "Plow a field and/or sow.")
                                 (assoc-in g [:players pid] (sow (plow-action-player g pid space cell) plantings (select-keys command [:smallholder?]))))
                :growth (update-in g [:players pid] grow true command)
                :modest-growth (update-in g [:players pid] grow true command)
                :urgent-growth (update-in g [:players pid] grow false command)
                :improvement (build-improvement g pid command)
                :renovate (cond-> (renovate-game g pid (cond-> command (or (:card command) (:improvement command)) (dissoc :wood-carver?))) (:improvement command) (build-improvement pid command))
                :redevelop (cond-> (renovate-game g pid (cond-> command (seq fences) (dissoc :wood-carver?))) (seq fences) (update-in [:players pid] build-fences fences (select-keys command [:fence-material :wood-carver?])))
                (throw (ex-info "Unknown action." {:type :illegal-command}))))
          g (-> g (update-in [:players pid :remaining] dec) (assoc-in [:occupied space] pid)
                (log-event (str (:name p) " · " (:name a))))]
      (if (:defer-advance? g) g (if (animal-housing (get-in g [:players pid])) (next-turn g) (assoc g :phase :housing))))))
(defn settle-animals [g pid {:keys [keep cook?]}]
  (require! (= :housing (:phase g)) "No animal housing decision is pending.")
  (quantities! keep animals)
  (require! (= (set animals) (set (keys keep))) "Choose how many of each animal to keep.")
  (let [p (get-in g [:players pid]) surplus (into {} (for [a animals] [a (- (amount p a) (keep a))]))]
    (quantities! surplus animals)
    (let [p' (if cook? (convert-food p surplus) (pay p surplus))]
      (require! (animal-housing p') "Those animals do not fit in the available housing.")
      (-> g (assoc-in [:players pid] p') (assoc :phase :work) next-turn))))
(defn plane? [p] (some true? (cards/passives p :plane)))
(defn craft-food [p craft plane-extra?]
  (require! (= (count craft) (count (set craft))) "A workshop can be used once per harvest.")
  (require! (or (not plane-extra?) (and (plane? p) (some #{:joinery} craft))) "Plane needs the Joinery to convert a second wood.")
  (reduce (fn [p id]
            (let [{:keys [craft rate]} (improvements id)
                  plane (and (= id :joinery) (plane? p))]
              (require! (and ((:improvements p) id) craft) "You do not own that craft improvement.")
              (gain (pay p {craft (if (and plane plane-extra?) 2 1)})
                    {:food (+ rate (if plane (if plane-extra? 2 1) 0))}))) p craft))
(defn feed [g pid {:keys [convert craft plane-extra?] :or {convert {} craft []}}]
  (require! (= :feeding (:phase g)) "It is not the feeding phase.")
  (let [p (convert-food (get-in g [:players pid]) convert (select-keys g [:phase :round]))
        p (craft-food p craft plane-extra?)
        needed (food-needed g p) missing (max 0 (- needed (amount p :food)))
        g (-> g (assoc-in [:players pid] (-> p (pay {:food (min needed (amount p :food))}) (update :begging + missing)))
              (update :fed conj pid) (log-event (str (:name p) " fed their family" (when (pos? missing) (str " · " missing " begging marks")))))
        next (first (remove (:fed g) (or (:feeding-order g)
                                        (map #(mod (+ pid %) (count (:players g))) (range 1 (inc (count (:players g))))))))]
    (if (= (count (:fed g)) (count (:players g)))
      (-> g (assoc :phase :breeding :turn (first (remove (:bred g) (map #(mod (+ (:starting-player g) %) (count (:players g))) (range (count (:players g)))))))
          ;; Feeding is a shared phase. Track it for everyone, even before
          ;; Woolgrower is played, and make the new capacity available to breed.
          (update :players #(mapv (fn [player] (update player :completed-feeding-phases (fnil inc 0))) %)))
      (assoc g :turn next))))
(defn breeding-options [p]
  (into {} (for [animal animals :when (>= (amount p animal) 2)]
             [animal (if (and (= animal :sheep) (>= (amount p :sheep) 4)
                              (some true? (cards/passives p :double-sheep-breeding))) 2 1)])))
(defn breeding-choices [p]
  (let [options (breeding-options p)]
    (for [sheep (range (inc (get options :sheep 0)))
          boar (range (inc (get options :boar 0)))
          cattle (range (inc (get options :cattle 0)))]
      (into {} (filter (comp pos? val) {:sheep sheep :boar boar :cattle cattle})))))
(defn default-breeding [p]
  (or (first (filter #(animal-housing (gain p %))
                     (sort-by #(reduce + 0 (vals %)) > (breeding-choices p)))) {}))
(defn breed [g pid {:keys [offspring]}]
  (require! (= :breeding (:phase g)) "It is not the breeding phase.")
  (let [p (get-in g [:players pid]) options (breeding-options p) offspring (or offspring options)]
    (quantities! offspring animals)
    (require! (every? (fn [[a n]] (<= n (get options a 0))) offspring) "Choose offspring within the amounts allowed by your animals and cards.")
    (let [p' (gain p offspring)]
      (require! (animal-housing p') "There is not enough room for those offspring.")
      ;; Breeding is mandatory whenever the additional animal can be housed.
      (doseq [[a n] options :when (< (get offspring a 0) n)]
        (require! (nil? (animal-housing (gain p' {a 1}))) "You must breed animals when you have room for them."))
      (let [g (-> g (assoc-in [:players pid] p') (update :bred conj pid)
                  (log-event (str (:name p) " · breeding complete")))]
        (if (= (count (:bred g)) (count (:players g))) (if (:defer-advance? g) (assoc g :phase :finish-round) (finish-round g))
            (assoc g :turn (first (remove (:bred g) (map #(mod (+ pid %) (count (:players g)))
                                                       (range 1 (inc (count (:players g)))))))))))))
(defn command [g pid {:keys [type] :as cmd}]
  (require! (not= :finished (:phase g)) "This game has finished.")
  (require! (= pid (:turn g)) "It is not your turn.")
  (case type
    :action (apply-action g pid cmd)
    :house-animals (settle-animals g pid cmd)
    :feed (feed g pid cmd)
    :breed (breed g pid cmd)
    :convert (do (require! (#{:work :feeding} (:phase g)) "Food conversion is unavailable during breeding or housing.")
                 (require! (some pos? (vals (:goods cmd))) "Choose goods to convert.")
                 (-> g (update-in [:players pid] convert-food (:goods cmd))
                     (log-event (str (get-in g [:players pid :name]) " converted goods to food."))))
    (throw (ex-info "Unknown command." {:type :illegal-command}))))
(defn public-state [g]
  ;; Future round order is private. Clients only see the cards already revealed.
  (-> g (dissoc :round-order :deck-order :minor-order :minor-deck :draw :card-rng) (update :players #(mapv (fn [p] (dissoc p :hand)) %))))
