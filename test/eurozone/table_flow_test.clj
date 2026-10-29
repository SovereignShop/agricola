(ns eurozone.table-flow-test
  (:require [clojure.test :refer :all] [eurozone.store :as store] [eurozone.table-flow :as flow]
            [eurozone.model :as model] [agricola.engine :as a] [agricola.recording :as recording]
            [agricola.card-game :as game]))

(defn fixture [n state seconds]
  (let [s (store/make-store nil)
        id (:id (store/transact! s "p0" {:event :create :name "P0" :mode :online :expected-players n :turn-seconds seconds}))]
    (doseq [pid (range 1 n)]
      (store/transact! s (str "p" pid) {:event :join :id id :revision (dec pid) :name (str "P" pid)}))
    (with-redefs [store/start-state (fn [& _] state)]
      (store/transact! s "p0" {:event :start :id id :revision (dec n)}))
    [s id]))
(defn send! [s id token msg]
  (store/transact! s token (merge {:id id :revision (get-in @(:rooms s) [id :revision])} msg)))
(defn state [n]
  (a/new-game (mapv #(str "P" %) (range n)) (vec (mapcat identity a/stages)) 0))

(deftest harvest-queues-are-private-concurrent-and-ordered
  (let [initial (-> (state 3) (assoc :round 4 :phase :feeding :fed #{} :bred #{})
                    (update :players #(mapv (fn [p] (assoc-in p [:goods :food] 20)) %)))
        [s id] (fixture 3 initial 0)
        plan {:event :harvest-plan :round 4 :feed {:type :feed} :breed {:type :breed :offspring {}}}]
    (send! s id "p2" (assoc plan :player 2))
    (is (= initial (get-in @(:rooms s) [id :state])))
    (is (get-in (store/snapshot s "p2") [:private-plans id 2 :feed]))
    (is (empty? (get-in (store/snapshot s "p0") [:private-plans id])))
    (is (not (.contains (pr-str (:datoms (store/snapshot s "spy"))) ":harvest-plans")))
    (is (thrown? clojure.lang.ExceptionInfo (send! s id "p0" (assoc plan :player 2))))
    ;; A stale revision is safe here: the harvest identity and owner are checked.
    (send! s id "p0" (assoc plan :player 0 :revision 0))
    (is (= 1 (get-in @(:rooms s) [id :state :turn])))
    (send! s id "p1" (assoc plan :player 1 :revision 0))
    (let [room (get @(:rooms s) id) timeline (recording/timeline (:recording room))]
      (is (= :work (get-in room [:state :phase])))
      (is (= 5 (get-in room [:state :round])))
      (is (= [0 1 2 0 1 2] (mapv :player (:moves timeline))))
      (is (= [:feed :feed :feed :breed :breed :breed] (mapv #(get-in % [:command :type]) (:moves timeline))))
      (is (empty? (:harvest-plans room))))
    (is (thrown? clojure.lang.ExceptionInfo (send! s id "p2" (assoc plan :player 2))))))

(deftest queued-plans-revalidate-after-card-effects
  (let [initial (-> (state 2) (assoc :round 4 :phase :feeding :fed #{} :bred #{})
                    (assoc-in [:players 1 :goods :grain] 1))
        [s id] (fixture 2 initial 0)]
    (send! s id "p1" {:event :harvest-plan :round 4 :player 1 :feed {:type :feed :convert {:grain 1}}})
    ;; Simulate a preceding card effect spending the planned crop.
    (swap! (:rooms s) assoc-in [id :state :players 1 :goods :grain] 0)
    (send! s id "p0" {:event :command :command {:type :feed}})
    (is (= 1 (get-in @(:rooms s) [id :state :turn])))
    (is (nil? (get-in @(:rooms s) [id :harvest-plans 1 :feed])))
    (is (string? (get-in (store/private-plans s "p1") [id 1 :error])))))

(deftest majority-takeback-pauses-clock-and-restores-recording
  (let [time (atom 100000)]
    (with-redefs [flow/now #(deref time)]
      (let [[s id] (fixture 3 (state 3) 30)]
        (send! s id "p0" {:event :command :command {:type :action :space :wood}})
        (swap! time + 10000)
        (send! s id "p0" {:event :request-takeback :player 0})
        (let [v (get-in @(:rooms s) [id :takeback])]
          (is (= 2 (:needed v)))
          (is (nil? (flow/due (get @(:rooms s) id))))
          (is (thrown? clojure.lang.ExceptionInfo (send! s id "p1" {:event :command :command {:type :action :space :clay}})))
          (is (thrown? clojure.lang.ExceptionInfo (send! s id "p0" {:event :takeback-vote :player 1 :vote-id (:id v) :approve? true})))
          (swap! time + 20000)
          (send! s id "p1" {:event :takeback-vote :player 1 :vote-id (:id v) :approve? true :revision 0})
          (let [room (get @(:rooms s) id)]
            (is (= :approved (get-in room [:takeback-result :status])))
            (is (empty? (get-in room [:state :occupied])))
            (is (= 0 (get-in room [:state :turn])))
            (is (= 160000 (get-in room [:clocks 0 :deadline])))
            (is (empty? (:moves (recording/timeline (:recording room))))))
          (is (thrown? clojure.lang.ExceptionInfo (send! s id "p2" {:event :takeback-vote :player 2 :vote-id (:id v) :approve? true}))))))))

(deftest clocks-authorize-only-current-expired-decisions
  (let [time (atom 100000)]
    (with-redefs [flow/now #(deref time)]
      (let [[s id] (fixture 2 (state 2) 30)
            clock (get-in @(:rooms s) [id :clocks 0])
            message {:event :timeout-command :player 0 :clock-id (:id clock) :command {:type :action :space :wood}}]
        (is (thrown? clojure.lang.ExceptionInfo (send! s id "p0" message)))
        (swap! time + 30000)
        (is (= {:player 0 :clock-id (:id clock)} (flow/due (get @(:rooms s) id))))
        (is (thrown? clojure.lang.ExceptionInfo (send! s id "p1" message)))
        (send! s id "p0" message)
        (is (= 1 (get-in @(:rooms s) [id :state :turn])))
        (is (thrown? clojure.lang.ExceptionInfo (send! s id "p0" message)))
        (is (= 160000 (get-in @(:rooms s) [id :clocks 1 :deadline])))))))

(deftest rejected-vote-preserves-remaining-time-and-expires
  (let [time (atom 100000)]
    (with-redefs [flow/now #(deref time)]
      (let [[s id] (fixture 2 (state 2) 30)]
        (send! s id "p0" {:event :command :command {:type :action :space :wood}})
        (swap! time + 10000)
        (send! s id "p0" {:event :request-takeback :player 0})
        (let [vote-id (get-in @(:rooms s) [id :takeback :id])]
          (swap! time + 10000)
          (send! s id "p1" {:event :takeback-vote :player 1 :vote-id vote-id :approve? false})
          (is (= :declined (get-in @(:rooms s) [id :takeback-result :status])))
          (is (= 140000 (get-in @(:rooms s) [id :clocks 1 :deadline]))))
        (send! s id "p0" {:event :request-takeback :player 0})
        (swap! time + 60000)
        (send! s id "p0" {:event :expire-takeback :vote-id (get-in @(:rooms s) [id :takeback :id])})
        (is (= :expired (get-in @(:rooms s) [id :takeback-result :status])))
        (is (= 200000 (get-in @(:rooms s) [id :clocks 1 :deadline])))))))

(deftest draw-clocks-are-per-pending-player-and-do-not-refresh-on-another-pick
  (let [time (atom 100000)
        s {:phase :draw :round 0 :draw {:wave 1 :pending #{0 1 2}}}]
    (with-redefs [flow/now #(deref time)]
      (let [room (flow/refresh-clocks {:state s :turn-seconds 30})
            c (get-in room [:clocks 1])]
        (swap! time + 20000)
        (let [picked (flow/refresh-clocks (assoc-in room [:state :draw :pending] #{1 2}))]
          (is (nil? (get-in picked [:clocks 0])))
          (is (= c (get-in picked [:clocks 1])))
          (swap! time + 10000)
          (is (thrown? clojure.lang.ExceptionInfo (flow/require-time picked 1)))
          (is (= 1 (:player (flow/due picked))))
          (is (= 160000 (get-in (flow/refresh-clocks (update-in picked [:state :draw :wave] inc)) [:clocks 1 :deadline]))))))))

(deftest human-majority-excludes-bots-and-plans-wait-for-effects
  (let [initial (-> (state 3) (assoc :phase :feeding :round 4 :fed #{} :bred #{})
                    (update :players #(mapv (fn [p] (assoc-in p [:goods :food] 20)) %)))
        [s id] (fixture 3 initial 0)]
    (send! s id "p1" {:event :harvest-plan :player 1 :round 4 :feed {:type :feed}})
    (let [room (-> (get @(:rooms s) id) (assoc-in [:state :turn] 1)
                   (assoc-in [:state :effect-queue] [{:player 1 :card :revised-base/a002}]))]
      (is (= room (store/drain-plans room))))
    (send! s id "p0" {:event :command :command {:type :feed}})
    (swap! (:rooms s) assoc-in [id :seats 2 :bot?] true)
    (send! s id "p0" {:event :request-takeback :player 0})
    (let [v (get-in @(:rooms s) [id :takeback])]
      (is (= [0 1] (:voters v)))
      (is (= 2 (:needed v)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (send! s id "p2" {:event :takeback-vote :player 2 :vote-id (:id v) :approve? true}))))))

(deftest takeback-rewinds-requesters-turn-through-intervening-bots
  (let [initial (state 3) [s id] (fixture 3 initial 0)]
    (swap! (:rooms s) #(-> % (assoc-in [id :seats 1 :bot?] true) (assoc-in [id :seats 2 :bot?] true)))
    (send! s id "p0" {:event :command :command {:type :action :space :wood}})
    (send! s id "p0" {:event :bot-command :player 1 :command {:type :action :space :clay}})
    (send! s id "p0" {:event :bot-command :player 2 :command {:type :action :space :grain}})
    (is (= 3 (:steps (flow/takeback-target (get @(:rooms s) id) 0))))
    (send! s id "p0" {:event :request-takeback :player 0})
    (let [room (get @(:rooms s) id)]
      (is (= :approved (get-in room [:takeback-result :status])))
      (is (= 3 (get-in room [:takeback-result :steps])))
      (is (= initial (:state room)))
      (is (empty? (:history room)))
      (is (empty? (:moves (recording/timeline (:recording room))))))))

(deftest takeback-target-is-requester-even-after-another-human-moves
  (let [[s id] (fixture 3 (state 3) 0)]
    (is (thrown? clojure.lang.ExceptionInfo (send! s id "p2" {:event :request-takeback :player 2})))
    (send! s id "p0" {:event :command :command {:type :action :space :wood}})
    (let [before-p1 (get-in @(:rooms s) [id :state])]
      (send! s id "p1" {:event :command :command {:type :action :space :clay}})
      (send! s id "p2" {:event :command :command {:type :action :space :grain}})
      (send! s id "p1" {:event :request-takeback :player 1})
      (let [vote (get-in @(:rooms s) [id :takeback])]
        (is (= 1 (:player vote)))
        (is (= 2 (:steps vote)))
        (send! s id "p0" {:event :takeback-vote :player 0 :vote-id (:id vote) :approve? true}))
      (is (= before-p1 (get-in @(:rooms s) [id :state])))
      (is (= 1 (count (:moves (recording/timeline (get-in @(:rooms s) [id :recording])))))))))
