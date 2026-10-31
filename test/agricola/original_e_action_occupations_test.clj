(ns agricola.original-e-action-occupations-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot]))
(defn fresh ([] (fresh 4)) ([n] (g/initialize (a/new-game (mapv str (range n)) (vec (mapcat identity a/stages)) 0))))
(defn cid [n] (:id (first (filter #(and (= "Original" (:edition %)) (= "E" (:deck %)) (= (str n) (:number %))) (vals c/metadata)))))
(defn own [state pid n] (update-in state [:players pid :occupations] conj (cid n)))
(defn play [state pid n] (g/drain (g/play-card (update-in state [:players pid :hand :occupation] (fnil conj []) (cid n)) pid (cid n) 0 {})))
(defn resolve! [state choice] (g/command state (g/acting-player state) (merge {:type :resolve :decision (:decision-id state)} choice)))
(defn delta [before after pid resource] (- (a/amount (g/p after pid) resource) (a/amount (g/p before pid) resource)))
(defn wealthy [state pid] (update-in state [:players pid] a/gain {:wood 20 :clay 20 :reed 20 :stone 20 :grain 3 :food 10}))

(deftest master-baker-is-a-saved-reaction-without-its-own-food-bonus
  (let [before (-> (fresh) (own 0 149) (assoc :turn 1) (update :active conj :sow-bake)
                   (assoc-in [:players 0 :improvements] #{:stone-oven}) (assoc-in [:players 0 :goods :grain] 2)
                   (assoc-in [:players 1 :improvements] #{:clay-oven}) (assoc-in [:players 1 :goods :grain] 1))
        pending (g/command before 1 {:type :action :space :sow-bake :bake-grain 1})
        after (resolve! (edn/read-string (pr-str pending)) {:grain 1})]
    (is (= 0 (g/acting-player pending)))
    (is (= :bake (get-in pending [:effect-queue 0 :effect :op])))
    (is (= (cid 149) (get-in pending [:effect-queue 0 :card])))
    (is (some #(and (= 1 (:grain %)) (bot/preview pending 0 %)) (bot/decision-candidates pending 0)))
    (is (= 4 (delta before after 0 :food)))
    (is (= 0 (delta before (resolve! pending {:skip? true}) 0 :food)))
    (is (empty? (:effect-queue after))))
  (let [before (-> (fresh) (own 0 149) (update :active conj :sow-bake)
                   (assoc-in [:players 0 :improvements] #{:stone-oven}) (assoc-in [:players 0 :goods :grain] 1))
        after (g/command before 0 {:type :action :space :sow-bake :bake-grain 1})]
    (is (= 5 (delta before after 0 :food))))
  (let [before (-> (fresh) (own 0 149) (assoc-in [:players 1 :goods :grain] 1))
        after (g/command before 1 {:type :convert :goods {:grain 1}})]
    (is (empty? (:effect-queue after)))
    (is (= 0 (delta before after 0 :food)))))

(deftest baking-reaction-chain-cannot-return-to-an-already-reacting-owner
  ;; Multiple printing variants can eventually coexist. Exercise the engine's
  ;; finite response chain even when two players have Master Baker semantics.
  (let [before (-> (fresh) (own 0 149) (own 1 149) (assoc :turn 1) (update :active conj :sow-bake)
                   (assoc-in [:players 0 :improvements] #{:stone-oven}) (assoc-in [:players 0 :goods :grain] 2)
                   (assoc-in [:players 1 :improvements] #{:stone-oven}) (assoc-in [:players 1 :goods :grain] 2))
        first-response (g/command before 1 {:type :action :space :sow-bake :bake-grain 1})
        second-response (resolve! first-response {:grain 1})
        after (resolve! second-response {:grain 1})]
    (is (= 0 (g/acting-player first-response)))
    (is (= 1 (g/acting-player second-response)))
    (is (empty? (:effect-queue after)))
    (is (= 4 (delta before after 0 :food)))
    (is (= 9 (delta before after 1 :food)))))

(deftest head-of-family-shares-room-and-growth-spaces-with-other-players
  (let [before (-> (fresh) (own 0 159) (wealthy 0) (assoc-in [:occupied :build] 1))
        after (g/command before 0 {:type :action :space :build :rooms [0]})]
    (is (= [1 0] (g/space-owners after :build)))
    (is (= 3 (count (a/tiles (g/p after 0) :room))))
    (is (some #(and (= :build (:space %)) (bot/preview before 0 %)) (bot/action-candidates before 0)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc after :turn 0) 0 {:type :action :space :build :rooms [1]}))))
  (let [before (-> (fresh) (own 0 159) (update :active conj :growth)
                   (assoc-in [:players 0 :farm 0] {:type :room}) (assoc-in [:occupied :growth] 1))
        after (g/command before 0 {:type :action :space :growth})]
    (is (= 3 (get-in after [:players 0 :family])))
    (is (= [1 0] (g/space-owners after :growth)))))

(deftest head-of-family-shares-five-player-linked-alternatives-without-losing-occupants
  (let [before (-> (fresh 5) (own 0 159) (wealthy 0) (assoc-in [:occupied :traveling] 1))
        after (g/command before 0 {:type :action :space :house-building :rooms [0]})]
    (is (g/available-action-space? before 0 :house-building))
    (is (= 1 (get-in after [:occupied :traveling])))
    (is (= [0] (g/space-owners after :house-building)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc after :turn 0) 0 {:type :action :space :traveling}))))
  (let [before (-> (fresh 5) (own 0 159) (assoc-in [:occupied :house-building] 1)
                   (assoc-in [:piles :traveling] {:food 3}))
        after (g/command before 0 {:type :action :space :traveling})]
    (is (= 3 (delta before after 0 :food)))
    (is (= 1 (get-in after [:occupied :house-building])))
    (is (= [0] (g/space-owners after :traveling))))
  (let [before (-> (fresh 5) (own 0 159) (assoc :round 5) (assoc-in [:occupied :modest-growth] 1)
                   (update-in [:players 0 :hand :occupation] conj (cid 176)))
        after (g/command before 0 {:type :action :space :lessons-growth :card (cid 176)})]
    (is (= 1 (get-in after [:occupied :modest-growth])))
    (is (some #{(cid 176)} (get-in after [:players 0 :occupations]))))
  (let [before (-> (fresh 5) (own 0 159) (assoc :round 4)
                   (assoc-in [:occupied :lessons-growth] 1) (assoc-in [:players 0 :farm 0] {:type :room}))]
    (is (not (g/available-action-space? before 0 :modest-growth)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command before 0 {:type :action :space :modest-growth}))))
  (let [before (-> (fresh 5) (own 0 159) (wealthy 0) (assoc-in [:occupied :traveling] 0))]
    (is (not (g/head-family-permission? before 0 :house-building)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command before 0 {:type :action :space :house-building :rooms [0]})))))

(deftest field-warden-shares-each-listed-space-but-never-his-own-placement
  (doseq [[space opts] [[:plow {:cell 0}] [:vegetable {}] [:cultivation {:cell 0 :plantings [{:cell 0 :crop :grain}]}]]]
    (let [before (-> (fresh) (own 0 163) (update :active conj space)
                     (assoc-in [:occupied space] 1) (assoc-in [:players 0 :goods :grain] 1))
          after (g/command before 0 (merge {:type :action :space space} opts))]
      (is (= [1 0] (g/space-owners after space)))
      (is (nil? (g/occupied-permission (assoc-in before [:occupied space] [1 0]) 0 space)))
      (when (= space :cultivation) (is (= 3 (get-in after [:players 0 :farm 0 :count]))))))
  (is (nil? (g/occupied-permission (-> (fresh) (own 0 163) (assoc-in [:occupied :grain] 1)) 0 :grain))))

(deftest storyteller-returns-pile-food-and-preserves-dancers-minimum
  (doseq [pile [1 2 4 5 6] dancer? [false true]]
    (let [before (cond-> (-> (fresh) (own 0 169) (assoc-in [:piles :traveling] {:food pile})) dancer? (own 0 212))
          pending (g/command before 0 {:type :action :space :traveling})
          after (resolve! (edn/read-string (pr-str pending)) {})]
      (is (= 1 (get-in after [:piles :traveling :food])))
      (is (= 1 (delta before after 0 :vegetable)))
      (is (= (if dancer? (max 4 (dec pile)) (dec pile)) (delta before after 0 :food)))
      (is (= (if dancer? (max 4 pile) pile) (delta before (resolve! pending {:skip? true}) 0 :food)))
      (is (some #(and (not (:skip? %)) (bot/preview pending 0 %)) (bot/decision-candidates pending 0)))))
  (let [before (-> (fresh) (own 0 169) (assoc-in [:piles :traveling] {:food 0}))
        after (g/command before 0 {:type :action :space :traveling})]
    (is (empty? (:effect-queue after)))
    (is (zero? (delta before after 0 :vegetable))))
  (let [before (-> (fresh 5) (own 0 169) (own 0 159)
                   (assoc-in [:occupied :house-building] 1) (assoc-in [:piles :traveling] {:food 0}))]
    (is (thrown? clojure.lang.ExceptionInfo (g/command before 0 {:type :action :space :traveling})))))

(deftest stone-carrier-charges-only-when-this-action-also-delivers-other-building-goods
  (let [before (-> (fresh) (own 0 210) (update :active conj :stone-west)
                   (assoc-in [:piles :stone-west] {:stone 2}) (update-in [:players 0] a/gain {:wood 9})
                   (assoc-in [:players 0 :goods :food] 0))
        pending (g/command before 0 {:type :action :space :stone-west})
        after (resolve! pending {})]
    (is (= 3 (delta before after 0 :stone)))
    (is (= 0 (delta before after 0 :food))))
  (let [before (own (fresh) 0 210)
        pending (g/command before 0 {:type :action :space :market})
        after (resolve! pending {})]
    (is (= 2 (delta before after 0 :stone)))
    (is (= 1 (delta before after 0 :reed)))
    (is (= 0 (delta before after 0 :food)))
    (is (some #(and (not (:skip? %)) (bot/preview pending 0 %)) (bot/decision-candidates pending 0))))
  ;; A card-triggered receipt counts even when that resource is immediately paid
  ;; away. Old stock does not, and additional stone does not add the food cost.
  (let [before (-> (fresh) (own 0 210) (assoc-in [:players 0 :goods :food] 2))
        before-received (:received (g/p before 0))
        received (update-in before [:players 0] #(-> % (a/gain {:wood 1}) (a/pay {:wood 1})))
        pending (g/drain (g/queue-front received [(g/event :after-action 0 {:source-goods {:stone 1} :before-received before-received})]))
        after (resolve! pending {})]
    (is (= -1 (delta received after 0 :food)))
    (is (= 1 (delta received after 0 :stone))))
  (let [before (-> (fresh) (own 0 210) (assoc-in [:players 0 :goods :stone] 5))
        after (g/drain (g/queue-front before [(g/event :after-action 0 {:source-goods {:food 2}})]))]
    (is (empty? (:effect-queue after)))
    (is (= 5 (a/amount (g/p after 0) :stone)))))

(deftest ratcatcher-leaves-an-adult-idle-without-removing-or-discounting-feeding
  (doseq [round [10 12]]
    (let [before (-> (fresh) (own 0 198) (assoc :round (dec round))
                     (assoc-in [:players 0 :family] 3) (assoc-in [:players 1 :family] 2)
                     (assoc-in [:players 2 :family] 3) (assoc-in [:players 3 :family] 5))
          after (g/drain (g/begin-round before))]
      (is (= [3 2 2 4] (mapv :remaining (:players after))))
      (is (= [3 2 3 5] (mapv :family (:players after))))
      (is (= 6 (a/food-needed after (g/p after 2))))
      (is (= 10 (a/food-needed after (g/p after 3))))
      (is (= (:players after) (:players (g/apply-ratcatcher after))))
      (is (= [3 2 3 5] (mapv :remaining (:players (g/drain (g/begin-round after))))))))
  (let [late (play (assoc (fresh) :round 10) 0 198)]
    (is (not (some #{(cid 198)} (get-in late [:players 0 :occupations]))))
    (is (some #{(cid 198)} (:removed-cards late))))
  (let [state (-> (fresh) (own 0 198) (assoc :round 10)
                  (assoc-in [:players 1 :family] 3) (assoc-in [:players 1 :newborn] 1)
                  (assoc-in [:players 1 :remaining] 0) (assoc-in [:players 1 :goods :food] 1)
                  (update-in [:players 1 :occupations] conj (c/id :a092)))
        adopted (g/ability state 1 {:card (c/id :a092)})]
    (is (zero? (get-in adopted [:players 1 :remaining])))
    (is (= 3 (get-in adopted [:players 1 :family])))
    (is (zero? (get-in adopted [:players 1 :newborn]))))
  (let [state (-> (fresh) (own 0 198) (assoc :round 10)
                  (assoc-in [:players 1 :family] 3) (assoc-in [:players 1 :temporary-family] 1)
                  (assoc-in [:players 1 :remaining] 3))]
    (is (= 3 (get-in (g/apply-ratcatcher state) [:players 1 :remaining])))))

(deftest field-warden-can-use-an-original-plow-before-sowing-on-occupied-cultivation
  (let [plow :catalog/original-base-e-62-turnwrest-plow
        before (-> (fresh) (own 0 163) (update :active conj :cultivation)
                   (assoc-in [:occupied :cultivation] 1)
                   (update-in [:players 0 :minors] conj plow)
                   (assoc-in [:players 0 :card-state plow :uses] 1)
                   (assoc-in [:players 0 :goods :grain] 3))
        plan (g/command before 0 {:type :action :space :cultivation :cell 0})
        offer (resolve! plan {:plow-card plow :cells [1 2]})
        sow (resolve! offer {})
        after (resolve! (edn/read-string (pr-str sow)) {:plantings (mapv #(hash-map :cell % :crop :grain) [0 1 2])})]
    (is (= [1 0] (g/space-owners after :cultivation)))
    (is (= [3 3 3] (mapv #(get-in after [:players 0 :farm % :count]) [0 1 2])))
    (is (= 0 (get-in after [:players 0 :card-state plow :uses])))))
