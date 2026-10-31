(ns agricola.wizkids-white-minors-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn] [clojure.java.io :as io]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.card-expressions :as expr] [eurozone.store :as store]))

(defn cid [deck n]
  (:id (first (filter #(and (= "WizKids White Expansion" (:pack %)) (= deck (:deck %)) (= (str n) (:number %))) (vals c/metadata)))))
(defn fresh [] (g/initialize (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0)))
(defn rich [s] (update-in s [:players 0] a/gain {:wood 20 :clay 20 :stone 20 :reed 20 :food 20 :grain 6 :vegetable 4}))
(defn ready [s] (assoc-in s [:players 0 :occupations] (mapv c/id [:a116 :a112 :a120])))
(defn own [s id] (update-in s [:players 0 :minors] conj id))
(defn play [s id] (g/drain (g/play-card (update-in s [:players 0 :hand :minor] (fnil conj []) id) 0 id 0 {})))
(defn resolve! [s choice] (g/command s 0 (merge {:type :resolve :decision (:decision-id s)} choice)))
(defn emit [s kind] (g/drain (g/queue-front s [(g/event kind 0)])))
(defn op [s] (get-in s [:effect-queue 0 :effect :op]))
(defn reload [s] (edn/read-string (pr-str s)))
(defn stock [s r] (a/amount (g/p s 0) r))
(defn deliveries [s id]
  (into (sorted-map) (for [[round entries] (get-in s [:players 0 :schedule])]
                       [round (apply merge-with + {} (map :goods (filter #(= id (:card %)) entries)))])))

(deftest complete-white-pack-has-native-rules-and-twenty-distinct-prints
  (let [catalogue (filter #(= "WizKids White Expansion" (:pack %)) (vals c/metadata))
        art (with-open [r (java.io.PushbackReader. (io/reader "src/cljs/eurozone/browser/card_art.cljs"))]
              (loop [] (let [f (read r false nil)]
                         (cond (nil? f) nil
                               (and (seq? f) (= '(def printed-files) (take 2 f))) (nth f 2)
                               :else (recur)))))]
    (is (= 20 (count catalogue) (count c/wizkids-white-definitions)))
    (is (= {:minor 9 :occupation 11} (frequencies (map :kind catalogue))))
    (is (= 20 (count (distinct (map #(art (:id %)) catalogue)))))
    (doseq [{:keys [id]} catalogue]
      (is (some? (c/card id)) (str id))
      (is (some? (if (contains? (c/card id) :requires) (:requires (c/card id)) (expr/requirement (:prerequisites (c/card id))))) (str id))
      (is (.isFile (io/file "resources/public/art/agricola/cards" (art id))) (str id)))
    (doseq [players (range 1 6)]
      (is (some #(= "WizKids White Expansion" (:pack %)) (store/hand-pool players)))
      (is (not-any? #(= "WizKids White Expansion" (:pack %)) (store/hand-pool players ["revised · wizkids white expansion"]))))))

(deftest steam-machine-bakes-only-after-last-person-uses-an-accumulation-space
  (doseq [[remaining space expected] [[2 :wood nil] [1 :wood :bake] [1 :grain nil]]]
    (let [s (-> (fresh) (own (cid "C" 25)) (assoc-in [:players 0 :remaining] remaining)
                (assoc-in [:players 0 :goods :grain] 1) (assoc-in [:players 0 :improvements] #{:hearth-4}))
          after (g/command s 0 {:type :action :space space})]
      (is (= expected (op after)))
      (when expected
        (let [done (resolve! (reload after) {:grain 1}) skipped (resolve! after {:skip? true})]
          (is (= 0 (stock done :grain)))
          (is (= (+ 3 (stock s :food)) (stock done :food)))
          (is (= 1 (stock skipped :grain)))
          (is (nil? (op done))))))))

(deftest white-half-timbered-house-keeps-its-one-card-stone-house-limit
  (let [id (cid "C" 30) s (own (fresh) id)]
    (is (= 0 (g/card-bonus s 0 id)))
    (is (= 0 (g/card-bonus (assoc-in s [:players 0 :house] :clay) 0 id)))
    (is (= 2 (g/card-bonus (assoc-in s [:players 0 :house] :stone) 0 id)))
    (doseq [other [:catalog/original-base-e-21-half-timbered-house :catalog/original-base-e-172-chief :catalog/original-base-k-144-mansion]]
      (let [key (if (= :occupation (:kind (c/card other))) :occupations :minors)
            both (-> s (assoc-in [:players 0 :house] :stone) (update-in [:players 0 key] conj other))]
        (is (= 0 (g/card-bonus both 0 id)))
        (is (pos? (g/card-bonus both 0 other)))))))

(deftest schnapps-distillery-is-optional-five-food-and-counts-planted-vegetables
  (let [id (cid "C" 59) s (-> (fresh) (own id) (assoc :phase :feeding :round 4)
                            (assoc-in [:players 0 :goods :vegetable] 1))
        pending (emit s :feeding-start) done (resolve! (reload pending) {})
        skipped (resolve! pending {:skip? true})]
    (is (= :exchange (op pending)))
    (is (= 0 (stock done :vegetable)))
    (is (= (+ 5 (stock s :food)) (stock done :food)))
    (is (= 1 (stock skipped :vegetable)))
    (doseq [[n expected] [[3 0] [4 0] [5 1] [6 2] [7 2]]]
      (is (= expected (g/card-bonus (assoc-in s [:players 0 :farm 0] {:type :field :crop :vegetable :count (dec n)}) 0 id))))))

(deftest white-deliveries-are-offset-from-play-and-truncated-at-round-fourteen
  (doseq [[deck number round expected]
          [["C" 77 12 {13 {:clay 1} 14 {:clay 1}}]
           ["C" 78 1 {6 {:reed 1} 8 {:reed 1} 10 {:reed 1} 12 {:reed 1} 14 {:reed 1}}]
           ["C" 78 10 {}]
           ["D" 41 10 {11 {:food 1} 12 {:sheep 1} 13 {:food 1} 14 {:sheep 1}}]
           ["D" 43 10 {11 {:food 0} 12 {:food 1} 13 {:food 2} 14 {:food 3}}]
           ["D" 43 13 {14 {:food 0}}]]]
    (let [id (cid deck number) s (-> (fresh) rich ready (assoc :round round))
          after (play s id)]
      (is (= expected (deliveries (reload after) id)) (str id))
      (is (some #{id} (get-in after [:players 0 :minors]))))))

(deftest horse-drawn-boat-delivers-animal-and-queues-housing
  (let [id (cid "D" 41) s (-> (fresh) rich ready (play id) (assoc :round 2))
        next (g/drain (g/begin-round (reload s)))]
    (is (= 1 (stock next :sheep)))
    (is (= 3 (:round next)))))

(deftest cross-cut-wood-enforces-prerequisites-and-counts-stone-without-spending-it
  (let [id (cid "D" 4) s (-> (fresh) rich (assoc-in [:players 0 :goods :stone] 5))]
    (is (thrown? clojure.lang.ExceptionInfo (play s id)))
    (let [done (play (ready s) id)]
      (is (= (+ 5 (stock s :wood)) (stock done :wood)))
      (is (= 5 (stock done :stone)))
      (is (= (dec (stock s :food)) (stock done :food))))))

(deftest milking-stool-requires-two-occupations-and-counts-current-cattle
  (let [id (cid "D" 38)]
    (is (thrown? clojure.lang.ExceptionInfo (play (rich (fresh)) id)))
    (is (= (dec (stock (rich (fresh)) :wood)) (stock (play (ready (rich (fresh))) id) :wood)))
    (doseq [n (range 7)]
      (let [s (-> (fresh) (own id) (assoc-in [:players 0 :goods :cattle] n))
            done (emit s :before-field-phase)]
        (is (= (min 3 (quot (inc n) 2)) (- (stock done :food) (stock s :food))))
        (is (= (quot n 2) (g/card-bonus s 0 id)))))))
