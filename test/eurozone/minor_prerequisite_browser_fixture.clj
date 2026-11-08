(require '[eurozone.store :as store] '[clojure.string :as str])
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))]
 (println (str/join "," (for [_ (range 2)] (:id (store/transact! s token {:event :create :name "Ada" :scenario "brushwood-roof-prerequisite"}))))))
