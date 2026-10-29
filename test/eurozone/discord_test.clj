(ns eurozone.discord-test
  (:require [clojure.test :refer :all] [clojure.data.json :as json] [eurozone.discord :as discord] [eurozone.peer :as peer])
  (:import [java.security KeyPairGenerator Signature] [java.io ByteArrayInputStream]))
(def keypair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519")))
(def public-key (apply str (map #(format "%02x" (bit-and 255 %)) (take-last 32 (.getEncoded (.getPublic keypair))))))
(def config {:client-id "123" :client-secret "client-secret" :public-key public-key :bot-token "bot-token"
             :secret "test-session-secret-long-and-distinct" :public-url "https://eurozone.example" :private? true :allowed-users #{"111" "222"}})
(defn signed-request [body]
  (let [raw (json/write-str body) timestamp (str (discord/now)) signature (Signature/getInstance "Ed25519")]
    (.initSign signature (.getPrivate keypair)) (.update signature (discord/utf8 (str timestamp raw)))
    {:uri "/discord/interactions" :request-method :post :body (ByteArrayInputStream. (discord/utf8 raw))
     :headers {"x-signature-timestamp" timestamp "x-signature-ed25519" (apply str (map #(format "%02x" (bit-and 255 %)) (.sign signature)))}}))
(defn app [] {:discord-config config :peer-service (peer/service nil) :activity-options (atom {})})
(deftest activity-bot-settings
  (is (= {:expected-players 5 :bots 4}
         (select-keys (discord/table-options "Host" [{:name "players" :value 5} {:name "bots" :value 4}]) [:expected-players :bots])))
  (doseq [n [-1 2 5 1.5 nil]]
    (is (thrown? clojure.lang.ExceptionInfo (discord/table-options "Host" [{:name "players" :value 2} {:name "bots" :value n}]))))
  (let [application (app) user {:id "111" :username "Host"}
        event {:type 2 :application_id "123" :id "9001" :channel_id "10" :user user
               :data {:name "eurozone" :options [{:name "activity" :options [{:name "players" :value 5} {:name "bots" :value 3}]}]}}
        reply (discord/handler application (signed-request event))]
    (is (= 12 (:type (json/read-str (:body reply) :key-fn keyword))))
    (with-redefs [discord/*request* (fn [& _] {:application_id "123" :instance_id "bots-test" :users ["111"] :location {:channel_id "10"}})]
      (discord/activity-table application config (discord/session-view config (discord/auth config user)) "bots-test"))
    (let [table (first (vals @(:tables (:peer-service application))))]
      (is (= {:expected-players 5 :bots 3} (select-keys (:options table) [:expected-players :bots]))))))
(deftest interaction-signatures-and-replay-window
  (let [req (signed-request {:type 1 :application_id "123"}) raw (slurp (:body req)) ts (get-in req [:headers "x-signature-timestamp"]) sig (get-in req [:headers "x-signature-ed25519"])]
    (is (discord/valid-signature? public-key ts sig raw))
    (is (not (discord/valid-signature? public-key ts sig (str raw " "))))
    (is (not (discord/valid-signature? public-key "1" sig raw)))
    (is (= 401 (:status (discord/handler (app) {:uri "/discord/interactions" :request-method :post :headers {} :body (ByteArrayInputStream. (.getBytes "{}"))}))))))
(deftest private-identities-and-invitation-ownership
  (let [application (app) user {:id "111" :username "Ada"}
        credential (discord/auth config user) session (discord/session-view config credential)
        event {:type 2 :application_id "123" :id "9000" :channel_id "10" :user user
               :data {:name "eurozone" :options [{:name "agricola" :options [{:name "players" :value 5} {:name "setup" :value "draw"}]}]}}
        response1 (discord/handler application (signed-request event))
        response2 (discord/handler application (signed-request event))
        table (first (vals @(:tables (:peer-service application))))]
    (is (= 200 (:status response1) (:status response2)))
    (is (= (:body response1) (:body response2)))
    (is (= 1 (count @(:tables (:peer-service application)))))
    (is (= 5 (get-in table [:options :expected-players])))
    (is (= (:host table) (peer/peer-id (:room table) (:token session))))
    (is (nil? (discord/session-view config (str credential "broken"))))
    (is (thrown? clojure.lang.ExceptionInfo (discord/session-view config (discord/auth config {:id "333" :username "Outsider"}))))
    (is (thrown? clojure.lang.ExceptionInfo (discord/authorize-peer! config (:token session) nil)))
    (is (nil? (discord/authorize-peer! config (:token session) credential)))
    (is (= 400 (:status (discord/handler application (signed-request (assoc event :user {:id "333" :username "Outsider"})))))))
)
(deftest activity-membership-is-verified-with-discord
  (let [application (app) session (discord/session-view config (discord/auth config {:id "111" :username "Ada"}))
        instance {:application_id "123" :instance_id "instance-1" :users ["111" "222"] :location {:channel_id "10"}}]
    (with-redefs [discord/*request* (fn [method url headers body] instance)]
      (let [table (discord/activity-table application config session "instance-1")
            again (discord/activity-table application config session "instance-1")]
        (is (= table again)) (is (:relay table))
        (is (= 1 (count @(:tables (:peer-service application)))))))
    (with-redefs [discord/*request* (fn [& _] (assoc instance :users ["222"]))]
      (is (thrown? clojure.lang.ExceptionInfo (discord/activity-table application config session "instance-1"))))))
(deftest oauth-state-is-signed-and-private-default-denies-empty-allowlist
  (let [state (discord/signed config {:kind "oauth" :exp (+ (discord/now) 20) :nonce "nonce" :return "/"})]
    (is (= "nonce" (:nonce (discord/verified config state "oauth"))))
    (is (nil? (discord/verified config state "session")))
    (with-redefs [discord/now (fn [] (+ 1000 (System/currentTimeMillis)))]
      (is (nil? (discord/verified config state "oauth"))))
    (is (thrown? clojure.lang.ExceptionInfo (discord/allow-user! (assoc config :allowed-users #{}) "111")))))

(deftest activity-lobby-tables-are-independent-private-and-idempotent
  (let [application (app)
        host (discord/session-view config (discord/auth config {:id "111" :username "Ada"}))
        guest (discord/session-view config (discord/auth config {:id "222" :username "Ben"}))
        service (:peer-service application)
        message {:instance "lobby" :action "create" :requestId "00000000-0000-0000-0000-000000000001"}]
    (with-redefs [discord/*request* (fn [_ url & _] {:application_id "123" :instance_id (last (clojure.string/split url #"/")) :users ["111" "222"]})]
      (is (= {:tables []} (discord/activity-lobby application config host (assoc message :action "list"))))
      (is (empty? @(:tables service)))
      (let [first-table (discord/activity-lobby application config host message)
            old (get @(:tables service) (:room first-table))]
        (is (= first-table (discord/activity-lobby application config host message)))
        (is (:host? first-table))
        (is (not (:host? (discord/activity-lobby application config guest (assoc message :action "join" :room (:room first-table))))))
        (is (thrown? clojure.lang.ExceptionInfo (discord/activity-lobby application config guest (assoc message :instance "other" :action "join" :room (:room first-table)))))
        (is (= [] (:tables (discord/activity-lobby application config guest (assoc message :instance "other" :action "list")))))
        ;; The other participant can host a separate game without changing the first.
        (let [second-table (discord/activity-lobby application config guest message)
              listed (:tables (discord/activity-lobby application config host (assoc message :action "list")))]
          (is (not= (:room first-table) (:room second-table)))
          (is (= 2 (count listed)))
          (is (every? #(not (contains? % :invite)) listed))
          (is (= old (get @(:tables service) (:room first-table))))
          (is (= (:invite-hash old) (peer/digest (:invite first-table))))
          (is (= 2 (count @(:tables service)))))))
    (with-redefs [discord/*request* (fn [& _] {:application_id "123" :instance_id "lobby" :users []})]
      (doseq [action ["list" "create" "join"]]
        (is (thrown? clojure.lang.ExceptionInfo (discord/activity-lobby application config host (assoc message :action action))))))))
