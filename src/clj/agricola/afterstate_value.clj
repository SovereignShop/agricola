(ns agricola.afterstate-value
  "Experimental public-information afterstate regression. Production policy is unchanged."
  (:require [clojure.data.json :as json] [clojure.java.io :as io]
            [agricola.bot :as bot] [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as cards] [agricola.simulation :as sim]
            [agricola.recording :as recording]))

(def version 1)
(defn source-hash []
  (recording/sha (apply str (map #(slurp (io/resource %))
                                ["agricola/afterstate_value.clj" "agricola/bot.clj" "agricola/simulation.clj"]))))
(defn flag [v] (if v 1.0 0.0))
(defn farm-features [state pid prefix]
  (let [p (g/p state pid) rates (a/cook-rates p) fields (g/fields p)
        food (+ (a/amount p :food)
                (reduce + (for [r a/animals] (* (get rates r 0) (max 0 (- (a/amount p r) 2))))))]
    (into {} (map (fn [[k v]] [(str "base/" prefix "/" k) (double v)]))
          (merge {"score" (/ (:total (g/score state pid)) 40.0)
                  "family" (/ (:family p) 5.0) "remaining" (/ (:remaining p 0) 5.0)
                  "rooms" (/ (count (a/tiles p :room)) 5.0)
                  "growth-room" (flag (> (count (a/tiles p :room)) (:family p)))
                  "fields" (/ (count fields) 5.0)
                  "productive-fields" (/ (count (filter #(pos? (:count % 0)) fields)) 5.0)
                  "pastures" (/ (count (a/pastures p)) 4.0)
                  "begging" (/ (:begging p) 5.0)
                  "kitchen" (flag (pos? (get rates :sheep 0)))
                  "oven" (flag (some #(:oven (a/improvements %)) (:improvements p)))
                  "food-deficit" (/ (max 0 (- (* 2 (:family p)) food)) 10.0)
                  "occupations" (/ (count (:occupations p)) 7.0)
                  "minors" (/ (count (:minors p)) 7.0)
                  "clay-house" (flag (= :clay (:house p)))
                  "stone-house" (flag (= :stone (:house p)))
                  "starting" (flag (= pid (:starting-player state)))}
                 (into {} (for [r [:food :wood :clay :reed :stone :grain :vegetable :sheep :boar :cattle]]
                            [(name r) (/ (a/amount p r) 10.0)]))))))

(defn features
  "No opponent hands, unrevealed rounds, or final-game information are consulted.
   Pair terms describe available combinations, not proven causal card effects."
  [state pid]
  (a/require! (= 2 (count (:players state))) "Value experiment supports two players.")
  (let [state (bot/observation state pid) p (g/p state pid)
        future (/ (- 14 (:round state)) 13.0)
        own (farm-features state pid "own")
        opponent (farm-features state (- 1 pid) "opponent")
        base (merge own opponent
                    {"base/bias" 1.0 "base/future" future "base/future2" (* future future)
                     "base/utility" (/ (bot/utility state pid) 40.0)
                     "base/pending" (flag (seq (:effect-queue state)))
                     (str "base/phase/" (name (:phase state))) 1.0})
        base (merge base (into {} (for [[k v] (merge own opponent)] [(str k "/future") (* v future)])))
        hand (set (mapcat val (:hand p))) active (set (cards/active-owned p))
        owned (set (concat (cards/owned p) (:improvements p)))
        statuses (into (sorted-map) (concat (map #(vector % 0.25) hand)
                                            (map #(vector % (if (or (active %) ((set (:improvements p)) %)) 1.0 0.5)) owned)))
        context {"future" future "future2" (* future future)
                 "kitchen" (* future (get own "base/own/kitchen"))
                 "family" (* future (get own "base/own/family"))
                 "hunger" (* future (get own "base/own/food-deficit"))}
        individual
        (into {} (mapcat (fn [[cid status]]
                          (let [mode (if (hand cid) "hand" "played") prefix (str "card/" cid "/" mode "/")]
                            (concat (for [[k v] context] [(str prefix k) (* status v)])
                                    (when (hand cid)
                                      [[(str prefix "prerequisite") (* future (flag (g/prerequisites? state pid (cards/card cid))))]])))) statuses))
        available (vec statuses)
        pairs (into {} (for [i (range (count available)) j (range (inc i) (count available))
                              :let [[c x] (available i) [d y] (available j)]]
                         [(str "pair/" c "/" d) (* future x y)]))]
    (into {} (remove (fn [[_ v]] (zero? v)) (merge base individual pairs)))))

(defn extract [{:keys [recording output stride] :or {stride 2}}]
  (a/require! (and (integer? stride) (pos? stride)) "Sampling stride must be positive.")
  (let [records (sim/read-records recording) header (first records)
        _ (a/require! (= (:rules-hash header) (sim/rules-hash)) "Recording rules differ.")
        {:keys [status frames moves result]} (recording/timeline records)
        _ (a/require! (= :complete status) "Only complete verified games are training data.")
        _ (a/require! (= 2 (count (:players (first frames)))) "Value experiment supports two players.")
        scores (mapv :total (:scores result))]
    (io/make-parents output)
    (with-open [writer (io/writer output)]
      (let [n (reduce (fn [n [state move]]
                        (let [pid (:player move)]
                          (if-let [after (when (zero? (mod (:index move) stride))
                                          (bot/preview (bot/observation state pid) pid (:command move)))]
                            (do (.write writer (str (json/write-str
                                                    {:seed (:seed header) :move (:index move) :seat pid
                                                     :round (:round state) :target (scores pid)
                                                     :utility (bot/utility after pid) :features (features after pid)}) "\n"))
                                (inc n)) n))) 0 (map vector frames moves))]
        {:seed (:seed header) :rows n :moves (count moves) :replay "complete" :output output}))))

(defn predict [model values]
  (+ (double (get model "intercept" 0.0))
     (reduce-kv (fn [total k value] (+ total (* (double value) (double (get (get model "weights") k 0.0)))))
                0.0 values)))

(defn choose [state pid ^java.util.Random rng model margin blend]
  (let [state (bot/observation state pid) base (bot/utility state pid)
        legal (vec (keep (fn [command]
                           (when-let [after (bot/preview state pid command)]
                             (let [delta (- (bot/utility after pid) base)
                                   ability? (= :ability (:type command))]
                               (when (or (not ability?) (> delta 0.15))
                                 {:command command :after after
                                  :value (- delta (if ability? 0.15 0.0))
                                  :jitter (* 0.025 (.nextDouble rng))}))))
                         (distinct (bot/candidates state pid))))
        _ (a/require! (seq legal) "No legal value-model candidate.")
        best (apply max (map :value legal))
        scored (for [{:keys [after value jitter] :as candidate} legal
                     :when (>= value (- best margin))
                     :let [prediction (predict model (features after pid))
                           residual (- prediction (bot/utility after pid))]]
                 (assoc candidate :prediction prediction :rank (+ value jitter (* blend residual))))]
    (dissoc (reduce #(if (> (:rank %2) (:rank %1)) %2 %1) scored) :after :jitter)))

(defn run-game [{:keys [seed focal model output margin blend] :or {margin 1.0 blend 0.25}}]
  (a/require! (and (integer? seed) (#{0 1} focal)) "Invalid deal or seat.")
  (a/require! (not (.exists (io/file output))) "Recording already exists.")
  (let [learned (when model (json/read-str (slurp model))) original sim/choose
        _ (when learned
            (a/require! (= (get learned "rules") (sim/rules-hash)) "Value model rules differ.")
            (a/require! (= (get learned "source") (source-hash))
                        "Value model feature/policy source differs."))]
    (io/make-parents output)
    (let [result (with-open [writer (io/writer output)]
                   (with-redefs [sim/choose (fn [state pid rng policy]
                                             (if (and learned (= pid focal))
                                               (choose state pid rng learned margin blend)
                                               (original state pid rng policy)))]
                     (sim/run-game {:seed seed :players 2 :mode :random :policy :heuristic
                                    :study {:kind :afterstate-value :model model :focal focal :margin margin :blend blend}}
                                   #(.write writer (str (pr-str %) "\n")))))
          verified (sim/replay output)]
      (a/require! (= :complete (:status verified)) "Value experiment replay failed.")
      {:seed seed :model_seat focal :scores (mapv :total (:scores result)) :moves (:moves result)
       :replay "complete" :recording output :winners (:winners result) :begging (:begging result)})))

(defn request [{:keys [op] :as message}]
  (case op "extract" (extract message) "game" (run-game message)
        "predict" (predict (json/read-str (slurp (:model message))) (:features message))
        "schema" {:version version :rules (sim/rules-hash)
                  :source (source-hash)}))
(defn -main [& _]
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (try (println (json/write-str (request (json/read-str line :key-fn #(if (#{"op" "recording" "output" "stride" "seed" "focal" "model" "margin" "blend" "features"} %) (keyword %) %)))))
         (catch Exception e (binding [*out* *err*] (.printStackTrace e))
           (println (json/write-str {:error (.getMessage e)}))))
    (flush)))
