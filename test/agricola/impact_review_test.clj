(ns agricola.impact-review-test
  (:require [clojure.test :refer :all] [agricola.card-impact-test :as fixture]
            [agricola.impact-review :as review] [agricola.recording :as rec]
            [agricola.simulation :as sim] [eurozone.store :as store]))

(deftest no-benefit-is-per-player-and-not-an-assertion-of-a-rule-bug
  (let [trap fixture/trap
        pond fixture/pond
        passive :catalog/original-expansion-wm-109-steel-plow
        state (-> (fixture/fresh) (assoc-in [:players 0 :minors] [trap pond passive])
                   (assoc-in [:players 1 :minors] [trap]))
        room (store/recorded-command (fixture/room state) 0 {:type :action :space :reed})
        rows (review/summarize (rec/timeline (:recording room)))
        row (fn [pid cid] (first (filter #(and (= pid (:player %)) (= (str cid) (:id %))) rows)))]
    (is (= "measured" (:reason (row 0 trap))))
    (is (false? (:zero-impact (row 0 trap))))
    (is (= "no-trigger" (:reason (row 1 trap))))
    (is (true? (:zero-impact (row 1 trap))))
    (is (= "unmeasured" (:reason (row 0 passive))))
    (is (= "unavailable" (:reason (first (review/summarize (rec/timeline (mapv #(dissoc % :impacts) (:recording room))))))))))

(deftest declined-and-zero-gain-effects-are-distinct
  (let [state (assoc-in (fixture/fresh) [:players 0 :minors] [fixture/trap])
        effect {:player 0 :card fixture/trap :effect {:op :gain} :choice {:skip? true}}
        timeline {:frames [state state] :moves [{:index 0 :round 1 :impacts [] :effects [effect]}]}]
    (is (= "declined" (:reason (first (review/summarize timeline)))))
    (is (= "no-measured-gain" (:reason (first (review/summarize (assoc-in timeline [:moves 0 :effects 0 :choice] {}))))))))

(deftest simulator-records-only-committed-impacts
  (let [records (atom []) state (assoc-in (fixture/fresh) [:players 0 :minors] [fixture/trap])]
    (with-redefs [sim/initial-state (constantly state)
                  sim/choose (fn [& _] {:command {:type :action :space :reed}})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (sim/run-game {:players 2 :max-moves 1} #(swap! records conj %)))))
    (let [move (second @records)]
      (is (= 1 (count (:impacts move))))
      (is (= {:food 1} (get-in move [:impacts 0 :changes 0 :gained]))))
    (is (= :failed-recording (:status (rec/timeline @records))))))
