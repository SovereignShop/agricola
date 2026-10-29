(ns eurozone.play-values-test
  (:require [clojure.test :refer :all] [eurozone.play-values :as learned]
            [eurozone.bot-player :as runtime] [agricola.afterstate-value :as value]
            [agricola.bot :as bot] [agricola.simulation :as sim]
            [agricola.recording :as recording]))

(deftest model-retains-its-recorded-training-provenance
  ;; Do not relabel existing weights as trained on newly added rules/cards.
  ;; Runtime legality/evaluator parity is tested below; strength needs new games.
  (is (= "694dc119425ef5e1829f5e3b0df184f135185351a3617a49af9f977d5fe18356" (get learned/model "rules")))
  (is (= "7852ad77cdd89bce5718656c52951cd0eeb8c0201b23e905ddcbd528cc3546e5" (get learned/model "source")))
  (doseq [kind ["random_gain" "draft_gain"] metric ["score_gain_95_interval" "margin_gain_95_interval"]]
    (is (pos? (first (get-in learned/model ["provenance" kind metric]))))))

(deftest complete-runtime-game-matches-tested-evaluator
  (let [events (atom [])]
    (with-redefs [sim/choose
                  (fn [state pid _ _]
                    (let [observed (runtime/observation state pid)
                          expected (:command (value/choose observed pid (java.util.Random. 42) learned/model 1.0 0.75))
                          actual (runtime/choose observed pid)]
                      (is (= expected actual))
                      {:command actual :value 0.0}))]
      (sim/run-game {:seed 941291 :players 2 :mode :random} #(swap! events conj %)))
    (is (= :complete (:status (recording/timeline @events))))))

(deftest larger-tables-retain-the-existing-policy
  (doseq [players [3 4 5]]
    (let [state (sim/initial-state {:seed 94131 :players players}) pid (sim/actor state)
          observed (runtime/observation state pid)]
      (is (nil? (learned/choice observed pid (java.util.Random. 42))))
      (is (= (:command (bot/choose observed pid (java.util.Random. 42))) (runtime/choose observed pid))))))
