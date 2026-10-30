(ns agricola.placement-cards-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))
(defn act [state pid command]
  (-> (g/take-action (assoc state :turn pid) pid command) bot/without-continuations g/drain))
(defn five [] (g/initialize (a/new-game ["A" "B" "C" "D" "E"] (vec (mapcat identity a/stages)) 0)))

(deftest bassinet-marks-one-shared-nonaccumulation-space-and-allows-only-a-second-person
  (let [state (-> (fresh) (own 0 :a025))
        wood (act state 1 {:space :wood}) grain (act wood 1 {:space :grain})
        twice (act (edn/read-string (pr-str grain)) 0 {:space :grain})]
    (is (nil? (:first-nonaccumulation wood)))
    (is (= :grain (:first-nonaccumulation grain)))
    (is (= 1 (delta grain twice :grain)))
    (is (= [1 0] (g/space-owners twice :grain)))
    (is (thrown? clojure.lang.ExceptionInfo (act twice 0 {:space :grain})))
    (is (= :grain (:first-nonaccumulation (act twice 2 {:space :labor})))))
  (let [state (act (own (fresh) 0 :a025) 0 {:space :meeting})]
    (is (thrown? clojure.lang.ExceptionInfo (act state 0 {:space :meeting}))))
  (let [state (-> (fresh) (own 0 :a025) (update :active conj :growth)
                  (assoc-in [:players 1 :farm 0] {:type :room}))
        grown (act state 1 {:space :growth})]
    (is (= 2 (g/space-people grown :growth)))
    (is (nil? (g/occupied-permission grown 0 :growth)))))

(deftest bassinet-can-use-a-private-card-space-but-does-not-ignore-its-requirements
  (let [state (-> (fresh) (own 0 :a025) (own 1 :a162) (assoc :occupied {:wood 2 :clay 3} :turn 1))
        used (g/command state 1 {:type :card-action :card-space (c/id :a162)})
        done (g/command (assoc used :turn 0) 0 {:type :card-action :card-space (c/id :a162)})]
    (is (= (c/id :a162) (:first-nonaccumulation used)))
    (is (nil? (g/card-space-error used 0 (c/id :a162))))
    (is (= 3 (delta used done :wood)))
    (is (= [1 0] (g/space-owners done (c/id :a162))))
    (is (some? (g/card-space-error done 0 (c/id :a162)))))
  (let [used (-> (five) (own 0 :a025) (assoc :first-nonaccumulation :lessons-growth :occupied {:modest-growth 1}))]
    (is (thrown? clojure.lang.ExceptionInfo (g/validate-space used 0 :lessons-growth)))))

(deftest forest-school-uses-occupied-lessons-and-splits-the-occupation-cost
  (doseq [wood [0 1 2]]
    (let [state (-> (fresh) (own 0 :a028) (own 0 :a103) (own 0 :a104) (hand :a116)
                    (assoc :occupied {:lessons-extra [1 2]})
                    (assoc-in [:players 0 :goods :wood] 2))
          done (g/command state 0 {:type :action :space :lessons-extra :card (c/id :a116) :occupation-wood wood})]
      (is (= [1 2 0] (g/space-owners done :lessons-extra)))
      (is (= (- wood) (delta state done :wood)))
      (is (= (- wood 2) (delta state done :food)))
      (is (c/has? (g/p done 0) :a116))))
  (doseq [wood [-1 3 0.5]]
    (let [state (-> (fresh) (own 0 :a028) (hand :a116))]
      (is (thrown? clojure.lang.ExceptionInfo (g/command state 0 {:type :action :space :lessons :card (c/id :a116) :occupation-wood wood})))))
  (let [state (-> (fresh) (own 0 :a103) (hand :a116) (update-in [:players 0] a/gain {:wood 1}))]
    (is (thrown? clojure.lang.ExceptionInfo (g/command state 0 {:type :action :space :lessons :card (c/id :a116) :occupation-wood 1}))))
  (let [state (-> (five) (own 0 :a028) (hand :a116) (assoc :occupied {:copse 1}))]
    (is (thrown? clojure.lang.ExceptionInfo (g/command state 0 {:type :action :space :lessons-copse :card (c/id :a116)})))))

(deftest forest-school-also-funds-granted-occupations-and-bots-see-sharing
  (let [state (-> (fresh) (own 0 :a028) (hand :a116) (assoc-in [:players 0 :goods :food] 0)
                  (update-in [:players 0] a/gain {:wood 1}))
        done (g/granted-card-action state 0 {:kinds #{:occupation} :occupation-cost 1} {:card (c/id :a116) :occupation-wood 1})]
    (is (c/has? (g/p done 0) :a116)) (is (= -1 (delta state done :wood))))
  (let [state (-> (fresh) (own 0 :a028) (own 0 :a103) (hand :a116) (assoc :occupied {:lessons 1})
                  (assoc-in [:players 0 :goods :food] 0) (update-in [:players 0] a/gain {:wood 1}))
        cmd {:type :action :space :lessons :card (c/id :a116) :occupation-wood 1}]
    (is (some #{cmd} (bot/action-candidates state 0)))
    (is (some? (bot/preview state 0 cmd)))))

(deftest mummys-boy-reuses-the-second-worker-once-per-round-after-two-placements
  (let [state (-> (fresh) (own 0 :a130) (assoc-in [:players 0 :family] 5) (assoc-in [:players 0 :remaining] 5))
        first-worker (act state 0 {:space :grain}) second-worker (act first-worker 0 {:space :labor})
        third-worker (act (edn/read-string (pr-str second-worker)) 0 {:space :labor})]
    (is (nil? (g/occupied-permission first-worker 0 :grain)))
    (is (= :mummys-boy (g/occupied-permission second-worker 0 :labor)))
    (is (= [:grain :labor :labor] (get-in third-worker [:players 0 :worker-spaces])))
    (is (= 2 (delta second-worker third-worker :food)))
    (is (= [0 0] (g/space-owners third-worker :labor)))
    (is (thrown? clojure.lang.ExceptionInfo (act third-worker 0 {:space :labor})))
    (is (thrown? clojure.lang.ExceptionInfo (act second-worker 0 {:space :grain})))
    (let [new-round (-> third-worker g/begin-round g/drain)]
      (is (= [] (get-in new-round [:players 0 :worker-spaces])))
      (is (nil? (:first-nonaccumulation new-round)))))
  (let [state (-> (fresh) (own 0 :a130) (assoc-in [:players 0 :family] 3) (assoc-in [:players 0 :remaining] 3))
        ready (-> state (act 0 {:space :grain}) (act 0 {:space :meeting}))]
    (is (thrown? clojure.lang.ExceptionInfo (act ready 0 {:space :meeting})))))

(deftest swagman-moves-the-same-worker-and-never-bounces-back
  (let [state (-> (fresh) (own 0 :a129) (update-in [:players 0] a/gain {:wood 5 :reed 2}))
        pending (g/command state 0 {:type :action :space :grain})
        done (resolve! (edn/read-string (pr-str pending)) {:action-command {:type :action :space :build :rooms [0]}})]
    (is (= :extra-action (get-in pending [:effect-queue 0 :effect :op])))
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (= [:build] (get-in done [:players 0 :worker-spaces])))
    (is (not (contains? (:occupied done) :grain)))
    (is (= [0] (g/space-owners done :build)))
    (is (= 1 (:turn done)))
    (is (= 1 (:turn-serial done)))
    (is (empty? (:effect-queue done)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:action-command {:space :wood}}))))
  (let [state (-> (fresh) (own 0 :a129) (assoc :occupied {:grain [1 2]})
                  (update-in [:players 0] a/gain {:wood 2}))
        pending (g/command state 0 {:type :action :space :build :stables [0]})
        done (resolve! pending {:action-command {:space :grain}})]
    (is (= 1 (delta pending done :grain)))
    (is (= [1 2 0] (g/space-owners done :grain)))
    (is (= [:grain] (get-in done [:players 0 :worker-spaces])))))

(deftest lazy-sowman-can-chain-occupied-cultivation-with-a-worker-each-time
  (let [state (-> (fresh) (own 0 :a094) (update :active conj :cultivation)
                  (assoc-in [:players 0 :family] 3) (assoc-in [:players 0 :remaining] 3))
        sow1 (g/command state 0 {:type :action :space :cultivation :cell 0})
        lazy1 (resolve! sow1 {:skip? true})
        sow2 (resolve! lazy1 {:action-command {:space :cultivation :cell 1}})
        lazy2 (resolve! (edn/read-string (pr-str sow2)) {:skip? true})
        done (resolve! lazy2 {:action-command {:space :grain}})]
    (is (= :lazy-sowman (get-in lazy1 [:effect-queue 0 :effect :mode])))
    (is (= 2 (get-in lazy1 [:players 0 :remaining])))
    (is (= :sow-offer (get-in sow2 [:effect-queue 0 :effect :op])))
    (is (= 1 (get-in lazy2 [:players 0 :remaining])))
    (is (= 0 (get-in done [:players 0 :remaining])))
    (is (= [0 0] (g/space-owners done :cultivation)))
    (is (= #{0 1} (set (a/tiles (g/p done 0) :field))))
    (is (= 1 (:turn done)))
    (is (= 1 (:turn-serial done)))
    (is (empty? (:effect-queue done)))
    (is (nil? (:action-context done)))))

(deftest lazy-sowman-cannot-reuse-meeting-or-trigger-from-cancelled-or-off-turn-sowing
  (let [state (-> (fresh) (own 0 :a094) (update :active conj :cultivation) (assoc :occupied {:meeting 1}))
        lazy (resolve! (g/command state 0 {:type :action :space :cultivation :cell 0}) {:skip? true})]
    (is (thrown? clojure.lang.ExceptionInfo (resolve! lazy {:action-command {:space :meeting}})))
    (is (= 1 (:turn (resolve! lazy {:skip? true})))))
  (let [state (-> (fresh) (own 0 :a094) (assoc :turn 1))
        done (g/drain (g/queue-front state [(g/event :sow-declined 0 {:unconditional? true})]))]
    (is (empty? (:effect-queue done))))
  (let [state (-> (fresh) (own 0 :a094) (own 1 :a132) (update :active conj :sow-bake)
                  (assoc-in [:players 0 :farm 0] {:type :field :count 0})
                  (update-in [:players 1] a/gain {:grain 1}))
        publican (resolve! (g/command state 0 {:type :action :space :sow-bake}) {})
        cancelled (g/command publican 1 {:type :resolve :decision (:decision-id publican) :skip? true})]
    (is (empty? (:effect-queue cancelled))) (is (= 2 (get-in cancelled [:players 0 :remaining])))))

(deftest minstrel-uses-a-revealed-card-effect-without-a-person-or-use-space-triggers
  (let [state (-> (fresh) (own 0 :a151) (own 0 :a126) (own 0 :a147)
                  (assoc :phase :return-home :turn 3)
                  (assoc-in [:players 0 :remaining] 0) (assoc-in [:piles :sheep] {:sheep 1}))
        pending (g/drain (g/queue-front state [(g/event :return-home 0)]))
        done (resolve! (edn/read-string (pr-str pending)) {:action-command {:space :sheep}})]
    (is (= :minstrel (get-in pending [:effect-queue 0 :effect :mode])))
    (is (= 1 (delta state done :sheep)))
    (is (= 0 (delta state done :wood)) "Master Workman is not triggered")
    (is (= 0 (get-in done [:players 0 :remaining])))
    (is (= :return-home (:phase done)))
    (is (= 3 (:turn done)))
    (is (empty? (:occupied done)))
    (is (empty? (:effect-queue done)) "Animal Dealer is not triggered")
    (is (empty? (get-in done [:players 0 :worker-spaces]))))
  (let [state (-> (fresh) (own 0 :a151) (update :active conj :improvement) (assoc :phase :return-home))]
    (is (empty? (:effect-queue (g/drain (g/queue-front state [(g/event :return-home 0)])))))))

(deftest minstrel-saved-sow-restores-return-home-and-does-not-trigger-lazy-sowman
  (let [order (vec (concat [:sow-bake :sheep :improvement :fences] (drop 4 (mapcat identity a/stages))))
        state (-> (g/initialize (a/new-game ["A" "B" "C" "D"] order 0)) (own 0 :a151) (own 0 :a065) (own 0 :a094)
                  (assoc :phase :return-home :turn 2) (assoc-in [:players 0 :remaining] 0)
                  (assoc-in [:players 0 :farm 0] {:type :field :count 0}))
        pending (g/drain (g/queue-front state [(g/event :return-home 0)]))
        offer (resolve! pending {:action-command {:space :sow-bake}})
        sow (resolve! offer {})
        done (resolve! (edn/read-string (pr-str sow)) {:plantings [{:cell 0 :crop :grain}]})]
    (is (= :sow-offer (get-in offer [:effect-queue 0 :effect :op])))
    (is (= :return-home (:phase done)))
    (is (= 2 (:turn done)))
    (is (= 0 (get-in done [:players 0 :remaining])))
    (is (empty? (:occupied done)))
    (is (empty? (:effect-queue done)))
    (is (= 3 (get-in done [:players 0 :farm 0 :count])))))

(deftest minstrel-cannot-apply-placement-only-command-bonuses
  (let [state (-> (fresh) (own 0 :a151) (own 0 :d180)
                  (assoc :phase :return-home) (assoc-in [:players 0 :remaining] 0)
                  (assoc-in [:piles :sheep] {:sheep 2}))
        pending (g/drain (g/queue-front state [(g/event :return-home 0)]))]
    (doseq [modifier [:part-time? :master-hora? :animal-brander? :shaving-horse? :lasso?]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (resolve! pending {:action-command {:space :sheep modifier true}}))))))

(deftest minstrel-runs-after-temporary-family-and-lodger-cleanup
  (doseq [kind [:telegram :lodger]]
    (let [state (cond-> (-> (fresh) (own 0 :a151)
                           (assoc :phase :return-home :turn 3 :occupied {:labor 0})
                           (assoc-in [:players 0 :family] 3)
                           (assoc-in [:players 0 :remaining] 0)
                           (assoc-in [:players 0 :worker-spaces] [:labor])
                           (assoc-in [:piles :sheep] {:sheep 1}))
                  (= kind :telegram) (assoc-in [:players 0 :temporary-family] 1)
                  (= kind :lodger) (own 0 :a127)
                  (= kind :lodger) (assoc :round 9)
                  (= kind :lodger) (assoc-in [:players 0 :card-state (c/id :a127) :room-active?] true))
          pending (-> state g/prepare-return-home
                      (g/queue-front [(g/event :return-home 0)]) g/drain)
          done (resolve! (edn/read-string (pr-str pending)) {:action-command {:space :sheep}})]
      (is (= :minstrel (get-in pending [:effect-queue 0 :effect :mode])))
      (is (= 2 (get-in pending [:players 0 :family])))
      (is (= 2 (get-in done [:players 0 :family])))
      (is (= 0 (get-in done [:players 0 :remaining])))
      (is (= {:labor 0} (:occupied done)) "Previously used spaces remain occupied throughout returning home")
      (is (= [:labor] (get-in done [:players 0 :worker-spaces])))
      (is (= :return-home (:phase done)))
      (is (= 1 (delta pending done :sheep)))
      (is (= (if (= kind :lodger) 4 5) (a/family-limit (g/p done 0)))))))

(deftest swagman-room-growth-is-not-duplicated-and-the-newborn-stays-on-the-source
  (let [state (-> (fresh) (own 0 :a129) (own 0 :a021)
                  (assoc-in [:players 0 :farm 0] {:type :room})
                  (update-in [:players 0] a/gain {:wood 5 :reed 2}))
        growth (g/command state 0 {:type :action :space :build :rooms [1]})
        pending (resolve! growth {})
        done (resolve! pending {:action-command {:space :grain}})]
    (is (= 3 (get-in pending [:players 0 :family])))
    (is (= 1 (delta state pending :food)))
    (is (= 3 (get-in done [:players 0 :family])))
    (is (= 1 (g/space-people done :build)))
    (is (empty? (g/space-owners done :build)))
    (is (contains? (:occupied done) :build))
    (is (= [0] (get-in done [:newborn-spaces :build])))
    (is (= [:grain] (get-in done [:players 0 :worker-spaces])))))

(deftest lazy-cancelled-child-sowing-preserves-one-parent-turn-advance
  (let [state (-> (fresh) (own 0 :a094) (own 1 :a132) (update :active conj :cultivation :sow-bake)
                  (update-in [:players 1] a/gain {:grain 1}))
        lazy (resolve! (g/command state 0 {:type :action :space :cultivation :cell 0}) {:skip? true})
        offer (resolve! lazy {:action-command {:space :sow-bake}})
        publican (resolve! offer {})
        done (g/command publican 1 {:type :resolve :decision (:decision-id publican) :skip? true})]
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (= 1 (:turn done)))
    (is (= 1 (:turn-serial done)))
    (is (= {:cultivation 0} (:occupied done)))
    (is (nil? (:action-context done)))
    (is (empty? (:effect-queue done)))))

(deftest nested-placement-bots-have-real-commands-and-context-has-no-hidden-snapshot
  (let [state (-> (fresh) (own 0 :a094) (update :active conj :cultivation))
        pending (resolve! (g/command state 0 {:type :action :space :cultivation :cell 0}) {:skip? true})
        candidates (bot/decision-candidates (bot/observation pending 0) 0)
        choose (first (filter #(= :grain (get-in % [:action-command :space])) candidates))
        staged (resolve! pending {:action-command {:space :cultivation :cell 1}})]
    (is (some? choose))
    (is (= 1 (:turn (g/command pending 0 choose))))
    (is (= #{:player :mode :phase :turn :remaining :occupied :worker-index} (set (keys (:action-context staged)))))
    (is (not-any? #(and (map? %) (or (contains? % :hand) (contains? % :round-order)))
                  (tree-seq coll? seq (:action-context (a/public-state staged)))))))

(deftest freshman-delayed-sow-decline-allows-lazy-sowman-and-advances-once
  (let [state (-> (fresh) (own 0 :a097) (own 0 :a094) (hand :a116) (update :active conj :sow-bake))
        freshman (g/command state 0 {:type :action :space :sow-bake})
        sow-offer (resolve! freshman {:card (c/id :a116)})
        lazy (resolve! (edn/read-string (pr-str sow-offer)) {:skip? true})
        done (resolve! lazy {:action-command {:space :grain}})]
    (is (= :sow-offer (get-in sow-offer [:effect-queue 0 :effect :op])))
    (is (= :lazy-sowman (get-in lazy [:effect-queue 0 :effect :mode])))
    (is (= {:sow-bake 0 :grain 0} (:occupied done)))
    (is (= 0 (get-in done [:players 0 :remaining])))
    (is (= 1 (:turn done)))
    (is (= 1 (:turn-serial done)))
    (is (nil? (:action-context done)))
    (is (nil? (:freshman-plan done)))))

(defn minstrel-freshman-state []
  (let [order (vec (concat [:sow-bake :sheep :improvement :fences] (drop 4 (mapcat identity a/stages))))]
    (-> (g/initialize (a/new-game ["A" "B" "C" "D"] order 0))
        (own 0 :a151) (own 0 :a097) (hand :a116)
        (assoc :phase :return-home :turn 3) (assoc-in [:players 0 :remaining] 0))))

(deftest minstrel-freshman-cancellation-restores-saved-return-home-context
  (let [state (minstrel-freshman-state)
        minstrel (g/drain (g/queue-front state [(g/event :return-home 0)]))
        freshman (resolve! minstrel {:action-command {:space :sow-bake}})
        done (resolve! (edn/read-string (pr-str freshman)) {:skip? true})]
    (is (= :freshman-bake (get-in freshman [:effect-queue 0 :effect :op])))
    (is (= :return-home (:phase done)))
    (is (= 3 (:turn done)))
    (is (= 0 (get-in done [:players 0 :remaining])))
    (is (empty? (:occupied done)))
    (is (empty? (:effect-queue done)))
    (is (nil? (:action-context done)))
    (is (nil? (:freshman-plan done)))))

(deftest minstrel-freshman-keeps-one-use-through-followup-baking
  (let [state (-> (minstrel-freshman-state) (own 0 :b025) (hand :a117)
                  (assoc-in [:players 0 :improvements] #{:fireplace-2})
                  (update-in [:players 0] a/gain {:grain 1}))
        minstrel (g/drain (g/queue-front state [(g/event :return-home 0)]))
        freshman (resolve! minstrel {:action-command {:space :sow-bake}})
        bake (resolve! (edn/read-string (pr-str freshman)) {:card (c/id :a116)})
        done (resolve! (edn/read-string (pr-str bake)) {:grain 1})]
    (is (= :bake (get-in bake [:effect-queue 0 :effect :op])))
    (is (not (g/freshman-available? bake 0)))
    (is (not (g/freshman-available? done 0)))
    (is (= :return-home (:phase done)))
    (is (= 3 (:turn done)))
    (is (= 0 (get-in done [:players 0 :remaining])))
    (is (empty? (:occupied done)))
    (is (c/has? (g/p done 0) :a116))
    (is (not (c/has? (g/p done 0) :a117)))))
