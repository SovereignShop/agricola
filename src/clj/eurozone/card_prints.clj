(ns eurozone.card-prints
  "Build-time audit of the browser's actual print lookup and bundled assets."
  (:require [agricola.card-rules :as cards] [clojure.java.io :as io]
            [clojure.data.json :as json])
  (:import [java.security MessageDigest] [java.nio.file Files] [javax.imageio ImageIO]))

(defn lookup []
  ;; Read data, never evaluate browser code or duplicate its lists.
  (with-open [r (java.io.PushbackReader. (io/reader "src/cljs/eurozone/browser/card_art.cljs"))]
    (into {} (for [form (take-while some? (repeatedly #(read {:eof nil} r)))
                   :when (and (seq? form) (= 'def (first form)))]
               [(second form) (nth form 2)]))))
(defn asset [lookup id]
  (or (when-let [file (get-in lookup ['printed-files id])] (str "cards/" file))
      (when ((get lookup 'available) id) (str "cards/" (name id) ".png"))
      (first (get-in lookup ['original-sprites id]))))
(defn sha256 [file]
  (format "%064x" (java.math.BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") (Files/readAllBytes (.toPath file))))))
(defn audit []
  (let [lookup (lookup) root "resources/public/art/agricola"
        manifest (json/read-str (slurp (io/file root "manifest.json")) :key-fn keyword)
        entries (group-by :file (:files manifest))
        assets (into {} (for [id (keys cards/cards)] [id (asset lookup id)]))
        missing (vec (sort (for [[id file] assets :when (nil? file)] id)))
        broken (vec
                (for [path (sort (set (keep val assets)))
                      :let [file (io/file root path) entry (first (entries path))
                            problem (cond
                                      (not (.isFile file)) "file missing"
                                      (not= 1 (count (entries path))) "missing or duplicate manifest entry"
                                      (not (re-find #"^https?://" (:source entry ""))) "missing source URL"
                                      (not= (:sha256 entry) (sha256 file)) "checksum mismatch"
                                      :else (try (when-not (ImageIO/read file) "invalid image")
                                                 (catch Exception _ "invalid image")))]
                      :when problem] {:file path :problem problem}))
        bad-sprites (vec (for [[id [_ index columns rows]] (get lookup 'original-sprites)
                              :when (and (contains? cards/cards id)
                                         (not (and (integer? index) (<= 0 index)
                                                   (< index (* (or columns 10) (or rows 7))))))] id))]
    {:implemented (count assets) :covered (- (count assets) (count missing))
     :missing missing :broken broken :bad-sprites bad-sprites}))
(defn verify! []
  (let [report (audit)]
    (when (some seq ((juxt :missing :broken :bad-sprites) report))
      (throw (ex-info "Implemented cards must have valid, sourced English prints." report)))
    (println "Card prints:" (:covered report) "/" (:implemented report) "implemented hand cards covered.")
    report))
