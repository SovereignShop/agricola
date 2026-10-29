(ns agricola.card-rules
  "Explicit executable card definitions. Catalogue prose is never executed.
   IDs include the edition/pack so reprints cannot overwrite each other's rules."
  (:require [agricola.card-catalog :as catalog] [clojure.string :as str]
            #?(:clj [agricola.data :refer [read-edn]]))
  #?(:cljs (:require-macros [agricola.data :refer [read-edn]])))

(defn catalogue-id [card]
  (let [code (str (str/lower-case (:deck card))
                  (apply str (repeat (max 0 (- 3 (count (:number card)))) "0")) (:number card))]
    (cond
      (and (= "Revised" (:edition card)) (= "Base (Revised)" (:pack card)) (seq (:deck card))) (keyword "revised-base" code)
      (and (= "5-6 Player Expansion" (:pack card)) (= "Occupation" (:type card))) (keyword "revised-five" code)
      :else (keyword "catalog" (:id card)))))
(defn pack-id [card]
  (cond
    (= "revised-base" (namespace (catalogue-id card))) (str "base-" (str/lower-case (:deck card)))
    (= "revised-five" (namespace (catalogue-id card))) "five-player"
    :else (str/lower-case (str (:edition card) " · " (:pack card)
                              (when (= "Original" (:edition card)) (str " · " (:deck card)))))))
(def metadata
  (into {} (for [card catalog/cards
                 :let [cid (catalogue-id card) points (:points-text card)]]
             [cid (assoc card :id cid :catalogue-id (:id card) :pack-id (pack-id card)
                         :kind (case (:type card)
                                 "Occupation" :occupation
                                 ("Minor Improvement" "Minor" "Minor/Major Improvement") :minor
                                 "Major Improvement" :major
                                 "Parent (Mother)" :mother "Parent (Father)" :father
                                 :other)
                         :points (if (re-matches #"-?[0-9]+" points)
                                   #?(:clj (Integer/parseInt points) :cljs (js/parseInt points)) 0))])))
(def revised-code-ids
  ;; Keep legacy identifiers and distinguish printing variants. Short codes are
  ;; convenience aliases for the canonical Revised printing, never Original cards.
  (into {} (for [card (sort-by #(if (str/starts-with? (:pack %) "WizKids") 0 1) catalog/cards)
                 :when (and (= "Revised" (:edition card)) (re-matches #"[A-E]" (:deck card)))
                 :let [code (keyword (str (str/lower-case (:deck card))
                                          (apply str (repeat (max 0 (- 3 (count (:number card)))) "0")) (:number card)))]]
             [code (catalogue-id card)])))
(defn id [code]
  (if (and (keyword? code) (namespace code)) code
    (let [code (keyword (str/lower-case (name code)))]
      (get revised-code-ids code (keyword "revised-base" (name code))))))
(defn five-card? [cid] (= "revised-five" (namespace cid)))
(defn gain [goods] {:op :gain :goods goods})
(defn schedule [offsets goods] {:op :schedule :offsets offsets :goods goods})
(defn on-space [spaces effect] {:event :use-space :spaces (set spaces) :effect effect})

;; These definitions describe timing as well as outcomes. Optional actions stay
;; in the decision queue until their owner resolves them; they never auto-spend.
(def legacy-definitions (merge (read-edn "agricola/cards.edn") (read-edn "agricola/five-player-cards.edn")))
(def expansion-definitions (read-edn "agricola/expansion-cards.edn"))
(def definitions (merge (into {} (map (fn [[code rule]] [(id code) rule]) legacy-definitions)) expansion-definitions))
(def cards (into {} (for [[cid definition] definitions] [cid (merge (metadata cid) definition)])))
(defn card [cid] (get cards cid))
(defn owned [p] (concat (:occupations p) (:minors p)))
(defn has? [p code] (boolean (some #{(id code)} (owned p))))
(defn passives [p key] (keep #(get-in (card %) [:passive key]) (owned p)))

(defn active-owned [p] (remove #(get-in p [:card-state % :face-down?]) (owned p)))
