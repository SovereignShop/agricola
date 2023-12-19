(ns agricola.macros
  (:require
   [re-posh.core :as re-posh]))


(defmacro def-event-ds
  [k args & body]
  (let [sym (symbol (namespace k) (name k))]
    `(do (defn ~(symbol (name k)) ~args ~@body)
         (re-posh.core/reg-event-ds ~k ~sym))))
