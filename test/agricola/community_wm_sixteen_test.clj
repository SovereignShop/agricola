(ns agricola.community-wm-sixteen-test
  (:require [clojure.test :refer :all] [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [agricola.action-board :as board] [eurozone.card-scenarios :as s]
            [agricola.bot :as bot] [eurozone.store :as store] [agricola.recording :as recording])
  (:import [java.nio.file Files]))
(defn table [id] (s/initial-state id (:names (s/options id "Ada"))))
(defn resolve! [state choice] (g/command state (g/acting-player state) (merge {:type :resolve :decision (:decision-id state)} choice)))
(def branch :catalog/original-expansion-wm-7-branch-collector)
(def contractor :catalog/original-expansion-wm-11-contractor)
(def rotator :catalog/original-expansion-wm-14-crop-rotator)
(def forest :catalog/original-expansion-wm-23-forest-farmer)
(def nursery :catalog/original-expansion-wm-57-animal-nursery)
(def back :catalog/original-expansion-wm-61-back-door)
(def donkey :catalog/original-expansion-wm-74-donkey)
(def ram :catalog/original-expansion-wm-102-ram)
(def review-commands
  {"branch-collector" [{:type :action :space :build :rooms [0]}]
   "contractor" [{:type :action :space :build :rooms [0] :contractor-resource :wood}]
   "crop-rotator" [{}]
   "forest-farmer" [{:option 0} {:action-command {:type :action :space :wood}}]
   "godfather" [{:type :action :space :improvement :improvement :fireplace-2}]
   "animal-nursery" [{:type :breed :offspring {:sheep 1} :nursery {:sheep 1}}]
   "back-door" [{:type :action :space :meeting :card back} {:action-command {:type :action :space :growth}}]
   "donkey" [{:cell 1} {:type :action :space :plow :cell 2} {:cells [3]}]
   "fish-hook" [{}]
   "ram" [{:discard? false}]})
(defn command [state choice] (if (:type choice) (g/command state (g/acting-player state) choice) (resolve! state choice)))
(deftest construction-receipts-and-substitutions
  (let [p (g/p (table "branch-collector") 0) q (a/build-rooms p [0])]
    (is (= (inc (a/amount p :wood)) (a/amount q :wood)))
    (is (= (- (a/amount p :clay) 5) (a/amount q :clay)))
    (is (= (a/amount p :wood) (a/amount (a/build-rooms p [0] {} {:free? true}) :wood)))
    (is (= (a/amount p :wood) (a/amount (a/build-rooms p [0] {} {:room-discount {:clay 5}}) :wood))))
  (let [p (g/p (table "contractor") 0) q (a/build-rooms p [0] {:contractor-resource :wood})]
    (is (zero? (a/amount q :wood)))
    (is (= (dec (a/amount p :food)) (a/amount q :food)))
    (is (thrown? clojure.lang.ExceptionInfo (a/build-rooms p [0] {:contractor-resource :stone})))
    (is (some #(= :wood (:contractor-resource %)) (a/room-payment-choices p))))
  (let [p (-> (g/p (table "branch-collector") 0) (update :occupations conj contractor))]
    (is (= {:clay 0 :food 1} (a/contractor-cost p {:clay 1} {:contractor-resource :clay})))
    (is (= (a/amount p :wood) (a/amount (a/branch-collector-gain p [(a/contractor-cost p {:clay 1} {:contractor-resource :clay})]) :wood))))
  (let [p (-> (g/p (table "contractor") 0) (a/gain {:wood 10 :reed 10}))
        q (a/build-rooms p [0 1] {:room-options {0 {:contractor-resource :wood} 1 {:contractor-resource :reed}}})]
    (is (= (- (a/amount p :food) 2) (a/amount q :food)))
    (is (= (- (a/amount p :wood) 9) (a/amount q :wood)))
    (is (= (- (a/amount p :reed) 3) (a/amount q :reed)))))
(deftest crop-rotation-native-play-and-removal
  (let [x (table "crop-rotator") yes (resolve! x {}) no (resolve! x {:skip? true})]
    (is (= :wm-rotate (get-in x [:effect-queue 0 :effect :op])))
    (is (= :vegetable (get-in yes [:players 0 :farm 0 :crop])))
    (is (= 2 (get-in yes [:players 0 :farm 0 :count])))
    (is (zero? (get-in no [:players 0 :farm 0 :count])))
    (is (empty? (:effect-queue (g/drain no))))
    (let [pending (-> yes (update-in [:players 0] a/remove-field-crop 0 :vegetable)
                      (update-in [:players 0] a/remove-field-crop 0 :vegetable) g/drain)]
      (is (= :grain (get-in pending [:effect-queue 0 :effect :crop])))))
  (let [x (-> (table "branch-collector") (s/played 0 :catalog/original-expansion-g-49-seed-master) (s/played 0 :catalog/original-base-e-18-bean-field)
              (update-in [:players 0] a/sow [{:cell :catalog/original-base-e-18-bean-field :crop :vegetable}])
              (assoc-in [:players 0 :card-fields :catalog/original-base-e-18-bean-field :count] 1)
              (s/played 0 rotator))]
    (is (empty? (:effect-queue x)) "Cannot rotate grain into a vegetable-only card field.")))
(deftest fishing-and-growth-adjacency
  (let [x (table "fish-hook") before (a/amount (g/p x 0) :food) yes (resolve! x {}) no (resolve! x {:skip? true})]
    (is (= (inc before) (a/amount (g/p yes 0) :food)))
    (is (= before (a/amount (g/p no 0) :food)))
    (is (= (dec (get-in x [:piles :fishing :food])) (get-in yes [:piles :fishing :food]))))
  (let [x (table "godfather") adjacent (first (remove #{:growth} (board/adjacent-spaces x :growth)))]
    (is (= (inc (a/amount (g/p x 0) :grain)) (a/amount (g/before-use-gains x 0 adjacent) :grain)))
    (is (= (a/amount (g/p x 0) :grain) (a/amount (g/before-use-gains (assoc x :occupied {}) 0 adjacent) :grain)))))
(deftest donor-pasture-plow-and-marker-moves
  (let [x (table "donkey") y (resolve! x {:cell 1}) z (reduce command y [{:type :action :space :plow :cell 2} {:cells [3]}])]
    (is (= {:clay 1} (get-in y [:players 0 :farm 1 :marker-goods])))
    (is (not-any? #(some #{1} (:cells %)) (a/accommodations (g/p y 0))))
    (is (= #{2 3} (set (a/tiles (g/p z 0) :field))))
    (is (not (contains? (get-in z [:players 0 :card-state]) nil)))
    (let [move (g/command y 0 {:type :ability :card donkey}) moved (resolve! move {:cell 0})]
      (is (= {:clay 1} (get-in moved [:players 0 :farm 0 :marker-goods])))
      (is (nil? (get-in moved [:players 0 :farm 1 :marker-goods]))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! x {:cell 2})))
    (is (= 3 (a/animal-farmer-points (-> (g/p y 0) (a/gain {:sheep 2})))))))
(deftest nursery-newborns-only-and-scoring
  (let [x (table "animal-nursery") y (g/command x 0 {:type :breed :offspring {:sheep 1} :nursery {:sheep 1}}) p (g/p y 0)]
    (is (= 2 (a/amount p :sheep)))
    (is (= 1 (get-in p [:card-state nursery :stored-goods :sheep])))
    (is (= 3 (binding [a/*scoring-animals?* true] (a/amount p :sheep))))
    (is (thrown? clojure.lang.ExceptionInfo (g/command x 0 {:type :breed :offspring {} :nursery {}})))
    (is (thrown? clojure.lang.ExceptionInfo (g/command x 0 {:type :breed :offspring {:sheep 1} :nursery {:sheep 2}})))))
(deftest ram-food-breeding-and-nonfood-virtual-sheep
  (let [x (table "ram") y (resolve! x {:discard? false}) no (resolve! x {:discard? true}) p (g/p y 0)]
    (is (= (dec (a/amount (g/p x 0) :food)) (a/amount p :food)))
    (is (= 1 (a/amount p :sheep)))
    (is (= 2 (binding [a/*scoring-animals?* true] (a/amount p :sheep))))
    (is (= {:sheep 1} (a/breeding-options p)))
    (is (not (some #{ram} (c/owned (g/p no 0)))))
    (is (= {} (a/breeding-options (g/p no 0))))))
(deftest prepared-decisions-have-legal-bot-options
  (doseq [id ["crop-rotator" "forest-farmer" "donkey" "fish-hook" "ram"]]
    (let [x (table id)]
      (is (some (fn [cmd] (try (g/command x (g/acting-player x) cmd) true (catch Exception _ false)))
                (bot/decision-candidates x (g/acting-player x))) id))))
(deftest ten-tables-resolve-persist-and-replay
  (doseq [[scenario commands] review-commands]
    (let [path (str (.resolve (Files/createTempDirectory "wm-sixteen" (make-array java.nio.file.attribute.FileAttribute 0)) "games.edn"))
          service (store/make-store path)
          id (:id (store/transact! service "tester" {:event :create :name "Ada" :scenario scenario}))]
      (doseq [choice commands]
        (let [room (get @(:rooms service) id) state (:state room)
              cmd (if (:type choice) choice (merge {:type :resolve :decision (:decision-id state)} choice))]
          (store/transact! service "tester" {:event :command :id id :revision (:revision room) :command cmd})))
      (let [room (get @(:rooms service) id)]
        (is (= (:state room) (get-in @(:rooms (store/make-store path)) [id :state])) scenario)
        (is (= (:state room) (last (:frames (recording/timeline (:recording room))))) scenario)))))

(deftest back-door-requires-a-real-remaining-worker-and-revealed-growth
  (let [x (table "back-door") y (command x {:type :action :space :meeting :card back})
        z (resolve! y {:action-command {:type :action :space :growth}})]
    (is (= :extra-action (get-in y [:effect-queue 0 :effect :op])))
    (is (= 1 (get-in y [:players 0 :remaining])))
    (is (= 3 (get-in z [:players 0 :family])))
    (is (zero? (get-in z [:players 0 :remaining])))
    (is (= #{0 1} (set (g/space-owners z :growth))))
    (is (some #{back} (get-in z [:players 1 :hand :minor])))
    (is (thrown? clojure.lang.ExceptionInfo (command (assoc-in x [:players 0 :remaining] 1) {:type :action :space :meeting :card back})))
    (is (thrown? clojure.lang.ExceptionInfo (command (update x :active #(vec (remove #{:growth} %))) {:type :action :space :meeting :card back})))))
(deftest forest-farmer-pays-only-for-committed-free-space-relocation
  (let [x (table "forest-farmer") y (resolve! x {:option 0})
        no (resolve! y {:skip? true}) yes (resolve! y {:action-command {:type :action :space :wood}})]
    (is (= 3 (get-in no [:players 0 :card-state forest :markers])))
    (is (= 2 (get-in yes [:players 0 :card-state forest :markers])))
    (is (= (+ 2 (a/amount (g/p x 0) :wood)) (a/amount (g/p yes 0) :wood)))
    (is (not (contains? (:occupied yes) :plow)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! y {:action-command {:type :action :space :plow :cell 1}})))))
(deftest ram-additional-births-are-mandatory-when-housed-and-nursery-can-store
  (let [x (-> (table "branch-collector") (s/prepared-round 6)
              (update-in [:players 0] a/build-fences (a/edges 0))
              (update-in [:players 0] a/gain {:sheep 2}) (s/played 0 ram) s/last-harvest-action)
        y (resolve! x {:nursery? false})]
    (is (= :wm-ram-breed (get-in x [:effect-queue 0 :effect :op])))
    (is (= 2 (a/amount (g/p y 0) :sheep)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! x {:skip? true})))
    (let [with-nursery (update-in x [:players 0 :minors] conj nursery)
          z (resolve! with-nursery {:nursery? true})]
      (is (= 1 (a/amount (g/p z 0) :sheep)))
      (is (= 1 (get-in z [:players 0 :card-state nursery :stored-goods :sheep]))))))
(deftest optional-declines-persist-and-replay
  (doseq [scenario ["crop-rotator" "forest-farmer" "fish-hook"]]
    (let [path (str (.resolve (Files/createTempDirectory "wm-sixteen-decline" (make-array java.nio.file.attribute.FileAttribute 0)) "games.edn"))
          service (store/make-store path) id (:id (store/transact! service "tester" {:event :create :name "Ada" :scenario scenario}))
          before (get-in @(:rooms service) [id :state])]
      (store/transact! service "tester" {:event :command :id id :revision 0 :command {:type :resolve :decision (:decision-id before) :skip? true}})
      (let [room (get @(:rooms service) id)]
        (is (= (:state room) (get-in @(:rooms (store/make-store path)) [id :state])) scenario)
        (is (= (:state room) (last (:frames (recording/timeline (:recording room))))) scenario)))))
(deftest nursery-birth-counts-for-bountiful-harvest
  (let [x (-> (table "animal-nursery") (update-in [:players 0 :minors] conj :catalog/original-expansion-g-67-bountiful-harvest)
              (assoc-in [:players 0 :g-harvest-count] 2))
        y (g/command x 0 {:type :breed :offspring {:sheep 1} :nursery {:sheep 1}})]
    (is (= 1 (get-in y [:players 0 :card-state :catalog/original-expansion-g-67-bountiful-harvest :stored-goods :food])))))
