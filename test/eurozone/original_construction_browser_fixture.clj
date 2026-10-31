(require '[eurozone.store :as store] '[agricola.engine :as a]
         '[agricola.card-game :as g] '[clojure.string :as str])
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      names ["Ada" "Ben"]
      riding :catalog/original-base-e-61-riding-plow
      turnwrest :catalog/original-base-e-62-turnwrest-plow
      maker :catalog/original-base-e-195-plow-maker
      base (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0))
      plow (-> base (update-in [:players 0 :minors] into [riding turnwrest])
                   (update-in [:players 0 :occupations] conj maker)
                   (assoc-in [:players 0 :card-state riding :uses] 2)
                   (assoc-in [:players 0 :card-state turnwrest :uses] 1)
                   (g/command 0 {:type :action :space :plow :cell 0}))
      build (-> base (update-in [:players 0 :minors] into [:catalog/original-base-e-36-clay-roof :catalog/original-base-e-37-clay-supports])
                    (update-in [:players 0 :occupations] conj :catalog/original-base-e-218-carpenter)
                    (assoc-in [:players 0 :house] :clay)
                    (assoc-in [:players 0 :goods] {:wood 1 :clay 5 :reed 3 :food 2})
                    (g/queue-front [(g/task 0 nil {:op :build-rooms :optional? true} {})]) g/drain)
      ids (mapv (fn [[title state]]
                  (let [id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names names :title title :setup {:mode :random}}))]
                    (store/save! s (swap! (:rooms s) assoc-in [id :state] state)) id))
                [["Original plows" plow] ["Per-room payments" build]])]
  (println (str/join "," ids)))
