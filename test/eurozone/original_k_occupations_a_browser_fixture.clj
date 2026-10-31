(require '[eurozone.store :as store] '[agricola.engine :as a] '[agricola.card-game :as g]
         '[agricola.card-rules :as c] '[clojure.string :as str])
(defn cid [n] (:id (first (filter #(and (= "Original" (:edition %)) (= "K" (:deck %)) (= (str n) (:number %))) (vals c/metadata)))))
(defn own [s n] (update-in s [:players 0 :occupations] conj (cid n)))
(defn rich [s] (update-in s [:players 0] a/gain {:food 20 :wood 20 :reed 20 :clay 20 :stone 20 :grain 6 :vegetable 4}))
(let [store (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      names ["Ada" "Ben" "Cy" "Dee"] base (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0))
      serf (-> base (own 266) (update :active conj :sow-bake) (update-in [:players 0] a/place-tile :field 0)
               (assoc-in [:players 0 :goods :grain] 1) (g/command 0 {:type :action :space :sow-bake}))
      nurse (-> base rich (own 270) (own 267) (g/command 0 {:type :action :space :build :rooms [0 1]}))
      pieceworker (-> base (own 268) (g/command 0 {:type :action :space :market}))
      scholar (-> base rich (own 279) (assoc :phase :preparation) (assoc-in [:players 0 :house] :stone)
                  (assoc-in [:players 0 :hand :occupation] [(cid 287)]) (g/queue-front [(g/event :round-start 0)]) g/drain)
      student (-> base (own 275) (assoc-in [:players 0 :goods :food] 0)
                  (assoc-in [:players 0 :hand :occupation] [(cid 291)]) (g/play-card 0 (cid 291) 3 {:perpetual-student? true}) g/drain)
      salesman (-> base rich (own 281) (update-in [:players 0 :occupations] conj :catalog/original-base-e-179-merchant)
                   (assoc-in [:players 0 :hand :minor] (mapv cid [135 120 123 109])) (update :active conj :improvement)
                   (g/command 0 {:type :action :space :improvement :salesman? true :card (cid 135)}))
      relocation (-> base (own 289) (assoc :turn 3) (update :active conj :sow-bake)
                     (update :players #(mapv (fn [p] (assoc p :remaining 0)) %))
                     (assoc-in [:players 0 :worker-spaces] [:grain]) (assoc-in [:occupied :grain] 0)
                     (update-in [:players 0] a/place-tile :field 0) (assoc-in [:players 0 :goods :grain] 1)
                     g/start-relocations g/drain)
      ids (mapv (fn [[title state]]
                  (let [id (:id (store/transact! store token {:event :create :mode :local :name "Ada" :names names :title title :setup {:mode :random}}))]
                    (store/save! store (swap! (:rooms store) assoc-in [id :state] state)) id))
                [["Serf" serf] ["Wet Nurse" nurse] ["Pieceworker" pieceworker] ["Scholar" scholar]
                 ["Perpetual Student" student] ["Traveling Salesman" salesman] ["Countryman" relocation]])]
  (println (str/join "," ids)))
