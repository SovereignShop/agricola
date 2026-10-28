(ns eurozone.play-values
  "Independently tested expected-score evaluator for two-player card games."
  (:require [agricola.afterstate-value :as value]
            #?(:clj [agricola.data :refer [read-edn]]))
  #?(:cljs (:require-macros [agricola.data :refer [read-edn]])))

(def model (read-edn "eurozone/play-values.edn"))
(defn choice [observed pid rng]
  (when (and (= (get model "players") (count (:players observed)))
             (= :cards (:variant observed)) (not= :draw (:phase observed)))
    (value/choose observed pid rng model (get model "margin") (get model "blend"))))
