(require '[eurozone.store :as store] '[agricola.engine :as a] '[agricola.card-game :as g] '[agricola.card-rules :as c])
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names ["Ada" "Ben" "Cam" "Di" "Eve"] :title "Five-player browser fixture" :setup {:mode :random}}))
      game (-> (g/initialize (a/new-game ["Ada" "Ben" "Cam" "Di" "Eve"] (vec (mapcat identity a/stages)) 0))
               (update :players #(mapv (fn [p] (-> p (a/gain {:food 10 :wood 10 :reed 5}) (assoc :hand {:occupation [] :minor []}))) %))
               (assoc-in [:players 0 :occupations] [(c/id :a174)])
               (assoc-in [:players 0 :hand :occupation] [(c/id :a173)]))]
  (store/save! s (assoc-in @(:rooms s) [id :state] game)) (println id))
