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
(defn room-cost [p options]
  (let [material (:house p)
        base (min 5 (if (cards/has? p :b126) 3 5) (if (and (= material :wood) (cards/has? p :b013)) 2 5))
        cost {material base :reed 2}
        cost (if (and (:frame? options) (cards/has? p :a123) (#{:clay :stone} material))
               (-> cost (update material - 2) (update :wood (fnil inc 0))) cost)
        cost (if (and (:brushwood? options) (cards/has? p :b145))
               (-> cost (assoc :reed 0) (update :wood (fnil inc 0))) cost)]
    (discounted cost (if (cards/has? p :a143) {:stone 1} {}))))
(defn build-rooms
  ([p cells] (build-rooms p cells {}))
  ([p cells options] (build-rooms p cells options {}))
  ([p cells options {:keys [room-discount]}]
   (let [cost (apply merge-with + {} (map #(discounted (room-cost p (get-in options [:room-options %] options)) room-discount) cells))
         reductions (when (and (>= (count cells) 2) (some true? (cards/passives p :batch-room-discount)))
                      {:reed 2 (:house p) ({:wood 2 :clay 3 :stone 4} (:house p))})]
     (reduce #(place-tile %1 :room %2) (pay p (discounted cost reductions)) cells))))
(defn fences-available [p] (- 15 (count (:fences p)) (:discarded-fences p 0)))
(defn stables-available [p]
  (- 4 (count (filter :stable (:farm p))) (reduce + 0 (vals (:reserved-stables p)))))
(defn family-limit [p] (- 5 (:removed-family p 0)))
(defn build-stables [p cells cost]
  (reduce (fn [p i]
            (require! (and (cell? i) (= :empty (get-in p [:farm i :type])) (not (get-in p [:farm i :stable]))) "A stable needs an empty space or pasture without another stable.")
            (require! (pos? (stables-available p)) "No unreserved stable remains in your supply.")
            (assoc-in (pay p {:wood cost}) [:farm i :stable] true)) p cells))
(defn build-fences
  ([p additions] (build-fences p additions {}))
  ([p additions options]
  (let [new (set additions) old (set (:fences p))]
    (require! (and (seq new) (= (count new) (count additions)) (set/subset? new all-edges) (empty? (set/intersection new old))) "Select new fence edges.")
    (require! (<= (count new) (fences-available p)) "Not enough fence pieces remain in your supply.")
    (let [material (if (and (= :clay (:fence-material options)) (cards/has? p :a016)) :clay :wood)
          cost (max 0 (- (count new) (if (cards/has? p :a088) 3 0) (or (:free-fences options) 0)))
          p' (assoc (pay p {material cost}) :fences (set/union old new))
          regions (pastures p') covered (set (mapcat identity regions))
          borders (set (mapcat (fn [region] (->> region (mapcat edges) frequencies (filter #(= 1 (val %))) (map key))) regions))]
      (require! (and (seq covered) (connected? covered)) "Pastures must be adjacent and fully enclosed.")
      (require! (every? #(= :empty (get-in p [:farm % :type])) covered) "Pastures cannot contain rooms or fields.")
      (require! (set/subset? (:fences p') borders) "Every fence must border a fully enclosed pasture.")
      p'))))
(defn accommodations [p]
  (let [ps (pastures p) fenced (set (mapcat identity ps))]
    (into (if (cards/has? p :a086) (mapv #(hash-map :cells [%] :capacity 1 :kind :pet) (tiles p :room)) [{:cells [5] :capacity 1 :kind :pet}])
          (concat
           (for [region ps :let [stables (count (filter #(get-in p [:farm % :stable]) region))]]
             {:cells region :capacity (+ (* 2 (count region) (reduce * 1 (repeat stables 2))) (if (cards/has? p :a012) 2 0)) :kind :pasture})
           (for [i (range 15) :when (and (get-in p [:farm i :stable]) (not (fenced i)))]
             {:cells [i] :capacity 1 :kind :stable})
           (when (some true? (cards/passives p :boar-on-empty-fields))
             (for [i (tiles p :field) :when (zero? (get-in p [:farm i :count] 0))]
               {:cells [i] :capacity 1 :kind :field :allowed-animals #{:boar}}))
           (for [_ (range (get p :card-animal-capacity 0))]
             {:cells [] :capacity 1 :kind :card :card (cards/id :b169)})
           (when (and (cards/has? p :a148) (pos? (:completed-feeding-phases p 0)))
             [{:cells [] :capacity (:completed-feeding-phases p) :kind :card
               :card (cards/id :a148) :allowed-animals #{:sheep}}])))))
(defn organic-housing
  "Choose a legal arrangement maximizing the combined pasture card bonuses.
   Memoization is local to this calculation; no game state is retained."
  [p]
  (let [spaces (vec (sort-by :capacity > (accommodations p))) cache (atom {})]
    (letfn [(fit [index remaining]
              (let [key [index remaining]]
                (if-let [cached (find @cache key)] (val cached)
                  (let [result
                        (cond
                          (every? zero? remaining) {:points 0 :placement []}
                          (>= index (count spaces)) nil
                          (> (reduce + remaining) (reduce + (map :capacity (subvec spaces index)))) nil
                          :else
                          (let [space (spaces index)
                                candidates
                                (cons (fit (inc index) remaining)
                                      (for [animal-index (range 3)
                                            :when ((get space :allowed-animals (set animals)) (nth animals animal-index))
                                            n (range 1 (inc (min (:capacity space) (remaining animal-index))))
                                            :let [tail (fit (inc index) (update remaining animal-index - n))]
                                            :when tail]
                                        (-> tail
                                            (update :points + (+ (if (and (cards/has? p :b098) (= :pasture (:kind space)) (>= (- (:capacity space) n) 3)) 1 0)
                                                                 (if (and (cards/has? p :a134) (= :pasture (:kind space)) (= (:capacity space) n)) 1 0)))
                                            (update :placement conj (assoc space :animal (nth animals animal-index) :count n)))))]
                            (reduce (fn [best candidate] (if (and candidate (or (nil? best) (> (:points candidate) (:points best)))) candidate best)) nil candidates)))]
                    (swap! cache assoc key result)
                    result))))]
      (:placement (fit 0 (mapv #(amount p %) animals))))))
(defn animal-housing
  "Find a legal assignment of single-species compartments, or nil."
  [p]
  (letfn [(fit [spaces remaining result]
            (if (every? zero? (vals remaining)) result
                (when-let [space (first spaces)]
                  (or (some (fn [a]
                              (when (and (pos? (get remaining a 0)) ((get space :allowed-animals (set animals)) a))
                                (let [n (min (:capacity space) (remaining a))]
                                  (fit (rest spaces) (update remaining a - n)
                                       (conj result (assoc space :animal a :count n)))))) animals)
                      (fit (rest spaces) remaining result)))))]
    (if (or (cards/has? p :b098) (cards/has? p :a134)) (organic-housing p)
        (fit (sort-by :capacity > (accommodations p)) (select-keys (:goods p) animals) []))))
(defn improvement-definition [id]
  (or (improvements id) (when-let [card (cards/card id)] (merge card (:passive card)))))
(defn owned-improvement-ids [p] (concat (:improvements p) (:minors p)))
(defn baking-improvement-ids [p] (filter #(pos? (:bake (improvement-definition %) 0)) (owned-improvement-ids p)))
(defn fireplace-upgrades [p]
  (concat (sort (set/intersection (:improvements p) #{:fireplace-2 :fireplace-3}))
          (filter #(get-in (cards/card %) [:passive :fireplace]) (:minors p))))
(defn returnable-cooking [p] (sort (set/intersection (:improvements p) #{:fireplace-2 :fireplace-3 :hearth-4 :hearth-5})))
(defn cook-rates [p]
  (apply merge-with max {:grain 1 :vegetable 1} (map #(get (improvement-definition %) :cook {}) (owned-improvement-ids p))))
(defn convert-food [p goods]
  (quantities! goods (concat animals [:grain :vegetable]))
  (let [rates (cook-rates p)]
    (require! (every? #(or (zero? (val %)) (contains? rates (key %))) goods) "You need a Fireplace or Cooking Hearth to cook animals.")
    (gain (pay p goods) {:food (reduce + 0 (map (fn [[r n]] (* n (get rates r 0))) goods))})))
(defn bake [p n]
  (require! (natural? n) "Choose a whole number of grain to bake.")
  (if (zero? n) p
      (let [ovens (sort-by :bake > (map improvement-definition (baking-improvement-ids p)))
            food (loop [left n ovens ovens food 0]
                   (if (zero? left) food
                       (let [oven (first ovens)]
                         (require! oven "Your improvements cannot bake that much grain in one action.")
                         (let [used (min left (get oven :limit left))]
                           (recur (- left used) (rest ovens) (+ food (* used (:bake oven))))))))]
        (gain (pay p {:grain n}) {:food food}))))
(defn field-path [cell] (if (integer? cell) [:farm cell] [:card-fields cell]))
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
(defn sow [p plantings]
  (reduce (fn [p {:keys [cell crop]}]
            (let [path (field-path cell) field (get-in p path)]
              (require! (and (or (cell? cell) (contains? (:card-fields p) cell)) (#{:grain :vegetable} crop)
                             (= :field (:type field)) (zero? (or (:count field) 0))
                             (or (nil? (:allowed-crop field)) (= crop (:allowed-crop field))))
                        "Sow an allowed crop in an empty field.")
              (assoc-in (pay p {crop 1}) path (assoc field :crop crop :count (if (= crop :grain) 3 2))))) p plantings))
(defn renovate
  ([p] (renovate p {}))
  ([p options] (renovate p options {}))
  ([p options modifiers]
   (let [material (if (and (= :wood (:house p)) (cards/has? p :a087) (= :stone (:renovate-to options))) :stone ({:wood :clay :clay :stone} (:house p)))
         _ (require! (not (some true? (cards/passives p :no-renovation))) "An improvement prevents further renovation.")
         _ (require! material "Your house is already stone.")
         cost {material (count (tiles p :room)) :reed 1}
         cost (if (and (:frame? options) (cards/has? p :a123) (>= (get cost material) 2))
                (-> cost (update material - 2) (assoc :wood 1)) cost)
         cost (if (and (:brushwood? options) (cards/has? p :b145))
                (-> cost (assoc :reed 0) (update :wood (fnil inc 0))) cost)
         cost (discounted cost (if (cards/has? p :a143) {:stone 1} {}))]
     (assoc (pay p (if (:free? modifiers) {} cost)) :house material))))
(defn family-capacity [p]
  (let [field-cells (set (tiles p :field)) pasture-cells (pasture-cells p)
        homekeeper? (and (some true? (cards/passives p :homekeeper)) (#{:clay :stone} (:house p))
                         (some #(and (some field-cells (neighbors %)) (some pasture-cells (neighbors %))) (tiles p :room)))]
    (+ (count (tiles p :room)) (reduce + 0 (cards/passives p :extra-room))
       (if (:off-site-room p) 1 0) (if homekeeper? 1 0)
       (if (and (cards/has? p :a127) (get-in p [:card-state (cards/id :a127) :room-active?])) 1 0))))
(defn grow [p room-required?]
  (require! (< (:family p) (family-limit p)) "No person remains in your supply.")
  (require! (or (not room-required?) (> (family-capacity p) (:family p))) "Family growth needs an empty room.")
  (-> p (update :family inc) (update :newborn inc)))
(defn improvement-cost
  ([p improvement upgrade] (improvement-cost p improvement upgrade {}))
  ([p improvement upgrade modifiers]
   (if upgrade {} (discounted (or (:cost modifiers) (:cost (improvements improvement)))
                              {:wood (if (cards/has? p :a075) 1 0)
                               :stone (+ (if (cards/has? p :a143) 1 0)
                                         (if (cards/has? p :b095) (max 0 (- (count (tiles p :room)) 2)) 0))}))))
(defn build-improvement
  ([g pid options] (build-improvement g pid options {}))
  ([g pid {:keys [improvement upgrade bake-grain]} modifiers]
   (let [card (improvements improvement) p (get-in g [:players pid])]
     (require! (and card ((:available-improvements g) improvement)) "That improvement is not available.")
     (when upgrade (require! (and (:upgrade card) (some #{upgrade} (fireplace-upgrades p))) "Only your Fireplace can be exchanged for a Cooking Hearth."))
     (when (and (pos? (or bake-grain 0)) (not (:oven card))) (require! false "Only a new oven grants an immediate Bake Bread action."))
     (let [p' (-> (pay p (improvement-cost p improvement upgrade modifiers))
                  (update :improvements #(cond-> (conj % improvement) upgrade (disj upgrade)))
                  (cond-> (and upgrade (not (improvements upgrade)))
                    (update :minors #(vec (remove #{upgrade} %))))
                  (cond-> (= improvement :well) (assoc :well-rounds (set (range (inc (:round g)) (min 15 (+ 6 (:round g))))))))
           p' (if (:oven card) (bake p' (or bake-grain 0)) p')]
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
  (cond-> (actions id)
    (and (= id :wood) (= 1 (count (:players g)))) (assoc :gain {:wood 2})
    (and (= id :hollow) (= 4 (count (:players g)))) (assoc :gain {:clay 2})
    (and (= id :hollow) (= 5 (count (:players g)))) (assoc :gain {:clay 3})
    (and (= id :market) (= 5 (count (:players g)))) (assoc :gain {:reed 1 :wood 1 :stone 1} :description "Take 1 reed, 1 wood, and 1 stone.")
    (and (= :cards (:variant g)) (= id :meeting)) (assoc :gain nil :accumulate false :description "Become starting player, then optionally play a minor improvement.")
    (and (= :cards (:variant g)) (= id :improvement)) (assoc :name "Major or Minor Improvement" :description "Build a major improvement or play a minor improvement from your hand.")))
(defn log-event [g message] (update g :log conj {:round (:round g) :text message}))
(defn begin-round [g]
  (let [r (inc (:round g)) ids (conj (:active g) (nth (:round-order g) (dec r)))
        g (assoc g :round r :phase :work :active ids :occupied {} :turn (:starting-player g))]
    (-> (reduce (fn [g id]
                  (let [a (action-definition g id)]
                    (if (:accumulate a) (update-in g [:piles id] #(merge-with + (or % {}) (:gain a))) g))) g ids)
        (update :players #(mapv (fn [p] (cond-> (assoc p :remaining (:family p) :newborn 0)
                                            ((:well-rounds p) r) (gain {:food 1}))) %))
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

(defn food-needed [g p] (+ (* (if (= 1 (count (:players g))) 3 2) (- (:family p) (:newborn p))) (:newborn p)))
(defn field-harvest [p]
  (reduce (fn [p cell]
            (let [path (field-path cell) {:keys [crop count]} (get-in p path)]
              (if (and crop (pos? (or count 0))) (-> p (gain {crop 1}) (remove-field-crop cell crop)) p)))
          p (concat (range 15) (keys (:card-fields p)))))
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
                   :unused (- (count (filter #(empty-cell? p %) (range 15))))
                   :stables (count (filter #(get-in p [:farm % :stable]) fenced))
                   :rooms (* ({:wood 0 :clay 1 :stone 2} (:house p)) (count (tiles p :room)))
                   :family (* 3 (:family p))
                   :improvements (reduce + 0 (map #(:points (improvements %)) (:improvements p)))
                   :bonus (reduce + 0 (for [id (:improvements p) :let [{:keys [craft bonus]} (improvements id)] :when craft]
                                       (count (filter #(>= (amount p craft) %) bonus))))
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
    (reduce #(place-tile %1 :field %2) (get-in g [:players pid])
            (concat (when (some? cell) [cell]) extra))))
(defn replaced-baking? [g pid space]
  (let [plan (:freshman-plan g)]
    (and (= pid (:player plan)) (= space (:space plan)) (some? (:card plan)))))
(defn apply-action [g pid {:keys [space cell rooms stables fences plantings bake-grain choice] :as command}]
  (let [a (action-definition g space) p (get-in g [:players pid])]
    (require! (= :work (:phase g)) "Finish the harvest or animal housing first.")
    (require! (available-space? g space) "That action is not available yet or its linked space is occupied.")
    (require! (not (contains? (:occupied g) space)) "That action space is occupied.")
    (require! (pos? (:remaining p)) "You have no workers left this round.")
    (let [g (cond
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
                           (update-in g [:players pid] #(-> % (build-rooms rooms command) (build-stables stables 2))))
                :house-building (do (require! (and (seq rooms) (empty? stables)) "Build at least one room; this space does not build stables.")
                                    (update-in g [:players pid] build-rooms rooms command))
                :animal-market (do (require! (some #{choice} animals) "Choose sheep, boar, or cattle.")
                                   (update-in g [:players pid] #(-> % (pay (if (= choice :cattle) {:food 1} {}))
                                                                    (gain (merge {choice 1} (when (= choice :sheep) {:food 1}))))))
                :fences (update-in g [:players pid] build-fences fences (select-keys command [:fence-material]))
                :side-job (do (require! (and (<= (count stables) 1) (or (seq stables) (pos? (or bake-grain 0)) (replaced-baking? g pid space))) "Build one stable and/or bake bread.")
                              (update-in g [:players pid] #(-> % (build-stables stables 1) (bake (or bake-grain 0)))))
                :sow-bake (do (require! (or (seq plantings) (pos? (or bake-grain 0)) (replaced-baking? g pid space)) "Sow at least one field or bake bread.")
                             (update-in g [:players pid] #(-> % (sow plantings) (bake (or bake-grain 0)))))
                :cultivation (do (require! (or (some? cell) (seq plantings) (and (= pid (get-in g [:plow-plan :player])) (= space (get-in g [:plow-plan :space])) (seq (get-in g [:plow-plan :cells])))) "Plow a field and/or sow.")
                                 (assoc-in g [:players pid] (sow (plow-action-player g pid space cell) plantings)))
                :growth (update-in g [:players pid] grow true)
                :modest-growth (update-in g [:players pid] grow true)
                :urgent-growth (update-in g [:players pid] grow false)
                :improvement (build-improvement g pid command)
                :renovate (cond-> (update-in g [:players pid] renovate command) (:improvement command) (build-improvement pid command))
                :redevelop (cond-> (update-in g [:players pid] renovate command) (seq fences) (update-in [:players pid] build-fences fences (select-keys command [:fence-material])))
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
      (require! (animal-housing p') "Those animals do not fit. Each pasture holds only one species.")
      (-> g (assoc-in [:players pid] p') (assoc :phase :work) next-turn))))
(defn feed [g pid {:keys [convert craft] :or {convert {} craft []}}]
  (require! (= :feeding (:phase g)) "It is not the feeding phase.")
  (let [p (convert-food (get-in g [:players pid]) convert)
        _ (require! (= (count craft) (count (set craft))) "A workshop can be used once per harvest.")
        p (reduce (fn [p id]
                    (let [{:keys [craft rate]} (improvements id)]
                      (require! (and ((:improvements p) id) craft) "You do not own that craft improvement.")
                      (gain (pay p {craft 1}) {:food rate}))) p craft)
        needed (food-needed g p) missing (max 0 (- needed (amount p :food)))
        g (-> g (assoc-in [:players pid] (-> p (pay {:food (min needed (amount p :food))}) (update :begging + missing)))
              (update :fed conj pid) (log-event (str (:name p) " fed their family" (when (pos? missing) (str " · " missing " begging marks")))))
        next (mod (inc pid) (count (:players g)))]
    (if (= (count (:fed g)) (count (:players g)))
      (-> g (assoc :phase :breeding :turn (:starting-player g))
          ;; Feeding is a shared phase. Track it for everyone, even before
          ;; Woolgrower is played, and make the new capacity available to breed.
          (update :players #(mapv (fn [player] (update player :completed-feeding-phases (fnil inc 0))) %)))
      (assoc g :turn next))))
(defn breeding-options [p] (into {} (for [a animals :when (>= (amount p a) 2)] [a 1])))
(defn breed [g pid {:keys [offspring]}]
  (require! (= :breeding (:phase g)) "It is not the breeding phase.")
  (let [p (get-in g [:players pid]) options (breeding-options p) offspring (or offspring options)]
    (quantities! offspring animals)
    (require! (every? (fn [[a n]] (<= n (get options a 0))) offspring) "Each pair can produce at most one offspring.")
    (let [p' (gain p offspring)]
      (require! (animal-housing p') "There is not enough room for those offspring.")
      ;; Breeding is mandatory whenever the additional animal can be housed.
      (doseq [[a n] options :when (< (get offspring a 0) n)]
        (require! (nil? (animal-housing (gain p' {a 1}))) "You must breed animals when you have room for them."))
      (let [g (-> g (assoc-in [:players pid] p') (update :bred conj pid)
                  (log-event (str (:name p) " · breeding complete")))]
        (if (= (count (:bred g)) (count (:players g))) (if (:defer-advance? g) (assoc g :phase :finish-round) (finish-round g))
            (assoc g :turn (mod (inc pid) (count (:players g)))))))))
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
  (-> g (dissoc :round-order :deck-order :draw :card-rng) (update :players #(mapv (fn [p] (dissoc p :hand)) %))))
