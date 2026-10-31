(ns agricola.original-fencing-test
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn] [clojure.set :as set]
            [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [agricola.bot :as bot]))

(def hedge :catalog/original-base-e-175-hedge-keeper)
(def mini :catalog/original-base-e-40-mini-pasture)
(defn fresh [] (g/initialize (a/new-game ["A" "B"] (vec (mapcat identity a/stages)) 0)))
(defn player [] (-> (g/p (fresh) 0) (assoc :occupations [hedge]) (a/gain {:wood 20})))
(defn boundary [cells]
  (->> cells (mapcat a/edges) frequencies (keep (fn [[edge n]] (when (= n 1) edge))) vec))
(defn additions [p cells] (vec (remove (:fences p) (boundary cells))))
(defn pending-mini [p]
  (-> (fresh) (assoc-in [:players 0] (assoc-in p [:hand :minor] [mini]))
      (g/play-card 0 mini 0 {}) g/drain))
(defn resolve! [state choice]
  (g/command state 0 (merge {:type :resolve :decision (:decision-id state)} choice)))

(deftest original-hedge-keeper-needs-a-first-paid-or-provided-fence
  (let [before (player)
        built (a/build-fences before (a/edges 0))]
    (is (= 1 (- (a/amount before :wood) (a/amount built :wood))))
    (is (= 0 (a/turn-free-fences-available built)))
    (is (true? (:turn-fence-bonus-used? built))))
  ;; Subdividing a 2x2 pasture needs only two new pieces. Original Hedge Keeper
  ;; still requires the first piece to be supplied; the Revised discount does not.
  (let [base (-> (player) (assoc :occupations []) (a/build-fences (boundary [1 2 6 7])))
        fence ["v:0:2" "v:1:2"]
        original (assoc base :occupations [hedge])
        revised (assoc base :occupations [(c/id :a088)])]
    (let [split (a/build-fences original fence)
          next-construction (a/build-fences split (additions split [3]))]
      (is (= 1 (- (a/amount original :wood) (a/amount split :wood))))
      (is (= 0 (a/turn-free-fences-available split)))
      (is (= 3 (- (a/amount split :wood) (a/amount next-construction :wood)))))
    (is (= (a/amount revised :wood) (a/amount (a/build-fences revised fence) :wood))))
  (let [base (assoc (player) :goods {:food 2 :wood 0})]
    (is (thrown? clojure.lang.ExceptionInfo (a/build-fences base (a/edges 0))))
    (is (= #{0} (a/pasture-cells (a/build-fences base (a/edges 0) {:free-fences 1}))))))

(deftest original-hedge-keeper-cannot-reuse-or-bank-a-partial-bonus
  (let [first (a/build-fences (player) (a/edges 0))
        second (a/build-fences (edn/read-string (pr-str first)) (additions first [1]))]
    (is (= 3 (- (a/amount first :wood) (a/amount second :wood))))
    (let [renewed (a/reset-turn-fence-bonus first)
          next-turn (a/build-fences renewed (additions renewed [1]))]
      (is (= 1 (- (a/amount renewed :wood) (a/amount next-turn :wood)))))))

(deftest mini-pasture-and-hedge-keeper-build-and-pass-as-one-construction
  (let [pending (pending-mini (player))
        extra (vec (remove (set (a/edges 0)) (a/edges 1)))
        done (resolve! (edn/read-string (pr-str pending)) {:cell 0 :fences extra})]
    (is (= :mini-pasture (get-in pending [:effect-queue 0 :effect :op])))
    (is (= #{0 1} (a/pasture-cells (g/p done 0))))
    (is (= 7 (count (get-in done [:players 0 :fences]))))
    (is (= (a/amount (g/p pending 0) :wood) (a/amount (g/p done 0) :wood)))
    (is (= [mini] (get-in done [:players 1 :hand :minor])))
    (is (not-any? #{mini} (get-in done [:players 0 :minors])))
    (is (= 0 (a/turn-free-fences-available (g/p done 0))))
    (is (some (fn [cmd] (and (= 0 (:cell cmd)) (= (set extra) (set (:fences cmd)))
                             (some? (bot/preview pending 0 cmd))))
              (bot/decision-candidates pending 0)))))

(deftest mini-pasture-validates-the-complete-pasture-layout
  ;; Cell2 alone is disconnected from the existing pasture. Extra fences around
  ;; cell1 connect it, so checking the intermediate layout would wrongly reject it.
  (let [before (-> (player) (assoc :occupations []) (a/build-fences (a/edges 0)) (assoc :occupations [hedge]))
        base (set/union (:fences before) (set (a/edges 2)))
        extra (vec (remove base (a/edges 1)))
        done (a/build-mini-pasture before 2 extra)]
    (is (= 2 (count extra)))
    (is (= #{0 1 2} (a/pasture-cells done)))
    (is (= (a/amount before :wood) (a/amount done :wood)))
    (is (thrown? clojure.lang.ExceptionInfo (a/build-mini-pasture before 2 [])))
    (is (= 0 (a/turn-free-fences-available done)))))

(deftest mini-pasture-rejects-forged-extra-edges-atomically
  (let [p (player) extra (vec (remove (set (a/edges 0)) (a/edges 1))) pending (pending-mini p)]
    (doseq [choice [{:cell 0 :fences (conj extra (first extra))}
                    {:cell 0 :fences [(first (a/edges 0))]}
                    {:cell 0 :fences ["h:99:99"]}
                    {:cell 0 :fences ["v:2:4"]}
                    {:cell 0 :fences (a/edges 2)}
                    {:cell 5 :fences extra}]]
      (is (thrown? clojure.lang.ExceptionInfo (resolve! pending choice)) (str choice)))
    (is (= #{} (get-in pending [:players 0 :fences])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (a/build-mini-pasture (assoc p :occupations []) 0 extra)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (a/build-mini-pasture (assoc p :turn-fence-bonus-used? true) 0 extra)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (a/build-mini-pasture (assoc p :discarded-fences 9) 0 extra)))
    (let [built (a/build-mini-pasture p 0 [])]
      (is (thrown? clojure.lang.ExceptionInfo (a/build-mini-pasture built 0 []))))))

(deftest fence-entitlement-resets-only-at-a-new-turn-or-phase
  (let [state (assoc-in (fresh) [:players 0] (a/build-fences (player) (a/edges 0)))
        lasso (assoc state :lasso {:second? false})]
    (is (= 0 (a/turn-free-fences-available (g/p (g/prepare-placement state) 0))))
    (is (= 0 (a/turn-free-fences-available (g/p (g/advance lasso) 0))))
    (is (= 3 (a/turn-free-fences-available (g/p (g/advance state) 0))))
    (is (= 3 (a/turn-free-fences-available (g/p (g/begin-round state) 0))))
    (is (= 3 (a/turn-free-fences-available (g/p (g/continue-step state :work) 0))))))
