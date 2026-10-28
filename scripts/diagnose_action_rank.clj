;; Attribute observed score changes; descriptive, not a causal decomposition.
;; clojure -M scripts/diagnose_action_rank.clj EXPERIMENT_DIRECTORY
(require '[clojure.data.json :as json] '[clojure.java.io :as io]
         '[clojure.string :as str] '[agricola.simulation :as sim])
(let [directory (first *command-line-args*)
      rows (map #(json/read-str % :key-fn keyword)
                (str/split-lines (slurp (io/file directory "games.jsonl"))))
      rows (filter #(str/starts-with? (:stage %) "test-") rows)
      result (mapv (fn [row]
                     (let [records (sim/read-records (:recording row))
                           final (last records) pid (:model_seat row)
                           moves (filter #(and (= :move (:record %)) (= pid (:player %))
                                               (<= (:round %) 10) (= :action (get-in % [:command :type]))) records)]
                       (assoc (select-keys row [:stage :variant :seed :model_seat :first_growth :begging])
                              :breakdown (get-in final [:scores pid :breakdown])
                              :early-actions (frequencies (map #(get-in % [:command :space]) moves))))) rows)]
  (spit (io/file directory "score-diagnostics.json") (json/write-str result))
  (println "Diagnosed" (count result) "verified final-test recordings."))
