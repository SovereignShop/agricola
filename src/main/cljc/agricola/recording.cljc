(ns agricola.recording
  "Portable, verified command playback. Loading a recording never mutates a game."
  (:require [agricola.engine :as a] [agricola.card-game :as g] [agricola.draw :as draw]
            #?(:cljs [goog.crypt :as crypt]) #?(:cljs [goog.crypt.Sha256]))
  #?(:clj (:import [java.security MessageDigest])))

(defn canonical [x]
  (cond (map? x) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2))) (map (fn [[k v]] [k (canonical v)]) x))
        (set? x) (into (sorted-set-by #(compare (pr-str %1) (pr-str %2))) (map canonical x))
        (sequential? x) (mapv canonical x)
        :else x))
(defn sha [text]
  #?(:clj (format "%064x" (java.math.BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (str text) "UTF-8"))))
     :cljs (let [digest (goog.crypt.Sha256.)]
             (.update digest (crypt/stringToUtf8ByteArray (str text)))
             (crypt/byteArrayToHex (.digest digest)))))
(defn state-hash [state]
  ;; Clojure defaults this to true, ClojureScript to false. Version-1 logs use
  ;; the JVM spelling (e.g. #:revised-base{:a044 ...}); keep both runtimes exact.
  (binding [*print-namespace-maps* true *print-length* nil *print-level* nil]
    (sha (pr-str (canonical state)))))
(defn apply-command [state pid command]
  (if (= :draw (:phase state))
    (do (a/require! (= :pick (:type command)) "The draw requires a pick.")
        (let [state (update state :draw draw/pick pid (:wave command) (:card command))]
          (if (= :complete (get-in state [:draw :status]))
            (g/initialize (update state :players #(mapv (fn [p] (assoc p :hand (get-in state [:draw :selected (:id p)]))) %))) state)))
    ((if (= :cards (:variant state)) g/command a/command) state pid command)))
(defn timeline [records]
  (let [[header & records] records initial (:initial header)]
    (a/require! (and (= :header (:record header)) (= 1 (:format header))) "Unsupported recording format.")
    (a/require! (= (:hash header) (state-hash initial)) "Initial-state hash mismatch.")
    (loop [state initial records records frames [initial] moves []]
      (if-let [record (first records)]
        (case (:record record)
          :move (let [effects (atom [])
                      next (binding [g/*effect-observer* #(swap! effects conj %)]
                             (apply-command state (:player record) (:command record)))]
                  (a/require! (= (count moves) (:index record)) "Recording move index mismatch.")
                  (a/require! (= (:hash record) (state-hash next)) (str "Replay diverged at move " (:index record) "."))
                  (a/require! (= (:effects record) @effects) "Replay effect trace mismatch.")
                  (recur next (rest records) (conj frames next) (conj moves record)))
          :result (do (a/require! (and (= :finished (:phase state)) (= (:hash record) (state-hash state))
                                      (= (:scores record) (:scores state)) (= (count moves) (:moves record))) "Result mismatch.")
                      (a/require! (empty? (rest records)) "Unexpected records after result.")
                      {:status :complete :frames frames :moves moves :result record :header header})
          :error (do (a/require! (empty? (rest records)) "Unexpected records after error.")
                     {:status :failed-recording :frames frames :moves moves :failure record :header header})
          (throw (ex-info "Unknown recording entry." {:record (:record record)})))
        {:status :partial :frames frames :moves moves :header header}))))
