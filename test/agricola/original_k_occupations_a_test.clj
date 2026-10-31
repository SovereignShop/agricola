(ns agricola.original-k-occupations-a-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c] [agricola.bot :as bot]))
(defn cid [n] (:id (first (filter #(and (= "Original" (:edition %)) (= "K" (:deck %)) (= (str n) (:number %))) (vals c/metadata)))))
(defn fresh [] (g/initialize (a/new-game ["Ada" "Ben" "Cora" "Dan"] (vec (mapcat identity a/stages)) 0)))
(defn own [s pid n] (update-in s [:players pid :occupations] conj (if (number? n) (cid n) n)))
(defn play [s pid n] (let [id (cid n)] (g/drain (g/play-card (update-in s [:players pid :hand :occupation] conj id) pid id 0 {}))))
(defn resolve! [s choice] (g/command s (g/acting-player s) (merge {:type :resolve :decision (:decision-id s)} choice)))
(defn reload [s] (edn/read-string (pr-str s)))
(defn emit [s kind pid context] (g/drain (g/queue-front s [(g/event kind pid context)])))
(defn rich [s pid] (update-in s [:players pid] a/gain {:food 20 :wood 20 :clay 20 :reed 20 :stone 20 :grain 6 :vegetable 4}))
(defn goods [s pid r] (a/amount (g/p s pid) r))
(defn op [s] (get-in s [:effect-queue 0 :effect :op]))

(deftest canonical-original-k-first-half
  (is (= 24 (count (filter c/card (map cid (range 266 290)))))))

(deftest serf-before-sowing-and-baking
  (doseq [resource [:grain :vegetable]]
    (let [s (-> (fresh) (own 0 266) (update :active conj :sow-bake)
                (assoc-in [:players 0 :farm 0] {:type :field})
                (assoc-in [:players 0 :goods :grain] (if (= resource :vegetable) 1 0)))
          pending (g/command s 0 {:type :action :space :sow-bake})
          offer (resolve! (reload pending) {:resource resource})
          sow (resolve! offer {})
          done (resolve! sow {:plantings [{:cell 0 :crop resource}]})]
      (is (= :serf (op pending))) (is (= :sow-offer (op offer))) (is (= :sow (op sow)))
      (is (= resource (get-in done [:players 0 :farm 0 :crop])))
      (is (= 0 (goods done 0 :grain))) (is (nil? (:serf-plan done)))))
  (let [s (-> (fresh) (own 0 266) (update :active conj :sow-bake)
              (assoc-in [:players 0 :improvements] #{:fireplace-2}))
        pending (g/command s 0 {:type :action :space :sow-bake})
        done (resolve! (resolve! pending {:resource :grain}) {:skip? true})]
    (is (= (+ 2 (goods s 0 :food)) (goods done 0 :food)))))

(deftest pieceworker-direct-resources-only-and-one-of-each
  (let [s (-> (fresh) (own 0 268) (own 0 :catalog/original-base-e-176-woodcutter) (rich 0))
        pending (g/command s 0 {:type :action :space :wood})
        done (resolve! (reload pending) {:purchase {:wood 1}})]
    (is (= :pieceworker (op pending)))
    (is (= 23 (goods pending 0 :wood)))
    (is (= 25 (goods done 0 :wood)))
    (is (= (dec (goods s 0 :food)) (goods done 0 :food)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:purchase {:wood 2}})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:purchase {:stone 1}}))))
  (let [s (-> (fresh) (own 0 268) (rich 0))
        done (resolve! (g/command s 0 {:type :action :space :market}) {:purchase {:reed 1 :stone 1}})]
    (is (= 22 (goods done 0 :reed))) (is (= 22 (goods done 0 :stone)))
    (is (= (dec (goods s 0 :food)) (goods done 0 :food)))))

(deftest wet-nurse-multiple-newborns-and-original-adoption
  (let [s (-> (fresh) (rich 0) (own 0 270) (own 0 267))
        nurse (g/command s 0 {:type :action :space :build :rooms [0 1]})
        adoption (resolve! (reload nurse) {:amount 2})
        done (resolve! (reload adoption) {:amount 2})]
    (is (= :wet-nurse (op nurse))) (is (= :adopt-original (op adoption)))
    (is (= 4 (get-in done [:players 0 :family]))) (is (= 0 (get-in done [:players 0 :newborn])))
    (is (= 3 (get-in done [:players 0 :remaining])))
    (is (= (- (goods s 0 :food) 4) (goods done 0 :food)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! nurse {:amount 3})))))

(deftest educator-fourth-occupation-costs-two
  (doseq [[n cost] [[2 3] [3 2]]]
    (let [s (-> (fresh) (rich 1) (own 1 271)
                (update-in [:players 1 :occupations] into (take (dec n) [(cid 283) (cid 288)]))
                (assoc-in [:players 1 :hand :occupation] [(cid 287)]))
          pending (play s 0 283) done (resolve! pending {:card (cid 287)})]
      (is (= :educator (op pending)))
      (is (= (- (goods s 1 :food) cost) (goods done 1 :food)))
      (is (some #{(cid 287)} (get-in done [:players 1 :occupations]))))))

(deftest manufacturer-retains-major-classification-and-chooses-two-materials
  (let [s (-> (fresh) (own 0 277) (rich 0) (assoc-in [:players 0 :house] :clay))
        p (g/p s 0)]
    (is (a/major-as-minor? p :joinery)) (is (not (a/major-as-minor? (assoc p :house :wood) :joinery)))
    (is (= {:wood 1 :stone 1} (a/improvement-cost p :joinery nil {:manufacturer-discount {:wood 1 :stone 1}})))
    (is (thrown? clojure.lang.ExceptionInfo (a/improvement-cost p :joinery nil {:manufacturer-discount {:wood 3}})))
    (is (contains? (get-in (g/command s 0 {:type :action :space :meeting :improvement :joinery :manufacturer-discount {:wood 2}}) [:players 0 :improvements]) :joinery))))

(deftest forester-fieldsman-and-smallholder-stacks
  (let [s (-> (fresh) (rich 0) (own 0 :catalog/original-base-i-219-fieldsman) (own 0 286) (play 0 278))
        p (g/p s 0) planted (a/sow p [{:cell (cid 278) :crop :wood}])]
    (is (= 3 (count (:card-fields p))))
    (is (= 5 (get-in planted [:card-fields (cid 278) :count])))
    (is (= 1 (- (a/amount (a/field-harvest planted) :wood) (a/amount planted :wood)))))
  (let [p (-> (g/p (-> (fresh) (own 0 286) (rich 0)) 0) (a/place-tile :field 0))]
    (is (= 4 (get-in (a/sow p [{:cell 0 :crop :grain}]) [:farm 0 :count])))
    (is (= 3 (get-in (a/sow p [{:cell 0 :crop :grain}] {:smallholder? false}) [:farm 0 :count])))))

(deftest scholar-major-and-immediate-round-start-new-card
  (let [s (-> (fresh) (rich 0) (own 0 279) (assoc :phase :preparation) (assoc-in [:players 0 :house] :stone)
              (assoc-in [:players 0 :hand :occupation] [(cid 287)]))
        pending (emit s :round-start 0 {})
        done (resolve! pending {:card (cid 287)})]
    (is (= :original-scholar (op pending)))
    (is (= 21 (goods done 0 :stone))) (is (= 21 (goods done 0 :wood))))
  (let [s (-> (fresh) (rich 0) (own 0 279) (assoc :phase :preparation) (assoc-in [:players 0 :house] :stone))
        done (resolve! (emit s :round-start 0 {}) {:improvement :well})]
    (is (contains? (get-in done [:players 0 :improvements]) :well))))

(deftest perpetual-student-persists-random-draw-and-begs-only-for-food
  (let [s (-> (fresh) (own 0 275) (assoc-in [:players 0 :goods :food] 0)
              (assoc-in [:players 0 :hand :occupation] [(cid 291)]))
        pending (g/drain (g/play-card s 0 (cid 291) 3 {:perpetual-student? true}))
        done (resolve! (reload pending) {})]
    (is (= :perpetual-play (op pending))) (is (= 3 (goods pending 0 :food)))
    (is (= (cid 291) (get-in pending [:effect-queue 0 :effect :card])))
    (is (= 4 (get-in done [:players 0 :begging])))
    (is (= 3 (get-in done [:players 0 :family])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:skip? true})))))

(deftest cooked-boar-marker-exclusion-is-symmetric
  (let [s (-> (fresh) (rich 0) (own 0 273) (own 0 280) (own 0 :catalog/original-base-e-156-brush-maker)
              (assoc-in [:players 0 :improvements] #{:hearth-4}) (assoc-in [:players 0 :goods :boar] 3))
        pending (g/command s 0 {:type :convert :goods {:boar 3}})
        basin (g/reorder-effect pending 0 (cid 273))
        after (resolve! basin {:amount 4})
        tanner (g/reorder-effect after 0 (cid 280))
        done (resolve! tanner {:animals {:boar 1}})]
    (is (= :basin-maker (op basin)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! tanner {:animals {:boar 2}})))
    (is (= 2 (g/card-bonus done 0 (cid 273))))
    (is (= 1 (get-in done [:players 0 :card-state (cid 280) :stored-goods :boar])))))

(deftest wood-deliveries-clerk-keeper-and-tinsmith
  (let [s (play (assoc (fresh) :round 8) 0 283)]
    (is (nil? (get-in s [:players 0 :schedule 8])))
    (is (= 6 (count (get-in s [:players 0 :schedule])))))
  (let [s (-> (fresh) (own 0 288)) done (resolve! (g/command s 0 {:type :action :space :market}) {:resource :grain})]
    (is (= 1 (goods done 0 :grain))))
  (let [s (-> (fresh) (own 0 285) (assoc-in [:players 0 :goods :clay] 5))
        ordinary (g/command s 0 {:type :ability :card (cid 285) :amount 3})
        well (g/command (assoc s :well-build-count 1) 0 {:type :ability :card (cid 285) :amount 3})]
    (is (= (+ 3 (goods s 0 :food)) (goods ordinary 0 :food)))
    (is (= (+ 4 (goods s 0 :food)) (goods well 0 :food)))))

(deftest distributor-evenly-splits-and-keeps-remainder
  (let [s (-> (fresh) (own 0 284) (assoc :phase :preparation) (assoc-in [:piles :wood :wood] 8))
        pending (g/drain (g/preparation-reconsider s)) done (resolve! pending {})]
    (is (= :wood-distributor (op pending)))
    (is (= 2 (get-in done [:piles :wood :wood])))
    (doseq [space [:clay :reed :fishing]] (is (= 2 (get-in done [:piles space :wood]))))
    (is (= :work (:phase done)))))

(deftest acrobat-and-countryman-keep-person-identities-and-order
  (let [s (-> (fresh) (own 0 269) (own 0 289) (assoc :turn 3)
              (update :active into [:cultivation :sow-bake])
              (update :players #(mapv (fn [p] (assoc p :remaining 0)) %))
              (assoc-in [:players 0 :worker-spaces] [:traveling :grain])
              (assoc-in [:occupied :traveling] 0) (assoc-in [:occupied :grain] 0)
              (assoc-in [:players 0 :farm 0] {:type :field}) (assoc-in [:players 0 :goods :grain] 1))
        pending (g/drain (g/start-relocations s))
        country-index (first (keep-indexed #(when (= (cid 289) (:relocation-card %2)) %1) (g/relocation-origins pending 0)))
        action (resolve! (reload pending) {:option country-index})
        after (resolve! action {:action-command {:type :action :space :sow-bake :plantings [{:cell 0 :crop :grain}]}})]
    (is (= :relocate (op pending))) (is (= 0 (g/acting-player pending)))
    (is (= :extra-action (op action)))
    (is (= :sow-bake (get-in after [:players 0 :worker-spaces 1])))
    (is (= 0 (get-in after [:players 0 :remaining])))
    (is (= 1 (count (g/relocation-origins after 0))))
    (is (not (contains? (set (g/relocation-spaces after 0 (first (g/relocation-origins after 0)))) :grain)))
    (is (= 0 (get-in after [:players 0 :people-placed-by-round 1] 0)))))
