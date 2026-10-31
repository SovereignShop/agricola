(require '[eurozone.store :as store] '[agricola.engine :as a]
         '[agricola.card-game :as g] '[agricola.card-rules :as c] '[clojure.string :as str])
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      names ["Ada" "Ben"]
      tray :catalog/original-base-e-15-baking-tray
      ceramics :catalog/original-base-e-33-ceramics
      oven :catalog/original-base-e-27-wood-fired-oven
      base (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0))
      baking (-> base (update-in [:players 0 :minors] conj tray)
                     (update-in [:players 0] a/gain {:clay 3 :grain 1}))
      scholar (-> baking (assoc :phase :preparation)
                         (assoc-in [:players 0 :house] :stone)
                         (update-in [:players 0 :occupations] conj (c/id :b097))
                         (g/queue-front [(g/event :round-start 0)]) g/drain)
      artist-names ["Ada" "Ben" "Cora" "Dan" "Eve"]
      artist (-> (g/initialize (a/new-game artist-names (vec (mapcat identity a/stages)) 0))
                 (assoc :phase :return-home)
                 (update-in [:players 0 :minors] conj ceramics)
                 (update-in [:players 0 :occupations] conj (c/id :c171))
                 (g/queue-front [(g/event :return-home 0)]) g/drain)
      minor (-> base (update-in [:players 0 :minors] conj tray)
                     (assoc-in [:players 0 :hand] {:occupation [] :minor [oven]})
                     (update-in [:players 0] a/gain {:wood 3 :grain 1}))
      merchant (-> base (update-in [:players 0 :occupations] conj :catalog/original-base-e-179-merchant)
                    (assoc-in [:players 0 :hand] {:occupation [] :minor [:catalog/original-base-e-28-clogs tray]})
                    (update-in [:players 0] a/gain {:wood 2}))
      madonna (-> base (assoc-in [:players 0 :minors] [:catalog/original-base-e-25-spices :catalog/original-base-e-28-clogs])
                   (assoc-in [:players 0 :hand] {:occupation [] :minor [:catalog/original-base-e-38-madonna-statue]}))
      resources (-> (g/initialize (a/new-game ["Ada" "Ben" "Cora" "Dan"] (vec (mapcat identity a/stages)) 0))
                    (update-in [:players 0 :occupations] conj :catalog/original-base-e-161-fisherman)
                    (update-in [:players 1 :occupations] conj :catalog/original-base-e-164-master-forester)
                    (assoc-in [:piles :catalog/original-base-e-164-master-forester] {:wood 6})
                    (assoc-in [:piles :fishing] {:food 2}))
      ids (mapv (fn [[title state players]]
                  (let [id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names players :title title :setup {:mode :random}}))]
                    (store/save! s (swap! (:rooms s) assoc-in [id :state] state)) id))
                [["Tray on Meeting Place" baking names] ["Tray through Scholar" scholar names]
                 ["Ceramics through Young Artist" artist artist-names] ["Discounted hand oven" minor names]
                 ["Merchant repeats minor action" merchant names] ["Madonna returns two cards" madonna names]
                 ["Resource occupations" resources ["Ada" "Ben" "Cora" "Dan"]]])]
  (println (str/join "," ids)))
