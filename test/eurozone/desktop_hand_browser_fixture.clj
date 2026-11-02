(require '[eurozone.store :as store])
(let [service (store/make-store (System/getenv "EUROZONE_DATA"))
      token #(apply str (repeat 64 %))
      id (:id (store/transact! service (token "a") {:event :create :name "Ada" :mode :online
                                                   :expected-players 5 :setup {:mode :random}}))]
  (doseq [[i name] (map-indexed vector ["Ben" "Cleo" "Dara" "Eli"])]
    (store/transact! service (token (str (nth "bcde" i))) {:event :join :id id :revision i :name name}))
  (with-redefs [store/randomize-seats identity]
    (store/transact! service (token "a") {:event :start :id id :revision 4}))
  (println id))
