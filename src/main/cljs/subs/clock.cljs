(ns agricola.subs.clock
  (:require
   [agricola.constants.ui-idents :as idents]
   [swig.macros :refer [def-sub]]))

(def-sub ::latest-time
  [:find ?time .
   :in $
   :where
   [?id :swig/ident :agricola.constants.ui-idents/clock]
   [?id :clock/latest-time ?time]])
