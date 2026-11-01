(require '[clojure.string :as str] '[clojure.java.io :as io] '[clojure.data.json :as json]
         '[agricola.simulation :as sim] '[agricola.draw :as draw] '[eurozone.store :as store])
(let [ids (str/split (str/trim (with-out-str (load-file "test/eurozone/tabletop_browser_fixture.clj"))) #",")
      state (sim/initial-state {:seed 201 :players 2 :mode :draw :extra 2})
      [room picks] (loop [room {:state state :history []} picks []]
                     (let [s (:state room)]
                       (if (not= :draw (:phase s)) [room picks]
                         (let [pid (first (sort (get-in s [:draw :pending]))) cid (first (draw/legal-cards (:draw s) pid))]
                           (recur (store/recorded-command room pid {:type :pick :wave (get-in s [:draw :wave]) :card cid})
                                  (conj picks {:picked (str cid) :pool (mapv str (get-in s [:draw :packets pid]))
                                               :legal (mapv str (draw/legal-cards (:draw s) pid))}))))))
      path (str (System/getenv "EUROZONE_DATA") ".draft.edn")]
  (spit path (str/join "\n" (map pr-str (:recording room))))
  (println (json/write-str {:table (first ids) :optional (nth ids 2) :recording path :picks picks})))
