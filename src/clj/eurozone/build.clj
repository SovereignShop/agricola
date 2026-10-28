(ns eurozone.build (:require [agricola.simulation :as simulation]))
(defmacro rules-hash [] (simulation/rules-hash))
