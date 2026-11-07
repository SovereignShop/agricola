(ns agricola.community-fifteen-fr-test
  (:require [clojure.test :refer :all]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.fr-fifteen :as fr] [eurozone.card-scenarios :as s]
            [eurozone.card-scenarios-test :as st] [agricola.bot :as bot]))
(defn table [id] (s/initial-state id (:names (s/options id "Ada"))))
(defn resolve! [state choice] (g/command state (g/acting-player state) (merge {:type :resolve :decision (:decision-id state)} choice)))
(defn reviewed [id] (reduce (fn [state cmd] (if (:type cmd) (g/command state (g/acting-player state) cmd) (resolve! state cmd))) (table id) (st/review-commands id)))
(defn own [p cid] (update p (if (= :minor (:kind (c/card cid))) :minors :occupations) (fnil conj []) cid))

(deftest grotto-collects-only-one-piece-and-becomes-family-capacity
  (let [state (table "grotto") cid (:card (s/scenario "grotto")) player (g/p state 0)
        after (resolve! state {:option 0})]
    (is (= 2 (a/family-capacity player)))
    (is (= 2 (get-in after [:players 0 :card-state cid :stored-goods :clay])))
    (is (= (- (a/amount player :food) 2) (a/amount (g/p after 0) :food)))
    (is (= 2 (count (a/tiles (assoc-in player [:card-state cid :stored-goods] {}) :room))))
    (is (= 3 (a/family-capacity (assoc-in player [:card-state cid :stored-goods] {}))))
    (is (= 2 (a/family-capacity (assoc (assoc-in player [:card-state cid :stored-goods] {}) :minors []))))))

(deftest threshing-machine-requires-the-exact-three-cells
  (let [state (table "threshing-machine-plow") cid (:card (s/scenario "threshing-machine-plow"))]
    (is (thrown? clojure.lang.ExceptionInfo (resolve! state {:plow-card cid :cells [1 2]})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! state {:plow-card cid :cells [7]})))
    (let [after (reviewed "threshing-machine-plow")]
      (is (= #{6 7 8} (set (a/tiles (g/p after 0) :field))))
      (is (zero? (get-in after [:players 0 :card-state cid :uses]))))
    (is (= [6] (a/tiles (g/p (resolve! state {:skip? true}) 0) :field)))
    (is (not (fr/exact-plow-available? (a/place-tile (g/p state 0) :field 7) [6 7 8])))
    (is (fr/exact-plow-available? (a/place-tile (g/p state 0) :field 9) [6 7 8]))
    (is (empty? (:plows (g/plow-options state 0 :cultivation))))
    (is (some #(= [7 8] (:cells %)) (bot/decision-candidates state 0)))))

(deftest wood-saw-needs-larger-opponents-and-does-not-use-miser
  (let [state (table "wood-saw") player (g/p state 0) cid (:card (s/scenario "wood-saw"))
        after (reviewed "wood-saw")]
    (is (= 3 (count (a/tiles (g/p after 0) :room))))
    (is (= (:remaining player) (get-in after [:players 0 :remaining])))
    (let [base (resolve! state {:skip? true})
          built (g/command base 0 {:type :ability :card cid :rooms [0]})]
      (is (= 3 (count (a/tiles (g/p built 0) :room))))
      (is (empty? (:effect-queue built)))
      (is (some :rooms (filter #(= cid (:card %)) (bot/ability-candidates base 0)))))
    (is (not (fr/wood-saw-available? (assoc state :players [player]) 0)))
    (is (not (fr/wood-saw-available? (assoc-in state [:players 1 :family] 2) 0)))
    (let [with-miser (update-in state [:players 0] own :catalog/original-expansion-fr-94-miser)
          after (resolve! with-miser {:rooms [0]})]
      (is (= (- (a/amount player :wood) 5) (a/amount (g/p after 0) :wood))))))

(deftest convict-blocks-a-distinct-person-next-round-and-still-feeds
  (let [state (table "convict-number-24601") player (g/p state 0)]
    (is (= 2 (:family player))) (is (= 1 (:remaining player)))
    (is (= 0 (g/placed-people state 0)))
    (is (= 6 (g/card-bonus state 0 fr/convict)))
    (is (= state (fr/apply-convict state)))
    (let [blocked (fr/apply-convict (-> state (assoc-in [:players 0 :remaining] 1)
                                       (update-in [:players 0] dissoc :convict-blocked-round)))]
      (is (zero? (get-in blocked [:players 0 :remaining]))))))

(deftest cowboy-mother-direct-plow-and-growth-exclusion
  (let [state (table "cowboy-and-mother") after (reviewed "cowboy-and-mother")]
    (is (= :choose (get-in state [:effect-queue 0 :effect :op])))
    (is (= [0] (a/tiles (g/p after 0) :field)))
    (let [grown (-> (table "miser") (s/prepared-round 12)
                    (update-in [:players 0] a/build-rooms [0] {} {:free? true})
                    (update-in [:players 0] a/grow true) (s/played 0 fr/cowboy-mother)
                    s/final-worker (g/command 0 {:type :action :space :grain}))]
      (is (not= fr/cowboy-mother (get-in grown [:effect-queue 0 :card]))))))

(deftest good-friend-uses-actual-paid-resources-including-major-as-minor
  (let [state (table "good-friend") cid fr/good-friend before (g/p state 0)
        after (resolve! state {:option 0})]
    (is (= (inc (a/amount before :wood)) (a/amount (g/p after 0) :wood)))
    (is (= 2 (get-in after [:players 0 :card-state cid :stored-goods :wood])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! state {:option 2})))
    (let [base (-> (resolve! state {:skip? true})
                   (s/played 1 :catalog/original-expansion-fr-23-goblet) (resolve! {:skip? true}) (assoc :turn 1))
          major (g/command base 1 {:type :action :space :meeting :improvement :well})]
      (is (= cid (get-in major [:effect-queue 0 :card])))
      (is (= {:wood 1 :stone 3} (get-in major [:effect-queue 0 :context :paid]))))
    (let [base (-> (resolve! state {:skip? true}) (assoc :turn 1) (s/prepared-round 5))
          major (g/command (assoc base :turn 1) 1 {:type :action :space :improvement :improvement :well})]
      (is (not= cid (get-in major [:effect-queue 0 :card]))))))

(deftest miser-discounts-before-substitution-and-only-single-action-room
  (let [state (table "miser") player (g/p state 0) after (reviewed "miser")]
    (is (zero? (a/amount (g/p after 0) :wood))) (is (zero? (a/amount (g/p after 0) :reed)))
    (is (thrown? clojure.lang.ExceptionInfo (a/build-rooms player [0])))
    (let [p (a/gain player {:wood 1 :reed 1}) built (a/build-rooms p [0] {:miser? false} {:action-space? true})]
      (is (zero? (a/amount built :wood))) (is (zero? (a/amount built :reed))))
    (let [p (-> player (own :catalog/original-expansion-g-1-alternative-designer) (a/gain {:clay 4 :stone 4}))
          built (a/build-rooms p [0] {:wood-clay 2 :wood-stone 2} {:action-space? true})]
      (is (= 4 (a/amount built :wood)))
      (is (= (- (a/amount p :clay) 2) (a/amount built :clay))))
    (let [p (a/gain player {:wood 20 :reed 10}) built (a/build-rooms p [0 1] {} {:action-space? true})]
      (is (= 10 (- (a/amount p :wood) (a/amount built :wood))))
      (is (= 4 (- (a/amount p :reed) (a/amount built :reed)))))))

(deftest turkey-breeder-free-pieces-and-nonfeeding-begging-debt
  (let [before (table "turkey-breeder") after (reviewed "turkey-breeder") cid (:card (s/scenario "turkey-breeder"))]
    (is (= (a/amount (g/p before 0) :wood) (a/amount (g/p after 0) :wood)))
    (is (= 4 (count (get-in after [:players 0 :fences]))))
    (is (true? (get-in after [:players 0 :farm 0 :stable])))
    (is (not (c/bonus-symbol? cid)))
    (let [finished (g/drain (g/finish-game (-> after (assoc :round 14) (assoc-in [:players 0 :goods :wood] 1))))]
      (is (= :finished (:phase finished)))
      (is (= 3 (get-in finished [:players 0 :begging])))
      (is (zero? (get-in finished [:players 0 :goods :wood]))))))

(deftest wood-gatherers-counts-earlier-construction-and-not-other-payments
  (let [before (table "wood-gatherers") after (reviewed "wood-gatherers")]
    (is (= 5 (get-in before [:players 0 :construction-wood-spent])))
    (is (= (+ 2 (a/amount (g/p before 0) :wood)) (a/amount (g/p after 0) :wood)))
    (is (zero? (get-in after [:players 0 :construction-wood-spent])))
    (is (= 5 (:construction-wood-spent (a/pay (g/p before 0) {:wood 1}))))
    (is (= 7 (:construction-wood-spent (a/build-stables (g/p before 0) [1] 2))))
    (is (not (contains? (g/p (table "miser") 0) :construction-wood-spent)))))

(deftest workaholic-requires-housed-animals-and-collects-each-pile-once
  (let [state (table "workaholic") cid (:card (s/scenario "workaholic")) player (g/p state 0)
        after (reviewed "workaholic")]
    (is (= (+ 5 (a/amount player :wood)) (a/amount (g/p after 0) :wood)))
    (is (empty? (fr/workaholic-options (g/p after 0) cid)))
    (is (empty? (fr/workaholic-options (a/gain player {:sheep 9}) cid)))
    (let [base (resolve! state {:skip? true})
          claimed (g/command base 0 {:type :ability :card cid :option 0})]
      (is (= (+ 5 (a/amount player :wood)) (a/amount (g/p claimed 0) :wood)))
      (is (empty? (:effect-queue claimed))))
    (is (= 4 (get-in after [:players 0 :card-state cid :stored-goods :clay])))
    (is (= 3 (get-in after [:players 0 :card-state cid :stored-goods :stone])))))
