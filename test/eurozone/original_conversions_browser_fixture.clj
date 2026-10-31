(require '[eurozone.store :as store] '[agricola.engine :as a]
         '[agricola.card-game :as g] '[clojure.string :as str])
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      names ["Ada" "Ben"] crock :catalog/original-base-e-19-gypsy-s-crock
      plane :catalog/original-base-e-26-plane brush :catalog/original-base-e-156-brush-maker
      tenant :catalog/original-base-e-215-tenant-farmer
      base (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0))
      loan (-> base (update-in [:players 0 :occupations] conj tenant)
                   (assoc-in [:players 0 :goods] (merge (zipmap a/resources (repeat 0)) {:sheep 1 :boar 1 :cattle 1}))
                   (assoc :round 14 :phase :finish-round) g/finish-game g/drain)
      bake (-> base (update-in [:players 0 :minors] conj crock)
                   (update-in [:players 0 :occupations] conj brush)
                   (update-in [:players 0 :improvements] conj :hearth-4)
                   (assoc-in [:players 0 :goods] (merge (zipmap a/resources (repeat 0)) {:grain 1 :boar 1}))
                   (g/queue-front [(g/task 0 nil {:op :bake :optional? true} {})]) g/drain)
      feed (-> base (update-in [:players 0 :minors] conj plane)
                   (update-in [:players 0 :improvements] conj :joinery)
                   (assoc-in [:players 0 :goods] (merge (zipmap a/resources (repeat 0)) {:wood 2}))
                   (assoc :phase :feeding :fed #{} :turn 0))
      ids (mapv (fn [[title state]]
                  (let [id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names names :title title :setup {:mode :random}}))]
                    (store/save! s (swap! (:rooms s) assoc-in [id :state] state)) id))
                [["Final animal loan" loan] ["Simultaneous cooking" bake] ["Plane feeding choice" feed]])]
  (println (str/join "," ids)))
