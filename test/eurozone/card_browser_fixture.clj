;; Deterministic private hands for the browser card-rules integration test.
(require '[eurozone.store :as store] '[agricola.engine :as a]
         '[agricola.card-game :as cards] '[agricola.card-rules :as rules])
(let [s (store/make-store (System/getenv "EUROZONE_DATA"))
      token (apply str (repeat 64 "a"))
      id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names ["Ada" "Ben"]
                                       :title "Card rules fixture" :setup {:mode :random}}))
      g (-> (cards/initialize (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0))
            (update-in [:players 0] a/gain {:food 10 :wood 10})
            (assoc-in [:players 0 :hand] {:occupation (mapv rules/id [:a116 :a112])
                                         :minor (mapv rules/id [:b068 :a044 :b056 :a002])})
            (assoc-in [:players 1 :hand] {:occupation [] :minor []}))]
  (store/save! s (assoc-in @(:rooms s) [id :state] g))
  (println id))
