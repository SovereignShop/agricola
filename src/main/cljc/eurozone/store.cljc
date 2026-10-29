(ns eurozone.store
  (:require [agricola.engine :as agricola] [agricola.draw :as draw] [agricola.card-game :as card-game] [agricola.card-rules :as cards] [eurozone.model :as model]
            [agricola.recording :as recording] [eurozone.table-flow :as flow]
            #?(:clj [agricola.simulation :as simulation])
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn]) #?(:clj [clojure.java.io :as io]) [clojure.string :as str]
            [datascript.core :as d])
  #?(:cljs (:require-macros [eurozone.build :refer [rules-hash]]))
  #?(:clj (:import [java.nio.file Files StandardCopyOption])))

(defn make-store [path]
  (let [saved #?(:clj (if (and path (.exists (io/file path))) (edn/read-string (slurp path)) {}) :cljs {})
        conn (d/create-conn model/schema)]
    (doseq [room (vals saved)] (d/transact! conn (model/game-tx room)))
    {:path path :rooms (atom saved) :conn conn :lock #?(:clj (Object.) :cljs nil)}))
(defn save! [{:keys [path]} rooms]
  #?(:clj (when path
    (let [file (.toPath (.getAbsoluteFile (io/file path))) parent (.getParent file)]
      (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0))
      (let [tmp (Files/createTempFile parent "eurozone-" ".edn" (make-array java.nio.file.attribute.FileAttribute 0))]
        (try
          (spit (.toFile tmp) (pr-str rooms))
          (Files/move tmp file (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
          (finally (Files/deleteIfExists tmp)))))) :cljs nil))
(defn valid-name [s]
  (agricola/require! (and (string? s) (<= 1 (count (str/trim s)) 40)) "Names must have 1–50 characters.")
  (str/trim s))
(defmulti new-state (fn [game-type _] game-type))
(defmulti apply-command (fn [game-type _ _ _] game-type))
(defmethod new-state :agricola [_ names] (agricola/new-game names (vec (mapcat shuffle agricola/stages)) (rand-int (count names))))
(defmethod apply-command :agricola [_ state pid command] ((if (= :cards (:variant state)) card-game/command agricola/command) state pid command))
(def card-packs
  [{:id "base-a" :label "Base game · A cards" :min-players 1}
   {:id "base-b" :label "Base game · B cards" :min-players 1}
   {:id "five-player" :label "5–6 Player Expansion" :min-players 5}])
(defn card-pack [card]
  (if (cards/five-card? (:id card)) "five-player" (str "base-" (str/lower-case (:deck card)))))
(defn valid-setup [settings]
  (let [{:keys [mode extra excluded-packs] :or {mode :beginner extra 2}} settings]
    (agricola/require! (#{:beginner :random :draw} mode) "Choose a valid hand setup.")
    (agricola/require! (and (integer? extra) (<= 0 extra 10)) "Choose 0–10 extra cards for the draw.")
    (agricola/require! (and (or (nil? excluded-packs) (sequential? excluded-packs))
                           (every? (set (map :id card-packs)) excluded-packs)) "Choose only supported card packs.")
    (cond-> {:mode mode :extra extra} (seq excluded-packs) (assoc :excluded-packs (vec (sort (set excluded-packs)))))))
(defn with-bots [room n]
  (let [humans (vec (remove :bot? (:seats room))) expected (or (:expected-players room) 5)]
    (agricola/require! (and (integer? n) (<= 0 n 4) (< n expected)) "Choose fewer bots than total players, leaving at least one human seat.")
    (agricola/require! (or (zero? n) (= :online (:mode room))) "Bots need a peer-hosted table.")
    (agricola/require! (<= (+ (count humans) n) expected) "There are already too many people seated for that many bots.")
    (assoc room :seats
      (into humans
        (for [i (range n)
              :let [base (str "Bot " (inc i)) names (set (map :name humans))
                    name (first (remove names (cons base (map #(str base " (" % ")") (range 2 8)))))] ]
          {:name name :owner (str "bot:" (:id room) ":" i) :bot? true :ready? true})))))
(defn hand-pool
  ([n] (hand-pool n []))
  ([n excluded] (filterv #(and (<= (:min-players %) n) (not ((set excluded) (card-pack %)))) (vals cards/cards))))
(defn pool-error [n settings]
  (when (not= :beginner (:mode settings))
    (let [pool (hand-pool n (:excluded-packs settings)) counts (frequencies (map :kind pool))
          needed (* 7 n) total (* n (+ 14 (if (= :draw (:mode settings)) (:extra settings 2) 0)))]
      (cond
        (< (get counts :occupation 0) needed) (str "Include more packs: " needed " occupations are needed; " (get counts :occupation 0) " are available.")
        (< (get counts :minor 0) needed) (str "Include more packs: " needed " minor improvements are needed; " (get counts :minor 0) " are available.")
        (< (count pool) total) (str "Include more packs or reduce extra cards: the draw needs " total " cards; " (count pool) " are available.")))))
(defn make-draw
  ([n extra] (make-draw n extra []))
  ([n extra excluded]
   (let [settings {:mode :draw :extra extra :excluded-packs excluded}
         _ (agricola/require! (nil? (pool-error n settings)) (pool-error n settings))
         pool (hand-pool n excluded)
         by-type (into {} (for [kind draw/kinds] [kind (vec (shuffle (filter #(= kind (:kind %)) pool)))]))
         required (mapcat #(take (* 7 n) (by-type %)) draw/kinds)
         remaining (shuffle (mapcat #(drop (* 7 n) (by-type %)) draw/kinds))
         supply (vec (shuffle (concat required (take (* extra n) remaining))))]
     (draw/new-draw n extra supply))))
(defn deal-hands
  ([state n] (deal-hands state n []))
  ([state n excluded]
   (let [settings {:mode :random :excluded-packs excluded}
         _ (agricola/require! (nil? (pool-error n settings)) (pool-error n settings))
         by-kind (into {} (for [kind draw/kinds]
                          [kind (vec (shuffle (filter #(= kind (:kind %)) (hand-pool n excluded))))]))]
     (update state :players
             (fn [players]
               (mapv (fn [p]
                       (assoc p :hand
                              (into {} (for [kind draw/kinds]
                                         [kind (mapv :id (take 7 (drop (* 7 (:id p)) (by-kind kind))))]))))
                     players))))))
(defn start-state [names settings]
  (let [settings (valid-setup settings) n (count names)
        _ (agricola/require! (nil? (pool-error n settings)) (pool-error n settings))
        excluded (:excluded-packs settings)
        state (cond-> (new-state :agricola names)
                (= n 5) (assoc :minor-order (vec (shuffle (map :id (filter #(= :minor (:kind %)) (hand-pool n excluded)))))))
        mode (:mode settings)]
    (case mode
      :draw (assoc state :phase :draw :round 0 :variant :cards :active [] :piles {} :log [] :draw (make-draw n (:extra settings) excluded))
      :random (card-game/initialize (deal-hands state n excluded))
      state)))
(defn finish-draw [state]
  (if (= :complete (get-in state [:draw :status]))
    (let [hands (get-in state [:draw :selected])]
      (-> state
          (update :players #(mapv (fn [p] (assoc p :hand (get hands (:id p)))) %))
          card-game/initialize))
    state))
(defn permissions [store token]
  (into {} (for [[id room] @(:rooms store)]
             [id {:host? (= token (:host room))
                  :seats (vec (keep-indexed #(when (= token (:owner %2)) %1) (:seats room)))}])))
(defn private-view [store token]
  ;; Hidden hands never enter the public DataScript connection or its deltas.
  (into {} (for [[id room] @(:rooms store)]
             [id (into {} (for [[pid seat] (map-indexed vector (:seats room))
                               :when (= token (:owner seat))]
                           [pid (get-in room [:state :players pid :hand])]))])))
(defn private-draws [store token]
  (into {} (for [[id room] @(:rooms store) :when (get-in room [:state :draw])]
             [id (into {} (for [[pid seat] (map-indexed vector (:seats room)) :when (= token (:owner seat))]
                           [pid (draw/private-view (get-in room [:state :draw]) pid)]))])))
(defn private-plans [store token]
  (into {} (for [[id room] @(:rooms store)]
             [id (into {} (for [[pid plan] (:harvest-plans room)
                               :when (flow/owns? room token pid)] [pid plan]))])))
(defn snapshot [store token]
  (#?(:clj locking :cljs do) (:lock store)
    {:event :snapshot :server-time (flow/now) :datoms (mapv (juxt :e :a :v :tx :added) (d/datoms @(:conn store) :eavt)) :permissions (permissions store token) :private-hands (private-view store token) :private-draws (private-draws store token) :private-plans (private-plans store token)}))
(def engine-fingerprint (delay #?(:clj (simulation/rules-hash) :cljs (rules-hash))))
(defn recording-header [room state partial-start?]
  {:record :header :format 1 :initial state :hash (recording/state-hash state)
   :rules-hash @engine-fingerprint :options (:setup room) :partial-start? partial-start?})
(defn recorded-command [room pid command]
  (let [before (:state room) effects (atom [])
        after (binding [card-game/*effect-observer* #(swap! effects conj %)]
                (recording/apply-command before pid command))
        records (or (:recording room) [(recording-header room before true)])
        index (dec (count records))
        records (conj records {:record :move :index index :player pid :round (:round before) :phase (:phase before)
                               :command command :effects @effects :hash (recording/state-hash after)})]
    (assoc room :state after :history (vec (take-last 100 (conj (:history room) before))) :recording
           (cond-> records
             (= :finished (:phase after))
             (conj {:record :result :moves (inc index) :scores (:scores after) :winners (:winners after)
                    :hash (recording/state-hash after)})))))
(defn undo-recording [room state]
  (let [hash (recording/state-hash state)
        i (last (keep-indexed #(when (and (= hash (:hash %2)) (not= :result (:record %2))) %1) (:recording room)))]
    (if (some? i) (subvec (:recording room) 0 (inc i)) [(recording-header room state true)])))
(defn restore-previous [room]
  (let [previous (peek (:history room))]
    (assoc room :state previous :history (pop (:history room)) :recording (undo-recording room previous)
           :harvest-plans {} :clocks {})))
(defn settle-vote [room]
  (if-let [result (flow/verdict room)]
    (let [{:keys [history-index record-index]} (:takeback-target room)
          finished (flow/finish-vote room result)]
      (if (= result :approved)
        (if (some? history-index)
          (assoc finished :state (nth (:history room) history-index)
                 :history (subvec (:history room) 0 history-index)
                 :recording (subvec (:recording room) 0 record-index) :harvest-plans {} :clocks {})
          (restore-previous finished))
        finished))
    room))
(defn drain-plans [room]
  (loop [room (flow/trim-plans room) n 0]
    (let [s (:state room) pid (card-game/acting-player s)
          kind (case (:phase s) :feeding :feed :breeding :breed nil)
          command (get-in room [:harvest-plans pid kind])]
      (if (or (:takeback room) (nil? command) (seq (:effect-queue s)) (>= n 20)) room
        (let [cleared (update-in room [:harvest-plans pid] dissoc kind :error)
              result (try {:room (recorded-command cleared pid command)}
                          (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                            {:error #?(:clj (.getMessage e) :cljs (.-message e))}))]
          (if (:error result)
            (assoc-in cleared [:harvest-plans pid :error] (str "Your queued decision needs review: " (:error result)))
            (recur (flow/trim-plans (:room result)) (inc n))))))))
(defn export-recording [store token id]
  (#?(:clj locking :cljs do) (:lock store)
    (let [room (get @(:rooms store) id)]
      (agricola/require! (and room (:state room) (= token (:host room))) "Only the host can export this recording.")
      (agricola/require! (or (= :local (:mode room)) (= :finished (get-in room [:state :phase])))
                        "Online recordings can be exported after the game finishes, to keep hands private.")
      {:event :recording :filename (str "agricola-" id ".edn")
       :text (apply str (map #(str (pr-str %) "\n")
                            (or (:recording room) [(recording-header room (:state room) true)])))})))
(defn transition [rooms token {:keys [event id revision name title mode names command setup expected-players bots ready? player wave card] :as msg}]
  (if (= event :create)
    (let [id (str (random-uuid)) mode (or mode :online) name (valid-name name)
          _ (agricola/require! (#{:online :local} mode) "Choose online or pass-and-play mode.")
          names (if (= mode :local) (mapv valid-name names) [name])
          _ (agricola/require! (and (<= 1 (count names) 5) (= (count names) (count (set names)))) "Choose 1–5 distinct player names.")
          setup (valid-setup setup)
          _ (agricola/require! (or (nil? expected-players) (and (integer? expected-players) (<= 1 expected-players 5))) "Choose 1–5 expected players.")
          room {:id id :type :agricola :title (valid-name (or title (str name "'s farm")))
                :host token :mode mode :revision 0 :setup setup :turn-seconds (flow/valid-limit (get msg :turn-seconds 0))
                :expected-players (if (= mode :local) (count names) expected-players)
                :seats (mapv #(hash-map :name % :owner token :ready? true) names) :history []}
          room (with-bots room (or bots 0))]
      [(assoc rooms id room) room])
    (let [room (get rooms id)]
      (agricola/require! room "This table does not exist.")
      (agricola/require! (or (#{:draw-pick :harvest-plan :cancel-harvest-plan :takeback-vote :cancel-takeback :expire-takeback} event)
                            (= revision (:revision room))) "The table changed. Your view has been refreshed; try again.")
      (agricola/require! (or (nil? (:takeback room)) (#{:takeback-vote :cancel-takeback :expire-takeback} event))
                        "Play is paused while the takeback vote is open.")
      (let [state (:state room) host? (= token (:host room))
            changed
            (case event
              :configure (do
                           (agricola/require! host? "Only the table host can change settings.")
                           (agricola/require! (and (nil? state) (= :online (:mode room))) "Settings can only change before an online game starts.")
                           (agricola/require! (and (integer? expected-players) (<= 1 expected-players 5)) "Choose 1–5 expected players.")
                           (let [settings (valid-setup setup) n (if (contains? msg :bots) bots (count (filter :bot? (:seats room))))
                                 seconds (flow/valid-limit (get msg :turn-seconds (get room :turn-seconds 0)))
                                 configured (with-bots (assoc room :expected-players expected-players :setup settings :turn-seconds seconds) n)]
                             (if (= [expected-players settings n seconds] [(:expected-players room) (:setup room) (count (filter :bot? (:seats room))) (get room :turn-seconds 0)]) room
                               (update configured :seats #(mapv (fn [seat] (assoc seat :ready? (or (true? (:bot? seat)) (= token (:owner seat))))) %)))))
              :join (do (agricola/require! (and (= :online (:mode room)) (nil? state)) "This table is no longer open for joining.")
                        (agricola/require! (< (count (:seats room)) (or (:expected-players room) 5)) "This table is full.")
                        (agricola/require! (not-any? #(or (= token (:owner %)) (= (valid-name name) (:name %))) (:seats room)) "You already have a seat, or that name is taken.")
                        (update room :seats conj {:name (valid-name name) :owner token :ready? true}))
              :ready (do (agricola/require! (nil? state) "Readiness is set before the game starts.")
                         (agricola/require! (boolean? ready?) "Choose ready or not ready.")
                         (let [pid (first (keep-indexed #(when (= token (:owner %2)) %1) (:seats room)))]
                           (agricola/require! (some? pid) "Take a seat first.")
                           (assoc-in room [:seats pid :ready?] ready?)))
              :leave (do (agricola/require! (and (nil? state) (not host?) (= :online (:mode room))) "Only a guest can leave an unstarted online table.")
                         (agricola/require! (some #(= token (:owner %)) (:seats room)) "You do not have a seat at this table.")
                         (update room :seats #(vec (remove (fn [seat] (= token (:owner seat))) %))))
              :start (do (agricola/require! host? "Only the table host can start the game.")
                         (agricola/require! (nil? state) "This game has already started.")
                         (agricola/require! (or (nil? (:expected-players room)) (= (:expected-players room) (count (:seats room)))) "Wait until all expected players have joined.")
                         (agricola/require! (every? #(get % :ready? true) (:seats room)) "Wait until everyone is ready.")
                         (let [state (start-state (mapv :name (:seats room)) (or (:setup room) {:mode :beginner :extra 2}))]
                           (assoc room :state state :recording [(recording-header room state false)])))
              :draw-pick (do (agricola/require! (= :draw (:phase state)) "This table is not drawing cards.")
                             (agricola/require! (and (integer? player) (<= 0 player) (< player (count (:seats room))) (= token (get-in room [:seats player :owner]))) "You can only choose cards for your own seat.")
                             (flow/require-time room player)
                             (recorded-command room player {:type :pick :wave wave :card card}))
              :bot-command (do
                             (agricola/require! (and host? state (integer? player) (true? (get-in room [:seats player :bot?]))) "Only the host can run an automated seat.")
                             (agricola/require! (if (= :draw (:phase state))
                                                 (and (= :pick (:type command)) (contains? (get-in state [:draw :pending]) player))
                                                 (= player (card-game/acting-player state))) "Wait for the bot's turn.")
                             (recorded-command room player command))
              :timeout-command (do
                                 (agricola/require! (and host? (some #{player} (flow/actors room))
                                                        (= (:clock-id msg) (get-in room [:clocks player :id]))
                                                        (<= (get-in room [:clocks player :deadline] ##Inf) (flow/now)))
                                                   "That decision clock has not expired or has changed.")
                                 (assoc (recorded-command room player command) :last-timeout {:player player :at (flow/now)}))
              :harvest-plan (flow/plan room token msg)
              :cancel-harvest-plan (do
                                     (agricola/require! (and (flow/owns? room token player) (= (:round msg) (:round state))) "This harvest has changed.")
                                     (update room :harvest-plans dissoc player))
              :request-takeback (flow/request-takeback room token player)
              :takeback-vote (flow/vote room token player (:vote-id msg) (:approve? msg))
              :cancel-takeback (do
                                (agricola/require! (and (= (:vote-id msg) (get-in room [:takeback :id]))
                                                       (flow/owns? room token (get-in room [:takeback :requester]))) "Only the requester can cancel this vote.")
                                (flow/finish-vote room :cancelled))
              :expire-takeback (do
                                (agricola/require! (and host? (= (:vote-id msg) (get-in room [:takeback :id])) (flow/expired-vote? room)) "This vote has not expired.")
                                (flow/finish-vote room :expired))
              :command (let [actor (if (= :cards (:variant state)) (card-game/acting-player state) (:turn state))
                             pid (if (and (= :cards (:variant state)) (#{:ability :convert} (:type command))) (get command :player actor) actor)]
                         (agricola/require! state "Start the game first.")
                         (agricola/require! (= token (get-in room [:seats pid :owner])) "It is another player's turn.")
                         (flow/require-time room pid)
                         (recorded-command room pid command))
              :undo (do (agricola/require! (and host? (= :local (:mode room))) "Undo is available to the host in pass-and-play games.")
                        (agricola/require! (seq (:history room)) "There is no move to undo.")
                        (restore-previous room))
              (throw (ex-info "Unknown event." {:type :illegal-command})))
            changed (-> changed settle-vote drain-plans flow/refresh-clocks (update :revision inc))]
        [(assoc rooms id changed) changed]))))
(defn transact! [store token msg]
  ;; Serialize authorization, version checks, durable write and publication.
  (#?(:clj locking :cljs do) (:lock store)
    (let [[rooms room] (transition @(:rooms store) token msg)
          db @(:conn store)
          previous (d/pull db '[* {:eurozone.game/players [* {:eurozone.player/cells [*]}]}]
                           [:eurozone.game/id (:id room)])
          ;; Replace the entire projection so absent attributes (e.g. crops after
          ;; undo) are retracted as well. Stable domain IDs remain lookup refs.
          entities (concat (mapcat :eurozone.player/cells (:eurozone.game/players previous))
                           (:eurozone.game/players previous) (when (:db/id previous) [previous]))
          tx (into (mapv #(vector :db/retractEntity (:db/id %)) entities) (model/game-tx room))
          report (d/with db tx)]
      (save! store rooms)
      (reset! (:rooms store) rooms)
      (d/reset-conn! (:conn store) (:db-after report))
      {:id (:id room) :datoms (mapv (juxt :e :a :v :tx :added) (:tx-data report))})))
