(require '[eurozone.store :as store] '[agricola.engine :as a] '[clojure.string :as str]
         '[agricola.card-game :as g] '[agricola.card-rules :as c] '[agricola.bot :as bot])
(def names ["Ada" "Ben" "Cara" "Dan"])
(defn fresh [] (g/initialize (a/new-game names (vec (mapcat identity a/stages)) 0)))
(defn own [state code] (let [cid (c/id code)] (update-in state [:players 0 (if (= :occupation (:kind (c/card cid))) :occupations :minors)] conj cid)))
(defn resolve! [state choice] (g/command state 0 (merge {:type :resolve :decision (:decision-id state)} choice)))
(defn act [state command] (-> (g/take-action (assoc state :turn 0) 0 command) bot/without-continuations g/drain))
(let [s (store/make-store (System/getenv "EUROZONE_DATA")) token (apply str (repeat 64 "a"))
      bassinet (-> (fresh) (own :a025) (assoc :turn 1) (g/command 1 {:type :action :space :grain}) (assoc :turn 0))
      forest (-> (fresh) (own :a028) (own :a103) (assoc :occupied {:lessons 1})
                 (assoc-in [:players 0 :goods :food] 0) (assoc-in [:players 0 :goods :wood] 1)
                 (update-in [:players 0 :hand :occupation] conj (c/id :a116)))
      mummy (-> (fresh) (own :a130) (assoc-in [:players 0 :family] 3) (assoc-in [:players 0 :remaining] 3)
                (act {:space :grain}) (act {:space :labor}))
      lazy (-> (fresh) (own :a094) (update :active conj :cultivation)
               (g/command 0 {:type :action :space :cultivation :cell 0}) (resolve! {:skip? true}))
      swag (-> (fresh) (own :a129) (update-in [:players 0] a/gain {:wood 2})
               (g/command 0 {:type :action :space :build :stables [0]}))
      minstrel (-> (fresh) (own :a151) (assoc :phase :return-home :turn 3)
                   (assoc-in [:players 0 :remaining] 0) (assoc-in [:piles :sheep] {:sheep 1})
                   (g/queue-front [(g/event :return-home 0)]) g/drain)
      ids (mapv (fn [state]
                  (let [id (:id (store/transact! s token {:event :create :mode :local :name "Ada" :names names
                                                         :title "Artifex placement cards" :setup {:mode :random}}))]
                    (store/save! s (swap! (:rooms s) assoc-in [id :state] state)) id))
                [bassinet forest mummy lazy swag minstrel])]
  (println (str/join "," ids)))
