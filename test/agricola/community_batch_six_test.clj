(ns agricola.community-batch-six-test
  (:require [clojure.test :refer :all] [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [agricola.bot :as bot] [eurozone.card-scenarios :as scenarios]
            [agricola.community-decks-test :refer [cid fresh rich own play reload resolve! emit op stock]]))
(defn scenario [id] (scenarios/initial-state id (:names (scenarios/options id "Ada"))))
(defn pile [s card] (get-in s [:players 0 :card-state card :stock]))
(defn stored [s card good] (get-in s [:players 0 :card-state card :stored-goods good] 0))

(deftest carrot-farmer-sells-three-vegetables-only-after-farmland
  (let [card (cid "G" 12) s (scenario "carrot-farmer") done (resolve! (reload s) {})]
    (is (= [:vegetable :vegetable :vegetable] (pile s card)))
    (is (= (dec (stock s 0 :food)) (stock done 0 :food)))
    (is (= (inc (stock s 0 :vegetable)) (stock done 0 :vegetable)))
    (is (= 2 (count (pile done card))))
    (is (= (:goods (g/p s 0)) (:goods (g/p (resolve! s {:skip? true}) 0)))))
  (let [card (cid "G" 12) s (-> (fresh) rich (play card))
        empty (reduce (fn [state _] (resolve! (emit (reload state) :use-space 0 {:space :plow}) {})) s (range 3))]
    (is (= 3 (- (stock empty 0 :vegetable) (stock s 0 :vegetable))))
    (is (empty? (pile empty card)))
    (is (nil? (op (emit empty :use-space 0 {:space :plow}))))
    (doseq [[actor space] [[1 :plow] [0 :cultivation] [0 :labor]]]
      (is (nil? (op (emit s :use-space actor {:space space})))))
    (is (nil? (op (emit (assoc-in s [:players 0 :goods] {}) :use-space 0 {:space :plow}))))))

(deftest pulley-builds-one-room-with-normal-payment-discounts-and-triggers
  (let [s (scenario "pulley") done (resolve! (reload s) {:rooms [0]})]
    (is (= 3 (count (a/tiles (g/p done 0) :room))))
    (is (= (- (stock s 0 :wood) 5) (stock done 0 :wood)))
    (is (= (- (stock s 0 :reed) 2) (stock done 0 :reed)))
    (is (= (get-in s [:players 0 :remaining]) (get-in done [:players 0 :remaining])))
    (is (= 1 (g/acting-player done)))
    (doseq [choice [{:rooms []} {:rooms [0 1]} {:rooms [14]} {:rooms [5]}]]
      (is (thrown? clojure.lang.ExceptionInfo (resolve! s choice))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! (assoc-in s [:players 0 :goods :wood] 0) {:rooms [0]})))
    (is (= 2 (count (a/tiles (g/p (resolve! s {:skip? true}) 0) :room))))
    (let [discounted (-> s (own 0 :catalog/original-base-e-157-thatcher) (own 0 (cid "G" 5)))
          built (resolve! discounted {:rooms [0]})]
      (is (= (dec (stock discounted 0 :reed)) (stock built 0 :reed)))
      (is (= (+ 2 (stock discounted 0 :food)) (stock built 0 :food)))))
  (let [s (-> (fresh) rich (play (cid "G" 97)))]
    (is (nil? (op (emit s :use-space 1 {:space :labor}))))
    (is (nil? (op (emit s :use-space 0 {:space :build}))))))

(deftest food-connoisseur-automatically-takes-the-top-item-only-with-source-food
  (let [card (cid "WM" 22) s (scenario "food-connoisseur")
        done (g/command (reload s) 0 {:type :action :space :fishing})]
    (is (= [:clay :vegetable :stone :grain :wood :reed] (pile s card)))
    (is (= (inc (stock s 0 :reed)) (stock done 0 :reed)))
    (is (= :wood (peek (pile done card))))
    (is (nil? (op done)))
    (doseq [[actor space food] [[1 :fishing 1] [0 :grain 1] [0 :fishing 0] [0 :traveling 0] [0 :labor 0]]]
      (is (= (pile s card) (pile (emit s :use-space actor {:space space :source-goods {:food food}}) card))))
    (is (= (pile s card) (pile (emit s :receive-goods 0 {:goods {:food 2}}) card)))
    (let [drained (reduce (fn [state _] (emit (reload state) :use-space 0 {:space :labor :source-goods {:food 2}})) s (range 6))]
      (is (empty? (pile drained card)))
      (doseq [good [:reed :wood :grain :stone :vegetable :clay]]
        (is (= (inc (stock s 0 good)) (stock drained 0 good))))
      (is (= (:goods (g/p drained 0)) (:goods (g/p (emit drained :use-space 0 {:space :labor :source-goods {:food 2}}) 0)))))))

(deftest oak-cask-grows-only-while-nonempty-and-can-be-collected-without-discarding
  (let [card (cid "WM" 96) s (scenario "oak-cask") command {:type :ability :card card}
        harvested (g/command (reload s) 0 {:type :action :space :grain})
        collected (g/command (reload harvested) 0 command)]
    (is (= 1 (stored s card :food)))
    (is (= 2 (stored harvested card :food)))
    (is (= (stock s 0 :food) (stock harvested 0 :food)))
    (is (= (+ 2 (stock s 0 :food)) (stock collected 0 :food)))
    (is (zero? (stored collected card :food)))
    (is (some #{card} (c/active-owned (g/p collected 0))))
    (is (not (get-in collected [:players 0 :card-state card :face-down?])))
    (is (zero? (stored (emit collected :field-phase 0) card :food)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command collected 0 command)))
    (is (= 1 (stored (emit s :field-phase 0 {:manure? true}) card :food)))
    (is (= 1 (stored (emit s :field-phase 1) card :food)))
    (is (= 7 (stored (reduce (fn [state _] (emit (reload state) :field-phase 0)) s (range 6)) card :food)))
    (is (some #(= command %) (bot/ability-candidates s 0)))
    (let [early (g/command s 0 command) later (g/command early 0 {:type :action :space :grain})]
      (is (= (inc (stock s 0 :food)) (stock later 0 :food)))
      (is (zero? (stored later card :food))))))

(deftest child-care-worker-offers-one-exchange-after-other-players-grow
  (let [s (scenario "child-care-worker")]
    (is (= 0 (g/acting-player s)))
    (is (= 3 (get-in s [:players 1 :family])))
    (doseq [[option food reed] [[0 2 0] [1 5 1]]]
      (let [done (resolve! (reload s) {:option option})]
        (is (= (+ food (stock s 0 :food)) (stock done 0 :food)))
        (is (= (dec (stock s 0 :wood)) (stock done 0 :wood)))
        (is (= (- (stock s 0 :reed) reed) (stock done 0 :reed)))
        (is (= 2 (g/acting-player done)))))
    (is (= (:goods (g/p s 0)) (:goods (g/p (resolve! s {:skip? true}) 0)))))
  (let [s (-> (fresh) rich (play (cid "FR" 71)))]
    (is (nil? (op (emit s :grow 0 {:newborns-added 1}))))
    (let [offer (emit s :grow 1 {:newborns-added 2}) done (resolve! offer {:option 0})]
      (is (= (+ 2 (stock s 0 :food)) (stock done 0 :food)))
      (is (nil? (op done))))
    (let [offer (emit (assoc-in s [:players 0 :goods :reed] 0) :grow 1)]
      (is (thrown? clojure.lang.ExceptionInfo (resolve! offer {:option 1})))
      (is (= (+ 2 (stock s 0 :food)) (stock (resolve! offer {:option 0}) 0 :food))))))

(deftest oceanographer-only-rewards-exactly-one-field-per-plow-action
  (let [card (cid "FR" 96) s (scenario "oceanographer") done (resolve! (reload s) {})]
    (is (= [:vegetable :stone :grain :clay :wood] (pile s card)))
    (is (= (inc (stock s 0 :wood)) (stock done 0 :wood)))
    (is (= :clay (peek (pile done card))))
    (is (= (pile s card) (pile (resolve! s {:skip? true}) card))))
  (let [card (cid "FR" 96) s (-> (fresh) rich (play card))]
    (doseq [[actor fields] [[0 0] [0 2] [0 3] [1 1]]]
      (is (nil? (op (emit s :plow actor {:fields-plowed fields})))))
    (let [done (reduce (fn [state _] (resolve! (emit (reload state) :plow 0 {:fields-plowed 1}) {})) s (range 5))]
      (is (empty? (pile done card)))
      (doseq [good [:wood :clay :grain :stone :vegetable]]
        (is (= (inc (stock s 0 good)) (stock done 0 good))))
      (is (nil? (op (emit done :plow 0 {:fields-plowed 1}))))))
  ;; A real plow tool aggregates its fields into one native plow event.
  (let [s (-> (fresh) rich (play (cid "FR" 96))
              (own 0 :catalog/original-base-k-115-swing-plow)
              (assoc-in [:players 0 :card-state :catalog/original-base-k-115-swing-plow :uses] 2))
        pending (g/command s 0 {:type :action :space :plow :cell 0})
        done (resolve! pending {:plow-card :catalog/original-base-k-115-swing-plow :cells [1 2]})]
    (is (= 3 (count (a/tiles (g/p done 0) :field))))
    (is (nil? (op done)))
    (is (= (pile s (cid "FR" 96)) (pile done (cid "FR" 96))))))
