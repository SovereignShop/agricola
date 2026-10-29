(require '[eurozone.store :as store] '[agricola.engine :as a] '[clojure.string :as str]
         '[agricola.card-game :as g] '[agricola.card-rules :as c])
(defn fresh [] (g/initialize (a/new-game ["Ada" "Ben" "Cara" "Dan"] (vec (mapcat identity a/stages)) 0)))
(defn own [state code]
  (let [cid (c/id code)] (update-in state [:players 0 (if (= :minor (:kind (c/card cid))) :minors :occupations)] conj cid)))
(defn hand [state kind codes] (assoc-in state [:players 0 :hand kind] (mapv c/id codes)))
(defn field [state cell crop n] (assoc-in state [:players 0 :farm cell] {:type :field :crop crop :count n}))
(defn emit [state kind] (g/drain (g/queue-front state [(g/event kind 0)])))
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      oven (-> (fresh) (hand :minor [:a027])
               (assoc-in [:players 0 :improvements] #{:fireplace-2 :hearth-4})
               (update :available-improvements disj :fireplace-2 :hearth-4)
               (update-in [:players 0] a/gain {:clay 1 :stone 1 :grain 1})
               (g/command 0 {:type :action :space :meeting :card (c/id :a027)}))
      teacher (-> (fresh) (own :a131) (hand :occupation [:a103 :a104])
                  (assoc :round 2 :active (conj (:active (fresh)) :improvement))
                  (update-in [:players 0] a/gain {:wood 2 :stone 2})
                  (g/command 0 {:type :action :space :improvement :improvement :joinery}))
      angler (-> (fresh) (own :a095) (own :a109) (hand :minor [:a010])
                 (update-in [:players 0] a/gain {:wood 2 :reed 1})
                 (assoc-in [:piles :fishing] {:food 2})
                 (g/command 0 {:type :action :space :fishing}))
      knife (-> (fresh) (own :a058) (field 0 :vegetable 2) (field 1 :grain 1)
                (assoc :phase :return-home :round 8) (emit :return-home))
      ridger (-> (fresh) (own :a059) (field 0 :vegetable 2)
                 (assoc :round 7) (update-in [:players 0] a/gain {:vegetable 2})
                 (g/continue-step :field-harvest) g/drain)
      fan (-> (fresh) (own :a061) (field 0 :grain 2)
              (assoc-in [:players 0 :improvements] #{:clay-oven})
              (assoc :round 4) (g/continue-step :field-harvest) g/drain)
      ids (mapv (fn [state]
                  (let [id (:id (store/transact! s token {:event :create :mode :local :name "Ada"
                                                         :names ["Ada" "Ben" "Cara" "Dan"]
                                                         :title "Artifex actions" :setup {:mode :random}}))]
                    (store/save! s (swap! (:rooms s) assoc-in [id :state] state)) id))
                [oven teacher angler knife ridger fan])]
  (println (str/join "," ids)))
