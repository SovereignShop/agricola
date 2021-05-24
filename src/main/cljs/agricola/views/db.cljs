(ns agricola.views.db
  (:require
   [agricola.constants.ui-tabs :as tabs]
   [agricola.subs.db :as db-subs]
   [swig.dispatch :as swig-view]
   [re-com.core :as com]
   [re-posh.core :as re-posh]))

(defn db-column [xs]
  [com/v-box
   :gap "10px"
   :children
   (for [x xs]
     [com/box :child (str x)])])

(defmethod swig-view/dispatch tabs/db [_]
  (let [db @(re-posh/subscribe [::db-subs/full-db])
        [es as vs] (apply map vector (sort-by (juxt first second) db))]
    [com/h-box
     :gap "10px"
     :children
     [[db-column es]
      [db-column as]
      [db-column vs]]]))

