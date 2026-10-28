(ns eurozone.table-flow
  "Table-level clocks, private harvest plans and human takeback ballots.
   These do not alter Agricola's rules or command ordering."
  (:require [agricola.engine :as a] [agricola.card-game :as game]))

(defn now [] #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))
(def limits [0 30 60 90 120 180 300])
(defn valid-limit [n]
  (a/require! (some #{n} limits) "Choose no clock, or 30, 60, 90, 120, 180 or 300 seconds.") n)
(defn actors [room]
  (let [s (:state room)]
    (cond (or (nil? s) (= :finished (:phase s))) []
          (= :draw (:phase s)) (vec (sort (get-in s [:draw :pending])))
          :else [(game/acting-player s)])))
(defn decision-key [s pid]
  [(:phase s) (:round s) pid (get-in s [:draw :wave])
   (get-in s [:players pid :remaining]) (:decision-id s)])
(defn refresh-clocks [room]
  (let [seconds (get room :turn-seconds 0) s (:state room) t (now)]
    (cond (:takeback room) room
          (zero? seconds) (assoc room :clocks {})
          :else (assoc room :clocks
                       (into {} (for [pid (actors room)
                                      :let [key (decision-key s pid) old (get-in room [:clocks pid])]]
                                  [pid (if (= key (:key old)) old
                                         {:id (str (random-uuid)) :key key :started-at t
                                          :deadline (+ t (* 1000 seconds))})]))))))
(defn due [room]
  (when-not (:takeback room)
    (first (for [pid (actors room) :let [clock (get-in room [:clocks pid])]
                 :when (and clock (<= (:deadline clock) (now)))]
             {:player pid :clock-id (:id clock)}))))
(defn expired-vote? [room]
  (and (:takeback room) (<= (get-in room [:takeback :expires-at]) (now))))
(defn require-time [room pid]
  (a/require! (or (nil? (get-in room [:clocks pid])) (> (get-in room [:clocks pid :deadline]) (now)))
              "Time has expired. A bot is choosing this decision."))
(defn pause-clocks [room]
  (update room :clocks #(into {} (for [[pid c] %] [pid (assoc c :remaining-ms (max 0 (- (:deadline c) (now))))]))))
(defn resume-clocks [room]
  (update room :clocks #(into {} (for [[pid c] %] [pid (-> c (assoc :deadline (+ (now) (:remaining-ms c 0))) (dissoc :remaining-ms))]))))
(defn human-seats [room] (vec (keep-indexed #(when-not (:bot? %2) %1) (:seats room))))
(defn owns? [room token pid]
  (and (integer? pid) (<= 0 pid) (< pid (count (:seats room)))
       (not (get-in room [:seats pid :bot?])) (= token (get-in room [:seats pid :owner]))))
(defn request-takeback [room token pid]
  (a/require! (owns? room token pid) "Only seated players can request a takeback.")
  (a/require! (and (:state room) (seq (:history room))) "There is no move to take back.")
  (a/require! (nil? (:takeback room)) "A takeback vote is already open.")
  (let [voters (human-seats room) move (last (filter #(= :move (:record %)) (:recording room)))]
    (-> room pause-clocks
        (assoc :takeback {:id (str (random-uuid)) :requester pid :player (:player move)
                         :kind (get-in move [:command :type]) :round (:round move)
                         :voters voters :needed (inc (quot (count voters) 2))
                         :yes #{pid} :no #{} :expires-at (+ (now) 60000)})
        (dissoc :takeback-result))))
(defn vote [room token pid vote-id approve?]
  (let [v (:takeback room)]
    (a/require! (and v (= vote-id (:id v))) "That takeback vote has ended.")
    (a/require! (and (owns? room token pid) (some #{pid} (:voters v))) "Only the listed players can vote.")
    (a/require! (boolean? approve?) "Choose approve or decline.")
    (a/require! (and (not ((:yes v) pid)) (not ((:no v) pid))) "This player has already voted.")
    (a/require! (not (expired-vote? room)) "The takeback vote has expired.")
    (update-in room [:takeback (if approve? :yes :no)] conj pid)))
(defn verdict [room]
  (when-let [v (:takeback room)]
    (cond (>= (count (:yes v)) (:needed v)) :approved
          (> (count (:no v)) (- (count (:voters v)) (:needed v))) :declined
          (expired-vote? room) :expired)))
(defn finish-vote [room result]
  (let [v (:takeback room)]
    (-> room resume-clocks (dissoc :takeback)
        (assoc :takeback-result (assoc (select-keys v [:id :requester :player :yes :no :needed]) :status result :at (now))))))
(defn feed-preview [s pid command]
  (a/feed (assoc s :phase :feeding :turn pid :fed #{} :effect-queue []) pid command))
(defn plan [room token {:keys [player round feed breed]}]
  (let [s (:state room)]
    (a/require! (owns? room token player) "Only the player can queue their harvest decisions.")
    (a/require! (and (= round (:round s)) (#{:feeding :breeding} (:phase s))) "This harvest has changed. Review your plan.")
    (a/require! (or feed breed) "Choose a feeding or breeding decision to queue.")
    (when (some #{player} (actors room)) (require-time room player))
    (when feed
      (a/require! (and (= :feeding (:phase s)) (not (contains? (:fed s) player)) (= :feed (:type feed))) "This family has already been fed.")
      (feed-preview s player feed))
    (when breed
      (a/require! (and (= :breed (:type breed)) (not (contains? (:bred s) player))) "This family has already bred its animals.")
      ;; Validate local costs and housing now; actual card effects are rechecked
      ;; when the queued command reaches its proper position in the harvest.
      (a/breed (assoc (if feed (feed-preview s player feed) s)
                     :phase :breeding :turn player :bred #{} :defer-advance? true) player breed))
    (assoc-in room [:harvest-plans player] (cond-> {:round round} feed (assoc :feed feed) breed (assoc :breed breed)))))
(defn trim-plans [room]
  (update room :harvest-plans
          #(into {} (filter (fn [[_ plan]] (and (= (:round plan) (get-in room [:state :round]))
                                               (#{:feeding :breeding} (get-in room [:state :phase])))) %))))
