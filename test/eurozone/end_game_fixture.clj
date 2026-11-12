(require '[eurozone.store :as store] '[agricola.engine :as a] '[agricola.recording :as recording] '[clojure.data.json :as json])
(let [s (store/make-store (System/getenv "EUROZONE_DATA"))
      host (apply str (repeat 64 "a")) guest (apply str (repeat 64 "b"))
      create #(-> (store/transact! s host {:event :create :mode :local :name "Ada" :names ["Ada" "Ben"]
                                          :title %}) :id)
      local (create "Local last harvest") online (create "Online last harvest")
      state (assoc (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0)
                   :round 14 :phase :breeding :turn 1 :fed #{0 1} :bred #{0})
      prepare #(assoc % :state state :recording [(store/recording-header % state true)])
      rooms (-> @(:rooms s) (update local prepare) (update online prepare)
                (assoc-in [online :mode] :online) (assoc-in [online :seats 1 :owner] guest))]
  (store/save! s rooms)
  (println (json/write-str {:local local :online online
                           :unfinished-hash (recording/state-hash state)
                           :recording (:text (store/export-recording
                                               (assoc s :rooms (atom {online (store/recorded-command (rooms online) 1 {:type :breed :offspring {}})}))
                                               host online))
                           :peer (pr-str {:rules "cf9b4346d2ccd0b08ebc1d4da13bf18ec92de3f9c1e35458138dad149607a3d9" :seen []
                                          :table (assoc (rooms online) :id "__ROOM__")})})))
