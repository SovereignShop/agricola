(require '[eurozone.store :as store] '[agricola.engine :as a] '[clojure.string :as str]
         '[agricola.card-game :as g] '[agricola.card-rules :as c] '[agricola.card-reactive :as reactive])
(def names ["Ada" "Ben" "Cara" "Dan"])
(defn fresh [] (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0)))
(defn own [state code] (let [cid (c/id code)] (update-in state [:players 0 (if (= :minor (:kind (c/card cid))) :minors :occupations)] conj cid)))
(defn hand [state code] (assoc-in state [:players 0 :hand (:kind (c/card (c/id code)))] [(c/id code)]))
(defn plowed [state cells] (reduce #(update-in %1 [:players 0] a/place-tile :field %2) state cells))
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      knife (-> (fresh) (hand :a003) (assoc-in [:players 0 :hand :occupation] (mapv c/id [:a103 :a104 :a116]))
                (update-in [:players 0] a/gain {:wood 1})
                (g/command 0 {:type :action :space :meeting :card (c/id :a003)}))
      yard (g/drain (g/play-card (-> (fresh) (plowed [0 1 2 3 4 6]) (hand :a040)
                                     (update-in [:players 0] a/gain {:wood 1 :reed 1})) 0 (c/id :a040) 0 {}))
      loppers (-> (fresh) (own :a034) (reactive/watch-fences 0) (update :active conj :fences)
                  (update-in [:players 0] a/gain {:wood 10})
                  (g/command 0 {:type :action :space :fences :fences (a/edges 0)}))
      horse (fn [wood] (-> (fresh) (own :a048) (update-in [:players 0] a/gain {:wood wood})
                            (reactive/watch-wood 0) (g/command 0 {:type :action :space :wood})))
      race (g/drain (g/play-card (-> (fresh) (plowed [0 1 2 3]) (hand :a144)) 0 (c/id :a144) 0 {}))
      ids (mapv (fn [state]
                  (let [id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names names
                                                         :title "Artifex supply workflows" :setup {:mode :random}}))]
                    (store/save! s (swap! (:rooms s) assoc-in [id :state] state)) id))
                [knife yard loppers (horse 2) (horse 4) race])]
  (println (str/join "," ids)))
