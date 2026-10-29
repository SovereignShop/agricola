(ns agricola.card-coverage
  "Executable coverage is counted by catalogue identity, including missing rules.
   A definition is not a claim of exhaustive combination verification."
  (:require [agricola.card-rules :as cards] [agricola.engine :as engine] [clojure.data.json :as json]
            [agricola.simulation :as simulation] [clojure.edn :as edn] [clojure.java.io :as io]))
(def base-majors
  {"1" :fireplace-2 "2" :fireplace-3 "3" :hearth-4 "4" :hearth-5
   "5" :well "6" :clay-oven "7" :stone-oven "8" :joinery "9" :pottery "10" :basketry})
(defn engine-major [card]
  (when (and (= "Revised" (:edition card)) (= "Base (Revised)" (:pack card)) (= :major (:kind card)))
    (let [id (base-majors (:number card))] (when (engine/improvements id) id))))
(defn entries []
  (mapv (fn [[cid card]]
          (assoc (select-keys card [:catalogue-id :name :edition :pack :deck :number :type :kind :min-players :pack-id])
                 :native-id (str cid)
                 :engine-id (some-> (engine-major card) str)
                 :status (cond (contains? cards/cards cid) :native-definition
                               (engine-major card) :native-major
                               :else :missing-native-definition)
                 :requires (cond
                             (= "Farmers of the Moor" (:pack card)) [:moor-variant]
                             (#{:mother :father} (:kind card)) [:parent-variant]
                             (= :other (:kind card)) [:special-card-type]
                             :else [])))
        (sort-by (comp str key) cards/metadata)))
(defn report []
  (let [entries (entries) rules (simulation/rules-hash)
        draft (edn/read-string (slurp (io/resource "eurozone/draft-values.edn")))
        play (edn/read-string (slurp (io/resource "eurozone/play-values.edn")))]
    {:catalogue-entries (count entries)
     :rules-hash rules
     :learned-policies {:draft {:training-rules (:rules-hash draft)
                               :matches-current-rules? (= rules (:rules-hash draft))
                               :ranked-cards (count (:values draft))}
                        :play {:training-rules (get play "rules")
                               :matches-current-rules? (= rules (get play "rules"))}
                        :note "Prior benchmark results apply to the training rules. Unranked draft cards use heuristic estimates."}
     :native-hand-cards (count (filter #(and (= :native-definition (:status %)) (#{:occupation :minor} (:kind %))) entries))
     :native-major-cards (count (filter #(= :native-major (:status %)) entries))
     :status-counts (frequencies (map :status entries))
     :packs (mapv (fn [[id rows]] {:id id :catalogue-count (count rows) :status-counts (frequencies (map :status rows))})
                  (sort-by key (group-by :pack-id entries)))
     :cards entries}))
(defn -main [& [output]]
  (let [report (report)]
    (if output
      (do (io/make-parents output) (spit output (json/write-str report :escape-slash false))
          (println "Wrote" (:catalogue-entries report) "coverage entries to" output))
      (doseq [pack (:packs report)] (println (:id pack) (:status-counts pack))))))
