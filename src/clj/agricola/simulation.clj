(ns agricola.simulation
  "Seeded self-play and append-only EDN recordings. Each accepted command goes
   through the production engine. A partial file remains replayable after failure."
  (:require [agricola.bot :as bot] [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [agricola.draw :as draw] [agricola.recording :as recording]
            [clojure.edn :as edn] [clojure.java.io :as io] [clojure.string :as str])
  (:import [java.util Random ArrayList Collections]
           [java.security MessageDigest] [java.io PushbackReader]))

(def canonical recording/canonical)
(def sha recording/sha)
(def state-hash recording/state-hash)
(def rule-files ["agricola/board.edn" "agricola/cards.edn" "agricola/five-player-cards.edn" "agricola/expansion-cards.edn" "agricola/original-e-construction.edn" "agricola/original-e-improvement-permissions.edn" "agricola/original-e-animals.edn" "agricola/original-e-timing.edn" "agricola/original-e-plows.edn" "agricola/original-e-animal-economy.edn" "agricola/original-e-improvement-followups.edn" "agricola/original-e-action-occupations.edn" "agricola/original-e-resource-actions.edn" "agricola/original-e-conversions.edn" "agricola/original-i-minors.edn" "agricola/original-i-occupations-a.edn" "agricola/original-i-occupations-b.edn" "agricola/original-i-crops-rounds.edn" "agricola/original-cards.edn" "agricola/original-e-minors-extra.edn" "agricola/original-e-occupations-extra.edn" "agricola/five_player.cljc" "agricola/engine.cljc" "agricola/card_game.cljc" "agricola/card_reactive.cljc" "agricola/card_random.cljc" "agricola/card_rules.cljc" "agricola/card_expressions.cljc" "agricola/card_catalog.cljc" "agricola/draw.cljc"])
(defn rules-hash [] (sha (apply str (map #(slurp (io/resource %)) rule-files))))
(defn shuffle-with [^Random rng xs]
  (let [copy (ArrayList. (vec xs))] (Collections/shuffle copy rng) (vec copy)))
(defn initial-state [{:keys [seed players mode extra] :or {seed 1 players 2 mode :random extra 2}}]
  (let [rng (Random. (long seed))
        order (vec (mapcat #(shuffle-with rng %) a/stages))
        state (assoc (a/new-game (mapv #(str "Bot " (inc %)) (range players)) order (.nextInt rng players))
                     :card-rng (inc (mod (long seed) 2147483646)))
        pool (filter #(<= (:min-players %) players) (sort-by :id (vals c/cards)))
        by-kind (into {} (for [kind draw/kinds] [kind (shuffle-with rng (filter #(= kind (:kind %)) pool))]))
        state (cond-> state (= players 5) (assoc :minor-order (mapv :id (by-kind :minor))))]
    (case mode
      :beginner state
      :random (g/initialize
               (update state :players
                       #(mapv (fn [p] (assoc p :hand (into {} (for [kind draw/kinds]
                                                               [kind (mapv :id (take 7 (drop (* 7 (:id p)) (by-kind kind))))])))) %)))
      :draw (let [required (mapcat #(take (* 7 players) (by-kind %)) draw/kinds)
                  remaining (shuffle-with rng (mapcat #(drop (* 7 players) (by-kind %)) draw/kinds))
                  cards (shuffle-with rng (concat required (take (* extra players) remaining)))]
              (assoc state :phase :draw :round 0 :variant :cards :active [] :piles {} :log []
                           :draw (draw/new-draw players extra cards)))
      (throw (ex-info "Simulation mode must be beginner, random or draw." {:mode mode})))))
(def apply-command recording/apply-command)
(defn actor [state]
  (if (= :draw (:phase state)) (first (sort (get-in state [:draw :pending]))) (g/acting-player state)))
(defn draw-value [cid]
  (let [card (c/card cid)]
    (+ (bot/future-card-value cid 10) (:points card 0)
       (* 0.4 (count (:play card)))
       (- (* 0.35 (reduce + (vals (:printed-cost card)))))
       (if (str/blank? (:prerequisites card)) 0 -0.75))))
(defn choose [state pid rng policy]
  (if (= :draw (:phase state))
    (let [cards (draw/legal-cards (:draw state) pid)
          cid (if (= policy :random) (nth cards (.nextInt ^Random rng (count cards)))
                  (last (sort-by (juxt draw-value str) cards)))]
      {:command {:type :pick :wave (get-in state [:draw :wave]) :card cid} :value (draw-value cid)})
    (bot/choose state pid rng policy)))
(defn assert-invariants! [state]
  (a/require! (<= 0 (:round state) 14) "Invalid round in simulation.")
  (doseq [p (:players state)]
    (a/require! (every? a/natural? (vals (:goods p))) "Negative/nonintegral goods in simulation.")
    (a/require! (<= 2 (:family p) 5) "Invalid family size in simulation.")
    (a/require! (<= 0 (:remaining p) (+ (:family p) (:guests p 0))) "Invalid remaining worker count.")
    (a/require! (= 15 (count (:farm p))) "Invalid farm size in simulation.")
    (a/require! (<= (count (:fences p)) 15) "Too many fences in simulation.")
    (when (not= :draw (:phase state))
      (let [cards (concat (get-in p [:hand :minor]) (get-in p [:hand :occupation]) (c/owned p))]
        (a/require! (= (count cards) (count (set cards))) "A player has a duplicate card.")))))
(defn coverage [initial events final]
  {:dealt (if (= :draw (:phase initial))
            (set (mapcat identity (get-in initial [:draw :packets])))
            (set (mapcat #(concat (get-in % [:hand :minor]) (get-in % [:hand :occupation])) (:players initial))))
   :drafted (set (keep #(when (= :pick (get-in % [:command :type])) (get-in % [:command :card])) events))
   :played (set (keep #(when (and (get-in % [:command :card]) (#{:action :resolve} (get-in % [:command :type]))
                             (not (get-in % [:command :skip?]))) (get-in % [:command :card])) events))
   :majors (set (keep #(get-in % [:command :improvement]) events))
   :spaces (frequencies (keep #(get-in % [:command :space]) events))
   :effects (frequencies (for [e events t (:effects e)] [(:card t) (get-in t [:effect :op]) (if (get-in t [:choice :skip?]) :skipped :applied)]))})
(defn run-game
  "emit receives the header, each move, and result/error as EDN-compatible maps.
   Callers may stream/flush them to disk without keeping full state snapshots."
  ([options] (run-game options (constantly nil)))
  ([{:keys [seed players policy policies max-moves] :or {seed 1 players 2 policy :heuristic max-moves 2000} :as options} emit]
   (let [initial (initial-state options) rng (Random. (bit-xor (long seed) 1597463007))
         policies (or policies (vec (repeat players policy)))
         _ (a/require! (and (= players (count policies)) (every? #{:random :heuristic} policies)) "Choose one policy per seat.")
         header {:record :header :format 1 :seed seed :options options :policies policies :bot-version bot/version :bot-hash (sha (slurp (io/resource "agricola/bot.clj")))
                 :rules-hash (rules-hash) :initial initial :hash (state-hash initial)}
         events (atom []) current (atom initial) attempted (atom nil)]
     (emit header)
     (try
       (loop [state initial i 0]
         (reset! current state)
         (assert-invariants! state)
         (if (= :finished (:phase state))
           (let [result {:record :result :seed seed :moves i :round (:round state) :hash (state-hash state)
                         :scores (:scores state) :winners (:winners state) :policies policies
                         :begging (mapv :begging (:players state))
                         :families (mapv :family (:players state)) :coverage (coverage initial @events state)}]
             (emit result) result)
           (do
             (a/require! (< i max-moves) "Simulation move limit reached (possible decision cycle).")
             (let [pid (actor state) _ (reset! attempted {:player pid})
                   choice (choose state pid rng (nth policies pid)) command (:command choice)
                   _ (reset! attempted {:player pid :command command})
                   effects (atom [])
                   next (binding [g/*effect-observer* #(swap! effects conj %)] (apply-command state pid command))
                   event {:record :move :index i :player pid :round (:round state) :phase (:phase state)
                          :command command :estimate (:value choice) :effects @effects :hash (state-hash next)}]
               (swap! events conj event) (emit event) (recur next (inc i))))))
       (catch Exception e
         (emit {:record :error :seed seed :moves (count @events) :attempt @attempted :state @current
                :message (.getMessage e) :data (ex-data e)})
         (throw (ex-info "Automated game failed; recording contains the failing position." {:seed seed :attempt @attempted} e)))))))
(defn read-records [file]
  (with-open [reader (PushbackReader. (io/reader file))]
    (loop [out []]
      (let [record (edn/read {:eof ::eof} reader)]
        (if (= ::eof record) out (recur (conj out record)))))))
(defn replay [file]
  (let [{:keys [status frames moves result failure header]} (recording/timeline (read-records file))]
    (doseq [frame frames] (assert-invariants! frame))
    (cond-> {:status status :moves (count moves) :same-rules? (= (:rules-hash header) (rules-hash))}
      result (assoc :scores (:scores result))
      failure (assoc :failure (dissoc failure :state))
      (= status :partial) (assoc :hash (state-hash (peek frames))))))
(defn summary [results]
  (let [scores (mapcat (fn [r] (map #(assoc %1 :policy %2) (:scores r) (:policies r))) results)]
    {:games (count results) :moves (reduce + (map :moves results))
     :by-policy (into {} (for [[policy rows] (group-by :policy scores)]
                           [policy {:seats (count rows) :mean-score (double (/ (reduce + (map :total rows)) (count rows)))
                                    :min-score (apply min (map :total rows)) :max-score (apply max (map :total rows))}]))
     :begging (reduce + (mapcat :begging results))
     :coverage (reduce (fn [out r]
                         (reduce-kv (fn [out k v]
                                      (update out k (if (set? v) (fnil into #{}) (fnil #(merge-with + %1 %2) {})) v)) out (:coverage r))) {} results)}))
(def help "Usage: clojure -M:simulate [--games 10] [--players 2] [--seed 1]\n  [--mode random|draw] [--extra 2] [--policy heuristic|random|mixed]\n  [--output data/simulations/run-name]\nReplay: clojure -M:simulate --replay path/game-1.edn\nFiles contain all hands for local debugging. Each output directory must be new.")
(defn parse-options [args]
  (loop [args args opts {:games 1 :players 2 :seed 1 :mode :random :extra 2 :policy :heuristic}]
    (if (empty? args) opts
        (let [[flag value & more] args k (get {"--games" :games "--players" :players "--seed" :seed "--mode" :mode
                                              "--extra" :extra "--policy" :policy "--output" :output "--replay" :replay} flag)]
          (a/require! (and k value) (str "Unknown or incomplete option: " flag))
          (recur more (assoc opts k (cond (#{:games :players :seed :extra} k) (Long/parseLong value)
                                         (#{:mode :policy} k) (keyword value) :else value)))))))
(defn -main [& args]
  (try
    (if (some #{"--help" "-h"} args) (println help)
      (let [{:keys [games players seed output replay policy] :as opts} (parse-options args)]
        (if replay (prn (agricola.simulation/replay replay))
          (let [_ (a/require! (and (<= 1 games) (<= 1 players 5) (#{:random :draw} (:mode opts))
                                  (<= 0 (:extra opts) 10) (#{:heuristic :random :mixed} policy)) "Invalid simulation options.")
                directory (io/file (or output (str "data/simulations/run-" (System/currentTimeMillis))))
                _ (a/require! (.mkdirs directory) "Output directory already exists or cannot be created.")
                results (mapv (fn [i]
                                (let [game-seed (+ seed i) file (io/file directory (str "game-" game-seed ".edn"))
                                      game-opts (cond-> (assoc opts :seed game-seed)
                                                  (= :mixed policy) (assoc :policies (mapv #(if (even? (+ % i)) :heuristic :random) (range players))))
                                      result (with-open [writer (io/writer file)]
                                               (run-game game-opts #(do (.write writer (str (pr-str %) "\n")) (.flush writer))))]
                                  (println "Recorded" (.getPath file) "scores" (mapv :total (:scores result))) (flush)
                                  result)) (range games))
                report (summary results)]
            (spit (io/file directory "summary.edn") (pr-str report))
            (prn (update report :coverage #(into {} (map (fn [[k v]] [k (count v)]) %))))))))
    (catch Exception e
      (binding [*out* *err*] (println (.getMessage e)) (prn (ex-data e))
               (when-let [cause (.getCause e)] (println (.getMessage cause)) (prn (ex-data cause))))
      (System/exit 1))))
