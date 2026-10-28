;; Diagnostic reproductions; does not modify rules or running games.
;; Run: clojure -M scripts/audit_card_timing.clj
(require '[agricola.engine :as a] '[agricola.card-game :as g] '[agricola.card-rules :as c])
(defn fresh [] (g/initialize (a/new-game ["Timing audit"] (vec (mapcat identity a/stages)) 0)))

(let [state (-> (fresh) (assoc :phase :breeding :round 14 :fed #{0} :bred #{})
                (assoc-in [:players 0 :occupations] [(c/id :b104)])
                (assoc-in [:players 0 :goods :sheep] 1))
      after (g/command state 0 {:type :breed :offspring {}})]
  (prn {:issue :sheep-walker-post-breeding
        :expected "An opportunity to exchange sheep before final scoring"
        :actual-phase (:phase after)
        :attempt (try (g/command after 0 {:type :ability :card (c/id :b104) :resource :vegetable})
                      :accepted (catch clojure.lang.ExceptionInfo e (.getMessage e)))}))

(doseq [owned [[:a090 :b097] [:b097 :a090]]]
  (let [state (-> (fresh)
                  (assoc-in [:players 0 :house] :stone)
                  (assoc-in [:players 0 :occupations] (mapv c/id owned))
                  (assoc-in [:players 0 :goods :food] 0)
                  (assoc-in [:players 0 :goods :wood] 1)
                  (assoc-in [:players 0 :hand :minor] [(c/id :b016)]))
        pending (g/drain (g/begin-round state))
        after (g/command pending 0 {:type :resolve :decision (:decision-id pending) :card (c/id :b016)})]
    (prn {:issue :round-start-affordability-order
          :occupation-order owned
          :expected "Scholar -> Mining Hammer supplies food, then Plow Driver can use it, regardless of card play order"
          :food-after-scholar (a/amount (g/p after 0) :food)
          :pending-after-scholar (select-keys (first (:effect-queue after)) [:card :effect])})))

(let [state (-> (fresh)
                (assoc-in [:players 0 :house] :stone)
                (assoc-in [:players 0 :occupations] [(c/id :b097)])
                (assoc-in [:players 0 :goods :food] 2)
                (assoc-in [:players 0 :hand :occupation] [(c/id :a090)]))
      pending (g/drain (g/begin-round state))
      after (g/command pending 0 {:type :resolve :decision (:decision-id pending) :card (c/id :a090)})]
  (prn {:issue :scholar-new-plow-driver
        :expected "Compendium A090 permits immediate use when played by Scholar at round start"
        :food-after-scholar (a/amount (g/p after 0) :food)
        :fields (count (a/tiles (g/p after 0) :field))
        :pending-after-scholar (select-keys (first (:effect-queue after)) [:card :effect])}))
