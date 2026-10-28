(ns eurozone.server
  (:require [org.httpkit.server :as http] [eurozone.store :as store] [eurozone.chat :as chat] [eurozone.peer :as peer] [eurozone.discord :as discord]
            [eurozone.table-flow :as flow] [eurozone.bot-player :as bot]
            [clojure.edn :as edn] [clojure.java.io :as io] [clojure.string :as str])
  (:gen-class))

(defn send! [channel message] (http/send! channel (pr-str message)))
(defn presence [app]
  (let [online (set (vals @(:clients app)))]
    (into {} (for [[id room] @(:rooms (:store app))]
               [id (vec (keep-indexed #(when (online (:owner %2)) %1) (:seats room)))]))))
(defn broadcast-presence! [app]
  (let [message {:event :presence :presence (presence app)}]
    (doseq [channel (keys @(:clients app))] (send! channel message))))
(defn token? [s] (and (string? s) (boolean (re-matches #"[a-f0-9-]{36,80}" s))))
(defn same-origin? [request]
  (let [origin (get-in request [:headers "origin"]) host (get-in request [:headers "host"])]
    (or (nil? origin) (= origin (str "http://" host)) (= origin (str "https://" host))
        (and (System/getenv "DISCORD_CLIENT_ID") (= origin (str "https://" (System/getenv "DISCORD_CLIENT_ID") ".discordsays.com"))))))
(defn broadcast-result! [app result]
  (doseq [[client uid] @(:clients app)]
    (send! client {:event :facts :server-time (flow/now) :datoms (:datoms result)
                  :permissions (store/permissions (:store app) uid)
                  :private-hands (store/private-view (:store app) uid)
                  :private-draws (store/private-draws (:store app) uid)
                  :private-plans (store/private-plans (:store app) uid) :presence (presence app)})))
(defn receive! [app channel raw]
  (try
    (when-not (and (string? raw) (< (count raw) 32768)) (throw (ex-info "Message too large." {})))
    (let [{:keys [event token request-id] :as message} (edn/read-string raw)]
      (if (= event :hello)
        (do (when-not (token? token) (throw (ex-info "Invalid session token." {})))
            (discord/authorize-peer! (:discord-config app) token (:auth message))
            (locking (:lock (:store app))
              (when (and (#{:create :configure} event) (pos? (or (:bots message) 0)))
                (throw (ex-info "Choose a peer-hosted table or Discord Activity to play with bots." {})))
              (chat/leave! app send! channel)
              (swap! (:clients app) assoc channel token)
              (send! channel (store/snapshot (:store app) token))
              (broadcast-presence! app)))
        (let [token (get @(:clients app) channel)]
          (when-not token (throw (ex-info "Connect before sending game commands." {})))
          (cond
            (= "chat" (namespace event)) (chat/receive! app send! channel token message)
            (= event :sync)
            (locking (:lock (:store app)) (send! channel (store/snapshot (:store app) token)))
            (= event :recording/request)
            (send! channel (store/export-recording (:store app) token (:id message)))
            :else
            ;; Broadcast under the store lock so revision deltas cannot arrive out of order.
            (locking (:lock (:store app))
              (when (= event :start)
                (let [room (get @(:rooms (:store app)) (:id message)) connected (set (vals @(:clients app)))]
                  (when (and (= :online (:mode room)) (not-every? #(connected (:owner %)) (:seats room)))
                    (throw (ex-info "Wait until all seated players are connected." {})))))
              (let [result (store/transact! (:store app) token message)]
                (broadcast-result! app result)
                (send! channel {:event :accepted :id (:id result) :request-id request-id})))))))
    (catch clojure.lang.ExceptionInfo e
      (send! channel {:event :error :message (.getMessage e)})
      (when-let [token (get @(:clients app) channel)] (send! channel (store/snapshot (:store app) token))))
    (catch Exception e
      (binding [*out* *err*] (println "Eurozone request failed:" (.getMessage e)))
      (send! channel {:event :error :message "The request could not be saved. Please try again."}))))
(def content-types {"html" "text/html; charset=utf-8" "css" "text/css; charset=utf-8" "js" "text/javascript; charset=utf-8" "map" "application/json" "json" "application/json" "svg" "image/svg+xml" "png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg" "webp" "image/webp"})
(defn handler [app request]
  (let [uri (:uri request)]
    (cond
      (= uri "/health") {:status 200 :headers {"Content-Type" "text/plain"} :body "Eurozone is ready"}
      (str/starts-with? uri "/discord/") (discord/handler app request)
      (= uri "/peer")
      (if (and (= :get (:request-method request)) (same-origin? request))
        (http/with-channel request channel
          (if (:websocket? request)
            (do (http/on-receive channel #(peer/route! (:peer-service app) http/send! channel %))
                (http/on-close channel (fn [_] (peer/leave! (:peer-service app) http/send! channel))))
            (http/send! channel {:status 400 :body "WebSocket connection required"})))
        {:status 403 :body "Origin not allowed"})
      (= uri "/chsk")
      (if (and (= :get (:request-method request)) (same-origin? request))
        (http/with-channel request channel
          (if (:websocket? request)
            (do (http/on-receive channel #(receive! app channel %))
                (http/on-close channel (fn [_] (chat/leave! app send! channel) (swap! (:clients app) dissoc channel) (broadcast-presence! app))))
            (http/send! channel {:status 400 :body "WebSocket connection required"})))
        {:status 403 :body "Origin not allowed"})
      (not (#{:get :head} (:request-method request))) {:status 405 :body "Method not allowed"}
      :else
      (let [path (if (= uri "/") "index.html" (subs uri 1))
            resource (when (and (not (str/includes? path "..")) (re-matches #"[a-zA-Z0-9_./-]+" path)) (io/resource (str "public/" path)))]
        (if resource
          {:status 200 :headers {"Content-Type" (get content-types (last (str/split path #"\.")) "application/octet-stream")
                                "X-Content-Type-Options" "nosniff" "Referrer-Policy" "same-origin" "Cache-Control" "no-cache"}
           :body (io/input-stream resource)}
          {:status 404 :body "Page not found"})))))
(defn tick! [app]
  (doseq [[id room] @(:rooms (:store app))
          :let [due (flow/due room) expired? (flow/expired-vote? room)]
          :when (and (or due expired?) (not (contains? @(:clock-jobs app) id)))]
    (swap! (:clock-jobs app) conj id)
    (future
      (try
        (let [message (if expired?
                        {:event :expire-takeback :vote-id (get-in room [:takeback :id])}
                        {:event :timeout-command :player (:player due) :clock-id (:clock-id due)
                         :command (bot/choose (bot/observation (:state room) (:player due)) (:player due))})]
          (locking (:lock (:store app))
            (when (and (not @(:stopped app)) (= (:revision room) (get-in @(:rooms (:store app)) [id :revision])))
              (broadcast-result! app (store/transact! (:store app) (:host room)
                                                      (assoc message :id id :revision (:revision room)))))))
        (catch Exception e (binding [*out* *err*] (println "Table clock:" (.getMessage e))))
        (finally (swap! (:clock-jobs app) disj id))))))
(defn start! [{:keys [port host path] :or {port 3001 host "127.0.0.1" path "data/eurozone.edn"}}]
  (let [config (discord/config)
        app {:store (store/make-store path) :clients (atom {}) :peers (atom {})
             :peer-service (assoc (peer/service (when path (str path ".peers"))) :authorize #(discord/authorize-peer! config %1 %2))
             :discord-config config :activity-options (atom {}) :clock-jobs (atom #{}) :stopped (atom false)}
        stop (http/run-server #(handler app %) {:port port :ip host :max-body 32768 :max-ws 1200000})
        clock (future (while (not @(:stopped app)) (tick! app) (Thread/sleep 250)))]
    (assoc app :stop (fn [& args] (reset! (:stopped app) true) (future-cancel clock) (apply stop args)))))
(defn -main [& args]
  (let [port (Integer/parseInt (or (first args) (System/getenv "PORT") "3001"))
        host (or (System/getenv "HOST") "127.0.0.1")
        app (start! {:port port :host host :path (or (System/getenv "EUROZONE_DATA") "data/eurozone.edn")})]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #((:stop app) :timeout 100)))
    (println (str "Eurozone → http://" host ":" port))
    @(promise)))
