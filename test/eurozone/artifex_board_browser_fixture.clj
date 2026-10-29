(require '[eurozone.store :as store] '[agricola.engine :as a] '[clojure.string :as str]
         '[agricola.card-game :as g] '[agricola.card-rules :as c])
(def names ["Ada" "Ben" "Cara" "Dan"])
(defn fresh [] (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0)))
(defn own [state pid code]
  (let [cid (c/id code)] (update-in state [:players pid (if (= :minor (:kind (c/card cid))) :minors :occupations)] conj cid)))
(defn hand [state code]
  (let [cid (c/id code)] (assoc-in state [:players 0 :hand (:kind (c/card cid))] [cid])))
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      certificate (-> (fresh) (own 0 :a103) (own 0 :a104) (own 0 :a106) (hand :a082)
                      (assoc-in [:piles :wood] {:wood 4})
                      (g/command 0 {:type :action :space :meeting :card (c/id :a082)}))
      shepherd (-> (fresh) (own 0 :a137)
                   (g/command 0 {:type :action :space :reed}))
      chapel (-> (fresh) (own 0 :a039) (assoc :turn 1)
                 (update-in [:players 1] a/gain {:grain 1}))
      stored (-> (fresh) (own 0 :a081) (assoc :round 6)
                 (g/command 0 {:type :action :space :clay}))
      released (-> (fresh) (own 0 :a081) (assoc :round 6)
                   (update :players #(mapv (fn [p] (assoc p :remaining (if (= 0 (:id p)) 1 0))) %))
                   (g/command 0 {:type :action :space :clay}))
      locked (-> (fresh) (own 0 :a162) (assoc :occupied {:wood 1}) (assoc-in [:piles :wood] {:wood 0}))
      tally (-> locked (assoc-in [:occupied :clay] 2) (assoc-in [:piles :clay] {:clay 0}))
      ids (mapv (fn [state]
                  (let [id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names names
                                                         :title "Artifex board effects" :setup {:mode :random}}))]
                    (store/save! s (swap! (:rooms s) assoc-in [id :state] state)) id))
                [certificate shepherd chapel stored released locked tally])]
  (println (str/join "," ids)))
