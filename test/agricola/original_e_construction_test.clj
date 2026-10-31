(ns agricola.original-e-construction-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-rules :as c] [agricola.card-game :as g]
            [agricola.bot :as bot]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(def roof :catalog/original-base-e-36-clay-roof)
(def supports :catalog/original-base-e-37-clay-supports)
(def trowel :catalog/original-base-e-50-builder-s-trowel)
(def thatcher :catalog/original-base-e-157-thatcher)
(def carpenter :catalog/original-base-e-218-carpenter)
(def maid :catalog/original-base-e-190-maid)
(defn rich [] (update-in (fresh) [:players 0] a/gain {:wood 20 :clay 20 :reed 20 :stone 20}))
(defn compact [m] (into {} (filter (comp pos? val) m)))
(defn cost [p options] (compact (a/room-cost p options)))

(deftest clay-roof-replaces-part-of-each-room-and-renovation-cost
  (let [p (g/p (own (rich) 0 roof) 0)]
    (is (= {:wood 5 :reed 2} (cost p {})))
    (is (= {:wood 5 :reed 1 :clay 1} (cost p {:clay-reed 1})))
    (is (= {:wood 5 :clay 2} (cost p {:clay-reed 2})))
    (doseq [amount [-1 3 1.5]]
      (is (thrown? clojure.lang.ExceptionInfo (a/room-cost p {:clay-reed amount}))))
    (let [done (a/renovate (assoc-in p [:goods :reed] 0) {:clay-reed 1})]
      (is (= :clay (:house done)))
      (is (= 17 (a/amount done :clay)))
      (is (= 0 (a/amount done :reed))))
    (is (thrown? clojure.lang.ExceptionInfo (a/renovate p {:clay-reed 2}))))
  (is (thrown? clojure.lang.ExceptionInfo (a/room-cost (g/p (rich) 0) {:clay-reed 1}))))

(deftest clay-supports-sets-an-alternative-base-with-optional-cost-modifiers
  (let [p (assoc (g/p (-> (rich) (own 0 supports) (own 0 carpenter)) 0) :house :clay)]
    (is (= {:clay 3 :reed 2} (cost p {})))
    (is (= {:clay 2 :wood 1 :reed 1} (cost p {:clay-supports? true})))
    (is (thrown? clojure.lang.ExceptionInfo (a/room-cost (assoc p :house :wood) {:clay-supports? true})))
    (let [p (update p :minors conj roof)
          paid (a/build-rooms p [0 1] {:room-options {0 {:clay-supports? true :clay-reed 1}
                                                     1 {:clay-reed 1}}})]
      (is (= 4 (count (a/tiles paid :room))))
      (is (= 13 (a/amount paid :clay)))
      (is (= 19 (a/amount paid :wood)))
      (is (= 19 (a/amount paid :reed)))))
  (is (thrown? clojure.lang.ExceptionInfo (a/room-cost (assoc (g/p (rich) 0) :house :clay) {:clay-supports? true}))))

(deftest room-options-are-validated-and-saved-in-granted-building-decisions
  (let [state (-> (rich) (own 0 roof) (own 0 supports) (own 0 carpenter)
                  (assoc-in [:players 0 :house] :clay)
                  (assoc-in [:players 0 :goods] {:wood 1 :clay 5 :reed 3 :food 2}))
        pending (g/drain (g/queue-front state [(g/task 0 nil {:op :build-rooms :optional? true} {})]))
        choice {:rooms [0 1] :room-options {0 {:clay-supports? true} 1 {}}}
        done (resolve! (edn/read-string (pr-str pending)) choice)]
    (is (= :build-rooms (get-in pending [:effect-queue 0 :effect :op])))
    (is (= 4 (count (a/tiles (g/p done 0) :room))))
    (is (= [0 0 0] (mapv #(a/amount (g/p done 0) %) [:wood :clay :reed])))
    (is (some #(and (= [0 1] (:rooms %)) (bot/preview pending 0 %)) (bot/decision-candidates pending 0)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending (assoc choice :room-options {0 {:clay-reed 3}}))))))

(deftest thatcher-reduces-each-room-renovation-and-only-six-specified-improvements
  (let [state (-> (rich) (own 0 thatcher) (own 0 roof)) p (g/p state 0)]
    (is (= {:wood 5 :reed 1} (cost p {})))
    (is (= {:wood 5 :clay 1} (cost p {:clay-reed 1})))
    (let [done (a/build-rooms p [0 1])]
      (is (= 18 (a/amount done :reed))))
    (let [done (a/renovate p)]
      (is (= 20 (a/amount done :reed)))
      (is (= :clay (:house done))))
    (doseq [cid (get-in (c/card thatcher) [:passive :reed-discount-for-improvements])]
      (let [card (c/metadata cid)]
        (is (some? card) (str cid))
        (is (= 1 (:reed (a/hand-improvement-discounts p card))) (str cid))))
    (is (zero? (:reed (a/hand-improvement-discounts p {:id :well}) 0)))))

(deftest builders-trowel-is-a-paid-boundary-action-that-triggers-renovation-cards
  (let [state (-> (fresh) (own 0 trowel) (own 0 roof) (own 0 maid)
                  (assoc-in [:players 0 :goods :clay] 3)
                  (assoc-in [:players 0 :goods :reed] 0))
        cmd {:type :ability :card trowel :clay-reed 1}
        done (g/command (edn/read-string (pr-str state)) 0 cmd)]
    (is (= :clay (get-in done [:players 0 :house])))
    (is (= 0 (get-in done [:players 0 :goods :clay])))
    (is (= (:remaining (g/p state 0)) (:remaining (g/p done 0))))
    (is (= (:turn state) (:turn done)))
    (is (= (set (range 2 15)) (set (keys (get-in done [:players 0 :schedule])))))
    (is (thrown? clojure.lang.ExceptionInfo (g/command done 0 cmd)))
    (is (some #(and (= trowel (:card %)) (bot/preview state 0 %)) (bot/ability-candidates state 0)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc-in state [:players 0 :goods :clay] 2) 0 cmd)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc state :effect-queue [(g/task 0 nil {:op :plow} {})]) 0 cmd)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc state :action-context {:player 0 :space :wood}) 0 cmd)))
    (let [conservator (own state 0 :catalog/original-base-e-200-conservator)]
      (is (thrown? clojure.lang.ExceptionInfo (g/command conservator 0 (assoc cmd :renovate-to :stone)))))))
