(ns agricola.wizkids-white-occupations-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g]
            [agricola.card-rules :as c] [agricola.bot :as bot]))

(defn cid [code]
  (:id (first (filter #(and (= "WizKids White Expansion" (:pack %))
                            (= code (str (:deck %) (:number %)))) (vals c/metadata)))))
(defn fresh [] (g/initialize (a/new-game ["Ada" "Ben" "Cy" "Dee"] (vec (mapcat identity a/stages)) 0)))
(defn own [s pid code] (update-in s [:players pid :occupations] conj (cid code)))
(defn play [s pid code]
  (let [id (cid code)] (g/drain (g/play-card (update-in s [:players pid :hand :occupation] conj id) pid id 0 {}))))
(defn resolve! [s choice]
  (g/command s (g/acting-player s) (merge {:type :resolve :decision (:decision-id s)} choice)))
(defn reload [s] (edn/read-string (pr-str s)))
(defn emit [s kind pid] (g/drain (g/queue-front s [(g/event kind pid)])))
(defn goods [s pid resource] (a/amount (g/p s pid) resource))
(defn op [s] (get-in s [:effect-queue 0 :effect :op]))
(defn room-cost [p] (into {} (filter (comp pos? val) (a/room-cost p {}))))

(deftest white-occupation-printings-are-distinct-and-complete
  (let [codes ["C89" "C113" "C118" "C126" "C128" "C152" "C166" "D90" "D107" "D121" "D132"]]
    (is (= 11 (count (filter c/card (map cid codes)))))
    (doseq [code codes]
      (is (= "WizKids White Expansion" (:pack (c/card (cid code)))))
      (is (= :occupation (:kind (c/card (cid code))))))))

(deftest stable-master-builds-one-optional-discounted-stable-and-expands-only-one
  (let [pending (play (assoc-in (fresh) [:players 0 :goods :wood] 1) 0 "C89")
        done (resolve! (reload pending) {:cell 0})
        player (a/build-stables (g/p done 0) [1] 0)
        capacities (map :capacity (filter #(= :stable (:kind %)) (a/accommodations player)))]
    (is (= :stable (op pending))) (is (= 0 (goods done 0 :wood)))
    (is (= #{1 3} (set capacities)))
    (is (= 0 (count (filter :stable (:farm (g/p (resolve! pending {:skip? true}) 0))))))
    (is (some #(bot/preview pending 0 %) (bot/decision-candidates pending 0)))))

(deftest winter-caretaker-offers-vegetable-after-harvest-with-paid-or-skipped-choice
  (let [played (play (fresh) 0 "C113")
        pending (emit (assoc played :phase :finish-round :round 4) :harvest-end 0)
        done (resolve! (reload pending) {})]
    (is (= 1 (goods played 0 :grain))) (is (= :gain (op pending)))
    (is (= 1 (goods done 0 :vegetable)))
    (is (= (- (goods pending 0 :food) 2) (goods done 0 :food)))
    (is (= 0 (goods (resolve! pending {:skip? true}) 0 :vegetable))))
  (let [s (-> (fresh) (own 0 "C113") (assoc-in [:players 0 :goods :food] 0))]
    (is (nil? (op (emit s :harvest-end 0))))))

(deftest collectors-schedule-only-their-future-deliveries
  (doseq [[code r rounds resource] [["C118" 1 [2 3 4 5 6] :wood]
                                   ["C118" 12 [13 14] :wood]
                                   ["C166" 1 [6 9] :cattle]
                                   ["C166" 9 [14] :cattle]]]
    (let [s (play (assoc (fresh) :round r) 0 code) deliveries (get-in s [:players 0 :schedule])]
      (is (= (set rounds) (set (keys deliveries))))
      (doseq [round rounds] (is (= {resource 1} (get-in deliveries [round 0 :goods])))))))

(deftest excavator-rewards-day-labor-and-funds-an-optional-stone
  (let [s (-> (fresh) (own 0 "C126") (assoc-in [:players 0 :goods :food] 0))
        pending (g/command s 0 {:type :action :space :labor}) done (resolve! (reload pending) {})]
    (is (= [1 1 2] (mapv #(goods pending 0 %) [:wood :clay :food])))
    (is (= [1 1] (mapv #(goods done 0 %) [:stone :food])))
    (is (= 0 (goods (resolve! pending {:skip? true}) 0 :stone)))
    (is (nil? (op (g/command s 0 {:type :action :space :wood}))))))

(deftest wooden-hut-extender-prices-follow-the-current-stage-before-preparation
  (doseq [[round wood] [[1 5] [5 5] [6 4] [7 4] [8 3] [14 3]]]
    (let [s (play (assoc (fresh) :round round) 0 "C128") p (g/p s 0)]
      (is (= {:wood wood :reed 1} (room-cost p)))
      (is (= {:clay 5 :reed 2} (room-cost (assoc p :house :clay))))))
  (let [played (play (assoc (fresh) :round 5) 0 "C128")
        sixth (a/begin-round played) eighth (a/begin-round (assoc sixth :round 7))]
    (is (= {:wood 4 :reed 1} (room-cost (g/p sixth 0))))
    (is (= {:wood 3 :reed 1} (room-cost (g/p eighth 0))))
    (let [p (-> (g/p eighth 0) (update :occupations conj :catalog/original-base-e-218-carpenter)
                (a/gain {:wood 6 :reed 2}))
          built (a/build-rooms p [0 1])]
      (is (= 4 (count (a/tiles built :room))))
      (is (= 0 (a/amount built :reed))))))

(deftest revised-puppeteer-pays-the-acting-player-and-not-the-bank
  (let [s (-> (fresh) (own 0 "C152") (assoc :turn 1)
              (assoc-in [:players 0 :goods :food] 1)
              (assoc-in [:players 0 :hand :occupation] [(cid "C118")]))
        pending (g/command s 1 {:type :action :space :traveling})
        done (resolve! (reload pending) {:card (cid "C118")})]
    (is (= :card-action (op pending)))
    (is (= 0 (g/acting-player pending)))
    (is (= 0 (goods done 0 :food)))
    (is (= (inc (goods pending 1 :food)) (goods done 1 :food)))
    (is (= #{2 3 4 5 6} (set (keys (get-in done [:players 0 :schedule])))))
    (let [skipped (resolve! pending {:skip? true})]
      (is (= 1 (goods skipped 0 :food)))
      (is (= (goods pending 1 :food) (goods skipped 1 :food))))
    (is (some #(bot/preview pending 0 %) (bot/decision-candidates pending 0)))))

(deftest plow-maker-saved-workflow-uses-its-own-printing
  (let [s (own (fresh) 0 "D90") pending (g/command s 0 {:type :action :space :plow :cell 0})
        done (resolve! (reload pending) {:plow-maker? true :cells [1]})]
    (is (= :wheel-plow (op pending)))
    (is (= (cid "D90") (get-in pending [:effect-queue 0 :card])))
    (is (= #{0 1} (set (a/tiles (g/p done 0) :field))))
    (is (= (dec (goods s 0 :food)) (goods done 0 :food)))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:plow-maker? true :cells [1 2]})))))

(deftest bellfounder-trades-all-current-clay-on-return-home-only
  (let [s (-> (fresh) (own 0 "D107") (assoc :phase :return-home)
              (assoc-in [:players 0 :goods :clay] 5))
        pending (emit s :return-home 0)
        food (resolve! (reload pending) {:option 0}) points (resolve! (reload pending) {:option 1})]
    (is (= :choose (op pending)))
    (is (= 0 (goods food 0 :clay))) (is (= 0 (goods points 0 :clay)))
    (is (= (+ 3 (goods s 0 :food)) (goods food 0 :food)))
    (is (= 1 (get-in points [:players 0 :bonus-points])))
    (is (= 5 (goods (resolve! pending {:skip? true}) 0 :clay)))
    (is (nil? (op (emit (assoc-in s [:players 0 :goods :clay] 0) :return-home 0))))
    (is (nil? (op (emit s :harvest-end 0))))
    (is (some #(bot/preview pending 0 %) (bot/decision-candidates pending 0))))
  (let [farmer :catalog/revised-artifex-expansion-a-134-full-farmer
        s (-> (fresh) (own 0 "D107") (assoc :phase :return-home)
              (update-in [:players 0 :occupations] conj :catalog/revised-artifex-expansion-a-152-night-school-student)
              (assoc-in [:players 0 :hand :occupation] [farmer]))
        school (emit s :return-home 0) bell (resolve! (reload school) {:card farmer})
        done (resolve! (reload bell) {:option 1})]
    (is (= :card-action (op school)))
    (is (= :choose (op bell))) (is (= 1 (goods bell 0 :clay)))
    (is (= 0 (goods done 0 :clay))) (is (= 1 (get-in done [:players 0 :bonus-points])))))

(deftest clay-plasterer-reduces-clay-construction-and-not-stone
  (let [p (-> (g/p (own (fresh) 0 "D121") 0) (a/gain {:clay 10 :reed 10 :stone 10}))
        renovated (a/renovate p) built (a/build-rooms renovated [0])]
    (is (= 9 (a/amount renovated :clay)))
    (is (= 6 (a/amount built :clay))) (is (= 7 (a/amount built :reed)))
    (is (= {:stone 5 :reed 2} (a/room-cost (assoc built :house :stone) {})))))

(deftest hide-farmer-uses-a-saved-final-settlement
  (let [s (-> (fresh) (own 0 "D132") (assoc :round 14 :phase :finish-round)
              (assoc-in [:players 0 :goods :food] 20))
        pending (g/drain (g/finish-game s)) done (resolve! (reload pending) {:amount 13})]
    (is (= :cover-unused (op pending)))
    (is (= :finished (:phase done))) (is (= 7 (goods done 0 :food)))
    (is (= 0 (get-in (g/score done 0) [:breakdown :unused])))
    (is (= -13 (get-in (g/score (resolve! pending {:skip? true}) 0) [:breakdown :unused])))))
