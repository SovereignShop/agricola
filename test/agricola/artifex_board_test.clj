(ns agricola.artifex-board-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(defn game [n] (g/initialize (a/new-game (mapv str (range n)) (vec (mapcat identity a/stages)) 0)))
(defn stored [state] (get-in state [:players 0 :card-state (c/id :a081) :stored-goods]))
(defn rich [state] (update-in state [:players 0] a/gain {:wood 10 :clay 10 :reed 10 :stone 10 :food 10}))

(deftest board-card-printed-costs-prerequisites-and-player-counts
  (doseq [[code required] [[:a039 2] [:a082 3]]]
    (let [state (-> (fresh) rich (hand code))]
      (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 (c/id code) 0 {})))
      (let [ready (reduce #(own %1 0 %2) state (take required [:a103 :a104 :a116]))
            done (g/play-card ready 0 (c/id code) 0 {})]
        (is (c/has? (g/p done 0) code))
        (when (= code :a039) (is (= -3 (delta ready done :wood))) (is (= -2 (delta ready done :clay)))))))
  (let [state (hand (fresh) :a081) done (g/play-card state 0 (c/id :a081) 0 {})]
    (is (= -2 (delta state done :food)))
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card (assoc-in state [:players 0 :goods :food] 1) 0 (c/id :a081) 0 {}))))
  (doseq [[code players] [[:a137 3] [:a162 4]]]
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card (hand (game (dec players)) code) 0 (c/id code) 0 {})))
    (is (c/has? (g/p (g/play-card (hand (game players) code) 0 (c/id code) 0 {}) 0) code))))

(deftest interim-storage-covers-every-relevant-accumulation-space
  (doseq [[space resource] [[:clay :wood] [:hollow :wood] [:reed :clay] [:stone-east :reed] [:stone-west :reed]]]
    (let [state (-> (game 5) (own 0 :a081) (update :active #(vec (distinct (conj % space)))))
          done (g/command state 0 {:type :action :space space})]
      (is (= {resource 1} (stored done)) (str space))
      (is (= 0 (delta state done resource)) "Stored goods do not enter supply or receipt counters")
      (is (= (get-in state [:players 0 :received resource] 0) (get-in done [:players 0 :received resource] 0)))))
  (doseq [space [:wood :riverbank :market :labor]]
    (let [done (g/command (own (game 5) 0 :a081) 0 {:type :action :space space :choice :reed})]
      (is (empty? (stored done)) (str "No storage from " space)))))

(deftest interim-storage-delivers-only-at-the-three-specified-round-starts
  (doseq [round [6 7 8 10 11 12 13 14]]
    (let [state (-> (fresh) (own 0 :a081) (assoc :round (dec round))
                    (assoc-in [:players 0 :card-state (c/id :a081) :stored-goods] {:wood 2 :clay 1 :reed 3}))
          restored (edn/read-string (pr-str state)) done (g/drain (g/begin-round restored))
          delivery? (#{7 11 14} round)]
      (is (= (if delivery? {} {:wood 2 :clay 1 :reed 3}) (stored done)))
      (is (= (if delivery? 2 0) (delta state done :wood)))
      (is (= (if delivery? 1 0) (delta state done :clay)))
      (is (= (if delivery? 3 0) (delta state done :reed)))))
  (let [state (-> (fresh) (hand :a081) (assoc :round 7)) done (g/drain (g/play-card state 0 (c/id :a081) 0 {}))]
    (is (empty? (stored done))) (is (= 0 (a/amount (g/p done 0) :wood)))))

(deftest work-certificate-takes-one-good-without-using-that-space
  (let [state (-> (fresh) (own 0 :a082) (own 0 :a116) (assoc-in [:piles :wood] {:wood 4}))
        pending (g/command state 0 {:type :action :space :grain})
        done (resolve! (edn/read-string (pr-str pending)) {:space :wood :resource :wood})]
    (is (= (c/id :a082) (get-in pending [:effect-queue 0 :card])))
    (is (= 1 (delta pending done :wood)) "Taking wood does not use Forest or trigger Wood Cutter")
    (is (= 3 (get-in done [:piles :wood :wood])))
    (is (= {:grain 0} (:occupied done)))
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (empty? (:effect-queue done)))
    (is (= 1 (:turn done)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:space :wood :resource :food})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:space :clay :resource :clay :minimum-building 0})))
    (is (= 4 (get-in (resolve! pending {:skip? true}) [:piles :wood :wood])))))

(deftest work-certificate-triggers-on-its-own-play-and-on-card-spaces
  (let [state (-> (fresh) (hand :a082) (own 0 :a103) (own 0 :a104) (own 0 :a116)
                  (assoc-in [:piles :wood] {:wood 4}))
        pending (g/command state 0 {:type :action :space :meeting :card (c/id :a082)})]
    (is (= (c/id :a082) (get-in pending [:effect-queue 0 :card])))
    (is (= 1 (delta pending (resolve! pending {:space :wood :resource :wood}) :wood))))
  (let [state (-> (fresh) (own 0 :a082) (own 0 :a039) (assoc-in [:piles :wood] {:wood 4}))
        pending (g/command state 0 {:type :card-action :card-space (c/id :a039)})]
    (is (= (c/id :a082) (get-in pending [:effect-queue 0 :card])))
    (is (= 3 (get-in pending [:players 0 :bonus-points])))))

(deftest work-certificate-validates-building-only-and-current-piles
  (let [state (-> (game 5) (own 0 :a082) (assoc :occupied {:wood 1})
                  (assoc-in [:piles :wood] {:wood 4}) (assoc-in [:piles :hollow] {:clay 4})
                  (assoc-in [:piles :traveling] {:food 9}) (assoc-in [:piles :riverbank] {:wood 3}))
        pending (g/command state 0 {:type :action :space :grain})
        effect (get-in pending [:effect-queue 0 :effect]) options (g/pile-good-options pending effect)]
    (is (some #{{:space :wood :resource :wood}} options) "Occupied spaces can still supply goods")
    (is (some #{{:space :hollow :resource :clay}} options) "Linked/blocked spaces can supply goods")
    (is (not-any? #(#{:traveling :riverbank} (:space %)) options))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! (assoc-in pending [:piles :wood :wood] 3) {:space :wood :resource :wood}))))
  (let [done (g/command (own (fresh) 0 :a082) 0 {:type :action :space :wood})]
    (is (empty? (:effect-queue done)) "No prompt when no eligible building pile remains")))

(deftest riverine-shepherd-takes-only-the-counterpart-and-handles-housing
  (doseq [[space other resource] [[:sheep :reed :reed] [:reed :sheep :sheep]]]
    (let [state (-> (fresh) (own 0 :a137) (update :active conj :sheep)
                    (assoc-in [:piles :sheep] {:sheep 1}) (assoc-in [:piles :reed] {:reed 2}))
          pending (g/command state 0 {:type :action :space space})
          done (resolve! (edn/read-string (pr-str pending)) {})]
      (is (= 1 (delta pending done resource)))
      (is (= (dec (get-in pending [:piles other resource])) (get-in done [:piles other resource])))
      (is (= {space 0} (:occupied done)))
      (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:space :wood :resource :wood})))
      (is (= (:piles pending) (:piles (resolve! pending {:skip? true}))))))
  (let [state (-> (fresh) (own 0 :a137) (update :active conj :sheep)
                  (assoc-in [:piles :sheep] {:sheep 2}) (update-in [:players 0] a/gain {:boar 1}))
        pending (g/command state 0 {:type :action :space :reed}) after (resolve! pending {})]
    (is (= :housing (get-in after [:effect-queue 0 :effect :op])))
    (is (= 1 (get-in after [:piles :sheep :sheep]))))
  (doseq [state [(update (fresh) :active #(vec (remove #{:sheep} %)))
                 (assoc-in (fresh) [:piles :sheep :sheep] 0)]]
    (let [done (g/command (own state 0 :a137) 0 {:type :action :space :reed})]
      (is (empty? (:effect-queue done)) "No unrevealed or empty Sheep Market goods can be taken"))))

(deftest chapel-is-public-and-the-recipient-cannot-be-forged
  (let [state (-> (fresh) (own 0 :a039))]
    (is (some #(= :chapel (:kind %)) (g/card-spaces state 1)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc state :turn 1) 1 {:type :card-action :card-space (c/id :a039)})))
    (let [ready (-> state (assoc :turn 1) (update-in [:players 1] a/gain {:grain 1}))
          done (g/command ready 1 {:type :card-action :card-space (c/id :a039) :owner 2})]
      (is (= 1 (a/amount (g/p done 0) :grain)))
      (is (= 0 (a/amount (g/p done 1) :grain)))
      (is (= 0 (a/amount (g/p done 2) :grain)))
      (is (= 3 (get-in done [:players 1 :bonus-points])))
      (is (= 1 (get-in done [:players 1 :remaining])))
      (is (= 1 (get-in done [:occupied (c/id :a039)])))
      (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc done :turn 0) 0 {:type :card-action :card-space (c/id :a039)}))))
    (let [done (g/command state 0 {:type :card-action :card-space (c/id :a039)})]
      (is (= 3 (get-in done [:players 0 :bonus-points])))
      (is (= 0 (a/amount (g/p done 0) :grain)))
      (is (= 1 (get-in done [:players 0 :remaining]))))))

(deftest forest-tallyman-is-private-and-needs-the-named-spaces-occupied
  (let [state (own (fresh) 0 :a162)]
    (doseq [occupied [{} {:wood 1} {:clay 1} {:copse 1 :hollow 2}]]
      (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc state :occupied occupied) 0 {:type :card-action :card-space (c/id :a162)}))))
    (let [ready (assoc state :occupied {:wood 1 :clay 2}) done (g/command ready 0 {:type :card-action :card-space (c/id :a162)})]
      (is (= 3 (delta ready done :wood)))
      (is (= 2 (delta ready done :clay)))
      (is (= 0 (get-in done [:occupied (c/id :a162)])))
      (is (empty? (g/card-spaces ready 1)))
      (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc ready :turn 1) 1 {:type :card-action :card-space (c/id :a162)})))
      (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc done :turn 0) 0 {:type :card-action :card-space (c/id :a162)}))))))

(deftest bots-enumerate-legal-board-card-choices
  (let [state (-> (fresh) (own 0 :a082) (assoc-in [:piles :wood] {:wood 4}))
        pending (g/command state 0 {:type :action :space :grain})]
    (is (some #(and (= :wood (:space %)) (= :wood (:resource %))) (bot/decision-candidates pending 0)))
    (is (some? (bot/choose pending 0 (java.util.Random. 1)))))
  (doseq [[code occupied] [[:a039 {}] [:a162 {:wood 1 :clay 2}]]]
    (let [state (assoc (own (fresh) 0 code) :occupied occupied)]
      (is (some #(= (c/id code) (:card-space %)) (bot/card-action-candidates state 0))))))
