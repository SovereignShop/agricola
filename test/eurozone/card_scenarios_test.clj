(ns eurozone.card-scenarios-test
  (:require [clojure.test :refer :all] [eurozone.card-scenarios :as scenarios]
            [eurozone.store :as store] [agricola.engine :as a] [agricola.card-rules :as cards]
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
    (let [state (scenarios/initial-state id (:names (scenarios/options id "Ada")))
          player (game/p state 0)]
      (is (some #{card} (concat (cards/owned player) (mapcat val (:hand player)))) id)
      (is (= (:players (scenarios/scenario id) 2) (count (:players state)))))))

(def review-commands
  {"carrot-farmer" [{}]
   "pulley" [{:rooms [0]}]
   "food-connoisseur" [{:type :action :space :fishing}]
   "oak-cask" [{:type :action :space :grain} {:type :ability :card :catalog/original-expansion-wm-96-oak-cask}]
   "child-care-worker" [{:option 1}]
   "oceanographer" [{}]
   "sheep-market" [{:option 2}]
   "wooden-bridge" [{:type :action :space :reed}]
   "edible-roots" [{:type :action :space :grain}]
   "festival-hall" [{:option 3}]
   "abandoned-willow" [{:cell 0}]
   "powerhouse" [{:type :action :space :grain}]
   "powerhouse-politician" [{:type :ability :card :catalog/original-expansion-wm-41-politician :option 0} {}]
   "seed-spreader" [{} {:plantings [{:cell 1 :crop :vegetable}]}]
   "vegetable-scoop" [{:type :action :space :vegetable}]
   "diamond-mine" [{:type :action :space :meeting :card :catalog/original-expansion-wm-73-diamond-mine}]
   "encircling-wall" [{:cell 1}]
   "stone-house-reconstruction" [{:type :ability :card :catalog/original-expansion-fr-45-stone-house-reconstruction}]
   "businesswoman" [{:improvement :fireplace-2} {:skip? true}]
   "singer" [{:resources [:reed :reed]}]
   "gardening-tools" [{} {:plantings [{:cell 0 :crop :grain}]}]
   "shovel" [{} {:plantings [{:cell 0 :crop :vegetable}]}]
   "debris-dealer" [{:type :action :space :renovate}]
   "herdsman" [{:resource :boar}]
   "ruins" [{}]
   "steamer" [{:type :ability :card :catalog/original-expansion-wm-108-steamer :option 1} {:amount 1}]
   "baguette" [{:grain 1}]
   "barber-shop" [{:type :action :space :meeting :card :catalog/original-expansion-fr-8-barber-shop}]
   "homework" [{:card :catalog/original-expansion-g-49-seed-master} {:skip? true}]
   "trailblazer" [{:cell 1}]
   "foreman" [{:space :grain}]
   "fence-builder" [{:space :wood}]
   "fence-overseer" [{:cell 0}]
   "animal-breeder" [{:animal :boar}]
   "resource-seller" [{:option 0}]
   "silage" [{:grain-source 2 :animal :sheep}]
   "stable-planner" [{:rounds [4 7]}]
   "harvest-helper" [{:owner 1 :cell 0}]
   "hide-farmer" [{:amount 2}]
   "clearing-spade" [{:type :ability :card :revised-base/a071 :from 0 :to :catalog/original-base-e-18-bean-field}]
   "asparagus-knife" [{:cell 0}]
   "work-certificate" [{:space :wood :resource :wood}]
   "stagehand" [{:option 1} {:stables [1 2]}]
   "cottager" [{:action :room :cell 0}]
   "copse" [{:type :action :space :sow-bake :plantings [{:cell :catalog/original-base-i-78-copse :crop :wood}
                                                       {:cell :catalog/original-base-i-78-copse-2 :crop :wood}]}]})

(deftest community-tables-resolve-decline-save-and-replay
  (doseq [[scenario commands] review-commands
          decline? [false true]]
    (let [path (str (.resolve (Files/createTempDirectory "community-scenario" (make-array java.nio.file.attribute.FileAttribute 0)) "games.edn"))
          service (store/make-store path)
          id (:id (store/transact! service "tester" {:event :create :name "Ada" :scenario scenario}))
          initial (get @(:rooms service) id)
          optional? (get-in initial [:state :effect-queue 0 :effect :optional?])
          commands (if (and decline? optional?)
                     (repeat (if (#{"businesswoman" "homework"} scenario) 2 1) {:skip? true}) commands)]
      (is (= scenario (:scenario initial)))
      (is (= scenario (get-in @(:rooms (store/make-store path)) [id :scenario])))
      (is (= (:state initial) (get-in @(:rooms (store/make-store path)) [id :state])) scenario)
      (doseq [choice commands]
        (let [room (get @(:rooms service) id) state (:state room)
              command (if (:type choice) choice (merge {:type :resolve :decision (:decision-id state)} choice))]
          (store/transact! service "tester" {:event :command :id id :revision (:revision room) :command command})))
      (let [room (get @(:rooms service) id)]
        (is (= (:state room) (get-in @(:rooms (store/make-store path)) [id :state])) scenario)
        (is (= (:state room) (last (:frames (recording/timeline (:recording room))))))
        (is (not= (:state initial) (:state room)) scenario)))))
