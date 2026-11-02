(ns agricola.community-batch-nine-test
  (:require [clojure.test :refer :all] [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [agricola.card-expressions :as expr]
            [agricola.bot :as bot] [eurozone.card-scenarios :as scenarios]
            [agricola.community-decks-test :refer [cid fresh rich own play reload resolve! emit op stock field]]))
(defn scenario [id] (scenarios/initial-state id (:names (scenarios/options id "Ada"))))
(defn exchange [s card option] (g/command s 0 {:type :ability :card card :option option}))

(deftest reed-sale-pays-reed-gains-food-and-passes-to-the-left
  (let [s (scenario "reed-sale") card (cid "G" 100)
        done (g/command (reload s) 0 {:type :action :space :meeting :card card})]
    (is (= (dec (stock s 0 :reed)) (stock done 0 :reed)))
    (is (= (+ 3 (stock s 0 :food)) (stock done 0 :food)))
    (is (some #{card} (get-in done [:players 1 :hand :minor])))
    (is (not-any? #{card} (c/owned (g/p done 0))))
    (is (thrown? clojure.lang.ExceptionInfo (play (assoc-in s [:players 0 :goods :reed] 0) card)))))

(deftest sheepskin-rug-counts-fireplaces-not-hearths-and-never-twice
  (let [card (cid "G" 109) s (scenario "sheepskin-rug") done (play s card)]
    (is (zero? (stock done 0 :sheep)))
    (is (= 2 (g/card-bonus done 0 card)))
    (doseq [improvements [#{} #{:hearth-4} #{:hearth-4 :hearth-5}]]
      (is (zero? (g/card-bonus (assoc-in done [:players 0 :improvements] improvements) 0 card))))
    (is (= 2 (g/card-bonus (assoc-in done [:players 0 :improvements] #{:fireplace-2 :fireplace-3}) 0 card)))
    (is (= 2 (g/card-bonus (-> done (assoc-in [:players 0 :improvements] #{})
                              (own 0 :catalog/original-base-e-20-simple-fireplace)) 0 card)))
    (is (thrown? clojure.lang.ExceptionInfo (play (rich (fresh)) card)))))

(deftest sickle-counts-planted-crops-but-only-real-field-tiles
  (let [card (cid "G" 111) s (scenario "sickle")
        done (g/command s 0 {:type :action :space :plow :cell 4})]
    (is (= 0 (g/card-bonus s 0 card)))
    (is (= 3 (g/card-bonus done 0 card)))
    (doseq [[resource cell] [[:grain 0] [:vegetable 2]]]
      (is (= 0 (g/card-bonus (update-in done [:players 0] a/remove-field-crop cell resource) 0 card))))
    (is (= 0 (g/card-bonus (assoc-in s [:players 0 :card-fields :test] {:crop :grain :count 3}) 0 card)))))

(deftest stonecutters-hall-offers-three-printed-costs-and-scores-stone-thresholds
  (let [card (cid "G" 114)]
    (doseq [[option resource] [[0 :wood] [1 :clay] [2 :reed]]]
      (let [s (rich (fresh)) done (play s card {:payment-option option})]
        (is (= (- (stock s 0 resource) 3) (stock done 0 resource)))
        (is (= (- (stock s 0 :stone) 2) (stock done 0 :stone)))
        (doseq [other (remove #{resource} [:wood :clay :reed])]
          (is (= (stock s 0 other) (stock done 0 other))))))
    (doseq [[n expected] [[0 0] [1 0] [2 1] [3 1] [4 2] [5 3] [8 3]]]
      (is (= expected (g/card-bonus (assoc-in (scenario "stonecutters-hall") [:players 0 :goods :stone] n) 0 card))))))

(deftest tree-house-opens-a-family-space-only-after-renovation
  (let [card (cid "G" 116) s (scenario "tree-house")]
    (is (= 3 (a/family-capacity (g/p s 0))))
    (is (= 2 (a/family-capacity (assoc (g/p s 0) :house :wood))))
    (is (= 3 (a/family-capacity (assoc (g/p s 0) :house :stone))))
    (let [done (g/command (reload s) 0 {:type :action :space :growth})]
      (is (= 3 (get-in done [:players 0 :family])))
      (is (= 2 (count (a/tiles (g/p done 0) :room))))
      (is (= 1 (get-in done [:players 0 :newborn])))
      (is (thrown? clojure.lang.ExceptionInfo (a/grow (g/p done 0) true))))
    (is (thrown? clojure.lang.ExceptionInfo (play (rich (fresh)) card)))))

(deftest boundary-stones-counts-orthogonal-endpoints-not-all-fields
  (let [card (cid "WM" 65) s (scenario "boundary-stones")]
    (is (= 1 (g/card-bonus s 0 card)))
    (is (= 2 (g/card-bonus (g/command s 0 {:type :action :space :plow :cell 6}) 0 card)))
    (doseq [[cells expected] [[[] 0] [[0] 0] [[0 1] 1] [[0 1 2 6] 2]
                             [[1 5 6 7 11] 3] [[1 3 6 7 8 9 11 13] 5]
                             [[0 1 5 6] 0]]]
      (let [ready (reduce #(field %1 %2 nil 0) (own (fresh) 0 card) cells)]
        (is (= expected (g/card-bonus ready 0 card)) (str cells))))))

(deftest feeding-exchanges-allow-one-unit-once-during-the-feeding-phase
  (doseq [[id deck number resource reward] [["stonecutters-hall" "G" 114 :stone 3]
                                          ["brazier" "WM" 66 :vegetable 3]
                                          ["glassblowers-shop" "WM" 86 :clay 3]]]
    (let [s (scenario id) card (cid deck number) done (exchange (reload s) card 0)]
      (is (g/anytime-available? s (g/p s 0) card))
      (is (= (dec (stock s 0 resource)) (stock done 0 resource)))
      (is (= (+ reward (stock s 0 :food)) (stock done 0 :food)))
      (is (false? (boolean (g/anytime-available? done (g/p done 0) card))))
      (is (thrown? clojure.lang.ExceptionInfo (exchange (reload done) card 0)))
      (is (thrown? clojure.lang.ExceptionInfo (g/command s 0 {:type :ability :card card :option 0 :times 2})))
      (is (thrown? clojure.lang.ExceptionInfo (exchange (assoc-in s [:players 0 :goods resource] 0) card 0)))
      (doseq [phase [:work :field-harvest :breeding :finish-round]]
        (let [other-phase (assoc s :phase phase)]
          (is (not (g/anytime-available? other-phase (g/p s 0) card)))
          (is (thrown? clojure.lang.ExceptionInfo (exchange other-phase card 0)))))
      (let [declined (g/command s 0 {:type :feed})]
        (is (= (stock s 0 resource) (stock declined 0 resource)))
        ;; Feeding is shared: the exchange remains legal while another player feeds.
        (is (= (+ reward (stock declined 0 :food)) (stock (exchange declined card 0) 0 :food)))
        (is (thrown? clojure.lang.ExceptionInfo (exchange (g/command declined 1 {:type :feed}) card 0))))
      (let [next-harvest (assoc done :round 7)]
        (is (g/anytime-available? next-harvest (g/p next-harvest 0) card))
        (is (= (+ reward (stock done 0 :food)) (stock (exchange next-harvest card 0) 0 :food))))
      (is (some #(= card (:card %)) (bot/ability-candidates s 0))))))

(deftest brazier-rates-are-mutually-exclusive-and-count-as-cooking
  (let [card (cid "WM" 66)]
    (doseq [[option resource reward] [[0 :vegetable 3] [1 :sheep 2] [2 :boar 3] [3 :cattle 4]]]
      (let [s (-> (scenario "brazier") (assoc-in [:players 0 :goods :sheep] 0)
                  (assoc-in [:players 0 :goods resource] 1)) done (exchange s card option)]
        (is (= (+ reward (stock s 0 :food)) (stock done 0 :food)))
        (is (= 0 (stock done 0 resource)))
        (is (thrown? clojure.lang.ExceptionInfo (exchange done card (mod (inc option) 4))))))))

(deftest glassblowers-shop-returns-pottery-and-suppresses-only-its-bonus
  (let [card (cid "WM" 86) s (scenario "glassblowers-shop")
        repurchased (a/build-improvement s 0 {:improvement :pottery})]
    (is (not (contains? (:improvements (g/p s 0)) :pottery)))
    (is (contains? (:available-improvements s) :pottery))
    (is (thrown? clojure.lang.ExceptionInfo (play (rich (fresh)) card)))
    (doseq [[n points] [[0 0] [1 0] [2 1] [3 1] [4 2] [5 3] [8 3]]]
      (let [ready (assoc-in repurchased [:players 0 :goods :clay] n)]
        (is (= points (g/card-bonus ready 0 card)))
        (is (= 0 (get-in (a/score (g/p ready 0)) [:breakdown :bonus])))))
    (let [ready (assoc-in repurchased [:players 0 :goods :clay] 8)
          exchanged (exchange ready card 0)
          done (g/command exchanged 0 {:type :feed :craft [:pottery]})]
      (is (= 6 (stock done 0 :clay)))
      (is (= (+ (stock ready 0 :food) 1) (stock done 0 :food)))
      (is (= 2 (get-in (a/score (g/p ready 0)) [:breakdown :improvements]))))
    (is (= 1 (expr/evaluate s 0 {} [:majors])))
    (is (= 1 (expr/evaluate s 0 {} [:minors])))))

(deftest lamp-oil-funds-the-fee-before-payment-and-requires-two-animals
  (let [s (scenario "lamp-oil") card (cid "WM" 94)
        done (g/command (reload s) 0 {:type :action :space :lessons :card (cid "G" 5)})]
    (is (= 0 (stock s 0 :food) (stock done 0 :food)))
    (is (some #{(cid "G" 5)} (:occupations (g/p done 0))))
    (doseq [n [0 1]]
      (is (thrown? clojure.lang.ExceptionInfo (play (assoc-in (rich (fresh)) [:players 0 :goods :sheep] n) card))))
    (let [other (-> s (assoc :turn 1) (scenarios/put-in-hand 1 (cid "G" 5)))
          next (g/command other 1 {:type :action :space :lessons :card (cid "G" 5)})]
      (is (= 0 (stock next 0 :food))))))

(deftest worm-bait-stores-decays-on-real-harvests-and-collects-only-on-fishing
  (let [s (scenario "worm-bait") card (cid "WM" 115)
        stored #(get-in % [:players 0 :card-state card :stored-goods :food] 0)
        harvested (g/command (reload s) 0 {:type :action :space :grain})]
    (is (= 2 (stored s)))
    (is (= 1 (stored harvested)))
    (is (= 2 (stored (emit s :before-field-phase 0 {:manure? true}))))
    (is (= 2 (stored (emit s :use-space 1 {:space :fishing}))))
    (is (= 2 (stored (emit s :use-space 0 {:space :cultivation}))))
    (let [next (-> harvested (g/command 0 {:type :feed}) (g/command 1 {:type :feed})
                   (g/command 0 {:type :breed}) (g/command 1 {:type :breed}))
          collected (g/command next 0 {:type :action :space :fishing})]
      (is (= 0 (stored collected)))
      (is (= (+ (stock next 0 :food) (get-in next [:piles :fishing :food]) 1) (stock collected 0 :food))))
    (is (= 0 (stored (emit (assoc-in s [:players 0 :card-state card :stored-goods] {}) :before-field-phase 0))))))

(deftest flat-hill-renovates-wood-only-and-counts-planted-card-fields
  (let [card (cid "FR" 21) s (scenario "flat-hill") done (play s card)]
    (is (= :clay (:house (g/p done 0))))
    (is (= (:goods (g/p s 0)) (:goods (g/p done 0))))
    (is (thrown? clojure.lang.ExceptionInfo (play (rich (fresh)) card)))
    (doseq [house [:clay :stone]]
      (is (= house (:house (g/p (play (assoc-in s [:players 0 :house] house) card) 0)))))
    (let [with-card-field (-> s (assoc-in [:players 0 :farm 3] {:type :field :crop nil :count 0})
                              (assoc-in [:players 0 :card-fields :copse] {:type :wood-stack :crop :wood :count 2}))]
      (is (= :clay (:house (g/p (play with-card-field card) 0)))))))

(deftest lighthouse-requires-exactly-two-stone-rooms-but-keeps-capacity-after-building
  (let [card (cid "FR" 35) s (scenario "lighthouse") done (g/command (reload s) 0 {:type :action :space :growth})]
    (is (= 3 (:family (g/p done 0)) (a/family-capacity (g/p done 0))))
    (is (= 4 (a/family-capacity (a/build-rooms (g/p s 0) [0]))))
    (doseq [house [:wood :clay]]
      (is (thrown? clojure.lang.ExceptionInfo (play (assoc-in (rich (fresh)) [:players 0 :house] house) card))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (play (-> (fresh) rich (assoc-in [:players 0 :house] :stone) (update-in [:players 0] a/build-rooms [0])) card)))))

(deftest absinthe-food-appears-only-on-future-rounds-seven-through-fourteen
  (let [card (cid "FR" 79)]
    (doseq [[round rounds] [[1 (range 7 15)] [6 (range 7 15)] [7 (range 8 15)] [13 [14]] [14 []]]]
      (let [s (-> (fresh) rich (scenarios/prepared-round round)) done (play s card)]
        (is (= (set rounds) (set (keys (:schedule (g/p done 0))))))
        (is (= (stock s 0 :food) (stock done 0 :food)))
        (when (seq rounds)
          (let [arrived (nth (iterate #(g/drain (g/begin-round %)) done) (- (first rounds) round))]
            (is (= (inc (stock done 0 :food)) (stock arrived 0 :food)))))))))

(deftest immigrants-son-offers-free-graphical-plowing-for-occupations-five-six-seven
  (let [s (scenario "immigrants-son") card (cid "FR" 87)]
    (is (= :plow (op s)))
    (let [done (resolve! (reload s) {:cell 0})]
      (is (= [0] (vec (a/tiles (g/p done 0) :field))))
      (is (= (:goods (g/p s 0)) (:goods (g/p done 0)))))
    (is (nil? (op (resolve! s {:skip? true}))))
    (doseq [n (range 1 10)]
      (let [ready (assoc-in (own (rich (fresh)) 0 card) [:players 0 :occupations] (vec (cons card (repeat (dec n) (cid "G" 5)))))]
        (is (= (when (#{5 6 7} n) :plow) (op (emit ready :occupation 0 {:card (cid "G" 5)}))))
        (is (nil? (op (emit ready :occupation 0 {:card card}))))
        (is (nil? (op (emit ready :occupation 1 {:card (cid "G" 5)}))))))))

(deftest village-druid-does-not-reward-itself-or-an-opponents-occupation
  (let [s (scenario "village-druid") card (cid "FR" 116)]
    (is (= 0 (stock s 0 :sheep)))
    (let [done (g/command (reload s) 0 {:type :action :space :lessons :card (cid "G" 5)})]
      (is (= 1 (stock done 0 :sheep)))
      (is (some #(= :pet (:kind %)) (a/animal-housing (g/p done 0)))))
    (is (= 0 (stock (emit s :occupation 1 {:card (cid "G" 5)}) 0 :sheep)))
    (is (= 4 (:min-players (c/card card))))))
