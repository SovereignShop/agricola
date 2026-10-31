(ns agricola.original-k-salesman-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.expansion-cards-test :refer [fresh own hand resolve!]]))
(def salesman :catalog/original-base-k-281-traveling-salesman)
(def merchant :catalog/original-base-e-179-merchant)
(def businessman :catalog/original-base-i-228-businessman)
(def cards [:catalog/original-base-k-135-horse :catalog/original-base-k-120-house-goat
            :catalog/original-base-k-123-wooden-strongbox :catalog/original-base-k-109-spit-roast
            :catalog/original-base-k-127-clapper :catalog/original-base-k-111-bread-paddle])
(defn ready [] (-> (fresh) (own 0 salesman) (assoc-in [:players 0 :hand :minor] cards)
                   (update-in [:players 0] a/gain {:wood 20 :clay 20 :reed 20 :stone 20})
                   (update :active conj :improvement)))
(defn head [s] (get-in s [:effect-queue 0 :card]))
(defn reload [s] (edn/read-string (pr-str s)))

(deftest board-minor-can-be-replaced-by-a-major-but-scholar-cannot
  (let [s (ready) done (g/command s 0 {:type :action :space :meeting :salesman? true :improvement :well})]
    (is (contains? (:improvements (g/p done 0)) :well)))
  (is (thrown? clojure.lang.ExceptionInfo (g/command (ready) 0 {:type :action :space :meeting :improvement :well})))
  (is (thrown? clojure.lang.ExceptionInfo (g/granted-card-action (ready) 0 {:kinds #{:minor} :original-scholar? true} {:salesman? true :improvement :well})))
  (is (thrown? clojure.lang.ExceptionInfo (g/command (ready) 0 {:type :action :space :meeting :salesman? true :card (first cards)}))))

(deftest two-minors-are-sequential-and-the-second-can-be-declined
  (let [s (g/command (ready) 0 {:type :action :space :improvement :salesman? true :card (first cards)})]
    (is (= salesman (head s)))
    (is (some #{(first cards)} (:minors (g/p s 0))))
    (let [done (resolve! (reload s) {:card (second cards)})]
      (is (= 2 (count (:minors (g/p done 0)))))
      (is (empty? (:effect-queue done))))
    (is (= 1 (count (:minors (g/p (resolve! s {:skip? true}) 0))))))
  (is (thrown? clojure.lang.ExceptionInfo (g/command (ready) 0 {:type :action :space :improvement :salesman? true :improvement :well}))))

(deftest merchant-repeats-two-minors-as-one-group-for-one-food
  (let [base (own (ready) 0 merchant)
        first (g/command base 0 {:type :action :space :improvement :salesman? true :card (cards 0)})
        second (resolve! first {:card (cards 1)})
        third (resolve! (reload second) {:card (cards 2)})
        fourth (resolve! third {:card (cards 3)})]
    (is (= salesman (head first)))
    (is (= merchant (head second)))
    (is (= salesman (head third)))
    (is (empty? (:effect-queue fourth)))
    (is (= 4 (count (:minors (g/p fourth 0)))))
    (is (= 1 (- (a/amount (g/p base 0) :food) (a/amount (g/p fourth 0) :food))))))

(deftest merchant-repeats-major-substitution-for-one-food
  (let [base (own (ready) 0 merchant)
        first (g/command base 0 {:type :action :space :meeting :salesman? true :improvement :fireplace-2})
        done (resolve! (reload first) {:improvement :well})]
    (is (= merchant (head first)))
    (is (= #{:fireplace-2 :well} (:improvements (g/p done 0))))
    (is (= 1 (- (a/amount (g/p base 0) :food) (a/amount (g/p done 0) :food))))
    (is (empty? (:effect-queue done)))))

(deftest businessman-extra-action-can-use-the-two-minor-mode
  (let [base (own (ready) 0 businessman)
        first (g/command base 0 {:type :action :space :meeting :card (cards 0)})
        second (resolve! first {:salesman? true :card (cards 1)})
        third (resolve! second {:card (cards 2)})]
    (is (= businessman (head first)))
    (is (= salesman (head second)))
    (is (= 3 (count (:minors (g/p third 0)))))
    (is (empty? (:effect-queue third)))))

(deftest no-repeat-card-can-reuse-salesman-in-the-same-turn
  (let [base (ready) done (g/command base 0 {:type :action :space :meeting :salesman? true :improvement :well})
        same-turn (assoc done :turn-serial (:turn-serial base 0) :turn 0)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/granted-card-action same-turn 0 {:kinds #{:major :minor} :origin-space :meeting :businessman? true}
                                        {:salesman? true :card (cards 0)})))))
