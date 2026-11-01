(require '[eurozone.store :as store] '[agricola.engine :as a] '[agricola.card-game :as g]
         '[agricola.card-rules :as c] '[clojure.string :as str])
(def batch (map c/card (keys c/community-deck-definitions)))
(defn cid [deck n] (:id (first (filter #(and (= deck (:deck %)) (= (str n) (:number %))) batch))))
(defn play [s id] (g/drain (g/play-card (update-in s [:players 0 :hand (:kind (c/card id))] conj id) 0 id 0 {})))
(let [service (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      names ["Ada" "Ben" "Cy" "Dee"]
      base (-> (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0))
               (update-in [:players 0] a/gain {:food 20 :wood 20 :clay 20 :reed 20 :stone 20})
               (assoc-in [:players 0 :occupations] [:catalog/original-base-e-197-braggart (c/id :a133) :catalog/original-base-e-170-estate-manager]))
      gallery (assoc-in base [:players 0 :hand]
                        (into {} (for [kind [:minor :occupation]] [kind (mapv :id (sort-by :id (filter #(= kind (:kind %)) batch)))])))
      pond (-> base (play (cid "WM" 76)) (assoc :round 4) g/begin-round g/drain)
      gate (-> base (assoc :phase :feeding :round 4 :fed #{})
               (assoc-in [:players 0 :goods :sheep] 1) (assoc-in [:players 0 :minors] [(cid "WM" 97)])
               (g/queue-front [(g/event :feeding-start 0)]) g/drain)
      ids (mapv (fn [[title state]]
                  (let [id (:id (store/transact! service token {:event :create :mode :local :name "Ada" :names names :title title :setup {:mode :random}}))]
                    (store/save! service (swap! (:rooms service) assoc-in [id :state] state)) id))
                [["G, WM and France prints" gallery] ["Drained Pond arrival" pond] ["Open Gate choice" gate]])]
  (println (str/join "," ids)))
