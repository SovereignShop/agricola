(ns eurozone.voice
  "Small, exact voice vocabulary. The native command handler remains authoritative."
  (:require [clojure.string :as str] [agricola.engine :as a] [agricola.card-game :as cards]))

(def spaces #{:wood :clay :reed :fishing :grain :labor :grove :hollow :market :copse
              :traveling :stone-west :stone-east :vegetable :sheep :boar :cattle})
(def aliases {:wood ["wood"] :clay ["clay"] :reed ["reed"] :grain ["grain"]
              :vegetable ["vegetable" "vegetables"] :sheep ["sheep"] :boar ["boar" "wild boar"]
              :cattle ["cattle"] :stone-west ["stone"] :stone-east ["stone"]
              :labor ["day laborer" "day labourer"]})
(defn normalize [text]
  (-> (or text "") str/lower-case (str/replace #"[.!?,]" "") (str/replace #"\s+" " ") str/trim))
(defn eligible? [room seats connected? busy?]
  (let [g (:state room)]
    (and connected? (not busy?) (some #{(cards/acting-player g)} seats)
         (= :work (:phase g)) (empty? (:effect-queue g)) (empty? (:takeback room)))))
(defn choices [g]
  (for [id (:active g)
        :when (and (spaces id) (a/available-space? g id) (not (contains? (:occupied g) id)))]
    {:space id :name (:name (a/action-definition g id))}))

(defn assistant-actions [g]
  (if (and (= :work (:phase g)) (empty? (:effect-queue g)))
    (mapv (fn [id]
            (let [definition (a/action-definition g id)]
              {:space id :name (:name definition) :mode (if (spaces id) :place :open)
               :description (:description definition)
               :gain (if (:accumulate definition) (get-in g [:piles id]) (:gain definition))}))
          (filter #(and (a/available-space? g %) (not (contains? (:occupied g) %))) (:active g))) []))

(defn assistant-context
  "Explicit public-state allowlist. Never send hands, decks, tokens, or future round order."
  [room seats history pending]
  (let [g (:state room)]
    {:schema "agricola-assistant-v1" :round (:round g) :phase (:phase g)
     :acting-player (cards/acting-player g) :controlled-seats (vec seats)
     :players (mapv (fn [p]
                      (assoc (select-keys p [:id :name :goods :family :remaining :house])
                             :farm (mapv #(select-keys % [:type :stable :crop :count]) (:farm p))
                             :food-needed (a/food-needed g p))) (:players g))
     :actions (assistant-actions g)
     :pending (when pending (select-keys pending [:space :name :mode]))
     :history (vec (take-last 6 history))
     :limits "No hand/card rules or future rounds included. Open actions require native board choices. Available spaces still require engine validation."}))
(defn interpret [g text]
  (let [text (normalize text)
        phrase (str/replace text #"^(?:please )?(?:take |collect |go to |place (?:a |my )?worker (?:on |at )?)(?:the )?" "")
        matches (filter (fn [{:keys [space name]}]
                          (some #{phrase} (map normalize (cons name (aliases space))))) (choices g))]
    (cond
      (#{"cancel" "never mind" "nevermind" "no"} text) {:kind :cancel}
      (#{"confirm" "yes" "do it"} text) {:kind :confirm}
      (= 1 (count matches)) (assoc (first matches) :kind :action)
      (> (count matches) 1) {:kind :error :message (str "Which space? Say " (str/join " or " (map :name matches)) ".")}
      :else {:kind :error :message "No matching available space. Try “take wood”, “fishing”, or an available space’s name."})))
