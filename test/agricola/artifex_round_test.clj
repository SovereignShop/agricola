(ns agricola.artifex-round-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(defn at-round [state r]
  (-> state (assoc :round r :active (vec (concat (a/base-actions (count (:players state)) :cards)
                                                (take r (:round-order state)))))))
(defn act [state pid cmd]
  (-> (g/take-action-complete (assoc state :turn pid) pid cmd) bot/without-continuations g/drain))

(deftest round-space-gains-follow-the-revealed-card-not-the-current-round
  (doseq [n [2 4 5] code [:a124 :a126]]
    (let [state (-> (g/initialize (a/new-game (mapv str (range n)) (vec (mapcat reverse a/stages)) 0))
                    (own 0 code) (at-round 14))]
      (doseq [[index space] (map-indexed vector (:round-order state))]
        (let [expected (if (= code :a124) (when (<= 4 index 6) {:stone 1})
                           (when (< index 4) {(nth [:wood :clay :reed :stone] index) 1}))
              actual (g/before-use-player (dissoc state :round-order) 0 space)]
          (is (= (inc index) (g/round-space-number state space)))
          (is (= (merge-with + (:goods (g/p state 0)) expected) (:goods actual)))))
      (doseq [space (a/base-actions n :cards)]
        (is (nil? (g/round-space-number state space)))
        (is (= (g/p state 0) (g/before-use-player state 0 space)))))))

(deftest pre-use-goods-can-pay-for-the-action-and-invalid-actions-are-atomic
  (let [state (-> (fresh) (own 0 :a124) (at-round 7)
                  (assoc-in [:players 0 :house] :clay)
                  (update-in [:players 0] a/gain {:stone 1 :reed 1}))
        cmd {:type :action :space :renovate}
        done (g/command state 0 cmd)]
    (is (= :stone (get-in done [:players 0 :house])))
    (is (= 0 (a/amount (g/p done 0) :stone)))
    (is (= 2 (get-in done [:players 0 :received :stone])))
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (= 1 (:turn done)))
    (is (some #(= cmd %) (bot/action-candidates (bot/observation state 0) 0)))
    (is (= :stone (get-in (bot/preview (bot/observation state 0) 0 cmd) [:players 0 :house])))
    (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc-in state [:players 0 :goods :reed] 0) 0 cmd)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc-in state [:occupied :renovate] 1) 0 cmd)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command (at-round state 5) 0 cmd)))
    (is (= 1 (a/amount (g/p state 0) :stone))))
  (let [order (vec (concat [:fences :sheep :sow-bake :improvement] (drop 4 (mapcat identity a/stages))))
        state (-> (g/initialize (a/new-game ["A" "B"] order 0)) (own 0 :a126)
                  (update-in [:players 0] a/gain {:wood 3}))
        done (g/command state 0 {:type :action :space :fences :fences (vec (a/edges 0))})]
    (is (= 4 (count (get-in done [:players 0 :fences]))))
    (is (= 0 (a/amount (g/p done 0) :wood))))
  (let [state (-> (fresh) (own 0 :a126) (at-round 4)
                  (assoc :round-order [:sheep :fences :sow-bake :improvement])
                  (assoc :active (vec (concat (a/base-actions 4 :cards) [:sheep :fences :sow-bake :improvement])))
                  (update-in [:players 0] a/gain {:stone 2 :wood 1}))
        done (g/command state 0 {:type :action :space :improvement :improvement :well})]
    (is (contains? (get-in done [:players 0 :improvements]) :well))
    (is (= 0 (a/amount (g/p done 0) :stone)))))

(deftest round-goods-do-not-trigger-for-card-space-imitation-or-client-flags
  (let [state (-> (fresh) (own 0 :a124) (own 0 :a126) (at-round 7)
                  (assoc-in [:players 0 :house] :clay)
                  (update-in [:players 0] a/gain {:stone 2 :reed 1}))
        forged (g/command state 0 {:type :action :space :grain :round-space 6 :before-use-round-goods {:stone 10}})]
    (is (= 0 (delta state forged :stone)))
    (is (= 0 (delta state forged :wood))))
  ;; Greenhouse Builder is B171; using its Renovation action does not use the round card.
  (let [state (-> (g/initialize (a/new-game ["A" "B" "C" "D" "E"] (vec (mapcat identity a/stages)) 0)) (own 0 :a124) (own 0 :b171) (at-round 7)
                  (assoc-in [:players 0 :house] :clay)
                  (update-in [:players 0] a/gain {:stone 1 :reed 1}))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/command state 0 {:type :card-action :card-space (c/id :b171) :action :renovate})))
    (let [ready (update-in state [:players 0] a/gain {:stone 1})
          done (g/command ready 0 {:type :card-action :card-space (c/id :b171) :action :renovate})]
      (is (= :stone (get-in done [:players 0 :house])))
      (is (= 0 (a/amount (g/p done 0) :stone)))))
  (let [state (-> (fresh) (own 0 :a126)
                  (assoc-in [:players 0 :card-state (c/id :a126) :face-down?] true))]
    (is (= (g/p state 0) (g/before-use-player state 0 :sheep)))))

(deftest staged-sowing-reload-gains-only-once-when-placement-commits
  (let [order (vec (concat [:sow-bake :sheep :improvement :fences] (drop 4 (mapcat identity a/stages))))
        state (-> (g/initialize (a/new-game ["A" "B"] order 0)) (own 0 :a126) (own 0 :a065)
                  (assoc-in [:players 0 :farm 0] {:type :field :count 0}))
        offer (g/command state 0 {:type :action :space :sow-bake})
        sow (resolve! offer {})
        done (resolve! (edn/read-string (pr-str sow)) {:plantings [{:cell 0 :crop :grain}]})]
    (is (= 0 (delta state offer :wood)))
    (is (= 0 (delta state sow :wood)))
    (is (= 1 (delta state done :wood)))
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (= 3 (get-in done [:players 0 :farm 0 :count])))))

(deftest patch-caretaker-requires-another-space-of-the-same-good-this-work-phase
  (let [state (-> (fresh) (own 0 :a161) (assoc-in [:players 0 :remaining] 5))
        wood (act state 0 {:space :wood :repeated-accumulation? true})
        grove (act (edn/read-string (pr-str wood)) 0 {:space :grove})
        copse (act grove 0 {:space :copse})
        clay (act copse 0 {:space :clay})
        hollow (act clay 0 {:space :hollow})]
    (is (= 0 (delta state wood :vegetable)))
    (is (= 1 (delta wood grove :vegetable)))
    (is (= 1 (delta grove copse :vegetable)))
    (is (= 0 (delta copse clay :vegetable)))
    (is (= 1 (delta clay hollow :vegetable)))
    (is (= #{:wood :grove :copse} (get (g/accumulation-history hollow 0) :wood)))
    (is (= #{:clay :hollow} (get (g/accumulation-history hollow 0) :clay)))
    (is (= 0 (delta wood (act wood 1 {:space :grove}) :vegetable)))
    (is (= 0 (delta wood (act (update wood :occupied dissoc :wood) 0 {:space :wood}) :vegetable)))
    (let [next-round (-> wood g/begin-round g/drain)
          taken (g/command next-round 0 {:type :action :space :grove})]
      (is (empty? (g/accumulation-history next-round 0)))
      (is (= 0 (delta next-round taken :vegetable))))))

(deftest patch-caretaker-tracks-use-before-it-is-played-and-excludes-taking-pile-goods
  (let [wood (act (fresh) 0 {:space :wood})
        state (own wood 0 :a161) done (act state 0 {:space :grove})]
    (is (= 1 (delta state done :vegetable))))
  (let [state (-> (fresh) (own 0 :a161) (own 0 :a082)
                  (assoc-in [:piles :wood] {:wood 4}))
        pending (g/command state 0 {:type :action :space :grain})
        taken (resolve! pending {:space :wood :resource :wood})
        done (act taken 0 {:space :grove})]
    (is (empty? (g/accumulation-history taken 0)))
    (is (= 0 (delta taken done :vegetable))))
  (let [state (-> (fresh) (own 0 :a161))
        market (act state 0 {:space :market})
        reed (act market 0 {:space :reed})]
    (is (= 0 (delta state reed :vegetable)))
    (is (nil? (get (g/accumulation-history reed 0) :stone)))))

(deftest patch-caretaker-covers-food-and-five-player-wood-spaces
  (let [state (own (fresh) 0 :a161) fishing (act state 0 {:space :fishing})
        done (act fishing 0 {:space :traveling})]
    (is (= 1 (delta fishing done :vegetable))))
  (let [state (-> (g/initialize (a/new-game ["A" "B" "C" "D" "E"] (vec (mapcat identity a/stages)) 0)) (own 0 :a161))
        wood (act state 0 {:space :wood}) done (act wood 0 {:space :riverbank})]
    (is (= 1 (delta wood done :vegetable))))
  (let [state (-> (fresh) (own 0 :a161) (assoc-in [:piles :wood] {:wood 0}))
        empty-use (act state 0 {:space :wood}) done (act empty-use 0 {:space :grove})]
    (is (= 1 (delta empty-use done :vegetable)))
    (is (= 0 (delta state empty-use :wood)))))

(deftest round-cards-native-player-count-and-occupation-cost
  (doseq [code [:a124 :a126 :a161]]
    (let [state (hand (fresh) code) done (g/command state 0 {:type :action :space :lessons :card (c/id code)})]
      (is (c/has? (g/p done 0) code))
      (is (= 1 (get-in done [:players 0 :remaining])))))
  (let [state (-> (g/initialize (a/new-game ["A" "B" "C"] (vec (mapcat identity a/stages)) 0)) (hand :a161))]
    (is (thrown? clojure.lang.ExceptionInfo (g/command state 0 {:type :action :space :lessons :card (c/id :a161)})))))
