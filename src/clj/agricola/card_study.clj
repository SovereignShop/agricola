(ns agricola.card-study
  "Isolated JSON-lines worker for randomized deals and controlled 2×2 hand studies.
   Uses production rules and the existing heuristic; never changes the live AI."
  (:require [clojure.data.json :as json] [clojure.java.io :as io] [clojure.string :as str]
            [agricola.simulation :as sim] [agricola.card-rules :as cards]
            [agricola.engine :as engine] [agricola.ai :as ai])
  (:import [java.util Random]))

(defn catalog [players]
  (vec (sort-by :id (filter #(<= (:min-players %) players) (vals cards/cards)))))
(defn hand [player] (vec (concat (get-in player [:hand :occupation]) (get-in player [:hand :minor]))))
(defn factorial
  "Four valid deals differing only in two cards in one hand. Controls are random
   same-kind replacements, absent from the background/opponent in every cell."
  [seed players pair]
  (let [[a b] (mapv #(keyword (str/replace % #"^:" "")) pair) eligible (catalog players)
        ids (set (map :id eligible))
        _ (engine/require! (and (= 2 (count pair)) (not= a b) (ids a) (ids b)) "Choose two distinct eligible hand cards.")
        rng (Random. (bit-xor (long seed) 182673))
        shuffled (sim/shuffle-with rng eligible)
        c (:id (first (filter #(and (= (:kind (cards/card a)) (:kind %)) (not (#{a b} (:id %)))) shuffled)))
        d (:id (first (filter #(and (= (:kind (cards/card b)) (:kind %)) (not (#{a b c} (:id %)))) shuffled)))
        focal (mod seed players) excluded #{a b c d}
        base (sim/initial-state {:seed seed :players players :mode :random})
        common (reduce
                (fn [state kind]
                  (let [pool (mapv :id (filter #(and (= kind (:kind %)) (not (excluded (:id %)))) shuffled))]
                    (first (reduce (fn [[state offset] pid]
                                     (let [n (- 7 (if (= pid focal) (count (filter #(= kind (:kind (cards/card %))) [a b])) 0))]
                                       [(assoc-in state [:players pid :hand kind] (subvec pool offset (+ offset n))) (+ offset n)]))
                                   [state 0] (range players))))) base [:occupation :minor])]
    {:focal focal :pair [a b] :controls [c d]
     :states (into {} (for [[label chosen] [["ab" [c d]] ["Ab" [a d]] ["aB" [c b]] ["AB" [a b]]]]
                       [label (reduce (fn [state cid] (update-in state [:players focal :hand (:kind (cards/card cid))] conj cid)) common chosen)]))}))

(defn recorded-game [initial seed file study]
  (engine/require! (not (.exists (io/file file))) "Study recording already exists.")
  (io/make-parents file)
  (let [result (with-open [writer (io/writer file)]
                 ;; This worker handles one request at a time in its own JVM.
                 (with-redefs [sim/initial-state (constantly initial)]
                   (sim/run-game {:seed seed :players (count (:players initial)) :mode :random :policy :heuristic :study study}
                     #(do (.write writer (str (pr-str %) "\n")) (.flush writer)))))
        verified (sim/replay file)]
    (engine/require! (= :complete (:status verified)) "Card study replay failed.")
    {:seed seed :scores (mapv :total (:scores result)) :moves (:moves result)
     :hands (mapv #(mapv str (hand %)) (:players initial)) :starting-player (:starting-player initial)
     :played (mapv str (get-in result [:coverage :played])) :recording file :replay "complete"}))

(defn request [{:keys [op seed players pair output] :or {players 2}}]
  (case op
    "catalog" {:cards (mapv #(-> (select-keys % [:id :name :kind :min-players]) (update :id str)) (catalog players))
               :fingerprints @ai/fingerprints}
    "random" (recorded-game (sim/initial-state {:seed seed :players players :mode :random}) seed output {:kind :random-deal})
    "factorial"
    (let [{:keys [focal pair controls states]} (factorial seed players pair)]
      {:seed seed :focal focal :pair (mapv str pair) :controls (mapv str controls)
       :cells (into {} (for [label ["ab" "Ab" "aB" "AB"]]
                         [label (recorded-game (get states label) seed (str output "-" label ".edn")
                                              {:kind :factorial :pair pair :controls controls :cell label :focal focal})]))})
    (throw (ex-info "Unknown card study operation" {}))))
(defn -main [& _]
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (try (println (json/write-str (request (json/read-str line :key-fn keyword))))
         (catch Exception e (println (json/write-str {:error (.getMessage e)}))))
    (flush)))
