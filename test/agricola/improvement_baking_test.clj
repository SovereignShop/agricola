(ns agricola.improvement-baking-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.recording :as recording] [eurozone.card-scenarios :as scenarios]
            [eurozone.store :as store]
            [agricola.expansion-cards-test :refer [fresh own hand resolve!]]))

(defn rich []
  (-> (fresh) (update :active conj :improvement :renovate)
      (update-in [:players 0] a/gain {:wood 10 :clay 10 :reed 10 :stone 10 :grain 3 :food 10})))
(defn op [state] (get-in state [:effect-queue 0 :effect :op]))

(deftest major-ovens-offer-baking-after-construction
  (doseq [oven [:clay-oven :stone-oven] space [:improvement :renovate]]
    (let [before (rich)
          pending (g/command before 0 {:type :action :space space :improvement oven})
          done (resolve! (edn/read-string (pr-str pending)) {:grain 1})
          declined (resolve! pending {:skip? true})]
      (is (= :bake (op pending)))
      (is (= 0 (g/acting-player pending)))
      (is (contains? (get-in pending [:players 0 :improvements]) oven))
      (is (= 3 (a/amount (g/p pending 0) :grain)))
      (is (= (:bake (a/improvements oven)) (- (a/amount (g/p done 0) :food) (a/amount (g/p pending 0) :food))))
      (is (= 2 (a/amount (g/p done 0) :grain)))
      (is (= (:goods (g/p pending 0)) (:goods (g/p declined 0))))
      (is (= 1 (g/acting-player done) (g/acting-player declined)))
      (is (empty? (:effect-queue done)))
      (is (empty? (:effect-queue declined))))))

(deftest explicit-baking-commands-remain-compatible-with-recordings
  (doseq [quantity [0 1] oven [:clay-oven :stone-oven]]
    (let [before (rich)
          done (g/command before 0 {:type :action :space :improvement :improvement oven :bake-grain quantity})]
      (is (empty? (:effect-queue done)))
      (is (= (- 3 quantity) (a/amount (g/p done 0) :grain)))
      (is (= (* quantity (:bake (a/improvements oven))) (- (a/amount (g/p done 0) :food) (a/amount (g/p before 0) :food)))))))

(deftest granted-builds-and-major-as-minor-offer-baking
  (doseq [oven [:clay-oven :stone-oven]]
    (let [pending (g/drain (g/granted-card-action (rich) 0 {:kinds #{:major}} {:improvement oven}))]
      (is (= :bake (op pending)))
      (is (empty? (:effect-queue (resolve! pending {:grain 1}))))
      (is (empty? (:effect-queue (resolve! pending {:skip? true}))))))
  (let [before (own (rich) 0 :catalog/original-expansion-fr-25-grain-mill-oven)
        pending (g/command before 0 {:type :action :space :meeting :improvement :clay-oven :grain-mill-oven? true})]
    (is (= :bake (op pending)))
    (is (not (c/has? (g/p pending 0) :catalog/original-expansion-fr-25-grain-mill-oven)))
    (is (= 2 (a/amount (g/p (resolve! pending {:grain 1}) 0) :grain)))))

(deftest construction-grain-can-be-baked-and-normal-bake-triggers-fire-once
  (let [before (-> (rich) (assoc-in [:players 0 :goods :grain] 0)
                   (own 0 :catalog/original-expansion-wm-4-bargaining-baker)
                   (own 0 :catalog/original-base-e-41-millstone))
        pending (g/command before 0 {:type :action :space :improvement :improvement :clay-oven})
        done (resolve! pending {:grain 1})]
    (is (= :bake (op pending)))
    (is (= 1 (a/amount (g/p pending 0) :grain)))
    (is (= 7 (- (a/amount (g/p done 0) :food) (a/amount (g/p pending 0) :food))))))

(deftest no-impossible-or-unearned-offers
  (doseq [before [(rich) (assoc-in (rich) [:players 0 :goods :grain] 0)]
          improvement [:fireplace-2 :well]]
    (is (empty? (:effect-queue (g/command before 0 {:type :action :space :improvement :improvement improvement})))))
  (is (empty? (:effect-queue (g/command (assoc-in (rich) [:players 0 :goods :grain] 0) 0
                                         {:type :action :space :improvement :improvement :clay-oven})))))

(deftest all-on-play-baking-minors-have-review-positions
  (is (= (set (for [[id card] c/cards :when (and (= :minor (:kind card)) (some #(= :bake (:op %)) (:play card)))] id))
         (set (keep #(when (and (:baking-review? %) (c/card (:card %))) (:card %)) scenarios/scenarios)))))

(deftest prepared-baking-resolves-declines-persists-and-replays
  (doseq [{:keys [id card]} (filter :baking-review? scenarios/scenarios) skip? [false true]]
    (let [path (str (.resolve (java.nio.file.Files/createTempDirectory "baking-review" (make-array java.nio.file.attribute.FileAttribute 0)) "games.edn"))
          service (store/make-store path)
          untouched (:id (store/transact! service "other" {:event :create :name "Other"}))
          original (get @(:rooms service) untouched)
          room-id (:id (store/transact! service "tester" {:event :create :scenario id :name "Ada"}))
          pending (get-in @(:rooms service) [room-id :state])
          command (merge {:type :resolve :decision (:decision-id pending)} (if skip? {:skip? true} {:grain 1}))]
      (is (= :bake (op pending)) id)
      (is (= pending (get-in @(:rooms (store/make-store path)) [room-id :state])))
      (store/transact! service "tester" {:event :command :id room-id :revision 0 :command command})
      (let [room (get @(:rooms service) room-id) after (:state room)]
        (is (= (- (a/amount (g/p pending 0) :grain) (if skip? 0 1)) (a/amount (g/p after 0) :grain)) id)
        (is (= after (get-in @(:rooms (store/make-store path)) [room-id :state])))
        (is (= after (last (:frames (recording/timeline (:recording room))))))
        (is (= original (get @(:rooms service) untouched)))))))
