(ns agricola.card-rules
  "Explicit executable card definitions. Catalogue prose is never executed.
   IDs include the edition/pack so reprints cannot overwrite each other's rules."
  (:require [agricola.card-catalog :as catalog] [clojure.string :as str]
            #?(:clj [agricola.data :refer [read-edn]]))
  #?(:cljs (:require-macros [agricola.data :refer [read-edn]])))

(def expansion-codes (set (for [deck ["a" "b" "c" "d"] number (range 169 181)] (str deck number))))
(defn id [code] (let [code (str/lower-case (name code))] (keyword (if (expansion-codes code) "revised-five" "revised-base") code)))
(defn five-card? [cid] (= "revised-five" (namespace cid)))
(def metadata
  (into {} (for [c (concat catalog/revised-base (filter #(and (= "5-6 Player Expansion" (:pack %)) (= "Occupation" (:type %))) catalog/cards)) :when (not= "Major Improvement" (:type c))
                 :let [code (str (str/lower-case (:deck c)) (apply str (repeat (- 3 (count (:number c))) "0")) (:number c))]]
             [(id code) (assoc c :id (id code) :kind (if (= "Occupation" (:type c)) :occupation :minor)
                              :points (if (seq (:points-text c)) #?(:clj (Integer/parseInt (:points-text c)) :cljs (js/parseInt (:points-text c))) 0))])))
(defn gain [goods] {:op :gain :goods goods})
(defn schedule [offsets goods] {:op :schedule :offsets offsets :goods goods})
(defn on-space [spaces effect] {:event :use-space :spaces (set spaces) :effect effect})

;; These definitions describe timing as well as outcomes. Optional actions stay
;; in the decision queue until their owner resolves them; they never auto-spend.
(def definitions (merge (read-edn "agricola/cards.edn") (read-edn "agricola/five-player-cards.edn")))

(def cards (into {} (for [[code definition] definitions :let [cid (id code)]] [cid (merge (metadata cid) definition)])))
(defn card [cid] (get cards cid))
(defn owned [p] (concat (:occupations p) (:minors p)))
(defn has? [p code] (boolean (some #{(id code)} (owned p))))
(defn passives [p key] (keep #(get-in (card %) [:passive key]) (owned p)))

(defn active-owned [p] (remove #(get-in p [:card-state % :face-down?]) (owned p)))
