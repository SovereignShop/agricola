(ns agricola.card-impact-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-impact :as impact]
            [agricola.recording :as rec] [eurozone.store :as store] [eurozone.model :as model]
            [datascript.core :as d]))
(def trap :catalog/original-base-i-95-fish-trap)
(def cave :catalog/original-expansion-wm-95-mountain-cave)
(def pond :catalog/original-expansion-wm-76-drained-pond)
(def mason :catalog/original-base-e-191-mason)
(defn fresh [] (g/initialize (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0)))
(defn room [state] {:id "impact-test" :title "Impact" :mode :local :state state :history [] :revision 0
                   :seats [{:name "Ada" :owner "host"} {:name "Ben" :owner "guest"}]})
(defn events [r pid cid] (get-in (impact/history (:recording r) (:state r)) [pid cid]))

(deftest fish-trap-counts-only-its-food-and-survives-saving-replay-and-takeback
  (let [r (room (-> (fresh) (assoc-in [:players 0 :minors] [trap])
                   (assoc-in [:players 0 :occupations] [:catalog/original-expansion-g-29-harbor-master])))
        one (store/recorded-command r 0 {:type :action :space :reed})
        two (store/recorded-command one 1 {:type :action :space :labor})
        three (store/recorded-command two 0 {:type :action :space :fishing})
        saved (edn/read-string (pr-str three))
        trace (events saved 0 trap)]
    (is (= 2 (count trace)))
    (is (= {:food 2} (:gained (impact/totals trace 0))))
    (is (= [:reed :fishing] (mapv :space trace)))
    (is (= [1 1] (mapv :round trace)))
    (is (= :partial (:status (rec/timeline (:recording saved)))))
    (is (= (:state saved) (peek (:frames (rec/timeline (:recording saved))))))
    (is (= {:food 1} (:gained (impact/totals (events (store/restore-previous saved) 0 trap) 0))))
    (let [db (d/db-with (d/empty-db model/schema) (model/game-tx saved))]
      (is (= trace (get-in (model/pull-game db "impact-test") [:card-impact 0 trap]))))
    (let [legacy (update saved :recording #(mapv (fn [record] (dissoc record :impacts)) %))]
      (is (= {:food 2} (:gained (impact/totals (events legacy 0 trap) 0)))))))

(deftest optional-payments-skips-fields-and-free-rooms-have-measured-results
  (let [base (-> (fresh) (assoc-in [:players 0 :minors] [cave pond])
                 (assoc-in [:players 0 :goods :wood] 2))
        pending (g/drain (g/queue-front base [(g/event :feeding-start 0)]))
        skipped (store/recorded-command (room pending) 0 {:type :resolve :decision (:decision-id pending) :skip? true})
        paid (store/recorded-command (room pending) 0 {:type :resolve :decision (:decision-id pending)})]
    (is (true? (:skipped? (first (events skipped 0 cave)))))
    (is (= {:gained {} :spent {} :farm {}} (impact/totals (events skipped 0 cave) 0)))
    (is (= {:gained {:stone 1} :spent {:wood 1} :farm {}} (impact/totals (events paid 0 cave) 0)))
    (let [pending (g/drain (g/queue-front base [(g/task 0 pond {:op :plow :cost {:food 1} :optional? true} {})]))
          after (store/recorded-command (room pending) 0 {:type :resolve :decision (:decision-id pending) :cell 0})]
      (is (= {:gained {} :spent {:food 1} :farm {:fields 1}} (impact/totals (events after 0 pond) 0)))))
  (let [base (-> (fresh) (assoc-in [:players 0 :occupations] [mason]) (assoc-in [:players 0 :house] :stone)
                 (assoc-in [:players 0 :farm 0] {:type :room}) (assoc-in [:players 0 :farm 6] {:type :room}))
        after (store/recorded-command (room base) 0 {:type :ability :card mason :rooms [1]})]
    (is (= {:rooms 1} (:farm (impact/totals (events after 0 mason) 0))))))

(deftest dynamic-amounts-are-measured-and-private-cards-are-not-published
  (let [before (assoc-in (fresh) [:players 0 :minors] [trap])
        after (update-in before [:players 0] #(-> % (a/pay {:food 1}) (a/gain {:food 3})))
        entry (impact/event before after {:player 0 :card trap :effect {:op :exchange}} {})]
    (is (= {:gained {:food 3} :spent {:food 1} :farm {}} (impact/totals [entry] 0)))
    (is (nil? (impact/event (fresh) after {:player 1 :card trap :effect {:op :gain}} {})))
    (is (empty? (impact/history [{:record :move :index 0 :impacts [(assoc entry :player 1)]}] before))))
  (let [unknown (impact/legacy-event {:round 4} {:card trap :player 0 :effect {:op :gain :goods {:food [:+ 2 3]}}})]
    (is (:unknown? unknown)) (is (empty? (:changes unknown)))))
