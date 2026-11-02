(ns eurozone.browser.card-review
  (:require [re-frame.core :as rf] [eurozone.card-scenarios :as scenarios]))

(defn open! [id]
  (if (.-EUROZONE_ACTIVITY js/window)
    (rf/dispatch [:activity/enter "create" nil id])
    (rf/dispatch [:send {:event :create :scenario id
                         :name (or (not-empty (.getItem js/sessionStorage "eurozone-name"))
                                   (not-empty (.getItem js/localStorage "eurozone-name")) "Tester")}])))

(defn controls [room]
  (let [ui @(rf/subscribe [:ui]) permission @(rf/subscribe [:permission])
        index (first (keep-indexed #(when (= (:scenario room) (:id %2)) %1) scenarios/scenarios))]
    (when (and (some? index) (:host? permission))
      (let [definition (nth scenarios/scenarios index)
            busy? (or (:busy? ui) (not (:connected? ui)))
            last? (= (inc index) (count scenarios/scenarios))]
        [:section.card-review {:aria-label "Card review"}
         [:div.card-review-heading
          [:strong (:name definition)]
          [:span.small (str (inc index) " / " (count scenarios/scenarios))]]
         [:p (:description definition)]
         [:div.card-review-actions
          [:button.button {:disabled (or busy? (zero? index))
                           :on-click #(open! (:id (nth scenarios/scenarios (dec index))))} "Previous card"]
          [:button.button {:disabled busy? :on-click #(open! (:id definition))} "Restart test"]
          [:button.button.primary {:disabled (or busy? last?)
                                   :on-click #(open! (:id (nth scenarios/scenarios (inc index))))} "Next card →"]]
         [:small (if last? "Last test table. Previous tables remain saved." "Each button opens a fresh test table; previous tables remain saved.")]]))))
