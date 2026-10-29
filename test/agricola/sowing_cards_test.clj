(ns agricola.sowing-cards-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot] [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(defn field [state cell & [crop n]]
  (assoc-in state [:players 0 :farm cell] {:type :field :crop crop :count (or n 0)}))
(defn sow-space [state] (update state :active conj :sow-bake :cultivation))
(defn offer [state] (g/command (sow-space state) 0 {:type :action :space :sow-bake}))
(defn resolve-player [state player choice]
  (g/command state player (merge {:type :resolve :decision (:decision-id state)} choice)))
(defn boundary [cells]
  (->> cells (mapcat a/edges) frequencies (keep (fn [[edge n]] (when (= n 1) edge))) vec))

(deftest card-prerequisites-and-native-costs
  (let [state (-> (fresh) (hand :a065) (field 0) (field 1))]
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 (c/id :a065) 0 {})))
    (is (some #{(c/id :a065)} (get-in (g/play-card (field state 2) 0 (c/id :a065) 0 {}) [:players 0 :minors]))))
  (let [state (-> (fresh) (hand :a073))]
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 (c/id :a073) 0 {}))))
  (let [state (-> (fresh) (hand :a079) (update-in [:players 0] a/gain {:wood 1}))
        after (g/play-card state 0 (c/id :a079) 0 {})]
    (is (= 0 (a/amount (g/p after 0) :wood)))))

(deftest seed-pellets-before-sowing-and-commit-exactly-one-worker
  (let [state (-> (fresh) (own 0 :a065) (field 0) (field 1) (field 2))
        pending (offer state) sow (resolve! pending {})
        restored (edn/read-string (pr-str sow))
        after (resolve! restored {:plantings [{:cell 0 :crop :grain}]})]
    (is (= :sow-offer (get-in pending [:effect-queue 0 :effect :op])))
    (is (= :sow (get-in sow [:effect-queue 0 :effect :op])))
    (is (= 1 (a/amount (g/p sow 0) :grain)))
    (is (= 2 (get-in sow [:players 0 :remaining])))
    (is (empty? (:occupied sow)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command sow 0 {:type :convert :goods {:grain 1}})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! sow {:skip? true})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! sow {:plantings [] :bake-grain 1})))
    (is (= 0 (a/amount (g/p after 0) :grain)))
    (is (= 3 (get-in after [:players 0 :farm 0 :count])))
    (is (= 1 (get-in after [:players 0 :remaining])))
    (is (= {:sow-bake 0} (:occupied after)))
    (is (= 1 (:turn after)))
    (is (nil? (:sow-in-progress after)))))

(deftest publican-gift-funds-the-sow-and-uses-trusted-actor
  (let [state (-> (fresh) (field 0) (own 1 :a132) (update-in [:players 1] a/gain {:grain 1}))
        pending (resolve! (offer state) {})
        sow (resolve-player (edn/read-string (pr-str pending)) 1 {:actor 2 :player 2})
        done (resolve! sow {:plantings [{:cell 0 :crop :grain}]})]
    (is (= 1 (g/acting-player pending)))
    (is (= 1 (a/amount (g/p sow 0) :grain)))
    (is (= 0 (a/amount (g/p sow 1) :grain)))
    (is (= 0 (a/amount (g/p sow 2) :grain)))
    (is (= 1 (get-in done [:players 1 :bonus-points])))
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (thrown? clojure.lang.ExceptionInfo (g/command sow 0 {:type :convert :goods {:grain 1}})))
    (let [with-ability (own sow 0 :b104)]
      (is (thrown? clojure.lang.ExceptionInfo (g/command with-ability 0 {:type :ability :card (c/id :b104) :resource :vegetable}))))))

(deftest declined-publican-cancels-uncommitted-placement-and-bot-does-not-loop
  (let [state (-> (fresh) (field 0) (own 1 :a132) (update-in [:players 1] a/gain {:grain 1}))
        pending (resolve! (offer state) {})
        done (resolve-player pending 1 {:skip? true})]
    (is (empty? (:effect-queue done)))
    (is (empty? (:occupied done)))
    (is (= 2 (get-in done [:players 0 :remaining])))
    (is (= 0 (:turn done)))
    (is (= 1 (a/amount (g/p done 1) :grain)))
    (is (= 0 (get-in done [:players 1 :bonus-points])))
    (is (not-any? #(= :sow-bake (:space %)) (bot/action-candidates done 0)))
    ;; A valid anytime exchange can provide a fresh seed and permit a retry.
    (let [ready (-> done (own 0 :b104) (update-in [:players 0] a/gain {:sheep 1}))
          changed (g/command ready 0 {:type :ability :card (c/id :b104) :resource :vegetable})]
      (is (nil? (:cancelled-sowing changed)))
      (is (some #(= :sow-bake (:space %)) (bot/action-candidates changed 0))))))

(deftest impossible-crops-and-invalid-flags-cannot-open-gift-workflows
  (let [state (-> (fresh) (own 0 :a065) (own 1 :a132)
                  (assoc-in [:players 0 :card-fields (c/id :b068)] {:type :field :allowed-crop :vegetable :count 0})
                  (update-in [:players 1] a/gain {:grain 1}))]
    (is (thrown? clojure.lang.ExceptionInfo (offer state)))
    (is (= 0 (a/amount (g/p state 0) :grain))))
  (let [state (-> (fresh) (own 0 :a065) (field 0) sow-space)]
    (is (thrown? clojure.lang.ExceptionInfo (g/command state 0 {:type :action :space :sow-bake :lasso? true})))
    (is (thrown? clojure.lang.ExceptionInfo (g/command state 0 {:type :action :space :sow-bake :bake-grain -1})))
    (is (thrown? clojure.lang.ExceptionInfo (g/command state 0 {:type :action :space :cultivation :cell 5})))))

(deftest declining-sow-performs-only-the-legal-alternative
  (let [state (-> (fresh) (own 0 :a065) sow-space)
        pending (g/command state 0 {:type :action :space :cultivation :cell 0})
        done (resolve! pending {:skip? true})]
    (is (= :field (get-in done [:players 0 :farm 0 :type])))
    (is (= 0 (a/amount (g/p done 0) :grain)))
    (is (= 1 (get-in done [:players 0 :remaining]))))
  (let [state (-> (fresh) (own 0 :a079) (update-in [:players 0] a/gain {:grain 1})
                  (assoc-in [:players 0 :improvements] #{:clay-oven}) sow-space)
        pending (g/command state 0 {:type :action :space :sow-bake :bake-grain 1})
        done (resolve! pending {:skip? true})]
    (is (= 7 (a/amount (g/p done 0) :food)))
    (is (= 1 (get-in done [:players 0 :remaining])))))

(deftest garden-hoe-requires-real-unconditional-vegetable-sowing
  (let [state (-> (fresh) (own 0 :a079) (field 0) (update-in [:players 0] a/gain {:vegetable 1}))
        done (resolve! (resolve! (offer state) {}) {:plantings [{:cell 0 :crop :vegetable}]})
        forged (g/command state 0 {:type :action :space :wood :plantings [{:cell 0 :crop :vegetable}]})]
    (is (= 1 (delta state done :clay)))
    (is (= 1 (delta state done :stone)))
    (is (= 0 (delta state forged :clay)))
    (is (= 0 (delta state forged :stone)))))

(deftest chief-forester-is-one-field-and-not-unconditional-even-on-riverbank
  (let [state (-> (g/initialize (a/new-game ["A" "B" "C" "D" "E"] (vec (mapcat identity a/stages)) 0))
                  (own 0 :a115) (own 0 :a065) (own 0 :a079) (own 1 :a132)
                  (field 0) (field 1) (update-in [:players 0] a/gain {:vegetable 2})
                  (update-in [:players 1] a/gain {:grain 1}))
        pending (g/command state 0 {:type :action :space :riverbank})
        sow (resolve! pending {})
        done (resolve! sow {:plantings [{:cell 0 :crop :vegetable}] :unconditional? true :max-fields 9})]
    (is (= 0 (a/amount (g/p sow 0) :grain)))
    (is (= 0 (g/acting-player sow)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! sow {:plantings [{:cell 0 :crop :vegetable} {:cell 1 :crop :vegetable}]})))
    (is (= 1 (delta state done :wood)))
    (is (= 1 (delta state done :reed)))
    (is (= 0 (delta state done :clay)))
    (is (= 0 (delta state done :stone)))
    (is (= 1 (get-in done [:players 0 :remaining])))))

(deftest agricultural-fertilizers-counts-newly-used-spaces-per-construction-action
  (let [state (-> (fresh) (own 0 :a073) (own 0 :a065) (field 0) (field 1) (field 2)
                  (update :active conj :fences) (update-in [:players 0] a/gain {:wood 6}))
        pending (g/command state 0 {:type :action :space :fences :fences (boundary [3 4])})
        done (resolve! (resolve! pending {}) {:plantings [{:cell 0 :crop :grain}]})]
    (is (= (c/id :a073) (get-in pending [:effect-queue 0 :card])))
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (= 3 (get-in done [:players 0 :farm 0 :count]))))
  (let [state (-> (fresh) (own 0 :a073) (field 3) (update-in [:players 0] a/gain {:wood 7 :reed 2 :grain 1}))
        done (g/command state 0 {:type :action :space :build :rooms [0] :stables [4]})]
    (is (empty? (:effect-queue done)) "One room and one stable are separate actions, not two used spaces in one action"))
  (let [state (-> (fresh) (own 0 :a073) (field 3) (update-in [:players 0] a/gain {:wood 10 :reed 4 :grain 1}))
        pending (g/command state 0 {:type :action :space :build :rooms [0 1]})]
    (is (= (c/id :a073) (get-in pending [:effect-queue 0 :card]))))
  (let [state (-> (fresh) (own 0 :a073) (field 0) (update-in [:players 0] a/gain {:wood 4 :grain 1})
                  (update-in [:players 0] a/build-fences (boundary [3 4]) {:free-fences 6}))
        done (g/command state 0 {:type :action :space :build :stables [3 4]})]
    (is (empty? (:effect-queue done)) "Stables on an existing pasture do not turn unused spaces into used spaces")))

(deftest sow-decisions-have-legal-bot-continuations
  (let [pending (offer (-> (fresh) (own 0 :a065) (field 0)))
        sow (resolve! pending {})]
    (is (some? (bot/choose pending 0 (java.util.Random. 1))))
    (is (some #(seq (:plantings %)) (bot/decision-candidates sow 0)))
    (is (some? (bot/choose sow 0 (java.util.Random. 1))))))

(deftest staged-cultivation-emits-plow-payoffs-exactly-once
  (doseq [decline? [false true]]
    (let [state (-> (fresh) (own 0 :a065) (own 0 :a105) sow-space)
          pending (g/command state 0 {:type :action :space :cultivation :cell 0})
          done (if decline? (resolve! pending {:skip? true})
                   (resolve! (resolve! pending {}) {:plantings [{:cell 0 :crop :grain}]}))]
      (is (= 1 (delta state done :clay)))
      (is (= 1 (delta state done :food)))
      (is (= 1 (get-in done [:players 0 :remaining])))
      (is (= 1 (:turn done))))))

(deftest sidekick-staged-sowing-pays-and-advances-only-once
  (doseq [gift-declined? [false true]]
    (let [state (-> (g/initialize (a/new-game ["A" "B" "C" "D" "E"] (vec (mapcat identity a/stages)) 0))
                    (own 0 :a171) (field 0)
                    (assoc :round 4 :active (into (a/base-actions 5 :cards) [:sheep :improvement :sow-bake :fences]))
                    (update-in [:players 0] a/gain {:wood 4}))
          state (if gift-declined? (-> state (own 1 :a132) (update-in [:players 1] a/gain {:grain 1})) (own state 0 :a065))
          sidekick (g/command state 0 {:type :action :space :fences :fences (boundary [4])})
          offer (resolve! sidekick {:action-command {}})
          pending (resolve! offer {})
          done (if gift-declined? (resolve-player pending 1 {:skip? true})
                   (resolve! pending {:plantings [{:cell 0 :crop :grain}]}))]
      (is (= :sidekick (get-in sidekick [:effect-queue 0 :effect :op])))
      (is (= :sow-offer (get-in offer [:effect-queue 0 :effect :op])))
      (is (= 0 (delta state offer :food)) "Sidekick's food is deferred until a real extra placement")
      (is (= (if gift-declined? 0 -1) (delta state done :food)))
      (is (= (if gift-declined? 1 0) (get-in done [:players 0 :remaining])))
      (is (= (if gift-declined? {:fences 0} {:fences 0 :sow-bake 0}) (:occupied done)))
      (is (= 1 (:turn done)))
      (is (empty? (:effect-queue done))))))
