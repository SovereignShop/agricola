(ns agricola.tx
  (:require
   [agricola.db :as db]
   [agricola.utils :as u]
   [datascript.core :as d]))

(defn insert-optional [& {:keys [title tx]}]
  tx)

(defn add-resource [entity resource n]
  [(d/datom (:db/id entity) resource (+ (get entity resource 0) n))])

(defn add-food [entity n-food]
  (add-resource entity :agricola.resource/food n-food))

(defn add-grain [entity n-grain]
  (add-resource entity :agricola.resource/grain n-grain))

(defn add-wood [entity n-grain]
  (add-resource entity :agricola.resource/wood n-grain))

(defn remove-grain [entity n-grain]
  (add-resource entity :agricola.resource/grain (- n-grain)))

(defn add-fields [entity n-fields]
  (add-resource entity :agricola.resource/field n-fields))

(defn remove-fields [entity n-fields]
  (add-resource entity :agricola.resource/field (- n-fields)))

(defn add-vegetables [entity n-vegetables]
  (add-resource entity :agricola.resource/vegetable n-vegetables))

(defn remove-vegetables [entity n-vegetables]
  (add-resource entity :agricola.resource/vegetable (- n-vegetables)))

(defn assoc-entity [entity attr value]
  [(d/datom (:db/id entity) attr value)])

(defn move-resources [from to]
  (let [a (:agricola.space/resources from)]
    (conj (vec (for [id (map :db/id a)]
                 [:db/add (:db/id to) :agricola.space/resources id]))
          [:db/retract (:db/id from) :agricola.space/resources])))

(defn signal
  ([name type ui-update?]
   (with-meta
     [(conj #:eurozone.event {:name name
                              :type type}
            db/event-id)]
     {:signal true :ui-update ui-update?}))
  ([name type]
   (signal name type false)))
