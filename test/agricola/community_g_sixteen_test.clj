(ns agricola.community-g-sixteen-test
 (:require [clojure.test :refer :all] [clojure.edn :as edn]
           [agricola.engine :as a] [agricola.card-rules :as c] [agricola.card-game :as g]
           [agricola.bot :as bot] [eurozone.card-scenarios :as scenarios]
           [eurozone.store :as store] [agricola.recording :as recording]
           [agricola.community-decks-test :refer [cid fresh rich own ready play reload resolve! emit op stock field]])
 (:import [java.nio.file Files]))
(defn run [s n op choice & [context]]
 (g/run-effect s (g/task 0 (cid "G" n) {:op op} (or context {})) choice))
(defn scenario [id] (scenarios/initial-state id (:names (scenarios/options id "Ada"))))
(deftest prints-and-definitions
 (doseq [n [15 35 37 38 42 54 80 82 91 96]]
  (is (c/card (cid "G" n)))
  (is (.isFile (java.io.File. (format "resources/public/art/agricola/cards/original-g%03d.jpg" n))))))
(deftest family-counselor-physical-board-and-real-adults
 (let [s (-> (fresh) (own 0 (cid "G" 15)) (assoc-in [:players 0 :worker-spaces] [:wood :clay]))]
  (is (= (inc (stock s 0 :food)) (stock (run s 15 :g-family-counselor {}) 0 :food)))
  (is (= (stock s 0 :food) (stock (run (assoc-in s [:players 0 :worker-spaces] [:wood :grain]) 15 :g-family-counselor {}) 0 :food)))
  (is (= (stock s 0 :food) (stock (run (assoc-in s [:players 0 :worker-spaces] [(cid "G" 96) (cid "G" 96)]) 15 :g-family-counselor {}) 0 :food)))
  (let [three (-> s (assoc-in [:players 0 :guests] 1) (assoc-in [:players 0 :worker-spaces] [:wood :clay :reed]))]
   (is (= 1 (stock (run three 15 :g-family-counselor {}) 0 :grain))))
  (let [newborn (-> s (assoc-in [:players 0 :family] 3) (assoc-in [:players 0 :newborn] 1))]
   (is (= (inc (stock s 0 :food)) (stock (run newborn 15 :g-family-counselor {}) 0 :food))))))
(deftest job-seeker-does-not-count-and-pays-on-resolution
 (let [s (scenario "job-seeker") before (stock s 0 :food) card (cid "G" 5)]
  (is (= 0 (c/occupation-count (g/p s 0) nil)))
  (is (= 0 (c/occupation-count (g/p s 0) :minor-prerequisite)))
  (is (= :card-action (op s)))
  (is (= before (stock (resolve! s {:skip? true}) 0 :food)))
  (let [after (resolve! s {:card card})]
   (is (= (dec before) (stock after 0 :food)))
   (is (true? (get-in after [:players 0 :card-state card :cannot-return-to-hand?])))
   (is (= 1 (c/occupation-count (g/p after 0) nil))))))
(deftest late-sleeper-guests-and-order
 (let [s (scenario "late-sleeper") before (:remaining (g/p s 0))
       awake (resolve! s {:skip? true}) asleep (resolve! (reload s) {})]
  (is (= 0 (:turn awake))) (is (= before (:remaining (g/p awake 0))))
  (is (= (inc before) (:remaining (g/p asleep 0))))
  (is (= 1 (:guests (g/p asleep 0))))
  (is (= 1 (:turn asleep)))
  (let [only-sleeper (-> asleep (assoc :turn 3) (update :players #(mapv (fn [p] (assoc p :remaining (if (zero? (:id p)) 1 0))) %)) g/advance)]
   (is (= 0 (:turn only-sleeper))))))
(deftest apprentice-mark-and-other-player-baking
 (let [s (scenario "master-bakers-apprentice")
       s (resolve! s {:space :grain}) s (resolve! s {:skip? true})
       s (-> s (assoc :turn 1) (a/build-improvement 1 {:improvement :stone-oven :bake-grain 0}))
       before (stock s 0 :food) s (g/command s 1 {:type :action :space :grain})]
  (is (= :bake (op s))) (is (= 1 (g/acting-player s)))
  (is (= before (stock (resolve! s {:skip? true}) 0 :food)))
  (is (= (inc before) (stock (resolve! (reload s) {:grain 1}) 0 :food)))))
(deftest oldest-occupation-growth-payment-and-room
 (let [s (scenario "oldest-occupation") before (stock s 0 :food) result (resolve! s {})]
  (is (= :grow (op s))) (is (= 3 (:family (g/p result 0))))
  (is (= before (stock result 0 :food)))
  (let [s (-> (fresh) rich (assoc :round 5) (own 0 (cid "G" 42))
              (update-in [:players 1] a/gain {:wood 5 :reed 2 :food 4})
              (update-in [:players 1] a/build-rooms [0])
              (emit :after-use-space 1 {:space :labor}))
        old (stock s 0 :food) paid (stock s 1 :food) after (resolve! s {})]
    (is (= (+ old 2) (stock after 0 :food))) (is (= (- paid 2) (stock after 1 :food))))))
(deftest tax-collector-paid-materials-and-optional-second
 (let [s (scenario "tax-collector") before (stock s 0 :clay) food (stock s 0 :food)]
  (is (= :g-tax-resource (op s)))
  (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:resource :stone})))
  (let [one (resolve! s {:resource :clay}) two (resolve! one {:resource :clay}) no (resolve! one {:skip? true})]
   (is (= (inc before) (stock no 0 :clay))) (is (= food (stock no 0 :food)))
   (is (= (+ before 2) (stock two 0 :clay))) (is (= (dec food) (stock two 0 :food))))))
(deftest harvest-feast-all-players-and-arbitrary-planted-amounts
 (let [s (scenario "harvest-feast") before (stock s 0 :grain)
       s (-> s (field 0 :grain 4) (assoc-in [:players 1 :farm 0] {:type :field :crop :grain :count 3}))
       after (resolve! s {:takes {0 {:grain 3} 1 {:vegetable 2}}})]
  (is (= (+ before 3) (stock after 0 :grain))) (is (= 1 (get-in after [:players 0 :farm 0 :count])))
  (is (= 1 (g/acting-player after))) (is (= :g-harvest-feast (op after)))
  (is (= 3 (get-in (resolve! after {:skip? true}) [:players 1 :farm 0 :count])))
  (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:takes {0 {:grain 5}}})))
  (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:takes {2 {:grain 1}}})))))
(deftest hired-helpers-final-chance-and-next-round-debt
 (let [s (scenario "hired-helpers") guest (resolve! s {})]
  (is (= :g-hired-helpers (op s)))
  (is (= 1 (:remaining (g/p guest 0)))) (is (= 1 (:guests (g/p guest 0))))
  (is (false? (boolean (g/g-hired-available? guest (g/p guest 0)))))
  (let [next (g/drain (g/begin-round guest))]
   (is (= 1 (:remaining (g/p next 0))))
   (is (false? (boolean (g/g-hired-available? next (g/p next 0))))))
  (is (not= :g-hired-helpers (op (resolve! s {:skip? true}))))))
(deftest pelt-roof-receipt-pairs-reserved-payment-only
 (let [s (scenario "pelt-roof") after (resolve! s {:amount 1}) p (assoc-in (g/p after 0) [:goods :reed] 0)
       p (assoc-in p [:card-state a/pelt-roof-id :stored-goods :reed] 3)]
  (is (= 1 (get-in after [:players 0 :card-state a/pelt-roof-id :stored-goods :reed])))
  (is (= 0 (a/amount p :reed)))
  (is (= 1 (a/pelt-roof-reed (a/build-rooms p [0]))))
  (is (= 2 (a/pelt-roof-reed (a/renovate p))))
  (is (thrown? clojure.lang.ExceptionInfo (a/build-rooms p [0] {:pelt-roof-reed 0})))
  (is (= 0 (get-in (resolve! s {:skip? true}) [:players 0 :card-state a/pelt-roof-id :stored-goods :reed] 0)))
  (is (thrown? clojure.lang.ExceptionInfo (run s 91 :g-pelt-roof {:amount 1} {:converted-goods {:sheep 1}})))))
(deftest private-school-occupation-cost-transfer
 (let [s (scenario "private-school") school (cid "G" 96) arch (cid "G" 5)
       result (g/command s 0 {:type :card-action :card-space school :action :lessons :card arch})]
  (is (= (stock s 0 :food) (stock result 0 :food))) (is (c/has? (g/p result 0) arch))
  (let [s (-> s (assoc :turn 1) (scenarios/put-in-hand 1 arch))
        after (g/command s 1 {:type :card-action :card-space school :action :lessons :card arch})]
   (is (= (inc (stock s 0 :food)) (stock after 0 :food)))
   (is (= (dec (stock s 1 :food)) (stock after 1 :food)))
   (is (c/has? (g/p after 1) arch)))))
(def review-actions
 {"family-counselor" [{:type :action :space :clay}]
  "job-seeker" [{:card (cid "G" 5)}]
  "late-sleeper" [{}]
  "master-bakers-apprentice" [{:space :grain} {:grain 1}]
  "oldest-occupation" [{}]
  "tax-collector" [{:resource :clay} {:skip? true}]
  "harvest-feast" [{:takes {0 {:grain 2} 1 {:vegetable 1}}}]
  "hired-helpers" [{}]
  "pelt-roof" [{:amount 1}]
  "private-school" [{:type :card-action :card-space (cid "G" 96) :action :lessons :card (cid "G" 5)}]})
(deftest prepared-scenarios-persist-replay-and-offer-bot-choices
 (doseq [[id commands] review-actions]
  (let [path (str (.resolve (Files/createTempDirectory "g-sixteen" (make-array java.nio.file.attribute.FileAttribute 0)) "games.edn"))
        service (store/make-store path) table (:id (store/transact! service "tester" {:event :create :scenario id :name "Ada"}))]
   (doseq [choice commands]
    (let [room (get @(:rooms service) table) state (:state room)
          cmd (if (:type choice) choice (merge {:type :resolve :decision (:decision-id state)} choice))]
      (when (seq (:effect-queue state))
        (is (seq (bot/decision-candidates state (g/acting-player state))) id))
      (store/transact! service "tester" {:event :command :id table :revision (:revision room) :command cmd})))
   (let [room (get @(:rooms service) table)]
    (is (= (:state room) (get-in @(:rooms (store/make-store path)) [table :state])) id)
    (is (= (:state room) (last (:frames (recording/timeline (:recording room))))) id)))))

(deftest sleeping-prevents-taster-and-declining-helpers-has-no-debt
 (let [s (scenario "late-sleeper") asleep (resolve! s {})
       asleep (assoc asleep :phase :preparation :starting-player 1)]
  (is (false? (boolean (g/taster-available? asleep 0))))
  (is (= 2 (:turn (g/apply-taster (assoc asleep :turn 2 :taster {:round (:round asleep) :player 0}))))))
 (let [declined (resolve! (scenario "hired-helpers") {:skip? true})
       next (g/drain (g/begin-round declined))]
  (is (= 2 (:remaining (g/p next 0))))
  (is (g/g-hired-available? next (g/p next 0)))))
(deftest counselor-adopted-newborn-and-relocated-worker
 (let [s (-> (fresh) (own 0 (cid "G" 15))
             (assoc-in [:players 0 :family] 3)
             (assoc-in [:players 0 :newborn] 0)
             (assoc-in [:players 0 :worker-spaces] [:wood :clay :reed]))]
  (is (= 1 (stock (run s 15 :g-family-counselor {}) 0 :grain)))
  (is (= 0 (stock (run (assoc-in s [:players 0 :worker-spaces] [:wood :clay :grain]) 15 :g-family-counselor {}) 0 :grain)))))
(deftest school-bot-can-select-an-occupation
 (is (seq (bot/card-action-candidates (scenario "private-school") 0))))
