(ns agricola.growth-value
  "Early-game value iteration from frozen stronger-parent continuations."
  (:require [clojure.data.json :as json] [clojure.java.io :as io] [clojure.string :as str]
            [agricola.afterstate-value :as value] [agricola.growth-learning :as growth]
            [agricola.bot :as bot] [agricola.simulation :as sim] [agricola.recording :as rec]
            [agricola.engine :as a] [eurozone.play-values :as parent]))
(def original-features value/features)
(defn source-hash []
  (rec/sha (str (growth/source-hash) (slurp (io/resource "agricola/growth_value.clj")))))
(defn features [state pid]
  (merge (into {} (remove #(str/starts-with? (key %) "pair/") (original-features state pid)))
         (growth/features state pid)))
(defn extract [{:keys [recording output case]}]
  (let [records (sim/read-records recording) timeline (rec/timeline records)
        _ (a/require! (= (sim/rules-hash) (:rules-hash (:header timeline))) "Source rules differ.")
        _ (a/require! (= :complete (:status timeline)) "Incomplete source recording.")
        scores (mapv :total (:scores (:result timeline)))]
    (io/make-parents output)
    (with-open [writer (io/writer output)]
      (let [n (reduce (fn [n [state move]]
                        (if (growth/eligible? state)
                          (let [pid (:player move) after (bot/preview (bot/observation state pid) pid (:command move))]
                            (if after
                              (let [f (features after pid)]
                                (.write writer (str (json/write-str {:case case :recording recording :round (:round state)
                                                                    :player pid :features f :target (scores pid)
                                                                    :parent (value/predict parent/model f)}) "\n"))
                                (inc n)) n)) n)) 0 (map vector (:frames timeline) (:moves timeline)))]
        {:rows n :output output :case case :replay "complete"}))))
(defn choose [state pid model margin]
  (if (and model (growth/eligible? state))
    (with-redefs [parent/model model value/features features]
      (growth/choose state pid nil margin 0.0))
    (growth/choose state pid nil margin 0.0)))
(defn run-game [{:keys [seed focal model margin output mode] :or {margin 1.0}}]
  (let [model (when model (json/read-str (slurp model)))
        _ (when model
            (a/require! (= (source-hash) (get model "source")) "Early value source differs.")
            (a/require! (= (sim/rules-hash) (get model "rules")) "Early value rules differ."))
        result (growth/recorded-game
                {:seed seed :players 2 :mode (keyword (or mode "random")) :extra 2 :policy :heuristic
                 :study {:kind :early-value-iteration :focal focal
                         :model-hash (when model (rec/sha (pr-str model))) :margin margin}}
                output (fn [state pid] (if (= focal pid) (choose state pid model margin)
                                          (growth/parent-choice state pid))) nil)]
    (assoc result :model_seat focal)))
(defn -main [& _]
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (try (let [request (json/read-str line :key-fn keyword)]
           (println (json/write-str (case (:op request)
                                     "extract" (extract request) "game" (run-game request)
                                     "schema" {:rules (sim/rules-hash) :source (source-hash)
                                               :parent_weights (get parent/model "weights")}))))
         (catch Exception e (binding [*out* *err*] (.printStackTrace e))
           (println (json/write-str {:error (.getMessage e)}))))
    (flush)))
