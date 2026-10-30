(ns agricola.five-player
  "Five-player expansion predicates and automatic effects. No transport or randomness."
  (:require [agricola.engine :as a] [agricola.card-rules :as c] [clojure.set :as set]))
(def lessons #{:lessons :lessons-copse :lessons-growth})
(def extension-accumulations #{:copse :grove :hollow :traveling :riverbank})
(def linked-groups {:copse :copse :lessons-copse :copse :lessons-growth :growth
                    :modest-growth :growth :house-building :house :traveling :house})
(defn p [g pid] (get-in g [:players pid]))
(defn occupied-wood [g]
  (count (filter #(and (contains? (:occupied g) %) (:accumulate (a/action-definition g %))
                      (get-in (a/action-definition g %) [:gain :wood])) (:active g))))
(defn wood-space? [g space] (and (:accumulate (a/action-definition g space)) (get-in (a/action-definition g space) [:gain :wood])))
(defn resource-taken [context resource] (get-in context [:taken resource] 0))
(defn stage-left [g space]
  ;; These are the physical round-card positions on the Revised board.
  (let [rounds (vec (drop (count (a/base-actions (count (:players g)) (:variant g))) (:active g)))
        index (first (keep-indexed #(when (= space %2) %1) rounds))
        left {1 0 2 1 3 2 5 4 6 5 8 7 10 9 12 11}]
    (when-let [i (left index)] (get rounds i))))
(defn refresh [g]
  (if-not (= 5 (count (:players g))) g
    (update g :players
      (fn [players]
        (mapv (fn [player]
                (cond-> player
                  (c/has? player :a169)
                  (assoc :off-site-room (or (:off-site-room player)
                    (>= (reduce + (mapcat #(vals (:cost (a/improvements %))) (:improvements player))) 9)))
                  (c/has? player :b169)
                  (assoc :card-animal-capacity
                    (min 8 (reduce + (for [other players :when (not= (:id player) (:id other))]
                                      (get-in g [:major-builds (:id other)] (count (:improvements other))))))))) players)))))
(defn eligible? [g pid op context]
  (let [player (p g pid) space (:space context) left (stage-left g space)]
    (case op
      :sidekick (and left (pos? (:remaining player)) (a/available-space? g left) (not (contains? (:occupied g) left)))
      :boat-painter (and (contains? (:occupied g) :fishing) (contains? (:occupied g) :traveling))
      :clay-thief (pos? (get-in g [:piles :hollow :clay] 0))
      :corral-builder (#{:boar :cattle} (last (:active g)))
      :amateur-fencer (empty? (a/pastures player))
      :wild-boar-hunter (>= (occupied-wood g) 3)
      :trapper (and (wood-space? g space) (<= 2 (occupied-wood g) 4))
      :plowsmith (and (wood-space? g space) (>= (resource-taken context :wood) 4))
      :fast-mason (let [material ({:wood :clay :clay :stone} (:house player))]
                    (and material (pos? (resource-taken context material))
                         (:accumulate (a/action-definition g space))))
      :countryman (and space (some #(and (= :field (:type %)) (zero? (get % :count 0)))
                                  (concat (:farm player) (vals (:card-fields player))))
                      (or (pos? (a/amount player :grain)) (pos? (a/amount player :vegetable))))
      :tag-along (pos? (:remaining player))
      true)))
(defn bank [g pid cid n] (update-in g [:players pid :card-state cid :food] (fnil + 0) n))
(defn collect-pile [g pid space resource]
  (-> g (update-in [:players pid] a/gain {resource (get-in g [:piles space resource] 0)})
        (assoc-in [:piles space resource] 0)))
(defn automatic [g pid cid op context]
  (let [player (p g pid) space (:space context)
        gain #(update-in g [:players pid] a/gain %)]
    (case op
      :hollow-gardener (let [n (resource-taken context :clay)] (gain (cond (>= n 6) {:vegetable 1} (>= n 3) {:grain 1} :else {})))
      :wheelmaker (if (and (> (count (:occupations player)) 1)
                           (> (a/amount player :wood) (reduce + (map #(a/amount % :wood) (remove #(= pid (:id %)) (:players g))))))
                   (gain {:wood (max 0 (- 15 (a/amount player :wood)))}) g)
      :middleman-stock (assoc-in g [:players pid :card-state cid :spaces] #{:copse :growth :house})
      :middleman (let [group (linked-groups space)]
                   (if (contains? (get-in player [:card-state cid :spaces]) group)
                     (-> (gain {:food 1 :stone 1}) (update-in [:players pid :card-state cid :spaces] disj group)) g))
      :carpenters-boy (gain {:wood (get context :rooms-built 0)})
      :cattle-caregiver (gain {:food (max 0 (min 3 (- (count (filter #(pos? (a/amount % :cattle)) (:players g))) 2)))})
      :sweeper (if (linked-groups space) (bank g pid cid 1) g)
      :field-overseer (let [n (reduce + (for [other (:players g) :when (not= pid (:id other))]
                                        (count (filter #(pos? (a/field-crop-count % :grain))
                                                 (concat (:farm other) (vals (:card-fields other)))))))]
                        (gain (cond (>= n 6) {:vegetable 1} (>= n 4) {:grain 1} (>= n 3) {:food 1} :else {})))
      :game-teaser (gain (if-let [animal ({1 :cattle 2 :boar 3 :sheep} (resource-taken context :food))] {animal 1} {}))
      :field-counter (bank g pid cid (get context :fields-plowed 0))
      :top-outer (collect-pile g pid :traveling :food)
      :stone-custodian (gain (case (count (filter #(pos? (get-in g [:piles % :stone] 0)) [:stone-west :stone-east]))
                              1 {:grain 1} 2 {:vegetable 1} {}))
      :village-teacher (gain (case (count (filter #(contains? (:occupied g) %) lessons)) 1 {:food 1} 2 {:grain 1} 3 {:vegetable 1} {}))
      :bovine-pioneer (gain (if (seq (set/difference (a/pasture-cells player) (set (mapcat identity (:old-pastures context))))) {:cattle 1} {}))
      :bank-food (bank g pid cid 1)
      :woodshacker (if (wood-space? g space)
                    (let [path [:players pid :card-state cid :wood-uses]
                          old (get-in g path) n (if (= (:round g) (:round old)) (inc (:count old)) 1)]
                      (assoc-in (gain {:clay (if (<= n 2) n 0)}) path {:round (:round g) :count n})) g)
      :graduate (if (pos? (a/amount player :food))
                  (update-in g [:players pid] #(-> % (a/pay {:food 1}) (a/gain {:stone 2 :reed 2}))) g)
      (throw (ex-info "Unknown five-player effect." {:op op})))))
