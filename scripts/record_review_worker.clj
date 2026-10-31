;; One sequential JVM per shard: with-redefs never overlaps another game.
(require '[agricola.simulation :as sim] '[agricola.recording :as rec]
         '[agricola.card-rules :as cards] '[agricola.engine :as engine]
         '[eurozone.bot-player :as runtime] '[clojure.data.json :as json]
         '[clojure.java.io :as io])

(defn card-row [cid] {:id (str cid) :name (or (:name (cards/card cid)) (:name (engine/improvements cid)) (str cid))})
(defn inspect-recording [path]
  (let [{:keys [status frames moves result failure header]} (rec/timeline (sim/read-records path))
        final (peek frames)
        coverage (sim/coverage (:initial header) moves final)
        played (set (mapcat #(cards/owned %) (:players final)))
        cards-in-effects (set (keep :card (mapcat :effects moves)))]
    (doseq [state frames] (sim/assert-invariants! state))
    {:status (name status) :verified true :rules (:rules-hash header)
     :moves (count moves) :round (:round final)
     :scores (mapv :total (:scores result)) :begging (mapv :begging (:players final))
     :families (mapv :family (:players final))
     :dealt (mapv card-row (sort (:dealt coverage)))
     :played (mapv card-row (sort (into played (:played coverage))))
     :effects (mapv card-row (sort cards-in-effects))
     :rounds (vec (for [[i state] (map-indexed vector frames)
                       :when (or (zero? i) (not= (:round state) (:round (nth frames (dec i)))))]
                   {:round (:round state) :move i}))
     :error (:message failure) :attempt (when failure (pr-str (:attempt failure)))}))

(let [[directory player-text mode-text count-text seed-text] *command-line-args*
      players (Long/parseLong player-text) mode (keyword mode-text)
      base-seed (Long/parseLong seed-text) n (Long/parseLong count-text)
      original-choose sim/choose]
  (doseq [i (range n)]
    (let [seed (+ base-seed i) path (str directory "/game-" seed ".edn")
          stress? (= 4 (mod i 5)) random-seat (mod (quot i 5) players)
          policies (mapv #(if (and stress? (= random-seat %)) :random :deployed) (range players))
          options {:seed seed :players players :mode mode :extra (nth [0 2 4 8 10] (mod i 5))
                   :policy :heuristic :review-policies policies :max-moves 2000}
          start (System/nanoTime)
          outcome (try
                    (engine/require! (.createNewFile (io/file path)) "Refusing to overwrite a recording.")
                    (with-open [writer (io/writer path)]
                      (with-redefs [sim/choose (fn [state pid rng _]
                                                (if (= :random (policies pid))
                                                  (original-choose state pid rng :random)
                                                  {:command (runtime/choose (runtime/observation state pid) pid)}))]
                        (sim/run-game options
                                      (fn [entry]
                                        (.write writer (str (pr-str (cond-> entry
                                                                     (#{:header :result} (:record entry)) (assoc :policies policies))) "\n"))
                                        (.flush writer)))))
                    (inspect-recording path)
                    (catch Exception e
                      (try (assoc (inspect-recording path) :error (or (some-> e .getCause .getMessage) (.getMessage e)))
                           (catch Exception verification
                             {:status "unverified-error" :verified false :error (.getMessage e)
                              :verification-error (.getMessage verification)}))))
          row (merge {:seed seed :players players :mode (name mode) :extra (:extra options)
                      :policies (mapv name policies) :file (str "game-" seed ".edn")
                      :seconds (/ (- (System/nanoTime) start) 1e9)
                      :scores [] :played [] :dealt [] :effects [] :moves 0} outcome)]
      (println (json/write-str row)) (flush))))
(shutdown-agents)
