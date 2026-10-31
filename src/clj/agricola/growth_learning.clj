(ns agricola.growth-learning
  "Paired native continuations teach the return from growth and its preparation.
   Existing deployed policy and rules are frozen and remain unchanged."
  (:require [clojure.data.json :as json] [clojure.java.io :as io]
            [agricola.bot :as bot] [agricola.afterstate-value :as value]
            [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.simulation :as sim] [agricola.recording :as rec]
            [eurozone.bot-player :as runtime] [eurozone.play-values :as parent]))

(defn source-hash []
  (rec/sha (str (slurp (io/resource "agricola/growth_learning.clj")) (value/source-hash)
               (slurp (io/resource "eurozone/play-values.edn")))))
(defn room-gap [p]
  (apply min (for [options (a/room-payment-choices p)]
               (reduce + (for [[r cost] (a/room-cost p options)] (max 0 (- cost (a/amount p r))))))))
(defn features [state pid]
  (a/require! (= 2 (count (:players state))) "Growth learning supports two players.")
  (let [state (bot/observation state pid) p (g/p state pid) other (g/p state (- 1 pid))
        r (:round state) future (/ (- 14 r) 13.0) family (:family p)
        rooms (count (a/tiles p :room)) rates (a/cook-rates p)
        harvests (count (filter #(>= % r) a/harvest-rounds))
        kitchen (value/flag (pos? (get rates :sheep 0)))
        crops (reduce + (for [field (g/fields p) :when (pos? (:count field 0))] (get rates (:crop field) 1)))
        edible (+ (a/amount p :food) (reduce + (for [animal a/animals]
                                                 (* (get rates animal 0) (max 0 (- (a/amount p animal) 2))))))
        deficit (max 0 (- (a/food-needed state p) edible))
        gap (room-gap p) ready (value/flag (zero? gap))
        context {"time" future "time2" (* future future) "kitchen" (* future kitchen)
                 "crop-income" (* future (/ crops 8.0)) "food-deficit" (* future (/ deficit 10.0))
                 "food-stock" (* future (/ (a/amount p :food) 10.0))
                 "harvests" (/ harvests 6.0) "room-gap" (* future (/ gap 7.0))
                 "room-ready" (* future ready)}
        base {"family" (/ family 5.0) "rooms" (/ rooms 5.0)
              "newborn" (/ (:newborn p) 3.0) "remaining" (/ (:remaining p) 5.0)
              "spare-room" (value/flag (> rooms family)) "room-ready" ready
              "room-gap" (/ gap 7.0) "kitchen" kitchen "crop-income" (/ crops 8.0)
              "food" (/ (a/amount p :food) 10.0) "food-deficit" (/ deficit 10.0)
              "growth-visible" (value/flag (some #{:growth :urgent-growth} (:active state)))
              "growth-open" (value/flag (some #(and (some #{%} (:active state)) (nil? (get-in state [:occupied %]))) [:growth :urgent-growth]))
              "opponent-ready" (value/flag (> (count (a/tiles other :room)) (:family other)))
              "opponent-family" (/ (:family other) 5.0)
              "starting" (value/flag (= pid (:starting-player state)))}]
    (into {} (remove (fn [[_ v]] (zero? v)))
          (concat (for [[k v] base] [(str "growth/" k) (double v)])
                  (for [[k v] base] [(str "growth/" k "/time") (* future v)])
                  (for [[k v] context] [(str "growth/family-" family "/" k) v])
                  (for [[k v] context] [(str "growth/spare/" k) (* v (value/flag (> rooms family)))])
                  (for [resource [:wood :reed :clay :stone]]
                    [(str "growth/resources/" (name resource)) (* future (/ (min 7 (a/amount p resource)) 7.0))])))))

(defn focus-kind [before after pid]
  (let [p (g/p before pid) q (g/p after pid)]
    (cond (> (:family q) (:family p)) :grow
          (> (count (a/tiles q :room)) (count (a/tiles p :room))) :room
          (and (< (:family p) 5) (<= (count (a/tiles p :room)) (:family p))
               (< (room-gap q) (room-gap p))) :prepare)))
(defn parent-choice [state pid]
  (if (= :draw (:phase state)) {:command (runtime/choose (runtime/observation state pid) pid) :value 0.0}
      (value/choose state pid (java.util.Random. 42) parent/model 1.0 0.75)))
(defn eligible? [state]
  (and (= :work (:phase state)) (<= (:round state) 10) (empty? (:effect-queue state))))
(defn candidates [state pid margin & [training?]]
  (let [state (bot/observation state pid) rng (java.util.Random. 42)
        base (bot/utility state pid)
        legal (vec (keep (fn [command]
                           (when-let [after (bot/preview state pid command)]
                             (let [u (bot/utility after pid) ability? (= :ability (:type command))]
                               (when (or (not ability?) (> (- u base) 0.15))
                                 {:command command :after after :utility u
                                  :value (- u base (if ability? 0.15 0.0))
                                  :penalty (if ability? 0.15 0.0)
                                  :focus (when (= :action (:type command)) (focus-kind state after pid))
                                  :jitter (* 0.025 (.nextDouble rng))}))))
                         (distinct (bot/candidates state pid))))
        best (apply max (map :value legal))]
    (mapv (fn [{:keys [after utility penalty] :as candidate}]
            (let [prediction (value/predict parent/model (value/features after pid))]
              (assoc candidate :parent-score (- (+ (* 0.25 utility) (* 0.75 prediction)) penalty)
                     :features (features after pid))))
          (filter #(>= (:value %) (- best (if (or training? (:focus %)) margin 1.0))) legal))))
(defn best-parent [choices]
  (reduce #(if (> (+ (:parent-score %2) (:jitter %2)) (+ (:parent-score %1) (:jitter %1))) %2 %1) choices))
(defn choose [state pid model margin strength]
  (if-not (eligible? state) (parent-choice state pid)
    (let [choices (candidates state pid margin)
          reference (best-parent choices)
          correction #(if model (value/predict model (:features %)) 0.0)
          center (correction reference)
          ranked (map #(assoc % :rank (+ (:parent-score %) (:jitter %)
                                         (* strength (max -4.0 (min 4.0 (- (correction %) center)))))) choices)]
      (dissoc (reduce #(if (> (:rank %2) (:rank %1)) %2 %1) ranked) :after :features))))

(defn recorded-game [options output choose-fn initial]
  (a/require! (not (.exists (io/file output))) "Recording exists.")
  (io/make-parents output)
  (let [original sim/initial-state
        result (with-open [writer (io/writer output)]
                 (with-redefs [sim/choose (fn [state pid _ _] (choose-fn state pid))
                               sim/initial-state (if initial (constantly initial) original)]
                   (sim/run-game options #(.write writer (str (pr-str (cond-> %
                                                                                     (and initial (= :header (:record %))) (assoc :partial-start? true))) "\n")))))
        timeline (rec/timeline (sim/read-records output))]
    (a/require! (= :complete (:status timeline)) "Replay failed.")
    {:seed (:seed options) :scores (mapv :total (:scores result)) :moves (:moves result)
     :winners (:winners result) :begging (:begging result) :families (:families result)
     :first_growth (mapv (fn [pid] (or (some #(when (> (get-in % [:players pid :family]) 2) (:round %)) (:frames timeline)) 15)) [0 1])
     :recording output :replay "complete" :partial_start (boolean initial)}))

(defn paired-sample [{:keys [seed focal category target_round output mode]}]
  (let [category (keyword category) pivot (atom nil)
        options {:seed seed :players 2 :mode (keyword (or mode "random")) :extra 2 :policy :heuristic
                 :study {:kind :growth-paired-continuation :category category :focal focal}}
        source (recorded-game options (str output "/source.edn")
                              (fn [state pid]
                                (when (and (nil? @pivot) (= focal pid) (eligible? state) (>= (:round state) target_round))
                                  ;; Observe alternatives even when the old heuristic
                                  ;; strongly prefers growth; otherwise the data omit
                                  ;; exactly the positions where growth pays most.
                                  (let [choices (candidates state pid 8.0 true)
                                        positive (filter #(= category (:focus %)) choices)
                                        negative (filter #(not= category (:focus %)) choices)]
                                    (when (and (seq positive) (seq negative))
                                      (reset! pivot {:state state :a (best-parent positive) :b (best-parent negative)}))))
                                (parent-choice state pid)) nil)]
    (if-not @pivot {:seed seed :category category :skipped true :source source}
      (let [{:keys [state a b]} @pivot
            run (fn [name candidate]
                  (let [first? (atom true)]
                    (recorded-game options (str output "/" name ".edn")
                                   (fn [s pid] (if (compare-and-set! first? true false)
                                                 (do (a/require! (= pid focal) "Wrong paired actor.") candidate)
                                                 (parent-choice s pid))) state)))
            ra (run "investment" a) rb (run "alternative" b)]
        {:seed seed :focal focal :category category :round (:round state) :source source
         :position_hash (rec/state-hash state)
         :a {:features (:features a) :parent_score (:parent-score a) :command (:command a) :result ra}
         :b {:features (:features b) :parent_score (:parent-score b) :command (:command b) :result rb}
         :score_difference (- (get-in ra [:scores focal]) (get-in rb [:scores focal]))}))))

(defn run-game [{:keys [seed focal output mode model margin strength] :or {margin 1.0 strength 0.0}}]
  (let [model (when model (json/read-str (slurp model)))
        _ (when model
            (a/require! (= (sim/rules-hash) (get model "rules")) "Growth model rules differ.")
            (a/require! (= (source-hash) (get model "source")) "Growth source differs."))
        result (recorded-game {:seed seed :players 2 :mode (keyword (or mode "random")) :extra 2 :policy :heuristic
                               :study {:kind :growth-evaluation :focal focal :margin margin :strength strength}}
                              output (fn [state pid] (if (= pid focal) (choose state pid model margin strength)
                                                        (parent-choice state pid))) nil)]
    (assoc result :model_seat focal)))
(defn -main [& _]
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (try (let [request (json/read-str line :key-fn keyword)]
           (println (json/write-str (case (:op request)
                                     "sample" (paired-sample request) "game" (run-game request)
                                     "schema" {:rules (sim/rules-hash) :source (source-hash)
                                               :parent (rec/sha (slurp (io/resource "eurozone/play-values.edn")))}))))
         (catch Exception e (binding [*out* *err*] (.printStackTrace e))
           (println (json/write-str {:error (.getMessage e)}))))
    (flush)))
