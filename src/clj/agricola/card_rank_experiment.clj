(ns agricola.card-rank-experiment
  "Offline draft-ranking experiments. No changes to production bots or rules."
  (:require [clojure.data.json :as json] [clojure.java.io :as io]
            [agricola.simulation :as sim] [agricola.card-study :as study]
            [agricola.card-rules :as cards] [agricola.draw :as draw]
            [agricola.engine :as engine] [agricola.ai :as ai]
            [agricola.recording :as recording]))

(defn rank-value [model cid selected]
  (+ (double (get-in model ["main" (str cid)] 0))
     (reduce + 0 (for [other selected] (double (get-in model ["pairs" (str cid) (str other)] 0))))))

(defn pick [state pid model]
  (let [selected (mapcat val (get-in state [:draw :selected pid]))
        legal (draw/legal-cards (:draw state) pid)
        cid (last (sort-by (juxt #(rank-value model % selected) str) legal))]
    (engine/require! cid "There is no legal draft choice.")
    {:command {:type :pick :wave (get-in state [:draw :wave]) :card cid}
     :value (rank-value model cid selected)}))

(defn run-game [{:keys [seed focal formula models output]}]
  (engine/require! (and (integer? seed) (#{0 1} focal)) "Choose a seed and focal seat.")
  (engine/require! (not (.exists (io/file output))) "Recording already exists.")
  (let [text (slurp models) learned (json/read-str text) model (get-in learned ["models" formula])
        _ (engine/require! (= (get learned "rules") (:rules @ai/fingerprints)) "Card rankings use different rules.")
        _ (engine/require! (or (= formula "heuristic") model) "Unknown card ranking.")
        choose sim/choose decisions (atom [])]
    (io/make-parents output)
    (let [result
          (with-open [writer (io/writer output)]
            (with-redefs [sim/choose
                          (fn [state pid rng policy]
                            (let [draft? (= :draw (:phase state))
                                  choice (if (and draft? (= pid focal) model) (pick state pid model)
                                             (choose state pid rng policy))]
                              (when (and draft? (= pid focal))
                                (swap! decisions conj {:wave (get-in state [:draw :wave])
                                                       :selected (mapv str (mapcat val (get-in state [:draw :selected pid])))
                                                       :card (str (get-in choice [:command :card])) :value (:value choice)}))
                              choice))]
              (sim/run-game {:seed seed :players 2 :mode :draw :extra 2 :policy :heuristic
                             :study {:kind :card-expected-value :formula formula :focal focal
                                     :rankings-hash (recording/sha text)}}
                            #(do (.write writer (str (pr-str %) "\n")) (.flush writer)))))
          verified (sim/replay output)]
      (engine/require! (= :complete (:status verified)) "Draft experiment replay failed.")
      {:seed seed :model_seat focal :formula formula :scores (mapv :total (:scores result))
       :moves (:moves result) :replay "complete" :recording output :draft @decisions
       :begging (:begging result) :families (:families result) :winners (:winners result)})))

(defn request [{:keys [op] :as message}]
  (case op
    "catalog" {:fingerprints @ai/fingerprints
               :cards (mapv #(assoc (-> (select-keys % [:id :name :kind :min-players :printed-cost :points :prerequisites])
                                       (update :id str)) :heuristic (sim/draw-value (:id %))) (study/catalog 2))}
    "game" (run-game message)
    (throw (ex-info "Unknown ranking experiment request." {}))))

(defn -main [& _]
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (try (println (json/write-str (request (json/read-str line :key-fn keyword))))
         (catch Exception e (println (json/write-str {:error (.getMessage e)}))))
    (flush)))
