;; Run on the experiment's frozen classpath. Both sides use the deployed draft
;; ranking; only post-draft play differs. Preserve honest :draw recording options.
(require '[agricola.afterstate-value :as value] '[agricola.simulation :as sim]
         '[eurozone.bot-player :as browser])
(def original-choice sim/choose)
(def original-value-choice value/choose)
(def original-run sim/run-game)
(defn draft-choice [state pid]
  {:command (browser/choose (browser/observation state pid) pid) :value 0.0})
(with-redefs [sim/run-game (fn [options emit] (original-run (assoc options :mode :draw :extra 2) emit))
              sim/choose (fn [state pid rng policy]
                           (if (= :draw (:phase state)) (draft-choice state pid)
                               (original-choice state pid rng policy)))
              value/choose (fn [state pid rng model margin blend]
                             (if (= :draw (:phase state)) (draft-choice state pid)
                                 (original-value-choice state pid rng model margin blend)))]
  (value/-main))
