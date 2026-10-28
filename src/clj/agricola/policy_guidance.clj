(ns agricola.policy-guidance
  "Public-state one-step heuristic advice for explicitly labeled hybrid policies."
  (:require [clojure.java.io :as io] [agricola.action-space :as space]
            [agricola.bot :as bot] [agricola.recording :as recording]
            [agricola.simulation :as sim]))
(def version "public-utility-v1")
(def fingerprint
  (recording/sha (str (slurp (io/resource "agricola/policy_guidance.clj"))
                      (slurp (io/resource "agricola/bot.clj")))))
(defn evaluate [state actions]
  (let [pid (sim/actor state)
        observed (space/observation state pid)
        preview (assoc observed :defer-advance? true
                       :round-order (vec (concat (:round-order observed)
                                                 (repeat (- 14 (:round state)) :grain))))
        values (mapv (fn [{:keys [command]}]
                       (if (= :draw (:phase state)) (sim/draw-value (:card command))
                         (if-let [after (bot/attempt #(recording/apply-command preview pid command))]
                           (bot/utility after pid) -1.0e9))) actions)
        expert (first (reduce (fn [[bi bv] [i v]] (if (> v bv) [i v] [bi bv]))
                              [0 -1.0e10] (map-indexed vector values)))]
    {:version version :fingerprint fingerprint :values values :expert expert}))
