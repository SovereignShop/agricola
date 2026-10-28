(ns eurozone.discord
  "Discord interaction verification, OAuth and Activity rendezvous. No game engine."
  (:require [clojure.data.json :as json] [clojure.string :as str] [eurozone.peer :as peer])
  (:import [java.net URI URLEncoder URLDecoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration] [java.util Base64 UUID]
           [java.security Signature KeyFactory MessageDigest] [java.security.spec X509EncodedKeySpec]
           [javax.crypto Mac] [javax.crypto.spec SecretKeySpec]))
(defn config []
  {:client-id (System/getenv "DISCORD_CLIENT_ID") :client-secret (System/getenv "DISCORD_CLIENT_SECRET")
   :public-key (System/getenv "DISCORD_PUBLIC_KEY") :bot-token (System/getenv "DISCORD_BOT_TOKEN")
   :secret (or (System/getenv "EUROZONE_SESSION_SECRET") (System/getenv "DISCORD_CLIENT_SECRET"))
   :private? (= "true" (System/getenv "EUROZONE_PRIVATE"))
   :rtc (some-> (System/getenv "EUROZONE_RTC_CONFIG") not-empty (json/read-str :key-fn keyword))
   :allowed-users (set (remove str/blank? (map str/trim (str/split (or (System/getenv "DISCORD_ALLOWED_USER_IDS") "") #","))))
   :public-url (some-> (System/getenv "EUROZONE_PUBLIC_URL") (str/replace #"/$" ""))})
(defn enabled? [config] (every? #(seq (get config %)) [:client-id :client-secret :public-key :secret :public-url]))
(defn now [] (quot (System/currentTimeMillis) 1000))
(defn utf8 [s] (.getBytes (str s) "UTF-8"))
(defn b64 [bytes] (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))
(defn unb64 [s] (.decode (Base64/getUrlDecoder) ^String s))
(defn hmac [secret text]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (utf8 secret) "HmacSHA256")) (.doFinal mac (utf8 text))))
(defn signed [config value]
  (let [body (b64 (utf8 (json/write-str value)))] (str body "." (b64 (hmac (:secret config) body)))))
(defn verified [config value kind]
  (try
    (let [[body signature extra] (str/split (or value "") #"\." -1)
          valid (and (not extra) body signature (MessageDigest/isEqual (hmac (:secret config) body) (unb64 signature)))
          decoded (when valid (json/read-str (String. (unb64 body) "UTF-8") :key-fn keyword))]
      (when (and (= kind (:kind decoded)) (number? (:exp decoded)) (> (:exp decoded) (now))) decoded))
    (catch Exception _ nil)))
(defn user-token [config user-id]
  (apply str (map #(format "%02x" (bit-and 255 %)) (hmac (:secret config) (str "discord-peer-user:" user-id)))))
(defn allow-user! [config user-id]
  (peer/require! (or (not (:private? config)) (contains? (:allowed-users config) user-id)) "This private Eurozone app is limited to invited Discord users."))
(defn auth [config user]
  (signed config {:kind "session" :user (:id user) :name (or (:global_name user) (:username user) "Farmer") :exp (+ (now) 604800)}))
(defn session-view [config value]
  (when-let [user (verified config value "session")]
    (allow-user! config (:user user))
    {:token (user-token config (:user user)) :userId (:user user) :name (:name user) :auth value}))
(defn authorize-peer! [config token proof]
  (when (:private? config)
    (let [session (session-view config proof)]
      (peer/require! (and session (= token (:token session))) "Sign in to this private Eurozone app with an invited Discord account."))))
(defn cookie [request key]
  (some (fn [part] (let [[k v] (str/split (str/trim part) #"=" 2)] (when (= k key) v)))
        (str/split (get-in request [:headers "cookie"] "") #";")))
(defn encode [s] (URLEncoder/encode (str s) "UTF-8"))
(defn form [values] (str/join "&" (for [[k v] values :when (some? v)] (str (encode (name k)) "=" (encode v)))))
(defn query [request]
  (into {} (for [part (str/split (or (:query-string request) "") #"&") :when (seq part)
                :let [[k v] (str/split part #"=" 2)]] [(keyword (URLDecoder/decode k "UTF-8")) (URLDecoder/decode (or v "") "UTF-8")])))
(defn response [status value]
  {:status status :headers {"Content-Type" "application/json" "Cache-Control" "no-store"} :body (json/write-str value)})
(defn hex-bytes [s]
  (when (and (string? s) (even? (count s)) (re-matches #"[a-fA-F0-9]+" s))
    (byte-array (map #(unchecked-byte (Integer/parseInt (apply str %) 16)) (partition 2 s)))))
(defn valid-signature? [public-key timestamp signature body]
  (try
    (and (= 64 (count public-key)) (= 128 (count signature))
         (<= (Math/abs (- (now) (Long/parseLong timestamp))) 300)
         (let [key (.generatePublic (KeyFactory/getInstance "Ed25519")
                                   (X509EncodedKeySpec. (hex-bytes (str "302a300506032b6570032100" public-key))))
               verifier (Signature/getInstance "Ed25519")]
           (.initVerify verifier key) (.update verifier (utf8 (str timestamp body))) (.verify verifier (hex-bytes signature))))
    (catch Exception _ false)))
(def ^:dynamic *request*
  (fn [method url headers body]
    (let [builder (-> (HttpRequest/newBuilder (URI/create url)) (.timeout (Duration/ofSeconds 10)))]
      (doseq [[k v] headers] (.header builder k v))
      (.method builder method (if body (HttpRequest$BodyPublishers/ofString body) (HttpRequest$BodyPublishers/noBody)))
      (let [result (.send (HttpClient/newHttpClient) (.build builder) (HttpResponse$BodyHandlers/ofString))]
        (peer/require! (<= 200 (.statusCode result) 299) "Discord could not verify this request. Please try again.")
        (json/read-str (.body result) :key-fn keyword)))))
(defn exchange [config code redirect]
  (peer/require! (and (string? code) (<= 1 (count code) 2048)) "Invalid Discord authorization code.")
  (let [tokens (*request* "POST" "https://discord.com/api/v10/oauth2/token" {"Content-Type" "application/x-www-form-urlencoded"}
                          (form {:client_id (:client-id config) :client_secret (:client-secret config) :grant_type "authorization_code" :code code :redirect_uri redirect}))
        user (*request* "GET" "https://discord.com/api/v10/users/@me" {"Authorization" (str "Bearer " (:access_token tokens))} nil)]
    (peer/require! (and (string? (:id user)) (re-matches #"[0-9]+" (:id user))) "Discord returned an invalid identity.")
    {:access_token (:access_token tokens) :session (session-view config (auth config user))}))
(defn table-options [name options]
  (let [options (into {} (map (juxt :name :value) options)) players (get options "players" 2)
        extra (get options "extra" 2) mode (get options "setup" "random") bots (get options "bots" 0)]
    (peer/require! (and (integer? players) (<= 1 players 5) (integer? extra) (<= 0 extra 10)
                        (integer? bots) (<= 0 bots 4) (< bots players)
                        (#{"beginner" "random" "draw"} mode)) "Choose 1–5 total players and fewer bots than players, leaving at least one human seat.")
    {:name (subs name 0 (min 40 (count name))) :title "Agricola with friends" :mode "online" :expected-players players :bots bots :setup {:mode mode :extra extra}}))
(defn invitation [config room invite]
  (str (:public-url config) "/?peer=" room "&key=" invite "&discord=1#" room))
(defn interaction [app config request]
  (let [body (slurp (:body request)) timestamp (get-in request [:headers "x-signature-timestamp"])
        signature (get-in request [:headers "x-signature-ed25519"])]
    (if-not (valid-signature? (:public-key config) timestamp signature body)
      (response 401 {:error "Invalid Discord signature"})
      (let [{:keys [type application_id data member user channel_id id]} (json/read-str body :key-fn keyword)]
        (peer/require! (= application_id (:client-id config)) "Wrong Discord application.")
        (cond
          (= type 1) (response 200 {:type 1})
          (and (= type 2) (= "eurozone" (:name data)))
          (let [user (or (:user member) user) command (first (:options data))
                _ (allow-user! config (:id user))
                options (table-options (or (:global_name user) (:username user) "Host") (:options command))]
            (case (:name command)
              "activity" (do (swap! (:activity-options app) assoc channel_id {:options options :time (now) :host-user (:id user)})
                             (response 200 {:type 12}))
              "agricola"
              (let [room (str (UUID/nameUUIDFromBytes (utf8 (str "discord-command:" id))))
                    invite (b64 (hmac (:secret config) (str "invitation:" id)))
                    ;; Peer invitations use hex so they also work with manual URLs.
                    invite (peer/digest invite)]
                (locking (:lock (:peer-service app))
                  (when-not (get @(:tables (:peer-service app)) room)
                    (peer/create-table! (:peer-service app) {:room room :token (user-token config (:id user)) :invite invite :options options :source :discord})))
                (response 200 {:type 4 :data {:content (str "Agricola · " (:expected-players options) " players. The organizer's browser hosts the table.")
                                             :allowed_mentions {:parse []}
                                             :components [{:type 1 :components [{:type 2 :style 5 :label "Join browser game" :url (invitation config room invite)}]}]}}))
              (response 200 {:type 4 :data {:content "Use /eurozone agricola or /eurozone activity." :flags 64}})))
          :else (response 400 {:error "Unsupported Discord interaction"}))))))
(defn activity-table [app config session instance]
  (peer/require! (and (seq (:bot-token config)) (string? instance) (re-matches #"[a-zA-Z0-9_-]{1,200}" instance)) "Activity verification is not configured or the instance is invalid.")
  (let [verified-instance (*request* "GET" (str "https://discord.com/api/v10/applications/" (:client-id config) "/activity-instances/" instance)
                                     {"Authorization" (str "Bot " (:bot-token config))} nil)
        _ (peer/require! (and (= (:client-id config) (:application_id verified-instance)) (= instance (:instance_id verified-instance))
                             (some #{(:userId session)} (:users verified-instance))) "You are not a participant in this Discord Activity.")
        channel (get-in verified-instance [:location :channel_id]) preset (get @(:activity-options app) channel)
        preset (when (and preset (< (- (now) (:time preset)) 600)) preset)
        room (str (UUID/nameUUIDFromBytes (utf8 (str (:client-id config) ":activity:" instance))))
        invite (peer/digest (b64 (hmac (:secret config) (str "activity-invite:" instance))))]
    (locking (:lock (:peer-service app))
      (when-not (get @(:tables (:peer-service app)) room)
        (peer/create-table! (:peer-service app)
          {:room room :invite invite :token (if preset (user-token config (:host-user preset)) (:token session))
           :options (or (:options preset) (table-options (:name session) [])) :source :activity :instance instance})))
    {:room room :invite invite :relay true}))
(defn handler [app request]
  (let [config (:discord-config app) path (:uri request) method (:request-method request)]
    (try
      (cond
        (= path "/discord/config") (response 200 {:enabled (boolean (enabled? config)) :private (boolean (:private? config)) :clientId (:client-id config) :publicUrl (:public-url config)
                                                   :installUrl (when (enabled? config) (str "https://discord.com/oauth2/authorize?client_id=" (:client-id config)))})
        (and (= path "/discord/rtc") (= method :get))
        (let [proof (or (some-> (get-in request [:headers "authorization"]) (str/replace #"^Bearer " ""))
                        (cookie request "eurozone_discord"))]
          (when (:private? config) (peer/require! (session-view config proof) "Discord sign-in is required."))
          (response 200 (or (:rtc config) {:iceServers [{:urls "stun:stun.l.google.com:19302"}]})))
        (not (enabled? config)) (response 503 {:error "Discord is not configured on this Eurozone server yet."})
        (and (= path "/discord/interactions") (= method :post)) (interaction app config request)
        (and (= path "/discord/session") (= method :get))
        (if-let [session (session-view config (cookie request "eurozone_discord"))] (response 200 session) (response 401 {:error "Sign in with Discord"}))
        (and (= path "/discord/login") (= method :get))
        (let [target (or (:return (query request)) "/") nonce (peer/secret)
              _ (peer/require! (and (str/starts-with? target "/") (not (str/starts-with? target "//")) (not (re-find #"[\\\r\n]" target))) "Invalid return address.")
              state (signed config {:kind "oauth" :nonce nonce :return target :exp (+ (now) 600)})]
          {:status 302 :headers {"Cache-Control" "no-store" "Set-Cookie" (str "eurozone_oauth_nonce=" nonce "; HttpOnly; Secure; SameSite=Lax; Path=/discord; Max-Age=600")
                                "Location" (str "https://discord.com/oauth2/authorize?" (form {:client_id (:client-id config) :response_type "code" :scope "identify"
                                                                                           :redirect_uri (str (:public-url config) "/discord/callback") :state state}))} :body ""})
        (and (= path "/discord/callback") (= method :get))
        (let [{:keys [state code]} (query request) state (verified config state "oauth")
              _ (peer/require! (and state (= (:nonce state) (cookie request "eurozone_oauth_nonce"))) "Discord sign-in expired. Start again from the invitation.")
              result (exchange config code (str (:public-url config) "/discord/callback"))]
          {:status 302 :headers {"Cache-Control" "no-store" "Location" (:return state)
                                "Set-Cookie" [(str "eurozone_discord=" (get-in result [:session :auth]) "; HttpOnly; Secure; SameSite=Lax; Path=/discord; Max-Age=604800")
                                              "eurozone_oauth_nonce=; HttpOnly; Secure; SameSite=Lax; Path=/discord; Max-Age=0"]} :body ""})
        (and (= path "/discord/token") (= method :post))
        (response 200 (exchange config (:code (json/read-str (slurp (:body request)) :key-fn keyword)) nil))
        (and (= path "/discord/activity") (= method :post))
        (let [credential (some-> (get-in request [:headers "authorization"]) (str/replace #"^Bearer " ""))
              session (session-view config credential)]
          (peer/require! session "Discord sign-in is required.")
          (response 200 (activity-table app config session (:instance (json/read-str (slurp (:body request)) :key-fn keyword)))))
        :else (response 404 {:error "Discord endpoint not found"}))
      (catch Exception e (response 400 {:error (.getMessage e)})))))
