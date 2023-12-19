(ns agricola.view.example
  (:require
   [reagent.core :as rdc]
   [re-frame.core :as re-frame]))

(def state (atom {:count 0}))

;; Define an event handler (shared logic)
(re-frame/reg-event-db
 :increment-count
 (fn [db _]
   (update db :count (fnil * 2))))

;; Define a subscription (shared logic)
(re-frame/reg-sub
 :count
 (fn [db _]
   (:count db)))

(re-frame/reg-sub
 :double-count
 (fn [& args]
   (re-frame/subscribe [:count]))
 (fn [& args]
   (+ 1)))

(defn view-fn []
  (let [x @(re-frame/subscribe [:double-count]) ]
    (println "x" x)) )

;; Shared utility function
(defn example-function [x]
  (println "ClojureScript" x))

(view-fn)

(re-frame/dispatch-sync [:increment-count])

(re-frame/dispatch [:increment-count] )

(re-frame/connect! )
