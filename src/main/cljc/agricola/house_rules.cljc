(ns agricola.house-rules
  "Optional, persisted table rules. Missing options always mean disabled."
  (:require [clojure.string :as str]))

(def banlist
  ["Ratcatcher" "Late Sleeper" "Remodeling" "Stone Crane" "Wooden Hut Extension"
   "Taster" "Reed Hut" "Lover" "Wet Nurse" "Guest Worker"])
(def banned-names (set (map str/lower-case banlist)))
(def option-keys #{:banlist :braggart :starting-food})
(defn validate [options]
  (when-not (and (or (nil? options) (map? options))
                 (every? option-keys (keys options))
                 (every? boolean? (vals options)))
    (throw (ex-info "Choose valid house-rule switches." {:type :illegal-command})))
  (into {} (filter (comp true? val) options)))
(defn banned? [options card]
  (and (true? (:banlist options))
       (contains? banned-names (str/lower-case (or (:name card) "")))))
(defn braggart? [options card]
  (and (true? (:braggart options)) (= "Braggart" (:name card))))
(defn starting-food [n starter pid options]
  (if (= n 1) 0
    (+ 3 (if (and (:starting-food options) (= starter pid)) -1 0)
         (if (and (:starting-food options) (#{3 4} n) (= pid (mod (dec starter) n))) 1 0))))
(defn configure-new-game [state options]
  (let [options (validate options) n (count (:players state))]
    (cond-> (update state :players
                    #(mapv (fn [p] (assoc-in p [:goods :food]
                                            (starting-food n (:starting-player state) (:id p) options))) %))
      (seq options) (assoc :house-rules options))))
(defn food-summary [n options]
  (cond
    (= n 1) "Starting food: 0 in a solo game."
    (not (:starting-food options)) "Starting food: every player gets 3 food."
    (#{3 4} n) "Starting food: the starting player gets 2; the last player in turn order gets 4; everyone else gets 3."
    :else "Starting food: the starting player gets 2; everyone else gets 3."))
