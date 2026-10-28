;; Run this reader against the recording's archived rules on the classpath.
(require '[clojure.data.json :as json] '[clojure.java.io :as io]
         '[agricola.simulation :as sim] '[agricola.recording :as recording]
         '[agricola.engine :as a] '[agricola.card-game :as g] '[agricola.card-rules :as cards])
(def rules (sim/rules-hash))
(defn inspect-recording [path]
  (let [records (sim/read-records path) header (first records) result (last records)
        moves (filterv #(= :move (:record %)) records)]
    {:records records
     :info {:path path :rules (:rules-hash header) :seed (:seed header)
            :players (count (get-in header [:initial :players]))
            :mode (or (get-in header [:options :mode]) (if (= :draw (get-in header [:initial :phase])) :draw :random))
            :policies (:policies header) :study (get-in header [:options :study])
            :partial-start (boolean (:partial-start? header))
            :initial-round (get-in header [:initial :round])
            :status (cond (not= :header (:record header)) :not-recording (= :result (:record result)) :complete (= :error (:record result)) :failed :else :partial)
            :moves (count moves) :scores (when (= :result (:record result)) (mapv :total (:scores result)))
            :id (recording/sha (pr-str [(:rules-hash header) (:hash header) (mapv :hash moves) (:hash result)]))}}))
(defn fields [p] (filter #(= :field (:type %)) (concat (:farm p) (vals (:card-fields p)))))
(defn public-position [state pid]
  (let [p (get-in state [:players pid]) rates (a/cook-rates p)
        edible (reduce + (for [resource (concat a/animals [:grain :vegetable])]
                           (* (get rates resource 0) (a/amount p resource))))]
    {:family (:family p) :rooms (count (a/tiles p :room)) :food (a/amount p :food)
     :begging (:begging p) :food-required (a/food-needed state p)
     :liquid-food (+ (a/amount p :food) edible)
     :kitchen (boolean (some #(pos? (get rates % 0)) a/animals))
     :oven (boolean (some #(:oven (a/improvements %)) (:improvements p)))
     :productive-fields (count (filter #(pos? (:count % 0)) (fields p)))
     :breeding-pairs (count (filter #(>= (a/amount p %) 2) a/animals))
     :major-improvements (mapv name (:improvements p))
     :occupations (mapv str (:occupations p)) :minors (mapv str (:minors p))
     :wood (a/amount p :wood) :reed (a/amount p :reed) :clay (a/amount p :clay)
     :grain (a/amount p :grain) :vegetable (a/amount p :vegetable)}))
(defn take-snapshots [rows state]
  (reduce (fn [out [key match?]]
            (if match?
              (mapv (fn [pid row] (if (get-in row [:snapshots key]) row
                                      (assoc-in row [:snapshots key] (merge (public-position state pid)
                                                                          (select-keys row [:placements :food-space-actions :food-consumed :net-food-income])))))
                    (range) out) out)) rows
          [[:pre-harvest-4 (and (= 4 (:round state)) (= :feeding (:phase state)) (empty? (:fed state)))]
           [:start-5 (= 5 (:round state))]
           [:pre-harvest-7 (and (= 7 (:round state)) (= :feeding (:phase state)) (empty? (:fed state)))]
           [:start-8 (= 8 (:round state))]
           [:pre-harvest-9 (and (= 9 (:round state)) (= :feeding (:phase state)) (empty? (:fed state)))]]))
(defn update-rows [rows before after move]
  (mapv
   (fn [pid row]
     (let [p (get-in before [:players pid]) q (get-in after [:players pid]) cmd (:command move)
           feed? (and (= pid (:player move)) (= :feed (:type cmd)))
           begged (- (:begging q) (:begging p))
           consumed (if feed? (- (a/food-needed before p) begged) 0)
           placed (if (and (= pid (:player move)) (= :work (:phase before))
                           (#{:action :card-action :resolve} (:type cmd)))
                    (if (< (:round before) (:round after)) (:remaining p) (max 0 (- (:remaining p) (:remaining q)))) 0)
           growth (- (:family q) (:family p))
           kitchen? (:kitchen (public-position after pid))]
       (cond-> (-> row (update :placements + placed)
                   (update :food-consumed + consumed)
                   (update :net-food-income + (+ (- (a/amount q :food) (a/amount p :food)) consumed)))
         (pos? growth) (update :growth-rounds into (repeat growth (:round before)))
         (and kitchen? (nil? (:first-kitchen row))) (assoc :first-kitchen (:round before))
         (and (pos? placed) (#{:fishing :labor :traveling} (:space cmd))) (update :food-space-actions + placed)
         (and (pos? placed) (<= (:round before) 4))
         (update :opening conj (merge (select-keys cmd [:space :card :improvement :type])
                                     {:round (:round before) :action-name (get-in a/actions [(:space cmd) :name])
                                      :card-name (:name (cards/card (:card cmd)))
                                      :improvement-name (get-in a/improvements [(:improvement cmd) :name])}))
         feed? (update :harvests conj {:round (:round before) :required (a/food-needed before p) :begging begged
                                      :consumed consumed :food-left (a/amount q :food) :family (:family q)}))))
   (range) rows))
(defn analyze [path compatible?]
  (let [{:keys [records info]} (inspect-recording path)]
    (a/require! (or compatible? (= rules (:rules info))) "Archived rules do not match this recording.")
    (a/require! (= :complete (:status info)) "Recording is not complete.")
    (let [initial (:initial (first records))
          _ (a/require! (= (:hash (first records)) (recording/state-hash initial)) "Bad initial hash.")
          initial-rows (mapv (fn [pid] {:seat pid :placements 0 :food-space-actions 0 :food-consumed 0 :net-food-income 0
                                        :growth-rounds [] :harvests [] :opening [] :snapshots {}}) (range (:players info)))]
      (loop [state initial pending (rest records) rows (take-snapshots initial-rows initial) i 0]
        (let [entry (first pending)]
          (case (:record entry)
            :move (let [effects (atom [])
                        next (binding [g/*effect-observer* #(swap! effects conj %)] (recording/apply-command state (:player entry) (:command entry)))]
                    (a/require! (and (= i (:index entry)) (= (:hash entry) (recording/state-hash next)) (= (:effects entry) @effects)) (str "Replay mismatch at move " i))
                    (recur next (rest pending) (take-snapshots (update-rows rows state next entry) next) (inc i)))
            :result (do (a/require! (and (= :finished (:phase state)) (= i (:moves entry)) (= (:scores entry) (:scores state))
                                        (= (:hash entry) (recording/state-hash state)) (empty? (rest pending))) "Result mismatch.")
                        (assoc info :replayed true :replay-rules rules :seats
                               (mapv (fn [pid row]
                                       (merge row {:score (get-in state [:scores pid :total])
                                                   :begging (get-in state [:players pid :begging])
                                                   :final-family (get-in state [:players pid :family])
                                                   :win (boolean (some #{pid} (:winners state)))
                                                   :tie (> (count (:winners state)) 1)
                                                   :starting-seat (= pid (:starting-player initial))
                                                   :growth-available (inc (.indexOf ^java.util.List (:round-order initial) :growth))
                                                   :score-without-begging (- (get-in state [:scores pid :total]) (get-in state [:scores pid :breakdown :begging] 0))}))
                                     (range) rows)))
            (throw (ex-info "Unexpected recording entry" {}))))))))
(doseq [line (line-seq (java.io.BufferedReader. *in*))]
  (try
    (let [{:keys [op path]} (json/read-str line :key-fn keyword)]
      (println (json/write-str (case op "index" (:info (inspect-recording path)) "analyze" (analyze path false) "compatible" (analyze path true) "rules" {:rules rules}
                                "saved-tables" (let [rooms (clojure.edn/read-string (slurp path))]
                                                 (mapv (fn [[id room]] {:id id :phase (get-in room [:state :phase]) :recorded (count (:recording room))}) rooms))))))
    (catch Exception e (println (json/write-str {:error (.getMessage e)}))))
  (flush))
