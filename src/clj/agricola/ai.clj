(ns agricola.ai
  "Local JSON-lines environment. The learner never receives authoritative state."
  (:require [clojure.data.json :as json] [clojure.java.io :as io]
            [agricola.action-space :as space] [agricola.simulation :as sim]
            [agricola.recording :as recording] [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.learning-rewards :as learning] [agricola.policy-guidance :as guidance]))
(def fingerprints
  (delay {:rules (sim/rules-hash)
          :schema (recording/state-hash space/schema)
          :actions (recording/sha (str (slurp (io/resource "agricola/action_space.clj"))
                                       (slurp (io/resource "agricola/bot.clj"))))}))
(defn header [state options]
  {:record :header :format 1 :options options :seed (:seed options) :action-space space/version
   :rules-hash (sim/rules-hash) :initial state :hash (recording/state-hash state)})
(defn response
  ([env expert?] (response env expert? false))
  ([{:keys [state actions moves replay-moves]} expert? guided?]
  (let [terminal? (= :finished (:phase state)) pid (when-not terminal? (sim/actor state))
        advice (when (and guided? (not terminal?)) (guidance/evaluate state actions))]
    (cond-> {:version space/version :fingerprints @fingerprints :round (:round state) :phase (:phase state)
             :player pid :players (count (:players state)) :terminal terminal? :ply moves
             :observation (if terminal? [] (vec (space/features (space/observation state pid))))
             :actions (mapv #(-> % (assoc :edn (pr-str (:command %))) (dissoc :command)) actions)
             :scores (when terminal? (mapv :total (:scores state)))
             :learning (learning/observation state)
             :reward (if terminal? (mapv #(/ (:total %) (get-in space/schema [:reward :scale])) (:scores state)) (vec (repeat (count (:players state)) 0.0)))}
      (and replay-moves (not terminal?))
      (assoc :recorded-action (let [id (space/action-id (:command (nth replay-moves moves)))]
                                (first (keep-indexed #(when (= id (:id %2)) %1) actions))))
      advice (assoc :guidance advice)
      (and expert? (not terminal?)) (assoc :expert (if advice (:expert advice) (space/expert-index state actions)))))))
(defn append! [writer record]
  (when writer (.write ^java.io.Writer writer (str (pr-str record) "\n")) (.flush ^java.io.Writer writer)))
(defn open-recording [path]
  (when path
    (let [file (io/file path)]
      (io/make-parents file)
      (a/require! (.createNewFile file) "Recording path already exists.")
      (io/writer file))))
(defn -main [& _]
  (let [env (atom nil)]
    (try
      (doseq [line (line-seq (java.io.BufferedReader. *in*))]
        (try
          (let [{:keys [op seed players mode action record expert guidance] :or {seed 1 players 1 mode "beginner"} :as request} (json/read-str line :key-fn keyword)
                result
                (case op
                  "schema" {:version space/version :schema space/schema :fingerprints @fingerprints
                            :learning {:version (:version learning/definition) :fingerprint learning/fingerprint :definition learning/definition}
                            :guidance {:version agricola.policy-guidance/version :fingerprint agricola.policy-guidance/fingerprint}}
                  "load-recording"
                  (let [{:keys [status header moves]} (recording/timeline (sim/read-records (:path request)))
                        _ (a/require! (= :complete status) "Only verified complete recordings can supply training examples.")
                        state (:initial header) actions (space/legal-actions state) writer (open-recording record)]
                    (when-let [old (:writer @env)] (.close ^java.io.Writer old))
                    (append! writer (assoc (agricola.ai/header state (:options header)) :reencoded-from (:path request)))
                    (reset! env {:state state :actions actions :moves 0 :writer writer :replay-moves moves})
                    (response @env false guidance))
                  "reset" (let [options {:seed seed :players players :mode (keyword mode)}
                                _ (a/require! (and (integer? players) (<= 1 players 4)) "Choose 1–4 players.")
                                state (sim/initial-state options) actions (space/legal-actions state)
                                writer (open-recording record)]
                            (when-let [old (:writer @env)] (.close ^java.io.Writer old))
                            (append! writer (header state options))
                            (reset! env {:state state :actions actions :moves 0 :writer writer})
                            (response @env expert guidance))
                  "step" (let [{:keys [state actions moves writer]} @env
                               _ (a/require! (and state (integer? action) (<= 0 action) (< action (count actions))) "Choose a legal action index from the current observation.")
                               command (:command (nth actions action)) pid (sim/actor state) effects (atom [])
                               _ (when-let [replay-moves (:replay-moves @env)]
                                   (a/require! (= (space/action-id command) (space/action-id (:command (nth replay-moves moves))))
                                               "Re-encoding must follow the verified recorded command."))
                               next (binding [g/*effect-observer* #(swap! effects conj %)] (recording/apply-command state pid command))
                               _ (sim/assert-invariants! next)
                               next-actions (space/legal-actions next)]
                           (a/require! (or (= :finished (:phase next)) (seq next-actions)) "No legal macro action; inspect this position.")
                           (append! writer {:record :move :index moves :player pid :round (:round state) :phase (:phase state)
                                            :command command :effects @effects :hash (recording/state-hash next)})
                           (when (= :finished (:phase next))
                             (append! writer {:record :result :moves (inc moves) :scores (:scores next) :winners (:winners next)
                                              :hash (recording/state-hash next)}))
                           (swap! env assoc :state next :actions next-actions :moves (inc moves))
                           (response @env expert guidance))
                  "replay" (sim/replay (:path request))
                  "observe" (response @env expert guidance)
                  (throw (ex-info "Unknown environment operation." {})))]
            (println (json/write-str result)) (flush))
          (catch Exception e
            (println (json/write-str {:error (.getMessage e)})) (flush))))
      (finally (when-let [writer (:writer @env)] (.close ^java.io.Writer writer))))))
