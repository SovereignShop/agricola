(require '[eurozone.store :as store] '[agricola.engine :as a] '[clojure.string :as str])
(let [service (store/make-store (System/getenv "EUROZONE_DATA"))
      token (apply str (repeat 64 "a")) names ["Ada" "Ben"]
      state (-> (a/new-game names (vec (mapcat identity a/stages)) 0)
                a/begin-round
                (update-in [:players 0] a/gain {:clay 3 :stone 1 :grain 1}))
      ids (mapv (fn [label]
                  (let [created (store/transact! service token {:event :create :mode :local :name "Ada" :names names :title label})
                        room (get @(:rooms service) (:id created))
                        room (assoc room :state state :recording [(store/recording-header room state true)])]
                    (store/save! service (swap! (:rooms service) assoc (:id room) room))
                    (:id room))) ["Beginner oven · bake" "Beginner oven · decline"])]
  (println (str/join "," ids)))
