(ns agricola.utils
  (:require
   [datascript.core :as d]
   [clojure.data.csv :as csv]
   [clojure.java.io :as io])
  #?(:clj
     (:import [java.util UUID]
              [java.security MessageDigest]
              [java.math BigInteger])))

(defn insert-optional [& {:keys [title tx]}]
  tx)

(defn add-resource [entity resource n]
  [(d/datom (:db/id entity) resource (+ (get entity resource 0) n))])

(defn add-food [entity n-food]
  (add-resource entity :agricola.resource/food n-food))

(defn add-grain [entity n-grain]
  (add-resource entity :agricola.resource/grain n-grain))

(defn add-wood [entity n-grain]
  (add-resource entity :agricola.resource/wood n-grain))

(defn remove-grain [entity n-grain]
  (add-resource entity :agricola.resource/grain (- n-grain)))

(defn add-fields [entity n-fields]
  (add-resource entity :agricola.resource/field n-fields))

(defn remove-fields [entity n-fields]
  (add-resource entity :agricola.resource/field (- n-fields)))

(defn add-vegetables [entity n-vegetables]
  (add-resource entity :agricola.resource/vegetable n-vegetables))

(defn remove-vegetables [entity n-vegetables]
  (add-resource entity :agricola.resource/vegetable (- n-vegetables)))

(defn assoc-entity [entity attr value]
  [(d/datom (:db/id entity) attr value)])

(defn move-resources [from to]
  (let [a (:agricola.space/resources from)]
    (conj (vec (for [id (map :db/id a)]
                 [:db/add (:db/id to) :agricola.space/resources id]))
          [:db/retract (:db/id from) :agricola.space/resources])))


(defn take-action? [event]
  (:agricola.event/action event))

(defn player-take-action? [event player-id]
  (when-let [action (take-action? event)]
    (let [player (:agricola.action/player action)]
      (and (= (:db/id player) player-id) action))))

(defn player-take-grain? [event player-id]
  (when-let [action (player-take-action? event player-id)]
    (= (:eurozone.event/name action) :action/take-grain)))

(defn end-of-round? [event]
  (= (:eurozone.event/name event) :events/end-of-round))

(defn new-round? [event]
  (= (:eurozone.event/name event) :events/new-round))

(defn get-owner [entity]
  (:agricola.bit/owner entity))

(defn get-workers [entity]
  (:agricola.player/workers entity))

(defn get-square [entity]
  (:agricola.bit/square entity))

(defn get-title [entity]
  (map :agricola.bit/title entity))

(defn get-bits [entity]
  (:agricola.bit/children entity))

(defn get-game [event]
  (:eurozone.event/game event))

(defn get-all-games [event]
  (let [db (d/entity-db event)
        game-ids (d/q '[:find [?game-id ...]
                        :in $
                        :where
                        [?game-id :agricola.game/players]]
                       db)]
    (for [game-id game-ids]
      (d/entity db game-id))))

(defn get-board [game]
  (:agricola.game/board game))

(defn get-players [game]
  (:agricola.game/players game))

(defn get-current-player [game]
  (:agricola.game/current-player game))

(defn get-actions [board]
  (:agricola.board/actions board))

(defn is-acc-resource? [])

(defn get-active-effects [game])

(defn get-acc-type [accumulator]
  (:agricola.accumulator/type accumulator))

(defn get-acc-increment [accumulator]
  (:agricola.accumulator/increment accumulator))

(defn get-acc-quantity [accumulator]
  (:agricola.accumulator/quantity accumulator))

(defn get-accumulators [bit]
  (:agricola.bit/accumulators bit))

(defn has-acc-resources? [action]
  (:agricola.action/acc-resources action))

(defn get-resource-name [resource]
  (:agricola.resource/name resource))

(defn get-resource-quantity [resource]
  (:agricola.resource/quantity resource))

(defn get-game-bit [event game-bit-name]
  (let [game (:eurozone.event/game event)
        bits (:agricola.game/bits game)]
    (get bits game-bit-name)))

(defn get-current-player [game]
  (:agricola.game/current-player game))

(defn get-next-round [round]
  (:agricola.round/next-round round))

(defn get-current-game-round [game]
  (-> game
      (:agricola.game/current-stage)
      (:agricola.stage/current-round)))

(defn get-current-player [round]
  (:agricola.round/current-player round))

(defn get-resources [entity]
  (:agricola.entity/resources entity))

(defn get-next-player [player]
  (:agricola.player/next-player player))

(defn get-home-room [worker]
  (:agricola.worker/room worker))

(defn action-taken? [action]
  (:agricola.action/is-taken action))

(defn get-available-actions [game]
  (let [board (get-board game)
        actions (get-actions board)
        untaken-actions (remove action-taken? actions)]
    untaken-actions))

(defn get-occupations [player]
  (:agricola.player/occupations player))

(defn get-chosen-occupation [action]
  (:agricola.action/occupation action))

(defn get-player [event]
  (let [game (get-game event)
        players (:agricola.game/players game)
        username (:eurozone.event/username event)]
    (first
     (filter #(= username (:agricola.player/name %1)) players))))

(defonce tempids (atom -1000000000))

(defn next-tempid! []
  (swap! tempids dec))

(defn parse-csv-file [file-path]
  (with-open [reader (io/reader file-path)]
    (doall (csv/read-csv reader))))

(defn csv->maps
  ([csv-data header]
   (let [fs (map second header)
         ks (map first header)]
     (mapv (fn [row] (zipmap ks (map #(%1 %2) fs row)))
           (next csv-data)))))

(defn hash-username-password [username password]
  (let [input-str (str username ":" password)
        md (MessageDigest/getInstance "SHA-256")]
    (.update md (.getBytes input-str "UTF-8"))
    (let [digest (.digest md)
          big-int (BigInteger. 1 digest)])))

(defn try-parse-float [val]
  (try
    (Float/parseFloat val)
    (catch Exception e
      val)))

(defn try-parse-int [val]
  (try
    (Integer/parseInt val)
    (catch Exception e
      val)))
