(ns agricola.original-i-occupations-b-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn] [clojure.java.io :as io]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot] [agricola.expansion-cards-test :refer [fresh own hand delta]]))
(defn cid [n] (first (filter #(and (= "I" (:deck (c/card %))) (= (str n) (:number (c/card %)))) (keys c/cards))))
(defn reload [s] (edn/read-string (pr-str s)))
(defn reply [s choice] (g/command s (g/acting-player s) (merge {:type :resolve :decision (:decision-id s)} choice)))
(defn op [s] (get-in s [:effect-queue 0 :effect :op]))
(defn fire [s event pid & [context]] (g/drain (g/queue-front s [(g/event event pid context)])))
(defn goods [s pid amounts] (assoc-in s [:players pid :goods] (merge (zipmap a/resources (repeat 0)) amounts)))
(defn play [s n] (g/drain (g/play-card (hand s (cid n)) 0 (cid n) 0 {})))
(defn rich [] (goods (fresh) 0 {:wood 30 :clay 30 :stone 30 :reed 30 :food 20}))
(defn boundary [cells] (set (keep #(when (= 1 (val %)) (key %)) (frequencies (mapcat a/edges cells)))))

(deftest second-half-contains-twenty-four-native-occupations
  (let [defs (edn/read-string (slurp (io/resource "agricola/original-i-occupations-b.edn")))]
    (is (= 24 (count defs)))
    (doseq [id (keys defs)]
      (is (= :occupation (:kind (c/card id))) (str id))
      (is (g/prerequisites? (fresh) 0 (c/card id))))))

(deftest bricklayer-combines-with-room-bases-and-discounts-all-improvement-clay
  (let [s (own (rich) 0 (cid 243)) p (g/p s 0)]
    (is (= 1 (get (a/improvement-cost p :fireplace-2 nil) :clay)))
    (is (= 0 (:clay (g/card-cost s 0 (c/card :catalog/original-base-e-20-simple-fireplace) 0))))
    (is (= 3 (:clay (a/room-cost (assoc p :house :clay) {}))))
    (is (= 0 (:clay (a/room-cost (assoc (g/p (own s 0 :catalog/original-base-e-37-clay-supports) 0) :house :clay) {:clay-supports? true}))))))

(deftest market-crier-is-optional-and-only-on-grain-space
  (let [before (own (fresh) 0 (cid 245))
        pending (g/command before 0 {:type :action :space :grain})
        accepted (reply (reload pending) {}) skipped (reply pending {:skip? true})]
    (is (= :all-gain (op pending)))
    (is (= 2 (delta before accepted :grain)))
    (is (= 1 (delta before accepted :vegetable)))
    (is (every? #(= 1 (a/amount (g/p accepted %) :grain)) [1 2 3]))
    (is (= 1 (delta before skipped :grain)))
    (is (zero? (a/amount (g/p skipped 1) :grain))))
  (is (empty? (:effect-queue (fire (own (fresh) 0 (cid 245)) :use-space 0 {:space :labor})))))

(deftest milking-hand-scales-and-scores-without-consuming-cattle
  (doseq [[n gain] [[0 0] [1 1] [2 1] [3 2] [4 2] [5 3]]]
    (let [before (-> (fresh) (own 0 (cid 246)) (goods 0 {:cattle n}))
          after (fire before :field-phase 0)]
      (is (= gain (delta before after :food))))
    (let [s (-> (fresh) (own 0 (cid 246)) (goods 0 {:cattle n}))]
      (is (= (quot n 2) (g/card-bonus s 0 (cid 246)))))))

(deftest butcher-needs-no-improvement-and-does-not-count-as-fireplace
  (let [s (-> (fresh) (own 0 (cid 247)) (own 0 :catalog/original-base-e-19-gypsy-s-crock)
              (goods 0 {:sheep 2 :boar 1 :cattle 1})) p (g/p s 0)]
    (is (= 7 (a/amount (a/convert-food p {:sheep 2 :boar 1 :cattle 1}) :food)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc s :phase :breeding) 0 {:type :convert :goods {:sheep 1}})))))

(deftest net-fisherman-claims-only-food-left-at-return-home
  (let [s (-> (fresh) (own 0 (cid 248)) (assoc-in [:piles :fishing] {:food 4 :wood 2}))
        reed (g/command s 0 {:type :action :space :market :choice :stone})
        pending (fire (assoc reed :phase :return-home) :return-home 0)
        after (reply (reload pending) {})]
    (is (= :net-fisherman (op pending)))
    (is (= 4 (delta reed after :food)))
    (is (= {:food 0 :wood 2} (get-in after [:piles :fishing])))
    (is (empty? (:effect-queue (fire (assoc-in reed [:piles :fishing :food] 0) :return-home 0)))))
  (let [s (own (fresh) 0 (cid 248))]
    (is (empty? (:effect-queue (fire (update-in s [:players 0] a/gain {:reed 1}) :return-home 0))))))

(deftest traveling-players-allows-puppeteer-and-pays-street-musician
  (let [s (-> (fresh) (own 1 (cid 249)) (own 2 (cid 257))
              (assoc-in [:players 1 :hand :occupation] [(cid 247)]))
        pending (g/command s 0 {:type :action :space :traveling})
        after (reply (reload pending) {:card (cid 247)})]
    (is (= 1 (a/amount (g/p pending 2) :grain)))
    (is (= :card-action (op pending)))
    (is (= 1 (g/acting-player pending)))
    (is (c/has? (g/p after 1) (cid 247)))
    (is (= -1 (- (a/amount (g/p after 1) :food) (a/amount (g/p s 1) :food))))))

(deftest sheep-whisperer-schedules-only-reachable-rounds
  (let [s (play (assoc (fresh) :round 5) 250)]
    (is (= #{9 12 14} (set (keys (get-in s [:players 0 :schedule])))))
    (is (every? #(= {:sheep 1} (:goods (first %))) (vals (get-in s [:players 0 :schedule]))))))

(deftest first-resource-buyers-track-every-player-and-reload
  (doseq [[card resource space] [[251 :reed :reed] [255 :stone :stone-west]]]
    (let [s (-> (fresh) (own 1 (cid card)) (update :active conj space) (assoc-in [:piles space resource] 2))
          pending (g/command s 0 {:type :action :space space})
          after (reply (reload pending) {})]
      (is (= :buy-action-resource (op pending)))
      (is (= 1 (a/amount (g/p after 0) resource)))
      (is (= 1 (a/amount (g/p after 1) resource)))
      (is (= 2 (delta pending after :food)))
      (is (= -1 (- (a/amount (g/p after 1) :food) (a/amount (g/p pending 1) :food))))
      (is (thrown? clojure.lang.ExceptionInfo (reply after {})))
      (let [again (-> after (assoc :turn 0) (update :occupied dissoc space) (assoc-in [:piles space resource] 2))]
        (is (not= :buy-action-resource (op (g/command again 0 {:type :action :space space})))))))
  (let [s (-> (fresh) (own 0 (cid 251)))
        own-first (g/command s 0 {:type :action :space :reed})
        next (g/command own-first 1 {:type :action :space :market})]
    (is (not= :buy-action-resource (op next)))))

(deftest pig-catcher-keeps-real-pile-wood-and-still-activates-wood-cards
  (let [s (-> (fresh) (own 0 (cid 253)) (own 0 :catalog/original-base-e-152-berry-picker)
              (assoc-in [:piles :wood :wood] 2))
        after (g/command s 0 {:type :action :space :wood :pig-catcher? true})]
    (is (= 0 (a/amount (g/p after 0) :wood)))
    (is (= 1 (a/amount (g/p after 0) :boar)))
    (is (= 1 (delta s after :food)))
    (is (= 2 (get-in after [:piles :wood :wood])))
    (is (some :pig-catcher? (bot/action-candidates s 0)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/command (assoc-in s [:piles :wood :wood] 1) 0 {:type :action :space :wood :pig-catcher? true})))))

(deftest pig-breeder-has-an-extra-birth-only-with-two-parents-and-room
  (let [p (-> (a/player 0 "A" 0) (assoc :fences (set (a/edges 0))) (a/gain {:boar 2}))
        s (-> (fresh) (assoc :round 12) (assoc-in [:players 0] p) (own 0 (cid 252)))
        after (fire s :round-end 0)]
    (is (= 3 (a/amount (g/p after 0) :boar)))
    (is (= 2 (a/amount (g/p (fire (assoc s :round 11) :round-end 0) 0) :boar)))
    (is (= 2 (a/amount (g/p (fire (assoc-in s [:players 0 :goods :sheep] 1) :round-end 0) 0) :boar)))))

(deftest groom-builds-one-stable-after-round-income
  (let [s (-> (fresh) (own 0 (cid 254)) (goods 0 {:wood 0})
              (assoc-in [:players 0 :house] :stone)
              (assoc-in [:players 0 :schedule 2] [{:card (cid 254) :goods {:wood 1}}]))
        pending (g/drain (g/begin-round s))
        after (reply (reload pending) {:cell 0})]
    (is (= :stable (op pending)))
    (is (= 1 (a/amount (g/p pending 0) :wood)))
    (is (get-in after [:players 0 :farm 0 :stable]))
    (is (zero? (a/amount (g/p after 0) :wood)))))

(deftest harvest-workshops-and-plane-offer-independent-saveable-exchanges
  (let [s (-> (fresh) (own 0 (cid 258)) (own 0 :catalog/original-base-e-26-plane)
              (goods 0 {:wood 2}) (assoc :phase :feeding))
        pending (fire s :feeding-start 0)
        one (reply (reload pending) {:option 0}) two (reply pending {:option 1})]
    (is (= 3 (delta s one :food)))
    (is (= 1 (a/amount (g/p one 0) :wood)))
    (is (= 4 (delta s two :food)))
    (is (zero? (a/amount (g/p two 0) :wood)))
    (is (some #(= 1 (:option %)) (bot/decision-candidates pending 0))))
  (let [s (-> (fresh) (own 0 (cid 256)) (goods 0 {:stone 1}) (assoc :phase :feeding))
        after (reply (fire s :feeding-start 0) {})]
    (is (= 3 (delta s after :food)))
    (is (zero? (a/amount (g/p after 0) :stone)))))

(deftest animal-dealer-excludes-the-five-player-mixed-market
  (let [s (-> (fresh) (own 0 (cid 259)) (update-in [:players 0 :improvements] conj :hearth-4)
              (update :active conj :boar) (assoc-in [:piles :boar :boar] 1))
        pending (g/command s 0 {:type :action :space :boar})
        after (reply pending {})]
    (is (= :animal-dealer (op pending)))
    (is (= 2 (a/amount (g/p after 0) :boar)))
    (is (= -1 (delta pending after :food)))
    (is (= :housing (op after))))
  (is (empty? (:effect-queue (fire (own (fresh) 0 (cid 259)) :use-space 0 {:space :animal-market})))))

(deftest taster-pays-one-food-and-restarts-normal-order-after-first-placement
  (let [s (-> (fresh) (own 3 (cid 260)))
        pending (g/drain (g/begin-round s))
        active (reply (reload pending) {})
        placed (g/command active 3 {:type :action :space :grain})
        next (g/command placed 0 {:type :action :space :wood})]
    (is (= :taster (op pending)))
    (is (= 3 (:turn active)))
    (is (= 1 (- (a/amount (g/p active 0) :food) (a/amount (g/p pending 0) :food))))
    (is (= -1 (- (a/amount (g/p active 3) :food) (a/amount (g/p pending 3) :food))))
    (is (= 0 (:turn placed)))
    (is (= 1 (:turn next))))
  (let [s (own (fresh) 0 (cid 260))]
    (is (not= :taster (op (g/drain (g/begin-round s)))))))

(deftest outrider-grain-is-available-before-sowing-and-follows-current-round
  (let [s (-> (fresh) (own 0 (cid 261))
              (assoc :round 1 :round-order (assoc (vec (mapcat identity a/stages)) 0 :sow-bake))
              (assoc :active (conj (vec (a/base-actions 4 :cards)) :sow-bake))
              (assoc-in [:players 0 :farm 0] {:type :field :count 0}))
        after (g/command s 0 {:type :action :space :sow-bake :plantings [{:cell 0 :crop :grain}]})]
    (is (= 3 (get-in after [:players 0 :farm 0 :count])))
    (is (= 0 (a/amount (g/p after 0) :grain)))
    (is (= 1 (a/amount (g/before-use-player s 0 :sow-bake) :grain)))
    (is (= 0 (a/amount (g/before-use-player (assoc s :round 2) 0 :sow-bake) :grain)))))

(deftest water-carrier-remembers-well-even-after-return-and-repeats-on-rebuild
  (let [s (fire (fresh) :build-improvement 1 {:improvement :well})
        played (play (assoc s :round 3) 262)
        again (fire played :build-improvement 1 {:improvement :well})
        schedule (get-in again [:players 0 :schedule])]
    (is (= 1 (:well-build-count s)))
    (is (= (set (range 4 15)) (set (keys schedule))))
    (is (= 2 (count (get schedule 4))))
    (is (= 2 (:well-build-count again))))
  (let [s (play (fresh) 262)]
    (is (empty? (get-in s [:players 0 :schedule])))))

(deftest fence-builder-uses-a-real-piece-and-activates-only-after-placement
  (let [s (-> (rich) (hand (cid 263)))
        pending (g/command s 0 {:type :action :space :lessons :card (cid 263)})
        placed (reply (reload pending) {:space :wood})
        after (g/command (assoc placed :turn 0) 0 {:type :action :space :wood})
        fenced (reply after {:fences (vec (a/edges 0))})]
    (is (= :mark-fence-space (op pending)))
    (is (some #(= :wood (:space %)) (bot/decision-candidates pending 0)))
    (is (= 14 (a/fences-available (g/p placed 0))))
    (is (= :build-fences (op after)))
    (is (= 4 (count (:fences (g/p fenced 0)))))
    (is (= 10 (a/fences-available (g/p fenced 0))))
    (is (= -4 (delta after fenced :wood)))
    (is (thrown? clojure.lang.ExceptionInfo (reply pending {:space :urgent-growth})))))

(deftest fence-builder-grants-a-second-fencing-action-including-hedgekeeper
  (let [s (-> (fresh) (own 0 (cid 263)) (own 0 :catalog/original-base-e-175-hedge-keeper)
              (own 1 (cid 264)) (goods 0 {:wood 2 :food 2})
              (assoc-in [:players 0 :card-state (cid 263) :marked-space] :fences)
              (assoc-in [:players 0 :discarded-fences] 1) (update :active conj :fences))
        first-build (g/command s 0 {:type :action :space :fences :fences (vec (a/edges 0))})
        additions (vec (remove (set (a/edges 0)) (a/edges 1)))
        done (reply first-build {:fences additions})]
    (is (= :build-fences (op first-build)))
    (is (= 7 (count (:fences (g/p done 0)))))
    (is (= 0 (a/amount (g/p done 0) :wood)))
    (is (= 2 (a/amount (g/p done 1) :wood)))))

(deftest fence-delivery-reserves-pieces-and-offers-a-saveable-free-build
  (let [s (play (goods (fresh) 0 {:food 2 :wood 0}) 265)
        pending (g/drain (g/begin-round (assoc s :round 6)))
        done (reply (reload pending) {:fences (vec (a/edges 0))})
        declined (reply pending {:skip? true})]
    (is (= {7 4 11 4} (get-in s [:players 0 :reserved-fences])))
    (is (= 7 (a/fences-available (g/p s 0))))
    (is (= :build-fences (op pending)))
    (is (= {11 4} (get-in pending [:players 0 :reserved-fences])))
    (is (= 4 (count (:fences (g/p done 0)))))
    (is (= 0 (a/amount (g/p done 0) :food)))
    (is (= 0 (a/amount (g/p done 0) :wood)))
    (is (= 11 (a/fences-available (g/p declined 0))))
    (is (= 2 (a/amount (g/p declined 0) :food)))
    (is (some #(and (:fences %) (bot/preview pending 0 %)) (bot/decision-candidates pending 0))))
  (let [s (play (-> (fresh) (assoc :round 8) (assoc-in [:players 0 :discarded-fences] 13)) 265)]
    (is (= {14 2} (get-in s [:players 0 :reserved-fences])))))

(deftest rancher-compares-farms-before-start-of-round-building
  (let [s (-> (fresh) (own 0 (cid 340)) (own 0 (cid 254))
              (assoc-in [:players 0 :house] :stone) (goods 0 {:wood 1}))
        pending (g/drain (g/begin-round s))
        done (reply pending {:cell 0})]
    (is (= 1 (a/amount (g/p pending 0) :wood)) "Tie before Groom grants no Rancher wood.")
    (is (= 0 (a/amount (g/p done 0) :wood)))
    (let [next (g/drain (g/begin-round done))]
      (is (= 1 (a/amount (g/p next 0) :wood))))))

(deftest buyers-wait-until-all-source-wood-return-decisions-finish
  (let [basket :catalog/original-base-e-34-basket
        s (-> (fresh) (own 0 basket) (own 1 (cid 234)) (goods 0 {:wood 10 :food 2})
              (assoc-in [:piles :wood :wood] 2))
        pending (g/command s 0 {:type :action :space :wood})
        returned (reply pending {})
        kept (reply pending {:skip? true})]
    (is (= :return-goods (op pending)))
    (is (not= :buy-action-resource (op returned)) "Old supply wood cannot substitute for returned source wood.")
    (is (= :buy-action-resource (op kept)))
    (is (= 11 (a/amount (g/p (reply kept {}) 0) :wood))))
  (let [s (-> (fresh) (own 0 (cid 253)) (own 1 (cid 234)) (goods 0 {:wood 10 :food 2})
              (assoc-in [:piles :wood :wood] 2))
        after (g/command s 0 {:type :action :space :wood :pig-catcher? true})]
    (is (not= :buy-action-resource (op after)))))

(deftest reed-buyer-tracks-fixed-three-player-market-before-card-is-played
  (let [s (g/initialize (a/new-game ["A" "B" "C"] (vec (mapcat identity a/stages)) 0))
        first (g/command s 0 {:type :action :space :market :choice :reed})
        before (own first 2 (cid 251))
        next (g/command before 1 {:type :action :space :reed})]
    (is (= (:round s) (get-in first [:first-action-goods :reed :round])))
    (is (not= :buy-action-resource (op next)))))

(deftest fence-delivery-can-combine-seven-fences-with-hedgekeeper-and-releases-all-on-skip
  (let [s (-> (fresh) (own 0 :catalog/original-base-e-175-hedge-keeper) (goods 0 {:food 2}) (play 265))
        pending (g/drain (g/begin-round (assoc s :round 6)))
        done (reply pending {:fences (vec (set (concat (a/edges 0) (a/edges 1))))})]
    (is (= 7 (count (:fences (g/p done 0)))))
    (is (= 0 (a/amount (g/p done 0) :wood)))
    (is (= 0 (a/amount (g/p done 0) :food)))))

(deftest outrider-does-not-follow-chamberlains-future-reveals
  (let [s (-> (fresh) (own 0 (cid 261)) (assoc :round 11)
              (assoc :revealed-round-order (vec (concat (repeat 10 :wood) [:cattle :cultivation :urgent-growth :redevelop]))))]
    (is (= 1 (a/amount (g/before-use-player s 0 :cattle) :grain)))
    (is (= 0 (a/amount (g/before-use-player s 0 :cultivation) :grain)))
    (is (= 1 (a/amount (g/before-use-player (assoc s :round 12) 0 :cultivation) :grain)))))

(deftest reed-buyer-does-not-cancel-net-fishermans-claim
  (let [s (-> (fresh) (own 0 (cid 248)) (own 1 (cid 251))
              (assoc-in [:piles :fishing :food] 3) (assoc-in [:piles :reed :reed] 1))
        purchased (reply (g/command s 0 {:type :action :space :reed}) {})
        pending (fire (assoc purchased :phase :return-home) :return-home 0)
        claimed (reply pending {})]
    (is (zero? (a/amount (g/p purchased 0) :reed)))
    (is (= :net-fisherman (op pending)))
    (is (= 3 (delta purchased claimed :food)))))

(deftest taster-is-unavailable-to-holiday-house-owner-in-round-fourteen
  (let [s (-> (fresh) (own 1 (cid 260)) (own 1 :catalog/original-base-i-71-holiday-house)
              (assoc :round 13))
        next (g/drain (g/begin-round s))]
    (is (not= :taster (op next)))
    (is (= 0 (:remaining (g/p next 1))))
    (is (= (a/amount (g/p s 1) :food) (a/amount (g/p next 1) :food)))))
