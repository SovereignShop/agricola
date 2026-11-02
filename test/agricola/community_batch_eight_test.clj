(ns agricola.community-batch-eight-test
  (:require [clojure.test :refer :all] [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [agricola.bot :as bot] [eurozone.card-scenarios :as scenarios]
            [agricola.community-decks-test :refer [cid fresh rich own play reload resolve! emit op stock]]))
(defn scenario [id] (scenarios/initial-state id (:names (scenarios/options id "Ada"))))
(defn deliveries [s card]
  (into {} (for [[round entries] (get-in s [:players 0 :schedule])
                 :when (> round (:round s))
                 :let [entries (filterv #(= card (:card %)) entries)] :when (seq entries)] [round entries])))

(deftest clay-starter-renovates-two-wooden-rooms-and-charges-extra-food
  (let [card (cid "G" 13) s (scenario "clay-starter")
        done (g/command (reload s) 0 {:type :action :space :lessons :card card})]
    (is (= :clay (get-in done [:players 0 :house])))
    (is (= (dec (stock s 0 :food)) (stock done 0 :food)))
    (doseq [r [:wood :clay :reed :stone]] (is (= (stock s 0 r) (stock done 0 r))))
    (is (= 1 (g/acting-player done)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/command (assoc-in s [:players 0 :goods :food] 0) 0 {:type :action :space :lessons :card card})))
    (let [with-renovation-bonus (own s 0 :catalog/original-expansion-wm-15-debris-dealer)
          changed (g/command with-renovation-bonus 0 {:type :action :space :lessons :card card})]
      (is (= :clay (get-in changed [:players 0 :house])))))
  (let [card (cid "G" 13)]
    (doseq [house [:clay :stone]]
      (let [s (-> (fresh) rich (assoc-in [:players 0 :house] house)) done (play s card)]
        (is (= house (get-in done [:players 0 :house])))
        (is (= (stock s 0 :food) (stock done 0 :food)))))
    (let [s (-> (fresh) rich (update-in [:players 0] a/build-rooms [0])) done (play s card)]
      (is (= :wood (get-in done [:players 0 :house])))
      (is (= (stock s 0 :food) (stock done 0 :food))))
    (let [s (-> (fresh) rich (own 0 (c/id :a109)) (assoc-in [:players 0 :goods :food] 1))
          ready (scenarios/put-in-hand s 0 card)]
      (is (thrown? clojure.lang.ExceptionInfo (g/command ready 0 {:type :action :space :lessons :card card}))))))

(deftest fence-helper-pays-the-builder-and-keeps-normal-fencing-costs
  (let [s (scenario "fence-helper") edges (vec (a/edges 0))
        done (resolve! (reload s) {:fences edges})]
    (is (= 1 (get-in s [:effect-queue 0 :context :actor])))
    (is (= (dec (stock s 0 :food)) (stock done 0 :food)))
    (is (= (inc (stock s 1 :food)) (stock done 1 :food)))
    (is (= (- (stock s 0 :wood) 4) (stock done 0 :wood)))
    (is (= (set edges) (get-in done [:players 0 :fences])))
    (is (= (get-in s [:players 0 :remaining]) (get-in done [:players 0 :remaining])))
    (is (= 2 (g/acting-player done)))
    (doseq [choice [{:fences []} {:fences [(first edges)]} {:fences (vec (a/edges 5))}]]
      (is (thrown? clojure.lang.ExceptionInfo (resolve! s choice))))
    (doseq [[r n] [[:wood 3] [:food 0]]]
      (is (thrown? clojure.lang.ExceptionInfo (resolve! (assoc-in s [:players 0 :goods r] n) {:fences edges}))))
    (let [declined (resolve! s {:skip? true})]
      (is (= (:goods (g/p s 0)) (:goods (g/p declined 0))))
      (is (= (:goods (g/p s 1)) (:goods (g/p declined 1)))))
    (let [hedge (own s 0 :catalog/original-base-e-175-hedge-keeper)
          built (resolve! hedge {:fences edges})]
      (is (= (dec (stock hedge 0 :wood)) (stock built 0 :wood)))))
  (let [s (-> (fresh) rich (play (cid "G" 16)))]
    (is (nil? (op (emit s :fence 0))))
    (is (nil? (op (emit s :build-stables 1))))
    (let [from-card (g/drain (g/run-effect s (g/task 1 nil {:op :mini-pasture} {}) {:cell 0}))]
      (is (= :build-fences (op from-card)))
      (is (= 1 (get-in from-card [:effect-queue 0 :context :actor]))))))

(deftest amazon-gives-only-the-printed-animal-in-each-listed-round
  (let [s (-> (fresh) rich (play (cid "WM" 1)))]
    (doseq [[round animal] [[7 :sheep] [9 :boar] [11 :cattle]]]
      (let [ready (scenarios/prepared-round s round)
            done (g/command (reload ready) 0 {:type :action :space :fishing})]
        (is (= 1 (stock done 0 animal)))
        (is (nil? (op done)))
        (is (> (stock done 0 :food) (stock ready 0 :food)))))
    (doseq [round [1 6 8 10 12 14]]
      (let [done (emit (assoc s :round round) :use-space 0 {:space :fishing})]
        (is (every? zero? (map #(stock done 0 %) a/animals)))
        (is (nil? (op done)))))
    (is (nil? (op (emit (assoc s :round 13) :use-space 1 {:space :fishing}))))
    (is (nil? (op (emit (assoc s :round 13) :use-space 0 {:space :labor})))))
  (let [s (scenario "amazon-choice")]
    (doseq [animal a/animals]
      (let [done (resolve! (reload s) {:resource animal})]
        (is (= 1 (stock done 0 animal)))
        (is (= (stock s 0 :food) (stock done 0 :food)))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:resource :grain})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:skip? true}))))
  (is (= 3 (:min-players (c/card (cid "WM" 1))))))

(deftest apprenticeship-compares-every-player-and-passes-after-the-occupation
  (let [card (cid "WM" 59) s (scenario "apprenticeship")
        done (resolve! (reload s) {:card (cid "G" 5)})]
    (is (= (stock s 0 :food) (stock done 0 :food)))
    (is (some #{(cid "G" 5)} (get-in done [:players 0 :occupations])))
    (is (some #{card} (get-in done [:players 1 :hand :minor])))
    (is (not-any? #{card} (c/owned (g/p done 0))))
    (is (some #{card} (get-in (resolve! s {:skip? true}) [:players 1 :hand :minor])))
    (let [chief :catalog/original-base-e-172-chief
          ready (-> s (assoc-in [:players 0 :house] :stone) (scenarios/put-in-hand 0 chief))
          result (resolve! ready {:card chief})]
      (is (= (- (stock ready 0 :food) 2) (stock result 0 :food)))
      (is (thrown? clojure.lang.ExceptionInfo (resolve! (assoc-in ready [:players 0 :goods :food] 1) {:card chief})))))
  (let [card (cid "WM" 59) s (-> (fresh) rich (own 1 (c/id :a109)) (own 2 (c/id :a109)))]
    (is (nil? (op (play s card))) "One tied opponent prevents the occupation offer.")
    (let [eligible (-> s (own 3 (c/id :a109)) (scenarios/put-in-hand 0 (cid "G" 5)))
          pending (play eligible card)]
      (is (= :card-action (op pending)))
      (is (= (dec (stock eligible 0 :food)) (stock pending 0 :food))))
    (is (nil? (op (play (own s 0 (c/id :a109)) card))))))

(deftest camembert-assigns-three-distinct-revealed-spaces-and-next-users-collect
  (let [s (scenario "camembert") selected [:meeting :grain :wood]
        done (resolve! (reload s) {:spaces selected})]
    (is (= 0 (get-in s [:occupied :meeting])))
    (is (= (:goods (g/p s 0)) (:goods (g/p done 0))) "The occupied space does not pay immediately.")
    (doseq [space selected] (is (= {:food 1} (get-in done [:space-bonuses space]))))
    (doseq [spaces [nil [] [:wood] [:wood :wood :grain] [:wood :grain :vegetable] [:wood :grain :clay :reed]]]
      (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:spaces spaces}))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:skip? true})))
    (let [collected (g/command done 1 {:type :action :space :grain})]
      (is (= (inc (stock done 1 :food)) (stock collected 1 :food)))
      (is (= (inc (stock done 1 :grain)) (stock collected 1 :grain)))
      (is (nil? (get-in collected [:space-bonuses :grain])))
      (is (= {:food 1} (get-in collected [:space-bonuses :meeting]))))
    (let [stacked (resolve! (assoc-in s [:space-bonuses :wood :food] 2) {:spaces selected})]
      (is (= 3 (get-in stacked [:space-bonuses :wood :food]))))
    (doseq [cmd (bot/decision-candidates s 0)]
      (is (= 3 (count (:spaces cmd))))))
  (is (thrown? clojure.lang.ExceptionInfo (play (rich (fresh)) (cid "FR" 12)))))

(deftest coffee-break-cancels-only-its-own-future-food-on-owners-occupation
  (let [card (cid "FR" 15) flower (cid "WM" 82) s (scenario "coffee-break")
        done (g/command (reload s) 0 {:type :action :space :lessons :card (cid "G" 5)})]
    (is (= #{3 4 5 6} (set (keys (deliveries s card)))))
    (is (empty? (deliveries done card)))
    (is (= (deliveries s flower) (deliveries done flower)))
    (is (= (stock s 0 :food) (stock done 0 :food)))
    (is (= #{3 4 5 6} (set (keys (deliveries (emit s :occupation 1) card)))))
    (is (= #{3 4 5 6} (set (keys (deliveries (emit s :build-improvement 0) card)))))
    (let [next-round (g/drain (g/begin-round done))]
      (is (= (stock done 0 :food) (stock next-round 0 :food)))))
  (let [card (cid "FR" 15) s (-> (fresh) rich (play card))
        ended (nth (iterate #(g/drain (g/begin-round %)) s) 5)]
    (is (= #{2 3 4 5 6} (set (keys (deliveries s card)))))
    (is (= (+ 5 (stock s 0 :food)) (stock ended 0 :food)))
    (is (empty? (deliveries ended card)))
    (is (= (stock ended 0 :food) (stock (emit ended :occupation 0) 0 :food))))
  (let [s (-> (fresh) rich (scenarios/prepared-round 13) (play (cid "FR" 15)))]
    (is (= #{14} (set (keys (deliveries s (cid "FR" 15))))))))
