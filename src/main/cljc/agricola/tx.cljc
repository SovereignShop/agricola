(ns agricola.tx
  (:require
   [datascript.core :as d]))

(defn insert-optional [& {:keys [title tx]}])

(defn add-resource [entity resource n]
  [[(:db/id entity) resource (+ (get entity resource) n)]])

(defn add-food [entity n-food]
  (add-resource entity :agricola.bit/food n-food))

(defn add-grain [entity n-grain]
  (add-resource entity :agricola.bit/grain n-grain))

(defn remove-grain [entity n-grain]
  (add-resource entity :agricola.bit/grain (- n-grain)))

(defn add-fields [entity n-fields]
  (add-resource entity :agricola.bit/field n-fields))

(defn remove-fields [entity n-fields]
  (add-resource entity :agricola.bit/field (- n-fields)))

(defn add-vegetables [entity n-vegetables]
  (add-resource entity :agricola.bit/vegetable n-vegetables))

(defn remove-vegetables [entity n-vegetables]
  (add-resource entity :agricola.bit/vegetable (- n-vegetables)))
