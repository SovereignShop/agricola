(require '[eurozone.store :as store] '[agricola.engine :as a] '[clojure.string :as str]
         '[agricola.card-game :as g] '[agricola.card-rules :as c])
(defn fresh [] (g/initialize (a/new-game ["Ada" "Ben" "Cara" "Dan"] (vec (mapcat identity a/stages)) 0)))
(defn own [state code]
  (let [cid (c/id code)] (update-in state [:players 0 (if (= :minor (:kind (c/card cid))) :minors :occupations)] conj cid)))
(defn hand [state code] (assoc-in state [:players 0 :hand :minor] [(c/id code)]))
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      shed (-> (fresh) (hand :a010) (assoc :round 2 :active (conj (:active (fresh)) :improvement))
               (update-in [:players 0] a/gain {:wood 2 :reed 1}))
      shelter (-> (fresh) (hand :a001) (update-in [:players 0] a/build-fences (a/edges 4) {:free-fences 4}))
      company (-> (fresh) (hand :a013) (own :a087) (update-in [:players 0] a/gain {:wood 4}))
      payment (-> (fresh) (own :a154) (assoc :turn 1) (update-in [:players 0] a/gain {:grain 1}))
      mud (-> (fresh) (own :a011) (assoc :round 3 :active (into (:active (fresh)) [:improvement :sow-bake]))
              (assoc-in [:players 0 :farm 4] {:type :field :count 0})
              (update-in [:players 0] a/gain {:boar 1 :sheep 1 :grain 1}))
      ids (mapv (fn [state]
                  (let [id (:id (store/transact! s token {:event :create :mode :local :name "Ada"
                                                         :names ["Ada" "Ben" "Cara" "Dan"]
                                                         :title "Artifex workflow" :setup {:mode :random}}))]
                    (store/save! s (swap! (:rooms s) assoc-in [id :state] state)) id))
                [shed shelter company payment mud])]
  (println (str/join "," ids)))
