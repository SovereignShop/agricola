(require '[eurozone.store :as store] '[clojure.data.json :as json])
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      result (store/transact! s token {:event :create :mode :local :name "Voice tester" :names ["Voice tester" "Second player"]
                                      :title "Voice command prototype"})
      id (:id result)]
  (store/transact! s token {:event :start :id id :revision (:revision (get @(:rooms s) id))})
  (println id))
