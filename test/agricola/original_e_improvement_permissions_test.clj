(ns agricola.original-e-improvement-permissions-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.card-expressions :as expr] [agricola.bot :as bot]
            [agricola.expansion-cards-test :refer [fresh own hand resolve! delta]]))

(def tray :catalog/original-base-e-15-baking-tray)
(def ceramics :catalog/original-base-e-33-ceramics)
(def wood-oven :catalog/original-base-e-27-wood-fired-oven)
(defn rich [state] (update-in state [:players 0] a/gain {:wood 20 :clay 20 :reed 20 :stone 20 :grain 4 :food 20}))
(defn cost= [expected actual]
  (= (into {} (remove (comp zero? val) expected))
     (into {} (remove (comp zero? val) actual))))
(defn reload [state] (edn/read-string (pr-str state)))
(defn pending [state op]
  (g/drain (g/queue-front state [(g/task 0 tray
    (case op :card-action {:op op :kinds #{:minor} :optional? true}
             :young-artist {:op op :cost {:food 1} :optional? true}
             {:op op :optional? true}) {})])))

(deftest ceramics-requires-an-oven-and-immediately-pays-two-food
  (let [state (-> (fresh) (hand ceramics) (assoc-in [:players 0 :goods :clay] 1))]
    (is (not (g/prerequisites? state 0 (c/card ceramics))))
    (is (not (g/prerequisites? (assoc-in state [:players 0 :improvements] #{:fireplace-2}) 0 (c/card ceramics))))
    (is (thrown? clojure.lang.ExceptionInfo (g/play-card state 0 ceramics 0 {})))
    (doseq [oven [:clay-oven :stone-oven wood-oven]]
      (let [with-oven (if (a/improvements oven) (assoc-in state [:players 0 :improvements] #{oven}) (own state 0 oven))
            done (g/drain (g/play-card with-oven 0 ceramics 0 {}))]
        (is (= 2 (delta state done :food)))
        (is (zero? (get-in done [:players 0 :goods :clay])))
        (is (a/major-as-minor? (g/p done 0) :pottery))))))

(deftest baking-tray-discount-is-one-chosen-printed-material-on-exactly-three-ovens
  (let [state (own (fresh) 0 tray) player (g/p state 0)]
    (is (cost= {:clay 2 :stone 1} (a/improvement-cost player :clay-oven nil {:discount-resource :clay})))
    (is (cost= {:clay 3 :stone 0} (a/improvement-cost player :clay-oven nil {:discount-resource :stone})))
    (is (cost= {:clay 0 :stone 3} (a/improvement-cost player :stone-oven nil {:discount-resource :clay})))
    (is (cost= {:clay 1 :stone 2} (a/improvement-cost player :stone-oven nil {:discount-resource :stone})))
    (is (cost= {:wood 2 :stone 1} (g/card-cost state 0 (c/card wood-oven) 0 {:discount-resource :wood})))
    (is (cost= {:wood 3 :stone 0} (g/card-cost state 0 (c/card wood-oven) 0 {:discount-resource :stone})))
    (doseq [id [:fireplace-2 :hearth-4 :well :pottery :joinery :basketry]]
      (is (= (:cost (a/improvements id)) (into {} (remove (comp zero? val) (a/improvement-cost player id nil))))))
    (is (empty? (a/improvement-discount-resources player :catalog/original-base-e-14-baker-s-oven)))
    (is (thrown? clojure.lang.ExceptionInfo (a/improvement-cost player :clay-oven nil {:discount-resource :wood})))
    (is (thrown? clojure.lang.ExceptionInfo (a/improvement-cost (g/p (fresh) 0) :clay-oven nil {:discount-resource :stone})))
    (is (thrown? clojure.lang.ExceptionInfo (g/card-cost state 0 (c/card wood-oven) 0 {:discount-resource :clay})))))

(deftest default-discount-selects-an-affordable-option-and-combines-with-stonecutter
  (let [state (-> (fresh) (own 0 tray) (hand wood-oven)
                  (assoc-in [:players 0 :goods :wood] 3) (assoc-in [:players 0 :goods :stone] 0))
        done (g/drain (g/play-card state 0 wood-oven 0 {}))]
    (is (c/has? (g/p done 0) wood-oven))
    (is (zero? (get-in done [:players 0 :goods :wood]))))
  (let [player (-> (fresh) (own 0 tray) (own 0 :catalog/original-base-e-211-stonecutter) (g/p 0))]
    (is (cost= {:clay 2 :stone 0} (a/improvement-cost player :clay-oven nil {:discount-resource :clay})))
    (is (cost= {:clay 3 :stone 0} (a/improvement-cost player :clay-oven nil {:discount-resource :stone})))
    (is (every? #(<= 0 %) (vals (a/improvement-cost player :clay-oven nil {:discount-resource :stone}))))))

(deftest baking-tray-permits-normal-minor-spaces-and-retains-immediate-baking
  (doseq [space [:meeting :growth :renovate :improvement]]
    (let [state (-> (fresh) rich (own 0 tray) (own 0 :catalog/original-base-e-41-millstone)
                    (update :active #(vec (distinct (conj % :growth :renovate :improvement))))
                    (update-in [:players 0] a/place-tile :room 0))
          done (g/command state 0 {:type :action :space space :improvement :clay-oven
                                   :discount-resource :stone :bake-grain 1})]
      (is (contains? (get-in done [:players 0 :improvements]) :clay-oven) (name space))
      (is (not (contains? (:available-improvements done) :clay-oven)))
      (is (= -1 (delta state done :grain)))
      (is (= 7 (delta state done :food)) "Clay Oven baking triggers Millstone, including on minor spaces.")
      (is (= 1 (:turn done)))
      (is (= 1 (get-in done [:players 0 :remaining])))))
  (let [state (rich (fresh))]
    (doseq [space [:meeting :growth :wood :grain]]
      (is (thrown? clojure.lang.ExceptionInfo (g/command state 0 {:type :action :space space :improvement :clay-oven}))))))

(deftest ceramics-takes-only-available-pottery-for-free-via-a-real-improvement-action
  (let [state (own (fresh) 0 ceramics)
        done (g/command state 0 {:type :action :space :meeting :improvement :pottery})]
    (is (= (:goods (g/p state 0)) (:goods (g/p done 0))))
    (is (contains? (get-in done [:players 0 :improvements]) :pottery))
    (is (not (contains? (:available-improvements done) :pottery)))
    (is (= 1 (get-in done [:players 0 :remaining])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/command (update state :available-improvements disj :pottery) 0
                            {:type :action :space :meeting :improvement :pottery})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/command (rich state) 0 {:type :action :space :meeting :improvement :joinery})))))

(deftest granted-minor-actions-scholar-and-young-artist-share-supply-permissions
  (doseq [op [:card-action :scholar :young-artist]
          [permission major] [[tray :clay-oven] [ceramics :pottery]]]
    (let [state (-> (fresh) rich (own 0 permission))
          waiting (pending state op)
          choice (cond-> {:improvement major :action :play}
                   (= permission tray) (assoc :discount-resource :stone :bake-grain 1))
          done (resolve! (reload waiting) choice)]
      (is (contains? (get-in done [:players 0 :improvements]) major) (str op " " major))
      (is (= 2 (get-in done [:players 0 :remaining])) "Card-granted actions do not place a worker.")
      (is (= (if (= permission tray) (- 5 (if (= op :young-artist) 1 0))
                 (if (= op :young-artist) -1 0)) (delta state done :food)))
      (is (some #(and (= major (:improvement %)) (bot/preview waiting 0 %))
                (bot/decision-candidates waiting 0)))))
  (let [state (pending (rich (fresh)) :card-action)]
    (is (thrown? clojure.lang.ExceptionInfo (resolve! state {:improvement :clay-oven}))))
  (let [state (pending (-> (fresh) rich (own 0 tray)) :card-action)]
    (is (thrown? clojure.lang.ExceptionInfo (resolve! state {:improvement :pottery})))))

(deftest supply-permission-does-not-bypass-granted-action-restrictions
  (let [state (-> (fresh) rich (own 0 tray) (own 0 ceramics))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/granted-card-action state 0 {:kinds #{:occupation}} {:improvement :pottery})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/granted-card-action state 0 {:kinds #{:minor} :major-ids #{:clay-oven}} {:improvement :stone-oven})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/granted-card-action state 0 {:kinds #{:major :minor} :minimum-paid {:stone 1}}
                                       {:improvement :pottery})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/granted-card-action state 0 {:kinds #{:major :minor} :minimum-paid {:stone 1}}
                                       {:improvement :clay-oven :discount-resource :stone})))))

(deftest supply-card-classification-does-not-double-count-one-card
  (doseq [[permission major] [[tray :clay-oven] [ceramics :pottery]]]
    (let [state (-> (fresh) (own 0 permission) (assoc-in [:players 0 :improvements] #{major}))]
      (is (= 2 (expr/evaluate state 0 {} [:minors])))
      (is (= 1 (expr/evaluate state 0 {} [:majors])))
      (is (= 2 (expr/evaluate state 0 {} [:improvements])))
      (is (not (expr/evaluate state 0 {} [:and [:>= [:minors] 2] [:>= [:majors] 1]])))
      (is (expr/evaluate state 0 {} [:and [:>= [:minors] 1] [:>= [:majors] 1]])))))

(deftest bots-enumerate-material-choices-and-minor-supply-actions
  (let [state (-> (fresh) rich (own 0 tray) (hand wood-oven)) player (g/p state 0)]
    (is (= #{:wood :stone} (set (map :discount-resource (filter #(= wood-oven (:card %)) (bot/hand-options player :minor))))))
    (is (= #{:clay :stone} (set (map :discount-resource (filter #(= :clay-oven (:improvement %)) (bot/major-options state player))))))
    (is (some #(and (= :meeting (:space %)) (= :clay-oven (:improvement %)) (bot/preview state 0 %))
              (bot/action-candidates state 0)))))

(deftest freshman-can-replace-the-oven-bake-on-a-minor-space-after-reload
  (let [state (-> (fresh) rich (own 0 tray) (own 0 :a097) (hand :a116))
        waiting (g/command state 0 {:type :action :space :meeting :improvement :clay-oven :discount-resource :stone})
        done (resolve! (reload waiting) {:card (c/id :a116)})]
    (is (= :freshman-bake (get-in waiting [:effect-queue 0 :effect :op])))
    (is (= :stone (get-in waiting [:effect-queue 0 :effect :source-command :discount-resource])))
    (is (contains? (get-in done [:players 0 :improvements]) :clay-oven))
    (is (c/has? (g/p done 0) :a116))
    (is (= 0 (delta state done :stone)))
    (is (= 0 (delta state done :grain)))
    (is (= 1 (:turn done)))))
