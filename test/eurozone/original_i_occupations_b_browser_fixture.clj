(require '[eurozone.store :as store] '[agricola.engine :as a]
         '[agricola.card-game :as g] '[clojure.string :as str])
(let [store (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      names ["Ada" "Ben" "Cy" "Dee"]
      pig :catalog/original-base-i-253-pig-catcher fence :catalog/original-base-i-263-fence-builder
      cabinet :catalog/original-base-i-258-cabinetmaker taster :catalog/original-base-i-260-taster
      base (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0))
      pig-game (-> base (update-in [:players 0 :occupations] into [pig :catalog/original-base-e-152-berry-picker])
                        (assoc-in [:piles :wood :wood] 2) (assoc-in [:players 0 :goods :food] 2))
      fence-game (-> base (assoc-in [:players 0 :hand :occupation] [fence])
                          (assoc-in [:players 0 :goods :wood] 4)
                          (g/play-card 0 fence 0 {}) g/drain)
      craft-game (-> base (update-in [:players 0 :occupations] conj cabinet)
                          (update-in [:players 0 :minors] conj :catalog/original-base-e-26-plane)
                          (assoc-in [:players 0 :goods :wood] 2) (assoc-in [:players 0 :goods :food] 0)
                          (assoc :phase :feeding :fed #{} :turn 0)
                          (g/queue-front [(g/event :feeding-start 0)]) g/drain)
      taste-game (-> base (update-in [:players 3 :occupations] conj taster)
                          (assoc-in [:players 3 :goods :food] 5) g/begin-round g/drain)
      ids (mapv (fn [[title state]]
                  (let [id (:id (store/transact! store token {:event :create :mode :local :name "Ada" :names names :title title :setup {:mode :random}}))]
                    (store/save! store (swap! (:rooms store) assoc-in [id :state] state)) id))
                [["Pig Catcher" pig-game] ["Fence Builder" fence-game] ["Cabinetmaker and Plane" craft-game] ["Taster" taste-game]])]
  (println (str/join "," ids)))
