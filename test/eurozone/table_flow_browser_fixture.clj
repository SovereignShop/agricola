(require '[eurozone.store :as store] '[agricola.engine :as a] '[clojure.data.json :as json])
(let [s (store/make-store (System/getenv "EUROZONE_DATA"))
      tokens (mapv #(apply str (repeat 64 %)) ["a" "b" "c"])
      id (:id (store/transact! s (tokens 0) {:event :create :mode :online :name "Ada" :expected-players 3 :title "Harvest plans"}))]
  (doseq [[pid name] [[1 "Ben"] [2 "Cam"]]]
    (store/transact! s (tokens pid) {:event :join :id id :revision (dec pid) :name name}))
  (let [state (-> (a/new-game ["Ada" "Ben" "Cam"] (vec (mapcat identity a/stages)) 0)
                  (assoc :round 4 :phase :feeding :fed #{} :bred #{})
                  (update :players #(mapv (fn [p] (-> p (a/gain {:food 20 :wood 10})
                                                        (a/build-fences (a/edges 14))
                                                        (a/build-stables [14] 2) (a/gain {:sheep 2}))) %)))]
    (with-redefs [store/start-state (fn [& _] state)]
      (store/transact! s (tokens 0) {:event :start :id id :revision 2})))
  (println (json/write-str {:harvest id})))
