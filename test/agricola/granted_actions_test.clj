(ns agricola.granted-actions-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot] [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(defn rich [state] (update-in state [:players 0] a/gain {:food 20 :wood 20 :clay 20 :reed 20 :stone 20 :grain 3}))
(defn emit [state event actor context] (g/drain (g/queue-front state [(g/event event actor context)])))
(defn activate [state space] (update state :active conj space))
(defn play [state code] (g/drain (g/play-card (hand state code) 0 (c/id code) 0 {})))

(deftest oven-site-checks-prerequisites-and-uses-discounted-substituted-cost
  (let [state (-> (fresh) rich (hand :a027))]
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 (c/id :a027) 0 {})))
    (doseq [owned [#{:fireplace-2} #{:hearth-4}]]
      (is (thrown? clojure.lang.ExceptionInfo (g/play-card (assoc-in state [:players 0 :improvements] owned) 0 (c/id :a027) 0 {}))))
    (doseq [oven [:clay-oven :stone-oven] discount? [false true]]
      (let [before (cond-> (assoc-in state [:players 0 :improvements] #{:fireplace-2 :hearth-4}) discount? (own 0 :a143))
            pending (g/drain (g/play-card before 0 (c/id :a027) 0 {}))
            after (resolve! (edn/read-string (pr-str pending)) {:improvement oven :bake-grain 1})]
        (is (= 2 (delta before pending :wood)))
        (is (= -1 (delta pending after :clay)))
        (is (= (if discount? 0 -1) (delta pending after :stone)))
        (is (= -1 (delta pending after :grain)))
        (is (pos? (delta pending after :food)))
        (is (contains? (get-in after [:players 0 :improvements]) oven))
        (is (= (:occupied before) (:occupied after)))
        (is (= (get-in before [:players 0 :remaining]) (get-in after [:players 0 :remaining])))
        (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:improvement :well :cost {}})))
        (is (thrown? clojure.lang.ExceptionInfo (resolve! (hand pending :a010) {:card (c/id :a010) :kinds #{:minor}})))))))

(deftest angler-tests-original-fishing-pile-and-grants-major-permission
  (doseq [[food offered?] [[0 true] [1 true] [2 true] [3 false] [4 false]]]
    (let [state (-> (fresh) rich (own 0 :a095) (hand :a010) (assoc-in [:piles :fishing :food] food))
          pending (g/command state 0 {:type :action :space :fishing})]
      (is (= offered? (boolean (seq (:effect-queue pending)))))
      (when offered?
        (let [after (resolve! pending {:card (c/id :a010)})]
          (is (= 3 (a/family-capacity (g/p after 0)))))
        (is (= 1 (get-in pending [:players 0 :remaining])))
        (is (= {:fishing 0} (:occupied pending))))))
  (let [state (-> (g/initialize (a/new-game ["A" "B" "C" "D" "E"] (vec (mapcat identity a/stages)) 0))
                  (own 0 :a095) (own 0 :d180) (assoc-in [:piles :fishing :food] 4))
        after (g/command state 0 {:type :action :space :fishing :part-time? true})]
    (is (not-any? #(= (c/id :a095) (:card %)) (:effect-queue after)))))

(deftest task-artisan-grants-minor-only-on-play-and-stone-reveal
  (let [state (-> (fresh) rich (hand :a010)) pending (play state :a096)]
    (is (= 1 (delta state pending :wood)))
    (is (= :card-action (get-in pending [:effect-queue 0 :effect :op])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:improvement :fireplace-2})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:card (c/id :a010) :major-action? true}))))
  (doseq [[space offered?] [[:stone-east true] [:stone-west true] [:boar false]]]
    (let [state (-> (fresh) rich (own 0 :a096))
          round-index (.indexOf (:round-order state) space)
          before (assoc state :round round-index)
          after (g/drain (g/begin-round before))]
      (is (= (if offered? 1 0) (delta before after :wood)))
      (is (= offered? (boolean (seq (:effect-queue after))))))))

(deftest small-trader-requires-a-literal-major-or-minor-action
  (doseq [[space bonus] [[:improvement 3] [:meeting 0]]]
    (let [state (-> (fresh) rich (own 0 :a109) (hand :a042) (activate space))
          after (g/command state 0 {:type :action :space space :card (c/id :a042)})]
      (is (= bonus (delta state after :food)))))
  (let [state (-> (fresh) rich (own 0 :a109) (own 0 :a095) (hand :a042))
        pending (g/command state 0 {:type :action :space :fishing})]
    (is (= 3 (delta pending (resolve! pending {:card (c/id :a042)}) :food))))
  (let [state (-> (fresh) rich (own 0 :a109) (hand :a042))
        pending (g/drain (g/queue-front state [(g/task 0 (c/id :a096) {:op :card-action :optional? true :kinds #{:minor}} {})]))]
    (is (= 0 (delta pending (resolve! pending {:card (c/id :a042)}) :food))))
  (let [state (-> (fresh) rich (own 0 :a109) (activate :improvement))
        after (g/command state 0 {:type :action :space :improvement :improvement :fireplace-2})]
    (is (= 0 (delta state after :food)))))

(deftest stone-company-requires-stone-actually-paid-and-ignores-client-costs
  (let [state (-> (fresh) rich (own 0 :a023) (activate :stone-east) (hand :b033)
                  (assoc-in [:players 0 :house] :clay))
        pending (g/command state 0 {:type :action :space :stone-east})]
    (is (contains? (get-in (resolve! pending {:improvement :well}) [:players 0 :improvements]) :well))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:improvement :fireplace-2 :minimum-paid {} :cost {:stone 1}})))
    (is (contains? (set (get-in (resolve! pending {:card (c/id :b033)}) [:players 0 :minors])) (c/id :b033)))
    (let [discounted (own pending 0 :a143)]
      (is (thrown? clojure.lang.ExceptionInfo (resolve! discounted {:card (c/id :b033) :stonecutter? false}))))
    (is (empty? (:effect-queue (resolve! pending {:skip? true}))))))

(deftest craft-teacher-queues-two-free-occupations-after-every-craft-major
  (doseq [major [:joinery :pottery :basketry] granted? [false true]]
    (let [state (-> (fresh) rich (own 0 :a131) (hand :a116) (hand :a108) (activate :improvement))
          pending (if granted?
                    (g/drain (g/granted-card-action state 0 {:kinds #{:major :minor}} {:improvement major}))
                    (g/command state 0 {:type :action :space :improvement :improvement major}))
          first (resolve! pending {:card (c/id :a116)})
          second (resolve! (edn/read-string (pr-str first)) {:card (c/id :a108)})]
      (is (= :card-action (get-in first [:effect-queue 0 :effect :op])))
      (is (= 0 (delta pending second :food)))
      (is (every? (set (get-in second [:players 0 :occupations])) (map c/id [:a116 :a108])))
      (is (= (:occupied pending) (:occupied second)))
      (is (= (get-in pending [:players 0 :remaining]) (get-in second [:players 0 :remaining])))))
  (let [state (-> (fresh) rich (own 0 :a131) (hand :a116))
        pending (g/drain (g/granted-card-action state 0 {:kinds #{:major}} {:improvement :joinery}))]
    (is (empty? (:effect-queue (resolve! (resolve! pending {:skip? true}) {:skip? true}))))))

(deftest hollow-warden-builds-only-a-paid-fireplace-and-takes-hollow-food
  (let [state (-> (fresh) rich) pending (play state :a139)]
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:improvement :hearth-4})))
    (is (= -2 (delta pending (resolve! pending {:improvement :fireplace-2 :cost {}}) :clay)))
    (let [done (resolve! pending {:skip? true}) after (g/command done 0 {:type :action :space :hollow})]
      (is (= 1 (delta done after :food))))))

(deftest night-school-checks-all-lessons-and-charges-occupation-cost
  (doseq [occupied [{:lessons 0} {:lessons-extra 1} {:lessons-copse 2} {:lessons-growth 3}]]
    (let [state (-> (fresh) rich (own 0 :a152) (assoc :occupied occupied))]
      (is (empty? (:effect-queue (emit state :return-home 0 {}))))))
  (let [state (-> (fresh) rich (own 0 :a152) (own 0 :b109) (hand :a116))
        pending (emit state :return-home 0 {})
        after (resolve! pending {:card (c/id :a116) :paper-maker? true})]
    (is (= -1 (delta pending after :wood)))
    (is (= 1 (delta pending after :food)) "Paper Maker grants food for the two previous occupations, then pays one food")
    (is (thrown? clojure.lang.ExceptionInfo (resolve! (own pending 0 :b176) {:card (c/id :a116)})))))

(deftest granted-majors-keep-five-player-build-counts-and-bots-have-choices
  (let [state (-> (g/initialize (a/new-game ["A" "B" "C" "D" "E"] (vec (mapcat identity a/stages)) 0)) rich)
        pending (play state :a139)
        after (resolve! pending {:improvement :fireplace-2})]
    (is (= 1 (get-in after [:major-builds 0])))
    (is (= 2 (get-in after [:players 0 :remaining])))
    (is (some #(= :fireplace-2 (:improvement %)) (bot/decision-candidates pending 0)))
    (is (not-any? #(= :well (:improvement %)) (bot/decision-candidates pending 0)))
    (is (some? (bot/choose pending 0 (java.util.Random. 1))))))
