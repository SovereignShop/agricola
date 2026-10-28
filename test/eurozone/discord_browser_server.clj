;; Test-only Discord provider. Production server/SDK/authorization stay unchanged.
(require '[eurozone.server :as server] '[eurozone.discord :as discord] '[clojure.string :as str])
(def port (Integer/parseInt (first *command-line-args*)))
(def config {:client-id "123" :client-secret "test-client" :public-key (apply str (repeat 64 "a"))
             :bot-token "test-bot" :secret "test-only-stable-secret-not-a-real-credential"
             :private? true :allowed-users #{"111" "222" "444" "555" "666"} :public-url (str "http://127.0.0.1:" port)})
(defn provider [method url headers body]
  (cond
    (str/ends-with? url "/oauth2/token")
    {:access_token (str "access-" (:code (discord/query {:query-string body})))}
    (str/ends-with? url "/users/@me")
    (let [id (str/replace (get headers "Authorization") "Bearer access-" "")]
      {:id id :username (case id "111" "Ada" "222" "Ben" "Outsider")})
    (str/includes? url "/activity-instances/")
    {:application_id "123" :instance_id (last (str/split url #"/")) :users ["111" "222" "444" "555" "666"] :location {:channel_id "10"}}
    :else (throw (ex-info "Unexpected test provider request" {:url url}))))
(with-redefs [discord/config (constantly config) discord/*request* provider]
  (let [app (server/start! {:port port :path (System/getenv "EUROZONE_DATA")})]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #((:stop app) :timeout 100)))
    (println "Private Discord test server ready")
    @(promise)))
