(ns agricola.house-rules-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.house-rules :as house] [agricola.engine :as a]
            [agricola.card-game :as g] [agricola.card-rules :as c] [agricola.draw :as draw]
            [agricola.recording :as recording] [agricola.simulation :as sim] [datascript.core :as d]
            [eurozone.store :as store] [eurozone.model :as model]))

(def options {:banlist true :braggart true :starting-food true})
(defn reload [state] (edn/read-string (pr-str state)))
(defn complete-draw [state]
  (loop [state state]
    (if (not= :draw (:phase state)) state
      (let [packet (:draw state) pid (first (sort (:pending packet)))]
        (recur (store/finish-draw
                (update state :draw draw/pick pid (:wave packet) (first (draw/legal-cards packet pid)))))))))

(deftest food-is-equal-by-default-and-adjusts-relative-to-the-first-player
  (doseq [n (range 1 6) starter (range n) mode [:beginner :random :draw] enabled? [false true]]
    (let [names (mapv #(str "P" %) (range n))
          state (with-redefs [store/new-state (fn [_ names] (a/new-game names (vec (mapcat identity a/stages)) starter))]
                  (store/start-state names {:mode mode :extra 2 :house-rules {:starting-food enabled?}}))
          expected (mapv (fn [pid] (cond (= n 1) 0
                                         (not enabled?) 3
                                         (= pid starter) 2
                                         (and (#{3 4} n) (= pid (mod (dec starter) n))) 4
                                         :else 3)) (range n))
          done (complete-draw (reload state))]
      (is (= expected (mapv #(a/amount % :food) (:players state))) (str [n starter mode enabled?]))
      (is (= expected (mapv #(a/amount % :food) (:players done)))))))

(deftest switches-are-independent-strict-and-off-by-default
  (is (= {:mode :beginner :extra 2} (store/valid-setup nil)))
  (is (= {} (house/validate {:banlist false :braggart false :starting-food false})))
  (doseq [key house/option-keys]
    (is (= {key true} (:house-rules (store/valid-setup {:house-rules {key true}})))))
  (doseq [bad [{:banlist "false"} {:braggart 1} {:starting-food nil} {:unknown true} [] "yes"]]
    (is (thrown? clojure.lang.ExceptionInfo (store/valid-setup {:house-rules bad}))))
  (is (= [3 3] (mapv #(a/amount % :food) (:players (a/new-game ["A" "B"]))))))

(deftest banlist-covers-all-ten-names-including-future-decks-and-reprints
  (let [matches (filter #(house/banned? options %) (vals c/metadata))]
    (is (= (set house/banlist) (set (map :name matches))))
    (is (some #(= "Late Sleeper" (:name %)) matches))
    (is (some #(= "Guest Worker" (:name %)) matches))
    (is (= 2 (count (filter #(= "Lover" (:name %)) matches))))
    (is (= 2 (count (filter #(= "Remodeling" (:name %)) matches))))
    (is (not-any? #(house/banned? {} %) matches))
    (doseq [n (range 1 6)]
      (let [normal (store/hand-pool n) banned (filter #(house/banned? options %) normal)
            allowed (store/hand-pool n [] options)]
        (is (= (set allowed) (set (remove (set banned) normal))))
        (is (not-any? #(house/banned? options %) allowed))))))

(deftest banlist-filters-random-hands-draw-packets-and-replacement-reserves
  (doseq [mode [:random :draw]]
    (let [state (store/start-state ["A" "B" "C" "D" "E"] {:mode mode :extra 10 :house-rules options})
          done (complete-draw (reload state))
          dealt (mapcat #(mapcat val (:hand %)) (:players done))
          reserve (:minor-deck done)]
      (is (= 70 (count dealt)))
      (is (not-any? #(house/banned? options (c/card %)) (concat dealt reserve (:minor-order state))))
      (is (= options (:house-rules done)))
      (when (= mode :draw)
        (is (not-any? #(house/banned? options (c/card %))
                     (filter keyword? (tree-seq coll? seq (:draw state))))))))
  (let [base (a/new-game ["A" "B" "C" "D" "E"])
        banned :catalog/original-base-i-81-wooden-hut-extension
        allowed :catalog/original-base-e-18-bean-field
        state (g/initialize (assoc base :house-rules options :minor-order [banned allowed]))]
    (is (= [allowed] (:minor-deck state)))
    (is (some #{banned} (:minor-deck (g/initialize base))))))

(deftest bans-are-enforced-by-the-engine-not-just-the-dealer
  (let [base (g/initialize (assoc (a/new-game ["A" "B" "C" "D" "E"]) :house-rules options))]
    (doseq [card (filter #(house/banned? options %) (vals c/cards))]
      (let [state (-> base (assoc-in [:players 0 :hand (:kind card)] [(:id card)])
                      (update-in [:players 0] a/gain {:food 20 :wood 20 :reed 20 :clay 20 :stone 20}))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"banned"
                             (g/paid-card-player (reload state) 0 (:id card) 0 {})) (:name card))))
    (let [id :catalog/original-base-e-198-ratcatcher
          state (-> base (dissoc :house-rules) (assoc-in [:players 0 :hand :occupation] [id]))]
      (is (some #{id} (get-in (g/drain (g/play-card state 0 id 0 {})) [:players 0 :occupations]))))))

(deftest braggart-shifts-every-tier-without-changing-its-points-or-other-cards
  (doseq [id [:catalog/original-base-e-197-braggart (c/id :a133)] n (range 13)]
    (let [{:keys [thresholds values]} (:score (c/card id))
          base (-> (g/initialize (a/new-game ["A" "B" "C"]))
                   (assoc-in [:players 0 :occupations] [id])
                   (assoc-in [:players 0 :minors] (vec (take n (map :id (filter #(= :minor (:kind %)) (vals c/cards)))))))
          expected (fn [shift] (last (cons 0 (for [[threshold value] (map vector thresholds values) :when (>= n (+ threshold shift))] value))))]
      (is (= (expected 0) (g/card-bonus base 0 id)))
      (is (= (expected 1) (g/card-bonus (reload (assoc base :house-rules {:braggart true})) 0 id)))
      (is (= (expected 0) (g/card-bonus (assoc base :house-rules {:banlist true :starting-food true}) 0 id))))))

(deftest house-rules-survive-authorized-lobby-edits-projection-saves-and-replay
  (let [s (store/make-store nil)
        id (:id (store/transact! s "host" {:event :create :mode :online :name "A" :expected-players 2}))
        send! (fn [who message] (store/transact! s who (assoc message :id id :revision (get-in @(:rooms s) [id :revision]))))]
    (send! "guest" {:event :join :name "B"})
    (send! "guest" {:event :ready :ready? true})
    (is (thrown? clojure.lang.ExceptionInfo (send! "guest" {:event :configure :expected-players 2 :setup {:house-rules options}})))
    (send! "host" {:event :configure :expected-players 2 :setup {:mode :random :house-rules options}})
    (is (false? (get-in @(:rooms s) [id :seats 1 :ready?])))
    (send! "guest" {:event :ready :ready? true})
    (send! "host" {:event :start})
    (let [room (get @(:rooms s) id) state (:state room)
          projected (:state (model/pull-game (d/db-with (d/empty-db model/schema) (model/game-tx room)) id))
          saved (reload room)]
      (is (= options (:house-rules projected) (get-in saved [:state :house-rules]) (get-in saved [:setup :house-rules])))
      (is (thrown? clojure.lang.ExceptionInfo (send! "host" {:event :configure :expected-players 2 :setup {}})))
      (let [pid (:turn state) token (get-in room [:seats pid :owner])]
        (send! token {:event :command :player pid :command {:type :action :space :grain}}))
      (let [records (:recording (get @(:rooms s) id)) timeline (recording/timeline (reload records))]
        (is (= :partial (:status timeline)))
        (is (= 1 (count (:moves timeline))))
        (is (= options (get-in timeline [:header :options :house-rules])))
        (is (every? #(= options (:house-rules %)) (:frames timeline)))))))

(deftest simulations-use-the-same-optional-rules-and-compatible-saves-are-explicit
  (doseq [mode [:random :draw]]
    (let [state (sim/initial-state {:seed 17 :players 4 :mode mode :house-rules options})
          done (complete-draw state)]
      (is (= options (:house-rules done)))
      (is (= [2 3 3 4] (vec (sort (map #(a/amount % :food) (:players done))))))
      (is (not-any? #(house/banned? options (c/card %))
                     (concat (:minor-deck done) (mapcat #(mapcat val (:hand %)) (:players done)))))))
  (is (store/compatible-host-save? @store/engine-fingerprint))
  (is (store/compatible-host-save? "cf9b4346d2ccd0b08ebc1d4da13bf18ec92de3f9c1e35458138dad149607a3d9"))
  (is (store/compatible-host-save? "c392563c1a00c063ed0975f223d2dae2adafc1aa84eba60c7d1c22f625a897f3"))
  (is (not (store/compatible-host-save? "unknown-version"))))
