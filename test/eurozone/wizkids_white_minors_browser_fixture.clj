(require '[eurozone.store :as store] '[agricola.engine :as a] '[agricola.card-game :as g]
         '[agricola.card-rules :as c] '[clojure.string :as str])
(def white (filter #(= "WizKids White Expansion" (:pack %)) (vals c/cards)))
(defn cid [deck n] (:id (first (filter #(and (= deck (:deck %)) (= (str n) (:number %))) white))))
(let [service (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      names ["Ada" "Ben"] base (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0))
      art (-> base (update-in [:players 0] a/gain {:food 20 :wood 20 :clay 20 :stone 20 :reed 20})
              (assoc-in [:players 0 :occupations] (mapv c/id [:a116 :a112 :a120]))
              (assoc-in [:players 0 :hand] (into {} (for [kind [:minor :occupation]] [kind (mapv :id (sort-by :id (filter #(= kind (:kind %)) white)))]))))
      steam (-> base (assoc-in [:players 0 :remaining] 1) (assoc-in [:players 0 :minors] [(cid "C" 25)])
                (assoc-in [:players 0 :goods :grain] 1) (assoc-in [:players 0 :improvements] #{:hearth-4})
                (g/command 0 {:type :action :space :wood}))
      distill (-> base (assoc :phase :feeding :round 4 :fed #{})
                  (assoc-in [:players 0 :goods :vegetable] 1) (assoc-in [:players 0 :minors] [(cid "C" 59)])
                  (g/queue-front [(g/event :feeding-start 0)]) g/drain)
      ids (mapv (fn [[title state]]
                  (let [id (:id (store/transact! service token {:event :create :mode :local :name "Ada" :names names :title title :setup {:mode :random}}))]
                    (store/save! service (swap! (:rooms service) assoc-in [id :state] state)) id))
                [["White card prints" art] ["Steam Machine" steam] ["Schnapps Distillery" distill]])]
  (println (str/join "," ids)))
