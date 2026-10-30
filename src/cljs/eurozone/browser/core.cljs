(ns eurozone.browser.core
  (:require [reagent.core :as r] [re-frame.core :as rf]
            [eurozone.browser.events] [eurozone.browser.readability :as readability] [eurozone.browser.subs]
            [eurozone.browser.peer :as peer] [eurozone.browser.views :as views] [eurozone.browser.comms :as comms]))
(defn ^:export init []
  (set! (.-EUROZONE_ACTIVITY_CHANGED js/window) #(rf/dispatch [:activity/change (when % (js->clj % :keywordize-keys true))]))
  (comms/session-token)
  (rf/dispatch-sync [:initialize])
  (readability/install!)
  (set! (.-onhashchange js/window) #(rf/dispatch [:navigate (eurozone.browser.events/hash-id)]))
  (if (.-EUROZONE_ACTIVITY_LOBBY js/window)
    (rf/dispatch [:activity/load])
    (when-not (peer/boot!) (comms/connect!)))
  (when-let [path (.get (js/URLSearchParams. (.-search js/location)) "replay")]
    (views/load-linked-recording! path))
  (r/render [views/app] (.getElementById js/document "app")))
(-> (or (.-EUROZONE_READY js/window) (js/Promise.resolve nil))
    (.then init)
    (.catch (fn [error] (set! (.-textContent (.getElementById js/document "app")) (str "Could not open Eurozone: " (.-message error))))))
