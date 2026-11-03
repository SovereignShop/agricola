(ns agricola.community-twelve-engine-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-rules :as c]
            [agricola.card-game :as g] [agricola.card-expressions :as expr]))
(defn cid [deck n]
  (:id (first (filter #(and (= "Original" (:edition %)) (= "Expansion" (:pack %))
                            (= deck (:deck %)) (= (str n) (:number %))) (vals c/metadata)))))
(defn fresh [] (g/initialize (a/new-game ["Ada" "Ben" "Cy" "Dee"] (vec (mapcat identity a/stages)) 0)))
(defn rich [s] (update-in s [:players 0] a/gain {:food 20 :wood 20 :clay 20 :reed 20 :stone 20 :grain 5 :vegetable 4}))
(defn own [s pid id] (update-in s [:players pid (if (= :occupation (:kind (c/card id))) :occupations :minors)] conj id))
(defn play [s id] (g/drain (g/play-card (update-in s [:players 0 :hand (:kind (c/card id))] conj id) 0 id 0 {})))
(defn reload [s] (edn/read-string (pr-str s)))
(defn stock [s pid resource] (a/amount (g/p s pid) resource))
(def inventor (cid "G" 34))
(def baker (cid "WM" 4))
(def clockwork (cid "WM" 70))
(def chameleon (cid "FR" 13))
(def goblet (cid "FR" 23))
(defn player-with [& cards] (g/p (reduce #(own %1 0 %2) (rich (fresh)) cards) 0))
(defn at-round [s n] (g/drain (g/begin-round (assoc s :round (dec n)))))
(defn animal-player [p goods]
  (-> p (assoc :goods (merge (zipmap a/animals (repeat 0)) goods))
      (update :minors conj (cid "WM" 63))
      (assoc :fences (set (a/edges 0)))))

(deftest inventor-uses-printed-resource-cost-not-food-or-discounted-price
  (let [p (player-with inventor)]
    (is (= 0 (a/improvement-discount-count p (c/id :b010))) "Three wood + three food is only three resources.")
    (is (= 1 (a/improvement-discount-count p :clay-oven)))
    (is (= [:clay :stone] (a/improvement-discount-resources p :clay-oven)))
    (is (= 0 (a/improvement-discount-count p :fireplace-3)))
    (is (= (a/amount p :grain) (a/amount (a/improvement-food-before-payment p (cid "G" 73)) :grain)))
    (is (not (a/baking-symbol? (cid "G" 73))))
    (is (= 0 (a/improvement-discount-count p :fireplace-2)))
    (is (= 1 (a/improvement-discount-count p :well)))
    (is (= {:clay 2 :stone 1 :wood 0} (a/improvement-cost p :clay-oven nil {:discount-resource :clay})))
    (is (= {:clay 3 :stone 0 :wood 0} (a/improvement-cost p :clay-oven nil {:discount-resource :stone})))
    (is (thrown? clojure.lang.ExceptionInfo (a/improvement-cost p :clay-oven nil {:discount-resource :wood})))
    (let [p (update p :occupations conj :catalog/original-base-e-211-stonecutter)]
      (is (= 1 (a/improvement-discount-count p :clay-oven)))
      (is (= 2 (:clay (a/improvement-cost p :clay-oven nil {:discount-resource :clay}))))
      (is (= 0 (:stone (a/improvement-cost p :clay-oven nil {:discount-resource :clay})))))))

(deftest inventor-considers-each-printed-alternative-separately
  (let [p (player-with inventor) mill (c/id :a064)
        s (assoc-in (fresh) [:players 0] p)]
    (is (= 1 (a/improvement-discount-count p mill {:payment-option 0})))
    (is (= 0 (a/improvement-discount-count p mill {:payment-option 1})))
    (is (= #{:wood :clay} (set (a/improvement-discount-resources p mill {:payment-option 0}))))
    (is (empty? (a/improvement-discount-resources p mill {:payment-option 1})))
    (is (= 3 (:clay (g/card-cost s 0 (c/card mill) 0 {:payment-option 0 :discount-resource :clay}))))
    (is (= 2 (:stone (g/card-cost s 0 (c/card mill) 0 {:payment-option 1}))))
    (is (thrown? clojure.lang.ExceptionInfo (g/card-cost s 0 (c/card mill) 0 {:payment-option 1 :discount-resource :stone})))
    (is (zero? (a/improvement-discount-count p :catalog/original-base-i-84-chicken-coop)))
    (is (zero? (a/improvement-discount-count p :catalog/original-base-k-116-granary)))
    (doseq [[index resources] [[0 #{:wood :stone}] [1 #{:clay :stone}] [2 #{:reed :stone}]]]
      (is (= resources (set (a/improvement-discount-resources p (cid "G" 114) {:payment-option index})))))))

(deftest independent-discounts-can-choose-different-resources-and-auto-fill-slots
  (let [p (player-with inventor baker :catalog/original-base-e-15-baking-tray)
        cost #(a/improvement-cost %1 :clay-oven nil %2)]
    (is (= 3 (a/improvement-discount-count p :clay-oven)))
    (is (= [[:clay :stone] [:clay :stone] [:clay :stone]] (a/improvement-discount-options p :clay-oven)))
    (is (= ["Baking Tray discount" "Inventor discount" "Bargaining Baker discount"] (a/improvement-discount-labels p :clay-oven)))
    (is (= 1 (:clay (cost p {:discount-resources [:clay :stone :clay]}))))
    (is (= 0 (:stone (cost p {:discount-resources [:clay :stone :clay]}))))
    (is (= 0 (:clay (cost p {:discount-resources [:clay :clay :clay]}))))
    (is (= 1 (:stone (cost p {:discount-resources [:clay :clay :clay]}))))
    (is (thrown? clojure.lang.ExceptionInfo (cost p {:discount-resources [:stone :stone :clay]})))
    (is (thrown? clojure.lang.ExceptionInfo (cost p {:discount-resources [:clay :clay :clay :stone]})))
    (is (thrown? clojure.lang.ExceptionInfo (cost p {:discount-resources [:food]})))
    (let [tight (assoc p :goods {:clay 1})]
      (is (= 1 (:clay (cost tight {:discount-resources [nil :stone]}))))
      (is (= 0 (:stone (cost tight {:discount-resources [nil :stone]}))))
      (is (= 1 (:clay (cost tight {}))))
      (is (= 0 (:stone (cost tight {})))))
    (is (empty? (a/improvement-discount-options p nil)))
    (is (empty? (a/improvement-discount-labels p nil)))))

(deftest bargaining-baker-receives-grain-before-immediate-oven-baking
  (let [s (-> (rich (fresh)) (own 0 baker) (at-round 2)
              (assoc-in [:players 0 :goods :grain] 0))
        done (g/command (reload s) 0 {:type :action :space :improvement :improvement :clay-oven
                                       :discount-resource :clay :bake-grain 1})]
    (is (= 0 (stock done 0 :grain)))
    (is (= (+ 5 (stock s 0 :food)) (stock done 0 :food)))
    (is (= (- (stock s 0 :clay) 2) (stock done 0 :clay)))
    (is (= (dec (stock s 0 :stone)) (stock done 0 :stone))))
  (let [p (player-with baker)]
    (doseq [id [:fireplace-2 :hearth-4 :clay-oven :stone-oven (cid "G" 113)]]
      (is (= (inc (a/amount p :grain)) (a/amount (a/improvement-food-before-payment p id) :grain))))
    (is (= (a/amount p :grain) (a/amount (a/improvement-food-before-payment p :well) :grain)))
    (is (= (a/amount p :grain) (a/amount (a/improvement-food-before-payment p (cid "G" 73)) :grain)))
    (is (not (a/baking-symbol? (cid "G" 73))))
    (is (= 0 (a/improvement-discount-count p :fireplace-2)))
    (is (= 0 (a/improvement-discount-count p :catalog/original-base-i-65-baker-s-kitchen)))
    (is (= 1 (a/improvement-discount-count p :clay-oven)))
    (is (= 0 (a/improvement-discount-count p :catalog/original-base-e-14-baker-s-oven)))))

(deftest chameleon-permits-only-one-boar-with-sheep-within-normal-capacity
  (let [p (animal-player (player-with chameleon) {:sheep 1 :boar 1})]
    (is (some? (a/animal-housing p)))
    (is (= 2 (:capacity (first (filter #(= :pasture (:kind %)) (a/accommodations p))))))
    (doseq [goods [{:sheep 2 :boar 1} {:sheep 1 :boar 2} {:boar 1 :cattle 1} {:sheep 1 :cattle 1}]]
      (is (nil? (a/animal-housing (animal-player p goods))) (str goods)))
    (is (some? (a/animal-housing (animal-player p {:boar 2}))))
    (is (some? (a/animal-housing (animal-player p {:cattle 2}))))
    (let [stable (assoc-in p [:farm 0 :stable] true)]
      (is (some? (a/animal-housing (animal-player stable {:sheep 3 :boar 1}))))
      (is (nil? (a/animal-housing (animal-player stable {:sheep 2 :boar 2})))))
    (let [friendly (-> p (assoc-in [:farm 0 :stable] true) (update :minors conj (cid "WM" 84)))]
      (is (some? (a/animal-housing (animal-player friendly {:sheep 2 :boar 2}))))
      (is (nil? (a/animal-housing (animal-player friendly {:sheep 1 :boar 1 :cattle 1})))))
    (let [mixed (update p :occupations conj :catalog/original-base-e-216-animal-keeper)]
      (is (some? (a/animal-housing (animal-player mixed {:boar 1 :cattle 1})))))))

(deftest goblet-snapshots-double-well-deliveries-only-on-a-new-build
  (let [s (-> (rich (fresh)) (own 0 goblet) (at-round 2))
        built (g/command (reload s) 0 {:type :action :space :meeting :improvement :well})
        removed (update-in built [:players 0] g/remove-owned-improvement goblet)
        next (at-round removed 3)]
    (is (contains? (:improvements (g/p built 0)) :well))
    (is (= #{3 4 5 6 7} (:well-rounds (g/p built 0))))
    (doseq [round [3 4 5 6 7]] (is (= 2 (a/well-food (g/p built 0) round))))
    (is (= 0 (a/well-food (g/p built 0) 2) (a/well-food (g/p built 0) 8)))
    (is (= (+ 2 (stock removed 0 :food)) (stock next 0 :food)))
    (is (= {:food 2} (:goods (first (get (g/future-round-resources built) 3)))))
    (let [village (play built :catalog/original-base-i-66-village-well)]
      (doseq [round [3 4 5 6 7]] (is (= 2 (get-in village [:players 0 :well-deliveries round]))))))
  (let [s (-> (rich (fresh)) (at-round 2))
        built (g/command s 0 {:type :action :space :improvement :improvement :well})
        played (play built goblet)]
    (is (= 1 (a/well-food (g/p played 0) 3)))
    (is (= (inc (stock played 0 :food)) (stock (at-round played 3) 0 :food)))))

(deftest clockwork-counts-as-an-occupation-for-all-counts-not-as-two-cards
  (let [s (-> (rich (fresh)) (own 0 clockwork)) p (g/p s 0)]
    (doseq [purpose [nil :minor-prerequisite :original-tutor :original-reeve]]
      (is (= 1 (c/occupation-count p purpose))))
    (is (= [clockwork] (vec (c/occupation-cards p))))
    (is (= 1 (expr/evaluate s 0 {} [:minors])))
    (is (= 1 (expr/evaluate s 0 {} [:cards])))
    (is (= 1 (expr/evaluate s 0 {} [:occupations])))
    (let [p (update p :occupations conj :catalog/original-base-e-148-academic)]
      (is (= 2 (c/occupation-count p nil)))
      (is (= 3 (c/occupation-count p :minor-prerequisite)))
      (is (= 2 (count (c/occupation-cards p)))))
    (let [reformer (update p :occupations conj (cid "FR" 105))]
      (is (= #{clockwork (cid "FR" 105)} (set (map :card (filter #(= :reformer (:slot %)) (a/accommodations reformer)))))))
    (let [occupation (cid "G" 21)
          ready (assoc-in s [:players 0 :hand :occupation] [occupation])
          done (g/command ready 0 {:type :action :space :lessons :card occupation})]
      (is (= (dec (stock s 0 :food)) (stock done 0 :food)) "Clockwork means the first occupation lesson is no longer free."))))

(deftest rock-pyramid-records-only-rooms-that-can-have-paid-stone
  (let [p (assoc (player-with (cid "FR" 42)) :house :stone)
        single (a/build-rooms p [0])
        free-room (a/build-rooms p [0] {} {:free? true})
        discounted (a/build-rooms p [0] {} {:room-discount {:stone 5}})
        wood (a/build-rooms (assoc p :house :wood) [0])]
    (is (= {:cells [0] :stone-rooms 1} (:last-room-payment single)))
    (is (= {:cells [0] :stone-rooms 0} (:last-room-payment free-room)))
    (is (= 0 (get-in discounted [:last-room-payment :stone-rooms])))
    (is (= 0 (get-in wood [:last-room-payment :stone-rooms])))
    (let [p (-> p (update :minors conj (c/id :a014))
                (update :occupations into [:catalog/original-base-e-218-carpenter
                                          :catalog/original-base-k-272-frame-builder]))
          one (a/build-rooms p [0 1 2] {:room-options {0 {:frame? true} 1 {:frame? true}}})
          two (a/build-rooms p [0 1])]
      (is (= 1 (- (a/amount p :stone) (a/amount one :stone))))
      (is (= 1 (get-in one [:last-room-payment :stone-rooms])))
      (is (= 2 (- (a/amount p :stone) (a/amount two :stone))))
      (is (= 2 (get-in two [:last-room-payment :stone-rooms])))))
  (is (not (contains? (a/build-rooms (player-with) [0]) :last-room-payment))))

(deftest inventor-can-discount-crops-and-combine-with-fixed-discounts
  (let [brewery :catalog/original-base-k-110-brewery
        s (-> (rich (fresh)) (own 0 inventor)
              (own 0 :catalog/original-base-e-211-stonecutter))]
    (is (= #{:stone :grain} (set (a/improvement-discount-resources (g/p s 0) brewery))))
    (let [cost (g/card-cost s 0 (c/card brewery) 0 {:discount-resources [:grain]})]
      (is (= 1 (:grain cost)))
      (is (= 1 (:stone cost))))))

(deftest goblet-does-not-retroactively-change-an-old-well-or-a-later-rebuild
  (let [s (-> (rich (fresh)) (own 0 goblet) (at-round 13))
        built (a/build-improvement s 0 {:improvement :well})
        removed (-> built
                    (update-in [:players 0] g/remove-owned-improvement goblet)
                    (update-in [:players 0] g/remove-owned-improvement :well)
                    (update :available-improvements conj :well))
        rebuilt (a/build-improvement removed 0 {:improvement :well})]
    (is (= #{14} (:well-rounds (g/p built 0))))
    (is (= 2 (a/well-food (g/p built 0) 14)))
    (is (= 1 (a/well-food (g/p rebuilt 0) 14)))
    (is (not (contains? (g/p rebuilt 0) :well-food-per-round)))))

(deftest bargaining-baker-and-engineer-prepayments-stack-once-before-baking
  (let [s (-> (rich (fresh)) (own 0 baker) (own 0 (cid "WM" 17))
              (at-round 2) (assoc-in [:players 0 :goods :grain] 0))
        done (g/command s 0 {:type :action :space :improvement :improvement :clay-oven :bake-grain 1})]
    (is (= 0 (stock done 0 :grain)))
    (is (= (+ 7 (stock s 0 :food)) (stock done 0 :food)) "Two Engineer food for printed stone, plus five from baking.")
    (is (= 1 (- (get-in done [:players 0 :received :grain]) (get-in s [:players 0 :received :grain]))))))

(deftest chameleon-trades-a-sheep-and-provides-the-boar-housing-immediately
  (let [s (-> (rich (fresh))
              (assoc-in [:players 0 :goods :sheep] 2)
              (assoc-in [:players 0 :fences] (set (a/edges 0))))
        done (play (reload s) chameleon)]
    (is (= 1 (stock done 0 :sheep)))
    (is (= 1 (stock done 0 :boar)))
    (is (some? (a/animal-housing (g/p done 0))))
    (is (empty? (:effect-queue done)))
    (is (thrown? clojure.lang.ExceptionInfo (play (assoc-in s [:players 0 :goods :sheep] 0) chameleon)))))

(deftest clockwork-earns-tutor-points-without-triggering-occupation-income
  (let [tutor :catalog/original-base-e-174-tutor
        patron :catalog/original-base-e-192-patron
        bookshelf :catalog/original-base-k-112-bookshelf
        s (-> (rich (fresh)) (play tutor) (play patron) (play (cid "G" 21)) (play bookshelf))
        before (g/card-bonus s 0 tutor)
        done (play (reload s) clockwork)]
    (is (= (inc before) (g/card-bonus done 0 tutor)))
    (is (= (stock s 0 :food) (stock done 0 :food)))
    (is (= 4 (c/occupation-count (g/p done 0) nil)))
    (is (= 2 (count (:minors (g/p done 0)))))
    (is (= 5 (expr/evaluate done 0 {} [:cards])))))

(deftest award-winner-snapshots-the-actual-major-payment
  (let [winner (cid "FR" 64)
        s (-> (rich (fresh)) (own 0 winner) (own 0 inventor) (own 0 baker))
        built (a/build-improvement s 0 {:improvement :clay-oven :discount-resources [:clay :stone]})
        snapshot (get-in built [:players 0 :last-improvement-payment])]
    (is (= :clay-oven (:improvement snapshot)))
    (is (= {:clay 2} (into {} (filter (comp pos? val) (:paid snapshot)))))
    (is (= (- (stock s 0 :clay) 2) (stock built 0 :clay)))
    (let [free (a/build-improvement s 0 {:improvement :well} {:cost {}})]
      (is (not-any? pos? (vals (get-in free [:players 0 :last-improvement-payment :paid])))))
    (let [s (-> s (assoc-in [:players 0 :improvements] #{:fireplace-2})
                (update :available-improvements disj :fireplace-2))
          upgraded (a/build-improvement s 0 {:improvement :hearth-4 :upgrade :fireplace-2})]
      (is (= {:improvement :hearth-4 :paid {}} (get-in upgraded [:players 0 :last-improvement-payment])))))
  (let [done (a/build-improvement (rich (fresh)) 0 {:improvement :fireplace-2})]
    (is (not (contains? (g/p done 0) :last-improvement-payment)))))

(deftest new-response-windows-do-not-change-games-without-the-new-card
  (let [daughter (cid "G" 9) master :catalog/original-base-e-149-master-baker
        s (-> (rich (fresh)) (assoc :occupation-deck []))
        absent (play s master)
        source {:event :occupation :player 0 :context {:card master}}]
    (is (not (g/game-has-cards? s #{daughter})))
    (is (empty? (:effect-queue absent)))
    (is (empty? (g/public-hand-response-tasks s source)))
    (doseq [available [(assoc s :occupation-deck [daughter])
                      (assoc-in s [:players 1 :hand :occupation] [daughter])]]
      (let [tasks (filter #(= daughter (get-in % [:effect :card])) (g/public-hand-response-tasks available source))]
        (is (= #{1 2 3} (set (map :player tasks))) "The public window remains identical for all other seats.")))))
