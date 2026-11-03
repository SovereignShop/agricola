(ns agricola.community-batch-eleven-test
  (:require [clojure.test :refer :all]
            [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [agricola.card-expressions :as expr]
            [agricola.community-decks-test :refer [cid fresh rich own play reload resolve! emit op stock field]]))
(defn hand [s id] (assoc-in s [:players 0 :hand (:kind (c/card id))] [id]))
(defn round-state [s n] (g/drain (g/begin-round (assoc s :round (dec n)))))
(defn effect [s card operation context]
  (g/drain (g/queue-front s [(g/task 0 card operation context)])))
(defn bonus [s] (get-in s [:players 0 :bonus-points] 0))

(deftest animal-holder-awards-every-tie-and-remaining-round-wood
  (doseq [[round wood] [[1 4] [5 4] [6 3] [8 3] [9 2] [11 2] [12 1] [13 1] [14 0]]]
    (let [s (assoc (rich (fresh)) :round round) done (play s (cid "G" 3))]
      (is (= (+ wood (stock s 0 :wood)) (stock done 0 :wood)))))
  (let [card (cid "G" 3) s (-> (fresh) (own 0 card)
                              (assoc-in [:players 0 :goods :sheep] 2)
                              (assoc-in [:players 1 :goods :cattle] 2)
                              (assoc-in [:players 2 :goods :boar] 1))]
    (is (= [3 3 0 0] (mapv #(g/card-bonus s % card) (range 4))))))

(deftest apprentice-counts-printed-value-majors-including-dual-upgrades
  (let [s (-> (rich (fresh)) (own 0 (cid "G" 4))
              (assoc-in [:players 0 :improvements] #{:fireplace-2 :hearth-4 :well :clay-oven :joinery})
              (own 0 :catalog/original-base-e-14-baker-s-oven)
              (own 0 :catalog/original-base-i-66-village-well))]
    (is (= (+ 5 (stock s 0 :food)) (stock (emit s :before-field-phase 0) 0 :food)))
    (is (= (stock s 0 :food) (stock (emit s :before-field-phase 0 {:manure? true}) 0 :food))))
  (let [s (-> (rich (fresh)) (own 0 (cid "G" 4)))]
    (is (= (stock s 0 :food) (stock (emit s :before-field-phase 0) 0 :food)))))

(deftest glutton-spends-only-after-normal-final-feeding-with-six-point-cap
  (doseq [points (range 1 7)]
    (let [s (-> (rich (fresh)) (own 0 (cid "G" 23))
                (assoc :round 14 :phase :feeding :fed #{} :turn 0)
                (assoc-in [:players 0 :goods :food] (+ 4 (* 3 points))))
          pending (g/command s 0 {:type :feed})
          done (resolve! (reload pending) {:option (dec points)})]
      (is (= :choose (op pending)))
      (is (= (* 3 points) (stock pending 0 :food)))
      (is (= 0 (stock done 0 :food)))
      (is (= points (bonus done)))
      (is (contains? (:fed done) 0))
      (is (not= :choose (op done)))
      (is (= (* 3 points) (stock (resolve! pending {:skip? true}) 0 :food)))))
  (let [s (-> (rich (fresh)) (own 0 (cid "G" 23))
              (assoc :round 13 :phase :feeding :fed #{} :turn 0))]
    (is (nil? (op (g/command s 0 {:type :feed})))))
  (let [s (-> (rich (fresh)) (own 0 (cid "G" 23))
              (assoc :round 14 :phase :feeding :fed #{} :turn 0))
        pending (g/command s 0 {:type :feed})]
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:option 6})))
    (let [unfunded (g/command (-> s (assoc-in [:players 0 :goods :food] 4) (assoc-in [:players 0 :goods :grain] 0) (assoc-in [:players 0 :goods :vegetable] 0)) 0 {:type :feed})]
      (is (thrown? clojure.lang.ExceptionInfo (resolve! unfunded {:option 0})))
      (is (= 0 (bonus (resolve! unfunded {:skip? true})))))))

(deftest hobbyist-ringmaster-and-wintercrafter-use-distinct-major-entry-costs
  (doseq [[deck number event actor context food grain]
          [["G" 32 :occupation 1 {} 2 0]
           ["G" 48 :use-space 0 {:space :traveling} 0 1]
           ["G" 60 :after-field-phase 0 {} 2 0]]]
    (let [s (-> (rich (fresh)) (own 0 (cid deck number)) (hand (cid "G" 81)))
          pending (emit s event actor context)
          major (resolve! (reload pending) {:improvement :fireplace-2})
          minor (resolve! pending {:card (cid "G" 81)})]
      (is (= :card-action (op pending)))
      (is (= (- (stock s 0 :food) food) (stock major 0 :food)))
      (is (= (- (stock s 0 :grain) grain) (stock major 0 :grain)))
      (is (= (- (stock s 0 :clay) 2) (stock major 0 :clay)))
      (is (= (- (stock s 0 :food) (if (= number 32) 1 0)) (stock minor 0 :food)))
      (is (= (stock s 0 :grain) (stock minor 0 :grain)))
      (is (= (:goods (g/p s 0)) (:goods (g/p (resolve! pending {:skip? true}) 0))))))
  (is (nil? (op (emit (-> (rich (fresh)) (own 0 (cid "G" 32))) :occupation 0))))
  (is (nil? (op (emit (-> (rich (fresh)) (own 0 (cid "G" 60))) :after-field-phase 0 {:manure? true}))))
  (let [s (-> (rich (fresh)) (own 0 (cid "G" 48)) (hand (cid "G" 81)) (assoc-in [:players 0 :goods :grain] 0))
        pending (emit s :use-space 0 {:space :traveling})]
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:improvement :fireplace-2})))
    (is (some #{(cid "G" 81)} (:minors (g/p (resolve! pending {:card (cid "G" 81)}) 0))))))

(deftest holiday-present-pays-one-alternative-and-passes-after-choice
  (doseq [[index resource] [[0 :wood] [1 :clay]]]
    (let [s (-> (rich (fresh)) (assoc-in [:players 0 :begging] 1))
          pending (play s (cid "G" 83) {:payment-option index})
          done (resolve! (reload pending) {:option 0})]
      (is (= (dec (stock s 0 resource)) (stock done 0 resource)))
      (is (= (stock s 0 (if (= resource :wood) :clay :wood)) (stock done 0 (if (= resource :wood) :clay :wood))))
      (is (= 0 (get-in done [:players 0 :begging])))
      (is (some #{(cid "G" 83)} (get-in done [:players 1 :hand :minor])))))
  (let [pending (play (rich (fresh)) (cid "G" 83))]
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:option 0})))
    (doseq [[index resource] [[1 :sheep] [2 :boar] [3 :cattle]]]
      (is (= 1 (stock (resolve! pending {:option index}) 0 resource))))))

(deftest pumpkin-pie-uses-or-cost-and-one-bonus-per-real-baking-action
  (doseq [[index resource amount] [[0 :grain 2] [1 :vegetable 1]]]
    (let [s (-> (rich (fresh)) (assoc-in [:players 0 :improvements] #{:clay-oven}))
          pending (play s (cid "G" 98) {:payment-option index})
          baked (resolve! pending {:grain 1})
          done (resolve! (reload baked) {})]
      (is (= :bake (op pending)))
      (is (= (- (stock s 0 resource) amount) (stock pending 0 resource)))
      (is (= :points (op baked)))
      (is (= 3 (bonus done)))
      (is (= (dec (stock baked 0 :grain)) (stock done 0 :grain)))
      (is (= (dec (stock baked 0 :vegetable)) (stock done 0 :vegetable)))
      (is (= 0 (bonus (resolve! baked {:skip? true}))))))
  (let [s (-> (rich (fresh)) (own 0 (cid "G" 98)))]
    (is (nil? (op (emit s :bake 0 {:grain-baked 0}))))
    (doseq [n [1 2 3]]
      (is (= 3 (bonus (resolve! (emit s :bake 0 {:grain-baked n}) {})))))))

(deftest fish-farm-selects-one-crop-from-each-chosen-field-without-harvesting
  (let [card (cid "G" 115)
        s (-> (rich (fresh)) (own 0 card) (field 0 :grain 3) (field 1 :vegetable 2)
              (assoc-in [:players 0 :farm 0 :under-crops] {:vegetable 1})
              (assoc-in [:players 0 :card-fields :extra] {:type :field :crop :grain :count 2})
              (assoc-in [:players 0 :card-fields :wood] {:type :wood-stack :crop :wood :count 3}))
        pending (emit s :use-space 1 {:space :fishing})
        done (resolve! (reload pending) {:plantings [{:cell 0 :crop :vegetable} {:cell :extra :crop :grain}]})]
    (is (= :take-field-crops (op pending)))
    (is (= (inc (stock s 0 :grain)) (stock done 0 :grain)))
    (is (= (inc (stock s 0 :vegetable)) (stock done 0 :vegetable)))
    (is (= 3 (get-in done [:players 0 :farm 0 :count])))
    (is (= 2 (get-in done [:players 0 :farm 1 :count])))
    (is (= 1 (get-in done [:players 0 :card-fields :extra :count])))
    (is (= 3 (get-in done [:players 0 :card-fields :wood :count])))
    (is (nil? (op (emit s :use-space 0 {:space :fishing}))))
    (is (= (:farm (g/p s 0)) (:farm (g/p (resolve! pending {:skip? true}) 0))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:plantings [{:cell 0 :crop :grain} {:cell 0 :crop :vegetable}]})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:plantings [{:cell :wood :crop :wood}]})))))

(deftest bread-carrier-schedules-once-after-any-players-oven
  (let [card (cid "WM" 8) s (play (rich (fresh)) card)
        before (emit s :check-state 1)
        oven (assoc-in s [:players 1 :improvements] #{:clay-oven})
        done (emit oven :check-state 1)
        again (emit (assoc-in done [:players 2 :improvements] #{:stone-oven}) :check-state 2)]
    (is (empty? (:schedule (g/p before 0))))
    (is (= (set (range 2 15)) (set (keys (:schedule (g/p done 0))))))
    (is (= (:schedule (g/p done 0)) (:schedule (g/p again 0))))
    (is (= (inc (stock done 0 :food)) (stock (round-state done 2) 0 :food))))
  (let [s (-> (rich (fresh)) (assoc :round 10) (assoc-in [:players 1 :improvements] #{:clay-oven}))
        done (play s (cid "WM" 8))]
    (is (= #{11 12 13 14} (set (keys (:schedule (g/p done 0))))))))

(deftest heiress-builds-discounted-matching-material-room-at-round-start
  (doseq [[round house material-cost] [[6 :wood 5] [10 :clay 4] [13 :stone 3]]]
    (let [s (-> (rich (fresh)) (assoc-in [:players 0 :house] house) (play (cid "WM" 27)))
          pending (round-state s round)
          done (resolve! (reload pending) {:rooms [0]})]
      (is (= :build-rooms (op pending)))
      (is (= 3 (count (a/tiles (g/p done 0) :room))))
      (is (= (- (stock s 0 house) material-cost) (stock done 0 house)))
      (is (= (- (stock s 0 :reed) 2) (stock done 0 :reed)))
      (is (= 2 (count (a/tiles (g/p (resolve! pending {:skip? true}) 0) :room))))
      (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:rooms [0 1]})))))
  (let [s (play (rich (fresh)) (cid "WM" 27))]
    (is (nil? (op (round-state s 10))))
    (is (= {:wood-room 1} (:goods (first (get (g/future-round-resources s) 6)))))))

(deftest hoarder-scores-different-building-types-not-total-markers
  (let [card (cid "WM" 30) s (-> (rich (fresh)) (own 0 card))]
    (loop [s s indexes [0 0 1 2 3] scores [1 1 2 3 5]]
      (when-let [index (first indexes)]
        (let [pending (emit s :feeding-start 0) done (resolve! (reload pending) {:option index})]
          (is (= (first scores) (g/card-bonus done 0 card)))
          (is (= (:goods (g/p s 0)) (:goods (g/p (resolve! pending {:skip? true}) 0))))
          (recur done (rest indexes) (rest scores)))))))

(deftest master-stableman-builds-before-animals-and-costs-one-wood-each
  (let [s (-> (rich (fresh)) (own 0 (cid "WM" 37)) (assoc-in [:piles :sheep] {:sheep 3}))
        pending (g/command s 0 {:type :action :space :sheep})
        done (resolve! (reload pending) {:stables [0 1]})]
    (is (= :build-stables (op pending)))
    (is (= 0 (stock pending 0 :sheep)))
    (is (= 3 (stock done 0 :sheep)))
    (is (= 2 (get-in pending [:players 0 :remaining])))
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (= 0 (get-in done [:occupied :sheep])))
    (is (= 3 (stock (resolve! pending {:skip? true}) 0 :sheep)))
    (is (= (- (stock s 0 :wood) 2) (stock done 0 :wood)))
    (is (true? (get-in done [:players 0 :farm 0 :stable])))
    (is (true? (get-in done [:players 0 :farm 1 :stable])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:stables [0 1 2]}))))
  (let [s (-> (rich (fresh)) (own 0 (cid "WM" 37)) (assoc-in [:piles :sheep] {:sheep 1}))]
    (is (nil? (op (g/command s 0 {:type :action :space :sheep}))))))

(deftest room-food-uses-physical-rooms-and-independent-card-limits
  (let [tapestry (cid "WM" 50) cookies (cid "WM" 71)
        s (-> (rich (fresh)) (own 0 tapestry) (own 0 cookies) (assoc-in [:players 0 :goods :sheep] 3)
              (assoc-in [:players 0 :fences] (set (a/edges 4))) (assoc-in [:players 0 :farm 4 :stable] true))
        pending (emit s :field-phase 0)
        woven (resolve! (reload pending) {:cells [5]})
        offered (emit woven :bake 0 {:grain-baked 2})
        baked (resolve! (reload offered) {:cells [5 10]})]
    (is (= :room-food (op pending)))
    (is (= (stock s 0 :food) (stock woven 0 :food)))
    (is (= 1 (g/card-bonus woven 0 tapestry)))
    (is (= (- (stock woven 0 :food) 2) (stock baked 0 :food)))
    (is (= 2 (g/card-bonus baked 0 cookies)))
    (is (= 1 (g/card-bonus baked 0 tapestry)))
    (is (= 1 (g/card-bonus (assoc-in baked [:players 0 :farm 5] {:type :empty}) 0 cookies)))
    (is (= 0 (g/card-bonus (assoc-in baked [:players 0 :farm 5] {:type :empty}) 0 tapestry)))
    (is (nil? (op (emit baked :bake 0 {:grain-baked 1}))))
    (is (nil? (op (emit s :field-phase 0 {:manure? true}))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:cells [0]})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:cells [5 10]})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! offered {:cells [5 5]})))
    (is (= (stock woven 0 :food) (stock (resolve! offered {:skip? true}) 0 :food)))))

(deftest crib-recognizes-growth-before-being-played-and-its-points-persist
  (let [card (cid "WM" 72)
        s (-> (rich (fresh)) (hand card) (round-state 5) (assoc-in [:players 0 :farm 0] {:type :room}))
        grown (g/command s 0 {:type :action :space :growth})
        owned (play grown card)
        pending (emit owned :return-home 0)
        food (resolve! (reload pending) {:option 0})
        point (resolve! pending {:option 1})]
    (is (= :choose (op pending)))
    (is (= (+ 2 (stock owned 0 :food)) (stock food 0 :food)))
    (is (= (- (stock owned 0 :food) 2) (stock point 0 :food)))
    (is (= 1 (bonus point)))
    (is (= 1 (bonus (update-in point [:players 0 :minors] #(vec (remove #{card} %))))))
    (is (nil? (op (emit (round-state (resolve! pending {:skip? true}) 6) :return-home 0))))))

(deftest home-garden-schedules-for-renovation-before-or-after-play
  (let [card (cid "WM" 89)
        s (-> (rich (fresh)) (hand card) (round-state 6))
        renovated (g/command s 0 {:type :action :space :renovate})
        after (play renovated card)]
    (is (= #{7 8 9} (set (keys (:schedule (g/p after 0))))))
    (is (= (inc (stock after 0 :food)) (stock (round-state after 7) 0 :food)))
    (is (empty? (:schedule (g/p (play (round-state renovated 7) card) 0)))))
  (let [s (-> (rich (fresh)) (play (cid "WM" 89)) (round-state 6))
        done (g/command s 0 {:type :action :space :renovate})]
    (is (= #{7 8 9} (set (keys (:schedule (g/p done 0))))))))

(deftest evening-prayer-plays-at-most-two-occupations-for-one-food-each
  (doseq [n [0 1 2 3]]
    (let [s (reduce #(field %1 %2 nil 0) (rich (fresh)) (range n))
          first-occ (cid "G" 21) second-occ (cid "WM" 17)
          s (assoc-in s [:players 0 :hand :occupation] [first-occ second-occ])
          pending (play s (cid "FR" 19))
          ;; Hermit can be followed by another occupation; its six-point condition is scoring only.
          after (if (pos? n) (resolve! (reload pending) {:card first-occ}) pending)
          done (if (> n 1) (resolve! after {:card second-occ}) after)]
      (is (= (min 2 n) (count (:occupations (g/p done 0)))))
      (is (= (- (stock s 0 :food) (min 2 n)) (stock done 0 :food)))))
  (is (thrown? clojure.lang.ExceptionInfo (play (-> (rich (fresh)) (own 0 (cid "G" 21))) (cid "FR" 19)))))

(deftest golden-rose-discounts-total-food-but-not-extra-occupation-cost
  (let [s (-> (rich (fresh)) (own 0 (cid "FR" 24)))
        chief :catalog/original-base-e-172-chief]
    (doseq [cost [0 1 2 3 4]]
      (is (= (max 0 (- cost 2)) (:food (g/card-cost s 0 (c/card (cid "G" 21)) cost))))
      (is (= (+ 2 (max 0 (- cost 2))) (:food (g/card-cost s 0 (c/card chief) cost)))))
    (let [minor (cid "WM" 95) s (-> s (own 0 (cid "G" 32)) (hand minor))
          done (resolve! (emit s :occupation 1) {:card minor})]
      (is (= (stock s 0 :food) (stock done 0 :food)) "One entry food plus one printed food share the two-food discount."))
    (let [;; Test the common card-action path with a three-food entry fee and one-food print.
          target (cid "WM" 95) s (hand s target)
          pending (effect s (cid "G" 32) {:op :card-action :kinds #{:minor} :cost {:food 3} :optional? true} {})
          done (resolve! pending {:card target})]
      (is (= (- (stock s 0 :food) 2) (stock done 0 :food)))))
  (let [s (-> (rich (fresh)) (own 0 (cid "FR" 24)) (own 0 (cid "G" 32)))
        done (resolve! (emit s :occupation 1) {:improvement :fireplace-2})]
    (is (= (- (stock s 0 :food) 2) (stock done 0 :food)) "Major entry fees are not discounted.")))

(deftest heatwave-free-fireplace-or-returned-fireplace-upgrade-only
  (let [s (-> (rich (fresh)) (assoc-in [:players 0 :house] :clay))
        pending (play s (cid "FR" 31))
        done (resolve! (reload pending) {:improvement :fireplace-2})]
    (is (= (dec (stock s 0 :stone)) (stock done 0 :stone)))
    (is (= (stock s 0 :clay) (stock done 0 :clay)))
    (is (contains? (:improvements (g/p done 0)) :fireplace-2))
    (is (some #{(cid "FR" 31)} (get-in done [:players 1 :hand :minor])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:improvement :hearth-4})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:improvement :well})))
    (let [s (-> s (assoc-in [:players 0 :improvements] #{:fireplace-3}) (update :available-improvements disj :fireplace-3))
          done (resolve! (play s (cid "FR" 31)) {:improvement :hearth-4 :upgrade :fireplace-3})]
      (is (= #{:hearth-4} (:improvements (g/p done 0))))
      (is (contains? (:available-improvements done) :fireplace-3)))))

(deftest march-pays-two-different-animals-only-when-two-fields-are-placed
  (let [s (-> (rich (fresh)) (assoc-in [:players 0 :goods :sheep] 1) (assoc-in [:players 0 :goods :boar] 1)
              (assoc-in [:players 0 :fences] (set (a/edges 4))))
        pending (play s (cid "FR" 36))
        cells (resolve! pending {:option 0})
        done (resolve! (reload cells) {:cells [0 1]})]
    (is (= :plow-many (op cells)))
    (is (= 1 (stock cells 0 :sheep)))
    (is (= 0 (stock done 0 :sheep) (stock done 0 :boar)))
    (is (= #{0 1} (set (a/tiles (g/p done 0) :field))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! cells {:cells [0]})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! cells {:cells [0 2]})))
    (is (= 1 (stock (resolve! cells {:skip? true}) 0 :sheep)))))

(deftest drawing-genius-only-follows-the-current-newest-round-card
  (let [s (-> (rich (fresh)) (own 0 (cid "FR" 78)) (hand (cid "G" 81)) (round-state 5))]
    (is (nil? (op (emit s :after-use-space 0 {:space :sheep}))))
    (is (nil? (op (emit s :after-use-space 1 {:space :growth}))))
    (let [pending (emit s :after-use-space 0 {:space :growth})
          done (resolve! (reload pending) {:card (cid "G" 81)})]
      (is (= :card-action (op pending)))
      (is (some #{(cid "G" 81)} (:minors (g/p done 0)))))))

(deftest lemon-trader-shares-a-two-use-limit-between-both-recipes
  (let [card (cid "FR" 90) s (-> (rich (fresh)) (own 0 card))
        first (g/command (reload s) 0 {:type :ability :card card :option 0})
        done (g/command first 0 {:type :ability :card card :option 9})]
    (is (= (dec (stock s 0 :grain)) (stock done 0 :grain)))
    (is (= (dec (stock s 0 :vegetable)) (stock done 0 :vegetable)))
    (doseq [resource [:wood :reed :stone]] (is (= (inc (stock s 0 resource)) (stock done 0 resource))))
    (is (thrown? clojure.lang.ExceptionInfo (g/command done 0 {:type :ability :card card :option 1})))
    (is (g/anytime-available? (round-state done 2) (g/p (round-state done 2) 0) card))
    (is (thrown? clojure.lang.ExceptionInfo (g/command s 0 {:type :ability :card card :option 10})))
    (is (thrown? clojure.lang.ExceptionInfo (g/command s 0 {:type :ability :card card :option 0 :times 3})))))

(deftest pasteurization-expert-takes-stack-top-for-receipts-but-not-births
  (let [card (cid "FR" 98) s (play (rich (fresh)) card)
        received (effect s nil {:op :gain :goods {:sheep 1}} {})]
    (is (= [:sheep :boar :sheep :cattle] (get-in s [:players 0 :card-state card :resource-stack])))
    (is (= 2 (stock received 0 :sheep)))
    (is (= [:boar :sheep :cattle] (get-in received [:players 0 :card-state card :resource-stack])))
    (let [wrong (effect received nil {:op :gain :goods {:sheep 1}} {})
          next (effect (reload wrong) nil {:op :gain :goods {:boar 1}} {})]
      (is (= 3 (stock wrong 0 :sheep)))
      (is (= 2 (stock next 0 :boar)))
      (is (= [:sheep :cattle] (get-in next [:players 0 :card-state card :resource-stack]))))
    (let [breeding (-> s (assoc :phase :breeding :turn 0 :fed #{0 1 2 3} :bred #{})
                       (assoc-in [:players 0 :goods :sheep] 2)
                       (assoc-in [:players 0 :fences] (set (a/edges 0)))
                       (assoc-in [:players 0 :farm 0 :stable] true))
          bred (g/command breeding 0 {:type :breed :offspring {:sheep 1}})]
      (is (= 3 (stock bred 0 :sheep)))
      (is (= [:sheep :boar :sheep :cattle] (get-in bred [:players 0 :card-state card :resource-stack]))))))

(deftest stableman-triggers-construction-cards-before-collecting-source-animals
  (doseq [[round space animal] [[1 :sheep :sheep] [9 :boar :boar] [10 :cattle :cattle]]]
    (let [architect (cid "G" 5)
          s (-> (rich (fresh)) (own 0 (cid "WM" 37)) (own 0 architect)
                (round-state round) (assoc-in [:piles space] {animal 2}))
          pending (g/command s 0 {:type :action :space space})
          observed (atom [])
          done (binding [g/*impact-observer*
                         (fn [before after head _]
                           (when (and (= architect (:card head)) (= :gain (get-in head [:effect :op])))
                             (swap! observed conj {:animal (stock before 0 animal)
                                                   :occupied (get-in before [:occupied space])
                                                   :food (- (stock after 0 :food) (stock before 0 :food))})))]
                 (resolve! (reload pending) {:stables [0]}))
          declined (resolve! pending {:skip? true})]
      (is (= [{:animal 0 :occupied nil :food 1}] @observed))
      (is (= (+ 1 (stock s 0 :food)) (stock done 0 :food)))
      (is (= 2 (stock done 0 animal)))
      (is (some? (a/animal-housing (g/p done 0))))
      (is (= 1 (get-in done [:players 0 :remaining]) (get-in declined [:players 0 :remaining])))
      (is (= 0 (get-in done [:occupied space]) (get-in declined [:occupied space])))
      (is (= 2 (stock declined 0 animal)))
      (is (= (stock s 0 :wood) (stock declined 0 :wood)))
      (is (= (stock s 0 :food) (stock declined 0 :food)))
      (is (not (get-in declined [:players 0 :farm 0 :stable]))))))

(deftest pasteurization-expert-ignores-all-breeding-phase-receipts
  (let [card (cid "FR" 98)
        s (-> (rich (fresh)) (play card)
              (assoc-in [:players 0 :fences] (set (a/edges 0)))
              (assoc-in [:players 0 :farm 0 :stable] true))
        breeding (assoc s :phase :breeding)
        received (effect breeding nil {:op :gain :goods {:sheep 2}} {})]
    (is (= 2 (stock received 0 :sheep)))
    (is (= (get-in s [:players 0 :card-state card :resource-stack])
           (get-in received [:players 0 :card-state card :resource-stack])))
    (let [outside (effect s nil {:op :gain :goods {:sheep 2}} {})]
      (is (= 3 (stock outside 0 :sheep)))
      (is (= [:boar :sheep :cattle] (get-in outside [:players 0 :card-state card :resource-stack]))))
    (let [offered (effect s nil {:op :gain :goods {:sheep 1 :boar 1}} {})
          ;; One receipt may order its two species; no species is processed twice.
          opts (g/resource-seller-options offered 0 card {:sheep 1 :boar 1})]
      (is (= :resource-seller-receipt (op offered)))
      (is (= #{{:sheep 1 :boar 1} {:sheep 1}} (set (map :goods opts)))))))

(deftest home-garden-on-the-renovation-action-schedules-each-future-round-once
  (let [garden (cid "WM" 89)
        s (-> (rich (fresh)) (hand garden) (round-state 6))
        done (g/command s 0 {:type :action :space :renovate :card garden})]
    (is (= :clay (:house (g/p done 0))))
    (is (= #{7 8 9} (set (keys (:schedule (g/p done 0))))))
    (doseq [round [7 8 9]]
      (is (= 1 (count (get-in done [:players 0 :schedule round]))))
      (is (= {:food 1} (:goods (first (get-in done [:players 0 :schedule round])))))))
  (let [garden (cid "WM" 89) starter (cid "G" 13)
        s (-> (rich (fresh)) (round-state 6)
              (assoc-in [:players 0 :hand :minor] [garden])
              (assoc-in [:players 0 :hand :occupation] [starter]))
        clay (g/command s 0 {:type :action :space :lessons :card starter})
        ;; The other three seats take native turns before the second renovation.
        again (-> clay (g/command 1 {:type :action :space :wood})
                  (g/command 2 {:type :action :space :reed})
                  (g/command 3 {:type :action :space :grain}))
        stone (g/command again 0 {:type :action :space :renovate :card garden})]
    (is (= :clay (:house (g/p clay 0))))
    (is (= :stone (:house (g/p stone 0))))
    (doseq [round [7 8 9]]
      (is (= 1 (count (get-in stone [:players 0 :schedule round])))))))

(deftest crib-played-on-the-growth-action-sees-that-growth-at-return-home
  (let [card (cid "WM" 72)
        s (-> (rich (fresh)) (round-state 5) (hand card)
              (assoc-in [:players 0 :farm 0] {:type :room})
              (update :players #(mapv (fn [p] (assoc p :remaining (if (= 0 (:id p)) 1 0))) %)))
        pending (g/command s 0 {:type :action :space :growth :card card})
        done (resolve! (reload pending) {:option 0})]
    (is (= :choose (op pending)))
    (is (= 3 (:family (g/p pending 0))))
    (is (= card (get-in pending [:effect-queue 0 :card])))
    (is (= (+ 2 (stock pending 0 :food)) (stock done 0 :food)))
    (is (= (inc (:round pending)) (:round done))))
  (let [s (-> (rich (fresh)) (hand (cid "WM" 72)) (round-state 5))
        done (g/command s 0 {:type :action :space :meeting :card (cid "WM" 72)})]
    (is (nil? (op (emit done :return-home 0))))))

(deftest golden-rose-combines-entry-and-print-discounts-with-engineer-income
  (let [rose (cid "FR" 24) hobby (cid "G" 32) engineer (cid "WM" 17)
        expensive (c/id :b010)
        s (-> (rich (fresh)) (own 0 rose) (own 0 hobby) (hand expensive)
              (assoc-in [:players 0 :goods :food] 2))
        pending (emit s :occupation 1)
        done (resolve! pending {:card expensive})]
    (is (= 0 (stock done 0 :food)) "One entry food + three printed food − two discount = two paid.")
    (is (= (- (stock s 0 :wood) 3) (stock done 0 :wood)))
    (let [empty (assoc-in s [:players 0 :goods :food] 0)]
      (is (thrown? clojure.lang.ExceptionInfo (resolve! (emit empty :occupation 1) {:card expensive}))))
    (let [s (-> s (own 0 engineer) (assoc-in [:players 0 :goods :food] 1))
          done (resolve! (emit s :occupation 1) {:card expensive})]
      (is (= 0 (stock done 0 :food)) "Engineer's one food is available toward the printed cost."))
    (let [minor :catalog/original-base-e-45-private-forest
          s (-> s (own 0 engineer) (hand minor) (assoc-in [:players 0 :goods :food] 0))
          done (resolve! (emit s :occupation 1) {:card minor})]
      (is (= 0 (stock done 0 :food)))
      (is (some #{minor} (:minors (g/p done 0)))))))

(deftest apprentice-counts-all-upgraded-majors-with-two-printed-points
  (let [upgrades [:catalog/original-base-e-14-baker-s-oven :catalog/original-base-i-65-baker-s-kitchen
                  :catalog/original-base-i-66-village-well :catalog/original-base-i-85-cooking-corner
                  :catalog/original-base-k-106-bakehouse :catalog/original-base-k-122-sawmill
                  :catalog/original-expansion-wm-86-glassblowers-shop :catalog/original-expansion-fr-41-peasants-boutique]]
    (doseq [upgrade upgrades]
      (let [s (-> (rich (fresh)) (own 0 (cid "G" 4)) (own 0 upgrade))
            done (emit s :before-field-phase 0)]
        (is (= (inc (stock s 0 :food)) (stock done 0 :food)) (str upgrade))))))

(deftest tapestry-weaver-observes-the-sheep-market-before-field-phase-exchange
  (let [s (-> (rich (fresh)) (own 0 (cid "WM" 50)) (own 0 (cid "G" 107)) (round-state 4)
              (assoc-in [:players 0 :goods :sheep] 2)
              (assoc-in [:players 0 :fences] (set (a/edges 4)))
              (assoc-in [:players 0 :farm 4 :stable] true)
              (update :players #(mapv (fn [p] (assoc p :remaining (if (= 0 (:id p)) 1 0))) %)))
        market (g/command s 0 {:type :action :space :grain})
        tapestry (resolve! market {:option 0})
        done (resolve! (reload tapestry) {:cells [5]})]
    (is (= (cid "G" 107) (get-in market [:effect-queue 0 :card])))
    (is (= 3 (stock tapestry 0 :sheep)))
    (is (= :room-food (op tapestry)))
    (is (= (cid "WM" 50) (get-in tapestry [:effect-queue 0 :card])))
    (is (= 1 (g/card-bonus done 0 (cid "WM" 50))))))

(deftest glutton-last-feeder-can-convert-food-before-breeding-resumes
  (let [s (-> (rich (fresh)) (own 0 (cid "G" 23))
              (assoc :round 14 :phase :feeding :fed #{1 2 3} :bred #{} :turn 0)
              (assoc-in [:players 0 :goods :food] 4)
              (assoc-in [:players 0 :goods :grain] 3)
              (assoc-in [:players 0 :goods :vegetable] 0))
        pending (g/command s 0 {:type :feed})
        funded (g/command (reload pending) 0 {:type :convert :goods {:grain 3}})
        done (resolve! funded {:option 0})]
    (is (= :feeding (:phase pending)))
    (is (= :choose (op pending)))
    (is (= #{0 1 2 3} (:fed pending)))
    (is (= 0 (stock pending 0 :food)))
    (is (= 3 (stock funded 0 :food)))
    (is (= 1 (bonus done)))
    (is (= 0 (stock done 0 :food)))
    (is (= :breeding (:phase done)))
    (is (= :breeding (:phase (resolve! pending {:skip? true}))))
    (is (thrown? clojure.lang.ExceptionInfo (g/command pending 0 {:type :breed})))))

(deftest reserve-cards-retain-round-facts-for-later-same-round-play
  (let [garden (cid "WM" 89)
        s (-> (rich (fresh)) (round-state 6) (assoc :minor-deck [garden]))
        renovated (g/command s 0 {:type :action :space :renovate})
        played (play renovated garden)]
    (is (= 6 (get-in renovated [:round-facts 0 :renovate])))
    (is (= #{7 8 9} (set (keys (:schedule (g/p played 0)))))))
  (let [crib (cid "WM" 72)
        s (-> (rich (fresh)) (round-state 5) (assoc :minor-deck [crib])
              (assoc-in [:players 0 :farm 0] {:type :room}))
        grown (g/command s 0 {:type :action :space :growth})
        pending (emit (play grown crib) :return-home 0)]
    (is (= 5 (get-in grown [:round-facts 0 :grow])))
    (is (= :choose (op pending))))
  (let [s (-> (rich (fresh)) (round-state 6) (assoc :minor-deck [] :occupation-deck []))
        done (g/command s 0 {:type :action :space :renovate})]
    (is (not (contains? done :round-facts)) "Unrelated old games keep their original state shape.")))

(deftest bread-carrier-remembers-previously-built-ovens-after-they-leave-play
  (let [carrier (cid "WM" 8) kitchen :catalog/original-base-i-65-baker-s-kitchen
        s (-> (rich (fresh)) (round-state 2) (assoc :occupation-deck [carrier]))
        built (g/command s 0 {:type :action :space :improvement :improvement :clay-oven})
        upgraded (resolve! (play built kitchen) {:skip? true})
        removed (update-in upgraded [:players 0] g/remove-owned-improvement kitchen)
        played (play (reload removed) carrier)]
    (is (true? (:oven-built? built)))
    (is (empty? (a/returnable-ovens (g/p removed 0))))
    (is (= (set (range 3 15)) (set (keys (:schedule (g/p played 0)))))))
  (let [carrier (cid "WM" 8)
        s (-> (rich (fresh)) (own 0 :catalog/original-base-i-65-baker-s-kitchen))
        played (play s carrier)]
    (is (= (set (range 2 15)) (set (keys (:schedule (g/p played 0))))) "Existing saves recognize an upgraded oven even without the new history flag.")))

(deftest golden-rose-respects-dual-improvement-classification
  (let [rose (cid "FR" 24) shop (cid "WM" 86)
        s (-> (rich (fresh)) (own 0 rose) (hand shop)
              (assoc-in [:players 0 :improvements] #{:pottery})
              (update :available-improvements disj :pottery))]
    (doseq [[kinds choice food] [[#{:minor :major} {:card shop} 0]
                               [#{:minor :major} {:card shop :as-major? true} 2]
                               [#{:major} {:card shop} 2]
                               [#{:minor} {:card shop} 0]]]
      (let [pending (effect s (cid "G" 32) {:op :card-action :kinds kinds :cost {:food 1} :major-fee 1 :optional? true} {})
            done (resolve! (reload pending) choice)]
        (is (= (- (stock s 0 :food) food) (stock done 0 :food)))
        (is (some #{shop} (:minors (g/p done 0))))
        (is (not (contains? (:improvements (g/p done 0)) :pottery)))))
    (let [s (assoc-in s [:players 0 :goods :food] 0)
          pending (effect s (cid "G" 32) {:op :card-action :kinds #{:minor :major} :cost {:food 1} :major-fee 1 :optional? true} {})]
      (is (= 0 (stock (resolve! pending {:card shop}) 0 :food)))
      (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:card shop :as-major? true})))))
  (let [s (-> (rich (fresh)) (own 0 (cid "FR" 24)) (own 0 (cid "WM" 93))
              (assoc-in [:players 0 :goods :food] 0))
        pending (effect s (cid "G" 32) {:op :card-action :kinds #{:minor} :cost {:food 1} :optional? true} {})
        done (resolve! (reload pending) {:improvement :fireplace-2})]
    (is (= 0 (stock done 0 :food)))
    (is (= (dec (stock s 0 :clay)) (stock done 0 :clay)))
    (is (contains? (:improvements (g/p done 0)) :fireplace-2))))
