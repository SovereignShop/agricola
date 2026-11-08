(ns agricola.community-sixteen-fr-test
 (:require [clojure.test :refer :all] [agricola.engine :as a] [agricola.card-game :as g]
 [agricola.card-rules :as c] [agricola.fr-sixteen :as fr] [agricola.bot :as bot]
 [eurozone.card-scenarios :as s] [eurozone.card-scenarios-test :as st]))
(def ids ["necklace" "prefect" "prosecutor" "tower-builder" "timetable" "cat-lover" "sunday-school" "park-cemetery" "fencing-master" "pear-peeler"])
(defn table [id] (s/initial-state id (:names (s/options id "Ada"))))
(defn resolve! [state choice] (g/command state (g/acting-player state) (merge {:type :resolve :decision (:decision-id state)} choice)))
(defn reviewed [id] (reduce (fn [state cmd] (if (:type cmd) (g/command state (g/acting-player state) cmd) (resolve! state cmd))) (table id) (st/review-commands id)))
(deftest ten-native-scenarios-resolve
 (doseq [id ids] (is (map? (reviewed id)) id)))
(deftest necklace-awards-real-board-adjacency
 (let [state (table "necklace") before (a/amount (g/p state 0) :food) after (reviewed "necklace")]
  (is (= (inc before) (a/amount (g/p after 0) :food)))
  (is (not (fr/necklace? (assoc-in state [:players 0 :worker-spaces] [:grain :fishing]) 0)))
  (is (not (fr/necklace? (-> state (assoc-in [:players 0 :worker-spaces] [:grain :plow]) (assoc-in [:players 0 :newborn] 1)) 0)))))
(deftest prefect-arrives-before-feeding-and-distinct-card-per-harvest
 (let [state (-> (table "prefect") (assoc-in [:players 0 :goods :food] 1))
       after (g/command state 0 {:type :feed :craft [:pottery]})]
  (is (zero? (get-in after [:players 0 :begging])))
  (is (zero? (a/amount (g/p after 0) :food)))
  (is (contains? (:prefect-conversions after) [4 0 :pottery]))
  (is (= 0 (second (fr/prefect-receipts after [[0 {:round 4 :phase :feeding :recipe-card :pottery :goods {:clay 1}}]]))))
  (is (= 1 (second (fr/prefect-receipts after [[1 {:round 4 :phase :feeding :recipe-card :joinery :goods {:wood 1}}]]))))
  (is (= 0 (second (fr/prefect-receipts after [[1 {:round 4 :phase :work :recipe-card :joinery :goods {:wood 1}}]]))))))
(deftest prosecutor-updates-discount-from-relative-improvements
 (let [state (table "prosecutor") after (reviewed "prosecutor")]
  (is (= 2 (get-in state [:players 0 :prosecutor-discount])))
  (is (= (a/amount (g/p state 0) :wood) (a/amount (g/p after 0) :wood)))
  (is (= (a/amount (g/p state 0) :clay) (a/amount (g/p after 0) :clay)))
  (is (zero? (get-in (fr/refresh (assoc state :players [(g/p state 0)])) [:players 0 :prosecutor-discount])))))
(deftest tower-builder-free-room-or-decline
 (let [state (table "tower-builder") pending (g/command state 0 {:type :action :space :build :rooms [0]})]
  (is (= 4 (count (a/tiles (g/p (reviewed "tower-builder") 0) :room))))
  (is (= 3 (count (a/tiles (g/p (resolve! pending {:skip? true}) 0) :room))))
  (is (some :rooms (bot/decision-candidates pending 0)))
  (is (not (fr/tower? (assoc-in state [:players 1 :farm 0] {:type :empty}) 0 {:rooms-before 3 :space :build})))
  (is (not (fr/tower? state 0 {:rooms-before 2 :space :meeting})))))
(deftest timetable-stock-pays-only-minors
 (let [state (table "timetable") p (g/p state 0) after (reviewed "timetable") cid a/timetable-id]
  (is (= {:reed 1 :stone 1 :wood 0 :clay 0} (get-in after [:players 0 :card-state cid :stored-goods])))
  (is (zero? (a/amount (g/p after 0) :wood)))
  (is (thrown? clojure.lang.ExceptionInfo (g/command state 0 {:type :action :space :meeting :card :catalog/original-expansion-g-110-shovel :timetable? false})))
  (is (= {:wood 1 :clay 1} (select-keys (g/card-cost state 0 (c/card :catalog/original-expansion-g-110-shovel) 0 {:as-major? true}) [:wood :clay])))
  (is (= 0 (a/amount p :wood)))))
(deftest cat-lover-per-room-choice-and-animal-thresholds
 (let [state (table "cat-lover") p (g/p state 0) after (reviewed "cat-lover")]
  (is (= 3 (a/cat-lover-discount p)))
  (is (= (- (a/amount p :wood) 4) (a/amount (g/p after 0) :wood)))
  (is (= (a/amount p :reed) (a/amount (g/p after 0) :reed)))
  (is (thrown? clojure.lang.ExceptionInfo (a/room-cost p {:cat-lover-resources {:wood 4}})))
  (is (zero? (a/cat-lover-discount (assoc p :house :clay)))))
 (doseq [[n discount] [[0 0] [1 1] [2 2] [3 2] [4 3] [6 3] [7 4]]]
  (is (= discount (a/cat-lover-discount (assoc-in (g/p (table "cat-lover") 0) [:goods :sheep] n))))))
(deftest sunday-school-owner-and-free-occupation-workflow
 (let [state (table "sunday-school") after (reviewed "sunday-school")]
  (is (= :return-home (:phase state)))
  (is (not (c/has? (g/p state 0) fr/school)))
  (is (some #{fr/school} (get-in state [:players 1 :hand :minor])))
  (is (c/has? (g/p after 0) :catalog/original-base-e-172-chief))
  (is (c/has? (g/p after 0) :catalog/original-base-e-174-tutor))
  (is (= 3 (:round after)))
  (let [declined (-> state (resolve! {:skip? true}) (resolve! {:skip? true}))]
   (is (= :work (:phase declined))) (is (= 2 (get-in declined [:players 0 :remaining]))))))
(deftest park-cemetery-shared-stone-and-direct-discard-plow
 (let [state (table "park-cemetery") after (reviewed "park-cemetery")]
  (is (= 2 (:park-stone after)))
  (is (= (inc (a/amount (g/p state 0) :stone)) (a/amount (g/p after 0) :stone)))
  (is (= 3 (:park-stone (resolve! state {:skip? true}))))
  (is (thrown? clojure.lang.ExceptionInfo (g/command after 0 {:type :ability :card fr/park :cell 1})))
  (let [empty (assoc after :park-stone 0) plowed (g/command empty 0 {:type :ability :card fr/park :cell 1})]
   (is (= #{0 1} (set (a/tiles (g/p plowed 0) :field))))
   (is (not (c/has? (g/p plowed 0) fr/park)))
   (is (some :cell (filter #(= fr/park (:card %)) (bot/ability-candidates empty 0)))))))
(deftest fencing-master-reserves-then-spends-only-on-fences-action
 (let [state (table "fencing-master") after (reviewed "fencing-master") p (g/p after 0)
       built (a/build-fences p (a/edges 0))]
  (is (= 2 (get-in p [:reserved-fences :fr-fencing-master])))
  (is (= 13 (a/fences-available p)))
  (is (= (- (a/amount p :wood) 2) (a/amount built :wood)))
  (is (zero? (get-in built [:reserved-fences :fr-fencing-master])))
  (is (= (- (a/amount p :wood) 4) (a/amount (a/build-fences p (a/edges 0) {:not-fence-action? true}) :wood)))
  (is (zero? (get-in (resolve! state {:skip? true}) [:players 0 :reserved-fences :fr-fencing-master] 0)))))
(deftest pear-peeler-leaves-actual-wood-on-space
 (let [state (table "pear-peeler") p (g/p state 0) after (reviewed "pear-peeler")]
  (is (= 3 (get-in after [:piles :wood :wood])))
  (is (= (- (a/amount p :wood) 3) (a/amount (g/p after 0) :wood)))
  (is (= (inc (a/amount p :grain)) (a/amount (g/p after 0) :grain)))
  (is (= (inc (a/amount p :vegetable)) (a/amount (g/p after 0) :vegetable)))
  (is (= (a/amount p :wood) (a/amount (g/p (resolve! state {:skip? true}) 0) :wood)))
  (is (seq (bot/decision-candidates state 0)))))
(deftest timetable-can-preserve-each-resource-independently
 (let [p (g/p (table "timetable") 0)]
  (is (= [{:wood 0 :clay 1} {:wood 1}] (a/timetable-plan p {:wood 1 :clay 1} {:timetable-resources {:wood 1}})))
  (is (= [{:wood 1 :clay 1} {}] (a/timetable-plan p {:wood 1 :clay 1} {:timetable? false})))
  (is (= 0 (:wood (first (a/timetable-plan p {:wood 1} {})))))))
(deftest pear-peeler-cannot-return-wood-already-returned-by-another-card
 (let [state (table "pear-peeler") action-id (get-in state [:effect-queue 0 :context :action-id])
       used (assoc-in state [:action-returned-goods action-id :wood] 2)]
  (is (thrown? clojure.lang.ExceptionInfo (resolve! used {:option 2})))
  (is (map? (resolve! used {:option 0})))))
