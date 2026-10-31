(require '[eurozone.store :as store] '[agricola.engine :as a]
         '[agricola.card-game :as g] '[agricola.card-rules :as c]
         '[agricola.simulation :as simulation] '[clojure.string :as str])
(let [store (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      names ["Ada" "Ben"] copse :catalog/original-base-i-78-copse
      base (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0))
      work (-> base (update :active conj :sow-bake)
               (update-in [:players 0 :occupations] conj :catalog/original-base-i-219-fieldsman)
               (assoc-in [:players 0 :hand :minor] [copse])
               (assoc-in [:players 0 :goods :wood] 6)
               (g/play-card 0 copse 0 {}) g/drain)
      popup (g/drain (g/queue-front work [(g/task 0 copse {:op :sow :optional? true} {})]))
      draw (with-redefs [c/cards (into {} (filter (fn [[_ card]] (and (= "Original" (:edition card)) (= "I" (:deck card)))) c/cards))]
             (simulation/initial-state {:seed 915 :players 2 :mode :draw :extra 2}))
      ids (mapv (fn [[title state]]
                  (let [id (:id (store/transact! store token {:event :create :mode :local :name "Ada" :names names :title title :setup {:mode :random}}))]
                    (store/save! store (swap! (:rooms store) assoc-in [id :state] state)) id))
                [["I Copse board" work] ["I Copse popup" popup] ["I draw" draw]])]
  (println (str/join "," ids)))
