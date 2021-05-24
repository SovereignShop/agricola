(ns agricola.events.clock
  (:require
   [agricola.constants.ui-idents :as idents]
   [swig.macros :refer-macros [def-event-ds]]))

(def-event-ds ::set-time
  [db [_ t]]
  [{:swig/ident idents/clock
    :clock/latest-time t}])

