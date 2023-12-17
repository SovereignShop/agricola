(ns agricola.bits)

(defn make-bit
  ([name title]
   {:agricola.bit/title title
    :agricola.bit/name name})
  ([name title description]
   {:agricola.bit/title title
    :agricola.bit/name name
    :agricola.bit/description description}))

(defn make-occupation
  ([name title]
   (assoc (make-bit name title)
          :agricola.bit/type :agricola.type/occupation))
  ([name title description]
   (assoc (make-bit name title description)
          :agricola.bit/type :agricola.type/occupation)))

(defn make-square
  ([name title]
   (assoc (make-bit name title)
          :agricola.bit/type :agricola.type/square))
  ([name title description]
   (assoc (make-bit name title description)
          :agricola.bit/type :agricola.type/square)))

;; Squares
(def take-one-grain :agricola.square/take-one-grain)
(def take-one-building-resource :agricola.square/take-one-building-resource)
(def plow-one-field :agricola.square/plow-one-field)
(def play-one-occupation :agricola.square/play-occupation)
(def play-one-occupation-expensive :agricola.square/play-occupation-expensive)
(def take-two-wood :agricola.square/take-two-wood)
(def take-three-wood :agricola.square/take-three-wood)
(def take-one-clay-board-one :agricola.square/take-one-clay-board-one)
(def take-one-clay-board-two :agricola.square/take-one-clay-board-two)
(def take-one-reed :agricola.square/take-one-reed)
(def fishing :agricola.square/fishing)
(def build-rooms :agricola.square/build-rooms)
(def day-laborer :agricola.square/day-laborer)
(def take-sheep :agricola.square/take-sheep)
(def take-stone-round-2 :agricola.square/take-stone-round-2)
(def renovate :agricola.square/renovate)
(def family-growth :agricola.square/family-growth)
(def starting-player :agricola.square/starting-player)
(def build-fences :agricola.square/build-fences)
(def major-or-minor :agricola.square/major-or-minor)
(def sow-bake :agricola.square/sow-bake)
(def take-one-vegetable :agricola.square/take-one-vegetable)

;; Cards
(def grain-elevator :agricola.card/grain-elevator)
(def field-watchman :agricola.card/field-watchman)
(def family-counseler :agricola.card/family-counseler)

(def bits
 [(make-square take-one-grain "Take 1 Grain")
  (make-square take-one-building-resource "Take 1 Building Resource")
  (make-square plow-one-field "Plow 1 Field")
  (make-square take-two-wood "Take 2 Wood")
  (make-square take-three-wood "Take 3 Wood")
  (make-square take-one-vegetable "Take 1 Vegetable")

  (make-occupation grain-elevator "Grain Elevator")
  (make-occupation field-watchman "Field Watchman")
  (make-occupation family-counseler "Family Counseler")])
