;; Read-only audit of completed synthetic games. Each recording is replayed again.
(require '[agricola.simulation :as sim] '[agricola.recording :as rec]
         '[agricola.impact-review :as review] '[agricola.card-rules :as cards]
         '[clojure.data.json :as json])
(let [directory (first *command-line-args*)
      manifest (json/read-str (slurp (str directory "/manifest.json")) :key-fn keyword)]
  (doseq [game (:games manifest) :when (= "complete" (:status game))]
    (let [{:keys [frames moves] :as timeline} (rec/timeline (sim/read-records (str directory "/" (:file game))))
          zeros (for [row (review/summarize timeline)
                      :when (and (:zero-impact row) (not= "unmeasured" (:reason row)))
                      :let [pid (:player row) cid (read-string (:id row))
                            after (filter #(and (= pid (:player %)) (> (:index %) (:first-move row))) moves)
                            effects (for [m moves e (:effects m) :when (= cid (:card e))]
                                      {:move (:index m) :round (:round m) :effect (:effect e) :choice (:choice e)})]]
                  (assoc row :text (:text (cards/card cid))
                         :at-play (select-keys (get-in frames [(inc (:first-move row)) :players pid]) [:improvements :goods :family :card-state])
                         :commands-after-play (mapv #(select-keys % [:index :round :command]) after)
                         :trace (vec effects)))
          ;; A fixed :gain trace must have a corresponding measured receipt.
          ;; Compare per move/card/player, allowing additional dynamic gains.
          expected (for [m moves
                         [[pid cid] traces] (group-by (juxt :player :card)
                           (filter #(and (:card %) (= :gain (get-in % [:effect :op]))
                                         (not (get-in % [:choice :skip?]))
                                         (map? (get-in % [:effect :goods]))
                                         (every? number? (vals (get-in % [:effect :goods])))) (:effects m)))
                         :let [goods (apply merge-with + (map #(get-in % [:effect :goods]) traces))
                               actual (apply merge-with + {} (for [event (:impacts m) :when (and (= pid (:player event)) (= cid (:card event)))
                                                                   change (:changes event) :when (= pid (:player change))] (:gained change)))]
                         :when (some (fn [[r n]] (> n (get actual r 0))) goods)]
                     {:move (:index m) :player pid :card cid :expected goods :actual actual})]
      (println (json/write-str {:seed (:seed game) :fixed-gain-mismatches (vec expected) :zero-cases (vec zeros)}))
      (flush))))
(shutdown-agents)
