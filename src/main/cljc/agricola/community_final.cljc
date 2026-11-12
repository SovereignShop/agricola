(ns agricola.community-final
  "Rules for the final Original G/WM/FR cards. Choices remain persisted engine data."
  (:require [agricola.engine :as a] [agricola.card-rules :as cards]
            [agricola.action-board :as board]))

(def witches :catalog/original-expansion-fr-59-witches-dance-ground)
(defn witches-choices [p placement]
  (let [farm (set (a/farm-cells p))
        cells (if (= :cover placement) (filter #(a/empty-cell? p %) farm) (remove farm a/all-cells))
        adjacent (set (mapcat a/neighbors farm))]
    (for [one cells two (a/neighbors one)
          :when (and (< one two) (some #{two} cells)
                     (or (= :cover placement) (adjacent one) (adjacent two)))]
      {:placement placement :cells [one two]})))
(defn place-witches [p placement cells]
  (a/require! (and (#{:cover :extend} placement)
                   (some #(= (set cells) (set (:cells %))) (witches-choices p placement)))
              "Cover two adjacent unused farm spaces, or place two adjacent new spaces beside your farm.")
  (let [farm (vec (take (max (count (:farm p)) (inc (apply max cells))) (concat (:farm p) (repeat {:type :removed}))))]
    (-> (if (= placement :extend) (update p :bonus-points (fnil inc 0)) p)
        (assoc :farm (reduce #(assoc %1 %2 (if (= placement :cover) {:type :removed :card witches} {:type :empty :extension-card witches})) farm cells))
        (assoc-in [:card-state witches] {:placement placement :cells (vec cells)}))))
(def village-school :catalog/original-expansion-wm-112-village-school)
(defn school-available? [g pid]
  (let [p (get-in g [:players pid])]
    (and (cards/has? p village-school) (= :work (:phase g)) (= pid (:turn g))
         (not= [(:round g) (:turn-serial g 0)] (get-in p [:card-state village-school :used-turn]))
         (nil? (:school-voucher p)) (seq (concat (get-in p [:hand :minor]) (get-in p [:hand :occupation]))))))
(defn prepare-school [g pid cmd]
  (let [choice (merge (:action-command cmd) cmd) cid (:school-card choice) resource (:school-resource choice)
        p (get-in g [:players pid]) kind (:kind (cards/card cid))]
    (if-not cid g
      (do (a/require! (and (school-available? g pid) (some #{cid} (get-in p [:hand kind]))
                           (#{:wood :clay :reed :stone} resource) (not (:skip? cmd)))
                       "Village School discards a hand card for one building-resource discount, once during your turn.")
          (-> g (update-in [:players pid :hand kind] #(vec (remove #{cid} %)))
              (assoc-in [:players pid :private-card-state village-school :pending] cid)
              (assoc-in [:players pid :school-voucher] {:resource resource :turn [(:round g) (:turn-serial g 0)]}))))))
(defn finish-school [g]
  (update g :players
    (fn [players] (mapv (fn [p]
      (if-let [voucher (:school-voucher p)]
        (if (:used? voucher)
          (-> p (assoc-in [:card-state village-school :used-turn] (:turn voucher))
              (update-in [:private-card-state village-school :discarded] (fnil conj []) (get-in p [:private-card-state village-school :pending]))
              (update-in [:private-card-state village-school] dissoc :pending) (dissoc :school-voucher))
          (do (a/require! (seq (:effect-queue g)) "The selected Village School discount must reduce an actual cost.") p)) p)) players))))
(def mooch :catalog/original-expansion-g-41-mooch)
(defn refresh-construction [g]
  (if (some #(cards/has? % a/expert-builder-id) (:players g))
    (update g :players #(mapv (fn [p] (assoc p :track-room-cost? true)) %)) g))
(defn refresh-mooch [g]
  (update g :players
    (fn [players] (mapv (fn [p]
      (if-let [owner (when (cards/has? p mooch) (get-in p [:card-state mooch :owner]))]
        (assoc p :borrowed-food-improvements
               (vec (remove (set (a/owned-improvement-ids p))
                      (remove (get-in players [owner :pawned-improvements] #{})
                        (concat (get-in players [owner :improvements])
                                (filter #(#{:minor :major} (:kind (cards/card %))) (cards/active-owned (get players owner))))))))
        (dissoc p :borrowed-food-improvements))) players))))
(defn food-recipe? [{:keys [cost goods]}]
  (and (seq cost) (every? #{:wood :clay :reed :stone :grain :vegetable :sheep :boar :cattle} (keys cost))
       (number? (:food goods)) (pos? (:food goods)) (= #{:food} (set (keys goods)))))
(defn borrowed-ability? [p cid]
  (let [ability (:ability (cards/card cid))]
    (and (some #{cid} (:borrowed-food-improvements p)) (= :exchange-options (:op ability))
         (every? food-recipe? (:options ability)))))
(defn borrowed-food-effect [effect]
  (cond (and (= :exchange (:op effect)) (food-recipe? effect)) effect
        (= :bake (:op effect)) effect
        (= :choose (:op effect))
        (let [options (filterv #(and (seq (:effects %))
                                    (every? (fn [e] (and (= :exchange (:op e)) (food-recipe? e))) (:effects %))) (:options effect))]
          (when (seq options) (assoc effect :options options)))
        :else nil))
(def auctioneer :catalog/original-expansion-g-8-auctioneer)
(def writing-maniac :catalog/original-expansion-fr-120-writing-maniac)
(def auction-goods {7 {:reed 2} 9 {:wood 3} 11 {:clay 3} 13 {:stone 2}})
(defn writing-rounds [g p]
  (vec (remove #(contains? (get-in p [:private-card-state writing-maniac]) %)
               (filter #(> % (:round g)) [5 7 9 11]))))
(def sunday-worker :catalog/original-expansion-wm-48-sunday-worker)
(def debt-collector :catalog/original-expansion-fr-76-debt-collector)
(def early-riser :catalog/original-expansion-g-14-early-riser)
(def badger :catalog/original-expansion-fr-6-badger)
(def agrarian :catalog/original-expansion-fr-61-agrarian)
(def card-player :catalog/original-expansion-fr-68-card-player)
(def bathroom :catalog/original-expansion-fr-9-bathroom)
(def critic :catalog/original-expansion-g-19-food-critic)
(def astronomer :catalog/original-expansion-g-7-astronomer)
(def late-bloomer :catalog/original-expansion-g-36-late-bloomer)
(defn late-bloomer-available? [g pid]
  (let [p (get-in g [:players pid])]
    (and (< (:family p) 5) (> (a/family-capacity p) (:family p))
         (every? #(or (= pid (:id %)) (< (:family p) (:family %))) (:players g)))))
(defn task [pid cid effect context]
  {:kind :effect :player pid :card cid :effect effect :context context})
(defn prepend [g tasks] (update g :effect-queue #(vec (concat tasks %))))

(def baking-field :catalog/original-expansion-g-63-baking-field)
(def kitchen-table :catalog/original-expansion-fr-34-kitchen-table)
(defn bake-field-receipts [before after]
  (reduce
   (fn [state old]
     (let [pid (:id old) current (get-in state [:players pid])
           removed (- (get-in old [:card-fields baking-field :count] 0)
                      (get-in current [:card-fields baking-field :count] 0))
           received (- (get-in current [:received :grain] 0) (get-in old [:received :grain] 0))
           accounted (- (:baking-field-baked current 0) (:baking-field-baked old 0))
           n (max 0 (- (min removed received) accounted))]
       (if (and (cards/has? old baking-field) (pos? n))
         (let [baked (or (last (filter (fn [n] (try (a/bake current n) true
                                        (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false))) (range (inc n)))) 0)
               remaining (- n baked)
               player (-> current (a/bake baked) (a/pay {:grain remaining}) (a/gain {:food remaining})
                          (update :baking-field-baked (fnil + 0) n))]
           (cond-> (assoc-in state [:players pid] player)
             (pos? baked) (prepend [{:kind :event :event :bake :player pid
                                     :context {:grain-baked baked :bake-source-card baking-field}}]))) state)))
   after (:players before)))

(def unicycle :catalog/original-expansion-fr-115-unicycle-driver)
(defn roads [p] (get-in p [:card-state unicycle :roads] []))
(def mailman :catalog/original-expansion-wm-35-mailman)
(def mailman-packages [{:wood 2} {:clay 2} {:reed 1} {:stone 1}])
(defn mailman-offers [g space]
  (vec (for [p (:players g) :when (cards/has? p mailman)
             :let [goods (get-in p [:card-state mailman :packages space])] :when (seq goods)]
         {:owner (:id p) :goods goods :space space})))
(defn buy-mail [g pid {:keys [owner goods space paid?]}]
  (-> (if paid? g (update-in g [:players pid] a/pay {:food 1}))
      (update-in [:players owner] a/gain {:food 1})
      (update-in [:players pid] a/gain goods)
      (update-in [:players owner :card-state mailman :packages] dissoc space)))

(def musketeer :catalog/original-expansion-fr-95-musketeer)
(defn all-board-locations [g]
  (into (board/locations g) (for [round (range (inc (:round g)) 15)]
                             [[:round round] {:round round :rect (nth board/round-rects (dec round))}])))
(defn intersections [g]
  (into {} (for [[point corners] (group-by first
                         (for [[space {[x y w h] :rect}] (all-board-locations g)
                               point [[x y] [(+ x w) y] [x (+ y h)] [(+ x w) (+ y h)]]]
                           [point space]))
                 :when (= 4 (count corners))] [point (set (map second corners))])))
(defn intersection-position [g [px py :as point]]
  (let [locations (all-board-locations g)
        positions (for [space (get (intersections g) point)
                        :let [{[x y w h] :rect round :round} (locations space)
                              [left top width height] (or (get board/actions space)
                                  (when round (into (nth board/rounds (dec round)) [340 510]))
                                  (let [[left top width height] board/player-count-area]
                                    [(+ left (* (/ x 2) width)) (+ top (* (/ y 6) height))
                                     (* (/ w 2) width) (* (/ h 6) height)]))]]
                    [(+ left (if (= px x) 0 width)) (+ top (if (= py y) 0 height))])]
    (when (seq positions) [(double (/ (reduce + (map first positions)) (count positions)))
                           (double (/ (reduce + (map second positions)) (count positions)))])))

(defn board-neighbors [g space]
  (let [positions (all-board-locations g) [x y w h] (get-in positions [space :rect])]
    (into #{} (for [[id {[xx yy ww hh] :rect}] positions
                    :when (and x (not= id space)
                               (or (and (or (= (+ x w) xx) (= (+ xx ww) x)) (< (max y yy) (min (+ y h) (+ yy hh))))
                                   (and (or (= (+ y h) yy) (= (+ yy hh) y)) (< (max x xx) (min (+ x w) (+ xx ww))))))] id))))
(defn road-choices [g p]
  (let [used (set (mapcat identity (roads p)))]
    (when (pos? (a/fences-available p))
      (for [from (:active g) to (:active g)
            :when (and (neg? (compare (str from) (str to))) (not (used from)) (not (used to))
                       (contains? (board-neighbors g from) to))] {:spaces [from to]}))))
(defn road-targets [g pid from]
  (let [p (get-in g [:players pid])]
    (when (and (cards/has? p unicycle) (not= (:round g) (get-in p [:card-state unicycle :used-round])))
      (filterv #(and (some #{%} (:active g)) (not (contains? (:occupied g) %)))
               (mapcat #(when (some #{from} %) (remove #{from} %)) (roads p))))))
(defn road-origins [g pid]
  (for [[index source] (map-indexed vector (get-in g [:players pid :worker-spaces]))
        :when (seq (road-targets g pid source))]
    {:space source :worker-index index :relocation-card unicycle}))
(defn board-center [g space]
  (let [{:keys [round rect]} (get (all-board-locations g) space)
        [x y w h] (or (board/actions space) (when round (into (nth board/rounds (dec round)) [340 510]))
                      (when rect (let [[x y w h] rect [left top width height] board/player-count-area]
                                   [(+ left (* (/ x 2) width)) (+ top (* (/ y 6) height)) (* (/ w 2) width) (* (/ h 6) height)])))]
    (when x [(+ x (/ w 2)) (+ y (/ h 2))])))
(defn reveal-space-bonuses [g]
  (reduce (fn [state [space goods]]
            (if (vector? space)
              (if-let [id (first (for [[id loc] (board/locations g) :when (= (second space) (:round loc))] id))]
                (-> state (update :space-bonuses dissoc space) (update-in [:space-bonuses id] #(merge-with + (or % {}) goods))) state) state))
          g (:space-bonuses g)))
(defn plow-plans [p n]
  (if (zero? n) [[]]
    (for [cell (range (count (:farm p)))
          :let [after (try (a/place-tile p :field cell) (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))]
          :when after tail (plow-plans after (dec n))] (into [cell] tail))))
(defn pickaxe-spaces [g pid cid context]
  (when (and (not (get-in g [:players pid :card-state cid :used?]))
             (pos? (a/amount (get-in g [:players pid]) :wood))
             (seq (plow-plans (get-in g [:players pid]) 1)))
    (board-neighbors g (:space context))))
(defn lender-source [context]
  (into {} (filter (comp pos? val) (select-keys (:source-goods context) [:wood :clay :reed :stone]))))
(defn lender-goods [g pid context]
  (into {} (for [[r n] (lender-source context)
                :let [returned (+ (get-in context [:source-returned r] 0) (get-in g [:action-returned-goods (:action-id context) r] 0))
                      n (min (a/amount (get-in g [:players pid]) r) (max 0 (- n returned)))] :when (pos? n)] [r n])))

(def winter-garden :catalog/original-expansion-g-118-winter-garden)
(def permaculturist :catalog/original-expansion-wm-40-permaculturist)
(defn winter-cells [p] (filterv #(and (:winter-garden? (get-in p [:farm %])) (pos? (get-in p [:farm % :count] 0))) (a/tiles p :room)))
(defn permaculture-cells [p] (filterv #(get-in p [:farm % :permaculture?]) (range (count (:farm p)))))
(defn refresh-farm-fields [g]
  (update g :players
    #(mapv (fn [p]
             (let [pet? (or (cards/has? p :catalog/original-base-k-120-house-goat)
                            (some (fn [space] (and (= :pet (:kind space)) (pos? (:count space 0)))) (a/animal-housing p)))
                   clear-perma? (or (not (cards/has? p permaculturist)) (#{:scoring :finished} (:phase g)))
                   clear-winter? (or pet? (not (cards/has? p winter-garden)))]
               (update p :farm (fn [farm] (mapv (fn [cell]
                   (cond (and (:permaculture? cell) clear-perma?) {:type :empty}
                         (and (:winter-garden? cell) clear-winter?) (dissoc cell :crop :count :under-crops :winter-garden?)
                         :else cell)) farm))))) %)))

(def irrigation :catalog/original-expansion-g-85-irrigation-canal)
(defn irrigation-context [before after]
  {:new-fields (vec (remove (set (a/tiles before :field)) (a/tiles after :field)))
   :irrigation-sources (into {} (for [cell (a/tiles before :field)
                                    :let [goods (select-keys (a/field-crops (get-in before [:farm cell])) [:grain :vegetable])]
                                    :when (some pos? (vals goods))] [cell goods]))})
(defn irrigation-choices [p context]
  (for [target (:new-fields context) source (a/neighbors target)
        [crop n] (get-in context [:irrigation-sources source])
        :when (and (pos? n) (pos? (a/field-crop-count (get-in p [:farm source]) crop))
                   (a/can-sow-field? p target crop {:empty-only? true}))]
    {:cell target :from source :crop crop}))
(defn prepare-irrigation [p context plantings]
  (let [eligible (set (irrigation-choices p context))]
    (a/require! (and (seq plantings) (= (count plantings) (count (set (map :cell plantings)))) (every? eligible plantings))
                "For each newly plowed field, choose one crop from a previously planted adjacent field.")
    (reduce (fn [p {:keys [from crop]}] (-> p (a/remove-field-crop from crop) (update-in [:goods crop] (fnil inc 0)))) p plantings)))

(def remodeler :catalog/original-expansion-wm-42-remodeler)
(defn remodeler-available? [p] (and (cards/has? p remodeler) (= :wood (:house p)) (some :stable (:farm p))))
(defn remodeler-player [p stable room]
  (a/require! (and (integer? stable) (get-in p [:farm stable :stable])) "Return a stable from your farm.")
  (a/build-rooms (update-in p [:farm stable] dissoc :stable :stable-disabled?) [room] {} {:free? true}))
(defn remodeler-choices [g pid context]
  (let [p (get-in g [:players pid]) options (:renovation-choice context) modifiers (:renovation-effect context)]
    (when (remodeler-available? p)
      (for [stable (keep-indexed #(when (:stable %2) %1) (:farm p)) room (range (count (:farm p)))
            :when (try (a/renovate (remodeler-player p stable room) options modifiers) true
                       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false))]
        {:stable stable :cell room}))))

(defn badger-space [g pid]
  (let [space (get-in g [:players pid :card-state badger :badger-space])]
    (if (vector? space)
      (or (first (for [[id location] (board/locations g) :when (= (second space) (:round location))] id)) space)
      space)))
(defn badger-spaces [g pid]
  (let [positions (board/locations g)
        all-positions (into positions (for [round (range (inc (:round g)) 15)]
                                        [[:round round] {:rect (nth board/round-rects (dec round))}]))
        space (badger-space g pid)]
    (if space
      (let [[x y w h] (get-in all-positions [space :rect])]
        (into #{} (for [[id {[xx yy ww hh] :rect}] positions
                        :when (and x (not= id space)
                                   (or (and (or (= (+ x w) xx) (= (+ xx ww) x)) (< (max y yy) (min (+ y h) (+ yy hh))))
                                       (and (or (= (+ y h) yy) (= (+ yy hh) y)) (< (max x xx) (min (+ x w) (+ xx ww))))))] id)))
      (set (keys all-positions)))))

(defn line-length [g pid]
  (let [positions (board/locations g)
        occupied (set (keep #(get-in positions [% :rect])
                            (get-in g [:players pid :worker-spaces])))
        run (fn [[x y w h] dx dy]
              (count (take-while occupied (map #(vector (+ x (* % dx)) (+ y (* % dy)) w h) (range 6)))))]
    (reduce max 0 (for [[x y w h :as rect] occupied]
                    (max (run rect w 0) (run rect 0 h))))))

(defn downsize-rooms [p cid]
  (when (and (= :wood (:house p)) (not (get-in p [:card-state cid :used?])))
    (filterv #(and (not (#{5 10} %)) (a/connected? (remove #{%} (a/tiles p :room)))) (a/tiles p :room))))

(defn converted-items [head]
  (let [context (:context head)]
    (if (= :bake (:trigger-event context)) {:grain (:grain-baked context 0)}
        (:converted-goods context {}))))
(def input-ops #{:final-witches :final-mooch :final-road :final-auction-bid :final-writing-place :final-writing-arrive :final-badger :final-store-converted :final-bathroom-sow :final-personal-breed :final-downsize :final-expert-resource :final-musketeer-place :final-mailman-place :final-pickaxe :final-lender :final-irrigation :final-debt-schedule :final-debt-arrival :final-remodeler})
(def ops (into input-ops #{:final-mooch-expire :final-auction-schedule :final-auction-start :final-writing-start :final-badger-income :final-agrarian-offer :final-agrarian-deposit
                          :final-agrarian-end :final-agrarian-taken :final-card-player-start :final-card-player-rotate
                          :final-critic-fishing :final-astronomer :final-late-offer :final-late-grow :final-clear-worker :final-early-breeding :final-crane :final-expert-offer :final-musketeer-reward :final-mailman-buy :final-winter-harvest :final-sunday-gain}))
(defn choices [g {:keys [player card effect] :as head}]
  (let [p (get-in g [:players player])]
    (case (:op effect)
      :final-witches (concat (witches-choices p :extend) (witches-choices p :cover))
      :final-mooch (for [other (:players g) :when (not= player (:id other))] {:owner (:id other)})
      :final-road (road-choices g p)
      :final-auction-bid (map #(hash-map :amount %) (range (inc (a/amount p :food))))
      :final-writing-place (for [round (writing-rounds g p) cid (get-in p [:hand :occupation])] {:round round :card cid})
      :final-writing-arrive [{:decline? true} {}]
      :final-remodeler (remodeler-choices g player (:context head))
      :final-debt-schedule (map #(hash-map :round %) (range (+ 3 (:round g)) 15))
      :final-debt-arrival [{}]
      :final-irrigation (map #(hash-map :irrigations [%]) (irrigation-choices p (:context head)))
      :final-lender (for [resource (keys (lender-source (:context head)))
                          :let [goods (update (lender-goods g player (:context head)) resource (fnil inc 0))]]
                      {:resource resource :resources (vec (take (- 14 (:round g)) (mapcat (fn [[r n]] (repeat n r)) goods)))})
      :final-pickaxe (map #(hash-map :spaces [%]) (pickaxe-spaces g player card (:context head)))
      :final-mailman-buy [{}]
      :final-mailman-place (for [space (:active g) :when (not (contains? (get-in p [:card-state card :packages]) space))] {:space space})
      :final-musketeer-place (map #(hash-map :point %) (keys (intersections g)))
      :final-expert-resource (map #(hash-map :resource %) (:choices effect))
      :final-downsize (map #(hash-map :cell %) (downsize-rooms p card))
      :final-personal-breed (map #(hash-map :offspring %) (a/breeding-choices p))
      :final-late-grow (when (late-bloomer-available? g player) [{}])
      :final-badger (mapv #(hash-map :space %) (sort-by str (badger-spaces g player)))
      :final-store-converted (for [[r n] (converted-items head)
                                   :when (and (pos? n) (if (= card bathroom) (#{:grain :vegetable} r)
                                                          (and (or (not= r :grain) (= :bake (get-in head [:context :trigger-event])))
                                                               (not (pos? (get-in p [:card-state card :stored-goods r] 0))))))]
                              {:goods {r n}})
      :final-bathroom-sow (for [{:keys [cell crop]} (get-in head [:context :plantings])
                               :when (pos? (get-in p [:card-state bathroom :stored-goods crop] 0))]
                           {:cells [cell]})
      nil)))

(defn run [g {:keys [player card effect context] :as head} choice]
  (let [pid player p (get-in g [:players pid]) path [:players pid :card-state card]]
    (case (:op effect)
      :final-witches (update-in g [:players pid] place-witches (:placement choice) (:cells choice))
      :final-mooch
      (do (a/require! (and (integer? (:owner choice)) (<= 0 (:owner choice)) (< (:owner choice) (count (:players g))) (not= pid (:owner choice))) "Choose another player's improvements to borrow.")
          (refresh-mooch (assoc-in g (conj path :owner) (:owner choice))))
      :final-mooch-expire
      (-> g (update-in [:players pid :card-state card] dissoc :owner)
          (update-in [:players pid] dissoc :borrowed-food-improvements))
      :final-road
      (let [spaces (:spaces choice)]
        (a/require! (some #(= (set spaces) (set (:spaces %))) (road-choices g p))
                    "Connect two adjacent revealed action spaces with a road. Each space may touch only one of your roads.")
        (-> g (update-in (conj path :roads) (fnil conj []) (vec spaces))
            (update-in [:players pid :reserved-fences card] (fnil inc 0))))
      :final-auction-schedule
      (reduce-kv (fn [state round goods]
                   (if (> round (:round g))
                     (update-in state [:players pid :schedule round] (fnil conj [])
                                {:card card :marker-goods goods :effect {:op :final-auction-start :goods goods}}) state)) g auction-goods)
      :final-auction-start
      (let [order (mapv #(mod (+ (:starting-player g) %) (count (:players g))) (range (count (:players g))))]
        (-> g (assoc :auction {:owner pid :order order :goods (:goods effect) :submitted []})
            (assoc :private-auction-bids {})
            (prepend (map #(task % card {:op :final-auction-bid} {}) order))))
      :final-auction-bid
      (let [bid (:amount choice 0) auction (:auction g)]
        (a/require! (and auction (a/natural? bid) (<= bid (a/amount p :food))
                         (not (contains? (:private-auction-bids g) pid))) "Seal one affordable food bid; it remains hidden until everyone has bid.")
        (let [state (-> g (assoc-in [:private-auction-bids pid] bid) (update-in [:auction :submitted] conj pid))
              bids (:private-auction-bids state)]
          (if (< (count bids) (count (:players g))) state
            (let [winner (first (sort-by #(- (get bids %)) (:order auction))) price (get bids winner)]
              (-> (cond-> (update-in state [:players winner] a/pay {:food price})
                    (not= winner (:owner auction)) (update-in [:players (:owner auction)] a/gain {:food price}))
                  (update-in [:players winner] a/gain (:goods auction))
                  (assoc-in [:players (:owner auction) :card-state card :last-auction]
                            {:round (:round g) :bids bids :winner winner :goods (:goods auction)})
                  (a/log-event (str (:name (get-in g [:players winner])) " won Auctioneer for " price " food."))
                  (dissoc :auction :private-auction-bids))))))
      :final-writing-start
      (if (and (seq (writing-rounds g p)) (seq (get-in p [:hand :occupation])))
        (prepend g [(task pid card {:op :final-writing-place} {})]) g)
      :final-writing-place
      (let [round (:round choice) cid (:card choice)]
        (a/require! (and (some #{round} (writing-rounds g p)) (some #{cid} (get-in p [:hand :occupation])))
                    "Place a hand occupation face down on an unassigned future round 5, 7, 9, or 11.")
        (-> g (update-in [:players pid :hand :occupation] #(vec (remove #{cid} %)))
            (assoc-in [:players pid :private-card-state card round] cid)
            (update-in [:players pid :schedule round] (fnil conj []) {:card card :marker-goods {:occupation 1} :effect {:op :final-writing-arrive :round round}})
            (prepend [(task pid card {:op :final-writing-start} {})])))
      :final-remodeler
      (do (a/require! (some #{(select-keys choice [:stable :cell])} (remodeler-choices g pid context))
                      "Return a stable, build one free wooden room, and retain enough resources to renovate the enlarged home.")
          (update-in g [:players pid] remodeler-player (:stable choice) (:cell choice)))
      :final-sunday-gain
      (-> g (update-in [:players pid] a/gain (:goods effect)) (assoc-in (conj path :used-round) (:round g)))
      :final-debt-schedule
      (let [round (:round choice)]
        (a/require! (and (integer? round) (<= (+ 3 (:round g)) round 14)) "Place the guest at least three rounds in the future.")
        (update-in g [:players pid :schedule round] (fnil conj [])
                   {:card card :marker-goods {:guest 1} :effect {:op :final-debt-arrival :cost {:food 1}}}))
      :final-debt-arrival
      (-> g (update-in [:players (:starting-player g)] a/gain {:food 1})
          (update-in [:players pid :guests] (fnil inc 0)) (update-in [:players pid :remaining] inc)
          (assoc :debt-placement {:player pid :round (:round g)}))
      :final-winter-harvest
      (if (#{6 8 10 12} (:round g))
        (reduce (fn [state cell] (let [crop (get-in p [:farm cell :crop])]
                  (update-in state [:players pid] #(-> % (a/remove-field-crop cell crop) (a/gain {crop 1}))))) g (winter-cells p)) g)
      :final-lender
      (let [resource (:resource choice) source (lender-goods g pid context)
            _ (a/require! (contains? (lender-source context) resource) "Add one building resource of a type received from this action.")
            goods (update source resource (fnil inc 0)) resources (:resources choice)
            _ (a/require! (and (vector? resources) (= (min (- 14 (:round g)) (reduce + 0 (vals goods))) (count resources))
                               (every? (fn [[r n]] (<= n (get goods r 0))) (frequencies resources)))
                          "Schedule all resources that fit, one per next round, in your chosen order.")
            state (-> g (update-in [:players pid] a/remove-goods source)
                      (update-in [:action-returned-goods (:action-id context)] #(merge-with + (or % {}) source)))]
        (reduce (fn [state [offset r]] (update-in state [:players pid :schedule (+ (:round g) offset)] (fnil conj []) {:card card :goods {r 1}}))
                state (map vector (range 1 15) resources)))
      :final-pickaxe
      (let [spaces (:spaces choice) eligible (set (pickaxe-spaces g pid card context)) n (count spaces)]
        (a/require! (and (<= 1 n 2) (= n (count (set spaces))) (every? eligible spaces) (seq (plow-plans p n)))
                    "Place one wood on one or two different adjacent board spaces, with room for that many additional fields.")
        (-> (reduce #(update-in %1 [:space-bonuses %2 :wood] (fnil inc 0)) g spaces)
            (update-in [:players pid] a/pay {:wood n}) (assoc-in (conj path :used?) true)
            (prepend [(task pid card {:op :plow-many :min-fields n :max-fields n} {})])))
      :final-mailman-place
      (let [space (:space choice) index (:index effect)]
        (a/require! (and (some #{space} (:active g)) (not (contains? (get-in p [:card-state card :packages]) space)))
                    "Place each package on a different revealed action space.")
        (cond-> (assoc-in g (conj path :packages space) (nth mailman-packages index))
          (< index 3) (prepend [(task pid card {:op :final-mailman-place :index (inc index)} {})])))
      :final-mailman-buy (buy-mail g pid effect)
      :final-musketeer-place
      (do (a/require! (contains? (intersections g) (:point choice)) "Place the arrow at an intersection of four action spaces.")
          (assoc-in g (conj path :point) (:point choice)))
      :final-musketeer-reward
      (let [spaces (get (intersections g) (get-in p [:card-state card :point]))
            n (count (filter spaces (set (:worker-spaces p))))
            animal ({2 :sheep 3 :boar 4 :cattle} n)]
        (if animal (update-in g [:players pid] a/gain {animal 1}) g))
      :final-expert-offer
      (let [resources (vec (for [[r n] (:room-paid context) :when (and (pos? n) (some #{r} [:wood :clay :reed :stone]))] r))]
        (if (seq resources) (prepend g [(task (:actor context) card {:op :final-expert-resource :owner pid :choices resources} context)]) g))
      :final-expert-resource
      (do (a/require! (some #{(:resource choice)} (:choices effect)) "Choose a building resource actually paid for those rooms.")
          (update-in g [:players (:owner effect) :card-state card :stored-goods (:resource choice)] (fnil inc 0)))
      :final-downsize
      (do (a/require! (some #{(:cell choice)} (downsize-rooms p card)) "Return one wooden room built during this game, once only.")
          (-> g (assoc-in [:players pid :farm (:cell choice)] {:type :empty})
              (assoc-in (conj path :used?) true)
              (prepend [{:kind :event :event :check-state :player pid :context {}}
                        (task pid card {:op :build-fences :max-fences 7 :free-fences 7 :optional? true} {})])))
      :final-crane
      (prepend g [(task pid card (if (= :build-rooms (:trigger-event context))
                                  {:op :build-rooms :max-rooms (:rooms-built context) :stone-crane? true :optional? true}
                                  {:op :renovate :stone-crane? true :optional? true}) context)])
      :final-early-breeding
      (if (:manure? context) g
        (-> g
            (cond-> (not (:almanac? context)) (update :early-bred (fnil conj #{}) pid))
            (prepend [{:kind :event :event :breeding-start :player pid :context context}
                      (task pid card {:op :final-personal-breed :early? true} context)])))
      :final-personal-breed
      (let [offspring (or (:offspring choice) (a/default-breeding p))
            probe (-> g (assoc :phase :breeding :defer-advance? true :bred (disj (set (range (count (:players g)))) pid)))
            after (get-in (a/breed probe pid (assoc choice :offspring offspring)) [:players pid])
            surplus (merge-with - (a/breeding-options p) offspring)
            cook? (:cook? choice)
            _ (a/require! (or (not cook?) (:early? effect)) "Only Early Riser may cook newborn animals without housing them.")
            after (if (and cook? (some pos? (vals surplus)))
                    (a/convert-food (a/gain after surplus) surplus (select-keys g [:phase :round])) after)]
        (-> g (assoc-in [:players pid] after)
            (prepend [{:kind :event :event :check-state :player pid :context context}])))
      :final-clear-worker (update-in g path dissoc :worker-on-card?)
      :final-late-offer
      (let [fingerprint [(mapv :family (:players g)) (a/family-capacity p)]]
        (if (and (late-bloomer-available? g pid) (not= fingerprint (get-in g (conj path :offered))))
          (-> g (assoc-in (conj path :offered) fingerprint)
              (prepend [(task pid card {:op :final-late-grow :optional? true} {})])) g))
      :final-late-grow
      (do (a/require! (late-bloomer-available? g pid) "Every opponent must have more family members, and you need an empty room.")
          (-> g (update-in [:players pid] #(cond-> (-> % (a/grow true) (update :newborn dec))
                                             (#{:work :preparation} (:phase g)) (update :remaining inc)))
              (prepend [{:kind :event :event :grow :player pid
                         :context {:family-before (:family p) :newborns-added 0}}])))
      :final-badger
      (do (a/require! (contains? (badger-spaces g pid) (:space choice)) "Move Badger to a revealed, orthogonally adjacent action space.")
          (assoc-in g (conj path :badger-space) (:space choice)))
      :final-badger-income
      (if (= (:space context) (get-in p [:card-state card :badger-space]))
        (update-in g [:players (:actor context)] a/gain {:food 1}) g)
      :final-agrarian-offer
      (prepend g [(task pid card {:op :final-agrarian-deposit :optional? true :cost {:food 1}} {})])
      :final-agrarian-deposit
      (-> g (assoc-in (conj path :round) (:round g))
          (update-in [:space-bonuses :plow :food] (fnil inc 0)))
      :final-agrarian-taken (assoc-in g (conj path :taken-round) (:round g))
      :final-agrarian-end
      (if (and (= (:round g) (get-in p [:card-state card :round]))
               (not= (:round g) (get-in p [:card-state card :taken-round])))
        (-> g (update-in [:space-bonuses :plow :food] dec)
            (update-in [:players pid] a/gain {:food 1})
            (prepend [(task pid card {:op :plow :optional? true} {})])) g)
      :final-card-player-start (assoc-in g (conj path :stack) [:wood :clay :reed :stone])
      :final-card-player-rotate
      (update-in g (conj path :stack) #(conj (vec (rest %)) (first %)))
      :final-critic-fishing
      (assoc-in g (conj path :stored-goods :food) 1)
      :final-store-converted
      (let [goods (:goods choice) available (converted-items head)
            available (cond-> available
                        (= card critic) (dissoc :grain))
            available (if (and (= card critic) (= :bake (:trigger-event context)))
                        {:grain (:grain-baked context 0)} available)]
        (a/quantities! goods (if (= card bathroom) [:grain :vegetable] (keys available)))
        (a/require! (and (some pos? (vals goods)) (every? (fn [[r n]] (<= n (get available r 0))) goods))
                    "Store only goods from this conversion.")
        (update-in g (conj path :stored-goods) #(merge-with + (or % {}) goods)))
      :final-bathroom-sow
      (let [cells (:cells choice) plantings (into {} (map (juxt :cell :crop) (:plantings context)))
            goods (frequencies (map plantings cells))]
        (a/require! (and (seq cells) (= (count cells) (count (distinct cells))) (every? plantings cells))
                    "Select fields just sown, once each.")
        (a/require! (every? (fn [[r n]] (>= (get-in p [:card-state card :stored-goods r] 0) n)) goods)
                    "Bathroom needs a stored crop of each selected field's type.")
        (reduce (fn [state cell]
                  (-> state (update-in (into [:players pid] (conj (a/field-path cell) :count)) inc)
                      (update-in (conj path :stored-goods (plantings cell)) dec))) g cells))
      :final-astronomer
      (update-in g [:players pid :bonus-points] (fnil + 0) (case (min 5 (line-length g pid)) 3 1 4 3 5 5 0)))))
