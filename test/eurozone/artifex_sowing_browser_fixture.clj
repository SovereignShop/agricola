(require '[eurozone.store :as store] '[agricola.engine :as a] '[clojure.string :as str]
         '[agricola.card-game :as g] '[agricola.card-rules :as c])
(def names ["Ada" "Ben" "Cara" "Dan"])
(defn own [state pid code]
  (let [cid (c/id code)] (update-in state [:players pid (if (= :minor (:kind (c/card cid))) :minors :occupations)] conj cid)))
(defn fresh []
  (-> (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0))
      (assoc :round 3) (update :active into [:improvement :sow-bake])
      (assoc-in [:players 0 :farm 0] {:type :field :count 0})
      (assoc-in [:players 0 :farm 1] {:type :field :count 0})
      (assoc-in [:players 0 :farm 2] {:type :field :count 0})))
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      seed (-> (fresh) (own 0 :a065) (own 0 :a079) (update-in [:players 0] a/gain {:vegetable 1}))
      publican (-> (fresh) (own 1 :a132) (update-in [:players 1] a/gain {:grain 1}))
      forester (-> (fresh) (own 0 :a065) (own 0 :a079) (own 0 :a115)
                   (update-in [:players 0] a/gain {:vegetable 2}))
      ids (mapv (fn [state]
                  (let [id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names names
                                                         :title "Artifex sowing" :setup {:mode :random}}))]
                    (store/save! s (swap! (:rooms s) assoc-in [id :state] state)) id))
                [seed publican publican forester])]
  (println (str/join "," ids)))
