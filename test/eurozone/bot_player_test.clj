(ns eurozone.bot-player-test
  (:require [clojure.test :refer :all] [eurozone.store :as store]
            [eurozone.bot-player :as bot] [agricola.card-game :as game]
            [agricola.recording :as recording]))

(deftest bot-seats-do-not-grant-human-permissions
  (let [[_ room] (store/transition {} "host" {:event :create :name "Host" :mode :online :expected-players 3 :bots 2})
        id (:id room) service (store/make-store nil)]
    (reset! (:rooms service) {id room})
    (is (= [0] (get-in (store/permissions service "host") [id :seats])))
    (is (every? :bot? (rest (:seats room))))
    (doseq [n [-1 3 5 1.5 nil]]
      (is (thrown? clojure.lang.ExceptionInfo (store/with-bots room n))))
    (let [room (store/with-bots room 1)
          [_ room] (store/transition {id room} "guest" {:event :join :id id :revision 0 :name "Guest"})]
      (is (thrown? clojure.lang.ExceptionInfo (store/with-bots room 2)))
      (is (= 2 (count (:seats (store/with-bots room 0))))))))

(deftest mixed-human-and-bot-games-complete-and-replay
  (doseq [[n mode] [[2 :beginner] [3 :random] [4 :random] [5 :draw]]]
    (let [[_ initial] (store/transition {} "host" {:event :create :name "Host" :mode :online :expected-players n :bots (dec n) :setup {:mode mode :extra 2}})
          id (:id initial)
          [_ initial] (store/transition {id initial} "host" {:event :start :id id :revision 0})
          final (loop [room initial moves 0]
                  (is (< moves 1600) (str n " players " mode " must finish"))
                  (if (or (= :finished (get-in room [:state :phase])) (>= moves 1600)) room
                    (let [state (:state room) bot-pid (bot/actor room)
                          pid (or bot-pid (if (= :draw (:phase state)) (first (sort (get-in state [:draw :pending]))) (game/acting-player state)))
                          observed (bot/observation state pid)
                          command (bot/choose observed pid)
                          event (cond (some? bot-pid) {:event :bot-command :player pid :command command}
                                      (= :draw (:phase state)) (merge {:event :draw-pick :player pid} (select-keys command [:wave :card]))
                                      :else {:event :command :command command})
                          message (assoc event :id id :revision (:revision room))]
                      (is (every? nil? (keep-indexed #(when (not= pid %1) (:hand %2)) (:players observed))))
                      (when (and (some? bot-pid) (< moves 5))
                        (is (thrown? clojure.lang.ExceptionInfo (store/transition {id room} "guest" message))))
                      (let [[_ next-room] (store/transition {id room} "host" message)]
                        (recur next-room (inc moves))))))
          timeline (recording/timeline (:recording final))]
      (is (= :finished (get-in final [:state :phase])))
      (is (= (:state final) (peek (:frames timeline))))
      (is (= n (count (get-in final [:state :players])))))))
