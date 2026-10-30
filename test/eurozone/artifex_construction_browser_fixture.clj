(require '[eurozone.store :as store] '[agricola.engine :as a] '[clojure.string :as str]
         '[agricola.card-game :as g] '[agricola.card-rules :as c])
(def names ["Ada" "Ben" "Cara" "Dan"])
(defn fresh [] (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0)))
(defn own [state code]
  (let [cid (c/id code)] (update-in state [:players 0 (if (= :minor (:kind (c/card cid))) :minors :occupations)] conj cid)))
(defn rich [state] (update-in state [:players 0] a/gain {:wood 20 :reed 10 :grain 3}))
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      artist (-> (fresh) rich (own :a149) (own :a093)
                 (g/command 0 {:type :action :space :traveling}))
      bed (-> (fresh) rich (own :a093)
              (g/command 0 {:type :action :space :build :rooms [0]}))
      home (-> (fresh) rich (own :a021)
               (update-in [:players 0] a/place-tile :room 0)
               (g/command 0 {:type :action :space :build :rooms [1]}))
      ids (mapv (fn [state]
                  (let [id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names names
                                                         :title "Artifex construction effects" :setup {:mode :random}}))]
                    (store/save! s (swap! (:rooms s) assoc-in [id :state] state)) id))
                [artist bed home])]
  (println (str/join "," ids)))
