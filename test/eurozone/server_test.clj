(ns eurozone.server-test
  (:require [clojure.test :refer [deftest is]] [eurozone.server :as server])
  (:import [java.io ByteArrayInputStream File] [java.util.zip GZIPInputStream]))

(deftest static-assets-negotiate-cache-and-refresh
  (let [file (File/createTempFile "eurozone-asset" ".js")
        url (.toURL (.toURI file)) path (.getPath file)
        request {:request-method :get :headers {}}
        text (apply str (repeat 100 "let greeting = 'Hello Eurozone';\n"))]
    (try
      (spit file text)
      (let [plain (server/static-response request path url)
            zipped (server/static-response (assoc-in request [:headers "accept-encoding"] "br, gzip") path url)
            etag (get-in zipped [:headers "ETag"])]
        (is (= text (String. ^bytes (:body plain) "UTF-8")))
        (is (= text (slurp (GZIPInputStream. (ByteArrayInputStream. (:body zipped))))))
        (is (< (alength ^bytes (:body zipped)) (alength ^bytes (:body plain))))
        (is (= "gzip" (get-in zipped [:headers "Content-Encoding"])))
        (is (= "Accept-Encoding" (get-in zipped [:headers "Vary"])))
        (is (= "no-cache" (get-in zipped [:headers "Cache-Control"])))
        (is (not= etag (get-in plain [:headers "ETag"])))
        (let [cached (server/static-response (assoc request :headers {"accept-encoding" "gzip" "if-none-match" (str "W/" etag)}) path url)]
          (is (= 304 (:status cached))) (is (nil? (:body cached))))
        (is (= 200 (:status (server/static-response (assoc-in request [:headers "if-none-match"] etag) path url))))
        (let [head (server/static-response (assoc request :request-method :head :headers {"accept-encoding" "gzip"}) path url)]
          (is (nil? (:body head))) (is (= (:headers zipped) (:headers head))))
        (spit file (str text "// changed"))
        (let [changed (server/static-response request path url)]
          (is (not= (get-in plain [:headers "ETag"]) (get-in changed [:headers "ETag"]))))
        (doseq [encoding ["gzip;q=0" "gzip; q=0, *;q=1" "br" nil]]
          (is (not (server/accepts-gzip? encoding))))
        (doseq [encoding ["gzip" "GZIP; q=0.5" "br, *;q=1"]]
          (is (server/accepts-gzip? encoding))))
      (finally (.delete file) (swap! server/asset-cache dissoc path)))))

(deftest static-routing-stays-confined
  (doseq [uri ["/../deps.edn" "/%2e%2e/deps.edn" "/.env"]]
    (is (= 404 (:status (server/handler {} {:uri uri :request-method :get})))))
)
