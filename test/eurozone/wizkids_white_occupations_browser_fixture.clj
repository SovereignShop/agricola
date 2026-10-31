(require '[eurozone.store :as store] '[agricola.engine :as a] '[agricola.card-game :as g]
         '[agricola.card-rules :as c] '[clojure.string :as str])
(defn cid [code] (:id (first (filter #(and (= "WizKids White Expansion" (:pack %))
                                          (= code (str (:deck %) (:number %)))) (vals c/metadata)))))
(defn own [s code] (update-in s [:players 0 :occupations] conj (cid code)))
(let [store (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      names ["Ada" "Ben" "Cy" "Dee"] base (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0))
      stable (-> base (assoc-in [:players 0 :goods :wood] 1)
                 (assoc-in [:players 0 :hand :occupation] [(cid "C89")]) (g/play-card 0 (cid "C89") 0 {}) g/drain)
      puppet (-> base (own "C152") (assoc :turn 1)
                 (assoc-in [:players 0 :goods :food] 1) (assoc-in [:players 1 :goods :food] 0)
                 (assoc-in [:players 0 :hand :occupation] [(cid "C118")])
                 (g/command 1 {:type :action :space :traveling}))
      bell (-> base (own "D107") (assoc :phase :return-home)
               (assoc-in [:players 0 :goods :clay] 5) (assoc-in [:players 0 :goods :food] 2)
               (g/queue-front [(g/event :return-home 0)]) g/drain)
      plow (-> base (own "D90") (g/command 0 {:type :action :space :plow :cell 0}))
      ids (mapv (fn [[title state]]
                  (let [id (:id (store/transact! store token {:event :create :mode :local :name "Ada" :names names :title title :setup {:mode :random}}))]
                    (store/save! store (swap! (:rooms store) assoc-in [id :state] state)) id))
                [["Stable Master" stable] ["Puppeteer" puppet] ["Bellfounder" bell] ["Plow Maker" plow]])]
  (println (str/join "," ids)))
