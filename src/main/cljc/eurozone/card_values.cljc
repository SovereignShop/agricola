(ns eurozone.card-values
  "Validated two-player draft rankings, independent of the game rules."
  #?(:clj (:require [agricola.data :refer [read-edn]]))
  #?(:cljs (:require-macros [agricola.data :refer [read-edn]])))
(def model (read-edn "eurozone/draft-values.edn"))
(defn expected-value [players card]
  (when (= players (:players model)) (get-in model [:values card])))
