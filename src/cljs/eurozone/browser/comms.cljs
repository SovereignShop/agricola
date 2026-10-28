(ns eurozone.browser.comms
  (:require [cljs.reader :as reader] [re-frame.core :as rf]))
(defonce socket (atom nil))
(defonce timer (atom nil))
(defonce peer-sender (atom nil))
(defn peer? [] (some? @peer-sender))
(defn use-peer! [sender]
  (reset! peer-sender sender)
  (when @timer (js/clearTimeout @timer))
  (when @socket (set! (.-onclose @socket) nil) (.close @socket))
  (rf/dispatch [:connection false]))
(defn invite-url [id & [new-seat?]]
  (let [url (js/URL. (str (.-origin js/location) "/"))]
    (when (peer?)
      (set! (.-search url) (.-search js/location)))
    (if new-seat? (.set (.-searchParams url) "seat" "new") (.delete (.-searchParams url) "seat"))
    (set! (.-hash url) id) (.toString url)))
(defn fresh-token []
  (let [bytes (js/Uint8Array. 32)]
    (.getRandomValues js/crypto bytes)
    (apply str (map #(.padStart (.toString % 16) 2 "0") (array-seq bytes)))))
(defn session-token []
  (let [url (js/URL. (.-href js/location)) fresh? (= "new" (.get (.-searchParams url) "seat"))]
    (when fresh?
      (when-not (.-EUROZONE_DISCORD_USER js/window)
        (.setItem js/sessionStorage "eurozone-session" (fresh-token))
        (.setItem js/sessionStorage "eurozone-name" ""))
      (.delete (.-searchParams url) "seat")
      (.replaceState js/history nil "" (.toString url)))
    (or (.getItem js/sessionStorage "eurozone-session")
        (let [token (or (.getItem js/localStorage "eurozone-session") (fresh-token))]
          (.setItem js/sessionStorage "eurozone-session" token)
          ;; Preserve a returning browser's default seat. Extra player tabs have
          ;; independent sessionStorage and never overwrite this default token.
          (when-not (.getItem js/localStorage "eurozone-session")
            (.setItem js/localStorage "eurozone-session" token))
          token))))
(defn send! [message]
  (if @peer-sender (@peer-sender message)
   (if (and @socket (= 1 (.-readyState @socket)))
    (.send @socket (pr-str message))
    (rf/dispatch [:error "Connection lost. Reconnecting…"]))))
(defn connect! []
  (when @timer (js/clearTimeout @timer))
  (let [ws (js/WebSocket. (str (if (= "https:" (.-protocol js/location)) "wss://" "ws://") (.-host js/location) "/chsk"))]
    (reset! socket ws)
    (set! (.-onopen ws) #(send! {:event :hello :token (session-token) :auth (.-EUROZONE_DISCORD_AUTH js/window)}))
    (set! (.-onmessage ws) #(try (rf/dispatch [:server-message (reader/read-string (.-data %))])
                                (catch :default _ (rf/dispatch [:error "Could not read the server response."]))))
    (set! (.-onclose ws) (fn [_] (rf/dispatch [:connection false]) (reset! timer (js/setTimeout connect! 1500))))
    (set! (.-onerror ws) (fn [_] (.close ws)))))
