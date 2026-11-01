(ns eurozone.card-scenarios-test
  (:require [clojure.test :refer :all] [eurozone.card-scenarios :as scenarios]
            [eurozone.store :as store] [agricola.engine :as a]
            [agricola.card-game :as game] [agricola.recording :as recording])
  (:import [java.nio.file Files]))

(deftest swing-plow-table-resolves-declines-persists-and-replays
  (doseq [skip? [false true]]
    (let [path (str (.resolve (Files/createTempDirectory "card-scenario" (make-array java.nio.file.attribute.FileAttribute 0)) "games.edn"))
          service (store/make-store path)
          original (:id (store/transact! service "owner" {:event :create :name "Ada"}))
          before @(:rooms service)
          id (:id (store/transact! service "tester" {:event :create :scenario "swing-plow" :name "Partner"
                                                    :bots 4 :turn-seconds 30 :mode :online}))
          room (get @(:rooms service) id) pending (:state room)
          head (first (:effect-queue pending))
          command (merge {:type :resolve :decision (:decision-id pending)}
                         (if skip? {:skip? true} {:plow-card (:card head) :cells [1 2]}))]
      (is (= (get before original) (get @(:rooms service) original)))
      (is (= :local (:mode room)))
      (is (= ["Partner" "Neighbor"] (mapv :name (:seats room))))
      (is (= ["tester" "tester"] (mapv :owner (:seats room))))
      (is (= 0 (:turn-seconds room)))
      (is (= :catalog/original-base-k-115-swing-plow (:card head)))
      (is (= pending (get-in @(:rooms (store/make-store path)) [id :state])))
      (is (true? (get-in room [:recording 0 :partial-start?])))
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/transact! service "intruder" {:event :command :id id :revision 0 :command command})))
      (store/transact! service "tester" {:event :command :id id :revision 0 :command command})
      (let [after (get @(:rooms service) id)]
        (is (= (if skip? #{0} #{0 1 2}) (set (a/tiles (get-in after [:state :players 0]) :field))))
        (is (= (if skip? 2 1) (get-in after [:state :players 0 :card-state (:card head) :uses])))
        (is (= (:state after) (last (:frames (recording/timeline (:recording after))))))
        (is (= 1 (game/acting-player (:state after))))))))

(deftest only-registered-prepared-positions-are-accepted
  (is (thrown? clojure.lang.ExceptionInfo (store/transition {} "tester" {:event :create :name "Ada" :scenario "unknown"})))
  (doseq [{:keys [id card]} scenarios/scenarios]
    (is (= card (get-in (scenarios/initial-state id ["Ada" "Ben"]) [:effect-queue 0 :card])))))
