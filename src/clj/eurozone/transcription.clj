(ns eurozone.transcription
  "Authenticated, bounded audio forwarding to an explicitly configured loopback worker."
  (:require [clojure.data.json :as json] [clojure.string :as str]
            [eurozone.discord :as discord] [eurozone.peer :as peer])
  (:import [java.net URI] [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration] [java.util.concurrent Semaphore]))
(defonce gate (Semaphore. 1))
(defn configured-url []
  (when-let [value (not-empty (System/getenv "EUROZONE_TRANSCRIBE_URL"))]
    (let [uri (URI/create value)]
      (when-not (and (= "http" (.getScheme uri)) (= "127.0.0.1" (.getHost uri))
                     (= "/transcribe" (.getPath uri)) (nil? (.getUserInfo uri)) (nil? (.getQuery uri)))
        (throw (ex-info "EUROZONE_TRANSCRIBE_URL must point to a loopback /transcribe endpoint" {})))
      value)))
(defn authorize! [app request]
  (let [headers (:headers request) pair (get headers "x-eurozone-voice-pair")]
    (if pair
      (let [clients (vals @(:clients (:voice-service app))) now (System/currentTimeMillis)]
        (when-not (and (re-matches #"[a-f0-9]{64}" pair)
                       (some #(and (= pair (:pair %)) (= :mic (:role %)) (< now (:expires %))) clients)
                       (some #(and (= pair (:pair %)) (= :game (:role %)) (< now (:expires %))) clients))
          (throw (ex-info "Pair the microphone page with your game first." {:status 401}))))
      (let [token (some-> (get headers "authorization") (str/replace #"^Bearer " ""))]
        (when-not (peer/valid-token? token) (throw (ex-info "Open a game session before recording." {:status 401})))
        (discord/authorize-peer! (:discord-config app) token (get headers "x-eurozone-auth"))))))
(defn response [status body] {:status status :headers {"Content-Type" "application/json" "Cache-Control" "no-store"} :body (json/write-str body)})
(defn transcribe [app request]
  (try
    (authorize! app request)
    (if-let [url (:transcription-url app)]
      (if-not (.tryAcquire gate)
        (response 429 {:error "Transcription is busy. Try again in a moment."})
        (try
          (let [bytes (.readNBytes ^java.io.InputStream (:body request) 2000001)]
            (when-not (< 0 (alength bytes) 2000001) (throw (ex-info "Recording too large or empty." {:status 413})))
            (let [out (-> (HttpRequest/newBuilder (URI/create url)) (.timeout (Duration/ofSeconds 45))
                          (.header "Content-Type" "application/octet-stream")
                          (.POST (HttpRequest$BodyPublishers/ofByteArray bytes)) .build)
                  result (.send (HttpClient/newHttpClient) out (HttpResponse$BodyHandlers/ofString))
                  value (json/read-str (.body result) :key-fn keyword)]
              (if (= 200 (.statusCode result))
                (response 200 {:text (subs (str (:text value)) 0 (min 300 (count (str (:text value)))))})
                (response (.statusCode result) {:error (or (:error value) "Transcription failed. Try again.")}))))
          (finally (.release gate))))
      (response 503 {:error "Local transcription is not enabled on this server."}))
    (catch clojure.lang.ExceptionInfo e (response (or (:status (ex-data e)) 403) {:error (.getMessage e)}))
    (catch Exception _ (response 503 {:error "Local transcription is unavailable. Please try again."}))))
