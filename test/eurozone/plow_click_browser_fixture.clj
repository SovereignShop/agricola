(require '[eurozone.store :as store] '[agricola.engine :as a] '[agricola.card-game :as g] '[agricola.card-rules :as c] '[clojure.string :as str])
(let [service (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      names ["Ada" "Ben"] base (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0))
      swing :catalog/original-base-k-115-swing-plow
      swing-state (-> base (assoc-in [:players 0 :minors] [swing])
                      (assoc-in [:players 0 :card-state swing :uses] 2)
                      (g/command 0 {:type :action :space :plow :cell 0}))
      wheel (c/id :a018)
      wheel-state (-> base (assoc :phase :preparation) (assoc-in [:players 0 :minors] [wheel])
                      (g/queue-front [(g/task 0 wheel {:op :plow-many :max-fields 2 :optional? true} {})]) g/drain)
      ids (mapv (fn [[title state]]
                  (let [id (:id (store/transact! service token {:event :create :mode :local :name "Ada" :names names :title title}))]
                    (store/save! service (swap! (:rooms service) assoc-in [id :state] state)) id))
                [["Swing Plow pending" swing-state] ["Wheel Plow pending" wheel-state]])]
  (println (str/join "," ids)))
