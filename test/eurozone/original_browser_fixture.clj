(require '[eurozone.store :as store] '[agricola.engine :as a]
         '[agricola.card-game :as g] '[agricola.card-rules :as c]
         '[agricola.simulation :as simulation] '[clojure.string :as str])
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      names ["Ada" "Ben"]
      base (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0))
      work (assoc-in base [:players 0 :hand] {:occupation [] :minor [:catalog/original-base-e-11-field]})
      optional (-> base (update-in [:players 0 :occupations] conj :catalog/original-base-e-154-master-brewer)
                       (assoc-in [:players 0 :goods :grain] 1)
                       (g/queue-front [(g/event :feeding-start 0)]) g/drain)
      draw (with-redefs [c/cards (into {} (filter (fn [[_ card]] (and (= "Original" (:edition card)) (= "E" (:deck card)))) c/cards))]
             (simulation/initial-state {:seed 914 :players 2 :mode :draw :extra 2}))
      ids (mapv (fn [[title state]]
                  (let [id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names names :title title :setup {:mode :random}}))]
                    (store/save! s (swap! (:rooms s) assoc-in [id :state] state)) id))
                [["Original E table" work] ["Original E optional" optional] ["Original E draw" draw]])]
  (println (str/join "," ids)))
