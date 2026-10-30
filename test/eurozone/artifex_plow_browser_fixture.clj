(require '[eurozone.store :as store] '[agricola.engine :as a] '[clojure.string :as str]
         '[agricola.card-game :as g] '[agricola.card-rules :as c])
(def names ["Ada" "Ben" "Cara" "Dan"])
(defn fresh [] (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0)))
(defn wheel []
  (-> (fresh) (update-in [:players 0 :minors] conj (c/id :a018))
      (assoc-in [:players 0 :card-state (c/id :a018) :uses] 1)))
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      double (-> (fresh) (update-in [:players 0 :hand :minor] conj (c/id :a020))
                 (update-in [:players 0] a/gain {:grain 1})
                 (g/command 0 {:type :action :space :meeting :card (c/id :a020)}))
      cultivation (-> (wheel) (assoc :round 12) (update :active conj :cultivation)
                     (update-in [:players 0] a/gain {:grain 3}))
      ids (mapv (fn [state]
                  (let [id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names names
                                                         :title "Artifex plows" :setup {:mode :random}}))]
                    (store/save! s (swap! (:rooms s) assoc-in [id :state] state)) id))
                [double (wheel) cultivation])]
  (println (str/join "," ids)))
