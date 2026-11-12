;; Native command recordings for cross-runtime browser playback of every final card.
(require '[agricola.community-final-test :as final]
         '[eurozone.store :as store]
         '[clojure.data.json :as json])
(import '[java.nio.file Files])
(let [path (str (.resolve (Files/createTempDirectory "final-browser-replays" (make-array java.nio.file.attribute.FileAttribute 0)) "tables.edn"))
      service (store/make-store path)]
  (println
    (json/write-str
      (mapv (fn [[id commands]]
              (let [table (:id (store/transact! service "reviewer" {:event :create :scenario id :name "Ada"}))]
                (doseq [choice commands]
                  (let [room (get @(:rooms service) table) state (:state room)]
                    (store/transact! service "reviewer"
                      {:event :command :id table :revision (:revision room)
                       :command (merge {:type :resolve :decision (:decision-id state)}
                                       (cond-> choice (= :ability (:type choice)) (assoc :player 0)))})))
                {:id id :moves (count commands) :text (:text (store/export-recording service "reviewer" table))}))
            (sort-by key final/review-commands)))))
