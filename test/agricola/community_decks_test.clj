(ns agricola.community-decks-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn] [clojure.java.io :as io]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.card-expressions :as expr] [agricola.bot :as bot]
            [eurozone.store :as store] [eurozone.round-markers :as markers]))

(defn cid [deck n]
  (:id (first (filter #(and (= "Original" (:edition %)) (= "Expansion" (:pack %))
                            (= deck (:deck %)) (= (str n) (:number %))) (vals c/metadata)))))
(defn fresh [] (g/initialize (a/new-game ["Ada" "Ben" "Cy" "Dee"] (vec (mapcat identity a/stages)) 0)))
(defn rich [s] (update-in s [:players 0] a/gain {:food 20 :wood 20 :clay 20 :reed 20 :stone 20 :grain 5 :vegetable 4}))
(defn own [s pid id] (update-in s [:players pid (if (= :occupation (:kind (c/card id))) :occupations :minors)] conj id))
(defn ready [s] (assoc-in s [:players 0 :occupations] [:catalog/original-base-e-197-braggart (c/id :a133) (cid "G" 21)]))
(defn play [s id & [options]]
  (g/drain (g/play-card (update-in s [:players 0 :hand (:kind (c/card id))] conj id) 0 id 0 (or options {}))))
(defn reload [s] (edn/read-string (pr-str s)))
(defn resolve! [s choice] (g/command s (g/acting-player s) (merge {:type :resolve :decision (:decision-id s)} choice)))
(defn emit [s event pid & [context]] (g/drain (g/queue-front s [(g/event event pid context)])))
(defn op [s] (get-in s [:effect-queue 0 :effect :op]))
(defn stock [s pid resource] (a/amount (g/p s pid) resource))
(defn field [s cell crop count] (assoc-in s [:players 0 :farm cell] {:type :field :crop crop :count count}))
(defn delivery-rounds [s] (set (keys (get-in s [:players 0 :schedule]))))

(deftest all-thirty-two-printings-have-native-rules-costs-prerequisites-and-local-images
  (let [art (with-open [r (java.io.PushbackReader. (io/reader "src/cljs/eurozone/browser/card_art.cljs"))]
              (loop [] (let [f (read r false nil)]
                         (cond (nil? f) nil
                               (and (seq? f) (= '(def printed-files) (take 2 f))) (nth f 2)
                               :else (recur)))))]
    (is (= 32 (count c/community-deck-definitions)))
    (doseq [[id _] c/community-deck-definitions :let [card (c/card id)]]
      (is (= id (:id card)))
      (is (some? (if (contains? card :requires) (:requires card) (expr/requirement (:prerequisites card)))) (str id))
      (is (.isFile (io/file "resources/public/art/agricola/cards" (art id))) (str id)))
    (doseq [[deck implemented total] [["G" 10 119] ["WM" 10 115] ["FR" 12 120]]
            :let [pack (:pack-id (c/card (first (filter #(= deck (:deck (c/card %))) (keys c/community-deck-definitions)))))
                  coverage (first (filter #(= pack (:id %)) store/card-packs))]]
      (is (= implemented (:implemented coverage))) (is (= total (:total coverage)))
      (is (not-any? #(= pack (:pack-id %)) (store/hand-pool 5 [pack]))))
    (is (not-any? #(= (cid "G" 21) (:id %)) (store/hand-pool 3)))
    (is (some #(= (cid "G" 21) (:id %)) (store/hand-pool 4)))))

(deftest card-prerequisites-remain-mandatory
  (doseq [[deck number allowed]
          [["G" 69 ready] ["WM" 67 ready] ["WM" 76 ready] ["WM" 109 ready]
           ["WM" 100 #(-> % (field 0 :grain 1) (field 1 :vegetable 1))]
           ["FR" 16 #(field % 0 nil 0)] ["FR" 17 ready] ["FR" 38 #(field % 0 :grain 1)]
           ["FR" 44 #(assoc-in % [:players 0 :house] :stone)] ["FR" 53 ready]]]
    (let [id (cid deck number) state (rich (fresh))]
      (is (thrown? clojure.lang.ExceptionInfo (play state id)) (str id))
      (is (some #{id} (c/owned (g/p (play (allowed state) id) 0))) (str id))))
  (is (thrown? clojure.lang.ExceptionInfo (play (ready (fresh)) (cid "WM" 111))))
  (is (thrown? clojure.lang.ExceptionInfo (play (fresh) (cid "FR" 3)))))

(deftest architect-rewards-each-new-room-and-stable-once
  (let [s (-> (fresh) rich (own 0 (cid "G" 5)))
        done (g/command s 0 {:type :action :space :build :rooms [0 1] :stables [2 3]})]
    (is (= 6 (- (stock done 0 :food) (stock s 0 :food))))
    (is (= 4 (count (a/tiles (g/p done 0) :room))))
    (is (= 2 (count (filter :stable (:farm (g/p done 0))))))
    (is (= 1 (- (stock (emit s :stables-created 0 {:stables-built 1}) 0 :food) (stock s 0 :food))))))

(deftest bard-and-harbor-master-respect-who-used-the-action
  (let [bard (own (fresh) 0 (cid "G" 10)) harbor (own (fresh) 0 (cid "G" 29))]
    (doseq [[actor space food wood] [[0 :traveling 0 0] [1 :traveling 1 1] [1 :fishing 0 0]]]
      (let [done (emit bard :use-space actor {:space space})]
        (is (= [food wood] (mapv #(- (stock done 0 %) (stock bard 0 %)) [:food :wood])))))
    (doseq [[actor space food reed] [[0 :fishing 0 1] [1 :fishing 1 1] [1 :traveling 0 0]]]
      (let [done (emit harbor :use-space actor {:space space})]
        (is (= [food reed] (mapv #(- (stock done 0 %) (stock harbor 0 %)) [:food :reed])))))))

(deftest passing-cards-pay-their-printed-cost-and-pass-only-after-resolving
  (doseq [[deck number cost goods] [["G" 75 {:food 2} {:vegetable 1}]
                                   ["G" 87 {:cattle 1} {:vegetable 2}]
                                   ["FR" 57 {:boar 1} {:food 5}]]]
    (let [id (cid deck number) s (reduce-kv #(assoc-in %1 [:players 0 :goods %2] %3) (fresh) cost)
          done (play s id)]
      (doseq [[resource n] goods] (is (= n (- (stock done 0 resource) (stock s 0 resource)))))
      (doseq [[resource _] cost] (is (= 0 (stock done 0 resource))))
      (is (some #{id} (get-in done [:players 1 :hand :minor])))
      (is (not (some #{id} (c/owned (g/p done 0)))))
      (is (thrown? clojure.lang.ExceptionInfo (play (assoc-in s [:players 0 :goods (first (keys cost))] 0) id)))))
  (let [id (cid "G" 93) s (-> (fresh) (field 0 :grain 2) (field 1 :vegetable 1)
                             (assoc-in [:players 0 :card-fields :bean] {:type :field :crop :vegetable :count 2}))
        done (play s id)]
    (is (= 2 (- (stock done 0 :food) (stock s 0 :food))))
    (is (some #{id} (get-in done [:players 1 :hand :minor])))))

(deftest absolute-and-relative-deliveries-never-create-past-or-post-game-promises
  (doseq [[deck number round expected] [["G" 51 1 #{8 10 12 14}] ["G" 51 10 #{12 14}]
                                        ["WM" 76 10 #{14}] ["WM" 76 11 #{}]
                                        ["WM" 82 6 #{8}] ["WM" 82 7 #{}]
                                        ["WM" 111 1 #{}] ["WM" 111 4 #{5 6 7}] ["WM" 111 13 #{14}]
                                        ["FR" 16 6 #{7 9}] ["FR" 16 7 #{9}]
                                        ["FR" 39 7 #{11 14}] ["FR" 39 11 #{}]
                                        ["FR" 44 12 #{13 14}] ["FR" 44 14 #{}]]]
    (let [s (-> (fresh) rich (field 0 :grain 1) (assoc-in [:players 0 :house] :stone) (assoc :round round))
          s (if (= [deck number] ["WM" 111]) s (ready s))
          done (play s (cid deck number))]
      (is (= expected (delivery-rounds (reload done))) (str [deck number round]))))
  (let [s (-> (fresh) (play (cid "G" 51)) (assoc :round 7))
        next (g/drain (g/begin-round (reload s)))]
    (is (= 1 (stock next 0 :stone)))
    (is (nil? (get (markers/by-round next) 8)))))

(deftest future-field-and-animal-payments-are-visible-optional-and-reloadable
  (doseq [[id resource op-name choice] [[(cid "WM" 76) :field :plow {:cell 0}]
                                        [(cid "FR" 39) :boar :gain {}]]]
    (let [s (-> (fresh) rich ready (play id))
          marker (first (get (markers/by-round s) 5))
          pending (g/drain (g/begin-round (assoc (reload s) :round 4)))
          done (resolve! (reload pending) choice) skipped (resolve! pending {:skip? true})]
      (is (= {resource 1} (:goods marker)))
      (is (= {:food 1} (get-in marker [:deliveries 0 :cost])))
      (is (true? (get-in marker [:deliveries 0 :optional?])))
      (is (= op-name (op pending)))
      (is (= (dec (stock pending 0 :food)) (stock done 0 :food)))
      (is (= (stock pending 0 :food) (stock skipped 0 :food)))
      (is (some #(bot/preview pending 0 %) (bot/decision-candidates pending 0)))
      (if (= resource :field)
        (do (is (= [0] (a/tiles (g/p done 0) :field))) (is (empty? (a/tiles (g/p skipped 0) :field))))
        (do (is (= 1 (stock done 0 :boar))) (is (= 0 (stock skipped 0 :boar)))))
      (is (nil? (op (g/drain (g/begin-round (update-in (assoc s :round 4) [:players 0 :goods] merge {:food 0 :grain 0 :vegetable 0})))))))))

(deftest orchard-and-plum-patch-count-farm-fields-once-and-snapshot-the-count
  (doseq [id [(cid "FR" 38) (cid "WM" 100)]]
    (let [s (-> (fresh) (field 0 :grain 2) (field 1 :vegetable 1)
                (assoc-in [:players 0 :farm 0 :under-crops] {:vegetable 1})
                (assoc-in [:players 0 :card-fields :bean] {:type :field :crop :vegetable :count 2}))
          done (play s id)]
      (is (= #{2 3} (delivery-rounds done)))
      (is (= #{2 3} (delivery-rounds (field done 2 :grain 3))))))
  (let [s (-> (fresh) (field 0 :grain 1) (assoc-in [:players 0 :farm 0 :under-crops] {:vegetable 1}))]
    (is (thrown? clojure.lang.ExceptionInfo (play s (cid "WM" 100))))))

(deftest amusement-park-schedules-food-from-the-current-pasture-count
  (doseq [n (range 1 5)]
    (let [fences (set (mapcat a/edges (range n)))
          s (assoc-in (fresh) [:players 0 :fences] fences)
          done (play s (cid "FR" 3))]
      (is (= n (count (a/pastures (g/p s 0)))))
      (is (= (set (range 2 (+ n 3))) (delivery-rounds done))))))

(deftest optional-harvest-exchanges-pay-once-without-cooking-an-animal
  (doseq [[id resource expected-op] [[(cid "WM" 95) :wood :exchange] [(cid "WM" 97) :sheep :points]]]
    (let [s (-> (fresh) (own 0 id) (assoc-in [:players 0 :goods resource] 2))
          pending (emit s :feeding-start 0) done (resolve! (reload pending) {}) skipped (resolve! pending {:skip? true})]
      (is (= expected-op (op pending)))
      (is (= 1 (stock done 0 resource)))
      (is (= 2 (stock skipped 0 resource)))
      (is (nil? (op done)))
      (if (= resource :wood) (is (= 1 (stock done 0 :stone)))
        (do (is (= 1 (get-in done [:players 0 :bonus-points])))
            (is (= 1 (get-in (update-in done [:players 0] g/remove-owned-improvement id) [:players 0 :bonus-points])))
            (is (= (stock s 0 :food) (stock done 0 :food)))))
      (is (some #(bot/preview pending 0 %) (bot/decision-candidates pending 0)))
      (is (nil? (op (emit (assoc-in s [:players 0 :goods resource] 0) :feeding-start 0)))))))

(deftest apple-picker-diary-and-pipe-smoker-use-their-own-timing
  (doseq [space [:grain :vegetable]]
    (let [s (own (fresh) 0 (cid "WM" 3)) done (g/command (update s :active conj :vegetable) 0 {:type :action :space space})]
      (is (= 1 (stock done 0 :wood)))
      (is (= (inc (stock s 0 :food)) (stock done 0 :food)))))
  (let [s (own (fresh) 0 (cid "FR" 17)) done (play s (cid "WM" 3))]
    (is (= 1 (stock done 0 :wood)))
    (is (= 0 (stock (emit s :occupation 1) 0 :wood))))
  (let [s (-> (fresh) (own 0 (cid "FR" 100)) (field 0 :grain 1))]
    (is (= 2 (stock (emit s :before-field-phase 0) 0 :wood)))
    (is (= 0 (stock (emit (field s 0 :vegetable 1) :before-field-phase 0) 0 :wood)))
    (is (= 0 (stock (emit s :before-field-phase 0 {:manure? true}) 0 :wood)))))

(deftest fishing-bonuses-can-supply-the-optional-swimming-exchange
  (let [s (-> (fresh) (own 0 (cid "FR" 53)) (own 0 (cid "FR" 48)))
        pending (g/command s 0 {:type :action :space :fishing})
        done (resolve! (reload pending) {}) skipped (resolve! pending {:skip? true})]
    (is (= :exchange (op pending)))
    (is (= 0 (stock done 0 :wood)))
    (is (= 1 (stock skipped 0 :wood)))
    (is (= (+ 5 (stock s 0 :food)) (stock done 0 :food))))
  (let [s (own (fresh) 0 (cid "FR" 48))]
    (is (nil? (op (g/command s 0 {:type :action :space :fishing}))))))

(deftest breakfast-outdoors-has-two-cost-options-and-a-feeding-income
  (doseq [[option cost] [[0 {:vegetable 1}] [1 {:grain 2}]]]
    (let [s (rich (fresh)) id (cid "FR" 10) done (play s id {:payment-option option})]
      (doseq [[r n] cost] (is (= (- (stock s 0 r) n) (stock done 0 r))))
      (is (= (inc (stock done 0 :food)) (stock (emit done :feeding-start 0) 0 :food)))))
  (is (thrown? clojure.lang.ExceptionInfo (play (fresh) (cid "FR" 10)))))

(deftest scoring-tiers-use-current-farm-pieces
  (let [id (cid "G" 21) s (own (fresh) 0 id)]
    (is (= 6 (g/card-bonus s 0 id)))
    (is (= 0 (g/card-bonus (own s 0 (cid "WM" 3)) 0 id))))
  (doseq [[n expected] [[0 0] [1 0] [2 0] [3 1] [4 2]]]
    (let [s (reduce #(assoc-in %1 [:players 0 :farm %2 :stable] true) (fresh) (range n))]
      (is (= expected (g/card-bonus s 0 (cid "G" 81))))))
  (doseq [[rooms expected] [[2 4] [3 2] [4 0] [5 0]]]
    (let [s (reduce #(assoc-in %1 [:players 0 :farm %2] {:type :room}) (fresh) (take (- rooms 2) [0 1 2]))]
      (is (= expected (g/card-bonus s 0 (cid "FR" 43))))))
  (doseq [[sheep boar cattle expected] [[1 1 1 0] [2 1 1 1] [1 2 1 1] [2 2 1 2] [2 2 0 0]]]
    (let [s (assoc-in (fresh) [:players 0 :goods] {:sheep sheep :boar boar :cattle cattle})]
      (is (= expected (g/card-bonus s 0 (cid "WM" 98)))))))

(deftest steel-plow-is-once-per-game-combines-with-clear-cutting-and-does-not-multiply-treasure
  (let [id (cid "WM" 109) s (-> (fresh) rich ready (play id) (own 0 (cid "G" 69)) (own 0 (cid "WM" 67)))
        pending (g/command s 0 {:type :action :space :plow :cell 0})
        done (resolve! (reload pending) {:plow-card id :cells [1 2 3]})]
    (is (= :wheel-plow (op pending)))
    (is (= #{0 1 2 3} (set (a/tiles (g/p done 0) :field))))
    (is (= 4 (- (stock done 0 :wood) (stock s 0 :wood))))
    (is (= 1 (- (stock done 0 :stone) (stock s 0 :stone))))
    (is (= 0 (get-in done [:players 0 :card-state id :uses])))
    (is (= 1 (get-in (resolve! pending {:skip? true}) [:players 0 :card-state id :uses])))
    (is (some #(and (= id (:plow-card %)) (bot/preview pending 0 %)) (bot/decision-candidates pending 0)))
    (is (nil? (op (g/command (-> done (assoc :turn 0 :occupied {}) (assoc-in [:players 0 :remaining] 1))
                             0 {:type :action :space :plow :cell 4}))))))
