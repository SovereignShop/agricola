(ns agricola.card-expressions
  "Small, explicit numeric/predicate language shared by card rules and previews.
   Unknown forms fail closed; card prose is never an executable effect."
  (:require [agricola.engine :as a] [clojure.string :as str]))
(declare evaluate)
(defn field-list [player] (concat (filter #(= :field (:type %)) (:farm player)) (vals (:card-fields player))))
(defn count-crop [player crop] (count (filter #(and (= crop (:crop %)) (pos? (:count % 0))) (field-list player))))
(defn evaluate [g pid context form]
  (let [p (get-in g [:players pid]) ev #(evaluate g pid context %)]
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
          :house (:house p) :round (:round g) :family (:family p) :newborn (:newborn p 0) :remaining (:remaining p 0)
          :rooms (count (a/tiles p :room)) :fields (count (field-list p)) :field-tiles (count (a/tiles p :field))
          :grain-fields (count-crop p :grain) :vegetable-fields (count-crop p :vegetable)
          :planted-fields (count (filter #(pos? (:count % 0)) (field-list p)))
          :empty-fields (count (filter #(zero? (:count % 0)) (field-list p)))
          :unused (count (filter #(a/empty-cell? p %) (range 15)))
          :pastures (count (a/pastures p))
          :pastures-at-least (count (filter #(>= (count %) (ev x)) (a/pastures p)))
          :fences (count (:fences p)) :fences-left (a/fences-available p)
          :occupation-hand (count (get-in p [:hand :occupation]))
          :stables (count (filter :stable (:farm p)))
          :occupations (count (:occupations p)) :minors (count (:minors p)) :majors (count (:improvements p))
          :improvements (+ (count (:minors p)) (count (:improvements p)))
          :cards (+ (count (:minors p)) (count (:improvements p)) (count (:occupations p)))
          :animals (reduce + (map #(a/amount p %) a/animals))
          :animal-types (count (filter #(pos? (a/amount p %)) a/animals))
          :building-resources (reduce + (map #(a/amount p %) [:wood :clay :reed :stone]))
          :owns-major (boolean ((:improvements p) x))
          :baking-improvements (count (filter #(get-in a/improvements [% :bake]) (:improvements p)))
          :cooking-improvements (count (filter #(get-in a/improvements [% :cook]) (:improvements p)))
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
          :before-goods (get-in context [:before-goods x] 0)
          :context (get context x 0)
          :pile (get-in g [:piles x (second args)] 0)
          :space-pile-count (count (for [space (:active g)
                                       :let [amount (get-in g [:piles space x] 0)]
                                       :when (and (:accumulate (a/action-definition g space))
                                                  (apply (case (second args) := = :>= >= :> >) [amount (ev (nth args 2))]))] space))
          (throw (ex-info "Unknown card expression" {:form form})))))))
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
