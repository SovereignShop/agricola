(ns agricola.bot-test
  (:require [clojure.test :refer :all]
            [agricola.bot :as bot] [agricola.simulation :as sim]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]))

(deftest reproducible-setup-and-private-observation
  (let [state (sim/initial-state {:seed 731 :players 2}) pid (g/acting-player state)
        other (- 1 pid)
        changed (-> state (assoc-in [:players other :hand] {:occupation [(c/id :b097)] :minor []})
                    (update :round-order #(into [(first %)] (reverse (rest %)))))
        choose #(select-keys (bot/choose % pid (java.util.Random. 12)) [:command :value])]
    (is (= state (sim/initial-state {:seed 731 :players 2})))
    (is (not= state (sim/initial-state {:seed 732 :players 2})))
    (is (= (bot/observation state pid) (bot/observation changed pid)))
    (is (= (choose state) (choose changed)) "Opponent hands and unrevealed rounds cannot influence the policy")
    (is (= 1 (count (:round-order (bot/observation state pid)))))
    (is (nil? (get-in (bot/observation state pid) [:players other :hand])))))

(deftest previews-do-not-reveal-the-next-round
  (let [state (-> (sim/initial-state {:seed 3 :players 1}) (assoc-in [:players 0 :remaining] 1))
        cmd {:type :action :space :wood}
        preview (bot/preview (bot/observation state 0) 0 cmd)
        actual (g/command state 0 cmd)]
    (is (= 1 (:round preview)))
    (is (= 2 (:round actual)))
    (is (empty? (:effect-queue preview)))))

(deftest optional-effects-are-accepted-or-skipped-for-a-reason
  (let [base (-> (sim/initial-state {:seed 5 :players 2})
                 (assoc :turn 0 :round 14)
                 (assoc-in [:players 0 :occupations] [])
                 (assoc-in [:players 0 :minors] [(c/id :a056)])
                 (assoc-in [:players 0 :goods :food] 0))
        pending (g/command base 0 {:type :action :space :wood})
        hungry (:command (bot/choose pending 0 (java.util.Random. 1)))]
    (is (= :resolve (:type hungry)))
    (is (not (:skip? hungry)))
    ;; With spare food and the wood valued for an available room, retain it.
    (let [early (-> pending (assoc :round 3) (assoc-in [:players 0 :goods :food] 50))
          cmd (:command (bot/choose early 0 (java.util.Random. 1)))]
      (is (:skip? cmd)))
    (is (map? (g/command pending 0 hungry)))))

(deftest legality-filters-do-not-hide-engine-errors
  (is (nil? (bot/attempt #(a/require! false "Illegal candidate"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Internal defect"
                       (bot/attempt #(throw (ex-info "Internal defect" {:unexpected true}))))))

(deftest complete-recordings-replay-and-detect-corruption
  (let [events (atom []) result (sim/run-game {:seed 19 :players 1 :mode :draw :extra 2} #(swap! events conj %))
        file (java.io.File/createTempFile "agricola-replay-" ".edn")]
    (try
      (is (= 14 (:round result)))
      (is (< 0 (:moves result) 2000))
      (is (= 14 (count (get-in result [:coverage :drafted]))))
      (is (= 16 (count (get-in result [:coverage :dealt]))))
      (is (seq (get-in result [:coverage :effects])))
      (spit file (apply str (map #(str (pr-str %) "\n") @events)))
      (is (= :complete (:status (sim/replay file))))
      (is (= (:scores result) (:scores (sim/replay file))))
      (let [bad (assoc-in @events [1 :hash] "corrupt")]
        (spit file (apply str (map #(str (pr-str %) "\n") bad)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Replay diverged" (sim/replay file))))
      (spit file (apply str (map #(str (pr-str %) "\n") (take 3 @events))))
      (is (= :partial (:status (sim/replay file))))
      (finally (.delete file)))))

(deftest failures-leave-a-replayable-diagnostic-record
  (let [events (atom [])]
    (is (thrown? clojure.lang.ExceptionInfo
                 (sim/run-game {:seed 1 :players 2 :max-moves 0} #(swap! events conj %))))
    (is (= [:header :error] (mapv :record @events)))
    (is (= (get-in @events [0 :initial]) (get-in @events [1 :state])))))
