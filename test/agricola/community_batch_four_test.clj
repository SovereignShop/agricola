(ns agricola.community-batch-four-test
  (:require [clojure.test :refer :all] [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [eurozone.card-scenarios :as scenarios]
            [agricola.community-decks-test :refer [cid fresh rich own play reload resolve! emit op stock]]))
(defn scenario [id] (scenarios/initial-state id (:names (scenarios/options id "Ada"))))

(deftest seed-spreader-sows-only-one-grain-or-vegetable-after-farmland
  (let [s (scenario "seed-spreader") accepted (resolve! (reload s) {})]
    (is (= :sow-offer (op s)))
    (doseq [crop [:grain :vegetable] cell [0 1]]
      (let [done (resolve! (reload accepted) {:plantings [{:cell cell :crop crop}]})]
        (is (= crop (get-in done [:players 0 :farm cell :crop])))
        (is (= (dec (stock s 0 crop)) (stock done 0 crop)))
        (is (= 1 (g/acting-player done)))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (resolve! accepted {:plantings [{:cell 0 :crop :grain} {:cell 1 :crop :vegetable}]})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (resolve! accepted {:plantings [{:cell :catalog/original-base-i-78-copse :crop :wood}]})))
    (is (nil? (op (resolve! s {:skip? true}))))
    (is (= (get-in s [:players 0 :goods]) (get-in (resolve! s {:skip? true}) [:players 0 :goods]))))
  (let [s (-> (fresh) rich (own 0 (cid "G" 105)) (update-in [:players 0] a/place-tile :field 0))]
    (is (nil? (op (emit s :use-space 0 {:space :cultivation}))))
    (is (nil? (op (emit s :use-space 1 {:space :plow}))))
    (is (= :sow-offer (op (emit s :use-space 0 {:space :plow}))))))

(deftest vegetable-scoop-is-automatic-and-space-specific
  (let [s (scenario "vegetable-scoop") done (g/command s 0 {:type :action :space :vegetable})]
    (is (= (+ 2 (stock s 0 :vegetable)) (stock done 0 :vegetable)))
    (is (nil? (op done)))
    (is (= (stock s 0 :vegetable) (stock (emit s :receive-goods 0 {:goods {:vegetable 1}}) 0 :vegetable)))
    (is (= (stock s 0 :vegetable) (stock (emit s :use-space 1 {:space :vegetable}) 0 :vegetable)))))

(deftest diamond-mine-needs-three-occupations-and-schedules-only-remaining-rounds
  (let [s (scenario "diamond-mine") card (cid "WM" 73)
        done (g/command s 0 {:type :action :space :meeting :card card})]
    (is (= #{5 6} (set (keys (get-in done [:players 0 :schedule])))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (g/command (assoc-in s [:players 0 :occupations] []) 0 {:type :action :space :meeting :card card})))
    (let [next (-> done reload g/begin-round g/drain)]
      (is (= (inc (stock done 0 :stone)) (stock next 0 :stone)))))
  (doseq [[round expected] [[13 #{14}] [14 #{}]]]
    (let [s (assoc (scenario "diamond-mine") :round round)
          done (g/command s 0 {:type :action :space :meeting :card (cid "WM" 73)})]
      (is (= expected (set (keys (get-in done [:players 0 :schedule]))))))))

(deftest encircling-wall-preserves-connectivity-piece-limit-and-free-wood
  (let [s (scenario "encircling-wall") done (resolve! (reload s) {:cell 1})]
    (is (= (stock s 0 :wood) (stock done 0 :wood)))
    (is (= #{0 1} (a/pasture-cells (g/p done 0))))
    (is (= 7 (count (get-in done [:players 0 :fences]))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:cell 14})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:cell 0})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:cell 5})))
    (is (= #{0} (a/pasture-cells (g/p (resolve! s {:skip? true}) 0)))))
  (let [edges (vec (for [[edge n] (frequencies (mapcat a/edges [1 2 3 4 6 7 8 9 11 12 13 14])) :when (= n 1)] edge))
        s (-> (fresh) rich (update-in [:players 0] a/build-fences edges) (play (cid "FR" 18)))]
    (is (= 14 (count edges)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! s {:cell 0}))))
  (is (thrown? clojure.lang.ExceptionInfo
               (play (assoc-in (rich (fresh)) [:players 0 :goods :stone] 3) (cid "FR" 18)))))

(deftest stone-house-reconstruction-pays-normal-cost-and-consumes-no-worker
  (let [s (scenario "stone-house-reconstruction") command {:type :ability :card (cid "FR" 45)}
        done (g/command (reload s) 0 command)]
    (is (= :stone (get-in done [:players 0 :house])))
    (is (= (- (stock s 0 :stone) 2) (stock done 0 :stone)))
    (is (= (dec (stock s 0 :reed)) (stock done 0 :reed)))
    (is (= (:turn s) (:turn done)))
    (is (= (get-in s [:players 0 :remaining]) (get-in done [:players 0 :remaining])))
    (doseq [bad [(assoc-in s [:players 0 :house] :wood)
                (assoc-in s [:players 0 :goods :reed] 0)
                (assoc-in s [:players 0 :goods :stone] 1) done]]
      (is (thrown? clojure.lang.ExceptionInfo (g/command bad 0 command))))))
