(ns eurozone.store-test
  (:require [clojure.test :refer :all] [eurozone.store :as s] [eurozone.model :as model]
            [datascript.core :as d] [agricola.engine :as a]
            [agricola.recording :as recording] [clojure.edn :as edn]
            [agricola.card-game :as cards] [agricola.card-rules :as rules] [agricola.draw :as draw])
  (:import [java.nio.file Files]))
(defn create [store mode] (:id (s/transact! store "host" {:event :create :name "Ada" :mode mode :names ["Ada" "Ben"]})))
(deftest starting-player-food-survives-every-hand-setup
  (doseq [n (range 2 6) starter (range n) mode [:beginner :random :draw]]
    (let [names (mapv #(str "Player " %) (range n))
          order (vec (mapcat identity a/stages))
          initial (with-redefs [s/new-state (fn [_ names] (a/new-game names order starter))]
                    (s/start-state names {:mode mode :extra 2}))
          expected (mapv #(if (= starter %) 2 3) (range n))
          finished (if (= mode :draw)
                     (s/finish-draw
                      (loop [state initial]
                        (let [d (:draw state)]
                          (if (= :complete (:status d)) state
                            (let [pid (first (sort (:pending d))) card (first (draw/legal-cards d pid))]
                              (recur (update state :draw draw/pick pid (:wave d) card))))))) initial)]
      (is (= expected (mapv #(a/amount % :food) (:players initial))) (str n " players, start " starter ", " mode))
      (is (= starter (:starting-player finished)))
      (is (= expected (mapv #(a/amount % :food) (:players finished)))))))
(deftest newborn-occupancy-set-survives-client-projection
  (doseq [newborn-only [#{} #{:growth}]]
    (let [state (-> (cards/initialize (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0))
                    (assoc :newborn-only newborn-only :newborn-spaces {:growth [0]} :occupied {:growth 0}))
          room {:id "projection" :title "Projection" :mode :local :revision 0 :state state}
          db (d/db-with (d/empty-db model/schema) (model/game-tx room))
          projected (:state (model/pull-game db "projection"))
          after (cards/take-action projected 0 {:space :grain})]
      (is (= newborn-only (:newborn-only projected)))
      (is (set? (:newborn-only projected)))
      (is (= (if (seq newborn-only) [] [0]) (cards/space-owners projected :growth)))
      (is (= 1 (a/amount (cards/p after 0) :grain))))))
(deftest harvest-membership-and-cancelled-actions-survive-client-projection
  (let [cancelled #{[2 :sow-bake 7]}
        state (-> (cards/initialize (a/new-game ["Ada" "Ben" "Cara"] (vec (mapcat identity a/stages)) 0))
                  (assoc :phase :feeding :fed #{2} :bred #{1} :cancelled-sowing cancelled))
        room {:id "membership" :title "Membership" :mode :local :revision 0 :state state}
        db (d/db-with (d/empty-db model/schema) (model/game-tx room))
        projected (:state (model/pull-game db "membership"))]
    (is (= #{2} (:fed projected)))
    (is (= #{1} (:bred projected)))
    (is (= cancelled (:cancelled-sowing projected)))
    (is (not (contains? (:fed projected) 0)) "The first vector index is not an already-fed player")
    (is (contains? (:fed projected) 2))
    (is (not (contains? (:bred projected) 0)))
    (is (contains? (:bred projected) 1))
    (is (contains? (:cancelled-sowing projected) [2 :sow-bake 7]))
    (is (= 1 (count (conj (:fed projected) 2))))
    (is (= 1 (count (conj (:bred projected) 1))))))
(deftest lobby-settings-are-authorized-and-persisted
  (doseq [n [3 4 5]]
    (let [store (s/make-store nil)
          id (:id (s/transact! store "host" {:event :create :name "Host" :mode :online :expected-players 2}))
          send! (fn [token msg] (s/transact! store token (assoc msg :id id :revision (get-in @(:rooms store) [id :revision]))))
          settings {:event :configure :expected-players n :setup {:mode :draw :extra 2}}]
      (send! "guest" {:event :join :name "Guest"})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Only the table host" (send! "guest" settings)))
      (is (thrown? clojure.lang.ExceptionInfo (send! "spectator" settings)))
      (doseq [invalid [0 6 2.5 nil]]
        (is (thrown? clojure.lang.ExceptionInfo (send! "host" (assoc settings :expected-players invalid)))))
      (is (thrown? clojure.lang.ExceptionInfo (send! "host" (assoc settings :expected-players 1))))
      (is (thrown? clojure.lang.ExceptionInfo (send! "host" (assoc settings :setup {:mode :invalid}))))
      (send! "host" settings)
      (let [projected (model/pull-game @(:conn store) id)]
        (is (= n (:expected-players projected)))
        (is (= {:mode :draw :extra 2} (:setup projected)))
        (is (= [true false] (:seat-readiness projected))))
      (doseq [i (range 2 n)] (send! (str "guest-" i) {:event :join :name (str "Guest " i)}))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"everyone is ready" (send! "host" {:event :start})))
      (send! "guest" {:event :ready :ready? true})
      (send! "host" settings)
      (is (every? :ready? (get-in @(:rooms store) [id :seats])))
      (send! "host" {:event :start})
      (is (= n (count (get-in @(:rooms store) [id :state :players]))))
      (is (= (repeat n 16) (map count (get-in @(:rooms store) [id :state :draw :packets]))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"before an online game starts" (send! "host" settings))))))
(deftest five-player-capacity-and-reserve-privacy
  (let [store (s/make-store nil)
        id (:id (s/transact! store "host" {:event :create :name "Host" :mode :online
                                           :expected-players 5 :setup {:mode :random}}))]
    (doseq [i (range 1 5)]
      (s/transact! store (str "guest-" i) {:event :join :id id :revision (dec i) :name (str "Guest " i)}))
    (is (thrown? clojure.lang.ExceptionInfo
                 (s/transact! store "sixth" {:event :join :id id :revision 4 :name "Sixth"})))
    (s/transact! store "host" {:event :start :id id :revision 4})
    (let [state (get-in @(:rooms store) [id :state])
          public (:state (model/pull-game @(:conn store) id))
          deck (set (:minor-deck state))
          dealt (set (mapcat #(get-in % [:hand :minor]) (:players state)))]
      (is (= (- (count (filter #(= :minor (:kind %)) (s/hand-pool 5))) 35)
             (count deck) (:minor-deck-count public)))
      (is (not-any? dealt deck))
      (is (nil? (:minor-order public)))
      (is (nil? (:minor-deck public)))
      (is (every? #(nil? (:hand %)) (:players public)))
      (doseq [token ["host" "guest-4" "spectator"]
              :let [snapshot (s/snapshot store token) text (pr-str snapshot)]]
        (is (not (.contains text ":minor-order")))
        (is (not (.contains text ":minor-deck ")))
        (is (not-any? #(.contains text (str %)) deck))))))
(deftest human-recording-survives-undo-and-remains-private
  (let [store (s/make-store nil) id (create store :local)]
    (s/transact! store "host" {:event :start :id id :revision 0})
    (s/transact! store "host" {:event :command :id id :revision 1 :command {:type :action :space :wood}})
    (s/transact! store "host" {:event :undo :id id :revision 2})
    (s/transact! store "host" {:event :command :id id :revision 3 :command {:type :action :space :clay}})
    (let [export (s/export-recording store "host" id)
          timeline (recording/timeline (edn/read-string (str "[" (:text export) "]")))]
      (is (= 1 (count (:moves timeline))))
      (is (= :clay (get-in timeline [:moves 0 :command :space])))
      (is (= (get-in @(:rooms store) [id :state]) (peek (:frames timeline))))
      (is (false? (get-in timeline [:header :partial-start?]))))
    (is (thrown? clojure.lang.ExceptionInfo (s/export-recording store "spectator" id)))
    (swap! (:rooms store) assoc-in [id :mode] :online)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"after the game finishes" (s/export-recording store "host" id)))
    (is (not (.contains (pr-str (:datoms (s/snapshot store "host"))) ":recording")))))
(deftest authority-and-versioning
  (let [store (s/make-store nil) id (create store :online)]
    (is (thrown? clojure.lang.ExceptionInfo (s/transact! store "other" {:event :start :id id :revision 0})))
    (s/transact! store "other" {:event :join :id id :revision 0 :name "Ben"})
    (is (thrown? clojure.lang.ExceptionInfo (s/transact! store "host" {:event :start :id id :revision 0})))
    (s/transact! store "host" {:event :start :id id :revision 1})
    (let [g (get-in @(:rooms store) [id :state]) pid (:turn g) owner (get-in @(:rooms store) [id :seats pid :owner])
          cmd {:event :command :id id :revision 2 :command {:type :action :space :wood}}]
      (is (thrown? clojure.lang.ExceptionInfo (s/transact! store "spectator" cmd)))
      (is (= 2 (get-in @(:rooms store) [id :revision])))
      (s/transact! store owner cmd)
      (is (= 3 (get-in @(:rooms store) [id :revision])))
      (is (thrown? clojure.lang.ExceptionInfo (s/transact! store owner cmd))))
    (is (thrown? clojure.lang.ExceptionInfo (s/transact! store "host" {:event :undo :id id :revision 3})))
    (let [public (pr-str (s/snapshot store "host"))]
      (is (not (.contains public ":round-order")))
      (is (not (.contains public ":owner")))
      (is (not (.contains public "\"host\""))))))
(deftest projection-and-client-deltas
  (let [store (s/make-store nil) id (create store :local)
        client (d/db-with (d/empty-db model/schema) (model/datoms->tx (:datoms (s/snapshot store "host"))))
        started (s/transact! store "host" {:event :start :id id :revision 0})
        client (d/db-with client (model/datoms->tx (:datoms started)))
        before (:state (model/pull-game client id))
        move (s/transact! store "host" {:event :command :id id :revision 1 :command {:type :action :space :plow :cell 4}})
        client (d/db-with client (model/datoms->tx (:datoms move)))
        after (:state (model/pull-game client id))
        undo (s/transact! store "host" {:event :undo :id id :revision 2})
        client (d/db-with client (model/datoms->tx (:datoms undo)))]
    (is (= :field (get-in after [:players (:turn before) :farm 4 :type])))
    (is (= before (:state (model/pull-game client id))))
    (is (= (model/pull-game @(:conn store) id) (model/pull-game client id)))
    (is (= 2 (count (:players before))))
    (is (= 15 (count (get-in before [:players 0 :farm]))))))
(deftest durable-reload
  (let [path (.toString (Files/createTempFile "eurozone-test" ".edn" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (spit path "{}")
      (let [store (s/make-store path) id (create store :local)]
        (s/transact! store "host" {:event :start :id id :revision 0})
        (let [restored (s/make-store path)]
          (is (= @(:rooms store) @(:rooms restored)))
          (is (= (model/pull-game @(:conn store) id) (model/pull-game @(:conn restored) id)))))
      (finally (.delete (java.io.File. path))))))
(deftest failed-persistence-does-not-publish
  (let [store (s/make-store nil) rooms @(:rooms store) facts @(:conn store)]
    (with-redefs [s/save! (fn [& _] (throw (ex-info "disk unavailable" {})))]
      (is (thrown? clojure.lang.ExceptionInfo (create store :local))))
    (is (= rooms @(:rooms store)))
    (is (= facts @(:conn store)))))
(deftest simultaneous-commands-only-commit-once
  (let [store (s/make-store nil) id (create store :local)
        _ (s/transact! store "host" {:event :start :id id :revision 0})
        gate (promise)
        attempt #(future @gate (try (s/transact! store "host" {:event :command :id id :revision 1 :command {:type :action :space %}})
                                    :accepted (catch clojure.lang.ExceptionInfo _ :rejected)))
        one (attempt :wood) two (attempt :clay)]
    (deliver gate true)
    (is (= #{:accepted :rejected} #{@one @two}))
    (is (= 2 (get-in @(:rooms store) [id :revision])))
    (is (= 1 (count (get-in @(:rooms store) [id :state :occupied]))))))

(deftest configured-lobby-enforces-capacity-and-readiness
  (let [store (s/make-store nil)
        id (:id (s/transact! store "host" {:event :create :name "Host" :mode :online :expected-players 2 :setup {:mode :draw :extra 3}}))]
    (is (thrown? clojure.lang.ExceptionInfo (s/transact! store "host" {:event :start :id id :revision 0})))
    (s/transact! store "guest" {:event :join :id id :revision 0 :name "Guest"})
    (is (thrown? clojure.lang.ExceptionInfo (s/transact! store "third" {:event :join :id id :revision 1 :name "Third"})))
    (s/transact! store "guest" {:event :ready :id id :revision 1 :ready? false})
    (is (thrown? clojure.lang.ExceptionInfo (s/transact! store "host" {:event :start :id id :revision 2})))
    (s/transact! store "guest" {:event :ready :id id :revision 2 :ready? true})
    (s/transact! store "host" {:event :start :id id :revision 3})
    (is (= :draw (get-in @(:rooms store) [id :state :phase])))
    (is (= [17 17] (mapv count (get-in @(:rooms store) [id :state :draw :packets]))))))

(deftest draft-packets-and-picks-are-private-and-concurrent
  (let [store (s/make-store nil)
        id (:id (s/transact! store "host" {:event :create :name "Host" :mode :online :expected-players 2 :setup {:mode :draw :extra 0}}))
        _ (s/transact! store "guest" {:event :join :id id :revision 0 :name "Guest"})
        _ (s/transact! store "host" {:event :start :id id :revision 1})
        host-view (s/snapshot store "host") guest-view (s/snapshot store "guest") spy-view (s/snapshot store "spy")
        host-card (first (get-in host-view [:private-draws id 0 :legal]))
        guest-card (first (get-in guest-view [:private-draws id 1 :legal]))
        public (pr-str (:datoms host-view))]
    (is (= #{0} (set (keys (get-in host-view [:private-draws id])))))
    (is (= #{1} (set (keys (get-in guest-view [:private-draws id])))))
    (is (empty? (get-in spy-view [:private-draws id])))
    (is (not (.contains public (str host-card))))
    (is (not (.contains public (str guest-card))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (s/transact! store "guest" {:event :draw-pick :id id :revision 2 :wave 1 :player 0 :card host-card})))
    (s/transact! store "host" {:event :draw-pick :id id :revision 2 :wave 1 :player 0 :card host-card})
    ;; Guest's revision is now stale, but their packet has not changed. Their
    ;; independent pick must still commit, rather than racing the host.
    (s/transact! store "guest" {:event :draw-pick :id id :revision 2 :wave 1 :player 1 :card guest-card})
    (is (= 2 (get-in @(:rooms store) [id :state :draw :wave])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (s/transact! store "host" {:event :draw-pick :id id :revision 2 :wave 1 :player 0 :card host-card})))
    (loop [steps 0]
      (let [room (get @(:rooms store) id) state (:state room)]
        (when (= :draw (:phase state))
          (is (< steps 40))
          (let [pid (first (sort (get-in state [:draw :pending]))) token (get-in room [:seats pid :owner])
                choice (first (get-in (s/private-draws store token) [id pid :legal]))]
            (s/transact! store token {:event :draw-pick :id id :revision (:revision room) :wave (get-in state [:draw :wave]) :player pid :card choice})
            (recur (inc steps))))))
    (is (= :work (get-in @(:rooms store) [id :state :phase])))
    (is (= 1 (get-in @(:rooms store) [id :state :round])))
    (is (every? #(= {:occupation 7 :minor 7} (into {} (map (fn [[k v]] [k (count v)]) (:hand %))))
                (get-in @(:rooms store) [id :state :players])))
    (is (= #{0} (set (keys (get-in (s/private-view store "host") [id])))))
    (is (not (.contains (pr-str (:datoms (s/snapshot store "spy"))) (str host-card))))))

(deftest guests-can-leave-and-another-browser-can-take-the-seat
  (let [store (s/make-store nil) id (:id (s/transact! store "host" {:event :create :name "Host" :mode :online :expected-players 2}))]
    (s/transact! store "guest" {:event :join :id id :revision 0 :name "Guest"})
    (s/transact! store "guest" {:event :leave :id id :revision 1})
    (is (= [] (get-in (s/permissions store "guest") [id :seats])))
    (s/transact! store "replacement" {:event :join :id id :revision 2 :name "Replacement"})
    (is (= [1] (get-in (s/permissions store "replacement") [id :seats])))
    (is (thrown? clojure.lang.ExceptionInfo (s/transact! store "host" {:event :leave :id id :revision 3})))))

(deftest illegal-card-command-does-not-change-disk-facts-or-state
  (let [path (.toString (Files/createTempFile "eurozone-card-validation" ".edn" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (spit path "{}")
      (let [store (s/make-store path) id (create store :local)
            state (-> (cards/initialize (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0))
                      (assoc-in [:players 0 :hand] {:minor [(rules/id :b068)] :occupation []}))]
        (with-redefs [s/start-state (fn [_ _] state)]
          (s/transact! store "host" {:event :start :id id :revision 0}))
        (let [rooms @(:rooms store) facts @(:conn store) saved (slurp path)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Requires: 2 Occupations"
                               (s/transact! store "host" {:event :command :id id :revision 1
                                                         :command {:type :action :space :meeting :card (rules/id :b068)}})))
          (is (= rooms @(:rooms store)))
          (is (= facts @(:conn store)))
          (is (= saved (slurp path)))
          (is (= rooms @(:rooms (s/make-store path))))))
      (finally (.delete (java.io.File. path))))))

(deftest draft-and-card-decisions-survive-server-restarts
  (let [path (.toString (Files/createTempFile "eurozone-decisions" ".edn" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (spit path "{}")
      (let [store (s/make-store path)
            id (:id (s/transact! store "host" {:event :create :mode :local :name "Ada" :names ["Ada" "Ben"] :setup {:mode :draw :extra 2}}))]
        (s/transact! store "host" {:event :start :id id :revision 0})
        (let [drawing (get-in @(:rooms store) [id :state :draw]) card (first (draw/legal-cards drawing 0))]
          (s/transact! store "host" {:event :draw-pick :id id :revision 1 :player 0 :wave (:wave drawing) :card card}))
        (let [restored (s/make-store path)]
          (is (= @(:rooms store) @(:rooms restored)))
          (is (= (s/private-draws store "host") (s/private-draws restored "host")))
          (is (empty? (get (s/private-draws restored "spectator") id)))))
      (let [store (s/make-store path) id (create store :local)
            state (-> (cards/initialize (a/new-game ["Ada" "Ben"] (vec (mapcat identity a/stages)) 0))
                      (assoc-in [:players 0 :hand] {:minor [(rules/id :a002)] :occupation []}))]
        (with-redefs [s/start-state (fn [_ _] state)]
          (s/transact! store "host" {:event :start :id id :revision 0}))
        (s/transact! store "host" {:event :command :id id :revision 1 :command {:type :action :space :meeting :card (rules/id :a002)}})
        (let [restored (s/make-store path)]
          (is (= @(:rooms store) @(:rooms restored)))
          (s/transact! restored "host" {:event :command :id id :revision 2 :command {:type :resolve :decision 0 :cell 4}})
          (is (= :field (get-in @(:rooms restored) [id :state :players 0 :farm 4 :type])))
          (is (= 1 (get-in @(:rooms restored) [id :state :turn])))))
      (finally (.delete (java.io.File. path))))))

(def non-base-b-packs (mapv :id (remove #(= "base-b" (:id %)) s/card-packs)))

(deftest excluded-packs-filter-real-draws-and-deals
  (is (= {:mode :draw :extra 2 :excluded-packs ["base-a" "five-player"]}
         (s/valid-setup {:mode :draw :excluded-packs ["five-player" "base-a" "base-a"]})))
  (is (thrown? clojure.lang.ExceptionInfo (s/valid-setup {:mode :draw :excluded-packs ["unimplemented-pack"]})))
  (let [draft (s/make-draw 2 2 non-base-b-packs)
        ids (mapcat identity (:packets draft))
        game (s/start-state ["Ada" "Ben"] {:mode :random :excluded-packs non-base-b-packs})
        hands (mapcat #(concat (get-in % [:hand :minor]) (get-in % [:hand :occupation])) (:players game))]
    (is (= 32 (count ids)))
    (is (every? #(= "base-b" (s/card-pack (rules/card %))) ids))
    (is (= 28 (count hands)))
    (is (every? #(= "base-b" (s/card-pack (rules/card %))) hands)))
  (let [draft (s/make-draw 5 2 ["five-player"])]
    (is (= 80 (count (mapcat identity (:packets draft)))))
    (is (not-any? rules/five-card? (mapcat identity (:packets draft)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Include more packs" (s/make-draw 4 2 non-base-b-packs)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reduce extra cards" (s/make-draw 2 10 non-base-b-packs)))
  (is (thrown? clojure.lang.ExceptionInfo (s/start-state ["Ada"] {:mode :random :excluded-packs (mapv :id s/card-packs)}))))

(deftest pack-settings-are-host-only-and-reset-readiness
  (let [store (s/make-store nil)
        id (:id (s/transact! store "host" {:event :create :name "Ada" :mode :online :expected-players 2 :setup {:mode :draw}}))
        send! (fn [token msg] (s/transact! store token (assoc msg :id id :revision (get-in @(:rooms store) [id :revision]))))
        message {:event :configure :expected-players 2 :setup {:mode :draw :extra 2 :excluded-packs ["base-a"]}}]
    (send! "guest" {:event :join :name "Ben"})
    (is (thrown? clojure.lang.ExceptionInfo (send! "guest" message)))
    (send! "host" message)
    (is (= ["base-a"] (get-in (model/pull-game @(:conn store) id) [:setup :excluded-packs])))
    (is (false? (get-in @(:rooms store) [id :seats 1 :ready?])))
    (send! "guest" {:event :ready :ready? true})
    (send! "host" {:event :start})
    (is (not-any? #(= "base-a" (s/card-pack (rules/card %))) (mapcat identity (get-in @(:rooms store) [id :state :draw :packets]))))
    (is (thrown? clojure.lang.ExceptionInfo (send! "host" message)))))

(deftest paper-knife-random-stream-never-enters-live-projections
  (let [store (s/make-store nil)
        id (:id (s/transact! store "host" {:event :create :mode :local :name "Ada" :names ["Ada" "Ben"]
                                           :setup {:mode :random}}))]
    (s/transact! store "host" {:event :start :id id :revision 0})
    (is (pos? (get-in @(:rooms store) [id :state :card-rng])) "The authoritative game persists its random stream")
    (doseq [token ["host" "guest" "spectator"]]
      (let [datoms (:datoms (s/snapshot store token))
            client (d/db-with (d/empty-db model/schema) (model/datoms->tx datoms))]
        (is (not-any? #(= :agricola.game/card-rng (second %)) datoms))
        (is (nil? (get-in (model/pull-game client id) [:state :card-rng])))))
    (let [occupations (mapv rules/id [:a103 :a104 :a116])
          state (-> (get-in @(:rooms store) [id :state])
                    (assoc :card-rng 13 :turn 0)
                    (assoc-in [:players 0 :hand] {:minor [(rules/id :a003)] :occupation occupations})
                    (update-in [:players 0] a/gain {:wood 1}))
          room (-> (get @(:rooms store) id) (assoc :state state) (dissoc :recording))]
      (swap! (:rooms store) assoc id room)
      (d/transact! (:conn store) (model/game-tx room))
      (let [play (s/transact! store "host" {:event :command :id id :revision 1
                                           :command {:type :action :space :meeting :card (rules/id :a003)}})
            decision (get-in @(:rooms store) [id :state :decision-id])
            resolve (s/transact! store "host" {:event :command :id id :revision 2
                                              :command {:type :resolve :decision decision :cards occupations}})]
        (is (not= 13 (get-in @(:rooms store) [id :state :card-rng])))
        (doseq [update [play resolve (s/snapshot store "spectator")]]
          (is (not-any? #(= :agricola.game/card-rng (second %)) (:datoms update))))
        (is (nil? (get-in (model/pull-game @(:conn store) id) [:state :card-rng])))))))
