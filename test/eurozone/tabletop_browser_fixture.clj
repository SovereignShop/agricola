;; A five-player table with private hands and a card decision ready to trigger.
(require '[eurozone.store :as store] '[agricola.engine :as a]
         '[agricola.card-reactive :as reactive] '[clojure.string :as str] '[agricola.card-game :as cards] '[agricola.card-rules :as rules])
(let [s (store/make-store (System/getenv "EUROZONE_DATA"))
      token (apply str (repeat 64 "a"))
      names ["Ada" "Ben" "Cleo" "Dara" "Eli"]
      id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names names
                                       :title "Tabletop experiment" :setup {:mode :random}}))
      g (-> (cards/initialize (a/new-game names (vec (mapcat identity a/stages)) 0))
            (update :players (fn [ps] (mapv #(-> % (a/gain {:food 10 :wood 10 :clay 4 :reed 2 :stone 2})
                                                (assoc :hand {:occupation (mapv rules/id [:a116 :a112 :a120 :a106 :a104 :a110 :a115])
                                                              :minor (mapv rules/id [:b068 :a044 :b056 :a002 :a005 :a007 :a008])})) ps))))]
  (store/save! s (swap! (:rooms s) assoc-in [id :state] g))
  (let [online (:id (store/transact! s token {:event :create :mode :online :name "Ada" :expected-players 2
                                            :title "Private hands" :setup {:mode :random}}))
        _ (store/transact! s (apply str (repeat 64 "b")) {:event :join :id online :revision 0 :name "Ben"})
        _ (store/transact! s token {:event :start :id online :revision 1})
        private-game (-> (cards/initialize (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0))
                         (assoc-in [:players 0 :hand] {:occupation [(rules/id :a112)] :minor []})
                         (assoc-in [:players 1 :hand] {:occupation (mapv rules/id [:a116 :a115]) :minor []}))
        effect-id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names ["Ada" "Ben"]
                                               :title "Optional effect" :setup {:mode :random}}))
        horse (-> (cards/initialize (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0))
                  (update-in [:players 0 :minors] conj (rules/id :a048))
                  (update-in [:players 0] a/gain {:wood 2})
                  (reactive/watch-wood 0)
                  (cards/command 0 {:type :action :space :wood}))]
    (store/save! s (-> @(:rooms s) (assoc-in [online :state] private-game) (assoc-in [effect-id :state] horse)))
    (println (str/join "," [id online effect-id]))))
