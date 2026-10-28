(ns agricola.five-player-test
  (:require [clojure.test :refer :all] [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [agricola.five-player :as five] [agricola.bot :as bot]
            [eurozone.store :as store] [agricola.draw :as draw]))
(def order (vec (mapcat identity a/stages)))
(defn game [] (g/initialize (a/new-game ["A" "B" "C" "D" "E"] order 0)))
(defn own [state pid & codes] (update-in state [:players pid :occupations] into (map c/id codes)))
(defn rich [state] (update state :players #(mapv (fn [p] (a/gain p {:food 20 :wood 20 :clay 20 :reed 10 :stone 10 :grain 3 :vegetable 2})) %)))
(defn move [state space & [opts]] (g/command state (:turn state) (merge {:type :action :space space} opts)))
(defn resolve-head [state choice] (g/command state (g/acting-player state) (merge {:type :resolve :decision (:decision-id state)} choice)))
(defn effect [state pid code op context choice]
  (g/run-effect state (g/task pid (c/id code) {:op op} context) choice))
(defn illegal? [f] (try (f) false (catch clojure.lang.ExceptionInfo _ true)))
(deftest five-player-board-and-linked-spaces
  (let [s (game) river (move s :riverbank) market (move s :market)]
    (is (= [2 3 3 3 3] (mapv #(a/amount % :food) (:players s))))
    (is (= 3 (get-in s [:piles :hollow :clay])))
    (is (= {:wood 1 :reed 1} (select-keys (get-in river [:players 0 :goods]) [:wood :reed])))
    (is (= {:wood 1 :reed 1 :stone 1 :food 2} (select-keys (get-in market [:players 0 :goods]) [:wood :reed :stone :food])))
    (is (illegal? #(move s :modest-growth)))
    (doseq [[space linked] [[:copse :lessons-copse] [:traveling :house-building]]]
      (let [next (move s space)]
        (is (a/blocked? next linked))
        (is (not (contains? (:occupied next) linked)))
        (is (illegal? #(move next linked)))))
    (let [s (-> (rich s) (assoc-in [:players 0 :hand :occupation] [(c/id :a169)]))
          next (move s :lessons-copse {:card (c/id :a169)})]
      (is (a/blocked? next :copse))
      (is (= 1 (get-in next [:piles :copse :wood])))
      (is (= 20 (a/amount (g/p next 0) :food))) ; 2 initial +20 -2
      (is (= 2 (get-in (a/begin-round next) [:piles :copse :wood]))))
    (let [next (move (-> (rich s) (assoc :round 5) (update-in [:players 0] a/build-rooms [0])) :modest-growth)]
      (is (= 3 (get-in next [:players 0 :family])))
      (is (a/blocked? next :lessons-growth)))))
(deftest five-player-only-dealing-and-draw
  (is (= 48 (count (filter c/five-card? (keys c/cards)))))
  (is (not-any? #(= 5 (:min-players %)) (store/hand-pool 4)))
  (is (= 48 (count (filter #(= 5 (:min-players %)) (store/hand-pool 5)))))
  (let [d (store/make-draw 5 10)
        done (loop [d d n 0]
               (is (< n 300))
               (if (= :complete (:status d)) d
                 (let [pid (first (sort (:pending d)))]
                   (recur (draw/pick d pid (:wave d) (first (draw/legal-cards d pid))) (inc n)))))]
    (is (every? #(= {:minor 7 :occupation 7} (into {} (map (fn [[k v]] [k (count v)]) %))) (:selected done)))))
(deftest every-five-player-occupation-can-be-played-and-settles
  (doseq [cid (sort (filter c/five-card? (keys c/cards)))]
    (testing (str cid)
      (let [s (assoc-in (rich (game)) [:players 0 :hand :occupation] [cid])
            played (move s :lessons {:card cid})
            settled (loop [s played n 0]
                      (when (> n 20) (throw (ex-info "Effect did not settle" {:card cid})))
                      (if (empty? (:effect-queue s)) s
                        (let [pid (g/acting-player s)
                              next (some #(try (g/command s pid %) (catch clojure.lang.ExceptionInfo _ nil)) (bot/decision-candidates s pid))]
                          (is (some? next))
                          (recur next (inc n)))))]
        (is (some #{cid} (get-in settled [:players 0 :occupations])))
        (is (= 1 (:turn settled)))))))
(deftest lone-occupation-and-face-down-lifecycle
  (let [s (assoc-in (game) [:players 0 :hand :occupation] [(c/id :b176) (c/id :a169)])
        played (move s :lessons {:card (c/id :b176)})]
    (is (empty? (get-in played [:players 0 :hand :occupation])))
    (is (illegal? #(g/play-card (assoc-in played [:players 0 :hand :occupation] [(c/id :a169)]) 0 (c/id :a169) 0 {})))
    (is (illegal? #(g/play-card (own s 0 :a169) 0 (c/id :b176) 0 {}))))
  (let [s (own (game) 0 :b173)
        banked (effect s 0 :b173 :sweeper {:space :copse} {})
        spent (g/command banked 0 {:type :ability :card (c/id :b173)})]
    (is (= 3 (a/amount (g/p spent 0) :food)))
    (is (= 1 (count (:occupations (g/p spent 0)))))
    (is (empty? (g/trigger-tasks spent (g/event :use-space 0 {:space :copse}))))
    (is (illegal? #(g/command spent 0 {:type :ability :card (c/id :b173)})))))
(deftest extension-timing-and-optional-payments
  (let [s (-> (game) (own 0 :a174 :a180 :d180) (assoc-in [:players 0 :goods :food] 0))]
    (is (illegal? #(move s :traveling {:master-hora? true}))) ; cannot spend the food about to be collected
    (is (illegal? #(move s :animal-market {:choice :sheep :animal-brander? true}))))
  (let [s (-> (game) (own 0 :a175 :d180) (assoc-in [:piles :hollow :clay] 6))
        next (move s :hollow {:part-time? true})]
    (is (= 3 (get-in next [:piles :hollow :clay])))
    (is (= 3 (a/amount (g/p next 0) :clay)))
    (is (= 1 (a/amount (g/p next 0) :grain)))
    (is (= 0 (a/amount (g/p next 0) :vegetable)))
    (is (= 1 (a/amount (g/p next 0) :cattle))))
  (let [s (-> (game) (own 0 :a177))
        s (effect s 0 :a177 :middleman-stock {} {})
        next (move s :copse)]
    (is (= 1 (a/amount (g/p next 0) :stone)))
    (is (= #{:growth :house} (get-in next [:players 0 :card-state (c/id :a177) :spaces])))))
(deftest optional-effect-queue-survives-edn-and-declining
  (let [s (-> (game) (own 0 :a173) (g/queue-front [(g/event :work-start 0)]) g/drain)
        loaded (clojure.edn/read-string (pr-str s))
        accepted (resolve-head loaded {})
        declined (resolve-head loaded {:skip? true})]
    (is (= :clay-thief (get-in s [:effect-queue 0 :effect :op])))
    (is (= 3 (a/amount (g/p accepted 0) :clay)))
    (is (get-in accepted [:players 0 :card-state (c/id :a173) :face-down?]))
    (is (= 3 (get-in declined [:piles :hollow :clay])))
    (is (not (get-in declined [:players 0 :card-state (c/id :a173) :face-down?])))))
(deftest sidekick-extra-placement-and-turn-order
  (let [s (-> (rich (game)) (own 0 :a171) (assoc :round 2) (update :active conj :improvement))
        first (move s :improvement {:improvement :fireplace-2})
        next (resolve-head first {:action-command {}})]
    (is (= :sidekick (get-in first [:effect-queue 0 :effect :op])))
    (is (= 0 (get-in next [:occupied :sheep])))
    (is (= 0 (get-in next [:players 0 :remaining])))
    (is (= 1 (:turn next)))
    (is (= 21 (a/amount (g/p next 0) :food)))))
(deftest tag-along-uses-person-without-skipping-the-regular-next-player
  (let [s (own (game) 3 :b178)
        pending (move s :market)
        next (resolve-head pending {})]
    (is (= 3 (g/acting-player pending)))
    (is (= [0 3] (get-in next [:occupied :market])))
    (is (= 1 (get-in next [:players 3 :remaining])))
    (is (= 1 (:turn next)))
    (is (= 1 (a/amount (g/p next 3) :stone)))))
(deftest card-action-spaces-and-real-occupancy
  (let [s (-> (rich (game)) (own 0 :b171 :d178 :d179))]
    (is (g/card-space-error s 0 (c/id :d178)))
    (is (g/card-space-error (assoc s :occupied {:lessons 1 :copse 2 :modest-growth 3}) 0 (c/id :d178)))
    (let [s (assoc s :occupied {:lessons 1 :lessons-copse 2 :lessons-growth 3})
          next (g/command s 0 {:type :card-action :card-space (c/id :d178) :bundle :crops})]
      (is (= 4 (a/amount (g/p next 0) :grain)))
      (is (= 0 (get-in next [:occupied (c/id :d178)])))
      (is (g/card-space-error next 0 (c/id :d178))))
    (is (illegal? #(g/command s 0 {:type :card-action :card-space (c/id :b171) :action :vegetable})))
    (let [s (update s :active conj :vegetable) next (g/command s 0 {:type :card-action :card-space (c/id :b171) :action :vegetable})]
      (is (= 3 (a/amount (g/p next 0) :vegetable)))
      (is (nil? (get-in next [:occupied :vegetable])))))
  (let [s (-> (rich (game)) (own 2 :d170))
        next (g/command s 0 {:type :card-action :card-space (c/id :d170) :fences (a/edges 0)})]
    (is (= 21 (a/amount (g/p next 0) :food)))
    (is (= 24 (a/amount (g/p next 2) :food)))
    (is (= 1 (a/amount (g/p next 0) :sheep)))))
(deftest offsite-room-livestock-capacity-and-minor-reserve
  (let [s (-> (game) (own 0 :a169 :b169) (assoc-in [:players 0 :improvements] #{:well :joinery :pottery})
              (assoc :major-builds {1 4 2 5}) five/refresh)]
    (is (:off-site-room (g/p s 0)))
    (is (= 8 (:card-animal-capacity (g/p s 0))))
    (is (= 3 (:family (a/grow (g/p s 0) true))))
    (is (a/animal-housing (a/gain (g/p s 0) {:sheep 3 :boar 3 :cattle 3}))))
  (let [s (-> (game) (own 0 :c171) (g/queue-front [(g/task 0 (c/id :c171) {:op :young-artist :cost {:food 1} :optional? true} {})]))
        next (resolve-head s {:action :draw})]
    (is (= 2 (count (get-in next [:players 0 :hand :minor]))))
    (is (= 46 (:minor-deck-count next)))
    (is (= 1 (a/amount (g/p next 0) :food)))))

(deftest five-player-automatic-effect-thresholds
  (doseq [[code op context expected setup]
          [[:a175 :hollow-gardener {:taken {:clay 3}} {:grain 1} identity]
           [:a175 :hollow-gardener {:taken {:clay 6}} {:vegetable 1} identity]
           [:a178 :carpenters-boy {:rooms-built 3} {:wood 3} identity]
           [:b180 :game-teaser {:taken {:food 1}} {:cattle 1} identity]
           [:b180 :game-teaser {:taken {:food 2}} {:boar 1} identity]
           [:b180 :game-teaser {:taken {:food 3}} {:sheep 1} identity]
           [:b172 :cattle-caregiver {} {:food 3} #(update % :players (fn [ps] (mapv (fn [p] (a/gain p {:cattle 1})) ps)))]
           [:c174 :stone-custodian {} {:vegetable 1} #(assoc % :piles {:stone-west {:stone 1} :stone-east {:stone 2}})]
           [:c175 :village-teacher {:space :lessons} {:grain 1} #(assoc % :occupied {:lessons 0 :lessons-copse 1})]
           [:d177 :graduate {} {:food -1 :stone 2 :reed 2} identity]]]
    (let [s (setup (game)) after (effect s 0 code op context {})]
      (doseq [[r delta] expected]
        (is (= delta (- (a/amount (g/p after 0) r) (a/amount (g/p s 0) r))) (str code " " r)))))
  (let [s (-> (game) (own 0 :a169 :a176) (assoc-in [:players 0 :goods :wood] 5))]
    (is (= 15 (a/amount (g/p (effect s 0 :a176 :wheelmaker {} {}) 0) :wood))))
  (let [s (assoc-in (game) [:piles :traveling :food] 4) after (effect s 0 :c173 :top-outer {} {})]
    (is (= 6 (a/amount (g/p after 0) :food)))
    (is (zero? (get-in after [:piles :traveling :food]))))
  (let [s (game) one (effect s 0 :d176 :woodshacker {:space :wood} {})
        two (effect one 0 :d176 :woodshacker {:space :grove} {})
        three (effect two 0 :d176 :woodshacker {:space :copse} {})
        again (effect (assoc three :round 2) 0 :d176 :woodshacker {:space :riverbank} {})]
    (is (= [1 3 3 4] (mapv #(a/amount (g/p % 0) :clay) [one two three again])))))
(deftest five-player-optional-effects-and-before-after-events
  (let [s (-> (game) (own 0 :d174)) pending (move s :clay) after (resolve-head pending {})]
    (is (= :exchange (get-in pending [:effect-queue 0 :effect :op])))
    (is (= 1 (a/amount (g/p after 0) :vegetable)))
    (is (= 1 (a/amount (g/p after 0) :food))))
  (let [s (-> (rich (game)) (own 0 :c169)) pending (move s :clay) after (resolve-head pending {})]
    (is (= :fast-mason (get-in pending [:effect-queue 0 :effect :op])))
    (is (= :clay (:house (g/p after 0))))
    (is (= (a/amount (g/p s 0) :reed) (a/amount (g/p after 0) :reed))))
  (let [s (-> (game) (own 0 :b177 :c172) (own 1 :c172)) next (move s :plow {:cell 0})]
    (is (= 1 (a/amount (g/p next 0) :stone)))
    (is (= 1 (get-in next [:players 1 :card-state (c/id :c172) :food])))
    (is (nil? (get-in next [:players 0 :card-state (c/id :c172) :food]))))
  (let [s (-> (game) (own 0 :a170 :c179)
              (update-in [:players 0] a/gain {:wood 4})
              (g/queue-front [(g/task 0 nil {:op :housing} {})]))
        after (g/command s 0 {:type :ability :card (c/id :a170) :fences (a/edges 0)})]
    (is (= 4 (count (:fences (g/p after 0)))))
    (is (= 0 (a/amount (g/p after 0) :cattle))) ; Hayward is explicitly not a fencing action
    (is (= :housing (get-in after [:effect-queue 0 :effect :op]))))
  (let [s (-> (game) (own 0 :b170 :c179) (assoc :round 8 :active (into (a/base-actions 5 :cards) (take 9 order))))
        pending (g/drain (g/queue-front s [(g/event :round-start 0)])) after (resolve-head pending {:cell 0})]
    (is (= 4 (count (:fences (g/p after 0)))))
    (is (= 1 (a/amount (g/p after 0) :cattle))))
  (let [s (-> (game) (own 0 :a172) (assoc :occupied {:fishing 1 :traveling 2}))]
    (is (= 4 (a/amount (g/p (effect s 0 :a172 :boat-painter {} {:resource :food}) 0) :food)))))
