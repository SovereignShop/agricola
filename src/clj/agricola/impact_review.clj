(ns agricola.impact-review
  "Review hints for synthetic recordings; zero measured benefit is not a rule verdict."
  (:require [agricola.card-rules :as cards] [agricola.card-impact :as impact]))

(defn benefit? [event]
  ;; A gift to another player also changes the game. Costs alone are not a benefit.
  (some #(some pos? (concat (vals (:gained %)) (vals (:farm %)))) (:changes event)))

(defn summarize [{:keys [frames moves]}]
  (let [seen (reduce (fn [out [i state]]
                       (reduce (fn [out [pid cid]]
                                 (if (contains? out [pid cid]) out
                                   (assoc out [pid cid] {:first-move (max 0 (dec i)) :played-round (:round state)})))
                               out (for [p (:players state) cid (cards/owned p)] [(:id p) cid])))
                     {} (map-indexed vector frames))
        measured? (every? #(contains? % :impacts) moves)
        events (mapcat (fn [move] (map #(assoc % :move (:index move)) (:impacts move))) moves)
        ;; Include passing/discarded cards that are present only in effect traces.
        seen (reduce (fn [out [move e]]
                       (let [key [(:player e) (:card e)]]
                         (if (or (nil? (:card e)) (nil? (:player e)) (contains? out key)) out
                           (assoc out key {:first-move (:index move) :played-round (:round move)}))))
                     seen (for [move moves e (:effects move)] [move e]))]
    (vec
     (for [[[pid cid] first-play] (sort-by (comp pr-str key) seen)
           :let [definition (cards/card cid)] :when definition
           :let [history (filter #(and (= pid (:player %)) (= cid (:card %))) events)
                 trace (filter #(and (= pid (:player %)) (= cid (:card %))) (mapcat :effects moves))
                 skipped (count (filter #(get-in % [:choice :skip?]) trace))
                 benefits (count (filter benefit? history))
                 unmeasured (cond-> []
                              (seq (:passive definition)) (conj "passive benefits")
                              (:score definition) (conj "end-game scoring")
                              (pos? (:points definition 0)) (conj "printed points")
                              (:bake definition) (conj "baking")
                              (:cook definition) (conj "cooking"))
                 reason (cond (not measured?) "unavailable"
                              (pos? benefits) "measured"
                              (seq unmeasured) "unmeasured"
                              (and (pos? skipped) (= skipped (count trace))) "declined"
                              (and (empty? trace) (empty? history)) "no-trigger"
                              :else "no-measured-gain")]]
       (merge first-play
              {:id (str cid) :name (:name definition) :player pid
               :reason reason :zero-impact (and measured? (zero? benefits))
               :effects (count trace) :declined skipped :benefits benefits
               :unmeasured unmeasured :totals (impact/totals history pid)
               :effect-ops (vec (sort (set (map #(name (get-in % [:effect :op])) trace))))
               :trigger-events (vec (sort (set (keep #(some-> % :event name) (:triggers definition)))))})))))
