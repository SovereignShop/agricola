(require '[eurozone.store :as store] '[agricola.engine :as a] '[agricola.card-game :as g])
(let [service (store/make-store (System/getenv "EUROZONE_DATA")) host (apply str (repeat 64 "a")) guest (apply str (repeat 64 "b"))
      online (:id (store/transact! service host {:event :create :name "Ada" :mode :online :expected-players 2 :setup {:mode :random}}))
      _ (store/transact! service guest {:event :join :id online :revision 0 :name "Ben"})
      _ (with-redefs [clojure.core/shuffle (comp vec reverse)
                     store/new-state (fn [_ names] (a/new-game names (vec (mapcat identity a/stages)) 1))]
          (store/transact! service host {:event :start :id online :revision 1}))
      seller :catalog/original-base-k-310-resource-seller
      local (:id (store/transact! service host {:event :create :name "Ada" :mode :local :names ["Ada" "Ben"]}))
      state (-> (g/initialize (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0))
                (assoc-in [:players 0 :occupations] [seller])
                (assoc-in [:players 0 :card-state seller :resource-stack] '(:wood :clay :reed :clay :stone :clay :stone)))
      recorded (-> (get @(:rooms service) local) (assoc :state state)
                   (store/recorded-command 0 {:type :action :space :reed})
                   (store/recorded-command 1 {:type :action :space :labor})
                   (store/recorded-command 0 {:type :action :space :wood})
                   (update-in [:recording 1 :impacts] conj {:player 0 :card seller :round 1 :op :set-card-state :changes []}))]
  (store/save! service (swap! (:rooms service) assoc local recorded))
  (println (str online "," local)))
