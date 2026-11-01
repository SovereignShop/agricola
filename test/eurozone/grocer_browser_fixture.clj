(require '[clojure.string :as str] '[eurozone.store :as store]
         '[agricola.engine :as a] '[agricola.card-game :as g] '[agricola.card-rules :as c])
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))]
  (println
   (str/join ","
    (for [cid [:catalog/original-base-e-184-grocer (c/id :a102)]]
      (let [id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names ["Ada" "Ben"] :setup {:mode :random}}))
            state (-> (g/initialize (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0))
                      (assoc-in [:players 0 :hand :occupation] [cid])
                      (update-in [:players 0] a/gain {:food 20})
                      (g/play-card 0 cid 0 {}) g/drain)]
        (store/save! s (swap! (:rooms s) assoc-in [id :state] state))
        id)))))
