(ns eurozone.card-values
  "Two-player base-deck draft rankings. Unknown cards use the caller fallback;
   the stored training fingerprint is preserved when the live rules change."
  #?(:clj (:require [agricola.data :refer [read-edn]]))
  #?(:cljs (:require-macros [agricola.data :refer [read-edn]])))
(def model (read-edn "eurozone/draft-values.edn"))
(defn expected-value [players card]
  (when (= players (:players model)) (get-in model [:values card])))
