(require '[eurozone.store :as store] '[agricola.engine :as a]
         '[agricola.card-game :as g] '[agricola.card-rules :as c])
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      state (-> (g/initialize (a/new-game ["Ada" "Ben" "Cara" "Dan"] (vec (mapcat identity a/stages)) 0))
                (update-in [:players 0 :occupations] into [(c/id :a148) (c/id :b169)])
                (assoc-in [:players 0 :completed-feeding-phases] 3)
                (assoc-in [:players 0 :card-animal-capacity] 1)
                (update-in [:players 0] a/gain {:sheep 3 :boar 1 :cattle 1}))
      baking (-> (g/initialize (a/new-game ["Ada" "Ben" "Cara" "Dan"] (vec (mapcat identity a/stages)) 0))
                 (update-in [:players 0 :minors] conj (c/id :a030))
                 (update :active conj :sow-bake)
                 (assoc-in [:players 0 :goods :grain] 2)
                 (update-in [:players 0 :improvements] conj :fireplace-2)
                 (g/command 0 {:type :action :space :sow-bake :bake-grain 1}))
      ids (mapv (fn [game]
                  (let [id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names ["Ada" "Ben" "Cara" "Dan"]
                                                         :title "Artifex harvest cards" :setup {:mode :random}}))]
                    (store/save! s (swap! (:rooms s) assoc-in [id :state] game)) id)) [state baking])]
  (println (clojure.string/join "," ids)))
