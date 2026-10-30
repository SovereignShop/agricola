(require '[eurozone.store :as store] '[agricola.engine :as a] '[clojure.string :as str]
         '[agricola.card-game :as g] '[agricola.card-rules :as c])
(def names ["Ada" "Ben" "Cara" "Dan"])
(defn fresh [] (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0)))
(defn own [state code]
  (let [cid (c/id code)] (update-in state [:players 0 (if (= :minor (:kind (c/card cid))) :minors :occupations)] conj cid)))
(defn hand [state code] (assoc-in state [:players 0 :hand (:kind (c/card (c/id code)))] [(c/id code)]))
(defn rich [state] (update-in state [:players 0] a/gain {:wood 20 :reed 10 :clay 20 :grain 3}))
(defn resolve! [state choice] (g/command state (g/acting-player state) (merge {:type :resolve :decision (:decision-id state)} choice)))
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      stagehand (-> (fresh) rich (own :a150) (assoc :turn 1) (g/command 1 {:type :action :space :traveling}))
      riparian (-> (fresh) rich (own :a128) (assoc :turn 1) (assoc-in [:players 0 :house] :clay)
                   (g/command 1 {:type :action :space :reed}))
      planner (g/drain (g/play-card (-> (fresh) (hand :a089)) 0 (c/id :a089) 0 {}))
      planned (resolve! planner {:rounds [4 7 10]})
      arrival (g/drain (g/begin-round (assoc planned :round 3)))
      lodger (-> (g/drain (g/play-card (-> (fresh) (hand :a127) (assoc :round 9)) 0 (c/id :a127) 0 {}))
                 (update-in [:players 0] a/grow true) (assoc-in [:players 0 :remaining] 0)
                 (g/continue-step :return-home) g/drain)
      telegram (-> (g/drain (g/play-card (-> (fresh) (hand :a022) (assoc-in [:players 0 :discarded-fences] 12)) 0 (c/id :a022) 0 {}))
                   (assoc :round 3) g/begin-round g/drain)
      ids (mapv (fn [state]
                  (let [id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names names
                                                         :title "Artifex temporary and off-turn effects" :setup {:mode :random}}))]
                    (store/save! s (swap! (:rooms s) assoc-in [id :state] state)) id))
                [stagehand stagehand riparian planner arrival lodger telegram])]
  (println (str/join "," ids)))
