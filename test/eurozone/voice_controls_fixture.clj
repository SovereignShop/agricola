(require '[eurozone.store :as store] '[clojure.data.json :as json])
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      normal (:id (store/transact! s token {:event :create :mode :local :name "Voice tester" :names ["Voice tester" "Second player"] :title "Voice commands · all controls"}))
      _ (store/transact! s token {:event :start :id normal :revision (:revision (get @(:rooms s) normal))})
      card (:id (store/transact! s token {:event :create :scenario "voice-control-review" :name "Voice tester"}))
      fences (:id (store/transact! s token {:event :create :scenario "fence-dealer" :name "Voice tester"}))
      quantity (:id (store/transact! s token {:event :create :scenario "lemon-trader" :name "Voice tester"}))
      optional (:id (store/transact! s token {:event :create :scenario "cabbage-lover" :name "Voice tester"}))]
  (println (json/write-str {:normal normal :card card :optional optional :fences fences :quantity quantity})))
