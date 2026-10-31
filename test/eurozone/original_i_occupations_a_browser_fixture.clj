(require '[eurozone.store :as store] '[agricola.engine :as a] '[agricola.card-game :as g] '[clojure.string :as str])
(defn own [s pid cid] (update-in s [:players pid :occupations] conj cid))
(defn emit [s kind pid] (g/drain (g/queue-front s [(g/event kind pid)])))
(defn resolve! [s choice] (g/command s (g/acting-player s) (merge {:type :resolve :decision (:decision-id s)} choice)))
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      names ["Ada" "Ben" "Cora" "Dan"] base (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0))
      harvest (-> base (own 0 :catalog/original-base-i-223-harvest-helper)
                  (assoc-in [:players 1 :farm 0] {:type :field :crop :grain :count 1}) (emit :feeding-start 0))
      chamberlain (-> base (assoc :round 11) (own 0 :catalog/original-base-i-238-chamberlain) (emit :check-state 0))
      hide (-> base (assoc :round 14 :phase :finish-round) (own 0 :catalog/original-base-i-236-hide-farmer)
               (assoc-in [:players 0 :goods :food] 20) g/finish-game g/drain)
      corn (-> base (own 0 :catalog/original-base-i-239-corn-profiteer) (assoc-in [:players 0 :goods :grain] 1)
               (g/command 0 {:type :ability :card :catalog/original-base-i-239-corn-profiteer})
               (resolve! {}) (resolve! {}) (resolve! {:skip? true}))
      juggler (-> base (own 0 :catalog/original-base-i-237-juggler) (own 0 :catalog/original-base-e-212-dancer)
                  (own 1 :catalog/original-base-e-169-storyteller) (assoc-in [:piles :traveling :food] 1)
                  (g/command 0 {:type :action :space :traveling}))
      businessman (-> base (own 0 :catalog/original-base-i-228-businessman) (own 0 :catalog/original-base-e-179-merchant)
                      (assoc-in [:players 0 :hand :minor] [:catalog/original-base-e-25-spices :catalog/original-base-e-28-clogs
                                                         :catalog/original-base-e-22-raft :catalog/original-base-e-26-plane])
                      (update-in [:players 0] a/gain {:food 20 :wood 20})
                      (g/command 0 {:type :action :space :meeting :card :catalog/original-base-e-25-spices}))
      ids (mapv (fn [[title state]]
                  (let [id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names names :title title :setup {:mode :random}}))]
                    (store/save! s (swap! (:rooms s) assoc-in [id :state] state)) id))
                [["Harvest Helper" harvest] ["Chamberlain" chamberlain] ["Hide Farmer" hide]
                 ["Corn Profiteer" corn] ["Juggler" juggler] ["Businessman and Merchant" businessman]])]
  (println (str/join "," ids)))
