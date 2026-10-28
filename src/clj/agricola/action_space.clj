(ns agricola.action-space
  "Versioned, bounded macro actions for learning. These are executable engine
   commands, not a claim to enumerate every possible multi-room/fence layout."
  (:require [agricola.engine :as a] [agricola.card-game :as g] [agricola.bot :as bot]
            [agricola.draw :as draw] [agricola.recording :as recording]
            [agricola.simulation :as sim] [agricola.data :refer [read-edn]]))
(def schema (read-edn "agricola/learning.edn"))
(def version (:action-space schema))
(defn observation [state pid]
  (cond-> (-> (bot/observation state pid)
              (#(apply dissoc % (get-in schema [:observation :remove-state])))
              (assoc :observer pid)
              (update :players #(mapv (fn [p] (apply dissoc p (get-in schema [:observation :remove-player]))) %)))
    (= :draw (:phase state)) (assoc :draw-public (draw/public-view (:draw state))
                                   :draw-private (draw/private-view (:draw state) pid))))
(defn features
  "Stable named scalar features. No future round order or opponent hand enters."
  ([x] (features [] x))
  ([path x]
   (cond
     (map? x) (mapcat (fn [[k v]] (features (conj path k) v)) (sort-by (comp pr-str key) x))
     (set? x) (mapcat #(features (conj path %) true) (sort-by pr-str x))
     (sequential? x) (mapcat (fn [[i v]] (features (conj path i) v)) (map-indexed vector x))
     (number? x) [[(pr-str path) (double x)]]
     (nil? x) []
     :else [[(pr-str (conj path x)) 1.0]])))
(defn semantic-command [command] (apply dissoc command (get-in schema [:action :identity-ignore])))
(defn action-id [command] (recording/state-hash (semantic-command command)))
(defn extra-work [state pid]
  (let [p (g/p state pid)]
    (concat
     (when (some #{:side-job} (:active state))
       (for [cell (cons nil (range 15)) grain (range (inc (min 8 (a/amount p :grain))))
             :when (or cell (pos? grain))]
         (cond-> {:type :action :space :side-job :bake-grain grain} cell (assoc :stables [cell]))))
     ;; Offer one-unit conversions separately; feeding plans also handle bulk conversion.
     (for [r (concat a/animals [:grain :vegetable])
           :when (and (pos? (a/amount p r)) (pos? (get (a/cook-rates p) r 0)))]
       {:type :convert :goods {r 1}}))))
(defn candidates [state pid]
  (cond
    (= :finished (:phase state)) []
    (= :draw (:phase state)) (for [cid (draw/legal-cards (:draw state) pid)]
                              {:type :pick :wave (get-in state [:draw :wave]) :card cid})
    (= :housing (:phase state)) (for [keep (bot/housing-plans (g/p state pid)) cook? [false true]]
                                 {:type :house-animals :keep keep :cook? cook?})
    (seq (:effect-queue state)) (bot/decision-candidates state pid)
    :else (concat (bot/candidates state pid)
                  (when (#{:work :feeding} (:phase state)) (extra-work state pid)))))
(defn legal-actions [state]
  (if (= :finished (:phase state)) []
    (let [pid (sim/actor state)]
      (->> (candidates state pid)
           distinct
           (filter #(binding [g/*effect-observer* nil]
                      (bot/attempt (fn [] (recording/apply-command state pid %)))))
           (sort-by #(pr-str (recording/canonical %)))
           (mapv (fn [command] {:id (action-id command) :command command
                               :features (vec (features (semantic-command command)))}))))))
(defn expert-index [state actions]
  (let [pid (sim/actor state)
        observed (observation state pid)
        ;; Heuristic previews may reach a new round. Use a fixed placeholder,
        ;; never the unrevealed order, when evaluating candidate commands.
        preview-state (assoc observed :defer-advance? true
                             :round-order (vec (concat (:round-order observed) (repeat (- 14 (:round state)) :grain))))]
    (first
     (reduce (fn [[bi bv] [i {:keys [command]}]]
               (let [value (if (= :draw (:phase state)) (sim/draw-value (:card command))
                               (if-let [after (bot/attempt #(recording/apply-command preview-state pid command))]
                                 (bot/utility after pid) -1.0e9))]
                 (if (> value bv) [i value] [bi bv]))) [0 -1.0e10] (map-indexed vector actions)))))
