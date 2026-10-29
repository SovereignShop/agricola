(ns eurozone.browser.views
  (:require [reagent.core :as r] [re-frame.core :as rf] [clojure.string :as str]
            [cljs.reader :as reader] [agricola.recording :as recording] [eurozone.browser.table-controls :as controls]
            [agricola.engine :as a] [agricola.five-player :as five] [agricola.card-game :as card-game] [eurozone.catalog :as catalog] [eurozone.browser.art :as art] [agricola.card-rules :as cards] [eurozone.browser.card-art :as card-art] [eurozone.browser.layout :as layout] [eurozone.browser.comms :as comms] [eurozone.store :as store]))

(def glyph {:wood "▰" :clay "◆" :reed "≋" :stone "⬟" :grain "♧" :vegetable "●" :food "◉" :sheep "♧" :boar "◆" :cattle "⬢"})
(def labels {:wood "Wood" :clay "Clay" :reed "Reed" :stone "Stone" :grain "Grain" :vegetable "Vegetables" :food "Food" :sheep "Sheep" :boar "Wild boar" :cattle "Cattle"})
(def colors ["#b96942" "#47779b" "#b28e32" "#826891" "#4a9dba"])
(defn dispatch [& event] (rf/dispatch (vec event)))
(defn button [text handler & [class disabled?]] [:button {:type "button" :class (or class "button") :on-click handler :disabled disabled?} text])
(defn badge [r n & [pid]] [:span.resource {:class (name r) :title (labels r) :data-piece-key (when (some? pid) (str "goods:" pid ":" r)) :data-piece-type (name r) :data-piece-count n} [art/piece r] [:b n] [:span (labels r)]])
(defn goods [m & [pid]] [:div.goods (for [r a/resources :when (pos? (get m r 0))] ^{:key r} [badge r (m r) pid])])
(defn field [label control] [:label.form-field [:span label] control])
(defn input [value change & [attrs]] [:input (merge {:value value :on-change #(change (.. % -target -value))} attrs)])
(defn number-input [value change maximum]
  [input (or value 0) #(change (max 0 (min maximum (or (js/parseInt %) 0)))) {:type "number" :min 0 :max maximum}])
(defn draft! [k v] (dispatch :draft k v))
(defn toggle-value [xs v] (if (some #{v} xs) (vec (remove #{v} xs)) (conj (vec xs) v)))
(defonce replay-data (r/atom nil))
(defn show-recording! [text filename]
  (let [timeline (recording/timeline (reader/read-string (str "[" text "]")))]
    (reset! replay-data (assoc timeline :index 0 :file filename :playing? false))))
(defn load-linked-recording! [path]
  (when (and (str/starts-with? path "/recordings/") (not (str/includes? path "..")))
    (-> (js/fetch path)
        (.then (fn [response] (if (.-ok response) (.text response) (throw (js/Error. "Recording not found.")))))
        (.then #(show-recording! % (last (str/split path #"/"))))
        (.catch #(dispatch :error (str "Could not replay recording: " (.-message %)))))))
(defn recording-picker []
  [:label.button.recording-picker "Open recording"
   [:input {:type "file" :accept ".edn" :aria-label "Open game recording"
            :on-change
            (fn [e]
              (when-let [file (aget (.. e -target -files) 0)]
                (if (> (.-size file) (* 20 1024 1024))
                  (dispatch :error "Choose a recording smaller than 20 MB.")
                  (-> (.text file)
                      (.then (fn [text]
                               (show-recording! text (.-name file))))
                      (.catch #(dispatch :error (str "Could not replay recording: " (.-message %))))))))}]])
(defn farmer-art [] [:img.farm-art {:src "/art/eurozone/farmstead.png" :alt "An illustrated farmstead, orchards and wheat fields"}])

(defn pack-picker [n settings change disabled?]
  (let [excluded (set (:excluded-packs settings)) pool (store/hand-pool n excluded)
        counts (frequencies (map :kind pool)) problem (store/pool-error n settings)]
    [:fieldset.pack-picker [:legend "Card packs"]
     (for [{:keys [id label min-players implemented total]} store/card-packs] ^{:key id}
       [:label.checkbox
        [:input {:type "checkbox" :aria-label (str "Include " label) :checked (not (contains? excluded id))
                 :disabled (or disabled? (< n min-players))
                 :on-change #(change (assoc settings :excluded-packs
                                           (vec (sort (if (contains? excluded id) (disj excluded id) (conj excluded id))))))}]
        [:span label (when (< implemented total) [:small (str " · " implemented "/" total " cards")]) (when (< n min-players) [:small " · five players only"]) ]])
     [:p.small (str (get counts :occupation 0) " occupations · " (get counts :minor 0) " minor improvements in the eligible pool.")]
     [:details [:summary "Card coverage"]
      [:p.small "Only cards with native rules enter the draw. A partial pack includes the listed number of playable cards; the remaining cards are still being implemented."]
      (for [{:keys [id label implemented total]} store/card-pack-coverage] ^{:key id}
        [:p.small (str label ": " implemented " / " total)])]
     (when problem [:p.warning {:role "status"} problem])]))
(defn create-form []
  (let [name (r/atom (or (.getItem js/sessionStorage "eurozone-name") (.getItem js/localStorage "eurozone-name") "")) title (r/atom "") mode (r/atom :local) setup (r/atom :beginner) extra (r/atom 2) count (r/atom 2) bots (r/atom 0) seconds (r/atom 0) excluded (r/atom []) others (r/atom ["Farmer 2" "Farmer 3" "Farmer 4" "Farmer 5"])]
    (fn []
      (let [ui @(rf/subscribe [:ui])]
        [:section.panel.create-panel
         [:h2 "Create a table"]
         [:p.muted "Play together on one screen, or invite friends to join from their browsers."]
         [:form {:on-submit (fn [e] (.preventDefault e) (.setItem js/sessionStorage "eurozone-name" @name) (.setItem js/localStorage "eurozone-name" @name)
                              (dispatch :send {:event :create :name @name :title (if (str/blank? @title) (str @name "'s farm") @title)
                                               :mode @mode :expected-players @count :turn-seconds @seconds :bots (if (= @mode :peer) @bots 0) :setup {:mode @setup :extra @extra :excluded-packs @excluded} :names (into [@name] (take (dec @count) @others))}))}
          [:div.form-row [field "Your name" [input @name #(reset! name %) {:required true :max-length 40 :placeholder "Your name" :auto-complete "nickname"}]]
           [field "Table name" [input @title #(reset! title %) {:max-length 40 :placeholder "Sunday at the farm"}]]]
          [:div.segmented
           [button "One screen" #(reset! mode :local) (when (= @mode :local) "selected")]
           [button "Online table" #(reset! mode :online) (when (= @mode :online) "selected")]
           [button "Peer-hosted table" #(reset! mode :peer) (when (= @mode :peer) "selected")]]
          (when (= @mode :peer) [:p.small "Your browser hosts and saves this game. Keep this tab open; friends can reconnect when you return."])
          [field "Players expected" [:select {:value @count :on-change #(let [n (js/parseInt (.. % -target -value))] (reset! count n) (swap! bots min (dec n)))}
                                      (for [n (range 1 6)] ^{:key n} [:option {:value n} (if (= n 1) "1 · Solo" (str n " players"))])]]
          (when (= @mode :peer)
            [:div
             [field "Bot players" [:select {:value @bots :on-change #(reset! bots (js/parseInt (.. % -target -value)))}
                                   (for [n (range @count)] ^{:key n} [:option {:value n} n])]]
             [:p.small (str (- @count @bots) " human seats + " @bots " bots = " @count " total players.")]])
          (when (= @mode :local)
            [:div.form-row (for [i (range (dec @count))] ^{:key i}
                             [field (str "Player " (+ i 2)) [input (nth @others i) #(swap! others assoc i %) {:required true :max-length 40}]])])
          [field "Pre-game hand setup"
           [:select {:value (cljs.core/name @setup) :on-change #(reset! setup (keyword (.. % -target -value)))}
            [:option {:value "beginner"} "Beginner · no hand cards"]
            [:option {:value "random"} "Random deal · 7 occupations + 7 minors"]
            [:option {:value "draw"} "The draw · choose one, pass left"]]]
          (when (= @setup :draw)
            [:div [field "Extra cards per packet (N)" [number-input @extra #(reset! extra %) 10]]
             [:p.small (str "Each player receives " (+ 14 @extra) " mixed cards. Pick one and pass left until you have 7 occupations and 7 minors. Packets with no legal pick pass automatically.")]])
          (when (not= @setup :beginner)
            [pack-picker @count {:mode @setup :extra @extra :excluded-packs @excluded}
             #(reset! excluded (:excluded-packs %)) (:busy? ui)])
          [controls/limit-select @seconds #(reset! seconds %) (:busy? ui)]
          [:p.small (if (= @setup :beginner) "Revised Edition beginner game · 14 rounds and 10 major improvements."
                        (if (= @count 5) "Revised base cards + all 48 five-player occupations · linked action spaces and private hands." "Revised Edition base cards · private hands and card-effect decisions."))]
          [:button.button.primary {:type "submit" :disabled (or (:busy? ui) (not (:connected? ui)) (some? (store/pool-error @count {:mode @setup :extra @extra :excluded-packs @excluded})))} "Create table →"]]]))))
(defn table-list []
  (let [rooms @(rf/subscribe [:games])]
    [:section.tables
     [:div.section-heading [:h2 "The tables"] [:span.muted (str (count rooms) " saved")]]
     (if (seq rooms)
       [:div.table-list
        (for [room rooms] ^{:key (:id room)}
          [:button.table-entry {:on-click #(dispatch :navigate (:id room))}
           [:span.table-icon "♧"] [:span [:strong (:title room)] [:small (str/join " · " (map :name (:seats room)))]]
           [:span.table-status (case (:status room) :lobby "Open table" :draw "Drawing cards" :finished "Finished" (str "Round " (get-in room [:state :round])))]
           [:span "↗"]])]
       [:div.empty-state [:p "No saved tables."]])]))
(defn library []
  [:main.library
   [:div.hero
    [:div.hero-copy [:div.eyebrow "EUROZONE"] [:h1 "Agricola"]
     [:p "A farming game by Uwe Rosenberg"]
     [:div.hero-note "Revised Edition · 1–5 players"]]
    [:div.feature-art [farmer-art]]]
   [:div.library-body
    [:section.game-feature [:div.eyebrow "FIRST IN THE COLLECTION"] [:h2 "Agricola"] [:p.byline "by Uwe Rosenberg"]
     [:p "Build your farm over fourteen rounds: grow your family, cultivate fields and raise animals."]
     [:div.game-meta [:span "1–5 players"] [:span "30 min / player"] [:span "Worker placement"]]
     [:p.small "Expand your home, cultivate the land, and raise animals. Just remember: everyone needs to eat."]
     [:a.text-link {:href (:rules (first catalog/games)) :target "_blank" :rel "noreferrer"} "Read the official rules ↗"]]
    [create-form]]
   [recording-picker]
   [table-list]])
(defn lobby [room]
  (let [name (r/atom (or (.getItem js/sessionStorage "eurozone-name") (.getItem js/localStorage "eurozone-name") "")) copied? (r/atom false)]
    (fn [room]
      (let [permission @(rf/subscribe [:permission]) ui @(rf/subscribe [:ui]) seated? (seq (:seats permission))
            expected (let [n (:expected-players room)] (if (pos? (or n 0)) n (count (:seats room))))
            online (set (get-in ui [:presence (:id room)]))
            ready (:seat-readiness room) bot-seats (set (:bot-seats room)) bot-count (or (:bots room) 0)
            startable? (and (nil? (store/pool-error expected (:setup room))) (= expected (count (:seats room))) (every? true? ready)
                            (or (= :local (:mode room)) (= expected (count online))))]
        [:main.lobby
         [button "← All tables" #(dispatch :navigate nil) "text-button"]
         [:div.lobby-grid
          [:section.panel [:div.eyebrow "AGRICOLA"] [:h1 (:title room)]
           [:p.muted "Choose your settings and wait for everyone to be ready."]
           (when (and (:host? permission) (= :online (:mode room)))
             [:div.lobby-settings
              [:h2 "Table settings"]
              [:div.form-row
               [field "Players expected"
                [:select {:value expected :disabled (:busy? ui)
                          :on-change #(dispatch :send {:event :configure :expected-players (js/parseInt (.. % -target -value)) :setup (:setup room)})}
                 (for [n (range 1 6)] ^{:key n}
                   [:option {:value n :disabled (< n (count (:seats room)))} (if (= n 1) "1 · Solo" (str n " players"))])]]
               [field "Pre-game hand setup"
                [:select {:value (cljs.core/name (get-in room [:setup :mode] :beginner)) :disabled (:busy? ui)
                          :on-change #(dispatch :send {:event :configure :expected-players expected
                                                      :setup (assoc (:setup room) :mode (keyword (.. % -target -value)))})}
                 [:option {:value "beginner"} "Beginner · no hand cards"]
                 [:option {:value "random"} "Random deal · 7 occupations + 7 minors"]
                 [:option {:value "draw"} "The draw · choose one, pass left"]]]]
              (when (= :draw (get-in room [:setup :mode]))
                [field "Extra cards per packet (N)"
                 [:select {:value (get-in room [:setup :extra] 2) :disabled (:busy? ui)
                           :on-change #(dispatch :send {:event :configure :expected-players expected
                                                       :setup (assoc (:setup room) :extra (js/parseInt (.. % -target -value)))})}
                  (for [n (range 11)] ^{:key n} [:option {:value n} n])]])
              (when (comms/peer?)
                [:div
                 [field "Bot players"
                  [:select {:value bot-count :disabled (:busy? ui)
                            :on-change #(dispatch :send {:event :configure :expected-players expected :setup (:setup room)
                                                        :bots (js/parseInt (.. % -target -value))})}
                   (for [n (range expected)] ^{:key n}
                     [:option {:value n :disabled (> (+ n (- (count (:seats room)) bot-count)) expected)} n])]]
                 [:p.small (str (- expected bot-count) " human seats + " bot-count " bots = " expected " total players. Bots play automatically while the host is here.")]])
              (when (not= :beginner (get-in room [:setup :mode]))
                [pack-picker expected (:setup room)
                 #(dispatch :send {:event :configure :expected-players expected :setup %}) (:busy? ui)])
              [controls/limit-select (get room :turn-seconds 0)
               #(dispatch :send {:event :configure :expected-players expected :setup (:setup room) :turn-seconds %}) (:busy? ui)]
              [:p.small "Settings save automatically. Seated guests need to mark themselves ready again after a change."]])
           (when (and (not= :beginner (get-in room [:setup :mode])) (not (and (:host? permission) (= :online (:mode room)))))
             [pack-picker expected (:setup room) identity true])
           [:div.seats (for [[i seat] (map-indexed vector (:seats room))] ^{:key i}
                         [:div.seat [:span.avatar {:style {:background (colors i)}} (subs (:name seat) 0 1)]
                          [:strong (:name seat)] [:span.muted (str (when (some #{i} (:seats permission)) "You · ")
                            (cond (bot-seats i) "Bot · Ready" (and (= :online (:mode room)) (not (online i))) "Offline" (get ready i) "Ready" :else "Not ready"))]])]
           [:p.small (str (count (:seats room)) " / " expected " players · "
                          (case (get-in room [:setup :mode]) :draw (str "Draw: 14 + " (get-in room [:setup :extra]) " cards, pass left") :random "Random 7 + 7 hand" "Beginner game"))]
           (when (and (= :online (:mode room)) (not (.-EUROZONE_ACTIVITY js/window)))
             [:div.invite
              [:span.small "INVITE YOUR FRIENDS"]
              [:div.form-row [input (comms/invite-url (:id room)) identity {:read-only true :aria-label "Table invitation link"}]
               [button (if @copied? "Copied" "Copy link")
                #(if-let [clipboard (.-clipboard js/navigator)]
                   (-> (.writeText clipboard (comms/invite-url (:id room)))
                       (.then (fn [] (reset! copied? true)))
                       (.catch (fn [] (dispatch :error "Select and copy the invitation link."))))
                   (dispatch :error "Select and copy the invitation link.")) "button secondary"]]])
           (when (.-EUROZONE_ACTIVITY js/window)
             [button "Invite in Discord"
              #(-> (.EUROZONE_INVITE js/window)
                   (.catch (fn [_] (dispatch :error "Use Discord’s Activity invitation controls to invite a friend.")))) "button secondary"])
           (when (and (= :online (:mode room)) (not (.-EUROZONE_DISCORD_USER js/window)))
             [:p.small [:a.text-link {:href (comms/invite-url (:id room) true) :target "_blank" :rel "noopener"}
                        "Open another player in a new tab ↗"]
              [:br] "Each extra player tab has an independent seat and reconnects after a reload."])
           (when (.-EUROZONE_DISCORD_USER js/window)
             [:p.small "Each player joins with their own invited Discord account."])
           (when (and (= :online (:mode room)) seated?)
             [:div.form-row
              [button (if (get ready (first (:seats permission))) "Mark not ready" "I'm ready")
               #(dispatch :send {:event :ready :ready? (not (get ready (first (:seats permission))))}) "button secondary" (:busy? ui)]
              (when-not (:host? permission) [button "Leave seat" #(dispatch :send {:event :leave}) "text-button" (:busy? ui)])])
           (when (and (= :online (:mode room)) (not seated?) (< (count (:seats room)) expected))
             [:form.form-row {:on-submit (fn [e] (.preventDefault e)
                                          (let [chosen (.-value (.namedItem (.-elements (.-currentTarget e)) "player-name"))]
                                            (.setItem js/sessionStorage "eurozone-name" chosen)
                                            (dispatch :send {:event :join :name chosen})))}
              [input @name #(reset! name %) {:name "player-name" :placeholder "Your name" :required true :max-length 40 :aria-label "Your name"}]
              [:button.button.primary {:disabled (:busy? ui)} "Take a seat"]])
           (if (:host? permission)
             [button (cond (= :draw (get-in room [:setup :mode])) "Start draw →" (= 1 expected) "Begin solo game →" :else "Start the game →") #(dispatch :send {:event :start}) "button primary" (or (:busy? ui) (not startable?))]
             [:p.muted "The host will start once everyone is ready."])]
          [:aside.panel [:h2 "Agricola"] [farmer-art]
           [:ul [:li "Place one family member at a time on an unoccupied action."] [:li "New actions appear each round. Unclaimed goods accumulate."]
            [:li "Harvest after rounds 4, 7, 9, 11, 13, and 14."] [:li "The best-developed farm wins after the final harvest."]]
           [:p.small (if (= :beginner (get-in room [:setup :mode]))
                       "Beginner variant: no hand cards. Meeting Place accumulates food; Side Job lets you build a stable or bake bread."
                       "Occupations and minor improvements from the selected packs are enabled. Prerequisites and payment are enforced; card effects trigger automatically and pause when you have a choice.")]]]]))))

(defn select-cell! [draft i]
  (let [space (:space draft) tool (or (:tool draft) (case space (:build :house-building) :room :side-job :stable (:sow-bake) :grain :field))]
    (case tool
      :room (draft! :rooms (toggle-value (:rooms draft) i))
      :stable (draft! :stables (toggle-value (:stables draft) i))
      :field (draft! :cell (when-not (= (:cell draft) i) i))
      (:grain :vegetable)
      (let [old (some #(when (= i (:cell %)) %) (:plantings draft))
            rest (vec (remove #(= i (:cell %)) (:plantings draft)))]
        (draft! :plantings (if (= tool (:crop old)) rest (conj rest {:cell i :crop tool}))))
      nil)))
(defn farm [p draft editable? & [edit!]]
  (let [fenced (a/pasture-cells p) housed (a/animal-housing p) selection (set (concat (:rooms draft) (:stables draft) [(:cell draft)] (map :cell (:plantings draft))))
        fence-mode? (and editable? (#{:fences :redevelop} (:space draft)))]
    [:svg.farm {:viewBox "-45 -45 562 400" :role "group" :aria-label (str (:name p) "'s farm")}
     [:image {:href (art/url "farm.jpeg") :x -45 :y -45 :width 562 :height 400 :preserveAspectRatio "none"}]
     (for [i (range 15) :let [x (* 100 (mod i 5)) y (* 100 (quot i 5)) cell (get-in p [:farm i])
                             tile (:type cell) chosen? (and editable? (selection i))
                             animal (some #(when (= i (first (:cells %))) %) housed)]]
       ^{:key i}
       [:g {:role (when editable? "button") :tab-index (when editable? 0)
            :aria-label (str "Farm space " (inc i) ": " (name tile) (when (fenced i) ", pasture"))
            :on-click #(when editable? (select-cell! draft i))
            :on-key-down #(when (and editable? (#{"Enter" " "} (.-key %))) (.preventDefault %) (select-cell! draft i))}
        (when (or (= tile :field) (= tile :room))
          [:image {:href (art/url (str (if (= tile :field) "plow" ({:wood "wood_room" :clay "soil_room" :stone "rock_room"} (:house p))) ".png"))
                   :x (+ x 4) :y (+ y 4) :width 92 :height 92 :preserveAspectRatio "none"}])
        [:rect {:x (+ x 2) :y (+ y 2) :width 96 :height 96 :rx 4
                :fill (if chosen? "#f9dc7170" "transparent")
                :stroke (if chosen? "#fff1a0" "#ffffff30") :stroke-width (if chosen? 4 1)}]
        [:text {:x (+ x 9) :y (+ y 17) :class "cell-number"} (inc i)]
        (when (= i 10)
          [:g {:aria-label (str (:remaining p) " family members at home")}
           (for [n (range (:remaining p))] ^{:key n}
             [:image {:data-piece-key (str "home:" (:id p) ":" n) :data-piece-type (str "worker:" (:id p)) :href (art/worker-url (:id p)) :x (+ x 6 (* 25 (mod n 3))) :y (+ y 5 (* 34 (quot n 3))) :width 32 :height 40}])])
        (when (:stable cell) [:g [:rect {:x (+ x 30) :y (+ y 38) :width 40 :height 30 :fill "#b07949"}]
                                [:path {:d (str "M" (+ x 23) " " (+ y 38) "l27 -18 27 18Z") :fill "#6f563a"}]])
        (when (pos? (get cell :count 0))
          [:g (for [n (range (:count cell))] ^{:key n}
                [:image {:data-piece-key (str "crop:" (:id p) ":" i ":" n) :data-piece-type (name (:crop cell)) :href (art/piece-url (:crop cell)) :x (+ x 15 (* n 13)) :y (- (+ y 40) (* n 6)) :width 36 :height 40}])])
        (when animal
          [:g
           (for [n (range (min 4 (:count animal)))] ^{:key n}
             [:image {:data-piece-key (str "animal:" (:id p) ":" i ":" n) :data-piece-type (name (:animal animal)) :href (art/piece-url (:animal animal)) :x (+ x 8 (* n 17)) :y (+ y 58) :width 35 :height 30}])
           [:text {:x (+ x 85) :y (+ y 89) :text-anchor "middle" :class "animal-label"} (:count animal)]])
        (when chosen? [:text {:x (+ x 84) :y (+ y 20) :text-anchor "middle" :class "selected-mark"} "✓"])])
     (for [edge (sort a/all-edges)
           :let [[direction rs cs] (str/split edge #":") row (js/parseInt rs) col (js/parseInt cs)
                 x (* 100 col) y (* 100 row) horizontal? (= direction "h")
                 built? ((:fences p) edge) selected? (some #{edge} (:fences draft))]
           :when (or fence-mode? built?)]
       ^{:key edge}
       [:g {:role (when (and fence-mode? (not built?)) "button") :tab-index (when (and fence-mode? (not built?)) 0)
            :aria-label (str "Fence " edge (when built? ", built"))
            :on-click (fn [e] (.stopPropagation e) (when (and fence-mode? (not built?)) ((or edit! draft!) :fences (toggle-value (:fences draft) edge))))
            :on-key-down #(when (and fence-mode? (not built?) (#{"Enter" " "} (.-key %))) (.preventDefault %) ((or edit! draft!) :fences (toggle-value (:fences draft) edge)))}
        [:line {:x1 (if horizontal? (+ x 6) x) :y1 (if horizontal? y (+ y 6))
                :x2 (if horizontal? (+ x 94) x) :y2 (if horizontal? y (+ y 94)) :stroke "transparent" :stroke-width 16}]
        [:line {:x1 (if horizontal? (+ x 6) x) :y1 (if horizontal? y (+ y 6))
                :x2 (if horizontal? (+ x 94) x) :y2 (if horizontal? y (+ y 94))
                :stroke (cond built? "#7b5638" selected? "#be6e3b" :else "#a5b495")
                :stroke-width (if (or built? selected?) 6 2) :stroke-dasharray (when-not (or built? selected?) "5 6") :stroke-linecap "round"}]])]))

(defn round-track [g]
  [:div.round-track {:aria-label "Round progress"}
   (for [n (range 1 15)] ^{:key n}
     [:div {:class (str "round " (when (= n (:round g)) "current ") (when (< n (:round g)) "past "))
            :title (str "Round " n (when (a/harvest-rounds n) " · Harvest"))}
      [:span n] [:small (when (a/harvest-rounds n) "♧")]])])
(def board-spaces
  {:build [188 9 115 131] :meeting [188 148 115 76] :grain [188 231 115 76]
   :plow [188 323 115 76] :side-job [188 414 115 106] :lessons [188 414 115 106] :lessons-extra [6 417 145 106] :labor [188 533 115 88]
   :wood [329 159 147 149] :clay [329 318 147 92] :reed [329 420 147 103] :fishing [329 533 147 88]
   :copse [6 8 145 131] :grove [6 148 145 85] :market [6 243 145 76]
   :hollow [6 329 145 82] :traveling [6 533 145 88]})
(def round-spaces [[329 8] [491 8] [653 8] [815 8] [491 164] [653 164] [815 164]
                   [491 320] [653 320] [491 476] [653 476] [167 632] [329 632] [653 632]])
(defn round-space-position [round]
  (let [[x y] (nth round-spaces (dec round))]
    {:left (str (/ x 10) "%") :top (str (/ y 8) "%") :width "14.7%" :height "18.25%"}))
(defn future-round-markers [g]
  [:<>
   (for [[round owners] (card-game/future-round-resources g)]
     ^{:key round}
     [:section.future-round-resources {:style (round-space-position round) :data-round round
                                      :aria-label (str "Resources for round " round)}
      [:strong.future-round-label (str "Round " round)]
      [:div.future-round-owners
       (for [{:keys [player goods cards]} owners
             :let [owner (get-in g [:players player :name])
                   sources (str/join ", " (map #(or (:name (cards/card %)) (:name (a/improvements %))) cards))]]
         ^{:key player}
         [:div.future-round-owner {:data-player player :style {:border-color (colors player)}
                                   :title (str owner " · " sources)}
          [:span.future-owner-name owner]
          [:div.future-goods
           (for [resource (conj a/resources :field) :let [n (get goods resource 0)] :when (pos? n)]
             ^{:key resource}
             [:span.future-resource {:data-resource (name resource)
                                     :aria-label (str owner ": " n " " (name resource) " in round " round)
                                     :title (str owner " · " sources " · " n " " (name resource))}
              (if (= resource :field)
                [:svg.piece {:viewBox "0 0 24 24" :aria-label "Field" :role "img"}
                 [:rect {:x 1 :y 1 :width 22 :height 22 :rx 2 :fill "#b78246"}]
                 [:path {:d "M5 3v18 M10 3v18 M15 3v18 M20 3v18" :stroke "#79502e" :stroke-width 2}]]
                [art/piece resource])
              [:b n]])]])]])])
(defn board-position [g id]
  (let [round-index (.indexOf (clj->js (vec (drop (count (a/base-actions (count (:players g)) (:variant g))) (:active g)))) (name id))
        [x y w h] (or (board-spaces id) (let [[x y] (nth round-spaces round-index)] [x y 147 146]))]
    {:left (str (/ x 10) "%") :top (str (/ y 8) "%") :width (str (/ w 10) "%") :height (str (/ h 8) "%")}))
(defn board-action [g id can-play? draft busy? round & [extension?]]
  (let [action (a/action-definition g id) placement (get-in g [:occupied id]) owners (if (vector? placement) placement (when (some? placement) [placement])) owner (first owners) occupied? (some? owner)
                              share? (and (= :cards (:variant g)) (cards/has? (get-in g [:players (:turn g)]) :a026) (#{:growth :urgent-growth :modest-growth} id) (= 1 (count owners)) (not= owner (:turn g)))
                              gain (if (:accumulate action) (get-in g [:piles id]) (:gain action))]
      [:button.action-card {:class (str (name (:group action)) (when round " round-action") (when extension? " extension-action") (when occupied? " occupied") (when (= id (:space draft)) " chosen"))
                            :style (when-not (or round extension?) (board-position g id)) :disabled (or (not (a/available-space? g id)) (and occupied? (not share?)) (not can-play?) busy?) :on-click #(dispatch :select-action id)
                            :title (or (:description action) (:name action))}
       (when round [art/round-card-art :front (art/round-card-indices id)])
       [:span.action-top [:span.action-category (name (:group action))]
        (when round [:span.new-tag (str "Round " round)])]

       [:strong (:name action)]
       (when (a/blocked? g id) [:span.linked-status "Linked space used"])
       (when (and (= id :modest-growth) (< (:round g) 5)) [:span.linked-status "Opens in round 5"])
       (if gain [:div.board-piles (for [[resource n] gain :when (pos? n)] ^{:key resource} [:span {:data-piece-key (str "pile:" id ":" resource) :data-piece-type (name resource) :data-piece-count n} [art/pile resource n]])]
           [:span.action-description (:description action)])
       (when (:bonus action) [:small.accumulation "+1 reed from supply"])
       (when (:accumulate action) [:small.accumulation (str "+" (str/join ", " (map (fn [[r n]] (str n " " (str/lower-case (labels r)))) (:gain action))) " each round")])
       (for [[i pid] (map-indexed vector owners)] ^{:key (str "worker-" pid)}
         [:img.placed-worker {:data-piece-key (str "worker:" id ":" pid) :data-piece-type (str "worker:" pid) :src (art/worker-url pid)
                              :style (when (pos? i) {:margin-right (str (* i 22) "px")}) :alt (str (get-in g [:players pid :name]) "’s family member")}])
       (when (and occupied? (#{:growth :urgent-growth :modest-growth} id)) [:img.placed-worker.newborn-worker {:data-piece-key (str "newborn:" id) :data-piece-type (str "worker:" owner) :src (art/worker-url owner) :alt "Newborn family member"}])
       (when occupied? [:span.occupant {:style {:background (colors owner)}} (str (str/join " & " (map #(get-in g [:players % :name]) owners)) " · placed")])]))

(defn round-card [g round id can-play? draft busy?]
  [:div.round-card-slot {:style (round-space-position round) :data-round round
                         :data-revealed (boolean id)
                         :aria-label (str "Round " round (when-not id " · unrevealed"))}
   [:div.round-card-flipper {:class (when id "revealed")}
    [:div.round-card-back {:aria-hidden (boolean id)}
     [art/round-card-art :back (dec round)]
     [:span.round-card-number (str "Round " round)]]
    [:div.round-card-front
     (when id [board-action g id can-play? draft busy? round])]]])

(def five-extension-spaces #{:copse :grove :hollow :market :traveling :riverbank :house-building :modest-growth :animal-market :lessons-copse :lessons-growth})
(defn five-extension [g can-play? draft busy?]
  [:section.five-extension {:aria-label "Five-player action spaces"}
   [:h3 "Five-player village"]
   [:p.small "Linked spaces share one placement per round. Goods remain on a blocked accumulation space."]
   [:div.extension-spaces
    (for [[i pair] (map-indexed vector [[:lessons-copse :copse] [:lessons-growth :modest-growth] [:house-building :traveling]
                                       [:riverbank] [:grove] [:hollow] [:market] [:animal-market]])] ^{:key i}
      [:div {:class (if (> (count pair) 1) "linked-pair" "extension-single")}
       (when (> (count pair) 1) [:small "Linked · choose one"])
       (for [id pair :when (some #{id} (:active g))] ^{:key id} [board-action g id can-play? draft busy? nil true])])]])
(defn card-action-spaces [g can-play? busy?]
  (let [pid (:turn g) spaces (card-game/card-spaces g pid)]
    (when (seq spaces)
      [:section.card-action-spaces [:h3 "Card action spaces"]
       (for [{:keys [card owner kind]} spaces] ^{:key card}
         [:div [:strong (:name (cards/card card))] [:span.small (str " · " (get-in g [:players owner :name]))]
          (for [[action label] (case kind
                               :greenhouse (for [id [:fences :renovate :vegetable] :when (some #{id} (:active g))] [id (:name (a/action-definition g id))])
                               :fold-builder [[:fences "Fence, then take a sheep"]]
                               :substitute-teacher [[:market "Resources or crops"]]
                               :bullcatcher [[:labor "Cattle and food"]])]
            ^{:key action} [button label #(do (dispatch :select-action action) (draft! :card-space card)) "button secondary"
                            (or (not can-play?) busy? (boolean (card-game/card-space-error g pid card)))])
          (when-let [worker (get-in g [:occupied card])]
            [:img.card-action-worker {:src (art/worker-url worker) :alt (str (get-in g [:players worker :name]) "’s family member")
                                      :data-piece-key (str "worker:" card ":" worker) :data-piece-type (str "worker:" worker)}])
          (when-let [reason (card-game/card-space-error g pid card)] [:p.small reason])])])))
(defn action-board [g can-play? draft busy?]
  (let [base (a/base-actions (count (:players g)) (:variant g))
        revealed (vec (drop (count base) (:active g)))]
    [:section.action-board
     [:div.section-heading [:div [:div.eyebrow "THE VILLAGE"] [:h2 "Make your move."]] [:span.small "One person. One action."]]
     [:div.board-scroll {:role "region" :aria-label "Agricola board; scroll horizontally on small screens" :tab-index 0}
      [:div.action-grid.physical-board
       [:img.board-art {:src (art/url "board.png") :alt "Original Agricola Revised Edition board, Korean printing; English actions overlaid" :draggable false}]
       (for [id [:copse :grove :market :hollow :traveling] :when (or (= 5 (count (:players g))) (not (some #{id} (:active g))))] ^{:key (str "closed-" id)}
         [:div.closed-board-space {:style (board-position g id)} [:span (if (= 5 (count (:players g))) "Use five-player village below" "Additional players")]])
       [:div.closed-board-space {:style {:left "0.6%" :top "52%" :width "14.5%" :height "13%"}}
        [:span (if (= :cards (:variant g)) "Additional players" "Beginner game") [:br] (when-not (= :cards (:variant g)) "No occupations")]]
       (for [id (take (count base) (:active g)) :when (not (and (= 5 (count (:players g))) (five-extension-spaces id)))] ^{:key id}
         [board-action g id can-play? draft busy? nil])
       ;; Stable slots let a newly revealed public action animate in place.
       ;; Future identities are neither requested nor rendered in the browser.
       (for [round (range 1 15)] ^{:key round}
         [round-card g round (get revealed (dec round)) can-play? draft busy?])
       [future-round-markers g]]]
     (when (= 5 (count (:players g))) [five-extension g can-play? draft busy?])
     [card-action-spaces g can-play? busy?]]))
(defn improvement-select [g p draft]
  [:div
   [field "Major improvement"
    [:select {:value (or (some-> (:improvement draft) name) "") :on-change #(draft! :improvement (when (seq (.. % -target -value)) (keyword (.. % -target -value))))}
     [:option {:value ""} "Choose an improvement…"]
     (for [id (sort (:available-improvements g)) :let [card (a/improvements id)]] ^{:key id}
       [:option {:value (name id)} (str (:name card) " · " (str/join ", " (for [[r n] (:cost card)] (str n " " (name r)))) " · " (:points card) " VP")])]]
   (when (:upgrade (a/improvements (:improvement draft)))
     [field "Payment" [:select {:value (or (some-> (:upgrade draft) name) "") :on-change #(draft! :upgrade (when (seq (.. % -target -value)) (keyword (.. % -target -value))))}
                       [:option {:value ""} "Pay clay"]
                       (for [id [:fireplace-2 :fireplace-3] :when ((:improvements p) id)] ^{:key id} [:option {:value (name id)} "Return a Fireplace"])]] )])
(defn with-private-hand [g pid ui]
  (assoc-in g [:players pid :hand] (get-in ui [:private-hands (:game-id ui) pid])))
(defn action-command [draft]
  (cond-> (merge {:type :action :choice (if (= :animal-market (:space draft)) :sheep :reed)} (dissoc draft :tool))
    (:card-space draft) (assoc :type :card-action :action (:space draft) :bundle (or (:bundle draft) :resources))))
(defn card-action-error [g pid draft ui]
  ;; Validate the action without advancing the round: future round cards stay
  ;; server-private, and no effects are applied to the browser's replica.
  (try
    (let [g (with-private-hand g pid ui)]
      (cond
        (= :sidekick (get-in g [:effect-queue 0 :effect :op])) (card-game/resolve-choice g pid {:decision (:decision-id g) :action-command (action-command draft)})
        (= :young-artist (get-in g [:effect-queue 0 :effect :op])) (card-game/resolve-choice g pid {:decision (:decision-id g) :action :play :card (:card draft)})
        (= :scholar (get-in g [:effect-queue 0 :effect :op]))
        (card-game/resolve-choice g pid (assoc draft :decision (:decision-id g)))
        :else ((if (:card-space draft) card-game/card-action card-game/take-action) g pid (action-command draft))))
    nil
    (catch :default error (.-message error))))
(defn hand-select [g p kind draft & [optional?]]
  (let [pid (:id p) ui @(rf/subscribe [:ui]) hand (get-in ui [:private-hands (:game-id ui) pid kind])]
    [:div [field (if (= kind :occupation) "Occupation" "Minor improvement")
     [:select {:aria-label (if (= kind :occupation) "Occupation" "Minor improvement")
               :value (or (some-> (:card draft) str) "")
               :on-change #(draft! :card (let [value (.. % -target -value)] (when (seq value) (keyword (subs value 1)))))}
      [:option {:value ""} (if optional? "None / use a major improvement" "Choose a card…")]
      (for [id hand :let [reason (card-action-error g pid (-> draft (assoc :card id) (dissoc :improvement :upgrade :bake-grain :payment-option)) ui)]] ^{:key id}
        [:option {:value (str id) :disabled (boolean reason)}
         (str (:name (cards/card id)) (when reason (str " — " reason)))])]]
     (when-let [costs (:cost-options (cards/card (:card draft)))]
       [field "Card payment"
        [:select {:aria-label "Card payment" :value (if (some? (:payment-option draft)) (:payment-option draft) "")
                  :on-change #(draft! :payment-option (when (seq (.. % -target -value)) (js/parseInt (.. % -target -value))))}
         [:option {:value ""} "First affordable payment"]
         (for [[index cost] (map-indexed vector costs)] ^{:key index}
           [:option {:value index} (str/join ", " (for [[resource n] cost] (str n " " (labels resource))))])]])]))
(defn payment-options [p draft]
  [:div
   (when (cards/has? p :a123) [:label.checkbox [:input {:type "checkbox" :checked (boolean (:frame? draft)) :on-change #(draft! :frame? (.. % -target -checked))}] "Frame Builder: replace 2 clay/stone with 1 wood"])
   (when (cards/has? p :b145) [:label.checkbox [:input {:type "checkbox" :checked (boolean (:brushwood? draft)) :on-change #(draft! :brushwood? (.. % -target -checked))}] "Brushwood Collector: replace reed with 1 wood"])
   (when (and (cards/has? p :a087) (= :wood (:house p)))
     [field "Renovate to" [:select {:value (name (or (:renovate-to draft) :clay)) :on-change #(draft! :renovate-to (keyword (.. % -target -value)))} [:option {:value "clay"} "Clay"] [:option {:value "stone"} "Stone (Conservator)"]]])])
(defn action-editor [g p draft disabled? & [submit]]
  (let [space (:space draft) action (a/action-definition g space) bake? (or (#{:sow-bake :side-job} space) (:oven (a/improvements (:improvement draft))))
        ui @(rf/subscribe [:ui]) reason (when (= :cards (:variant g)) (card-action-error g (:id p) draft ui))]
    [:div.action-editor
     [:div.eyebrow "PLAN YOUR ACTION"] [:h3 (or (:name (cards/card (:card-space draft))) (:name action))] [:p.small (or (:description action) "Collect the goods and place one family member here.")]
     (when (#{:build :house-building :side-job :plow :cultivation :sow-bake :fences :redevelop} space)
       [:div
        (when (#{:build :cultivation :sow-bake} space)
          [:div.tool-options
           (for [[tool title] (case space :build [[:room "Rooms"] [:stable "Stables"]] :cultivation [[:field "Plow"] [:grain "Sow grain"] [:vegetable "Sow veg"]] [[:grain "Sow grain"] [:vegetable "Sow veg"]])]
             ^{:key tool} [button title #(draft! :tool tool) (str "tool " (when (= tool (or (:tool draft) (case space (:build :house-building) :room :cultivation :field :grain))) "selected"))])])
        [:p.hint (if (#{:fences :redevelop} space) "Click the edges of your farm to enclose pastures." "Click spaces on your farm below to plan this action.")]
        (when (seq (:rooms draft)) [:p.small (str "Rooms: " (str/join ", " (map inc (:rooms draft))))])
        (when (seq (:stables draft)) [:p.small (str "Stables: " (str/join ", " (map inc (:stables draft))))])
        (when (some? (:cell draft)) [:p.small (str "Plow space " (inc (:cell draft)))])
        (when (seq (:fences draft)) [:p.small (str (count (:fences draft)) " fences · " (count (:fences draft)) " wood")])
        (when (seq (:plantings draft)) [:p.small (str "Sowing " (count (:plantings draft)) " fields")])])
     (when (and (#{:sow-bake :cultivation} space) (seq (:card-fields p)))
       [:div [:p.small "Fields on cards"]
        (for [[cid field] (:card-fields p) :when (zero? (or (:count field) 0))] ^{:key cid}
          [:label.checkbox
           [:input {:type "checkbox" :checked (boolean (some #(= cid (:cell %)) (:plantings draft)))
                    :on-change #(let [rest (vec (remove (fn [planting] (= cid (:cell planting))) (:plantings draft)))]
                                  (draft! :plantings (if (.. % -target -checked) (conj rest {:cell cid :crop (:allowed-crop field)}) rest)))}]
           (str "Sow " (:name (cards/card cid)) " with " (name (:allowed-crop field)))])])
     (when (and (= :cards (:variant g)) (#{:lessons :lessons-extra :lessons-copse :lessons-growth :meeting :growth :improvement :renovate} space))
       [hand-select g p (if (#{:lessons :lessons-extra :lessons-copse :lessons-growth} space) :occupation :minor) draft (not (#{:lessons :lessons-extra :lessons-copse :lessons-growth} space))])
     (when (and (= :cards (:variant g)) (#{:lessons :lessons-extra :lessons-copse :lessons-growth} space) (cards/has? p :b109))
       [:label.checkbox [:input {:type "checkbox" :checked (boolean (:paper-maker? draft)) :on-change #(draft! :paper-maker? (.. % -target -checked))}] "Use Paper Maker before paying the occupation cost"])
     (when (and (cards/has? p :b024) (not (:lasso g)))
       [:label.checkbox [:input {:type "checkbox" :checked (boolean (:lasso? draft)) :on-change #(draft! :lasso? (.. % -target -checked))}] "Lasso: take a second action immediately (one action must be an animal market)"])
     (when (#{:build :house-building :renovate :redevelop} space) [payment-options p draft])
     (when (and (cards/has? p :a016) (#{:fences :redevelop} space))
       [field "Fence material" [:select {:value (name (or (:fence-material draft) :wood)) :on-change #(draft! :fence-material (keyword (.. % -target -value)))} [:option {:value "wood"} "Wood"] [:option {:value "clay"} "Clay (Rammed Clay)"]]])
     (when (#{:renovate :redevelop} space) [:p.hint (str "Renovation: " (count (a/tiles p :room)) " " (if (= :wood (:house p)) "clay" "stone") " + 1 reed")])
     (when (and (#{:improvement :renovate} space) (not (:card draft))) [improvement-select g p draft])
     (when (and (= space :market) (= 3 (count (:players g))))
       [field "Choose a resource" [:select {:value (name (or (:choice draft) :reed)) :on-change #(draft! :choice (keyword (.. % -target -value)))} [:option {:value "reed"} "Reed + food"] [:option {:value "stone"} "Stone + food"]]])
     (when (= space :animal-market)
       [field "Animal" [:select {:value (name (or (:choice draft) :sheep)) :on-change #(draft! :choice (keyword (.. % -target -value)))}
                         [:option {:value "sheep"} "Sheep + 1 food"] [:option {:value "boar"} "Wild boar"] [:option {:value "cattle"} "Cattle (pay 1 food)"]]])
     (when (= :substitute-teacher (:action-space (cards/card (:card-space draft))))
       [field "Bundle" [:select {:value (name (or (:bundle draft) :resources)) :on-change #(draft! :bundle (keyword (.. % -target -value)))}
                         [:option {:value "resources"} "1 wood, clay, reed, and stone"] [:option {:value "crops"} "1 grain and vegetable"]]])
     (for [[code key label eligible?] [[:a174 :master-hora? "Master Hora: pay 1 food for a vegetable before placing" (five/linked-groups space)]
                                      [:a180 :animal-brander? "Animal Brander: pay 1 food to take the same option twice" (= space :animal-market)]
                                      [:d180 :part-time? "Part-Time Worker: leave half the pile for an animal" (:accumulate action)]]
           :when (and eligible? (cards/has? p code))] ^{:key key}
       [:label.checkbox [:input {:type "checkbox" :checked (boolean (get draft key)) :on-change #(draft! key (.. % -target -checked))}] label])
     (when bake? [field "Grain to bake" [number-input (:bake-grain draft) #(draft! :bake-grain %) (a/amount p :grain)]])
     (when reason [:p.card-unavailable {:role "status"} reason])
     [:div.form-row [button "Place family member →" #(if submit (submit (action-command draft)) (dispatch :command (action-command draft))) "button primary" (or disabled? (boolean reason))]
      [button "Cancel" #(dispatch :select-action nil) "text-button"]]]))

(defn conversion-controls [p draft key]
  [:div.conversion-grid
   (for [resource (concat [:grain :vegetable] a/animals) :let [n (a/amount p resource) rate ((a/cook-rates p) resource)] :when (and (pos? n) rate)] ^{:key resource}
     [field (str (labels resource) " → " rate " food each")
      [number-input (get-in draft [key resource] 0) #(draft! key (assoc (get draft key {}) resource %)) n]])])
(defn harvest-panel [g p draft busy?]
  (case (:phase g)
    :feeding
    (let [convert (get draft :convert {}) rates (a/cook-rates p)
          crafted (reduce + 0 (map #(:rate (a/improvements %)) (:craft draft)))
          food (+ (a/amount p :food) crafted (reduce + 0 (for [[resource n] convert] (* n (get rates resource 0)))))
          needed (a/food-needed g p) missing (max 0 (- needed food))]
      [:div.harvest-panel [:div.eyebrow "HARVEST · FEEDING"] [:h3 "Feed your family"]
       [:p (str (:name p) " needs " needed " food. You have " (a/amount p :food) ".")]
       [:p.small "Your fields have been harvested. Convert crops or cook animals to cover the difference. Baking bread needs a work action."]
       [conversion-controls p draft :convert]
       (for [id (:improvements p) :when (:craft (a/improvements id))] ^{:key id}
         [:label.checkbox [:input {:type "checkbox" :checked (boolean (some #{id} (:craft draft))) :on-change #(draft! :craft (toggle-value (:craft draft) id))}]
          (str "Use " (:name (a/improvements id)) " · 1 " (name (:craft (a/improvements id))) " → " (:rate (a/improvements id)) " food")])
       [:div.feeding-total [:strong (str food " / " needed " food")]
        [:span (if (pos? missing) (str missing " begging marks (−" (* 3 missing) " points)") "Everyone will be fed")]]
       [button (if (pos? missing) (str "Feed & take " missing " begging marks") "Feed family →") #(dispatch :command {:type :feed :convert convert :craft (or (:craft draft) [])}) "button primary" busy?]])
    :breeding
    (let [options (a/breeding-options p) default (if (a/animal-housing (a/gain p options)) options {})
          offspring (get draft :offspring default)]
      [:div.harvest-panel [:div.eyebrow "HARVEST · BREEDING"] [:h3 "Breed animals"]
       [:p.small "Each pair produces one offspring if you can house it. Animals cannot be cooked during breeding."]
       (if (seq options)
         (for [[animal n] options] ^{:key animal}
           [field (str "New " (str/lower-case (labels animal))) [number-input (get offspring animal 0) #(draft! :offspring (assoc offspring animal %)) n]])
         [:p "No pairs to breed this harvest."])
       [button "Complete breeding →" #(dispatch :command {:type :breed :offspring offspring}) "button primary" busy?]])
    :housing
    (let [keep (get draft :keep (select-keys (:goods p) a/animals)) cook? (get draft :cook? (boolean (some (:improvements p) [:fireplace-2 :fireplace-3 :hearth-4 :hearth-5])))]
      [:div.harvest-panel [:div.eyebrow "ANIMAL HUSBANDRY"] [:h3 "House your animals"]
       [:p.small "Keep the animals you can house. Each pasture holds one species; your home holds one pet. Surplus animals must be cooked or released."]
       (for [animal a/animals] ^{:key animal}
         [field (str (labels animal) " to keep · " (a/amount p animal) " available") [number-input (keep animal) #(draft! :keep (assoc keep animal %)) (a/amount p animal)]])
       [:label.checkbox [:input {:type "checkbox" :checked cook? :on-change #(draft! :cook? (.. % -target -checked))}] "Cook surplus animals (requires a cooking improvement)"]
       [button "Settle animals →" #(dispatch :command {:type :house-animals :keep keep :cook? cook?}) "button primary" busy?]])
    nil))
(defn card-decision [g p draft busy?]
  (let [head (first (:effect-queue g)) effect (:effect head) op (:op effect) options (card-game/order-options g)
        keep (get draft :keep (select-keys (:goods p) a/animals))
        choice (merge (select-keys draft [:cell :frame? :brushwood? :renovate-to :paper-maker? :card :cook? :option :payment-option])
                      (when (contains? draft :effect-fields) {:fields (:effect-fields draft)})
                      {:type :resolve :decision (:decision-id g) :keep keep :grain (get draft :effect-grain 1)
                       :action (or (:effect-action draft) (if (= op :young-artist) :draw :room))
                       :resource (or (:effect-resource draft) (first (or (:choices effect) (if (= op :boat-painter) [:grain :food] [:grain :vegetable]))))
                       :plantings (when (and (= op :countryman) (some? (:cell draft))) [{:cell (:cell draft) :crop (or (:effect-resource draft) :grain)}])})
        ui @(rf/subscribe [:ui])
        reason (try (card-game/resolve-choice (with-private-hand g (:id p) ui) (:id p) choice) nil
                    (catch :default error (.-message error)))]
    [:div.card-decision
     [:div.eyebrow "RESOLVE A CARD EFFECT"] [:h3 (if (= op :ability-window) "After the harvest" (or (:name (cards/card (:card head))) "Make room for your animals"))]
     (when (= op :ability-window) [:p.small "You may use your card abilities before continuing to the next round or final scoring."])
     (when (= op :choose)
       [field "Card effect" [:select {:value (if (some? (:option draft)) (:option draft) "")
                                      :on-change #(draft! :option (js/parseInt (.. % -target -value)))}
                             [:option {:value ""} "Choose an effect…"]
                             (for [[index option] (map-indexed vector (:options effect))] ^{:key index}
                               [:option {:value index} (:label option)])]])
     (when (#{:preparation :work :return-home :field-harvest :feeding :finish-round} (:phase g))
       [:details [:summary "Convert goods to food"] [conversion-controls p draft :convert]
        [button "Convert to food" #(dispatch :command {:type :convert :goods (or (:convert draft) {})}) "button secondary" busy?]])
     (when (:card head) [:p.small (:text (cards/card (:card head)))])
     (when (> (count options) 1)
       [field "Resolve effects in your chosen order"
        [:select {:value (str (:card head)) :on-change #(dispatch :command {:type :order-effect :card (keyword (subs (.. % -target -value) 1))})}
         (for [id options] ^{:key id} [:option {:value (str id)} (:name (cards/card id))])]])
     (when (#{:choose-good :seasonal-crop :boat-painter :countryman} op)
       [field "Resource" [:select {:value (name (or (:effect-resource draft) (first (or (:choices effect) (if (= op :boat-painter) [:grain :food] [:grain :vegetable])))))
                                   :on-change #(draft! :effect-resource (keyword (.. % -target -value)))}
                          (for [r (or (:choices effect) (if (= op :boat-painter) [:grain :food] [:grain :vegetable]))] ^{:key r} [:option {:value (name r)} (labels r)])]])
     (when (= op :cottage)
       [field "Cottager action" [:select {:value (name (or (:effect-action draft) :room)) :on-change #(draft! :effect-action (keyword (.. % -target -value)))} [:option {:value "room"} "Build one room"] [:option {:value "renovate"} "Renovate"]]])
     (when (or (#{:plow :mini-pasture :stable :corral-builder :amateur-fencer :plowsmith} op) (and (= op :cottage) (not= :renovate (:effect-action draft))))
       [field "Farm space" [:select {:aria-label "Farm space" :value (if (some? (:cell draft)) (:cell draft) "") :on-change #(draft! :cell (js/parseInt (.. % -target -value)))}
                            [:option {:value ""} "Choose a space…"]
                            (for [i (range 15) :when (= :empty (get-in p [:farm i :type]))] ^{:key i} [:option {:value i} (str "Space " (inc i))])]])
     (when (= op :bake) [field "Grain to bake" [number-input (get draft :effect-grain 1) #(draft! :effect-grain %) (a/amount p :grain)]])
     (when (= op :extra-grain-harvest)
       (let [eligible (vec (for [i (concat (range 15) (keys (:card-fields p)))
                                :let [field (get-in p (a/field-path i))]
                                :when (and (= :grain (:crop field)) (pos? (get field :count 0)))] i))
             selected (get draft :effect-fields eligible)]
         [:div [:p.small "Choose fields to harvest one additional grain from."]
          (for [i eligible] ^{:key i}
            [:label.checkbox [:input {:type "checkbox" :checked (boolean (some #{i} selected))
                                       :on-change #(draft! :effect-fields (toggle-value selected i))}]
             (if (integer? i) (str "Field " (inc i)) (:name (cards/card i)))])]))
     (when (#{:cottage :fast-mason} op) [payment-options p draft])
     (when (= op :countryman)
       [field "Field to sow" [:select {:value (if (some? (:cell draft)) (str (:cell draft)) "")
                                     :on-change #(draft! :cell (when (seq (.. % -target -value)) (reader/read-string (.. % -target -value))))}
                              [:option {:value ""} "Choose an empty field…"]
                              (for [i (concat (a/tiles p :field) (keys (:card-fields p)))
                                    :when (zero? (get-in p (conj (a/field-path i) :count) 0))]
                                ^{:key i} [:option {:value (str i)} (if (integer? i) (str "Field " (inc i)) (:name (cards/card i)))])]])
     (when (= op :young-artist)
       [:div [field "Young Artist action" [:select {:value (name (or (:effect-action draft) :draw)) :on-change #(draft! :effect-action (keyword (.. % -target -value)))}
                                           [:option {:value "draw"} (str "Draw up to 2 minor improvements (" (:minor-deck-count g) " left)")]
                                           [:option {:value "play"} "Play a minor improvement"]]]
        (when (= :play (:effect-action draft)) [hand-select g p :minor draft])])
     (when (= op :sidekick)
       [action-editor g p (assoc draft :space (five/stage-left g (get-in head [:context :space]))) busy?
        #(dispatch :command {:type :resolve :decision (:decision-id g) :action-command %})])
     (when (= op :scholar)
       [:div [hand-select g p :occupation draft true] [hand-select g p :minor draft true]
        (when (cards/has? p :b109)
          [:label.checkbox [:input {:type "checkbox" :checked (boolean (:paper-maker? draft))
                                    :on-change #(draft! :paper-maker? (.. % -target -checked))}]
           "Use Paper Maker before paying the occupation cost"])])
     (when (= op :housing)
       [:div (for [animal a/animals] ^{:key animal}
               [field (str (labels animal) " to keep") [number-input (get keep animal 0) #(draft! :keep (assoc keep animal %)) (a/amount p animal)]])
        [:label.checkbox [:input {:type "checkbox" :checked (boolean (:cook? draft)) :on-change #(draft! :cook? (.. % -target -checked))}] "Cook surplus (requires a cooking improvement)"]])
     (when (and reason (not= op :sidekick)) [:p.card-unavailable {:role "status"} reason])
     [:div.form-row
      (when-not (= op :sidekick) [button (if (= op :ability-window) "Finish harvest decisions →" "Resolve effect →")
       #(dispatch :command choice) "button primary" (or busy? (boolean reason))])
      (when (:optional? effect) [button "Skip this effect" #(dispatch :command {:type :resolve :decision (:decision-id g) :skip? true}) "text-button" busy?])]]))

(defn card-ability [g p cid]
  (r/with-let [selection (r/atom {:amount 2 :resource :boar})]
    (let [card (cards/card cid) op (get-in card [:ability :op]) ui @(rf/subscribe [:ui])
          cmd (merge @selection {:type :ability :player (:id p) :card cid})
          reason (try (card-game/ability g (:id p) cmd) nil (catch :default error (.-message error)))
          field-ids (concat (a/tiles p :field) (keys (:card-fields p)))
          parse-id (fn [value] (if (str/starts-with? value ":") (keyword (subs value 1)) (js/parseInt value)))]
      [:details.card-ability [:summary (:name card)] [:p.small (:text card)]
       (case op
         :free-placement-fences [farm p {:space :fences :fences (:fences @selection)} true #(swap! selection assoc %1 %2)]
         :adopt [:p.small "Pay 1 food to let a newborn take an action this work phase. That child will need adult feeding."]
         :cash-card [:p.small (str "Collect " (get-in p [:card-state cid :food] 0) " food and turn this card face down.")]
         :reed-food [:p.small "Exchange 1 reed for 2 food."]
         :buy-stock [:p.small (str "Next good: " (or (some-> (get-in p [:card-state cid :stock]) peek labels) "sold out") " · 1 food")]
         :porcelain [field "Clay to exchange" [:select {:value (:amount @selection) :on-change #(swap! selection assoc :amount (js/parseInt (.. % -target -value)))}
                                               (for [n [2 3 4]] ^{:key n} [:option {:value n} (str n " clay → " (dec n) " stone")])]]
         :sheep-exchange [field "Exchange one sheep for" [:select {:value (name (:resource @selection)) :on-change #(swap! selection assoc :resource (keyword (.. % -target -value)))}
                                                                 (for [resource [:boar :vegetable :stone]] ^{:key resource} [:option {:value (name resource)} (labels resource)])]]
         :move-crop [:div (for [[key title] [[:from "Source field"] [:to "Destination field"]]] ^{:key key}
                           [field title [:select {:value (str (get @selection key "")) :on-change #(swap! selection assoc key (parse-id (.. % -target -value)))}
                                         [:option {:value ""} "Choose a field…"]
                                         (for [i field-ids] ^{:key i} [:option {:value (str i)} (if (integer? i) (str "Field " (inc i)) (:name (cards/card i)))])]])]
         nil)
       (when reason [:p.card-unavailable reason])
       [button "Use card ability" #(dispatch :command cmd) "button secondary"
        (or (:busy? ui) (not (:connected? ui)) (boolean reason))]])))
(defn card-abilities [g p]
  (let [permission @(rf/subscribe [:permission]) abilities (filter #(get-in (cards/card %) [:ability]) (cards/active-owned p))]
    (when (and (seq abilities) (some #{(:id p)} (:seats permission)) (#{:preparation :work :return-home :field-harvest :feeding :breeding :housing :finish-round} (:phase g)))
      [:div.panel [:div.eyebrow "CARD ABILITIES"]
       (for [cid abilities] ^{:key cid} [card-ability g p cid])])))

(defn player-panel [g p draft can-play? busy?]
  [:aside.player-panel
   [:div.panel.farm-panel
    [:div.section-heading [:div [:h2 (str (:name p) "'s farm")]]
     [:span.player-dot {:style {:background (colors (:id p))}}]]
    [:div.farm-info [:span (str (:family p) " family members")] [:span (str (count (a/tiles p :room)) " " (name (:house p)) " rooms")]]
    [farm p (if (= :sidekick (get-in g [:effect-queue 0 :effect :op]))
              (assoc draft :space (five/stage-left g (get-in g [:effect-queue 0 :context :space])))
              draft)
     (and can-play? (= (:id p) (card-game/acting-player g)) (= :work (:phase g)))]
    [:div.farm-stats [:span (str (count (:fences p)) "/15 fences")] [:span (str (count (filter :stable (:farm p))) "/4 stables")]
     [:span (str "Pet: " (or (some #(when (= :pet (:kind %)) (labels (:animal %))) (a/animal-housing p)) "none"))]]
    [:div.inventory (for [resource a/resources] ^{:key resource} [badge resource (a/amount p resource) (:id p)])]
    (when (pos? (:begging p)) [:p.warning (str (:begging p) " begging marks · −" (* 3 (:begging p)) " points")])
    ]
   [card-abilities g p]
   (when (and can-play? (= (:id p) (card-game/acting-player g)))
     [:div.panel
      (cond
        (seq (:effect-queue g)) [card-decision g p draft busy?]
        (#{:feeding :breeding :housing} (:phase g)) [harvest-panel g p draft busy?]
        (:space draft) [action-editor g p draft busy?]
        :else [:div [:div.eyebrow "YOUR TURN"] [:h3 "Choose an action"]
               [:p.muted "Select an available action on the board."]
               [:details [:summary "Convert goods to food"] [conversion-controls p draft :convert]
                [button "Convert to food" #(dispatch :command {:type :convert :goods (or (:convert draft) {})}) "button secondary" busy?]]])])])

(defn improvement-effects [card]
  [:div.improvement-effects
   [goods (:cost card)]
   (when (:cook card) [:p.small (str "Cook: " (str/join ", " (for [[resource rate] (:cook card)] (str (labels resource) " → " rate " food"))))])
   (when (:bake card) [:p.small (str "Bake: " (:bake card) " food per grain" (when (:limit card) (str " · max " (:limit card) " grain per action")) (when (:oven card) ". Bake immediately when built."))])
   (when (:craft card) [:p.small (str "Each harvest: 1 " (name (:craft card)) " → " (:rate card) " food. Bonus points for saved resources: " (str/join "/" (:bonus card)) ".")])
   (when (:upgrade card) [:p.small "Or return a Fireplace instead of paying clay."])
   (when (:description card) [:p.small (:description card)])])

(defn played-hand-stack [title ids p]
  (r/with-let [all? (r/atom false) exposed (r/atom nil)]
    [:div.played-hand-lane
     [:div.card-lane-heading [:strong title] [:span (count ids)]
      (when (seq ids) [button (if @all? "Stack" (str "Show all " (str/lower-case title))) #(swap! all? not) "text-button"])]
     (if (empty? ids) [:p.empty-lane (str "No " (str/lower-case title) " played yet.")]
       [:div.card-stack {:class (when @all? "expanded") :style (when-not @all? {:height (str (+ 244 (* 31 (dec (count ids)))) "px")})}
        (for [[i cid] (map-indexed vector ids) :let [card (cards/card cid) open? (= @exposed cid) field (get-in p [:card-fields cid])]] ^{:key cid}
          [:button.played-card {:class (str (when open? "exposed ") (when (get-in p [:card-state cid :face-down?]) "spent-card")) :data-face-down (boolean (get-in p [:card-state cid :face-down?])) :aria-label (:name card) :aria-pressed open?
                                :style (when-not @all? {:top (str (* i 31) "px") :left (if open? "min(162px, calc(100% - 150px))" (str (* i 2) "px")) :z-index (if open? 20 i)})
                                :on-click #(reset! exposed (when-not open? cid))}
           (if-let [url (card-art/url cid)] [:img {:src url :alt (:name card) :draggable false}]
             [:div.text-card [:h3 (:name card)] [:p (:text card)]])
           [:span.card-name (:name card) (when (get-in p [:card-state cid :face-down?]) " · used")]
           (when (= cid (cards/id :b169))
             [:div.card-crops (for [[animal n] (frequencies (map :animal (filter #(= :card (:kind %)) (a/animal-housing p))))] ^{:key animal}
                               [art/pile animal n])])
           (when-let [food (get-in p [:card-state cid :food])] [:span.card-food (str food " food saved")])
           (when (pos? (or (:count field) 0)) [:div.card-crops [art/pile (:crop field) (:count field)]])])])
     (when @exposed [:div.card-detail [:strong (:name (cards/card @exposed))] [:p.small (:text (cards/card @exposed))]])]))
(defn played-cards [p]
  (r/with-let [all? (r/atom false) exposed (r/atom nil)]
    (let [cards (vec (sort (:improvements p))) selected (when (some #{@exposed} cards) @exposed)]
      [:aside.played-cards {:aria-label "Played cards"}
       [:div.section-heading [:div [:div.eyebrow "ON YOUR FARM"] [:h3 "Played cards"]]
        (when (seq cards) [button (if @all? "Stack cards" "Show all") #(swap! all? not) "text-button"]) ]
       [:div.card-lane-heading [:strong "Major improvements"] [:span (count cards)]]
       (if (seq cards)
         [:div.card-stack {:class (str (when @all? "expanded ") (when selected "has-exposed"))
                           :style (when-not @all? {:height (str (+ 244 (* 31 (dec (count cards)))) "px")})}
          (for [[i id] (map-indexed vector cards) :let [card (a/improvements id) open? (= selected id)]] ^{:key id}
            [:button.played-card {:class (when open? "exposed") :aria-pressed open?
                                  :aria-label (str (:name card) " · " (str/join ", " (map (fn [[r n]] (str n " " (name r))) (:cost card))))
                                  :style (when-not @all? {:top (str (* i 31) "px") :left (if open? "min(162px, calc(100% - 150px))" (str (* i 2) "px")) :z-index (if open? 20 i)})
                                  :on-click #(reset! exposed (when-not open? id))}
             [:img {:src (art/card-url id) :alt (str (:name card) " — scanned Korean edition card") :draggable false}]
             [:span.card-name (:name card) [:small (str (:points card) " VP")]]])]
         [:p.empty-lane "Build a major improvement to add its card here."])
       (when selected
         [:div.card-detail [:strong (:name (a/improvements selected))]
          [improvement-effects (a/improvements selected)]
          [:p.small "Click this card again to return it to the stack."]])
       (if (contains? p :occupations)
         [:div [played-hand-stack "Minor improvements" (:minors p) p] [played-hand-stack "Occupations" (:occupations p) p]]
         [:div [:div.card-lane-heading [:strong "Occupations & minor improvements"] [:span "0"]]
          [:p.empty-lane "This table uses the beginner variant without hand cards."]])
       [:p.art-credit "Major scans: Korean · hand cards: English. " [:a {:href "/art/agricola/sources.html" :target "_blank" :rel "noreferrer"} "Artwork sources ↗"]]])))

(defn chat-panel [room]
  (let [ui @(rf/subscribe [:ui]) permission @(rf/subscribe [:permission])
        peers (:chat-peers ui) statuses (:chat-status ui)
        ready? (and (:connected? ui) (seq peers) (every? #(= :open (get statuses (:id %))) peers))
        messages (get-in ui [:chat-messages (:id room)] [])]
    [:aside.chat-panel {:aria-label "Table chat"}
     [:div.section-heading [:div [:h3 "Table chat"]] [:span.chat-dot {:class (when ready? "ready")}]]
     [:p.chat-privacy (if (comms/peer?) "Through the host · not saved" "Direct peer-to-peer · not saved")]
     [:div.chat-peers (for [peer peers] ^{:key (:id peer)}
                        [:span {:title (name (get statuses (:id peer) :connecting))}
                         [:i {:class (when (= :open (get statuses (:id peer))) "ready")}] (:name peer)])]
     (cond (not (seq (:seats permission))) [:p.small "Take a seat to join the conversation."]
           (not (seq peers)) [:p.small "Chat is ready when another seated player opens this table."]
           (not ready?) [:p.small "Connecting directly… If this takes too long, try reconnecting or a different network. "
                        [button "Reconnect" #(dispatch :chat/retry) "text-button"]])
     [:div.chat-messages {:ref #(when % (set! (.-scrollTop %) (.-scrollHeight %))) :role "log" :aria-live "polite" :aria-relevant "additions text" :tab-index 0}
      (for [message messages] ^{:key (:id message)}
        [:div.chat-message {:class (when (:own? message) "own")}
         [:div [:strong (:name message)] [:time (.toLocaleTimeString (js/Date. (:time message)) #js [] #js {:hour "2-digit" :minute "2-digit"})]]
         [:p (:text message)]])]
     (when (:chat-error ui) [:p.chat-error {:role "status"} (:chat-error ui)])
     [:form.chat-compose {:on-submit (fn [e] (.preventDefault e) (dispatch :chat/send))}
      [input (or (:chat-draft ui) "") #(dispatch :chat/draft %) {:aria-label "Chat message" :placeholder "Say something…" :max-length 1000 :disabled (not ready?) :auto-complete "off"}]
      [:button {:type "submit" :disabled (or (not ready?) (str/blank? (:chat-draft ui)))} "Send"]]
     [:span.small "Messages stay in this tab until you reload."]]))

(defn score-table [g]
  (let [scores (or (:scores g) (if (= :cards (:variant g)) (mapv #(card-game/score g (:id %)) (:players g)) (mapv a/score (:players g)))) winners (:winners g)]
    [:section.panel.score-panel
     [:div.eyebrow (if (= :finished (:phase g)) "THE FINAL HARVEST" "A LOOK AT YOUR FARM")]
     [:h2 (if (= :finished (:phase g)) (str (str/join " & " (map #(get-in g [:players % :name]) winners)) (if (= 1 (count (:players g))) " · your farm is complete" " wins!")) "Scoring overview")]
     [:p.muted "Crops in fields count. Building resources break ties; food does not score."]
     [:div.table-scroll [:table.score-table [:thead [:tr [:th "Category"] (for [p (:players g)] ^{:key (:id p)} [:th (:name p)])]]
      [:tbody (for [k [:fields :pastures :grain :vegetables :sheep :boar :cattle :unused :stables :rooms :family :improvements :bonus :begging]] ^{:key k}
                [:tr [:td (str/capitalize (name k))] (for [[i score] (map-indexed vector scores)] ^{:key i} [:td (get-in score [:breakdown k])])])]
      [:tfoot [:tr [:th "Total"] (for [[i score] (map-indexed vector scores)] ^{:key i} [:th (:total score)])]
       [:tr [:td "Building resources"] (for [[i score] (map-indexed vector scores)] ^{:key i} [:td (:tiebreak score)])]]]]]))
(defn improvement-library [g]
  [:section.panel [:div.eyebrow "TOOLS FOR A BETTER FARM"] [:h2 "Major improvements"]
   [:p.small "All 10 base-game major improvements are available here. Original Korean card scans accompany the English effects enforced by the game. In card mode, private occupations and minor improvements appear in your hand panel. Pack coverage is shown in table setup."]
   [:div.improvement-grid
    (for [[id card] (sort-by (comp :name val) a/improvements)] ^{:key id}
      [:div.improvement-card {:class (when-not ((:available-improvements g) id) "built")}
       [:img.improvement-art {:src (art/card-url id) :alt (str (:name card) " — original Korean edition card")} ]
       [:div.section-heading [:strong (:name card)] [:span (str (:points card) " VP")]]
       [improvement-effects card]
       [:span.small (if ((:available-improvements g) id) "Available" (str "Built by " (:name (first (filter #((:improvements %) id) (:players g))))))]])]])
(defn rules-panel [g]
  [:section.panel.rules-panel [:div.eyebrow "A LITTLE GUIDANCE"] [:h2 "Life on the farm"]
   [:div.rules-grid
    [:div [:h3 "Place your people"] [:p "Starting with the first player, take turns placing one family member on an empty action. Use at least one offered action. Newborns work from the next round. The first player stays the same until someone visits Meeting Place."]]
    [:div [:h3 "Build a home"] [:p "Rooms cost 5 of your house material and 2 reed each. Rooms must connect. Renovation costs 1 reed plus 1 clay or stone per room, upgrading wood to clay, then stone. Up to five family members can join your farm."]]
    [:div [:h3 "Cultivate the land"] [:p "Fields must touch your other fields. Sow one grain to make a stack of three, or one vegetable to make two. Harvest one crop per planted field at each harvest."]]
    [:div [:h3 "Care for animals"] [:p "A pasture holds two animals per space, all of one species. Each stable doubles its pasture’s capacity. An unfenced stable holds one animal; your house holds one pet. Build up to 15 fences and four stables."]]
    [:div [:h3 "Feed and breed"] [:p "Harvest after rounds 4, 7, 9, 11, 13, and 14. Adults need two food (three in solo); newborns need one. Each missing food costs a begging mark worth −3 points. After feeding, each pair breeds one animal if it fits."]]
    [:div [:h3 "Make it count"] [:p "Score fields, pastures, crops, animals, rooms, family, improvements and fenced stables. Empty land and missing categories lose points. The game ends after round 14’s harvest."]]]
   [:p.small (if (= :cards (:variant g))
               "This table uses occupations and minor improvements from the selected packs. Use Lessons for occupations and Meeting Place or an improvement action for minor improvements. Card prerequisites and costs are enforced. Effects trigger automatically; optional effects and choices wait for their owner. Cards can change the basic costs and rules above. Pack coverage is shown in table setup."
               "This table uses the official beginner variant without hand cards. Meeting Place accumulates one food each round. Side Job offers one stable for one wood and/or baking bread.")]
   [:p.small "Animal placement is automatically arranged to fit your herd. Drag panel titles to dock them and drag dividers to resize. Use Show all farms to see every player’s board, or Focus table for more room."]
   [:div.form-row [:a.button.secondary {:href (:rules (first catalog/games)) :target "_blank" :rel "noreferrer"} "Official rulebook ↗"]
    [:a.button.secondary {:href (:appendix (first catalog/games)) :target "_blank" :rel "noreferrer"} "Official appendix ↗"]]])
(declare hand-panel)
(defn game [room]
  (let [g (:state room) ui @(rf/subscribe [:ui]) permission @(rf/subscribe [:permission])
        actor (card-game/acting-player g)
        can-play? (and (:connected? ui) (some #{actor} (:seats permission)) (not= :finished (:phase g)) (not (seq (:takeback room))))
        selected (or (:inspect-player ui) (if can-play? actor (first (:seats permission))) actor)
        p (get-in g [:players selected]) panel (:panel ui)]
    [:main.game {:data-revision (:revision room) :data-motion-table (:id room)}
     [:div.game-heading [:div [button "← All tables" #(dispatch :navigate nil) "text-button"] [:h1 "Agricola"] [:p.muted (:title room)]]
      [:div.round-heading [:div.eyebrow (if (= :finished (:phase g)) "GAME COMPLETE" (str "ROUND " (:round g) " OF 14"))]
       [:h3 (case (:phase g) :work "Work the land" :feeding "Harvest · Feeding" :breeding "Harvest · Breeding" :housing "Animal husbandry" "A season well spent")]
       [:span.small (str "Next harvest: " (or (first (filter #(>= % (:round g)) [4 7 9 11 13 14])) 14))]]]
     [controls/turn-banner room]
     [round-track g]
     [:div.player-tabs
      (for [farmer (:players g) :let [id (:id farmer) current? (= id actor)]] ^{:key id}
        [:button.player-tab {:class (str (when (= selected id) "selected ") (when current? "current")) :on-click #(dispatch :inspect-player id)}
         [:span.avatar {:style {:background (colors id)}} (subs (:name farmer) 0 1)]
         [:span [:strong (:name farmer) (when (= id (:starting-player g)) " ♛")]
          [:small (str (:remaining farmer) " / " (:family farmer) " workers ready")]]
         (when current? [:span.turn-label (if can-play? "YOUR TURN" "PLAYING")])])]
     [:div.game-toolbar
      [:nav.tabs (for [[id label] [[:board "Village & farm"] [:improvements "Improvements"] [:scores "Scoring"] [:rules "How to play"]]] ^{:key id}
                   [button label #(dispatch :panel id) (str "tab " (when (= panel id) "active"))])]
      [recording-picker]
      (when (and (:host? permission) (or (= :local (:mode room)) (= :finished (:phase g))))
        [button "Save recording" #(dispatch :send {:event :recording/request}) "text-button" (:busy? ui)])
      [controls/takeback room]]
     (case panel
       :scores [score-table g]
       :rules [rules-panel g]
       :improvements [improvement-library g]
       [layout/table-layout (:id room)
        (vec (concat
              [{:id "board" :title "Shared board" :content [action-board g (and can-play? (= :work (:phase g)) (empty? (:effect-queue g))) (:draft ui) (:busy? ui)]}
               {:id "chat" :title "Table chat" :content [chat-panel room]}
               {:id "farm" :title "Selected player's farm" :content [:div [player-panel g p (:draft ui) can-play? (:busy? ui)]
                                                                       ^{:key [(:round g) (:id p)]} [controls/harvest-planner room (:id p)]]}
               {:id "cards" :title "Selected player's played cards" :content ^{:key (:id p)} [played-cards p]}
               {:id "log" :title "Game log" :content [:details.panel.activity [:summary "Game log"]
                                                     (for [[i entry] (map-indexed vector (reverse (take-last 30 (:log g))))] ^{:key i}
                                                       [:div.log-entry [:span (str "R" (:round entry))] [:p (:text entry)]])]}]
              (when (= :cards (:variant g)) [{:id "hand" :title "Your hand cards" :content [hand-panel room (:id p)]}])
              (for [farmer (:players g)]
                {:id (str "farm-" (:id farmer)) :title (str (:name farmer) "'s farm")
                 :content [:div [player-panel g farmer (:draft ui) can-play? (:busy? ui)]
                           ^{:key [(:round g) (:id farmer)]} [controls/harvest-planner room (:id farmer)]]})
              (for [farmer (:players g)]
                {:id (str "cards-" (:id farmer)) :title (str (:name farmer) "'s played cards") :content [played-cards farmer]})))
        (count (:players g))])]))
(defn hand-card [cid & [attrs]]
  (let [card (cards/card cid)]
    [:button.hand-card (merge {:type "button" :class (name (:kind card)) :aria-label (:name card)} attrs)
     (if-let [url (card-art/url cid)]
       [:img {:src url :alt (str (:name card) " · original card image") :loading "lazy" :draggable false}]
       [:div.text-card [:span.small (str/upper-case (name (:kind card)))] [:h3 (:name card)] [:p (:text card)]])
     [:strong (:name card)]
     [:span.small (str (if (= :occupation (:kind card)) "Occupation" "Minor improvement")
                       (when (seq (:cost-text card)) (str " · " (:cost-text card))))]
     [:p.card-text (:text card)]
     (when (seq (:prerequisites card)) [:span.small (str "Requires: " (:prerequisites card))])]))
(defn hand-panel [room pid]
  (r/with-let [kind (r/atom :occupation)]
    (let [ui @(rf/subscribe [:ui]) hand (get-in ui [:private-hands (:id room) pid])
          g (:state room) draft (:draft ui)
          choosing? (or (:space draft) (= :scholar (get-in g [:effect-queue 0 :effect :op])))
          active? (and (= pid (card-game/acting-player g)) choosing?)]
      [:section.panel.hand-panel
       [:div.eyebrow "PRIVATE HAND"] [:h3 (str (get-in room [:seats pid :name]) "’s cards")]
       (if-not hand [:p.small "Only this player can see their hand."]
         [:div
          [:div.segmented [button (str "Occupations · " (count (:occupation hand))) #(reset! kind :occupation) (when (= @kind :occupation) "selected")]
           [button (str "Minors · " (count (:minor hand))) #(reset! kind :minor) (when (= @kind :minor) "selected")]]
          [:p.small "Choose an appropriate action space, then select a card to play in the action panel."]
          [:div.hand-grid (for [cid (get hand @kind)
                               :let [reason (when active? (card-action-error g pid (-> draft (assoc :card cid) (dissoc :improvement :upgrade :bake-grain)) ui))]] ^{:key cid}
                            [:div.hand-choice
                             [hand-card cid {:on-click #(draft! :card cid) :disabled (or (not active?) (boolean reason))
                                             :title (or reason (when-not active? "Choose an action that allows playing this card."))
                                             :aria-pressed (= cid (:card draft))}]
                             (when reason [:p.card-unavailable reason])])]])])))
(defn draw-packet [room pid private]
  (r/with-let [revealed? (r/atom (= :online (:mode room))) selected (r/atom nil)]
    (let [ui @(rf/subscribe [:ui]) legal (set (:legal private)) card @selected
          counts (get-in room [:state :draw :counts pid]) choosing? (seq (:packet private))]
      [:section.draw-packet
       [:div.section-heading [:div [:div.eyebrow "YOUR PACKET"] [:h2 (str (get-in room [:seats pid :name]) "’s choice")]]
        [:span.small (if choosing? (str (count (:packet private)) " cards in this packet") "Waiting for the next packet")]]
       (if-not @revealed?
         [:div.panel.handoff [:h3 (str "Pass the screen to " (get-in room [:seats pid :name]))]
          [:p "The next packet and chosen cards stay hidden until you reveal them."]
          [button "Reveal packet" #(reset! revealed? true) "button primary"]]
         [:div.draw-content
          [:div.draw-choices
           (if choosing?
            [:div
             [:div.draw-confirm
           [:span (if card (str "Selected: " (:name (cards/card card))) "Choose one card, then confirm to pass left.")]
           [button "Keep card & pass left →"
            #(dispatch :send {:event :draw-pick :player pid :wave (get-in room [:state :draw :wave]) :card card})
            "button primary" (or (:busy? ui) (seq (:takeback room)) (not (legal card)))]]
          [:div.hand-grid
           (for [cid (:packet private)] ^{:key cid}
             [hand-card cid {:class (str (name (:kind (cards/card cid))) (when (= card cid) " selected"))
                             :aria-pressed (= card cid) :disabled (not (legal cid))
                             :title (if (legal cid) "Choose this card" "You already have seven cards of this type")
                             :on-click #(reset! selected cid)}])]]
            [:div.draw-waiting [:h2 "Your pick is in"]
             [:p "Your hand stays visible while the other players choose."]])]
          [:aside.draw-kept {:aria-label "Your drafted cards"}
           [:h3 "Your hand"]
           (for [[kind label] [[:occupation "Occupations"] [:minor "Minor improvements"]]] ^{:key kind}
             [:section [:h4 (str label " · " (get counts kind 0) "/7")]
              (if (seq (get-in private [:selected kind]))
                [:div.hand-grid (for [cid (get-in private [:selected kind])] ^{:key cid}
                                  [hand-card cid {:disabled true :aria-label (str "Drafted " (:name (cards/card cid)))}])]
                [:p.small "None selected yet."])])]])])) )
(defn draw-view [room]
  (let [ui @(rf/subscribe [:ui]) permission @(rf/subscribe [:permission]) state (get-in room [:state :draw])
        pending (set (:pending state)) owned (:seats permission)
        pid (or (first (filter pending owned)) (first owned))
        private (get-in ui [:private-draws (:id room) pid])]
    [:main.game.draw-view {:data-revision (:revision room)}
     [:div.game-heading [:div [button "← All tables" #(dispatch :navigate nil) "text-button"] [:h1 "The draw"] [:p.muted (:title room)]]
      [:div.round-heading [:div.eyebrow "BEFORE THE FIRST ROUND"] [:h3 (str "Pass " (:wave state))]
       [:p.small (str "14 + " (:extra state) " cards · one pick · pass left")]]]
     [controls/turn-banner room]
     [:div.draw-tools [controls/takeback room]]
     [:div.draw-progress
      (for [[i seat] (map-indexed vector (:seats room)) :let [counts (get (:counts state) i)]] ^{:key i}
        [:div.draw-seat {:class (when (= pid i) "selected")}
         [:strong (:name seat)] [:span (str (:occupation counts) "/7 occupations · " (:minor counts) "/7 minors")]
         [:small (cond (= :complete (get (:passes state) i)) "Hand complete · automatically passing"
                       (= :no-legal-card (get (:passes state) i)) "No legal pick · automatically passing"
                       (pending i) "Choosing a card" :else "Picked · waiting to pass")]])]
     [:div.tabletop-layout
      [:div
       (if (some? pid)
         ^{:key [(:wave state) pid]} [draw-packet room pid private]
         [:section.panel [:h2 "The players are drawing cards."]
          [:p "Packets pass once everyone who has a legal choice has picked. Completed hands and blocked packets pass automatically."]])
       [:p.small "Every player finishes with 7 occupations and 7 minor improvements. Remaining cards are discarded only after every hand is complete."]]
      [chat-panel room]]]))
(defn replay-view []
  (r/with-let [player (r/atom 0)
               timer (js/setInterval
                      #(when (:playing? @replay-data)
                         (swap! replay-data (fn [r]
                                             (let [index (min (count (:moves r)) (inc (:index r)))]
                                               (assoc r :index index :playing? (< index (count (:moves r)))))))) 650)]
    (let [{:keys [frames moves index file status playing?]} @replay-data
          g (nth frames index) p (get-in g [:players @player]) move (when (pos? index) (nth moves (dec index)))
          seek #(swap! replay-data assoc :index % :playing? false)]
      [:main.game.replay-view {:data-motion-table (str "replay:" file) :data-motion-revision index}
       [:div.section-heading [:div [:div.eyebrow "VERIFIED RECORDING · READ ONLY"] [:h1 file]]
        [button "Close playback" #(reset! replay-data nil)]]
       [:div.replay-controls
        [button "First" #(seek 0) nil (zero? index)]
        [button "Previous" #(seek (dec index)) nil (zero? index)]
        [button (if playing? "Pause" "Play") #(swap! replay-data update :playing? not) nil (= index (count moves))]
        [button "Next" #(seek (inc index)) nil (= index (count moves))]
        [button "Last" #(seek (count moves)) nil (= index (count moves))]
        [:input {:type "range" :min 0 :max (count moves) :value index :aria-label "Replay move"
                 :on-change #(seek (js/parseInt (.. % -target -value)))}]
        [:strong (str "Move " index " / " (count moves) " · Round " (:round g) " · " (name status))]]
       [:p.replay-command (if move (str (get-in g [:players (:player move) :name]) " · " (pr-str (:command move))) "Initial position")]
       [:div.replay-grid
        [:div.tabletop-main
         (if (= :draw (:phase g))
           [:section.panel [:h2 (str "Draft · wave " (get-in g [:draw :wave]))]
            (for [[pid selected] (map-indexed vector (get-in g [:draw :selected]))]
              ^{:key pid} [:p (str (get-in g [:players pid :name]) ": " (str/join ", " (map #(get-in cards/cards [% :name]) (mapcat val selected))))])]
           [action-board g false {} false])]
        [:div
         [field "Replay player" [:select {:value @player :on-change #(reset! player (js/parseInt (.. % -target -value)))}
                                 (for [p (:players g)] ^{:key (:id p)} [:option {:value (:id p)} (:name p)])]]
         [player-panel g p {} false false]
         [played-cards p]]]
       (when (= :finished (:phase g)) [score-table g])])
    (finally (js/clearInterval timer))))
(defn activity-lobby []
  (r/with-let [refresh (js/setInterval #(dispatch :activity/refresh) 5000)]
    (let [ui @(rf/subscribe [:ui])]
      [:main.library.activity-lobby
       [:div.section-heading
        [:div [:div.eyebrow "AGRICOLA"] [:h1 "Activity tables"] [:p "Join a table or start a new game with people in this Activity."]]
        [button "Create a new table" #(dispatch :activity/enter "create" nil) "button primary" (:busy? ui)]]
       [:div.section-heading [:h2 "Tables"]
        [button "Refresh tables" #(dispatch :activity/load) "text-button" (:busy? ui)]]
       (if (seq (:activity-tables ui))
         [:div.activity-table-list
          (for [table (:activity-tables ui)
                :let [unavailable? (and (:host? table) (:initialized table) (not (:saveAvailable? table)))]]
            ^{:key (:room table)}
            [:section.panel.activity-table
             [:div [:h3 (:title table)]
              [:p.small (str "Host: " (:hostName table) " · Created " (.toLocaleString (js/Date. (:created table))))]
              [:p (cond unavailable? "Host save is in another browser. You can create a new table here."
                        (:host? table) "Your table"
                        (:hostOnline? table) "Host connected"
                        :else "Waiting for the host to return")]]
             [button (if (:host? table) "Open table" "Join table")
              #(dispatch :activity/enter "join" (:room table)) "button" (or (:busy? ui) unavailable?)]])]
         [:section.panel [:p (if (:busy? ui) "Loading tables…" "No tables yet. Create one to get started.")]])])
    (finally (js/clearInterval refresh))))
(defn app []
  (let [ui @(rf/subscribe [:ui]) room @(rf/subscribe [:game])
        playing? (or @replay-data (and (:state room) (not= :draw (:status room))))
        focused? (and playing? (:focus? (layout/layout ui (:id room))))]
    [:div.app {:aria-busy (boolean (:busy? ui)) :class (str (when playing? "playing ") (when focused? "table-focused"))}
     [:header.site-header [:button.wordmark {:on-click #(dispatch :navigate nil)} "eurozone" [:span "✳"]]
      [:div.header-right [:span.connection {:class (when (:connected? ui) "online")} [:i] (if (:activity-lobby? ui) "Discord Activity" (or (:peer-status ui) (if (:connected? ui) "Connected" "Reconnecting…")))]]]
     (when (:error ui) [:div.toast {:role "alert"} [:span (:error ui)] [button "×" #(dispatch :dismiss-error) "text-button"]])
     (cond (:activity-lobby? ui) [activity-lobby]
           (:missing-host-save ui)
           [:main.panel.activity-recovery
            [:h1 "Host save unavailable"]
            [:p "Join another table or create a new one from the Activity lobby."]
            [button "Back to Activity tables" #(.EUROZONE_ACTIVITY_HOME js/window) "button primary"]]
           @replay-data [replay-view]
           room (cond (= :draw (:status room)) [draw-view room] (:state room) [game room] :else [lobby room])
           (and (:game-id ui) (:connected? ui)) [:main.panel.not-found [:h1 "Table not found"] [:p "This table may belong to another Eurozone server."] [button "Back to the library" #(dispatch :navigate nil) "button primary"]]
           (comms/peer?) [:main.panel [button "← All tables" #(dispatch :navigate nil) "text-button"] [:h1 "Connecting to your table"] [:p (or (:peer-status ui) "Waiting for the host…")]]
           :else [library])
     [:footer [:span.wordmark.small-mark "eurozone"] [:span "Agricola · an unofficial implementation"]]]))
