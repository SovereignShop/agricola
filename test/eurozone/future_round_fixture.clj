(require '[eurozone.store :as store] '[agricola.engine :as a]
         '[agricola.card-game :as cards] '[agricola.card-rules :as rules])
(let [s (store/make-store (System/getenv "EUROZONE_DATA"))
      token (apply str (repeat 64 "a"))
      id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names ["Ada" "Ben"]
                                       :title "Future round markers" :setup {:mode :random}}))
      g (-> (cards/initialize (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0))
            (update-in [:players 0] a/gain {:wood 10})
            (assoc-in [:players 0 :occupations] (mapv rules/id [:a116 :a112]))
            (assoc-in [:players 0 :hand] {:occupation [] :minor [(rules/id :a044)]})
            (assoc-in [:players 0 :improvements] #{:well})
            (assoc-in [:players 0 :well-rounds] #{2 3 4 5 6})
            (assoc-in [:players 1 :hand] {:occupation [] :minor []})
            (assoc-in [:players 1 :minors] (mapv rules/id [:a044 :a019]))
            (cards/schedule 1 (rules/id :a044) {:offsets [1 2] :goods {:food 2}})
            (cards/schedule 1 (rules/id :a019) {:offsets [5] :goods {:field 1}}))]
  (store/save! s (assoc-in @(:rooms s) [id :state] g))
  (println id))
