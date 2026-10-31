(ns agricola.original-e-timing-test
  (:require [clojure.test :refer :all] [clojure.edn :as edn]
            [agricola.engine :as a] [agricola.card-game :as g] [agricola.card-rules :as c]
            [agricola.card-expressions :as expr] [agricola.bot :as bot]))
(defn fresh [] (g/initialize (a/new-game ["A" "B" "C" "D"] (vec (mapcat identity a/stages)) 0)))
(defn cid [number] (:id (first (filter #(and (= "Original" (:edition %)) (= "E" (:deck %)) (= (str number) (:number %))) (vals c/metadata)))))
(defn own [state pid number] (update-in state [:players pid :occupations] conj (cid number)))
(defn hand [state pid number] (update-in state [:players pid :hand :occupation] (fnil conj []) (cid number)))
(defn play [state pid number] (g/drain (g/play-card (hand state pid number) pid (cid number) 0 {})))
(defn resolve! [state choice] (g/command state (g/acting-player state) (merge {:type :resolve :decision (:decision-id state)} choice)))
(defn emit [state event pid context] (g/drain (g/queue-front state [(g/event event pid context)])))
(defn delta [before after pid resource] (- (a/amount (g/p after pid) resource) (a/amount (g/p before pid) resource)))

(deftest academic-counts-twice-only-in-the-printed-contexts
  (let [state (play (fresh) 0 148) player (g/p state 0)]
    (is (= 1 (count (:occupations player))))
    (is (= 1 (expr/evaluate state 0 {} [:occupations])))
    (is (= 2 (c/occupation-count player :original-reeve)))
    (is (g/prerequisites? state 0 (c/card :catalog/original-base-e-18-bean-field)))
    (is (not (g/prerequisites? state 0 {:kind :occupation :prerequisites "2 Occupations"})))
    (is (not (g/prerequisites? state 0 (c/card :catalog/original-base-e-24-animal-pen)))))
  (let [tutor-first (play (play (fresh) 0 174) 0 148)
        academic-first (play (play (fresh) 0 148) 0 174)]
    (is (= 2 (g/card-bonus tutor-first 0 (cid 174))))
    (is (= 0 (g/card-bonus academic-first 0 (cid 174))))
    (is (= 1 (g/card-bonus (play academic-first 0 176) 0 (cid 174)))))
  (let [base (-> (fresh) (update-in [:players 0 :hand :occupation] conj (c/id :b099)))
        tutor (g/drain (g/play-card base 0 (c/id :b099) 0 {}))
        after (play tutor 0 148)]
    (is (= 1 (g/card-bonus after 0 (c/id :b099))) "Revised Tutor counts physical occupations")))

(deftest tutor-keeps-points-for-played-occupations-that-leave-play
  (doseq [tutor [(cid 174) (c/id :b099)]]
    (let [before (g/drain (g/play-card (assoc-in (assoc (fresh) :round 10) [:players 0 :hand :occupation] [tutor]) 0 tutor 0 {}))
          ratcatcher (play before 0 198)
          reloaded (edn/read-string (pr-str ratcatcher))
          academic (play reloaded 0 148)]
      (is (= 0 (g/card-bonus before 0 tutor)))
      (is (not (some #{(cid 198)} (get-in ratcatcher [:players 0 :occupations]))))
      (is (= 1 (g/card-bonus reloaded 0 tutor)))
      (is (= (if (= tutor (cid 174)) 3 2) (g/card-bonus academic 0 tutor)))
      (is (= 1 (get-in reloaded [:players 0 :card-state tutor :occupations-after])))))
  ;; A save written before the event counter existed retains already-earned
  ;; points and migrates correctly whether the new occupation remains or leaves.
  (let [tutor (cid 174)
        legacy (update-in (play (play (assoc (fresh) :round 10) 0 174) 0 148)
                          [:players 0 :card-state tutor] dissoc :occupations-after)]
    (is (= 2 (g/card-bonus legacy 0 tutor)))
    (is (= 3 (g/card-bonus (play legacy 0 198) 0 tutor)))
    (is (= 3 (g/card-bonus (play legacy 0 176) 0 tutor)))))

(deftest yeoman-cancels-negative-scoring-without-cancelling-begging-or-unused-land
  (let [plain (assoc-in (fresh) [:players 0 :begging] 2)
        state (own plain 0 165)
        score (g/score state 0)]
    (is (= 7 (g/card-bonus state 0 (cid 165))))
    (is (= 7 (- (:total score) (:total (g/score plain 0)))))
    (is (= -6 (get-in score [:breakdown :begging])))
    (is (= -13 (get-in score [:breakdown :unused])))
    (let [crops (assoc-in state [:players 0 :goods] {:grain 1 :vegetable 1 :sheep 1 :boar 1 :cattle 1})]
      (is (= 2 (g/card-bonus crops 0 (cid 165)))))))

(deftest chief-response-window-does-not-reveal-whether-daughter-is-held
  (let [without (play (fresh) 0 172)
        with (play (hand (fresh) 1 173) 0 172)]
    (is (= (:effect-queue without) (:effect-queue with)))
    (is (= 1 (g/acting-player with)))
    (is (= :play-hand-response (get-in with [:effect-queue 0 :effect :op])))
    (is (nil? (get-in with [:effect-queue 0 :card])))
    (is (= [1 2 3] (mapv :player (filter #(= :play-hand-response (get-in % [:effect :op])) (:effect-queue with)))))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! without {})))
    (is (some #(and (:skip? %) (bot/preview without 1 %)) (bot/decision-candidates without 1)))
    (is (some #{(cid 173)} (get-in (resolve! with {}) [:players 1 :occupations]))))
  (let [before (-> (fresh) (hand 1 173) (own 1 192) (assoc-in [:players 1 :goods :food] 0))
        pending (play before 0 172)
        played (resolve! (edn/read-string (pr-str pending)) {})]
    (is (some #{(cid 173)} (get-in played [:players 1 :occupations])))
    (is (= 2 (a/amount (g/p played 1) :food)) "Patron pays even for this free response")
    (is (= 2 (g/acting-player played)))
    (is (= 1 (g/card-bonus (assoc-in played [:players 1 :house] :clay) 1 (cid 173))))
    (is (= 3 (g/card-bonus (assoc-in played [:players 1 :house] :stone) 1 (cid 173)))))
  (let [pending (play (hand (fresh) 0 173) 0 172)]
    (is (not-any? #(and (= 0 (:player %)) (= :play-hand-response (get-in % [:effect :op]))) (:effect-queue pending)))
    (is (some #{(cid 173)} (get-in pending [:players 0 :hand :occupation])))))

(deftest hut-builder-is-a-single-round-eleven-offer
  (doseq [round [1 4]]
    (let [played (play (assoc (fresh) :round round) 0 178)
          pending (emit (assoc played :round 11 :phase :preparation) :round-start 0 {})
          built (resolve! (edn/read-string (pr-str pending)) {:rooms [0]})
          declined (resolve! pending {:skip? true})]
      (is (= :build-rooms (get-in pending [:effect-queue 0 :effect :op])))
      (is (= 3 (count (a/tiles (g/p built 0) :room))))
      (is (= (:goods (g/p pending 0)) (:goods (g/p built 0))))
      (is (empty? (:effect-queue (emit declined :round-start 0 {}))))
      (is (empty? (:effect-queue (emit (assoc played :round 12) :round-start 0 {}))))
      (is (empty? (:effect-queue (emit (assoc-in (assoc played :round 11) [:players 0 :house] :stone) :round-start 0 {}))))))
  ;; Compendium expressly allows a later play as an occupation without its room.
  (let [late (play (assoc (fresh) :round 5) 0 178)]
    (is (some #{(cid 178)} (get-in late [:players 0 :occupations])))
    (is (empty? (:effect-queue (emit (assoc late :round 11) :round-start 0 {}))))))

(deftest hobby-farmer-sows-only-one-vegetable-with-normal-sowing-interactions
  (let [before (-> (fresh) (assoc-in [:players 0 :farm 0] {:type :field :count 0})
                   (assoc-in [:players 0 :farm 1] {:type :field :count 0})
                   (assoc-in [:players 0 :goods :grain] 2)
                   (update-in [:players 0 :minors] conj :catalog/original-base-e-32-potato-dibber))
        offer (play before 0 180)
        pending (resolve! offer {})]
    (is (= :sow-offer (get-in offer [:effect-queue 0 :effect :op])))
    (is (= :sow (get-in pending [:effect-queue 0 :effect :op])))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:plantings [{:cell 0 :crop :grain}]})))
    (is (thrown? clojure.lang.ExceptionInfo (resolve! pending {:plantings [{:cell 0 :crop :vegetable} {:cell 1 :crop :vegetable}]})))
    (let [after (resolve! (edn/read-string (pr-str pending)) {:plantings [{:cell 0 :crop :vegetable}]})]
      (is (= 3 (get-in after [:players 0 :farm 0 :count])))
      (is (= 2 (a/amount (g/p after 0) :grain))))
    (is (every? #(every? (fn [planting] (= :vegetable (:crop planting))) (:plantings %))
                (bot/decision-candidates pending 0))))
  (let [without-field (play (fresh) 0 180)]
    (is (= 1 (a/amount (g/p without-field 0) :vegetable)))
    (is (empty? (:effect-queue without-field)))))

(deftest charcoal-burner-rewards-baking-improvements-after-payment
  (doseq [improvement [:fireplace-2 :hearth-4 :clay-oven :stone-oven]]
    (let [before (-> (fresh) (own 0 182) (assoc :turn 1)
                     (update :active conj :improvement)
                     (update-in [:players 1] a/gain {:wood 6 :stone 6 :clay 6 :reed 6}))
          after (g/command before 1 {:type :action :space :improvement :improvement improvement})]
      (is (= 1 (delta before after 0 :food)))
      (is (= 1 (delta before after 0 :wood)))))
  (let [before (-> (fresh) (own 0 182)
                   (update-in [:players 1 :hand :minor] conj :catalog/original-base-e-20-simple-fireplace)
                   (assoc-in [:players 1 :goods :clay] 1))
        after (g/drain (g/play-card before 1 :catalog/original-base-e-20-simple-fireplace 0 {}))]
    (is (= 1 (delta before after 0 :food)))
    (is (= 1 (delta before after 0 :wood))))
  (let [before (-> (fresh) (own 0 182) (update :active conj :improvement)
                   (assoc-in [:players 0 :goods :wood] 0))]
    (is (thrown? clojure.lang.ExceptionInfo (g/command before 0 {:type :action :space :improvement :improvement :well}))))
  (is (= 0 (delta (own (fresh) 0 182) (emit (own (fresh) 0 182) :build-improvement 1 {:improvement :well}) 0 :food))))

(deftest pastor-can-wait-until-chosen-and-cannot-claim-twice
  (let [early (play (fresh) 0 193)
        qualified (reduce #(assoc-in %1 [:players %2 :farm 0] {:type :room}) early [1 2 3])
        waiting (emit qualified :check-state 0 {})
        cmd {:type :ability :card (cid 193)}
        claimed (g/command (edn/read-string (pr-str waiting)) 0 cmd)]
    (is (not (g/anytime-available? early (g/p early 0) (cid 193))))
    (is (g/anytime-available? waiting (g/p waiting 0) (cid 193)))
    (is (= (:goods (g/p qualified 0)) (:goods (g/p waiting 0))))
    (is (some #{cmd} (bot/ability-candidates waiting 0)))
    (doseq [[r n] {:wood 3 :clay 2 :reed 1 :stone 1}] (is (= n (delta waiting claimed 0 r))))
    (is (thrown? clojure.lang.ExceptionInfo (g/command early 0 cmd)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command claimed 0 cmd)))
    (is (thrown? clojure.lang.ExceptionInfo (g/command (assoc-in waiting [:players 0 :farm 0] {:type :room}) 0 cmd)))))

(deftest guildmaster-counts-existing-cards-and-later-acquisitions-separately
  (let [before (-> (fresh) (own 0 214) (own 0 183)
                   (assoc-in [:players 0 :improvements] #{:joinery :pottery :basketry}))
        after (play before 0 341)]
    (is (= 2 (delta before after 0 :wood)))
    (is (= 4 (delta before after 0 :clay)))
    (is (= 4 (delta before after 0 :reed))))
  (let [before (own (fresh) 0 341)
        potter (play before 0 214)
        basketmaker (play before 0 183)]
    (is (= 4 (delta before potter 0 :clay)))
    (is (= 3 (delta before basketmaker 0 :reed))))
  (doseq [[improvement resource amount] [[:joinery :wood 4] [:pottery :clay 4] [:basketry :reed 3]]]
    (let [before (-> (fresh) (own 0 341) (update :active conj :improvement)
                     (update-in [:players 0] a/gain {:wood 10 :clay 10 :reed 10 :stone 10}))
          after (g/command before 0 {:type :action :space :improvement :improvement improvement})]
      (is (= (- amount (get-in a/improvements [improvement :cost resource] 0)) (delta before after 0 resource)))))
  (let [before (-> (fresh) (own 0 341) (update :active conj :improvement) (assoc-in [:players 0 :goods :stone] 2))]
    (is (thrown? clojure.lang.ExceptionInfo (g/command before 0 {:type :action :space :improvement :improvement :joinery})))))

(deftest trowel-can-be-used-in-the-real-after-harvest-window
  (let [card :catalog/original-base-e-50-builder-s-trowel
        before (-> (fresh) (assoc :phase :finish-round :round 4)
                   (update-in [:players 0 :minors] conj card)
                   (assoc-in [:players 0 :goods :clay] 2) (assoc-in [:players 0 :goods :reed] 1)
                   (assoc :effect-queue [(g/task 0 nil {:op :ability-window} {}) (g/step :finish-round)]))
        after (g/command before 0 {:type :ability :card card})]
    (is (g/trowel-boundary? before))
    (is (= :clay (get-in after [:players 0 :house])))
    (is (= :ability-window (get-in after [:effect-queue 0 :effect :op])))))
