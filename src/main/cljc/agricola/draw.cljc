(ns agricola.draw
  "Mixed-packet draft: one card per wave, passing left, seven of each kind.
   Completed or blocked seats pass automatically. Packets keep circulating until
   every quota is met; extra cards are discarded only at completion."
  (:require [clojure.set :as set]))
(def kinds [:occupation :minor])
(def quota 7)
(defn require! [ok message] (when-not ok (throw (ex-info message {:type :illegal-draw}))))
(defn complete-seat? [d pid] (every? #(= quota (count (get-in d [:selected pid %]))) kinds))
(defn legal-cards [d pid]
  (filterv #(< (count (get-in d [:selected pid (get (:types d) %)])) quota) (get-in d [:packets pid])))
(defn rotate-left [packets]
  ;; Seat i passes to (i+1) mod n. Empty packets and completed seats still rotate.
  (into [(peek packets)] (pop packets)))
(defn normalize [d]
  (loop [d d empty-waves 0]
    (cond
      (every? #(complete-seat? d %) (range (count (:packets d))))
      (assoc d :status :complete :pending #{} :discarded (vec (mapcat identity (:packets d)))
               :packets (mapv (constantly []) (:packets d)))
      (seq (:pending d)) d
      :else
      (let [d (if (:advance? d)
                (-> d (update :packets rotate-left) (update :wave inc) (assoc :advance? false :ready #{})) d)
            reasons (into {} (for [pid (range (count (:packets d))) :when (empty? (legal-cards d pid))]
                               [pid (if (complete-seat? d pid) :complete :no-legal-card)]))
            pending (set/difference (set (range (count (:packets d)))) (set (keys reasons)))
            d (assoc d :pending pending :passes reasons)]
        (if (seq pending) d
            (do
              ;; A valid deal has enough of both kinds globally. In at most n
              ;; rotations a still-needed kind must reach a seat that needs it.
              (require! (< empty-waves (count (:packets d))) "The draw cannot fill every hand; the card supply is inconsistent.")
              (recur (assoc d :advance? true) (inc empty-waves))))))))
(defn new-draw
  "cards is a shuffled vector of unique {:id ... :kind ...}. Its length must be
   n*(14+extra), with at least 7*n cards of each kind. Caller owns randomness."
  [n extra cards]
  (require! (and (integer? n) (<= 1 n 6) (integer? extra) (<= 0 extra 50)) "Invalid draw settings.")
  (require! (= (count cards) (* n (+ 14 extra))) "Each packet must contain exactly 14+N cards.")
  (require! (= (count cards) (count (set (map :id cards)))) "Cards cannot occur twice in the draw.")
  (require! (every? (set kinds) (map :kind cards)) "Only occupations and minor improvements belong in the draw.")
  (doseq [kind kinds]
    (require! (>= (count (filter #(= kind (:kind %)) cards)) (* quota n)) "Not enough cards of each type to fill every hand."))
  (normalize {:status :drawing :wave 1 :extra extra :advance? false :ready #{} :pending #{}
              :types (into {} (map (juxt :id :kind) cards))
              :packets (mapv vec (partition (+ 14 extra) (map :id cards)))
              :selected (vec (repeat n {:occupation [] :minor []})) :discarded []}))
(defn pick [d pid wave card]
  (require! (= :drawing (:status d)) "This draw is already complete.")
  (require! (and (integer? pid) (<= 0 pid) (< pid (count (:packets d)))) "Invalid draw seat.")
  (require! (= wave (:wave d)) "That packet has already been passed. Review your current packet.")
  (require! (contains? (:pending d) pid) "You have already picked, or have no legal pick in this packet.")
  (require! (some #{card} (legal-cards d pid)) "Choose a card from your packet whose type is not already full.")
  (-> d
      (update-in [:selected pid ((:types d) card)] conj card)
      (update-in [:packets pid] #(vec (remove #{card} %)))
      (update :pending disj pid)
      (update :ready conj pid)
      (assoc :advance? true)
      normalize))
(defn public-view [d]
  (when d
    {:status (:status d) :wave (:wave d) :extra (:extra d) :pending (:pending d)
     :ready (:ready d) :passes (:passes d)
     :counts (mapv #(into {} (map (fn [[k v]] [k (count v)]) %)) (:selected d))}))
(defn private-view [d pid]
  (when d
    {:packet (if (contains? (:pending d) pid) (get-in d [:packets pid]) [])
     :selected (get-in d [:selected pid])
     :legal (if (contains? (:pending d) pid) (legal-cards d pid) [])}))
