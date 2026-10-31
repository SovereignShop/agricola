(require '[eurozone.store :as store] '[agricola.engine :as a]
         '[agricola.card-game :as cards] '[agricola.card-rules :as rules])

(defn play [g pid cid]
  (cards/drain (cards/play-card (update-in g [:players pid :hand (:kind (rules/card cid))] conj cid) pid cid 0 {})))

(let [s (store/make-store (System/getenv "EUROZONE_DATA"))
      names ["Ada" "Ben" "Cal" "Dee" "Eve"]
      token (apply str (repeat 64 "a"))
      id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names names
                                       :title "Future pieces" :setup {:mode :random}}))
      planner (rules/id :a089)
      telegram :catalog/revised-artifex-expansion-a-22-telegram
      g (-> (cards/initialize (a/new-game names (vec (mapcat identity a/stages)) 0))
            (update :players #(mapv (fn [p] (a/gain p {:food 10})) %))
            (play 1 :catalog/original-base-k-305-animal-handler)
            (play 2 :catalog/original-base-i-265-fence-deliveryman)
            (play 3 :catalog/original-base-i-73-guest)
            (assoc-in [:players 0 :hand] {:occupation [:catalog/original-base-k-293-plowman] :minor []})
            (assoc-in [:players 4 :occupations] [planner])
            (assoc-in [:players 4 :minors] [telegram])
            (assoc-in [:players 4 :reserved-stables] {4 1})
            (assoc-in [:players 4 :schedule] {4 [{:card planner :stable true} {:card telegram :person true}]})
            (assoc-in [:players 4 :well-deliveries] {4 1}))]
  (store/save! s (assoc-in @(:rooms s) [id :state] g))
  (println id))
