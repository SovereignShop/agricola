(ns agricola.draw-test
  (:require [clojure.test :refer :all] [agricola.draw :as d]))
(defn cards [n kind] (mapv #(hash-map :id (keyword (name kind) (str "c" %)) :kind kind) (range n)))
(defn finish-draw [draw]
  (loop [state draw steps 0]
    (is (< steps 100))
    (if (= :complete (:status state)) state
        (let [pid (first (sort (:pending state))) cid (first (d/legal-cards state pid))]
          (recur (d/pick state pid (:wave state) cid) (inc steps))))))
(deftest balanced-packets-complete-with-extras
  (doseq [n (range 1 7) extra [0 1 2 5 10]]
    (let [supply (into (cards (* n 7) :occupation) (cards (* n (+ 7 extra)) :minor))
          initial (d/new-draw n extra (vec (shuffle supply)))
          final (finish-draw initial)
          selected (mapcat #(mapcat val %) (:selected final))]
      (is (every? #(= {:occupation 7 :minor 7} %) (:counts (d/public-view final))))
      (is (= (* n extra) (count (:discarded final))))
      (is (= (set (map :id supply)) (set (concat selected (:discarded final)))))
      (is (= (count selected) (count (set selected)))))))
(deftest completely-one-type-packets-do-not-deadlock
  ;; Everyone can finish despite receiving packets that temporarily contain only
  ;; a type they have already filled. Packets must not be thrown away early.
  (let [initial (d/new-draw 2 0 (into (cards 14 :occupation) (cards 14 :minor)))
        final (finish-draw initial)]
    (is (= :complete (:status final)))
    (is (every? #(= {:occupation 7 :minor 7} %) (:counts (d/public-view final))))))
(deftest completed-and-blocked-seats-pass-automatically
  (let [types (into {} (map (juxt :id :kind) (concat (cards 20 :occupation) (cards 20 :minor))))
        initial {:status :drawing :wave 13 :extra 0 :advance? false :ready #{} :pending #{}
                 :types types :packets [[:minor/c14] [:occupation/c14]]
                 :selected [{:occupation (mapv :id (cards 7 :occupation)) :minor (mapv :id (cards 7 :minor))}
                            {:occupation (mapv #(keyword "occupation" (str "c" %)) (range 7 14))
                             :minor (mapv #(keyword "minor" (str "c" %)) (range 7 13))}]}
        normalized (d/normalize initial)]
    (is (= #{1} (:pending normalized)))
    (is (= 14 (:wave normalized)))
    (is (= [:minor/c14] (d/legal-cards normalized 1)))
    (is (= :complete (:status (d/pick normalized 1 14 :minor/c14))))))
(deftest stale-illegal-and-duplicate-picks-are-rejected
  (let [initial (d/new-draw 2 0 (into (cards 14 :occupation) (cards 14 :minor)))
        picked (d/pick initial 0 1 :occupation/c0)]
    (is (thrown? clojure.lang.ExceptionInfo (d/pick initial 0 0 :occupation/c0)))
    (is (thrown? clojure.lang.ExceptionInfo (d/pick initial 0 1 :minor/c0)))
    (is (thrown? clojure.lang.ExceptionInfo (d/pick picked 0 1 :occupation/c1)))
    (is (= [:occupation/c0] (get-in picked [:selected 0 :occupation])))
    (is (= [:occupation/c1 :occupation/c2] (take 2 (get-in picked [:packets 0]))))
    (is (empty? (:packet (d/private-view picked 0))))
    (is (not (contains? (d/public-view picked) :packets)))))
(deftest invalid-supplies-fail-before-the-draw
  (is (thrown? clojure.lang.ExceptionInfo (d/new-draw 2 0 (cards 28 :minor))))
  (is (thrown? clojure.lang.ExceptionInfo (d/new-draw 2 0 (vec (repeat 28 {:id :same :kind :minor}))))))
