(ns eurozone.browser.bots
  (:require [cljs.reader :as reader] [eurozone.bot-player :as bot]))

(defonce worker (atom nil))
(defonce pending (atom nil))
(defonce fallback? (atom false))
(defn finish! [result]
  (when (= (:id result) (:id @pending))
    (let [callback (:callback @pending)] (reset! pending nil) (callback result))))
(defn local! []
  (when-let [{:keys [id observed player]} @pending]
    (js/setTimeout
     #(when (= id (:id @pending))
        (finish! (try {:id id :command (bot/choose observed player)}
                      (catch :default e {:id id :error (.-message e)})))) 0)))
(defn fallback! []
  (when @worker (.terminate @worker))
  (reset! worker nil) (reset! fallback? true) (local!))
(defn request! [observed player callback]
  (let [id (str (random-uuid))]
    (reset! pending {:id id :observed observed :player player :callback callback})
    (if @fallback? (local!)
      (try
        (when-not @worker
          (let [w (js/Worker. "/js/bot-worker.js")]
            (set! (.-onmessage w) #(finish! (reader/read-string (.-data %))))
            (set! (.-onerror w) (fn [event] (.preventDefault event) (fallback!)))
            (reset! worker w)))
        (.postMessage @worker (pr-str {:id id :observed observed :player player}))
        (catch :default _ (fallback!))))))
(defn close! []
  (reset! pending nil)
  (when @worker (.terminate @worker))
  (reset! worker nil))
