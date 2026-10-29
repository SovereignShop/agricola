(ns eurozone.bot-player
  "Draft and move policies run by the host browser. Learned weights retain
   their original training provenance; unseen cards use heuristic estimates."
  (:require [agricola.bot :as bot] [agricola.draw :as draw] [agricola.engine :as engine]
            [agricola.card-game :as game] [agricola.card-rules :as cards]
            [clojure.string :as str] [eurozone.card-values :as card-values]
            [eurozone.play-values :as play-values]))

(defn actor [room]
  (let [state (:state room) bots (set (keep-indexed #(when (:bot? %2) %1) (:seats room)))]
    (when (and state (not= :finished (:phase state)))
      (if (= :draw (:phase state))
        (first (sort (filter bots (get-in state [:draw :pending]))))
        (let [pid (game/acting-player state)] (when (bots pid) pid))))))

(defn observation [state pid]
  (cond-> (bot/observation state pid)
    (= :draw (:phase state)) (assoc :draw-private (assoc (draw/private-view (:draw state) pid) :wave (get-in state [:draw :wave])))))

(defn draw-value [cid]
  (let [card (cards/card cid)]
    (+ (bot/future-card-value cid 10) (:points card 0)
       (* 0.4 (count (:play card)))
       (- (* 0.35 (reduce + (vals (:printed-cost card)))))
       (if (str/blank? (:prerequisites card)) 0 -0.75))))

(defn beginner-choice [observed pid]
  ;; The original heuristic's previews use the card-game continuation queue.
  ;; Beginner games instead use the base engine, including animal housing.
  (let [p (get-in observed [:players pid])
        preview (assoc observed :defer-advance? true
                       :round-order (vec (concat (:round-order observed) (repeat (- 14 (:round observed)) :grain))))
        candidates (if (= :housing (:phase observed))
                     (for [keep (bot/housing-plans p) cook? [true false]] {:type :house-animals :keep keep :cook? cook?})
                     (concat (bot/candidates observed pid)
                             (when (and (= :work (:phase observed)) (some #{:side-job} (:active observed)))
                               (for [cell (cons nil (range 15)) grain (range (inc (min 8 (engine/amount p :grain))))
                                     :when (or cell (pos? grain))]
                                 (cond-> {:type :action :space :side-job :bake-grain grain} cell (assoc :stables [cell]))))))
        legal (keep (fn [command]
                      (when-let [after (bot/attempt #(engine/command preview pid command))]
                        {:command command :value (bot/utility (assoc after :round (:round observed)) pid)})) candidates)
        best (reduce (fn [best choice] (if (or (nil? best) (> (:value choice) (:value best))) choice best)) nil legal)]
    (when-not best (throw (ex-info "Bot has no legal beginner action." {:phase (:phase observed) :player pid})))
    (:command best)))

(defn choose [observed pid]
  (cond
    (= :draw (:phase observed))
    (let [private (:draw-private observed)
          value #(or (card-values/expected-value (count (:players observed)) %) (draw-value %))
          cid (last (sort-by (juxt value str) (:legal private)))]
      (when-not cid (throw (ex-info "The bot has no legal draft pick." {:player pid})))
      {:type :pick :wave (:wave private) :card cid})
    (not= :cards (:variant observed)) (beginner-choice observed pid)
    :else
    (let [rng #?(:clj (java.util.Random. 42)
                 :cljs #js {:nextInt (fn [n] (js/Math.floor (* (js/Math.random) n)))
                            :nextDouble (fn [] (js/Math.random))})]
      (:command (or (play-values/choice observed pid rng) (bot/choose observed pid rng))))))
