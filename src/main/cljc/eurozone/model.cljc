(ns eurozone.model
  "Shared normalized facts and queries. The rules aggregate is projected into
   game -> player -> farm-cell entities; views never depend on entity paths."
  (:require [datascript.core :as d] [agricola.draw :as draw] [clojure.string :as str]))

(def schema
  {:eurozone.game/id {:db/unique :db.unique/identity}
   :eurozone.game/players {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :eurozone.player/id {:db/unique :db.unique/identity}
   :eurozone.player/cells {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :eurozone.cell/id {:db/unique :db.unique/identity}})
(defn qualify [prefix m] (into {} (for [[k v] m :when (some? v)] [(keyword prefix (name k)) (if (set? v) (vec (sort v)) v)])))
(defn unqualify [prefix m] (into {} (for [[k v] m :when (= prefix (namespace k))] [(keyword (name k)) v])))
(defn game-tx [room]
  (let [{:keys [id title mode revision state seats setup expected-players]} room
        players (or (:players state) (map-indexed (fn [i p] {:id i :name (:name p) :farm [] :goods {}}) seats))]
    [(merge {:eurozone.game/id id :eurozone.game/title title :eurozone.game/type :agricola
             :eurozone.game/mode mode :eurozone.game/revision revision
             :eurozone.game/setup (or setup {:mode :beginner :extra 2})
             :eurozone.game/expected-players (or expected-players 0)
             :eurozone.game/bots (count (filter :bot? seats))
             :eurozone.game/turn-seconds (get room :turn-seconds 0)
             :eurozone.game/clocks (or (:clocks room) {})
             :eurozone.game/takeback (or (:takeback room) {})
             :eurozone.game/takeback-result (or (:takeback-result room) {})
             :eurozone.game/can-takeback? (boolean (seq (:history room)))
             :eurozone.game/takeback-players (set (map :player (filter #(= :move (:record %)) (:recording room))))
             :eurozone.game/last-move-player (or (:player (last (filter #(= :move (:record %)) (:recording room)))) 0)
             :eurozone.game/harvest-ready (into {} (for [[pid plan] (:harvest-plans room)]
                                                    [pid (vec (filter #(contains? plan %) [:feed :breed]))]))
             :eurozone.game/bot-seats (vec (keep-indexed #(when (:bot? %2) %1) seats))
             :eurozone.game/seat-readiness (mapv #(get % :ready? true) seats)
             :eurozone.game/status (or (:phase state) :lobby)
             :eurozone.game/players
             (mapv (fn [p]
                     (merge {:eurozone.player/id (str id "/" (:id p)) :eurozone.player/index (:id p)
                             :eurozone.player/cells
                             (mapv (fn [i cell] (merge {:eurozone.cell/id (str id "/" (:id p) "/" i) :eurozone.cell/index i}
                                                       (qualify "agricola.cell" cell))) (range) (:farm p))}
                            (qualify "agricola.player" (dissoc p :id :farm :goods :hand))
                            (when (:hand p) {:agricola.player/hand-counts (into {} (map (fn [[k v]] [k (count v)]) (:hand p)))})
                            (qualify "agricola.resource" (:goods p)))) players)}
            (qualify "agricola.game" (cond-> (dissoc state :players :round-order :deck-order :minor-order :minor-deck :draw :card-rng) (:draw state) (assoc :draw (draw/public-view (:draw state))))))]))
(defn pull-game [db id]
  (when-let [room (d/pull db '[* {:eurozone.game/players [* {:eurozone.player/cells [*]}]}] [:eurozone.game/id id])]
    (when (:eurozone.game/id room)
      (merge (unqualify "eurozone.game" (dissoc room :eurozone.game/players))
             {:state (when-not (= :lobby (:eurozone.game/status room))
                       (merge (unqualify "agricola.game" room)
                              {:available-improvements (set (:agricola.game/available-improvements room))
                               :newborn-only (set (:agricola.game/newborn-only room))
                               :fed (set (:agricola.game/fed room))
                               :bred (set (:agricola.game/bred room))
                               :cancelled-sowing (set (:agricola.game/cancelled-sowing room))
                               :players
                               (mapv (fn [p]
                                       (-> (unqualify "agricola.player" p)
                                           (assoc :id (:eurozone.player/index p)
                                                  :goods (unqualify "agricola.resource" p)
                                                  :farm (mapv #(unqualify "agricola.cell" %) (sort-by :eurozone.cell/index (:eurozone.player/cells p))))
                                           (update :fences set) (update :improvements set) (update :well-rounds set)))
                                     (sort-by :eurozone.player/index (:eurozone.game/players room)))}))
              :seats (mapv #(hash-map :name (:agricola.player/name %) :index (:eurozone.player/index %))
                           (sort-by :eurozone.player/index (:eurozone.game/players room)))}))))
(defn games [db]
  (sort-by :title (map #(pull-game db %) (d/q '[:find [?id ...] :where [_ :eurozone.game/id ?id]] db))))
(defn datoms->tx [datoms]
  (mapv (fn [[e a v _ added?]] [(if added? :db/add :db/retract) e a v]) datoms))
