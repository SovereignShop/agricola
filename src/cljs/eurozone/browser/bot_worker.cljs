(ns eurozone.browser.bot-worker
  (:require [cljs.reader :as reader] [eurozone.bot-player :as bot]
            [agricola.afterstate-value :as value] [eurozone.play-values :as play-values]))
(set! (.-onmessage js/self)
      (fn [event]
        (let [{:keys [id op observed player]} (reader/read-string (.-data event))]
          (.postMessage js/self
            (pr-str (try (if (= :evaluate op)
                           (let [features (value/features observed player)]
                             {:id id :features features :prediction (value/predict play-values/model features)})
                           {:id id :command (bot/choose observed player)})
                         (catch :default e {:id id :error (.-message e)})))))))
