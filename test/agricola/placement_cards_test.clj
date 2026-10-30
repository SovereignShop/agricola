(ns agricola.placement-cards-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(defn act [state pid command]
  (-> (g/take-action (assoc state :turn pid) pid command) bot/without-continuations g/drain))
(defn five [] (g/initialize (a/new-game ["A" "B" "C" "D" "E"] (vec (mapcat identity a/stages)) 0)))

(deftest bassinet-marks-one-shared-nonaccumulation-space-and-allows-only-a-second-person
  (let [state (-> (fresh) (own 0 :a025))
        wood (act state 1 {:space :wood}) grain (act wood 1 {:space :grain})
        twice (act (edn/read-string (pr-str grain)) 0 {:space :grain})]
    (is (nil? (:first-nonaccumulation wood)))
    (is (= :grain (:first-nonaccumulation grain)))
    (is (= 1 (delta grain twice :grain)))
    (is (= [1 0] (g/space-owners twice :grain)))
    (is (thrown? clojure.lang.ExceptionInfo (act twice 0 {:space :grain})))
    (is (= :grain (:first-nonaccumulation (act twice 2 {:space :labor})))))
  (let [state (act (own (fresh) 0 :a025) 0 {:space :meeting})]
    (is (thrown? clojure.lang.ExceptionInfo (act state 0 {:space :meeting}))))
  (let [state (-> (fresh) (own 0 :a025) (update :active conj :growth)
                  (assoc-in [:players 1 :farm 0] {:type :room}))
        grown (act state 1 {:space :growth})]
    (is (= 2 (g/space-people grown :growth)))
    (is (nil? (g/occupied-permission grown 0 :growth)))))

(deftest bassinet-can-use-a-private-card-space-but-does-not-ignore-its-requirements
  (let [state (-> (fresh) (own 0 :a025) (own 1 :a162) (assoc :occupied {:wood 2 :clay 3} :turn 1))
        used (g/command state 1 {:type :card-action :card-space (c/id :a162)})
        done (g/command (assoc used :turn 0) 0 {:type :card-action :card-space (c/id :a162)})]
    (is (= (c/id :a162) (:first-nonaccumulation used)))
    (is (nil? (g/card-space-error used 0 (c/id :a162))))
    (is (= 3 (delta used done :wood)))
    (is (= [1 0] (g/space-owners done (c/id :a162))))
    (is (some? (g/card-space-error done 0 (c/id :a162)))))
  (let [used (-> (five) (own 0 :a025) (assoc :first-nonaccumulation :lessons-growth :occupied {:modest-growth 1}))]
    (is (thrown? clojure.lang.ExceptionInfo (g/validate-space used 0 :lessons-growth)))))

(deftest forest-school-uses-occupied-lessons-and-splits-the-occupation-cost
  (doseq [wood [0 1 2]]
    (let [state (-> (fresh) (own 0 :a028) (own 0 :a103) (own 0 :a104) (hand :a116)
                    (assoc :occupied {:lessons-extra [1 2]})
                    (assoc-in [:players 0 :goods :wood] 2))
          done (g/command state 0 {:type :action :space :lessons-extra :card (c/id :a116) :occupation-wood wood})]
      (is (= [1 2 0] (g/space-owners done :lessons-extra)))
      (is (= (- wood) (delta state done :wood)))
      (is (= (- wood 2) (delta state done :food)))
      (is (c/has? (g/p done 0) :a116))))
  (doseq [wood [-1 3 0.5]]
    (let [state (-> (fresh) (own 0 :a028) (hand :a116))]
      (is (thrown? clojure.lang.ExceptionInfo (g/command state 0 {:type :action :space :lessons :card (c/id :a116) :occupation-wood wood})))))
  (let [state (-> (fresh) (own 0 :a103) (hand :a116) (update-in [:players 0] a/gain {:wood 1}))]
    (is (thrown? clojure.lang.ExceptionInfo (g/command state 0 {:type :action :space :lessons :card (c/id :a116) :occupation-wood 1}))))
  (let [state (-> (five) (own 0 :a028) (hand :a116) (assoc :occupied {:copse 1}))]
    (is (thrown? clojure.lang.ExceptionInfo (g/command state 0 {:type :action :space :lessons-copse :card (c/id :a116)})))))

(deftest forest-school-also-funds-granted-occupations-and-bots-see-sharing
  (let [state (-> (fresh) (own 0 :a028) (hand :a116) (assoc-in [:players 0 :goods :food] 0)
                  (update-in [:players 0] a/gain {:wood 1}))
        done (g/granted-card-action state 0 {:kinds #{:occupation} :occupation-cost 1} {:card (c/id :a116) :occupation-wood 1})]
    (is (c/has? (g/p done 0) :a116)) (is (= -1 (delta state done :wood))))
  (let [state (-> (fresh) (own 0 :a028) (own 0 :a103) (hand :a116) (assoc :occupied {:lessons 1})
                  (assoc-in [:players 0 :goods :food] 0) (update-in [:players 0] a/gain {:wood 1}))
        cmd {:type :action :space :lessons :card (c/id :a116) :occupation-wood 1}]
    (is (some #{cmd} (bot/action-candidates state 0)))
    (is (some? (bot/preview state 0 cmd)))))

(deftest mummys-boy-reuses-the-second-worker-once-per-round-after-two-placements
  (let [state (-> (fresh) (own 0 :a130) (assoc-in [:players 0 :family] 5) (assoc-in [:players 0 :remaining] 5))
        first-worker (act state 0 {:space :grain}) second-worker (act first-worker 0 {:space :labor})
        third-worker (act (edn/read-string (pr-str second-worker)) 0 {:space :labor})]
    (is (nil? (g/occupied-permission first-worker 0 :grain)))
    (is (= :mummys-boy (g/occupied-permission second-worker 0 :labor)))
    (is (= [:grain :labor :labor] (get-in third-worker [:players 0 :worker-spaces])))
    (is (= 2 (delta second-worker third-worker :food)))
    (is (= [0 0] (g/space-owners third-worker :labor)))
    (is (thrown? clojure.lang.ExceptionInfo (act third-worker 0 {:space :labor})))
    (is (thrown? clojure.lang.ExceptionInfo (act second-worker 0 {:space :grain})))
    (let [new-round (-> third-worker g/begin-round g/drain)]
      (is (= [] (get-in new-round [:players 0 :worker-spaces])))
      (is (nil? (:first-nonaccumulation new-round)))))
  (let [state (-> (fresh) (own 0 :a130) (assoc-in [:players 0 :family] 3) (assoc-in [:players 0 :remaining] 3))
        ready (-> state (act 0 {:space :grain}) (act 0 {:space :meeting}))]
    (is (thrown? clojure.lang.ExceptionInfo (act ready 0 {:space :meeting})))))
