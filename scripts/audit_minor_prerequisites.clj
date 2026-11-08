(require '[agricola.card-rules :as cards] '[agricola.card-expressions :as expr]
         '[agricola.card-game :as game] '[agricola.engine :as a]
         '[clojure.edn :as edn] '[clojure.pprint :as pp])
;; This checks executable requirements against the declared English requirements.
;; It does not pretend to OCR/verify every printed face. Exceptional predicates
;; must have an exact, reviewed entry; changed text/rules invalidate that entry.
(def reviewed (edn/read-string (slurp "resources/rules/minor-prerequisite-reviews.edn")))
(def minors (sort-by key (filter #(= :minor (:kind (val %))) cards/cards)))
(def issues (atom []))
(def checks (atom 0))
(def base (game/initialize (a/new-game ["Audit" "Other"] (vec (mapcat identity a/stages)) 0)))
(def ordinary-occupations [:catalog/original-base-e-176-woodcutter :catalog/original-base-e-205-reed-collector
                           :catalog/original-base-e-187-clay-deliveryman :catalog/original-base-e-201-cattle-whisperer
                           :catalog/original-base-e-174-tutor :catalog/original-base-e-197-braggart])
(doseq [[id card] minors
        :let [text (:prerequisites card) parsed (expr/requirement text)
              actual (if (contains? card :requires) (:requires card) parsed)
              review (get reviewed id)]]
  (when (or (nil? actual)
            (and (not= parsed actual)
                 (not (and (= text (:text review)) (= actual (:requires review)) (seq (:reason review))))))
    (swap! issues conj {:id id :text text :parsed parsed :actual actual :issue :unreviewed-requirement}))
  ;; Exercise all pure occupation counts on both sides of their boundary through
  ;; the same legality predicate used by the UI, bots and native card play.
  (when (and (vector? parsed) (= [:occupations] (second parsed)))
    (doseq [n (range 7)]
      (swap! checks inc)
      (let [s (assoc-in base [:players 0 :occupations] (vec (take n ordinary-occupations)))
            expected (boolean (expr/evaluate s 0 {:occupation-purpose :minor-prerequisite} parsed))]
        (when (not= expected (game/prerequisites? s 0 card))
          (swap! issues conj {:id id :occupations n :expected expected :issue :occupation-boundary}))))))
(def overrides
  (vec (for [[id card] minors :let [source (get cards/metadata id)]
             :when (not= (:prerequisites source) (:prerequisites card))]
         {:id id :catalogue (:prerequisites source) :effective (:prerequisites card)})))
(def report {:minor-count (count minors) :occupation-boundary-checks @checks
             :reviewed-special-requirements (count reviewed)
             :metadata-overrides overrides :failures @issues
             :scope "Declared prerequisite/executable consistency, occupation boundaries, reviewed special predicates. Print existence/checksums are checked separately; this is not a visual audit of every print."})
(pp/pprint report)
(when-let [path (first *command-line-args*)] (spit path (with-out-str (pp/pprint report))))
(when (seq @issues) (System/exit 1))
