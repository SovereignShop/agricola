(ns agricola.original-conversions-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.bot :as bot] [agricola.original-animals-test :as animals]
            [agricola.original-animal-economy-test :as economy]))
(def crock :catalog/original-base-e-19-gypsy-s-crock)
(def plane :catalog/original-base-e-26-plane)
(defn player [ids goods improvements]
  (-> (apply animals/own (animals/player) ids)
      (assoc :goods (merge (zipmap a/resources (repeat 0)) goods) :improvements (set improvements))))
(defn state [p] (assoc-in (animals/fresh) [:players 0] p))
(defn food [p] (a/amount p :food))

(deftest conversion-cards-have-printed-costs-and-points
  (doseq [[cid goods cost points] [[crock {:clay 2} :clay 1] [plane {:wood 1} :wood 0]]]
    (let [before (assoc-in (state (player [] goods [])) [:players 0 :hand :minor] [cid])
          after (g/drain (g/play-card before 0 cid 0 {}))]
      (is (c/card cid))
      (is (= 0 (a/amount (g/p after 0) cost)))
      (is (= points (- (:total (g/score after 0)) (:total (g/score before 0))))))))

(deftest crock-counts-pairs-within-one-qualifying-batch
  (let [p (player [crock] {:sheep 3 :boar 1 :vegetable 1 :grain 1} [:hearth-4])]
    (is (= 14 (food (a/convert-food p {:sheep 3 :boar 1 :vegetable 1}))))
    (is (= 6 (food (a/convert-food p {:sheep 1 :boar 1}))))
    (is (= 5 (food (-> p (a/convert-food {:sheep 1}) (a/convert-food {:boar 1})))))
    (is (= 3 (food (a/convert-food p {:sheep 1 :grain 1}))) "Eating raw grain is not using a cooking improvement")
    (is (= 6 (food (a/convert-food (edn/read-string (pr-str p)) {:sheep 1 :boar 1}))))))

(deftest crock-does-not-reward-oven-only-meat-seller-or-noncooking-conversions
  (let [p (player [crock animals/meat] {:sheep 2 :grain 2 :vegetable 2} [:stone-oven])]
    (is (= 4 (food (a/convert-food p {:sheep 2}))))
    (is (= 2 (food (a/convert-food p {:grain 2}))))
    (is (= 8 (food (a/bake p 2))))
    (is (= 5 (food (a/convert-food (assoc p :improvements #{:fireplace-2 :stone-oven}) {:vegetable 2}))))
    (is (= 5 (food (a/convert-food (assoc p :improvements #{:fireplace-2 :stone-oven}) {:sheep 2}))))))

(deftest simultaneous-baking-and-cooking-can-make-a-pair
  (let [p (player [crock] {:grain 3 :sheep 3} [:hearth-4])]
    (is (= 6 (food (a/bake p 1 {:sheep 1}))))
    (is (= 5 (food (-> p (a/bake 1) (a/convert-food {:sheep 1})))))
    (is (= 12 (food (a/bake p 2 {:sheep 2}))))
    (is (= 11 (food (a/bake (assoc p :improvements #{:clay-oven :hearth-4}) 2 {:sheep 1}))))
    (is (= 8 (food (a/bake (assoc p :improvements #{:stone-oven :hearth-4}) 2))))
    (is (thrown? clojure.lang.ExceptionInfo (a/bake p 0 {:sheep 1})))
    (is (thrown? clojure.lang.ExceptionInfo (a/bake p 1 {:grain 3})))
    (is (thrown? clojure.lang.ExceptionInfo (a/bake p 1 {:wood 1})))))

(deftest bake-popup-reloads-and-brush-maker-receives-real-cooking-only
  (let [p (player [crock economy/brush] {:grain 1 :boar 1} [:hearth-4])
        pending (g/drain (g/queue-front (state p) [(g/task 0 nil {:op :bake :optional? true} {})]))
        reply {:grain 1 :cook-with-bake {:boar 1}}
        cooked (economy/resolve! (edn/read-string (pr-str pending)) reply)
        done (economy/resolve! cooked {:amount 1})]
    (is (= 7 (food (g/p cooked 0))))
    (is (= :collect-cooked-animal (get-in cooked [:effect-queue 0 :effect :op])))
    (is (= 1 (get-in done [:players 0 :card-state economy/brush :stored-goods :boar])))
    (is (some #(= reply (select-keys % [:grain :cook-with-bake])) (bot/decision-candidates pending 0)))))

(deftest crock-food-is-available-before-feeding-payment
  (let [p (player [crock] {:sheep 1 :boar 1} [:hearth-4])
        pending (assoc (state (assoc p :family 3)) :phase :feeding :fed #{} :turn 0)
        done (a/feed pending 0 {:convert {:sheep 1 :boar 1}})]
    (is (= 0 (get-in done [:players 0 :begging])))
    (is (= 0 (food (g/p done 0))))
    (is (some #(= {:sheep 1 :boar 1} (:convert %)) (bot/feeding-candidates pending 0)))))

(deftest plane-offers-a-real-extra-wood-choice-only-for-joinery
  (let [p (player [plane] {:wood 2 :reed 1} [:joinery :basketry])]
    (is (= 3 (food (a/craft-food p [:joinery] false))))
    (is (= 1 (a/amount (a/craft-food p [:joinery] false) :wood)))
    (is (= 4 (food (a/craft-food p [:joinery] true))))
    (is (= 0 (a/amount (a/craft-food p [:joinery] true) :wood)))
    (is (= 3 (food (a/craft-food p [:basketry] false))))
    (is (thrown? clojure.lang.ExceptionInfo (a/craft-food p [:basketry] true)))
    (is (thrown? clojure.lang.ExceptionInfo (a/craft-food p [:joinery :joinery] false)))
    (is (thrown? clojure.lang.ExceptionInfo (a/craft-food (assoc-in p [:goods :wood] 1) [:joinery] true)))
    (is (thrown? clojure.lang.ExceptionInfo (a/craft-food (assoc p :minors []) [:joinery] true)))
    (let [pending (assoc (state p) :phase :feeding :fed #{} :turn 0)
          extra (a/feed pending 0 {:craft [:joinery] :plane-extra? true})
          normal (a/feed pending 0 {:craft [:joinery]})]
      (is (= 0 (get-in extra [:players 0 :begging])))
      (is (= 1 (get-in normal [:players 0 :begging])))
      (is (some :plane-extra? (bot/feeding-candidates pending 0))))))

(deftest combined-cooking-survives-real-work-action-and-freshman-resumption
  (let [p (player [crock] {:grain 1 :sheep 1} [:hearth-4])
        before (update (state p) :active conj :sow-bake)
        cmd {:type :action :space :sow-bake :bake-grain 1 :cook-with-bake {:sheep 1}}
        after (g/command before 0 cmd)]
    (is (= 6 (food (g/p after 0))))
    (is (= 0 (a/amount (g/p after 0) :sheep)))
    (is (= 1 (get-in after [:players 0 :remaining])))
    (is (some #(= cmd (select-keys % (keys cmd))) (bot/action-candidates before 0)))
    (let [freshman (-> before (update-in [:players 0] animals/own (c/id :a097))
                             (assoc-in [:players 0 :hand :occupation] [(c/id :a116)]))
          pending (g/command freshman 0 cmd)
          baked (economy/resolve! (edn/read-string (pr-str pending)) {:grain 1 :cook-with-bake {:sheep 1}})
          replaced (economy/resolve! pending {:card (c/id :a116) :cook-with-bake {:sheep 1}})]
      (is (= :freshman-bake (get-in pending [:effect-queue 0 :effect :op])))
      (is (= 6 (food (g/p baked 0))))
      (is (= 1 (a/amount (g/p replaced 0) :grain)))
      (is (= 1 (a/amount (g/p replaced 0) :sheep)))
      (is (c/has? (g/p replaced 0) :a116)))))

(deftest new-oven-baking-cooks-in-the-same-batch-using-existing-hearth
  (let [p (player [crock] {:grain 2 :sheep 1 :clay 3 :stone 1} [:hearth-4])
        before (update (state p) :active conj :improvement)
        after (g/command before 0 {:type :action :space :improvement :improvement :clay-oven
                                   :bake-grain 2 :cook-with-bake {:sheep 1}})]
    ;; One grain uses the new clay oven; one uses the existing hearth.
    ;; Only the latter grain and the sheep form the Crock pair.
    (is (= 11 (food (g/p after 0))))
    (is (= 0 (a/amount (g/p after 0) :grain)))
    (is (= 0 (a/amount (g/p after 0) :sheep)))
    (is (contains? (:improvements (g/p after 0)) :clay-oven))))
