(require '[eurozone.store :as store] '[agricola.engine :as a] '[agricola.card-game :as g]
         '[clojure.string :as str])
(let [service (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      ids (for [[card house cells] [[:catalog/original-base-e-191-mason :stone [0 6]]
                                    [:catalog/original-base-e-151-master-builder :wood [0 6 11]]]]
            (let [id (:id (store/transact! service token {:event :create :mode :local :name "Ada" :names ["Ada" "Ben"] :setup {:mode :random}}))
                  state (-> (g/initialize (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0))
                            (assoc-in [:players 0 :house] house)
                            (assoc-in [:players 0 :occupations] [card])
                            (update-in [:players 0 :farm] #(reduce (fn [farm cell] (assoc farm cell {:type :room})) % cells)))]
              (store/save! service (swap! (:rooms service) assoc-in [id :state] state)) id))]
  (println (str/join "," ids)))
