;; Test driver for optimized bundles: exchange EDN through public persistence and
;; transport boundaries instead of relying on Closure's private symbol names.
(require '[clojure.data.json :as json] '[clojure.edn :as edn]
         '[eurozone.bot-player :as bot] '[agricola.card-game :as game]
         '[agricola.recording :as recording])
(doseq [line (line-seq (java.io.BufferedReader. *in*))]
  (try
    (let [{:keys [op text choose]} (json/read-str line :key-fn keyword)
          value (edn/read-string text)
          result (case op
                   "decode" value
                   "state-hash" (recording/state-hash (get-in value [:table :state]))
                   "status"
                   (let [room (:table value) state (:state room) automated (bot/actor room)
                         pid (if (= :draw (:phase state))
                               (first (sort (get-in state [:draw :pending]))) (game/acting-player state))
                         command (when (and choose (nil? automated) (some? pid) (not= :finished (:phase state)))
                                   (bot/choose (bot/observation state pid) pid))
                         message (when command (merge {:event :command :command command :id (:id room)
                                                       :revision (:revision room) :request-id (str (random-uuid))}
                                                      (when (= :draw (:phase state))
                                                        {:event :draw-pick :player pid :wave (:wave command) :card (:card command)})))]
                     {:phase (:phase state) :round (:round state) :revision (:revision room) :bot automated :player pid
                      :message (when message (pr-str {:rules (:rules value) :message message}))}))]
      (println (json/write-str result)) (flush))
    (catch Exception e (println (json/write-str {:bridge-error (.getMessage e)})) (flush))))
