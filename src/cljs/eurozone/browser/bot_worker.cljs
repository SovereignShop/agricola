(ns eurozone.browser.bot-worker
  (:require [cljs.reader :as reader] [eurozone.bot-player :as bot]))
(set! (.-onmessage js/self)
      (fn [event]
        (let [{:keys [id observed player]} (reader/read-string (.-data event))]
          (.postMessage js/self
            (pr-str (try {:id id :command (bot/choose observed player)}
                         (catch :default e {:id id :error (.-message e)})))))))
