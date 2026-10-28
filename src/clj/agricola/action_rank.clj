(ns agricola.action-rank
  "Experimental paired action preferences; isolated sequential research workers."
  (:require [clojure.data.json :as json] [clojure.java.io :as io] [clojure.string :as str]
            [agricola.bot :as bot] [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.afterstate-value :as value] [agricola.growth-learning :as growth]
            [agricola.simulation :as sim] [agricola.recording :as rec]))

(defn source-hash []
  (rec/sha (str (growth/source-hash) (slurp (io/resource "agricola/action_rank.clj")))))
(defn features [state pid]
  (let [state (bot/observation state pid) p (g/p state pid)
        rates (a/cook-rates p) next-harvest (first (filter #(>= % (:round state)) a/harvest-rounds))
        crops (g/fields p)]
    (merge (into {} (remove #(str/starts-with? (key %) "pair/") (value/features state pid)))
           (growth/features state pid)
           {"rank/harvest-distance" (/ (- (or next-harvest 14) (:round state)) 4.0)
            "rank/breeding-food" (/ (reduce + (for [animal a/animals :when (>= (a/amount p animal) 2)]
                                                (get rates animal 0))) 10.0)
            "rank/crop-stock" (/ (reduce + (map #(:count % 0) crops)) 12.0)
            "rank/food-per-person" (/ (a/amount p :food) (* 2.0 (:family p)))
            "rank/room-deficit" (/ (growth/room-gap p) 7.0)})))

(defn category [state after pid command]
  (or (growth/focus-kind state after pid)
      (let [p (g/p state pid) q (g/p after pid)]
        (cond (or (> (get (a/cook-rates q) :sheep 0) (get (a/cook-rates p) :sheep 0))
                  (> (a/amount q :food) (a/amount p :food))
                  (> (count (filter #(pos? (:count % 0)) (g/fields q)))
                     (count (filter #(pos? (:count % 0)) (g/fields p))))) :food
              (or (> (count (:occupations q)) (count (:occupations p)))
                  (> (count (:minors q)) (count (:minors p)))
                  (:card command) (:improvement command)) :card
              :else :other))))
(defn choices [state pid margin]
  (mapv #(assoc % :features (features (:after %) pid)
                 :category (category state (:after %) pid (:command %)))
        (growth/candidates state pid margin true)))
(defn dot [a b] (reduce + 0.0 (map * a b)))
(defn network [member x]
  (+ (dot (get member "linear") x)
     (dot (get member "out")
          (map (fn [w b] (Math/tanh (+ b (dot w x)))) (get member "hidden") (get member "bias")))))
(defn predict [model f]
  (let [x (mapv #(double (get f % 0.0)) (get model "names"))]
    (mapv #(network % x) (get model "members"))))
(defn choose [state pid model margin strength]
  (if-not (growth/eligible? state) (growth/parent-choice state pid)
   (if (or (nil? model) (zero? strength))
    (if (= margin 1.0) (growth/parent-choice state pid)
      (dissoc (growth/best-parent (choices state pid margin)) :after :features))
    (let [cs (choices state pid margin) reference (growth/best-parent cs)
          center (predict model (:features reference))
          scored (map (fn [c]
                        (let [ds (map - (predict model (:features c)) center)
                              correction (/ (reduce + ds) (count ds))]
                          (assoc c :rank (+ (:parent-score c) (:jitter c)
                                           (* strength (max -4.0 (min 4.0 correction))))))) cs)]
      (dissoc (apply max-key :rank (reverse scored)) :after :features)))))

(defn sample [{:keys [recording output case]}]
  (let [timeline (rec/timeline (sim/read-records recording))
        _ (a/require! (= :complete (:status timeline)) "Source replay incomplete.")
        _ (a/require! (= (sim/rules-hash) (get-in timeline [:header :rules-hash])) "Source rules differ.")
        pid (mod case 2) seed (get-in timeline [:header :seed])
        scores (mapv :total (get-in timeline [:result :scores]))
        targets [[:food (+ 2 (mod case 3))] [:prepare (+ 3 (mod case 3))]
                 [:room (+ 4 (mod case 3))] [:grow (+ 5 (mod case 3))]]
        positions (filter (fn [[state move]] (and (= pid (:player move)) (growth/eligible? state)
                                                   (= :action (get-in move [:command :type]))))
                          (map vector (:frames timeline) (:moves timeline)))
        used (atom #{})]
    {:case case :seed seed :source recording
     :pairs
     (vec (keep
       (fn [[kind start]]
         (when-let [{:keys [state move parent alternative]}
                    (some (fn [[s m]]
                            (when (and (>= (:round s) start) (not (@used (:index m))))
                              (let [cs (choices s pid 5.0)
                                    parent (some #(when (= (:command %) (:command m)) %) cs)
                                    matching (filter #(and (= :action (get-in % [:command :type]))
                                                           (not= (:command %) (:command m))
                                                           (if (= kind (:category parent))
                                                             (not= kind (:category %)) (= kind (:category %)))) cs)]
                                (when (and parent (seq matching))
                                  (a/require! (= (:command m) (:command (growth/parent-choice s pid)))
                                              "Source was not generated by current parent.")
                                  {:state s :move m :parent parent :alternative (growth/best-parent matching)})))) positions)]
           (swap! used conj (:index move))
           (let [first? (atom true)
                 result (growth/recorded-game
                          {:seed seed :players 2 :mode :random :extra 2 :policy :heuristic
                           :study {:kind :action-rank :case case :category kind :source recording :source-move (:index move)}}
                          (str output "/" (name kind) ".edn")
                          (fn [s actor] (if (compare-and-set! first? true false)
                                          (do (a/require! (= actor pid) "Wrong branch actor.") alternative)
                                          (growth/parent-choice s actor))) state)
                 delta (mapv - (:scores result) scores)]
             {:category kind :round (:round state) :focal pid :position_hash (rec/state-hash state)
              :source_move (:index move) :baseline_scores scores :result result
              :a (select-keys alternative [:features :parent-score :command])
              :b (select-keys parent [:features :parent-score :command])
              :score_difference (delta pid) :margin_difference (- (delta pid) (delta (- 1 pid)))}))) targets))}))

(defn run-game [{:keys [seed focal output mode model margin strength] :or {margin 1.0 strength 0.0}}]
  (let [model (when model (json/read-str (slurp model)))
        _ (when model (a/require! (and (= (source-hash) (get model "source"))
                                       (= (sim/rules-hash) (get model "rules"))) "Rank model fingerprint differs."))]
    (assoc (growth/recorded-game
             {:seed seed :players 2 :mode (keyword (or mode "random")) :extra 2 :policy :heuristic
              :study {:kind :action-rank-evaluation :focal focal :margin margin :strength strength
                      :model-hash (when model (rec/sha (pr-str model)))}}
             output (fn [state pid] (if (= pid focal) (choose state pid model margin strength)
                                       (growth/parent-choice state pid))) nil) :model_seat focal)))
(defn -main [& _]
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (try (let [request (json/read-str line :key-fn keyword)]
           (println (json/write-str (case (:op request)
             "sample" (sample request) "game" (run-game request)
             "predict" (predict (json/read-str (slurp (:model request))) (json/read-str (:features_json request)))
             "schema" {:rules (sim/rules-hash) :source (source-hash)}))))
         (catch Exception e (binding [*out* *err*] (.printStackTrace e))
           (println (json/write-str {:error (.getMessage e)}))))
    (flush)))
