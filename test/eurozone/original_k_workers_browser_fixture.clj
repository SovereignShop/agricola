(require '[eurozone.store :as store] '[agricola.engine :as a]
         '[agricola.card-game :as g] '[agricola.card-rules :as c] '[clojure.string :as str])
(let [store (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      names ["Ada" "Ben"] hut :catalog/original-base-k-138-reed-hut broom :catalog/original-base-k-125-broom
      horse :catalog/original-base-k-135-horse
      base (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0))
      reserve (into [horse] (take 6 (sort (for [[id card] c/cards :when (and (= "K" (:deck card)) (= :minor (:kind card))
                                                                                    (not (#{broom horse} id)))] id))))
      broom-game (-> base (assoc :minor-deck reserve :minor-deck-count 7)
                       (assoc-in [:players 0 :hand :minor] [broom]) (update-in [:players 0] a/gain {:wood 10 :food 10})
                       (g/play-card 0 broom 0 {}) g/drain)
      hut-ready (-> base (assoc-in [:players 0 :hand :minor] [hut])
                    (update-in [:players 0] a/gain {:wood 1 :reed 4})
                    (g/play-card 0 hut 0 {}) g/drain)
      growth (g/drain (g/queue-front hut-ready [(g/task 0 hut {:op :grow :room-required? false} {})]))
      last-worker (-> hut-ready (assoc-in [:players 0 :family] 4) (assoc-in [:players 0 :remaining] 1)
                               (update :active conj :urgent-growth))
      ids (mapv (fn [[title state]]
                  (let [id (:id (store/transact! store token {:event :create :mode :local :name "Ada" :names names :title title :setup {:mode :random}}))]
                    (store/save! store (swap! (:rooms store) assoc-in [id :state] state)) id))
                [["Broom replacement hand" broom-game] ["Adopt Reed Hut" growth] ["Last Reed Hut worker" last-worker]])]
  (println (str/join "," ids)))
