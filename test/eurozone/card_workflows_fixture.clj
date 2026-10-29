(require '[eurozone.store :as store] '[agricola.engine :as a] '[clojure.string :as str]
         '[agricola.card-game :as g] '[agricola.card-rules :as c])
(defn fresh [] (g/initialize (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0)))
(defn own [state code]
  (let [cid (c/id code)] (update-in state [:players 0 (if (= :minor (:kind (c/card cid))) :minors :occupations)] conj cid)))
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      payment (-> (fresh) (assoc-in [:players 0 :hand :minor] [(c/id :a004)])
                  (assoc-in [:players 0 :goods :grain] 1))
      choice (-> (fresh) (own :a118) (assoc :phase :field-harvest)
                 (assoc-in [:players 0 :goods :food] 1)
                 (g/queue-front [(g/event :field-phase 0)]) g/drain)
      preparation (-> (fresh) (own :a090) (assoc-in [:players 0 :house] :stone)
                      (assoc-in [:players 0 :goods :food] 0) (assoc-in [:players 0 :goods :grain] 1)
                      g/begin-round g/drain)
      harvest (-> (fresh) (own :b104)
                  (assoc :phase :breeding :round 14 :fed #{0 1} :bred #{0} :turn 1)
                  (assoc-in [:players 0 :goods :sheep] 1)
                  (g/command 1 {:type :breed :offspring {}}))
      ids (mapv (fn [state]
                  (let [id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names ["Ada" "Ben"]
                                                         :title "Card workflow fixture" :setup {:mode :random}}))]
                    (store/save! s (swap! (:rooms s) assoc-in [id :state] state)) id))
                [payment choice preparation harvest])]
  (println (str/join "," ids)))
