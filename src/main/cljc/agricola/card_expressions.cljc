(ns agricola.card-expressions
  "Small, explicit numeric/predicate language shared by card rules and previews.
   Unknown forms fail closed; card prose is never an executable effect."
  (:require [agricola.engine :as a] [agricola.card-rules :as cards] [clojure.string :as str]))
(declare evaluate evaluate*)
(defn field-list [player] (filter #(= :field (:type %)) (concat (:farm player) (vals (:card-fields player)))))
(defn mixed-fields [player] (filter #(and (pos? (a/field-crop-count % :grain)) (pos? (a/field-crop-count % :vegetable))) (field-list player)))
(defn minor-supply-improvements [player] (filter #(a/major-as-minor? player %) (:improvements player)))
(defn dual-improvements [player]
  (concat (filter #(get-in (a/improvement-definition %) [:major-or-minor]) (:minors player))
          (minor-supply-improvements player)))
(defn references? [form op] (boolean (some #(and (vector? %) (= op (first %))) (tree-seq coll? seq form))))
(defn classification-options [player form]
  {:mixed-fields (when (and (references? form :grain-fields) (references? form :vegetable-fields)) (count (mixed-fields player)))
   :dual-improvements (when (and (references? form :majors) (references? form :minors)) (count (dual-improvements player)))})
(defn count-contexts [player context form]
  (let [{:keys [mixed-fields dual-improvements]} (classification-options player form)]
    (for [grain (if (and (pos? (or mixed-fields 0)) (not (contains? context :mixed-as-grain))) (range (inc mixed-fields)) [(:mixed-as-grain context)])
          major (if (and (pos? (or dual-improvements 0)) (not (contains? context :dual-as-major))) (range (inc dual-improvements)) [(:dual-as-major context)])]
      (cond-> context (some? grain) (assoc :mixed-as-grain grain) (some? major) (assoc :dual-as-major major)))))
(defn count-crop [player crop context]
  (let [all (count (filter #(pos? (a/field-crop-count % crop)) (field-list player))) mixed (count (mixed-fields player))]
    (if (contains? context :mixed-as-grain)
      (+ (- all mixed) (if (= crop :grain) (:mixed-as-grain context) (- mixed (:mixed-as-grain context)))) all)))
(defn evaluate* [g pid context form]
  (let [p (get-in g [:players pid]) ev #(evaluate* g pid context %)]
    (if-not (vector? form) form
      (let [[op & args] form x (first args)]
        (case op
          :+ (reduce + 0 (map ev args)) :- (apply - (map ev args)) :* (reduce * 1 (map ev args))
          :min (apply min (map ev args)) :max (apply max (map ev args)) :quot (apply quot (map ev args))
          := (apply = (map ev args)) :< (apply < (map ev args)) :<= (apply <= (map ev args))
          :> (apply > (map ev args)) :>= (apply >= (map ev args))
          :and (every? ev args) :or (boolean (some ev args)) :not (not (ev x))
          :if (ev (if (ev x) (second args) (nth args 2)))
          :goods (a/amount p x)
          :owned-card-count (count (for [player (:players g) cid (concat (cards/owned player) (:improvements player)) :when (contains? x cid)] cid))
          :all-goods (reduce + 0 (map #(a/amount % x) (:players g)))
          :total-crops (+ (a/amount p x) (reduce + 0 (map #(a/field-crop-count % x) (field-list p))))
          :returnable-hearths (count (a/returnable-hearths p))
          :player-count (count (:players g))
          :actor-family-difference (let [actor (get-in g [:players (:actor context)])]
                                     (- (- (:family actor 0) (:temporary-family actor 0))
                                        (- (:family p 0) (:temporary-family p 0))))
          :own-work-turn (and (= :work (:phase g)) (= pid (:turn g)))
          :house (:house p) :round (:round g) :family (:family p) :newborn (:newborn p 0) :remaining (:remaining p 0) :begging (:begging p 0)
          :rooms (count (a/tiles p :room)) :fields (count (field-list p)) :field-tiles (count (a/tiles p :field))
          :grain-fields (count-crop p :grain context) :vegetable-fields (count-crop p :vegetable context)
          :planted-fields (count (filter #(pos? (:count % 0)) (field-list p)))
          :empty-fields (count (filter #(zero? (:count % 0)) (field-list p)))
          :unused (count (filter #(a/empty-cell? p %) (range 15)))
          :pastures (count (a/pastures p))
          :pastures-at-least (count (filter #(>= (count %) (ev x)) (a/pastures p)))
          :fences (count (:fences p)) :fences-left (a/fences-available p)
          :occupation-hand (count (get-in p [:hand :occupation]))
          :stables (count (filter :stable (:farm p)))
          :occupations (cards/occupation-count p (:occupation-purpose context))
          :minors (- (+ (count (:minors p)) (count (minor-supply-improvements p))) (:dual-as-major context 0))
          :majors (+ (- (count (:improvements p)) (count (minor-supply-improvements p)))
                     (get context :dual-as-major (count (dual-improvements p))))
          :improvements (+ (count (:minors p)) (count (:improvements p)))
          :cards (+ (count (:minors p)) (count (:improvements p)) (count (:occupations p)))
          :animals (reduce + (map #(a/amount p %) a/animals))
          :animal-types (count (filter #(pos? (a/amount p %)) a/animals))
          :building-resources (reduce + (map #(a/amount p %) [:wood :clay :reed :stone]))
          :owns-major (boolean ((:improvements p) x))
          :owns-card (boolean (some #{x} (concat (:occupations p) (:minors p) (:improvements p))))
          :used-this-turn (= [(:round g) (or (get-in g [:action-context :phase]) (:phase g)) (:turn-serial g 0)]
                            (get-in p [:card-state x :last-turn]))
          :card-state (get-in p [:card-state x (second args)] 0)
          :other-occupations-below (boolean (some #(< (count (:occupations %)) (ev x))
                                         (if (= 1 (count (:players g))) [p] (remove #(= pid (:id %)) (:players g)))))
          :other-owned-cards (reduce + 0 (for [other (:players g) :when (not= pid (:id other))]
                                          (count (filter x (cards/owned other)))))
          :baking-improvements (count (a/baking-improvement-ids p))
          :ovens (count (a/returnable-ovens p))
          :cooking-improvements (count (filter #(:cook (a/improvement-definition %)) (a/owned-improvement-ids p)))
          :rounds-left (- 14 (:round g))
          :completed-harvests (count (filter #(< % (:round g)) a/harvest-rounds))
          :harvest-round (boolean (a/harvest-rounds (:round g)))
          :occupied (contains? (:occupied g) x)
          :owns-space (let [owner (get-in g [:occupied x])] (if (vector? owner) (boolean (some #{pid} owner)) (= pid owner)))
          :lessons-used (boolean (some #(contains? (:occupied g) %) [:lessons :lessons-extra :lessons-copse :lessons-growth]))
          :unoccupied-lessons (boolean (some #(and (some #{%} (:active g)) (not (contains? (:occupied g) %)))
                                             [:lessons :lessons-extra :lessons-copse :lessons-growth]))
          :accumulation-workers (reduce + 0 (for [[space owners] (:occupied g)
                                                 :when (:accumulate (a/action-definition g space))]
                                             (count (filter #{pid} (if (vector? owners) owners [owners])))))
          :taken (get-in context [:taken x] 0)
          :source-accumulation (boolean (:accumulate (a/action-definition g (:space context))))
          :source-rate (get-in (a/action-definition g (:space context)) [:gain x] 0)
          :source-goods (get-in context [:source-goods x] 0)
          :before-goods (get-in context [:before-goods x] 0)
          :received-since (max 0 (- (get-in p [:received x] 0) (get-in context [:before-received x] 0)))
          :already-baked-response (contains? (set (:bake-responders context)) pid)
          :context (get context x 0)
          :event-card-id (or (:card context) (:improvement context))
          :event-card-property (get (a/improvement-definition (or (:card context) (:improvement context))) x 0)
          :pile (get-in g [:piles x (second args)] 0)
          :space-pile-count (count (for [space (a/accumulation-spaces g)
                                       :let [amount (get-in g [:piles space x] 0)]
                                       :when (and (:accumulate (a/action-definition g space))
                                                  (apply (case (second args) := = :>= >= :> >) [amount (ev (nth args 2))]))] space))
          (throw (ex-info "Unknown card expression" {:form form})))))))
(defn evaluate [g pid context form]
  (let [values (map #(evaluate* g pid % form) (count-contexts (get-in g [:players pid]) context form))]
    (cond (= 1 (count values)) (first values) (every? number? values) (apply max values) :else (boolean (some identity values)))))
(defn quantities [g pid context goods]
  (into {} (for [[resource expression] goods] [resource (evaluate g pid context expression)])))
(def count-subjects
  {"occupation" [:occupations] "improvement" [:improvements] "major improvement" [:majors]
   "field" [:fields] "field tile" [:field-tiles] "plowed field" [:field-tiles] "planted field" [:planted-fields]
   "grain field" [:grain-fields] "vegetable field" [:vegetable-fields] "empty field" [:empty-fields]
   "unplanted field" [:empty-fields] "pasture" [:pastures] "room" [:rooms] "stable" [:stables]
   "family member" [:family] "people" [:family] "animal" [:animals] "animal type" [:animal-types]
   "unused space" [:unused] "unused farmyard space" [:unused] "unused fence" [:fences-left]
   "baking improvement" [:baking-improvements] "cooking improvement" [:cooking-improvements]
   "card played" [:cards] "card in play" [:cards]
   "wood" [:goods :wood] "clay" [:goods :clay] "reed" [:goods :reed] "stone" [:goods :stone]
   "food" [:goods :food] "grain" [:goods :grain] "vegetable" [:goods :vegetable]
   "sheep" [:goods :sheep] "wild boar" [:goods :boar] "cattle" [:goods :cattle]
   "person yet to place" [:remaining] "unplaced person" [:remaining]})
(defn number! [s] #?(:clj (Long/parseLong s) :cljs (js/parseInt s 10)))
(defn subject [s]
  (or (get count-subjects s) (get count-subjects (str/replace s #"s$" ""))
      (get count-subjects (-> s (str/replace #"\b(fields|cards)\b" {"fields" "field" "cards" "card"}) (str/replace #"s$" "")))))
(defn requirement
  "Compile a recognized printed prerequisite to explicit comparisons. A nil
   result is unsupported, never permission to play the card."
  [printed]
  (let [s (-> (or printed "") str/lower-case str/trim
              (str/replace #"^have (?:at least )?" "")
              (str/replace #" in your supply$" ""))]
    (or
      (case s
        "" true
        "play in round 3 (5) or before" [:<= [:round] 5]
        ("clay or stone house" "clay hut or stone house" "renovated to clay or stone") [:not [:= [:house] :wood]]
        ("wooden house" "still in wooden house" "wooden hut") [:= [:house] :wood]
        "clay house" [:= [:house] :clay] "stone house" [:= [:house] :stone]
        ("all farmyard spaces used" "all farmyard spaces are used") [:= [:unused] 0]
        "in wooden house with exactly 2 rooms" [:and [:= [:house] :wood] [:= [:rooms] 2]]
        "wood in your supply >= current round" [:>= [:goods :wood] [:round]]
        "at least 1 fence in supply" [:>= [:fences-left] 1]
        "have more rooms than people" [:> [:rooms] [:family]]
        "more rooms than people" [:> [:rooms] [:family]]
        "both fireplace and cooking hearth" [:and [:or [:owns-major :fireplace-2] [:owns-major :fireplace-3]] [:or [:owns-major :hearth-4] [:owns-major :hearth-5]]]
        "baking improvement" [:>= [:baking-improvements] 1]
        "cooking improvement" [:>= [:cooking-improvements] 1]
        ("1 oven" "return 1 oven of your choice") nil
        "have a well" [:owns-major :well] "a well" [:owns-major :well] "well major improvement" [:owns-major :well]
        nil)
      (when-let [[_ n direction] (re-matches #"(?:play in )?round (\d+) or (before|later)" s)]
        [(if (= direction "before") :<= :>=) [:round] (number! n)])
      (when-let [[_ n] (re-matches #"play in round (\d+)" s)] [:= [:round] (number! n)])
      (when-let [[_ noun] (re-matches #"no (.+)" s)]
        (when-let [metric (subject noun)] [:= metric 0]))
      (when-let [[_ comparator n noun] (re-matches #"(?:(exactly|at most|at least) )?(\d+) (.+)" s)]
        (when-let [metric (subject noun)] [(case comparator "exactly" := "at most" :<= :>=) metric (number! n)]))
      (when (or (str/includes? s " and ") (str/includes? s " & "))
        (let [parts (mapv requirement (str/split s #" and | & "))]
          (when (every? some? parts) (into [:and] parts)))))))
