(ns agricola.card-impact
  "Presentation-only history of committed card effects, kept outside rules state."
  (:require [agricola.engine :as a] [agricola.card-rules :as cards]))

(defn nonzero [m] (into {} (remove (comp zero? val) m)))
(defn delta [before after] (nonzero (merge-with + (into {} (map (fn [[k v]] [k (- v)]) before)) after)))
(defn pieces [p]
  {:fields (count (a/tiles p :field)) :rooms (count (a/tiles p :room))
   :stables (count (filter :stable (:farm p))) :fences (count (:fences p))
   :family (:family p 0) :bonus-points (:bonus-points p 0)})
(defn event [before after {:keys [player card effect context]} choice]
  (when (and card (some #{card} (concat (cards/owned (get-in before [:players player]))
                                      (cards/owned (get-in after [:players player])))))
    {:player player :card card :round (:round before) :phase (:phase before)
     :op (:op effect) :space (:space context) :skipped? (boolean (:skip? choice))
     :changes (if (:skip? choice) []
                (vec (for [[old p] (map vector (:players before) (:players after))
                           :let [received (delta (:received old) (:received p))
                                 goods (delta (:goods old) (:goods p))
                                 gained (nonzero (into {} (for [r a/resources]
                                                           [r (max 0 (get received r 0) (get goods r 0))])))
                                 spent (nonzero (into {} (for [r a/resources]
                                                          [r (max 0 (- (get gained r 0) (get goods r 0)))])))
                                 farm (delta (pieces old) (pieces p))]
                           :when (or (seq gained) (seq spent) (seq farm))]
                       {:player (:id p) :gained gained :spent spent :farm farm})))}))

(defn legacy-event [move {:keys [player card effect context choice]}]
  (when card
    (let [known? (and (= :gain (:op effect)) (map? (:goods effect))
                      (every? number? (vals (:goods effect))) (not (:cost effect)))
          skipped? (boolean (:skip? choice))]
      {:player player :card card :round (:round move) :phase (:phase move)
       :op (:op effect) :space (:space context) :skipped? skipped?
       :legacy? true :unknown? (and (not skipped?) (not known?))
       :changes (if (and known? (not skipped?))
                  [{:player player :gained (:goods effect) :spent {} :farm {}}] [])})))

(defn history [records state]
  ;; Publish only the owner's played cards. Private hand responses and draft
  ;; identities must never escape through this convenience projection.
  (let [visible (set (for [p (:players state) cid (cards/owned p)] [(:id p) cid]))]
    (reduce
     (fn [out [move-index event-index event]]
       (let [path [(:player event) (:card event)]]
         (if-not (visible path) out
           (update-in out path (fnil conj []) (assoc event :move move-index :index event-index)))))
     {}
     (for [move records :when (= :move (:record move))
           [i event] (map-indexed vector (if (contains? move :impacts) (:impacts move)
                                           (keep #(legacy-event move %) (:effects move))))]
       [(:index move) i event]))))

(defn totals [events pid]
  (reduce (fn [out change]
            (merge-with #(merge-with + %1 %2) out (select-keys change [:gained :spent :farm])))
          {:gained {} :spent {} :farm {}}
          (filter #(= pid (:player %)) (mapcat :changes events))))
