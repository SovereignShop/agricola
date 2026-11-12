(ns eurozone.browser.assistant
  (:require [re-frame.core :as rf] [eurozone.browser.comms :as comms]))

(defonce connection (atom nil))
(defonce deadline (atom nil))
(defn send! [value]
  (when (and @connection (= 1 (.-readyState @connection)))
    (.send @connection (js/JSON.stringify (clj->js value))) true))
(defn cancel! []
  (js/clearTimeout @deadline)
  (send! {:op "cancel"}))
(defn close! []
  (js/clearTimeout @deadline)
  (when-let [socket @connection] (reset! connection nil) (.close socket)))
(defn connect! []
  (close!)
  (let [pair (comms/fresh-token)
        endpoint (str (if (= "https:" (.-protocol js/location)) "wss://" "ws://") (.-host js/location) "/assistant")
        public (js/URL. "/assistant" (or (aget js/window "EUROZONE_PUBLIC_URL") (.-origin js/location)))
        _ (set! (.-protocol public) (if (= "https:" (.-protocol public)) "wss:" "ws:"))
        link (str (.toString public) "#" pair) socket (js/WebSocket. endpoint)]
    (reset! connection socket)
    (rf/dispatch [:voice/assistant-state {:message "Creating your private pairing link…"}])
    (set! (.-onopen socket)
          #(send! {:op "listen" :pair pair :token (comms/session-token) :auth (.-EUROZONE_DISCORD_AUTH js/window)}))
    (set! (.-onmessage socket)
          (fn [event]
            (when (identical? socket @connection)
              (let [packet (js->clj (js/JSON.parse (.-data event)) :keywordize-keys true)]
                (case (:op packet)
                  "ready" (rf/dispatch [:voice/assistant-state {:link link :message "Open the local companion and choose your account and model."}])
                  "paired" (rf/dispatch [:voice/assistant-state {:connected? true :model (:model packet) :message (str "ChatGPT connected · " (:model packet))}])
                  "result" (do (js/clearTimeout @deadline) (rf/dispatch [:voice/assistant-result packet]))
                  ("closed" "error") (do (close!) (rf/dispatch [:voice/assistant-state {:message (:message packet)}]))
                  nil)))))
    (set! (.-onclose socket)
          #(when (identical? socket @connection)
             (reset! connection nil) (js/clearTimeout @deadline)
             (rf/dispatch [:voice/assistant-state {:message "ChatGPT disconnected. Connect again to use your plan."}])))
    (set! (.-onerror socket)
          #(when (identical? socket @connection)
             (close!) (rf/dispatch [:voice/assistant-state {:message "Could not reach the assistant relay. Check the game server."}])))))
(rf/reg-fx :assistant/request
  (fn [request]
    (js/clearTimeout @deadline)
    (if (send! (assoc request :op "interpret"))
      (reset! deadline (js/setTimeout
                       #(do (cancel!) (rf/dispatch [:voice/assistant-result
                                                   (assoc request :result {:kind "answer" :message "ChatGPT timed out. Try again."})])) 110000))
      (rf/dispatch [:voice/assistant-result (assoc request :result {:kind "answer" :message "Connect your local ChatGPT companion first."})]))))
(rf/reg-fx :assistant/cancel (fn [_] (cancel!)))
