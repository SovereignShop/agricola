(ns eurozone.browser.craft-controls
  (:require [agricola.engine :as a] [agricola.card-rules :as cards]
            [eurozone.browser.art :as art]))

(defn attempt [f] (try (f) (catch :default _ nil)))
(defn food-difference [before after] (- (a/amount after :food) (a/amount before :food)))
(defn recipe [resource amount food]
  [:div.choice-recipe {:role "img" :aria-label (str amount " " (name resource) " → " food " food")}
   [:span.choice-effect-pieces [art/piece resource] [:strong amount]]
   [:span {:aria-hidden true} "→"]
   [:span.choice-effect-pieces [art/piece :food] [:strong food]]])
(defn craft [p selected extra? context]
  (a/craft-food p selected (and extra? (some #{:joinery} selected)) context))
(defn preview [p selected id extra? context]
  (let [prefix (if (some #{id} selected) (take-while #(not= id %) selected) selected)
        ;; Funding is solely for displaying the printed exchange when a player
        ;; cannot currently pay. Button legality always uses the original goods.
        funded (reduce #(update-in %1 [:goods %2] (fnil max 0) 2) p [:wood :clay :reed])
        before (craft funded prefix extra? context)
        after (craft before [id] extra? context)
        resource (:craft (a/improvements id))
        amount (- (a/amount before resource) (a/amount after resource))
        food (food-difference before after)
        bonuses (for [cid (cards/active-owned before)
                      :when (some #(get-in (cards/card cid) [:passive %]) [:artisan :potters-wheel :plane])
                      :let [without (-> before (update :occupations #(vec (remove #{cid} %))) (update :minors #(vec (remove #{cid} %))))
                            plain (craft without [id] (and extra? (a/plane? without)) context)
                            bonus (- food (food-difference without plain))]
                      :when (pos? bonus)] {:card cid :food bonus})]
    {:resource resource :amount amount :food food :bonuses bonuses}))
(defn bonus-pieces [bonuses]
  [:div.craft-bonuses
   (for [{:keys [card food]} bonuses] ^{:key card}
     [:span.craft-bonus {:aria-label (str (:name (cards/card card)) " adds " food " food")}
      [:span (:name (cards/card card))] [:span "+"] [art/piece :food] [:b food]])])
(defn controls [g p selected extra? convert choose! choose-plane!]
  (let [context {:phase :feeding :round (:round g)}
        converted (or (attempt #(a/convert-food p convert context)) p)]
    [:div.craft-controls
     [:div.graphical-choice-options {:role "group" :aria-label "Harvest workshops"}
      (for [id [:joinery :pottery :basketry] :when (contains? (:improvements p) id)
            :let [chosen? (boolean (some #{id} selected))
                  next (if chosen? (vec (remove #{id} selected)) (conj (vec selected) id))
                  {:keys [resource amount food bonuses]} (preview converted selected id extra? context)
                  legal? (attempt #(craft converted next extra? context))]] ^{:key id}
        [:button.graphical-choice.craft-choice {:type "button" :aria-label (str "Use " (:name (a/improvements id)))
                                               :data-food food :data-cost amount :data-resource (name resource)
                                               :aria-pressed chosen? :class (when chosen? "selected")
                                               :disabled (and (not chosen?) (not legal?)) :on-click #(choose! next)}
         [:div.choice-card-face [art/major-card id]]
         [recipe resource amount food]
         [bonus-pieces bonuses]
         [:span.choice-caption (str (:name (a/improvements id)) (when chosen? " · selected"))]])]
     (when (and (a/plane? p) (some #{:joinery} selected))
       [:div.graphical-choices {:role "group" :aria-label "Plane recipe"}
        [:p.choice-title "Plane"]
        [:div.graphical-choice-options
         (for [extra [false true] :let [{:keys [resource amount food bonuses]} (preview converted selected :joinery extra context)
                                      legal? (attempt #(craft converted selected extra context))]] ^{:key extra}
           [:button.graphical-choice {:type "button" :aria-label (str "Use " amount " wood with Plane")
                                      :aria-pressed (= (boolean extra?) extra) :class (when (= (boolean extra?) extra) "selected")
                                      :disabled (not legal?) :on-click #(choose-plane! extra)}
            [recipe resource amount food] [bonus-pieces bonuses]])]])
     (when (seq (filter #(contains? (:improvements p) %) [:joinery :pottery :basketry]))
       [:p.small "Each workshop can be used once this harvest. Click again to clear it. Shared card bonuses apply in the order selected."])]))
