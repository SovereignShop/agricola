(ns eurozone.ascension-test
  (:require [clojure.test :refer :all] [clojure.data.json :as json]
            [eurozone.ascension :as a] [eurozone.discord :as discord])
  (:import [java.nio.file Files]))
(def alice "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
(def bob "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
(def eve "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
(defn act [s token room op & [args]] (a/transact! s token (merge {:op op :id (:id room) :revision (:revision room)} args)))
(defn create [s n bots] (a/transact! s alice {:op "create" :players n :bots bots :name "Alice"}))
(defn with-service [f]
  (let [path (str (.resolve (Files/createTempDirectory "ascension-test" (make-array java.nio.file.attribute.FileAttribute 0)) "tables.edn")) s (a/service path)]
    (try (f s path) (finally (a/stop-worker! s)))))
(deftest seats-authority-private-views-and-persistence
  (with-service
   (fn [s path]
    (let [room (create s 3 1)]
      (is (thrown-with-msg? Exception #"Wait for all" (act s alice room "start")))
      (let [joined (act s bob room "join" {:name "Bob"})]
       (is (= 1 (a/seat joined bob)))
       (is (thrown-with-msg? Exception #"changed" (act s bob room "join" {:name "Bob"})))
       (is (thrown-with-msg? Exception #"creator" (act s bob joined "start")))
       (let [playing (act s alice joined "start")
             state (:state playing) observer (:state (a/view playing eve)) one (:state (a/view playing alice))]
        (is (= 3 (count (:players state))))
        (is (= 3 (count (filter #(= "hand" (:zone %)) (:cards one)))))
        (is (not-any? #(= "hand" (:zone %)) (:cards observer)))
        (is (every? #(not (contains? observer %)) [:rng :deck :discard :eventDeck :eventDiscard :serial]))
        (is (= [3 3 3] (mapv :handCount (:players observer))))
        (is (not (clojure.string/includes? (json/write-str (a/view playing bob)) alice)))
        (is (thrown-with-msg? Exception #"Join a playing seat" (act s eve playing "command" {:action {:type "advance"}})))
        ;; Creator may disconnect: any seated human advances shared phases.
        (let [draft (reduce (fn [r _] (act s bob r "command" {:action {:type "advance"}})) playing (range 3))
              active (get-in draft [:state :active]) other (if (zero? active) bob alice)]
         (is (= 3 (get-in draft [:state :phase])))
         (is (thrown-with-msg? Exception #"another claimant" (act s other draft "command" {:action {:type "pass" :actor active}})))
         (is (= draft (get @(:rooms (a/service path)) (:id draft))))
         (is (= (:state (a/view draft bob)) (:state (a/view (get @(:rooms (a/service path)) (:id draft)) bob)))))))))))
(deftest multiplayer-game-completes-and-terminal-state-survives-restart
 (with-service
  (fn [s path]
   (let [room (act s alice (create s 2 1) "start")]
    (loop [room room steps 0]
     (is (< steps 700) "Bots and human passing must reach a terminal result.")
     (when (< steps 700)
      (if (= "finished" (:status room))
       (do (is (seq (get-in room [:state :winner :players])))
           (is (= room (get @(:rooms (a/service path)) (:id room))))
           (is (thrown? Exception (act s alice room "command" {:action {:type "advance"}}))))
       (let [state (:state room)]
        (if (and (#{3 4} (:phase state)) (get-in state [:players (:active state) :bot]))
          (do (swap! (:clients s) assoc :fake {:token alice :room (:id room)})
              (a/tick! s (fn [& _]))
              (recur (get @(:rooms s) (:id room)) (inc steps)))
          (recur (act s alice room "command" {:action {:type (if (#{3 4} (:phase state)) "pass" "advance")}}) (inc steps)))))))))))
(deftest wire-auth-and-observer-isolation
 (with-service
  (fn [s _]
   (let [sent (atom []) send (fn [channel raw] (swap! sent conj [channel (json/read-str raw :key-fn keyword)]))
         cfg {:private? true :secret "unit-test-secret" :allowed-users #{"alice" "bob"}}
         service (assoc s :authorize #(discord/authorize-peer! cfg %1 %2))
         proof (discord/auth cfg {:id "alice" :username "Alice"}) token (discord/user-token cfg "alice")]
    (a/route! service send :bad (json/write-str {:op "hello" :token alice}))
    (is (nil? (get @(:clients service) :bad)))
    (is (= "error" (get-in (last @sent) [1 :op])))
    (a/route! service send :alice (json/write-str {:op "hello" :token token :auth proof}))
    (is (= token (get-in @(:clients service) [:alice :token])))
    (a/route! service send :bad (json/write-str {:op "create" :players 2 :bots 1 :name "Intruder"}))
    (is (empty? @(:rooms service)))
    (a/route! service send :alice (json/write-str {:op "create" :players 2 :bots 1 :name "Alice"}))
    (is (= 1 (count @(:rooms service))))))))
