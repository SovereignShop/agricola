(require '[eurozone.store :as store] '[agricola.engine :as a] '[agricola.card-game :as g]
         '[agricola.card-rules :as c] '[clojure.string :as str])
(let [service (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      names ["Ada" "Ben" "Cy" "Dee" "Eve"]
      hand (into {} (for [kind [:minor :occupation]]
                     [kind (mapv :id (sort-by :id (filter #(= kind (:kind %)) (vals c/cards))))]))
      state (assoc-in (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0)) [:players 0 :hand] hand)
      id (:id (store/transact! service token {:event :create :mode :local :name "Ada" :names names :title "All implemented English prints" :setup {:mode :random}}))]
  (store/save! service (swap! (:rooms service) assoc-in [id :state] state))
  (println (str/join "," [id (count (:minor hand)) (count (:occupation hand))])))
