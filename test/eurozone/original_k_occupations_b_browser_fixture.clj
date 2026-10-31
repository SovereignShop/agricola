(require '[eurozone.store :as store] '[agricola.engine :as a]
         '[agricola.card-game :as g] '[clojure.string :as str])
(let [store (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      names ["Ada" "Ben" "Cy" "Dee"]
      trainer :catalog/original-base-k-342-animal-trainer tamer :catalog/original-base-k-306-animal-tamer
      overseer :catalog/original-base-k-312-fence-overseer foreman :catalog/original-base-k-308-foreman
      breeder :catalog/original-base-k-307-animal-breeder
      base (-> (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0))
               (assoc-in [:players 0 :goods :food] 10) (assoc-in [:players 0 :goods :wood] 20))
      train-game (-> base (update-in [:players 0 :occupations] into [trainer tamer])
                         (assoc-in [:piles :traveling :food] 5)
                         (g/command 0 {:type :action :space :traveling}))
      fence-game (-> base (update-in [:players 0 :occupations] conj overseer)
                         (g/command 0 {:type :action :space :build :stables [0]}))
      foreman-game (-> base (update-in [:players 0 :occupations] conj foreman) g/begin-round g/drain)
      breed-game (-> base (update-in [:players 0 :occupations] conj breeder) (update :active conj :fences)
                         (g/command 0 {:type :action :space :fences :fences (vec (a/edges 0))}))
      seller :catalog/original-base-k-310-resource-seller
      seller-game (-> base (update-in [:players 0 :occupations] conj seller)
                          (assoc-in [:players 0 :card-state seller :resource-stack] [:wood :clay :reed :clay :stone :clay :stone])
                          (g/run-effect (g/task 0 :catalog/original-base-k-284-wood-distributor {:op :gain :goods {:wood 1 :clay 1}} {}) {}) g/drain)
      ids (mapv (fn [[title state]]
                  (let [id (:id (store/transact! store token {:event :create :mode :local :name "Ada" :names names :title title :setup {:mode :random}}))]
                    (store/save! store (swap! (:rooms store) assoc-in [id :state] state)) id))
                [["Animal Trainer" train-game] ["Fence Overseer" fence-game] ["Foreman" foreman-game] ["Animal Breeder" breed-game] ["Resource Seller" seller-game]])]
  (println (str/join "," ids)))
